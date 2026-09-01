package com.smart.android.ad_app

import org.junit.Assert.assertEquals
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
}
