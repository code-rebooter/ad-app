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
import com.smart.android.hq008flow.internal.ScheduleStore;
import com.smart.android.hq008flow.internal.SdkLog;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

final class FlowRuntime {
    private static final String TAG = "Hq008FlowSdk";

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
        this.reporter = new AdEventReporter(apiClient, config, appContext);
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
            if (activeRun != null && activeRun.adDispatched && !activeRun.terminal) {
                activeRun.terminal = true;
                reporter.failed(
                        activeRun.requestId,
                        activeRun.createdAtMs,
                        null,
                        "FLOW_STOPPED"
                );
                recordFinalStatusAndReport(
                        activeRun,
                        DailyStatsStore.STATUS_FLOW_STOPPED,
                        DailyStatsStore.STATUS_FLOW_STOPPED
                );
            }
            activeRun = null;
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
        if (activeRun != null) {
            SdkLog.i(TAG, "上一轮流程尚未结束，本轮触发已跳过");
            return;
        }

        scheduleStore.markTriggered(System.currentTimeMillis());
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
        SdkLog.i(TAG, "开始 flow-control，requestId=" + localRequestId);
        apiClient.requestFlowControl(buildFlowControlBody(), (response, error) -> {
            if (!isActive(run)) {
                return;
            }
            if (error != null) {
                SdkLog.w(TAG, "flow-control 失败，按关闭处理: " + safeMessage(error));
                finishRun(run);
            } else if (response == null || !response.enabled) {
                SdkLog.i(TAG, "flow-control enabled=false，本轮结束");
                finishRun(run);
            } else {
                SdkLog.i(TAG, "flow-control 通过，requestId=" + localRequestId);
                requestAuthorize(run);
            }
        });
    }

    private void requestAuthorize(ActiveRun run) {
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
            scheduleStore.updateServerInterval(response.nextRequestSeconds);
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
        dailyStatsStore.recordAuthorizedCallback(System.currentTimeMillis());
        reporter.requested(run.requestId, run.createdAtMs);
        armTimeout(run);
        Hq008AdSession session = new Hq008AdSession(new Hq008AdSession.Delegate() {
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
        });
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
        SdkLog.i(TAG, "广告已加载，requestId=" + run.requestId);
        reporter.loaded(run.requestId, run.createdAtMs);
    }

    private void onStarted(ActiveRun run, Double progress) {
        if (!isActive(run) || run.terminal || run.started) {
            return;
        }
        run.started = true;
        SdkLog.i(
                TAG,
                "广告开始播放，requestId=" + run.requestId
                        + (progress == null ? "" : " progress=" + progress)
        );
        reporter.started(run.requestId, run.createdAtMs, progress);
    }

    private void onCompleted(ActiveRun run) {
        if (!markTerminal(run)) {
            return;
        }
        SdkLog.i(TAG, "广告播放完成，requestId=" + run.requestId);
        reporter.completed(run.requestId, run.createdAtMs);
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
        String resolvedMessage = message == null || message.trim().isEmpty()
                ? "AD_ERROR"
                : message;
        SdkLog.w(
                TAG,
                "广告播放失败，requestId=" + run.requestId
                        + " code=" + code
                        + " message=" + resolvedMessage
        );
        reporter.failed(run.requestId, run.createdAtMs, code, resolvedMessage);
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
            SdkLog.w(TAG, "广告回调超时，requestId=" + run.requestId);
            reporter.failed(run.requestId, run.createdAtMs, null, "TIMEOUT");
            recordFinalStatusAndReport(
                    run,
                    DailyStatsStore.STATUS_TIMEOUT,
                    DailyStatsStore.STATUS_TIMEOUT
            );
            finishRun(run);
        };
        mainHandler.postDelayed(
                timeoutRunnable,
                config.getAdCallbackTimeoutSeconds() * 1_000L
        );
    }

    private void finishRun(ActiveRun run) {
        if (activeRun == null || !activeRun.token.equals(run.token)) {
            return;
        }
        clearTimeout();
        activeRun = null;
        if (running) {
            schedule(scheduleStore.effectiveIntervalSeconds() * 1_000L);
        }
    }

    private boolean markTerminal(ActiveRun run) {
        if (!isCurrentRun(run) || run.terminal) {
            return false;
        }
        run.terminal = true;
        return true;
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
        return body;
    }

    private Map<String, Object> buildAuthorizeBody(String localRequestId) {
        DeviceInfo deviceInfo = DeviceInfo.collect(appContext);
        Map<String, Object> body = new LinkedHashMap<>();
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

    private static final class ActiveRun {
        final String token;
        String requestId;
        final long createdAtMs;
        boolean adDispatched;
        boolean loaded;
        boolean started;
        boolean terminal;

        ActiveRun(String token, String requestId, long createdAtMs) {
            this.token = token;
            this.requestId = requestId;
            this.createdAtMs = createdAtMs;
        }
    }
}
