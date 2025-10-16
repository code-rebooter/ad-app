package com.smart.android.ad_app

import android.util.Log
import android.view.View
import android.widget.FrameLayout
import com.zykj.vastplayer.manager.ZyVideoAd

object AdManagerImpl : IAdManager {
    override fun init() {
        println("hq003的广告初始化")
    }

    override fun showAd(
        flRoot: View,
        adStart: (() -> Unit)?,
        adError: (() -> Unit)?,
        adComplete: () -> Unit
    ) {
        val IS_DEBUG = false          // 是否测试模式
        val IS_FLL_VIDEO = false       // 是否拉伸视频
        val AD_ID = "112"             // 广告ID
        val CHANNEL_ID = "3"          // 渠道ID
        val T_ID = "3"          // 广告位ID


        val zyVideoAd = ZyVideoAd(appContext, IS_DEBUG, IS_FLL_VIDEO, AD_ID, CHANNEL_ID)

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

        zyVideoAd.requestAds(flRoot as FrameLayout, T_ID)
    }

    override fun destroyAd() {
        //销毁广告
    }
}

