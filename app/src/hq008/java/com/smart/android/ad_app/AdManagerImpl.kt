package com.smart.android.ad_app

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.smart.android.ad_app.AdLocalLog as Log
import android.view.ViewGroup
import com.tcl.ff.component.overseabase.base.constant.AdReportSwitchConfig
import com.tcl.ff.component.overseabase.base.constant.AdType
import com.tcl.ff.component.overseabasebusiness.requestparams.RequestParams
import com.tcl.ff.component.vastad.Ad
import com.tcl.ff.component.vastad.Controller
import com.tcl.ff.component.vastad.Initialization
import com.tcl.ff.component.vastad.MediaAdInitListener
import com.tcl.ff.component.vastad.core.callbacks.AdStatusListener
import org.json.JSONObject
import java.lang.ref.WeakReference
import java.util.Locale

object AdManagerImpl : IAdManager {
    override fun init() {
        if (BuildFlavor.isLsap()) {
            HaierLsapAdManagerBridge.init()
            return
        }
        Hq008TclVideoAd.init()
    }

    override fun showAd(
        flRoot: ViewGroup,
        adId: String?,
        soundEnabled: Boolean,
        callbackTimeoutMs: Long?,
        adStart: (() -> Unit)?,
        adError: (() -> Unit)?,
        adComplete: () -> Unit
    ) {
        if (BuildFlavor.isLsap()) {
            HaierLsapAdManagerBridge.showAd(
                flRoot = flRoot,
                adId = adId,
                soundEnabled = soundEnabled,
                callbackTimeoutMs = callbackTimeoutMs,
                adStart = adStart,
                adError = adError,
                adComplete = adComplete
            )
            return
        }
        Hq008TclVideoAd.showAd(
            flRoot = flRoot,
            adId = adId,
            soundEnabled = soundEnabled,
            callbackTimeoutMs = callbackTimeoutMs,
            adStart = adStart,
            adError = adError,
            adComplete = adComplete
        )
    }

    override fun destroyAd() = destroyAd(null)

    override fun destroyAd(requestId: String?) = destroyAd(requestId, "window_destroy")

    override fun destroyAd(requestId: String?, reason: String) {
        if (BuildFlavor.isLsap()) {
            HaierLsapAdManagerBridge.destroyAd(requestId, reason)
            return
        }
        Hq008TclVideoAd.destroyAd(requestId, reason)
    }
}

private object HaierLsapAdManagerBridge : IAdManager {
    private const val CLASS_NAME = "com.smart.android.ad_app.HaierLsapAdManager"

    private val delegate: IAdManager by lazy {
        Class.forName(CLASS_NAME)
            .getField("INSTANCE")
            .get(null) as IAdManager
    }

    override fun init() {
        delegate.init()
    }

    override fun showAd(
        flRoot: ViewGroup,
        adId: String?,
        soundEnabled: Boolean,
        callbackTimeoutMs: Long?,
        adStart: (() -> Unit)?,
        adError: (() -> Unit)?,
        adComplete: () -> Unit
    ) {
        delegate.showAd(
            flRoot = flRoot,
            adId = adId,
            soundEnabled = soundEnabled,
            callbackTimeoutMs = callbackTimeoutMs,
            adStart = adStart,
            adError = adError,
            adComplete = adComplete
        )
    }

    override fun destroyAd() = delegate.destroyAd()
    override fun destroyAd(requestId: String?) = delegate.destroyAd(requestId)
    override fun destroyAd(requestId: String?, reason: String) = delegate.destroyAd(requestId, reason)
}

/** The two public wrappers share one delegate in the pinned TCL 2.8.02 SDK. */
internal object Hq008TclControllerIdentity {
    private val delegateField by lazy {
        Controller::class.java.declaredFields.single {
            it.type.name.startsWith("com.tcl.ff.component.vastad.core.d.")
        }.apply { isAccessible = true }
    }

    fun of(controller: Controller): Any = delegateField.get(controller) ?: controller
}

private object Hq008TclVideoAd {
    private const val TAG = "Hq008TclVideoAd"
    private const val SDK_APP_CATEGORY = "app"
    private const val SDK_CONTENT_TITLE = "App Content"

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var initCompleted = false

    @Volatile
    private var initRequested = false

    private var pendingRequest: PendingShowRequest? = null
    private var currentRequest: PendingShowRequest? = null
    private val requestOwner = Hq008PlaybackRequestOwner()
    private val controllers = Hq008PlaybackControllers<Controller>(Hq008TclControllerIdentity::of)
    private var initAttempt = 0L
    private var initTimeoutRunnable: Runnable? = null

    fun init() = onMain { ensureInitialized() }

    fun showAd(
        flRoot: ViewGroup,
        adId: String?,
        soundEnabled: Boolean,
        callbackTimeoutMs: Long?,
        adStart: (() -> Unit)?,
        adError: (() -> Unit)?,
        adComplete: () -> Unit
    ) {
        val createdAtMs = SystemClock.elapsedRealtime()
        onMain {
            destroyAd(reason = "replaced_by_new_request")
            val requestId = Hq008ReportRequestIdResolver.resolve(adId)
            val effectiveCallbackTimeoutMs = callbackTimeoutMs ?: AdPlaybackPolicy.CALLBACK_TIMEOUT_MS
            // PLAY_FLOW showAd entry
            Log.i(
                TAG,
                "播放链路：开始请求广告，requestId=$requestId，adId=$adId，hidden=${AdDisplayConfig.isHiddenMode()}，callbackTimeoutMs=$effectiveCallbackTimeoutMs，container=${flRoot.width}x${flRoot.height}"
            )
            Log.i(TAG, "播放链路：已进入 showAd，当前隐藏模式=${AdDisplayConfig.isHiddenMode()}，容器尺寸=${flRoot.width}x${flRoot.height}")
            Hq008AdReporter.reportRequested(
                requestId = requestId,
                adId = adId,
                hiddenMode = AdDisplayConfig.isHiddenMode(),
                containerWidth = flRoot.width,
                containerHeight = flRoot.height,
                extra = mapOf(
                    "requestCreatedAtMs" to createdAtMs,
                    "callbackTimeoutMs" to effectiveCallbackTimeoutMs
                )
            )
            Hq008ConsentLogReporter.report(
                eventType = "AD_REQUESTED",
                eventMessage = "requestId=$requestId,adId=${adId.orEmpty()},hidden=${AdDisplayConfig.isHiddenMode()},callbackTimeoutMs=$effectiveCallbackTimeoutMs,containerWidth=${flRoot.width},containerHeight=${flRoot.height}"
            )
            val containerRef = WeakReference(flRoot)
            val controllerLease = controllers.begin { controller ->
                containerRef.get()?.let { container ->
                    runCatching { controller.stop(container) }
                        .onFailure { Log.w(TAG, "Stop TCL video ad failed.", it) }
                }
                runCatching { controller.release() }
                    .onFailure { Log.w(TAG, "Release TCL video ad failed.", it) }
            }
            val request = PendingShowRequest(
                requestId = requestId,
                adId = adId,
                soundEnabled = soundEnabled,
                callbackTimeoutMs = effectiveCallbackTimeoutMs,
                lifecycle = requestOwner.begin(createdAtMs, effectiveCallbackTimeoutMs),
                containerRef = containerRef,
                controllerLease = controllerLease,
                adStart = adStart,
                adError = adError,
                adComplete = adComplete
            )

            currentRequest = request
            armTimeout(request)
            if (!isInitialized()) {
                Log.i(TAG, "播放链路：媒体广告 SDK 尚未初始化完成，先缓存本次广告请求，等待初始化结束后继续")
                pendingRequest = request
                ensureInitialized()
                return@onMain
            }

            startAd(request)
        }
    }

    fun destroyAd(requestId: String? = null, reason: String = "window_destroy") = onMain {
        val request = currentRequest ?: return@onMain
        if (requestId != null && request.requestId != requestId) return@onMain
        if (request.markTerminal()) {
            Hq008ConsentLogReporter.report(
                eventType = "AD_PHASE_CANCELLED",
                eventMessage = "requestId=${request.requestId},adId=${request.adId.orEmpty()}," +
                    "stage=window_destroy,reason=$reason"
            )
        }
        clearActiveRequestState(request)
        releaseController(request)
    }

    private fun ensureInitialized() {
        if (isInitialized()) {
            startPendingRequestIfNeeded()
            return
        }
        if (initRequested) return
        initRequested = true
        val attempt = ++initAttempt
        initTimeoutRunnable = Runnable {
            if (initAttempt != attempt || !initRequested) return@Runnable
            resetInitializationAttempt()
            pendingRequest?.let { request ->
                if (request.lifecycle.remainingMs(SystemClock.elapsedRealtime()) == 0L) {
                    timeoutRequest(request)
                } else {
                    // Retry initialization without extending this request's original deadline.
                    ensureInitialized()
                }
            }
        }.also { mainHandler.postDelayed(it, AdPlaybackPolicy.CALLBACK_TIMEOUT_MS) }
        val switchConfig = AdReportSwitchConfig().apply {
            privacyAgreed = true
            uxpEnabled = true
            errorStatisticsEnabled = true
        }
        runCatching {
            Hq008AdSdkDebugCapture.ensureInstalled()
            Ad.get().setEnableLog(Hq008AdSdkDebugCapture.isSdkVerboseLogEnabled())
            Initialization.init(appContext, switchConfig, object : MediaAdInitListener {
                override fun onInitComplete() = onMain {
                    if (attempt != initAttempt) return@onMain
                    initCompleted = true
                    resetInitializationAttempt()
                    Log.i(TAG, "播放链路：TCL 媒体广告 SDK 初始化完成，准备继续处理待播放广告")
                    startPendingRequestIfNeeded()
                }
            })
            Log.i(TAG, "播放链路：已发起 TCL 媒体广告 SDK 初始化请求")
        }.onFailure { error ->
            if (attempt != initAttempt) return@onFailure
            resetInitializationAttempt()
            pendingRequest?.let { failRequest(it, "sdk_init", Hq008AdReporter.Message.INIT_ERROR, "sdk_init_failed", error) }
            Log.e(TAG, "播放链路：TCL 媒体广告 SDK 初始化失败", error)
        }
    }

    private fun resetInitializationAttempt() {
        initRequested = false
        initAttempt++
        initTimeoutRunnable?.let(mainHandler::removeCallbacks)
        initTimeoutRunnable = null
    }

    private fun isActive(request: PendingShowRequest): Boolean =
        currentRequest === request && requestOwner.isActive(request.lifecycle)

    private fun canContinue(request: PendingShowRequest): Boolean {
        if (!isActive(request)) return false
        if (requestOwner.canContinue(request.lifecycle, SystemClock.elapsedRealtime())) return true
        timeoutRequest(request)
        return false
    }

    private fun onMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action() else mainHandler.post(action)
    }

    private fun isInitialized(): Boolean {
        return initCompleted || Initialization.isHasInit()
    }

    private fun startPendingRequestIfNeeded() {
        val request = pendingRequest ?: return
        pendingRequest = null
        startAd(request)
    }

    private fun startAd(request: PendingShowRequest) {
        if (!canContinue(request)) return
        val container = request.containerRef.get()
        if (container == null) {
            failRequest(
                request = request,
                stage = "container_prepare",
                reporterMessage = Hq008AdReporter.Message.CONTAINER_RELEASED,
                reason = "container_released"
            )
            return
        }

        mainHandler.post {
            if (!canContinue(request)) return@post
            val safeContainer = request.containerRef.get()
            if (safeContainer == null) {
                failRequest(
                    request = request,
                    stage = "container_post",
                    reporterMessage = Hq008AdReporter.Message.CONTAINER_RELEASED,
                    reason = "container_released"
                )
                return@post
            }

            var hasNotifiedStart = false
            fun notifyStartOnce() {
                if (!hasNotifiedStart) {
                    hasNotifiedStart = true
                    request.adStart?.invoke()
                }
            }

            runCatching {
                val hiddenMode = AdDisplayConfig.isHiddenMode()
                safeContainer.alpha = if (hiddenMode) 0f else 1f
                // PLAY_FLOW startAd begin
                Log.i(
                    TAG,
                    "播放链路：开始执行 startAd，requestId=${request.requestId}，adId=${request.adId}，hidden=$hiddenMode，soundEnabled=${request.soundEnabled}，alpha=${safeContainer.alpha}"
                )
                Log.i(
                    TAG,
                    "播放链路：广告容器已准备就绪，hidden=$hiddenMode，alpha=${safeContainer.alpha}，container=${safeContainer.width}x${safeContainer.height}"
                )
                val controller = Ad.get()
                    .begin(appContext)
                    .lazyLoad()
                    .setAdType(AdType.WATERFALL)
                    .setVolume(if (request.soundEnabled) 1f else 0f)
                    .setRequestParams(buildRequestParams())
                    .listen(
                        object : AdStatusListener {
                            override fun onAdLoaded(controller: Controller) {
                                mainHandler.post {
                                    if (!canContinue(request)) {
                                        controllers.attach(request.controllerLease, controller)
                                        return@post
                                    }
                                    request.loadedAtMs = SystemClock.elapsedRealtime()
                                    Log.i(TAG, "播放链路：广告素材加载完成，hidden=${AdDisplayConfig.isHiddenMode()}")
                                    Hq008AdReporter.reportLoaded(
                                        requestId = request.requestId,
                                        adId = request.adId,
                                        hiddenMode = AdDisplayConfig.isHiddenMode(),
                                        extra = mapOf(
                                            "requestCreatedAtMs" to request.requestCreatedAtMs,
                                            "loadedAtMs" to request.loadedAtMs,
                                            "requestToLoadDurationMs" to request.requestToLoadDurationMs()
                                        )
                                    )
                                    Hq008ConsentLogReporter.report(
                                        eventType = "AD_LOADED",
                                        eventMessage = "requestId=${request.requestId},adId=${request.adId.orEmpty()},hidden=${AdDisplayConfig.isHiddenMode()},requestToLoadDurationMs=${request.requestToLoadDurationMs()}"
                                    )
                                    controllers.attach(request.controllerLease, controller)
                                    runCatching {
                                        Log.i(TAG, "播放链路：准备调用 controller.start() 开始播放")
                                        controller.start(safeContainer)
                                    }.onFailure { error ->
                                        failRequest(
                                            request = request,
                                            stage = "controller_start",
                                            reporterMessage = Hq008AdReporter.Message.START_ERROR,
                                            reason = "controller_start_failed",
                                            error = error
                                        )
                                    }
                                }
                            }

                            override fun onAdStartPlay() {
                                mainHandler.post {
                                    if (!canContinue(request)) {
                                        return@post
                                    }
                                    request.markStarted()
                                    Log.i(TAG, "播放链路：收到 onAdStartPlay 回调，hidden=${AdDisplayConfig.isHiddenMode()}，alpha=${safeContainer.alpha}")
                                    Hq008AdReporter.reportStarted(
                                        requestId = request.requestId,
                                        adId = request.adId,
                                        hiddenMode = AdDisplayConfig.isHiddenMode(),
                                        startProgress = null,
                                        extra = request.buildProgressDiagnostics()
                                    )
                                    Hq008ConsentLogReporter.report(
                                        eventType = "AD_STARTED",
                                        eventMessage = "requestId=${request.requestId},adId=${request.adId.orEmpty()},hidden=${AdDisplayConfig.isHiddenMode()},requestToStartDurationMs=${request.requestToStartDurationMs()}"
                                    )
                                    notifyStartOnce()
                                }
                            }

                            override fun onAdStartPlay(progress: Double) {
                                mainHandler.post {
                                    if (!canContinue(request)) {
                                        return@post
                                    }
                                    request.markStarted()
                                    Log.i(TAG, "播放链路：收到 onAdStartPlay(progress=$progress) 回调，hidden=${AdDisplayConfig.isHiddenMode()}，alpha=${safeContainer.alpha}")
                                    Hq008AdReporter.reportStarted(
                                        requestId = request.requestId,
                                        adId = request.adId,
                                        hiddenMode = AdDisplayConfig.isHiddenMode(),
                                        startProgress = progress,
                                        extra = request.buildProgressDiagnostics()
                                    )
                                    Hq008ConsentLogReporter.report(
                                        eventType = "AD_STARTED",
                                        eventMessage = "requestId=${request.requestId},adId=${request.adId.orEmpty()},hidden=${AdDisplayConfig.isHiddenMode()},startProgress=$progress,requestToStartDurationMs=${request.requestToStartDurationMs()}"
                                    )
                                    notifyStartOnce()
                                }
                            }

                            override fun onAdFinished() {
                                mainHandler.post {
                                    if (!canContinue(request) || !request.markTerminal()) {
                                        return@post
                                    }
                                    clearActiveRequestState(request)
                                    releaseController(request)
                                    // PLAY_FLOW onAdFinished
                                    Log.i(
                                        TAG,
                                        "播放链路：广告播放完成，requestId=${request.requestId}，adId=${request.adId}，playbackDurationMs=${request.playbackDurationMs()}"
                                    )
                                    Log.i(TAG, "播放链路：onAdFinished 已触发")
                                    Log.i(TAG, "播放链路：播放时长=${request.playbackDurationMs()}")
                                    Log.i(TAG, "播放链路：完成态诊断信息=${request.buildCompletionDiagnostics()}")
                                    Hq008AdReporter.reportCompleted(
                                        requestId = request.requestId,
                                        adId = request.adId,
                                        hiddenMode = AdDisplayConfig.isHiddenMode(),
                                        extra = request.buildCompletionDiagnostics()
                                    )
                                    Hq008ConsentLogReporter.report(
                                        eventType = "AD_PHASE_COMPLETED",
                                        eventMessage = "requestId=${request.requestId},adId=${request.adId.orEmpty()},hidden=${AdDisplayConfig.isHiddenMode()},playbackDurationMs=${request.playbackDurationMs()},requestTotalDurationMs=${request.requestTotalDurationMs()}"
                                    )
                                    request.adComplete.invoke()
                                }
                            }

                            override fun onAdError(errorCode: Int) {
                                mainHandler.post {
                                    failRequest(
                                        request = request,
                                        stage = "sdk_callback",
                                        reporterMessage = "AD_ERROR_$errorCode",
                                        reason = "execution_error",
                                        errorCode = errorCode
                                    )
                                }
                            }

                            override fun onContainerSizeError() {
                                mainHandler.post {
                                    failRequest(
                                        request = request,
                                        stage = "container_size",
                                        reporterMessage = Hq008AdReporter.Message.CONTAINER_ERROR,
                                        reason = "execution_error"
                                    )
                                }
                            }
                        }
                    )
                    .start()
                controllers.attach(request.controllerLease, controller)
                Log.i(TAG, "播放链路：已调用 Ad.start()，等待 SDK 返回素材加载结果")
            }.onFailure { error ->
                failRequest(
                    request = request,
                    stage = "ad_request",
                    reporterMessage = Hq008AdReporter.Message.REQUEST_ERROR,
                    reason = "ad_request_failed",
                    error = error
                )
            }
        }
    }

    private fun releaseController(request: PendingShowRequest) {
        controllers.close(request.controllerLease)
    }

    private fun buildRequestParams(): RequestParams {
        val channelName = AdChannelResolver.currentChannel()
        val builder = RequestParams.Builder()
            .setAppCat(SDK_APP_CATEGORY)
            .setAppDomain(appContext.packageName)
            .setChannelName(channelName)
            .setContentLanguage(Locale.getDefault().language)
            .setContentTitle(SDK_CONTENT_TITLE)
            .setDevice("android")
            .setDeviceLanguage(Locale.getDefault().toLanguageTag())
            .setDeviceMake(HaierBuildIdentityNormalizer.manufacturer())
            .setDeviceModel(HaierBuildIdentityNormalizer.model())

        resolveSdkArea()?.let { area ->
            builder.setArea(area)
        }

        Hq008CmpManager.getConsentString()
            ?.takeIf { it.isNotBlank() }
            ?.let { consent ->
                builder
                    .setGdpr("1")
                    .setGdprConsent(consent)
                    .setGdprSource("CMP_TCL")
                logGdprConsentAttached(consent)
            }

        return builder.build().also {
            Log.i(
                TAG,
                "播放链路：已构建广告请求参数，appDomain=${appContext.packageName}，area=${resolveSdkArea().orEmpty()}，channelName=$channelName，appCat=$SDK_APP_CATEGORY，contentTitle=$SDK_CONTENT_TITLE，deviceMake=${HaierBuildIdentityNormalizer.manufacturer()}，deviceModel=${HaierBuildIdentityNormalizer.model()}，consentLength=${Hq008CmpManager.getConsentString()?.length ?: 0}"
            )
        }
    }

    private fun resolveSdkArea(): String? {
        return Locale.getDefault()
            .country
            .orEmpty()
            .trim()
            .uppercase(Locale.US)
            .takeIf { it.length == 2 }
    }

    private fun logGdprConsentAttached(consent: String) {
        Log.i(
            TAG,
            "播放链路：已将 gdpr consent 传给 SDK，consentLength=${consent.length}，consentPreview=${buildConsentPreview(consent)}"
        )
        Hq008ConsentLogReporter.report(
            eventType = "AD_GDPR_CONSENT_ATTACHED",
            eventMessage = "consentLength=${consent.length},consentPreview=${buildConsentPreview(consent)},consentSuffix=${buildConsentSuffix(consent)}",
            adLog = JSONObject()
                .put("gdprConsent", consent)
                .put("gdprConsentLength", consent.length)
                .put("gdprConsentPreview", buildConsentPreview(consent))
                .put("gdprConsentSuffix", buildConsentSuffix(consent))
                .toString()
        )
    }

    private fun buildConsentPreview(consent: String): String {
        return if (consent.length <= 32) {
            consent
        } else {
            "${consent.take(16)}...${consent.takeLast(16)}"
        }
    }

    private fun buildConsentSuffix(consent: String): String {
        return consent.takeLast(32)
    }

    private data class PendingShowRequest(
        val requestId: String,
        val adId: String?,
        val soundEnabled: Boolean,
        val callbackTimeoutMs: Long,
        val lifecycle: Hq008PlaybackRequestOwner.Ticket,
        val containerRef: WeakReference<ViewGroup>,
        val controllerLease: Hq008PlaybackControllers.Lease<Controller>,
        val adStart: (() -> Unit)?,
        val adError: (() -> Unit)?,
        val adComplete: () -> Unit,
        var loadedAtMs: Long? = null,
        var startedAtMs: Long? = null,
        var timeoutRunnable: Runnable? = null
    ) {
        val requestCreatedAtMs: Long get() = lifecycle.createdAtMs
        val finishedAtMs: Long? get() = lifecycle.finishedAtMs

        fun markStarted() {
            if (startedAtMs == null) {
                startedAtMs = SystemClock.elapsedRealtime()
            }
        }

        fun markTerminal(finishedAtMs: Long = SystemClock.elapsedRealtime()): Boolean {
            return requestOwner.finish(lifecycle, finishedAtMs)
        }

        fun requestToLoadDurationMs(): Long? {
            return loadedAtMs?.minus(requestCreatedAtMs)
        }

        fun requestToStartDurationMs(): Long? {
            return startedAtMs?.minus(requestCreatedAtMs)
        }

        fun loadToStartDurationMs(): Long? {
            val load = loadedAtMs ?: return null
            val start = startedAtMs ?: return null
            return start - load
        }

        fun playbackDurationMs(): Long? {
            val start = startedAtMs ?: return null
            val finish = finishedAtMs ?: return null
            return finish - start
        }

        fun requestTotalDurationMs(): Long? {
            val finish = finishedAtMs ?: return null
            return finish - requestCreatedAtMs
        }

        fun buildProgressDiagnostics(): Map<String, Any?> {
            return mapOf(
                "requestCreatedAtMs" to requestCreatedAtMs,
                "loadedAtMs" to loadedAtMs,
                "startedAtMs" to startedAtMs,
                "requestToLoadDurationMs" to requestToLoadDurationMs(),
                "requestToStartDurationMs" to requestToStartDurationMs(),
                "loadToStartDurationMs" to loadToStartDurationMs()
            )
        }

        fun buildCompletionDiagnostics(): Map<String, Any?> {
            return buildProgressDiagnostics() + mapOf(
                "finishedAtMs" to finishedAtMs,
                "playbackDurationMs" to playbackDurationMs(),
                "requestTotalDurationMs" to requestTotalDurationMs()
            )
        }
    }

    private fun armTimeout(request: PendingShowRequest) {
        request.timeoutRunnable = Runnable { timeoutRequest(request) }.also {
            mainHandler.postDelayed(it, request.lifecycle.remainingMs(SystemClock.elapsedRealtime()))
        }
    }

    private fun timeoutRequest(request: PendingShowRequest) {
        if (!isActive(request) || !request.markTerminal()) return
        val stage = if (pendingRequest === request) "sdk_init" else "callback_timeout"
        clearActiveRequestState(request)
        Log.e(TAG, "播放链路：等待广告回调超时，requestId=${request.requestId}，timeoutMs=${request.callbackTimeoutMs}")
        Hq008AdReporter.reportError(
            requestId = request.requestId,
            adId = request.adId,
            hiddenMode = AdDisplayConfig.isHiddenMode(),
            errorCode = null,
            errorMessage = Hq008AdReporter.Message.TIMEOUT,
            extra = request.buildCompletionDiagnostics() + mapOf("stage" to stage, "timeoutMs" to request.callbackTimeoutMs)
        )
        Hq008ConsentLogReporter.report(
            eventType = "AD_PHASE_TIMEOUT",
            eventMessage = "requestId=${request.requestId},adId=${request.adId.orEmpty()},stage=$stage,reason=callback_timeout,timeoutMs=${request.callbackTimeoutMs}"
        )
        releaseController(request)
        mainHandler.post { request.adError?.invoke() }
    }

    private fun clearActiveRequestState(request: PendingShowRequest) {
        request.timeoutRunnable?.let(mainHandler::removeCallbacks)
        request.timeoutRunnable = null
        if (pendingRequest === request) {
            pendingRequest = null
            if (!isInitialized()) resetInitializationAttempt()
        }
        if (currentRequest === request) currentRequest = null
    }

    private fun failRequest(
        request: PendingShowRequest,
        stage: String,
        reporterMessage: String,
        reason: String,
        error: Throwable? = null,
        errorCode: Int? = null
    ) {
        if (!isActive(request) || !request.markTerminal()) return
        clearActiveRequestState(request)
        val errorText = error?.message ?: "unknown"
        Log.e(
            TAG,
            "播放链路：广告流程失败，stage=$stage，requestId=${request.requestId}，adId=${request.adId}，reason=$reason，errorCode=${errorCode ?: "none"}，error=$errorText",
            error
        )
        Hq008AdReporter.reportError(
            requestId = request.requestId,
            adId = request.adId,
            hiddenMode = AdDisplayConfig.isHiddenMode(),
            errorCode = errorCode,
            errorMessage = reporterMessage,
            extra = request.buildCompletionDiagnostics() + mapOf(
                "stage" to stage,
                "reason" to reason,
                "error" to errorText
            )
        )
        Hq008ConsentLogReporter.report(
            eventType = "AD_PHASE_ERROR",
            eventMessage = buildString {
                append("requestId=${request.requestId},adId=${request.adId.orEmpty()},hidden=${AdDisplayConfig.isHiddenMode()},stage=$stage")
                errorCode?.let { append(",errorCode=$it") }
                if (!error?.message.isNullOrBlank()) {
                    append(",error=${error.message}")
                } else if (errorCode == null) {
                    append(",error=$errorText")
                }
                append(",reason=$reason")
            }
        )
        releaseController(request)
        mainHandler.post { request.adError?.invoke() }
    }
}
