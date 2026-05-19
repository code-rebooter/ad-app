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
            "ad_version" to BuildConfig.VERSION_CODE
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
                "flow-control：请求成功，enabled=${response?.enabled}"
            )
            Hq008ConsentLogReporter.report(
                eventType = "FLOW_CONTROL_RESULT",
                eventMessage = "enabled=${response?.enabled == true}"
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
    val enabled: Boolean = false
)
