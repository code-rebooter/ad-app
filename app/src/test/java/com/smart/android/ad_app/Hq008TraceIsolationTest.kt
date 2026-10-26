package com.smart.android.ad_app

import org.junit.Assert.*
import org.junit.Test

class Hq008TraceIsolationTest {
    private var sequence = 0
    private fun session() = Hq008ConsentTraceSession(requestIdFactory = { "local-${++sequence}" })
    private fun Hq008ConsentTraceSession.add(event: String, message: String = "", owner: String? = null) =
        record(1_000L, event, message, message, null, owner)
    private fun Hq008ConsentTraceSession.start(serverId: String) {
        add("CMP_GATE_START")
        bindAuthorizationRequestId(activeRequestId(), serverId)
        add("AD_PHASE_START", "requestId=$serverId")
    }

    @Test fun `in flight polling leaves active request steps and disabled upload flag intact`() {
        val trace = session()
        trace.start("server-active")
        trace.updatePopupLogEnabled(false, "server-active")
        trace.add("AD_STARTED", "requestId=server-active")
        repeat(5) { assertNull(trace.add("FLOATING_FLOW_SKIPPED", "reason=in_flight,adType=FLOATING")) }
        assertEquals("server-active", trace.activeRequestId())
        val snapshot = trace.add("AD_PHASE_COMPLETED", "requestId=server-active")!!
        assertEquals(listOf("CMP_GATE_START", "AD_PHASE_START", "AD_STARTED", "AD_PHASE_COMPLETED"),
            snapshot.steps.map { it.eventType })
        assertFalse(snapshot.popupLogEnabled)
    }

    @Test fun `old terminal and capture cannot contaminate or finish replacement trace`() {
        for (event in listOf("AD_PHASE_TIMEOUT", "AD_PHASE_ERROR", "AD_PHASE_COMPLETED", "AD_SDK_HTTP_CAPTURE")) {
            val trace = session()
            trace.start("server-old")
            trace.start("server-new")
            assertNull(trace.add(event, "sdk=tcl,sdkEntry=native,requestId=server-old,reason=late"))
            assertNull(trace.add(event, "request_id=server-old"))
            assertEquals("server-new", trace.activeRequestId())
            val snapshot = trace.add("AD_PHASE_COMPLETED", "requestId=server-new")!!
            assertFalse(snapshot.steps.any { it.rawEventMessage.contains("server-old") })
        }
    }

    @Test fun `late force finish or flow control result does not reset newer popup log setting`() {
        val trace = session()
        trace.start("server-old")
        val oldLocalId = "local-1"
        trace.start("server-new")
        assertTrue(trace.updatePopupLogEnabled(false, "server-new"))
        assertFalse(trace.updatePopupLogEnabled(true, oldLocalId))
        assertNull(trace.forceFinish(1_100L, "FLOW_GUARD_FINISH", "reason=late", "", null, "server-old"))
        assertNull(trace.add("FLOW_CONTROL_RESULT", "popupLogEnabled=true", oldLocalId))
        assertEquals("server-new", trace.activeRequestId())
        val snapshot = trace.add("AD_PHASE_COMPLETED", "requestId=server-new")!!
        assertFalse(snapshot.popupLogEnabled)
        assertFalse(snapshot.steps.any { it.rawEventMessage.contains("late") || it.eventType == "FLOW_CONTROL_RESULT" })
    }

    @Test fun `local request ID remains a valid owner after server ID binding`() {
        val trace = session()
        trace.start("server-active")
        assertTrue(trace.ownsRequest("local-1"))
        assertTrue(trace.updatePopupLogEnabled(false, "local-1"))
        val snapshot = trace.forceFinish(1_100L, "FLOW_GUARD_FINISH", "", "", null, "local-1")!!
        assertEquals("server-active", snapshot.requestId)
        assertFalse(snapshot.popupLogEnabled)
        trace.start("server-next")
        assertFalse(trace.ownsRequest("local-1"))
        assertTrue(trace.isPopupLogEnabled())
    }
}
