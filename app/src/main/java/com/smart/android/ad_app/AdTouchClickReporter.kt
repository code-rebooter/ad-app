package com.smart.android.ad_app

import android.os.SystemClock
import android.provider.Settings
import com.google.gson.Gson
import com.smart.android.ad_app.AdLocalLog as Log
import com.smart.android.ad_app.bean.EmptyData
import com.speed.ext.getMacAddress
import com.speed.net.NetworkHelper
import com.speed.net.enum.RequestMethod
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.UUID

internal object AdTouchClickReporter {
    private const val TAG = "AdTouchClickReporter"
    private val HQ008_REPORT_URL = "${BuildConfig.AD_FLOW_BASE_URL}api/v2/ad/report"
    private const val EVENT_TYPE_AD_CLICK = "AD_CLICK"
    private const val MESSAGE_CLICKED = "CLICKED"
    private const val CLICK_SOURCE_TOUCH = "touch"
    private const val SDK_VERSION = "2.8.02"

    private val gson = Gson()

    fun reportHq008TouchClick(adId: String?) {
        if (!BuildFlavor.isHq008Family()) {
            return
        }

        val requestId = resolveRequestId(adId)
        val hiddenMode = AdDisplayConfig.isHiddenMode()
        val diagnosticInfo = gson.toJson(
            linkedMapOf<String, Any?>(
                "adId" to adId,
                "hiddenMode" to hiddenMode,
                "clickSource" to CLICK_SOURCE_TOUCH,
                "clickElapsedMs" to SystemClock.elapsedRealtime(),
                "sdkVersion" to SDK_VERSION,
                "deviceModel" to HaierBuildIdentityNormalizer.model(),
                "deviceMake" to HaierBuildIdentityNormalizer.manufacturer()
            )
        )
        val params = linkedMapOf<String, Any>(
            "request_id" to requestId,
            "event_type" to EVENT_TYPE_AD_CLICK,
            "uuid" to resolveDeviceId(),
            "channel_id" to AdChannelResolver.currentChannel(),
            "ad_version" to BuildConfig.VERSION_CODE,
            "mac" to (safeGetMacAddress()?.takeIf { it.isNotBlank() } ?: "00:00:00:00:00:00"),
            "app_id" to appContext.packageName,
            "make" to HaierBuildIdentityNormalizer.manufacturer(),
            "model" to HaierBuildIdentityNormalizer.model(),
            "message" to MESSAGE_CLICKED,
            "diagnostic_info" to diagnosticInfo
        )

        resolveLocalIp()?.let { params["local_ip"] = it }

        Log.i(
            TAG,
            "点击上报链路：准备上报广告触摸点击，requestId=$requestId，adId=${adId.orEmpty()}，hidden=$hiddenMode"
        )
        NetworkHelper.makeRequest<EmptyData>(
            url = HQ008_REPORT_URL,
            method = RequestMethod.POST,
            params = params,
            isEncryted = false,
            useDomainSwitch = false,
        ) { _, error ->
            if (error != null) {
                Log.e(TAG, "点击上报链路：广告触摸点击上报失败，requestId=$requestId，error=${error.message}", error)
            } else {
                Log.i(TAG, "点击上报链路：广告触摸点击上报成功，requestId=$requestId")
            }
        }
    }

    private fun resolveRequestId(adId: String?): String {
        return adId?.takeIf { it.isNotBlank() }
            ?: "hq008-click-${System.currentTimeMillis()}-${UUID.randomUUID()}"
    }

    private fun resolveDeviceId(): String {
        return Settings.Secure.getString(
            appContext.contentResolver,
            Settings.Secure.ANDROID_ID
        ) ?: "unknown_device"
    }

    private fun resolveLocalIp(): String? {
        return runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .flatMap { it.inetAddresses.toList() }
                .firstOrNull { !it.isLoopbackAddress && it is Inet4Address }
                ?.hostAddress
        }.getOrNull()
    }

    private fun safeGetMacAddress(): String? {
        return runCatching { getMacAddress() }
            .onFailure { error -> Log.w(TAG, "点击上报链路：读取 MAC 地址失败，将使用默认占位值，error=${error.message}") }
            .getOrNull()
    }
}
