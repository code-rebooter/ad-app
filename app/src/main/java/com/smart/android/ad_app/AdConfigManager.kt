package com.smart.android.ad_app

import android.provider.Settings
import android.util.Log
import com.smart.android.ad_app.bean.AdConfigDto
import com.smart.android.ad_app.bean.EmptyData
import com.speed.ext.getMacAddress
import com.speed.log.printLog
import com.speed.net.NetworkHelper
import com.speed.net.enum.RequestMethod

object AdConfigManager {
    private const val TAG = "AdConfigManager"

    private var currentAdId: String? = null

    fun hasOverlayPermission(): Boolean = Settings.canDrawOverlays(appContext)

    fun getAdConfig(adType: AdType) {
        val channel = AdChannelResolver.resolve()
        Log.i(
            TAG,
            "Requesting ad, type=$adType, package=${appContext.packageName}, channel=${channel.value}"
        )

        if (BuildConfig.HQ008_LOCAL_INTEGRATION) {
            requestHq008LocalAd(adType, channel.value)
            return
        }

        if (adType == AdType.FLOATING && !hasOverlayPermission()) {
            Log.w(TAG, "Skip FLOATING request: overlay permission missing")
            "没有悬浮窗权限，跳过 FLOATING 广告请求".printLog()
            return
        }
        requestBackendAd(adType, channel.value)
    }

    private fun requestHq008LocalAd(adType: AdType, channel: String) {
        if (adType == AdType.FLOATING && !hasOverlayPermission()) {
            Log.w(TAG, "Skip HQ008 FLOATING request: overlay permission missing")
            return
        }

        val flowToken = if (adType == AdType.FLOATING) {
            Hq008FloatingFlowGuard.tryEnter(channel) ?: run {
                Log.i(TAG, "A visible HQ008 floating-ad request is already in progress")
                return
            }
        } else {
            null
        }

        Hq008CmpManager.ensureConsent { cmpReady ->
            if (!cmpReady) {
                Log.w(TAG, "CMP did not complete; skipping this ad request")
                flowToken?.let { token ->
                    Hq008FloatingFlowGuard.finish(token, "cmp_unavailable")
                }
                return@ensureConsent
            }
            Log.i(TAG, "CMP flow finished; requesting the ad through the public TCL SDK")
            AdRenderer.showHq008LocalAd(
                adType = adType,
                onFloatingFlowFinished = flowToken?.let { token ->
                    { Hq008FloatingFlowGuard.finish(token, "floating_ad_finished") }
                }
            )
        }
    }

    private fun requestBackendAd(adType: AdType, channel: String) {
        val url = "${BuildConfig.BASE_URL}api/v2/ad/delivery"
        NetworkHelper.makeRequest<AdConfigDto>(
            url,
            RequestMethod.POST,
            mapOf(
                "packageName" to appContext.packageName,
                "channel" to channel,
                "macAddress" to (getMacAddress() ?: ""),
                "adType" to adType.value
            ),
            isEncryted = false
        ) { dto, error ->
            if (error != null) {
                Log.e(TAG, "Ad request failed for $adType", error)
                return@makeRequest
            }
            if (dto?.adId.isNullOrEmpty()) {
                Log.w(TAG, "No available ad for $adType")
                return@makeRequest
            }
            setCurrentAdId(dto!!.adId!!)
            dispatchAd(adType, dto)
        }
    }

    private fun dispatchAd(
        adType: AdType,
        dto: AdConfigDto,
        onFloatingFlowFinished: (() -> Unit)? = null
    ) {
        when (adType) {
            AdType.SPLASH -> AdRenderer.showSplashAd(dto)
            AdType.FLOATING -> AdRenderer.showFloatingAd(dto, onFloatingFlowFinished)
        }
    }

    fun setCurrentAdId(adId: String) {
        currentAdId = adId
    }

    fun reportAdStatus(statusStr: String, errorInfo: String, adId: String? = null) {
        val resolvedAdId = adId ?: currentAdId ?: return
        NetworkHelper.makeRequest<EmptyData>(
            "${BuildConfig.BASE_URL}api/v2/ad/task/report",
            RequestMethod.POST,
            mapOf(
                "packageName" to appContext.packageName,
                "channel" to AdChannelResolver.currentChannel(),
                "macAddress" to (getMacAddress() ?: ""),
                "status" to statusStr,
                "result" to errorInfo,
                "adId" to resolvedAdId
            ),
            isEncryted = false
        ) { _, error ->
            if (error != null) {
                Log.e(TAG, "Ad status report failed", error)
            }
        }
    }
}
