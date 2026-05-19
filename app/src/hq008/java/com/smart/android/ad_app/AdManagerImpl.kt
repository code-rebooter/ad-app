package com.smart.android.ad_app

import android.os.Build
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

    private val initLock = Any()

    @Volatile
    private var initCompleted = false

    @Volatile
    private var initRequested = false

    private var pendingRequest: PendingShowRequest? = null
    private var currentController: Controller? = null
    private var currentContainerRef: WeakReference<ViewGroup>? = null

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
            Ad.get().setEnableLog(BuildConfig.DEBUG)
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
            Log.e(TAG, "播放链路：TCL 媒体广告 SDK 初始化失败", error)
            pendingRequest?.adError?.invoke()
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
            Log.w(TAG, "播放链路：startAd 中止，广告容器已被释放")
            request.adError?.invoke()
            return
        }

        releaseCurrentController()
        container.post {
            val safeContainer = request.containerRef.get()
            if (safeContainer == null) {
                Log.w(TAG, "播放链路：startAd post 阶段中止，广告容器已被释放")
                request.adError?.invoke()
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
                                    currentController = controller
                                    currentContainerRef = WeakReference(safeContainer)
                                    runCatching {
                                        Log.i(TAG, "播放链路：准备调用 controller.start() 开始播放")
                                        controller.start(safeContainer)
                                    }.onFailure { error ->
                                        Log.e(TAG, "播放链路：调用 controller.start() 失败", error)
                                        releaseCurrentController()
                                        request.adError?.invoke()
                                    }
                                }
                            }

                            override fun onAdStartPlay() {
                                safeContainer.post {
                                    request.markStarted()
                                    Log.i(TAG, "播放链路：收到 onAdStartPlay 回调，hidden=${AdDisplayConfig.isHiddenMode()}，alpha=${safeContainer.alpha}")
                                    Hq008AdReporter.reportStarted(
                                        requestId = request.requestId,
                                        adId = request.adId,
                                        hiddenMode = AdDisplayConfig.isHiddenMode(),
                                        startProgress = null,
                                        extra = request.buildProgressDiagnostics()
                                    )
                                    notifyStartOnce()
                                }
                            }

                            override fun onAdStartPlay(progress: Double) {
                                safeContainer.post {
                                    request.markStarted()
                                    Log.i(TAG, "播放链路：收到 onAdStartPlay(progress=$progress) 回调，hidden=${AdDisplayConfig.isHiddenMode()}，alpha=${safeContainer.alpha}")
                                    Hq008AdReporter.reportStarted(
                                        requestId = request.requestId,
                                        adId = request.adId,
                                        hiddenMode = AdDisplayConfig.isHiddenMode(),
                                        startProgress = progress,
                                        extra = request.buildProgressDiagnostics()
                                    )
                                    notifyStartOnce()
                                }
                            }

                            override fun onAdFinished() {
                                safeContainer.post {
                                    request.finishedAtMs = SystemClock.elapsedRealtime()
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
                                    request.adComplete.invoke()
                                }
                            }

                            override fun onAdError(errorCode: Int) {
                                safeContainer.post {
                                    request.finishedAtMs = SystemClock.elapsedRealtime()
                                    Log.e(TAG, "播放链路：TCL 视频广告播放失败，errorCode=$errorCode")
                                    Log.i(TAG, "播放链路：失败前播放时长=${request.playbackDurationMs()}")
                                    Log.i(TAG, "播放链路：失败诊断信息=${request.buildCompletionDiagnostics()}")
                                    Hq008AdReporter.reportError(
                                        requestId = request.requestId,
                                        adId = request.adId,
                                        hiddenMode = AdDisplayConfig.isHiddenMode(),
                                        errorCode = errorCode,
                                        errorMessage = "AD_ERROR_$errorCode",
                                        extra = request.buildCompletionDiagnostics()
                                    )
                                    releaseCurrentController()
                                    request.adError?.invoke()
                                }
                            }

                            override fun onContainerSizeError() {
                                safeContainer.post {
                                    request.finishedAtMs = SystemClock.elapsedRealtime()
                                    Log.e(TAG, "播放链路：广告容器尺寸异常，无法继续播放")
                                    Log.i(TAG, "播放链路：容器异常时播放时长=${request.playbackDurationMs()}")
                                    Hq008AdReporter.reportError(
                                        requestId = request.requestId,
                                        adId = request.adId,
                                        hiddenMode = AdDisplayConfig.isHiddenMode(),
                                        errorCode = null,
                                        errorMessage = Hq008AdReporter.Message.CONTAINER_ERROR,
                                        extra = request.buildCompletionDiagnostics()
                                    )
                                    releaseCurrentController()
                                    request.adError?.invoke()
                                }
                            }
                        }
                    )
                    .start()
                Log.i(TAG, "播放链路：已调用 Ad.start()，等待 SDK 返回素材加载结果")
            }.onFailure { error ->
                Log.e(TAG, "播放链路：请求 TCL 视频广告失败", error)
                releaseCurrentController()
                request.adError?.invoke()
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
            }

        return builder.build().also {
            Log.i(
                TAG,
                "播放链路：已构建广告请求参数，appDomain=${appContext.packageName}，area=DE，deviceMake=${Build.MANUFACTURER.orEmpty()}，deviceModel=${Build.MODEL.orEmpty()}，consentLength=${Hq008CmpManager.getConsentString()?.length ?: 0}"
            )
        }
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
        fun markStarted() {
            if (startedAtMs == null) {
                startedAtMs = SystemClock.elapsedRealtime()
            }
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
}
