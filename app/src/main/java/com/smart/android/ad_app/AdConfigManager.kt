package com.smart.android.ad_app

import com.github.lib_autorun.ext.NetCache
import com.github.lib_autorun.ext.getMacAddress
import com.github.lib_autorun.net.NetworkHelper
import com.github.lib_autorun.net.enum.RequestMethod
import com.smart.android.ad_app.bean.AdConfigDto

object AdConfigManager {
    fun getAdConfig(onPluginInfoRequired: () -> Unit) {
        val url = "${BuildConfig.BASE_URL}api/v2/ad/delivery"
        NetworkHelper.makeRequest<AdConfigDto> (
            url,
            RequestMethod.POST,
            mapOf(
                "packageName" to NetCache.appId(),
                "channel" to NetCache.channel(),
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