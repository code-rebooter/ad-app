package com.smart.android.ad_app

object AdPlaybackPolicy {
    const val CALLBACK_TIMEOUT_MS = 180_000L
    const val FIRST_FRAME_TIMEOUT_MS = 30_000L
    const val PLAYBACK_STALL_TIMEOUT_MS = 60_000L
    const val PLAYBACK_BUFFERING_GRACE_LIMIT_MS = 120_000L
    const val AD_POD_TRANSITION_TIMEOUT_MS = 30_000L
    const val AD_BREAK_COMPLETION_GRACE_MS = 5_000L
    const val PLAYER_ENDED_GRACE_MS = 1_000L
    const val PLAYBACK_PROGRESS_INTERVAL_MS = 20_000L

    private const val PLAYBACK_COMPLETION_GRACE_MS = 60_000L
    private const val MIN_PLAYBACK_TIMEOUT_MS = 90_000L
    private const val MAX_PLAYBACK_TIMEOUT_MS = 15 * 60_000L
    private const val UNKNOWN_DURATION_PLAYBACK_TIMEOUT_MS = 10 * 60_000L
    private const val MIN_CALLBACK_TIMEOUT_SECONDS = 30L
    private const val MAX_CALLBACK_TIMEOUT_SECONDS = 600L

    fun resolveCallbackTimeoutMs(serverSeconds: Long?): Long {
        if (serverSeconds == null || serverSeconds !in MIN_CALLBACK_TIMEOUT_SECONDS..MAX_CALLBACK_TIMEOUT_SECONDS) {
            return CALLBACK_TIMEOUT_MS
        }
        return serverSeconds * 1_000L
    }

    fun resolvePlaybackTimeoutMs(adDurationMs: Long?): Long {
        if (adDurationMs == null || adDurationMs <= 0L) {
            return UNKNOWN_DURATION_PLAYBACK_TIMEOUT_MS
        }
        return (adDurationMs + PLAYBACK_COMPLETION_GRACE_MS)
            .coerceIn(MIN_PLAYBACK_TIMEOUT_MS, MAX_PLAYBACK_TIMEOUT_MS)
    }

    fun resolvePlaybackHardTimeoutMs(adDurationMs: Long?): Long {
        return resolvePlaybackTimeoutMs(adDurationMs) + PLAYBACK_BUFFERING_GRACE_LIMIT_MS
    }

    fun isTimeoutReason(reason: String): Boolean {
        return reason.contains("timeout") ||
            reason == "playback_deadline_exceeded" ||
            reason == "playback_stalled"
    }
}
