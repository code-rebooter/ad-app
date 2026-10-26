package com.smart.android.ad_app.google

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.smart.android.ad_app.Hq008AuthorizeResponseData
import org.junit.Assert.*
import org.junit.Test

class GoogleAuthorizedGamConfigTest {
    private val gson = Gson()

    private fun authorization(json: String) = gson.fromJson(json, Hq008AuthorizeResponseData::class.java)

    @Test fun `nested authorization carries the tag and timeouts`() {
        val response = authorization("""{"authorized":true,"request_id":"request-1","gam_config":{
            "ad_tag_url":"https://example.test/ads?iu=%2Fa&cust_params=x%3D1%26y%3D2",
            "ad_load_timeout_ms":12345,"ad_startup_timeout_ms":23456,"ima_tcl_url_patch":1}}""")
        val config = GoogleGamAdConfigParser.parse(response.gamConfigForPlayback())!!
        assertEquals("request-1", response.request_id)
        assertEquals("https://example.test/ads?iu=%2Fa&cust_params=x%3D1%26y%3D2", config.adTagUrl)
        assertEquals(12345, config.adLoadTimeoutMs)
        assertEquals(23456L, config.adStartupTimeoutMs)
    }

    @Test fun `nested config owns all fields without mixing flat old values`() {
        val response = authorization("""{"gam_config":{"ad_tag_url":"https://nested.test/ads"},
            "ad_tag_url":"https://flat.test/ads","ad_load_timeout_ms":123,"ima_tcl_url_patch":1}""")
        val config = GoogleGamAdConfigParser.parse(response.gamConfigForPlayback())!!
        assertEquals("https://nested.test/ads", config.adTagUrl)
        assertEquals(GoogleAdTvDesktopVastConfig.DEFAULT_AD_LOAD_TIMEOUT_MS, config.adLoadTimeoutMs)
    }

    @Test fun `previous flat authorize format remains readable`() {
        val response = authorization("""{"ad_tag_url":"https://flat.test/ads",
            "ad_load_timeout_ms":111,"ad_startup_timeout_ms":222,"ima_tcl_url_patch":"1"}""")
        val config = GoogleGamAdConfigParser.parse(response.gamConfigForPlayback())!!
        assertEquals("https://flat.test/ads", config.adTagUrl)
        assertEquals(111, config.adLoadTimeoutMs)
        assertEquals(222L, config.adStartupTimeoutMs)
    }

    @Test fun `explicit null malformed or empty nested config never falls back to flat tag`() {
        for (nested in listOf("null", "[]", "1", "{}", """{"ad_tag_url":null}""", """{"ad_tag_url":" "}""")) {
            val response = authorization("""{"gam_config":$nested,"ad_tag_url":"https://stale.test/ads"}""")
            assertNull(nested, GoogleGamAdConfigParser.parse(response.gamConfigForPlayback()))
        }
    }

    @Test fun `missing config skips this request without retaining the previous one`() {
        val first = authorization("""{"gam_config":{"ad_tag_url":"https://first.test/ads","ima_tcl_url_patch":1}}""")
        assertEquals("https://first.test/ads", GoogleGamAdConfigParser.parse(first.gamConfigForPlayback())!!.adTagUrl)
        val missing = authorization("""{"authorized":true}""")
        assertNull(GoogleGamAdConfigParser.parse(missing.gamConfigForPlayback()))
        val next = authorization("""{"gam_config":{"ad_tag_url":"https://next.test/ads","ima_tcl_url_patch":0}}""")
        val nextConfig = GoogleGamAdConfigParser.parse(next.gamConfigForPlayback())!!
        assertEquals("https://next.test/ads", nextConfig.adTagUrl)
    }

    @Test fun `authorization channel is a local binding rather than a server supplied field`() {
        val parsed = authorization("""{"authorized":true,"requestChannelId":"untrusted"}""")
        assertNull(parsed.requestChannelId)
        val bound = parsed.copy(requestChannelId = "actual-authorize-channel")
        assertEquals("actual-authorize-channel", bound.requestChannelId)
        assertFalse(gson.toJson(bound).contains("actual-authorize-channel"))
    }

    @Test fun `retired switch values are ignored`() {
        val configurations = listOf("0", "1", "true", "{}", "[]").map { value ->
            GoogleGamAdConfigParser.parse(JsonParser.parseString("""{"ad_tag_url":"https://example.test/ads","ima_tcl_url_patch":$value}"""))
        }
        assertEquals(1, configurations.distinct().size)
    }

    @Test fun `nonpositive timeouts use the existing defaults`() {
        val response = authorization("""{"gam_config":{"ad_tag_url":"https://example.test/ads",
            "ad_load_timeout_ms":0,"ad_startup_timeout_ms":-1}}""")
        val config = GoogleGamAdConfigParser.parse(response.gamConfigForPlayback())!!
        assertEquals(GoogleAdTvDesktopVastConfig.DEFAULT_AD_LOAD_TIMEOUT_MS, config.adLoadTimeoutMs)
        assertEquals(GoogleAdTvDesktopVastConfig.DEFAULT_AD_STARTUP_TIMEOUT_MS, config.adStartupTimeoutMs)
    }

    @Test fun `invalid nested field types do not escape as authorization failures`() {
        for (body in listOf("""{"ad_tag_url":{}}""", """{"ad_tag_url":"https://example.test/ads","ad_load_timeout_ms":{}}""")) {
            val response = authorization("""{"authorized":true,"gam_config":$body}""")
            assertTrue(response.authorized)
            assertNull(GoogleGamAdConfigParser.parse(response.gamConfigForPlayback()))
        }
    }

    @Test fun `each playback payload is a separate copy of its own authorization`() {
        val response = authorization("""{"gam_config":{"ad_tag_url":"https://example.test/ads","ima_tcl_url_patch":0}}""")
        val first = response.gamConfigForPlayback()!!.asJsonObject
        first.add("ad_tag_url", JsonParser.parseString("\"https://changed.test/ads\""))
        assertEquals("https://example.test/ads", GoogleGamAdConfigParser.parse(response.gamConfigForPlayback())!!.adTagUrl)
    }
}
