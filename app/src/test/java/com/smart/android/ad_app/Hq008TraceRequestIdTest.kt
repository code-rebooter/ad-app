package com.smart.android.ad_app

import org.junit.Assert.*
import org.junit.Test

class Hq008TraceRequestIdTest {
    private fun record(session: Hq008ConsentTraceSession, event: String) =
        session.record(1000L, event, "", "", null)

    @Test fun `authorization server ID is frozen into the log snapshot`() {
        val session = Hq008ConsentTraceSession(requestIdFactory = { "local-1" })
        record(session, "CMP_GATE_START")
        assertEquals("local-1", session.activeRequestId())
        session.bindAuthorizationRequestId("local-1", "server-1")
        record(session, "AD_PHASE_START")
        val result = record(session, "AD_PHASE_COMPLETED")!!
        assertEquals("server-1", result.requestId)
        assertEquals("", session.activeRequestId())
    }

    @Test fun `a flow stopped before authorize still has its own request ID`() {
        val session = Hq008ConsentTraceSession(requestIdFactory = { "local-before-auth" })
        record(session, "CMP_GATE_START")
        assertEquals("local-before-auth", record(session, "CMP_GATE_STOP")!!.requestId)
    }

    @Test fun `late authorize response cannot change a new flow or a frozen upload`() {
        var count = 0
        val session = Hq008ConsentTraceSession(requestIdFactory = { "local-${++count}" })
        record(session, "CMP_GATE_START")
        session.bindAuthorizationRequestId("local-1", "server-old")
        val old = session.forceFinish(1100L, "FLOW_GUARD_FINISH", "", "", null)!!
        record(session, "CMP_GATE_START")
        session.bindAuthorizationRequestId("local-1", "late-server-old")
        assertEquals("local-2", session.activeRequestId())
        session.bindAuthorizationRequestId("local-2", "server-new")
        assertEquals("server-new", record(session, "AUTHORIZE_DENIED")!!.requestId)
        assertEquals("server-old", old.requestId)
    }

    @Test fun `request ID survives step trimming and a blank server replacement`() {
        val session = Hq008ConsentTraceSession(maxTraceSteps = 3, requestIdFactory = { "local-1" })
        record(session, "CMP_GATE_START")
        session.bindAuthorizationRequestId("local-1", "server-1")
        repeat(12) { record(session, "AD_SDK_DIAGNOSTIC") }
        session.bindAuthorizationRequestId("server-1", " ")
        val result = record(session, "AD_PHASE_ERROR")!!
        assertEquals("server-1", result.requestId)
        assertEquals(3, result.steps.size)
    }

    @Test fun `skipped flows receive new IDs without inheriting a previous one`() {
        var count = 0
        val session = Hq008ConsentTraceSession(requestIdFactory = { "skip-${++count}" })
        assertEquals("skip-1", record(session, "FLOATING_FLOW_SKIPPED")!!.requestId)
        assertEquals("skip-2", record(session, "FLOATING_FLOW_SKIPPED")!!.requestId)
    }
}
