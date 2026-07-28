package com.smart.android.ad_app

import android.util.Base64
import com.smart.android.ad_app.AdLocalLog as Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.zip.GZIPOutputStream

internal object HaierAarAuditUploader {
    private const val TAG = "HaierAarAudit"
    private const val PLAIN_PAYLOAD_LIMIT = 120_000
    private const val MAX_PENDING_EVENTS = 160
    private val lock = Any()
    private val pendingEvents = ArrayDeque<String>()
    private var activeFlow: FlowAudit? = null

    fun enqueue(event: HaierAarAuditEvent, critical: Boolean = false) {
        val payload = event.toJson()
        Log.i(TAG, "AAR请求审计：${event.eventType} $payload")
        synchronized(lock) {
            val flow = activeFlow
            if (flow != null) {
                flow.events += payload
                if (critical) flow.criticalEventCount += 1
            } else {
                while (pendingEvents.size >= MAX_PENDING_EVENTS) {
                    pendingEvents.removeFirst()
                    Log.w(TAG, "AAR请求审计：启动阶段缓存已满，移除最早一条记录")
                }
                pendingEvents.addLast(payload)
            }
        }
    }

    fun beginFlow(requestId: String) {
        synchronized(lock) {
            val existing = activeFlow
            if (existing?.requestId == requestId) return
            if (existing != null && existing.events.isNotEmpty()) {
                existing.events.forEach(::appendPendingLocked)
                Log.w(
                    TAG,
                    "AAR请求审计：上一轮未正常收口，已并入待关联缓存，requestId=${existing.requestId}"
                )
            }
            activeFlow = FlowAudit(
                requestId = requestId,
                startedAtMs = System.currentTimeMillis(),
                events = pendingEvents.toMutableList()
            )
            pendingEvents.clear()
            Log.i(TAG, "AAR请求审计：开始聚合单轮广告网络日志，requestId=$requestId")
        }
    }

    fun appendFlowCaptureToConsentLog(requestId: String, terminalReason: String) {
        val flow = synchronized(lock) {
            val current = activeFlow
            if (current == null || current.requestId != requestId) {
                null
            } else {
                activeFlow = null
                current
            }
        }
        if (flow == null) {
            Log.w(TAG, "AAR请求审计：未找到待收口的广告网络日志，requestId=$requestId")
            return
        }

        val payload = buildFlowPayload(flow, terminalReason)
        Hq008ConsentLogReporter.report(
            eventType = "AD_SDK_HTTP_CAPTURE",
            eventMessage = "requestId=$requestId,eventCount=${flow.events.size},criticalEventCount=${flow.criticalEventCount},terminalReason=$terminalReason",
            adLog = payload
        )
        Log.i(
            TAG,
            "AAR请求审计：已合并到本轮 consent-log-report，requestId=$requestId，eventCount=${flow.events.size}，payloadLength=${payload.length}"
        )
    }

    private fun buildFlowPayload(flow: FlowAudit, terminalReason: String): String {
        val events = JSONArray()
        flow.events.forEach { raw ->
            events.put(runCatching { JSONObject(raw) }.getOrElse { raw })
        }
        val plainPayload = JSONObject().apply {
            put("schema_version", 1)
            put("request_id", flow.requestId)
            put("started_at_ms", flow.startedAtMs)
            put("finished_at_ms", System.currentTimeMillis())
            put("terminal_reason", terminalReason)
            put("event_count", flow.events.size)
            put("critical_event_count", flow.criticalEventCount)
            put("sdk_network_logs", events)
        }.toString()
        if (plainPayload.length <= PLAIN_PAYLOAD_LIMIT) return plainPayload

        val rawBytes = plainPayload.toByteArray(Charsets.UTF_8)
        val compressed = ByteArrayOutputStream().use { output ->
            GZIPOutputStream(output).use { it.write(rawBytes) }
            output.toByteArray()
        }
        val encoded = Base64.encodeToString(compressed, Base64.NO_WRAP)
        val sha256 = MessageDigest.getInstance("SHA-256")
            .digest(rawBytes)
            .joinToString("") { "%02x".format(it) }
        return JSONObject().apply {
            put("schema_version", 1)
            put("request_id", flow.requestId)
            put("terminal_reason", terminalReason)
            put("event_count", flow.events.size)
            put("critical_event_count", flow.criticalEventCount)
            put("encoding", "gzip+base64")
            put("original_length", rawBytes.size)
            put("original_sha256", sha256)
            put("sdk_network_logs_compressed", encoded)
        }.toString()
    }

    private fun appendPendingLocked(payload: String) {
        while (pendingEvents.size >= MAX_PENDING_EVENTS) {
            pendingEvents.removeFirst()
        }
        pendingEvents.addLast(payload)
    }

    private data class FlowAudit(
        val requestId: String,
        val startedAtMs: Long,
        val events: MutableList<String>,
        var criticalEventCount: Int = 0
    )
}
