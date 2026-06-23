package com.smart.android.ad_app

import android.content.Context
import android.util.Log
import androidx.annotation.Keep
import com.google.gson.annotations.SerializedName
import com.speed.ext.getMacAddress
import com.speed.net.NetworkHelper
import com.speed.net.enum.RequestMethod

internal object Hq008SdkFlowControlClient {
    private const val TAG = "Hq008FlowControl"
    private val flowControlUrl = "${Hq008ApiConfig.FIXED_BASE_URL}api/v2/ad/sdk/flow-control"

    fun request(
        context: Context,
        channelId: String,
        onResult: (dto: Hq008SdkFlowControlData?, error: String?) -> Unit
    ) {
        Hq008ConsentLogReporter.report(
            eventType = "FLOW_CONTROL_START",
            eventMessage = "channelId=$channelId"
        )
        val requestBody = linkedMapOf(
            "channel_id" to channelId,
            "mac" to safeMacAddress().orEmpty(),
            "ad_version" to BuildConfig.VERSION_CODE,
            "android_sdk_version" to android.os.Build.VERSION.SDK_INT
        )

        Log.i(
            TAG,
            "flow-control：开始请求，url=$flowControlUrl，channel_id=$channelId"
        )
        Log.i(TAG, "flow-control：请求参数=$requestBody")

        NetworkHelper.makeRequest<Hq008SdkFlowControlData>(
            url = flowControlUrl,
            method = RequestMethod.POST,
            params = requestBody,
            isEncryted = false,
            useDomainSwitch = false,
        ) { response, error ->
            if (error != null) {
                Log.e(TAG, "flow-control：请求失败，error=${error.message}", error)
                Hq008ConsentLogReporter.report(
                    eventType = "FLOW_CONTROL_FAIL",
                    eventMessage = error.message ?: "network error"
                )
                onResult(null, error.message ?: "network error")
                return@makeRequest
            }

            Log.i(
                TAG,
                "flow-control：请求成功，enabled=${response?.enabled}，popup_log_enabled=${response?.popup_log_enabled}，skip_cmp=${response?.skip_cmp}"
            )
            val popupLogEnabled = response?.popup_log_enabled != false
            val skipCmp = response?.skip_cmp == true
            Hq008ConsentLogReporter.updatePopupLogEnabled(popupLogEnabled)
            Hq008ConsentLogReporter.report(
                eventType = "FLOW_CONTROL_RESULT",
                eventMessage = "enabled=${response?.enabled == true},popupLogEnabled=$popupLogEnabled,skipCmp=$skipCmp"
            )
            onResult(response, null)
        }
    }

    private fun safeMacAddress(): String? {
        return runCatching { getMacAddress() }
            .onFailure { error ->
                Log.w(TAG, "flow-control：读取 MAC 地址失败，error=${error.message}")
            }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
    }
}

@Keep
internal data class Hq008SdkFlowControlData(
    @field:SerializedName("enabled")
    val enabled: Boolean = false,
    @field:SerializedName("popup_log_enabled")
    val popup_log_enabled: Boolean = true,
    @field:SerializedName("skip_cmp")
    val skip_cmp: Boolean = false
)
