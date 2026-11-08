package com.smart.android.tclad.ima

import android.os.SystemClock
import android.os.Handler
import android.os.Looper

/** App business transports remain host callbacks; all IMA playback state stays in this module. */
internal class GoogleImaHost(
    val execution: GoogleImaExecutionConfig,
    private val listener: GoogleImaSessionListener
) {
    private val main = Handler(Looper.getMainLooper())
    val reporter get() = this
    val userAgent get() = GoogleImaFirmwareBridge.getEffectiveUserAgent()
    fun isHiddenMode() = execution.isHiddenMode
    fun markActiveStage(requestId: String, stage: String) {
        report("IMA_ACTIVE_STAGE", stage, requestId = requestId)
    }
    fun extendActiveForPlayback(requestId: String, remainingMs: Long): Boolean {
        listener.onPlaybackDeadline(requestId, SystemClock.elapsedRealtime() + remainingMs)
        return true
    }
    fun report(eventType: String, eventMessage: String, adLog: String? = null, requestId: String? = null) {
        if (execution.isAuditEnabled) {
            val deliver = { listener.onTrace(requestId ?: execution.requestId, eventType, eventMessage, adLog) }
            if (Looper.myLooper() == Looper.getMainLooper()) deliver() else main.post { deliver() }
        }
    }
    fun onAudit(summary: String) {
        if (execution.isAuditEnabled) listener.onAdAudit(execution.requestId, summary)
    }
    fun reportRequested(id: String, adId: String?, hidden: Boolean, width: Int, height: Int, diagnostics: Map<String, Any?>) {
        event(id, "AD_REQUESTED", "REQUESTED", diagnostics + mapOf("adId" to adId,
            "hiddenMode" to hidden, "containerWidth" to width, "containerHeight" to height))
    }
    fun reportLoaded(id: String, adId: String?, hidden: Boolean, diagnostics: Map<String, Any?>) {
        event(id, "AD_LOADED", "LOADED", diagnostics + mapOf("adId" to adId, "hiddenMode" to hidden))
    }
    fun reportStarted(id: String, adId: String?, hidden: Boolean, duration: Double?, diagnostics: Map<String, Any?>) {
        event(id, "AD_STARTED", "STARTED", diagnostics + mapOf("adId" to adId,
            "hiddenMode" to hidden, "startProgress" to duration))
    }
    fun reportFilled(id: String, adId: String?, hidden: Boolean, diagnostics: Map<String, Any?>) {
        event(id, "AD_FILLED", "FILLED", diagnostics + mapOf("adId" to adId, "hiddenMode" to hidden))
    }
    fun reportCompleted(id: String, adId: String?, hidden: Boolean, diagnostics: Map<String, Any?>) {
        event(id, "AD_COMPLETED", "COMPLETED", diagnostics + mapOf("adId" to adId, "hiddenMode" to hidden))
    }
    fun reportError(id: String, adId: String?, hidden: Boolean, code: Int?, message: String, diagnostics: Map<String, Any?>) {
        event(id, "AD_ERROR", message, diagnostics + mapOf("adId" to adId, "hiddenMode" to hidden, "errorCode" to code))
    }
    fun event(id: String, type: String, message: String, diagnostics: Map<String, Any?>) {
        listener.onAdReport(id, type, message, execution.patchParameters + diagnostics + mapOf("requestId" to id,
            "requestChannelId" to execution.channelId, "sdk" to "google_ima_3.35.1"))
    }
}
