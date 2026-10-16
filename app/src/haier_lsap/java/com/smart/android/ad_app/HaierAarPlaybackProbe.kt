package com.smart.android.ad_app

import android.app.ActivityManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.view.ViewGroup
import android.widget.VideoView
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.Format
import com.google.android.exoplayer2.PlaybackException
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.analytics.AnalyticsListener
import com.google.android.exoplayer2.decoder.DecoderReuseEvaluation
import com.google.android.exoplayer2.source.LoadEventInfo
import com.google.android.exoplayer2.source.MediaLoadData
import com.google.android.exoplayer2.upstream.HttpDataSource
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.lang.ref.WeakReference

/** Passive observation of the actual ad. No extra network request, retry or playback change. */
internal object HaierAarPlaybackProbe {
    private const val PREFS = "jams_playback_probe"
    private const val CHECKPOINT = "last_flow"
    private const val INTERVAL_MS = 5_000L
    private val main = Handler(Looper.getMainLooper())
    private var flow: Flow? = null

    fun begin(id: String, container: ViewGroup) = safely {
        if (!HaierAarPlaybackDiagnostics.enabled) return@safely
        flow?.closeObservers()
        val next = Flow(id, container)
        flow = next
        HaierAarHttpDiagnostics.begin(id)
        val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val previous = prefs.getString(CHECKPOINT, null)?.let { runCatching { JSONObject(it) }.getOrNull() }
        if (previous != null && previous.optString("request_id") != id) {
            val oldPid = previous.optInt("process_id")
            HaierAarPlaybackDiagnostics.evidence("previous_flow_checkpoint", mapOf(
                "previous_flow" to previous,
                "process_changed" to (oldPid != Process.myPid()),
                "exit_record" to if (oldPid != Process.myPid()) exitRecord(oldPid, previous.optLong("timestamp_ms")) else null,
                // An unfinished checkpoint alone does not prove a crash or identify its cause.
                "recovered_on_next_flow" to true
            ), id, essential = true)
        }
        HaierAarPlaybackDiagnostics.evidence("probe_environment", environment() + next.containerState(), id)
        next.checkpoint(false)
        main.postDelayed(next.ticker, INTERVAL_MS)
    }

    /** Called at the vendor PlayerHolder return, before media preparation. */
    fun attach(value: Any?) = safely {
        val current = flow ?: return@safely
        if (current.id != HaierAarRequestContext.currentRequestId() || current.closed) return@safely
        val player = value as? ExoPlayer ?: return@safely
        if (Looper.myLooper() != player.applicationLooper) {
            HaierAarPlaybackDiagnostics.evidence("player_probe_attach_skipped", mapOf("reason" to "wrong_player_thread"), current.id)
            return@safely
        }
        if (current.players.any { it.player.get() === player }) return@safely
        if (current.players.size >= 3) return@safely
        val observer = PlayerObservation(current, player)
        player.addAnalyticsListener(observer)
        current.players += observer
        HaierAarPlaybackDiagnostics.evidence("player_probe_attached", mapOf("player_index" to observer.index), current.id)
    }

    fun callback(id: String, name: String, success: Boolean? = null) = safely {
        val current = flow?.takeIf { it.id == id && !it.closed } ?: return@safely
        current.lastCallback = name
        HaierAarPlaybackDiagnostics.evidence("app_callback", mapOf("callback" to name, "success" to success), id)
        current.checkpoint(false)
    }

    /** Runs BEFORE the existing flow's terminal event, which triggers the upload. */
    fun finish(id: String, reason: String, success: Boolean?) = safely {
        val current = flow?.takeIf { it.id == id && !it.closed } ?: return@safely
        current.terminalReason = reason
        current.sdkSuccess = success
        current.sample()
        val summary = current.summary(true)
        HaierAarPlaybackDiagnostics.evidence("playback_probe_summary", mapOf("probe" to summary), id, essential = true)
        current.checkpoint(true)
        current.closeObservers()
        current.closed = true
        flow = null
    }

    private class Flow(val id: String, container: ViewGroup) {
        val started = SystemClock.elapsedRealtime()
        val container = WeakReference(container)
        val players = mutableListOf<PlayerObservation>()
        val samples = ArrayDeque<JSONObject>()
        var closed = false
        var lastCallback = "request_created"
        var terminalReason: String? = null
        var sdkSuccess: Boolean? = null
        val ticker = object : Runnable {
            override fun run() {
                safely {
                    if (flow !== this@Flow || closed) return@safely
                    if (!HaierAarPlaybackDiagnostics.enabled) {
                        closeObservers()
                        closed = true
                        flow = null
                        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(CHECKPOINT).apply()
                        return@safely
                    }
                    sample()
                    checkpoint(false)
                    main.postDelayed(this, INTERVAL_MS)
                }
            }
        }
        fun containerState(): Map<String, Any?> {
            val view = container.get() ?: return mapOf("container_released" to true)
            return mapOf("container_width" to view.width, "container_height" to view.height,
                "container_attached" to view.isAttachedToWindow, "container_shown" to view.isShown,
                "container_alpha" to view.alpha, "window_visibility" to view.windowVisibility,
                "window_focus" to view.hasWindowFocus(), "child_count" to view.childCount)
        }
        fun sample() {
            val sample = JSONObject(containerState()).put("elapsed_ms", SystemClock.elapsedRealtime() - started)
                .put("players", JSONArray(players.map { JSONObject(it.snapshot(false)) }))
            runCatching {
                container.get()?.let { view ->
                    findVideo(view)?.let { video ->
                        sample.put("backup_video_view", JSONObject(mapOf("position_ms" to video.currentPosition,
                            "duration_ms" to video.duration, "is_playing" to video.isPlaying,
                            "observation" to "poll_only_not_natural_completion_proof")))
                    }
                }
            }
            if (samples.size >= 8) samples.removeFirst()
            samples.addLast(sample)
        }
        fun summary(terminal: Boolean): JSONObject {
            val result = JSONObject(mapOf(
            "request_id" to id, "process_id" to Process.myPid(), "timestamp_ms" to System.currentTimeMillis(),
            "flow_elapsed_ms" to SystemClock.elapsedRealtime() - started, "terminal_observed" to terminal,
            "terminal_reason" to terminalReason, "sdk_success" to sdkSuccess,
            "last_callback" to lastCallback, "players" to JSONArray(players.map { JSONObject(it.snapshot()) }),
            "recent_samples" to JSONArray(samples.toList()), "environment" to JSONObject(environment()),
            "sdk_failure_details" to JSONObject(HaierAarPlaybackDiagnostics.failureDetails(id))
        ) + HaierAarHttpDiagnostics.snapshot(id))
            // Keep summaries under the existing backend's payload cap even for a noisy player.
            if (result.toString().length > 48_000) {
                result.put("summary_truncated", true)
                result.put("recent_samples", JSONArray(samples.takeLast(2)))
                result.optJSONArray("players")?.let { array ->
                    for (i in 0 until array.length()) {
                        val p = array.getJSONObject(i)
                        for (key in listOf("loads", "milestones")) {
                            p.optJSONArray(key)?.let { original ->
                                p.put(key, JSONArray((maxOf(0, original.length() - 4) until original.length()).map(original::get)))
                            }
                        }
                    }
                }
                result.optJSONArray("http_calls")?.let { original ->
                    result.put("http_calls", JSONArray((maxOf(0, original.length() - 8) until original.length()).map(original::get)))
                }
            }
            return result
        }
        fun checkpoint(terminal: Boolean) = safely {
            // apply() keeps disk I/O off the player thread; the latest write may be lost on abrupt power loss.
            appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(CHECKPOINT, summary(terminal).toString()).apply()
        }
        fun closeObservers() {
            main.removeCallbacks(ticker)
            players.forEach { observer -> safely { observer.player.get()?.removeAnalyticsListener(observer) } }
        }
    }

    private class PlayerObservation(val owner: Flow, value: ExoPlayer) : AnalyticsListener {
        val player = WeakReference(value)
        val index = owner.players.size
        var firstFrame = false
        var ended = false
        var bufferingSince: Long? = null
        var bufferingMs = 0L
        var droppedFrames = 0
        var lastState = Player.STATE_IDLE
        var lastSnapshot = emptyMap<String, Any?>()
        val loads = linkedMapOf<Long, JSONObject>()
        val milestones = ArrayDeque<JSONObject>()

        fun active() = !owner.closed && flow === owner && owner.id == HaierAarRequestContext.currentRequestId()
        fun record(name: String, details: Map<String, Any?> = emptyMap(), error: Throwable? = null) = safely {
            if (!active()) return@safely
            val fields = mapOf("player_index" to index, "elapsed_ms" to SystemClock.elapsedRealtime() - owner.started) +
                details + (error?.let(HaierAarPlaybackDiagnostics::throwableDetails) ?: emptyMap())
            if (milestones.size >= 12) milestones.removeFirst()
            milestones.addLast(JSONObject(fields + mapOf("stage" to name)))
            HaierAarPlaybackDiagnostics.evidence(name, fields, owner.id, isFailure = error != null && name == "actual_player_error")
            if (error != null) owner.checkpoint(false)
        }
        fun snapshot(includeEvidence: Boolean = true): Map<String, Any?> {
            val p = player.get()
            if (p != null && Looper.myLooper() == p.applicationLooper) {
                runCatching {
                    lastSnapshot = mapOf("position_ms" to p.currentPosition, "duration_ms" to p.duration,
                        "buffered_position_ms" to p.bufferedPosition, "buffered_percent" to p.bufferedPercentage,
                        "is_playing" to p.isPlaying, "play_when_ready" to p.playWhenReady,
                        "suppression_reason" to p.playbackSuppressionReason, "playback_state" to p.playbackState,
                        "media_index" to p.currentMediaItemIndex, "media_count" to p.mediaItemCount)
                }
            }
            return lastSnapshot + mapOf("player_index" to index, "actual_first_frame" to firstFrame,
                "player_ended_observed" to ended, "dropped_frames" to droppedFrames,
                "buffering_ms" to bufferingMs + (bufferingSince?.let { SystemClock.elapsedRealtime() - it } ?: 0),
                ) + if (includeEvidence) mapOf("loads" to JSONArray(loads.values.toList()),
                    "milestones" to JSONArray(milestones.toList())) else emptyMap()
        }
        override fun onPlaybackStateChanged(eventTime: AnalyticsListener.EventTime, state: Int) = safely {
            if (!active()) return@safely
            val now = SystemClock.elapsedRealtime()
            if (lastState == Player.STATE_BUFFERING) { bufferingMs += now - (bufferingSince ?: now); bufferingSince = null }
            if (state == Player.STATE_BUFFERING) bufferingSince = now
            lastState = state
            if (state == Player.STATE_ENDED) ended = true
            record("actual_player_state", mapOf("state" to state, "position" to JSONObject(snapshot(false))))
        }
        override fun onIsPlayingChanged(eventTime: AnalyticsListener.EventTime, isPlaying: Boolean) =
            record("actual_is_playing", mapOf("is_playing" to isPlaying))
        override fun onPlayWhenReadyChanged(eventTime: AnalyticsListener.EventTime, playWhenReady: Boolean, reason: Int) =
            record("play_when_ready", mapOf("play_when_ready" to playWhenReady, "reason" to reason))
        override fun onPlaybackSuppressionReasonChanged(eventTime: AnalyticsListener.EventTime, reason: Int) =
            record("playback_suppressed", mapOf("reason" to reason))
        override fun onRenderedFirstFrame(eventTime: AnalyticsListener.EventTime, output: Any, renderTimeMs: Long) = safely {
            if (!active()) return@safely
            firstFrame = true
            record("actual_rendered_first_frame", mapOf("render_time_ms" to renderTimeMs))
        }
        override fun onVideoDecoderInitialized(eventTime: AnalyticsListener.EventTime, decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long) =
            record("video_decoder_initialized", mapOf("decoder_name" to decoderName.take(160), "initialization_ms" to initializationDurationMs))
        override fun onVideoInputFormatChanged(eventTime: AnalyticsListener.EventTime, format: Format, decoderReuseEvaluation: DecoderReuseEvaluation?) =
            record("video_format", mapOf("mime" to format.sampleMimeType, "codecs" to format.codecs,
                "width" to format.width, "height" to format.height, "bitrate" to format.bitrate, "frame_rate" to format.frameRate))
        override fun onVideoCodecError(eventTime: AnalyticsListener.EventTime, videoCodecError: Exception) =
            record("video_codec_error", error = videoCodecError)
        override fun onAudioCodecError(eventTime: AnalyticsListener.EventTime, audioCodecError: Exception) =
            record("audio_codec_error", error = audioCodecError)
        override fun onDroppedVideoFrames(eventTime: AnalyticsListener.EventTime, droppedFrames: Int, elapsedMs: Long) = safely {
            if (active()) this.droppedFrames += droppedFrames
        }
        override fun onPlayerError(eventTime: AnalyticsListener.EventTime, error: PlaybackException) =
            record("actual_player_error", mapOf("sdkErrorCode" to error.errorCode, "error_name" to error.errorCodeName,
                "position" to JSONObject(snapshot(false))), error)
        override fun onLoadStarted(eventTime: AnalyticsListener.EventTime, loadEventInfo: LoadEventInfo, mediaLoadData: MediaLoadData) =
            load("started", loadEventInfo, mediaLoadData)
        override fun onLoadCompleted(eventTime: AnalyticsListener.EventTime, loadEventInfo: LoadEventInfo, mediaLoadData: MediaLoadData) =
            load("completed", loadEventInfo, mediaLoadData)
        override fun onLoadCanceled(eventTime: AnalyticsListener.EventTime, loadEventInfo: LoadEventInfo, mediaLoadData: MediaLoadData) =
            load("canceled", loadEventInfo, mediaLoadData)
        override fun onLoadError(eventTime: AnalyticsListener.EventTime, loadEventInfo: LoadEventInfo, mediaLoadData: MediaLoadData, error: IOException, wasCanceled: Boolean) =
            load("error", loadEventInfo, mediaLoadData, error, wasCanceled)
        fun load(state: String, info: LoadEventInfo, data: MediaLoadData, error: IOException? = null, canceled: Boolean = false) = safely {
            if (!active()) return@safely
            val fields = mapOf("load_id" to info.loadTaskId, "load_state" to state,
                "host" to info.uri.host?.take(120), "url_hash" to AdPrivacySanitizer.shortHash(info.uri.toString()),
                "original_url_hash" to AdPrivacySanitizer.shortHash(info.dataSpec.uri.toString()),
                "bytes_loaded" to info.bytesLoaded, "load_duration_ms" to info.loadDurationMs,
                "range_position" to info.dataSpec.position, "requested_bytes" to info.dataSpec.length,
                "data_type" to data.dataType, "track_type" to data.trackType, "was_canceled" to canceled,
                "http_status" to (error as? HttpDataSource.InvalidResponseCodeException)?.responseCode,
                "http_failure_phase" to (error as? HttpDataSource.HttpDataSourceException)?.type)
            if (!loads.containsKey(info.loadTaskId) && loads.size >= 8) loads.remove(loads.keys.first())
            loads[info.loadTaskId] = JSONObject(fields + (error?.let(HaierAarPlaybackDiagnostics::throwableDetails) ?: emptyMap()))
            record("media_load_$state", fields, error)
        }
    }

    private fun environment(): Map<String, Any?> = runCatching {
        val connectivity = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val caps = if (Build.VERSION.SDK_INT >= 23) connectivity?.getNetworkCapabilities(connectivity.activeNetwork) else null
        val memory = ActivityManager.MemoryInfo()
        (appContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)?.getMemoryInfo(memory)
        mapOf("actual_device_model" to Build.MODEL, "actual_manufacturer" to Build.MANUFACTURER,
            "actual_sdk_int" to Build.VERSION.SDK_INT, "actual_os_release" to Build.VERSION.RELEASE,
            "screen_interactive" to (appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager)?.isInteractive,
            "network_validated" to caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
            "network_wifi" to caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI),
            "network_ethernet" to caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET),
            "network_cellular" to caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR),
            "available_memory_bytes" to memory.availMem, "low_memory" to memory.lowMemory,
            "free_storage_bytes" to appContext.filesDir.usableSpace)
    }.getOrElse { mapOf("environment_error" to it.javaClass.name) }

    private fun exitRecord(pid: Int, checkpointAt: Long): JSONObject? = runCatching {
        if (Build.VERSION.SDK_INT < 30) return@runCatching JSONObject(mapOf("available" to false, "reason" to "api_below_30"))
        val manager = appContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val item = manager?.getHistoricalProcessExitReasons(appContext.packageName, pid, 4)
            ?.firstOrNull { it.timestamp >= checkpointAt - 60_000 }
        if (item == null) JSONObject(mapOf("available" to false)) else JSONObject(mapOf(
            "available" to true, "reason_code" to item.reason, "status" to item.status, "timestamp_ms" to item.timestamp))
    }.getOrElse { JSONObject(mapOf("available" to false, "error_type" to it.javaClass.name)) }

    private fun findVideo(group: ViewGroup, depth: Int = 0): VideoView? {
        if (depth > 5) return null
        for (i in 0 until minOf(group.childCount, 16)) {
            val child = group.getChildAt(i)
            if (child is VideoView) return child
            if (child is ViewGroup) findVideo(child, depth + 1)?.let { return it }
        }
        return null
    }
    private inline fun safely(action: () -> Unit) { runCatching(action) }
}
