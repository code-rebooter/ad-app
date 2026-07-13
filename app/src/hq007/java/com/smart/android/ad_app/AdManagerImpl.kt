package com.smart.android.ad_app

import android.view.ViewGroup

object AdManagerImpl : IAdManager {
    override fun init() {
        TclVastAdHelper.init("hq007的广告初始化")
    }

    override fun showAd(
        flRoot: ViewGroup,
        adId: String?,
        soundEnabled: Boolean,
        adStart: (() -> Unit)?,
        adError: (() -> Unit)?,
        adComplete: () -> Unit
    ) {
        TclVastAdHelper.showAd(
            loadLogLabel = "hq007的广告展示，开始加载广告",
            flRoot = flRoot,
            adStart = adStart,
            adError = adError,
            adComplete = adComplete
        )
    }

    override fun destroyAd() {
        //销毁广告
    }
}
