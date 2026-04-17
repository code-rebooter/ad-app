package com.smart.android.ad_app

import android.os.Build
import android.provider.Settings
import android.util.Log
import com.smart.android.ad_app.bean.EmptyData
import com.google.gson.Gson
import io.github.lib_autorun.ext.getMacAddress
import io.github.lib_autorun.net.NetworkHelper
import io.github.lib_autorun.net.enum.RequestMethod
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.UUID

internal object Hq008AdReporter {
    private val REPORT_URL = "${BuildConfig.BASE_URL}api/v2/ad/report"
    private const val TAG = "Hq008AdReporter"
    private val gson = Gson()

    object EventType {
        const val AD_PROGRESS = "AD_PROGRESS"
        const val AD_COMPLETED = "AD_COMPLETED"
        const val AD_ERROR = "AD_ERROR"
    }

    object Message {
        const val REQUESTED = "REQUESTED"
        const val LOADED = "LOADED"
        const val STARTED = "STARTED"
        const val COMPLETED = "COMPLETED"
        const val CONTAINER_ERROR = "CONTAINER_ERROR"
    }

    fun newRequestId(): String {
        return "hq008-${System.currentTimeMillis()}-${UUID.randomUUID()}"
    }

    fun reportRequested(
        requestId: String,
        adId: String?,
        hiddenMode: Boolean,
        containerWidth: Int,
        containerHeight: Int,
        extra: Map<String, Any?> = emptyMap()
    ) {
        report(
            requestId = requestId,
            eventType = EventType.AD_PROGRESS,
            message = Message.REQUESTED,
            diagnosticInfo = buildDiagnosticInfo(
                adId = adId,
                hiddenMode = hiddenMode,
                extra = mapOf(
                    "containerWidth" to containerWidth,
                    "containerHeight" to containerHeight
                ) + extra
            )
        )
    }

    fun reportLoaded(
        requestId: String,
        adId: String?,
        hiddenMode: Boolean,
        extra: Map<String, Any?> = emptyMap()
    ) {
        report(
            requestId = requestId,
            eventType = EventType.AD_PROGRESS,
            message = Message.LOADED,
            diagnosticInfo = buildDiagnosticInfo(adId, hiddenMode, extra)
        )
    }

    fun reportStarted(
        requestId: String,
        adId: String?,
        hiddenMode: Boolean,
        startProgress: Double?,
        extra: Map<String, Any?> = emptyMap()
    ) {
        report(
            requestId = requestId,
            eventType = EventType.AD_PROGRESS,
            message = Message.STARTED,
            diagnosticInfo = buildDiagnosticInfo(
                adId = adId,
                hiddenMode = hiddenMode,
                extra = mapOf("startProgress" to startProgress) + extra
            )
        )
    }

    fun reportCompleted(
        requestId: String,
        adId: String?,
        hiddenMode: Boolean,
        extra: Map<String, Any?> = emptyMap()
    ) {
        report(
            requestId = requestId,
            eventType = EventType.AD_COMPLETED,
            message = Message.COMPLETED,
            diagnosticInfo = buildDiagnosticInfo(adId, hiddenMode, extra)
        )
    }

    fun reportError(
        requestId: String,
        adId: String?,
        hiddenMode: Boolean,
        errorCode: Int?,
        errorMessage: String,
        extra: Map<String, Any?> = emptyMap()
    ) {
        report(
            requestId = requestId,
            eventType = EventType.AD_ERROR,
            message = errorMessage,
            diagnosticInfo = buildDiagnosticInfo(
                adId = adId,
                hiddenMode = hiddenMode,
                extra = mapOf("errorCode" to errorCode) + extra
            )
        )
    }

    private fun report(
        requestId: String,
        eventType: String,
        message: String,
        diagnosticInfo: String
    ) {
        val params = linkedMapOf<String, Any>(
            "request_id" to requestId,
            "event_type" to eventType,
            "uuid" to resolveDeviceId(),
            "channel_id" to BuildConfig.CHANNEL,
            "mac" to (safeGetMacAddress()?.takeIf { it.isNotBlank() } ?: "00:00:00:00:00:00"),
            "app_id" to appContext.packageName,
            "make" to Build.MANUFACTURER.orEmpty(),
            "model" to Build.MODEL.orEmpty(),
            "message" to message,
            "diagnostic_info" to diagnosticInfo
        )

        resolveLocalIp()?.let { params["local_ip"] = it }
        val requestJson = gson.toJson(params)

        Log.i(
            TAG,
            "report requestId=$requestId eventType=$eventType message=$message body=$requestJson"
        )

        NetworkHelper.makeRequest<EmptyData>(
            url = REPORT_URL,
            method = RequestMethod.POST,
            params = params,
            isEncryted = false,
            useDomainSwitch = false,
        ) { _, error ->
            if (error != null) {
                Log.e(TAG, "report failed requestId=$requestId eventType=$eventType message=$message error=${error.message}", error)
            } else {
                Log.i(
                    TAG,
                    "report success requestId=$requestId eventType=$eventType message=$message"
                )
            }
        }
    }

    private fun buildDiagnosticInfo(
        adId: String?,
        hiddenMode: Boolean,
        extra: Map<String, Any?> = emptyMap()
    ): String {
        val payload = linkedMapOf<String, Any?>(
            "adId" to adId,
            "hiddenMode" to hiddenMode,
            "sdkVersion" to "2.8.02",
            "deviceModel" to Build.MODEL.orEmpty(),
            "deviceMake" to Build.MANUFACTURER.orEmpty()
        )
        payload.putAll(extra)
        return gson.toJson(payload)
    }

    private fun resolveDeviceId(): String {
        return Settings.Secure.getString(
            appContext.contentResolver,
            Settings.Secure.ANDROID_ID
        ) ?: "unknown_device"
    }

    private fun resolveLocalIp(): String? {
        return runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .flatMap { it.inetAddresses.toList() }
                .firstOrNull { !it.isLoopbackAddress && it is Inet4Address }
                ?.hostAddress
        }.getOrNull()
    }

    private fun safeGetMacAddress(): String? {
        return runCatching { getMacAddress() }
            .onFailure { error -> Log.w(TAG, "getMacAddress failed: ${error.message}") }
            .getOrNull()
    }
}
