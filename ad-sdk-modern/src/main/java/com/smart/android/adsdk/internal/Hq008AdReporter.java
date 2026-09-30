package com.smart.android.adsdk.internal;

import android.content.Context;
import android.os.Build;
import com.google.gson.Gson;
import com.smart.android.adsdk.AdError;
import com.smart.android.adsdk.AdResult;
import com.smart.android.adsdk.AdResultStatus;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;

final class Hq008AdReporter {
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    private static final String EVENT_PROGRESS = "AD_PROGRESS";
    private static final String EVENT_COMPLETED = "AD_COMPLETED";
    private static final String EVENT_ERROR = "AD_ERROR";

    private final Context context;
    private final OkHttpClient okHttpClient;
    private final Gson gson;
    private final String channelId;
    private final String reportUrl;
    private final String consentLogUrl;
    private final boolean enabled;

    Hq008AdReporter(
        Context context,
        OkHttpClient okHttpClient,
        Gson gson,
        String channelId,
        String apiBaseUrl
    ) {
        this.context = context;
        this.okHttpClient = okHttpClient;
        this.gson = gson;
        this.channelId = channelId;
        String baseUrl = apiBaseUrl.endsWith("/") ? apiBaseUrl : apiBaseUrl + "/";
        this.reportUrl = baseUrl + "api/v2/ad/report";
        this.consentLogUrl = baseUrl + "api/v2/ad/consent-log-report";
        this.enabled = true;
    }

    Hq008AdReporter() {
        this.context = null;
        this.okHttpClient = null;
        this.gson = null;
        this.channelId = "";
        this.reportUrl = "";
        this.consentLogUrl = "";
        this.enabled = false;
    }

    void requested(String requestId, long createdAtMs, int width, int height, Map<String, Object> timing) {
        Map<String, Object> diagnostics = baseDiagnostics(createdAtMs, timing);
        diagnostics.put("containerWidth", width);
        diagnostics.put("containerHeight", height);
        diagnostics.put("sdk", "ad-sdk-modern");
        report(requestId, EVENT_PROGRESS, "REQUESTED", diagnostics);
    }

    void loaded(String requestId, long createdAtMs, Map<String, Object> timing) {
        report(requestId, EVENT_PROGRESS, "LOADED", baseDiagnostics(createdAtMs, timing));
    }

    void started(String requestId, long createdAtMs, Map<String, Object> timing) {
        report(requestId, EVENT_PROGRESS, "STARTED", baseDiagnostics(createdAtMs, timing));
    }

    void finished(String requestId, long createdAtMs, AdResult result, Map<String, Object> timing) {
        Map<String, Object> diagnostics = baseDiagnostics(createdAtMs, timing);
        diagnostics.put("status", result.getStatus().name());
        diagnostics.put("reason", result.getReason());
        AdError error = result.getError();
        if (error != null) {
            diagnostics.put("errorSource", error.getSource());
            diagnostics.put("originalCode", error.getOriginalCode());
            diagnostics.put("originalMessage", error.getMessage());
            diagnostics.put("errorStage", error.getStage().name());
            diagnostics.put("responseBody", error.getResponseBody());
        }
        if (result.getStatus() == AdResultStatus.COMPLETED) {
            report(requestId, EVENT_COMPLETED, "COMPLETED", diagnostics);
        } else if (result.getStatus() == AdResultStatus.ERROR) {
            report(
                requestId,
                EVENT_ERROR,
                error == null ? "AD_ERROR" : error.getMessage(),
                diagnostics
            );
        } else if (result.getStatus() == AdResultStatus.SKIPPED) {
            report(requestId, EVENT_ERROR, result.getMessage(), diagnostics);
        } else if (result.getStatus() == AdResultStatus.CANCELLED) {
            report(requestId, EVENT_ERROR, "CANCELLED", diagnostics);
        }
    }

    private Map<String, Object> baseDiagnostics(long createdAtMs, Map<String, Object> timing) {
        Map<String, Object> diagnostics = new LinkedHashMap<>();
        diagnostics.putAll(timing);
        diagnostics.put("createdAtMs", createdAtMs);
        diagnostics.put("sdkEntry", "ima");
        diagnostics.put("deviceModel", Build.MODEL == null ? "" : Build.MODEL);
        diagnostics.put("deviceMake", Build.MANUFACTURER == null ? "" : Build.MANUFACTURER);
        return diagnostics;
    }

    private void report(
        String requestId,
        String eventType,
        String message,
        Map<String, Object> diagnostics
    ) {
        if (!enabled || requestId == null || requestId.trim().isEmpty()) {
            return;
        }
        DeviceInfo deviceInfo = DeviceInfo.collect(context);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("request_id", requestId);
        body.put("event_type", eventType);
        body.put("uuid", deviceInfo.androidId);
        body.put("channel_id", channelId);
        body.put("ad_version", deviceInfo.versionCode);
        body.put("mac", deviceInfo.mac.isEmpty() ? "00:00:00:00:00:00" : deviceInfo.mac);
        body.put("app_id", deviceInfo.packageName);
        body.put("make", deviceInfo.make);
        body.put("model", deviceInfo.model);
        body.put("message", message == null ? "" : message);
        if (!deviceInfo.localIp.isEmpty()) body.put("local_ip", deviceInfo.localIp);
        if (diagnostics != null) body.put("diagnostic_info", gson.toJson(diagnostics));
        send(reportUrl, body, "requestId=" + requestId + " event=" + eventType);
    }

    void consentLog(String eventType, String message, Map<String, Object> summary) {
        if (!enabled) return;
        String adLog = gson.toJson(summary);
        if (adLog.length() > 256_000) {
            // Drop large response bodies, keeping valid JSON, original code/message and all steps.
            Object error = summary.get("error");
            if (error instanceof Map) ((Map<?, ?>) error).remove("responseBody");
            summary.put("traceCompacted", true);
            summary.put("traceCompactedMode", "without_response_body");
            adLog = gson.toJson(summary);
        }
        // JSON escaping can expand even bounded step messages; keep the payload within the wire limit.
        List<?> steps = (List<?>) summary.get("steps");
        while (adLog.length() > 256_000 && steps != null && steps.size() > 2) {
            steps.remove(1);
            summary.put("stepCount", steps.size());
            summary.put("traceCompacted", true);
            summary.put("traceCompactedMode", "latest_steps");
            adLog = gson.toJson(summary);
        }
        if (adLog.length() > 256_000) {
            // Defensive fallback for any future fields added without a bound.
            summary.remove("steps");
            summary.remove("error");
            summary.remove("diagnostics");
            summary.put("stepCount", 0);
            summary.put("traceCompacted", true);
            summary.put("traceCompactedMode", "terminal_only");
            adLog = gson.toJson(summary);
        }
        DeviceInfo deviceInfo = DeviceInfo.collect(context);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("channel_id", channelId);
        body.put("mac", deviceInfo.mac.isEmpty() ? "00:00:00:00:00:00" : deviceInfo.mac);
        body.put("ad_version", deviceInfo.versionCode);
        body.put("event_type", eventType);
        body.put("event_message", message);
        body.put("ad_log", adLog);
        send(consentLogUrl, body, "consent-log-report event=" + eventType);
    }

    private void send(String url, Map<String, Object> body, String label) {
        Request request = new Request.Builder()
            .url(url)
            .post(RequestBody.create(JSON, gson.toJson(body)))
            .header("Accept", "application/json")
            .build();
        Call call = okHttpClient.newCall(request);
        call.enqueue(new okhttp3.Callback() {
            @Override
            public void onFailure(Call call, java.io.IOException error) {
                SdkLog.e("AdSdkReport", label + " report failed", error);
            }

            @Override
            public void onResponse(Call call, okhttp3.Response response) {
                SdkLog.i("AdSdkReport", label + " report HTTP=" + response.code());
                response.close();
            }
        });
    }
}
