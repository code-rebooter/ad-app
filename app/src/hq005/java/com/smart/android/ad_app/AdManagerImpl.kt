package com.smart.android.ad_app

import android.view.View
import android.view.ViewGroup
import com.google.android.exoplayer2.util.Log
import com.mofeng.ff.component.open.Sdk
import com.tcl.ff.component.vastad.Controller
import com.tcl.ff.component.vastad.core.callbacks.LazyLoaderAdListener
import java.lang.ref.WeakReference

object AdManagerImpl : IAdManager {
    override fun init() {
        // 广告初始化
        println("hq005的广告初始化")
        Sdk.init(appContext)
        Sdk.getAd().setEnableLog(BuildConfig.DEBUG)
    }

    private var mController: Controller? = null
    private var mFlRootRef: WeakReference<View>? = null // ✅ 用 WeakReference 包裹

    override fun showAd(
        flRoot: ViewGroup,
        adId: String?,
        adStart: (() -> Unit)?,
        adError: (() -> Unit)?,
        adComplete: () -> Unit
    ) {
        Log.d("TvAdFloatingWindow", "hq005的广告展示，开始加载广告")

        Sdk.getAd().begin(appContext).lazyLoad().listen(object : LazyLoaderAdListener {
            override fun onAdLoaded(controller: Controller) {
                println("广告被加载")
                mController = controller
                mFlRootRef = WeakReference(flRoot) // ✅ 设置弱引用
                controller.start(flRoot)
                adStart?.invoke()
            }

            override fun onAdFinished() {
                println("广告结束")
                adComplete.invoke()
            }

            override fun onAdError() {
                println("广告错误")
                adComplete.invoke()
            }

            override fun onContainerSizeError() {
                println("容器大小错误")
                adComplete.invoke()
            }
        }).start()
    }

    override fun destroyAd() {
        // 销毁广告
        mFlRootRef?.get()?.let { view ->
            mController?.stop(view as ViewGroup) // ✅ 从弱引用中安全获取 view
        }
        mController = null
        mFlRootRef?.clear()
        mFlRootRef = null // ✅ 清除引用
    }
}
