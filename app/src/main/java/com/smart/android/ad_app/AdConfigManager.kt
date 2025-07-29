package com.smart.android.ad_app

import com.github.lib_autorun.ext.NetCache
import com.github.lib_autorun.ext.getMacAddress
import com.github.lib_autorun.net.NetworkHelper
import com.github.lib_autorun.net.enum.RequestMethod

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
                if(dto?.adId.isNullOrEmpty()){
                    //可以展示广告
                }
            }
        }
    }
}