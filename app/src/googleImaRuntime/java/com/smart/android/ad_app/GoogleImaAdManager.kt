package com.smart.android.ad_app

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.ViewGroup
import com.google.gson.JsonElement
import com.google.gson.Gson
import com.speed.ext.getMacAddress
import com.smart.android.ad_app.AdLocalLog as Log
import com.smart.android.ad_app.google.GoogleGamAdConfigParser
import com.smart.android.ad_app.google.GoogleGamAdPlaybackConfig
import com.smart.android.googleima.GoogleImaAdInfo
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
            channelId = channelId?.takeIf { it.isNotBlank() } ?: AdChannelResolver.currentChannel(),
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
        trace(
            session,
            "AD_REQUESTED",
            "physicalSoundEnabled=$soundEnabled,reportedSoundEnabled=true," +
                "callbackTimeoutMs=${session.timeoutMs}"
        )

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
        if (!BuildFlavor.isGoogleImaAuditSampleEnabled()) return authorizedConfig
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
        val parameters = linkedMapOf<String, Any>(
            "request_id" to session.id,
            "channel_id" to session.channelId,
            "mac" to (runCatching { getMacAddress() }.getOrNull()?.takeIf { it.isNotBlank() }
                ?: "00:00:00:00:00:00"),
            "ad_version" to BuildConfig.VERSION_CODE,
            "ad_version_name" to BuildConfig.VERSION_NAME,
            "sdk_version" to "3.35.1"
        ).apply {
            Hq008ApiRequestFields.appendTo(this, container.context.applicationContext,
                googleAuthorization = true)
        }
        val patch = GoogleImaBackendPatch(
            adTagUrl = config.adTagUrl,
            parameters = parameters,
            deadlineMs = session.createdAt + session.timeoutMs,
            onRequest = { url ->
                if (isRunning(session)) {
                    session.patchStartedAt = SystemClock.elapsedRealtime()
                    trace(session, "AD_GAM_PATCH_REQUEST",
                        "urlHash=${AdPrivacySanitizer.shortHash(url)},urlLength=${url.length}")
                }
            },
            onReady = { url ->
                if (isRunning(session)) {
                    session.patchReadyAt = SystemClock.elapsedRealtime()
                    session.finalAdTagUrl = url
                    trace(session, "AD_GAM_PATCH_READY",
                        "urlHash=${AdPrivacySanitizer.shortHash(url)},urlLength=${url.length}")
                    Hq008ConsentLogReporter.report(
                        eventType = "AD_SDK_REQUEST",
                        eventMessage = "sdk=$SDK_NAME,sdkEntry=$SDK_ENTRY,requestId=${session.id},backendPatched=true",
                        adLog = Gson().toJson(mapOf("request_id" to session.id, "final_url" to url)),
                        requestId = session.id
                    )
                }
            },
            onFailure = { error ->
                onMain { if (isRunning(session)) fail(session, "gam_patch_failed", error) }
            }
        )
        session.backendPatch = patch
        trace(
            session,
            "AD_IMA_REQUEST_PREPARED",
            "imaLoadTimeoutMs=${config.adLoadTimeoutMs}," +
                "imaStartupTimeoutMs=${config.adStartupTimeoutMs}," +
                "debugAuditVastOverride=${config.adTagUrl.startsWith(DEBUG_AUDIT_AD_TAG_URL)}"
        )

        val request = GoogleImaAdRequest.Builder(config.adTagUrl)
            .setSoundEnabled(session.soundEnabled)
            .setAdLoadTimeoutMs(config.adLoadTimeoutMs)
            .setAdStartupTimeoutMs(config.adStartupTimeoutMs)
            .setUserAgent(Hq008XhsxAarRuntimeBridge.getEffectiveUserAgent())
            .setAdUrlResolver(patch)
            .build()

        player.play(request, object : GoogleImaAdListener {
            override fun onImaAuditEvent(event: GoogleImaAuditEvent) {
                session.runtimeAudit.record(event)
                onMain {
                    if (!isActive(session)) return@onMain
                    if (session.finishing && event.eventType != "terminal_snapshot") return@onMain
                    when (event.eventType) {
                        "gam_patch_error" -> fail(session, "gam_patch_failed", event.fields["error"])
                        "vast_response" -> if (session.runtimeAudit.hasVastFill()) {
                            reportFilled(session, event.fields["capture_source"].orEmpty())
                        }
                        "terminal_snapshot" -> handlePlayerSnapshot(session, event)
                        "ima_ad_event" -> handleImaAdEvent(session, event)
                        "player_state", "playback_progress" ->
                            handlePlayerSnapshot(session, event)
                    }
                }
            }

            override fun onAdLoaded() = onMain {
                if (!isRunning(session) || session.loadedAt != null) return@onMain
                reportFilled(session, "ima_loaded")
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

            override fun onAdMediaStartEvidence(source: String, adInstanceId: String) = onMain {
                handleMediaStartEvidence(session, source, adInstanceId)
            }

            override fun onAdStarted(source: String, adInfo: GoogleImaAdInfo) = onMain {
                handleAdStarted(session, source, adInfo)
            }

            override fun onAdFirstFrameRendered(adInstanceId: String) = onMain {
                handleRenderedFirstFrame(session, adInstanceId)
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

    private fun handleMediaStartEvidence(
        session: Session,
        source: String,
        adInstanceId: String
    ) {
        if (!isRunning(session) || adInstanceId.isBlank()) return
        if (session.segmentsByInstanceId[adInstanceId]?.terminalEvent?.isNotBlank() == true) return
        val now = SystemClock.elapsedRealtime()
        session.requestTimeout?.let(main::removeCallbacks)
        session.requestTimeout = null
        session.pendingMediaEvidenceAt[adInstanceId] = now
        if (session.firstPlaybackEvidenceAt == null) session.firstPlaybackEvidenceAt = now
        Hq008FloatingFlowGuard.markActiveStage(session.id, "ima_media_evidence")
        trace(
            session,
            "AD_MEDIA_START_EVIDENCE",
            "adInstanceId=$adInstanceId,source=$source,formalStartPending=true"
        )
    }

    private fun handleAdStarted(session: Session, source: String, adInfo: GoogleImaAdInfo) {
        if (!isRunning(session) || !checkSignal(session, "ad_started")) return
        val now = SystemClock.elapsedRealtime()
        val normalizedSource = source.ifBlank { "ima" }
        val adInstanceId = adInfo.instanceId
        if (adInstanceId.isBlank()) {
            fail(session, "ad_started_without_instance_id")
            return
        }
        session.segmentsByInstanceId[adInstanceId]?.let { existing ->
            if (existing.terminalEvent.isNotBlank()) {
                session.lateImaStartedCount += 1
                trace(
                    session,
                    "AD_START_IGNORED_AFTER_TERMINAL",
                    "adInstanceId=$adInstanceId,segment=${existing.index}," +
                        "terminalEvent=${existing.terminalEvent}"
                )
                return
            }
            if (session.currentSegment === existing) {
                trace(
                    session,
                    "AD_START_DUPLICATE_IGNORED",
                    "adInstanceId=$adInstanceId,segment=${existing.index},source=$normalizedSource"
                )
                return
            }
        }
        applyAdInfo(session, adInfo)
        session.segmentCount += 1
        session.currentSegmentPlaybackEvidenceAt = null
        session.currentSegmentRenderedFirstFrameAt = null
        session.currentSegmentImaProgressCount = 0
        session.currentSegment = SegmentAudit(
            index = session.segmentCount,
            startedAtMs = now,
            startSource = normalizedSource,
            adInstanceId = adInstanceId,
            imaAdId = adInfo.adId,
            creativeId = adInfo.creativeId,
            universalAdId = adInfo.universalAdIdValue,
            adDurationMs = session.currentAdDurationMs,
            totalAds = session.totalAds,
            adPosition = session.adPosition,
            podIndex = session.podIndex
        ).also { segment ->
            session.segments.add(segment)
            session.segmentsByInstanceId[adInstanceId] = segment
        }
        session.lastStartSource = normalizedSource
        if (session.startSource.isBlank()) session.startSource = normalizedSource
        resetSegmentProgress(session, now)
        armPlaybackTimeout(session, "segment_start")
        session.pendingMediaEvidenceAt.remove(adInstanceId)?.let { evidenceAt ->
            markSegmentPlaybackEvidence(
                session,
                evidenceAt,
                adInstanceId,
                "sdk_media_evidence"
            )
        }
        session.pendingRenderedFirstFrameAt.remove(adInstanceId)?.let { firstFrameAt ->
            markSegmentRenderedFirstFrame(session, firstFrameAt, adInstanceId)
        }
        session.pendingMaxPositionMs.remove(adInstanceId)?.let { maxPositionMs ->
            session.currentSegment?.maxPositionMs = maxOf(
                session.currentSegment?.maxPositionMs ?: -1L,
                maxPositionMs
            )
        }
        if (session.currentSegmentPlaybackEvidenceAt == null) {
            armPlaybackStartTimeout(session, session.currentSegment!!)
        }
        session.pendingTerminalEvents.remove(adInstanceId)?.let { terminal ->
            applyTerminalToSegment(session, session.currentSegment!!, terminal)
        }
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
                "adInstanceId=$adInstanceId,startSource=$normalizedSource,${session.podDetails()}"
            )
            if (!session.hostStartDelivered) {
                session.hostStartDelivered = true
                session.onStart?.invoke()
            }
        } else {
            trace(
                session,
                "AD_SEGMENT_STARTED",
                "segment=${session.segmentCount},adInstanceId=$adInstanceId," +
                    "startSource=$normalizedSource," +
                    session.podDetails()
            )
        }
    }

    private fun applyAdInfo(session: Session, adInfo: GoogleImaAdInfo) {
        if (session.currentAdInstanceId != adInfo.instanceId) {
            resetCurrentAdMetadata(session, adInfo.instanceId)
        }
        session.currentAdInstanceId = adInfo.instanceId
        session.currentImaAdId = adInfo.adId
        session.currentCreativeId = adInfo.creativeId
        session.currentUniversalAdId = adInfo.universalAdIdValue
        session.currentAdDurationMs = adInfo.durationMs.takeIf { it > 0L } ?: -1L
        session.podMaxDurationMs = adInfo.podMaxDurationMs.takeIf { it > 0L } ?: -1L
        session.totalAds = adInfo.totalAds.takeIf { it > 0 } ?: -1
        session.adPosition = adInfo.adPosition.takeIf { it > 0 } ?: -1
        session.podIndex = adInfo.podIndex.takeIf { it >= 0 } ?: -1
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
        session.backendPatch?.cancel()
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

    private fun armPlaybackTimeout(session: Session, trigger: String) {
        val now = SystemClock.elapsedRealtime()
        cancelPlaybackDeadlineCallbacks(session)
        val playbackTimeoutMs = AdPlaybackPolicy.resolvePlaybackTimeoutMs(
            session.currentAdDurationMs.takeIf { it > 0L }
        )
        val hardTimeoutMs = AdPlaybackPolicy.resolvePlaybackHardTimeoutMs(
            session.currentAdDurationMs.takeIf { it > 0L }
        )
        session.playbackTimeoutMs = playbackTimeoutMs
        session.playbackHardTimeoutMs = hardTimeoutMs
        session.playbackBudget.start(
            nowMs = now,
            totalMs = playbackTimeoutMs,
            hardTimeoutMs = hardTimeoutMs,
            paused = session.bufferingSinceAt != null
        )
        session.segmentBufferingBaselineMs = session.bufferingTotalMs
        schedulePlaybackBudgetTimeout(session)
        schedulePlaybackHardTimeout(session)
        val flowGuardExtended = Hq008FloatingFlowGuard.extendActiveForPlayback(
            session.id,
            hardTimeoutMs
        )
        session.flowGuardExtensionAttempts += 1
        if (flowGuardExtended) session.flowGuardExtensionSuccesses += 1
        trace(
            session,
            "AD_PLAYBACK_DEADLINE",
            "trigger=$trigger,adDurationMs=${session.currentAdDurationMs}," +
                "playbackTimeoutMs=$playbackTimeoutMs,hardTimeoutMs=$hardTimeoutMs," +
                "bufferingGraceLimitMs=${AdPlaybackPolicy.PLAYBACK_BUFFERING_GRACE_LIMIT_MS}," +
                "flowGuardExtended=$flowGuardExtended,${session.podDetails()}"
        )
    }

    private fun updatePlaybackTimeoutMetadata(session: Session, trigger: String) {
        if (!isCurrentSegmentActive(session)) return
        val now = SystemClock.elapsedRealtime()
        val playbackTimeoutMs = AdPlaybackPolicy.resolvePlaybackTimeoutMs(
            session.currentAdDurationMs.takeIf { it > 0L }
        )
        val hardTimeoutMs = AdPlaybackPolicy.resolvePlaybackHardTimeoutMs(
            session.currentAdDurationMs.takeIf { it > 0L }
        )
        cancelPlaybackDeadlineCallbacks(session)
        session.playbackBudget.update(
            nowMs = now,
            totalMs = playbackTimeoutMs,
            hardTimeoutMs = hardTimeoutMs,
            paused = session.bufferingSinceAt != null
        )
        session.playbackTimeoutMs = playbackTimeoutMs
        session.playbackHardTimeoutMs = hardTimeoutMs
        schedulePlaybackBudgetTimeout(session)
        schedulePlaybackHardTimeout(session)
        val hardRemainingMs = session.playbackBudget.hardRemaining(now).coerceAtLeast(1L)
        val flowGuardExtended = Hq008FloatingFlowGuard.extendActiveForPlayback(
            session.id,
            hardRemainingMs
        )
        session.flowGuardExtensionAttempts += 1
        if (flowGuardExtended) session.flowGuardExtensionSuccesses += 1
        trace(
            session,
            "AD_PLAYBACK_DEADLINE_UPDATED",
            "trigger=$trigger,adDurationMs=${session.currentAdDurationMs}," +
                "playbackTimeoutMs=$playbackTimeoutMs,hardTimeoutMs=$hardTimeoutMs," +
                "remainingMs=${session.playbackBudget.remaining(now)}," +
                "hardRemainingMs=$hardRemainingMs," +
                "bufferingPaused=${session.bufferingSinceAt != null}," +
                "flowGuardExtended=$flowGuardExtended,${session.podDetails()}"
        )
    }

    private fun schedulePlaybackBudgetTimeout(session: Session) {
        session.playbackTimeout?.let(main::removeCallbacks)
        session.playbackTimeout = null
        if (!session.playbackBudget.isRunning) return
        val remaining = session.playbackBudget.remaining(SystemClock.elapsedRealtime())
            .coerceAtLeast(0L)
        session.playbackTimeout = Runnable {
            if (!isCurrentSegmentActive(session)) return@Runnable
            val now = SystemClock.elapsedRealtime()
            if (session.bufferingSinceAt != null) {
                session.playbackBudget.pause(now)
                session.playbackTimeout = null
                return@Runnable
            }
            session.playbackBudget.stop(now)
            session.playbackTimeout = null
            fail(session, "playback_deadline_exceeded")
        }.also { main.postDelayed(it, remaining) }
    }

    private fun schedulePlaybackHardTimeout(session: Session) {
        session.playbackHardTimeout?.let(main::removeCallbacks)
        val remaining = session.playbackBudget.hardRemaining(SystemClock.elapsedRealtime())
        if (remaining < 0L) return
        session.playbackHardTimeout = Runnable {
            session.playbackHardTimeout = null
            if (!isCurrentSegmentActive(session)) return@Runnable
            val bufferedThisSegment = session.bufferingTotalMs -
                session.segmentBufferingBaselineMs +
                (session.bufferingSinceAt?.let { bufferingAt ->
                    (SystemClock.elapsedRealtime() - bufferingAt).coerceAtLeast(0L)
                } ?: 0L)
            fail(
                session,
                if (bufferedThisSegment > 0L) {
                    "playback_buffer_timeout:hard_deadline"
                } else {
                    "playback_hard_deadline_exceeded"
                }
            )
        }.also { main.postDelayed(it, remaining) }
    }

    private fun pausePlaybackBudget(session: Session, now: Long) {
        if (!isCurrentSegmentActive(session) || !session.playbackBudget.isRunning) return
        session.playbackBudget.pause(now)
        session.playbackTimeout?.let(main::removeCallbacks)
        session.playbackTimeout = null
    }

    private fun resumePlaybackBudget(session: Session, now: Long) {
        if (!isCurrentSegmentActive(session) || session.playbackBudget.isRunning) return
        if (!session.playbackBudget.resume(now)) {
            fail(session, "playback_deadline_exceeded")
            return
        }
        schedulePlaybackBudgetTimeout(session)
    }

    private fun cancelPlaybackDeadlineCallbacks(session: Session) {
        session.playbackTimeout?.let(main::removeCallbacks)
        session.playbackTimeout = null
        session.playbackHardTimeout?.let(main::removeCallbacks)
        session.playbackHardTimeout = null
    }

    private fun cancelPlaybackDeadlineTimers(session: Session) {
        session.playbackBudget.stop(SystemClock.elapsedRealtime())
        cancelPlaybackDeadlineCallbacks(session)
    }

    private fun cancelPlaybackStartTimeout(session: Session) {
        session.playbackStartTimeout?.let(main::removeCallbacks)
        session.playbackStartTimeout = null
    }

    private fun armPlaybackStartTimeout(session: Session, segment: SegmentAudit) {
        cancelPlaybackStartTimeout(session)
        session.playbackStartTimeout = Runnable {
            if (isCurrentSegmentActive(session) &&
                session.currentSegment === segment &&
                session.currentSegmentPlaybackEvidenceAt == null) {
                fail(session, "playback_start_timeout:segment=${segment.index}")
            }
        }.also { main.postDelayed(it, AdPlaybackPolicy.FIRST_FRAME_TIMEOUT_MS) }
    }

    private fun cancelSessionTimers(session: Session) {
        session.requestTimeout?.let(main::removeCallbacks)
        session.requestTimeout = null
        cancelPlaybackDeadlineTimers(session)
        cancelPlaybackStartTimeout(session)
    }

    private fun handleImaAdEvent(session: Session, event: GoogleImaAuditEvent) {
        val fields = event.fields
        val imaEvent = fields["ima_event"].orEmpty()
        val eventDisposition = fields["event_disposition"].orEmpty()
        val eventInstanceId = fields["ad_instance_id"].orEmpty()
        val adInstanceId = eventInstanceId.ifBlank {
            session.currentSegment?.adInstanceId.orEmpty()
        }
        val segment = session.segmentsByInstanceId[adInstanceId]
        val isCurrentAd = adInstanceId.isNotBlank() &&
            session.currentSegment?.adInstanceId == adInstanceId
        val isActiveCurrentAd = isCurrentAd && segment?.terminalEvent.isNullOrBlank()
        val previousAdDurationMs = if (isActiveCurrentAd) session.currentAdDurationMs else -1L
        val canPromoteLoadedMetadata = imaEvent == "LOADED" &&
            (session.currentSegment == null ||
                session.currentSegment?.terminalEvent?.isNotBlank() == true)
        if (canPromoteLoadedMetadata || isActiveCurrentAd) {
            updateSessionAdMetadata(session, fields, adInstanceId)
        }
        segment?.let { updateSegmentAdMetadata(it, fields) }
        val currentDurationChanged = isActiveCurrentAd &&
            session.currentAdDurationMs != previousAdDurationMs
        if (currentDurationChanged && session.currentSegment != null) {
            updatePlaybackTimeoutMetadata(session, "ima_${imaEvent.lowercase()}_metadata")
        }
        session.lastImaEvent = imaEvent
        if (imaEvent == "AD_PROGRESS" && session.startedAt != null) {
            session.imaProgressCount += 1
            if (isActiveCurrentAd) session.currentSegmentImaProgressCount += 1
            val progressPositionMs = GoogleImaProgressFields.positionMs(fields)
            if (isActiveCurrentAd &&
                session.imaProgressEvidence.observe(adInstanceId, progressPositionMs)) {
                session.lastImaProgressAt = event.elapsedRealtimeMs
                session.lastImaProgressPositionMs = progressPositionMs
                session.validImaProgressCount += 1
            }
        }
        if (imaEvent == "STARTED") {
            when {
                segment == null -> trace(
                    session,
                    "AD_IMA_STARTED_UNMATCHED",
                    "adInstanceId=$adInstanceId,eventDisposition=$eventDisposition"
                )
                segment.terminalEvent.isNotBlank() -> {
                    session.lateImaStartedCount += 1
                    trace(
                        session,
                        "AD_IMA_STARTED_AFTER_TERMINAL",
                        "adInstanceId=$adInstanceId,segment=${segment.index}," +
                            "terminalEvent=${segment.terminalEvent}"
                    )
                }
                segment.startSource == "media_fallback" && isCurrentAd -> {
                    session.lateImaStartedCount += 1
                    trace(
                        session,
                        "AD_IMA_STARTED_LATE",
                        "segment=${segment.index},adInstanceId=$adInstanceId," +
                            "startSource=media_fallback,${session.podDetails()}"
                    )
                }
            }
        }
        if (imaEvent == "COMPLETED" || imaEvent == "SKIPPED") {
            val terminal = TerminalAudit.from(event, imaEvent, fields)
            if (segment == null && adInstanceId.isNotBlank()) {
                session.pendingTerminalEvents[adInstanceId] = terminal
            } else if (segment != null) {
                applyTerminalToSegment(session, segment, terminal)
            }
        }

        if (imaEvent !in setOf("AD_PROGRESS", "CUEPOINTS_CHANGED", "LOG")) {
            trace(
                session,
                "AD_IMA_EVENT",
                "imaEvent=$imaEvent,adInstanceId=$adInstanceId," +
                    "eventDisposition=$eventDisposition,${session.podDetails()}"
            )
        }
    }

    private fun updateSessionAdMetadata(
        session: Session,
        fields: Map<String, String>,
        adInstanceId: String
    ) {
        if (adInstanceId.isNotBlank() && session.currentAdInstanceId != adInstanceId) {
            resetCurrentAdMetadata(session, adInstanceId)
        }
        if (adInstanceId.isNotBlank()) session.currentAdInstanceId = adInstanceId
        fields["ima_ad_id"]?.takeIf { it.isNotBlank() }?.let { session.currentImaAdId = it }
        fields["creative_id"]?.takeIf { it.isNotBlank() }?.let { session.currentCreativeId = it }
        fields["universal_ad_id_value"]?.takeIf { it.isNotBlank() }?.let {
            session.currentUniversalAdId = it
        }
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
    }

    private fun resetCurrentAdMetadata(session: Session, adInstanceId: String) {
        session.currentAdInstanceId = adInstanceId
        session.currentImaAdId = ""
        session.currentCreativeId = ""
        session.currentUniversalAdId = ""
        session.currentAdDurationMs = -1L
        session.podMaxDurationMs = -1L
        session.totalAds = -1
        session.adPosition = -1
        session.podIndex = -1
    }

    private fun updateSegmentAdMetadata(segment: SegmentAudit, fields: Map<String, String>) {
        fields["ima_ad_id"]?.takeIf { it.isNotBlank() }?.let { segment.imaAdId = it }
        fields["creative_id"]?.takeIf { it.isNotBlank() }?.let { segment.creativeId = it }
        fields["universal_ad_id_value"]?.takeIf { it.isNotBlank() }?.let {
            segment.universalAdId = it
        }
        fields["ad_duration_ms"]?.toLongOrNull()?.takeIf { it > 0L }?.let {
            segment.adDurationMs = it
        }
        fields["total_ads"]?.toIntOrNull()?.takeIf { it > 0 }?.let { segment.totalAds = it }
        fields["ad_position"]?.toIntOrNull()?.takeIf { it > 0 }?.let { segment.adPosition = it }
        fields["pod_index"]?.toIntOrNull()?.takeIf { it >= 0 }?.let { segment.podIndex = it }
    }

    private fun applyTerminalToSegment(
        session: Session,
        segment: SegmentAudit,
        terminal: TerminalAudit
    ) {
        if (segment.terminalEvent.isNotBlank()) return
        segment.completedAtMs = terminal.atMs
        segment.terminalEvent = terminal.event
        if (terminal.adDurationMs > 0L) segment.adDurationMs = terminal.adDurationMs
        if (terminal.totalAds > 0) segment.totalAds = terminal.totalAds
        if (terminal.adPosition > 0) segment.adPosition = terminal.adPosition
        if (terminal.podIndex >= 0) segment.podIndex = terminal.podIndex
        segment.renderCallbackMissing = segment.renderedFirstFrameAtMs == null
        if (segment.renderCallbackMissing) {
            trace(
                session,
                "AD_RENDER_CALLBACK_MISSING",
                "segment=${segment.index},adInstanceId=${segment.adInstanceId}," +
                    "imaEvent=${terminal.event}"
            )
        }
        session.pendingMediaEvidenceAt.remove(segment.adInstanceId)
        session.pendingRenderedFirstFrameAt.remove(segment.adInstanceId)
        session.pendingMaxPositionMs.remove(segment.adInstanceId)
        if (session.currentSegment === segment) {
            finalizeStallDurations(session, terminal.atMs)
            cancelPlaybackStartTimeout(session)
            cancelPlaybackDeadlineTimers(session)
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
                "imaEvent=${terminal.event},adInstanceId=${segment.adInstanceId}," +
                    "flowGuardExtended=$flowGuardExtended"
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
        val adInstanceId = fields["ad_instance_id"].orEmpty()
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
                val targetSegment = session.segmentsByInstanceId[adInstanceId]
                when {
                    targetSegment != null && targetSegment.terminalEvent.isBlank() -> {
                        targetSegment.maxPositionMs = maxOf(targetSegment.maxPositionMs, positionMs)
                    }
                    targetSegment == null && adInstanceId.isNotBlank() -> {
                        session.pendingMaxPositionMs[adInstanceId] = maxOf(
                            session.pendingMaxPositionMs[adInstanceId] ?: -1L,
                            positionMs
                        )
                    }
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
                    "adInstanceId=$adInstanceId," +
                    "playWhenReady=$playWhenReady," +
                    "playbackSuppressionReason=$playbackSuppressionReason"
            )
        }

        val activeSegment = session.segmentsByInstanceId[adInstanceId]
        if (session.startedAt == null || !isPlayingAd ||
            activeSegment == null || activeSegment.terminalEvent.isNotBlank() ||
            session.currentSegment !== activeSegment) {
            finalizeStallDurations(session, now)
            return
        }

        var positionAdvanced = false
        if (positionMs >= 0L) {
            when {
                session.lastProgressPositionMs < 0L -> {
                    session.lastProgressPositionMs = positionMs
                    positionAdvanced = positionMs >= MIN_MEDIA_PROGRESS_EVIDENCE_MS
                }
                positionMs < session.lastProgressPositionMs -> {
                    session.lastProgressPositionMs = positionMs
                }
                positionMs > session.lastProgressPositionMs -> {
                    positionAdvanced = true
                    session.lastProgressPositionMs = positionMs
                }
            }
        }
        if (positionAdvanced) {
            session.lastProgressAt = now
            markSegmentPlaybackEvidence(session, now, adInstanceId, "media_position")
        }

        if (playbackState == PLAYER_STATE_BUFFERING) {
            finishReadyNotPlaying(session, now)
            if (session.bufferingSinceAt == null) {
                session.bufferingSinceAt = now
                session.bufferingCount += 1
                pausePlaybackBudget(session, now)
            }
            if (now - (session.bufferingSinceAt ?: now) >= AdPlaybackPolicy.PLAYBACK_STALL_TIMEOUT_MS) {
                fail(session, "playback_buffer_timeout")
            }
            return
        }
        if (finishBuffering(session, now)) resumePlaybackBudget(session, now)

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

        val lastLivenessAt = maxOf(session.lastProgressAt, session.lastImaProgressAt)
        if (isPlaying &&
            now - lastLivenessAt >= AdPlaybackPolicy.PLAYBACK_STALL_TIMEOUT_MS) {
            fail(session, "playback_stalled")
        }
    }

    private fun markSegmentPlaybackEvidence(
        session: Session,
        now: Long,
        adInstanceId: String,
        source: String
    ) {
        if (session.currentSegment?.adInstanceId != adInstanceId) return
        if (session.currentSegmentPlaybackEvidenceAt != null) return
        session.currentSegmentPlaybackEvidenceAt = now
        if (session.firstPlaybackEvidenceAt == null) session.firstPlaybackEvidenceAt = now
        session.currentSegment?.playbackEvidenceAtMs = now
        cancelPlaybackStartTimeout(session)
        trace(
            session,
            "AD_PLAYBACK_EVIDENCE",
            "segment=${session.segmentCount},adInstanceId=$adInstanceId,source=$source,startSource=" +
                "${session.lastStartSource}," +
                "hidden=${AdDisplayConfig.isHiddenMode()}"
        )
    }

    private fun markSegmentRenderedFirstFrame(
        session: Session,
        now: Long,
        adInstanceId: String
    ) {
        if (session.currentSegment?.adInstanceId != adInstanceId) return
        markSegmentPlaybackEvidence(session, now, adInstanceId, "rendered_first_frame")
        if (session.currentSegmentRenderedFirstFrameAt != null) return
        session.currentSegmentRenderedFirstFrameAt = now
        if (session.firstFrameAt == null) session.firstFrameAt = now
        session.currentSegment?.renderedFirstFrameAtMs = now
        trace(
            session,
            "AD_FIRST_FRAME",
            "segment=${session.segmentCount},adInstanceId=$adInstanceId," +
                "source=rendered_callback," +
                "hidden=${AdDisplayConfig.isHiddenMode()}"
        )
        showVisibleContainer(session)
    }

    private fun handleRenderedFirstFrame(session: Session, adInstanceId: String) {
        if (!isRunning(session) || adInstanceId.isBlank()) return
        val now = SystemClock.elapsedRealtime()
        val segment = session.segmentsByInstanceId[adInstanceId]
        if (segment?.terminalEvent?.isNotBlank() == true) return
        if (segment != null && session.currentSegment === segment) {
            markSegmentRenderedFirstFrame(session, now, adInstanceId)
            return
        }
        if (segment != null ||
            (session.currentAdInstanceId.isNotBlank() &&
                session.currentAdInstanceId != adInstanceId)) return
        session.pendingRenderedFirstFrameAt.putIfAbsent(adInstanceId, now)
        if (session.firstFrameAt == null) session.firstFrameAt = now
        trace(
            session,
            "AD_FIRST_FRAME_BEFORE_FORMAL_START",
            "adInstanceId=$adInstanceId,hidden=${AdDisplayConfig.isHiddenMode()}"
        )
        showVisibleContainer(session)
    }

    private fun showVisibleContainer(session: Session) {
        if (AdDisplayConfig.isHiddenMode()) return
        session.container.get()?.let { container ->
            container.animate().cancel()
            container.animate().alpha(1f).setDuration(150L).start()
        }
    }

    private fun resetSegmentProgress(session: Session, now: Long) {
        finalizeStallDurations(session, now)
        session.lastProgressPositionMs = -1L
        session.lastProgressAt = now
        session.lastImaProgressAt = now
        session.lastImaProgressPositionMs = -1L
    }

    private fun finalizeStallDurations(session: Session, now: Long) {
        finishBuffering(session, now)
        finishReadyNotPlaying(session, now)
    }

    private fun finishBuffering(session: Session, now: Long): Boolean {
        val startedAt = session.bufferingSinceAt ?: return false
        val duration = (now - startedAt).coerceAtLeast(0L)
        session.bufferingTotalMs += duration
        session.bufferingMaxMs = maxOf(session.bufferingMaxMs, duration)
        session.bufferingSinceAt = null
        return true
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

    private fun isCurrentSegmentActive(session: Session): Boolean =
        isRunning(session) && session.currentSegment != null &&
            session.currentSegment?.terminalEvent.isNullOrBlank()

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
                "adId=${session.adId.orEmpty()},hidden=${AdDisplayConfig.isHiddenMode()},$details",
            requestId = session.id
        )
    }

    private fun reportFilled(session: Session, source: String) {
        if (!isRunning(session) || session.filledAt != null) return
        session.filledAt = SystemClock.elapsedRealtime()
        Hq008AdReporter.reportFilled(session.id, session.adId, AdDisplayConfig.isHiddenMode(),
            session.diagnostics() + mapOf("fillSource" to source))
        trace(session, "AD_FILLED", "source=$source")
    }

    private data class SegmentAudit(
        val index: Int,
        val startedAtMs: Long,
        val startSource: String,
        val adInstanceId: String,
        var imaAdId: String,
        var creativeId: String,
        var universalAdId: String,
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
            "startSource" to startSource,
            "adInstanceId" to adInstanceId,
            "imaAdId" to imaAdId,
            "creativeId" to creativeId,
            "universalAdId" to universalAdId,
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

    private data class TerminalAudit(
        val event: String,
        val atMs: Long,
        val adDurationMs: Long,
        val totalAds: Int,
        val adPosition: Int,
        val podIndex: Int
    ) {
        companion object {
            fun from(
                event: GoogleImaAuditEvent,
                terminalEvent: String,
                fields: Map<String, String>
            ) = TerminalAudit(
                event = terminalEvent,
                atMs = event.elapsedRealtimeMs,
                adDurationMs = fields["ad_duration_ms"]?.toLongOrNull() ?: -1L,
                totalAds = fields["total_ads"]?.toIntOrNull() ?: -1,
                adPosition = fields["ad_position"]?.toIntOrNull() ?: -1,
                podIndex = fields["pod_index"]?.toIntOrNull() ?: -1
            )
        }
    }

    private class Session(
        val id: String,
        val adId: String?,
        val channelId: String,
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
        var backendPatch: GoogleImaBackendPatch? = null
        @Volatile var patchStartedAt: Long? = null
        @Volatile var patchReadyAt: Long? = null
        @Volatile var finalAdTagUrl = ""
        var filledAt: Long? = null
        var requestTimeout: Runnable? = null
        var playbackTimeout: Runnable? = null
        var playbackHardTimeout: Runnable? = null
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
        val segmentsByInstanceId = mutableMapOf<String, SegmentAudit>()
        val pendingMediaEvidenceAt = mutableMapOf<String, Long>()
        val pendingRenderedFirstFrameAt = mutableMapOf<String, Long>()
        val pendingMaxPositionMs = mutableMapOf<String, Long>()
        val pendingTerminalEvents = mutableMapOf<String, TerminalAudit>()
        var currentSegment: SegmentAudit? = null
        var currentAdInstanceId = ""
        var currentImaAdId = ""
        var currentCreativeId = ""
        var currentUniversalAdId = ""
        var currentAdDurationMs = -1L
        var podMaxDurationMs = -1L
        var totalAds = -1
        var adPosition = -1
        var podIndex = -1
        var playbackTimeoutMs = -1L
        var playbackHardTimeoutMs = -1L
        val playbackBudget = GoogleImaPlaybackBudgetState()
        var segmentBufferingBaselineMs = 0L
        var lastImaEvent = ""
        var startSource = ""
        var lastStartSource = ""
        var lateImaStartedCount = 0
        var imaProgressCount = 0
        var validImaProgressCount = 0
        var currentSegmentImaProgressCount = 0
        var lastImaProgressAt = createdAt
        var lastImaProgressPositionMs = -1L
        val imaProgressEvidence = GoogleImaProgressEvidence()
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
                "physicalSoundEnabled" to soundEnabled,
                "reportedSoundEnabled" to true,
                "requestCreatedAtMs" to createdAt,
                "callbackTimeoutMs" to timeoutMs,
                "patchStartedAtMs" to patchStartedAt,
                "patchReadyAtMs" to patchReadyAt,
                "filledAtMs" to filledAt,
                "finalAdTagUrlHash" to finalAdTagUrl.takeIf { it.isNotBlank() }
                    ?.let(AdPrivacySanitizer::shortHash),
                "loadedAtMs" to loadedAt,
                "startedAtMs" to startedAt,
                "firstFrameAtMs" to firstFrameAt,
                "firstPlaybackEvidenceAtMs" to firstPlaybackEvidenceAt,
                "currentSegmentPlaybackEvidenceAtMs" to currentSegmentPlaybackEvidenceAt,
                "currentSegmentRenderedFirstFrameAtMs" to currentSegmentRenderedFirstFrameAt,
                "finishedAtMs" to finishedAt,
                "segmentCount" to segmentCount,
                "segments" to segments.map { it.diagnostics() },
                "currentAdInstanceId" to currentAdInstanceId,
                "currentImaAdId" to currentImaAdId,
                "currentCreativeId" to currentCreativeId,
                "currentUniversalAdId" to currentUniversalAdId,
                "adDurationMs" to currentAdDurationMs,
                "podMaxDurationMs" to podMaxDurationMs,
                "totalAds" to totalAds,
                "adPosition" to adPosition,
                "podIndex" to podIndex,
                "playbackTimeoutMs" to playbackTimeoutMs,
                "playbackHardTimeoutMs" to playbackHardTimeoutMs,
                "playbackBudgetRemainingMs" to playbackBudget.remaining(now),
                "playbackBudgetRunning" to playbackBudget.isRunning,
                "playbackHardDeadlineAtMs" to playbackBudget.hardDeadlineAtMs,
                "startSource" to startSource,
                "lastStartSource" to lastStartSource,
                "lateImaStartedCount" to lateImaStartedCount,
                "imaProgressCount" to imaProgressCount,
                "validImaProgressCount" to validImaProgressCount,
                "currentSegmentImaProgressCount" to currentSegmentImaProgressCount,
                "lastImaProgressPositionMs" to lastImaProgressPositionMs,
                "lastImaProgressAgeMs" to (now - lastImaProgressAt).coerceAtLeast(0L),
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
    private const val MIN_MEDIA_PROGRESS_EVIDENCE_MS = 100L
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
