package com.smart.android.hq008flow.internal;

import android.content.Context;
import android.os.SystemClock;

import com.smart.android.hq008flow.BuildConfig;
import com.smart.android.hq008flow.Hq008FlowConfig;

import com.google.gson.Gson;

import java.util.LinkedHashMap;
import java.util.Map;

public final class AdEventReporter {
    private final FlowApiClient apiClient;
    private final Hq008FlowConfig config;
    private final Context appContext;
    private final Gson gson = new Gson();
    private final DurableReports durableReports;

    public AdEventReporter(
            FlowApiClient apiClient,
            Hq008FlowConfig config,
            Context context
    ) {
        this.apiClient = apiClient;
        this.config = config;
        this.appContext = context.getApplicationContext();
        this.durableReports = new DurableReports(appContext, config.getApiBaseUrl());
        durableReports.resumePending();
    }

    public void dailyMetrics(String requestId, DailyStatsStore.Snapshot snapshot) {
        if (snapshot == null) {
            return;
        }
        DeviceInfo deviceInfo = DeviceInfo.collect(appContext);
        String message = buildDailyMetricsMessage(snapshot);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("request_id", requestId);
        body.put("event_type", "SDK_DAILY_METRIC");
        body.put("uuid", deviceInfo.androidId);
        body.put("channel_id", config.getChannelId());
        body.put("mac", deviceInfo.mac.isEmpty() ? "00:00:00:00:00:00" : deviceInfo.mac);
        body.put("app_id", deviceInfo.packageName);
        body.put("make", deviceInfo.make);
        body.put("model", deviceInfo.model);
        body.put("ad_version", deviceInfo.versionCode);
        body.put("ad_version_name", deviceInfo.versionName);
        body.put("sdk_version", config.getAdSdkVersion());
        body.put("androidid", "unknown_device".equals(deviceInfo.androidId) ? "" : deviceInfo.androidId);
        body.put("message", message);
        if (!deviceInfo.localIp.isEmpty()) {
            body.put("local_ip", deviceInfo.localIp);
        }
        SdkLog.i("Hq008FlowMetric", "metric report " + message);
        apiClient.report(body);
    }

    private String buildDailyMetricsMessage(DailyStatsStore.Snapshot snapshot) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("day", snapshot.day);
        message.put("timezone", snapshot.timezone);
        message.put("screensaver_start_total", snapshot.screensaverStartTotal);
        message.put("screensaver_stop_total", snapshot.screensaverStopTotal);
        message.put("authorized_callback_total", snapshot.authorizedCallbackTotal);
        message.put("current_final_status", snapshot.currentFinalStatus);
        message.put("current_final_message", snapshot.currentFinalMessage);

        java.util.List<Map<String, Object>> finalStatusTotals = new java.util.ArrayList<>();
        for (DailyStatsStore.FinalStatusTotal item : snapshot.finalStatusTotals) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("status", item.status);
            row.put("message", item.message);
            row.put("total", item.total);
            finalStatusTotals.add(row);
        }
        message.put("final_status_totals", finalStatusTotals);
        return gson.toJson(message);
    }

    public void audit(String requestId, String finalEvent, String message, String traceJson) {
        DeviceInfo info = DeviceInfo.collect(appContext);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("channel_id", config.getChannelId());
        body.put("request_id", requestId);
        body.put("mac", info.mac.isEmpty() ? "00:00:00:00:00:00" : info.mac);
        body.put("ad_version", info.versionCode);
        body.put("ad_version_name", info.versionName);
        body.put("sdk_version", config.getAdSdkVersion());
        body.put("androidid", "unknown_device".equals(info.androidId) ? "" : info.androidId);
        body.put("event_type", finalEvent);
        body.put("event_message", message);
        body.put("ad_log", traceJson);
        apiClient.consentLog(body);
    }

    public void lifecycle(String event, String requestId) {
        report(requestId == null ? "lifecycle-" + java.util.UUID.randomUUID() : requestId,
                "AD_LIFECYCLE", event, java.util.Collections.emptyMap());
    }

    public void filled(String requestId, long createdAtMs) {
        Map<String, Object> diagnostics = new LinkedHashMap<>();
        diagnostics.put("createdAtMs", createdAtMs);
        diagnostics.put("filledAtMs", SystemClock.elapsedRealtime());
        report(requestId, "AD_FILLED", "FILLED", diagnostics);
    }

    public void requested(String requestId, long createdAtMs, Map<String, Object> extra) {
        Map<String, Object> diagnostics = new LinkedHashMap<>(extra);
        diagnostics.put("createdAtMs", createdAtMs);
        report(requestId, "AD_REQUESTED", "REQUESTED", diagnostics);
    }

    public void requested(String requestId, long createdAtMs) {
        Map<String, Object> diagnostics = new LinkedHashMap<>();
        diagnostics.put("createdAtMs", createdAtMs);
        report(requestId, "AD_REQUESTED", "REQUESTED", diagnostics);
    }

    public void loaded(String requestId, long createdAtMs) {
        long now = SystemClock.elapsedRealtime();
        Map<String, Object> diagnostics = new LinkedHashMap<>();
        diagnostics.put("createdAtMs", createdAtMs);
        diagnostics.put("loadedAtMs", now);
        diagnostics.put("requestToLoadMs", now - createdAtMs);
        report(requestId, "AD_LOADED", "LOADED", diagnostics);
    }

    public void started(String requestId, long createdAtMs, Double progress) {
        long now = SystemClock.elapsedRealtime();
        Map<String, Object> diagnostics = new LinkedHashMap<>();
        diagnostics.put("createdAtMs", createdAtMs);
        diagnostics.put("startedAtMs", now);
        diagnostics.put("requestToStartMs", now - createdAtMs);
        if (progress != null) {
            diagnostics.put("adBreakMaxDurationSeconds", progress);
        }
        report(requestId, "AD_STARTED", "STARTED", diagnostics);
    }

    public void completed(String requestId, long createdAtMs) {
        completed(requestId, createdAtMs, java.util.Collections.emptyMap());
    }

    public void completed(String requestId, long createdAtMs, Map<String, Object> extra) {
        long now = SystemClock.elapsedRealtime();
        Map<String, Object> diagnostics = new LinkedHashMap<>(extra);
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
        failed(requestId, createdAtMs, code, message, java.util.Collections.emptyMap());
    }

    public void failed(String requestId, long createdAtMs, Integer code, String message,
            Map<String, Object> extra) {
        long now = SystemClock.elapsedRealtime();
        Map<String, Object> diagnostics = new LinkedHashMap<>(extra);
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
        DeviceInfo deviceInfo = DeviceInfo.collect(appContext);
        Map<String, Object> diagnosticInfo = new LinkedHashMap<>();
        diagnosticInfo.put("flowSdkVersion", BuildConfig.SDK_VERSION_NAME);
        diagnosticInfo.put("flowSdkVersionCode", BuildConfig.SDK_VERSION_CODE);
        diagnosticInfo.put("flowSdkBuildTime", BuildConfig.SDK_BUILD_TIME);
        diagnosticInfo.put("adSdkVersion", config.getAdSdkVersion());
        diagnosticInfo.putAll(eventDiagnostics);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("request_id", requestId);
        body.put("event_type", eventType);
        body.put("uuid", deviceInfo.androidId);
        body.put("channel_id", config.getChannelId());
        body.put("mac", deviceInfo.mac.isEmpty() ? "00:00:00:00:00:00" : deviceInfo.mac);
        body.put("app_id", deviceInfo.packageName);
        body.put("make", deviceInfo.make);
        body.put("model", deviceInfo.model);
        body.put("ad_version", deviceInfo.versionCode);
        body.put("ad_version_name", deviceInfo.versionName);
        body.put("sdk_version", config.getAdSdkVersion());
        body.put("androidid", "unknown_device".equals(deviceInfo.androidId) ? "" : deviceInfo.androidId);
        body.put("message", message);
        body.put("diagnostic_info", gson.toJson(diagnosticInfo));
        if (!deviceInfo.localIp.isEmpty()) {
            body.put("local_ip", deviceInfo.localIp);
        }
        SdkLog.i(
                "Hq008FlowReport",
                "report event requestId=" + requestId
                        + " eventType=" + eventType
                        + " message=" + message
        );
        if (shouldQueueDurably(eventType)) {
            durableReports.enqueue(body);
        } else {
            apiClient.report(body);
        }
    }
    /** Freeze terminal completion with the existing request/error durable delivery policy. */
    static boolean shouldQueueDurably(String eventType) {
        return "AD_ERROR".equals(eventType)
                || "AD_REQUESTED".equals(eventType)
                || "AD_COMPLETED".equals(eventType);
    }

}
