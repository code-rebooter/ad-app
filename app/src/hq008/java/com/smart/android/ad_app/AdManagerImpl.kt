package com.smart.android.ad_app

import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
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
        Hq008TclVideoAd.init()
    }

    override fun showAd(
        flRoot: ViewGroup,
        adId: String?,
        adStart: (() -> Unit)?,
        adError: (() -> Unit)?,
        adComplete: () -> Unit
    ) {
        Hq008TclVideoAd.showAd(
            flRoot = flRoot,
            adId = adId,
            adStart = adStart,
            adError = adError,
            adComplete = adComplete
        )
    }

    override fun destroyAd() {
        Hq008TclVideoAd.destroyAd()
    }
}

private object Hq008TclVideoAd {
    private const val TAG = "Hq008TclVideoAd"
    private const val AD_CALLBACK_TIMEOUT_MS = 60_000L

    private val initLock = Any()
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var initCompleted = false

    @Volatile
    private var initRequested = false

    private var pendingRequest: PendingShowRequest? = null
    private var currentController: Controller? = null
    private var currentContainerRef: WeakReference<ViewGroup>? = null
    private var currentRequest: PendingShowRequest? = null
    private var currentTimeoutRunnable: Runnable? = null

    fun init() {
        ensureInitialized()
    }

    fun showAd(
        flRoot: ViewGroup,
        adId: String?,
        adStart: (() -> Unit)?,
        adError: (() -> Unit)?,
        adComplete: () -> Unit
    ) {
        val requestId = Hq008ReportRequestIdResolver.resolve(adId)
        // PLAY_FLOW showAd entry
        Log.i(
            TAG,
            "播放链路：开始请求广告，requestId=$requestId，adId=$adId，hidden=${AdDisplayConfig.isHiddenMode()}，container=${flRoot.width}x${flRoot.height}"
        )
        Log.i(TAG, "播放链路：已进入 showAd，当前隐藏模式=${AdDisplayConfig.isHiddenMode()}，容器尺寸=${flRoot.width}x${flRoot.height}")
        Hq008AdReporter.reportRequested(
            requestId = requestId,
            adId = adId,
            hiddenMode = AdDisplayConfig.isHiddenMode(),
            containerWidth = flRoot.width,
            containerHeight = flRoot.height,
            extra = mapOf(
                "requestCreatedAtMs" to SystemClock.elapsedRealtime()
            )
        )
        Hq008ConsentLogReporter.report(
            eventType = "AD_REQUESTED",
            eventMessage = "requestId=$requestId,adId=${adId.orEmpty()},hidden=${AdDisplayConfig.isHiddenMode()},containerWidth=${flRoot.width},containerHeight=${flRoot.height}"
        )
        val request = PendingShowRequest(
            requestId = requestId,
            adId = adId,
            requestCreatedAtMs = SystemClock.elapsedRealtime(),
            containerRef = WeakReference(flRoot),
            adStart = adStart,
            adError = adError,
            adComplete = adComplete
        )

        if (!isInitialized()) {
            Log.i(TAG, "播放链路：媒体广告 SDK 尚未初始化完成，先缓存本次广告请求，等待初始化结束后继续")
            pendingRequest = request
            ensureInitialized()
            return
        }

        startAd(request)
    }

    fun destroyAd() {
        currentRequest
            ?.takeUnless { it.isTerminal() }
            ?.let { request ->
                request.markTerminal()
                Hq008ConsentLogReporter.report(
                    eventType = "AD_PHASE_CANCELLED",
                    eventMessage = "requestId=${request.requestId},adId=${request.adId.orEmpty()},hidden=${AdDisplayConfig.isHiddenMode()},stage=window_destroy,reason=window_hidden"
                )
            }
        clearActiveRequestState()
        pendingRequest = null
        releaseCurrentController()
    }

    private fun ensureInitialized() {
        if (isInitialized()) {
            startPendingRequestIfNeeded()
            return
        }

        synchronized(initLock) {
            if (isInitialized()) {
                startPendingRequestIfNeeded()
                return
            }
            if (initRequested) {
                return
            }
            initRequested = true
        }

        val switchConfig = AdReportSwitchConfig().apply {
            privacyAgreed = true
            uxpEnabled = true
            errorStatisticsEnabled = true
        }

        runCatching {
            Hq008AdSdkDebugCapture.ensureInstalled()
            Ad.get().setEnableLog(Hq008AdSdkDebugCapture.isSdkVerboseLogEnabled())
            Initialization.init(
                appContext,
                switchConfig,
                object : MediaAdInitListener {
                    override fun onInitComplete() {
                        synchronized(initLock) {
                            initCompleted = true
                            initRequested = false
                        }
                        Log.i(TAG, "播放链路：TCL 媒体广告 SDK 初始化完成，准备继续处理待播放广告")
                        startPendingRequestIfNeeded()
                    }
                }
            )
            Log.i(TAG, "播放链路：已发起 TCL 媒体广告 SDK 初始化请求")
        }.onFailure { error ->
            synchronized(initLock) {
                initCompleted = false
                initRequested = false
            }
            val failedRequest = pendingRequest
            if (failedRequest != null) {
                failRequest(
                    request = failedRequest,
                    stage = "sdk_init",
                    reporterMessage = Hq008AdReporter.Message.INIT_ERROR,
                    reason = "sdk_init_failed",
                    error = error
                )
            } else {
                Log.e(TAG, "播放链路：TCL 媒体广告 SDK 初始化失败", error)
            }
            pendingRequest = null
        }
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

        releaseCurrentController()
        container.post {
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
                    "播放链路：开始执行 startAd，requestId=${request.requestId}，adId=${request.adId}，hidden=$hiddenMode，alpha=${safeContainer.alpha}"
                )
                Log.i(
                    TAG,
                    "播放链路：广告容器已准备就绪，hidden=$hiddenMode，alpha=${safeContainer.alpha}，container=${safeContainer.width}x${safeContainer.height}"
                )
                Ad.get()
                    .begin(appContext)
                    .lazyLoad()
                    .setAdType(AdType.WATERFALL)
                    .setVolume(0f)
                    .setRequestParams(buildRequestParams())
                    .listen(
                        object : AdStatusListener {
                            override fun onAdLoaded(controller: Controller) {
                                safeContainer.post {
                                    request.loadedAtMs = SystemClock.elapsedRealtime()
                                    if (request.isTerminal()) {
                                        return@post
                                    }
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
                                    currentController = controller
                                    currentContainerRef = WeakReference(safeContainer)
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
                                safeContainer.post {
                                    if (request.isTerminal()) {
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
                                safeContainer.post {
                                    if (request.isTerminal()) {
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
                                safeContainer.post {
                                    if (!request.markTerminal()) {
                                        return@post
                                    }
                                    clearActiveRequestState(request)
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
                                safeContainer.post {
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
                                safeContainer.post {
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
                armTimeout(request)
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

    private fun releaseCurrentController() {
        val controller = currentController
        val container = currentContainerRef?.get()

        if (controller != null && container != null) {
            runCatching {
                controller.stop(container)
            }.onFailure { error ->
                Log.w(TAG, "Stop TCL video ad failed.", error)
            }
        }

        runCatching {
            controller?.release()
        }.onFailure { error ->
            Log.w(TAG, "Release TCL video ad failed.", error)
        }

        currentController = null
        currentContainerRef?.clear()
        currentContainerRef = null
    }

    private fun buildRequestParams(): RequestParams {
        val builder = RequestParams.Builder()
            .setAppCat("demo")
            .setAppDomain(appContext.packageName)
            .setArea("DE")
            .setChannelName("sdk-demo")
            .setContentLanguage(Locale.getDefault().language)
            .setContentTitle("TCL SDK demo")
            .setDevice("android")
            .setDeviceLanguage(Locale.getDefault().toLanguageTag())
            .setDeviceMake(Build.MANUFACTURER.orEmpty())
            .setDeviceModel(Build.MODEL.orEmpty())

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
                "播放链路：已构建广告请求参数，appDomain=${appContext.packageName}，area=DE，deviceMake=${Build.MANUFACTURER.orEmpty()}，deviceModel=${Build.MODEL.orEmpty()}，consentLength=${Hq008CmpManager.getConsentString()?.length ?: 0}"
            )
        }
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
        val requestCreatedAtMs: Long,
        val containerRef: WeakReference<ViewGroup>,
        val adStart: (() -> Unit)?,
        val adError: (() -> Unit)?,
        val adComplete: () -> Unit,
        var loadedAtMs: Long? = null,
        var startedAtMs: Long? = null,
        var finishedAtMs: Long? = null
    ) {
        fun isTerminal(): Boolean {
            return finishedAtMs != null
        }

        fun markStarted() {
            if (startedAtMs == null) {
                startedAtMs = SystemClock.elapsedRealtime()
            }
        }

        fun markTerminal(finishedAtMs: Long = SystemClock.elapsedRealtime()): Boolean {
            if (this.finishedAtMs != null) {
                return false
            }
            this.finishedAtMs = finishedAtMs
            return true
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
        clearActiveRequestState()
        currentRequest = request
        currentTimeoutRunnable = Runnable {
            if (!request.markTerminal()) {
                return@Runnable
            }
            currentTimeoutRunnable = null
            currentRequest = null
            Log.e(TAG, "播放链路：等待广告回调超时，requestId=${request.requestId}，adId=${request.adId}，timeoutMs=$AD_CALLBACK_TIMEOUT_MS")
            Hq008AdReporter.reportError(
                requestId = request.requestId,
                adId = request.adId,
                hiddenMode = AdDisplayConfig.isHiddenMode(),
                errorCode = null,
                errorMessage = Hq008AdReporter.Message.TIMEOUT,
                extra = request.buildCompletionDiagnostics() + mapOf(
                    "stage" to "callback_timeout",
                    "timeoutMs" to AD_CALLBACK_TIMEOUT_MS
                )
            )
            Hq008ConsentLogReporter.report(
                eventType = "AD_PHASE_TIMEOUT",
                eventMessage = "requestId=${request.requestId},adId=${request.adId.orEmpty()},hidden=${AdDisplayConfig.isHiddenMode()},stage=callback_timeout,reason=callback_timeout,timeoutMs=$AD_CALLBACK_TIMEOUT_MS"
            )
            releaseCurrentController()
            request.adError?.invoke()
        }
        mainHandler.postDelayed(currentTimeoutRunnable!!, AD_CALLBACK_TIMEOUT_MS)
    }

    private fun clearActiveRequestState(request: PendingShowRequest? = null) {
        currentTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        currentTimeoutRunnable = null
        if (request == null || currentRequest?.requestId == request.requestId) {
            currentRequest = null
        }
    }

    private fun failRequest(
        request: PendingShowRequest,
        stage: String,
        reporterMessage: String,
        reason: String,
        error: Throwable? = null,
        errorCode: Int? = null
    ) {
        if (!request.markTerminal()) {
            return
        }
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
        releaseCurrentController()
        request.adError?.invoke()
    }
}
