package com.smart.android.ad_app

import org.junit.Assert.*
import org.junit.Test

class TclPlaybackStateTest {
    private fun playing(duration: Long = 300_000L): TclPlaybackState =
        TclPlaybackState(0L, 180_000L).apply {
            select(TclPlaybackState.Metadata("first", adPosition = 1, totalAds = 2), 100L)
            markStarted(100L)
            firstFrame(200L)
            snapshot(200L, 100L, duration, true, false, 0)
        }

    @Test fun `five minute ad passes the old three minute deadline while making progress`() {
        val state = playing()
        for (now in 20_000L..320_000L step 20_000L) {
            state.snapshot(now, now - 100L, 300_000L, true, false, 0)
            assertNull("Unexpected timeout at $now", state.timeoutReason(now))
        }
        assertEquals(480_100L, state.current!!.budget.hardDeadlineMs)
        assertEquals("playback_deadline_exceeded", state.timeoutReason(360_100L))
    }

    @Test fun `duplicates do not renew playback allowance`() {
        val state = playing()
        val deadline = state.current!!.budget.hardDeadlineMs
        state.markStarted(100_000L)
        state.select(TclPlaybackState.Metadata("first", adPosition = 1, totalAds = 2), 100_000L)
        state.firstFrame(100_000L)
        assertEquals(1, state.segments.size)
        assertEquals(deadline, state.current!!.budget.hardDeadlineMs)
        assertEquals(200L, state.current!!.firstFrameAtMs)
    }

    @Test fun `late duration metadata retains already consumed time`() {
        val state = playing(300_000L)
        state.snapshot(60_100L, 60_000L, 120_000L, true, false, 0)
        assertEquals(120_000L, state.current!!.budget.remaining(60_100L))
        assertEquals(300_100L, state.current!!.budget.hardDeadlineMs)
    }

    @Test fun `buffering pauses the soft allowance but cannot move the hard deadline`() {
        val state = playing()
        state.buffering(20_100L)
        assertEquals(340_000L, state.current!!.budget.remaining(50_100L))
        state.playing(50_100L)
        assertEquals(330_000L, state.current!!.budget.remaining(60_100L))
        assertEquals(480_100L, state.current!!.budget.hardDeadlineMs)
        state.buffering(60_100L)
        assertEquals("playback_buffer_timeout", state.timeoutReason(120_100L))
        assertEquals("playback_hard_deadline_exceeded", state.timeoutReason(480_100L))
    }

    @Test fun `next ad gets a fresh allowance and preserves the previous ad evidence`() {
        val state = playing(20_000L)
        state.completeSegment("COMPLETED", 20_100L)
        assertNull(state.timeoutReason(25_100L))
        state.select(TclPlaybackState.Metadata("second", adPosition = 2, totalAds = 2), 25_100L)
        state.firstFrame(25_200L)
        state.snapshot(25_200L, 100L, 300_000L, true, false, 1)
        assertEquals(2, state.segments.size)
        assertEquals("COMPLETED", state.segments.first().terminalEvent)
        assertEquals(505_100L, state.current!!.budget.hardDeadlineMs)
        assertNull(state.timeoutReason(25_300L))
    }

    @Test fun `missing start evidence stalled playback and missing next ad have separate reasons`() {
        val noFrame = TclPlaybackState(0, 180_000).apply {
            select(TclPlaybackState.Metadata("first"), 100)
            markStarted(100)
        }
        assertEquals("playback_start_timeout", noFrame.timeoutReason(30_100))
        val stalled = playing()
        assertEquals("playback_stalled", stalled.timeoutReason(60_200))
        val between = playing().apply { completeSegment("COMPLETED", 20_000) }
        assertEquals("ad_pod_transition_timeout", between.timeoutReason(55_000))
    }

    @Test fun `initialization and patch consume the same request allowance`() {
        val state = TclPlaybackState(1_000, 180_000)
        assertEquals(60_000L, state.remainingRequestMs(121_000))
        assertEquals("request_start_timeout", state.timeoutReason(181_000))
        state.finish(181_000, "request_start_timeout")
        assertNull(state.timeoutReason(999_000))
    }

    @Test fun `final player snapshot cannot reopen completed ad or reset transition deadline`() {
        val state = playing(20_000)
        state.completeSegment("COMPLETED", 20_100)
        val deadline = state.current!!.budget.hardDeadlineMs
        assertFalse(state.select(TclPlaybackState.Metadata("first", adPosition = 1, totalAds = 2), 21_000))
        state.snapshot(21_000, 19_900, 20_000, true, false, 0)
        assertEquals(1, state.segments.size)
        assertEquals("COMPLETED", state.current!!.terminalEvent)
        assertEquals(deadline, state.current!!.budget.hardDeadlineMs)
        assertEquals("ad_pod_transition_timeout", state.timeoutReason(55_100))
    }

    @Test fun `late metadata from a finished creative cannot replace the next ad`() {
        val state = playing(20_000)
        state.completeSegment("COMPLETED", 20_100)
        assertTrue(state.select(TclPlaybackState.Metadata("second", adPosition = 2, totalAds = 2), 21_000))
        assertFalse(state.select(TclPlaybackState.Metadata("first", adPosition = 1, totalAds = 2), 21_100))
        assertEquals(2, state.segments.size)
        assertEquals("second", state.current!!.metadata.key)
    }
}
