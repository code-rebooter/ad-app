package com.smart.android.ad_app

import java.util.UUID

internal class Hq008ConsentTraceSession(
    private val maxTraceSteps: Int = 80,
    private val requestIdFactory: () -> String = {
        "client-${System.currentTimeMillis()}-${UUID.randomUUID().toString().substring(0, 8)}"
    }
) {
    data class TraceStep(
        val index: Int,
        val elapsedMs: Long,
        val eventType: String,
        val rawEventMessage: String,
        val eventMessage: String,
        val adLog: String?
    )

    data class TraceSnapshot(
        val finalEventType: String,
        val steps: List<TraceStep>,
        val milestones: List<TraceStep>,
        val observedEventTypes: Set<String>,
        val trimmedStepCount: Int,
        val popupLogEnabled: Boolean,
        val requestId: String
    )

    private val pendingSteps = mutableListOf<TraceStep>()
    private var traceStartedElapsedMs: Long = 0L
    private var popupLogEnabled = true
    private var requestId = ""
    private val requestIdAliases = mutableSetOf<String>()
    private val milestones = linkedMapOf<String, TraceStep>()
    private val observedEventTypes = linkedSetOf<String>()
    private var trimmedStepCount = 0

    fun activeRequestId(): String = requestId

    /** A late authorize callback must never rename a newer flow. */
    fun bindAuthorizationRequestId(localRequestId: String, resolvedRequestId: String) {
        if (ownsRequest(localRequestId) && resolvedRequestId.isNotBlank()) {
            requestIdAliases += resolvedRequestId
            requestId = resolvedRequestId
        }
    }

    fun ownsRequest(expectedRequestId: String): Boolean =
        pendingSteps.isNotEmpty() && expectedRequestId.isNotBlank() && expectedRequestId in requestIdAliases

    fun updatePopupLogEnabled(enabled: Boolean, expectedRequestId: String? = null): Boolean {
        if (expectedRequestId != null && !ownsRequest(expectedRequestId)) return false
        popupLogEnabled = enabled
        return true
    }

    fun isPopupLogEnabled(): Boolean = popupLogEnabled

    fun resetPopupLogEnabled() {
        popupLogEnabled = true
    }

    fun hasPendingSteps(): Boolean = pendingSteps.isNotEmpty()

    fun record(
        nowMs: Long,
        eventType: String,
        rawEventMessage: String,
        eventMessage: String,
        adLog: String?,
        expectedRequestId: String? = null
    ): TraceSnapshot? {
        val owner = expectedRequestId ?: REQUEST_ID_PATTERN.find(rawEventMessage)?.groupValues?.get(1)
        if (owner != null && !ownsRequest(owner)) return null
        // Polling while an ad is playing must not replace its trace or request ID.
        if (eventType == "FLOATING_FLOW_SKIPPED" && rawEventMessage.contains("reason=in_flight")) return null
        if (!shouldTrackEvent(eventType)) {
            return null
        }

        if (eventType == "CMP_GATE_START" ||
            (eventType == "FLOATING_FLOW_SKIPPED" && pendingSteps.isEmpty())) {
            resetAll()
            requestId = requestIdFactory()
            requestIdAliases += requestId
        } else if (pendingSteps.isEmpty()) {
            return null
        }

        appendStep(
            nowMs = nowMs,
            eventType = eventType,
            rawEventMessage = rawEventMessage,
            eventMessage = eventMessage,
            adLog = adLog
        )

        if (!isTerminalEvent(eventType)) {
            return null
        }
        if (eventType == "AUTHORIZE_ALLOWED" && !hasAdPhaseStarted()) {
            return null
        }
        return buildSnapshotAndReset(eventType)
    }

    fun forceFinish(
        nowMs: Long,
        eventType: String,
        rawEventMessage: String,
        eventMessage: String,
        adLog: String?,
        expectedRequestId: String? = null
    ): TraceSnapshot? {
        if (pendingSteps.isEmpty() ||
            (expectedRequestId != null && !ownsRequest(expectedRequestId))) return null
        appendStep(
            nowMs = nowMs,
            eventType = eventType,
            rawEventMessage = rawEventMessage,
            eventMessage = eventMessage,
            adLog = adLog
        )
        return buildSnapshotAndReset(eventType)
    }

    private fun shouldTrackEvent(eventType: String): Boolean {
        return pendingSteps.isNotEmpty() ||
            eventType == "CMP_GATE_START" ||
            eventType == "FLOATING_FLOW_SKIPPED"
    }

    private fun appendStep(
        nowMs: Long,
        eventType: String,
        rawEventMessage: String,
        eventMessage: String,
        adLog: String?
    ) {
        if (pendingSteps.isEmpty()) {
            traceStartedElapsedMs = nowMs
        }
        val step = TraceStep(
            index = pendingSteps.size + 1,
            elapsedMs = nowMs - traceStartedElapsedMs,
            eventType = eventType,
            rawEventMessage = rawEventMessage,
            eventMessage = eventMessage,
            adLog = adLog?.takeIf { it.isNotBlank() }
        )
        pendingSteps += step
        observedEventTypes += eventType
        if (eventType in milestoneEventTypes) milestones[eventType] = step
        trimSteps()
    }

    private fun trimSteps() {
        if (pendingSteps.size <= maxTraceSteps) {
            return
        }
        val first = pendingSteps.first()
        val tail = pendingSteps.takeLast(maxTraceSteps - 1)
        trimmedStepCount += pendingSteps.size - maxTraceSteps
        pendingSteps.clear()
        pendingSteps += first.copy(
            rawEventMessage = first.rawEventMessage.markTrimmed(),
            eventMessage = first.eventMessage.markTrimmed()
        )
        pendingSteps += tail.mapIndexed { index, step -> step.copy(index = index + 2) }
    }

    private fun hasAdPhaseStarted(): Boolean {
        return "AD_PHASE_START" in observedEventTypes
    }

    private fun isTerminalEvent(eventType: String): Boolean {
        return eventType == "CMP_GATE_STOP" ||
            eventType == "AUTHORIZE_ALLOWED" ||
            eventType == "AUTHORIZE_DENIED" ||
            eventType == "AUTHORIZE_CALLBACK_FAIL" ||
            eventType == "AUTHORIZE_CALLBACK_EMPTY" ||
            eventType == "AD_PHASE_COMPLETED" ||
            eventType == "AD_PHASE_ERROR" ||
            eventType == "AD_PHASE_TIMEOUT" ||
            eventType == "AD_PHASE_CANCELLED" ||
            eventType == "AD_PHASE_SKIPPED" ||
            eventType == "FLOATING_FLOW_SKIPPED" ||
            eventType == "FLOW_GUARD_FINISH"
    }

    private fun buildSnapshotAndReset(finalEventType: String): TraceSnapshot {
        val snapshot = TraceSnapshot(
            finalEventType = finalEventType,
            steps = pendingSteps.toList(),
            milestones = milestones.values.toList(),
            observedEventTypes = observedEventTypes.toSet(),
            trimmedStepCount = trimmedStepCount,
            popupLogEnabled = popupLogEnabled,
            requestId = requestId
        )
        resetAll()
        return snapshot
    }

    private fun resetStepsOnly() {
        pendingSteps.clear()
        traceStartedElapsedMs = 0L
        requestId = ""
        requestIdAliases.clear()
        milestones.clear()
        observedEventTypes.clear()
        trimmedStepCount = 0
    }

    private companion object {
        val REQUEST_ID_PATTERN = Regex("(?:^|[,\\s])(?:requestId|request_id)=([^,\\s]+)")
        val milestoneEventTypes = setOf(
            "AD_PHASE_START",
            "AD_REQUESTED",
            "AD_LOADED",
            "AD_STARTED",
            "AD_FIRST_FRAME",
            "AD_PLAYBACK_EVIDENCE",
            "AD_PHASE_COMPLETED",
            "AD_PHASE_ERROR",
            "AD_PHASE_TIMEOUT",
            "AD_PHASE_CANCELLED",
            "AD_PHASE_SKIPPED",
            "FLOW_GUARD_FINISH",
            "UMP_STATUS_AFTER_AUTHORIZED"
        )
    }

    private fun String.markTrimmed(): String = if (endsWith(" [已裁剪]")) this else "$this [已裁剪]"

    private fun resetAll() {
        resetStepsOnly()
        popupLogEnabled = true
    }
}
