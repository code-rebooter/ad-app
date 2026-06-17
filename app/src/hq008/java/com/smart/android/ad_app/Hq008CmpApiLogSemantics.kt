package com.smart.android.ad_app

import com.google.gson.JsonObject
import com.google.gson.JsonParser

internal object Hq008CmpApiLogSemantics {
    data class ResponseSummary(
        val code: Int?,
        val message: String?,
        val hasData: Boolean?
    )

    fun buildResultEventMessage(
        responseBody: String?,
        fallbackCode: Int?,
        fallbackMessage: String?,
        hasData: Boolean
    ): String {
        val summary = parseResponseSummary(responseBody)
        return buildMessage(
            code = summary?.code ?: fallbackCode,
            message = summary?.message ?: fallbackMessage,
            hasData = summary?.hasData ?: hasData
        )
    }

    fun buildFailureEventMessage(
        responseBody: String?,
        fallbackErrorMessage: String
    ): String {
        val summary = parseResponseSummary(responseBody)
        return if (summary != null && (
                summary.code != null ||
                    !summary.message.isNullOrBlank() ||
                    summary.hasData != null
                )
        ) {
            buildMessage(
                code = summary.code,
                message = summary.message,
                hasData = summary.hasData
            )
        } else {
            "error=${fallbackErrorMessage.ifBlank { "unknown" }}"
        }
    }

    fun parseResponseSummary(responseBody: String?): ResponseSummary? {
        if (responseBody.isNullOrBlank()) {
            return null
        }
        return runCatching {
            val root = JsonParser.parseString(responseBody).asJsonObject
            val dataElement = root.get("data")
            ResponseSummary(
                code = root.intOrNull("code") ?: root.intOrNull("error_code"),
                message = root.stringOrNull("msg") ?: root.stringOrNull("error_msg"),
                hasData = dataElement?.let { !it.isJsonNull }
            )
        }.getOrNull()
    }

    private fun buildMessage(
        code: Int?,
        message: String?,
        hasData: Boolean?
    ): String {
        val segments = mutableListOf<String>()
        code?.let { segments += "code=$it" }
        message?.takeIf { it.isNotBlank() }?.let { segments += "message=$it" }
        hasData?.let { segments += "hasData=$it" }
        return if (segments.isNotEmpty()) {
            segments.joinToString(",")
        } else {
            "error=unknown"
        }
    }

    private fun JsonObject.stringOrNull(key: String): String? {
        return get(key)?.takeIf { !it.isJsonNull }?.asString
    }

    private fun JsonObject.intOrNull(key: String): Int? {
        return get(key)?.takeIf { !it.isJsonNull }?.asInt
    }
}
