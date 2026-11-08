package com.smart.android.ad_app

import org.junit.Assert.assertEquals
import org.junit.Test

class GoogleReportSdkVersionTest {
    @Test fun `common report follows executing engine rather than Google flavor`() {
        assertEquals("google_ima_3.35.1", Hq008AdReporter.sdkVersionFor(mapOf("sdk" to "google_ima_3.35.1")))
        assertEquals("2.8.02", Hq008AdReporter.sdkVersionFor(mapOf("sdk" to "tcl_2.8.02")))
    }
}
