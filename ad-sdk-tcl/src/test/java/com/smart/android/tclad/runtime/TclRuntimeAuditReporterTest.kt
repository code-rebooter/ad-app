package com.smart.android.tclad.runtime

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

}
