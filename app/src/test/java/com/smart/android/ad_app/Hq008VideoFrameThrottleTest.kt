package com.smart.android.ad_app

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class Hq008VideoFrameThrottleTest {
    private val renderer = Any()

    @After
    fun tearDown() {
        invokeResetIfPresent()
    }

    @Test
    fun `sxk channel renders about twelve frames from a thirty fps second`() {
        invokeResetIfPresent()

        val renderedFrames = (0 until 30).count { frame ->
            shouldRender("ad_ytx01_sxk", renderer, frame * 33_333L)
        }

        assertEquals(12, renderedFrames)
    }

    @Test
    fun `other TCL flavors render every frame`() {
        invokeResetIfPresent()

        assertTrue(shouldRender("ad_ytx01", renderer, 0L))
        assertTrue(shouldRender("ad_ytx01", renderer, 33_333L))
        assertTrue(shouldRender("hq008", renderer, 66_666L))
        assertTrue(shouldRender("tcl_aishang", renderer, 99_999L))
    }

    @Test
    fun `new timeline renders its first frame immediately`() {
        invokeResetIfPresent()

        assertTrue(shouldRender("ad_ytx01_sxk", renderer, 0L))
        assertFalse(shouldRender("ad_ytx01_sxk", renderer, 33_333L))
        assertTrue(shouldRender("ad_ytx01_sxk", renderer, 99_999L))
        assertTrue(shouldRender("ad_ytx01_sxk", renderer, 0L))
    }

    @Test
    fun `preloaded renderer keeps an independent first frame`() {
        invokeResetIfPresent()
        val currentRenderer = Any()
        val preloadedRenderer = Any()

        assertTrue(shouldRender("ad_ytx01_sxk", currentRenderer, 0L))
        assertFalse(shouldRender("ad_ytx01_sxk", currentRenderer, 33_333L))
        assertTrue(shouldRender("ad_ytx01_sxk", preloadedRenderer, 33_333L))
    }

    private fun shouldRender(
        flavor: String,
        renderer: Any,
        presentationTimeUs: Long
    ): Boolean {
        val type = throttleClass()
        val method = type.getMethod(
            "shouldRender",
            String::class.java,
            Any::class.java,
            Long::class.javaPrimitiveType
        )
        return method.invoke(null, flavor, renderer, presentationTimeUs) as Boolean
    }

    private fun invokeResetIfPresent() {
        runCatching {
            throttleClass().getMethod("resetForTest").invoke(null)
        }
    }

    private fun throttleClass(): Class<*> {
        return runCatching {
            Class.forName("com.smart.android.ad_app.Hq008VideoFrameThrottle")
        }.getOrElse { error ->
            fail("缺少通用视频输出帧率节流策略: ${error.message}")
            throw AssertionError(error)
        }
    }
}
