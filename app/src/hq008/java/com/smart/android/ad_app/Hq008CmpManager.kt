package com.smart.android.ad_app

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import androidx.fragment.app.FragmentActivity
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.tcl.ff.component.oversea.CmpConfigParams
import com.tcl.ff.component.oversea.CmpConsentManager
import com.tcl.ff.component.oversea.CmpPopStateManager
import com.tcl.ff.component.oversea.constant.CMPErrorCode
import com.tcl.ff.component.oversea.constant.CmpDisplayType
import com.tcl.ff.component.oversea.listener.OnCmpLoadStateListener
import com.tcl.ff.component.oversea.listener.OnCmpStatusListener
import com.tcl.ff.component.oversea.model.data.Campaign
import com.tcl.ff.component.oversea.model.data.CmpCampaignBean
import com.tcl.ff.component.oversea.model.data.CmpConsentBean
import com.tcl.ff.component.oversea.model.data.GvlBean
import com.tcl.ff.component.oversea.model.expose.ActionType
import com.tcl.ff.component.oversea.model.expose.GDPRConsent
import com.tcl.ff.component.oversea.model.a as CmpSdkRepository
import com.tcl.ff.component.overseahttp.http.HttpRequester
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import okio.Buffer
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

object Hq008CmpManager {
    private const val TAG = "Hq008CmpManager"
    private const val DEFAULT_ZONE = "de"
    private const val CONSENT_READY_TIMEOUT_MS = 1_500L
    private const val KEY_PENDING_SDK_SYNC_STATE = "pending_sdk_sync_state"
    private const val KEY_PENDING_CONSENT_REPORT_STATE = "pending_consent_report_state"
    private const val SILENT_BOOTSTRAP_PREFS = "hq008_cmp_silent_bootstrap"
    private const val SILENT_ACCEPT_ALL_ACTION_CODE = 3
    private const val SILENT_CONSENT_SCREEN = 1
    private const val SILENT_ACCEPT_ALL_ACTION = "ACCEPT_ALL"
    private const val SILENT_REJECT_ACTION = "REJECT"
    private const val SILENT_SAVE_SETTINGS_ACTION = "SAVE_AND_EXIT"
    private const val REMOTE_SAVE_SETTINGS_ACTION = "SAVE_SETTINGS"
    private const val REMOTE_MAYBE_LATER_ACTION = "MAYBE_LATER"
    private const val REMOTE_SKIP_ALREADY_DECIDED_ACTION = "SKIP_ALREADY_DECIDED"
    private const val DEFAULT_CONSENT_EXPIRE_MS = 31_536_000_000L
    private const val MAX_CMP_AD_LOG_RAW_LENGTH = 4096
    private const val MAX_CMP_HTTP_CAPTURE_BYTES = 512L * 1024L
    private const val CMP_HTTP_CAPTURE_WAIT_MS = 2_000L
    private val gson = Gson()

    @Volatile
    private var initialized = false

    @Volatile
    private var consentString: String? = null

    @Volatile
    private var consentStateReady = false
    @Volatile
    private var cmpNeedShowPop = false
    @Volatile
    private var cmpDecisionEligible = false
    @Volatile
    private var cmpCycleKey: String? = null
    @Volatile
    private var currentCmpSeed: SilentConsentSeed? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private val consentReadyCallbacks = mutableListOf<() -> Unit>()
    private var cmpPopStateManager: CmpPopStateManager? = null
    private var cmpConsentManager: CmpConsentManager? = null
    @Volatile
    private var pendingRemoteRecovery: PendingRemoteRecoveryState? = null
    @Volatile
    private var remoteDecisionProvider: ((Context, (RemoteCmpDecision?) -> Unit) -> Unit)? = null
    private val cmpHttpCaptureSlots = CopyOnWriteArrayList<CmpHttpCaptureSlot>()
    private val cmpHttpCaptureInstalled = AtomicBoolean(false)
    private val cmpHttpCaptureInstallLock = Any()

    data class RemoteCmpDecision(
        val consentAction: String,
        val consentPayload: SaveSettingsPayload? = null
    )

    data class SaveSettingsPayload(
        val purposeConsentIds: List<Int> = emptyList(),
        val purposeLiIds: List<Int> = emptyList(),
        val customPurposeConsentIds: List<Int> = emptyList(),
        val customPurposeLiIds: List<Int> = emptyList(),
        val specialFeatureIds: List<Int> = emptyList(),
        val vendorConsentIds: List<Int> = emptyList(),
        val vendorLiIds: List<Int> = emptyList()
    )

    private data class SilentConsentSeed(
        val actionType: String,
        val campaignId: Int?,
        val campaignBeanString: String,
        val currentTcString: String?,
        val tcStringCreateTime: Long?,
        val tcStringExpireTime: Long?,
        val hasNewCampaign: Boolean,
        val purposeConsentIds: List<Int>,
        val purposeLiIds: List<Int>,
        val customPurposeConsentIds: List<Int>,
        val customPurposeLiIds: List<Int>,
        val specialFeatureIds: List<Int>,
        val vendorConsentIds: List<Int>,
        val vendorLiIds: List<Int>,
        val gvlBean: GvlBean
    )

    private data class SilentBootstrapState(
        val seed: SilentConsentSeed,
        val uploadHash: String
    )

    private data class PendingSdkSyncState(
        val cycleKey: String?,
        val reportAction: String,
        val seed: SilentConsentSeed,
        val uploadHash: String
    )

    private data class ReflectiveSdkActionResult(
        val tcString: String?,
        val persistedSeed: SilentConsentSeed?
    )

    private data class PendingConsentReportState(
        val cycleKey: String?,
        val reportAction: String
    )

    private data class RemoteConsentStatus(
        val tcString: String?,
        val hasNewCampaign: Boolean
    )

    private data class RemoteCampaignFetchResult(
        val seed: SilentConsentSeed? = null,
        val suppressDecisionFlow: Boolean = false,
        val suppressReason: String? = null
    )

    private data class PendingRemoteRecoveryState(
        val cycleKey: String?,
        val tcString: String
    )

    private data class CmpHttpCapture(
        val requestBody: String?,
        val responseBody: String?,
        val failureMessage: String? = null
    )

    private data class CmpHttpCaptureSlot(
        val requestUrl: String,
        val requestBody: String?,
        val latch: CountDownLatch = CountDownLatch(1)
    ) {
        @Volatile
        var capture: CmpHttpCapture? = null
    }

    private object CmpRawHttpCaptureInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            val requestUrl = request.url.toString()
            val requestBody = readRequestBodySafely(request)
            return try {
                val response = chain.proceed(request)
                notifyCmpHttpCapture(
                    requestUrl = requestUrl,
                    requestBody = requestBody,
                    responseBody = response.peekBody(MAX_CMP_HTTP_CAPTURE_BYTES).string(),
                    failureMessage = null
                )
                response
            } catch (error: Throwable) {
                notifyCmpHttpCapture(
                    requestUrl = requestUrl,
                    requestBody = requestBody,
                    responseBody = null,
                    failureMessage = error.message ?: error.javaClass.simpleName
                )
                throw error
            }
        }
    }

    private fun readRequestBodySafely(request: okhttp3.Request): String? {
        val requestBody = request.body ?: return null
        val unsafeToReplay = runCatching {
            val bodyClass = requestBody.javaClass
            val isOneShot = bodyClass.methods.firstOrNull {
                it.name == "isOneShot" && it.parameterTypes.isEmpty()
            }?.invoke(requestBody) as? Boolean ?: false
            val isDuplex = bodyClass.methods.firstOrNull {
                it.name == "isDuplex" && it.parameterTypes.isEmpty()
            }?.invoke(requestBody) as? Boolean ?: false
            isOneShot || isDuplex
        }.getOrDefault(false)
        if (unsafeToReplay) {
            return null
        }
        return runCatching {
            val buffer = Buffer()
            requestBody.writeTo(buffer)
            buffer.readUtf8()
        }.getOrNull()
    }

    private fun ensureCmpHttpCaptureInstalled(): Boolean {
        if (cmpHttpCaptureInstalled.get()) {
            return true
        }
        synchronized(cmpHttpCaptureInstallLock) {
            if (cmpHttpCaptureInstalled.get()) {
                return true
            }
            return runCatching {
                val httpRequester = HttpRequester.get()
                val clientField = findCmpHttpClientField(httpRequester.javaClass)
                    ?: error("missing OkHttpClient field on HttpRequester")
                val currentClient = clientField.get(httpRequester) as? OkHttpClient
                    ?: error("missing OkHttpClient instance on HttpRequester")
                if (currentClient.interceptors.any { it === CmpRawHttpCaptureInterceptor }) {
                    cmpHttpCaptureInstalled.set(true)
                    return@runCatching true
                }
                val replacedClient = currentClient.newBuilder()
                    .addInterceptor(CmpRawHttpCaptureInterceptor)
                    .build()
                clientField.set(httpRequester, replacedClient)
                cmpHttpCaptureInstalled.set(true)
                Log.i(TAG, "静默同意链路：已注入 CMP 原始 HTTP 抓取拦截器")
                true
            }.getOrElse { error ->
                Log.e(TAG, "静默同意链路：注入 CMP 原始 HTTP 抓取拦截器失败", error)
                false
            }
        }
    }

    private fun findCmpHttpClientField(targetClass: Class<*>): java.lang.reflect.Field? {
        var current: Class<*>? = targetClass
        while (current != null && current != Any::class.java) {
            current.declaredFields.firstOrNull {
                OkHttpClient::class.java.isAssignableFrom(it.type)
            }?.let { field ->
                field.isAccessible = true
                return field
            }
            current = current.superclass
        }
        return null
    }

    private fun registerCmpHttpCapture(
        requestUrl: String,
        requestBody: String?
    ): CmpHttpCaptureSlot? {
        if (!ensureCmpHttpCaptureInstalled()) {
            return null
        }
        cmpHttpCaptureSlots.removeAll { it.capture != null }
        return CmpHttpCaptureSlot(
            requestUrl = requestUrl,
            requestBody = requestBody
        ).also(cmpHttpCaptureSlots::add)
    }

    private fun awaitCmpHttpCapture(slot: CmpHttpCaptureSlot?): CmpHttpCapture? {
        if (slot == null) {
            return null
        }
        return try {
            val captured = slot.latch.await(CMP_HTTP_CAPTURE_WAIT_MS, TimeUnit.MILLISECONDS)
            if (!captured) {
                Log.w(TAG, "静默同意链路：等待 CMP 原始 HTTP 抓取超时，url=${slot.requestUrl}")
            }
            slot.capture
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        } finally {
            cmpHttpCaptureSlots.remove(slot)
        }
    }

    private fun notifyCmpHttpCapture(
        requestUrl: String,
        requestBody: String?,
        responseBody: String?,
        failureMessage: String?
    ) {
        val exactSlot = cmpHttpCaptureSlots.firstOrNull {
            it.capture == null && it.requestUrl == requestUrl && it.requestBody == requestBody
        }
        val matchedSlot = exactSlot ?: cmpHttpCaptureSlots.firstOrNull {
            it.capture == null && it.requestUrl == requestUrl
        } ?: return
        matchedSlot.capture = CmpHttpCapture(
            requestBody = requestBody ?: matchedSlot.requestBody,
            responseBody = responseBody,
            failureMessage = failureMessage
        )
        matchedSlot.latch.countDown()
    }

    private fun reportCmpTrace(
        eventType: String,
        eventMessage: String,
        adLog: String? = null
    ) {
        Hq008ConsentLogReporter.report(
            eventType = eventType,
            eventMessage = eventMessage,
            adLog = adLog
        )
    }

    private fun summarizeSeed(seed: SilentConsentSeed?): String {
        if (seed == null) {
            return "seed=null"
        }
        return "action=${seed.actionType},campaignId=${seed.campaignId},hasNewCampaign=${seed.hasNewCampaign},tcLength=${seed.currentTcString?.length ?: 0},purpose=${seed.purposeConsentIds.size},vendor=${seed.vendorConsentIds.size}"
    }

    private fun summarizePayload(payload: SaveSettingsPayload?): String {
        if (payload == null) {
            return "payload=null"
        }
        return "payload=purpose=${payload.purposeConsentIds.size},purposeLi=${payload.purposeLiIds.size},customPurpose=${payload.customPurposeConsentIds.size},customPurposeLi=${payload.customPurposeLiIds.size},specialFeature=${payload.specialFeatureIds.size},vendor=${payload.vendorConsentIds.size},vendorLi=${payload.vendorLiIds.size}"
    }

    fun init(context: Context) {
        if (BuildConfig.FLAVOR != "hq008") {
            return
        }

        // CMP_FLOW init start
        Log.i(TAG, "静默同意链路：开始初始化 CMP 管理器，initialized=$initialized，consentReady=$consentStateReady")
        reportCmpTrace(
            eventType = "CMP_INIT_START",
            eventMessage = "initialized=$initialized,consentReady=$consentStateReady,consentLength=${consentString?.length ?: 0}"
        )

        synchronized(this) {
            if (initialized) {
                Log.i(TAG, "静默同意链路：CMP 已初始化过，本次跳过重复初始化，consentLength=${consentString?.length ?: 0}")
                reportCmpTrace(
                    eventType = "CMP_INIT_SKIPPED",
                    eventMessage = "reason=already_initialized,consentLength=${consentString?.length ?: 0}"
                )
                return
            }
            initialized = true
            consentStateReady = false
            cmpNeedShowPop = false
            cmpDecisionEligible = false
            cmpCycleKey = null
        }

        try {
            com.tcl.ff.component.overseabase.base.util.GlobalContext.setAppContext(context.applicationContext)
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to prepopulate GlobalContext natively", e)
        }

        // --- 全程无感授权的核心 ---
        prePopulateConsentIfNeed(context)

        refreshSdkCmpSnapshot(
            context = context.applicationContext,
            reason = "init",
            markReady = true,
            onCompleted = {
                consumePendingSdkSyncIfNeeded(context)
                consumePendingConsentReportIfNeeded(context)
                markConsentStateReady()
            }
        )
    }

    fun getConsentString(): String? {
        return consentString
    }

    fun setRemoteDecisionProvider(provider: ((Context, (RemoteCmpDecision?) -> Unit) -> Unit)?) {
        remoteDecisionProvider = provider
    }

    fun isConsentExpired(context: Context): Boolean {
        val seed = loadSilentConsentSeedFromLocal(context.applicationContext) ?: return true
        return isSeedExpired(seed)
    }

    private fun isSeedExpired(seed: SilentConsentSeed?): Boolean {
        seed ?: return true
        val createdAt = seed.tcStringCreateTime ?: return true
        val expireDuration = seed.tcStringExpireTime ?: return true
        if (createdAt <= 0L || expireDuration <= 0L) {
            return true
        }
        return System.currentTimeMillis() - createdAt > expireDuration
    }

    fun runWhenConsentStateReady(
        timeoutMs: Long = CONSENT_READY_TIMEOUT_MS,
        onReady: () -> Unit
    ) {
        if (BuildConfig.FLAVOR != "hq008") {
            onReady()
            return
        }

        // CMP_FLOW runWhenConsentStateReady
        Log.i(
            TAG,
            "静默同意链路：等待同意状态就绪，ready=$consentStateReady，timeoutMs=$timeoutMs，consentLength=${consentString?.length ?: 0}"
        )
        reportCmpTrace(
            eventType = "CMP_READY_WAIT",
            eventMessage = "ready=$consentStateReady,timeoutMs=$timeoutMs,consentLength=${consentString?.length ?: 0}"
        )

        if (consentStateReady) {
            onReady()
            return
        }

        val executed = AtomicBoolean(false)
        val timeoutHolder = arrayOfNulls<Runnable>(1)
        val callback: () -> Unit = {
            if (executed.compareAndSet(false, true)) {
                timeoutHolder[0]?.let(mainHandler::removeCallbacks)
                onReady()
            }
        }

        val runImmediately = synchronized(this) {
            if (consentStateReady) {
                true
            } else {
                consentReadyCallbacks += callback
                false
            }
        }
        if (runImmediately) {
            onReady()
            return
        }

        val timeoutRunnable = Runnable {
            val removed = synchronized(this) {
                consentReadyCallbacks.remove(callback)
            }
            if (removed && executed.compareAndSet(false, true)) {
                Log.w(TAG, "静默同意链路：等待同意状态超时，timeoutMs=${timeoutMs}ms，继续放行后续逻辑")
                reportCmpTrace(
                    eventType = "CMP_READY_TIMEOUT",
                    eventMessage = "timeoutMs=$timeoutMs,consentLength=${consentString?.length ?: 0}"
                )
                onReady()
            }
        }
        timeoutHolder[0] = timeoutRunnable
        mainHandler.postDelayed(timeoutRunnable, timeoutMs)
    }

    fun showCmpPopup(
        activity: FragmentActivity,
        onFinished: (() -> Unit)? = null
    ) {
        val manager = cmpConsentManager ?: CmpConsentManager().also {
            cmpConsentManager = it
        }

        runCatching {
            val cmpConfig = buildCmpConfig(activity, forcePopup = false)
            Log.i(TAG, "调试弹窗链路：开始请求 CMP 正常弹窗（CMP_POP），zone=${cmpConfig.zone}")
            manager.loadCmpPrivacy(
                CmpDisplayType.CMP_POP,
                cmpConfig,
                activity,
                object : OnCmpStatusListener {
                    override fun onCmpDataReady() {
                        Log.i(TAG, "调试弹窗链路：CMP 弹窗数据已加载完成")
                    }

                    override fun onConsentStringReady(
                        consentString: String?,
                        gdprConsent: GDPRConsent?
                    ) {
                        this@Hq008CmpManager.consentString = consentString
                        Log.i(
                            TAG,
                            "调试弹窗链路：已拿到 CMP 弹窗结果，consentStringLength=${consentString?.length ?: 0}，consentAction=${gdprConsent?.consentAction}"
                        )
                        if (!consentString.isNullOrBlank()) {
                            onFinished?.invoke()
                        }
                    }

                    override fun onCmpPopup() {
                        Log.i(TAG, "调试弹窗链路：SDK 触发了弹窗回调 onCmpPopup")
                    }

                    override fun onError(errorCode: CMPErrorCode) {
                        Log.e(
                            TAG,
                            "调试弹窗链路：CMP 弹窗失败，errorName=${errorCode.name}，code=${errorCode.errorCode}，message=${errorCode.msg}"
                        )
                        onFinished?.invoke()
                    }
                },
                null
            )
        }.onFailure { error ->
            Log.e(TAG, "静默同意链路：发起静默同意请求失败，error=${error.message}", error)
            onFinished?.invoke()
        }
    }

    fun debugRunReflectiveSdkAction(
        context: Context,
        action: String,
        onCompleted: ((String) -> Unit)? = null
    ) {
        val applicationContext = context.applicationContext
        Thread {
            val result = runCatching {
                val baseSeed = loadSilentConsentSeed(applicationContext)
                    ?: error("missing CMP seed")
                when (action) {
                    SILENT_ACCEPT_ALL_ACTION,
                    SILENT_REJECT_ACTION -> {
                        val seed = buildSilentConsentSeedForDecision(
                            baseSeed = baseSeed,
                            decision = RemoteCmpDecision(consentAction = action)
                        ) ?: error("failed to build seed for $action")
                        val result = tryReflectiveUserActionIfPossible(applicationContext, seed)
                            ?: error("reflective $action did not persist sdk state")
                        val tcStringLength = result.tcString?.length ?: 0
                        if (result.persistedSeed == null) {
                            error("reflective $action did not persist sdk state")
                        }
                        val mirroredResponse = debugMirrorUserActionRequest(applicationContext, seed)
                        "OK action=$action tcStringLength=$tcStringLength mirroredResponse=$mirroredResponse"
                    }
                    REMOTE_SAVE_SETTINGS_ACTION -> {
                        val seed = buildSilentConsentSeedForDecision(
                            baseSeed = baseSeed,
                            decision = RemoteCmpDecision(
                                consentAction = action,
                                consentPayload = SaveSettingsPayload(
                                    purposeConsentIds = baseSeed.purposeConsentIds.take(2),
                                    purposeLiIds = baseSeed.purposeLiIds.take(2),
                                    customPurposeConsentIds = baseSeed.customPurposeConsentIds.take(2),
                                    customPurposeLiIds = baseSeed.customPurposeLiIds.take(2),
                                    specialFeatureIds = baseSeed.specialFeatureIds.take(2),
                                    vendorConsentIds = baseSeed.vendorConsentIds.take(2),
                                    vendorLiIds = baseSeed.vendorLiIds.take(2)
                                )
                            )
                        ) ?: error("failed to build seed for $action")
                        val result = tryReflectiveUserActionIfPossible(applicationContext, seed)
                            ?: error("reflective $action did not persist sdk state")
                        val tcStringLength = result.tcString?.length ?: 0
                        if (result.persistedSeed == null) {
                            error("reflective $action did not persist sdk state")
                        }
                        val mirroredResponse = debugMirrorUserActionRequest(applicationContext, seed)
                        "OK action=$action tcStringLength=$tcStringLength mirroredResponse=$mirroredResponse"
                    }
                    REMOTE_MAYBE_LATER_ACTION -> {
                        val maybeLaterSeed = baseSeed.copy(actionType = REMOTE_MAYBE_LATER_ACTION)
                        val reflected = tryReflectiveMaybeLaterIfPossible(applicationContext, maybeLaterSeed)
                        if (!reflected) {
                            error("reflective MAYBE_LATER returned false")
                        }
                        val mirroredResponse = debugMirrorUserActionRequest(applicationContext, maybeLaterSeed)
                        "OK action=$action reflected=true mirroredResponse=$mirroredResponse"
                    }
                    else -> error("unsupported action=$action")
                }
            }.onFailure { error ->
                Log.e(TAG, "静默同意链路：debugRunReflectiveSdkAction 失败，action=$action", error)
            }.getOrElse { error ->
                "FAIL action=$action error=${error.message}"
            }

            Log.i(TAG, "静默同意链路：debugRunReflectiveSdkAction 完成，result=$result")
            mainHandler.post {
                onCompleted?.invoke(result)
            }
        }.start()
    }

    private fun debugMirrorUserActionRequest(
        context: Context,
        seed: SilentConsentSeed
    ): String {
        val requestParamsClass = Class.forName("com.tcl.ff.component.oversea.model.requset.c")
        val requestParams = requestParamsClass.getDeclaredConstructor().newInstance()
        val requestParamsBaseClass = requestParamsClass.superclass
        val setBaseMethod = requestParamsBaseClass.getDeclaredMethod("a", CmpConfigParams::class.java).apply {
            isAccessible = true
        }
        val setUpActionMethod = requestParamsClass.getDeclaredMethod(
            "a",
            GvlBean::class.java,
            Integer::class.java,
            String::class.java,
            Integer::class.java,
            Integer::class.java
        ).apply {
            isAccessible = true
        }
        val toJsonMethod = requestParamsBaseClass.getDeclaredMethod("a").apply {
            isAccessible = true
        }
        setBaseMethod.invoke(requestParams, buildCmpConfig(context, forcePopup = false))
        setUpActionMethod.invoke(
            requestParams,
            seed.gvlBean,
            resolveSilentUserActionCode(seed.actionType),
            resolveCmpLanguage(context),
            seed.campaignId,
            SILENT_CONSENT_SCREEN
        )
        val requestBody = toJsonMethod.invoke(requestParams) as? String
            ?: error("failed to build mirrored user/action request body")

        val serverApiClass = Class.forName("com.tcl.ff.component.oversea.constant.b")
        val serverApi = serverApiClass.getField("a").get(null)
        val requestUrl = serverApiClass.getDeclaredMethod("d").apply {
            isAccessible = true
        }.invoke(serverApi) as? String ?: error("failed to resolve user/action url")

        Log.i(
            TAG,
            "静默同意链路：debug 镜像 user/action 请求，actionType=${seed.actionType}，campaignId=${seed.campaignId}，body=$requestBody"
        )
        val responseBody = HttpRequester.get().postJsonSync(requestUrl, requestBody)
        Log.i(
            TAG,
            "静默同意链路：debug 镜像 user/action 原始响应，actionType=${seed.actionType}，campaignId=${seed.campaignId}，response=$responseBody"
        )
        return responseBody
    }

    fun applyRemoteCmpDecisionIfNeeded(
        context: Context,
        onCompleted: (() -> Unit)? = null
    ) {
        val applicationContext = context.applicationContext
        consumePendingConsentReportIfNeeded(applicationContext)
        runWhenConsentStateReady {
            refreshSdkCmpSnapshot(
                context = applicationContext,
                reason = "ad_gate_sdk",
                markReady = false
            ) {
                if (!cmpNeedShowPop) {
                    Log.i(TAG, "静默同意链路：SDK 当前无需再次处理 CMP，跳过远端决策请求")
                    Hq008ConsentLogReporter.report(
                        eventType = "CMP_GATE_SKIP",
                        eventMessage = "reason=sdk_no_popup_needed"
                    )
                    onCompleted?.invoke()
                    return@refreshSdkCmpSnapshot
                }

                refreshAdGateCmpSnapshot(
                    context = applicationContext,
                    markReady = false
                ) {
                    if (!cmpDecisionEligible) {
                        val remoteRecoveryState = pendingRemoteRecovery
                        val recoverySeed = currentCmpSeed
                        if (remoteRecoveryState != null && recoverySeed != null) {
                            pendingRemoteRecovery = null
                            Log.i(
                                TAG,
                                "静默同意链路：广告门禁判定远端已存在统一记录，跳过 popup 直接恢复本地状态，campaignId=${recoverySeed.campaignId}"
                            )
                            recoverLocalConsentFromRemoteDecision(
                                context = applicationContext,
                                cycleKey = cmpCycleKey ?: buildCmpCycleKey(applicationContext, cmpNeedShowPop),
                                reportAction = resolveReportAction(recoverySeed.actionType),
                                seed = recoverySeed,
                                remoteTcString = remoteRecoveryState.tcString,
                                onCompleted = onCompleted
                            )
                            return@refreshAdGateCmpSnapshot
                        }
                        Log.i(TAG, "静默同意链路：广告门禁补校验后无需继续远端 CMP 决策")
                        onCompleted?.invoke()
                        return@refreshAdGateCmpSnapshot
                    }

                    val provider = remoteDecisionProvider
                    if (provider == null) {
                        Log.i(TAG, "静默同意链路：未配置远端 CMP 决策提供器，本次跳过静默同意/拒绝")
                        Hq008ConsentLogReporter.report(
                            eventType = "CMP_PROVIDER_MISSING",
                            eventMessage = "remoteDecisionProvider=null"
                        )
                        onCompleted?.invoke()
                        return@refreshAdGateCmpSnapshot
                    }

                    reportCmpTrace(
                        eventType = "CMP_GATE_DECISION_REQUEST",
                        eventMessage = "cmpCycleKey=${cmpCycleKey.orEmpty()},sdkNeedShowPop=$cmpNeedShowPop,decisionEligible=$cmpDecisionEligible"
                    )
                    provider.invoke(applicationContext) { decision ->
                        if (decision == null) {
                            Log.w(TAG, "静默同意链路：远端 CMP 决策为空，本次跳过执行")
                            Hq008ConsentLogReporter.report(
                                eventType = "CMP_DECISION_EMPTY",
                                eventMessage = "provider_callback=null"
                            )
                            onCompleted?.invoke()
                            return@invoke
                        }
                        Log.i(
                            TAG,
                            "静默同意链路：收到远端 CMP 决策，action=${decision.consentAction}，payloadPresent=${decision.consentPayload != null}"
                        )
                        applyRemoteCmpDecisionIfNeeded(
                            context = applicationContext,
                            decision = decision,
                            onCompleted = onCompleted
                        )
                    }
                }
            }
        }
    }

    fun applyRemoteCmpDecisionIfNeeded(
        context: Context,
        decision: RemoteCmpDecision,
        onCompleted: (() -> Unit)? = null
    ) {
        val applicationContext = context.applicationContext
        runWhenConsentStateReady {
            if (!cmpDecisionEligible) {
                Log.i(TAG, "静默同意链路：当前广告门禁未放行远端 CMP 决策，跳过 action=${decision.consentAction}")
                Hq008ConsentLogReporter.report(
                    eventType = "CMP_DECISION_SKIPPED",
                    eventMessage = "action=${decision.consentAction},reason=ad_gate_not_eligible"
                )
                onCompleted?.invoke()
                return@runWhenConsentStateReady
            }

            val baseSeed = loadSilentConsentSeed(applicationContext)
            if (baseSeed == null) {
                Log.w(TAG, "静默同意链路：缺少 CMP campaign 种子，无法执行远端静默决策")
                Hq008ConsentLogReporter.report(
                    eventType = "CMP_SEED_MISSING",
                    eventMessage = "action=${decision.consentAction}"
                )
                onCompleted?.invoke()
                return@runWhenConsentStateReady
            }

            val cycleKey = cmpCycleKey ?: buildCmpCycleKey(applicationContext, needShowPop = true)
            Log.i(
                TAG,
                "静默同意链路：开始处理远端 CMP 决策，action=${decision.consentAction}，cmpCycleKey=$cycleKey，sdkNeedShowPop=$cmpNeedShowPop，decisionEligible=$cmpDecisionEligible"
            )
            reportCmpTrace(
                eventType = "CMP_DECISION_START",
                eventMessage = "action=${decision.consentAction},cmpCycleKey=$cycleKey,sdkNeedShowPop=$cmpNeedShowPop,decisionEligible=$cmpDecisionEligible,${summarizePayload(decision.consentPayload)},${summarizeSeed(baseSeed)}"
            )
            val pendingState = loadPendingSdkSyncState(applicationContext)
            if (pendingState?.cycleKey == cycleKey) {
                Log.i(
                    TAG,
                    "静默同意链路：当前 CMP 轮次存在待补的 SDK user/action，同步优先补发，pendingAction=${pendingState.reportAction}"
                )
                reportCmpTrace(
                    eventType = "CMP_DECISION_RESUME_PENDING_SYNC",
                    eventMessage = "cmpCycleKey=$cycleKey,pendingAction=${pendingState.reportAction},hash=${pendingState.uploadHash}"
                )
                syncSdkUserAction(
                    context = applicationContext,
                    state = pendingState,
                    onCompleted = onCompleted,
                    onFailure = { onCompleted?.invoke() }
                )
                return@runWhenConsentStateReady
            }
            val remoteRecoveryState = pendingRemoteRecovery?.takeIf {
                it.cycleKey.isNullOrBlank() || it.cycleKey == cycleKey
            }
            pendingRemoteRecovery = null

            when (decision.consentAction) {
                SILENT_ACCEPT_ALL_ACTION,
                SILENT_REJECT_ACTION,
                REMOTE_SAVE_SETTINGS_ACTION -> {
                    val resolvedSeed = buildSilentConsentSeedForDecision(
                        baseSeed = baseSeed,
                        decision = decision
                    )
                    if (resolvedSeed == null) {
                        Log.w(TAG, "静默同意链路：无法构建 action=${decision.consentAction} 对应的 CMP 配置，本次跳过")
                        Hq008ConsentLogReporter.report(
                            eventType = "CMP_DECISION_BUILD_FAIL",
                            eventMessage = "action=${decision.consentAction}"
                        )
                        fallbackToMaybeLaterDecision(
                            context = applicationContext,
                            cycleKey = cycleKey,
                            baseSeed = baseSeed,
                            failedAction = decision.consentAction,
                            reason = "decision_build_failed",
                            onCompleted = onCompleted
                        )
                        return@runWhenConsentStateReady
                    }
                    Log.i(
                        TAG,
                        "静默同意链路：已构建动作种子，remoteAction=${decision.consentAction}，sdkAction=${resolvedSeed.actionType}，campaignId=${resolvedSeed.campaignId}"
                    )
                    reportCmpTrace(
                        eventType = "CMP_DECISION_SEED_READY",
                        eventMessage = "remoteAction=${decision.consentAction},cmpCycleKey=$cycleKey,${summarizeSeed(resolvedSeed)}"
                    )
                    if (remoteRecoveryState != null) {
                        reportCmpTrace(
                            eventType = "CMP_REMOTE_RECOVERY_PENDING",
                            eventMessage = "action=${decision.consentAction},cmpCycleKey=$cycleKey,remoteTcLength=${remoteRecoveryState.tcString.length}"
                        )
                        recoverLocalConsentFromRemoteDecision(
                            context = applicationContext,
                            cycleKey = cycleKey,
                            reportAction = decision.consentAction,
                            seed = resolvedSeed,
                            remoteTcString = remoteRecoveryState.tcString,
                            onCompleted = onCompleted
                        )
                        return@runWhenConsentStateReady
                    }
                    executeSilentDecision(
                        context = applicationContext,
                        cycleKey = cycleKey,
                        reportAction = decision.consentAction,
                        seed = resolvedSeed,
                        onCompleted = onCompleted
                    )
                }
                REMOTE_MAYBE_LATER_ACTION -> {
                    reportCmpTrace(
                        eventType = "CMP_DECISION_ROUTE",
                        eventMessage = "action=$REMOTE_MAYBE_LATER_ACTION,cmpCycleKey=$cycleKey,${summarizeSeed(baseSeed)}"
                    )
                    executeMaybeLaterDecision(
                        context = applicationContext,
                        cycleKey = cycleKey,
                        baseSeed = baseSeed,
                        onCompleted = onCompleted
                    )
                }
                REMOTE_SKIP_ALREADY_DECIDED_ACTION -> {
                    Log.i(TAG, "静默同意链路：远端返回 SKIP_ALREADY_DECIDED，本轮直接跳过 CMP 动作执行")
                    Hq008ConsentLogReporter.report(
                        eventType = "CMP_DECISION_SKIPPED",
                        eventMessage = "action=$REMOTE_SKIP_ALREADY_DECIDED_ACTION"
                    )
                    onCompleted?.invoke()
                }
                else -> {
                    Log.w(TAG, "静默同意链路：收到未知远端 CMP 动作=${decision.consentAction}，本次跳过")
                    Hq008ConsentLogReporter.report(
                        eventType = "CMP_DECISION_UNKNOWN",
                        eventMessage = "action=${decision.consentAction}"
                    )
                    onCompleted?.invoke()
                }
            }
        }
    }

    private fun buildCmpConfig(context: Context, forcePopup: Boolean): CmpConfigParams {
        val deviceId = resolveCmpDeviceId(context)
        val deviceMake = Build.MANUFACTURER.orEmpty().ifBlank { "android" }.lowercase(Locale.US)
        val clientType = Build.MODEL.orEmpty().ifBlank { "android" }

        return CmpConfigParams.Builder()
            .setDeviceMake(deviceMake)
            .setDeviceId(deviceId)
            .setZone(DEFAULT_ZONE)
            .setClientType(clientType)
            .setShowPopForce(forcePopup)
            .setCorner(24f)
            .build()
    }

    private fun resolveCmpDeviceId(context: Context): String {
        BuildConfig.CMP_DEVICE_ID_OVERRIDE.takeIf { it.isNotBlank() }?.let { overrideDeviceId ->
            Log.i(TAG, "静默同意链路：使用强制覆写的 SDK deviceId=$overrideDeviceId")
            return overrideDeviceId
        }
        return Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ANDROID_ID
        ).orEmpty()
    }

    private fun markConsentStateReady() {
        val callbacks = synchronized(this) {
            consentStateReady = true
            consentReadyCallbacks.toList().also {
                consentReadyCallbacks.clear()
            }
        }
        // CMP_FLOW state ready
        Log.i(
            TAG,
            "静默同意链路：同意状态已完成，准备回调等待方，callbackCount=${callbacks.size}，consentLength=${consentString?.length ?: 0}"
        )
        callbacks.forEach { callback ->
            mainHandler.post { callback() }
        }
    }

    private fun refreshSdkCmpSnapshot(
        context: Context,
        reason: String,
        markReady: Boolean,
        onCompleted: (() -> Unit)? = null
    ) {
        val manager = cmpPopStateManager ?: CmpPopStateManager().also {
            cmpPopStateManager = it
        }

        runCatching {
            val cmpConfig = buildCmpConfig(context, forcePopup = false)
            Log.i(TAG, "静默同意链路：开始刷新 SDK CMP 状态，reason=$reason，zone=${cmpConfig.zone}")
            reportCmpTrace(
                eventType = "CMP_SNAPSHOT_LOAD_START",
                eventMessage = "reason=$reason,zone=${cmpConfig.zone}"
            )
            manager.loadPopState(
                cmpConfig,
                object : OnCmpLoadStateListener {
                    override fun onLoadComplete(consentString: String?, needShowPop: Boolean) {
                        reportCmpTrace(
                            eventType = "CMP_SNAPSHOT_LOAD_RESULT",
                            eventMessage = "reason=$reason,needShowPop=$needShowPop,consentLength=${consentString?.length ?: 0}"
                        )
                        if (!needShowPop) {
                            updateCmpSnapshot(
                                context = context,
                                consentString = consentString,
                                needShowPop = false,
                                seed = loadSilentConsentSeedFromLocal(context),
                                reason = reason
                            )
                            if (markReady) {
                                Log.i(TAG, "静默同意链路：CMP 状态已就绪，reason=$reason")
                            }
                            onCompleted?.invoke()
                            return
                        }

                        updateCmpSnapshot(
                            context = context,
                            consentString = consentString,
                            needShowPop = true,
                            seed = currentCmpSeed ?: loadSilentConsentSeedFromLocal(context),
                            reason = reason
                        )
                        if (markReady) {
                            Log.i(TAG, "静默同意链路：CMP 状态已就绪，reason=$reason")
                        }
                        onCompleted?.invoke()
                    }
                }
            )
        }.onFailure { error ->
            Log.e(TAG, "静默同意链路：刷新 SDK CMP 状态失败，reason=$reason，error=${error.message}", error)
            reportCmpTrace(
                eventType = "CMP_SNAPSHOT_LOAD_FAIL",
                eventMessage = "reason=$reason,error=${error.message ?: "unknown"}"
            )
            onCompleted?.invoke()
        }
    }

    private fun refreshAdGateCmpSnapshot(
        context: Context,
        markReady: Boolean,
        onCompleted: (() -> Unit)?
    ) {
        val applicationContext = context.applicationContext
        Thread {
            val localSeed = currentCmpSeed ?: loadSilentConsentSeedFromLocal(applicationContext)
            val localInvalid = isLocalConsentStringInvalid(localSeed)
            val remoteConsentStatus = fetchRemoteConsentStatus(
                context = applicationContext,
                campaignVersion = localSeed?.campaignId
            )
            val remoteHasStoredConsent = !remoteConsentStatus?.tcString.isNullOrBlank()
            val shouldRecoverFromRemoteConsent = localInvalid &&
                remoteHasStoredConsent &&
                remoteConsentStatus?.hasNewCampaign != true
            val shouldFetchCampaign = remoteConsentStatus?.hasNewCampaign == true ||
                localSeed?.hasNewCampaign == true ||
                (localInvalid && localSeed == null)
            val remoteCampaignResult = if (shouldFetchCampaign) {
                fetchSilentConsentSeedFromRemote(applicationContext)
            } else {
                null
            }
            val suppressDecisionFlow = remoteCampaignResult?.suppressDecisionFlow == true
            val needsDecisionFlow = remoteConsentStatus?.hasNewCampaign == true ||
                localSeed?.hasNewCampaign == true ||
                localInvalid
            val refreshedSeed = when {
                remoteCampaignResult?.seed != null -> remoteCampaignResult.seed
                suppressDecisionFlow -> null
                else -> localSeed
            }
            val campaignSeedAvailable = refreshedSeed != null
            val remoteRecoveryEligible = shouldRecoverFromRemoteConsent && refreshedSeed != null
            val missingRequiredSeed = needsDecisionFlow && !suppressDecisionFlow && !campaignSeedAvailable
            val resolvedConsentString = when {
                suppressDecisionFlow -> null
                remoteRecoveryEligible -> null
                localInvalid -> null
                remoteHasStoredConsent -> remoteConsentStatus?.tcString
                else -> localSeed?.currentTcString ?: consentString
            }
            val decisionEligible = needsDecisionFlow &&
                !suppressDecisionFlow &&
                campaignSeedAvailable &&
                !remoteRecoveryEligible
            val skipReason = when {
                suppressDecisionFlow -> remoteCampaignResult?.suppressReason?.let { "campaign_$it" } ?: "campaign_suppressed"
                missingRequiredSeed -> "campaign_seed_missing"
                remoteRecoveryEligible -> "remote_already_decided"
                !decisionEligible -> "ad_gate_no_decision_needed"
                else -> null
            }
            if (skipReason != null) {
                if (skipReason == "campaign_seed_missing") {
                    Log.w(TAG, "静默同意链路：本轮需要 CMP campaign seed，但远端未返回有效 campaign data，跳过 popup 决策")
                }
                Hq008ConsentLogReporter.report(
                    eventType = "CMP_GATE_SKIP",
                    eventMessage = "reason=$skipReason"
                )
            }
            Log.i(
                TAG,
                "静默同意链路：ad_gate 对齐 SDK 判定完成，localInvalid=$localInvalid，remoteHasStoredConsent=$remoteHasStoredConsent，remoteHasNewCampaign=${remoteConsentStatus?.hasNewCampaign == true}，shouldFetchCampaign=$shouldFetchCampaign，remoteRecoveryEligible=$remoteRecoveryEligible，campaignSeedAvailable=$campaignSeedAvailable，suppressDecisionFlow=$suppressDecisionFlow，missingRequiredSeed=$missingRequiredSeed${remoteCampaignResult?.suppressReason?.let { "，suppressReason=$it" } ?: ""}"
            )
            reportCmpTrace(
                eventType = "CMP_GATE_EVALUATED",
                eventMessage = "localSeedPresent=${localSeed != null},localInvalid=$localInvalid,remoteHasStoredConsent=$remoteHasStoredConsent,remoteHasNewCampaign=${remoteConsentStatus?.hasNewCampaign == true},shouldFetchCampaign=$shouldFetchCampaign,remoteRecoveryEligible=$remoteRecoveryEligible,campaignSeedAvailable=$campaignSeedAvailable,suppressDecisionFlow=$suppressDecisionFlow,missingRequiredSeed=$missingRequiredSeed,decisionEligible=$decisionEligible,${summarizeSeed(refreshedSeed)}"
            )
            mainHandler.post {
                updateAdGateDecisionState(
                    context = applicationContext,
                    consentString = resolvedConsentString,
                    seed = refreshedSeed,
                    decisionEligible = decisionEligible,
                    reason = "ad_gate"
                )
                pendingRemoteRecovery = if (remoteRecoveryEligible) {
                    PendingRemoteRecoveryState(
                        cycleKey = cmpCycleKey,
                        tcString = remoteConsentStatus?.tcString.orEmpty()
                    )
                } else {
                    null
                }
                if (markReady) {
                    Log.i(TAG, "静默同意链路：CMP 状态已就绪，reason=ad_gate")
                }
                onCompleted?.invoke()
            }
        }.start()
    }

    private fun updateCmpSnapshot(
        context: Context,
        consentString: String?,
        needShowPop: Boolean,
        seed: SilentConsentSeed?,
        reason: String
    ) {
        if (!consentString.isNullOrBlank()) {
            this.consentString = consentString
        }
        currentCmpSeed = seed?.let { snapshotSeed ->
            if (!this.consentString.isNullOrBlank()) {
                snapshotSeed.copy(currentTcString = this.consentString)
            } else {
                snapshotSeed
            }
        }
        cmpNeedShowPop = needShowPop
        cmpDecisionEligible = needShowPop
        cmpCycleKey = buildCmpCycleKey(context, needShowPop)
        Log.i(
            TAG,
            "静默同意链路：已刷新 SDK CMP 快照，reason=$reason，needShowPop=$needShowPop，decisionEligible=$cmpDecisionEligible，cmpCycleKey=${cmpCycleKey.orEmpty()}，campaignId=${currentCmpSeed?.campaignId}，consentStringLength=${this.consentString?.length ?: 0}"
        )
        reportCmpTrace(
            eventType = "CMP_SNAPSHOT_UPDATED",
            eventMessage = "reason=$reason,needShowPop=$needShowPop,decisionEligible=$cmpDecisionEligible,cmpCycleKey=${cmpCycleKey.orEmpty()},consentLength=${this.consentString?.length ?: 0},${summarizeSeed(currentCmpSeed)}"
        )
    }

    private fun updateAdGateDecisionState(
        context: Context,
        consentString: String?,
        seed: SilentConsentSeed?,
        decisionEligible: Boolean,
        reason: String
    ) {
        if (!consentString.isNullOrBlank()) {
            this.consentString = consentString
        }
        currentCmpSeed = seed?.let { snapshotSeed ->
            if (!this.consentString.isNullOrBlank()) {
                snapshotSeed.copy(currentTcString = this.consentString)
            } else {
                snapshotSeed
            }
        }
        cmpDecisionEligible = decisionEligible
        cmpCycleKey = buildCmpCycleKey(context, cmpNeedShowPop)
        Log.i(
            TAG,
            "静默同意链路：已完成广告门禁补校验，reason=$reason，sdkNeedShowPop=$cmpNeedShowPop，decisionEligible=$cmpDecisionEligible，cmpCycleKey=${cmpCycleKey.orEmpty()}，campaignId=${currentCmpSeed?.campaignId}，consentStringLength=${this.consentString?.length ?: 0}"
        )
        reportCmpTrace(
            eventType = "CMP_GATE_DECISION_ELIGIBILITY",
            eventMessage = "reason=$reason,sdkNeedShowPop=$cmpNeedShowPop,decisionEligible=$cmpDecisionEligible,cmpCycleKey=${cmpCycleKey.orEmpty()},consentLength=${this.consentString?.length ?: 0},${summarizeSeed(currentCmpSeed)}"
        )
    }

    private fun prePopulateConsentIfNeed(context: Context) {
        try {
            val localSeed = loadSilentConsentSeedFromLocal(context)
            val skipReason = when {
                localSeed == null -> "local_seed_missing"
                !isTerminalSdkAction(localSeed.actionType) -> "action_not_terminal"
                isSeedExpired(localSeed) -> "local_seed_expired"
                else -> null
            }
            val seed = localSeed?.takeIf { skipReason == null } ?: run {
                Log.i(TAG, "静默同意链路：本地不存在有效终态 consent，本次不做启动静默注入")
                reportCmpTrace(
                    eventType = "CMP_BOOTSTRAP_SKIP",
                    eventMessage = "reason=${skipReason ?: "unknown"},${summarizeSeed(localSeed)}"
                )
                return
            }

            val dynamicTcString = generateSilentTcString(context, seed)
                ?: seed.currentTcString?.takeIf { it.isNotBlank() }
                ?: run {
                    Log.w(TAG, "静默同意链路：动态 TC String 生成失败，本次无法继续静默注入")
                    reportCmpTrace(
                        eventType = "CMP_BOOTSTRAP_SKIP",
                        eventMessage = "reason=tc_string_empty,${summarizeSeed(seed)}"
                    )
                    return
                }
            val uploadHash = buildSilentUserActionHash(seed)
            reportCmpTrace(
                eventType = "CMP_BOOTSTRAP_PREPARE",
                eventMessage = "uploadHash=$uploadHash,${summarizeSeed(seed)}"
            )

            persistSilentConsentState(
                context = context,
                seed = seed,
                tcString = dynamicTcString,
                uploadHash = uploadHash,
                source = "explicit_bootstrap",
                preferExistingSdkState = false
            )
            reportCmpTrace(
                eventType = "CMP_BOOTSTRAP_EXPLICIT",
                eventMessage = "uploadHash=$uploadHash,consentLength=${dynamicTcString.length},${summarizeSeed(seed)}"
            )
        } catch (e: Exception) {
            Log.e(TAG, "静默同意链路：预注入同意状态失败", e)
            reportCmpTrace(
                eventType = "CMP_BOOTSTRAP_FAIL",
                eventMessage = e.message ?: "unknown"
            )
        }
    }

    private fun persistSilentConsentState(
        context: Context,
        seed: SilentConsentSeed,
        tcString: String,
        uploadHash: String,
        source: String,
        preferExistingSdkState: Boolean
    ) {
        val consentFile = resolveSdkConsentFile(context)
        val cmpDir = requireNotNull(consentFile.parentFile) {
            "CMP consent parent directory should not be null"
        }
        if (!cmpDir.exists()) {
            cmpDir.mkdirs()
        }
        val now = System.currentTimeMillis()
        val expireDuration = seed.tcStringExpireTime ?: DEFAULT_CONSENT_EXPIRE_MS
        val persistedBySdk = persistSilentConsentStateViaSdk(
            context = context,
            seed = seed,
            tcString = tcString,
            createdAtMs = now,
            expireDurationMs = expireDuration,
            preferExistingSdkState = preferExistingSdkState
        )
        if (!persistedBySdk) {
            consentFile.writeText(buildSilentConsentJson(seed, tcString, now, expireDuration))
            Log.w(
                TAG,
                "静默同意链路：SDK 原生持久化不可用，已回退手写 consent 文件，source=$source，campaignId=${seed.campaignId}"
            )
        }

        android.preference.PreferenceManager.getDefaultSharedPreferences(context)
            .edit()
            .putString("IABTCF_TCString", tcString)
            .putInt("IABTCF_gdprApplies", 1)
            .apply()

        this.consentString = tcString
        this.currentCmpSeed = seed.copy(
            currentTcString = tcString,
            tcStringCreateTime = now,
            tcStringExpireTime = expireDuration
        )
        Log.i(
            TAG,
            "静默同意链路：已落盘并持久化同意状态，source=$source，storage=${if (persistedBySdk) "sdk" else "manual_fallback"}，consentLength=${tcString.length}，campaignId=${seed.campaignId}，actionType=${seed.actionType}"
        )
        reportCmpTrace(
            eventType = "CMP_STATE_PERSISTED",
            eventMessage = "source=$source,storage=${if (persistedBySdk) "sdk" else "manual_fallback"},consentLength=${tcString.length},uploadHash=$uploadHash,${summarizeSeed(seed)}"
        )
    }

    private fun persistSilentConsentStateViaSdk(
        context: Context,
        seed: SilentConsentSeed,
        tcString: String,
        createdAtMs: Long,
        expireDurationMs: Long,
        preferExistingSdkState: Boolean
    ): Boolean {
        return runCatching {
            val processorClass = Class.forName("com.tcl.ff.component.oversea.model.CMPConsentDataProcessor")
            val processor = resolveSdkConsentDataProcessor(processorClass)
            val clearConsentMethod = processorClass.getDeclaredMethod("b").apply { isAccessible = true }
            val setTcStringMethod = processorClass.getDeclaredMethod("b", String::class.java).apply { isAccessible = true }
            val setCreatedAtMethod = processorClass.getDeclaredMethod("b", Long::class.javaObjectType).apply { isAccessible = true }
            val setExpireDurationMethod = processorClass.getDeclaredMethod("a", Long::class.javaObjectType).apply { isAccessible = true }
            val setCampaignIdMethod = processorClass.getDeclaredMethod("a", Int::class.javaObjectType).apply { isAccessible = true }
            val setGvlBeanMethod = processorClass.getDeclaredMethod("b", GvlBean::class.java).apply { isAccessible = true }
            val setActionTypeMethod = processorClass.getDeclaredMethod("a", ActionType::class.java).apply { isAccessible = true }
            val setHasNewCampaignMethod = processorClass.getDeclaredMethod("a", Boolean::class.javaPrimitiveType).apply { isAccessible = true }
            val saveConsentMethod = processorClass.getDeclaredMethod("q").apply { isAccessible = true }

            if (!preferExistingSdkState) {
                clearConsentMethod.invoke(processor)
                applySeedToSdkConsentProcessor(processorClass, processor, seed)
            }

            setTcStringMethod.invoke(processor, tcString)
            setCreatedAtMethod.invoke(processor, createdAtMs)
            setExpireDurationMethod.invoke(processor, expireDurationMs)
            setCampaignIdMethod.invoke(processor, seed.campaignId)
            setGvlBeanMethod.invoke(processor, seed.gvlBean)
            setActionTypeMethod.invoke(processor, resolveSdkActionType(seed.actionType))
            setHasNewCampaignMethod.invoke(processor, seed.hasNewCampaign)
            saveConsentMethod.invoke(processor)

            val persistedSeed = loadSilentConsentSeedFromLocal(context)
            check(isPersistedSilentConsentStateCompatible(persistedSeed, seed, tcString, expireDurationMs)) {
                "sdk persisted state verification failed"
            }
            true
        }.onFailure { error ->
            Log.e(TAG, "静默同意链路：通过 SDK 原生链路持久化 consent 失败", error)
        }.getOrDefault(false)
    }

    private fun resolveSdkConsentDataProcessor(processorClass: Class<*>): Any {
        val companion = processorClass.getField("a").get(null)
        return requireNotNull(companion.javaClass.getDeclaredMethod("a").apply {
            isAccessible = true
        }.invoke(companion)) {
            "SDK CMPConsentDataProcessor instance should not be null"
        }
    }

    private fun applySeedToSdkConsentProcessor(
        processorClass: Class<*>,
        processor: Any,
        seed: SilentConsentSeed
    ) {
        val addPurposeConsentMethod = processorClass.getDeclaredMethod("c", Int::class.javaPrimitiveType).apply { isAccessible = true }
        val addPurposeLiMethod = processorClass.getDeclaredMethod("d", Int::class.javaPrimitiveType).apply { isAccessible = true }
        val addCustomPurposeConsentMethod = processorClass.getDeclaredMethod("a", Int::class.javaPrimitiveType).apply { isAccessible = true }
        val addCustomPurposeLiMethod = processorClass.getDeclaredMethod("b", Int::class.javaPrimitiveType).apply { isAccessible = true }
        val addSpecialFeatureMethod = processorClass.getDeclaredMethod("e", Int::class.javaPrimitiveType).apply { isAccessible = true }
        val addVendorConsentMethod = processorClass.getDeclaredMethod("f", Int::class.javaPrimitiveType).apply { isAccessible = true }
        val addVendorLiMethod = processorClass.getDeclaredMethod("g", Int::class.javaPrimitiveType).apply { isAccessible = true }

        seed.purposeConsentIds.forEach { addPurposeConsentMethod.invoke(processor, it) }
        seed.purposeLiIds.forEach { addPurposeLiMethod.invoke(processor, it) }
        seed.customPurposeConsentIds.forEach { addCustomPurposeConsentMethod.invoke(processor, it) }
        seed.customPurposeLiIds.forEach { addCustomPurposeLiMethod.invoke(processor, it) }
        seed.specialFeatureIds.forEach { addSpecialFeatureMethod.invoke(processor, it) }
        seed.vendorConsentIds.forEach { addVendorConsentMethod.invoke(processor, it) }
        seed.vendorLiIds.forEach { addVendorLiMethod.invoke(processor, it) }
    }

    private fun resolveSdkActionType(actionType: String): ActionType {
        return when (actionType) {
            SILENT_ACCEPT_ALL_ACTION -> ActionType.ACCEPT_ALL
            SILENT_REJECT_ACTION -> ActionType.REJECT
            SILENT_SAVE_SETTINGS_ACTION -> ActionType.SAVE_AND_EXIT
            else -> error("unsupported sdk actionType=$actionType")
        }
    }

    private fun isPersistedSilentConsentStateCompatible(
        persistedSeed: SilentConsentSeed?,
        expectedSeed: SilentConsentSeed,
        expectedTcString: String,
        expectedExpireDurationMs: Long
    ): Boolean {
        persistedSeed ?: return false
        return persistedSeed.actionType == expectedSeed.actionType &&
            persistedSeed.campaignId == expectedSeed.campaignId &&
            persistedSeed.currentTcString == expectedTcString &&
            persistedSeed.tcStringExpireTime == expectedExpireDurationMs &&
            persistedSeed.hasNewCampaign == expectedSeed.hasNewCampaign &&
            persistedSeed.purposeConsentIds.toSet() == expectedSeed.purposeConsentIds.toSet() &&
            persistedSeed.purposeLiIds.toSet() == expectedSeed.purposeLiIds.toSet() &&
            persistedSeed.customPurposeConsentIds.toSet() == expectedSeed.customPurposeConsentIds.toSet() &&
            persistedSeed.customPurposeLiIds.toSet() == expectedSeed.customPurposeLiIds.toSet() &&
            persistedSeed.specialFeatureIds.toSet() == expectedSeed.specialFeatureIds.toSet() &&
            persistedSeed.vendorConsentIds.toSet() == expectedSeed.vendorConsentIds.toSet() &&
            persistedSeed.vendorLiIds.toSet() == expectedSeed.vendorLiIds.toSet()
    }

    private fun executeSilentDecision(
        context: Context,
        cycleKey: String,
        reportAction: String,
        seed: SilentConsentSeed,
        onCompleted: (() -> Unit)?
    ) {
        var terminalStatePersisted = false
        try {
            val dynamicTcString = generateSilentTcString(context, seed)
                ?: seed.currentTcString?.takeIf { it.isNotBlank() }
            Log.i(
                TAG,
                "静默同意链路：准备执行 SDK 动作，reportAction=$reportAction，sdkAction=${seed.actionType}，campaignId=${seed.campaignId}，cmpCycleKey=$cycleKey，consentLength=${dynamicTcString?.length ?: 0}"
            )
            Hq008ConsentLogReporter.report(
                eventType = "SDK_ACTION_START",
                eventMessage = "reportAction=$reportAction,sdkAction=${seed.actionType},campaignId=${seed.campaignId}"
            )

            val uploadHash = buildSilentUserActionHash(seed)
            reportCmpTrace(
                eventType = "SDK_ACTION_PLAN",
                eventMessage = "reportAction=$reportAction,sdkAction=${seed.actionType},cmpCycleKey=$cycleKey,uploadHash=$uploadHash"
            )

            val reflectiveResult = tryReflectiveUserActionIfPossible(context, seed)
            if (reflectiveResult != null) {
                reportCmpTrace(
                    eventType = "SDK_ACTION_PATH",
                    eventMessage = "reportAction=$reportAction,path=reflective,cmpCycleKey=$cycleKey,uploadHash=$uploadHash,consentLength=${reflectiveResult.tcString?.length ?: 0},stateObserved=${reflectiveResult.persistedSeed != null}"
                )
                val observedSeed = reflectiveResult.persistedSeed
                val reflectiveTcString = reflectiveResult.tcString?.takeIf { it.isNotBlank() }
                if (!reflectiveTcString.isNullOrBlank()) {
                    persistSilentConsentState(
                        context = context,
                        seed = seed,
                        tcString = reflectiveTcString,
                        uploadHash = uploadHash,
                        source = "reflective_${seed.actionType.lowercase(Locale.US)}",
                        preferExistingSdkState = true
                    )
                } else if (observedSeed != null) {
                    acceptObservedReflectiveSdkState(
                        seed = observedSeed,
                        uploadHash = uploadHash,
                        source = "reflective_${seed.actionType.lowercase(Locale.US)}"
                    )
                } else if (!dynamicTcString.isNullOrBlank()) {
                    Log.w(TAG, "静默同意链路：反射动作已触发但未读到完整 SDK 状态，改走显式静默注入补齐")
                    persistSilentConsentState(
                        context = context,
                        seed = seed,
                        tcString = dynamicTcString,
                        uploadHash = uploadHash,
                        source = "explicit_${seed.actionType.lowercase(Locale.US)}",
                        preferExistingSdkState = false
                    )
                } else {
                    Log.w(TAG, "静默同意链路：反射动作未返回有效 TC String，且本地也无法按 SDK 规则补齐")
                    Hq008ConsentLogReporter.report(
                        eventType = "SDK_ACTION_PREPARE_FAIL",
                        eventMessage = "reportAction=$reportAction,sdkAction=${seed.actionType},reason=tc_string_empty"
                    )
                    fallbackToMaybeLaterDecision(
                        context = context,
                        cycleKey = cycleKey,
                        baseSeed = seed,
                        failedAction = reportAction,
                        reason = "tc_string_empty",
                        onCompleted = onCompleted
                    )
                    return
                }
                terminalStatePersisted = true
                syncSdkUserAction(
                    context = context,
                    state = PendingSdkSyncState(
                        cycleKey = cycleKey,
                        reportAction = reportAction,
                        seed = seed,
                        uploadHash = uploadHash
                    ),
                    onCompleted = onCompleted,
                    onFailure = { onCompleted?.invoke() }
                )
                return
            }

            if (dynamicTcString.isNullOrBlank()) {
                Log.w(TAG, "静默同意链路：动态 TC String 生成失败，且 SDK 原生动作也未落地，无法继续 actionType=${seed.actionType}")
                Hq008ConsentLogReporter.report(
                    eventType = "SDK_ACTION_PREPARE_FAIL",
                    eventMessage = "reportAction=$reportAction,sdkAction=${seed.actionType},reason=tc_string_empty"
                )
                fallbackToMaybeLaterDecision(
                    context = context,
                    cycleKey = cycleKey,
                    baseSeed = seed,
                    failedAction = reportAction,
                    reason = "tc_string_empty",
                    onCompleted = onCompleted
                )
                return
            }

            Log.w(TAG, "静默同意链路：SDK 原生反射 ${seed.actionType} 不可用，回退到显式静默注入实现")
            reportCmpTrace(
                eventType = "SDK_ACTION_PATH",
                eventMessage = "reportAction=$reportAction,path=explicit,reason=reflective_unavailable,cmpCycleKey=$cycleKey,uploadHash=$uploadHash"
            )

            persistSilentConsentState(
                context = context,
                seed = seed,
                tcString = dynamicTcString,
                uploadHash = uploadHash,
                source = "explicit_${seed.actionType.lowercase(Locale.US)}",
                preferExistingSdkState = false
            )
            terminalStatePersisted = true

            syncSdkUserAction(
                context = context,
                state = PendingSdkSyncState(
                    cycleKey = cycleKey,
                    reportAction = reportAction,
                    seed = seed,
                    uploadHash = uploadHash
                ),
                onCompleted = onCompleted,
                onFailure = { onCompleted?.invoke() }
            )
        } catch (error: Throwable) {
            Log.e(
                TAG,
                "静默同意链路：执行终态动作失败，reportAction=$reportAction，sdkAction=${seed.actionType}，campaignId=${seed.campaignId}",
                error
            )
            reportCmpTrace(
                eventType = "SDK_ACTION_EXCEPTION",
                eventMessage = "reportAction=$reportAction,sdkAction=${seed.actionType},reason=execution_error,error=${error.message ?: "unknown"}"
            )
            if (terminalStatePersisted) {
                onCompleted?.invoke()
            } else {
                fallbackToMaybeLaterDecision(
                    context = context,
                    cycleKey = cycleKey,
                    baseSeed = seed,
                    failedAction = reportAction,
                    reason = "execution_error",
                    onCompleted = onCompleted
                )
            }
        }
    }

    private fun executeMaybeLaterDecision(
        context: Context,
        cycleKey: String,
        baseSeed: SilentConsentSeed,
        onCompleted: (() -> Unit)?
    ) {
        val maybeLaterSeed = baseSeed.copy(actionType = REMOTE_MAYBE_LATER_ACTION)
        val uploadHash = buildSilentUserActionHash(maybeLaterSeed)
        Log.i(
            TAG,
            "静默同意链路：准备执行 MAYBE_LATER，campaignId=${baseSeed.campaignId}，cmpCycleKey=$cycleKey，uploadHash=$uploadHash"
        )
        reportCmpTrace(
            eventType = "CMP_MAYBE_LATER_START",
            eventMessage = "cmpCycleKey=$cycleKey,uploadHash=$uploadHash,${summarizeSeed(maybeLaterSeed)}"
        )

        val reflected = tryReflectiveMaybeLaterIfPossible(context, maybeLaterSeed)
        if (reflected) {
            reportCmpTrace(
                eventType = "CMP_MAYBE_LATER_PATH",
                eventMessage = "path=reflective,cmpCycleKey=$cycleKey,uploadHash=$uploadHash"
            )
            syncSdkUserAction(
                context = context,
                state = PendingSdkSyncState(
                    cycleKey = cycleKey,
                    reportAction = REMOTE_MAYBE_LATER_ACTION,
                    seed = maybeLaterSeed,
                    uploadHash = uploadHash
                ),
                onCompleted = onCompleted,
                onFailure = { onCompleted?.invoke() }
            )
            return
        }
        Log.w(TAG, "静默同意链路：SDK 原生反射 MAYBE_LATER 不可用，改走直接 user/action 上报")
        reportCmpTrace(
            eventType = "CMP_MAYBE_LATER_PATH",
            eventMessage = "path=direct_user_action,cmpCycleKey=$cycleKey,uploadHash=$uploadHash"
        )

        syncSdkUserAction(
            context = context,
            state = PendingSdkSyncState(
                cycleKey = cycleKey,
                reportAction = REMOTE_MAYBE_LATER_ACTION,
                seed = maybeLaterSeed,
                uploadHash = uploadHash
            ),
            onCompleted = onCompleted,
            onFailure = { onCompleted?.invoke() }
        )
    }

    private fun acceptObservedReflectiveSdkState(
        seed: SilentConsentSeed,
        uploadHash: String,
        source: String
    ) {
        if (!seed.currentTcString.isNullOrBlank()) {
            this.consentString = seed.currentTcString
        }
        this.currentCmpSeed = seed
        Log.i(
            TAG,
            "静默同意链路：检测到 SDK 已完成状态落地，source=$source，storage=sdk_observed，consentLength=${seed.currentTcString?.length ?: 0}，campaignId=${seed.campaignId}，actionType=${seed.actionType}"
        )
        reportCmpTrace(
            eventType = "CMP_STATE_PERSISTED",
            eventMessage = "source=$source,storage=sdk_observed,consentLength=${seed.currentTcString?.length ?: 0},uploadHash=$uploadHash,${summarizeSeed(seed)}"
        )
    }

    private fun fallbackToMaybeLaterDecision(
        context: Context,
        cycleKey: String,
        baseSeed: SilentConsentSeed,
        failedAction: String,
        reason: String,
        onCompleted: (() -> Unit)?
    ) {
        if (failedAction == REMOTE_MAYBE_LATER_ACTION) {
            onCompleted?.invoke()
            return
        }
        Log.w(
            TAG,
            "静默同意链路：动作=$failedAction 无法继续完成，回退执行 MAYBE_LATER，campaignId=${baseSeed.campaignId}，cmpCycleKey=$cycleKey，reason=$reason"
        )
        reportCmpTrace(
            eventType = "CMP_DECISION_FALLBACK",
            eventMessage = "action=$failedAction,fallback=$REMOTE_MAYBE_LATER_ACTION,reason=$reason,cycleKey=$cycleKey,campaignId=${baseSeed.campaignId ?: -1}"
        )
        executeMaybeLaterDecision(
            context = context,
            cycleKey = cycleKey,
            baseSeed = baseSeed,
            onCompleted = onCompleted
        )
    }

    private fun loadSilentConsentSeed(context: Context): SilentConsentSeed? {
        return currentCmpSeed
            ?: loadSilentConsentSeedFromLocal(context)
    }

    private fun loadSilentConsentSeedFromLocal(context: Context): SilentConsentSeed? {
        val sdkSeed = loadSilentConsentSeedViaSdk(context)
        if (sdkSeed != null) {
            reportCmpTrace(
                eventType = "CMP_LOCAL_STATE_LOADED",
                eventMessage = "path=sdk_processor,${summarizeSeed(sdkSeed)}"
            )
            return sdkSeed
        }
        return loadSilentConsentSeedFromFile(context)?.also { fileSeed ->
            reportCmpTrace(
                eventType = "CMP_LOCAL_STATE_LOADED",
                eventMessage = "path=file_fallback,${summarizeSeed(fileSeed)}"
            )
        }
    }

    private fun loadSilentConsentSeedViaSdk(context: Context): SilentConsentSeed? {
        return runCatching {
            val processorClass = Class.forName("com.tcl.ff.component.oversea.model.CMPConsentDataProcessor")
            val processor = resolveSdkConsentDataProcessor(processorClass)
            processorClass.getDeclaredMethod("p").apply {
                isAccessible = true
            }.invoke(processor)

            val gvlBean = processorClass.getDeclaredMethod("g").apply {
                isAccessible = true
            }.invoke(processor) as? GvlBean ?: return null
            gvlBean.makeUpData()

            val currentTcString = processorClass.getDeclaredMethod("l").apply {
                isAccessible = true
            }.invoke(processor) as? String
            val actionType = resolveLocalSdkActionType(
                processorClass.getDeclaredMethod("c").apply {
                    isAccessible = true
                }.invoke(processor) as? ActionType
            )
            val campaignId = processorClass.getDeclaredMethod("d").apply {
                isAccessible = true
            }.invoke(processor) as? Int
            val hasNewCampaign = processorClass.getDeclaredMethod("h").apply {
                isAccessible = true
            }.invoke(processor) as? Boolean ?: false
            val createdAt = processorClass.getDeclaredField("l").apply {
                isAccessible = true
            }.get(processor) as? Long
            val expireDuration = processorClass.getDeclaredField("m").apply {
                isAccessible = true
            }.get(processor) as? Long
            val purposeConsentIds = mapSdkIdSet(
                processorClass.getDeclaredMethod("i").apply {
                    isAccessible = true
                }.invoke(processor)
            )
            val purposeLiIds = mapSdkIdSet(
                processorClass.getDeclaredMethod("j").apply {
                    isAccessible = true
                }.invoke(processor)
            )
            val customPurposeConsentIds = mapSdkIdSet(
                processorClass.getDeclaredMethod("e").apply {
                    isAccessible = true
                }.invoke(processor)
            )
            val customPurposeLiIds = mapSdkIdSet(
                processorClass.getDeclaredMethod("f").apply {
                    isAccessible = true
                }.invoke(processor)
            )
            val specialFeatureIds = mapSdkIdSet(
                processorClass.getDeclaredMethod("k").apply {
                    isAccessible = true
                }.invoke(processor)
            )
            val vendorConsentIds = mapSdkIdSet(
                processorClass.getDeclaredMethod("m").apply {
                    isAccessible = true
                }.invoke(processor)
            )
            val vendorLiIds = mapSdkIdSet(
                processorClass.getDeclaredMethod("n").apply {
                    isAccessible = true
                }.invoke(processor)
            )

            val hasMaterialState = !currentTcString.isNullOrBlank() ||
                campaignId != null ||
                purposeConsentIds.isNotEmpty() ||
                purposeLiIds.isNotEmpty() ||
                customPurposeConsentIds.isNotEmpty() ||
                customPurposeLiIds.isNotEmpty() ||
                specialFeatureIds.isNotEmpty() ||
                vendorConsentIds.isNotEmpty() ||
                vendorLiIds.isNotEmpty()
            if (!hasMaterialState) {
                return null
            }

            SilentConsentSeed(
                actionType = actionType,
                campaignId = campaignId,
                campaignBeanString = gson.toJson(gvlBean),
                currentTcString = currentTcString,
                tcStringCreateTime = createdAt,
                tcStringExpireTime = expireDuration,
                hasNewCampaign = hasNewCampaign,
                purposeConsentIds = purposeConsentIds,
                purposeLiIds = purposeLiIds,
                customPurposeConsentIds = customPurposeConsentIds,
                customPurposeLiIds = customPurposeLiIds,
                specialFeatureIds = specialFeatureIds,
                vendorConsentIds = vendorConsentIds,
                vendorLiIds = vendorLiIds,
                gvlBean = gvlBean
            )
        }.onFailure { error ->
            Log.w(TAG, "静默同意链路：通过 SDK Processor 读取本地 consent 失败，准备回退文件解析", error)
            reportCmpTrace(
                eventType = "CMP_LOCAL_STATE_INVALID",
                eventMessage = "reason=sdk_processor_load_fail,error=${error.message ?: "unknown"}"
            )
        }.getOrNull()
    }

    private fun loadSilentConsentSeedFromFile(context: Context): SilentConsentSeed? {
        val consentFile = resolveSdkConsentFile(context)
        if (!consentFile.exists()) {
            return null
        }
        val root = runCatching {
            JsonParser.parseString(consentFile.readText()).asJsonObject
        }.getOrElse { error ->
            Log.e(TAG, "静默同意链路：解析本地 consent 文件失败", error)
            reportCmpTrace(
                eventType = "CMP_LOCAL_STATE_INVALID",
                eventMessage = "reason=consent_file_parse_fail,error=${error.message ?: "unknown"}"
            )
            return null
        }
        val campaignBeanString = root.stringOrNull("campaign_bean_string") ?: return null
        val gvlBean = runCatching {
            gson.fromJson(campaignBeanString, GvlBean::class.java).also { it?.makeUpData() }
        }.getOrElse { error ->
            Log.e(TAG, "静默同意链路：解析本地 campaign_bean_string 失败", error)
            reportCmpTrace(
                eventType = "CMP_LOCAL_STATE_INVALID",
                eventMessage = "reason=campaign_bean_parse_fail,error=${error.message ?: "unknown"}"
            )
            return null
        } ?: return null

        return SilentConsentSeed(
            actionType = root.stringOrNull("action_type") ?: SILENT_ACCEPT_ALL_ACTION,
            campaignId = root.intOrNull("campaign_id"),
            campaignBeanString = campaignBeanString,
            currentTcString = root.stringOrNull("cmp_tc_string"),
            tcStringCreateTime = root.longOrNull("tc_string_create_time"),
            tcStringExpireTime = root.longOrNull("tc_string_expire_time"),
            hasNewCampaign = root.booleanOrDefault("has_new_campaign", false),
            purposeConsentIds = root.intListOrEmpty("purpose_consent_list"),
            purposeLiIds = root.intListOrEmpty("purpose_li_list"),
            customPurposeConsentIds = root.intListOrEmpty("custom_purpose_consent_list"),
            customPurposeLiIds = root.intListOrEmpty("custom_purpose_li_list"),
            specialFeatureIds = root.intListOrEmpty("special_feature_consent_list"),
            vendorConsentIds = root.intListOrEmpty("vendor_consent_list"),
            vendorLiIds = root.intListOrEmpty("vendor_li_list"),
            gvlBean = gvlBean
        )
    }

    private fun fetchSilentConsentSeedFromRemote(context: Context): RemoteCampaignFetchResult? {
        var requestBodyForLog: String? = null
        var campaignUrlForLog: String? = null
        var responseBodyForLog: String? = null
        var captureFailureMessageForLog: String? = null
        var durationMsForLog: Long? = null
        var captureSlotForLog: CmpHttpCaptureSlot? = null
        return runCatching {
            val requestParamsClass = Class.forName("com.tcl.ff.component.oversea.model.requset.b")
            val requestParams = requestParamsClass
                .getDeclaredConstructor(java.lang.Boolean::class.java)
                .newInstance(false)
            val requestParamsBaseClass = requestParamsClass.superclass
            val setBaseMethod = requestParamsBaseClass.getDeclaredMethod("a", CmpConfigParams::class.java).apply {
                isAccessible = true
            }
            val toJsonMethod = requestParamsBaseClass.getDeclaredMethod("a").apply {
                isAccessible = true
            }
            setBaseMethod.invoke(requestParams, buildCmpConfig(context, forcePopup = false))
            val requestBody = toJsonMethod.invoke(requestParams) as? String
                ?: error("failed to build campaign request body")
            requestBodyForLog = requestBody

            val serverApiClass = Class.forName("com.tcl.ff.component.oversea.constant.b")
            val serverApi = serverApiClass.getField("a").get(null)
            val campaignUrl = serverApiClass.getDeclaredMethod("a").apply {
                isAccessible = true
            }.invoke(serverApi) as? String ?: error("failed to resolve campaign url")
            campaignUrlForLog = campaignUrl

            Log.i(TAG, "静默同意链路：开始请求远端 CMP campaign 种子，body=$requestBody")
            Hq008ConsentLogReporter.report(
                eventType = "CAMPAIGN_REQUEST_START",
                eventMessage = "forcePopup=false",
                adLog = buildCmpApiAdLog(
                    api = "getCampaign",
                    url = campaignUrl,
                    requestBody = requestBody,
                    responseBody = null,
                    durationMs = null,
                    success = false
                )
            )
            captureSlotForLog = registerCmpHttpCapture(
                requestUrl = campaignUrl,
                requestBody = requestBody
            )
            val startedAt = SystemClock.elapsedRealtime()
            val response = requestCmpCampaignViaSdkRepository(context, forcePopup = false)
            durationMsForLog = SystemClock.elapsedRealtime() - startedAt
            val capture = awaitCmpHttpCapture(captureSlotForLog)
            requestBodyForLog = capture?.requestBody?.takeIf { it.isNotBlank() } ?: requestBody
            val responseBody = capture?.responseBody?.takeIf { it.isNotBlank() }
                ?: response?.let(gson::toJson)
            responseBodyForLog = responseBody
            captureFailureMessageForLog = capture?.failureMessage
            if (!responseBody.isNullOrBlank()) {
                Log.i(TAG, "静默同意链路：远端 CMP campaign 原始 HTTP 响应=$responseBody")
            } else {
                Log.i(TAG, "静默同意链路：远端 CMP campaign 响应对象=${response?.let(gson::toJson)}")
            }
            val suppressReason = responseBody?.let(::resolveCampaignSuppressionReason)
                ?: resolveCampaignSuppressionReason(response)
            if (suppressReason != null) {
                Log.i(TAG, "静默同意链路：远端 CMP campaign 明确表示本轮无需展示，reason=$suppressReason")
                Hq008ConsentLogReporter.report(
                    eventType = "CAMPAIGN_REQUEST_SUPPRESSED",
                    eventMessage = "reason=$suppressReason",
                    adLog = buildCmpApiAdLog(
                        api = "getCampaign",
                        url = campaignUrl,
                        requestBody = requestBodyForLog,
                        responseBody = responseBody,
                        durationMs = durationMsForLog,
                        success = false,
                        captureFailureMessage = captureFailureMessageForLog
                    )
                )
                return@runCatching RemoteCampaignFetchResult(
                    suppressDecisionFlow = true,
                    suppressReason = suppressReason
                )
            }
            val campaign = response?.data ?: error("missing campaign data")
            campaign.gvlSource.makeUpData()
            val seed = buildSilentConsentSeedFromCampaign(
                campaign = campaign,
                actionType = SILENT_ACCEPT_ALL_ACTION
            ).also {
                Log.i(
                    TAG,
                    "静默同意链路：远端 CMP campaign 种子获取成功，campaignId=${it.campaignId}，vendorListVersion=${it.gvlBean.vendorListVersion}"
                )
                Hq008ConsentLogReporter.report(
                    eventType = "CAMPAIGN_REQUEST_SUCCESS",
                    eventMessage = "campaignId=${it.campaignId},vendorListVersion=${it.gvlBean.vendorListVersion}",
                    adLog = buildCmpApiAdLog(
                        api = "getCampaign",
                        url = campaignUrl,
                        requestBody = requestBodyForLog,
                        responseBody = responseBody,
                        durationMs = durationMsForLog,
                        success = true,
                        captureFailureMessage = captureFailureMessageForLog
                    )
                )
                logCampaignSummary(campaign, it)
            }
            RemoteCampaignFetchResult(seed = seed)
        }.onFailure { error ->
            if (responseBodyForLog == null && captureFailureMessageForLog == null) {
                val capture = awaitCmpHttpCapture(captureSlotForLog)
                requestBodyForLog = capture?.requestBody?.takeIf { it.isNotBlank() } ?: requestBodyForLog
                responseBodyForLog = capture?.responseBody?.takeIf { it.isNotBlank() } ?: responseBodyForLog
                captureFailureMessageForLog = capture?.failureMessage
            }
            Log.e(TAG, "静默同意链路：远端 CMP campaign 种子获取失败", error)
            Hq008ConsentLogReporter.report(
                eventType = "CAMPAIGN_REQUEST_FAIL",
                eventMessage = error.message ?: "unknown",
                adLog = buildCmpApiAdLog(
                    api = "getCampaign",
                    url = campaignUrlForLog,
                    requestBody = requestBodyForLog,
                    responseBody = responseBodyForLog,
                    durationMs = durationMsForLog,
                    success = false,
                    captureFailureMessage = captureFailureMessageForLog,
                    error = error
                )
            )
        }.getOrNull()
    }

    private fun resolveCampaignSuppressionReason(responseBody: String): String? {
        return runCatching {
            val responseJson = JsonParser.parseString(responseBody).asJsonObject
            val errorCode = responseJson.intOrNull("error_code")
            val errorMessage = responseJson.stringOrNull("error_msg").orEmpty()
            if (errorCode == 1000 && errorMessage.contains("投放频率控制中")) {
                "frequency_control"
            } else {
                null
            }
        }.getOrNull()
    }

    private fun resolveCampaignSuppressionReason(response: CmpCampaignBean?): String? {
        val responseCode = response?.code.orEmpty()
        val responseMessage = response?.msg.orEmpty()
        return if (responseCode == "1000" && responseMessage.contains("投放频率控制中")) {
            "frequency_control"
        } else {
            null
        }
    }

    private fun fetchRemoteConsentStatus(
        context: Context,
        campaignVersion: Int?
    ): RemoteConsentStatus? {
        var requestBodyForLog: String? = null
        var consentUrlForLog: String? = null
        var responseBodyForLog: String? = null
        var captureFailureMessageForLog: String? = null
        var durationMsForLog: Long? = null
        var captureSlotForLog: CmpHttpCaptureSlot? = null
        return runCatching {
            val requestParamsClass = Class.forName("com.tcl.ff.component.oversea.model.requset.d")
            val requestParams = requestParamsClass
                .getDeclaredConstructor(Int::class.javaObjectType)
                .newInstance(campaignVersion)
            val requestParamsBaseClass = requestParamsClass.superclass
            val setBaseMethod = requestParamsBaseClass.getDeclaredMethod("a", CmpConfigParams::class.java).apply {
                isAccessible = true
            }
            val toJsonMethod = requestParamsBaseClass.getDeclaredMethod("a").apply {
                isAccessible = true
            }
            setBaseMethod.invoke(requestParams, buildCmpConfig(context, forcePopup = false))
            val requestBody = toJsonMethod.invoke(requestParams) as? String
                ?: error("failed to build consent status request body")
            requestBodyForLog = requestBody

            val serverApiClass = Class.forName("com.tcl.ff.component.oversea.constant.b")
            val serverApi = serverApiClass.getField("a").get(null)
            val consentUrl = serverApiClass.getDeclaredMethod("c").apply {
                isAccessible = true
            }.invoke(serverApi) as? String ?: error("failed to resolve consent status url")
            consentUrlForLog = consentUrl

            Log.i(
                TAG,
                "静默同意链路：开始请求远端 CMP consent 状态，campaignVersion=$campaignVersion，body=$requestBody"
            )
            Hq008ConsentLogReporter.report(
                eventType = "CONSENT_STATUS_START",
                eventMessage = "campaignVersion=${campaignVersion ?: -1}",
                adLog = buildCmpApiAdLog(
                    api = "getTCString",
                    url = consentUrl,
                    requestBody = requestBody,
                    responseBody = null,
                    durationMs = null,
                    success = false
                )
            )
            captureSlotForLog = registerCmpHttpCapture(
                requestUrl = consentUrl,
                requestBody = requestBody
            )
            val startedAt = SystemClock.elapsedRealtime()
            val response = requestCmpConsentStatusViaSdkRepository(context, campaignVersion)
            durationMsForLog = SystemClock.elapsedRealtime() - startedAt
            val capture = awaitCmpHttpCapture(captureSlotForLog)
            requestBodyForLog = capture?.requestBody?.takeIf { it.isNotBlank() } ?: requestBody
            val responseBody = capture?.responseBody?.takeIf { it.isNotBlank() }
                ?: response?.let(gson::toJson)
            responseBodyForLog = responseBody
            captureFailureMessageForLog = capture?.failureMessage
            if (!responseBody.isNullOrBlank()) {
                Log.i(TAG, "静默同意链路：远端 CMP consent 状态原始 HTTP 响应=$responseBody")
            } else {
                Log.i(TAG, "静默同意链路：远端 CMP consent 状态响应对象=${response?.let(gson::toJson)}")
            }
            val data = response?.data
            Hq008ConsentLogReporter.report(
                eventType = "CONSENT_STATUS_RESULT",
                eventMessage = "code=${response?.code ?: -1},hasData=${data != null}",
                adLog = buildCmpApiAdLog(
                    api = "getTCString",
                    url = consentUrl,
                    requestBody = requestBodyForLog,
                    responseBody = responseBody,
                    durationMs = durationMsForLog,
                    success = true,
                    captureFailureMessage = captureFailureMessageForLog
                )
            )
            RemoteConsentStatus(
                tcString = data?.tcString,
                hasNewCampaign = response?.hasNewCampaign == true
            )
        }.onFailure { error ->
            if (responseBodyForLog == null && captureFailureMessageForLog == null) {
                val capture = awaitCmpHttpCapture(captureSlotForLog)
                requestBodyForLog = capture?.requestBody?.takeIf { it.isNotBlank() } ?: requestBodyForLog
                responseBodyForLog = capture?.responseBody?.takeIf { it.isNotBlank() } ?: responseBodyForLog
                captureFailureMessageForLog = capture?.failureMessage
            }
            Log.e(TAG, "静默同意链路：远端 CMP consent 状态获取失败", error)
            Hq008ConsentLogReporter.report(
                eventType = "CONSENT_STATUS_FAIL",
                eventMessage = error.message ?: "unknown",
                adLog = buildCmpApiAdLog(
                    api = "getTCString",
                    url = consentUrlForLog,
                    requestBody = requestBodyForLog,
                    responseBody = responseBodyForLog,
                    durationMs = durationMsForLog,
                    success = false,
                    captureFailureMessage = captureFailureMessageForLog,
                    error = error
                )
            )
        }.getOrNull()
    }

    private fun logCampaignSummary(campaign: Campaign, seed: SilentConsentSeed) {
        Log.i(
            TAG,
            "静默同意链路：云端 CMP 配置摘要 campaignId=${campaign.campaignId}，consentCookieExpiration=${campaign.consentCookieExpiration}，vendorListVersion=${seed.gvlBean.vendorListVersion}，tcfPolicyVersion=${seed.gvlBean.tcfPolicyVersion}，purposeConsentSize=${seed.purposeConsentIds.size}，purposeLiSize=${seed.purposeLiIds.size}，customPurposeConsentSize=${seed.customPurposeConsentIds.size}，customPurposeLiSize=${seed.customPurposeLiIds.size}，specialFeatureSize=${seed.specialFeatureIds.size}，vendorConsentSize=${seed.vendorConsentIds.size}，vendorLiSize=${seed.vendorLiIds.size}"
        )
    }

    private fun buildCmpApiAdLog(
        api: String,
        url: String?,
        requestBody: String?,
        responseBody: String?,
        durationMs: Long?,
        success: Boolean,
        captureFailureMessage: String? = null,
        error: Throwable? = null
    ): String {
        return JsonObject().apply {
            addProperty("source", "tcl_cmp_sdk")
            addProperty("api", api)
            url?.takeIf { it.isNotBlank() }?.let {
                addProperty("url", it)
                addProperty("path", it.substringAfter("://", it).substringAfter("/", ""))
            }
            durationMs?.let { addProperty("durationMs", it) }
            addProperty("success", success)
            requestBody?.let {
                addProperty("requestLength", it.length)
                addProperty("rawRequest", it.take(MAX_CMP_AD_LOG_RAW_LENGTH))
                addProperty("requestTruncated", it.length > MAX_CMP_AD_LOG_RAW_LENGTH)
            }
            responseBody?.let {
                addProperty("responseLength", it.length)
                addProperty("rawResponse", it.take(MAX_CMP_AD_LOG_RAW_LENGTH))
                addProperty("responseTruncated", it.length > MAX_CMP_AD_LOG_RAW_LENGTH)
                addCmpResponseSummary(it)
            }
            captureFailureMessage?.takeIf { it.isNotBlank() }?.let {
                addProperty("captureFailureMessage", it)
            }
            error?.let {
                addProperty("errorType", it.javaClass.simpleName)
                addProperty("errorMessage", it.message ?: "unknown")
            }
        }.toString()
    }

    private fun JsonObject.addCmpResponseSummary(responseBody: String) {
        runCatching {
            val root = JsonParser.parseString(responseBody).asJsonObject
            addProperty("responseCode", root.intOrNull("code") ?: root.intOrNull("error_code") ?: -1)
            root.stringOrNull("msg")?.let { addProperty("responseMsg", it) }
            val dataElement = root.get("data")
            addProperty("dataIsNull", dataElement == null || dataElement.isJsonNull)
            dataElement?.takeIf { it.isJsonObject }?.asJsonObject?.let { data ->
                data.intOrNull("campaignId")?.let { addProperty("campaignId", it) }
                data.get("gvlSource")?.takeIf { it.isJsonObject }?.asJsonObject
                    ?.intOrNull("vendorListVersion")
                    ?.let { addProperty("vendorListVersion", it) }
            }
        }.onFailure {
            addProperty("responseParseError", it.message ?: "unknown")
        }
    }

    internal fun extractCampaignPayload(responseBody: String): JsonObject? {
        return runCatching {
            val responseJson = JsonParser.parseString(responseBody).asJsonObject
            val wrappedData = responseJson.get("data")?.takeIf { it.isJsonObject }?.asJsonObject
            when {
                wrappedData?.looksLikeCampaignPayload() == true -> wrappedData
                responseJson.looksLikeCampaignPayload() -> responseJson
                else -> {
                    Log.w(
                        TAG,
                        "静默同意链路：远端 CMP campaign 响应结构不符合预期，body=${responseBody.take(512)}"
                    )
                    null
                }
            }
        }.onFailure { error ->
            Log.e(
                TAG,
                "静默同意链路：解析远端 CMP campaign 响应失败，body=${responseBody.take(512)}",
                error
            )
        }.getOrNull()
    }

    private fun buildSilentConsentSeedFromCampaign(
        campaign: Campaign,
        actionType: String
    ): SilentConsentSeed {
        val gvlBean = campaign.gvlSource.also { it.makeUpData() }
        val purposeConsentIds = mutableListOf<Int>()
        val purposeLiIds = mutableListOf<Int>()
        val customPurposeConsentIds = mutableListOf<Int>()
        val customPurposeLiIds = mutableListOf<Int>()
        val specialFeatureIds = mutableListOf<Int>()
        val vendorConsentIds = mutableListOf<Int>()
        val vendorLiIds = mutableListOf<Int>()

        gvlBean.purposeList.orEmpty().forEach { purpose ->
            val id = purpose?.id ?: return@forEach
            if (purpose.purposeConsent == true) {
                if (purpose.tclpurpose == true) {
                    customPurposeConsentIds += id
                } else {
                    purposeConsentIds += id
                }
            }
            if (purpose.purposeLITansparency == true) {
                if (purpose.tclpurpose == true) {
                    customPurposeLiIds += id
                } else {
                    purposeLiIds += id
                }
            }
        }

        gvlBean.specialFeatureList.orEmpty().forEach { specialFeature ->
            specialFeature?.id?.let(specialFeatureIds::add)
        }

        gvlBean.allVendorList.orEmpty().forEach { vendor ->
            val id = vendor?.id ?: return@forEach
            if (!vendor.purposes.isNullOrEmpty()) {
                vendorConsentIds += id
            }
            if (!vendor.legIntPurposes.isNullOrEmpty()) {
                vendorLiIds += id
            }
        }

        return buildSilentConsentSeedForDecision(
            baseSeed = SilentConsentSeed(
                actionType = SILENT_ACCEPT_ALL_ACTION,
                campaignId = campaign.campaignId,
                campaignBeanString = gson.toJson(gvlBean),
                currentTcString = null,
                tcStringCreateTime = null,
                tcStringExpireTime = campaign.consentCookieExpiration,
                hasNewCampaign = false,
                purposeConsentIds = purposeConsentIds,
                purposeLiIds = purposeLiIds,
                customPurposeConsentIds = customPurposeConsentIds,
                customPurposeLiIds = customPurposeLiIds,
                specialFeatureIds = specialFeatureIds,
                vendorConsentIds = vendorConsentIds,
                vendorLiIds = vendorLiIds,
                gvlBean = gvlBean
            ),
            decision = RemoteCmpDecision(consentAction = actionType)
        ) ?: error("unsupported campaign actionType=$actionType")
    }

    private fun buildSilentConsentSeedForDecision(
        baseSeed: SilentConsentSeed,
        decision: RemoteCmpDecision
    ): SilentConsentSeed? {
        val actionType = decision.consentAction
        if (actionType == SILENT_ACCEPT_ALL_ACTION) {
            return baseSeed.copy(actionType = SILENT_ACCEPT_ALL_ACTION)
        }

        if (actionType == SILENT_REJECT_ACTION) {
            val purposeLiIds = mutableListOf<Int>()
            val customPurposeLiIds = mutableListOf<Int>()
            val vendorLiIds = mutableListOf<Int>()

            baseSeed.gvlBean.purposeList.orEmpty().forEach { purpose ->
                val id = purpose?.id ?: return@forEach
                if (purpose.purposeLITansparency == true) {
                    if (purpose.tclpurpose == true) {
                        customPurposeLiIds += id
                    } else {
                        purposeLiIds += id
                    }
                }
            }

            baseSeed.gvlBean.allVendorList.orEmpty().forEach { vendor ->
                val id = vendor?.id ?: return@forEach
                if (!vendor.legIntPurposes.isNullOrEmpty()) {
                    vendorLiIds += id
                }
            }

            return baseSeed.copy(
                actionType = SILENT_REJECT_ACTION,
                currentTcString = null,
                purposeConsentIds = emptyList(),
                purposeLiIds = purposeLiIds,
                customPurposeConsentIds = emptyList(),
                customPurposeLiIds = customPurposeLiIds,
                specialFeatureIds = emptyList(),
                vendorConsentIds = emptyList(),
                vendorLiIds = vendorLiIds
            )
        }

        if (actionType == REMOTE_SAVE_SETTINGS_ACTION) {
            val payload = decision.consentPayload ?: return null
            return baseSeed.copy(
                actionType = SILENT_SAVE_SETTINGS_ACTION,
                currentTcString = null,
                purposeConsentIds = sanitizeIds(payload.purposeConsentIds, baseSeed.gvlBean.purposeIdList?.filterNotNull()),
                purposeLiIds = sanitizeIds(payload.purposeLiIds, baseSeed.gvlBean.purposeIdList?.filterNotNull()),
                customPurposeConsentIds = sanitizeIds(payload.customPurposeConsentIds, baseSeed.gvlBean.purposeIdList?.filterNotNull()),
                customPurposeLiIds = sanitizeIds(payload.customPurposeLiIds, baseSeed.gvlBean.purposeIdList?.filterNotNull()),
                specialFeatureIds = sanitizeIds(payload.specialFeatureIds, baseSeed.gvlBean.specialFeatureIdList?.filterNotNull()),
                vendorConsentIds = sanitizeIds(payload.vendorConsentIds, baseSeed.gvlBean.allVendorIdList?.filterNotNull()),
                vendorLiIds = sanitizeIds(payload.vendorLiIds, baseSeed.gvlBean.allVendorIdList?.filterNotNull())
            )
        }

        return null
    }

    private fun generateSilentTcString(context: Context, seed: SilentConsentSeed): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            Log.w(TAG, "静默同意链路：当前系统版本低于 Android 8.0，无法按 SDK 规则生成 TC String，sdk=${Build.VERSION.SDK_INT}")
            return null
        }
        val vendorListVersion = seed.gvlBean.vendorListVersion ?: return null
        val tcfPolicyVersion = seed.gvlBean.tcfPolicyVersion ?: return null
        val consentLanguage = resolveCmpLanguage(context)
        return runCatching {
            // Mirror SDK ConsentUtils.a.a(...) generation rules instead of keeping a fixed tc string.
            val consentUtilsClass = Class.forName("com.tcl.ff.component.oversea.c.b")
            val consentUtils = consentUtilsClass.getField("a").get(null)
            val generateMethod = consentUtilsClass.declaredMethods.first {
                it.name == "a" && it.parameterTypes.size == 12
            }.apply {
                isAccessible = true
            }
            generateMethod.invoke(
                consentUtils,
                consentLanguage,
                vendorListVersion,
                tcfPolicyVersion,
                seed.purposeConsentIds,
                seed.purposeLiIds,
                seed.customPurposeConsentIds,
                seed.customPurposeLiIds,
                seed.specialFeatureIds,
                seed.vendorConsentIds,
                seed.vendorLiIds,
                seed.gvlBean.allVendorIdList,
                SILENT_CONSENT_SCREEN
            ) as? String
        }.onFailure { error ->
            Log.e(TAG, "静默同意链路：按 SDK 规则生成 TC String 失败", error)
        }.getOrNull()
    }

    private fun buildSilentConsentJson(
        seed: SilentConsentSeed,
        tcString: String,
        createdAtMs: Long,
        expireDurationMs: Long
    ): String {
        val root = JsonObject().apply {
            addProperty("action_type", seed.actionType)
            addProperty("cmp_tc_string", tcString)
            addProperty("tc_string_create_time", createdAtMs)
            addProperty("tc_string_expire_time", expireDurationMs)
            add("purpose_consent_list", gson.toJsonTree(seed.purposeConsentIds))
            add("purpose_li_list", gson.toJsonTree(seed.purposeLiIds))
            add("custom_purpose_consent_list", gson.toJsonTree(seed.customPurposeConsentIds))
            add("custom_purpose_li_list", gson.toJsonTree(seed.customPurposeLiIds))
            add("special_feature_consent_list", gson.toJsonTree(seed.specialFeatureIds))
            add("vendor_consent_list", gson.toJsonTree(seed.vendorConsentIds))
            add("vendor_li_list", gson.toJsonTree(seed.vendorLiIds))
            seed.campaignId?.let { addProperty("campaign_id", it) }
            addProperty("campaign_bean_string", seed.campaignBeanString)
            addProperty("has_new_campaign", seed.hasNewCampaign)
        }
        return gson.toJson(root)
    }

    private fun resolveSdkConsentFile(context: Context): java.io.File {
        return java.io.File(context.filesDir.absolutePath + "CmpConsent/consent.0")
    }

    private fun consumePendingSdkSyncIfNeeded(context: Context) {
        val pendingState = loadPendingSdkSyncState(context) ?: return
        Log.i(
            TAG,
            "静默同意链路：检测到待补的 SDK user/action 状态，action=${pendingState.reportAction}，campaignId=${pendingState.seed.campaignId}，hash=${pendingState.uploadHash}"
        )
        Hq008ConsentLogReporter.report(
            eventType = "PENDING_USER_ACTION_FOUND",
            eventMessage = "action=${pendingState.reportAction},campaignId=${pendingState.seed.campaignId},hash=${pendingState.uploadHash}"
        )
        syncSdkUserAction(context, pendingState)
    }

    private fun consumePendingConsentReportIfNeeded(context: Context) {
        val pendingState = loadPendingConsentReportState(context) ?: return
        Log.i(
            TAG,
            "静默同意链路：检测到待补的 consent-report 状态，action=${pendingState.reportAction}，cycleKey=${pendingState.cycleKey.orEmpty()}"
        )
        Hq008ConsentLogReporter.report(
            eventType = "PENDING_CONSENT_REPORT_FOUND",
            eventMessage = "action=${pendingState.reportAction},cycleKey=${pendingState.cycleKey.orEmpty()}"
        )
        reportConsentResult(
            actionType = pendingState.reportAction,
            onSuccess = {
                clearPendingConsentReportState(context)
            }
        )
    }

    private fun syncSdkUserAction(
        context: Context,
        state: PendingSdkSyncState,
        onCompleted: (() -> Unit)? = null,
        onFailure: (() -> Unit)? = null
    ) {
        persistPendingSdkSyncState(context, state)
        reportCmpTrace(
            eventType = "PENDING_SDK_SYNC_STORED",
            eventMessage = "cycleKey=${state.cycleKey.orEmpty()},reportAction=${state.reportAction},hash=${state.uploadHash},${summarizeSeed(state.seed)}"
        )
        uploadSilentUserActionIfNeeded(
            context = context,
            state = SilentBootstrapState(
                seed = state.seed,
                uploadHash = state.uploadHash
            ),
            onCompleted = {
                clearPendingSdkSyncState(context)
                enqueueOrReportConsentResult(
                    context = context,
                    cycleKey = state.cycleKey,
                    actionType = state.reportAction,
                    onCompleted = onCompleted
                )
            },
            onFailure = {
                onFailure?.invoke()
            }
        )
    }

    private fun recoverLocalConsentFromRemoteDecision(
        context: Context,
        cycleKey: String,
        reportAction: String,
        seed: SilentConsentSeed,
        remoteTcString: String,
        onCompleted: (() -> Unit)? = null
    ) {
        val tcString = remoteTcString.takeIf { it.isNotBlank() } ?: run {
            Log.w(TAG, "静默同意链路：remote recovery 缺少有效 tcString，回退到显式执行 action=$reportAction")
            reportCmpTrace(
                eventType = "CMP_REMOTE_RECOVERY_FALLBACK",
                eventMessage = "action=$reportAction,cmpCycleKey=$cycleKey,reason=remote_tc_empty,${summarizeSeed(seed)}"
            )
            executeSilentDecision(
                context = context,
                cycleKey = cycleKey,
                reportAction = reportAction,
                seed = seed,
                onCompleted = onCompleted
            )
            return
        }
        persistSilentConsentState(
            context = context,
            seed = seed,
            tcString = tcString,
            uploadHash = buildSilentUserActionHash(seed),
            source = "remote_recovery_${seed.actionType.lowercase(Locale.US)}",
            preferExistingSdkState = false
        )
        updateCmpSnapshot(
            context = context,
            consentString = tcString,
            needShowPop = false,
            seed = currentCmpSeed,
            reason = "remote_recovery"
        )
        Log.i(
            TAG,
            "静默同意链路：检测到云端已存在终态 consent，改为恢复本地状态并结束本轮，action=$reportAction，campaignId=${seed.campaignId}，cmpCycleKey=$cycleKey"
        )
        Hq008ConsentLogReporter.report(
            eventType = "CMP_REMOTE_RECOVERY",
            eventMessage = "action=$reportAction,campaignId=${seed.campaignId},cmpCycleKey=$cycleKey"
        )
        onCompleted?.invoke()
    }

    private fun uploadSilentUserActionIfNeeded(
        context: Context,
        state: SilentBootstrapState,
        onCompleted: (() -> Unit)? = null,
        onFailure: (() -> Unit)? = null
    ) {
        Thread {
            var requestBodyForLog: String? = null
            var requestUrlForLog: String? = null
            var responseBodyForLog: String? = null
            var durationMsForLog: Long? = null
            runCatching {
                // Keep silent replay payload aligned with SDK CmpUserActionRequestParams.
                val requestParamsClass = Class.forName("com.tcl.ff.component.oversea.model.requset.c")
                val requestParams = requestParamsClass.getDeclaredConstructor().newInstance()
                val requestParamsBaseClass = requestParamsClass.superclass
                val setBaseMethod = requestParamsBaseClass.getDeclaredMethod("a", CmpConfigParams::class.java).apply {
                    isAccessible = true
                }
                val setUpActionMethod = requestParamsClass.getDeclaredMethod(
                    "a",
                    GvlBean::class.java,
                    Integer::class.java,
                    String::class.java,
                    Integer::class.java,
                    Integer::class.java
                ).apply {
                    isAccessible = true
                }
                val toJsonMethod = requestParamsBaseClass.getDeclaredMethod("a").apply {
                    isAccessible = true
                }
                setBaseMethod.invoke(requestParams, buildCmpConfig(context, forcePopup = false))
                setUpActionMethod.invoke(
                    requestParams,
                    state.seed.gvlBean,
                    resolveSilentUserActionCode(state.seed.actionType),
                    resolveCmpLanguage(context),
                    state.seed.campaignId,
                    SILENT_CONSENT_SCREEN
                )
                val requestBody = toJsonMethod.invoke(requestParams) as? String
                requestBodyForLog = requestBody

                val serverApiClass = Class.forName("com.tcl.ff.component.oversea.constant.b")
                val serverApi = serverApiClass.getField("a").get(null)
                val requestUrl = serverApiClass.getDeclaredMethod("d").apply {
                    isAccessible = true
                }.invoke(serverApi) as? String
                requestUrlForLog = requestUrl

                if (requestBody.isNullOrBlank() || requestUrl.isNullOrBlank()) {
                    error("failed to build silent user/action request payload")
                }
                Log.i(
                    TAG,
                    "静默同意链路：准备补发 user/action，上报 hash=${state.uploadHash}，campaignId=${state.seed.campaignId}，actionType=${state.seed.actionType}，body=$requestBody"
                )
                Hq008ConsentLogReporter.report(
                    eventType = "USER_ACTION_START",
                    eventMessage = "action=${state.seed.actionType},campaignId=${state.seed.campaignId},hash=${state.uploadHash}",
                    adLog = buildCmpApiAdLog(
                        api = "userAction",
                        url = requestUrl,
                        requestBody = requestBody,
                        responseBody = null,
                        durationMs = null,
                        success = false
                    )
                )
                val startedAt = SystemClock.elapsedRealtime()
                val responseBody = HttpRequester.get().postJsonSync(requestUrl, requestBody)
                durationMsForLog = SystemClock.elapsedRealtime() - startedAt
                responseBodyForLog = responseBody
                if (!isServerSuccessResponse(responseBody)) {
                    error("user/action server rejected request, response=$responseBody")
                }
                Log.i(
                    TAG,
                    "静默同意链路：user/action 原始响应，hash=${state.uploadHash}，actionType=${state.seed.actionType}，response=$responseBody"
                )
                Log.i(TAG, "静默同意链路：user/action 补发成功，hash=${state.uploadHash}")
                Hq008ConsentLogReporter.report(
                    eventType = "USER_ACTION_SUCCESS",
                    eventMessage = "action=${state.seed.actionType},campaignId=${state.seed.campaignId},hash=${state.uploadHash}",
                    adLog = buildCmpApiAdLog(
                        api = "userAction",
                        url = requestUrl,
                        requestBody = requestBody,
                        responseBody = responseBody,
                        durationMs = durationMsForLog,
                        success = true
                    )
                )
                onCompleted?.invoke()
            }.onFailure { error ->
                Log.e(TAG, "静默同意链路：user/action 补发失败，hash=${state.uploadHash}", error)
                Hq008ConsentLogReporter.report(
                    eventType = "USER_ACTION_FAIL",
                    eventMessage = "action=${state.seed.actionType},campaignId=${state.seed.campaignId},hash=${state.uploadHash},error=${error.message}",
                    adLog = buildCmpApiAdLog(
                        api = "userAction",
                        url = requestUrlForLog,
                        requestBody = requestBodyForLog,
                        responseBody = responseBodyForLog,
                        durationMs = durationMsForLog,
                        success = false,
                        error = error
                    )
                )
                onFailure?.invoke()
            }
        }.start()
    }

    private fun tryReflectiveUserActionIfPossible(
        context: Context,
        seed: SilentConsentSeed
    ): ReflectiveSdkActionResult? {
        return when (seed.actionType) {
            SILENT_ACCEPT_ALL_ACTION -> tryReflectiveSilentAction(
                context = context,
                seed = seed,
                intentClassName = "com.tcl.ff.component.oversea.b.a\$a",
                actionName = "AcceptAll"
            )
            SILENT_REJECT_ACTION -> tryReflectiveSilentAction(
                context = context,
                seed = seed,
                intentClassName = "com.tcl.ff.component.oversea.b.a\$b",
                actionName = "AcceptEssential"
            )
            SILENT_SAVE_SETTINGS_ACTION -> tryReflectiveSilentAction(
                context = context,
                seed = seed,
                intentClassName = "com.tcl.ff.component.oversea.b.a\$i",
                actionName = "SaveSettings"
            )
            else -> null
        }
    }

    private fun tryReflectiveAcceptAllIfPossible(
        context: Context,
        seed: SilentConsentSeed
    ): ReflectiveSdkActionResult? {
        // com.tcl.ff.component.oversea.b.a$a
        return tryReflectiveSilentAction(
            context = context,
            seed = seed,
            intentClassName = "com.tcl.ff.component.oversea.b.a\$a",
            actionName = "AcceptAll"
        )
    }

    private fun tryReflectiveSilentAction(
        context: Context,
        seed: SilentConsentSeed,
        intentClassName: String,
        actionName: String
    ): ReflectiveSdkActionResult? {
        return runCatching {
            val cmpIntentInterfaceClass = Class.forName("com.tcl.ff.component.oversea.b.a")
            val actionIntentClass = Class.forName(intentClassName)
            val cmpViewModelClass = Class.forName("com.tcl.ff.component.oversea.e.a")
            val cmpViewModel = cmpViewModelClass.getDeclaredConstructor().newInstance()
            val actionIntent = actionIntentClass.getDeclaredConstructor(
                GvlBean::class.java,
                CmpConfigParams::class.java,
                Integer::class.java,
                Int::class.javaPrimitiveType
            ).newInstance(
                seed.gvlBean,
                buildCmpConfig(context, forcePopup = false),
                seed.campaignId,
                SILENT_CONSENT_SCREEN
            )
            val processIntentMethod = cmpViewModelClass.getDeclaredMethod("b", cmpIntentInterfaceClass).apply {
                isAccessible = true
            }
            Log.i(TAG, "静默同意链路：开始尝试通过反射触发 SDK 原生 $actionName")
            processIntentMethod.invoke(cmpViewModel, actionIntent)
            waitForReflectiveSdkActionResult(context, seed)
        }.onFailure { error ->
            Log.e(TAG, "静默同意链路：反射触发 SDK 原生 $actionName 失败", error)
        }.getOrNull()
    }

    private fun waitForReflectiveSdkActionResult(
        context: Context,
        expectedSeed: SilentConsentSeed,
        timeoutMs: Long = 4_000L
    ): ReflectiveSdkActionResult? {
        return runCatching {
            val startedAt = SystemClock.elapsedRealtime()
            while (SystemClock.elapsedRealtime() - startedAt < timeoutMs) {
                val observedSeed = loadSilentConsentSeedViaSdk(context)
                if (isReflectiveSdkActionStateCompatible(observedSeed, expectedSeed)) {
                    val tcString = observedSeed?.currentTcString?.takeIf { it.isNotBlank() }
                    Log.i(
                        TAG,
                        "静默同意链路：反射链路已检测到 SDK 状态落地，actionType=${expectedSeed.actionType}，consentLength=${tcString?.length ?: 0}"
                    )
                    return ReflectiveSdkActionResult(
                        tcString = tcString,
                        persistedSeed = observedSeed
                    )
                }
                Thread.sleep(50)
            }
            null
        }.onFailure { error ->
            Log.e(TAG, "静默同意链路：等待反射链路落地 SDK 状态失败", error)
        }.getOrNull()
    }

    private fun isReflectiveSdkActionStateCompatible(
        observedSeed: SilentConsentSeed?,
        expectedSeed: SilentConsentSeed
    ): Boolean {
        observedSeed ?: return false
        return observedSeed.actionType == expectedSeed.actionType &&
            observedSeed.campaignId == expectedSeed.campaignId &&
            observedSeed.hasNewCampaign == expectedSeed.hasNewCampaign &&
            observedSeed.purposeConsentIds.toSet() == expectedSeed.purposeConsentIds.toSet() &&
            observedSeed.purposeLiIds.toSet() == expectedSeed.purposeLiIds.toSet() &&
            observedSeed.customPurposeConsentIds.toSet() == expectedSeed.customPurposeConsentIds.toSet() &&
            observedSeed.customPurposeLiIds.toSet() == expectedSeed.customPurposeLiIds.toSet() &&
            observedSeed.specialFeatureIds.toSet() == expectedSeed.specialFeatureIds.toSet() &&
            observedSeed.vendorConsentIds.toSet() == expectedSeed.vendorConsentIds.toSet() &&
            observedSeed.vendorLiIds.toSet() == expectedSeed.vendorLiIds.toSet()
    }

    private fun tryReflectiveMaybeLaterIfPossible(
        context: Context,
        seed: SilentConsentSeed
    ): Boolean {
        return runCatching {
            val cmpIntentInterfaceClass = Class.forName("com.tcl.ff.component.oversea.b.a")
            val actionIntentClass = Class.forName("com.tcl.ff.component.oversea.b.a\$d")
            val cmpViewModelClass = Class.forName("com.tcl.ff.component.oversea.e.a")
            val cmpViewModel = cmpViewModelClass.getDeclaredConstructor().newInstance()
            val actionIntent = actionIntentClass.getDeclaredConstructor(
                GvlBean::class.java,
                CmpConfigParams::class.java,
                Integer::class.java,
                Boolean::class.javaPrimitiveType,
                Int::class.javaPrimitiveType
            ).newInstance(
                seed.gvlBean,
                buildCmpConfig(context, forcePopup = false),
                seed.campaignId,
                false,
                SILENT_CONSENT_SCREEN
            )
            val processIntentMethod = cmpViewModelClass.getDeclaredMethod("b", cmpIntentInterfaceClass).apply {
                isAccessible = true
            }
            Log.i(TAG, "静默同意链路：开始尝试通过反射触发 SDK 原生 MAYBE_LATER")
            processIntentMethod.invoke(cmpViewModel, actionIntent)
            true
        }.onFailure { error ->
            Log.e(TAG, "静默同意链路：反射触发 SDK 原生 MAYBE_LATER 失败", error)
        }.getOrDefault(false)
    }

    private fun resolveSilentUserActionCode(actionType: String): Int {
        return when (actionType) {
            SILENT_ACCEPT_ALL_ACTION -> SILENT_ACCEPT_ALL_ACTION_CODE
            SILENT_REJECT_ACTION -> 1
            SILENT_SAVE_SETTINGS_ACTION -> 4
            REMOTE_MAYBE_LATER_ACTION -> 2
            else -> SILENT_ACCEPT_ALL_ACTION_CODE
        }
    }

    private fun resolveCmpLanguage(context: Context): String {
        return runCatching {
            val utilsClass = Class.forName("com.tcl.ff.component.oversea.c.e")
            val utils = utilsClass.getField("a").get(null)
            utilsClass.getDeclaredMethod("a", Context::class.java).apply {
                isAccessible = true
            }.invoke(utils, context.applicationContext) as? String ?: Locale.getDefault().language
        }
            .getOrElse { Locale.getDefault().language }
    }

    private fun resolveLocalSdkActionType(actionType: ActionType?): String {
        return when (actionType) {
            ActionType.ACCEPT_ALL -> SILENT_ACCEPT_ALL_ACTION
            ActionType.REJECT -> SILENT_REJECT_ACTION
            ActionType.SAVE_AND_EXIT -> SILENT_SAVE_SETTINGS_ACTION
            else -> SILENT_ACCEPT_ALL_ACTION
        }
    }

    private fun mapSdkIdSet(rawValue: Any?): List<Int> {
        return (rawValue as? Set<*>)?.mapNotNull { (it as? Number)?.toInt() } ?: emptyList()
    }

    private fun requestCmpCampaignViaSdkRepository(
        context: Context,
        forcePopup: Boolean
    ): CmpCampaignBean? {
        val repository = CmpSdkRepository()
        val method = repository.javaClass.getDeclaredMethod(
            "a",
            java.lang.Boolean::class.java,
            CmpConfigParams::class.java,
            kotlin.coroutines.Continuation::class.java
        ).apply {
            isAccessible = true
        }
        return method.invoke(repository, forcePopup, buildCmpConfig(context, forcePopup), null) as? CmpCampaignBean
    }

    private fun requestCmpConsentStatusViaSdkRepository(
        context: Context,
        campaignVersion: Int?
    ): CmpConsentBean? {
        val repository = CmpSdkRepository()
        val method = repository.javaClass.getDeclaredMethod(
            "a",
            Integer::class.java,
            CmpConfigParams::class.java,
            kotlin.coroutines.Continuation::class.java
        ).apply {
            isAccessible = true
        }
        return method.invoke(repository, campaignVersion, buildCmpConfig(context, forcePopup = false), null) as? CmpConsentBean
    }

    private fun buildSilentUserActionHash(seed: SilentConsentSeed): String {
        val stableCampaignKey = seed.campaignId?.toString()
            ?: seed.campaignBeanString.hashCode().toString()
        return "$stableCampaignKey|${seed.actionType}".hashCode().toString()
    }

    private fun buildCmpCycleKey(context: Context, needShowPop: Boolean): String {
        val activeSeed = currentCmpSeed ?: loadSilentConsentSeedFromLocal(context)
        val campaignId = activeSeed?.campaignId?.toString().orEmpty()
        val expireAt = activeSeed?.tcStringExpireTime?.toString().orEmpty()
        val hasNewCampaign = activeSeed?.hasNewCampaign?.toString().orEmpty()
        val expired = isConsentExpired(context)
        return "$campaignId|$expireAt|$hasNewCampaign|$needShowPop|$expired"
    }

    private fun isLocalConsentStringInvalid(seed: SilentConsentSeed?): Boolean {
        val tcString = seed?.currentTcString
        val createdAt = seed?.tcStringCreateTime
        val expireDuration = seed?.tcStringExpireTime
        if (tcString.isNullOrBlank() || createdAt == null || expireDuration == null) {
            return true
        }
        if (createdAt <= 0L || expireDuration <= 0L) {
            return true
        }
        return System.currentTimeMillis() - createdAt > expireDuration
    }

    private fun isTerminalRemoteAction(actionType: String): Boolean {
        return actionType == SILENT_ACCEPT_ALL_ACTION ||
            actionType == SILENT_REJECT_ACTION ||
            actionType == REMOTE_SAVE_SETTINGS_ACTION
    }

    private fun isTerminalSdkAction(actionType: String): Boolean {
        return actionType == SILENT_ACCEPT_ALL_ACTION ||
            actionType == SILENT_REJECT_ACTION ||
            actionType == SILENT_SAVE_SETTINGS_ACTION
    }

    private fun sanitizeIds(input: List<Int>, allowed: List<Int>?): List<Int> {
        val allowedSet = allowed?.toSet() ?: return emptyList()
        return input.distinct().filter { allowedSet.contains(it) }
    }

    private fun reportConsentResult(
        actionType: String,
        onSuccess: (() -> Unit)? = null,
        onFailure: (() -> Unit)? = null
    ) {
        reportCmpTrace(
            eventType = "CONSENT_REPORT_START",
            eventMessage = "actionType=$actionType"
        )
        Hq008CmpDecisionClient.reportConsentResult(actionType) { error ->
            if (error != null) {
                Log.e(TAG, "静默同意链路：consent-report 上报失败，actionType=$actionType，error=$error")
                reportCmpTrace(
                    eventType = "CONSENT_REPORT_FAIL",
                    eventMessage = "actionType=$actionType,error=$error"
                )
                onFailure?.invoke()
            } else {
                Log.i(TAG, "静默同意链路：consent-report 上报成功，actionType=$actionType")
                reportCmpTrace(
                    eventType = "CONSENT_REPORT_SUCCESS",
                    eventMessage = "actionType=$actionType"
                )
                onSuccess?.invoke()
            }
        }
    }

    private fun enqueueOrReportConsentResult(
        context: Context,
        cycleKey: String?,
        actionType: String,
        onCompleted: (() -> Unit)? = null
    ) {
        persistPendingConsentReportState(
            context = context,
            state = PendingConsentReportState(
                cycleKey = cycleKey,
                reportAction = actionType
            )
        )
        reportCmpTrace(
            eventType = "CONSENT_REPORT_ENQUEUED",
            eventMessage = "cycleKey=${cycleKey.orEmpty()},actionType=$actionType"
        )
        reportConsentResult(
            actionType = actionType,
            onSuccess = {
                clearPendingConsentReportState(context)
                onCompleted?.invoke()
            },
            onFailure = {
                onCompleted?.invoke()
            }
        )
    }

    private fun persistPendingSdkSyncState(
        context: Context,
        state: PendingSdkSyncState
    ) {
        val payload = JsonObject().apply {
            state.cycleKey?.let { addProperty("cycle_key", it) }
            addProperty("report_action", state.reportAction)
            addProperty("upload_hash", state.uploadHash)
            addProperty("action_type", state.seed.actionType)
            state.seed.campaignId?.let { addProperty("campaign_id", it) }
            addProperty("campaign_bean_string", state.seed.campaignBeanString)
            addProperty("has_new_campaign", state.seed.hasNewCampaign)
            add("purpose_consent_list", gson.toJsonTree(state.seed.purposeConsentIds))
            add("purpose_li_list", gson.toJsonTree(state.seed.purposeLiIds))
            add("custom_purpose_consent_list", gson.toJsonTree(state.seed.customPurposeConsentIds))
            add("custom_purpose_li_list", gson.toJsonTree(state.seed.customPurposeLiIds))
            add("special_feature_consent_list", gson.toJsonTree(state.seed.specialFeatureIds))
            add("vendor_consent_list", gson.toJsonTree(state.seed.vendorConsentIds))
            add("vendor_li_list", gson.toJsonTree(state.seed.vendorLiIds))
        }
        context.getSharedPreferences(SILENT_BOOTSTRAP_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PENDING_SDK_SYNC_STATE, gson.toJson(payload))
            .apply()
    }

    private fun loadPendingSdkSyncState(context: Context): PendingSdkSyncState? {
        val raw = context.getSharedPreferences(SILENT_BOOTSTRAP_PREFS, Context.MODE_PRIVATE)
            .getString(KEY_PENDING_SDK_SYNC_STATE, null)
            ?: return null
        return runCatching {
            val root = JsonParser.parseString(raw).asJsonObject
            val campaignBeanString = root.stringOrNull("campaign_bean_string")
                ?: error("missing campaign_bean_string")
            val gvlBean = gson.fromJson(campaignBeanString, GvlBean::class.java)
                ?.also { it.makeUpData() }
                ?: error("failed to parse gvlBean")
            PendingSdkSyncState(
                cycleKey = root.stringOrNull("cycle_key"),
                reportAction = root.stringOrNull("report_action")
                    ?: error("missing report_action"),
                uploadHash = root.stringOrNull("upload_hash")
                    ?: error("missing upload_hash"),
                seed = SilentConsentSeed(
                    actionType = root.stringOrNull("action_type")
                        ?: error("missing action_type"),
                    campaignId = root.intOrNull("campaign_id"),
                    campaignBeanString = campaignBeanString,
                    currentTcString = null,
                    tcStringCreateTime = null,
                    tcStringExpireTime = null,
                    hasNewCampaign = root.booleanOrDefault("has_new_campaign", false),
                    purposeConsentIds = root.intListOrEmpty("purpose_consent_list"),
                    purposeLiIds = root.intListOrEmpty("purpose_li_list"),
                    customPurposeConsentIds = root.intListOrEmpty("custom_purpose_consent_list"),
                    customPurposeLiIds = root.intListOrEmpty("custom_purpose_li_list"),
                    specialFeatureIds = root.intListOrEmpty("special_feature_consent_list"),
                    vendorConsentIds = root.intListOrEmpty("vendor_consent_list"),
                    vendorLiIds = root.intListOrEmpty("vendor_li_list"),
                    gvlBean = gvlBean
                )
            )
        }.onFailure { error ->
            Log.e(TAG, "静默同意链路：解析待补 SDK user/action 状态失败，已清理脏数据", error)
            reportCmpTrace(
                eventType = "PENDING_SDK_SYNC_INVALID",
                eventMessage = "error=${error.message ?: "unknown"}"
            )
            clearPendingSdkSyncState(context)
        }.getOrNull()
    }

    private fun clearPendingSdkSyncState(context: Context) {
        context.getSharedPreferences(SILENT_BOOTSTRAP_PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_PENDING_SDK_SYNC_STATE)
            .apply()
    }

    private fun persistPendingConsentReportState(
        context: Context,
        state: PendingConsentReportState
    ) {
        val payload = JsonObject().apply {
            state.cycleKey?.let { addProperty("cycle_key", it) }
            addProperty("report_action", state.reportAction)
        }
        context.getSharedPreferences(SILENT_BOOTSTRAP_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PENDING_CONSENT_REPORT_STATE, gson.toJson(payload))
            .apply()
    }

    private fun loadPendingConsentReportState(context: Context): PendingConsentReportState? {
        val raw = context.getSharedPreferences(SILENT_BOOTSTRAP_PREFS, Context.MODE_PRIVATE)
            .getString(KEY_PENDING_CONSENT_REPORT_STATE, null)
            ?: return null
        return runCatching {
            val root = JsonParser.parseString(raw).asJsonObject
            PendingConsentReportState(
                cycleKey = root.stringOrNull("cycle_key"),
                reportAction = root.stringOrNull("report_action")
                    ?: error("missing report_action")
            )
        }.onFailure { error ->
            Log.e(TAG, "静默同意链路：解析待补 consent-report 状态失败，已清理脏数据", error)
            reportCmpTrace(
                eventType = "PENDING_CONSENT_REPORT_INVALID",
                eventMessage = "error=${error.message ?: "unknown"}"
            )
            clearPendingConsentReportState(context)
        }.getOrNull()
    }

    private fun clearPendingConsentReportState(context: Context) {
        context.getSharedPreferences(SILENT_BOOTSTRAP_PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_PENDING_CONSENT_REPORT_STATE)
            .apply()
    }

    private fun resolveReportAction(actionType: String): String {
        return when (actionType) {
            SILENT_SAVE_SETTINGS_ACTION -> REMOTE_SAVE_SETTINGS_ACTION
            else -> actionType
        }
    }

    private fun isServerSuccessResponse(responseBody: String?): Boolean {
        return runCatching {
            JsonParser.parseString(responseBody).asJsonObject.intOrNull("code") == 100000
        }.getOrDefault(false)
    }

    private fun JsonObject.stringOrNull(key: String): String? {
        return get(key)?.takeIf { !it.isJsonNull }?.asString
    }

    private fun JsonObject.intOrNull(key: String): Int? {
        return get(key)?.takeIf { !it.isJsonNull }?.asInt
    }

    private fun JsonObject.longOrNull(key: String): Long? {
        return get(key)?.takeIf { !it.isJsonNull }?.asLong
    }

    private fun JsonObject.booleanOrDefault(key: String, defaultValue: Boolean): Boolean {
        return get(key)?.takeIf { !it.isJsonNull }?.asBoolean ?: defaultValue
    }

    private fun JsonObject.intListOrEmpty(key: String): List<Int> {
        val element = get(key) ?: return emptyList()
        if (!element.isJsonArray) {
            return emptyList()
        }
        return element.asJsonArray.mapNotNull { item ->
            item.takeIf { !it.isJsonNull }?.asInt
        }
    }

    private fun JsonObject.looksLikeCampaignPayload(): Boolean {
        return has("campaignId") || has("campaign_id") || has("gvlSource") || has("consentCookieExpiration")
    }
}
