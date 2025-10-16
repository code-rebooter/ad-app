package com.smart.android.ad_app

import android.view.View
import android.widget.FrameLayout
import com.seraphic.ad.AdConfig
import com.seraphic.ad.AdManager
import com.seraphic.ad.AdPlayManager
import com.seraphic.ad.AdPlayManager.TYPE_WITH_CONTAINER
import com.seraphic.ad.AdStateListener
import com.seraphic.ad.AdStateListener.AD_COMPLETE_ALL
import com.seraphic.ad.AdStateListener.AD_ERROR
import com.seraphic.ad.AdStateListener.AD_LOADED
import com.seraphic.ad.AdStateListener.AD_LOADING
import com.seraphic.ad.AdStateListener.AD_PLAYING
import com.seraphic.ad.AdStateListener.AD_STOPPED
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

    override fun showAd(
        flRoot: View,
        adStart: (() -> Unit)?,
        adError: (() -> Unit)?,
        adComplete: () -> Unit
    ) {
        //展示广告
        println("hq002的广告展示")

        try {
            val container = flRoot as? FrameLayout
            if (container == null) {
                println("容器不是 FrameLayout，广告展示失败")
                adError?.invoke()
                return
            }

            val adPlayManager = AdPlayManager(
                appContext,
                TYPE_WITH_CONTAINER,
                11,
                object : AdStateListener {
                    override fun onAdStateChange(state: Int) {
                        when (state) {
                            AD_ERROR -> {
                                println("hq002广告加载错误")
                                adError?.invoke()
                            }

                            AD_LOADING -> {
                                println("hq002广告开始加载")
                            }

                            AD_LOADED -> {
                                println("hq002广告加载完成")
                            }

                            AD_PLAYING -> {
                                println("hq002广告开始播放")
                                adStart?.invoke()
                            }

                            AD_COMPLETE_ALL -> {
                                println("hq002广告播放完成")
                                adComplete.invoke()
                            }
                        }
                    }
                }
            )

            adPlayManagerRef = WeakReference(adPlayManager)
            adPlayManager.startAd(container, 0f)

        } catch (e: Exception) {
            println("广告播放异常：${e.message}")
            adError?.invoke()
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
