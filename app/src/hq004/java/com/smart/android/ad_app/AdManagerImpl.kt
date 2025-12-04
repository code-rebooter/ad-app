package com.smart.android.ad_app

import android.util.Log
import android.view.View
import android.view.ViewGroup
import com.sjkj.ad.AdManager
import com.sjkj.ad.AdPlayManager
import com.sjkj.ad.AdViewDynamic
import com.sjkj.ad.listener.AdPlayListener

object AdManagerImpl : IAdManager {
    override fun init() {
        //广告初始化
        println("hq004的广告初始化")
        val config = com.sjkj.ad.common.AdConfig.Builder()
            .appId("AD_SZ_20241213_9949413184") //appId，由sdk提供⽅分配
            .isDebug(true) //可选，是否为debug模式，debug模式时会打印更多log，供调试
            .build()
        AdManager.getInstance().init(appContext, config)
    }

    private var manager : AdPlayManager?=null
    override fun showAd(
        flRoot: ViewGroup,
        adStart: (() -> Unit)?,
        adError: (() -> Unit)?,
        adComplete: () -> Unit
    ) {
        println("hq004的广告展示")
        val adView = AdViewDynamic(appContext)
         manager = AdPlayManager()
        manager?.playAd(adView, object : AdPlayListener{
            override fun onStart(cur: Int, total: Int) {
               //广告开始
                if(cur==1){
                    //开始播放广告
                    adStart?.invoke()
                }
            }

            override fun onError(code: Int, errorMsg: String?) {
                //播放错误
                adComplete.invoke()
            }

            override fun onAllComplete() {
                //全部播放完成
                adComplete.invoke()
            }

        })
    }

    override fun destroyAd() {
        //销毁广告
        manager?.endPlay()
    }

}




