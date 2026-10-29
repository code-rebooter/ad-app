package com.smart.android.ad_app

internal object BuildFlavor {
    const val CHANNEL_TYPE_TCL = "TCL"
    const val CHANNEL_TYPE_LSAP = "LSAP"
    const val CHANNEL_TYPE_GOOGLE = "GOOGLE"
    private const val GOOGLE_AD_RUNTIME_IMA = "ima"

    fun isGoogleAd(channelType: String = BuildConfig.AD_CHANNEL_TYPE): Boolean {
        return channelType == CHANNEL_TYPE_GOOGLE
    }

    fun isLsap(channelType: String = BuildConfig.AD_CHANNEL_TYPE): Boolean {
        return channelType == CHANNEL_TYPE_LSAP
    }

    fun usesSharedAdRuntime(channelType: String = BuildConfig.AD_CHANNEL_TYPE): Boolean {
        return isGoogleAd(channelType) || usesTclRuntime(channelType) || isLsap(channelType)
    }

    fun usesTclRuntime(
        channelType: String = BuildConfig.AD_CHANNEL_TYPE,
        googleAdRuntime: String = BuildConfig.GOOGLE_AD_RUNTIME
    ): Boolean {
        return channelType == CHANNEL_TYPE_TCL ||
            (isGoogleAd(channelType) && googleAdRuntime != GOOGLE_AD_RUNTIME_IMA)
    }

    fun usesGoogleImaRuntime(
        channelType: String = BuildConfig.AD_CHANNEL_TYPE,
        googleAdRuntime: String = BuildConfig.GOOGLE_AD_RUNTIME
    ): Boolean {
        return isGoogleAd(channelType) && googleAdRuntime == GOOGLE_AD_RUNTIME_IMA
    }

    fun needsIdentityPatch(channelType: String = BuildConfig.AD_CHANNEL_TYPE): Boolean {
        return usesSharedAdRuntime(channelType)
    }

    fun usesGoogleConsent(channelType: String = BuildConfig.AD_CHANNEL_TYPE): Boolean {
        return isGoogleAd(channelType)
    }

    fun isTclPoly(flavor: String = BuildConfig.FLAVOR): Boolean {
        return flavor == "tcl_poly"
    }
}
