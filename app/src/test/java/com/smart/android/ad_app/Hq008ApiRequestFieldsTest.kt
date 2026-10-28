package com.smart.android.ad_app

import org.junit.Assert.*
import org.junit.Test

class Hq008ApiRequestFieldsTest {
    @Test fun `Google channel types send host and execution SDK versions`() {
        for (channelType in listOf(BuildFlavor.CHANNEL_TYPE_GOOGLE)) {
            val fields = Hq008ApiRequestFields.fields(channelType, "host-version") { "actual-android-id" }
            assertEquals("actual-android-id", fields["androidid"])
            assertEquals("host-version", fields["ad_version_name"])
            assertEquals("2.8.02", fields["sdk_version"])
            assertFalse(fields.containsKey("rdid"))
            assertFalse(fields.containsKey("android_id"))
        }
    }

    @Test fun `all TCL flavors send the host version and actual TCL SDK version`() {
        for (channelType in listOf(BuildFlavor.CHANNEL_TYPE_TCL)) {
            assertEquals(channelType, mapOf(
                "androidid" to "actual-android-id",
                "ad_version_name" to "1.0.4",
                "sdk_version" to "2.8.02"
            ), Hq008ApiRequestFields.fields(channelType, "1.0.4") { "actual-android-id" })
        }
    }

    @Test fun `unavailable android ID is sent as an empty string`() {
        for (channelType in listOf(BuildFlavor.CHANNEL_TYPE_GOOGLE, BuildFlavor.CHANNEL_TYPE_TCL)) {
            assertEquals("", Hq008ApiRequestFields.fields(channelType, "v") { null }["androidid"])
            assertEquals("", Hq008ApiRequestFields.fields(channelType, "v") {
                throw SecurityException("unavailable")
            }["androidid"])
        }
    }

    @Test fun `LSAP and unrelated flavors do not claim the TCL SDK version`() {
        for (channelType in listOf(BuildFlavor.CHANNEL_TYPE_LSAP, "OTHER")) {
            assertEquals(channelType, mapOf("androidid" to "actual-id"),
                Hq008ApiRequestFields.fields(channelType, "v") { "actual-id" })
        }
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
