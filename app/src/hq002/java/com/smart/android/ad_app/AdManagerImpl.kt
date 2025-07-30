package com.smart.android.ad_app

import android.view.View
import android.widget.FrameLayout
import com.seraphic.ad.AdConfig
import com.seraphic.ad.AdManager
import com.seraphic.ad.AdPlayManager
import com.seraphic.ad.AdPlayManager.TYPE_WITH_CONTAINER

object AdManagerImpl : IAdManager {
    override fun init() {
        //广告初始化
        println("hq002的广告初始化")

        try {
            val config = AdConfig.Builder()
                .isDebug(false) // 是否开启 debug 模式，开启会打印更多 log，供开发调试
                .productName("test")
                .productTag("test")
                .adId("test") // 如果环境中没有 GMS 可不填
                .build()

            AdManager.getInstance().init(appContext, config)
        }catch (e: Exception){
            println("当前异常是：${e.message}")
        }

    }

    override fun showAd(flRoot: View, adComplete: () -> Unit) {
        //展示广告
        println("hq002的广告展示")

        try {
            val adPlayManager = AdPlayManager(appContext, TYPE_WITH_CONTAINER, 11, null)
            adPlayManager.startAd(
                flRoot as FrameLayout,  // 用于承载 AdView 的 Container
                0f     // 圆角值
            )
        }catch (e: Exception){
            println("当前的异常是：${e.message}")
        }

    }


}
