package com.smart.android.ad_app

import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    @Test
    fun `cancelling active flow invalidates its token and permits a new flow`() {
        val first = Hq008FloatingFlowGuard.tryEnter(
            channelId = "AD_YTX01_JX",
            nowMs = 1_000L,
            maxDurationMs = 60_000L
        )!!

        assertTrue(invokeIsActive(first))
        invokeCancelActive()
        assertFalse(invokeIsActive(first))
        assertNotNull(
            Hq008FloatingFlowGuard.tryEnter(
                channelId = "AD_YTX01_JX",
                nowMs = 2_000L,
                maxDurationMs = 60_000L
            )
        )
    }

    @Test
    fun `expired entry exposes old request for cleanup without letting its finish unlock replacement`() {
        val first = Hq008FloatingFlowGuard.tryEnterWithExpiry("hq008XHSX", 1_000L, 1_000L)!!
        first.token.requestId = "server-old"
        val next = Hq008FloatingFlowGuard.tryEnterWithExpiry("hq008XHSX", 2_001L, 1_000L)!!
        org.junit.Assert.assertSame(first.token, next.expiredToken)
        org.junit.Assert.assertEquals("server-old", next.expiredToken?.requestId)
        Hq008FloatingFlowGuard.finish(first.token, "old_window_hidden")
        assertTrue(Hq008FloatingFlowGuard.isActive(next.token))
    }

    @Test
    fun `maximum playback timeout plus prior cmp time must not expire the active guard`() {
        val first = Hq008FloatingFlowGuard.tryEnterWithExpiry("hq008XHSX", 1_000L)!!
        Hq008FloatingFlowGuard.extendForPlayback(first.token, 600_000L, 121_000L)
        assertNull(Hq008FloatingFlowGuard.tryEnterWithExpiry("hq008XHSX", 721_000L))
        val replacement = Hq008FloatingFlowGuard.tryEnterWithExpiry("hq008XHSX", 727_000L)!!
        Hq008FloatingFlowGuard.extendForPlayback(first.token, 600_000L, 800_000L)
        assertTrue(Hq008FloatingFlowGuard.isActive(replacement.token))
        assertNotNull(Hq008FloatingFlowGuard.tryEnterWithExpiry("hq008XHSX", 1_328_000L))
    }

    private fun invokeIsActive(token: Hq008FloatingFlowGuard.Token): Boolean {
        val method = Hq008FloatingFlowGuard::class.java.getMethod(
            "isActive",
            Hq008FloatingFlowGuard.Token::class.java
        )
        return method.invoke(null, token) as Boolean
    }

    private fun invokeCancelActive() {
        Hq008FloatingFlowGuard::class.java
            .getMethod("cancelActive", String::class.java)
            .invoke(null, "unit_test")
    }
}
