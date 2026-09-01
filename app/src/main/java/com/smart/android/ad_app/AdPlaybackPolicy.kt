package com.smart.android.ad_app

object AdPlaybackPolicy {
    const val CALLBACK_TIMEOUT_MS = 180_000L
    private const val MIN_CALLBACK_TIMEOUT_SECONDS = 30L
    private const val MAX_CALLBACK_TIMEOUT_SECONDS = 600L

    fun resolveCallbackTimeoutMs(serverSeconds: Long?): Long {
        if (serverSeconds == null || serverSeconds !in MIN_CALLBACK_TIMEOUT_SECONDS..MAX_CALLBACK_TIMEOUT_SECONDS) {
            return CALLBACK_TIMEOUT_MS
        }
        return serverSeconds * 1_000L
    }
}
