package com.smart.android.ad_app

import com.smart.android.ad_app.AdLocalLog as Log
import android.view.ViewGroup
import com.zykj.vastplayer.manager.ZyVideoAd

object AdManagerImpl : IAdManager {
    private const val isDebugMode = false
    private const val isFillVideo = false
    private const val adIdValue = "112"
    private const val channelId = "3"
    private const val placementId = "3"

    override fun init() {
        adDebugPrintln("hq003的广告初始化")
    }

    override fun showAd(
        flRoot: ViewGroup,
        adId: String?,
        soundEnabled: Boolean,
        adStart: (() -> Unit)?,
        adError: (() -> Unit)?,
        adComplete: () -> Unit
    ) {
        val container = flRoot.requireFrameLayout("hq003容器不是 FrameLayout，广告展示失败", adError)
            ?: return
        val zyVideoAd = ZyVideoAd(appContext, isDebugMode, isFillVideo, adIdValue, channelId)

        zyVideoAd.addAdEventListener(object : ZyVideoAd.JoyeAdListener {
            override fun onStart() {
                Log.d("ZyAd", "广告开始播放")
                adStart?.invoke()
            }

            override fun onError() {
                Log.e("ZyAd", "广告播放出错")
                adError?.invoke()
            }

            override fun onComplete() {
                Log.d("ZyAd", "广告播放完成")
                adComplete.invoke()
            }
        })

        zyVideoAd.requestAds(container, placementId)
    }

    override fun destroyAd() {
        //销毁广告
    }
}
