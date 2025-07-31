package com.smart.android.ad_app

import android.util.Log
import android.view.View
import android.widget.FrameLayout
import com.zykj.vastplayer.manager.ZyVideoAd

object AdManagerImpl : IAdManager {

    lateinit var zyVideoAd :ZyVideoAd

    override fun init() {
        //广告初始化
        println("hq003的广告初始化")
        zyVideoAd = ZyVideoAd(appContext, true, "test")
    }

    private var isListenerInited = false
    override fun showAd(
        flRoot: View,
        adStart: (() -> Unit)?,
        adError: (() -> Unit)?,
        adComplete: () -> Unit
    ) {
        if(!isListenerInited){
            initAdListener(adStart,adError,adComplete)
            isListenerInited = true
        }
        zyVideoAd.requestAds(flRoot as FrameLayout, "test")
    }

    private fun initAdListener(
        adStart: (() -> Unit)?,
        adError: (() -> Unit)?,
        adComplete: () -> Unit
    ) {
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
    }

    override fun destroyAd() {
        //销毁广告
    }


}
