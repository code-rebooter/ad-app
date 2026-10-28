package com.smart.android.ad_app

internal object BuildFlavor {
    const val CHANNEL_TYPE_TCL = "TCL"
    const val CHANNEL_TYPE_LSAP = "LSAP"
    const val CHANNEL_TYPE_GOOGLE = "GOOGLE"

    fun isGoogleAd(channelType: String = BuildConfig.AD_CHANNEL_TYPE): Boolean {
        return channelType == CHANNEL_TYPE_GOOGLE
    }

    fun isLsap(channelType: String = BuildConfig.AD_CHANNEL_TYPE): Boolean {
        return channelType == CHANNEL_TYPE_LSAP
    }

    fun usesSharedAdRuntime(channelType: String = BuildConfig.AD_CHANNEL_TYPE): Boolean {
        return usesTclRuntime(channelType) || isLsap(channelType)
    }

    fun usesTclRuntime(channelType: String = BuildConfig.AD_CHANNEL_TYPE): Boolean {
        return channelType == CHANNEL_TYPE_TCL || isGoogleAd(channelType)
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
