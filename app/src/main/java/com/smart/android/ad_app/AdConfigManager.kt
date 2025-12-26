package com.smart.android.ad_app

import android.os.Build
import android.provider.Settings
import com.smart.android.ad_app.bean.AdConfigDto
import com.smart.android.ad_app.bean.EmptyData
import io.github.lib_autorun.ext.getMacAddress
import io.github.lib_autorun.net.NetworkHelper
import io.github.lib_autorun.net.enum.RequestMethod

object AdConfigManager {

    fun hasOverlayPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(appContext)
        } else {
            true
        }
    }
    fun getAdConfig() {
        if(hasOverlayPermission()) {
            println("当前有悬浮窗权限")
            //val inDesktop = isInDesktop
           // println("当前是否是在桌面的：$inDesktop")
           // if (inDesktop) {
                val url = "${BuildConfig.BASE_URL}api/v2/ad/delivery"
                NetworkHelper.makeRequest<AdConfigDto>(
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
                        if (!dto?.adId.isNullOrEmpty()) {
                            //可以展示广告
                            dto?.adId?.let { setCurrentAdId(it) }
                            dto?.let { showAd(it) }
                        }
                    }
                }
            /*} else {
                println("当前是没有在桌面的")
            }*/
        }else{
            println("没有悬浮窗权限")
        }
    }
    private var currentAdId: String? = null  // 保存当前广告 adId

    fun setCurrentAdId(adId: String) {
        currentAdId = adId
    }

    fun reportAdStatus(isAdSuccess: Boolean) {
        val adId = currentAdId ?: run {
            println("adId 为空，上报失败")
            return
        }
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
                    "adId" to adId
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