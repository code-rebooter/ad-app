package com.smart.android.ad_app.google

import com.smart.android.ad_app.Hq008ApiRequestFields
import org.junit.Assert.*
import org.junit.Test

class Hq008ApiRequestFieldsTest {
    @Test fun `all Google flavors send real host version and the current execution SDK version`() {
        for (flavor in listOf("google_ad_tv_desktop_v260904_1", "google_ad_tv_desktop_v260904_x98k",
            "google_ad_tv_desktop_localcalendar", "google_ad_tv_desktop_kaneshort",
            "google_ad_tv_desktop", "google_ad_tv_desktop_jm", "google_ad_tv_desktop_ytx",
            "google_ad_tv_desktop_007", "google_ad_tv_desktop_tpmaotai1935",
            "google_ad_tv_lockscreen", "google_ad_tv_lockscreen_hq002", "google_ad_tv_desktop_tcl_test")) {
            val fields = Hq008ApiRequestFields.fields(flavor, "host-version") { "actual-android-id" }
            assertEquals("actual-android-id", fields["androidid"])
            assertEquals("host-version", fields["ad_version_name"])
            assertEquals("2.8.02", fields["sdk_version"])
            assertFalse(fields.containsKey("rdid"))
            assertFalse(fields.containsKey("android_id"))
        }
    }

    @Test fun `unavailable android ID is sent as an empty string`() {
        assertEquals("", Hq008ApiRequestFields.fields("google_ad_tv_desktop_v260904_1", "v") { null }["androidid"])
        assertEquals("", Hq008ApiRequestFields.fields("google_ad_tv_desktop_v260904_1", "v") {
            throw SecurityException("unavailable")
        }["androidid"])
    }

    @Test fun `other app flavors gain androidid without claiming the Google TCL SDK identity`() {
        assertEquals(mapOf("androidid" to "actual-id"), Hq008ApiRequestFields.fields("haier_lsap", "v") { "actual-id" })
    }

    @Test fun `existing local UID is reused without replacing or saving it`() {
        val result = Hq008ApiRequestFields.persistedRdid(
            read = { "stored-uid" }, save = { fail("must not rewrite") }, create = { error("must not regenerate") }
        )
        assertEquals("stored-uid", result)
    }

    @Test fun `a missing UID is saved once and reused by the next reader`() {
        var stored: String? = null
        var writes = 0
        val first = Hq008ApiRequestFields.persistedRdid(
            read = { stored }, save = { stored = it; writes++ }, create = { "new-test-uid" }
        )
        val second = Hq008ApiRequestFields.persistedRdid(
            read = { stored }, save = { writes++ }, create = { error("must reuse") }
        )
        assertEquals(first, second)
        assertEquals(1, writes)
    }
}
