package com.smart.android.ad_app

import org.junit.Assert.*
import org.junit.Test

class Hq008PlaybackRequestOwnerTest {
    @Test fun `cancelling before queued start prevents playback`() {
        val owner = Hq008PlaybackRequestOwner()
        val request = owner.begin(1_000L, 180_000L)
        var starts = 0
        val queuedStart = { if (owner.isActive(request)) starts++ }
        assertTrue(owner.finish(request, 1_100L))
        queuedStart()
        assertEquals(0, starts)
    }

    @Test fun `late old callback cannot clear new request or its deadline`() {
        val owner = Hq008PlaybackRequestOwner()
        val first = owner.begin(1_000L, 5_000L)
        val next = owner.begin(2_000L, 180_000L)
        assertFalse(owner.isActive(first))
        assertFalse(owner.finish(first, 3_000L))
        assertTrue(owner.isActive(next))
        assertEquals(179_000L, next.remainingMs(3_000L))
        assertTrue(owner.finish(next, 4_000L))
        assertFalse(owner.finish(next, 5_000L))
        assertEquals(4_000L, next.finishedAtMs)
    }

    @Test fun `delayed load callback cannot start playback after deadline while timeout message is still queued`() {
        val owner = Hq008PlaybackRequestOwner()
        val request = owner.begin(1_000L, 180_000L)
        var starts = 0
        val queuedLoadedCallback = { if (owner.canContinue(request, 181_001L)) starts++ }
        assertTrue(owner.isActive(request))
        queuedLoadedCallback()
        assertEquals(0, starts)
        assertTrue(owner.finish(request, 181_001L))
    }

    @Test fun `initialization and queued start consume the original total timeout`() {
        val owner = Hq008PlaybackRequestOwner()
        val request = owner.begin(1_000L, 180_000L)
        assertEquals(60_000L, request.remainingMs(121_000L))
        assertEquals(0L, request.remainingMs(181_000L))
        assertEquals(0L, request.remainingMs(999_000L))
        assertTrue(owner.finish(request, 181_000L))
        assertFalse(owner.isActive(request))
        val retry = owner.begin(182_000L, 180_000L)
        assertTrue(owner.isActive(retry))
        assertEquals(180_000L, retry.remainingMs(182_000L))
    }
}
