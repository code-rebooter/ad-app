package com.smart.android.ad_app

import java.security.MessageDigest

internal object AdPrivacySanitizer {
    private const val EMPTY = "empty"
    private const val HASH_PREFIX_LENGTH = 12

    fun shortHash(value: String?): String {
        val normalized = value?.takeIf { it.isNotBlank() } ?: return EMPTY
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(normalized.toByteArray(Charsets.UTF_8))
        return digest.joinToString(separator = "") { byte -> "%02x".format(byte) }
            .take(HASH_PREFIX_LENGTH)
    }

    fun length(value: String?): Int {
        return value?.length ?: 0
    }

    fun buildDiagnostics(prefix: String, value: String?): Map<String, Any> {
        return mapOf(
            "${prefix}Hash" to shortHash(value),
            "${prefix}Length" to length(value)
        )
    }
}
