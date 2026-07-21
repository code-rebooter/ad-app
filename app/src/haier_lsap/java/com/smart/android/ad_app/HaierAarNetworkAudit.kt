package com.smart.android.ad_app

import org.json.JSONObject
import java.util.UUID

internal data class HaierAarAuditEvent(
    val eventType: String,
    val sourceStack: String,
    val method: String,
    val urlRaw: String,
    val headersRaw: String,
    val bodyRaw: String,
    val contentType: String = "",
    val bodyEncoding: String = "text",
    val bodyLength: Long? = null,
    val bodySha256: String = "",
    val responseCode: Int? = null,
    val responseHeadersRaw: String = "",
    val errorMessage: String = "",
    val durationMs: Long? = null,
    val coverage: String = "java_enforced",
    val extra: Map<String, Any?> = emptyMap(),
    val auditId: String = UUID.randomUUID().toString(),
    val timestampMs: Long = System.currentTimeMillis()
) {
    fun toJson(): String {
        val check = HaierUserAgentInstaller.currentRuntimeCheck()
        return JSONObject().apply {
            put("audit_id", auditId)
            put("request_id", HaierAarRequestContext.currentRequestId().orEmpty())
            put("channel_id", AdChannelResolver.currentChannel())
            put("application_id", appContext.packageName)
            put("app_version_code", BuildConfig.VERSION_CODE)
            put("app_version_name", BuildConfig.VERSION_NAME)
            put("lsap_sdk_version", "1.1.12")
            put("patch_version", HaierAarRuntimeBridge.PATCH_VERSION)
            put("timestamp_ms", timestampMs)
            put("duration_ms", durationMs ?: JSONObject.NULL)
            put("source_stack", sourceStack)
            put("coverage", coverage)
            put("method", method)
            put("url_raw", urlRaw)
            put("query_raw", rawQuery(urlRaw))
            put("headers_raw", headersRaw)
            put("content_type", contentType)
            put("body_encoding", bodyEncoding)
            put("body_raw", bodyRaw)
            put("body_length", bodyLength ?: bodyRaw.toByteArray(Charsets.UTF_8).size)
            put("body_sha256", bodySha256)
            put("system_ua_before", check?.observedUa.orEmpty())
            put("system_ua_after", check?.effectiveUa.orEmpty())
            put("aar_effective_ua", HaierAarRuntimeBridge.currentEffectiveUa())
            put("ua_drift_detected", check?.repaired == true)
            put("ua_repaired", check?.repaired == true)
            put("response_code", responseCode ?: JSONObject.NULL)
            put("response_headers_raw", responseHeadersRaw)
            put("error_message", errorMessage)
            put("background", HaierAarRequestContext.currentRequestId().isNullOrEmpty())
            extra.forEach { (key, value) -> put(key, value ?: JSONObject.NULL) }
        }.toString()
    }

    private fun rawQuery(url: String): String {
        val marker = url.indexOf('?')
        if (marker < 0 || marker == url.lastIndex) return ""
        val fragment = url.indexOf('#', marker + 1).takeIf { it >= 0 } ?: url.length
        return url.substring(marker + 1, fragment)
    }
}

internal object HaierAarRequestContext {
    @Volatile
    private var requestId: String? = null

    fun set(requestId: String) {
        this.requestId = requestId
    }

    fun clear(requestId: String) {
        if (this.requestId == requestId) {
            this.requestId = null
        }
    }

    fun currentRequestId(): String? = requestId
}
