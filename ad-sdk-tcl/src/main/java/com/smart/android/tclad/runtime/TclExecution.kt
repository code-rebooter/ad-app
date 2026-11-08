package com.smart.android.tclad.runtime

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.smart.android.tclad.AdStatusListener
import com.smart.android.tclad.ExecutionConfig
import com.smart.android.tclad.internal.OriginalBoundary
import java.util.UUID

/** App playback lifecycle, with host reporting supplied through the SDK listener. */
class TclExecution(
    config: ExecutionConfig?, private val listener: AdStatusListener, volume: Float,
    private val releaseNative: Runnable, private val onClosed: Runnable
) {
    private val main = Handler(Looper.getMainLooper())
    private val state = TclPlaybackState(SystemClock.elapsedRealtime(), config?.callbackTimeoutMs ?: 180_000L)
    private var terminal = false
    private var nativeReleased = false
    private var completionPending = false
    private var loaded = false
    private var started = false
    private var lastSnapshotMs = 0L
    private var guardDeadlineMs = -1L
    private val session = TclRuntimeBridge.create(config?.requestId ?: UUID.randomUUID().toString(),
        config?.channelId.orEmpty(), volume > 0f, state, config,
        onRequestResult = { fields ->
            if (Looper.myLooper() == Looper.getMainLooper()) listener.onAdRequested(fields)
            else main.post { listener.onAdRequested(fields) }
        },
        onAudit = { listener.onAdAudit(it) },
        listener = { event -> main.post { if (!terminal && !completionPending) onEvent(event) } })
    private val finishCompletion = Runnable { finish("completed", null) }
    private val watchdog = object : Runnable {
        override fun run() {
            if (terminal || completionPending) return
            val now = SystemClock.elapsedRealtime()
            val snapshot = TclRuntimeBridge.snapshot(session)
            if (snapshot["player_available"] == true) {
                if (snapshot["creative_id"] != null) state.select(metadata(snapshot), now)
                state.snapshot(now, number(snapshot, "position_ms"), number(snapshot, "duration_ms"),
                    snapshot["is_playing"] == true, snapshot["is_preparing"] == true,
                    number(snapshot, "media_index").toInt())
                if (state.startedAtMs != null) observedStart("media_position")
                if (isClosed()) return
                extendDeadline()
                if (isClosed()) return
            }
            if (now - lastSnapshotMs >= AdPlaybackPolicy.PLAYBACK_PROGRESS_INTERVAL_MS) {
                lastSnapshotMs = now
                session.audit.record("player_snapshot", now, snapshot)
            }
            state.timeoutReason(now)?.let { fail(901, it); return }
            main.postDelayed(this, 1_000L)
        }
    }
    init { main.post(watchdog) }
    fun isClosed(): Boolean = terminal || completionPending
    fun starting(action: Runnable) {
        if (!isClosed()) TclRuntimeBridge.starting(session) { action.run() }
    }
    fun loaded(deliver: Runnable) {
        if (isClosed() || loaded) return
        loaded = true
        deliver.run()
    }
    fun started(seconds: Double?) {
        if (isClosed() || started) return
        state.markStarted(SystemClock.elapsedRealtime())
        started = true
        if (seconds == null) listener.onAdStartPlay() else listener.onAdStartPlay(seconds)
        if (isClosed()) return
        extendDeadline()
    }
    fun completed() {
        if (isClosed()) return
        completionPending = true
        main.removeCallbacks(watchdog)
        releaseNativeOnce()
        if (terminal) return
        main.postDelayed(finishCompletion, 500L)
    }
    fun fail(code: Int, reason: String?) {
        if (isClosed()) return
        finish(reason ?: session.failureReason(loaded, started), code)
    }
    fun cancel() {
        if (terminal) return
        if (completionPending) finish("completed", null) else finish("cancelled", null)
    }
    fun addressAttempt() { session.sdkRequest.begin(SystemClock.elapsedRealtime()) }
    fun addressResponse(status: Int) {
        session.sdkRequest.responded(SystemClock.elapsedRealtime(), status)
        session.publishRequestResult()
    }
    fun addressFailure(message: String) { session.sdkRequest.failed(message) }
    private fun finish(reason: String, code: Int?) {
        if (terminal) return
        terminal = true
        main.removeCallbacks(watchdog)
        main.removeCallbacks(finishCompletion)
        val now = SystemClock.elapsedRealtime()
        releaseNativeOnce()
        state.finish(now, reason)
        TclRuntimeBridge.close(session, reason, state.diagnostics(now))
        onClosed.run()
        listener.onAdDiagnostics(state.diagnostics(now) + session.sdkRequest.diagnostics() + mapOf(
            "requestId" to session.id, "requestChannelId" to session.channelId,
            "terminalReason" to reason, "filled" to session.filled, "tagReady" to session.tagReady,
            "loaded" to loaded, "started" to started, "patchError" to session.patchError,
            "lastDownloadError" to session.lastDownloadError, "lastMaterialParseError" to session.lastMaterialParseError))
        when {
            reason == "completed" -> listener.onAdFinished()
            code != null -> listener.onAdError(code)
            else -> listener.onAdCancelled()
        }
    }
    private fun releaseNativeOnce() {
        if (nativeReleased) return
        nativeReleased = true
        session.audit.record("player_snapshot", SystemClock.elapsedRealtime(),
            TclRuntimeBridge.snapshot(session) + mapOf("trigger" to "before_release"))
        TclRuntimeBridge.stopNetwork(session)
        releaseNative.run()
    }

    private fun onEvent(event: TclRuntimeEvent) {
        listener.onAdExecutionEvent(event.type, event.fields)
        // Host callbacks can synchronously cancel this controller or begin its replacement.
        if (isClosed()) return
        when (event.type) {
            "patch_failed" -> fail(902, "gam_patch_failed")
            "filled" -> listener.onAdFilled()
            "material_loaded", "material_selected" -> {
                if (event.type == "material_selected" || state.current == null) {
                    state.select(metadata(event.fields), event.atMs)
                    extendDeadline()
                }
            }
            "first_frame" -> {
                val snapshot = TclRuntimeBridge.snapshot(session)
                if (snapshot["creative_id"] != null) state.select(metadata(snapshot), event.atMs)
                state.firstFrame(event.atMs)
                observedStart("native_first_frame")
                extendDeadline()
            }
            "buffering" -> state.buffering(event.atMs)
            "video_started" -> state.playing(event.atMs)
            "sdk_event" -> if (event.fields["native_event"]?.toString() == "COMPLETED") {
                state.completeSegment("COMPLETED", event.atMs)
                listener.onPlaybackDeadline(SystemClock.elapsedRealtime() +
                    AdPlaybackPolicy.AD_POD_TRANSITION_TIMEOUT_MS + AdPlaybackPolicy.AD_BREAK_COMPLETION_GRACE_MS)
            }
        }
    }
    private fun observedStart(source: String) {
        if (isClosed() || started) return
        session.event("observed_start", mapOf("source" to source))
        started(null)
    }
    private fun extendDeadline() {
        if (isClosed()) return
        val deadline = state.current?.budget?.hardDeadlineMs ?: return
        if (state.startedAtMs == null || deadline == guardDeadlineMs) return
        guardDeadlineMs = deadline
        session.deadlineMs = deadline
        listener.onPlaybackDeadline(deadline)
    }
    private fun metadata(fields: Map<String, Any?>) = TclPlaybackState.Metadata(
        key = "${fields["media_url_hash"]}|${fields["creative_id"]}|${fields["ad_position"]}",
        creativeId = fields["creative_id"]?.toString().orEmpty(),
        mediaUrlHash = fields["media_url_hash"]?.toString().orEmpty(),
        adPosition = number(fields, "ad_position").toInt(), totalAds = number(fields, "total_ads").toInt(),
        podMaxDurationMs = number(fields, "pod_max_duration_ms"))
    private fun number(fields: Map<String, Any?>, key: String) = (fields[key] as? Number)?.toLong() ?: -1L

    companion object {
        @JvmStatic fun installHttpInterceptors() {
            val requester = com.tcl.ff.component.overseahttp.http.HttpRequester.get()
            val client = OriginalBoundary.field(requester, requester.javaClass, "c") as okhttp3.OkHttpClient
            if (client.interceptors.any { it is TclSdkRequestInterceptor }) return
            val observed = client.newBuilder().addInterceptor(TclRuntimeBridge.httpInterceptor)
                .addInterceptor(TclSdkRequestInterceptor()).addNetworkInterceptor(TclSdkResponseInterceptor()).build()
            OriginalBoundary.field(requester, requester.javaClass, "c", observed)
        }
    }
}
