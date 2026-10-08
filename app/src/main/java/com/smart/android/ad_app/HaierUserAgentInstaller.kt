package com.smart.android.ad_app

import com.smart.android.ad_app.logging.PropertyLog

import android.os.Build
import com.smart.android.ad_app.AdLocalLog as Log

internal object HaierUserAgentInstaller {

    private const val TAG = "HaierUaNormalizer"
    private const val HTTP_AGENT_PROPERTY = "http.agent"
    private val supportedFlavors = setOf(
        "hq008",
        "hq008XHSX",
        "tcl_aishang",
        "ad_ytx01",
        "ad_ytx01_sxk",
        "ad_ytx01_jx",
        "ad_album_101_001",
        "hq008Noneu",
        "hq008Noneuc2",
        "tcl_poly",
        "haier_lsap",
        "addy_hq1002",
        "addy_jams",
        "google_ad_tv_desktop",
        "google_ad_tv_desktop_jm",
        "google_ad_tv_desktop_ytx",
        "google_ad_tv_desktop_007",
        "google_ad_tv_desktop_v260904_1",
        "google_ad_tv_desktop_tpmaotai1935",
        "google_ad_tv_lockscreen",
        "google_ad_tv_lockscreen_hq002"
    )
    @Volatile
    private var latestResult: HaierUaNormalizationResult? = null

    @Volatile
    private var latestRuntimeCheck: HaierUaRuntimeCheck? = null

    fun supportsFlavor(flavor: String): Boolean {
        return flavor in supportedFlavors
    }

    fun currentResult(): HaierUaNormalizationResult? {
        return latestResult
    }

    fun currentRuntimeCheck(): HaierUaRuntimeCheck? {
        return latestRuntimeCheck
    }

    fun installForCurrentProcess(flavor: String) {
        installForProcess(
            flavor = flavor,
            sdkInt = Build.VERSION.SDK_INT,
            logger = { message -> Log.i(TAG, message) }
        )
    }

    internal fun installForProcess(
        flavor: String,
        sdkInt: Int,
        logger: (String) -> Unit
    ): HaierUaNormalizationResult? {
        if (!supportsFlavor(flavor)) {
            latestResult = null
            return null
        }

        val result = HaierUserAgentNormalizer.normalize(
            rawUa = System.getProperty(HTTP_AGENT_PROPERTY),
            sdkInt = sdkInt
        )
        if (result.changed) {
            System.setProperty(HTTP_AGENT_PROPERTY, result.effectiveUa)
        }
        latestResult = result
        latestRuntimeCheck = HaierUaRuntimeCheck(
            observedUa = result.originalUa,
            effectiveUa = System.getProperty(HTTP_AGENT_PROPERTY).orEmpty(),
            repaired = result.changed,
            reason = result.reason,
            checkedAtMs = System.currentTimeMillis()
        )

        logger(
            buildString {
                append("UA规范化：flavor=")
                append(flavor)
                append("，sdk=")
                append(sdkInt)
                append("，changed=")
                append(result.changed)
                append("，reason=")
                append(result.reason)
                if (PropertyLog.isEnabled()) {
                    append("，original=")
                    append(result.originalUa)
                    append("，effective=")
                    append(result.effectiveUa)
                }
            }
        )
        return result
    }

    @Synchronized
    fun ensureEffectiveForCurrentProcess(
        flavor: String = BuildConfig.FLAVOR,
        sdkInt: Int = Build.VERSION.SDK_INT
    ): HaierUaRuntimeCheck {
        val observed = System.getProperty(HTTP_AGENT_PROPERTY).orEmpty()
        if (!supportsFlavor(flavor)) {
            return HaierUaRuntimeCheck(
                observedUa = observed,
                effectiveUa = observed,
                repaired = false,
                reason = HaierUaNormalizationReason.UNCHANGED_UNSUPPORTED_SDK,
                checkedAtMs = System.currentTimeMillis()
            )
        }

        val normalized = HaierUserAgentNormalizer.normalize(observed, sdkInt)
        if (normalized.changed) {
            System.setProperty(HTTP_AGENT_PROPERTY, normalized.effectiveUa)
        }
        val effective = System.getProperty(HTTP_AGENT_PROPERTY).orEmpty()
            .ifBlank { normalized.effectiveUa }
        return HaierUaRuntimeCheck(
            observedUa = observed,
            effectiveUa = effective,
            repaired = observed != effective,
            reason = normalized.reason,
            checkedAtMs = System.currentTimeMillis()
        ).also { latestRuntimeCheck = it }
    }
}

internal data class HaierUaRuntimeCheck(
    val observedUa: String,
    val effectiveUa: String,
    val repaired: Boolean,
    val reason: HaierUaNormalizationReason,
    val checkedAtMs: Long
)
