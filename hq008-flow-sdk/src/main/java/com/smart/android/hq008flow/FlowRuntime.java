package com.smart.android.hq008flow;

import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import com.smart.android.hq008flow.internal.AdEventReporter;
import com.smart.android.hq008flow.internal.DailyStatsStore;
import com.smart.android.hq008flow.internal.DeviceInfo;
import com.smart.android.hq008flow.internal.FlowApiClient;
import com.smart.android.hq008flow.internal.FlowTrace;
import com.smart.android.hq008flow.internal.ScheduleStore;
import com.smart.android.hq008flow.internal.SdkLog;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

final class FlowRuntime {
    private static final String TAG = "Hq008FlowSdk";
    private static final long PLAYBACK_CLEANUP_MARGIN_MS = 5_000L;

    private final Context appContext;
    private final Hq008FlowConfig config;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final FlowApiClient apiClient;
    private final AdEventReporter reporter;
    private final ScheduleStore scheduleStore;
    private final DailyStatsStore dailyStatsStore;

    private boolean running;
    private Runnable scheduledRunnable;
    private Runnable timeoutRunnable;
    private Hq008AdCallback adCallback;
    private ActiveRun activeRun;

    FlowRuntime(Context context, Hq008FlowConfig config) {
        Context appContext = context.getApplicationContext();
        this.appContext = appContext;
        this.config = config;
        this.apiClient = new FlowApiClient(config.getApiBaseUrl());
        this.dailyStatsStore = new DailyStatsStore(appContext, config.getChannelId());
        this.reporter = new AdEventReporter(new FlowApiClient(config.getApiBaseUrl()), config, appContext);
        this.scheduleStore = new ScheduleStore(
                appContext,
                config.getChannelId(),
                config.getFallbackIntervalSeconds()
        );
    }

    void start() {
        runOnMain(() -> {
            if (running) {
                return;
            }
            running = true;
            dailyStatsStore.recordScreensaverStart(System.currentTimeMillis());
            long delayMs = scheduleStore.initialDelayMs(
                    System.currentTimeMillis(),
                    config.getInitialDelaySeconds()
            );
            SdkLog.i(TAG, "流程启动，channel=" + config.getChannelId() + "，首次延迟=" + delayMs + "ms");
            reporter.lifecycle("FLOW_START", null);
            schedule(delayMs);
        });
    }

    void stop() {
        runOnMain(() -> {
            if (!running && activeRun == null) {
                return;
            }
            boolean wasRunning = running;
            running = false;
            if (wasRunning) {
                dailyStatsStore.recordScreensaverStop(System.currentTimeMillis());
            }
            if (scheduledRunnable != null) {
                mainHandler.removeCallbacks(scheduledRunnable);
                scheduledRunnable = null;
            }
            clearTimeout();
            ActiveRun stopping = activeRun;
            activeRun = null;
            // Cancel old transport before calling host code: the release callback
            // may synchronously start and authorize another round.
            apiClient.cancelAll();
            if (stopping != null && stopping.adDispatched && !stopping.terminal) {
                stopping.terminal = true;
                cancelAd(stopping);
                reporter.failed(
                        stopping.requestId,
                        stopping.createdAtMs,
                        null,
                        "FLOW_STOPPED", stopping.diagnostics
                );
                recordFinalStatusAndReport(
                        stopping,
                        DailyStatsStore.STATUS_FLOW_STOPPED,
                        DailyStatsStore.STATUS_FLOW_STOPPED
                );
            }
            if (stopping != null) uploadTrace(stopping);
            reporter.lifecycle("FLOW_STOP", null);
            SdkLog.i(TAG, "流程已停止");
        });
    }

    void triggerNow() {
        runOnMain(() -> {
            if (!running) {
                SdkLog.w(TAG, "triggerNow 已忽略：流程尚未 start");
                return;
            }
            if (scheduledRunnable != null) {
                mainHandler.removeCallbacks(scheduledRunnable);
                scheduledRunnable = null;
            }
            triggerDue();
        });
    }

    void setAdCallback(Hq008AdCallback callback) {
        runOnMain(() -> {
            adCallback = callback;
            SdkLog.i(TAG, "广告回调已设置");
        });
    }

    void clearAdCallback() {
        runOnMain(() -> {
            adCallback = null;
            SdkLog.i(TAG, "广告回调已清除");
        });
    }

    void clearAdCallback(Hq008AdCallback callback) {
        runOnMain(() -> {
            if (adCallback != callback) {
                return;
            }
            adCallback = null;
            SdkLog.i(TAG, "广告回调已清除");
        });
    }

    private void triggerDue() {
        if (!running) {
            return;
        }
        // Match app's periodic polling: playback duration does not shift the next poll.
        scheduleStore.markTriggered(System.currentTimeMillis());
        schedule(scheduleStore.effectiveIntervalSeconds() * 1_000L);
        if (activeRun != null && !activeRun.terminal) {
            SdkLog.i(TAG, "上一轮流程尚未结束，本轮触发已跳过");
            return;
        }
        String localRequestId = "client-"
                + System.currentTimeMillis()
                + "-"
                + UUID.randomUUID().toString().substring(0, 8);
        ActiveRun run = new ActiveRun(
                UUID.randomUUID().toString(),
                localRequestId,
                SystemClock.elapsedRealtime()
        );
        activeRun = run;
        run.trace.record("FLOW_CONTROL_START", java.util.Collections.emptyMap());
        SdkLog.i(TAG, "开始 flow-control，requestId=" + localRequestId);
        apiClient.requestFlowControl(buildFlowControlBody(), (response, error) -> {
            if (!isActive(run)) {
                return;
            }
            if (response != null) {
                run.popupLogEnabled = response.popupLogEnabled;
                run.skipCmp = response.skipCmp;
                run.trace.setEnabled(response.popupLogEnabled);
                run.trace.record("FLOW_CONTROL_RESULT", java.util.Collections.singletonMap("enabled", response.enabled));
            }
            if (error != null) {
                SdkLog.w(TAG, "flow-control 失败，按关闭处理: " + safeMessage(error));
                finishRun(run);
            } else if (response == null || !response.enabled) {
                SdkLog.i(TAG, "flow-control enabled=false，本轮结束");
                finishRun(run);
            } else {
                SdkLog.i(TAG, "flow-control 通过，requestId=" + localRequestId);
                requestConsent(run);
            }
        });
    }

    private void requestConsent(ActiveRun run) {
        if (run.skipCmp) { requestAuthorize(run); return; }
        Hq008ConsentGate gate = config.getConsentGate();
        if (gate == null) {
            // Existing integrations complete CMP before start(); an installed gate moves it into this cycle.
            run.trace.record("CMP_HOST_MANAGED", java.util.Collections.emptyMap());
            requestAuthorize(run);
            return;
        }
        // A missing/late CMP callback must not permanently occupy the scheduler.
        timeoutRunnable = () -> {
            if (isActive(run)) {
                reporter.lifecycle("CMP_GATE_TIMEOUT", run.requestId);
                finishRun(run);
            }
        };
        mainHandler.postDelayed(timeoutRunnable, config.getAdCallbackTimeoutSeconds() * 1_000L);
        try {
            gate.evaluate(run.requestId, config.getChannelId(), allowed -> runOnMain(() -> {
                if (!isActive(run) || run.consentDecided) return;
                run.consentDecided = true;
                clearTimeout();
                if (allowed) requestAuthorize(run);
                else {
                    reporter.lifecycle("CMP_GATE_STOP", run.requestId);
                    finishRun(run);
                }
            }));
        } catch (Throwable error) {
            if (!isActive(run) || run.consentDecided) return;
            reporter.lifecycle("CMP_GATE_ERROR", run.requestId);
            finishRun(run);
        }
    }

    private void requestAuthorize(ActiveRun run) {
        run.trace.record("AUTHORIZE_START", java.util.Collections.emptyMap());
        SdkLog.i(TAG, "开始 authorize，requestId=" + run.requestId);
        apiClient.requestAuthorize(buildAuthorizeBody(run.requestId), (response, error) -> {
            if (!isActive(run)) {
                return;
            }
            if (error != null || response == null) {
                SdkLog.w(TAG, "authorize 失败: " + (error == null ? "empty response" : safeMessage(error)));
                finishRun(run);
                return;
            }
            long previousInterval = scheduleStore.effectiveIntervalSeconds();
            scheduleStore.updateServerInterval(response.nextRequestSeconds);
            if (previousInterval != scheduleStore.effectiveIntervalSeconds()) {
                schedule(scheduleStore.effectiveIntervalSeconds() * 1_000L);
            }
            run.trace.record("AUTHORIZE_RESULT", java.util.Collections.singletonMap("authorized", response.authorized));
            if (response.requestId != null && !response.requestId.trim().isEmpty()) {
                run.requestId = response.requestId;
            }
            SdkLog.i(
                    TAG,
                    "authorize 返回 requestId=" + run.requestId
                            + " authorized=" + response.authorized
                            + " next_request_seconds=" + response.nextRequestSeconds
            );
            if (!response.authorized) {
                SdkLog.i(TAG, "authorize authorized=false，本轮结束");
                finishRun(run);
                return;
            }
            long seconds = response.callbackTimeoutSeconds;
            run.callbackTimeoutMs = (seconds >= 30L && seconds <= 600L
                    ? seconds : config.getAdCallbackTimeoutSeconds()) * 1_000L;
            run.authorization = new Hq008Authorization(response.values, run.requestId,
                    config.getChannelId(), run.callbackTimeoutMs, run.popupLogEnabled, run.skipCmp);
            dispatchToAdCallback(run);
        });
    }

    private void dispatchToAdCallback(ActiveRun run) {
        Hq008AdCallback currentCallback = adCallback;
        if (currentCallback == null) {
            SdkLog.i(TAG, "authorize 已通过，但当前没有广告回调，本轮记为 NO_AD_CALLBACK");
            if (!markTerminal(run)) {
                return;
            }
            reporter.failed(run.requestId, run.createdAtMs, null, "NO_AD_CALLBACK");
            recordFinalStatusAndReport(
                    run,
                    DailyStatsStore.STATUS_NO_AD_CALLBACK,
                    DailyStatsStore.STATUS_NO_AD_CALLBACK
            );
            finishRun(run);
            return;
        }
        run.adDispatched = true;
        run.deadlineMs = SystemClock.elapsedRealtime() + run.callbackTimeoutMs + PLAYBACK_CLEANUP_MARGIN_MS;
        dailyStatsStore.recordAuthorizedCallback(System.currentTimeMillis());
        reporter.lifecycle("AD_AUTHORIZED", run.requestId);
        armTimeout(run);
        Hq008AdSession session = new Hq008AdSession(new Hq008AdSession.Delegate() {
            @Override public void runtimeEvent(String event, Map<String, Object> fields) {
                Map<String, Object> snapshot = new LinkedHashMap<>(fields);
                runOnMain(() -> { if (acceptsFinalEvidence(run)) recordRuntimeEvent(run, event, snapshot); });
            }
            @Override public void runtimeAudit(String summaryJson) {
                runOnMain(() -> { if (acceptsFinalEvidence(run)) run.trace.audit(summaryJson); });
            }
            @Override public void cancellationHandler(Runnable handler) {
                runOnMain(() -> {
                    if (isActive(run) && !run.terminal) run.cancellationHandler = handler;
                    else if (handler != null) handler.run();
                });
            }
            @Override public void requested(Map<String, Object> values) {
                Map<String, Object> snapshot = new LinkedHashMap<>(values);
                runOnMain(() -> {
                    // Real response headers retain their frozen owner even after terminal delivery.
                    Map<String, Object> confirmed = run.takeRequestedReport(snapshot);
                    if (confirmed == null) return;
                    if (!run.traceUploaded) run.trace.record("AD_REQUESTED", snapshot);
                    reporter.requested(run.requestId, run.createdAtMs, confirmed);
                });
            }
            @Override public void filled() {
                runOnMain(() -> {
                    if (!isActive(run) || run.terminal || run.filled) return;
                    run.filled = true;
                    run.diagnostics.put("filled", true);
                    reporter.filled(run.requestId, run.createdAtMs);
                });
            }
            @Override public void diagnostics(Map<String, Object> values) {
                Map<String, Object> snapshot = new LinkedHashMap<>(values);
                runOnMain(() -> {
                    if (acceptsFinalEvidence(run)) run.diagnostics.putAll(snapshot);
                });
            }
            @Override public void extendPlaybackDeadline(long deadlineMs) {
                runOnMain(() -> {
                    if (!isActive(run) || run.terminal || deadlineMs <= SystemClock.elapsedRealtime()) return;
                    run.deadlineMs = Math.max(run.deadlineMs,
                            deadlineMs > Long.MAX_VALUE - PLAYBACK_CLEANUP_MARGIN_MS
                                    ? Long.MAX_VALUE : deadlineMs + PLAYBACK_CLEANUP_MARGIN_MS);
                    armTimeout(run);
                });
            }
            @Override
            public void loaded() {
                runOnMain(() -> onLoaded(run));
            }

            @Override
            public void started(Double progress) {
                runOnMain(() -> onStarted(run, progress));
            }

            @Override
            public void completed() {
                runOnMain(() -> onCompleted(run));
            }

            @Override
            public void failed(Integer code, String message) {
                runOnMain(() -> onFailed(run, code, message));
            }
        }, run.authorization);
        try {
            currentCallback.onAdAuthorized(session);
        } catch (Throwable error) {
            onFailed(run, null, "AD_CALLBACK_ERROR:" + safeMessage(error));
        }
    }

    private void onLoaded(ActiveRun run) {
        if (!isActive(run) || run.terminal || run.loaded) {
            return;
        }
        run.loaded = true;
        run.trace.record("AD_LOADED", java.util.Collections.emptyMap());
        SdkLog.i(TAG, "广告已加载，requestId=" + run.requestId);
        reporter.loaded(run.requestId, run.createdAtMs);
    }

    private void onStarted(ActiveRun run, Double progress) {
        if (!isActive(run) || run.terminal || run.started) {
            return;
        }
        run.started = true;
        run.trace.record("AD_STARTED", java.util.Collections.emptyMap());
        SdkLog.i(
                TAG,
                "广告开始播放，requestId=" + run.requestId
                        + (progress == null ? "" : " adBreakMaxDurationSeconds=" + progress)
        );
        reporter.started(run.requestId, run.createdAtMs, progress);
    }

    private void onCompleted(ActiveRun run) {
        if (!markTerminal(run)) {
            return;
        }
        cancelAd(run);
        SdkLog.i(TAG, "广告播放完成，requestId=" + run.requestId);
        reporter.completed(run.requestId, run.createdAtMs, run.diagnostics);
        recordFinalStatusAndReport(
                run,
                DailyStatsStore.STATUS_COMPLETED,
                DailyStatsStore.STATUS_COMPLETED
        );
        finishRun(run);
    }

    private void onFailed(ActiveRun run, Integer code, String message) {
        if (!markTerminal(run)) {
            return;
        }
        cancelAd(run);
        String resolvedMessage = message == null || message.trim().isEmpty()
                ? "AD_ERROR"
                : message;
        SdkLog.w(
                TAG,
                "广告播放失败，requestId=" + run.requestId
                        + " code=" + code
                        + " message=" + resolvedMessage
        );
        reporter.failed(run.requestId, run.createdAtMs, code, resolvedMessage, run.diagnostics);
        recordFinalStatusAndReport(
                run,
                DailyStatsStore.STATUS_FAILED,
                finalStatusMessage(code, resolvedMessage)
        );
        finishRun(run);
    }

    private void armTimeout(ActiveRun run) {
        clearTimeout();
        timeoutRunnable = () -> {
            if (!markTerminal(run)) {
                return;
            }
            cancelAd(run);
            SdkLog.w(TAG, "广告回调超时，requestId=" + run.requestId);
            reporter.failed(run.requestId, run.createdAtMs, null, "TIMEOUT", run.diagnostics);
            recordFinalStatusAndReport(
                    run,
                    DailyStatsStore.STATUS_TIMEOUT,
                    DailyStatsStore.STATUS_TIMEOUT
            );
            finishRun(run);
        };
        mainHandler.postDelayed(
                timeoutRunnable,
                Math.max(1L, (run.deadlineMs > 0L ? run.deadlineMs
                        : run.createdAtMs + run.callbackTimeoutMs) - SystemClock.elapsedRealtime())
        );
    }

    private void finishRun(ActiveRun run) {
        // Evidence still belongs to this frozen run when a cancellation callback
        // has already started the next run. Never clear the next run's timeout.
        uploadTrace(run);
        if (activeRun == null || !activeRun.token.equals(run.token)) {
            return;
        }
        clearTimeout();
        run.cancellationHandler = null;
        activeRun = null;
        if (running && scheduledRunnable == null) {
            schedule(scheduleStore.effectiveIntervalSeconds() * 1_000L);
        }
    }

    private void recordRuntimeEvent(ActiveRun run, String type, Map<String, Object> fields) {
        String event;
        switch (type) {
            case "tag_ready": event = "AD_TCL_TAG_READY"; break;
            case "patch_request": event = "AD_GAM_PATCH_REQUEST"; break;
            case "patch_ready": event = "AD_GAM_PATCH_READY"; break;
            case "filled": event = "AD_FILLED"; break;
            case "material_selected": event = "AD_SEGMENT_SELECTED"; break;
            case "first_frame": event = "AD_FIRST_FRAME"; break;
            case "sdk_event": event = "AD_TCL_EVENT"; break;
            default: event = type;
        }
        run.trace.record(event, fields);
        if ("patch_ready".equals(type)) {
            Map<String, Object> request = new LinkedHashMap<>(fields);
            request.put("request_id", run.requestId);
            request.put("channel_id", config.getChannelId());
            run.trace.record("AD_SDK_REQUEST", request);
        }
    }

    private void cancelAd(ActiveRun run) {
        Runnable handler = run.cancellationHandler;
        run.cancellationHandler = null;
        if (handler != null) {
            try { handler.run(); }
            catch (Throwable error) { SdkLog.w(TAG, "释放广告失败", error); }
        }
    }

    private void uploadTrace(ActiveRun run) {
        if (run.traceUploaded || !run.trace.isEnabled()) return;
        run.traceUploaded = true;
        String event = "COMPLETED".equals(run.finalStatus) ? "AD_COMPLETED"
                : "FLOW_FINISHED".equals(run.finalStatus) ? "FLOW_FINISHED" : "AD_ERROR";
        reporter.audit(run.requestId, event, run.finalMessage,
                run.trace.finish(run.requestId, config.getChannelId(), event, run.finalMessage));
    }

    private boolean markTerminal(ActiveRun run) {
        if (!isCurrentRun(run) || run.terminal) {
            return false;
        }
        run.terminal = true;
        clearTimeout();
        return true;
    }

    private boolean acceptsFinalEvidence(ActiveRun run) {
        return !run.traceUploaded && (isCurrentRun(run) || (run.adDispatched && run.terminal));
    }

    private boolean isActive(ActiveRun run) {
        return running && isCurrentRun(run);
    }

    private boolean isCurrentRun(ActiveRun run) {
        return activeRun != null && activeRun.token.equals(run.token);
    }

    private void schedule(long delayMs) {
        if (scheduledRunnable != null) {
            mainHandler.removeCallbacks(scheduledRunnable);
        }
        scheduledRunnable = () -> {
            scheduledRunnable = null;
            triggerDue();
        };
        mainHandler.postDelayed(scheduledRunnable, Math.max(0L, delayMs));
        SdkLog.i(TAG, "下一轮已安排，delay=" + delayMs + "ms");
    }

    private void clearTimeout() {
        if (timeoutRunnable != null) {
            mainHandler.removeCallbacks(timeoutRunnable);
            timeoutRunnable = null;
        }
    }

    private void recordFinalStatusAndReport(ActiveRun run, String status, String message) {
        run.finalStatus = status;
        run.finalMessage = message;
        DailyStatsStore.Snapshot snapshot = dailyStatsStore.recordFinalStatus(
                status,
                message,
                System.currentTimeMillis()
        );
        reporter.dailyMetrics(run.requestId, snapshot);
    }

    private String finalStatusMessage(Integer code, String message) {
        if (code == null) {
            return message;
        }
        return "code=" + code + ",message=" + message;
    }

    private Map<String, Object> buildFlowControlBody() {
        DeviceInfo deviceInfo = DeviceInfo.collect(appContext);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("channel_id", config.getChannelId());
        body.put("mac", deviceInfo.mac);
        body.put("ad_version", deviceInfo.versionCode);
        body.put("android_sdk_version", Build.VERSION.SDK_INT);
        appendRequestFields(body, deviceInfo);
        return body;
    }

    private Map<String, Object> buildAuthorizeBody(String localRequestId) {
        DeviceInfo deviceInfo = DeviceInfo.collect(appContext);
        Map<String, Object> body = new LinkedHashMap<>();
        appendRequestFields(body, deviceInfo);
        body.put("request_id", localRequestId);
        body.put("uuid", deviceInfo.uuid);
        body.put("channel_id", config.getChannelId());
        body.put("ad_version", deviceInfo.versionCode);
        body.put("app_id", deviceInfo.packageName);
        body.put("app_name", config.getAppName());
        body.put("bundle", deviceInfo.packageName);
        body.put("ua", deviceInfo.userAgent);
        body.put("ifa", deviceInfo.uuid);
        body.put("make", deviceInfo.make);
        body.put("model", deviceInfo.model);
        body.put("os", "Android");
        body.put("osv", deviceInfo.osVersion);
        body.put("language", deviceInfo.language);
        body.put("video_w", deviceInfo.screenWidth);
        body.put("video_h", deviceInfo.screenHeight);
        body.put("screen_w", deviceInfo.realScreenWidth);
        body.put("screen_h", deviceInfo.realScreenHeight);
        body.put("local_ip", deviceInfo.localIp);
        body.put("mac", deviceInfo.mac);
        return body;
    }

    private void appendRequestFields(Map<String, Object> body, DeviceInfo deviceInfo) {
        body.put("androidid", "unknown_device".equals(deviceInfo.androidId) ? "" : deviceInfo.androidId);
        body.put("ad_version_name", deviceInfo.versionName);
        body.put("sdk_version", config.getAdSdkVersion());
    }

    private void runOnMain(Runnable action) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action.run();
        } else {
            mainHandler.post(action);
        }
    }

    private String safeMessage(Throwable error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }

    static final class ActiveRun {
        final String token;
        String requestId;
        final long createdAtMs;
        final FlowTrace trace;
        String finalStatus = "FLOW_FINISHED";
        String finalMessage = "FLOW_FINISHED";
        boolean traceUploaded;
        Runnable cancellationHandler;
        Hq008Authorization authorization;
        boolean consentDecided;
        boolean popupLogEnabled = true;
        boolean skipCmp;
        long callbackTimeoutMs;
        long deadlineMs;
        boolean requested;
        boolean filled;
        final Map<String, Object> diagnostics = new LinkedHashMap<>();
        boolean adDispatched;
        boolean loaded;
        boolean started;
        boolean terminal;

        Map<String, Object> takeRequestedReport(Map<String, Object> responseDiagnostics) {
            if (requested) return null;
            requested = true;
            diagnostics.putAll(responseDiagnostics);
            return new LinkedHashMap<>(diagnostics);
        }

        ActiveRun(String token, String requestId, long createdAtMs) {
            this.token = token;
            this.requestId = requestId;
            this.createdAtMs = createdAtMs;
            this.trace = new FlowTrace(createdAtMs);
        }
    }
}
