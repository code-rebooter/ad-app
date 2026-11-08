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
                ?: GoogleAdTvDesktopVastConfig.DEFAULT_AD_STARTUP_TIMEOUT_MS,
            reportedSoundEnabled = reportedSoundEnabled(gamConfig),
            enableFakeCtr = response.enable_fake_ctr?.let { it == 1 } ?: false,
            enableGamPatch = enableGamPatch(gamConfig)
        )
    }

    /** Only an explicit numeric/string 0 enables muted reporting; invalid data stays unmuted. */
    fun reportedSoundEnabled(gamConfig: JsonElement?): Boolean = runCatching {
        val value = gamConfig?.takeIf { it.isJsonObject }?.asJsonObject
            ?.get("reported_sound_mode") ?: return@runCatching true
        if (!value.isJsonPrimitive) return@runCatching true
        val primitive = value.asJsonPrimitive
        if (!primitive.isNumber && !primitive.isString) return@runCatching true
        primitive.asString.trim() != "0"
    }.getOrDefault(true)

    /** Only an explicit numeric/string 1 enables the backend patch round trip. */
    fun enableGamPatch(gamConfig: JsonElement?): Boolean = runCatching {
        val value = gamConfig?.takeIf { it.isJsonObject }?.asJsonObject
            ?.get("enable_gam_patch") ?: return@runCatching false
        if (!value.isJsonPrimitive) return@runCatching false
        val primitive = value.asJsonPrimitive
        if (!primitive.isNumber && !primitive.isString) return@runCatching false
        primitive.asString.trim() == "1"
    }.getOrDefault(false)
}

@Keep
internal data class GoogleGamAdConfigResponseData(
    @field:SerializedName("ad_tag_url")
    val ad_tag_url: String? = null,
    @field:SerializedName("ad_load_timeout_ms")
    val ad_load_timeout_ms: Int? = null,
    @field:SerializedName("ad_startup_timeout_ms")
    val ad_startup_timeout_ms: Long? = null,
    @field:SerializedName("enable_fake_ctr")
    val enable_fake_ctr: Int? = null,
    @field:SerializedName("enable_gam_patch")
    val enable_gam_patch: Int? = null
)

internal data class GoogleGamAdPlaybackConfig(
    val adTagUrl: String,
    val adLoadTimeoutMs: Int,
    val adStartupTimeoutMs: Long,
    val reportedSoundEnabled: Boolean = true,
    val enableFakeCtr: Boolean = false,
    val enableGamPatch: Boolean = false
)
