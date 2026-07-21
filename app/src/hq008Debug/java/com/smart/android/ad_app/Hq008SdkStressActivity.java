package com.smart.android.ad_app;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Debug;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.tcl.ff.component.overseabase.base.constant.AdReportSwitchConfig;
import com.tcl.ff.component.overseabase.base.constant.AdType;
import com.tcl.ff.component.overseabasebusiness.requestparams.RequestParams;
import com.tcl.ff.component.vastad.Ad;
import com.tcl.ff.component.vastad.Controller;
import com.tcl.ff.component.vastad.Initialization;
import com.tcl.ff.component.vastad.MediaAdInitListener;
import com.tcl.ff.component.vastad.core.callbacks.AdStatusListener;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Debug-only black-box reproducer for the customer screensaver's TCL ad path.
 * It intentionally keeps the customer lifecycle as the default mode: the
 * controller is replaced for every request and is only released on Activity
 * teardown. Immediate-release and UI-only modes provide controls for isolating
 * the source of a freeze.
 */
public final class Hq008SdkStressActivity extends Activity {
    private static final String TAG = "Hq008SdkStress";
    private static final long HEARTBEAT_MS = 500L;
    private static final long WATCHDOG_MS = 1000L;
    private static final long STALL_THRESHOLD_MS = 2500L;
    private static final long UI_ONLY_DURATION_MS = 2500L;
    private static final int MAX_STATUS_LINES = 90;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final StringBuilder statusBuffer = new StringBuilder();
    private final Runnable nextRequestRunnable = this::requestAd;

    private FrameLayout adContainer;
    private ImageView imageA;
    private ImageView imageB;
    private View heartbeatView;
    private TextView heartbeatText;
    private TextView countersView;
    private TextView statusView;
    private EditText intervalInput;
    private EditText maxRequestsInput;
    private Button modeButton;
    private Button startButton;
    private Button stopButton;
    private Button requestButton;

    private Hq008StressRunState.Mode selectedMode = Hq008StressRunState.Mode.CUSTOMER_LIFECYCLE;
    private Hq008StressRunState runState;
    private RequestContext activeRequest;
    private Controller latestController;
    private int requestSequence;
    private int terminalCount;
    private int loadedCount;
    private int errorCount;
    private int finishedCount;
    private int containerErrorCount;
    private long lastHeartbeatElapsed;
    private long lastWatchdogReportElapsed;
    private boolean destroyed;
    private ScheduledExecutorService diagnostics;
    private PrintWriter csvWriter;

    private final Runnable heartbeatRunnable = new Runnable() {
        @Override
        public void run() {
            if (destroyed) {
                return;
            }
            lastHeartbeatElapsed = SystemClock.elapsedRealtime();
            int sequence = Integer.parseInt(String.valueOf(heartbeatText.getTag() == null
                    ? "0" : heartbeatText.getTag()));
            sequence++;
            heartbeatText.setTag(String.valueOf(sequence));
            heartbeatText.setText("主线程心跳 #" + sequence + "  " + System.currentTimeMillis());
            if (sequence % 10 == 0) {
                Log.i(TAG, "HEARTBEAT seq=" + sequence);
            }
            heartbeatView.setBackgroundColor(sequence % 2 == 0
                    ? Color.rgb(48, 180, 108) : Color.rgb(54, 125, 220));
            mainHandler.postDelayed(this, HEARTBEAT_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().addFlags(128); // FLAG_KEEP_SCREEN_ON
        buildUi();
        openDiagnostics();
        initializeTclSdk();
        lastHeartbeatElapsed = SystemClock.elapsedRealtime();
        mainHandler.post(heartbeatRunnable);
        mainHandler.postDelayed(() -> {
            if (!destroyed && (runState == null || !runState.isRunning())) {
                appendStatus("自动开始默认压测");
                startRun();
            }
        }, 4000L);
    }

    @Override
    protected void onStart() {
        super.onStart();
        destroyed = false;
        lastHeartbeatElapsed = SystemClock.elapsedRealtime();
        appendStatus("页面已启动；默认模式=客户生命周期（终态不立即 release）");
    }

    @Override
    protected void onStop() {
        stopRun("ACTIVITY_STOP");
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        stopRun("ACTIVITY_DESTROY");
        mainHandler.removeCallbacksAndMessages(null);
        if (diagnostics != null) {
            diagnostics.shutdownNow();
            diagnostics = null;
        }
        closeCsv();
        super.onDestroy();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(12), dp(18), dp(12));
        root.setBackgroundColor(Color.rgb(18, 24, 34));

        TextView title = new TextView(this);
        title.setText("HQ008 / TCL SDK 卡死复现压测（Debug）");
        title.setTextColor(Color.WHITE);
        title.setTextSize(21f);
        root.addView(title, wrap());

        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.setGravity(Gravity.CENTER_VERTICAL);

        modeButton = button("客户生命周期");
        modeButton.setOnClickListener(v -> cycleMode());
        controls.addView(modeButton, new LinearLayout.LayoutParams(0, dp(48), 1f));

        intervalInput = numberInput("5");
        controls.addView(intervalInput, new LinearLayout.LayoutParams(dp(80), dp(48)));

        maxRequestsInput = numberInput("0");
        controls.addView(maxRequestsInput, new LinearLayout.LayoutParams(dp(80), dp(48)));

        startButton = button("开始");
        startButton.setOnClickListener(v -> startRun());
        controls.addView(startButton, new LinearLayout.LayoutParams(dp(86), dp(48)));

        stopButton = button("停止");
        stopButton.setOnClickListener(v -> stopRun("MANUAL_STOP"));
        controls.addView(stopButton, new LinearLayout.LayoutParams(dp(86), dp(48)));

        requestButton = button("请求一次");
        requestButton.setOnClickListener(v -> requestOnce());
        controls.addView(requestButton, new LinearLayout.LayoutParams(dp(100), dp(48)));
        root.addView(controls, wrap());

        TextView hint = new TextView(this);
        hint.setText("间隔(s)   次数(0=不限)   模式按钮循环：客户生命周期 / 立即释放 / 仅UI");
        hint.setTextColor(Color.LTGRAY);
        hint.setTextSize(12f);
        root.addView(hint, wrap());

        LinearLayout heartbeatRow = new LinearLayout(this);
        heartbeatRow.setOrientation(LinearLayout.HORIZONTAL);
        heartbeatView = new View(this);
        heartbeatView.setBackgroundColor(Color.rgb(48, 180, 108));
        heartbeatRow.addView(heartbeatView, new LinearLayout.LayoutParams(dp(18), dp(18)));
        heartbeatText = new TextView(this);
        heartbeatText.setTag("0");
        heartbeatText.setTextColor(Color.rgb(190, 235, 210));
        heartbeatText.setTextSize(13f);
        heartbeatText.setPadding(dp(8), 0, 0, 0);
        heartbeatRow.addView(heartbeatText, wrap());
        Button responseButton = button("点我测 UI 响应");
        responseButton.setOnClickListener(v -> appendStatus("UI 响应按钮点击成功"));
        heartbeatRow.addView(responseButton, new LinearLayout.LayoutParams(dp(170), dp(42)));
        root.addView(heartbeatRow, wrap());

        countersView = new TextView(this);
        countersView.setTextColor(Color.rgb(255, 224, 160));
        countersView.setTextSize(13f);
        countersView.setPadding(0, dp(4), 0, dp(4));
        root.addView(countersView, wrap());

        FrameLayout stage = new FrameLayout(this);
        imageA = new ImageView(this);
        imageB = new ImageView(this);
        imageA.setScaleType(ImageView.ScaleType.CENTER_CROP);
        imageB.setScaleType(ImageView.ScaleType.CENTER_CROP);
        imageA.setBackgroundColor(Color.rgb(35, 70, 125));
        imageB.setBackgroundColor(Color.rgb(112, 58, 38));
        stage.addView(imageA, match());
        stage.addView(imageB, match());
        imageB.setVisibility(View.GONE);

        adContainer = new FrameLayout(this);
        adContainer.setBackgroundColor(Color.BLACK);
        adContainer.setVisibility(View.GONE);
        stage.addView(adContainer, match());
        root.addView(stage, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        ScrollView statusScroll = new ScrollView(this);
        statusView = new TextView(this);
        statusView.setTextColor(Color.rgb(185, 225, 255));
        statusView.setTextSize(12f);
        statusView.setTypeface(android.graphics.Typeface.MONOSPACE);
        statusScroll.addView(statusView, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(statusScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(190)));
        setContentView(root);
        updateCounters();
    }

    private void initializeTclSdk() {
        try {
            Ad.get().setEnableLog(true);
            if (!Initialization.isHasInit()) {
                AdReportSwitchConfig config = new AdReportSwitchConfig();
                config.setPrivacyAgreed(true);
                config.setUxpEnabled(true);
                config.setErrorStatisticsEnabled(true);
                Initialization.init(getApplicationContext(), config, new MediaAdInitListener() {
                    @Override
                    public void onInitComplete() {
                        appendStatus("TCL SDK 初始化回调完成");
                    }
                });
                appendStatus("已发起 TCL Initialization.init()");
            } else {
                appendStatus("TCL SDK 已由 Application 初始化");
            }
        } catch (Throwable error) {
            appendStatus("TCL SDK 初始化异常: " + safeMessage(error));
            Log.e(TAG, "initializeTclSdk", error);
        }
        mainHandler.postDelayed(this::reportSdkReady, 1000L);
    }

    private void reportSdkReady() {
        if (destroyed) {
            return;
        }
        appendStatus("TCL SDK ready=" + Initialization.isHasInit());
    }

    private void startRun() {
        stopRun("RESTART");
        int maxRequests = parseNonNegative(maxRequestsInput, 0);
        runState = new Hq008StressRunState(selectedMode, maxRequests);
        runState.start();
        terminalCount = loadedCount = errorCount = finishedCount = containerErrorCount = 0;
        requestSequence = 0;
        openCsv();
        appendStatus("开始压测 mode=" + selectedMode + ", interval="
                + parseNonNegative(intervalInput, 5) + "s, max=" + maxRequests);
        requestAd();
    }

    private void requestOnce() {
        if (runState == null || !runState.isRunning()) {
            runState = new Hq008StressRunState(selectedMode, 1);
            runState.start();
            openCsv();
        }
        requestAd();
    }

    private void requestAd() {
        if (runState == null || !runState.tryStartRequest()) {
            appendStatus("跳过请求：已有请求进行中或已达到次数上限");
            return;
        }

        final RequestContext context = new RequestContext(++requestSequence, selectedMode, runState);
        activeRequest = context;
        updateCounters();
        appendStatus("request#" + context.id + " Ad.start()");

        if (context.mode == Hq008StressRunState.Mode.UI_ONLY) {
            animateImages();
            mainHandler.postDelayed(() -> finishRequest(context, "UI_ONLY", null), UI_ONLY_DURATION_MS);
            return;
        }
        if (!Initialization.isHasInit()) {
            finishRequest(context, "SDK_NOT_INITIALIZED", null);
            return;
        }

        try {
            Controller returned = Ad.get()
                    .begin(getApplicationContext())
                    .setVolume(0f)
                    .setAdType(AdType.WATERFALL)
                    .setRequestParams(buildRequestParams())
                    .lazyLoad()
                    .listen(new AdStatusListener() {
                        @Override
                        public void onAdLoaded(Controller controller) {
                            runOnUiThread(() -> handleLoaded(context, controller));
                        }

                        @Override
                        public void onAdStartPlay() {
                            runOnUiThread(() -> appendForCurrent(context, "onAdStartPlay"));
                        }

                        @Override
                        public void onAdStartPlay(double progress) {
                            runOnUiThread(() -> appendForCurrent(context, "onAdStartPlay(" + progress + ")"));
                        }

                        @Override
                        public void onAdFinished() {
                            runOnUiThread(() -> {
                                finishedCount++;
                                finishRequest(context, "FINISHED", null);
                            });
                        }

                        @Override
                        public void onAdError(int errorCode) {
                            runOnUiThread(() -> {
                                errorCount++;
                                finishRequest(context, "ERROR_" + errorCode, null);
                            });
                        }

                        @Override
                        public void onContainerSizeError() {
                            runOnUiThread(() -> {
                                containerErrorCount++;
                                finishRequest(context, "CONTAINER_SIZE_ERROR", null);
                            });
                        }
                    })
                    .start();
            context.controller = returned;
            latestController = returned;
            if (context.terminal && context.state.shouldReleaseOnTerminal()) {
                releaseContext(context, "synchronous-terminal");
            }
            appendForCurrent(context, "Ad.start() returned controller=" + (returned != null));
        } catch (Throwable error) {
            finishRequest(context, "START_EXCEPTION", error);
        }
    }

    private void handleLoaded(RequestContext context, Controller controller) {
        if (!isCurrent(context)) {
            safeRelease(controller, "stale onAdLoaded");
            return;
        }
        context.controller = controller;
        latestController = controller;
        loadedCount++;
        appendStatus("request#" + context.id + " onAdLoaded");
        adContainer.setVisibility(View.VISIBLE);
        try {
            controller.start(adContainer);
        } catch (Throwable error) {
            finishRequest(context, "CONTROLLER_START_EXCEPTION", error);
        }
    }

    private void finishRequest(RequestContext context, String reason, Throwable error) {
        if (context.terminal) {
            return;
        }
        if (context.state != runState || context != activeRequest) {
            context.terminal = true;
            safeRelease(context.controller, "stale-terminal-" + reason);
            return;
        }
        context.terminal = true;
        runState.finishRequest();
        terminalCount++;
        adContainer.setVisibility(View.GONE);
        appendStatus("request#" + context.id + " terminal=" + reason
                + (error == null ? "" : " " + safeMessage(error)));
        writeCsv(context, reason);
        if (runState != null && runState.shouldReleaseOnTerminal()) {
            releaseContext(context, "terminal-" + reason);
        }
        updateCounters();
        scheduleNextRequest();
    }

    private void scheduleNextRequest() {
        if (runState == null || !runState.isRunning() || !runState.canStartRequest()) {
            updateCounters();
            return;
        }
        long delay = parseNonNegative(intervalInput, 5) * 1000L;
        mainHandler.postDelayed(nextRequestRunnable, Math.max(1000L, delay));
    }

    private void stopRun(String reason) {
        mainHandler.removeCallbacks(nextRequestRunnable);
        if (runState != null && runState.isRunning()) {
            runState.stop();
            appendStatus("压测停止: " + reason);
        }
        if (activeRequest != null && !activeRequest.terminal) {
            activeRequest.terminal = true;
        }
        releaseContext(activeRequest, reason);
        activeRequest = null;
        latestController = null;
        if (adContainer != null) {
            adContainer.removeAllViews();
            imageA.setAlpha(1f);
            imageA.setVisibility(View.VISIBLE);
            imageB.setAlpha(1f);
            imageB.setVisibility(View.GONE);
            adContainer.setVisibility(View.GONE);
        }
        closeCsv();
        updateCounters();
    }

    private void releaseContext(RequestContext context, String reason) {
        Controller controller = context == null ? latestController : context.controller;
        if (controller == null) {
            return;
        }
        if (context != null && context.released) {
            return;
        }
        if (context != null) {
            context.released = true;
        }
        if (controller == latestController) {
            latestController = null;
        }
        try {
            controller.stop(adContainer);
        } catch (Throwable stopError) {
            appendStatus("controller.stop(" + reason + ") failed: " + safeMessage(stopError));
        }
        try {
            controller.release();
        } catch (Throwable releaseError) {
            appendStatus("controller.release(" + reason + ") failed: " + safeMessage(releaseError));
        }
    }

    private void safeRelease(Controller controller, String reason) {
        if (controller == null) {
            return;
        }
        try {
            controller.stop(adContainer);
        } catch (Throwable ignored) {
            Log.w(TAG, "stop stale controller failed: " + reason);
        }
        try {
            controller.release();
        } catch (Throwable ignored) {
            Log.w(TAG, "release stale controller failed: " + reason);
        }
    }

    private void cycleMode() {
        Hq008StressRunState.Mode[] modes = Hq008StressRunState.Mode.values();
        selectedMode = modes[(selectedMode.ordinal() + 1) % modes.length];
        modeButton.setText(modeLabel(selectedMode));
        appendStatus("切换模式: " + selectedMode);
    }

    private void animateImages() {
        imageA.setVisibility(View.VISIBLE);
        imageB.setVisibility(View.VISIBLE);
        imageA.animate().alpha(0f).setDuration(500L).withEndAction(() -> {
            imageA.setAlpha(1f);
            imageA.setVisibility(View.GONE);
            imageB.setAlpha(1f);
        }).start();
    }

    private void appendForCurrent(RequestContext context, String message) {
        if (isCurrent(context)) {
            appendStatus("request#" + context.id + " " + message);
        }
    }

    private boolean isCurrent(RequestContext context) {
        return !destroyed && context != null && context == activeRequest && !context.terminal;
    }

    private void openDiagnostics() {
        openCsv();
        diagnostics = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "hq008-stress-watchdog");
            thread.setDaemon(true);
            return thread;
        });
        diagnostics.scheduleAtFixedRate(
                this::sampleDiagnostics,
                WATCHDOG_MS,
                WATCHDOG_MS,
                TimeUnit.MILLISECONDS
        );
    }

    private void openCsv() {
        closeCsv();
        try {
            File file = new File(getFilesDir(), "hq008-stress.csv");
            csvWriter = new PrintWriter(new OutputStreamWriter(
                    new FileOutputStream(file, false), StandardCharsets.UTF_8));
            csvWriter.println("time,request,reason,mode,fd,threads,java_heap,native_heap,rss_kb");
            csvWriter.flush();
            appendStatus("诊断 CSV: " + file.getAbsolutePath());
        } catch (Throwable error) {
            appendStatus("打开诊断 CSV 失败: " + safeMessage(error));
        }
    }

    private void sampleDiagnostics() {
        long now = SystemClock.elapsedRealtime();
        long gap = now - lastHeartbeatElapsed;
        if (gap > STALL_THRESHOLD_MS && now - lastWatchdogReportElapsed > 5000L) {
            lastWatchdogReportElapsed = now;
            Log.e(TAG, "MAIN_THREAD_STALL gapMs=" + gap);
            writeThreadDump("main-stall-" + now);
        }
        String sample = resourceSample();
        Log.i(TAG, "RESOURCE " + sample);
        runOnUiThread(() -> updateCountersWithResource(sample));
    }

    private String resourceSample() {
        return "fd=" + countEntries("/proc/self/fd")
                + ",threads=" + countEntries("/proc/self/task")
                + ",java=" + javaHeapUsedKb()
                + "KB,native=" + Debug.getNativeHeapAllocatedSize() / 1024
                + "KB,rss=" + readRssKb() + "KB";
    }

    private void writeThreadDump(String name) {
        try {
            File file = new File(getFilesDir(), "hq008-thread-" + name + ".txt");
            PrintWriter writer = new PrintWriter(new OutputStreamWriter(
                    new FileOutputStream(file), StandardCharsets.UTF_8));
            for (Map.Entry<Thread, StackTraceElement[]> entry : Thread.getAllStackTraces().entrySet()) {
                writer.println("\n### " + entry.getKey().getName() + " state=" + entry.getKey().getState());
                for (StackTraceElement element : entry.getValue()) {
                    writer.println("    at " + element);
                }
            }
            writer.close();
            Log.e(TAG, "THREAD_DUMP_FILE=" + file.getAbsolutePath());
        } catch (Throwable error) {
            Log.e(TAG, "writeThreadDump failed", error);
        }
    }

    private void writeCsv(RequestContext context, String reason) {
        if (csvWriter == null) {
            return;
        }
        String sample = resourceSample();
        csvWriter.println(System.currentTimeMillis() + "," + context.id + "," + reason + ","
                + context.mode + "," + csvValue(sample, "fd") + "," + csvValue(sample, "threads")
                + "," + javaHeapUsedKb() + "," + Debug.getNativeHeapAllocatedSize() / 1024
                + "," + readRssKb());
        csvWriter.flush();
    }

    private static String csvValue(String sample, String key) {
        String token = key + "=";
        int start = sample.indexOf(token);
        if (start < 0) {
            return "";
        }
        start += token.length();
        int end = sample.indexOf(',', start);
        return end < 0 ? sample.substring(start) : sample.substring(start, end);
    }

    private void closeCsv() {
        if (csvWriter != null) {
            csvWriter.close();
            csvWriter = null;
        }
    }

    private void updateCounters() {
        updateCountersWithResource(null);
    }

    private void updateCountersWithResource(String resource) {
        if (countersView == null) {
            return;
        }
        String run = runState == null ? "idle" : (runState.isRunning() ? "running" : "stopped");
        String current = resource == null ? "" : "\n" + resource;
        countersView.setText(String.format(Locale.US,
                "状态=%s  req=%d  loaded=%d  finish=%d  error=%d  sizeError=%d  terminal=%d%s",
                run, requestSequence, loadedCount, finishedCount, errorCount,
                containerErrorCount, terminalCount, current));
    }

    private void appendStatus(String message) {
        Log.i(TAG, message);
        if (statusView == null) {
            return;
        }
        if (Looper.myLooper() != Looper.getMainLooper()) {
            runOnUiThread(() -> appendStatus(message));
            return;
        }
        String[] lines = statusBuffer.toString().split("\\n");
        if (lines.length >= MAX_STATUS_LINES) {
            statusBuffer.setLength(0);
            for (int i = Math.max(1, lines.length - MAX_STATUS_LINES + 2); i < lines.length; i++) {
                statusBuffer.append(lines[i]).append('\n');
            }
        }
        statusBuffer.append(String.format(Locale.US, "%tT ", System.currentTimeMillis()))
                .append(message).append('\n');
        statusView.setText(statusBuffer.toString());
    }

    private static int countEntries(String path) {
        File directory = new File(path);
        String[] entries = directory.list();
        return entries == null ? -1 : entries.length;
    }

    private static long readRssKb() {
        try (BufferedReader reader = new BufferedReader(new FileReader("/proc/self/status"))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("VmRSS:")) {
                    String[] parts = line.trim().split("\\s+");
                    return Long.parseLong(parts[1]);
                }
            }
        } catch (Throwable ignored) {
            // Best-effort diagnostic only.
        }
        return -1L;
    }

    private static long javaHeapUsedKb() {
        Runtime runtime = Runtime.getRuntime();
        return (runtime.totalMemory() - runtime.freeMemory()) / 1024L;
    }

    private RequestParams buildRequestParams() {
        return new RequestParams.Builder()
                .setAppCat("app")
                .setAppDomain(getPackageName())
                .setChannelName(BuildConfig.CHANNEL)
                .setContentLanguage(Locale.getDefault().getLanguage())
                .setContentTitle("HQ008 SDK Stress")
                .setDevice("android")
                .setDeviceLanguage(Locale.getDefault().toLanguageTag())
                .setDeviceMake(android.os.Build.MANUFACTURER == null ? "" : android.os.Build.MANUFACTURER)
                .setDeviceModel(android.os.Build.MODEL == null ? "" : android.os.Build.MODEL)
                .build();
    }

    private static String safeMessage(Throwable error) {
        return error == null ? "" : (error.getClass().getSimpleName() + ": " + error.getMessage());
    }

    private static int parseNonNegative(EditText input, int fallback) {
        try {
            return Math.max(0, Integer.parseInt(input.getText().toString().trim()));
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private static String modeLabel(Hq008StressRunState.Mode mode) {
        switch (mode) {
            case IMMEDIATE_RELEASE:
                return "立即释放";
            case UI_ONLY:
                return "仅 UI";
            default:
                return "客户生命周期";
        }
    }

    private Button button(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextSize(12f);
        return button;
    }

    private EditText numberInput(String value) {
        EditText input = new EditText(this);
        input.setText(value);
        input.setTextColor(Color.WHITE);
        input.setHintTextColor(Color.GRAY);
        input.setInputType(2);
        input.setGravity(Gravity.CENTER);
        return input;
    }

    private LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private FrameLayout.LayoutParams match() {
        return new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static final class RequestContext {
        final int id;
        final Hq008StressRunState.Mode mode;
        final Hq008StressRunState state;
        Controller controller;
        boolean terminal;
        boolean released;

        RequestContext(int id, Hq008StressRunState.Mode mode, Hq008StressRunState state) {
            this.id = id;
            this.mode = mode;
            this.state = state;
        }
    }
}
