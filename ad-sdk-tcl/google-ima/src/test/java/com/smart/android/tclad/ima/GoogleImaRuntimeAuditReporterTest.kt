package com.smart.android.tclad.ima

import com.google.common.io.BaseEncoding
import com.google.gson.JsonParser
import com.smart.android.googleima.GoogleImaAuditEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.util.zip.GZIPInputStream

class GoogleImaRuntimeAuditReporterTest {
    @Test fun `wrapper and inline without media cannot confirm fill`() {
        val reporter = GoogleImaRuntimeAuditReporter("fill") {}
        reporter.record(event("vast_response", 1, emptyMap(),
            "<VAST><Ad><Wrapper><VASTAdTagURI>https://example.com/inner</VASTAdTagURI></Wrapper></Ad></VAST>"))
        assertFalse(reporter.hasVastFill())
        reporter.record(event("vast_response", 2, emptyMap(),
            "<VAST><Ad><InLine><Creatives/></InLine></Ad></VAST>"))
        assertFalse(reporter.hasVastFill())
        reporter.record(event("vast_response", 3, emptyMap(),
            "<VAST><Ad><InLine><Creatives><Creative><Linear><MediaFiles><MediaFile>https://example.com/video.mp4</MediaFile></MediaFiles></Linear></Creative></Creatives></InLine></Ad></VAST>"))
        assertTrue(reporter.hasVastFill())
    }
    @Test
    fun `vast verification and omid runtime are correlated in one summary`() {
        val reporter = GoogleImaRuntimeAuditReporter("request-1") {}
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
        reporter.record(
            event(
                "ima_ad_event",
                330L,
                mapOf(
                    "ima_event" to "COMPLETED",
                    "ad_duration_ms" to "15000",
                    "pod_max_duration_ms" to "15000",
                    "total_ads" to "1",
                    "ad_position" to "1",
                    "pod_index" to "0"
                )
            )
        )
        reporter.record(
            event(
                "ima_ad_event",
                340L,
                mapOf(
                    "ima_event" to "ALL_ADS_COMPLETED",
                    "ad_duration_ms" to "-1",
                    "pod_max_duration_ms" to "-1",
                    "total_ads" to "-1",
                    "ad_position" to "-1",
                    "pod_index" to "-1"
                )
            )
        )

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
        val playback = summary["ima_playback_audit"].asJsonObject
        assertEquals("ALL_ADS_COMPLETED", playback["last_ima_event"].asString)
        assertEquals(15_000L, playback["last_ad_duration_ms"].asLong)
        assertEquals(1, playback["last_total_ads"].asInt)
        assertEquals(1, playback["last_ad_position"].asInt)
        assertEquals(0, playback["last_pod_index"].asInt)
        assertEquals(1.0, playback["ad_progress_ratio"].asDouble, 0.0)
    }

    @Test
    fun `network omid sessions and ad player snapshot remain inspectable`() {
        val reporter = GoogleImaRuntimeAuditReporter("request-detail") {}
        reporter.record(
            event(
                "native_xhr_response",
                10L,
                mapOf(
                    "response_index" to "2",
                    "request_id" to "ima-request",
                    "response_id" to "ima-response",
                    "request_url" to "https://ads.example/signal",
                    "request_type" to "POST",
                    "content_type" to "application/json",
                    "error_code" to "0",
                    "content_length" to "27",
                    "content_sha256" to "response-hash"
                )
            )
        )
        reporter.record(
            event(
                "web_resource",
                20L,
                mapOf(
                    "phase" to "request",
                    "url" to "https://verify.example/omid.js",
                    "http_method" to "GET",
                    "main_frame" to "false"
                )
            )
        )
        reporter.record(
            event(
                "web_resource",
                25L,
                mapOf(
                    "phase" to "http_error",
                    "url" to "https://verify.example/omid.js",
                    "error_code" to "503",
                    "error" to "Service Unavailable"
                )
            )
        )

        val hierarchy = """{"adSessionId":"session-1","hasWindowFocus":true}"""
        reporter.record(
            event(
                "omid_bridge",
                30L,
                mapOf("method" to "startSession", "session_id" to "session-1")
            )
        )
        reporter.record(
            event(
                "omid_bridge",
                40L,
                mapOf(
                    "method" to "setNativeViewHierarchy",
                    "session_id" to "session-1",
                    "hierarchy_length" to hierarchy.length.toString(),
                    "hierarchy_sha256" to sha256(hierarchy),
                    "not_visible_reason_present" to "false",
                    "window_focus_false_present" to "false"
                ),
                hierarchy
            )
        )
        reporter.record(
            event(
                "omid_bridge",
                50L,
                mapOf("method" to "finishSession", "session_id" to "session-1")
            )
        )
        reporter.record(
            event(
                "first_frame",
                60L,
                mapOf(
                    "playback_state" to "3",
                    "position_ms" to "100",
                    "duration_ms" to "15000",
                    "buffered_position_ms" to "15000",
                    "is_playing" to "true",
                    "is_playing_ad" to "true"
                )
            )
        )
        reporter.record(
            event(
                "playback_progress",
                70L,
                mapOf(
                    "playback_state" to "3",
                    "position_ms" to "14500",
                    "duration_ms" to "15000",
                    "buffered_position_ms" to "15000",
                    "is_playing" to "true",
                    "is_playing_ad" to "true"
                )
            )
        )
        reporter.record(
            event(
                "player_state",
                80L,
                mapOf(
                    "playback_state" to "3",
                    "position_ms" to "1000",
                    "duration_ms" to "60000",
                    "buffered_position_ms" to "60000",
                    "is_playing" to "true",
                    "is_playing_ad" to "false"
                )
            )
        )

        val summary = buildSummary(reporter)
        assertEquals(6, summary["schema_version"].asInt)
        assertEquals(1, summary["native_xhr_response_count"].asInt)
        val xhr = summary["native_xhr_responses"].asJsonArray.single().asJsonObject
        assertEquals("https://ads.example/signal", xhr["request_url"].asString)
        assertEquals("response-hash", xhr["content_sha256"].asString)

        val web = summary["web_resources"].asJsonArray.single().asJsonObject
        assertEquals("GET", web["last_http_method"].asString)
        assertEquals("false", web["last_main_frame"].asString)
        assertEquals(1, web["error_count"].asInt)

        val playback = summary["ima_playback_audit"].asJsonObject
        assertEquals(1, playback["first_frame_count"].asInt)
        assertEquals(14_500L, playback["last_ad_position_ms"].asLong)
        assertEquals(14_500L, playback["max_ad_position_ms"].asLong)
        assertEquals(14_500L, playback["current_ad_max_position_ms"].asLong)
        assertEquals(1_000L, playback["last_content_position_ms"].asLong)

        val omidSession = summary["omid_runtime_audit"].asJsonObject["sessions"]
            .asJsonArray.single().asJsonObject
        assertEquals("session-1", omidSession["session_id"].asString)
        assertTrue(omidSession["session_started"].asBoolean)
        assertTrue(omidSession["session_finished"].asBoolean)
        assertEquals(
            hierarchy,
            gunzipBase64(
                omidSession["last_hierarchy_artifact"].asJsonObject["content"].asString
            )
        )
    }

    @Test
    fun `verification not executed macro matches the actual failure request`() {
        val reporter = GoogleImaRuntimeAuditReporter("request-macro") {}
        val template = "https://verify.example/not-executed?reason=[REASON]"
        val vastXml = """
            <VAST version="4.1"><Ad><InLine><AdVerifications>
              <Verification vendor="vendor-a">
                <JavaScriptResource apiFramework="omid">https://verify.example/omid.js</JavaScriptResource>
                <TrackingEvents><Tracking event="verificationNotExecuted">$template</Tracking></TrackingEvents>
              </Verification>
            </AdVerifications></InLine></Ad></VAST>
        """.trimIndent()
        reporter.record(event("vast_response", 10L, mapOf("response_index" to "1"), vastXml))
        reporter.record(
            event(
                "web_resource",
                20L,
                mapOf(
                    "phase" to "request",
                    "url" to "https://verify.example/not-executed?reason=2"
                )
            )
        )

        val script = buildSummary(reporter)["verification_scripts"].asJsonArray.single().asJsonObject
        assertTrue(script["verification_not_executed_request_observed"].asBoolean)
        assertEquals("2", script["verification_not_executed_reason"].asString)
        assertEquals(
            "https://verify.example/not-executed?reason=2",
            script["verification_not_executed_actual_urls"].asJsonArray.single().asString
        )
    }

    @Test
    fun `verification macro preserves encoded ampersands inside values`() {
        val reporter = GoogleImaRuntimeAuditReporter("request-encoded-macro") {}
        val template =
            "https://verify.example/not-executed?page=[PAGEURL]&reason=[REASON]"
        val vastXml = """
            <VAST version="4.1"><Ad><InLine><AdVerifications>
              <Verification vendor="vendor-a">
                <JavaScriptResource apiFramework="omid">https://verify.example/omid.js</JavaScriptResource>
                <TrackingEvents><Tracking event="verificationNotExecuted">${template.replace("&", "&amp;")}</Tracking></TrackingEvents>
              </Verification>
            </AdVerifications></InLine></Ad></VAST>
        """.trimIndent()
        val actualUrl = "https://verify.example/not-executed?" +
            "page=https%3A%2F%2Fpublisher.example%2Fpage%3Fa%3D1%26b%3D2&reason=2"
        reporter.record(event("vast_response", 10L, mapOf("response_index" to "1"), vastXml))
        reporter.record(
            event(
                "web_resource",
                20L,
                mapOf("phase" to "request", "url" to actualUrl)
            )
        )

        val script = buildSummary(reporter)["verification_scripts"].asJsonArray.single().asJsonObject
        assertTrue(script["verification_not_executed_request_observed"].asBoolean)
        val values = script["verification_not_executed_macro_values"].asJsonObject
        assertEquals("https://publisher.example/page?a=1&b=2", values["PAGEURL"].asString)
        assertEquals("2", values["REASON"].asString)
    }

    @Test
    fun `loaded next ad does not inherit the previous ad progress`() {
        val reporter = GoogleImaRuntimeAuditReporter("request-progress") {}
        fun adEvent(
            time: Long,
            eventName: String,
            adId: String,
            durationMs: Long,
            position: Int
        ) = event(
            "ima_ad_event",
            time,
            mapOf(
                "ima_event" to eventName,
                "ad_instance_id" to "ima-ad-$position",
                "ima_ad_id" to adId,
                "creative_id" to "creative-$adId",
                "ad_duration_ms" to durationMs.toString(),
                "pod_max_duration_ms" to "45000",
                "total_ads" to "2",
                "ad_position" to position.toString(),
                "pod_index" to "0"
            )
        )
        reporter.record(adEvent(10L, "LOADED", "ad-1", 15_000L, 1))
        reporter.record(adEvent(20L, "STARTED", "ad-1", 15_000L, 1))
        reporter.record(
            event(
                "playback_progress",
                30L,
                mapOf(
                    "trigger" to "periodic",
                    "playback_state" to "3",
                    "position_ms" to "7500",
                    "duration_ms" to "15000",
                    "buffered_position_ms" to "15000",
                    "is_playing" to "true",
                    "is_playing_ad" to "true"
                )
            )
        )
        reporter.record(adEvent(40L, "COMPLETED", "ad-1", 15_000L, 1))
        reporter.record(adEvent(50L, "LOADED", "ad-2", 30_000L, 2))

        val playback = buildSummary(reporter)["ima_playback_audit"].asJsonObject
        assertEquals("ima-ad-1", playback["active_ad_key"].asString)
        assertEquals("ima-ad-2", playback["pending_ad_key"].asString)
        val second = playback["ad_instances"].asJsonArray
            .map { it.asJsonObject }
            .first { it["ad_position"].asInt == 2 }
        assertEquals(-1L, second["max_position_ms"].asLong)
        assertEquals(1.0, playback["ad_progress_ratio"].asDouble, 0.0)
    }

    @Test
    fun `repeated material in different pod positions remains separate instances`() {
        val reporter = GoogleImaRuntimeAuditReporter("request-repeated-material") {}
        fun adEvent(time: Long, eventName: String, position: Int) = event(
            "ima_ad_event",
            time,
            mapOf(
                "ima_event" to eventName,
                "ad_instance_id" to "ima-ad-$position",
                "ima_ad_id" to "same-ad",
                "creative_id" to "same-creative",
                "ad_duration_ms" to if (position == 1) "15000" else "30000",
                "pod_max_duration_ms" to "45000",
                "total_ads" to "2",
                "ad_position" to position.toString(),
                "pod_index" to "0"
            )
        )
        reporter.record(adEvent(10L, "LOADED", 1))
        reporter.record(adEvent(20L, "STARTED", 1))
        reporter.record(
            event(
                "playback_progress",
                30L,
                mapOf(
                    "playback_state" to "3",
                    "position_ms" to "15000",
                    "duration_ms" to "15000",
                    "buffered_position_ms" to "15000",
                    "is_playing" to "true",
                    "is_playing_ad" to "true"
                )
            )
        )
        reporter.record(adEvent(40L, "COMPLETED", 1))
        reporter.record(adEvent(50L, "LOADED", 2))

        val playback = buildSummary(reporter)["ima_playback_audit"].asJsonObject
        val instances = playback["ad_instances"].asJsonArray.map { it.asJsonObject }
        assertEquals(2, instances.size)
        val first = instances.first { it["ad_position"].asInt == 1 }
        val second = instances.first { it["ad_position"].asInt == 2 }
        assertEquals("COMPLETED", first["terminal_event"].asString)
        assertEquals(1.0, first["playback_ratio"].asDouble, 0.0)
        assertEquals("", second["terminal_event"].asString)
        assertEquals(-1L, second["max_position_ms"].asLong)
    }

    @Test
    fun `fallback start and snapshots stay on sdk instance while late terminal start is ignored`() {
        val reporter = GoogleImaRuntimeAuditReporter("request-fallback-instance") {}
        fun adEvent(time: Long, name: String, instanceId: String, position: Int) = event(
            "ima_ad_event",
            time,
            mapOf(
                "ima_event" to name,
                "ad_instance_id" to instanceId,
                "ima_ad_id" to "material-$position",
                "creative_id" to "creative-$position",
                "ad_duration_ms" to "5000",
                "total_ads" to "2",
                "ad_position" to position.toString(),
                "pod_index" to "0"
            )
        )
        reporter.record(adEvent(10L, "LOADED", "ima-ad-1", 1))
        reporter.record(
            event(
                "effective_ad_start",
                20L,
                mapOf(
                    "ad_instance_id" to "ima-ad-1",
                    "start_source" to "ima",
                    "ad_duration_ms" to "5000",
                    "total_ads" to "2",
                    "ad_position" to "1",
                    "pod_index" to "0"
                )
            )
        )
        reporter.record(adEvent(30L, "COMPLETED", "ima-ad-1", 1))
        reporter.record(adEvent(40L, "LOADED", "ima-ad-2", 2))
        reporter.record(
            event(
                "effective_ad_start",
                50L,
                mapOf(
                    "ad_instance_id" to "ima-ad-2",
                    "start_source" to "media_fallback",
                    "ad_duration_ms" to "5000",
                    "total_ads" to "2",
                    "ad_position" to "2",
                    "pod_index" to "0"
                )
            )
        )
        reporter.record(
            event(
                "playback_progress",
                60L,
                mapOf(
                    "ad_instance_id" to "ima-ad-2",
                    "playback_state" to "3",
                    "position_ms" to "2000",
                    "duration_ms" to "5000",
                    "buffered_position_ms" to "5000",
                    "is_playing" to "true",
                    "is_playing_ad" to "true"
                )
            )
        )
        reporter.record(adEvent(70L, "STARTED", "ima-ad-1", 1))

        val playback = buildSummary(reporter)["ima_playback_audit"].asJsonObject
        assertEquals("ima-ad-2", playback["active_ad_key"].asString)
        val instances = playback["ad_instances"].asJsonArray
            .map { it.asJsonObject }
            .associateBy { it["ad_instance_id"].asString }
        assertEquals("COMPLETED", instances.getValue("ima-ad-1")["terminal_event"].asString)
        assertEquals(5_000L, instances.getValue("ima-ad-1")["max_position_ms"].asLong)
        assertEquals("media_fallback", instances.getValue("ima-ad-2")["start_source"].asString)
        assertEquals(2_000L, instances.getValue("ima-ad-2")["max_position_ms"].asLong)
    }

    @Test
    fun `ima log identity ad data and structured errors remain inspectable`() {
        val reporter = GoogleImaRuntimeAuditReporter("request-log") {}
        reporter.record(
            event(
                "ima_ad_event",
                10L,
                mapOf(
                    "ima_event" to "LOG",
                    "ima_ad_id" to "ima-ad-1",
                    "creative_id" to "creative-1",
                    "creative_ad_id" to "creative-ad-1",
                    "universal_ad_ids" to "ad-id.org:universal-1",
                    "ad_duration_ms" to "15000",
                    "total_ads" to "1",
                    "ad_position" to "1",
                    "pod_index" to "0",
                    "ad_data.type" to "adLoadError",
                    "ad_data.errorCode" to "402",
                    "ad_data.errorMessage" to "media unavailable",
                    "ad_data.innerError" to "decoder"
                )
            )
        )
        reporter.record(
            event(
                "playback_error",
                20L,
                mapOf(
                    "category" to "player",
                    "reason" to "player_error",
                    "message" to "decode failed",
                    "playback_error_code_name" to "ERROR_CODE_DECODING_FAILED",
                    "cause_chain" to "decoder cause"
                )
            )
        )

        val playback = buildSummary(reporter)["ima_playback_audit"].asJsonObject
        val log = playback["ima_event_timeline"].asJsonArray.single().asJsonObject
        assertEquals("ima-ad-1", log["ima_ad_id"].asString)
        assertEquals("402", log["ad_data"].asJsonObject["errorCode"].asString)
        assertEquals("decoder", log["ad_data"].asJsonObject["innerError"].asString)
        val error = playback["playback_errors"].asJsonArray.single().asJsonObject
        assertEquals(
            "ERROR_CODE_DECODING_FAILED",
            error["details"].asJsonObject["playback_error_code_name"].asString
        )
    }

    @Test
    fun `ima measurement state remains passive and inspectable`() {
        val reporter = GoogleImaRuntimeAuditReporter("request-measurement") {}
        reporter.record(
            event(
                "ima_measurement_state",
                10L,
                mapOf(
                    "phase" to "ima_started",
                    "trigger" to "ad_event_started",
                    "terminal_event" to "",
                    "ima_started" to "true",
                    "omid_started" to "false",
                    "measurement_complete" to "false"
                )
            )
        )
        reporter.record(
            event(
                "ima_measurement_state",
                20L,
                mapOf(
                    "phase" to "omid_session_started",
                    "trigger" to "",
                    "terminal_event" to "",
                    "ima_started" to "true",
                    "omid_started" to "true",
                    "measurement_complete" to "true"
                )
            )
        )
        reporter.record(
            event(
                "ima_measurement_state",
                30L,
                mapOf(
                    "phase" to "measurement_terminal",
                    "trigger" to "",
                    "terminal_event" to "COMPLETED",
                    "ima_started" to "true",
                    "omid_started" to "true",
                    "measurement_complete" to "true"
                )
            )
        )

        val playback = buildSummary(reporter)["ima_playback_audit"].asJsonObject
        val events = playback.getAsJsonArray("measurement_state_events")
        assertEquals(3, events.size())
        assertEquals("ad_event_started", events[0].asJsonObject["trigger"].asString)
        assertEquals("COMPLETED", events[2].asJsonObject["terminal_event"].asString)
        assertTrue(events[2].asJsonObject["measurement_complete"].asBoolean)
        assertFalse(playback["measurement_incomplete"].asBoolean)
    }

    @Test
    fun `rdid resolution source distinguishes gms from local fallback`() {
        val reporter = GoogleImaRuntimeAuditReporter("request-rdid-source") {}
        reporter.record(
            event(
                "identity_control",
                10L,
                mapOf(
                    "identity_usage" to "url",
                    "rdid_source" to "fallback",
                    "rdid_fallback_source" to "shared_local",
                    "limited_ad_tracking" to "false"
                )
            )
        )
        reporter.record(
            event(
                "identity_control",
                20L,
                mapOf(
                    "identity_usage" to "v2",
                    "rdid_source" to "gms",
                    "rdid_fallback_source" to "",
                    "limited_ad_tracking" to "false"
                )
            )
        )

        val identity = buildSummary(reporter)["identity_control_audit"].asJsonObject
        assertEquals(2, identity["rdid_resolution_count"].asInt)
        assertEquals(1, identity["rdid_gms_count"].asInt)
        assertEquals(1, identity["rdid_fallback_count"].asInt)
        assertEquals("gms", identity["last_rdid_source"].asString)
        assertEquals("shared_local", identity["last_rdid_fallback_source"].asString)

        val decisions = identity["decisions"].asJsonArray
        assertEquals("url", decisions[0].asJsonObject["identity_usage"].asString)
        assertEquals("fallback", decisions[0].asJsonObject["rdid_source"].asString)
        assertEquals(
            "shared_local",
            decisions[0].asJsonObject["rdid_fallback_source"].asString
        )
        assertEquals("v2", decisions[1].asJsonObject["identity_usage"].asString)
        assertEquals("gms", decisions[1].asJsonObject["rdid_source"].asString)
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

    private fun buildSummary(reporter: GoogleImaRuntimeAuditReporter) = JsonParser.parseString(
        GoogleImaRuntimeAuditReporter::class.java
            .getDeclaredMethod("buildSummary", String::class.java)
            .apply { isAccessible = true }
            .invoke(reporter, "completed") as String
    ).asJsonObject

    private fun event(
        type: String,
        elapsedMs: Long,
        fields: Map<String, String>,
        payload: String? = null
    ): GoogleImaAuditEvent = GoogleImaAuditEvent(type, elapsedMs, fields, payload)
}
