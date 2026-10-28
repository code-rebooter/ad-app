package com.smart.android.ad_app

import com.smart.android.ad_app.logging.PropertyLog

import android.os.Build
import com.smart.android.ad_app.AdLocalLog as Log

internal object HaierUserAgentInstaller {

    private const val TAG = "HaierUaNormalizer"
    private const val HTTP_AGENT_PROPERTY = "http.agent"
    @Volatile
    private var latestResult: HaierUaNormalizationResult? = null

    @Volatile
    private var latestRuntimeCheck: HaierUaRuntimeCheck? = null

    fun supportsChannelType(channelType: String): Boolean {
        return BuildFlavor.needsIdentityPatch(channelType)
    }

    fun currentResult(): HaierUaNormalizationResult? {
        return latestResult
    }

    fun currentRuntimeCheck(): HaierUaRuntimeCheck? {
        return latestRuntimeCheck
    }

    fun installForCurrentProcess(channelType: String = BuildConfig.AD_CHANNEL_TYPE) {
        installForProcess(
            channelType = channelType,
            sdkInt = Build.VERSION.SDK_INT,
            logger = { message -> Log.i(TAG, message) }
        )
    }

    internal fun installForProcess(
        channelType: String,
        sdkInt: Int,
        identity: HaierUaIdentity = HaierBuildIdentityNormalizer.userAgentIdentity(sdkInt),
        logger: (String) -> Unit
    ): HaierUaNormalizationResult? {
        if (!supportsChannelType(channelType)) {
            latestResult = null
            return null
        }

        val result = HaierUserAgentNormalizer.normalize(
            rawUa = System.getProperty(HTTP_AGENT_PROPERTY),
            sdkInt = sdkInt,
            identity = identity
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
                append("UA规范化：channelType=")
                append(channelType)
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
        channelType: String = BuildConfig.AD_CHANNEL_TYPE,
        sdkInt: Int = Build.VERSION.SDK_INT,
        identity: HaierUaIdentity = HaierBuildIdentityNormalizer.userAgentIdentity(sdkInt)
    ): HaierUaRuntimeCheck {
        val observed = System.getProperty(HTTP_AGENT_PROPERTY).orEmpty()
        if (!supportsChannelType(channelType)) {
            return HaierUaRuntimeCheck(
                observedUa = observed,
                effectiveUa = observed,
                repaired = false,
                reason = HaierUaNormalizationReason.UNCHANGED_UNSUPPORTED_SDK,
                checkedAtMs = System.currentTimeMillis()
            )
        }

        val normalized = HaierUserAgentNormalizer.normalize(observed, sdkInt, identity)
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
