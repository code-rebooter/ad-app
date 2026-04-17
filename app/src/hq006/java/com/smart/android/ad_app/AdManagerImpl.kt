package com.smart.android.ad_app

import android.view.ViewGroup
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import com.smart.android.ad_app.sdk.RtbAds
import io.github.lib_autorun.log.printLog

object AdManagerImpl : IAdManager {

    // 定义最大重试次数
    private const val MAX_RETRY_COUNT = 2

    @OptIn(UnstableApi::class)
    override fun init() {
        // 广告初始化
       "hq006的广告初始化".printLog()
        RtbAds.init(appContext, debugMode = BuildConfig.DEBUG)
    }

    @OptIn(UnstableApi::class)
    override fun showAd(
        flRoot: ViewGroup,
        adId: String?,
        adStart: (() -> Unit)?,
        adError: (() -> Unit)?,
        adComplete: () -> Unit
    ) {
        // 首次调用，重试次数传 0
        "hq006的广告展示，开始首次加载".printLog()
        loadAdWithRetry(flRoot, adId, adStart, adError, adComplete, 0)
    }

    /**
     * 私有辅助方法，带重试计数
     */
    @OptIn(UnstableApi::class)
    private fun loadAdWithRetry(
        flRoot: ViewGroup,
        adId: String?,
        adStart: (() -> Unit)?,
        adError: (() -> Unit)?,
        adComplete: () -> Unit,
        retryCount: Int
    ) {
        RtbAds.showAd(
            context = appContext,
            container = flRoot,
            adId = adId,
            onAdStarted = {
                adStart?.invoke()
            },
            onAdCompleted = {
                adComplete.invoke()
            },
            onAdError = {
                // 核心重试逻辑
                if (retryCount < MAX_RETRY_COUNT) {
                    val nextRetry = retryCount + 1
                    "hq006广告加载失败，正在进行第 $nextRetry 次重试...".printLog()

                    // 递归调用，次数 +1
                    loadAdWithRetry(flRoot, adId, adStart, adError, adComplete, nextRetry)
                } else {
                    // 重试次数用尽，仍然失败，才回调给业务层
                   "hq006广告加载失败，重试次数已用尽 ($MAX_RETRY_COUNT 次)，上报 Error".printLog()
                    adError?.invoke()
                }
            }
        )
    }

    @OptIn(UnstableApi::class)
    override fun destroyAd() {
        // 销毁广告
        RtbAds.stopAd()
    }
}
