package com.smart.android.ad_app

import android.util.Base64
import java.security.MessageDigest

internal data class HaierCapturedBody(
    val raw: String,
    val encoding: String,
    val length: Long,
    val sha256: String,
    val unavailableReason: String = ""
)

internal fun captureAarBytes(bytes: ByteArray, contentType: String?): HaierCapturedBody {
    val normalizedType = contentType.orEmpty().lowercase()
    val isText = normalizedType.startsWith("text/") ||
        normalizedType.contains("json") ||
        normalizedType.contains("xml") ||
        normalizedType.contains("form") ||
        normalizedType.contains("javascript") ||
        normalizedType.contains("x-www-form-urlencoded")
    return HaierCapturedBody(
        raw = if (isText || bytes.isEmpty()) {
            bytes.toString(Charsets.UTF_8)
        } else {
            Base64.encodeToString(bytes, Base64.NO_WRAP)
        },
        encoding = if (isText || bytes.isEmpty()) "text" else "base64",
        length = bytes.size.toLong(),
        sha256 = aarSha256(bytes)
    )
}

internal fun unavailableAarBody(reason: String, length: Long = -1L): HaierCapturedBody {
    return HaierCapturedBody(
        raw = "",
        encoding = "unavailable",
        length = length,
        sha256 = "",
        unavailableReason = reason
    )
}

internal fun aarSha256(bytes: ByteArray): String {
    return MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }
}
