package com.smart.android.hq008flow.internal;

import static org.junit.Assert.*;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.Collections;
import org.junit.Test;

public final class FlowTraceTest {
    @Test public void googleMeasurementAuditRetainsItsEngineAndPrecedesCompletion() {
        FlowTrace trace = new FlowTrace(0L, () -> 10L);
        trace.audit("{\"ima_playback_audit\":{\"measurement_incomplete\":false}}");
        JsonArray steps = JsonParser.parseString(trace.finish("google-run", "google-channel", "AD_COMPLETED", "COMPLETED"))
                .getAsJsonObject().getAsJsonArray("steps");
        assertEquals("IMA_RUNTIME_AUDIT", steps.get(0).getAsJsonObject().get("eventType").getAsString());
        assertFalse(steps.get(0).getAsJsonObject().getAsJsonObject("adLog")
                .getAsJsonObject("ima_playback_audit").get("measurement_incomplete").getAsBoolean());
        assertEquals("AD_COMPLETED", steps.get(1).getAsJsonObject().get("eventType").getAsString());
    }
    @Test public void completeLargeNativeAuditSurvivesBeforeTheTerminalStep() {
        FlowTrace trace = new FlowTrace(0L, () -> 10L);
        trace.record("AD_SDK_REQUEST", Collections.singletonMap("final_url", "https://example.com/vast"));
        String content = "x".repeat(400_000);
        JsonObject artifact = new JsonObject();
        artifact.addProperty("content", content);
        JsonArray artifacts = new JsonArray(); artifacts.add(artifact);
        JsonObject audit = new JsonObject(); audit.add("vast_artifacts", artifacts);
        trace.audit(audit.toString());
        trace.audit("{}");
        JsonArray steps = JsonParser.parseString(trace.finish("request", "channel", "AD_COMPLETED", "COMPLETED"))
                .getAsJsonObject().getAsJsonArray("steps");
        assertEquals(3, steps.size());
        assertEquals("TCL_RUNTIME_AUDIT", steps.get(1).getAsJsonObject().get("eventType").getAsString());
        assertEquals(content, steps.get(1).getAsJsonObject().getAsJsonObject("adLog")
                .getAsJsonArray("vast_artifacts").get(0).getAsJsonObject().get("content").getAsString());
        assertEquals("AD_COMPLETED", steps.get(2).getAsJsonObject().get("eventType").getAsString());
    }
    @Test public void disabledPopupLoggingDropsPreviouslyCollectedDetail() {
        FlowTrace trace = new FlowTrace(0L, () -> 10L);
        trace.record("FLOW_CONTROL_START", Collections.emptyMap());
        trace.audit("{\"secret\":\"body\"}");
        trace.setEnabled(false);
        trace.record("SHOULD_NOT_APPEAR", Collections.emptyMap());
        trace.audit("{\"late\":true}");
        assertFalse(trace.isEnabled());
        String json = trace.finish("request", "channel", "FLOW_FINISHED", "done");
        assertFalse(json.contains("FLOW_CONTROL_START"));
        assertFalse(json.contains("SHOULD_NOT_APPEAR"));
        assertFalse(json.contains("secret"));
        assertFalse(json.contains("TCL_RUNTIME_AUDIT"));
    }
    @Test public void longTimelineRetainsFirstAndNewestStepsWithExplicitOmissions() {
        FlowTrace trace = new FlowTrace(0L, () -> 10L);
        for (int i = 0; i < 450; i++) trace.record("STEP_" + i, Collections.emptyMap());
        JsonObject payload = JsonParser.parseString(trace.finish("request", "channel", "AD_ERROR", "TIMEOUT")).getAsJsonObject();
        assertEquals(50, payload.get("trimmedStepCount").getAsInt());
        JsonArray steps = payload.getAsJsonArray("steps");
        assertEquals("STEP_0", steps.get(0).getAsJsonObject().get("eventType").getAsString());
        assertEquals("STEP_449", steps.get(399).getAsJsonObject().get("eventType").getAsString());
    }
}
