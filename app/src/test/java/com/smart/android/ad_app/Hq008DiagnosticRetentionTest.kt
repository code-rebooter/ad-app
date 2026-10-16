package com.smart.android.ad_app

import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class Hq008DiagnosticRetentionTest {
    @Test
    fun `large HTTP capture keeps the diagnostic summaries in the upload`() {
        val session = Hq008ConsentTraceSession()
        fun add(type: String, message: String = "", json: String? = null) =
            session.record(100L, type, message, message, json)
        add("CMP_GATE_START")
        add("AD_PHASE_START")
        repeat(100) { add("AD_SDK_DIAGNOSTIC", "stage=ordinary", "{\"index\":$it}") }
        add("AD_SDK_HTTP_CAPTURE", json = "{\"large\":\"${"x".repeat(300_000)}\"}")
        add("AD_SDK_DIAGNOSTIC", "requestId=test,stage=playback_probe_summary",
            "{\"stage\":\"playback_probe_summary\",\"position_ms\":2400}")
        add("AD_SDK_DIAGNOSTIC", "requestId=test,stage=diagnostic_evidence_summary",
            "{\"stage\":\"diagnostic_evidence_summary\",\"root\":\"SocketTimeoutException\"}")
        val snapshot = add("AD_PHASE_ERROR")!!
        assertEquals(80, snapshot.steps.size)
        val build = Hq008ConsentLogReporter.javaClass.getDeclaredMethod(
            "buildUploadPayload", Hq008ConsentTraceSession.TraceSnapshot::class.java
        ).apply { isAccessible = true }
        val payload = build.invoke(Hq008ConsentLogReporter, snapshot)
        val field = payload.javaClass.getDeclaredField("adLog").apply { isAccessible = true }
        val json = field.get(payload) as String
        assertTrue(json.length < 256_000)
        val parsed = JsonParser.parseString(json).asJsonObject
        assertEquals("diagnostic_summaries_only", parsed["traceCompactedMode"].asString)
        val retained = parsed["steps"].asJsonArray.map { it.asJsonObject }
            .filter { it.has("adLog") }.map { it["adLog"].asJsonObject }
        assertEquals(2, retained.size)
        assertTrue(retained.any { it["position_ms"]?.asInt == 2400 })
        assertTrue(retained.any { it["root"]?.asString == "SocketTimeoutException" })
    }
}
