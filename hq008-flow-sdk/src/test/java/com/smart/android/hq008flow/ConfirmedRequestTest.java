package com.smart.android.hq008flow;

import static org.junit.Assert.*;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.Test;

public final class ConfirmedRequestTest {
    @Test public void queuedResponseRemainsReportableAfterTerminationAndReplacement() {
        FlowRuntime.ActiveRun previous = new FlowRuntime.ActiveRun("previous", "request-old", 1L);
        FlowRuntime.ActiveRun current = new FlowRuntime.ActiveRun("current", "request-new", 2L);
        previous.terminal = true;
        previous.traceUploaded = true;
        Map<String, Object> headers = new LinkedHashMap<>();
        headers.put("sdkRequestResponded", true);
        headers.put("sdkRequestHttpStatus", 503);
        Map<String, Object> report = previous.takeRequestedReport(headers);
        assertNotNull(report);
        assertEquals(503, report.get("sdkRequestHttpStatus"));
        assertNull(previous.takeRequestedReport(headers));
        assertFalse(current.requested);
        assertTrue(current.diagnostics.isEmpty());
        assertEquals("request-old", previous.requestId);
    }
    @Test public void confirmedPayloadIsASnapshotIndependentOfLaterCallbacks() {
        FlowRuntime.ActiveRun run = new FlowRuntime.ActiveRun("run", "request", 1L);
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("sdkRequestHttpStatus", 200);
        Map<String, Object> saved = run.takeRequestedReport(fields);
        fields.put("sdkRequestHttpStatus", 500);
        run.diagnostics.put("terminalReason", "cancelled");
        assertEquals(200, saved.get("sdkRequestHttpStatus"));
        assertFalse(saved.containsKey("terminalReason"));
    }
}
