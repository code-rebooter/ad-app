package com.smart.android.ad_app

import android.util.Log
import android.view.View
import android.widget.FrameLayout
import com.zykj.vastplayer.manager.ZyVideoAd

object AdManagerImpl : IAdManager {
    override fun init() {
        //广告初始化
        println("hq003的广告初始化")
    }

    override fun showAd(flRoot: View, adStart: () -> Unit, adComplete: () -> Unit) {
        //展示广告
        lateinit var zyVideoAd: ZyVideoAd
        val IS_DEBUG = true
        val AD_ID = "test"
        val T_ID = "test"
        zyVideoAd = ZyVideoAd(appContext, IS_DEBUG, AD_ID)
        zyVideoAd.addAdEventListener(object : ZyVideoAd.JoyeAdListener {
            override fun onStart() {
                Log.d("ZyAd", "广告开始播放")
            }

            override fun onError() {
                Log.e("ZyAd", "广告播放出错")
                adComplete.invoke()
            }

            override fun onComplete() {
                Log.d("ZyAd", "广告播放完成")
                adComplete.invoke()
            }
        })

        // 请求 native 广告
        zyVideoAd.requestAds(flRoot as FrameLayout, T_ID)
    }

    override fun destroyAd() {
        //销毁广告
    }


}
