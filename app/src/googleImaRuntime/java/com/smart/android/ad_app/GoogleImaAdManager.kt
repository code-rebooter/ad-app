package com.smart.android.ad_app

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.ViewGroup
import com.google.gson.JsonElement
import com.smart.android.ad_app.AdLocalLog as Log
import com.smart.android.ad_app.google.GoogleGamAdConfigParser
import com.smart.android.ad_app.google.GoogleGamAdPlaybackConfig
import com.smart.android.googleima.GoogleImaAdListener
import com.smart.android.googleima.GoogleImaAdPlayerView
import com.smart.android.googleima.GoogleImaAdRequest
import com.smart.android.googleima.GoogleImaAuditEvent
import java.lang.ref.WeakReference

/** Complete IMA request/playback path. It does not invoke the independent generator or TCL. */
internal object GoogleImaAdManager : IAdManager {
    private const val TAG = "GooglePureImaAd"
    private const val SDK_NAME = "google_ima_3.35.1"
    private const val SDK_ENTRY = "media3_ima_vast_aar"
    private const val DEBUG_AUDIT_AD_TAG_URL =
        "https://pubads.g.doubleclick.net/gampad/ads?" +
            "iu=/21775744923/external/omid_ad_samples&env=vp&gdfp_req=1&output=vast&" +
            "sz=640x480&description_url=http%3A%2F%2Ftest_site.com%2Fhomepage&" +
            "vpmute=0&vpa=0&vad_format=linear&url=http%3A%2F%2Ftest_site.com&" +
            "vpos=preroll&unviewed_position_start=1&correlator="
    private const val DEBUG_AUDIT_AD_LOAD_TIMEOUT_MS = 20_000
    private const val DEBUG_AUDIT_AD_STARTUP_TIMEOUT_MS = 35_000L
    private const val OM_COMPLETION_SETTLE_MS = 500L

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var current: Session? = null

    override fun init() {
        Log.i(TAG, "纯 IMA AAR 渠道初始化完成，广告处理入口=$SDK_ENTRY")
    }

    override fun showAd(
        flRoot: ViewGroup,
        adId: String?,
        soundEnabled: Boolean,
        callbackTimeoutMs: Long?,
        adStart: (() -> Unit)?,
        adError: (() -> Unit)?,
        adComplete: () -> Unit
    ) = showAd(
        flRoot,
        adId,
        soundEnabled,
        callbackTimeoutMs,
        null,
        null,
        adStart,
        adError,
        adComplete
    )

    override fun showAd(
        flRoot: ViewGroup,
        adId: String?,
        soundEnabled: Boolean,
        callbackTimeoutMs: Long?,
        gamConfig: JsonElement?,
        channelId: String?,
        adStart: (() -> Unit)?,
        adError: (() -> Unit)?,
        adComplete: () -> Unit
    ) {
        destroyAd()
        val session = Session(
            id = Hq008ReportRequestIdResolver.resolve(adId),
            adId = adId,
            soundEnabled = soundEnabled,
            timeoutMs = callbackTimeoutMs ?: AdPlaybackPolicy.CALLBACK_TIMEOUT_MS,
            container = WeakReference(flRoot),
            onStart = adStart,
            onError = adError,
            onComplete = adComplete
        )
        current = session
        flRoot.alpha = 0f
        session.timeout = Runnable {
            if (isActive(session)) fail(session, "host_callback_timeout")
        }.also { main.postDelayed(it, session.timeoutMs) }

        Hq008AdReporter.reportRequested(
            session.id,
            adId,
            AdDisplayConfig.isHiddenMode(),
            flRoot.width,
            flRoot.height,
            session.diagnostics()
        )
        trace(session, "AD_REQUESTED", "soundEnabled=$soundEnabled,callbackTimeoutMs=${session.timeoutMs}")

        val config = resolvePlaybackConfig(gamConfig)
        flRoot.post {
            if (!isActive(session) || !checkSignal(session, "before_ima_start")) return@post
            if (config == null) {
                fail(session, "gam_config_unavailable", skipped = true)
            } else {
                startPlayback(session, config)
            }
        }
    }

    private fun resolvePlaybackConfig(gamConfig: JsonElement?): GoogleGamAdPlaybackConfig? {
        val authorizedConfig = GoogleGamAdConfigParser.parse(gamConfig)
        if (!BuildConfig.DEBUG) return authorizedConfig
        return GoogleGamAdPlaybackConfig(
            adTagUrl = DEBUG_AUDIT_AD_TAG_URL + System.currentTimeMillis(),
            adLoadTimeoutMs = authorizedConfig?.adLoadTimeoutMs ?: DEBUG_AUDIT_AD_LOAD_TIMEOUT_MS,
            adStartupTimeoutMs = authorizedConfig?.adStartupTimeoutMs
                ?: DEBUG_AUDIT_AD_STARTUP_TIMEOUT_MS
        )
    }

    private fun startPlayback(session: Session, config: GoogleGamAdPlaybackConfig) {
        val container = session.container.get() ?: run {
            fail(session, "container_released")
            return
        }
        session.adTagUrl = config.adTagUrl
        container.removeAllViews()
        container.alpha = 0f

        val player = GoogleImaAdPlayerView(container.context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        session.player = player
        container.addView(player)
        trace(
            session,
            "AD_SDK_REQUEST",
            "imaLoadTimeoutMs=${config.adLoadTimeoutMs}," +
                "imaStartupTimeoutMs=${config.adStartupTimeoutMs}," +
                "debugAuditVastOverride=${BuildConfig.DEBUG}"
        )

        val request = GoogleImaAdRequest.Builder(config.adTagUrl)
            .setSoundEnabled(session.soundEnabled)
            .setAdLoadTimeoutMs(config.adLoadTimeoutMs)
            .setAdStartupTimeoutMs(config.adStartupTimeoutMs)
            .setUserAgent(Hq008XhsxAarRuntimeBridge.getEffectiveUserAgent())
            .build()

        player.play(request, object : GoogleImaAdListener {
            override fun onImaAuditEvent(event: GoogleImaAuditEvent) {
                session.runtimeAudit.record(event)
            }

            override fun onAdLoaded() = onMain {
                if (!isActive(session) || session.loadedAt != null) return@onMain
                session.loadedAt = SystemClock.elapsedRealtime()
                Hq008AdReporter.reportLoaded(
                    session.id,
                    session.adId,
                    AdDisplayConfig.isHiddenMode(),
                    session.diagnostics()
                )
                trace(session, "AD_LOADED")
            }

            override fun onAdStarted() = onMain {
                if (!isActive(session) || !checkSignal(session, "ad_started")) return@onMain
                if (session.startedAt != null) return@onMain
                if (session.loadedAt == null) session.loadedAt = SystemClock.elapsedRealtime()
                session.startedAt = SystemClock.elapsedRealtime()
                Hq008AdReporter.reportStarted(
                    session.id,
                    session.adId,
                    AdDisplayConfig.isHiddenMode(),
                    null,
                    session.diagnostics()
                )
                trace(session, "AD_STARTED")
                if (!session.hostStartDelivered) {
                    session.hostStartDelivered = true
                    session.onStart?.invoke()
                }
            }

            override fun onAdFirstFrameRendered() = onMain {
                if (!isActive(session) || AdDisplayConfig.isHiddenMode()) return@onMain
                container.animate().cancel()
                container.animate().alpha(1f).setDuration(150L).start()
                trace(session, "AD_FIRST_FRAME")
            }

            override fun onAdCompleted(event: String) = onMain {
                if (!isActive(session) || session.completionPending) return@onMain
                session.completionPending = true
                session.finishedAt = SystemClock.elapsedRealtime()
                session.timeout?.let(main::removeCallbacks)
                session.timeout = null
                Hq008AdReporter.reportCompleted(
                    session.id,
                    session.adId,
                    AdDisplayConfig.isHiddenMode(),
                    session.diagnostics() + mapOf("imaTerminalEvent" to event)
                )
                session.completionRelease = Runnable {
                    if (!isActive(session)) return@Runnable
                    releasePlayer(session)
                    finishRuntimeAudit(session, "completed:$event")
                    trace(session, "AD_PHASE_COMPLETED", "imaTerminalEvent=$event")
                    close(session)
                    session.onComplete()
                }.also { main.postDelayed(it, OM_COMPLETION_SETTLE_MS) }
            }

            override fun onAdSkipped(event: String) = onMain {
                if (isActive(session)) fail(session, "ima_ad_skipped", event)
            }

            override fun onAdError(message: String) = onMain {
                if (isActive(session)) fail(session, "ima_playback_failed", message)
            }
        })
    }

    private fun checkSignal(session: Session, stage: String): Boolean {
        if (!Hq002SignalStatusGate.isEnabled() || Hq002SignalStatusGate.isPlaybackAllowed()) return true
        fail(
            session,
            "external_playback_denied:$stage:${Hq002SignalStatusGate.currentReason()}",
            skipped = true
        )
        return false
    }

    private fun fail(
        session: Session,
        reason: String,
        error: String? = null,
        skipped: Boolean = false
    ) {
        if (!isActive(session)) return
        session.finishedAt = SystemClock.elapsedRealtime()
        Hq008AdReporter.reportError(
            session.id,
            session.adId,
            AdDisplayConfig.isHiddenMode(),
            null,
            if (reason == "host_callback_timeout") {
                Hq008AdReporter.Message.TIMEOUT
            } else {
                Hq008AdReporter.Message.REQUEST_ERROR
            },
            session.diagnostics() + mapOf("reason" to reason, "error" to error)
        )
        releasePlayer(session)
        finishRuntimeAudit(session, "${if (skipped) "skipped" else "error"}:$reason")
        trace(
            session,
            if (skipped) "AD_PHASE_SKIPPED" else "AD_PHASE_ERROR",
            "reason=$reason,error=${error.orEmpty()}"
        )
        close(session)
        if (skipped) session.onComplete() else session.onError?.invoke()
    }

    override fun destroyAd() = destroyAd(null)

    override fun destroyAd(requestId: String?) = onMain {
        val session = current ?: return@onMain
        if (requestId != null && session.id != requestId) return@onMain
        if (session.active) {
            releasePlayer(session)
            finishRuntimeAudit(session, "cancelled:window_destroy")
            trace(session, "AD_PHASE_CANCELLED", "reason=window_destroy")
        }
        close(session)
    }

    private fun close(session: Session) {
        session.active = false
        session.timeout?.let(main::removeCallbacks)
        session.timeout = null
        session.completionRelease?.let(main::removeCallbacks)
        session.completionRelease = null
        releasePlayer(session)
        finishRuntimeAudit(session, "closed_without_terminal_event")
        session.container.get()?.removeAllViews()
        if (current === session) current = null
    }

    private fun releasePlayer(session: Session) {
        session.player?.release()
        session.player = null
    }

    private fun finishRuntimeAudit(session: Session, reason: String) {
        if (session.runtimeAuditFinished) return
        session.runtimeAuditFinished = true
        session.runtimeAudit.finish(reason)
    }

    private fun isActive(session: Session): Boolean = current === session && session.active

    private fun onMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action() else main.post(action)
    }

    private fun trace(session: Session, event: String, details: String = "") {
        Hq008ConsentLogReporter.report(
            event,
            "sdk=$SDK_NAME,sdkEntry=$SDK_ENTRY,requestId=${session.id}," +
                "adId=${session.adId.orEmpty()},hidden=${AdDisplayConfig.isHiddenMode()},$details"
        )
    }

    private class Session(
        val id: String,
        val adId: String?,
        val soundEnabled: Boolean,
        val timeoutMs: Long,
        val container: WeakReference<ViewGroup>,
        val onStart: (() -> Unit)?,
        val onError: (() -> Unit)?,
        val onComplete: () -> Unit
    ) {
        val createdAt = SystemClock.elapsedRealtime()
        val runtimeAudit = GoogleImaRuntimeAuditReporter(id)
        var player: GoogleImaAdPlayerView? = null
        var timeout: Runnable? = null
        var completionRelease: Runnable? = null
        @Volatile var active = true
        var completionPending = false
        var runtimeAuditFinished = false
        var hostStartDelivered = false
        var adTagUrl = ""
        var loadedAt: Long? = null
        var startedAt: Long? = null
        var finishedAt: Long? = null

        fun diagnostics(): Map<String, Any?> = mapOf(
            "sdk" to SDK_NAME,
            "sdkEntry" to SDK_ENTRY,
            "requestCreatedAtMs" to createdAt,
            "callbackTimeoutMs" to timeoutMs,
            "loadedAtMs" to loadedAt,
            "startedAtMs" to startedAt,
            "finishedAtMs" to finishedAt,
            "requestToLoadDurationMs" to loadedAt?.minus(createdAt),
            "requestToStartDurationMs" to startedAt?.minus(createdAt),
            "playbackDurationMs" to startedAt?.let { start -> finishedAt?.minus(start) },
            "requestTotalDurationMs" to finishedAt?.minus(createdAt)
        ) + AdPrivacySanitizer.buildDiagnostics("adTagUrl", adTagUrl)
    }
}
