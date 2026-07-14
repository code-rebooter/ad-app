package com.smart.android.ad_app.google

import android.util.Log
import androidx.annotation.Keep
import com.google.gson.annotations.SerializedName
import com.smart.android.ad_app.Hq008ApiConfig
import com.speed.net.NetworkHelper
import com.speed.net.enum.RequestMethod

internal object GoogleGamAdConfigClient {
    private const val TAG = "GoogleGamConfig"
    private const val CHANNEL_ID = "GOOGLE_AD_TV_DESKTOP"
    private val resolveUrl = "${Hq008ApiConfig.FIXED_BASE_URL}api/v2/ad/google-gam/resolve"

    fun request(onResult: (config: GoogleGamAdPlaybackConfig?, error: String?) -> Unit) {
        val requestBody = mapOf(
            "channel_id" to CHANNEL_ID
        )
        Log.i(TAG, "开始请求 Google GAM 配置，channel_id=$CHANNEL_ID")

        NetworkHelper.makeRequest<GoogleGamAdConfigResponseData>(
            url = resolveUrl,
            method = RequestMethod.POST,
            params = requestBody,
            isEncryted = false,
            useDomainSwitch = false
        ) { response, error ->
            if (error != null) {
                val errorMessage = error.message ?: "network error"
                Log.e(TAG, "Google GAM 配置请求失败，error=$errorMessage", error)
                onResult(null, errorMessage)
                return@makeRequest
            }

            val adTagUrl = response?.ad_tag_url?.takeIf { it.isNotBlank() }
            if (adTagUrl == null) {
                Log.i(TAG, "Google GAM 配置无可用广告链接，直接跳过广告")
                onResult(null, null)
                return@makeRequest
            }

            val adLoadTimeoutMs = response.ad_load_timeout_ms
                ?.takeIf { it > 0 }
                ?: GoogleAdTvDesktopVastConfig.DEFAULT_AD_LOAD_TIMEOUT_MS
            val adStartupTimeoutMs = response.ad_startup_timeout_ms
                ?.takeIf { it > 0L }
                ?: GoogleAdTvDesktopVastConfig.DEFAULT_AD_STARTUP_TIMEOUT_MS

            Log.i(
                TAG,
                "Google GAM 配置请求成功，adLoadTimeoutMs=$adLoadTimeoutMs，adStartupTimeoutMs=$adStartupTimeoutMs"
            )
            onResult(
                GoogleGamAdPlaybackConfig(
                    adTagUrl = adTagUrl,
                    adLoadTimeoutMs = adLoadTimeoutMs,
                    adStartupTimeoutMs = adStartupTimeoutMs
                ),
                null
            )
        }
    }
}

@Keep
internal data class GoogleGamAdConfigResponseData(
    @field:SerializedName("ad_tag_url")
    val ad_tag_url: String = "",
    @field:SerializedName("ad_load_timeout_ms")
    val ad_load_timeout_ms: Int? = null,
    @field:SerializedName("ad_startup_timeout_ms")
    val ad_startup_timeout_ms: Long? = null
)

internal data class GoogleGamAdPlaybackConfig(
    val adTagUrl: String,
    val adLoadTimeoutMs: Int,
    val adStartupTimeoutMs: Long
)
