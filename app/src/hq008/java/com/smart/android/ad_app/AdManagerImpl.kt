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
        Log.i(TAG, "showAd requested hidden=${AdDisplayConfig.isHiddenMode()} container=${flRoot.width}x${flRoot.height}")
        val requestId = Hq008ReportRequestIdResolver.resolve(adId)
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
            Log.i(TAG, "SDK not initialized yet. Queue pending ad request.")
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
                        Log.i(TAG, "TCL media SDK init complete.")
                        startPendingRequestIfNeeded()
                    }
                }
            )
            Log.i(TAG, "TCL media SDK init requested.")
        }.onFailure { error ->
            synchronized(initLock) {
                initCompleted = false
                initRequested = false
            }
            Log.e(TAG, "TCL media SDK init failed.", error)
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
            Log.w(TAG, "startAd aborted: container released.")
            request.adError?.invoke()
            return
        }

        releaseCurrentController()
        container.post {
            val safeContainer = request.containerRef.get()
            if (safeContainer == null) {
                Log.w(TAG, "startAd aborted in post: container released.")
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
                Log.i(
                    TAG,
                    "begin startAd hidden=$hiddenMode alpha=${safeContainer.alpha} container=${safeContainer.width}x${safeContainer.height}"
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
                                    Log.i(TAG, "onAdLoaded hidden=${AdDisplayConfig.isHiddenMode()}")
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
                                        Log.i(TAG, "controller.start invoked.")
                                        controller.start(safeContainer)
                                    }.onFailure { error ->
                                        Log.e(TAG, "Start TCL video ad failed.", error)
                                        releaseCurrentController()
                                        request.adError?.invoke()
                                    }
                                }
                            }

                            override fun onAdStartPlay() {
                                safeContainer.post {
                                    request.markStarted()
                                    Log.i(TAG, "onAdStartPlay hidden=${AdDisplayConfig.isHiddenMode()} alpha=${safeContainer.alpha}")
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
                                    Log.i(TAG, "onAdStartPlay(progress=$progress) hidden=${AdDisplayConfig.isHiddenMode()} alpha=${safeContainer.alpha}")
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
                                    Log.i(TAG, "onAdFinished")
                                    Log.i(TAG, "playbackDurationMs=${request.playbackDurationMs()}")
                                    Log.i(TAG, "completionDiagnostics=${request.buildCompletionDiagnostics()}")
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
                                    Log.e(TAG, "TCL video ad error: $errorCode")
                                    Log.i(TAG, "playbackDurationMs=${request.playbackDurationMs()}")
                                    Log.i(TAG, "errorDiagnostics=${request.buildCompletionDiagnostics()}")
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
                                    Log.e(TAG, "TCL video ad container size error.")
                                    Log.i(TAG, "playbackDurationMs=${request.playbackDurationMs()}")
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
                Log.i(TAG, "Ad.start() requested.")
            }.onFailure { error ->
                Log.e(TAG, "Request TCL video ad failed.", error)
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
        return RequestParams.Builder()
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
            .build()
            .also {
                Log.i(
                    TAG,
                    "buildRequestParams appDomain=${appContext.packageName} area=DE deviceMake=${Build.MANUFACTURER.orEmpty()} deviceModel=${Build.MODEL.orEmpty()}"
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
