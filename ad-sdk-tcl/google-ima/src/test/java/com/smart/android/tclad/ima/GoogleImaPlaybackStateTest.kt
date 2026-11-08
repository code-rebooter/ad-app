package com.smart.android.tclad.ima

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GoogleImaPlaybackStateTest {
    @Test
    fun `late metadata preserves consumed budget and buffering pause`() {
        val budget = GoogleImaPlaybackBudgetState()
        budget.start(nowMs = 1_000L, totalMs = 60_000L, hardTimeoutMs = 90_000L, paused = false)
        budget.pause(11_000L)

        budget.update(
            nowMs = 20_000L,
            totalMs = 99_000L,
            hardTimeoutMs = 130_000L,
            paused = true
        )

        assertEquals(89_000L, budget.remaining(50_000L))
        assertFalse(budget.isRunning)
        assertEquals(131_000L, budget.hardDeadlineAtMs)
    }

    @Test
    fun `resuming budget continues from stored remainder`() {
        val budget = GoogleImaPlaybackBudgetState()
        budget.start(nowMs = 0L, totalMs = 50_000L, hardTimeoutMs = 80_000L, paused = false)
        budget.pause(10_000L)

        assertTrue(budget.resume(20_000L))
        assertEquals(35_000L, budget.remaining(25_000L))
    }

    @Test
    fun `fixed ima progress is not playback evidence`() {
        val evidence = GoogleImaProgressEvidence()

        assertFalse(evidence.observe("ad-1", 5_000L))
        assertFalse(evidence.observe("ad-1", 5_000L))
        assertTrue(evidence.observe("ad-1", 5_001L))
        assertFalse(evidence.observe("ad-2", 5_001L))
    }

    @Test
    fun `controlled ads manager progress is read before legacy ad data`() {
        assertEquals(
            2_345L,
            GoogleImaProgressFields.positionMs(
                mapOf(
                    "ima_progress_current_time_ms" to "2345",
                    "ad_data.currentTime" to "99.0"
                )
            )
        )
    }
}
