package com.smart.android.ad_app

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Test

class HaierAarUaPayloadNormalizerTest {

    private val effectiveUa =
        "Dalvik/2.1.0 (Linux; U; Android 10; X96_NEXT Build/QP1A.190711.019)"

    @Test
    fun `query ua and generic device model are normalized`() {
        val input =
            "https://example.test/ad?ua=bad&device_model=TV%20BOX&creative_model=TV%20BOX"

        val result = normalizeAarUrlUa(input, effectiveUa)

        assertEquals(
            "https://example.test/ad?" +
                "ua=Dalvik%2F2.1.0%20%28Linux%3B%20U%3B%20Android%2010%3B%20X96_NEXT%20Build%2FQP1A.190711.019%29" +
                "&device_model=X96_NEXT&creative_model=TV%20BOX",
            result
        )
    }

    @Test
    fun `json device model fields are normalized without touching specific or unrelated models`() {
        val input =
            """{
                "userAgent":"bad",
                "deviceModel":"TVBOX",
                "specific":{"deviceModel":"TX9 PRO"},
                "device":{"ua":"bad","model":"Smart TV","build":"bad"},
                "content":{"model":"TV BOX"}
            }""".trimIndent()

        val result = JsonParser.parseString(
            normalizeAarPayloadUa(
                raw = input,
                contentType = "application/json",
                effectiveUa = effectiveUa,
                effectiveBuildId = "QP1A.190711.019"
            )
        ).asJsonObject

        assertEquals(effectiveUa, result.get("userAgent").asString)
        assertEquals("X96_NEXT", result.get("deviceModel").asString)
        assertEquals("X96_NEXT", result.getAsJsonObject("specific").get("deviceModel").asString)
        assertEquals(effectiveUa, result.getAsJsonObject("device").get("ua").asString)
        assertEquals("X96_NEXT", result.getAsJsonObject("device").get("model").asString)
        assertEquals("QP1A.190711.019", result.getAsJsonObject("device").get("build").asString)
        assertEquals("TV BOX", result.getAsJsonObject("content").get("model").asString)
    }
}
