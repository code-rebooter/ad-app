package com.smart.android.hq008flow;

import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import com.smart.android.hq008flow.internal.AdEventReporter;
import com.smart.android.hq008flow.internal.DeviceInfo;
import com.smart.android.hq008flow.internal.FlowApiClient;
import com.smart.android.hq008flow.internal.ScheduleStore;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

final class FlowRuntime {
    private static final String TAG = "Hq008FlowSdk";

    private final Hq008FlowConfig config;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final DeviceInfo deviceInfo;
    private final FlowApiClient apiClient;
    private final AdEventReporter reporter;
    private final ScheduleStore scheduleStore;

    private boolean running;
    private boolean pendingTrigger;
    private Runnable scheduledRunnable;
    private Runnable timeoutRunnable;
    private Hq008AdHost adHost;
    private ActiveRun activeRun;

    FlowRuntime(Context context, Hq008FlowConfig config) {
        Context appContext = context.getApplicationContext();
        this.config = config;
        this.deviceInfo = DeviceInfo.collect(appContext);
        this.apiClient = new FlowApiClient(config.getApiBaseUrl());
        this.reporter = new AdEventReporter(apiClient, config, deviceInfo);
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
            long delayMs = scheduleStore.initialDelayMs(
                    System.currentTimeMillis(),
                    config.getInitialDelaySeconds()
            );
            Log.i(TAG, "流程启动，channel=" + config.getChannelId() + "，首次延迟=" + delayMs + "ms");
            schedule(delayMs);
        });
    }

    void stop() {
        runOnMain(() -> {
            if (!running && activeRun == null) {
                return;
            }
            running = false;
            pendingTrigger = false;
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
            }
            activeRun = null;
            apiClient.cancelAll();
            Log.i(TAG, "流程已停止");
        });
    }

    void triggerNow() {
        runOnMain(() -> {
            if (!running) {
                Log.w(TAG, "triggerNow 已忽略：流程尚未 start");
                return;
            }
            if (scheduledRunnable != null) {
                mainHandler.removeCallbacks(scheduledRunnable);
                scheduledRunnable = null;
            }
            triggerDue();
        });
    }

    void attachAdHost(Hq008AdHost host) {
        runOnMain(() -> {
            adHost = host;
            Log.i(TAG, "广告 UI 已绑定");
            if (running && pendingTrigger && activeRun == null) {
                pendingTrigger = false;
                if (scheduledRunnable != null) {
                    mainHandler.removeCallbacks(scheduledRunnable);
                    scheduledRunnable = null;
                }
                triggerDue();
            }
        });
    }

    void detachAdHost(Hq008AdHost host) {
        runOnMain(() -> {
            if (adHost != host) {
                return;
            }
            adHost = null;
            Log.i(TAG, "广告 UI 已解绑");
        });
    }

    private void triggerDue() {
        if (!running) {
            return;
        }
        if (activeRun != null) {
            Log.i(TAG, "上一轮流程尚未结束，本轮触发已跳过");
            return;
        }
        if (adHost == null) {
            pendingTrigger = true;
            Log.i(TAG, "当前没有绑定的广告 UI，等待 UI attach 后再执行");
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
        Log.i(TAG, "开始 flow-control，requestId=" + localRequestId);
        apiClient.requestFlowControl(buildFlowControlBody(), (response, error) -> {
            if (!isActive(run)) {
                return;
            }
            if (error != null) {
                Log.w(TAG, "flow-control 失败，按关闭处理: " + error.getMessage());
                finishRun(run);
            } else if (response == null || !response.enabled) {
                Log.i(TAG, "flow-control enabled=false，本轮结束");
                finishRun(run);
            } else {
                requestAuthorize(run);
            }
        });
    }

    private void requestAuthorize(ActiveRun run) {
        Log.i(TAG, "开始 authorize，requestId=" + run.requestId);
        apiClient.requestAuthorize(buildAuthorizeBody(run.requestId), (response, error) -> {
            if (!isActive(run)) {
                return;
            }
            if (error != null || response == null) {
                Log.w(TAG, "authorize 失败: " + (error == null ? "empty response" : error.getMessage()));
                finishRun(run);
                return;
            }
            scheduleStore.updateServerInterval(response.nextRequestSeconds);
            if (response.requestId != null && !response.requestId.trim().isEmpty()) {
                run.requestId = response.requestId;
            }
            if (!response.authorized) {
                Log.i(TAG, "authorize authorized=false，本轮结束");
                finishRun(run);
                return;
            }
            dispatchToAdHost(run);
        });
    }

    private void dispatchToAdHost(ActiveRun run) {
        Hq008AdHost currentHost = adHost;
        if (currentHost == null) {
            pendingTrigger = true;
            finishRun(run);
            return;
        }
        run.adDispatched = true;
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
            currentHost.onAdAuthorized(session);
        } catch (Throwable error) {
            onFailed(run, null, "AD_HOST_CALLBACK_ERROR:" + safeMessage(error));
        }
    }

    private void onLoaded(ActiveRun run) {
        if (!isActive(run) || run.terminal || run.loaded) {
            return;
        }
        run.loaded = true;
        reporter.loaded(run.requestId, run.createdAtMs);
    }

    private void onStarted(ActiveRun run, Double progress) {
        if (!isActive(run) || run.terminal || run.started) {
            return;
        }
        run.started = true;
        reporter.started(run.requestId, run.createdAtMs, progress);
    }

    private void onCompleted(ActiveRun run) {
        if (!markTerminal(run)) {
            return;
        }
        reporter.completed(run.requestId, run.createdAtMs);
        finishRun(run);
    }

    private void onFailed(ActiveRun run, Integer code, String message) {
        if (!markTerminal(run)) {
            return;
        }
        String resolvedMessage = message == null || message.trim().isEmpty()
                ? "AD_ERROR"
                : message;
        reporter.failed(run.requestId, run.createdAtMs, code, resolvedMessage);
        finishRun(run);
    }

    private void armTimeout(ActiveRun run) {
        clearTimeout();
        timeoutRunnable = () -> {
            if (!markTerminal(run)) {
                return;
            }
            reporter.failed(run.requestId, run.createdAtMs, null, "TIMEOUT");
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
        if (!isActive(run) || run.terminal) {
            return false;
        }
        run.terminal = true;
        return true;
    }

    private boolean isActive(ActiveRun run) {
        return running && activeRun != null && activeRun.token.equals(run.token);
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
        Log.i(TAG, "下一轮已安排，delay=" + delayMs + "ms");
    }

    private void clearTimeout() {
        if (timeoutRunnable != null) {
            mainHandler.removeCallbacks(timeoutRunnable);
            timeoutRunnable = null;
        }
    }

    private Map<String, Object> buildFlowControlBody() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("channel_id", config.getChannelId());
        body.put("mac", deviceInfo.mac);
        body.put("app_version", deviceInfo.versionName);
        body.put("ad_version", deviceInfo.versionCode);
        body.put("android_sdk_version", Build.VERSION.SDK_INT);
        return body;
    }

    private Map<String, Object> buildAuthorizeBody(String localRequestId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("request_id", localRequestId);
        body.put("uuid", deviceInfo.uuid);
        body.put("channel_id", config.getChannelId());
        body.put("app_version", deviceInfo.versionName);
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
        body.put("screen_w", deviceInfo.screenWidth);
        body.put("screen_h", deviceInfo.screenHeight);
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
