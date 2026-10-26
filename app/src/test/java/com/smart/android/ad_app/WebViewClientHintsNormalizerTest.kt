package com.smart.android.ad_app

import org.junit.Assert.*
import org.junit.Test

class WebViewClientHintsNormalizerTest {
    private fun normalize(raw: Map<String, Any?>?, release: String = "10", sdk: Int = 29) =
        WebViewClientHintsNormalizer.normalize(raw, "X96_NEXT", release, sdk)

    @Test fun `wrong firmware version and model use the same native values`() {
        val raw = linkedMapOf<String, Any?>("platform" to "Android", "platformVersion" to "15.0.0",
            "model" to "mstar", "uaFullVersion" to "128.0.6613.99", "architecture" to "arm",
            "bitness" to "32", "mobile" to false, "wow64" to false,
            "fullVersionList" to listOf(mapOf("brand" to "Android WebView", "version" to "128.0.6613.99")))
        val corrected = normalize(raw)!!
        assertEquals("10.0.0", corrected["platformVersion"])
        assertEquals("X96_NEXT", corrected["model"])
        for (key in raw.keys - setOf("model", "platformVersion")) assertEquals(key, raw[key], corrected[key])
        assertEquals("15.0.0", raw["platformVersion"])
        assertEquals("mstar", raw["model"])
        assertEquals(corrected, normalize(corrected))
    }

    @Test fun `absent empty failed and non android metadata do not acquire a version`() {
        assertNull(normalize(null))
        for (raw in listOf(emptyMap(), mapOf("platform" to "Android"),
            mapOf("platform" to "Android", "platformVersion" to ""),
            mapOf("platform" to "Linux", "platformVersion" to "15.0.0"),
            mapOf("platform" to "Android", "platformVersion" to 15))) {
            assertEquals(raw, normalize(raw))
        }
    }

    @Test fun `equivalent official version spelling and browser versions remain intact`() {
        val cases = listOf(Triple("14", 34, "14.0.0"), Triple("6.0.1", 23, "6.0.1"),
            Triple("8.0", 26, "8.0.0"), Triple("12L", 32, "12.0.0"))
        for ((release, sdk, observed) in cases) {
            val raw = mapOf<String, Any?>("platform" to "Android", "platformVersion" to observed,
                "uaFullVersion" to "128.0.6613.99")
            assertEquals(raw, normalize(raw, release, sdk))
        }
    }

    @Test fun `different supported patch release still aligns to the actual native source`() {
        val raw = mapOf<String, Any?>("platform" to "Android", "platformVersion" to "6.0.1")
        assertEquals("6.0.0", normalize(raw, "6.0", 23)!!["platformVersion"])
        assertEquals(raw, normalize(raw, "unknown", 22))
    }

    @Test fun `document start bootstrap uses the same canonical platform version`() {
        assertEquals("10.0.0", WebViewClientHintsNormalizer.canonicalPlatformVersion("10", 29))
        assertEquals("6.0.1", WebViewClientHintsNormalizer.canonicalPlatformVersion("6.0.1", 23))
        assertEquals("12.0.0", WebViewClientHintsNormalizer.canonicalPlatformVersion("12L", 32))
        assertNull(WebViewClientHintsNormalizer.canonicalPlatformVersion("unknown", 22))
    }
}
