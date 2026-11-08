package com.smart.android.hq008flow.internal;

import android.os.SystemClock;
import com.google.gson.Gson;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/** Retains the first step, latest steps and complete bounded native audit. */
public final class FlowTrace {
    private final long createdAtMs;
    interface Clock { long now(); }
    private final Clock clock;
    private final List<Map<String, Object>> steps = new ArrayList<>();
    private final LinkedHashSet<String> events = new LinkedHashSet<>();
    private boolean enabled = true;
    private int count, omitted;
    private String runtimeAudit;
    public FlowTrace(long createdAtMs) { this(createdAtMs, SystemClock::elapsedRealtime); }
    FlowTrace(long createdAtMs, Clock clock) {
        this.createdAtMs = createdAtMs;
        this.clock = clock;
    }
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        if (!enabled) { steps.clear(); events.clear(); runtimeAudit = null; }
    }
    public boolean isEnabled() { return enabled; }
    public void record(String type, Map<String, Object> fields) {
        if (!enabled) return;
        events.add(type);
        if (steps.size() >= 400) { steps.remove(1); omitted++; }
        Map<String, Object> step = new LinkedHashMap<>();
        step.put("index", ++count);
        step.put("elapsedMs", clock.now() - createdAtMs);
        step.put("eventType", type);
        step.put("eventMessage", "");
        step.put("adLog", new LinkedHashMap<>(fields));
        steps.add(step);
    }
    public void audit(String json) { if (enabled && runtimeAudit == null) runtimeAudit = json; }
    public String finish(String requestId, String channelId, String status, String message) {
        List<Map<String, Object>> upload = new ArrayList<>(steps);
        if (runtimeAudit != null) {
            Map<String, Object> audit = new LinkedHashMap<>();
            audit.put("index", ++count);
            audit.put("elapsedMs", clock.now() - createdAtMs);
            String runtimeEvent = "TCL_RUNTIME_AUDIT";
            try {
                com.google.gson.JsonObject body = JsonParser.parseString(runtimeAudit).getAsJsonObject();
                if (body.has("ima_playback_audit") || body.has("omid_runtime_audit")) runtimeEvent = "IMA_RUNTIME_AUDIT";
            } catch (RuntimeException ignored) { }
            audit.put("eventType", runtimeEvent);
            audit.put("eventMessage", "requestId=" + requestId);
            try { audit.put("adLog", JsonParser.parseString(runtimeAudit)); }
            catch (RuntimeException error) { audit.put("adLog", runtimeAudit); }
            upload.add(audit);
            events.add(runtimeEvent);
        }
        Map<String, Object> terminal = new LinkedHashMap<>();
        terminal.put("index", ++count);
        terminal.put("elapsedMs", clock.now() - createdAtMs);
        terminal.put("eventType", status);
        terminal.put("eventMessage", message);
        upload.add(terminal);
        events.add(status);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("traceVersion", 3);
        body.put("requestId", requestId);
        body.put("channelId", channelId);
        body.put("finalEventType", status);
        body.put("finalEventMessage", message);
        body.put("stepCount", upload.size());
        body.put("trimmedStepCount", omitted);
        body.put("observedEventTypes", events);
        body.put("milestones", new ArrayList<>());
        body.put("steps", upload);
        return new Gson().toJson(body);
    }
}
