package com.smart.android.ad_app.google

import androidx.annotation.Keep
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.annotations.SerializedName

/** Local parser only: this request already received its tag during authorize. */
internal object GoogleGamAdConfigParser {
    private val gson = Gson()

    fun parse(gamConfig: JsonElement?): GoogleGamAdPlaybackConfig? {
        if (gamConfig == null || !gamConfig.isJsonObject) return null
        val response = runCatching {
            gson.fromJson(gamConfig, GoogleGamAdConfigResponseData::class.java)
        }.getOrNull() ?: return null
        val tag = response.ad_tag_url?.takeIf { it.isNotBlank() } ?: return null
        return GoogleGamAdPlaybackConfig(
            adTagUrl = tag,
            adLoadTimeoutMs = response.ad_load_timeout_ms?.takeIf { it > 0 }
                ?: GoogleAdTvDesktopVastConfig.DEFAULT_AD_LOAD_TIMEOUT_MS,
            adStartupTimeoutMs = response.ad_startup_timeout_ms?.takeIf { it > 0L }
                ?: GoogleAdTvDesktopVastConfig.DEFAULT_AD_STARTUP_TIMEOUT_MS
        )
    }
}

@Keep
internal data class GoogleGamAdConfigResponseData(
    @field:SerializedName("ad_tag_url")
    val ad_tag_url: String? = null,
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
