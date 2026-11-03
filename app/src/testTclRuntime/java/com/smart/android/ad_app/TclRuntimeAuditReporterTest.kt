package com.smart.android.ad_app

import com.google.common.io.BaseEncoding
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test
import java.util.zip.GZIPInputStream

class TclRuntimeAuditReporterTest {
    private val xml = """<VAST version="4.1"><Ad><InLine><Creatives><Creative><Linear><MediaFiles>
        <MediaFile type="video/mp4">https://media.example/ad.mp4</MediaFile>
        </MediaFiles></Linear></Creative></Creatives><AdVerifications><Verification>
        <JavaScriptResource>https://verify.example/script.js</JavaScriptResource>
        </Verification></AdVerifications></InLine></Ad></VAST>"""

    @Test fun `filled requires a video MediaFile in a valid Inline response`() {
        assertTrue(TclVastObservation.parse(xml).filled)
        assertFalse(TclVastObservation.parse("<VAST><Ad><Wrapper><VASTAdTagURI>https://next.example</VASTAdTagURI></Wrapper></Ad></VAST>").filled)
        assertFalse(TclVastObservation.parse("<VAST/>").filled)
        assertFalse(TclVastObservation.parse(xml.replace("https://media.example/ad.mp4", "http://")).filled)
        assertFalse(TclVastObservation.parse(xml.replace("<Creatives>", "<Extension>")
            .replace("</Creatives>", "</Extension>")).filled)
        assertTrue(TclVastObservation.parse(xml.replace("<VAST version", "<VAST xmlns=\"urn:vast\" version")).filled)
        assertFalse(TclVastObservation.parse("<!DOCTYPE VAST [<!ENTITY x SYSTEM 'file:///tmp/never-read'>]><VAST>&x;</VAST>").filled)
    }

    @Test fun `XML is recoverable and measurement support is not inferred from verification declarations`() {
        val reporter = TclRuntimeAuditReporter("request", "TCL_FFA", false, true)
        reporter.vast(xml, 10L, mapOf("native_request_id" to "native"), TclVastObservation.parse(xml))
        val summary = JsonParser.parseString(reporter.finish("completed", emptyMap())).asJsonObject
        val artifact = summary["vast_artifacts"].asJsonArray.single().asJsonObject
        val recovered = GZIPInputStream(BaseEncoding.base64().decode(artifact["content"].asString).inputStream())
            .bufferedReader().use { it.readText() }
        assertEquals(xml, recovered)
        assertEquals("not_collected", summary["sound_audit"].asJsonObject["device_volume_collection"].asString)
        assertEquals("not_available", summary["measurement_capabilities"].asJsonObject["omid"].asString)
        assertNull(reporter.finish("duplicate", emptyMap()))
    }

    @Test fun `disabled detailed logging produces no audit payload`() {
        val reporter = TclRuntimeAuditReporter("request", "TCL_FFA", false, false)
        reporter.record("buffering", 10L)
        assertNull(reporter.finish("completed", emptyMap()))
    }

    @Test fun `large TCL artifact survives the existing single terminal upload`() {
        val random = java.util.Random(42)
        val payload = CharArray(400_000) { ('a'.code + random.nextInt(26)).toChar() }.concatToString()
        val largeXml = xml.replace("<Ad>", "<Ad><!--$payload-->")
        val reporter = TclRuntimeAuditReporter("request", "TCL_FFA", false, true)
        reporter.vast(largeXml, 10, emptyMap(), TclVastObservation.parse(largeXml))
        val audit = reporter.finish("completed", emptyMap())!!
        val trace = Hq008ConsentTraceSession(80) { "request" }
        fun record(event: String, adLog: String? = null) = trace.record(0L, event, "", "", adLog)
        record("CMP_GATE_START")
        record("AD_PHASE_START")
        record("TCL_RUNTIME_AUDIT", audit)
        val snapshot = record("AD_PHASE_COMPLETED")!!
        val method = Hq008ConsentLogReporter::class.java.getDeclaredMethod("buildUploadPayload",
            Hq008ConsentTraceSession.TraceSnapshot::class.java).apply { isAccessible = true }
        val upload = method.invoke(Hq008ConsentLogReporter, snapshot)
        val field = upload.javaClass.getDeclaredField("adLog").apply { isAccessible = true }
        val summary = JsonParser.parseString(field.get(upload) as String).asJsonObject
        assertTrue((field.get(upload) as String).length > 256_000)
        val saved = summary["steps"].asJsonArray.single { it.asJsonObject["eventType"].asString == "TCL_RUNTIME_AUDIT" }
        assertTrue(saved.asJsonObject["adLog"].asJsonObject["vast_artifacts"].asJsonArray.size() == 1)
        assertNull(record("AD_PHASE_COMPLETED"))
    }
}
