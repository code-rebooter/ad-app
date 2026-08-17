package com.smart.android.adsdk.internal;

import android.content.Context;
import android.os.Build;
import com.google.gson.Gson;
import com.smart.android.adsdk.AdError;
import com.smart.android.adsdk.AdResult;
import com.smart.android.adsdk.AdResultStatus;
import java.util.LinkedHashMap;
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
    private final boolean enabled;
    private final Clock clock;

    Hq008AdReporter(
        Context context,
        OkHttpClient okHttpClient,
        Gson gson,
        String channelId,
        String apiBaseUrl
    ) {
        this(context, okHttpClient, gson, channelId, apiBaseUrl, new SystemClockClock());
    }

    Hq008AdReporter(
        Context context,
        OkHttpClient okHttpClient,
        Gson gson,
        String channelId,
        String apiBaseUrl,
        Clock clock
    ) {
        this.context = context;
        this.okHttpClient = okHttpClient;
        this.gson = gson;
        this.channelId = channelId;
        String normalizedBaseUrl = apiBaseUrl.endsWith("/") ? apiBaseUrl : apiBaseUrl + "/";
        this.reportUrl = normalizedBaseUrl + "api/v2/ad/report";
        this.enabled = true;
        this.clock = clock;
    }

    Hq008AdReporter() {
        this.context = null;
        this.okHttpClient = null;
        this.gson = null;
        this.channelId = "";
        this.reportUrl = "";
        this.enabled = false;
        this.clock = () -> 0L;
    }

    void requested(String requestId, long createdAtMs, int width, int height) {
        Map<String, Object> diagnostics = baseDiagnostics(createdAtMs);
        diagnostics.put("containerWidth", width);
        diagnostics.put("containerHeight", height);
        diagnostics.put("sdk", "ad-sdk-gam-vast");
        report(requestId, EVENT_PROGRESS, "REQUESTED", diagnostics);
    }

    void loaded(String requestId, long createdAtMs) {
        long now = clock.elapsedRealtime();
        Map<String, Object> diagnostics = baseDiagnostics(createdAtMs);
        diagnostics.put("loadedAtMs", now);
        diagnostics.put("requestToLoadMs", now - createdAtMs);
        report(requestId, EVENT_PROGRESS, "LOADED", diagnostics);
    }

    void started(String requestId, long createdAtMs) {
        long now = clock.elapsedRealtime();
        Map<String, Object> diagnostics = baseDiagnostics(createdAtMs);
        diagnostics.put("startedAtMs", now);
        diagnostics.put("requestToStartMs", now - createdAtMs);
        report(requestId, EVENT_PROGRESS, "STARTED", diagnostics);
    }

    void finished(String requestId, long createdAtMs, AdResult result) {
        if (result.getStatus() == AdResultStatus.COMPLETED) {
            completed(requestId, createdAtMs);
        } else if (result.getStatus() == AdResultStatus.ERROR) {
            AdError error = result.getError();
            failed(
                requestId,
                createdAtMs,
                error == null ? null : error.getCode().ordinal(),
                error == null ? "AD_ERROR" : error.getMessage()
            );
        } else if (result.getStatus() == AdResultStatus.SKIPPED) {
            failed(requestId, createdAtMs, null, result.getReason());
        } else if (result.getStatus() == AdResultStatus.CANCELLED) {
            failed(requestId, createdAtMs, null, "CANCELLED");
        }
    }

    private void completed(String requestId, long createdAtMs) {
        long now = clock.elapsedRealtime();
        Map<String, Object> diagnostics = baseDiagnostics(createdAtMs);
        diagnostics.put("finishedAtMs", now);
        diagnostics.put("totalDurationMs", now - createdAtMs);
        report(requestId, EVENT_COMPLETED, "COMPLETED", diagnostics);
    }

    private void failed(String requestId, long createdAtMs, Integer code, String message) {
        long now = clock.elapsedRealtime();
        Map<String, Object> diagnostics = baseDiagnostics(createdAtMs);
        diagnostics.put("failedAtMs", now);
        diagnostics.put("totalDurationMs", now - createdAtMs);
        if (code != null) {
            diagnostics.put("errorCode", code);
        }
        report(requestId, EVENT_ERROR, normalizeMessage(message), diagnostics);
    }

    private Map<String, Object> baseDiagnostics(long createdAtMs) {
        Map<String, Object> diagnostics = new LinkedHashMap<>();
        diagnostics.put("createdAtMs", createdAtMs);
        diagnostics.put("sdkEntry", "self_vast");
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
        if (requestId == null || requestId.trim().isEmpty()) {
            return;
        }
        if (!enabled) {
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
        body.put("message", message);
        body.put("diagnostic_info", gson.toJson(diagnostics));
        if (!deviceInfo.localIp.isEmpty()) {
            body.put("local_ip", deviceInfo.localIp);
        }
        Request request = new Request.Builder()
            .url(reportUrl)
            .post(RequestBody.create(JSON, gson.toJson(body)))
            .header("Accept", "application/json")
            .build();
        Call call = okHttpClient.newCall(request);
        call.enqueue(new okhttp3.Callback() {
            @Override
            public void onFailure(Call call, java.io.IOException error) {
            }

            @Override
            public void onResponse(Call call, okhttp3.Response response) {
                response.close();
            }
        });
    }

    private String normalizeMessage(String message) {
        return message == null || message.trim().isEmpty() ? "AD_ERROR" : message.trim();
    }
}
