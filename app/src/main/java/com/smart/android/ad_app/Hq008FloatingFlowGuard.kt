package com.smart.android.ad_app

object Hq008FloatingFlowGuard {
    private const val DEFAULT_MAX_FLOW_DURATION_MS = 10 * 60 * 1_000L
    private const val PLAYBACK_CLEANUP_MARGIN_MS = 5_000L

    class Token internal constructor(val channelId: String, internal val id: Long) {
        @Volatile var requestId: String? = null
        @Volatile var stage: String = "entered"
    }

    data class Entry(val token: Token, val expiredToken: Token?)
    data class Snapshot(
        val channelId: String,
        val requestId: String?,
        val stage: String,
        val elapsedMs: Long,
        val deadlineMs: Long,
        val remainingMs: Long
    )

    private data class ActiveFlow(
        val token: Token,
        val startedAtMs: Long,
        var deadlineMs: Long
    )
    private val lock = Any()
    private var activeFlow: ActiveFlow? = null
    private var nextTokenId = 1L

    // Monotonic time also works in local JVM tests without Android framework calls.
    private fun elapsedMs(): Long = System.nanoTime() / 1_000_000L

    fun tryEnter(channelId: String = AdChannelResolver.currentChannel()): Token? =
        tryEnterWithExpiry(channelId)?.token

    fun tryEnterWithExpiry(channelId: String): Entry? =
        tryEnterWithExpiry(channelId, elapsedMs())

    internal fun tryEnter(channelId: String, nowMs: Long,
        maxDurationMs: Long = DEFAULT_MAX_FLOW_DURATION_MS): Token? =
        tryEnterWithExpiry(channelId, nowMs, maxDurationMs)?.token

    internal fun tryEnterWithExpiry(channelId: String, nowMs: Long,
        maxDurationMs: Long = DEFAULT_MAX_FLOW_DURATION_MS): Entry? = synchronized(lock) {
        val previous = activeFlow
        if (previous != null && nowMs <= previous.deadlineMs) return@synchronized null
        val token = Token(channelId, nextTokenId++)
        activeFlow = ActiveFlow(token, nowMs, nowMs + maxDurationMs.coerceAtLeast(1L))
        Entry(token, previous?.token)
    }

    fun extendForPlayback(token: Token, timeoutMs: Long) =
        extendForPlayback(token, timeoutMs, elapsedMs())

    internal fun extendForPlayback(token: Token, timeoutMs: Long, nowMs: Long) {
        synchronized(lock) {
            activeFlow?.takeIf { it.token === token }?.let {
                it.deadlineMs = maxOf(it.deadlineMs, nowMs + timeoutMs + PLAYBACK_CLEANUP_MARGIN_MS)
            }
        }
    }

    fun extendActiveForPlayback(requestId: String, timeoutMs: Long): Boolean =
        extendActiveForPlayback(requestId, timeoutMs, elapsedMs())

    internal fun extendActiveForPlayback(
        requestId: String,
        timeoutMs: Long,
        nowMs: Long
    ): Boolean = synchronized(lock) {
        val flow = activeFlow ?: return@synchronized false
        if (requestId.isBlank() || flow.token.requestId != requestId) return@synchronized false
        flow.deadlineMs = maxOf(
            flow.deadlineMs,
            nowMs + timeoutMs.coerceAtLeast(1L) + PLAYBACK_CLEANUP_MARGIN_MS
        )
        true
    }

    fun markActiveStage(requestId: String, stage: String): Boolean = synchronized(lock) {
        val flow = activeFlow ?: return@synchronized false
        if (requestId.isBlank() || flow.token.requestId != requestId) return@synchronized false
        flow.token.stage = stage
        true
    }

    fun markStage(token: Token, stage: String): Boolean = synchronized(lock) {
        val flow = activeFlow ?: return@synchronized false
        if (flow.token !== token) return@synchronized false
        flow.token.stage = stage
        true
    }

    fun activeSnapshot(): Snapshot? = activeSnapshot(elapsedMs())

    internal fun activeSnapshot(nowMs: Long): Snapshot? = synchronized(lock) {
        activeFlow?.let { flow ->
            Snapshot(
                channelId = flow.token.channelId,
                requestId = flow.token.requestId,
                stage = flow.token.stage,
                elapsedMs = (nowMs - flow.startedAtMs).coerceAtLeast(0L),
                deadlineMs = flow.deadlineMs,
                remainingMs = (flow.deadlineMs - nowMs).coerceAtLeast(0L)
            )
        }
    }

    fun finish(token: Token?, reason: String) {
        synchronized(lock) {
            if (activeFlow?.token === token) activeFlow = null
        }
    }

    @JvmStatic
    fun isActive(token: Token?): Boolean = synchronized(lock) {
        token != null && activeFlow?.token === token
    }

    @JvmStatic
    fun cancelActive(reason: String): Token? = synchronized(lock) {
        activeFlow?.token.also { activeFlow = null }
    }

    internal fun resetForTest() {
        synchronized(lock) {
            activeFlow = null
            nextTokenId = 1L
        }
    }
}
