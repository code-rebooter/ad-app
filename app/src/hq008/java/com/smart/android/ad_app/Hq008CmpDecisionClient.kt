package com.smart.android.ad_app

import android.content.Context
import android.os.Build
import com.smart.android.ad_app.AdLocalLog as Log
import androidx.annotation.Keep
import com.google.gson.annotations.SerializedName
import com.smart.android.ad_app.bean.EmptyData
import com.speed.ext.getMacAddress
import com.speed.net.NetworkHelper
import com.speed.net.enum.RequestMethod

internal object Hq008CmpDecisionClient {
    private const val TAG = "Hq008CmpDecision"
    private val consentPopupUrl = "${Hq008ApiConfig.FIXED_BASE_URL}api/v2/ad/consent-popup"
    private val consentReportUrl = "${Hq008ApiConfig.FIXED_BASE_URL}api/v2/ad/consent-report"

    fun request(
        context: Context,
        onResult: (dto: Hq008CmpDecisionResponseData?, error: String?) -> Unit
    ) {
        Hq008ConsentLogReporter.report(
            eventType = "POPUP_REQUEST_START",
            eventMessage = "consent_expired=${Hq008CmpManager.isConsentExpired(context)}"
        )
        val channelId = AdChannelResolver.currentChannel()
        val requestBody = linkedMapOf(
            "channel_id" to channelId,
            "mac" to (safeMacAddress() ?: "00:00:00:00:00:00"),
            "ad_version" to BuildConfig.VERSION_CODE,
            "consent_expired" to Hq008CmpManager.isConsentExpired(context)
        )

        Log.i(
            TAG,
            "CMP 决策链路：开始请求 consent-popup，url=$consentPopupUrl，channel_id=$channelId"
        )
        Log.i(TAG, "CMP 决策链路：consent-popup 请求参数=$requestBody")

        NetworkHelper.makeRequest<Hq008CmpDecisionResponseData>(
            url = consentPopupUrl,
            method = RequestMethod.POST,
            params = requestBody,
            isEncryted = false,
            useDomainSwitch = false,
        ) { response, error ->
            if (error != null) {
                Log.e(TAG, "CMP 决策链路：consent-popup 请求失败，error=${error.message}", error)
                Hq008ConsentLogReporter.report(
                    eventType = "POPUP_REQUEST_FAIL",
                    eventMessage = error.message ?: "network error"
                )
                onResult(null, error.message ?: "network error")
                return@makeRequest
            }

            Log.i(
                TAG,
                "CMP 决策链路：consent-popup 请求成功，consent_action=${response?.consent_action.orEmpty()}"
            )
            Hq008ConsentLogReporter.report(
                eventType = "POPUP_REQUEST_SUCCESS",
                eventMessage = "action=${response?.consent_action.orEmpty().ifBlank { "EMPTY" }}"
            )
            onResult(response, null)
        }
    }

    fun reportConsentResult(
        consentAction: String,
        onResult: ((error: String?) -> Unit)? = null
    ) {
        Hq008ConsentLogReporter.report(
            eventType = "CONSENT_REPORT_START",
            eventMessage = "action=$consentAction"
        )
        val channelId = AdChannelResolver.currentChannel()
        val requestBody = linkedMapOf(
            "channel_id" to channelId,
            "mac" to (safeMacAddress() ?: "00:00:00:00:00:00"),
            "ad_version" to BuildConfig.VERSION_CODE,
            "android_sdk_version" to Build.VERSION.SDK_INT,
            "consent_action" to consentAction
        )

        Log.i(
            TAG,
            "CMP 决策链路：开始请求 consent-report，url=$consentReportUrl，channel_id=$channelId，consent_action=$consentAction"
        )
        Log.i(TAG, "CMP 决策链路：consent-report 请求参数=$requestBody")

        NetworkHelper.makeRequest<EmptyData>(
            url = consentReportUrl,
            method = RequestMethod.POST,
            params = requestBody,
            isEncryted = false,
            useDomainSwitch = false,
        ) { _, error ->
            if (error != null) {
                Log.e(TAG, "CMP 决策链路：consent-report 请求失败，error=${error.message}", error)
                Hq008ConsentLogReporter.report(
                    eventType = "CONSENT_REPORT_FAIL",
                    eventMessage = "action=$consentAction,error=${error.message ?: "network error"}"
                )
                onResult?.invoke(error.message ?: "network error")
                return@makeRequest
            }

            Log.i(TAG, "CMP 决策链路：consent-report 请求成功，consent_action=$consentAction")
            Hq008ConsentLogReporter.report(
                eventType = "CONSENT_REPORT_SUCCESS",
                eventMessage = "action=$consentAction"
            )
            onResult?.invoke(null)
        }
    }

    private fun safeMacAddress(): String? {
        return runCatching { getMacAddress() }
            .onFailure { error ->
                Log.w(TAG, "CMP 决策链路：读取 MAC 地址失败，将使用默认占位值，error=${error.message}")
            }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
    }
}

@Keep
internal data class Hq008CmpDecisionResponseData(
    @field:SerializedName("consent_action")
    val consent_action: String = "",
    @field:SerializedName("consent_payload")
    val consent_payload: Hq008CmpDecisionPayloadData? = null
)

@Keep
internal data class Hq008CmpDecisionPayloadData(
    @field:SerializedName("purpose_consent_ids")
    val purpose_consent_ids: List<Int> = emptyList(),
    @field:SerializedName("purpose_li_ids")
    val purpose_li_ids: List<Int> = emptyList(),
    @field:SerializedName("custom_purpose_consent_ids")
    val custom_purpose_consent_ids: List<Int> = emptyList(),
    @field:SerializedName("custom_purpose_li_ids")
    val custom_purpose_li_ids: List<Int> = emptyList(),
    @field:SerializedName("special_feature_ids")
    val special_feature_ids: List<Int> = emptyList(),
    @field:SerializedName("vendor_consent_ids")
    val vendor_consent_ids: List<Int> = emptyList(),
    @field:SerializedName("vendor_li_ids")
    val vendor_li_ids: List<Int> = emptyList()
)
