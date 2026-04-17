package com.smart.android.ad_app

import android.provider.Settings
import android.util.Log
import com.smart.android.ad_app.bean.AdConfigDto
import com.smart.android.ad_app.bean.EmptyData
import io.github.lib_autorun.ext.getMacAddress
import io.github.lib_autorun.log.printLog
import io.github.lib_autorun.net.NetworkHelper
import io.github.lib_autorun.net.enum.RequestMethod

object AdConfigManager {
    private const val TAG = "AdConfigManager"
    private var currentAdId: String? = null  // 保存当前广告 adId

    fun hasOverlayPermission(): Boolean {
        return Settings.canDrawOverlays(appContext)
    }
    fun getAdConfig(adType: AdType) {
        Log.i(
            TAG,
            "getAdConfig adType=$adType package=${appContext.packageName} channel=${BuildConfig.CHANNEL} hidden=${AdDisplayConfig.isHiddenMode()}"
        )

        if (BuildConfig.FLAVOR == "hq008" && adType == AdType.FLOATING) {
            requestHq008Authorize()
            return
        }

        // 只有悬浮窗广告才校验权限
        if (adType == AdType.FLOATING && !hasOverlayPermission()) {
            Log.w(TAG, "Skip FLOATING request: overlay permission missing.")
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
                Log.e(TAG, "Ad request failed for $adType: ${error.message}", error)
               "广告请求失败: ${error.message}".printLog()
                return@makeRequest
            }

            if (dto?.adId.isNullOrEmpty()) {
                Log.w(TAG, "No available ad for $adType.")
                "无可用广告".printLog()
                return@makeRequest
            }

            Log.i(
                TAG,
                "Ad request success adType=$adType adId=${dto?.adId} position=${dto?.position} size=${dto?.floatingWidth}x${dto?.floatingHeight}"
            )
            setCurrentAdId(dto.adId!!)
            dispatchAd(adType, dto)
        }
    }


    private fun dispatchAd(adType: AdType, dto: AdConfigDto) {
        Log.i(TAG, "dispatchAd adType=$adType adId=${dto.adId} hidden=${AdDisplayConfig.isHiddenMode()}")
        when (adType) {
            AdType.SPLASH -> AdRenderer.showSplashAd(dto)
            AdType.FLOATING -> AdRenderer.showFloatingAd(dto)
        }
    }

    private fun requestHq008Authorize() {
        Hq008SdkAuthorizeClient.request(
            context = appContext,
            channelId = BuildConfig.CHANNEL
        ) { dto, error ->
            if (error != null) {
                Log.e(TAG, "hq008 authorize failed: $error")
                return@request
            }
            dto ?: return@request

            AdDisplayConfig.setRemoteHiddenMode(dto.hidden_mode)

            if (!dto.authorized) {
                Log.i(TAG, "hq008 authorize denied. request_id=${dto.request_id}")
                return@request
            }

            Log.i(
                TAG,
                "hq008 authorize success request_id=${dto.request_id} hidden_mode=${dto.hidden_mode} next_request_seconds=${dto.next_request_seconds}"
            )

            dispatchAd(
                AdType.FLOATING,
                AdConfigDto(
                    adId = dto.request_id,
                    adType = AdType.FLOATING.value,
                    adUrl = null,
                    contentType = null,
                    displayDuration = 0,
                    floatingHeight = 131,
                    floatingWidth = 210,
                    floatingX = 0,
                    floatingY = 0,
                    imageUrl = null,
                    isClosable = 1,
                    isCountdownVisible = false,
                    position = 0,
                    videoUrl = null
                )
            )
        }
    }

    fun setCurrentAdId(adId: String) {
        currentAdId = adId
    }

    fun reportAdStatus(statusStr: String, errorInfo: String, adId: String? = null) {
        val resolvedAdId = adId ?: currentAdId ?: run {
            "adId 为空，上报失败".printLog()
            return
        }

        "上报广告状态".printLog()
        val url = "${BuildConfig.BASE_URL}api/v2/ad/task/report"
        NetworkHelper.makeRequest<EmptyData>(
            url,
            RequestMethod.POST,
            mapOf(
                "packageName" to appContext.packageName,
                "channel" to BuildConfig.CHANNEL,
                "macAddress" to (getMacAddress() ?: ""),
                "status" to statusStr,
                "result" to errorInfo,
                "adId" to resolvedAdId,
            ),
            isEncryted = false
        ) { _, error ->
            if (error != null) {
                Log.e(TAG, "reportAdStatus failed status=$statusStr adId=$resolvedAdId error=${error.message}", error)
                "请求失败".printLog()
            } else {
                Log.i(TAG, "reportAdStatus success status=$statusStr adId=$resolvedAdId result=$errorInfo")
                "请求成功-${statusStr}, ${errorInfo}".printLog()
            }
        }
    }
}
