package com.smart.android.ad_app

import android.view.View
import android.view.ViewGroup
import com.google.android.exoplayer2.util.Log
import com.tcl.ff.component.vastad.Ad
import com.tcl.ff.component.vastad.Controller
import com.tcl.ff.component.vastad.Initialization
import com.tcl.ff.component.vastad.core.callbacks.LazyLoaderAdListener

object AdManagerImpl : IAdManager {
    override fun init() {
        //广告初始化
        println("hq001的广告初始化")
        Initialization.init(appContext)
    }

    override fun showAd(flRoot: View, adStart: () -> Unit, adComplete: () -> Unit) {
        Log.d("TvAdFloatingWindow", "hq001的广告展示，开始加载广告")

        Ad.get().begin(appContext)
            .lazyLoad()
            .listen(object : LazyLoaderAdListener {
                override fun onAdLoaded(controller: Controller) {
                    Log.d("TvAdFloatingWindow", "广告加载成功，开始播放广告")
                    controller.start(flRoot as ViewGroup)
                    adStart.invoke()
                }

                override fun onAdFinished() {
                    Log.d("TvAdFloatingWindow", "广告播放完成")
                    adComplete.invoke()
                }

                override fun onAdError() {
                    Log.e("TvAdFloatingWindow", "广告加载失败")
                    adComplete.invoke()
                }

                override fun onContainerSizeError() {
                    Log.e("TvAdFloatingWindow", "广告容器尺寸不符合要求")
                    adComplete.invoke()
                }
            })
            .start()
    }

    override fun destroyAd() {
        //销毁广告
    }

}




