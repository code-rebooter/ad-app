package com.smart.android.ad_app

import org.junit.Assert.assertEquals
import org.junit.Test

class Hq008SdkVersionTest {
    @Test fun `pure IMA reports its executing engine version`() {
        assertEquals("google_ima_3.35.1", Hq008AdReporter.sdkVersionFor(mapOf("sdk" to "google_ima_3.35.1")))
    }

    @Test fun `Google URL to TCL remains a TCL SDK version`() {
        assertEquals("2.8.02", Hq008AdReporter.sdkVersionFor(mapOf(
            "sdk" to "tcl_2.8.02", "urlEngine" to "google", "requestChannelId" to "GOOGLE_AD_TV_DESKTOP")))
        assertEquals("2.8.02", Hq008AdReporter.sdkVersionFor(emptyMap()))
    }
}
