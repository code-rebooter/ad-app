package com.smart.android.tclad.ima

import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class GoogleImaAuthorizationTest {
    @Test fun `only explicit numeric or string zero enables muted reporting`() {
        for (value in listOf("0", "\"0\"", "\" 0 \"")) {
            assertFalse(GoogleGamAdConfigParser.reportedSoundEnabled(JsonParser.parseString("{\"reported_sound_mode\":$value}")))
        }
        for (value in listOf("1", "-1", "false", "true", "null", "{}", "[]", "\"invalid\"", "\"\"", "0.0")) {
            assertTrue(value, GoogleGamAdConfigParser.reportedSoundEnabled(JsonParser.parseString("{\"reported_sound_mode\":$value}")))
        }
        assertTrue(GoogleGamAdConfigParser.reportedSoundEnabled(null))
    }

    @Test fun `each authorization owns its tag flags and long startup timeout`() {
        val first = GoogleGamAdConfigParser.parse(JsonParser.parseString("""{
          "ad_tag_url":"https://ads.test/tag?x=1&x=2#tail", "reported_sound_mode":0,
          "enable_fake_ctr":1, "ad_load_timeout_ms":1234, "ad_startup_timeout_ms":3000000000
        }"""))!!
        assertEquals("https://ads.test/tag?x=1&x=2#tail", first.adTagUrl)
        assertFalse(first.reportedSoundEnabled)
        assertTrue(first.enableFakeCtr)
        assertEquals(1234, first.adLoadTimeoutMs)
        assertEquals(3_000_000_000L, first.adStartupTimeoutMs)
        assertNull(GoogleGamAdConfigParser.parse(null))
        assertNull(GoogleGamAdConfigParser.parse(JsonParser.parseString("{\"ad_tag_url\":\" \"}")))
        val next = GoogleGamAdConfigParser.parse(JsonParser.parseString("{\"ad_tag_url\":\"https://ads.test/next\"}"))!!
        assertTrue(next.reportedSoundEnabled)
        assertFalse(next.enableFakeCtr)
        assertEquals(20_000, next.adLoadTimeoutMs)
        assertEquals(35_000L, next.adStartupTimeoutMs)
    }

    @Test fun `sound correction preserves signed query bytes duplicates order and fragments`() {
        val url = "https://ads.test/tag?x=one&VPMUTE=1&ms=a%2Bb%2Fc%3D&x=two&vpmute=1#route?vpmute=1"
        assertEquals("https://ads.test/tag?x=one&vpmute=0&ms=a%2Bb%2Fc%3D&x=two&vpmute=0#route?vpmute=1",
            GoogleReportedSoundMode.applyToUrl(url, true))
        assertEquals("https://ads.test/tag?x=1&vpmute=1#tail",
            GoogleReportedSoundMode.applyToUrl("https://ads.test/tag?x=1#tail", false))
    }

    @Test fun `terminal audit callback retains large XML and delivers once`() {
        val summaries = mutableListOf<String>()
        val reporter = GoogleImaRuntimeAuditReporter("audit-callback", summaries::add)
        reporter.record(com.smart.android.googleima.GoogleImaAuditEvent("vast_response", 10L,
            mapOf("capture_source" to "native_xhr", "request_url" to "https://ads.test/tag"),
            "<VAST version=\"4.0\"><Ad><InLine><Creatives><Creative><Linear><MediaFiles>" +
                "<MediaFile type=\"video/mp4\">https://cdn.test/video.mp4</MediaFile>" +
                "</MediaFiles></Linear></Creative></Creatives><Extensions>" + "x".repeat(300_000) +
                "</Extensions></InLine></Ad></VAST>"))
        reporter.finish("completed")
        reporter.finish("cancelled")
        assertEquals(1, summaries.size)
        val summary = JsonParser.parseString(summaries.single()).asJsonObject
        assertEquals("completed", summary["terminal_reason"].asString)
        assertEquals(1, summary["vast_artifacts"].asJsonArray.size())
        assertTrue(summary["vast_artifacts"].asJsonArray[0].asJsonObject["original_length"].asInt > 300_000)
    }
}
