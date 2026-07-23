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
    private val url = "${Hq008ApiConfig.FIXED_BASE_URL}api/v2/ad/sdk/flow-control"

    fun request(
        context: Context,
        channelId: String,
        onResult: (Hq008SdkFlowControlData?, String?) -> Unit
    ) {
        val requestBody = linkedMapOf(
            "channel_id" to channelId,
            "mac" to runCatching { getMacAddress() }.getOrNull().orEmpty(),
            "ad_version" to BuildConfig.VERSION_CODE,
            "android_sdk_version" to android.os.Build.VERSION.SDK_INT
        )
        Log.i(TAG, "request flow-control channel_id=$channelId")
        NetworkHelper.makeRequest<Hq008SdkFlowControlData>(
            url = url,
            method = RequestMethod.POST,
            params = requestBody,
            isEncryted = false,
            useDomainSwitch = false
        ) { response, error ->
            if (error != null) {
                Log.e(TAG, "flow-control failed", error)
                onResult(null, error.message ?: "network error")
            } else {
                Log.i(TAG, "flow-control enabled=${response?.enabled == true}")
                onResult(response, null)
            }
        }
    }
}

@Keep
internal data class Hq008SdkFlowControlData(
    @field:SerializedName("enabled")
    val enabled: Boolean = false
)
