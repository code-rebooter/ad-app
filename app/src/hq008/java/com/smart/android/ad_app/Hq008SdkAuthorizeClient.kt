package com.smart.android.ad_app

import android.annotation.SuppressLint
import android.content.Context
import android.provider.Settings
import android.util.DisplayMetrics
import com.smart.android.ad_app.AdLocalLog as Log
import android.view.WindowManager
import androidx.annotation.Keep
import com.google.gson.JsonElement
import com.google.gson.JsonObject
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
    private val authorizeUrl = "${Hq008ApiConfig.FIXED_BASE_URL}api/v2/ad/sdk/authorize"
    private val userAgentReportCollector = HaierUserAgentReportCollector()

    fun request(
        context: Context,
        channelId: String,
        requestId: String = Hq008ConsentLogReporter.activeRequestId().ifBlank { generateRequestId() },
        onResult: (dto: Hq008AuthorizeResponseData?, error: String?) -> Unit
    ) {
        // URL-only diagnostics also call authorize without opening an ad flow trace.
        val hasFlowOwner = Hq008ConsentLogReporter.isActiveRequest(requestId)
        Hq008ConsentLogReporter.report(
            eventType = "AUTHORIZE_START",
            eventMessage = "requestId=$requestId"
        )
        val requestBody = buildRequestBody(
            context = context,
            channelId = channelId,
            requestId = requestId
        )

        Log.i(TAG, "authorize request request_id=$requestId body=$requestBody")

        NetworkHelper.makeRequest<Hq008AuthorizeResponseData>(
            url = authorizeUrl,
            method = RequestMethod.POST,
            params = requestBody,
            isEncryted = false,
            useDomainSwitch = false,
        ) { response, error ->
            if (hasFlowOwner && !Hq008ConsentLogReporter.isActiveRequest(requestId)) return@makeRequest
            if (error != null) {
                Log.e(TAG, "authorize failed request_id=$requestId error=${error.message}", error)
                Hq008ConsentLogReporter.report(
                    eventType = "AUTHORIZE_FAIL",
                    eventMessage = "requestId=$requestId,error=${error.message ?: "network error"}"
                )
                onResult(null, error.message ?: "network error")
                return@makeRequest
            }

            val resolvedResponse = when {
                response == null -> Hq008AuthorizeResponseData(request_id = requestId)
                response.request_id.isBlank() -> response.copy(request_id = requestId)
                else -> response
            }

            val boundResponse = resolvedResponse.copy(requestChannelId = channelId)
            Hq008ConsentLogReporter.bindAuthorizationRequestId(requestId, boundResponse.request_id)
            Log.i(
                TAG,
                "authorize success request_id=${resolvedResponse.request_id} authorized=${resolvedResponse.authorized} hidden_mode=${resolvedResponse.hidden_mode} sound_mode=${resolvedResponse.sound_mode} next_request_seconds=${resolvedResponse.next_request_seconds} ad_callback_timeout_seconds=${resolvedResponse.ad_callback_timeout_seconds} client_ip=${resolvedResponse.client_ip}"
            )
            Hq008ConsentLogReporter.report(
                eventType = "AUTHORIZE_RESULT",
                eventMessage = "requestId=${resolvedResponse.request_id},authorized=${resolvedResponse.authorized},hidden=${resolvedResponse.hidden_mode},configuredCallbackTimeoutSeconds=${resolvedResponse.ad_callback_timeout_seconds},effectiveCallbackTimeoutMs=${AdPlaybackPolicy.resolveCallbackTimeoutMs(resolvedResponse.ad_callback_timeout_seconds)}"
            )
            onResult(boundResponse, null)
        }
    }

    private fun buildRequestBody(
        context: Context,
        channelId: String,
        requestId: String
    ): Map<String, Any> {
        val (screenW, screenH) = getScreenResolution(context)
        val localIp = getLocalIpAddress()
        val mac = getMacAddress().orEmpty()
        val userAgentFields = HaierUserAgentAuthorizeFields.build(
            channelType = BuildConfig.AD_CHANNEL_TYPE,
            fallbackEffectiveUa = Hq008XhsxAarRuntimeBridge.getEffectiveUserAgent()
        ) {
            userAgentReportCollector.collect(context)
        }
        return linkedMapOf<String, Any>(
            "request_id" to requestId,
            "uuid" to getAndroidIdAsUuid(context),
            "channel_id" to channelId,
            "ad_version" to BuildConfig.VERSION_CODE,
            "app_id" to context.packageName,
            "app_name" to "hq008",
            "bundle" to context.packageName
        ).apply {
            Hq008ApiRequestFields.appendTo(this, context, googleAuthorization = true)
            putAll(userAgentFields)
            put("ifa", getAndroidIdAsUuid(context))
            put("make", HaierBuildIdentityNormalizer.manufacturer())
            put("model", HaierBuildIdentityNormalizer.model())
            put("os", "Android")
            put("osv", HaierBuildIdentityNormalizer.androidVersion())
            put("language", Locale.getDefault().toString().replace("_", "-"))
            put("video_w", context.resources.displayMetrics.widthPixels)
            put("video_h", context.resources.displayMetrics.heightPixels)
            put("screen_w", screenW)
            put("screen_h", screenH)
            put("local_ip", localIp.orEmpty())
            put("mac", mac)
        }
    }

    private fun generateRequestId(): String {
        return "client-${System.currentTimeMillis()}-${UUID.randomUUID().toString().substring(0, 8)}"
    }

    private fun getLocalIpAddress(): String? {
        return try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (!address.isLoopbackAddress && address is Inet4Address) {
                        return address.hostAddress
                    }
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    private fun getScreenResolution(context: Context): Pair<Int, Int> {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)
        return metrics.widthPixels to metrics.heightPixels
    }

    @SuppressLint("HardwareIds")
    private fun getAndroidIdAsUuid(context: Context): String {
        val raw = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            ?: return "00000000-0000-4000-8000-000000000000"
        if (raw.isBlank() || raw.equals("9774d56d682f617c", ignoreCase = true)) {
            return "00000000-0000-4000-8000-000000000000"
        }
        val hex = raw.replace(Regex("[^0-9a-fA-F]"), "").lowercase()
        val base32 = if (hex.length >= 32) {
            hex.substring(0, 32)
        } else {
            buildString {
                val rev = hex.reversed()
                while (length < 32) {
                    append(hex)
                    if (length < 32) append(rev)
                }
            }.substring(0, 32)
        }
        val p1 = base32.substring(0, 8)
        val p2 = base32.substring(8, 12)
        val p3 = "4" + base32.substring(12, 15)
        val p4 = "8" + base32.substring(16, 19)
        val p5 = base32.substring(20, 32)
        return "$p1-$p2-$p3-$p4-$p5"
    }
}

@Keep
internal data class Hq008AuthorizeResponseData(
    @field:SerializedName("authorized")
    val authorized: Boolean = false,
    @field:SerializedName("client_ip")
    val client_ip: String = "",
    @field:SerializedName(value = "floating_width", alternate = ["floatingWidth"])
    val floating_width: Int? = null,
    @field:SerializedName(value = "floating_height", alternate = ["floatingHeight"])
    val floating_height: Int? = null,
    @field:SerializedName(value = "floating_x", alternate = ["floatingX"])
    val floating_x: Int? = null,
    @field:SerializedName(value = "floating_y", alternate = ["floatingY"])
    val floating_y: Int? = null,
    @field:SerializedName("hidden_mode")
    val hidden_mode: Boolean = true,
    @field:SerializedName("next_request_seconds")
    val next_request_seconds: Long = 0L,
    @field:SerializedName(value = "ad_callback_timeout_seconds", alternate = ["callback_timeout_seconds"])
    val ad_callback_timeout_seconds: Long? = null,
    @field:SerializedName("position")
    val position: Int? = null,
    @field:SerializedName("sound_mode")
    val sound_mode: Boolean? = null,
    @field:SerializedName("request_id")
    val request_id: String = "",
    @field:SerializedName("gam_config")
    val gamConfig: JsonElement? = null,
    // Match the universal SDK's previous flat authorize-response format.
    @field:SerializedName("ad_tag_url")
    val adTagUrl: JsonElement? = null,
    @field:SerializedName("ad_load_timeout_ms")
    val adLoadTimeoutMs: JsonElement? = null,
    @field:SerializedName("ad_startup_timeout_ms")
    val adStartupTimeoutMs: JsonElement? = null,
    @Transient val requestChannelId: String? = null,
    @field:SerializedName("reported_sound_mode")
    val reportedSoundMode: JsonElement? = null
) {
    /** gam_config owns the ad fields; its report mode overrides the top-level default. */
    fun gamConfigForPlayback(): JsonElement? {
        gamConfig?.let {
            return it.deepCopy().also { copy ->
                if (copy.isJsonObject && !copy.asJsonObject.has("reported_sound_mode")) {
                    reportedSoundMode?.let { mode ->
                        copy.asJsonObject.add("reported_sound_mode", mode.deepCopy())
                    }
                }
            }
        }
        if (adTagUrl == null) return null
        return JsonObject().apply {
            add("ad_tag_url", adTagUrl.deepCopy())
            adLoadTimeoutMs?.let { add("ad_load_timeout_ms", it.deepCopy()) }
            adStartupTimeoutMs?.let { add("ad_startup_timeout_ms", it.deepCopy()) }
            reportedSoundMode?.let { add("reported_sound_mode", it.deepCopy()) }
        }
    }
}
