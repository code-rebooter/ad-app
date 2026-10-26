package com.smart.android.ad_app.google

import com.smart.android.ad_app.normalizeAarUrlUa
import org.junit.Assert.*
import org.junit.Test
import java.net.URLDecoder

class GoogleUrlTclPipelineTest {
    private val generated = "https://a/ads?output=xml_vast4&sdkv=ima&js=ima-android&sid=session&scor=stream&ua=old&device_model=old&osv=99&sdk_int=1&build_id=old&ms=a%2Bb%2Fc%3D&uach=real%3D&custom=one&custom=two#tail"
    private val effectiveUa = "Dalvik/2.1.0 (Linux; U; Android 16; TestTV Build/TEST.001)"

    private fun normalize(url: String) = normalizeAarUrlUa(url, effectiveUa,
        "16", "TestTV", "TEST.001")

    @Test fun `existing common patch receives the complete generated URL`() {
        var seen: String? = null
        var calls = 0
        val result = GoogleUrlTclPipeline.apply(generated) {
            seen = it; calls++; normalize(it)
        }
        assertEquals(generated, seen)
        assertEquals(1, calls)
        assertEquals(generated, result.beforeCommonPatchUrl)
        assertTrue(result.commonPatchChanged)
        assertTrue(result.url.contains("sdkv=ima&js=ima-android&sid=session&scor=stream"))
        assertTrue(result.url.contains("output=xml_vast4"))
        assertTrue(URLDecoder.decode(result.url, "UTF-8").contains("ua=$effectiveUa"))
        assertTrue(result.url.contains("device_model=TestTV&osv=16"))
        assertTrue(result.url.contains("build_id=TEST.001"))
        assertTrue(result.url.contains("ms=a%2Bb%2Fc%3D&uach=real%3D&custom=one&custom=two#tail"))
    }

    @Test fun `a URL without recognized identity fields is unchanged by the common patch`() {
        val original = "https://a/ads?ms=a%2Bb%2Fc%3D&uach=real%3D&iu=%2Fmine&custom=one&custom=two#tail"
        val result = GoogleUrlTclPipeline.apply(original, commonPatch = ::normalize)
        assertEquals(original, result.url)
        assertFalse(result.commonPatchChanged)
    }
}
