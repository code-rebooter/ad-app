package com.smart.android.ad_app.google

import com.google.gson.Gson
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class GoogleImaTclSwitchTest {
    @Test fun `numeric string and boolean forms decode through the real response model`() {
        val gson = Gson()
        for (raw in listOf("1", "1.0", "\"1\"", "true", "\"true\"")) {
            val response = gson.fromJson("""{"ad_tag_url":"https://a/ads","ima_tcl_url_patch":$raw}""",
                GoogleGamAdConfigResponseData::class.java)
            assertTrue(raw, response.imaTclUrlPatchEnabled)
            assertEquals("https://a/ads", response.ad_tag_url)
        }
        for (raw in listOf("0", "\"0\"", "false", "\"false\"", "null", "2", "-1", "\"unknown\"", "{}", "[]")) {
            val response = gson.fromJson("""{"ad_tag_url":"https://a/ads","ima_tcl_url_patch":$raw}""",
                GoogleGamAdConfigResponseData::class.java)
            assertFalse(raw, response.imaTclUrlPatchEnabled)
            assertEquals("https://a/ads", response.ad_tag_url)
        }
    }

    @Test fun `missing optional field defaults to off for the existing backend`() {
        val response = Gson().fromJson("""{"ad_tag_url":"https://a/ads"}""",
            GoogleGamAdConfigResponseData::class.java)
        assertFalse(response.imaTclUrlPatchEnabled)
        assertFalse(GoogleImaTclSwitch.enabled(null))
    }

    @Test fun `a later response switches back without sharing previous state`() {
        assertTrue(GoogleImaTclSwitch.enabled(JsonParser.parseString("1")))
        assertFalse(GoogleImaTclSwitch.enabled(JsonParser.parseString("0")))
        assertTrue(GoogleImaTclSwitch.enabled(JsonParser.parseString("\"1\"")))
    }
}
