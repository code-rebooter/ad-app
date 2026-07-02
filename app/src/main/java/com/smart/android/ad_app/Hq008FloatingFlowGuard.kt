package com.smart.android.ad_app

object Hq008FloatingFlowGuard {
    private const val DEFAULT_MAX_FLOW_DURATION_MS = 10 * 60 * 1_000L

    class Token internal constructor(
        val channelId: String,
        internal val id: Long
    )

    private data class ActiveFlow(
        val token: Token,
        val startedAtMs: Long,
        val maxDurationMs: Long
    ) {
        fun isExpired(nowMs: Long): Boolean {
            return nowMs - startedAtMs > maxDurationMs
        }
    }

    private val lock = Any()
    private var activeFlow: ActiveFlow? = null
    private var nextTokenId = 1L

    fun tryEnter(channelId: String = AdChannelResolver.currentChannel()): Token? {
        return tryEnter(
            channelId = channelId,
            nowMs = System.currentTimeMillis(),
            maxDurationMs = DEFAULT_MAX_FLOW_DURATION_MS
        )
    }

    internal fun tryEnter(
        channelId: String,
        nowMs: Long,
        maxDurationMs: Long = DEFAULT_MAX_FLOW_DURATION_MS
    ): Token? {
        synchronized(lock) {
            val current = activeFlow
            if (current != null && !current.isExpired(nowMs)) {
                return null
            }

            val token = Token(
                channelId = channelId,
                id = nextTokenId++
            )
            activeFlow = ActiveFlow(
                token = token,
                startedAtMs = nowMs,
                maxDurationMs = maxDurationMs.coerceAtLeast(1L)
            )
            return token
        }
    }

    fun finish(token: Token?, reason: String) {
        if (token == null) return
        synchronized(lock) {
            val current = activeFlow ?: return
            if (current.token == token) {
                activeFlow = null
            }
        }
    }

    internal fun resetForTest() {
        synchronized(lock) {
            activeFlow = null
            nextTokenId = 1L
        }
    }
}
