package com.smart.android.hq008flow.internal;

import android.os.SystemClock;

import com.smart.android.hq008flow.Hq008FlowConfig;

import com.google.gson.Gson;

import java.util.LinkedHashMap;
import java.util.Map;

public final class AdEventReporter {
    private static final String FLOW_SDK_VERSION = "1.0.0";

    private final FlowApiClient apiClient;
    private final Hq008FlowConfig config;
    private final DeviceInfo deviceInfo;
    private final Gson gson = new Gson();

    public AdEventReporter(
            FlowApiClient apiClient,
            Hq008FlowConfig config,
            DeviceInfo deviceInfo
    ) {
        this.apiClient = apiClient;
        this.config = config;
        this.deviceInfo = deviceInfo;
    }

    public void requested(String requestId, long createdAtMs) {
        Map<String, Object> diagnostics = new LinkedHashMap<>();
        diagnostics.put("createdAtMs", createdAtMs);
        report(requestId, "AD_PROGRESS", "REQUESTED", diagnostics);
    }

    public void loaded(String requestId, long createdAtMs) {
        long now = SystemClock.elapsedRealtime();
        Map<String, Object> diagnostics = new LinkedHashMap<>();
        diagnostics.put("createdAtMs", createdAtMs);
        diagnostics.put("loadedAtMs", now);
        diagnostics.put("requestToLoadMs", now - createdAtMs);
        report(requestId, "AD_PROGRESS", "LOADED", diagnostics);
    }

    public void started(String requestId, long createdAtMs, Double progress) {
        long now = SystemClock.elapsedRealtime();
        Map<String, Object> diagnostics = new LinkedHashMap<>();
        diagnostics.put("createdAtMs", createdAtMs);
        diagnostics.put("startedAtMs", now);
        diagnostics.put("requestToStartMs", now - createdAtMs);
        if (progress != null) {
            diagnostics.put("progress", progress);
        }
        report(requestId, "AD_PROGRESS", "STARTED", diagnostics);
    }

    public void completed(String requestId, long createdAtMs) {
        long now = SystemClock.elapsedRealtime();
        Map<String, Object> diagnostics = new LinkedHashMap<>();
        diagnostics.put("createdAtMs", createdAtMs);
        diagnostics.put("finishedAtMs", now);
        diagnostics.put("totalDurationMs", now - createdAtMs);
        report(requestId, "AD_COMPLETED", "COMPLETED", diagnostics);
    }

    public void failed(
            String requestId,
            long createdAtMs,
            Integer code,
            String message
    ) {
        long now = SystemClock.elapsedRealtime();
        Map<String, Object> diagnostics = new LinkedHashMap<>();
        diagnostics.put("createdAtMs", createdAtMs);
        diagnostics.put("failedAtMs", now);
        diagnostics.put("totalDurationMs", now - createdAtMs);
        if (code != null) {
            diagnostics.put("errorCode", code);
        }
        report(requestId, "AD_ERROR", message, diagnostics);
    }

    private void report(
            String requestId,
            String eventType,
            String message,
            Map<String, Object> eventDiagnostics
    ) {
        Map<String, Object> diagnosticInfo = new LinkedHashMap<>();
        diagnosticInfo.put("flowSdkVersion", FLOW_SDK_VERSION);
        diagnosticInfo.put("adSdkVersion", config.getAdSdkVersion());
        diagnosticInfo.putAll(eventDiagnostics);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("request_id", requestId);
        body.put("event_type", eventType);
        body.put("uuid", deviceInfo.androidId);
        body.put("channel_id", config.getChannelId());
        body.put("local_ip", deviceInfo.localIp);
        body.put("mac", deviceInfo.mac.isEmpty() ? "00:00:00:00:00:00" : deviceInfo.mac);
        body.put("app_id", deviceInfo.packageName);
        body.put("app_name", config.getAppName());
        body.put("bundle", deviceInfo.packageName);
        body.put("make", deviceInfo.make);
        body.put("model", deviceInfo.model);
        body.put("os", "Android");
        body.put("osv", deviceInfo.osVersion);
        body.put("language", deviceInfo.language);
        body.put("ad_version", deviceInfo.versionCode);
        body.put("message", message);
        body.put("diagnostic_info", gson.toJson(diagnosticInfo));
        apiClient.report(body);
    }
}
