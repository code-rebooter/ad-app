package com.smart.android.ad_app

import android.os.Build
import android.util.Log
import com.smart.android.ad_app.bean.EmptyData
import com.speed.ext.getMacAddress
import com.speed.net.NetworkHelper
import com.speed.net.enum.RequestMethod

internal object Hq008ConsentReportClient {
    private const val TAG = "Hq008ConsentReport"
    private val url = "${Hq008ApiConfig.FIXED_BASE_URL}api/v2/ad/consent-report"

    fun reportConsentResult(
        consentAction: String,
        onResult: (String?) -> Unit
    ) {
        val requestBody = linkedMapOf(
            "channel_id" to AdChannelResolver.currentChannel(),
            "mac" to runCatching { getMacAddress() }.getOrNull().orEmpty(),
            "ad_version" to BuildConfig.VERSION_CODE,
            "android_sdk_version" to Build.VERSION.SDK_INT,
            "consent_action" to consentAction
        )
        Log.i(TAG, "reporting real CMP action=$consentAction")
        NetworkHelper.makeRequest<EmptyData>(
            url = url,
            method = RequestMethod.POST,
            params = requestBody,
            isEncryted = false,
            useDomainSwitch = false
        ) { _, error ->
            if (error != null) {
                Log.e(TAG, "consent-report failed", error)
                onResult(error.message ?: "network error")
            } else {
                onResult(null)
            }
        }
    }
}
