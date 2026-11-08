package com.smart.android.tclad.ima

import com.google.gson.JsonParser

internal class GoogleImaPlaybackBudgetState {
    var totalMs: Long = -1L
        private set
    var hardTimeoutMs: Long = -1L
        private set
    var originAtMs: Long = -1L
        private set
    var hardDeadlineAtMs: Long = -1L
        private set
    var runningAtMs: Long? = null
        private set

    private var storedRemainingMs: Long = -1L

    val isRunning: Boolean
        get() = runningAtMs != null

    fun start(nowMs: Long, totalMs: Long, hardTimeoutMs: Long, paused: Boolean) {
        this.totalMs = totalMs.coerceAtLeast(0L)
        this.hardTimeoutMs = hardTimeoutMs.coerceAtLeast(0L)
        originAtMs = nowMs
        hardDeadlineAtMs = nowMs + this.hardTimeoutMs
        storedRemainingMs = this.totalMs
        runningAtMs = if (paused) null else nowMs
    }

    fun update(nowMs: Long, totalMs: Long, hardTimeoutMs: Long, paused: Boolean) {
        if (originAtMs < 0L || this.totalMs < 0L) {
            start(nowMs, totalMs, hardTimeoutMs, paused)
            return
        }
        val consumedMs = (this.totalMs - remaining(nowMs)).coerceAtLeast(0L)
        this.totalMs = totalMs.coerceAtLeast(0L)
        this.hardTimeoutMs = hardTimeoutMs.coerceAtLeast(0L)
        storedRemainingMs = (this.totalMs - consumedMs).coerceAtLeast(0L)
        hardDeadlineAtMs = originAtMs + this.hardTimeoutMs
        runningAtMs = if (paused) null else nowMs
    }

    fun pause(nowMs: Long) {
        storedRemainingMs = remaining(nowMs)
        runningAtMs = null
    }

    fun resume(nowMs: Long): Boolean {
        if (storedRemainingMs <= 0L) return false
        if (runningAtMs == null) runningAtMs = nowMs
        return true
    }

    fun stop(nowMs: Long) {
        storedRemainingMs = remaining(nowMs)
        runningAtMs = null
    }

    fun remaining(nowMs: Long): Long {
        if (storedRemainingMs < 0L) return storedRemainingMs
        val runningAt = runningAtMs ?: return storedRemainingMs
        return (storedRemainingMs - (nowMs - runningAt).coerceAtLeast(0L)).coerceAtLeast(0L)
    }

    fun hardRemaining(nowMs: Long): Long =
        if (hardDeadlineAtMs < 0L) -1L else (hardDeadlineAtMs - nowMs).coerceAtLeast(0L)
}

internal class GoogleImaProgressEvidence {
    private val lastPositionsMs = mutableMapOf<String, Long>()

    fun observe(adInstanceId: String, positionMs: Long): Boolean {
        if (adInstanceId.isBlank() || positionMs < 0L) return false
        val previous = lastPositionsMs.put(adInstanceId, positionMs) ?: return false
        return positionMs > previous
    }
}

internal object GoogleImaProgressFields {
    fun positionMs(fields: Map<String, String>): Long {
        fields["ima_progress_current_time_ms"]?.toLongOrNull()
            ?.takeIf { it >= 0L }
            ?.let { return it }
        val millisecondKeys = listOf(
            "ad_data.currentTimeMs",
            "ad_data.current_time_ms",
            "ad_data.positionMs",
            "ad_data.position_ms"
        )
        millisecondKeys.firstNotNullOfOrNull { key ->
            fields[key]?.toDoubleOrNull()?.takeIf { it >= 0.0 }?.toLong()
        }?.let { return it }
        val secondKeys = listOf(
            "ad_data.currentTime",
            "ad_data.current_time",
            "ad_data.position"
        )
        secondKeys.firstNotNullOfOrNull { key ->
            fields[key]?.toDoubleOrNull()?.takeIf { it >= 0.0 }
        }?.let { return (it * 1_000.0).toLong() }
        val progressJson = fields["ad_data.adProgressData"] ?: return -1L
        return runCatching {
            val json = JsonParser.parseString(progressJson).asJsonObject
            val currentTime = json["currentTime"]?.asDouble ?: return@runCatching -1L
            (currentTime * 1_000.0).toLong()
        }.getOrDefault(-1L)
    }
}
