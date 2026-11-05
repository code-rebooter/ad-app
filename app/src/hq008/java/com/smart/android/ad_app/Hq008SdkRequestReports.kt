package com.smart.android.ad_app

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.google.gson.Gson
import com.smart.android.ad_app.AdLocalLog as Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/** Save before scheduling; keep the original request/channel fields across retries and restarts. */
internal object Hq008SdkRequestReports {
    private const val PREFERENCES = "hq008_sdk_request_reports"
    internal const val REPORT_KEY = "report_key"

    private fun store(context: Context) = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    @Synchronized fun enqueue(params: Map<String, Any>) {
        val key = reportKey(params)
        val persisted = runCatching {
            check(store(appContext).edit().putString(key, Gson().toJson(params)).commit()) {
                "Unable to persist SDK request report"
            }
        }
        if (persisted.isFailure) {
            Log.e("Hq008SdkRequestReports", "请求结果本地保存失败，立即尝试上报：$key", persisted.exceptionOrNull())
            Hq008AdReporter.send(params)
            return
        }
        schedule(appContext, key)
    }

    internal fun reportKey(params: Map<String, Any>): String = "sdk-request-" + UUID.nameUUIDFromBytes(
        "${params.getValue("request_id")}:${params.getValue("event_type")}:${params.getValue("message")}".toByteArray(Charsets.UTF_8))

    fun resumePending() {
        store(appContext).all.keys.forEach { schedule(appContext, it) }
    }

    internal fun read(context: Context, key: String): String? = store(context).getString(key, null)

    @Synchronized internal fun acknowledge(context: Context, key: String, payload: String) {
        val preferences = store(context)
        if (preferences.getString(key, null) == payload) {
            check(preferences.edit().remove(key).commit()) { "Unable to acknowledge SDK request report" }
        }
    }

    internal suspend fun deliver(
        key: String,
        read: (String) -> String?,
        acknowledge: (String, String) -> Unit,
        send: suspend (String) -> Boolean
    ): Boolean {
        val payload = read(key) ?: return true
        if (!send(payload)) return false
        acknowledge(key, payload)
        return true
    }

    private fun schedule(context: Context, key: String) {
        // The key is small; the full payload lives outside WorkManager's 10 KiB Data limit.
        val work = OneTimeWorkRequestBuilder<Hq008SdkRequestReportWorker>()
            .setInputData(workDataOf(REPORT_KEY to key))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        runCatching {
            WorkManager.getInstance(context).enqueueUniqueWork(key, ExistingWorkPolicy.KEEP, work)
        }.onFailure {
            // The saved report will be scheduled again at the next TCL init/showAd.
            Log.e("Hq008SdkRequestReports", "请求结果已保存，等待重新调度：$key", it)
        }
    }
}

class Hq008SdkRequestReportWorker(context: Context, parameters: WorkerParameters) :
    CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val key = inputData.getString(Hq008SdkRequestReports.REPORT_KEY) ?: return Result.failure()
        return try {
            val acknowledged = Hq008SdkRequestReports.deliver(key,
                read = { Hq008SdkRequestReports.read(applicationContext, it) },
                acknowledge = { reportKey, payload ->
                    Hq008SdkRequestReports.acknowledge(applicationContext, reportKey, payload)
                }) { payload ->
                val json = JSONObject(payload)
                val params = json.keys().asSequence().associateWith { json.get(it) }
                withTimeoutOrNull(60_000L) {
                    withContext(Dispatchers.Main) {
                        suspendCancellableCoroutine<Boolean> { continuation ->
                            Hq008AdReporter.send(params) { success ->
                                if (continuation.isActive) continuation.resume(success)
                            }
                        }
                    }
                } == true
            }
            if (acknowledged) Result.success() else Result.retry()
        } catch (error: Exception) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            Log.e("Hq008SdkRequestReports", "请求结果上报失败，保留并重试：$key", error)
            Result.retry()
        }
    }
}
