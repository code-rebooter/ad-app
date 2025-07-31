package com.smart.android.ad_app

import android.view.View
import android.widget.FrameLayout
import com.seraphic.ad.AdConfig
import com.seraphic.ad.AdManager
import com.seraphic.ad.AdPlayManager
import com.seraphic.ad.AdPlayManager.TYPE_WITH_CONTAINER
import com.seraphic.ad.AdStateListener
import com.seraphic.ad.AdStateListener.AD_COMPLETE_ALL
import com.seraphic.ad.AdStateListener.AD_LOADED
import com.seraphic.ad.AdStateListener.AD_LOADING
import com.seraphic.ad.AdStateListener.AD_PLAYING
import java.lang.ref.WeakReference

object AdManagerImpl : IAdManager {

    private var adPlayManagerRef: WeakReference<AdPlayManager>? = null

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
        } catch (e: Exception) {
            println("当前异常是：${e.message}")
        }

    }

    override fun showAd(flRoot: View, adStart: () -> Unit, adComplete: () -> Unit) {
        //展示广告
        println("hq002的广告展示")

        try {
            val adPlayManager = AdPlayManager(
                appContext, TYPE_WITH_CONTAINER, 11
            ) {
                when (it) {
                    AdStateListener.AD_ERROR -> {
                        //广告加载错误
                        adComplete.invoke()
                    }
                    AD_LOADING -> {
                        //广告开始加载
                    }
                    AD_LOADED -> {
                        //广告加载完成
                    }
                    AD_PLAYING -> {
                        //广告开始播放
                        adStart.invoke()
                    }
                    AD_COMPLETE_ALL -> adComplete.invoke()
                }
            }
            // 存储弱引用
            adPlayManagerRef = WeakReference(adPlayManager)
            adPlayManager.startAd(flRoot as FrameLayout, 0f)
        } catch (e: Exception) {
            println("当前的异常是：${e.message}")
        }

    }

    override fun destroyAd() {
        //销毁广告
        try {
            // 获取弱引用中的 AdPlayManager
            adPlayManagerRef?.get()?.let { adPlayManager ->
                // 调用 AdPlayManager 的销毁方法（假设有类似方法）
                adPlayManager.stopAd() // 请确认 AdPlayManager 是否有 destroy 方法
                adPlayManagerRef?.clear() // 清除弱引用
            }
        } catch (e: Exception) {
            println("销毁广告异常：${e.message}")
        }
    }


}
