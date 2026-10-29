package com.smart.android.ad_app

import com.google.gson.Gson
import com.google.common.io.BaseEncoding
import com.smart.android.googleima.GoogleImaAuditEvent
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource
import java.io.ByteArrayOutputStream
import java.io.StringReader
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.zip.GZIPOutputStream
import javax.xml.parsers.DocumentBuilderFactory

internal class GoogleImaRuntimeAuditReporter(
    private val requestId: String
) {
    private val lock = Any()
    private val gson = Gson()
    private val vastResponses = mutableListOf<VastResponse>()
    private val nativeXhrResponses = mutableListOf<NativeXhrResponse>()
    private val verificationScripts = mutableListOf<VerificationScript>()
    private val webResources = linkedMapOf<String, WebResourceStatus>()
    private val omidCalls = linkedMapOf<String, Int>()
    private val omidSessions = linkedMapOf<String, OmidSessionStatus>()
    private val imaAdEventCounts = linkedMapOf<String, Int>()
    private val imaAdEvents = mutableListOf<ImaAdEventRecord>()
    private val adPlaybackStates = linkedMapOf<String, AdPlaybackStatus>()
    private val playbackErrors = mutableListOf<PlaybackErrorRecord>()
    private val terminalSnapshots = mutableListOf<PlayerSnapshotRecord>()
    private var nativeXhrCount = 0
    private var webVastResponseCount = 0
    private var hierarchyCount = 0
    private var hierarchyFirstAtMs: Long? = null
    private var hierarchyLastAtMs: Long? = null
    private var hierarchyMaxGapMs = 0L
    private var hierarchyLastHash = ""
    private var hierarchyLastLength = 0
    private var hierarchyNotVisibleReasonCount = 0
    private var hierarchyWindowFocusFalseCount = 0
    private var lastImaAdEvent = ""
    private var lastAdDurationMs = -1L
    private var lastPodMaxDurationMs = -1L
    private var lastTotalAds = -1
    private var lastAdPosition = -1
    private var lastPodIndex = -1
    private var playerSnapshotCount = 0
    private var playbackProgressCount = 0
    private var firstFrameObserved = false
    private var firstFrameCount = 0
    private var lastPlayerState = -1
    private var lastPlayerPositionMs = -1L
    private var lastPlayerDurationMs = -1L
    private var lastBufferedPositionMs = -1L
    private var lastPlayerIsPlaying = false
    private var lastPlayerIsPlayingAd = false
    private var lastAdPlayerState = -1
    private var lastAdPlayerPositionMs = -1L
    private var lastAdPlayerDurationMs = -1L
    private var lastAdBufferedPositionMs = -1L
    private var lastAdPlayerIsPlaying = false
    private var maxAdPositionMs = -1L
    private var currentAdMaxPositionMs = -1L
    private var adInstanceSequence = 0
    private var activeAdKey = ""
    private var activeAdBaseKey = ""
    private var pendingAdKey = ""
    private var pendingAdBaseKey = ""
    private var lastContentPlayerState = -1
    private var lastContentPlayerPositionMs = -1L
    private var lastContentPlayerDurationMs = -1L
    private var lastContentBufferedPositionMs = -1L
    private var lastContentPlayerIsPlaying = false
    private var lastPlayWhenReady = false
    private var lastPlaybackSuppressionReason = -1
    private var finished = false

    fun record(event: GoogleImaAuditEvent) {
        synchronized(lock) {
            if (finished) return
            when (event.eventType) {
                "vast_response" -> recordVast(event)
                "native_xhr_response" -> recordNativeXhr(event)
                "web_resource" -> recordWebResource(event)
                "omid_bridge" -> recordOmid(event)
                "ima_ad_event" -> recordImaAdEvent(event)
                "player_state", "playback_progress", "first_frame", "terminal_snapshot" ->
                    recordPlayerSnapshot(event)
                "playback_error" -> recordPlaybackError(event)
            }
        }
    }

    fun finish(terminalReason: String) {
        val snapshot = synchronized(lock) {
            if (finished) return
            finished = true
            Snapshot(
                summary = buildSummary(terminalReason),
                vastResponseCount = vastResponses.size,
                verificationScriptCount = verificationScripts.size,
                omidStarted = (omidCalls["startSession"] ?: 0) > 0,
                omidFinished = (omidCalls["finishSession"] ?: 0) > 0
            )
        }

        Hq008ConsentLogReporter.report(
            eventType = "IMA_RUNTIME_AUDIT",
            eventMessage = "requestId=$requestId,vastResponses=${snapshot.vastResponseCount}," +
                "verificationScripts=${snapshot.verificationScriptCount}," +
                "omidStarted=${snapshot.omidStarted},omidFinished=${snapshot.omidFinished}," +
                "terminalReason=$terminalReason",
            adLog = snapshot.summary,
            requestId = requestId
        )
    }

    private fun recordVast(event: GoogleImaAuditEvent) {
        val captureSource = event.fields["capture_source"].orEmpty().ifBlank { "native_xhr" }
        if (captureSource == "native_xhr") nativeXhrCount += 1 else webVastResponseCount += 1
        val xml = event.payload ?: return
        val xmlBytes = xml.toByteArray(Charsets.UTF_8)
        val parsed = parseVast(xml)
        val fields = event.fields
        val responseIndex = fields["response_index"]?.toIntOrNull() ?: (vastResponses.size + 1)
        vastResponses += VastResponse(
            captureSource = captureSource,
            responseIndex = responseIndex,
            imaRequestId = fields["request_id"].orEmpty(),
            imaResponseId = fields["response_id"].orEmpty(),
            requestUrl = fields["request_url"].orEmpty(),
            requestType = fields["request_type"].orEmpty(),
            contentType = fields["content_type"].orEmpty(),
            errorCode = fields["error_code"]?.toIntOrNull() ?: 0,
            xmlLength = xmlBytes.size,
            xmlSha256 = sha256(xmlBytes),
            encodedXml = gzipBase64(xmlBytes),
            wrapperCount = parsed.wrapperCount,
            inlineCount = parsed.inlineCount,
            verificationScriptCount = parsed.scripts.size,
            parseError = parsed.error
        )
        verificationScripts += parsed.scripts.map { script ->
            script.copy(responseIndex = responseIndex)
        }
    }

    private fun recordNativeXhr(event: GoogleImaAuditEvent) {
        nativeXhrCount += 1
        val fields = event.fields
        nativeXhrResponses += NativeXhrResponse(
            elapsedRealtimeMs = event.elapsedRealtimeMs,
            responseIndex = fields["response_index"]?.toIntOrNull() ?: nativeXhrResponses.size + 1,
            imaRequestId = fields["request_id"].orEmpty(),
            imaResponseId = fields["response_id"].orEmpty(),
            requestUrl = fields["request_url"].orEmpty(),
            requestType = fields["request_type"].orEmpty(),
            contentType = fields["content_type"].orEmpty(),
            errorCode = fields["error_code"]?.toIntOrNull() ?: 0,
            contentLength = fields["content_length"]?.toIntOrNull() ?: 0,
            contentSha256 = fields["content_sha256"].orEmpty()
        )
    }

    private fun recordWebResource(event: GoogleImaAuditEvent) {
        val url = event.fields["url"].orEmpty()
        if (url.isBlank()) return
        val status = webResources.getOrPut(url) { WebResourceStatus() }
        status.lastHttpMethod = event.fields["http_method"].orEmpty().ifBlank {
            status.lastHttpMethod
        }
        status.lastMainFrame = event.fields["main_frame"].orEmpty().ifBlank {
            status.lastMainFrame
        }
        when (event.fields["phase"]) {
            "request" -> status.requestCount += 1
            "load" -> status.loadCallbackCount += 1
            "page_started" -> status.pageStartedCount += 1
            "page_finished" -> status.pageFinishedCount += 1
            "error", "http_error" -> {
                status.errorCount += 1
                status.lastErrorCode = event.fields["error_code"].orEmpty()
                status.lastError = event.fields["error"].orEmpty()
            }
        }
    }

    private fun recordOmid(event: GoogleImaAuditEvent) {
        val method = event.fields["method"].orEmpty()
        if (method.isBlank()) return
        omidCalls[method] = (omidCalls[method] ?: 0) + 1
        val sessionId = event.fields["session_id"].orEmpty().ifBlank { "unscoped" }
        val session = omidSessions.getOrPut(sessionId) { OmidSessionStatus() }
        session.methodCallCounts[method] = (session.methodCallCounts[method] ?: 0) + 1
        if (method != "setNativeViewHierarchy") return

        hierarchyCount += 1
        session.hierarchyCount += 1
        val now = event.elapsedRealtimeMs
        val previous = hierarchyLastAtMs
        if (previous != null) hierarchyMaxGapMs = maxOf(hierarchyMaxGapMs, now - previous)
        if (hierarchyFirstAtMs == null) hierarchyFirstAtMs = now
        hierarchyLastAtMs = now
        hierarchyLastHash = event.fields["hierarchy_sha256"].orEmpty()
        hierarchyLastLength = event.fields["hierarchy_length"]?.toIntOrNull() ?: 0
        session.lastHierarchyHash = hierarchyLastHash
        session.lastHierarchyLength = hierarchyLastLength
        event.payload?.takeIf { it.isNotBlank() }?.let { hierarchy ->
            session.lastHierarchyEncoded = gzipBase64(hierarchy.toByteArray(Charsets.UTF_8))
        }
        if (event.fields["not_visible_reason_present"] == "true") {
            hierarchyNotVisibleReasonCount += 1
            session.notVisibleReasonCount += 1
        }
        if (event.fields["window_focus_false_present"] == "true") {
            hierarchyWindowFocusFalseCount += 1
            session.windowFocusFalseCount += 1
        }
    }

    private fun recordImaAdEvent(event: GoogleImaAuditEvent) {
        val fields = event.fields
        val imaEvent = fields["ima_event"].orEmpty()
        val adBaseKey = buildAdBaseKey(fields)
        val adKey = resolveAdKey(imaEvent, adBaseKey)
        val adData = fields
            .filterKeys { it.startsWith(AD_DATA_PREFIX) }
            .mapKeys { it.key.removePrefix(AD_DATA_PREFIX) }
        val adStatus = adKey.takeIf { it.isNotBlank() }?.let { key ->
            adPlaybackStates.getOrPut(key) { AdPlaybackStatus(key) }.apply {
                updateMetadata(fields)
            }
        }
        if (imaEvent.isNotBlank()) {
            imaAdEventCounts[imaEvent] = (imaAdEventCounts[imaEvent] ?: 0) + 1
            lastImaAdEvent = imaEvent
            imaAdEvents += ImaAdEventRecord(
                elapsedRealtimeMs = event.elapsedRealtimeMs,
                event = imaEvent,
                adDurationMs = fields["ad_duration_ms"]?.toLongOrNull() ?: -1L,
                podMaxDurationMs = fields["pod_max_duration_ms"]?.toLongOrNull() ?: -1L,
                totalAds = fields["total_ads"]?.toIntOrNull() ?: -1,
                adPosition = fields["ad_position"]?.toIntOrNull() ?: -1,
                podIndex = fields["pod_index"]?.toIntOrNull() ?: -1,
                adKey = adKey,
                imaAdId = fields["ima_ad_id"].orEmpty(),
                creativeId = fields["creative_id"].orEmpty(),
                creativeAdId = fields["creative_ad_id"].orEmpty(),
                universalAdIds = fields["universal_ad_ids"].orEmpty(),
                adData = adData
            )
        }
        when (imaEvent) {
            "LOADED" -> {
                pendingAdKey = adKey
                pendingAdBaseKey = adBaseKey
                adStatus?.loadedAtMs = event.elapsedRealtimeMs
            }
            "STARTED" -> {
                activeAdKey = adKey
                activeAdBaseKey = adBaseKey
                if (pendingAdKey == adKey) pendingAdKey = ""
                if (pendingAdBaseKey == adBaseKey) pendingAdBaseKey = ""
                currentAdMaxPositionMs = -1L
                adStatus?.apply {
                    startedAtMs = event.elapsedRealtimeMs
                    maxPositionMs = -1L
                    terminalEvent = ""
                    completedAtMs = null
                }
            }
            "COMPLETED", "SKIPPED" -> {
                if (activeAdKey.isBlank() && adKey.isNotBlank()) activeAdKey = adKey
                val terminalStatus = adStatus ?: adPlaybackStates[activeAdKey]
                terminalStatus?.apply {
                    terminalEvent = imaEvent
                    completedAtMs = event.elapsedRealtimeMs
                    if (imaEvent == "COMPLETED" && durationMs > 0L) {
                        maxPositionMs = maxOf(maxPositionMs, durationMs)
                    }
                }
                currentAdMaxPositionMs = terminalStatus?.maxPositionMs ?: currentAdMaxPositionMs
            }
        }
        fields["ad_duration_ms"]?.toLongOrNull()?.takeIf { it > 0L }?.let {
            lastAdDurationMs = it
        }
        fields["pod_max_duration_ms"]?.toLongOrNull()?.takeIf { it > 0L }?.let {
            lastPodMaxDurationMs = it
        }
        fields["total_ads"]?.toIntOrNull()?.takeIf { it > 0 }?.let {
            lastTotalAds = it
        }
        fields["ad_position"]?.toIntOrNull()?.takeIf { it > 0 }?.let {
            lastAdPosition = it
        }
        fields["pod_index"]?.toIntOrNull()?.takeIf { it >= 0 }?.let {
            lastPodIndex = it
        }
    }

    private fun recordPlayerSnapshot(event: GoogleImaAuditEvent) {
        playerSnapshotCount += 1
        if (event.eventType == "playback_progress") playbackProgressCount += 1
        if (event.eventType == "first_frame") {
            firstFrameObserved = true
            firstFrameCount += 1
        }
        val fields = event.fields
        fields["playback_state"]?.toIntOrNull()?.let { lastPlayerState = it }
        fields["position_ms"]?.toLongOrNull()?.let { lastPlayerPositionMs = it }
        fields["duration_ms"]?.toLongOrNull()?.let { lastPlayerDurationMs = it }
        fields["buffered_position_ms"]?.toLongOrNull()?.let { lastBufferedPositionMs = it }
        fields["is_playing"]?.toBooleanStrictOrNull()?.let { lastPlayerIsPlaying = it }
        fields["is_playing_ad"]?.toBooleanStrictOrNull()?.let { lastPlayerIsPlayingAd = it }
        fields["play_when_ready"]?.toBooleanStrictOrNull()?.let { lastPlayWhenReady = it }
        fields["playback_suppression_reason"]?.toIntOrNull()?.let {
            lastPlaybackSuppressionReason = it
        }
        val isPlayingAd = fields["is_playing_ad"]?.toBooleanStrictOrNull() ?: false
        val state = fields["playback_state"]?.toIntOrNull() ?: -1
        val position = fields["position_ms"]?.toLongOrNull() ?: -1L
        val duration = fields["duration_ms"]?.toLongOrNull() ?: -1L
        val buffered = fields["buffered_position_ms"]?.toLongOrNull() ?: -1L
        val isPlaying = fields["is_playing"]?.toBooleanStrictOrNull() ?: false
        val trigger = fields["trigger"].orEmpty()
        if (isPlayingAd) {
            lastAdPlayerState = state
            lastAdPlayerPositionMs = position
            lastAdPlayerDurationMs = duration
            lastAdBufferedPositionMs = buffered
            lastAdPlayerIsPlaying = isPlaying
            val terminalTransitionSnapshot = trigger.startsWith("ima_COMPLETED") ||
                trigger.startsWith("ima_SKIPPED")
            if (position >= 0L && !terminalTransitionSnapshot) {
                maxAdPositionMs = maxOf(maxAdPositionMs, position)
                currentAdMaxPositionMs = maxOf(currentAdMaxPositionMs, position)
                adPlaybackStates[activeAdKey]?.let { status ->
                    status.maxPositionMs = maxOf(status.maxPositionMs, position)
                    currentAdMaxPositionMs = status.maxPositionMs
                }
            }
        } else {
            lastContentPlayerState = state
            lastContentPlayerPositionMs = position
            lastContentPlayerDurationMs = duration
            lastContentBufferedPositionMs = buffered
            lastContentPlayerIsPlaying = isPlaying
        }
        if (event.eventType == "terminal_snapshot") {
            terminalSnapshots += PlayerSnapshotRecord(
                elapsedRealtimeMs = event.elapsedRealtimeMs,
                trigger = trigger,
                playbackState = state,
                positionMs = position,
                durationMs = duration,
                bufferedPositionMs = buffered,
                isPlaying = isPlaying,
                isPlayingAd = isPlayingAd,
                playWhenReady = fields["play_when_ready"]?.toBooleanStrictOrNull() ?: false,
                playbackSuppressionReason =
                    fields["playback_suppression_reason"]?.toIntOrNull() ?: -1
            )
        }
    }

    private fun recordPlaybackError(event: GoogleImaAuditEvent) {
        playbackErrors += PlaybackErrorRecord(
            elapsedRealtimeMs = event.elapsedRealtimeMs,
            category = event.fields["category"].orEmpty(),
            reason = event.fields["reason"].orEmpty(),
            message = event.fields["message"].orEmpty(),
            details = event.fields.filterKeys {
                it != "category" && it != "reason" && it != "message"
            }
        )
    }

    private fun buildSummary(terminalReason: String): String {
        val verificationSummary = verificationScripts.map { script ->
            val resourceMatch = matchWebResources(script.url)
            val notExecutedMatch = matchWebResources(script.notExecutedUrl)
            val status = resourceMatch.status
            val notExecutedStatus = notExecutedMatch.status
            linkedMapOf<String, Any?>(
                "response_index" to script.responseIndex,
                "vendor" to script.vendor,
                "url" to script.url,
                "api_framework" to script.apiFramework,
                "browser_optional" to script.browserOptional,
                "verification_parameters" to script.verificationParameters,
                "verification_not_executed_url" to script.notExecutedUrl,
                "verification_not_executed_actual_urls" to notExecutedMatch.actualUrls,
                "verification_not_executed_macro_values" to notExecutedMatch.macroValues,
                "verification_not_executed_reason" to notExecutedMatch.macroValues["REASON"],
                "verification_not_executed_request_observed" to
                    ((notExecutedStatus?.requestCount ?: 0) > 0),
                "actual_urls" to resourceMatch.actualUrls,
                "request_observed" to ((status?.requestCount ?: 0) > 0),
                "load_callback_observed" to ((status?.loadCallbackCount ?: 0) > 0),
                "request_count" to (status?.requestCount ?: 0),
                "load_callback_count" to (status?.loadCallbackCount ?: 0),
                "error_count" to (status?.errorCount ?: 0),
                "last_error_code" to status?.lastErrorCode,
                "last_error" to status?.lastError,
                "client_observation" to when {
                    (status?.errorCount ?: 0) > 0 -> "error_observed"
                    (status?.requestCount ?: 0) > 0 ||
                        (status?.loadCallbackCount ?: 0) > 0 -> "load_observed_without_client_error"
                    else -> "not_observed"
                }
            )
        }
        val hierarchyDuration = hierarchyFirstAtMs?.let { first ->
            hierarchyLastAtMs?.minus(first)
        }
        val activeAdStatus = adPlaybackStates[activeAdKey]
        val adProgressRatio = activeAdStatus?.playbackRatio()
        return gson.toJson(
            linkedMapOf(
                "schema_version" to 3,
                "audit_scope" to "pure_ima_request_playback_omid",
                "request_id" to requestId,
                "terminal_reason" to terminalReason,
                "native_xhr_response_count" to nativeXhrCount,
                "native_xhr_responses" to nativeXhrResponses.map { response ->
                    linkedMapOf(
                        "elapsed_realtime_ms" to response.elapsedRealtimeMs,
                        "response_index" to response.responseIndex,
                        "ima_request_id" to response.imaRequestId,
                        "ima_response_id" to response.imaResponseId,
                        "request_url" to response.requestUrl,
                        "request_type" to response.requestType,
                        "content_type" to response.contentType,
                        "error_code" to response.errorCode,
                        "content_length" to response.contentLength,
                        "content_sha256" to response.contentSha256
                    )
                },
                "web_vast_response_count" to webVastResponseCount,
                "captured_vast_response_count" to vastResponses.size,
                "vast_response_count" to vastResponses.size,
                "vast_responses" to vastResponses.map { response ->
                    linkedMapOf(
                        "capture_source" to response.captureSource,
                        "response_index" to response.responseIndex,
                        "ima_request_id" to response.imaRequestId,
                        "ima_response_id" to response.imaResponseId,
                        "request_url" to response.requestUrl,
                        "request_type" to response.requestType,
                        "content_type" to response.contentType,
                        "error_code" to response.errorCode,
                        "xml_length" to response.xmlLength,
                        "xml_sha256" to response.xmlSha256,
                        "wrapper_count" to response.wrapperCount,
                        "inline_count" to response.inlineCount,
                        "verification_script_count" to response.verificationScriptCount,
                        "parse_error" to response.parseError,
                        "artifact_id" to "vast-response-${response.responseIndex}"
                    )
                },
                "vast_artifacts" to vastResponses.map { response ->
                    linkedMapOf(
                        "artifact_type" to "ima_vast_xml",
                        "artifact_id" to "vast-response-${response.responseIndex}",
                        "encoding" to "gzip+base64",
                        "original_length" to response.xmlLength,
                        "original_sha256" to response.xmlSha256,
                        "metadata" to linkedMapOf(
                            "capture_source" to response.captureSource,
                            "response_index" to response.responseIndex,
                            "ima_request_id" to response.imaRequestId,
                            "ima_response_id" to response.imaResponseId,
                            "request_url" to response.requestUrl,
                            "request_type" to response.requestType,
                            "content_type" to response.contentType,
                            "error_code" to response.errorCode,
                            "wrapper_count" to response.wrapperCount,
                            "inline_count" to response.inlineCount,
                            "verification_script_count" to response.verificationScriptCount,
                            "parse_error" to response.parseError
                        ),
                        "content" to response.encodedXml
                    )
                },
                "verification_scripts" to verificationSummary,
                "web_resource_url_count" to webResources.size,
                "web_resources" to webResources.map { (url, status) ->
                    linkedMapOf(
                        "url" to url,
                        "request_count" to status.requestCount,
                        "load_callback_count" to status.loadCallbackCount,
                        "page_started_count" to status.pageStartedCount,
                        "page_finished_count" to status.pageFinishedCount,
                        "error_count" to status.errorCount,
                        "last_http_method" to status.lastHttpMethod,
                        "last_main_frame" to status.lastMainFrame,
                        "last_error_code" to status.lastErrorCode,
                        "last_error" to status.lastError
                    )
                },
                "ima_playback_audit" to linkedMapOf(
                    "ima_event_counts" to imaAdEventCounts,
                    "ima_event_timeline" to imaAdEvents.map { event ->
                        linkedMapOf(
                            "elapsed_realtime_ms" to event.elapsedRealtimeMs,
                            "event" to event.event,
                            "ad_duration_ms" to event.adDurationMs,
                            "pod_max_duration_ms" to event.podMaxDurationMs,
                            "total_ads" to event.totalAds,
                            "ad_position" to event.adPosition,
                            "pod_index" to event.podIndex,
                            "ad_key" to event.adKey,
                            "ima_ad_id" to event.imaAdId,
                            "creative_id" to event.creativeId,
                            "creative_ad_id" to event.creativeAdId,
                            "universal_ad_ids" to event.universalAdIds,
                            "ad_data" to event.adData
                        )
                    },
                    "active_ad_key" to activeAdKey,
                    "pending_ad_key" to pendingAdKey,
                    "ad_instances" to adPlaybackStates.values.map { status ->
                        linkedMapOf<String, Any?>(
                            "ad_key" to status.adKey,
                            "ima_ad_id" to status.imaAdId,
                            "creative_id" to status.creativeId,
                            "creative_ad_id" to status.creativeAdId,
                            "universal_ad_ids" to status.universalAdIds,
                            "duration_ms" to status.durationMs,
                            "total_ads" to status.totalAds,
                            "ad_position" to status.adPosition,
                            "pod_index" to status.podIndex,
                            "loaded_at_ms" to status.loadedAtMs,
                            "started_at_ms" to status.startedAtMs,
                            "completed_at_ms" to status.completedAtMs,
                            "terminal_event" to status.terminalEvent,
                            "max_position_ms" to status.maxPositionMs,
                            "playback_ratio" to status.playbackRatio()
                        )
                    },
                    "playback_errors" to playbackErrors.map { error ->
                        linkedMapOf(
                            "elapsed_realtime_ms" to error.elapsedRealtimeMs,
                            "category" to error.category,
                            "reason" to error.reason,
                            "message" to error.message,
                            "details" to error.details
                        )
                    },
                    "last_ima_event" to lastImaAdEvent,
                    "last_ad_duration_ms" to lastAdDurationMs,
                    "last_pod_max_duration_ms" to lastPodMaxDurationMs,
                    "last_total_ads" to lastTotalAds,
                    "last_ad_position" to lastAdPosition,
                    "last_pod_index" to lastPodIndex,
                    "player_snapshot_count" to playerSnapshotCount,
                    "playback_progress_count" to playbackProgressCount,
                    "first_frame_observed" to firstFrameObserved,
                    "first_frame_count" to firstFrameCount,
                    "last_player_state" to lastPlayerState,
                    "last_position_ms" to lastPlayerPositionMs,
                    "last_duration_ms" to lastPlayerDurationMs,
                    "last_buffered_position_ms" to lastBufferedPositionMs,
                    "last_is_playing" to lastPlayerIsPlaying,
                    "last_is_playing_ad" to lastPlayerIsPlayingAd,
                    "last_play_when_ready" to lastPlayWhenReady,
                    "last_playback_suppression_reason" to lastPlaybackSuppressionReason,
                    "last_ad_player_state" to lastAdPlayerState,
                    "last_ad_position_ms" to lastAdPlayerPositionMs,
                    "last_ad_duration_ms_from_player" to lastAdPlayerDurationMs,
                    "last_ad_buffered_position_ms" to lastAdBufferedPositionMs,
                    "last_ad_is_playing" to lastAdPlayerIsPlaying,
                    "max_ad_position_ms" to maxAdPositionMs,
                    "current_ad_max_position_ms" to currentAdMaxPositionMs,
                    "ad_progress_ratio" to adProgressRatio,
                    "last_content_player_state" to lastContentPlayerState,
                    "last_content_position_ms" to lastContentPlayerPositionMs,
                    "last_content_duration_ms" to lastContentPlayerDurationMs,
                    "last_content_buffered_position_ms" to lastContentBufferedPositionMs,
                    "last_content_is_playing" to lastContentPlayerIsPlaying,
                    "terminal_snapshots" to terminalSnapshots.map { snapshot ->
                        linkedMapOf(
                            "elapsed_realtime_ms" to snapshot.elapsedRealtimeMs,
                            "trigger" to snapshot.trigger,
                            "playback_state" to snapshot.playbackState,
                            "position_ms" to snapshot.positionMs,
                            "duration_ms" to snapshot.durationMs,
                            "buffered_position_ms" to snapshot.bufferedPositionMs,
                            "is_playing" to snapshot.isPlaying,
                            "is_playing_ad" to snapshot.isPlayingAd,
                            "play_when_ready" to snapshot.playWhenReady,
                            "playback_suppression_reason" to
                                snapshot.playbackSuppressionReason
                        )
                    }
                ),
                "omid_runtime_audit" to linkedMapOf(
                    "method_call_counts" to omidCalls,
                    "initialized" to ((omidCalls["init"] ?: 0) > 0),
                    "session_started" to ((omidCalls["startSession"] ?: 0) > 0),
                    "session_finished" to ((omidCalls["finishSession"] ?: 0) > 0),
                    "hierarchy_submission_count" to hierarchyCount,
                    "hierarchy_first_at_ms" to hierarchyFirstAtMs,
                    "hierarchy_last_at_ms" to hierarchyLastAtMs,
                    "hierarchy_duration_ms" to hierarchyDuration,
                    "hierarchy_max_gap_ms" to hierarchyMaxGapMs,
                    "last_hierarchy_length" to hierarchyLastLength,
                    "last_hierarchy_sha256" to hierarchyLastHash,
                    "not_visible_reason_submission_count" to hierarchyNotVisibleReasonCount,
                    "window_focus_false_submission_count" to hierarchyWindowFocusFalseCount,
                    "sessions" to omidSessions.map { (sessionId, status) ->
                        linkedMapOf<String, Any?>(
                            "session_id" to sessionId,
                            "method_call_counts" to status.methodCallCounts,
                            "session_started" to ((status.methodCallCounts["startSession"] ?: 0) > 0),
                            "session_finished" to ((status.methodCallCounts["finishSession"] ?: 0) > 0),
                            "hierarchy_submission_count" to status.hierarchyCount,
                            "last_hierarchy_length" to status.lastHierarchyLength,
                            "last_hierarchy_sha256" to status.lastHierarchyHash,
                            "not_visible_reason_submission_count" to status.notVisibleReasonCount,
                            "window_focus_false_submission_count" to status.windowFocusFalseCount,
                            "last_hierarchy_artifact" to status.lastHierarchyEncoded
                                .takeIf { it.isNotBlank() }
                                ?.let { encoded ->
                                    linkedMapOf(
                                        "encoding" to "gzip+base64",
                                        "original_length" to status.lastHierarchyLength,
                                        "original_sha256" to status.lastHierarchyHash,
                                        "content" to encoded
                                    )
                                }
                        )
                    }
                )
            )
        )
    }

    private fun resolveAdKey(imaEvent: String, adBaseKey: String): String {
        fun activeMatches(): Boolean = activeAdKey.isNotBlank() &&
            (adBaseKey.isBlank() || activeAdBaseKey == adBaseKey)
        fun pendingMatches(): Boolean = pendingAdKey.isNotBlank() &&
            (adBaseKey.isBlank() || pendingAdBaseKey == adBaseKey)

        return when (imaEvent) {
            "LOADED" -> newAdInstanceKey(adBaseKey)
            "STARTED" -> if (pendingMatches()) pendingAdKey else newAdInstanceKey(adBaseKey)
            "COMPLETED", "SKIPPED" -> when {
                activeMatches() -> activeAdKey
                pendingMatches() -> pendingAdKey
                else -> newAdInstanceKey(adBaseKey)
            }
            else -> when {
                activeMatches() -> activeAdKey
                pendingMatches() -> pendingAdKey
                adBaseKey.isBlank() -> ""
                else -> newAdInstanceKey(adBaseKey)
            }
        }
    }

    private fun newAdInstanceKey(adBaseKey: String): String {
        adInstanceSequence += 1
        return "${adBaseKey.ifBlank { "unknown" }}:instance:$adInstanceSequence"
    }

    private fun buildAdBaseKey(fields: Map<String, String>): String {
        val podIndex = fields["pod_index"]?.toIntOrNull() ?: -1
        val adPosition = fields["ad_position"]?.toIntOrNull() ?: -1
        val materialIdentity = fields["ima_ad_id"].orEmpty().takeIf { it.isNotBlank() }
            ?.let { "ad:$it" }
            ?: fields["creative_id"].orEmpty().takeIf { it.isNotBlank() }
                ?.let { "creative:$it" }
            ?: "material:unknown"
        return if (podIndex >= 0 && adPosition > 0) {
            "pod:$podIndex:$adPosition:$materialIdentity"
        } else if (materialIdentity != "material:unknown") {
            materialIdentity
        } else {
            ""
        }
    }

    private fun matchWebResources(templateUrl: String): WebResourceMatch {
        if (templateUrl.isBlank()) return WebResourceMatch(null, emptyList(), emptyMap())
        webResources[templateUrl]?.let { exact ->
            return WebResourceMatch(exact, listOf(templateUrl), emptyMap())
        }
        val macros = URL_MACRO.findAll(templateUrl).toList()
        if (macros.isEmpty()) return WebResourceMatch(null, emptyList(), emptyMap())

        val names = macros.map { match ->
            (match.groups[1]?.value ?: match.groups[2]?.value.orEmpty()).uppercase()
        }
        val pattern = buildString {
            append('^')
            var offset = 0
            macros.forEach { match ->
                append(Regex.escape(templateUrl.substring(offset, match.range.first)))
                append("([^&#]*)")
                offset = match.range.last + 1
            }
            append(Regex.escape(templateUrl.substring(offset)))
            append('$')
        }.toRegex(RegexOption.IGNORE_CASE)

        val matches = webResources.mapNotNull { (actualUrl, status) ->
            val match = pattern.matchEntire(actualUrl) ?: return@mapNotNull null
            val values = names.mapIndexed { index, name ->
                name to decodeUrl(match.groupValues[index + 1])
            }.toMap()
            Triple(actualUrl, status, values)
        }
        if (matches.isEmpty()) return WebResourceMatch(null, emptyList(), emptyMap())
        val aggregate = WebResourceStatus()
        matches.forEach { (_, status, _) -> aggregate.merge(status) }
        val macroValues = linkedMapOf<String, String>()
        matches.forEach { (_, _, values) ->
            values.forEach { (name, value) ->
                macroValues.putIfAbsent(name, value)
            }
        }
        return WebResourceMatch(aggregate, matches.map { it.first }, macroValues)
    }

    private fun decodeUrl(value: String): String = runCatching {
        URLDecoder.decode(value, Charsets.UTF_8.name())
    }.getOrDefault(value)

    private fun parseVast(xml: String): ParsedVast {
        var wrapperCount = 0
        var inlineCount = 0
        val scripts = mutableListOf<VerificationScript>()
        return runCatching {
            val factory = DocumentBuilderFactory.newInstance().apply {
                isNamespaceAware = true
            }
            runCatching { factory.isXIncludeAware = false }
            runCatching { factory.isExpandEntityReferences = false }
            setSecureXmlFeature(factory, "http://apache.org/xml/features/disallow-doctype-decl", true)
            setSecureXmlFeature(factory, "http://xml.org/sax/features/external-general-entities", false)
            setSecureXmlFeature(factory, "http://xml.org/sax/features/external-parameter-entities", false)
            setSecureXmlFeature(factory, "http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
            val document = factory.newDocumentBuilder().parse(InputSource(StringReader(xml)))
            walkElements(document.documentElement) { element ->
                when (element.localTagName()) {
                    "Wrapper" -> wrapperCount += 1
                    "InLine" -> inlineCount += 1
                    "Verification" -> scripts += parseVerification(element)
                }
            }
            ParsedVast(wrapperCount, inlineCount, scripts, null)
        }.getOrElse { error ->
            ParsedVast(wrapperCount, inlineCount, scripts, error.message ?: error.javaClass.simpleName)
        }
    }

    private fun parseVerification(element: Element): List<VerificationScript> {
        val vendor = element.attribute("vendor")
        val resources = mutableListOf<Element>()
        var parameters = ""
        var notExecutedUrl = ""
        walkElements(element) { child ->
            when (child.localTagName()) {
                "JavaScriptResource" -> resources += child
                "VerificationParameters" -> parameters = child.textContent.orEmpty().trim()
                "Tracking" -> if (child.attribute("event") == "verificationNotExecuted") {
                    notExecutedUrl = child.textContent.orEmpty().trim()
                }
            }
        }
        return resources.mapNotNull { resource ->
            val url = resource.textContent.orEmpty().trim()
            if (url.isBlank()) null else VerificationScript(
                responseIndex = 0,
                vendor = vendor,
                url = url,
                apiFramework = resource.attribute("apiFramework"),
                browserOptional = resource.attribute("browserOptional"),
                verificationParameters = parameters,
                notExecutedUrl = notExecutedUrl
            )
        }
    }

    private fun walkElements(root: Element, visitor: (Element) -> Unit) {
        visitor(root)
        val children = root.childNodes
        for (index in 0 until children.length) {
            val child = children.item(index)
            if (child.nodeType == Node.ELEMENT_NODE) {
                walkElements(child as Element, visitor)
            }
        }
    }

    private fun Element.localTagName(): String = localName ?: tagName.substringAfter(':')

    private fun Element.attribute(name: String): String {
        val attributes = attributes
        for (index in 0 until attributes.length) {
            val attribute = attributes.item(index)
            val localName = attribute.localName ?: attribute.nodeName.substringAfter(':')
            if (localName == name) return attribute.nodeValue.orEmpty()
        }
        return ""
    }

    private fun setSecureXmlFeature(
        factory: DocumentBuilderFactory,
        feature: String,
        enabled: Boolean
    ) {
        runCatching { factory.setFeature(feature, enabled) }
    }

    private fun sha256(value: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(value)
        .joinToString("") { "%02x".format(it) }

    private fun gzipBase64(value: ByteArray): String {
        val compressed = ByteArrayOutputStream().use { output ->
            GZIPOutputStream(output).use { gzip -> gzip.write(value) }
            output.toByteArray()
        }
        return BaseEncoding.base64().encode(compressed)
    }

    private data class Snapshot(
        val summary: String,
        val vastResponseCount: Int,
        val verificationScriptCount: Int,
        val omidStarted: Boolean,
        val omidFinished: Boolean
    )

    private data class VastResponse(
        val captureSource: String,
        val responseIndex: Int,
        val imaRequestId: String,
        val imaResponseId: String,
        val requestUrl: String,
        val requestType: String,
        val contentType: String,
        val errorCode: Int,
        val xmlLength: Int,
        val xmlSha256: String,
        val encodedXml: String,
        val wrapperCount: Int,
        val inlineCount: Int,
        val verificationScriptCount: Int,
        val parseError: String?
    )

    private data class ParsedVast(
        val wrapperCount: Int,
        val inlineCount: Int,
        val scripts: List<VerificationScript>,
        val error: String?
    )

    private data class VerificationScript(
        val responseIndex: Int,
        val vendor: String,
        val url: String,
        val apiFramework: String,
        val browserOptional: String,
        val verificationParameters: String,
        val notExecutedUrl: String
    )

    private data class NativeXhrResponse(
        val elapsedRealtimeMs: Long,
        val responseIndex: Int,
        val imaRequestId: String,
        val imaResponseId: String,
        val requestUrl: String,
        val requestType: String,
        val contentType: String,
        val errorCode: Int,
        val contentLength: Int,
        val contentSha256: String
    )

    private data class ImaAdEventRecord(
        val elapsedRealtimeMs: Long,
        val event: String,
        val adDurationMs: Long,
        val podMaxDurationMs: Long,
        val totalAds: Int,
        val adPosition: Int,
        val podIndex: Int,
        val adKey: String,
        val imaAdId: String,
        val creativeId: String,
        val creativeAdId: String,
        val universalAdIds: String,
        val adData: Map<String, String>
    )

    private data class PlaybackErrorRecord(
        val elapsedRealtimeMs: Long,
        val category: String,
        val reason: String,
        val message: String,
        val details: Map<String, String>
    )

    private data class PlayerSnapshotRecord(
        val elapsedRealtimeMs: Long,
        val trigger: String,
        val playbackState: Int,
        val positionMs: Long,
        val durationMs: Long,
        val bufferedPositionMs: Long,
        val isPlaying: Boolean,
        val isPlayingAd: Boolean,
        val playWhenReady: Boolean,
        val playbackSuppressionReason: Int
    )

    private data class AdPlaybackStatus(
        val adKey: String,
        var imaAdId: String = "",
        var creativeId: String = "",
        var creativeAdId: String = "",
        var universalAdIds: String = "",
        var durationMs: Long = -1L,
        var totalAds: Int = -1,
        var adPosition: Int = -1,
        var podIndex: Int = -1,
        var loadedAtMs: Long? = null,
        var startedAtMs: Long? = null,
        var completedAtMs: Long? = null,
        var terminalEvent: String = "",
        var maxPositionMs: Long = -1L
    ) {
        fun updateMetadata(fields: Map<String, String>) {
            fields["ima_ad_id"].orEmpty().takeIf { it.isNotBlank() }?.let { imaAdId = it }
            fields["creative_id"].orEmpty().takeIf { it.isNotBlank() }?.let { creativeId = it }
            fields["creative_ad_id"].orEmpty().takeIf { it.isNotBlank() }?.let {
                creativeAdId = it
            }
            fields["universal_ad_ids"].orEmpty().takeIf { it.isNotBlank() }?.let {
                universalAdIds = it
            }
            fields["ad_duration_ms"]?.toLongOrNull()?.takeIf { it > 0L }?.let {
                durationMs = it
            }
            fields["total_ads"]?.toIntOrNull()?.takeIf { it > 0 }?.let { totalAds = it }
            fields["ad_position"]?.toIntOrNull()?.takeIf { it > 0 }?.let { adPosition = it }
            fields["pod_index"]?.toIntOrNull()?.takeIf { it >= 0 }?.let { podIndex = it }
        }

        fun playbackRatio(): Double? = when {
            terminalEvent == "COMPLETED" -> 1.0
            durationMs > 0L && maxPositionMs >= 0L ->
                (maxPositionMs.toDouble() / durationMs.toDouble()).coerceIn(0.0, 1.0)
            else -> null
        }
    }

    private data class OmidSessionStatus(
        val methodCallCounts: LinkedHashMap<String, Int> = linkedMapOf(),
        var hierarchyCount: Int = 0,
        var lastHierarchyLength: Int = 0,
        var lastHierarchyHash: String = "",
        var lastHierarchyEncoded: String = "",
        var notVisibleReasonCount: Int = 0,
        var windowFocusFalseCount: Int = 0
    )

    private data class WebResourceStatus(
        var requestCount: Int = 0,
        var loadCallbackCount: Int = 0,
        var pageStartedCount: Int = 0,
        var pageFinishedCount: Int = 0,
        var errorCount: Int = 0,
        var lastHttpMethod: String = "",
        var lastMainFrame: String = "",
        var lastErrorCode: String = "",
        var lastError: String = ""
    ) {
        fun merge(other: WebResourceStatus) {
            requestCount += other.requestCount
            loadCallbackCount += other.loadCallbackCount
            pageStartedCount += other.pageStartedCount
            pageFinishedCount += other.pageFinishedCount
            errorCount += other.errorCount
            if (other.lastHttpMethod.isNotBlank()) lastHttpMethod = other.lastHttpMethod
            if (other.lastMainFrame.isNotBlank()) lastMainFrame = other.lastMainFrame
            if (other.lastErrorCode.isNotBlank()) lastErrorCode = other.lastErrorCode
            if (other.lastError.isNotBlank()) lastError = other.lastError
        }
    }

    private data class WebResourceMatch(
        val status: WebResourceStatus?,
        val actualUrls: List<String>,
        val macroValues: Map<String, String>
    )

    private companion object {
        const val AD_DATA_PREFIX = "ad_data."
        val URL_MACRO = Regex(
            "(?:\\[([A-Za-z0-9_]+)]|%5B([A-Za-z0-9_]+)%5D)",
            RegexOption.IGNORE_CASE
        )
    }
}
