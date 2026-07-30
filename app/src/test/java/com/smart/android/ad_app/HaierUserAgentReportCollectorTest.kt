package com.smart.android.ad_app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HaierUserAgentReportCollectorTest {

    @Test
    fun `collector reports original effective and webview user agents`() {
        val installedResult = HaierUaNormalizationResult(
            originalUa = "original-system-ua",
            effectiveUa = "installed-effective-ua",
            changed = true,
            reason = HaierUaNormalizationReason.REPLACED_UNPARSEABLE
        )
        val collector = HaierUserAgentReportCollector()

        val report = collector.collect(
            installedResult = installedResult,
            currentHttpAgent = "current-effective-ua",
            webViewUserAgentProvider = { "webview-default-ua" }
        )

        assertEquals("original-system-ua", report.originalUa)
        assertEquals("current-effective-ua", report.effectiveUa)
        assertEquals("webview-default-ua", report.webViewUa)
    }

    @Test
    fun `collector falls back to current user agent when installer has no snapshot`() {
        val collector = HaierUserAgentReportCollector()

        val report = collector.collect(
            installedResult = null,
            currentHttpAgent = "current-system-ua",
            webViewUserAgentProvider = { "webview-default-ua" }
        )

        assertEquals("current-system-ua", report.originalUa)
        assertEquals("current-system-ua", report.effectiveUa)
    }

    @Test
    fun `collector refreshes webview user agent for every collection`() {
        var collectionCount = 0
        val collector = HaierUserAgentReportCollector()
        val provider = {
            collectionCount += 1
            "webview-ua-$collectionCount"
        }

        val first = collector.collect(null, "system-ua", provider)
        val second = collector.collect(null, "system-ua", provider)

        assertEquals("webview-ua-1", first.webViewUa)
        assertEquals("webview-ua-2", second.webViewUa)
        assertEquals(2, collectionCount)
    }

    @Test
    fun `collector returns empty webview user agent when provider fails`() {
        val collector = HaierUserAgentReportCollector()

        val report = collector.collect(
            installedResult = null,
            currentHttpAgent = "system-ua",
            webViewUserAgentProvider = { error("WebView unavailable") }
        )

        assertEquals("", report.webViewUa)
    }

    @Test
    fun `authorize fields wrap user agent diagnostics in ext for supported channels`() {
        val fields = HaierUserAgentAuthorizeFields.build(
            flavor = "haier_lsap",
            fallbackEffectiveUa = "fallback"
        ) {
            HaierUserAgentReport(
                originalUa = "original",
                effectiveUa = "effective",
                webViewUa = "webview",
                observedUa = "observed",
                aarCachedUa = "cached",
                aarEffectiveUa = "aar-effective",
                driftDetected = true,
                aarDriftDetected = true,
                repaired = true,
                checkedAtMs = 123L
            )
        }

        val ext = fields["ext"] as? Map<*, *> ?: error("supported channel should send diagnostics in ext")
        assertEquals(
            linkedMapOf(
                "ua_original" to "original",
                "ua_effective" to "effective",
                "webview_ua" to "webview",
                "ua_observed" to "observed",
                "ua_aar_cached" to "cached",
                "ua_aar_effective" to "aar-effective",
                "ua_drift_detected" to true,
                "ua_aar_drift_detected" to true,
                "ua_repaired" to true,
                "ua_checked_at_ms" to 123L
            ),
            ext
        )
        assertEquals(setOf("ext"), fields.keys)
    }

    @Test
    fun `authorize fields preserve existing behavior without collecting webview for other channels`() {
        var reportCollected = false

        val fields = HaierUserAgentAuthorizeFields.build(
            flavor = "hq008",
            fallbackEffectiveUa = "existing-ua"
        ) {
            reportCollected = true
            HaierUserAgentReport("original", "effective", "webview")
        }

        assertEquals(mapOf("ua" to "existing-ua"), fields)
        assertFalse(reportCollected)
    }

    @Test
    fun `all three requested channels enable extended user agent reporting`() {
        val supported = listOf("haier_lsap", "addy_hq1002", "addy_jams")

        supported.forEach { flavor ->
            val fields = HaierUserAgentAuthorizeFields.build(flavor, "fallback") {
                HaierUserAgentReport("original", "effective", "webview")
            }
            val ext = fields["ext"] as? Map<*, *> ?: error("$flavor should report diagnostics in ext")
            assertFalse("$flavor should not report original UA at top level", fields.containsKey("ua_original"))
            assertFalse("$flavor should not report effective UA at top level", fields.containsKey("ua_effective"))
            assertFalse("$flavor should not report WebView UA at top level", fields.containsKey("webview_ua"))
            assertTrue("$flavor should report original UA in ext", ext.containsKey("ua_original"))
            assertTrue("$flavor should report effective UA in ext", ext.containsKey("ua_effective"))
            assertTrue("$flavor should report WebView UA in ext", ext.containsKey("webview_ua"))
        }
    }
}
