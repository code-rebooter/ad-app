package com.smart.android.ad_app.sdk

import android.content.Context
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import com.smart.android.ad_app.AdConfigManager
import java.lang.ref.WeakReference

/**
 * 统一的广告 SDK 入口
 * 包含：初始化配置 + 广告加载展示 + 停止控制
 */
@UnstableApi
object RtbAds {

    // ================= Global Configuration (初始化配置) =================

    private var isDebugMode: Boolean = false
    private var isMutedDefault: Boolean = false

    // 【新增】持有当前正在展示的广告 View，以便外部可以强制停止
    // 使用 WeakReference 防止内存泄漏
    private var currentAdViewRef: WeakReference<VastAdPlayerView>? = null

    // 【新增】标记位：防止用户调用停止后，网络请求才回来导致广告又弹出来
    private var isStopRequested: Boolean = false

    /**
     * 第一步：全局初始化
     */
    fun init(context: Context, debugMode: Boolean = false, muted: Boolean = false) {
        this.isDebugMode = debugMode
        this.isMutedDefault = muted
    }

    private fun debugLog(message: String) {
        if (isDebugMode) {
            println(message)
        }
    }

    // ================= Ad Loading & Showing (加载展示) =================

    /**
     * 第二步：请求并展示插屏视频广告
     */
    @OptIn(UnstableApi::class)
    fun showAd(
        context: Context,
        container: ViewGroup,
        adId: String? = null,
        onAdStarted: (() -> Unit)? = null,   // 【新增】开始播放
        onAdError: ((String) -> Unit)? = null, // 【新增】出错
        onAdCompleted: (() -> Unit)? = null    // 【修改】播放完成/结束
    ) {
        // 安全起见，如果有旧的广告没销毁，先销毁掉
        stopAd()

        // 每次请求前，重置停止标记
        isStopRequested = false

        // 1. 请求广告数据
        AdManager.requestHomeVideoAd(context) { admVast, error ->

            // 【关键】如果用户在请求过程中已经调用了 stopCurrentAd()，则数据回来也不展示
            if (isStopRequested) {
                debugLog("SDK: 广告已取消展示")
                return@requestHomeVideoAd
            }

            if (error != null) {
                val errorMsg = error?.toString() ?: "Unknown request error"

                AdConfigManager.reportAdStatus("adm_failed", errorMsg, adId)
                debugLog("SDK: 无广告返回或请求失败: $errorMsg")
                onAdError?.invoke(errorMsg)
            } else {
                if (admVast.isNullOrEmpty()) {
                    val errorMsg = "EMPTY_ADM"
                    AdConfigManager.reportAdStatus("adm_empty", errorMsg, adId)
                    onAdError?.invoke(errorMsg)
                    return@requestHomeVideoAd
                }

                AdConfigManager.reportAdStatus("adm_success", admVast, adId)
                // 2. 成功：创建 View 并播放
                val vastAdView = VastAdPlayerView(context).apply {
                    layoutParams = ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT)
                    requestAdId = adId
                    enableDebugLogging = isDebugMode

                    this.isMuted = isMutedDefault

                    // --- 设置回调事件 ---

                    // A. 开始播放回调
                    this.onAdStarted = {
                        onAdStarted?.invoke()
                    }

                    // 定义统一的清理动作（不论是出错还是播完）
                    val finishAction = {
                        (parent as? ViewGroup)?.removeView(this)
                        release()
                        if (currentAdViewRef?.get() == this) {
                            currentAdViewRef = null
                        }
                    }

                    // B. 播放完成回调 (包含跳过、正常播完)
                    this.onAllAdsCompleted = {
                        finishAction()
                        onAdCompleted?.invoke()
                    }
                    this.onAdError = { errorMsg ->
                        finishAction()
                        debugLog("SDK: 播放器内部错误: $errorMsg")
                        onAdError?.invoke(errorMsg)
                    }
                }

                // 【新增】保存引用
                currentAdViewRef = WeakReference(vastAdView)

                // 3. 添加到 UI 并开始播放
                container.addView(vastAdView)
                vastAdView.playWithAdm(admVast)
            }
        }
    }

    // ================= Stop Control (停止控制) =================

    /**
     * 【新增】第三步：停止并销毁当前广告
     * 务必在 Activity.onDestroy() 中调用，防止内存泄漏或后台播放
     */
    @OptIn(UnstableApi::class)
    fun stopAd() {
        // 1. 标记停止请求（拦截正在进行中的网络回调）
        isStopRequested = true

        // 2. 获取当前的 View
        val currentView = currentAdViewRef?.get()

        if (currentView != null) {
            debugLog("SDK: 强制停止当前广告")
            // 从父布局移除
            (currentView.parent as? ViewGroup)?.removeView(currentView)
            // 调用 View 内部的 release 方法彻底释放 ExoPlayer
            currentView.release()
        }

        // 3. 置空引用
        currentAdViewRef = null
    }
}
