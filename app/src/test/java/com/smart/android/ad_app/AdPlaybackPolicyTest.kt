package com.smart.android.ad_app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdPlaybackPolicyTest {

    @Test
    fun `missing or disabled server timeout should use 180 second fallback`() {
        assertEquals(180_000L, AdPlaybackPolicy.resolveCallbackTimeoutMs(null))
        assertEquals(180_000L, AdPlaybackPolicy.resolveCallbackTimeoutMs(0L))
        assertEquals(180_000L, AdPlaybackPolicy.resolveCallbackTimeoutMs(-1L))
    }

    @Test
    fun `valid server timeout should apply to the current ad request`() {
        assertEquals(240_000L, AdPlaybackPolicy.resolveCallbackTimeoutMs(240L))
    }

    @Test
    fun `out of range server timeout should use fallback`() {
        assertEquals(180_000L, AdPlaybackPolicy.resolveCallbackTimeoutMs(1L))
        assertEquals(180_000L, AdPlaybackPolicy.resolveCallbackTimeoutMs(3_600L))
    }

    @Test
    fun `short ad should finish before a four minute polling interval`() {
        assertEquals(90_000L, AdPlaybackPolicy.resolvePlaybackTimeoutMs(15_000L))
    }

    @Test
    fun `long ad should receive its declared duration plus completion grace`() {
        assertEquals(351_000L, AdPlaybackPolicy.resolvePlaybackTimeoutMs(291_000L))
        assertEquals(631_000L, AdPlaybackPolicy.resolvePlaybackTimeoutMs(571_000L))
    }

    @Test
    fun `unknown and extreme durations should remain bounded`() {
        assertEquals(600_000L, AdPlaybackPolicy.resolvePlaybackTimeoutMs(null))
        assertEquals(900_000L, AdPlaybackPolicy.resolvePlaybackTimeoutMs(2_000_000L))
    }

    @Test
    fun `playback timeout reasons should be classified consistently`() {
        assertTrue(AdPlaybackPolicy.isTimeoutReason("request_start_timeout"))
        assertTrue(AdPlaybackPolicy.isTimeoutReason("playback_buffer_timeout"))
        assertTrue(AdPlaybackPolicy.isTimeoutReason("playback_deadline_exceeded"))
        assertTrue(AdPlaybackPolicy.isTimeoutReason("playback_stalled"))
        assertFalse(AdPlaybackPolicy.isTimeoutReason("ima_playback_failed"))
        assertFalse(AdPlaybackPolicy.isTimeoutReason("ima_ad_skipped"))
    }
}
