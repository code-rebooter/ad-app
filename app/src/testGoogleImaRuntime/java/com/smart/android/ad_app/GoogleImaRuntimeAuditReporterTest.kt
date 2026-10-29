package com.smart.android.ad_app

import com.google.common.io.BaseEncoding
import com.google.gson.JsonParser
import com.smart.android.googleima.GoogleImaAuditEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.util.zip.GZIPInputStream

class GoogleImaRuntimeAuditReporterTest {
    @Test
    fun `vast verification and omid runtime are correlated in one summary`() {
        val reporter = GoogleImaRuntimeAuditReporter("request-1")
        val verificationUrl = "https://verify.example/omid.js"
        val vastXml = """
            <VAST version="4.1">
              <Ad><Wrapper>
                <AdVerifications>
                  <Verification vendor="vendor-a">
                    <JavaScriptResource apiFramework="omid" browserOptional="false">$verificationUrl</JavaScriptResource>
                    <VerificationParameters>parameter-a</VerificationParameters>
                    <TrackingEvents>
                      <Tracking event="verificationNotExecuted">https://verify.example/not-executed</Tracking>
                    </TrackingEvents>
                  </Verification>
                </AdVerifications>
              </Wrapper></Ad>
            </VAST>
        """.trimIndent()
        reporter.record(
            event(
                "vast_response",
                10L,
                mapOf(
                    "capture_source" to "webview_fetch",
                    "response_index" to "1",
                    "request_id" to "ima-1",
                    "response_id" to "ima-1",
                    "request_url" to "https://ads.example/root",
                    "request_type" to "GET",
                    "content_type" to "application/xml",
                    "error_code" to "0"
                ),
                vastXml
            )
        )
        reporter.record(event("web_resource", 20L, mapOf("phase" to "request", "url" to verificationUrl)))
        reporter.record(event("web_resource", 25L, mapOf("phase" to "load", "url" to verificationUrl)))
        reporter.record(event("omid_bridge", 30L, mapOf("method" to "init")))
        reporter.record(event("omid_bridge", 40L, mapOf("method" to "startSession")))
        reporter.record(
            event(
                "omid_bridge",
                100L,
                mapOf(
                    "method" to "setNativeViewHierarchy",
                    "hierarchy_length" to "100",
                    "hierarchy_sha256" to "hash-1",
                    "not_visible_reason_present" to "false",
                    "window_focus_false_present" to "false"
                )
            )
        )
        reporter.record(
            event(
                "omid_bridge",
                300L,
                mapOf(
                    "method" to "setNativeViewHierarchy",
                    "hierarchy_length" to "120",
                    "hierarchy_sha256" to "hash-2",
                    "not_visible_reason_present" to "false",
                    "window_focus_false_present" to "false"
                )
            )
        )
        reporter.record(event("omid_bridge", 320L, mapOf("method" to "finishSession")))

        val summaryMethod = GoogleImaRuntimeAuditReporter::class.java
            .getDeclaredMethod("buildSummary", String::class.java)
            .apply { isAccessible = true }
        val summary = JsonParser.parseString(
            summaryMethod.invoke(reporter, "completed") as String
        ).asJsonObject

        assertEquals(1, summary["vast_response_count"].asInt)
        assertEquals(1, summary["web_vast_response_count"].asInt)
        assertEquals(0, summary["native_xhr_response_count"].asInt)
        val response = summary["vast_responses"].asJsonArray[0].asJsonObject
        assertEquals("webview_fetch", response["capture_source"].asString)
        assertEquals(1, response["wrapper_count"].asInt)
        assertEquals(1, response["verification_script_count"].asInt)

        val artifact = summary["vast_artifacts"].asJsonArray[0].asJsonObject
        assertEquals("ima_vast_xml", artifact["artifact_type"].asString)
        assertEquals("vast-response-1", artifact["artifact_id"].asString)
        assertEquals("gzip+base64", artifact["encoding"].asString)
        assertEquals(vastXml.toByteArray(Charsets.UTF_8).size, artifact["original_length"].asInt)
        assertEquals(sha256(vastXml), artifact["original_sha256"].asString)
        assertEquals(vastXml, gunzipBase64(artifact["content"].asString))

        val script = summary["verification_scripts"].asJsonArray[0].asJsonObject
        assertEquals("vendor-a", script["vendor"].asString)
        assertEquals("omid", script["api_framework"].asString)
        assertTrue(script["request_observed"].asBoolean)
        assertTrue(script["load_callback_observed"].asBoolean)

        val omid = summary["omid_runtime_audit"].asJsonObject
        assertTrue(omid["session_started"].asBoolean)
        assertTrue(omid["session_finished"].asBoolean)
        assertEquals(2, omid["hierarchy_submission_count"].asInt)
        assertEquals(200L, omid["hierarchy_max_gap_ms"].asLong)
    }

    @Test
    fun `oversized ima audit remains in the single terminal trace`() {
        val session = Hq008ConsentTraceSession(requestIdFactory = { "request-oversized" })
        fun add(type: String, adLog: String? = null) = session.record(
            nowMs = 100L,
            eventType = type,
            rawEventMessage = "",
            eventMessage = "",
            adLog = adLog
        )
        add("CMP_GATE_START")
        add("AD_PHASE_START")
        val artifactContent = "x".repeat(300_000)
        add(
            "IMA_RUNTIME_AUDIT",
            "{\"vast_artifacts\":[{\"encoding\":\"gzip+base64\",\"content\":\"$artifactContent\"}]}"
        )
        val snapshot = add("AD_PHASE_COMPLETED")!!
        val build = Hq008ConsentLogReporter::class.java.getDeclaredMethod(
            "buildUploadPayload",
            Hq008ConsentTraceSession.TraceSnapshot::class.java
        ).apply { isAccessible = true }
        val payload = build.invoke(Hq008ConsentLogReporter, snapshot)
        val field = payload.javaClass.getDeclaredField("adLog").apply { isAccessible = true }
        val upload = JsonParser.parseString(field.get(payload) as String).asJsonObject
        assertEquals("critical_ad_log_only", upload["traceCompactedMode"].asString)
        val auditStep = upload["steps"].asJsonArray
            .map { it.asJsonObject }
            .first { it["eventType"].asString == "IMA_RUNTIME_AUDIT" }
        assertEquals(
            artifactContent.length,
            auditStep["adLog"].asJsonObject["vast_artifacts"].asJsonArray[0]
                .asJsonObject["content"].asString.length
        )
    }

    private fun gunzipBase64(value: String): String {
        val compressed = BaseEncoding.base64().decode(value)
        return GZIPInputStream(ByteArrayInputStream(compressed))
            .bufferedReader(Charsets.UTF_8)
            .use { it.readText() }
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private fun event(
        type: String,
        elapsedMs: Long,
        fields: Map<String, String>,
        payload: String? = null
    ): GoogleImaAuditEvent = GoogleImaAuditEvent(type, elapsedMs, fields, payload)
}
