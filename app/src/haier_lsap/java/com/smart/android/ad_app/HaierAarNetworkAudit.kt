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
            put("url_hash", AdPrivacySanitizer.shortHash(urlRaw))
            put("url_length", urlRaw.length)
            put("url_path", safePath(urlRaw))
            put("query_hash", AdPrivacySanitizer.shortHash(rawQuery(urlRaw)))
            put("query_length", rawQuery(urlRaw).length)
            put("headers_hash", AdPrivacySanitizer.shortHash(headersRaw))
            put("headers_length", headersRaw.length)
            put("header_names", headerNames(headersRaw))
            put("content_type", contentType)
            put("body_encoding", bodyEncoding)
            put("body_hash", bodySha256.ifBlank { AdPrivacySanitizer.shortHash(bodyRaw) })
            put("body_length", bodyLength ?: bodyRaw.toByteArray(Charsets.UTF_8).size)
            put("system_ua_before_hash", AdPrivacySanitizer.shortHash(check?.observedUa.orEmpty()))
            put("system_ua_before_length", check?.observedUa.orEmpty().length)
            put("system_ua_after_hash", AdPrivacySanitizer.shortHash(check?.effectiveUa.orEmpty()))
            put("system_ua_after_length", check?.effectiveUa.orEmpty().length)
            put("aar_effective_ua_hash", AdPrivacySanitizer.shortHash(HaierAarRuntimeBridge.currentEffectiveUa()))
            put("aar_effective_ua_length", HaierAarRuntimeBridge.currentEffectiveUa().length)
            put("ua_drift_detected", check?.repaired == true)
            put("ua_repaired", check?.repaired == true)
            put("response_code", responseCode ?: JSONObject.NULL)
            put("response_headers_hash", AdPrivacySanitizer.shortHash(responseHeadersRaw))
            put("response_headers_length", responseHeadersRaw.length)
            put("response_header_names", headerNames(responseHeadersRaw))
            put("error_message", errorMessage.take(180))
            put("background", HaierAarRequestContext.currentRequestId().isNullOrEmpty())
            extra.forEach { (key, value) -> putSanitizedExtra(key, value) }
        }.toString()
    }

    private fun JSONObject.putSanitizedExtra(key: String, value: Any?) {
        if (value !is String || !shouldHashExtra(key, value)) {
            put(key, value ?: JSONObject.NULL)
            return
        }
        put("${key}_hash", AdPrivacySanitizer.shortHash(value))
        put("${key}_length", value.length)
    }

    private fun shouldHashExtra(key: String, value: String): Boolean {
        val normalizedKey = key.lowercase()
        return normalizedKey.contains("ua") ||
            normalizedKey.contains("url") ||
            normalizedKey.endsWith("_raw") ||
            value.contains("://")
    }

    private fun safePath(url: String): String {
        return runCatching {
            java.net.URI(url).rawPath.orEmpty()
        }.getOrDefault(
            url.substringAfter("://", url)
                .substringAfter("/", "")
                .substringBefore("?")
                .substringBefore("#")
        ).take(180)
    }

    private fun headerNames(headers: String): String {
        return headers
            .lineSequence()
            .mapNotNull { line ->
                line.substringBefore(":", missingDelimiterValue = "")
                    .trim()
                    .takeIf { it.isNotBlank() }
            }
            .distinct()
            .joinToString(",")
            .take(180)
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
