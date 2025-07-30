package com.smart.android.ad_app

import android.content.Context
import android.view.View
import android.view.ViewGroup
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

    override fun showAd(flRoot: View, adComplete: () -> Unit) {
        //展示广告
        println("hq001的广告展示")

        Ad.get().begin(appContext)
            .lazyLoad()
            .listen(object : LazyLoaderAdListener {
                override fun onAdLoaded(controller: Controller) {
                    controller.start(flRoot as ViewGroup)
                }

                override fun onAdFinished() {
                    adComplete.invoke()
                }

                override fun onAdError() {
                    adComplete.invoke()
                }

                override fun onContainerSizeError() {
                    adComplete.invoke()
                }
            })
            .start()
    }

}




