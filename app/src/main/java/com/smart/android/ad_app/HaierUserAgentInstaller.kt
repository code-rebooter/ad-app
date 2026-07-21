package com.smart.android.ad_app

import android.os.Build
import android.util.Log

internal object HaierUserAgentInstaller {

    private const val TAG = "HaierUaNormalizer"
    private const val HTTP_AGENT_PROPERTY = "http.agent"
    private val supportedFlavors = setOf(
        "haier_lsap",
        "addy_hq1002",
        "addy_jams"
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
                if (BuildConfig.DEBUG) {
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
