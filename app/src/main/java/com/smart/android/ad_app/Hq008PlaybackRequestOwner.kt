package com.smart.android.ad_app

/** Main-thread session ownership and one deadline spanning initialization, loading and playback. */
internal class Hq008PlaybackRequestOwner {
    class Ticket internal constructor(val createdAtMs: Long, val timeoutMs: Long) {
        var finishedAtMs: Long? = null
            internal set
        fun remainingMs(nowMs: Long): Long = (timeoutMs - (nowMs - createdAtMs)).coerceAtLeast(0L)
    }

    private var current: Ticket? = null

    fun begin(nowMs: Long, timeoutMs: Long): Ticket {
        current?.finishedAtMs = nowMs
        return Ticket(nowMs, timeoutMs.coerceAtLeast(1L)).also { current = it }
    }

    fun isActive(ticket: Ticket): Boolean = current === ticket && ticket.finishedAtMs == null

    fun canContinue(ticket: Ticket, nowMs: Long): Boolean =
        isActive(ticket) && ticket.remainingMs(nowMs) > 0L

    fun finish(ticket: Ticket, nowMs: Long): Boolean {
        if (!isActive(ticket)) return false
        ticket.finishedAtMs = nowMs
        current = null
        return true
    }
}
