package com.smart.android.ad_app

import android.content.Context

object Hq008CmpManager {
    data class SaveSettingsPayload(
        val purposeConsentIds: List<Int> = emptyList(),
        val purposeLiIds: List<Int> = emptyList(),
        val customPurposeConsentIds: List<Int> = emptyList(),
        val customPurposeLiIds: List<Int> = emptyList(),
        val specialFeatureIds: List<Int> = emptyList(),
        val vendorConsentIds: List<Int> = emptyList(),
        val vendorLiIds: List<Int> = emptyList()
    )

    data class RemoteCmpDecision(
        val consentAction: String,
        val consentPayload: SaveSettingsPayload? = null
    )

    fun init(context: Context) = Unit

    fun setRemoteDecisionProvider(
        provider: ((Context, (RemoteCmpDecision?) -> Unit) -> Unit)?
    ) = Unit

    fun runWhenConsentStateReady(action: () -> Unit) {
        action()
    }

    fun applyRemoteCmpDecisionIfNeeded(context: Context, onComplete: () -> Unit) {
        onComplete()
    }

    fun getConsentString(): String? = null

    fun isConsentExpired(context: Context): Boolean = false

    fun setDebugDeviceIdOverride(deviceId: String?) = Unit

    fun getDebugDeviceIdOverride(): String? = null

    fun showCmpPopup(context: Context, onDismiss: () -> Unit) {
        onDismiss()
    }

    fun debugRunReflectiveSdkAction(
        context: Context,
        action: String,
        onResult: (String) -> Unit
    ) {
        onResult("haier_lsap_skip_cmp")
    }
}
