package com.smart.android.adsdk.internal;

import com.smart.android.adsdk.AdError;
import com.smart.android.adsdk.AdErrorCode;
import com.smart.android.adsdk.AdResult;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** One trace per play, independent of the local logging switch and other sessions. */
final class FlowTrace {
    private final TimeoutScheduler clock;
    private final long startedAtMs;
    private final List<Map<String, Object>> steps = new ArrayList<>();
    private boolean enabled = true;
    private boolean finished;
    private boolean truncated;

    FlowTrace(TimeoutScheduler clock) {
        this.clock = clock;
        this.startedAtMs = clock.nowMs();
    }

    synchronized void setEnabled(boolean value) {
        if (!finished) enabled = value;
    }

    synchronized void record(String event, String message) {
        if (finished) return;
        Map<String, Object> step = new LinkedHashMap<>();
        step.put("elapsedMs", Math.max(0L, clock.nowMs() - startedAtMs));
        step.put("eventType", event);
        step.put("eventMessage", limit(message, 512));
        steps.add(step);
        if (steps.size() > 80) {
            steps.remove(1); // Preserve the start plus the latest 79 steps.
            truncated = true;
        }
    }

    void finish(Hq008AdReporter reporter, String requestId, AdResult result,
                Map<String, Object> diagnostics) {
        Map<String, Object> summary;
        String event = terminalEvent(result, String.valueOf(diagnostics.get("phase")),
            String.valueOf(diagnostics.get("authorizationOutcome")));
        synchronized (this) {
            if (finished) return;
            record(event, result.toString());
            finished = true;
            if (!enabled) {
                SdkLog.i("AdSdkReport", "requestId=" + requestId + " popup_log_enabled=false; trace upload disabled");
                steps.clear();
                return;
            }
            summary = new LinkedHashMap<>();
            summary.put("traceVersion", 2);
            summary.put("requestId", limit(requestId, 512));
            summary.put("finalEventType", event);
            summary.put("status", result.getStatus().name());
            summary.put("finalEventMessage", limit(result.getMessage(), 512));
            summary.put("stepCount", steps.size());
            summary.put("traceTruncated", truncated);
            List<Map<String, Object>> snapshot = new ArrayList<>();
            for (Map<String, Object> step : steps) {
                Map<String, Object> copy = new LinkedHashMap<>(step);
                copy.put("index", snapshot.size() + 1);
                snapshot.add(copy);
            }
            summary.put("steps", snapshot);
            Map<String, Object> boundedDiagnostics = new LinkedHashMap<>();
            for (Map.Entry<String, Object> entry : diagnostics.entrySet()) {
                Object value = entry.getValue();
                boundedDiagnostics.put(entry.getKey(), value instanceof String ? limit((String) value, 512) : value);
            }
            summary.put("diagnostics", boundedDiagnostics);
            AdError error = result.getError();
            if (error != null) {
                Map<String, Object> originalError = new LinkedHashMap<>();
                originalError.put("source", limit(error.getSource(), 512));
                originalError.put("code", error.getOriginalCode() == null ? null : limit(error.getOriginalCode(), 512));
                originalError.put("message", limit(error.getMessage(), 512));
                originalError.put("stage", error.getStage().name());
                String responseBody = error.getResponseBody();
                originalError.put("responseBody", responseBody == null ? null : limit(responseBody, 32_000));
                if (responseBody != null && responseBody.length() > 32_000) {
                    originalError.put("responseBodyTruncated", true);
                }
                summary.put("error", originalError);
            }
            steps.clear();
        }
        reporter.consentLog(event, limit(result.getMessage(), 512), summary);
    }

    private static String terminalEvent(AdResult result, String phase, String authorizationOutcome) {
        switch (result.getStatus()) {
            case COMPLETED: return "AD_PHASE_COMPLETED";
            case CANCELLED: return "AD_PHASE_CANCELLED";
            case SKIPPED:
                if ("FLOW_CONTROL".equals(phase) || "CMP".equals(phase)) return "CMP_GATE_STOP";
                if ("AUTHORIZE".equals(phase)) {
                    return "DENIED".equals(authorizationOutcome) ? "AUTHORIZE_DENIED" : "AUTHORIZE_CALLBACK_FAIL";
                }
                return "AD_PHASE_SKIPPED";
            default:
                return result.getError() != null && result.getError().getCode() == AdErrorCode.TIMEOUT
                    ? "AD_PHASE_TIMEOUT" : "AD_PHASE_ERROR";
        }
    }

    private static String limit(String text, int max) {
        return text == null ? "" : text.length() <= max ? text : text.substring(0, max);
    }
}
