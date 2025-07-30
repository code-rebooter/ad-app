package com.smart.android.ad_app

import android.view.View
import android.widget.FrameLayout

interface IAdManager {
    fun init()
    fun showAd(flRoot: View,adComplete:()->Unit)
}