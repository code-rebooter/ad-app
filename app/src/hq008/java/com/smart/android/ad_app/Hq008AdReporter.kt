package com.smart.android.ad_app

import android.provider.Settings
import com.smart.android.ad_app.AdLocalLog as Log
import com.smart.android.ad_app.bean.EmptyData
import com.google.gson.Gson
import com.speed.ext.getMacAddress
import com.speed.net.NetworkHelper
import com.speed.net.enum.RequestMethod
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.UUID

internal object Hq008AdReporter {
    private val REPORT_URL = "${Hq008ApiConfig.FIXED_BASE_URL}api/v2/ad/report"
    private const val TAG = "Hq008AdReporter"
    private val gson = Gson()
    @Volatile internal var debugReportObserver: ((String, Map<String, Any>, String?) -> Unit)? = null

    object EventType {
        const val AD_REQUESTED = "AD_REQUESTED"
        const val AD_LOADED = "AD_LOADED"
        const val AD_STARTED = "AD_STARTED"
        const val AD_FILLED = "AD_FILLED"
        const val AD_COMPLETED = "AD_COMPLETED"
        const val AD_ERROR = "AD_ERROR"
    }

    object Message {
        const val REQUESTED = "REQUESTED"
        const val FILLED = "FILLED"
        const val LOADED = "LOADED"
        const val STARTED = "STARTED"
        const val COMPLETED = "COMPLETED"
        const val CONTAINER_ERROR = "CONTAINER_ERROR"
        const val INIT_ERROR = "INIT_ERROR"
        const val CONTAINER_RELEASED = "CONTAINER_RELEASED"
        const val START_ERROR = "START_ERROR"
        const val REQUEST_ERROR = "REQUEST_ERROR"
        const val TIMEOUT = "TIMEOUT"
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
        extra: Map<String, Any?> = emptyMap(),
        durable: Boolean = false
    ) {
        report(
            requestId = requestId,
            eventType = EventType.AD_REQUESTED,
            message = Message.REQUESTED,
            durable = durable,
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

    fun reportFilled(
        requestId: String,
        adId: String?,
        hiddenMode: Boolean,
        extra: Map<String, Any?> = emptyMap()
    ) {
        report(
            requestId = requestId,
            eventType = EventType.AD_FILLED,
            message = Message.FILLED,
            diagnosticInfo = buildDiagnosticInfo(adId, hiddenMode, extra)
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
            eventType = EventType.AD_LOADED,
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
            eventType = EventType.AD_STARTED,
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
        extra: Map<String, Any?> = emptyMap(),
        durable: Boolean = false
    ) {
        report(
            requestId = requestId,
            eventType = EventType.AD_ERROR,
            message = errorMessage,
            durable = durable,
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
        diagnosticInfo: DiagnosticPayload,
        durable: Boolean = false
    ) {
        // REPORT_FLOW request
        val params = linkedMapOf<String, Any>(
            "request_id" to requestId,
            "event_type" to eventType,
            "uuid" to resolveDeviceId(),
            "channel_id" to diagnosticInfo.channelId,
            "ad_version" to BuildConfig.VERSION_CODE,
            "mac" to (safeGetMacAddress()?.takeIf { it.isNotBlank() } ?: "00:00:00:00:00:00"),
            "app_id" to appContext.packageName,
            "make" to HaierBuildIdentityNormalizer.manufacturer(),
            "model" to HaierBuildIdentityNormalizer.model(),
            "message" to message,
            "diagnostic_info" to diagnosticInfo.json
        ).apply { Hq008ApiRequestFields.appendTo(this, appContext) }

        resolveLocalIp()?.let { params["local_ip"] = it }
        val requestJson = gson.toJson(params)
        if (BuildConfig.DEBUG) debugReportObserver?.invoke("request", params.toMap(), null)

        Log.i(
            TAG,
            "上报链路：准备上报事件，requestId=$requestId，eventType=$eventType，message=$message"
        )
        Log.i(
            TAG,
            "上报链路：请求体已生成，requestId=$requestId，eventType=$eventType，message=$message，body=$requestJson"
        )

        if (durable) {
            Hq008SdkRequestReports.enqueue(params)
        } else {
            send(params)
        }
    }

    internal fun send(params: Map<String, Any>, onResult: (Boolean) -> Unit = {}) {
        val requestId = params["request_id"]
        val eventType = params["event_type"]
        val message = params["message"]
        val fixtureUrl = if (BuildConfig.DEBUG &&
            (requestId.toString().startsWith("device125-") || requestId.toString().startsWith("device-validation-"))) {
            appContext.getSharedPreferences("tcl_device_validation", android.content.Context.MODE_PRIVATE)
                .getString("report_url", null)?.takeIf { it.startsWith("http://127.0.0.1:") }
        } else null
        NetworkHelper.makeRequest<EmptyData>(
            url = fixtureUrl ?: REPORT_URL,
            method = RequestMethod.POST,
            params = params,
            isEncryted = false,
            useDomainSwitch = false,
        ) { _, error ->
            onResult(error == null)
            if (BuildConfig.DEBUG) debugReportObserver?.invoke("result", params.toMap(), error?.message)
            if (error != null) {
                Log.e(TAG, "上报链路：上报失败，requestId=$requestId，eventType=$eventType，message=$message，error=${error.message}", error)
            } else {
                // REPORT_FLOW success
                Log.i(
                    TAG,
                    "上报链路：上报成功，requestId=$requestId，eventType=$eventType，message=$message"
                )
                Log.i(
                    TAG,
                    "上报链路：服务端已确认上报成功，requestId=$requestId，eventType=$eventType，message=$message"
                )
            }
        }
    }

    private fun buildDiagnosticInfo(
        adId: String?,
        hiddenMode: Boolean,
        extra: Map<String, Any?> = emptyMap()
    ): DiagnosticPayload {
        val payload = linkedMapOf<String, Any?>(
            "adId" to adId,
            "hiddenMode" to hiddenMode,
            "sdkVersion" to "2.8.02",
            "deviceModel" to HaierBuildIdentityNormalizer.model(),
            "deviceMake" to HaierBuildIdentityNormalizer.manufacturer()
        )
        payload.putAll(sanitizeExtra(extra))
        return DiagnosticPayload(gson.toJson(payload),
            (extra["requestChannelId"] as? String)?.takeIf { it.isNotBlank() }
                ?: AdChannelResolver.currentChannel())
    }

    private data class DiagnosticPayload(val json: String, val channelId: String)

    private fun sanitizeExtra(extra: Map<String, Any?>): Map<String, Any?> {
        val sanitized = linkedMapOf<String, Any?>()
        extra.forEach { (key, value) ->
            if (shouldHashValue(key, value)) {
                val raw = value as String
                sanitized["${key}Hash"] = AdPrivacySanitizer.shortHash(raw)
                sanitized["${key}Length"] = AdPrivacySanitizer.length(raw)
            } else {
                sanitized[key] = value
            }
        }
        return sanitized
    }

    private fun shouldHashValue(key: String, value: Any?): Boolean {
        if (value !is String || value.isBlank()) {
            return false
        }
        val normalizedKey = key.lowercase()
        if (normalizedKey.endsWith("hash") || normalizedKey.endsWith("length")) {
            return false
        }
        return normalizedKey == "adtagurl" ||
            normalizedKey.endsWith("url") ||
            value.contains("://")
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
            .onFailure { error -> Log.w(TAG, "上报链路：读取 MAC 地址失败，将使用默认占位值，error=${error.message}") }
            .getOrNull()
    }
}
