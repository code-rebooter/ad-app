package com.smart.android.ad_app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import com.smart.android.ad_app.AdLocalLog as Log
import com.google.android.ump.ConsentDebugSettings
import com.google.android.ump.ConsentInformation
import com.google.android.ump.ConsentInformation.PrivacyOptionsRequirementStatus
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.FormError
import com.google.android.ump.UserMessagingPlatform
import java.lang.ref.WeakReference

object GoogleUmpConsentManager {
    enum class ConsentAction {
        CHECK_ONLY,
        ACCEPT_ALL,
        REJECT,
        DEFER_WHEN_REQUIRED
    }

    data class Result(
        val action: ConsentAction,
        val canRequestAds: Boolean,
        val errorMessage: String? = null,
        val deferred: Boolean = false,
        val consentStatus: Int = -1,
        val formAvailable: Boolean = false,
        val privacyOptionsStatus: String = "",
        val storedConsentSnapshot: String = "",
        val storedConsentSnapshotData: StoredConsentSnapshot = StoredConsentSnapshot()
    )

    data class StoredConsentSnapshot(
        val iabtcfGdprApplies: String = "",
        val tcStringLength: Int = 0,
        val purposeConsentsLength: Int = 0,
        val vendorConsentsLength: Int = 0,
        val consentModeValues: String = ""
    )

    private enum class State {
        IDLE,
        STARTING_ACTIVITY,
        GATHERING_CONSENT,
        COMPLETE
    }

    private const val TAG = "GoogleUmpConsent"

    private val mainHandler = Handler(Looper.getMainLooper())
    private val pendingCallbacks = mutableListOf<(Result) -> Unit>()

    private var state = State.IDLE
    private var consentInformation: ConsentInformation? = null
    private var hostActivityRef: WeakReference<Activity>? = null
    private var finishingActivity = false
    private var activeAction = ConsentAction.ACCEPT_ALL

    fun requestConsent(
        context: Context,
        action: ConsentAction = ConsentAction.ACCEPT_ALL,
        callback: (Result) -> Unit
    ) {
        mainHandler.post {
            val appContext = context.applicationContext
            val information = consentInformation
                ?: UserMessagingPlatform.getConsentInformation(appContext).also {
                    consentInformation = it
                }

            if (state == State.COMPLETE && information.canRequestAds()) {
                if (!shouldApplyStoredConsentViaPrivacyOptions(action, information)) {
                    callback(buildResult(appContext, action = action, errorMessage = null))
                    return@post
                }
                state = State.IDLE
            }

            pendingCallbacks += callback
            if (state != State.IDLE) {
                if (activeAction != action) {
                    Log.w(TAG, "已有 UMP action=$activeAction 正在执行，本次 action=$action 将复用当前流程")
                }
                return@post
            }

            activeAction = action
            state = State.STARTING_ACTIVITY
            runCatching {
                appContext.startActivity(
                    Intent(appContext, GoogleUmpConsentActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
                    }
                )
            }.onFailure { error ->
                Log.e(TAG, "无法启动 UMP consent Activity", error)
                finishFlow(
                    action = action,
                    errorMessage = error.message ?: "Unable to start UMP consent Activity",
                    allowRetry = true
                )
            }
        }
    }

    internal fun runConsentFlow(activity: Activity) {
        mainHandler.post {
            if (state == State.GATHERING_CONSENT) {
                return@post
            }

            hostActivityRef = WeakReference(activity)
            finishingActivity = false
            state = State.GATHERING_CONSENT

            val information = consentInformation
                ?: UserMessagingPlatform.getConsentInformation(activity.applicationContext).also {
                    consentInformation = it
            }
            val requestParameters = buildRequestParameters(activity)
            val action = activeAction

            Log.i(TAG, "开始更新 UMP consent 信息，action=$action")
            information.requestConsentInfoUpdate(
                activity,
                requestParameters,
                {
                    Log.i(
                        TAG,
                        "UMP consent 信息更新完成，status=${information.getConsentStatus()}，" +
                            "canRequestAds=${information.canRequestAds()}，" +
                            "formAvailable=${information.isConsentFormAvailable()}，" +
                            "privacyOptions=${information.getPrivacyOptionsRequirementStatus()}"
                    )
                    if (information.canRequestAds()) {
                        if (shouldApplyStoredConsentViaPrivacyOptions(action, information)) {
                            showSilentPrivacyOptionsForm(activity, action)
                        } else {
                            finishFlow(action = action, errorMessage = null, allowRetry = false)
                        }
                    } else {
                        when (action) {
                            ConsentAction.CHECK_ONLY -> {
                                finishFlow(
                                    action = action,
                                    errorMessage = "UMP consent is required before ad request",
                                    allowRetry = true
                                )
                            }
                            ConsentAction.DEFER_WHEN_REQUIRED -> {
                                finishFlow(
                                    action = action,
                                    errorMessage = "UMP consent deferred by remote decision",
                                    allowRetry = true,
                                    deferred = true
                                )
                            }
                            ConsentAction.ACCEPT_ALL,
                            ConsentAction.REJECT -> {
                                loadAndShowSilentConsentForm(activity, action)
                            }
                        }
                    }
                },
                { requestError ->
                    Log.w(
                        TAG,
                        "UMP consent 信息更新失败，canRequestAds=${information.canRequestAds()}，error=${requestError.message}"
                    )
                    finishFlow(
                        action = action,
                        errorMessage = requestError.message,
                        allowRetry = !information.canRequestAds()
                    )
                }
            )
        }
    }

    private fun buildRequestParameters(activity: Activity): ConsentRequestParameters {
        val builder = ConsentRequestParameters.Builder()
        val debugDeviceId = BuildConfig.UMP_DEBUG_DEVICE_ID
        if (BuildConfig.DEBUG && debugDeviceId.isNotBlank()) {
            Log.i(TAG, "启用 UMP EEA 调试地域，testDeviceId=$debugDeviceId")
            builder.setConsentDebugSettings(
                ConsentDebugSettings.Builder(activity)
                    .setDebugGeography(ConsentDebugSettings.DebugGeography.DEBUG_GEOGRAPHY_EEA)
                    .addTestDeviceHashedId(debugDeviceId)
                    .build()
            )
        }
        return builder.build()
    }

    private fun loadAndShowSilentConsentForm(
        activity: Activity,
        action: ConsentAction
    ) {
        val information = consentInformation
            ?: UserMessagingPlatform.getConsentInformation(activity.applicationContext).also {
                consentInformation = it
            }
        if (!information.isConsentFormAvailable()) {
            val message = "UMP consent form is unavailable after consent info update"
            Log.w(TAG, "$message，${buildStoredConsentSnapshot(activity.applicationContext)}")
            finishFlow(
                action = action,
                errorMessage = message,
                allowRetry = !information.canRequestAds()
            )
            return
        }

        Log.i(TAG, "开始加载 UMP consent 表单用于静默完成用户操作，action=$action")
        UserMessagingPlatform.loadConsentForm(
            activity.applicationContext,
            { consentForm ->
                mainHandler.post {
                    if (state != State.GATHERING_CONSENT || hostActivityRef?.get() !== activity) {
                        Log.w(TAG, "UMP consent 表单已加载，但宿主 Activity 已失效")
                        return@post
                    }
                    Log.i(TAG, "UMP consent 表单加载完成，开始静默执行 action=$action")
                    GoogleUmpSilentConsentFormRunner.showAndApplyDecisionSilently(
                        activity = activity,
                        consentForm = consentForm,
                        decisionMode = when (action) {
                            ConsentAction.REJECT -> GoogleUmpSilentConsentFormRunner.DecisionMode.REJECT
                            else -> GoogleUmpSilentConsentFormRunner.DecisionMode.ACCEPT_ALL
                        }
                    ) { result ->
                        mainHandler.post {
                            completeAfterConsentForm(
                                action = action,
                                formError = result.formError,
                                localErrorMessage = result.localErrorMessage
                            )
                        }
                    }
                }
            },
            { loadError ->
                mainHandler.post {
                    Log.w(
                        TAG,
                        "UMP consent 表单加载失败，canRequestAds=${information.canRequestAds()}，error=${loadError.message}"
                    )
                    finishFlow(
                        action = action,
                        errorMessage = loadError.message,
                        allowRetry = !information.canRequestAds()
                    )
                }
            }
        )
    }

    private fun showSilentPrivacyOptionsForm(
        activity: Activity,
        action: ConsentAction
    ) {
        Log.i(TAG, "UMP 已有 consent 状态，开始通过 privacy options 静默改写 action=$action")
        GoogleUmpSilentConsentFormRunner.showPrivacyOptionsAndApplyDecisionSilently(
            activity = activity,
            decisionMode = when (action) {
                ConsentAction.REJECT -> GoogleUmpSilentConsentFormRunner.DecisionMode.REJECT
                else -> GoogleUmpSilentConsentFormRunner.DecisionMode.ACCEPT_ALL
            }
        ) { result ->
            mainHandler.post {
                completeAfterConsentForm(
                    action = action,
                    formError = result.formError,
                    localErrorMessage = result.localErrorMessage
                )
            }
        }
    }

    private fun shouldApplyStoredConsentViaPrivacyOptions(
        action: ConsentAction,
        information: ConsentInformation
    ): Boolean {
        return when (action) {
            ConsentAction.ACCEPT_ALL,
            ConsentAction.REJECT -> {
                information.getPrivacyOptionsRequirementStatus() ==
                    PrivacyOptionsRequirementStatus.REQUIRED
            }
            ConsentAction.CHECK_ONLY,
            ConsentAction.DEFER_WHEN_REQUIRED -> false
        }
    }

    internal fun onHostActivityDestroyed(activity: Activity) {
        mainHandler.post {
            if (hostActivityRef?.get() !== activity) {
                return@post
            }
            hostActivityRef?.clear()
            hostActivityRef = null
            if (!finishingActivity && state == State.GATHERING_CONSENT) {
                finishFlow(
                    action = activeAction,
                    errorMessage = "UMP consent Activity was destroyed before completion",
                    allowRetry = true
                )
            }
        }
    }

    private fun completeAfterConsentForm(
        action: ConsentAction,
        formError: FormError?,
        localErrorMessage: String? = null
    ) {
        val information = consentInformation
        val canRequestAds = information?.canRequestAds() == true
        val snapshot = hostActivityRef
            ?.get()
            ?.applicationContext
            ?.let { buildStoredConsentSnapshot(it) }
            .orEmpty()
        val errorMessage = formError?.message ?: localErrorMessage
        if (errorMessage == null) {
            Log.i(TAG, "UMP consent 静默流程结束，action=$action，canRequestAds=$canRequestAds，$snapshot")
        } else {
            Log.w(
                TAG,
                "UMP consent 静默流程结束但返回错误，action=$action，canRequestAds=$canRequestAds，error=$errorMessage，$snapshot"
            )
        }
        finishFlow(
            action = action,
            errorMessage = errorMessage,
            allowRetry = errorMessage != null && !canRequestAds
        )
    }

    fun getConsentString(context: Context): String? {
        val prefs = context.applicationContext.getSharedPreferences(
            "${context.applicationContext.packageName}_preferences",
            Context.MODE_PRIVATE
        )
        return prefs.getString("IABTCF_TCString", null)
    }

    fun buildStoredConsentSnapshotForLog(context: Context): String {
        return buildStoredConsentSnapshotData(context.applicationContext).toLogString()
    }

    private fun buildStoredConsentSnapshot(context: Context): String {
        return buildStoredConsentSnapshotData(context).toLogString()
    }

    fun buildStoredConsentSnapshotData(context: Context): StoredConsentSnapshot {
        val prefs = context.getSharedPreferences("${context.packageName}_preferences", Context.MODE_PRIVATE)
        val tcString = prefs.getString("IABTCF_TCString", null)
        val gdprApplies = prefs.all["IABTCF_gdprApplies"]
        val purposeConsents = prefs.getString("IABTCF_PurposeConsents", null)
        val vendorConsents = prefs.getString("IABTCF_VendorConsents", null)
        val consentModeValues = prefs.getString("UMP_consentModeValues", null)
        return StoredConsentSnapshot(
            iabtcfGdprApplies = gdprApplies?.toString().orEmpty(),
            tcStringLength = tcString?.length ?: 0,
            purposeConsentsLength = purposeConsents?.length ?: 0,
            vendorConsentsLength = vendorConsents?.length ?: 0,
            consentModeValues = consentModeValues.orEmpty()
        )
    }

    private fun StoredConsentSnapshot.toLogString(): String {
        return "IABTCF_TCString_length=$tcStringLength," +
            "IABTCF_gdprApplies=${iabtcfGdprApplies.ifBlank { "unknown" }}," +
            "PurposeConsents_length=$purposeConsentsLength," +
            "VendorConsents_length=$vendorConsentsLength," +
            "UMP_consentModeValues=${consentModeValues.ifBlank { "empty" }}"
    }

    private fun buildResult(
        context: Context?,
        action: ConsentAction,
        errorMessage: String?,
        deferred: Boolean = false
    ): Result {
        val information = consentInformation
        val snapshotData = context
            ?.let(::buildStoredConsentSnapshotData)
            ?: StoredConsentSnapshot()
        return Result(
            action = action,
            canRequestAds = information?.canRequestAds() == true,
            errorMessage = errorMessage,
            deferred = deferred,
            consentStatus = information?.getConsentStatus() ?: -1,
            formAvailable = information?.isConsentFormAvailable() == true,
            privacyOptionsStatus = information?.getPrivacyOptionsRequirementStatus()?.name.orEmpty(),
            storedConsentSnapshot = snapshotData.toLogString(),
            storedConsentSnapshotData = snapshotData
        )
    }

    private fun finishFlow(
        action: ConsentAction,
        errorMessage: String?,
        allowRetry: Boolean,
        deferred: Boolean = false
    ) {
        val result = buildResult(
            context = hostActivityRef?.get()?.applicationContext,
            action = action,
            errorMessage = errorMessage,
            deferred = deferred
        )
        state = if (allowRetry && !result.canRequestAds) State.IDLE else State.COMPLETE

        val callbacks = pendingCallbacks.toList()
        pendingCallbacks.clear()
        callbacks.forEach { callback -> callback(result) }

        finishingActivity = true
        hostActivityRef?.get()?.let { activity ->
            if (!activity.isFinishing) {
                activity.finish()
            }
        }
        hostActivityRef?.clear()
        hostActivityRef = null
    }
}
