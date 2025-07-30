package com.smart.android.ad_app

import android.view.View

object AdManagerImpl : IAdManager {
    override fun init() {
        //广告初始化
        println("hq003的广告初始化")
    }

    override fun showAd(flRoot: View) {
        //展示广告
        println("hq003的广告展示")
    }


}
