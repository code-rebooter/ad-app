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
import com.smart.android.googleima.GoogleImaPlaybackError
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
        destroyAd(null, "replaced_by_new_request")
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
        Hq008FloatingFlowGuard.markActiveStage(session.id, "ima_request_created")
        flRoot.alpha = 0f
        armRequestTimeout(session)

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
                onMain {
                    if (!isActive(session)) return@onMain
                    if (session.finishing && event.eventType != "terminal_snapshot") return@onMain
                    when (event.eventType) {
                        "terminal_snapshot" -> handlePlayerSnapshot(session, event)
                        "ima_ad_event" -> handleImaAdEvent(session, event)
                        "player_state", "playback_progress" ->
                            handlePlayerSnapshot(session, event)
                    }
                }
            }

            override fun onAdLoaded() = onMain {
                if (!isRunning(session) || session.loadedAt != null) return@onMain
                session.loadedAt = SystemClock.elapsedRealtime()
                Hq008FloatingFlowGuard.markActiveStage(session.id, "ima_loaded")
                Hq008AdReporter.reportLoaded(
                    session.id,
                    session.adId,
                    AdDisplayConfig.isHiddenMode(),
                    session.diagnostics()
                )
                trace(session, "AD_LOADED")
            }

            override fun onAdStarted() = onMain {
                if (!isRunning(session) || !checkSignal(session, "ad_started")) return@onMain
                val now = SystemClock.elapsedRealtime()
                session.segmentCount += 1
                session.currentSegmentPlaybackEvidenceAt = null
                session.currentSegmentRenderedFirstFrameAt = null
                session.currentSegment = SegmentAudit(
                    index = session.segmentCount,
                    startedAtMs = now,
                    adDurationMs = session.currentAdDurationMs,
                    totalAds = session.totalAds,
                    adPosition = session.adPosition,
                    podIndex = session.podIndex
                ).also(session.segments::add)
                resetSegmentProgress(session, now)
                armPlaybackTimeout(session)
                armPlaybackStartTimeout(session, session.segmentCount)
                Hq008FloatingFlowGuard.markActiveStage(
                    session.id,
                    "ima_playing_segment_${session.segmentCount}"
                )
                if (session.startedAt == null) {
                    session.requestTimeout?.let(main::removeCallbacks)
                    session.requestTimeout = null
                    if (session.loadedAt == null) session.loadedAt = now
                    session.startedAt = now
                    Hq008AdReporter.reportStarted(
                        session.id,
                        session.adId,
                        AdDisplayConfig.isHiddenMode(),
                        null,
                        session.diagnostics()
                    )
                    trace(
                        session,
                        "AD_STARTED",
                        session.podDetails()
                    )
                    if (!session.hostStartDelivered) {
                        session.hostStartDelivered = true
                        session.onStart?.invoke()
                    }
                } else {
                    trace(
                        session,
                        "AD_SEGMENT_STARTED",
                        "segment=${session.segmentCount},${session.podDetails()}"
                    )
                }
            }

            override fun onAdFirstFrameRendered() = onMain {
                if (!isRunning(session)) return@onMain
                markSegmentRenderedFirstFrame(session, SystemClock.elapsedRealtime())
            }

            override fun onAdCompleted(event: String) = onMain {
                if (!beginFinishing(session) ||
                    !session.completionSettlement.begin(event)
                ) return@onMain
                session.finishedAt = SystemClock.elapsedRealtime()
                finalizeStallDurations(session, session.finishedAt!!)
                cancelSessionTimers(session)
                Hq008AdReporter.reportCompleted(
                    session.id,
                    session.adId,
                    AdDisplayConfig.isHiddenMode(),
                    session.diagnostics() + mapOf("imaTerminalEvent" to event)
                )
                session.completionRelease = Runnable {
                    finishCompletedSession(session)
                }.also { main.postDelayed(it, OM_COMPLETION_SETTLE_MS) }
            }

            override fun onAdSkipped(event: String) = onMain {
                if (isRunning(session)) fail(session, "ima_ad_skipped", event, skipped = true)
            }

            override fun onAdError(error: GoogleImaPlaybackError) = onMain {
                if (!isRunning(session)) return@onMain
                fail(
                    session = session,
                    reason = error.reason.ifBlank { "ima_playback_failed" },
                    error = error.message,
                    timedOutOverride = error.isTimeout,
                    errorDetails = error.details + mapOf("error_category" to error.category)
                )
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
        skipped: Boolean = false,
        timedOutOverride: Boolean? = null,
        errorDetails: Map<String, String> = emptyMap()
    ) {
        if (!beginFinishing(session)) return
        val timedOut = timedOutOverride ?: AdPlaybackPolicy.isTimeoutReason(reason)
        session.finishedAt = SystemClock.elapsedRealtime()
        session.lastErrorDetails = errorDetails
        finalizeStallDurations(session, session.finishedAt!!)
        cancelSessionTimers(session)
        val numericErrorCode = errorDetails["ima_error_code_number"]?.toIntOrNull()
            ?: errorDetails["playback_error_code"]?.toIntOrNull()
        Hq008AdReporter.reportError(
            session.id,
            session.adId,
            AdDisplayConfig.isHiddenMode(),
            numericErrorCode,
            if (timedOut) {
                Hq008AdReporter.Message.TIMEOUT
            } else {
                Hq008AdReporter.Message.REQUEST_ERROR
            },
            session.diagnostics() + mapOf("reason" to reason, "error" to error) + errorDetails
        )
        releasePlayer(session, "${if (skipped) "skipped" else "error"}:$reason")
        finishRuntimeAudit(session, "${if (skipped) "skipped" else "error"}:$reason")
        trace(
            session,
            when {
                skipped -> "AD_PHASE_SKIPPED"
                timedOut -> "AD_PHASE_TIMEOUT"
                else -> "AD_PHASE_ERROR"
            },
            "reason=$reason,errorCategory=${errorDetails["error_category"].orEmpty()}," +
                "errorCode=${numericErrorCode ?: "none"}," +
                "errorCodeName=${errorDetails["playback_error_code_name"]
                    ?: errorDetails["ima_error_code"].orEmpty()},error=${error.orEmpty()}"
        )
        close(session)
        if (skipped) session.onComplete() else session.onError?.invoke()
    }

    override fun destroyAd() = destroyAd(null)

    override fun destroyAd(requestId: String?) = destroyAd(requestId, "window_destroy")

    override fun destroyAd(requestId: String?, reason: String) = onMain {
        val session = current ?: return@onMain
        if (requestId != null && session.id != requestId) return@onMain
        if (session.completionPending) {
            finishCompletedSession(session)
            return@onMain
        }
        if (!beginFinishing(session)) return@onMain
        session.finishedAt = SystemClock.elapsedRealtime()
        releasePlayer(session, "cancelled:$reason")
        finishRuntimeAudit(session, "cancelled:$reason")
        trace(session, "AD_PHASE_CANCELLED", "reason=$reason")
        close(session)
    }

    private fun finishCompletedSession(session: Session) {
        val event = session.completionSettlement.settle() ?: return
        session.completionRelease?.let(main::removeCallbacks)
        session.completionRelease = null
        releasePlayer(session, "completed:$event")
        finishRuntimeAudit(session, "completed:$event")
        trace(session, "AD_PHASE_COMPLETED", "imaTerminalEvent=$event")
        close(session)
        session.onComplete()
    }

    private fun close(session: Session) {
        session.active = false
        session.terminalGate.finish()
        cancelSessionTimers(session)
        session.completionRelease?.let(main::removeCallbacks)
        session.completionRelease = null
        releasePlayer(session, "closed_without_terminal_event")
        finishRuntimeAudit(session, "closed_without_terminal_event")
        session.container.get()?.removeAllViews()
        if (current === session) current = null
    }

    private fun releasePlayer(session: Session, reason: String) {
        session.player?.capturePlayerSnapshot("before_release:$reason")
        session.player?.release()
        session.player = null
    }

    private fun armRequestTimeout(session: Session) {
        session.requestTimeout?.let(main::removeCallbacks)
        session.requestTimeout = Runnable {
            if (isRunning(session)) fail(session, "request_start_timeout")
        }.also { main.postDelayed(it, session.timeoutMs) }
    }

    private fun armPlaybackTimeout(session: Session) {
        session.playbackTimeout?.let(main::removeCallbacks)
        val playbackTimeoutMs = AdPlaybackPolicy.resolvePlaybackTimeoutMs(
            session.currentAdDurationMs.takeIf { it > 0L }
        )
        session.playbackTimeoutMs = playbackTimeoutMs
        session.playbackTimeout = Runnable {
            if (isRunning(session)) fail(session, "playback_deadline_exceeded")
        }.also { main.postDelayed(it, playbackTimeoutMs) }
        val flowGuardExtended = Hq008FloatingFlowGuard.extendActiveForPlayback(
            session.id,
            playbackTimeoutMs
        )
        session.flowGuardExtensionAttempts += 1
        if (flowGuardExtended) session.flowGuardExtensionSuccesses += 1
        trace(
            session,
            "AD_PLAYBACK_DEADLINE",
            "adDurationMs=${session.currentAdDurationMs},playbackTimeoutMs=$playbackTimeoutMs," +
                "flowGuardExtended=$flowGuardExtended,${session.podDetails()}"
        )
    }

    private fun armPlaybackStartTimeout(session: Session, segment: Int) {
        session.playbackStartTimeout?.let(main::removeCallbacks)
        session.playbackStartTimeout = Runnable {
            if (isRunning(session) &&
                session.segmentCount == segment &&
                session.currentSegmentPlaybackEvidenceAt == null) {
                fail(session, "playback_start_timeout:segment=$segment")
            }
        }.also { main.postDelayed(it, AdPlaybackPolicy.FIRST_FRAME_TIMEOUT_MS) }
    }

    private fun cancelSessionTimers(session: Session) {
        session.requestTimeout?.let(main::removeCallbacks)
        session.requestTimeout = null
        session.playbackTimeout?.let(main::removeCallbacks)
        session.playbackTimeout = null
        session.playbackStartTimeout?.let(main::removeCallbacks)
        session.playbackStartTimeout = null
    }

    private fun handleImaAdEvent(session: Session, event: GoogleImaAuditEvent) {
        val fields = event.fields
        val imaEvent = fields["ima_event"].orEmpty()
        fields["ad_duration_ms"]?.toLongOrNull()?.takeIf { it > 0L }?.let {
            session.currentAdDurationMs = it
        }
        fields["pod_max_duration_ms"]?.toLongOrNull()?.takeIf { it > 0L }?.let {
            session.podMaxDurationMs = it
        }
        fields["total_ads"]?.toIntOrNull()?.takeIf { it > 0 }?.let {
            session.totalAds = it
        }
        fields["ad_position"]?.toIntOrNull()?.takeIf { it > 0 }?.let {
            session.adPosition = it
        }
        fields["pod_index"]?.toIntOrNull()?.takeIf { it >= 0 }?.let {
            session.podIndex = it
        }
        session.lastImaEvent = imaEvent
        if (imaEvent == "COMPLETED" || imaEvent == "SKIPPED") {
            session.currentSegment?.apply {
                completedAtMs = event.elapsedRealtimeMs
                terminalEvent = imaEvent
                adDurationMs = session.currentAdDurationMs
                totalAds = session.totalAds
                adPosition = session.adPosition
                podIndex = session.podIndex
                renderCallbackMissing = renderedFirstFrameAtMs == null
            }
            if (session.currentSegment?.renderCallbackMissing == true) {
                trace(
                    session,
                    "AD_RENDER_CALLBACK_MISSING",
                    "segment=${session.segmentCount},imaEvent=$imaEvent,${session.podDetails()}"
                )
            }
        }

        if (imaEvent !in setOf("AD_PROGRESS", "CUEPOINTS_CHANGED", "LOG")) {
            trace(
                session,
                "AD_IMA_EVENT",
                "imaEvent=$imaEvent,${session.podDetails()}"
            )
        }

        if (imaEvent == "COMPLETED" || imaEvent == "SKIPPED") {
            session.playbackTimeout?.let(main::removeCallbacks)
            session.playbackTimeout = null
            val flowGuardExtended = Hq008FloatingFlowGuard.extendActiveForPlayback(
                session.id,
                AdPlaybackPolicy.AD_POD_TRANSITION_TIMEOUT_MS +
                    AdPlaybackPolicy.AD_BREAK_COMPLETION_GRACE_MS
            )
            session.flowGuardExtensionAttempts += 1
            if (flowGuardExtended) session.flowGuardExtensionSuccesses += 1
            trace(
                session,
                "AD_FLOW_GUARD_EXTENDED",
                "imaEvent=$imaEvent,flowGuardExtended=$flowGuardExtended"
            )
        }
    }

    private fun handlePlayerSnapshot(session: Session, event: GoogleImaAuditEvent) {
        val fields = event.fields
        val now = event.elapsedRealtimeMs
        val positionMs = fields["position_ms"]?.toLongOrNull() ?: -1L
        val durationMs = fields["duration_ms"]?.toLongOrNull() ?: -1L
        val bufferedPositionMs = fields["buffered_position_ms"]?.toLongOrNull() ?: -1L
        val playbackState = fields["playback_state"]?.toIntOrNull() ?: -1
        val isPlaying = fields["is_playing"]?.toBooleanStrictOrNull() ?: false
        val isPlayingAd = fields["is_playing_ad"]?.toBooleanStrictOrNull() ?: false
        val playWhenReady = fields["play_when_ready"]?.toBooleanStrictOrNull() ?: false
        val playbackSuppressionReason = fields["playback_suppression_reason"]?.toIntOrNull() ?: -1

        session.playerPositionMs = positionMs
        session.playerDurationMs = durationMs
        session.playerBufferedPositionMs = bufferedPositionMs
        session.playerState = playbackState
        session.playerIsPlaying = isPlaying
        session.playerIsPlayingAd = isPlayingAd
        session.playerPlayWhenReady = playWhenReady
        session.playerPlaybackSuppressionReason = playbackSuppressionReason
        if (isPlayingAd) {
            session.lastAdPlayerPositionMs = positionMs
            session.lastAdPlayerDurationMs = durationMs
            session.lastAdPlayerBufferedPositionMs = bufferedPositionMs
            session.lastAdPlayerState = playbackState
            session.lastAdPlayerIsPlaying = isPlaying
            if (positionMs >= 0L) {
                session.maxAdPlayerPositionMs = maxOf(session.maxAdPlayerPositionMs, positionMs)
                session.currentSegment?.let { segment ->
                    segment.maxPositionMs = maxOf(segment.maxPositionMs, positionMs)
                }
            }
        } else {
            session.lastContentPlayerPositionMs = positionMs
            session.lastContentPlayerDurationMs = durationMs
            session.lastContentPlayerState = playbackState
            session.lastContentPlayerIsPlaying = isPlaying
        }

        // A release snapshot is diagnostic-only. Terminal collection must never re-enter fail().
        if (session.finishing) return

        if (event.eventType == "player_state" || session.startedAt != null) {
            trace(
                session,
                if (event.eventType == "player_state") "AD_PLAYER_STATE" else "AD_PLAYBACK_PROGRESS",
                "trigger=${fields["trigger"].orEmpty()},positionMs=$positionMs," +
                    "durationMs=$durationMs,bufferedPositionMs=$bufferedPositionMs," +
                    "playerState=$playbackState,isPlaying=$isPlaying,isPlayingAd=$isPlayingAd," +
                    "playWhenReady=$playWhenReady," +
                    "playbackSuppressionReason=$playbackSuppressionReason"
            )
        }

        if (session.startedAt == null || !isPlayingAd) {
            finalizeStallDurations(session, now)
            return
        }

        if (session.currentSegmentPlaybackEvidenceAt == null && (isPlaying || positionMs > 0L)) {
            markSegmentPlaybackEvidence(session, now)
        }

        if (positionMs >= 0L &&
            (session.lastProgressPositionMs < 0L || positionMs > session.lastProgressPositionMs + 1_000L)) {
            session.lastProgressPositionMs = positionMs
            session.lastProgressAt = now
        }

        if (playbackState == PLAYER_STATE_BUFFERING) {
            finishReadyNotPlaying(session, now)
            if (session.bufferingSinceAt == null) {
                session.bufferingSinceAt = now
                session.bufferingCount += 1
            }
            if (now - (session.bufferingSinceAt ?: now) >= AdPlaybackPolicy.PLAYBACK_STALL_TIMEOUT_MS) {
                fail(session, "playback_buffer_timeout")
            }
            return
        }
        finishBuffering(session, now)

        if (playbackState == PLAYER_STATE_READY && !isPlaying) {
            if (session.readyNotPlayingSinceAt == null) {
                session.readyNotPlayingSinceAt = now
                session.readyNotPlayingCount += 1
            }
            if (now - (session.readyNotPlayingSinceAt ?: now) >=
                AdPlaybackPolicy.PLAYBACK_STALL_TIMEOUT_MS) {
                fail(session, "playback_ready_not_playing_timeout")
            }
            return
        }
        finishReadyNotPlaying(session, now)

        if (isPlaying && now - session.lastProgressAt >= AdPlaybackPolicy.PLAYBACK_STALL_TIMEOUT_MS) {
            fail(session, "playback_stalled")
        }
    }

    private fun markSegmentPlaybackEvidence(session: Session, now: Long) {
        if (session.currentSegmentPlaybackEvidenceAt != null) return
        session.currentSegmentPlaybackEvidenceAt = now
        if (session.firstPlaybackEvidenceAt == null) session.firstPlaybackEvidenceAt = now
        session.currentSegment?.playbackEvidenceAtMs = now
        session.playbackStartTimeout?.let(main::removeCallbacks)
        session.playbackStartTimeout = null
        trace(
            session,
            "AD_PLAYBACK_EVIDENCE",
            "segment=${session.segmentCount},source=active_playback," +
                "hidden=${AdDisplayConfig.isHiddenMode()}"
        )
    }

    private fun markSegmentRenderedFirstFrame(session: Session, now: Long) {
        markSegmentPlaybackEvidence(session, now)
        if (session.currentSegmentRenderedFirstFrameAt != null) return
        session.currentSegmentRenderedFirstFrameAt = now
        if (session.firstFrameAt == null) session.firstFrameAt = now
        session.currentSegment?.renderedFirstFrameAtMs = now
        trace(
            session,
            "AD_FIRST_FRAME",
            "segment=${session.segmentCount},source=rendered_callback," +
                "hidden=${AdDisplayConfig.isHiddenMode()}"
        )
        if (!AdDisplayConfig.isHiddenMode()) {
            session.container.get()?.let { container ->
                container.animate().cancel()
                container.animate().alpha(1f).setDuration(150L).start()
            }
        }
    }

    private fun resetSegmentProgress(session: Session, now: Long) {
        finalizeStallDurations(session, now)
        session.lastProgressPositionMs = -1L
        session.lastProgressAt = now
    }

    private fun finalizeStallDurations(session: Session, now: Long) {
        finishBuffering(session, now)
        finishReadyNotPlaying(session, now)
    }

    private fun finishBuffering(session: Session, now: Long) {
        val startedAt = session.bufferingSinceAt ?: return
        val duration = (now - startedAt).coerceAtLeast(0L)
        session.bufferingTotalMs += duration
        session.bufferingMaxMs = maxOf(session.bufferingMaxMs, duration)
        session.bufferingSinceAt = null
    }

    private fun finishReadyNotPlaying(session: Session, now: Long) {
        val startedAt = session.readyNotPlayingSinceAt ?: return
        val duration = (now - startedAt).coerceAtLeast(0L)
        session.readyNotPlayingTotalMs += duration
        session.readyNotPlayingMaxMs = maxOf(session.readyNotPlayingMaxMs, duration)
        session.readyNotPlayingSinceAt = null
    }

    private fun finishRuntimeAudit(session: Session, reason: String) {
        if (session.runtimeAuditFinished) return
        session.runtimeAuditFinished = true
        session.runtimeAudit.finish(reason)
    }

    private fun isActive(session: Session): Boolean = current === session && session.active

    private fun isRunning(session: Session): Boolean = isActive(session) && !session.finishing

    private fun beginFinishing(session: Session): Boolean {
        if (!isActive(session) || !session.terminalGate.begin()) return false
        cancelSessionTimers(session)
        return true
    }

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

    private data class SegmentAudit(
        val index: Int,
        val startedAtMs: Long,
        var playbackEvidenceAtMs: Long? = null,
        var renderedFirstFrameAtMs: Long? = null,
        var completedAtMs: Long? = null,
        var terminalEvent: String = "",
        var adDurationMs: Long = -1L,
        var totalAds: Int = -1,
        var adPosition: Int = -1,
        var podIndex: Int = -1,
        var maxPositionMs: Long = -1L,
        var renderCallbackMissing: Boolean = false
    ) {
        fun playbackRatio(): Double? = when {
            terminalEvent == "COMPLETED" -> 1.0
            adDurationMs > 0L && maxPositionMs >= 0L ->
                (maxPositionMs.toDouble() / adDurationMs.toDouble()).coerceIn(0.0, 1.0)
            else -> null
        }

        fun diagnostics(): Map<String, Any?> = mapOf(
            "segment" to index,
            "startedAtMs" to startedAtMs,
            "playbackEvidenceAtMs" to playbackEvidenceAtMs,
            "renderedFirstFrameAtMs" to renderedFirstFrameAtMs,
            "renderCallbackMissing" to renderCallbackMissing,
            "completedAtMs" to completedAtMs,
            "terminalEvent" to terminalEvent,
            "adDurationMs" to adDurationMs,
            "totalAds" to totalAds,
            "adPosition" to adPosition,
            "podIndex" to podIndex,
            "maxPositionMs" to maxPositionMs,
            "playbackRatio" to playbackRatio(),
            "playbackDurationMs" to completedAtMs?.minus(startedAtMs)
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
        var requestTimeout: Runnable? = null
        var playbackTimeout: Runnable? = null
        var playbackStartTimeout: Runnable? = null
        var completionRelease: Runnable? = null
        @Volatile var active = true
        val terminalGate = GoogleImaTerminalGate()
        val completionSettlement = GoogleImaCompletionSettlement()
        val finishing: Boolean get() = terminalGate.isFinishing
        val completionPending: Boolean get() = completionSettlement.isPending
        var runtimeAuditFinished = false
        var hostStartDelivered = false
        var adTagUrl = ""
        var loadedAt: Long? = null
        var startedAt: Long? = null
        var firstFrameAt: Long? = null
        var firstPlaybackEvidenceAt: Long? = null
        var currentSegmentPlaybackEvidenceAt: Long? = null
        var currentSegmentRenderedFirstFrameAt: Long? = null
        var finishedAt: Long? = null
        var segmentCount = 0
        val segments = mutableListOf<SegmentAudit>()
        var currentSegment: SegmentAudit? = null
        var currentAdDurationMs = -1L
        var podMaxDurationMs = -1L
        var totalAds = -1
        var adPosition = -1
        var podIndex = -1
        var playbackTimeoutMs = -1L
        var lastImaEvent = ""
        var playerPositionMs = -1L
        var playerDurationMs = -1L
        var playerBufferedPositionMs = -1L
        var playerState = -1
        var playerIsPlaying = false
        var playerIsPlayingAd = false
        var playerPlayWhenReady = false
        var playerPlaybackSuppressionReason = -1
        var lastAdPlayerPositionMs = -1L
        var lastAdPlayerDurationMs = -1L
        var lastAdPlayerBufferedPositionMs = -1L
        var lastAdPlayerState = -1
        var lastAdPlayerIsPlaying = false
        var maxAdPlayerPositionMs = -1L
        var lastContentPlayerPositionMs = -1L
        var lastContentPlayerDurationMs = -1L
        var lastContentPlayerState = -1
        var lastContentPlayerIsPlaying = false
        var lastProgressPositionMs = -1L
        var lastProgressAt = createdAt
        var bufferingSinceAt: Long? = null
        var bufferingCount = 0
        var bufferingTotalMs = 0L
        var bufferingMaxMs = 0L
        var readyNotPlayingSinceAt: Long? = null
        var readyNotPlayingCount = 0
        var readyNotPlayingTotalMs = 0L
        var readyNotPlayingMaxMs = 0L
        var flowGuardExtensionAttempts = 0
        var flowGuardExtensionSuccesses = 0
        var lastErrorDetails: Map<String, String> = emptyMap()

        fun podDetails(): String =
            "adDurationMs=$currentAdDurationMs,podMaxDurationMs=$podMaxDurationMs," +
                "totalAds=$totalAds,adPosition=$adPosition,podIndex=$podIndex"

        fun diagnostics(): Map<String, Any?> {
            val now = SystemClock.elapsedRealtime()
            val adProgressRatio = currentSegment?.playbackRatio()
            val knownSegments = segments.filter { it.adDurationMs > 0L }
            val podProgressRatio = knownSegments.takeIf { it.isNotEmpty() }?.let { segmentList ->
                val totalDurationMs = segmentList.sumOf { it.adDurationMs }
                val observedDurationMs = segmentList.sumOf { segment ->
                    if (segment.terminalEvent == "COMPLETED") {
                        segment.adDurationMs
                    } else {
                        segment.maxPositionMs.coerceIn(0L, segment.adDurationMs)
                    }
                }
                observedDurationMs.toDouble() / totalDurationMs.toDouble()
            }
            return mapOf(
                "sdk" to SDK_NAME,
                "sdkEntry" to SDK_ENTRY,
                "requestCreatedAtMs" to createdAt,
                "callbackTimeoutMs" to timeoutMs,
                "loadedAtMs" to loadedAt,
                "startedAtMs" to startedAt,
                "firstFrameAtMs" to firstFrameAt,
                "firstPlaybackEvidenceAtMs" to firstPlaybackEvidenceAt,
                "currentSegmentPlaybackEvidenceAtMs" to currentSegmentPlaybackEvidenceAt,
                "currentSegmentRenderedFirstFrameAtMs" to currentSegmentRenderedFirstFrameAt,
                "finishedAtMs" to finishedAt,
                "segmentCount" to segmentCount,
                "segments" to segments.map { it.diagnostics() },
                "adDurationMs" to currentAdDurationMs,
                "podMaxDurationMs" to podMaxDurationMs,
                "totalAds" to totalAds,
                "adPosition" to adPosition,
                "podIndex" to podIndex,
                "playbackTimeoutMs" to playbackTimeoutMs,
                "lastImaEvent" to lastImaEvent,
                "playerPositionMs" to playerPositionMs,
                "playerDurationMs" to playerDurationMs,
                "playerBufferedPositionMs" to playerBufferedPositionMs,
                "playerState" to playerState,
                "playerIsPlaying" to playerIsPlaying,
                "playerIsPlayingAd" to playerIsPlayingAd,
                "playerPlayWhenReady" to playerPlayWhenReady,
                "playerPlaybackSuppressionReason" to playerPlaybackSuppressionReason,
                "lastAdPlayerPositionMs" to lastAdPlayerPositionMs,
                "lastAdPlayerDurationMs" to lastAdPlayerDurationMs,
                "lastAdPlayerBufferedPositionMs" to lastAdPlayerBufferedPositionMs,
                "lastAdPlayerState" to lastAdPlayerState,
                "lastAdPlayerIsPlaying" to lastAdPlayerIsPlaying,
                "maxAdPlayerPositionMs" to maxAdPlayerPositionMs,
                "adProgressRatio" to adProgressRatio,
                "podProgressRatio" to podProgressRatio,
                "lastContentPlayerPositionMs" to lastContentPlayerPositionMs,
                "lastContentPlayerDurationMs" to lastContentPlayerDurationMs,
                "lastContentPlayerState" to lastContentPlayerState,
                "lastContentPlayerIsPlaying" to lastContentPlayerIsPlaying,
                "lastProgressPositionMs" to lastProgressPositionMs,
                "lastProgressAgeMs" to (now - lastProgressAt).coerceAtLeast(0L),
                "bufferingCount" to bufferingCount,
                "bufferingTotalMs" to bufferingTotalMs,
                "bufferingMaxMs" to bufferingMaxMs,
                "currentBufferingDurationMs" to bufferingSinceAt?.let { now - it },
                "readyNotPlayingCount" to readyNotPlayingCount,
                "readyNotPlayingTotalMs" to readyNotPlayingTotalMs,
                "readyNotPlayingMaxMs" to readyNotPlayingMaxMs,
                "currentReadyNotPlayingDurationMs" to readyNotPlayingSinceAt?.let { now - it },
                "flowGuardExtensionAttempts" to flowGuardExtensionAttempts,
                "flowGuardExtensionSuccesses" to flowGuardExtensionSuccesses,
                "requestToLoadDurationMs" to loadedAt?.minus(createdAt),
                "requestToStartDurationMs" to startedAt?.minus(createdAt),
                "playbackDurationMs" to startedAt?.let { start -> finishedAt?.minus(start) },
                "requestTotalDurationMs" to finishedAt?.minus(createdAt),
                "lastErrorDetails" to lastErrorDetails
            ) + AdPrivacySanitizer.buildDiagnostics("adTagUrl", adTagUrl)
        }
    }

    private const val PLAYER_STATE_BUFFERING = 2
    private const val PLAYER_STATE_READY = 3
}

internal class GoogleImaTerminalGate {
    var isFinishing: Boolean = false
        private set

    fun begin(): Boolean {
        if (isFinishing) return false
        isFinishing = true
        return true
    }

    fun finish() {
        isFinishing = true
    }
}

internal class GoogleImaCompletionSettlement {
    private var terminalEvent: String? = null
    private var settled = false

    val isPending: Boolean
        get() = terminalEvent != null && !settled

    fun begin(event: String): Boolean {
        if (terminalEvent != null || settled) return false
        terminalEvent = event
        return true
    }

    fun settle(): String? {
        if (settled) return null
        val event = terminalEvent ?: return null
        terminalEvent = null
        settled = true
        return event
    }
}
