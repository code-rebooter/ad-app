package com.smart.android.ad_app

import android.view.ViewGroup

object AdManagerImpl : IAdManager {
    override fun init() {
        HaierLsapAdManager.init()
    }

    override fun showAd(
        flRoot: ViewGroup,
        adId: String?,
        soundEnabled: Boolean,
        adStart: (() -> Unit)?,
        adError: (() -> Unit)?,
        adComplete: () -> Unit
    ) {
        HaierLsapAdManager.showAd(
            flRoot = flRoot,
            adId = adId,
            soundEnabled = soundEnabled,
            adStart = adStart,
            adError = adError,
            adComplete = adComplete
        )
    }

    override fun destroyAd() {
        HaierLsapAdManager.destroyAd()
    }
}
