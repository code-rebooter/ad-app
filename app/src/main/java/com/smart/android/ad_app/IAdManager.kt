package com.smart.android.ad_app

import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout

interface IAdManager {
    fun init()
    fun showAd(
        flRoot: ViewGroup,
        adId: String? = null,
        adStart: (() -> Unit)? = null,
        adError: (() -> Unit)? = null,
        adComplete: () -> Unit
    )
    fun destroyAd()
}
