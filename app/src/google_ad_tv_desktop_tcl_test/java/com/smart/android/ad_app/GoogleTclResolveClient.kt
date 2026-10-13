package com.smart.android.ad_app

import android.content.Context
import android.provider.Settings
import androidx.annotation.Keep
import com.google.gson.annotations.SerializedName
import com.smart.android.ad_app.AdLocalLog as Log
import com.speed.ext.getMacAddress
import com.speed.net.NetworkHelper
import com.speed.net.enum.RequestMethod
import java.util.UUID

/** Upload host identifiers to resolve; the backend prepares the returned adurl. */
internal object GoogleTclResolveClient {
    private const val IDENTIFIER_PREFS_NAME = "google_tcl_ad"
    private const val AID_KEY = "aid"
    private val resolveUrl = "${Hq008ApiConfig.FIXED_BASE_URL}api/v2/ad/google-gam/resolve"

    fun request(onResult: (GoogleTclResolveResult?, String?) -> Unit) {
        val mac = runCatching { getMacAddress() }.getOrNull()?.takeIf { it.isNotBlank() }
        val requestBody = mapOf(
            "channel_id" to BuildConfig.CHANNEL,
            "mac" to (mac ?: "00:00:00:00:00:00"),
            "rdid" to readOrCreateRdid(),
            "androidid" to readAndroidId()
        )
        NetworkHelper.makeRequest<GoogleTclResolveResult>(
            url = resolveUrl,
            method = RequestMethod.POST,
            params = requestBody,
            isEncryted = false,
            useDomainSwitch = false
        ) { response, error ->
            when {
                error != null -> onResult(null, error.message ?: "network error")
                response?.adTagUrl.isNullOrBlank() -> onResult(null, null)
                else -> {
                    Log.i("GoogleTclResolve", "取得广告链接，长度=${response!!.adTagUrl.length}；广告内部超时使用 TCL 原厂配置")
                    onResult(response, null)
                }
            }
        }
    }

    // Same UUID generation/persistence as TCL, using the host's own storage.
    @Synchronized
    private fun readOrCreateRdid(): String {
        val prefs = appContext.getSharedPreferences(IDENTIFIER_PREFS_NAME, Context.MODE_PRIVATE)
        val stored = prefs.getString(AID_KEY, "").orEmpty()
        if (stored.isNotEmpty()) return stored

        return UUID.randomUUID().toString().also { generated ->
            prefs.edit().putString(AID_KEY, generated).apply()
        }
    }

    private fun readAndroidId(): String = runCatching {
        Settings.Secure.getString(appContext.contentResolver, Settings.Secure.ANDROID_ID)
    }.getOrNull().orEmpty()
}

@Keep
internal data class GoogleTclResolveResult(
    @field:SerializedName("ad_tag_url") val adTagUrl: String = "",
    @field:SerializedName("ad_load_timeout_ms") val backendLoadTimeoutMs: Int? = null,
    @field:SerializedName("ad_startup_timeout_ms") val backendStartupTimeoutMs: Long? = null
)
