package com.smart.android.ad_app

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AdPlaybackAvailabilityTest {

    @After
    fun tearDown() {
        invokeStatic("resetForTest")
    }

    @Test
    fun `jx starts denied until plugin explicitly allows playback`() {
        assertFalse(isPlaybackAllowed("ad_ytx01_jx"))
        assertTrue(updateStatus(canPlayAd = true, reason = "foreground_app_allowed:launcher", eventTime = 100L))
        assertTrue(isPlaybackAllowed("ad_ytx01_jx"))
    }

    @Test
    fun `all non jx flavors bypass the external playback gate`() {
        assertTrue(isPlaybackAllowed("ad_ytx01"))
        assertTrue(isPlaybackAllowed("ad_ytx01_sxk"))
        assertTrue(isPlaybackAllowed("hq008"))
    }

    @Test
    fun `older plugin status cannot overwrite a newer decision`() {
        assertTrue(updateStatus(canPlayAd = true, reason = "allowed", eventTime = 200L))
        assertFalse(updateStatus(canPlayAd = false, reason = "stale_denied", eventTime = 100L))
        assertTrue(isPlaybackAllowed("ad_ytx01_jx"))
    }

    private fun isPlaybackAllowed(flavor: String): Boolean {
        return invokeStatic(
            "isPlaybackAllowed",
            arrayOf(String::class.java),
            arrayOf(flavor)
        ) as Boolean
    }

    private fun updateStatus(canPlayAd: Boolean, reason: String, eventTime: Long): Boolean {
        return invokeStatic(
            "updateStatus",
            arrayOf(
                Boolean::class.javaPrimitiveType!!,
                String::class.java,
                Long::class.javaPrimitiveType!!
            ),
            arrayOf(canPlayAd, reason, eventTime)
        ) as Boolean
    }

    private fun invokeStatic(
        name: String,
        parameterTypes: Array<Class<*>> = emptyArray(),
        args: Array<Any> = emptyArray()
    ): Any? {
        val type = runCatching {
            Class.forName("com.smart.android.ad_app.AdPlaybackAvailability")
        }.getOrElse { error ->
            fail("缺少极鑫广告播放状态控制: ${error.message}")
            throw AssertionError(error)
        }
        return type.getMethod(name, *parameterTypes).invoke(null, *args)
    }
}
