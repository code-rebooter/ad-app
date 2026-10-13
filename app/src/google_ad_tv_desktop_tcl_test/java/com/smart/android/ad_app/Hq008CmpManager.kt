package com.smart.android.ad_app

import android.content.Context

/** Retains host business decisions without loading or fabricating a UMP implementation. */
object Hq008CmpManager {
    @Volatile private var provider: ((Context, (RemoteCmpDecision?) -> Unit) -> Unit)? = null
    @Volatile private var canContinue = false

    data class SaveSettingsPayload(
        val purposeConsentIds: List<Int> = emptyList(),
        val purposeLiIds: List<Int> = emptyList(),
        val customPurposeConsentIds: List<Int> = emptyList(),
        val customPurposeLiIds: List<Int> = emptyList(),
        val specialFeatureIds: List<Int> = emptyList(),
        val vendorConsentIds: List<Int> = emptyList(),
        val vendorLiIds: List<Int> = emptyList()
    )
    data class RemoteCmpDecision(val consentAction: String, val consentPayload: SaveSettingsPayload? = null)

    fun init(context: Context) = Unit
    fun setRemoteDecisionProvider(value: ((Context, (RemoteCmpDecision?) -> Unit) -> Unit)?) { provider = value }
    fun runWhenConsentStateReady(action: () -> Unit) { action() }

    fun applyRemoteCmpDecisionIfNeeded(context: Context, onComplete: () -> Unit) {
        canContinue = false
        val source = provider
        if (source == null) {
            recordDecision(null, GoogleTclBusinessDecision.evaluate(null))
            onComplete()
            return
        }
        source(context.applicationContext) { decision -> applyDecision(decision, onComplete) }
    }

    private fun applyDecision(decision: RemoteCmpDecision?, onComplete: () -> Unit) {
        val result = GoogleTclBusinessDecision.evaluate(decision?.consentAction)
        canContinue = result.canContinue
        recordDecision(decision?.consentAction, result)
        val report = result.reportAction
        if (report == null) onComplete()
        else Hq008CmpDecisionClient.reportConsentResult(report) { onComplete() }
    }

    private fun recordDecision(action: String?, result: GoogleTclBusinessDecision.Result) {
        Hq008ConsentLogReporter.report(
            eventType = if (result.canContinue) "CMP_DECISION_SKIPPED" else "CMP_DECISION_FAIL",
            eventMessage = "action=${action.orEmpty()},source=host_business,canContinue=${result.canContinue},reason=${result.reason},sdkActionExecuted=false"
        )
    }

    fun canContinueAfterRemoteDecision(): Boolean = canContinue
    fun getConsentString(): String? = null
    fun isConsentExpired(context: Context): Boolean = true
    fun debugRunReflectiveSdkAction(context: Context, action: String, onResult: (String) -> Unit) {
        applyDecision(RemoteCmpDecision(action)) {
            onResult("action=$action,canContinue=$canContinue,sdkActionExecuted=false")
        }
    }
}
