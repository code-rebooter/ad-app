package com.smart.android.ad_app

import android.view.ViewGroup
import com.smart.android.ad_app.AdLocalLog as Log
import com.tcl.ff.component.vastad.Ad
import com.tcl.ff.component.vastad.Controller
import com.tcl.ff.component.vastad.Initialization
import com.tcl.ff.component.vastad.core.callbacks.AdStatusListener

object TclVastAdHelper {

    fun init(initLogLabel: String) {
        adDebugPrintln(initLogLabel)
        Initialization.init(appContext)
    }

    fun showAd(
        loadLogLabel: String,
        flRoot: ViewGroup,
        adStart: (() -> Unit)?,
        adError: (() -> Unit)?,
        adComplete: () -> Unit
    ) {
        Log.d("TvAdFloatingWindow", loadLogLabel)

        Ad.get().begin(appContext)
            .lazyLoad()
            .listen(object : AdStatusListener {
                override fun onAdLoaded(controller: Controller) {
                    Log.d("TvAdFloatingWindow", "广告加载成功，开始播放广告")
                    controller.start(flRoot)
                    adStart?.invoke()
                }

                override fun onAdFinished() {
                    Log.d("TvAdFloatingWindow", "广告播放完成")
                    adComplete.invoke()
                }

                override fun onAdError() {
                    Log.e("TvAdFloatingWindow", "广告加载失败")
                    adError?.invoke()
                }

                override fun onContainerSizeError() {
                    Log.e("TvAdFloatingWindow", "广告容器尺寸不符合要求")
                    adError?.invoke()
                }
            })
            .start()
    }
}
