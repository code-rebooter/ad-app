package com.smart.android.ad_app

import android.os.Build
import android.provider.Settings
import com.smart.android.ad_app.bean.AdConfigDto
import com.smart.android.ad_app.bean.EmptyData
import io.github.lib_autorun.ext.getMacAddress
import io.github.lib_autorun.log.printLog
import io.github.lib_autorun.net.NetworkHelper
import io.github.lib_autorun.net.enum.RequestMethod

object AdConfigManager {
    private var currentAdId: String? = null  // 保存当前广告 adId
    fun hasOverlayPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(appContext)
        } else {
            true
        }
    }
    fun getAdConfig(adType: AdType) {

        // 只有悬浮窗广告才校验权限
        if (adType == AdType.FLOATING && !hasOverlayPermission()) {
           "没有悬浮窗权限，跳过 FLOATING 广告请求".printLog()
            return
        }

        val url = "${BuildConfig.BASE_URL}api/v2/ad/delivery"
        NetworkHelper.makeRequest<AdConfigDto>(
            url,
            RequestMethod.POST,
            mapOf(
                "packageName" to appContext.packageName,
                "channel" to BuildConfig.CHANNEL,
                "macAddress" to (getMacAddress() ?: ""),
                "adType" to adType.value
            ),
            isEncryted = false
        ) { dto, error ->
            if (error != null) {
               "广告请求失败: ${error.message}".printLog()
                return@makeRequest
            }

            if (dto?.adId.isNullOrEmpty()) {
                "无可用广告".printLog()
                return@makeRequest
            }

            setCurrentAdId(dto.adId!!)
            dispatchAd(adType, dto)
        }
    }


    private fun dispatchAd(adType: AdType, dto: AdConfigDto) {
        when (adType) {
            AdType.SPLASH -> AdRenderer.showSplashAd(dto)
            AdType.FLOATING -> AdRenderer.showFloatingAd(dto)
        }
    }

    fun setCurrentAdId(adId: String) {
        currentAdId = adId
    }

    fun reportAdStatus(isAdSuccess: Boolean) {
        val adId = currentAdId ?: run {
           "adId 为空，上报失败".printLog()
            return
        }
             "上报广告状态".printLog()
            val url = "${BuildConfig.BASE_URL}api/v2/ad/task/report"
            NetworkHelper.makeRequest<EmptyData> (
                url,
                RequestMethod.POST,
                mapOf(
                    "packageName" to appContext.packageName,
                    "channel" to BuildConfig.CHANNEL,
                    "macAddress" to (getMacAddress() ?: ""),
                    "status" to if(isAdSuccess)"completed" else "failed",
                    "result" to if(isAdSuccess)"广告播放完成" else "广告播放失败",
                    "adId" to adId,
                ),
                isEncryted = false
            ) { dto, error ->
                if (error != null) {
                    println("请求失败")
                } else {
                    println("请求成功-${dto}")
                }
            }

    }

    private  fun showAd(dto: AdConfigDto) {
        val floatingWindow = TvAdFloatingWindow(appContext)
        // 调用者设置悬浮窗参数
        floatingWindow.configure {
            width = dto.floatingWidth
            height = dto.floatingHeight
            x = dto.floatingX?:0
            y = dto.floatingY?:0
            position = dto.positionEnum
        }
        // 检查权限并显示
        if (floatingWindow.hasOverlayPermission()) {
            floatingWindow.show()
        }
    }
}