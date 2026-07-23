package com.smart.android.ad_app

import android.os.Build
import android.os.Handler
import android.os.Looper
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
        if (BuildFlavor.isHaierLsap()) {
            HaierLsapAdManagerBridge.init()
            return
        }
        Hq008TclVideoAd.init()
    }

    override fun showAd(
        flRoot: ViewGroup,
        adId: String?,
        soundEnabled: Boolean,
        adStart: (() -> Unit)?,
        adError: (() -> Unit)?,
        adComplete: () -> Unit
    ) {
        if (BuildFlavor.isHaierLsap()) {
            HaierLsapAdManagerBridge.showAd(
                flRoot,
                adId,
                soundEnabled,
                adStart,
                adError,
                adComplete
            )
            return
        }
        Hq008TclVideoAd.showAd(flRoot, adId, adStart, adError, adComplete)
    }

    override fun destroyAd() {
        if (BuildFlavor.isHaierLsap()) {
            HaierLsapAdManagerBridge.destroyAd()
            return
        }
        Hq008TclVideoAd.destroyAd()
    }
}

private object HaierLsapAdManagerBridge : IAdManager {
    private const val CLASS_NAME = "com.smart.android.ad_app.HaierLsapAdManager"

    private val delegate: IAdManager by lazy {
        Class.forName(CLASS_NAME).getField("INSTANCE").get(null) as IAdManager
    }

    override fun init() = delegate.init()

    override fun showAd(
        flRoot: ViewGroup,
        adId: String?,
        soundEnabled: Boolean,
        adStart: (() -> Unit)?,
        adError: (() -> Unit)?,
        adComplete: () -> Unit
    ) = delegate.showAd(flRoot, adId, soundEnabled, adStart, adError, adComplete)

    override fun destroyAd() = delegate.destroyAd()
}

/** Normal TCL media-ad SDK integration used by the HQ008 family. */
private object Hq008TclVideoAd {
    private const val TAG = "Hq008TclVideoAd"
    private const val SDK_APP_CATEGORY = "app"
    private const val SDK_CONTENT_TITLE = "App Content"
    private const val CALLBACK_TIMEOUT_MS = 60_000L

    private val initLock = Any()
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var initCompleted = false

    @Volatile
    private var initRequested = false

    private var pendingRequest: PendingShowRequest? = null
    private var currentRequest: PendingShowRequest? = null
    private var currentController: Controller? = null
    private var currentContainerRef: WeakReference<ViewGroup>? = null
    private var timeoutRunnable: Runnable? = null

    fun init() {
        ensureInitialized()
    }

    fun showAd(
        container: ViewGroup,
        adId: String?,
        adStart: (() -> Unit)?,
        adError: (() -> Unit)?,
        adComplete: () -> Unit
    ) {
        val request = PendingShowRequest(
            adId = adId,
            containerRef = WeakReference(container),
            adStart = adStart,
            adError = adError,
            adComplete = adComplete
        )
        Log.i(TAG, "Requesting a visible TCL media ad, adId=${adId.orEmpty()}")
        if (!isInitialized()) {
            pendingRequest = request
            ensureInitialized()
            return
        }
        startAd(request)
    }

    fun destroyAd() {
        pendingRequest = null
        currentRequest?.markTerminal()
        clearTimeout()
        currentRequest = null
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
            Ad.get().setEnableLog(false)
            Initialization.init(
                appContext,
                switchConfig,
                object : MediaAdInitListener {
                    override fun onInitComplete() {
                        synchronized(initLock) {
                            initCompleted = true
                            initRequested = false
                        }
                        Log.i(TAG, "TCL media-ad SDK initialized")
                        startPendingRequestIfNeeded()
                    }
                }
            )
        }.onFailure { error ->
            synchronized(initLock) {
                initRequested = false
                initCompleted = false
            }
            Log.e(TAG, "TCL media-ad SDK initialization failed", error)
            pendingRequest?.let(::failRequest)
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
            failRequest(request)
            return
        }
        destroyAd()
        currentRequest = request
        container.post {
            val target = request.containerRef.get()
            if (target == null || request.isTerminal()) {
                failRequest(request)
                return@post
            }
            target.alpha = 1f
            var startDelivered = false
            fun deliverStartOnce() {
                if (!startDelivered) {
                    startDelivered = true
                    request.adStart?.invoke()
                }
            }

            runCatching {
                Ad.get()
                    .begin(appContext)
                    .lazyLoad()
                    .setAdType(AdType.WATERFALL)
                    .setVolume(1f)
                    .setRequestParams(buildRequestParams())
                    .listen(object : AdStatusListener {
                        override fun onAdLoaded(controller: Controller) {
                            target.post {
                                if (request.isTerminal()) {
                                    runCatching { controller.release() }
                                    return@post
                                }
                                currentController = controller
                                currentContainerRef = WeakReference(target)
                                runCatching { controller.start(target) }
                                    .onFailure { error ->
                                        Log.e(TAG, "TCL media-ad render failed", error)
                                        failRequest(request)
                                    }
                            }
                        }

                        override fun onAdStartPlay() {
                            target.post { if (!request.isTerminal()) deliverStartOnce() }
                        }

                        override fun onAdStartPlay(progress: Double) {
                            target.post { if (!request.isTerminal()) deliverStartOnce() }
                        }

                        override fun onAdFinished() {
                            target.post {
                                if (!request.markTerminal()) {
                                    return@post
                                }
                                clearTimeout()
                                currentRequest = null
                                Log.i(TAG, "TCL media ad completed")
                                request.adComplete()
                            }
                        }

                        override fun onAdError(errorCode: Int) {
                            target.post {
                                Log.e(TAG, "TCL media-ad SDK error, code=$errorCode")
                                failRequest(request)
                            }
                        }

                        override fun onContainerSizeError() {
                            target.post {
                                Log.e(TAG, "TCL media-ad container size rejected")
                                failRequest(request)
                            }
                        }
                    })
                    .start()
                armTimeout(request)
            }.onFailure { error ->
                Log.e(TAG, "TCL media-ad request failed", error)
                failRequest(request)
            }
        }
    }

    private fun buildRequestParams(): RequestParams {
        val builder = RequestParams.Builder()
            .setAppCat(SDK_APP_CATEGORY)
            .setAppDomain(appContext.packageName)
            .setChannelName(AdChannelResolver.currentChannel())
            .setContentLanguage(Locale.getDefault().language)
            .setContentTitle(SDK_CONTENT_TITLE)
            .setDevice("android")
            .setDeviceLanguage(Locale.getDefault().toLanguageTag())
            .setDeviceMake(Build.MANUFACTURER.orEmpty())
            .setDeviceModel(Build.MODEL.orEmpty())

        Locale.getDefault().country.orEmpty()
            .trim()
            .uppercase(Locale.US)
            .takeIf { it.length == 2 }
            ?.let(builder::setArea)

        Hq008CmpManager.getConsentString()
            ?.takeIf { it.isNotBlank() }
            ?.let { consent ->
                builder
                    .setGdpr("1")
                    .setGdprConsent(consent)
                    .setGdprSource("CMP_TCL")
            }
        return builder.build()
    }

    private fun armTimeout(request: PendingShowRequest) {
        clearTimeout()
        val timeout = Runnable {
            Log.e(TAG, "Timed out waiting for TCL media-ad callbacks")
            failRequest(request)
        }
        timeoutRunnable = timeout
        mainHandler.postDelayed(timeout, CALLBACK_TIMEOUT_MS)
    }

    private fun clearTimeout() {
        timeoutRunnable?.let(mainHandler::removeCallbacks)
        timeoutRunnable = null
    }

    private fun failRequest(request: PendingShowRequest) {
        if (!request.markTerminal()) {
            return
        }
        clearTimeout()
        if (currentRequest === request) {
            currentRequest = null
        }
        releaseCurrentController()
        request.adError?.invoke()
    }

    private fun releaseCurrentController() {
        val controller = currentController
        val container = currentContainerRef?.get()
        if (controller != null && container != null) {
            runCatching { controller.stop(container) }
        }
        runCatching { controller?.release() }
        currentController = null
        currentContainerRef?.clear()
        currentContainerRef = null
    }

    private data class PendingShowRequest(
        val adId: String?,
        val containerRef: WeakReference<ViewGroup>,
        val adStart: (() -> Unit)?,
        val adError: (() -> Unit)?,
        val adComplete: () -> Unit,
        private var terminal: Boolean = false
    ) {
        fun isTerminal(): Boolean = terminal

        fun markTerminal(): Boolean {
            if (terminal) {
                return false
            }
            terminal = true
            return true
        }
    }
}
