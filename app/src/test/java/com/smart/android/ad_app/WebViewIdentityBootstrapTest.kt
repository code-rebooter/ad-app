package com.smart.android.ad_app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebViewIdentityBootstrapTest {
    @Test
    fun `bootstrap corrects script-visible ua and high entropy firmware fields`() {
        val script = WebViewIdentityBootstrap.script(
            "Dalvik/2.1.0 (Linux; U; Android 10; X96_NEXT Build/QP1A.191105.004)",
            "X96_NEXT",
            "10",
            29
        )

        assertTrue(script.contains("navigator.userAgentData"))
        assertTrue(script.contains("getHighEntropyValues"))
        assertTrue(script.contains("corrected.model = fixedModel"))
        assertTrue(script.contains("corrected.platformVersion = fixedPlatformVersion"))
        assertTrue(script.contains("\"X96_NEXT\""))
        assertTrue(script.contains("\"10.0.0\""))
        assertFalse(script.contains("mstar"))
    }

    @Test
    fun `bootstrap string values cannot terminate the injected script element`() {
        val script = WebViewIdentityBootstrap.script(
            "ua</script><script>bad()",
            "model<&>",
            "10",
            29
        )

        assertFalse(script.contains("</script>"))
        assertTrue(script.contains("\\u003c/script\\u003e"))
        assertTrue(script.contains("model\\u003c\\u0026\\u003e"))
    }

    @Test
    fun `html fallback runs immediately after doctype and is idempotent`() {
        val html = "<!doctype html><html><head><script>remote()</script></head></html>"
        val injected = WebViewIdentityBootstrap.prependToHtml(html, "bootstrap()")

        assertEquals(
            "<!doctype html><script data-hq008-firmware-identity=\"1\">bootstrap()</script>" +
                "<html><head><script>remote()</script></head></html>",
            injected
        )
        assertEquals(injected, WebViewIdentityBootstrap.prependToHtml(injected, "bootstrap()"))
        assertTrue(injected.indexOf("bootstrap()") < injected.indexOf("remote()"))
    }
}
