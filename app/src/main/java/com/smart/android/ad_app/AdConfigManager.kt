package com.smart.android.ad_app

import com.github.lib_autorun.ext.NetCache
import com.github.lib_autorun.ext.getMacAddress
import com.github.lib_autorun.net.NetworkHelper
import com.github.lib_autorun.net.enum.RequestMethod
import com.smart.android.ad_app.bean.AdConfigDto
import com.smart.android.ad_app.bean.EmptyData

object AdConfigManager {
    fun getAdConfig() {
        if(appContext.isInHome()){
            println("当前是在桌面的")
            val url = "${BuildConfig.BASE_URL}api/v2/ad/delivery"
            NetworkHelper.makeRequest<AdConfigDto> (
                url,
                RequestMethod.POST,
                mapOf(
                    "packageName" to appContext.packageName,
                    "channel" to BuildConfig.CHANNEL,
                    "macAddress" to (getMacAddress() ?: ""),
                ),
                isEncryted = false
            ) { dto, error ->
                if (error != null) {
                    println("请求失败")
                } else {
                    println("请求成功-${dto}")
                    if(!dto?.adId.isNullOrEmpty()){
                        //可以展示广告
                        showAd(dto!!)
                    }
                }
            }
        }else{
            println("当前是没有在桌面的")
        }

    }

    fun reportAdStatus(isAdSuccess: Boolean) {
            println("上报广告状态")
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