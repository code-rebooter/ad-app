package com.smart.android.ad_app

import android.content.Context
import com.smart.android.ad_app.AdLocalLog as Log
import java.util.Locale

object Hq008CmpManager {
    private const val TAG = "GoogleUmpCmpGate"
    private const val ACTION_ACCEPT_ALL = "ACCEPT_ALL"
    private const val ACTION_REJECT = "REJECT"
    private const val ACTION_SAVE_SETTINGS = "SAVE_SETTINGS"
    private const val ACTION_MAYBE_LATER = "MAYBE_LATER"
    private const val ACTION_SKIP_ALREADY_DECIDED = "SKIP_ALREADY_DECIDED"
    private const val MAYBE_LATER_COOLDOWN_MS = 0L
    private const val PREFS_NAME = "google_ump_cmp_gate"
    private const val KEY_MAYBE_LATER_RECORDED_AT = "maybe_later_recorded_at"
    private const val KEY_LAST_APPLIED_REMOTE_ACTION = "last_applied_remote_action"

    @Volatile
    private var remoteDecisionProvider: ((Context, (RemoteCmpDecision?) -> Unit) -> Unit)? = null

    @Volatile
    private var continueAfterRemoteDecision = true

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
    ) {
        remoteDecisionProvider = provider
    }

    fun runWhenConsentStateReady(action: () -> Unit) {
        action()
    }

    fun applyRemoteCmpDecisionIfNeeded(context: Context, onComplete: () -> Unit) {
        val appContext = context.applicationContext
        continueAfterRemoteDecision = true
        Hq008ConsentLogReporter.report(
            eventType = "UMP_GATE_START",
            eventMessage = "source=google_ump,consentLength=${getConsentString()?.length ?: 0}"
        )

        if (isMaybeLaterCoolingDown(appContext)) {
            Log.i(TAG, "UMP 门禁：MAYBE_LATER 冷却中，本轮不触发 UMP 表单")
            continueAfterRemoteDecision = false
            Hq008ConsentLogReporter.report(
                eventType = "UMP_GATE_STOP",
                eventMessage = "reason=maybe_later_cooldown"
            )
            onComplete()
            return
        }

        GoogleUmpConsentManager.requestConsent(
            context = appContext,
            action = GoogleUmpConsentManager.ConsentAction.CHECK_ONLY
        ) { result ->
            Hq008ConsentLogReporter.report(
                eventType = "UMP_GATE_STATUS",
                eventMessage = result.toGateEventMessage()
            )
            if (result.canRequestAds) {
                if (result.privacyOptionsStatus == "REQUIRED") {
                    Log.i(TAG, "UMP 门禁：当前可请求广告且 privacy options 可用，继续请求远端 consent-popup 以支持静默切换")
                    requestRemoteDecision(
                        appContext = appContext,
                        reason = "privacy_options_required",
                        canContinueWithoutDecision = true,
                        onComplete = onComplete
                    )
                } else {
                    Log.i(TAG, "UMP 门禁：当前可请求广告且无需 privacy options，跳过远端 consent-popup 决策")
                    continueAfterRemoteDecision = true
                    onComplete()
                }
                return@requestConsent
            }

            if (!result.formAvailable) {
                Log.w(TAG, "UMP 门禁：当前不可请求广告且表单不可用，本轮阻断广告，${result.toGateEventMessage()}")
                continueAfterRemoteDecision = false
                Hq008ConsentLogReporter.report(
                    eventType = "UMP_GATE_STOP",
                    eventMessage = "reason=form_unavailable,${result.toGateEventMessage()}"
                )
                onComplete()
                return@requestConsent
            }

            requestRemoteDecision(
                appContext = appContext,
                reason = "consent_required",
                canContinueWithoutDecision = false,
                onComplete = onComplete
            )
        }
    }

    private fun requestRemoteDecision(
        appContext: Context,
        reason: String,
        canContinueWithoutDecision: Boolean,
        onComplete: () -> Unit
    ) {
        val provider = remoteDecisionProvider
        if (provider == null) {
            Log.w(TAG, "UMP 门禁：未配置远端 consent-popup 决策提供器，canContinueWithoutDecision=$canContinueWithoutDecision")
            continueAfterRemoteDecision = canContinueWithoutDecision
            Hq008ConsentLogReporter.report(
                eventType = "UMP_PROVIDER_MISSING",
                eventMessage = "remoteDecisionProvider=null,reason=$reason,canContinueWithoutDecision=$canContinueWithoutDecision"
            )
            onComplete()
            return
        }

        Hq008ConsentLogReporter.report(
            eventType = "UMP_DECISION_REQUEST",
            eventMessage = "reason=$reason,canContinueWithoutDecision=$canContinueWithoutDecision"
        )
        provider.invoke(appContext) { decision ->
            if (decision == null) {
                Log.w(TAG, "UMP 门禁：远端 consent-popup 决策为空，canContinueWithoutDecision=$canContinueWithoutDecision")
                continueAfterRemoteDecision = canContinueWithoutDecision
                Hq008ConsentLogReporter.report(
                    eventType = "UMP_DECISION_EMPTY",
                    eventMessage = "provider_callback=null,reason=$reason,canContinueWithoutDecision=$canContinueWithoutDecision"
                )
                onComplete()
                return@invoke
            }
            applyRemoteCmpDecisionIfNeeded(
                context = appContext,
                decision = decision,
                canContinueWithoutDecision = canContinueWithoutDecision,
                onCompleted = onComplete
            )
        }
    }

    fun applyRemoteCmpDecisionIfNeeded(
        context: Context,
        decision: RemoteCmpDecision,
        forceApply: Boolean = false,
        canContinueWithoutDecision: Boolean = false,
        onCompleted: (() -> Unit)? = null
    ) {
        val appContext = context.applicationContext
        val reportAction = normalizeAction(decision.consentAction)
        val umpAction = resolveUmpAction(reportAction)
        if (reportAction == ACTION_SKIP_ALREADY_DECIDED) {
            Log.i(TAG, "UMP 门禁：远端返回 SKIP_ALREADY_DECIDED，canContinueWithoutDecision=$canContinueWithoutDecision")
            continueAfterRemoteDecision = canContinueWithoutDecision
            Hq008ConsentLogReporter.report(
                eventType = "UMP_DECISION_SKIPPED",
                eventMessage = "action=$reportAction,canContinueWithoutDecision=$canContinueWithoutDecision"
            )
            onCompleted?.invoke()
            return
        }
        if (umpAction == null) {
            Log.w(TAG, "UMP 门禁：未知远端动作=${decision.consentAction}，canContinueWithoutDecision=$canContinueWithoutDecision")
            continueAfterRemoteDecision = canContinueWithoutDecision
            Hq008ConsentLogReporter.report(
                eventType = "UMP_DECISION_UNKNOWN",
                eventMessage = "action=${decision.consentAction},canContinueWithoutDecision=$canContinueWithoutDecision"
            )
            onCompleted?.invoke()
            return
        }

        if (!forceApply &&
            canContinueWithoutDecision &&
            isTerminalAction(reportAction) &&
            hasStoredConsent(appContext) &&
            getLastAppliedAction(appContext) == reportAction
        ) {
            Log.i(TAG, "UMP 门禁：远端动作=$reportAction 已在本地执行过，本轮跳过重复 privacy options 操作")
            continueAfterRemoteDecision = true
            Hq008ConsentLogReporter.report(
                eventType = "UMP_DECISION_ALREADY_APPLIED",
                eventMessage = "action=$reportAction"
            )
            onCompleted?.invoke()
            return
        }

        if (reportAction == ACTION_SAVE_SETTINGS) {
            Log.i(TAG, "UMP 门禁：远端动作=$reportAction 按 ACCEPT_ALL 执行，保留原 action 用于 consent-report")
            Hq008ConsentLogReporter.report(
                eventType = "UMP_DECISION_MAPPED",
                eventMessage = "remoteAction=$reportAction,umpAction=$ACTION_ACCEPT_ALL"
            )
        }

        if (umpAction == GoogleUmpConsentManager.ConsentAction.DEFER_WHEN_REQUIRED) {
            persistMaybeLaterCooldown(appContext)
        }

        Hq008ConsentLogReporter.report(
            eventType = "UMP_DECISION_START",
            eventMessage = "remoteAction=$reportAction,umpAction=$umpAction"
        )
        GoogleUmpConsentManager.requestConsent(
            context = appContext,
            action = umpAction
        ) { result ->
            val actionSucceeded = result.errorMessage.isNullOrBlank() &&
                (result.canRequestAds || result.deferred)
            continueAfterRemoteDecision = result.canRequestAds || result.deferred
            Hq008ConsentLogReporter.report(
                eventType = "UMP_DECISION_RESULT",
                eventMessage = "remoteAction=$reportAction,umpAction=$umpAction,actionSucceeded=$actionSucceeded,${result.toGateEventMessage()}"
            )
            if (actionSucceeded) {
                if (isTerminalAction(reportAction)) {
                    persistLastAppliedAction(appContext, reportAction)
                }
                Hq008CmpDecisionClient.reportConsentResult(reportAction) {
                    onCompleted?.invoke()
                }
            } else {
                onCompleted?.invoke()
            }
        }
    }

    fun canContinueAfterRemoteDecision(): Boolean {
        return continueAfterRemoteDecision
    }

    fun getConsentString(): String? {
        return GoogleUmpConsentManager.getConsentString(appContext)
    }

    fun isConsentExpired(context: Context): Boolean {
        return GoogleUmpConsentManager.getConsentString(context.applicationContext).isNullOrBlank()
    }

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
        applyRemoteCmpDecisionIfNeeded(
            context = context,
            decision = RemoteCmpDecision(consentAction = action),
            forceApply = true,
            canContinueWithoutDecision = true
        ) {
            onResult(
                "google_ump_action=$action," +
                    "canContinue=$continueAfterRemoteDecision," +
                    "consentLength=${getConsentString()?.length ?: 0}"
            )
        }
    }

    private fun normalizeAction(action: String): String {
        return action.trim().uppercase(Locale.US)
    }

    private fun resolveUmpAction(action: String): GoogleUmpConsentManager.ConsentAction? {
        return when (action) {
            ACTION_ACCEPT_ALL,
            ACTION_SAVE_SETTINGS -> GoogleUmpConsentManager.ConsentAction.ACCEPT_ALL
            ACTION_REJECT -> GoogleUmpConsentManager.ConsentAction.REJECT
            ACTION_MAYBE_LATER -> GoogleUmpConsentManager.ConsentAction.DEFER_WHEN_REQUIRED
            else -> null
        }
    }

    private fun isTerminalAction(action: String): Boolean {
        return action == ACTION_ACCEPT_ALL ||
            action == ACTION_REJECT ||
            action == ACTION_SAVE_SETTINGS
    }

    private fun hasStoredConsent(context: Context): Boolean {
        return !GoogleUmpConsentManager.getConsentString(context.applicationContext).isNullOrBlank()
    }

    private fun getLastAppliedAction(context: Context): String? {
        return prefs(context).getString(KEY_LAST_APPLIED_REMOTE_ACTION, null)
    }

    private fun persistLastAppliedAction(context: Context, action: String) {
        prefs(context)
            .edit()
            .putString(KEY_LAST_APPLIED_REMOTE_ACTION, action)
            .apply()
    }

    private fun isMaybeLaterCoolingDown(context: Context): Boolean {
        if (MAYBE_LATER_COOLDOWN_MS <= 0L) {
            return false
        }
        val recordedAtMs = prefs(context).getLong(KEY_MAYBE_LATER_RECORDED_AT, 0L)
        return recordedAtMs > 0L &&
            System.currentTimeMillis() - recordedAtMs < MAYBE_LATER_COOLDOWN_MS
    }

    private fun persistMaybeLaterCooldown(context: Context) {
        if (MAYBE_LATER_COOLDOWN_MS <= 0L) {
            return
        }
        prefs(context)
            .edit()
            .putLong(KEY_MAYBE_LATER_RECORDED_AT, System.currentTimeMillis())
            .apply()
    }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )

    private fun GoogleUmpConsentManager.Result.toGateEventMessage(): String {
        return "action=$action,canRequestAds=$canRequestAds,deferred=$deferred," +
            "status=$consentStatus,formAvailable=$formAvailable," +
            "privacyOptions=$privacyOptionsStatus,error=${errorMessage.orEmpty()}," +
            storedConsentSnapshot
    }
}
