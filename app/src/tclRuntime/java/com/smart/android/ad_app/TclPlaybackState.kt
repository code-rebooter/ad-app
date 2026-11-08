package com.smart.android.ad_app

/** Monotonic budgets shared by TCL callbacks and the real player's snapshots. */
internal class TclPlaybackState(val createdAtMs: Long, val requestTimeoutMs: Long) {
    data class Metadata(
        val key: String = "",
        val creativeId: String = "",
        val mediaUrlHash: String = "",
        val adPosition: Int = -1,
        val totalAds: Int = -1,
        val podMaxDurationMs: Long = -1L
    )

    class Segment(val sequence: Int, val selectedAtMs: Long, var metadata: Metadata) {
        var durationMs = -1L
        var firstFrameAtMs: Long? = null
        var evidenceAtMs: Long? = null
        var completedAtMs: Long? = null
        var terminalEvent = ""
        var maxPositionMs = -1L
        var lastPositionMs = -1L
        var lastProgressAtMs = selectedAtMs
        var bufferingSinceMs: Long? = null
        var bufferingTotalMs = 0L
        var bufferingCount = 0
        var notPlayingSinceMs: Long? = null
        var notPlayingTotalMs = 0L
        var nativeIndex = -1
        val budget = Budget()

        fun diagnostics(now: Long): Map<String, Any?> = linkedMapOf(
            "segment" to sequence, "creative_id" to metadata.creativeId,
            "media_url_hash" to metadata.mediaUrlHash, "ad_position" to metadata.adPosition,
            "total_ads" to metadata.totalAds, "pod_max_duration_ms" to metadata.podMaxDurationMs,
            "native_media_index" to nativeIndex, "duration_ms" to durationMs,
            "selected_at_ms" to selectedAtMs, "first_frame_at_ms" to firstFrameAtMs,
            "playback_evidence_at_ms" to evidenceAtMs, "completed_at_ms" to completedAtMs,
            "terminal_event" to terminalEvent, "max_position_ms" to maxPositionMs,
            "playback_ratio" to if (durationMs > 0 && maxPositionMs >= 0)
                (maxPositionMs.toDouble() / durationMs).coerceIn(0.0, 1.0) else null,
            "buffering_count" to bufferingCount,
            "buffering_total_ms" to bufferingTotalMs + (bufferingSinceMs?.let { now - it } ?: 0L),
            "ready_not_playing_total_ms" to notPlayingTotalMs +
                (notPlayingSinceMs?.let { now - it } ?: 0L),
            "playback_budget_remaining_ms" to budget.remaining(now),
            "playback_hard_deadline_ms" to budget.hardDeadlineMs
        )
    }

    class Budget {
        var originMs = -1L
            private set
        var totalMs = -1L
            private set
        var hardDeadlineMs = -1L
            private set
        private var storedMs = -1L
        private var runningSinceMs: Long? = null

        fun update(now: Long, durationMs: Long, paused: Boolean) {
            val consumed = if (totalMs < 0) 0L else totalMs - remaining(now)
            if (originMs < 0) originMs = now
            totalMs = AdPlaybackPolicy.resolvePlaybackTimeoutMs(durationMs.takeIf { it > 0 })
            storedMs = (totalMs - consumed).coerceAtLeast(0L)
            hardDeadlineMs = originMs + AdPlaybackPolicy.resolvePlaybackHardTimeoutMs(
                durationMs.takeIf { it > 0 })
            runningSinceMs = if (paused) null else now
        }

        fun pause(now: Long) {
            storedMs = remaining(now)
            runningSinceMs = null
        }

        fun resume(now: Long) {
            if (runningSinceMs == null) runningSinceMs = now
        }

        fun remaining(now: Long): Long = if (storedMs < 0) -1L else
            (storedMs - (runningSinceMs?.let { now - it } ?: 0L)).coerceAtLeast(0L)
    }

    val segments = mutableListOf<Segment>()
    val current: Segment? get() = segments.lastOrNull()
    var startedAtMs: Long? = null
        private set
    private var betweenAdsSinceMs: Long? = null
    private var playerIndex = -1
    var closed = false
        private set

    fun remainingRequestMs(now: Long): Long =
        (createdAtMs + requestTimeoutMs - now).coerceAtLeast(0L)

    fun select(metadata: Metadata, now: Long): Boolean {
        if (closed) return false
        val previous = current
        // Native COMPLETED can precede the player's index change by a watchdog
        // tick. That last snapshot still identifies the finished ad; it must
        // not reopen it or renew the budget while waiting for the next item.
        if (metadata.key.isNotBlank() && segments.any {
                it.terminalEvent.isNotBlank() && it.metadata.key == metadata.key
            }) return false
        if (previous?.terminalEvent?.isNotBlank() == true && metadata.key.isBlank()) return false
        if (previous != null && previous.terminalEvent.isBlank() &&
            (metadata.key.isBlank() || previous.metadata.key.isBlank() || metadata.key == previous.metadata.key ||
                (previous.metadata.key.startsWith("native-index:") &&
                    metadata.adPosition == previous.nativeIndex + 1))) {
            if (metadata.key.isNotBlank()) previous.metadata = metadata
            return false
        }
        if (previous != null && previous.terminalEvent.isBlank()) completeSegment("media_changed", now)
        betweenAdsSinceMs = null
        segments += Segment(segments.size + 1, now, metadata).also { it.budget.update(now, -1L, false) }
        return true
    }

    fun markStarted(now: Long) {
        if (startedAtMs == null) startedAtMs = now
        if (current == null) select(Metadata(), now)
    }

    fun firstFrame(now: Long) {
        if (closed) return
        markStarted(now)
        current?.takeIf { it.terminalEvent.isBlank() }?.let {
            if (it.firstFrameAtMs == null) it.firstFrameAtMs = now
            if (it.evidenceAtMs == null) it.evidenceAtMs = now
        }
    }

    fun buffering(now: Long) {
        current?.takeIf { !closed && it.terminalEvent.isBlank() }?.let { segment ->
            finishNotPlaying(segment, now)
            if (segment.bufferingSinceMs == null) {
                segment.bufferingSinceMs = now
                segment.bufferingCount++
                segment.budget.pause(now)
            }
        }
    }

    fun playing(now: Long) {
        current?.takeIf { !closed && it.terminalEvent.isBlank() }?.let {
            finishBuffering(it, now)
            finishNotPlaying(it, now)
        }
    }

    fun snapshot(now: Long, positionMs: Long, durationMs: Long, isPlaying: Boolean,
                 preparing: Boolean, mediaIndex: Int) {
        if (closed) return
        if (mediaIndex >= 0 && playerIndex >= 0 && mediaIndex != playerIndex &&
            current?.metadata?.adPosition != mediaIndex + 1) {
            select(Metadata(key = "native-index:$mediaIndex", adPosition = mediaIndex + 1), now)
        }
        if (mediaIndex >= 0) playerIndex = mediaIndex
        val segment = current?.takeIf { it.terminalEvent.isBlank() } ?: return
        segment.nativeIndex = mediaIndex
        if (durationMs > 0 && durationMs != segment.durationMs) {
            segment.durationMs = durationMs
            segment.budget.update(now, durationMs, segment.bufferingSinceMs != null)
        }
        if (positionMs >= 0) {
            if ((segment.lastPositionMs >= 0 && positionMs > segment.lastPositionMs) ||
                (segment.lastPositionMs < 0 && positionMs >= 100L)) {
                segment.lastProgressAtMs = now
                if (segment.evidenceAtMs == null) segment.evidenceAtMs = now
                if (startedAtMs == null) startedAtMs = now
            }
            segment.lastPositionMs = positionMs
            segment.maxPositionMs = maxOf(segment.maxPositionMs, positionMs)
        }
        when {
            preparing -> buffering(now)
            isPlaying -> playing(now)
            segment.evidenceAtMs != null -> {
                // A quiet player during an observed buffering period remains buffering until
                // the SDK's buffer-end callback or an actual playing snapshot arrives.
                if (segment.bufferingSinceMs == null && segment.notPlayingSinceMs == null)
                    segment.notPlayingSinceMs = now
            }
        }
    }

    fun completeSegment(event: String, now: Long) {
        current?.takeIf { !closed && it.terminalEvent.isBlank() }?.let {
            finishBuffering(it, now)
            finishNotPlaying(it, now)
            it.completedAtMs = now
            it.terminalEvent = event
            betweenAdsSinceMs = now
        }
    }

    fun timeoutReason(now: Long): String? {
        if (closed) return null
        val segment = current
        if (startedAtMs == null && remainingRequestMs(now) <= 0L) return "request_start_timeout"
        if (segment == null) return null
        if (segment.terminalEvent.isNotBlank()) {
            val wait = if (segment.metadata.totalAds > 0 &&
                segment.metadata.adPosition >= segment.metadata.totalAds)
                AdPlaybackPolicy.AD_BREAK_COMPLETION_GRACE_MS
            else AdPlaybackPolicy.AD_POD_TRANSITION_TIMEOUT_MS + AdPlaybackPolicy.AD_BREAK_COMPLETION_GRACE_MS
            return if (now - (betweenAdsSinceMs ?: now) >= wait) "ad_pod_transition_timeout" else null
        }
        if (segment.evidenceAtMs == null && now - segment.selectedAtMs >= AdPlaybackPolicy.FIRST_FRAME_TIMEOUT_MS)
            return "playback_start_timeout"
        if (now >= segment.budget.hardDeadlineMs) return "playback_hard_deadline_exceeded"
        if (segment.bufferingSinceMs?.let { now - it >= AdPlaybackPolicy.PLAYBACK_STALL_TIMEOUT_MS } == true)
            return "playback_buffer_timeout"
        if (segment.notPlayingSinceMs?.let { now - it >= AdPlaybackPolicy.PLAYBACK_STALL_TIMEOUT_MS } == true)
            return "playback_ready_not_playing_timeout"
        if (segment.bufferingSinceMs == null && segment.budget.remaining(now) == 0L)
            return "playback_deadline_exceeded"
        if (segment.evidenceAtMs != null && segment.bufferingSinceMs == null &&
            segment.notPlayingSinceMs == null && now - segment.lastProgressAtMs >= AdPlaybackPolicy.PLAYBACK_STALL_TIMEOUT_MS)
            return "playback_stalled"
        return null
    }

    fun finish(now: Long, reason: String) {
        if (closed) return
        completeSegment(reason, now)
        closed = true
    }

    fun diagnostics(now: Long): Map<String, Any?> = linkedMapOf(
        "requestCreatedAtMs" to createdAtMs, "callbackTimeoutMs" to requestTimeoutMs,
        "startedAtMs" to startedAtMs, "segmentCount" to segments.size,
        "segments" to segments.map { it.diagnostics(now) }
    )

    private fun finishBuffering(segment: Segment, now: Long) {
        segment.bufferingSinceMs?.let {
            segment.bufferingTotalMs += (now - it).coerceAtLeast(0L)
            segment.bufferingSinceMs = null
            segment.lastProgressAtMs = now
            segment.budget.resume(now)
        }
    }

    private fun finishNotPlaying(segment: Segment, now: Long) {
        segment.notPlayingSinceMs?.let {
            segment.notPlayingTotalMs += (now - it).coerceAtLeast(0L)
            segment.notPlayingSinceMs = null
        }
    }
}
