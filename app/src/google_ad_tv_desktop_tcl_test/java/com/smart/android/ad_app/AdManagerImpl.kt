package com.smart.android.ad_app

import android.view.ViewGroup

object AdManagerImpl : IAdManager {
    override fun init() = GoogleTclAdManager.init()

    override fun showAd(
        flRoot: ViewGroup,
        adId: String?,
        soundEnabled: Boolean,
        callbackTimeoutMs: Long?,
        adStart: (() -> Unit)?,
        adError: (() -> Unit)?,
        adComplete: () -> Unit
    ) = GoogleTclAdManager.showAd(flRoot, adId, soundEnabled, callbackTimeoutMs, adStart, adError, adComplete)

    override fun destroyAd() = GoogleTclAdManager.destroyAd()
}
