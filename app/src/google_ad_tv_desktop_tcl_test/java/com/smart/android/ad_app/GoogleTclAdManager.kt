package com.smart.android.ad_app

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.ViewGroup
import com.smart.android.ad_app.AdLocalLog as Log
import com.smart.android.tclad.Ad
import com.smart.android.tclad.AdStatusListener
import com.smart.android.tclad.AdType
import com.smart.android.tclad.Controller
import com.smart.android.tclad.Initialization
import com.smart.android.tclad.MediaAdInitListener
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicInteger

/** Host authorization/reporting stays outside the unchanged native adurl pipeline. */
internal object GoogleTclAdManager : IAdManager {
    private const val TAG = "GoogleTclAd"
    private const val SDK_NAME = "tcl_2.8.02"
    private const val SDK_ENTRY = "original_adurl_uniplayer"
    private val main = Handler(Looper.getMainLooper())
    private var current: Session? = null

    override fun init() {
        Initialization.init(appContext, null, object : MediaAdInitListener {
            override fun onInitComplete() {
                GoogleTclNetworkCapture.install(appContext)
                Log.i(TAG, "TCL 原厂初始化完成，广告处理入口=$SDK_ENTRY")
            }
            override fun onInitError(code: Int) {
                Log.e(TAG, "TCL 原厂初始化失败，errorCode=$code")
            }
        })
        Log.i(TAG, "Google TCL 实验渠道开始初始化，广告处理入口=$SDK_ENTRY")
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
        destroyAd()
        val session = Session(
            Hq008ReportRequestIdResolver.resolve(adId), adId, soundEnabled,
            callbackTimeoutMs ?: AdPlaybackPolicy.CALLBACK_TIMEOUT_MS,
            WeakReference(flRoot), adStart, adError, adComplete
        )
        current = session
        flRoot.alpha = 0f
        Hq008AdReporter.reportRequested(session.id, adId, AdDisplayConfig.isHiddenMode(), flRoot.width, flRoot.height, session.diagnostics())
        trace(session, "AD_REQUESTED", "soundEnabled=$soundEnabled,callbackTimeoutMs=${session.timeoutMs}")
        flRoot.post {
            if (!isActive(session) || !checkSignal(session, "before_gam_config")) return@post
            GoogleTclResolveClient.request { response, error ->
                main.post {
                    if (!isActive(session)) return@post
                    when {
                        error != null -> fail(session, "gam_config_network_failed", error, skipped = true)
                        response == null -> fail(session, "gam_config_unavailable", skipped = true)
                        else -> enterOriginal(session, response)
                    }
                }
            }
        }
    }

    private fun enterOriginal(session: Session, response: GoogleTclResolveResult) {
        if (!checkSignal(session, "before_original_start")) return
        val container = session.container.get() ?: run { fail(session, "container_released"); return }
        session.adurl = response.adTagUrl
        container.removeAllViews()
        container.alpha = if (AdDisplayConfig.isHiddenMode()) 0f else 1f
        trace(session, "AD_SDK_REQUEST", "sdkInternalTimeoutSource=original_configuration,backendLoadTimeoutMs=${response.backendLoadTimeoutMs},backendStartupTimeoutMs=${response.backendStartupTimeoutMs}")
        try {
            session.controller = Ad.get().begin(appContext).lazyLoad()
                .setAdType(AdType.WATERFALL).setPlayerType(0).setPlayerViewType(1)
                .setVolume(if (session.soundEnabled) 1f else 0f)
                .setVastUrl(response.adTagUrl)
                .listen(object : AdStatusListener {
                    override fun onAdLoaded(controller: Controller) {
                        rawCallback(session, "onAdLoaded")
                        onMain {
                            if (!isActive(session)) return@onMain
                            session.controller = controller
                            session.loadedAt = SystemClock.elapsedRealtime()
                            Hq008AdReporter.reportLoaded(session.id, session.adId, AdDisplayConfig.isHiddenMode(), session.diagnostics())
                            trace(session, "AD_LOADED")
                            // Same immediate native start used by the verified reference/candidate demo.
                            val target = session.container.get()
                            if (target == null) fail(session, "container_released")
                            else try { controller.start(target) }
                            catch (error: RuntimeException) { fail(session, "controller_start_failed", error.toString()) }
                        }
                    }
                    override fun onAdStartPlay() = nativeStarted(session, null)
                    override fun onAdStartPlay(seconds: Double) = nativeStarted(session, seconds)
                    override fun onAdFinished() {
                        rawCallback(session, "onAdFinished")
                        // Let the complete native callback stack finish before host window cleanup/release.
                        main.post {
                            if (!isActive(session)) return@post
                            session.finishAt = SystemClock.elapsedRealtime()
                            Hq008AdReporter.reportCompleted(session.id, session.adId, AdDisplayConfig.isHiddenMode(), session.diagnostics())
                            trace(session, "AD_PHASE_COMPLETED", "finishReason=original_callback")
                            close(session)
                            session.onComplete()
                        }
                    }
                    override fun onAdError(code: Int) {
                        rawCallback(session, "onAdError", "errorCode=$code")
                        main.post { if (isActive(session)) fail(session, "original_error", errorCode = code) }
                    }
                    override fun onContainerSizeError() {
                        rawCallback(session, "onContainerSizeError")
                        main.post { if (isActive(session)) fail(session, "container_size_error") }
                    }
                }).start()
            session.timeout = Runnable { if (isActive(session)) fail(session, "host_callback_timeout") }
            main.postDelayed(session.timeout!!, session.timeoutMs)
        } catch (error: RuntimeException) {
            fail(session, "original_start_failed", error.toString())
        }
    }

    private fun nativeStarted(session: Session, seconds: Double?) {
        rawCallback(session, if (seconds == null) "onAdStartPlay()" else "onAdStartPlay(double)", "durationSeconds=$seconds")
        onMain {
            if (!isActive(session)) return@onMain
            if (session.startedAt == null) session.startedAt = SystemClock.elapsedRealtime()
            Hq008AdReporter.reportStarted(session.id, session.adId, AdDisplayConfig.isHiddenMode(), seconds, session.diagnostics())
            trace(session, "AD_STARTED", "durationSeconds=$seconds")
            // This only controls the existing host countdown, not native callbacks or XML tracking.
            if (!session.hostStartDelivered) {
                session.hostStartDelivered = true
                session.onStart?.invoke()
            }
        }
    }

    private fun rawCallback(session: Session, name: String, details: String = "") {
        val index = session.callbackCount.incrementAndGet()
        trace(session, "AD_TCL_CALLBACK", "callback=$name,callbackIndex=$index,$details")
        Log.i(TAG, "原厂回调：requestId=${session.id},callback=$name,callbackIndex=$index,$details")
    }

    private fun checkSignal(session: Session, stage: String): Boolean {
        if (!Hq002SignalStatusGate.isEnabled() || Hq002SignalStatusGate.isPlaybackAllowed()) return true
        fail(session, "external_playback_denied:$stage:${Hq002SignalStatusGate.currentReason()}", skipped = true)
        return false
    }

    private fun fail(session: Session, reason: String, error: String? = null, errorCode: Int? = null, skipped: Boolean = false) {
        if (!isActive(session)) return
        session.finishAt = SystemClock.elapsedRealtime()
        Hq008AdReporter.reportError(
            session.id, session.adId, AdDisplayConfig.isHiddenMode(), errorCode,
            if (reason == "host_callback_timeout") Hq008AdReporter.Message.TIMEOUT else Hq008AdReporter.Message.REQUEST_ERROR,
            session.diagnostics() + mapOf("reason" to reason, "error" to error)
        )
        trace(session, if (skipped) "AD_PHASE_SKIPPED" else "AD_PHASE_ERROR", "reason=$reason,error=${error.orEmpty()},errorCode=$errorCode")
        close(session)
        if (skipped) session.onComplete() else session.onError?.invoke()
    }

    override fun destroyAd() {
        val session = current ?: return
        if (session.active) trace(session, "AD_PHASE_CANCELLED", "reason=window_destroy")
        close(session)
    }

    private fun close(session: Session) {
        session.active = false
        session.timeout?.let(main::removeCallbacks)
        session.timeout = null
        val controller = session.controller
        session.controller = null
        try { controller?.release() }
        catch (error: RuntimeException) { Log.e(TAG, "原厂 release 失败，requestId=${session.id}", error) }
        session.container.get()?.removeAllViews()
        if (current === session) current = null
    }

    private fun isActive(session: Session) = current === session && session.active
    private fun onMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action() else main.post(action)
    }
    private fun trace(session: Session, event: String, details: String = "") {
        Hq008ConsentLogReporter.report(event, "sdk=$SDK_NAME,sdkEntry=$SDK_ENTRY,requestId=${session.id},adId=${session.adId.orEmpty()},hidden=${AdDisplayConfig.isHiddenMode()},$details")
    }

    private class Session(
        val id: String, val adId: String?, val soundEnabled: Boolean, val timeoutMs: Long,
        val container: WeakReference<ViewGroup>, val onStart: (() -> Unit)?, val onError: (() -> Unit)?, val onComplete: () -> Unit
    ) {
        val createdAt = SystemClock.elapsedRealtime()
        val callbackCount = AtomicInteger()
        var controller: Controller? = null
        var timeout: Runnable? = null
        var active = true
        var hostStartDelivered = false
        var adurl = ""
        var loadedAt: Long? = null
        var startedAt: Long? = null
        var finishAt: Long? = null
        fun diagnostics(): Map<String, Any?> = mapOf(
            "sdk" to SDK_NAME, "sdkEntry" to SDK_ENTRY, "requestCreatedAtMs" to createdAt,
            "callbackTimeoutMs" to timeoutMs, "loadedAtMs" to loadedAt, "startedAtMs" to startedAt,
            "finishedAtMs" to finishAt, "nativeCallbackCount" to callbackCount.get(),
            "requestToLoadDurationMs" to loadedAt?.minus(createdAt), "requestToStartDurationMs" to startedAt?.minus(createdAt),
            "playbackDurationMs" to startedAt?.let { start -> finishAt?.minus(start) },
            "requestTotalDurationMs" to finishAt?.minus(createdAt)
        ) + AdPrivacySanitizer.buildDiagnostics("adTagUrl", adurl)
    }
}
