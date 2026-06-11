package com.smart.android.ad_app

import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class Hq008FloatingFlowGuardTest {

    @After
    fun tearDown() {
        Hq008FloatingFlowGuard.resetForTest()
    }

    @Test
    fun `floating flow guard should reject overlapping flow until current token finishes`() {
        val first = Hq008FloatingFlowGuard.tryEnter(
            channelId = "TCL_NONEU",
            nowMs = 1_000L,
            maxDurationMs = 60_000L
        )

        assertNotNull(first)
        assertNull(
            Hq008FloatingFlowGuard.tryEnter(
                channelId = "TCL_NONEU",
                nowMs = 2_000L,
                maxDurationMs = 60_000L
            )
        )

        Hq008FloatingFlowGuard.finish(first!!, "unit_test")

        assertNotNull(
            Hq008FloatingFlowGuard.tryEnter(
                channelId = "TCL_NONEU",
                nowMs = 3_000L,
                maxDurationMs = 60_000L
            )
        )
    }

    @Test
    fun `stale token should not unlock newer flow after timeout replacement`() {
        val first = Hq008FloatingFlowGuard.tryEnter(
            channelId = "TCL_NONEU",
            nowMs = 1_000L,
            maxDurationMs = 1_000L
        )

        val second = Hq008FloatingFlowGuard.tryEnter(
            channelId = "TCL_NONEU",
            nowMs = 2_001L,
            maxDurationMs = 1_000L
        )

        assertNotNull(first)
        assertNotNull(second)

        Hq008FloatingFlowGuard.finish(first!!, "stale_finish")

        assertNull(
            Hq008FloatingFlowGuard.tryEnter(
                channelId = "TCL_NONEU",
                nowMs = 2_100L,
                maxDurationMs = 1_000L
            )
        )
    }
}
