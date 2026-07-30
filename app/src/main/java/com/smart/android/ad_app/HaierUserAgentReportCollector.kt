package com.smart.android.ad_app

import android.content.Context
import android.webkit.WebSettings

internal data class HaierUserAgentReport(
    val originalUa: String,
    val effectiveUa: String,
    val webViewUa: String,
    val observedUa: String = effectiveUa,
    val aarCachedUa: String = "",
    val aarEffectiveUa: String = effectiveUa,
    val driftDetected: Boolean = false,
    val aarDriftDetected: Boolean = false,
    val repaired: Boolean = false,
    val checkedAtMs: Long = 0L
)

internal class HaierUserAgentReportCollector {

    fun collect(context: Context): HaierUserAgentReport {
        val appContext = context.applicationContext
        val aarCachedUa = appContext
            .getSharedPreferences(LSAP_PREFS_NAME, Context.MODE_PRIVATE)
            .getString(LSAD_WEB_UA, "")
            .orEmpty()
        val check = HaierUserAgentInstaller.ensureEffectiveForCurrentProcess()
        appContext.getSharedPreferences(LSAP_PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(LSAD_WEB_UA, check.effectiveUa)
            .commit()
        return collect(
            installedResult = HaierUserAgentInstaller.currentResult(),
            currentHttpAgent = check.effectiveUa,
            webViewUserAgentProvider = {
                WebSettings.getDefaultUserAgent(appContext)
            },
            runtimeCheck = check,
            aarCachedUa = aarCachedUa
        )
    }

    internal fun collect(
        installedResult: HaierUaNormalizationResult?,
        currentHttpAgent: String?,
        webViewUserAgentProvider: () -> String?,
        runtimeCheck: HaierUaRuntimeCheck? = null,
        aarCachedUa: String = ""
    ): HaierUserAgentReport {
        val effectiveUa = currentHttpAgent.orEmpty().ifEmpty {
            installedResult?.effectiveUa.orEmpty()
        }
        return HaierUserAgentReport(
            originalUa = installedResult?.originalUa ?: effectiveUa,
            effectiveUa = effectiveUa,
            webViewUa = resolveWebViewUa(webViewUserAgentProvider),
            observedUa = runtimeCheck?.observedUa ?: effectiveUa,
            aarCachedUa = aarCachedUa,
            aarEffectiveUa = effectiveUa,
            driftDetected = runtimeCheck?.repaired == true,
            aarDriftDetected = aarCachedUa.isNotBlank() && aarCachedUa != effectiveUa,
            repaired = runtimeCheck?.repaired == true ||
                (aarCachedUa.isNotBlank() && aarCachedUa != effectiveUa),
            checkedAtMs = runtimeCheck?.checkedAtMs ?: System.currentTimeMillis()
        )
    }

    private fun resolveWebViewUa(provider: () -> String?): String {
        return try {
            provider().orEmpty()
        } catch (_: Throwable) {
            ""
        }
    }

    private companion object {
        const val LSAP_PREFS_NAME = "lsapdata"
        const val LSAD_WEB_UA = "LSADWEBUA"
    }
}

internal object HaierUserAgentAuthorizeFields {

    fun build(
        flavor: String,
        fallbackEffectiveUa: String?,
        reportProvider: () -> HaierUserAgentReport
    ): Map<String, Any> {
        if (!HaierUserAgentInstaller.supportsFlavor(flavor)) {
            return linkedMapOf("ua" to fallbackEffectiveUa.orEmpty())
        }

        val report = reportProvider()
        val ext = linkedMapOf<String, Any>(
            "ua_original" to report.originalUa,
            "ua_effective" to report.effectiveUa,
            "webview_ua" to report.webViewUa,
            "ua_observed" to report.observedUa,
            "ua_aar_cached" to report.aarCachedUa,
            "ua_aar_effective" to report.aarEffectiveUa,
            "ua_drift_detected" to report.driftDetected,
            "ua_aar_drift_detected" to report.aarDriftDetected,
            "ua_repaired" to report.repaired,
            "ua_checked_at_ms" to report.checkedAtMs
        )
        return linkedMapOf("ext" to ext)
    }
}
