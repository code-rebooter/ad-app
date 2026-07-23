package com.smart.android.ad_app

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import androidx.annotation.Keep
import com.google.gson.annotations.SerializedName
import com.speed.ext.getMacAddress
import com.speed.net.NetworkHelper
import com.speed.net.enum.RequestMethod
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Locale
import java.util.UUID

internal object Hq008SdkAuthorizeClient {
    private const val TAG = "Hq008Authorize"
    private val url = "${Hq008ApiConfig.FIXED_BASE_URL}api/v2/ad/sdk/authorize"
    private val userAgentReportCollector = HaierUserAgentReportCollector()

    fun request(
        context: Context,
        channelId: String,
        onResult: (Hq008AuthorizeResponseData?, String?) -> Unit
    ) {
        val requestId = generateRequestId()
        val requestBody = buildRequestBody(context, channelId, requestId)
        Log.i(TAG, "authorize request request_id=$requestId")
        NetworkHelper.makeRequest<Hq008AuthorizeResponseData>(
            url = url,
            method = RequestMethod.POST,
            params = requestBody,
            isEncryted = false,
            useDomainSwitch = false
        ) { response, error ->
            if (error != null) {
                Log.e(TAG, "authorize failed request_id=$requestId", error)
                onResult(null, error.message ?: "network error")
                return@makeRequest
            }

            val resolvedResponse = when {
                response == null -> Hq008AuthorizeResponseData(request_id = requestId)
                response.request_id.isBlank() -> response.copy(request_id = requestId)
                else -> response
            }
            Log.i(
                TAG,
                "authorize result request_id=${resolvedResponse.request_id}, " +
                    "authorized=${resolvedResponse.authorized}, " +
                    "next_request_seconds=${resolvedResponse.next_request_seconds}"
            )
            onResult(resolvedResponse, null)
        }
    }

    private fun buildRequestBody(
        context: Context,
        channelId: String,
        requestId: String
    ): Map<String, Any> {
        val (screenW, screenH) = getScreenResolution(context)
        val androidId = getAndroidIdAsUuid(context)
        val userAgentFields = HaierUserAgentAuthorizeFields.build(
            flavor = BuildConfig.FLAVOR,
            fallbackEffectiveUa = System.getProperty("http.agent")
        ) {
            userAgentReportCollector.collect(context)
        }
        return linkedMapOf<String, Any>(
            "request_id" to requestId,
            "uuid" to androidId,
            "channel_id" to channelId,
            "ad_version" to BuildConfig.VERSION_CODE,
            "app_id" to context.packageName,
            "app_name" to "hq008",
            "bundle" to context.packageName
        ).apply {
            putAll(userAgentFields)
            put("ifa", androidId)
            put("make", Build.MANUFACTURER.orEmpty())
            put("model", Build.MODEL.orEmpty())
            put("os", "Android")
            put("osv", Build.VERSION.RELEASE.orEmpty())
            put("language", Locale.getDefault().toString().replace("_", "-"))
            put("video_w", context.resources.displayMetrics.widthPixels)
            put("video_h", context.resources.displayMetrics.heightPixels)
            put("screen_w", screenW)
            put("screen_h", screenH)
            put("local_ip", getLocalIpAddress().orEmpty())
            put("mac", runCatching { getMacAddress() }.getOrNull().orEmpty())
        }
    }

    private fun generateRequestId(): String {
        return "client-${System.currentTimeMillis()}-${UUID.randomUUID().toString().substring(0, 8)}"
    }

    private fun getLocalIpAddress(): String? {
        return runCatching {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val addresses = interfaces.nextElement().inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (!address.isLoopbackAddress && address is Inet4Address) {
                        return@runCatching address.hostAddress
                    }
                }
            }
            null
        }.getOrNull()
    }

    private fun getScreenResolution(context: Context): Pair<Int, Int> {
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)
        return metrics.widthPixels to metrics.heightPixels
    }

    @SuppressLint("HardwareIds")
    private fun getAndroidIdAsUuid(context: Context): String {
        val raw = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            ?: return ZERO_UUID
        if (raw.isBlank() || raw.equals("9774d56d682f617c", ignoreCase = true)) {
            return ZERO_UUID
        }
        val hex = raw.replace(Regex("[^0-9a-fA-F]"), "").lowercase()
        if (hex.isBlank()) {
            return ZERO_UUID
        }
        val base32 = if (hex.length >= 32) {
            hex.substring(0, 32)
        } else {
            buildString {
                val reversed = hex.reversed()
                while (length < 32) {
                    append(hex)
                    if (length < 32) append(reversed)
                }
            }.substring(0, 32)
        }
        return "${base32.substring(0, 8)}-${base32.substring(8, 12)}-" +
            "4${base32.substring(12, 15)}-8${base32.substring(16, 19)}-" +
            base32.substring(20, 32)
    }

    private const val ZERO_UUID = "00000000-0000-4000-8000-000000000000"
}

@Keep
internal data class Hq008AuthorizeResponseData(
    @field:SerializedName("authorized")
    val authorized: Boolean = false,
    @field:SerializedName("client_ip")
    val client_ip: String = "",
    @field:SerializedName("next_request_seconds")
    val next_request_seconds: Long = 0L,
    @field:SerializedName("request_id")
    val request_id: String = ""
)
