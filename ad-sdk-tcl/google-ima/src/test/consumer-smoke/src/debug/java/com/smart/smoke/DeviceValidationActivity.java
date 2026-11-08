package com.smart.smoke;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.WindowManager;
import android.widget.FrameLayout;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.smart.android.tclad.ima.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/** Debug-only host for the migrated SDK; all fixtures and reports use explicit localhost endpoints. */
public final class DeviceValidationActivity extends Activity {
    private static final Gson GSON = new com.google.gson.GsonBuilder().serializeNulls().create();
    private final ExecutorService reports = Executors.newFixedThreadPool(4);
    private final Map<String, List<Map<String,Object>>> traceBatches = new HashMap<>();
    private GoogleImaAdManager manager;
    private GoogleImaAdManager previousManager;
    private FrameLayout container;
    private String activeId = "unknown";
    private String activeBase;
    private boolean playbackDenied;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        container = new FrameLayout(this);
        setContentView(container);
        command(getIntent());
    }
    @Override public void onNewIntent(Intent intent) { super.onNewIntent(intent); setIntent(intent); command(intent); }
    private void command(Intent intent) {
        String operation = intent.getStringExtra("command");
        if ("cancel".equals(operation)) { if (manager != null) manager.release(); return; }
        if ("finish".equals(operation)) { finish(); return; }
        if ("release_previous".equals(operation)) { if (previousManager != null) previousManager.release(); return; }
        if ("deny".equals(operation)) { playbackDenied = true; return; }
        if ("pause".equals(operation) || "resume".equals(operation)) {
            try {
                Object player = mediaPlayer();
                player.getClass().getMethod("setPlayWhenReady", boolean.class).invoke(player, "resume".equals(operation));
                record(activeId, "host_" + operation, Collections.emptyMap());
            } catch (Exception error) { record(activeId, "host_command_error", map("error", error.toString())); }
            return;
        }
        if ("volume".equals(operation)) {
            try {
                Object player = mediaPlayer();
                float value = intent.getFloatExtra("volume", 0f);
                player.getClass().getMethod("setVolume", float.class).invoke(player, value);
                Object actual = player.getClass().getMethod("getVolume").invoke(player);
                Map<String,Object> data = map("request_id", activeId, "requested", value, "actual", actual,
                    "at_ms", SystemClock.elapsedRealtime());
                record(activeId, "host_volume", data);
                upload(activeId, activeBase + "/host_volume", data);
            } catch (Exception error) {
                record(activeId, "host_command_error", map("error", error.toString()));
                upload(activeId, activeBase + "/host_command_error", map("error", error.toString()));
            }
            return;
        }
        if ("release_twice".equals(operation)) { if (manager != null) { manager.release(); manager.release(); } return; }
        final String id = intent.getStringExtra("run_id");
        if (id == null || !id.matches("[A-Za-z0-9._-]+")) throw new IllegalArgumentException("run_id required");
        final String base = intent.getStringExtra("fixture_base");
        if (base == null || !base.startsWith("https://127.0.0.1:")) throw new IllegalArgumentException("explicit local TLS fixture required");
        activeId = id;
        activeBase = base;
        playbackDenied = intent.getBooleanExtra("deny", false);
        final boolean releaseOnCompletion = intent.getBooleanExtra("release_on_completion", false);
        final boolean replaceOnCompletion = intent.getBooleanExtra("replace_on_completion", false);
        final String reviewCase = intent.getStringExtra("review_case");
        final String cancelOnReport = intent.getStringExtra("cancel_on_report");
        final String rawConfig = intent.getStringExtra("gam_config");
        final JsonElement config = rawConfig == null ? null : JsonParser.parseString(rawConfig);
        String channel = intent.getStringExtra("channel_id");
        GoogleImaExecutionConfig.Builder executionBuilder = GoogleImaExecutionConfig.builder(this, id,
            channel == null ? "GOOGLE_IMA_DEVICE_VALIDATION" : channel)
            .callbackTimeoutSeconds(intent.getLongExtra("callback_seconds", 180L))
            .auditEnabled(intent.getBooleanExtra("audit", true))
            .hiddenMode(intent.getBooleanExtra("hidden", false));
        if (!intent.getBooleanExtra("use_real_patch", false)) executionBuilder.patchEndpoint(base + "/api/v2/ad/google-gam/patch");
        GoogleImaExecutionConfig execution = executionBuilder.build();
        Map<String,Object> started = map("request_id", id, "physicalSound", intent.getBooleanExtra("physical", false),
            "config", rawConfig == null ? "null" : rawConfig, "sdkInt", android.os.Build.VERSION.SDK_INT,
            "apk_sha256", DeviceTestEvidence.apkSha256(this));
        record(id, "test_started", started); upload(id, base + "/test_started", started);
        previousManager = manager;
        manager = new GoogleImaAdManager(execution, new GoogleImaSessionListener() {
            @Override public void onAdLoaded() {
                record(id, "loaded", Collections.emptyMap());
                upload(id, base + "/host_loaded", map("request_id", id, "callback_at_ms", SystemClock.elapsedRealtime()));
                attachMediaProbe(id);
            }
            @Override public void onAdStarted() {
                record(id, "started", Collections.emptyMap());
                upload(id, base + "/host_started", map("request_id", id, "callback_at_ms", SystemClock.elapsedRealtime()));
            }
            @Override public void onAdCompleted() { terminal(id, "completed"); }
            @Override public void onAdFailed() { terminal(id, "failed"); }
            @Override public void onAdCancelled(String reason) { terminal(id, "cancelled:" + reason); }
            @Override public boolean isPlaybackAllowed() { return !playbackDenied; }
            @Override public String playbackDeniedReason() { return "device_fixture_denied"; }
            @Override public void onPlaybackDeadline(String requestId, long deadline) {
                record(id, "deadline", map("deadline_ms", deadline));
            }
            @Override public void onAdReport(String requestId, String event, String message, Map<String,Object> diagnostic) {
                Map<String,Object> body = new LinkedHashMap<>();
                body.put("request_id", requestId); body.put("event_type", event); body.put("message", message); body.put("diagnostics", diagnostic);
                body.put("callback_at_ms", SystemClock.elapsedRealtime());
                record(id, "report", body); upload(id, base + "/report", body);
                if (event.equals(cancelOnReport)) manager.release();
                if ("AD_COMPLETED".equals(event) && reviewCase != null && reviewCase.startsWith("pending_")) {
                    Intent next = new Intent(intent).putExtra("run_id", id + "-next")
                        .putExtra("review_case", "").putExtra("replace_on_completion", false);
                    DeviceValidationActivity.this.command(next);
                    if ("pending_cancel".equals(reviewCase)) {
                        manager.release(); manager.release();
                        upload(id, base + "/queued_release", map("request_id", id + "-next"));
                    } else if ("pending_latest".equals(reviewCase)) {
                        DeviceValidationActivity.this.command(new Intent(next).putExtra("run_id", id + "-latest"));
                    } else if ("pending_release_previous".equals(reviewCase)) {
                        previousManager.release();
                    }
                }
                if (releaseOnCompletion && "AD_COMPLETED".equals(event)) { manager.release(); manager.release(); }
                if (replaceOnCompletion && "AD_COMPLETED".equals(event)) {
                    Intent next = new Intent(intent).putExtra("run_id", id + "-next").putExtra("replace_on_completion", false);
                    DeviceValidationActivity.this.command(next);
                }
            }
            @Override public void onTrace(String requestId, String event, String message, String adLog) {
                Map<String,Object> body = new LinkedHashMap<>();
                body.put("request_id", requestId); body.put("event_type", event); body.put("message", message); body.put("ad_log", adLog);
                body.put("callback_at_ms", SystemClock.elapsedRealtime());
                record(id, "trace", body);
                synchronized (traceBatches) {
                    List<Map<String,Object>> batch = traceBatches.get(id);
                    if (batch == null) { batch = new ArrayList<>(); traceBatches.put(id, batch); }
                    batch.add(map("kind", "trace", "body", body));
                }
            }
            @Override public void onAdAudit(String requestId, String summary) {
                try { write(id, "runtime-audit.json", summary, false); } catch (IOException failure) { throw new RuntimeException(failure); }
                record(id, "audit", map("bytes", summary.length()));
                upload(id, base + "/audit", map("request_id", requestId, "audit", JsonParser.parseString(summary)));
            }
        });
        manager.showAd(container, config, intent.getBooleanExtra("physical", false));
    }
    private Object mediaPlayer() throws Exception {
        if (container.getChildCount() == 0) throw new IllegalStateException("No active player view");
        Object view = container.getChildAt(0);
        java.lang.reflect.Field field = view.getClass().getDeclaredField("player"); field.setAccessible(true);
        return field.get(view);
    }
    private void attachMediaProbe(String id) {
        try {
            if (container.getChildCount() == 0) return;
            Object view = container.getChildAt(0);
            java.lang.reflect.Field field = view.getClass().getDeclaredField("player"); field.setAccessible(true);
            Object player = field.get(view);
            if (player == null) return;
            Class<?> analytics = Class.forName("androidx.media3.exoplayer.analytics.AnalyticsListener");
            Object probe = java.lang.reflect.Proxy.newProxyInstance(analytics.getClassLoader(), new Class<?>[]{analytics}, (instance, method, values) -> {
                if (method.getDeclaringClass() == Object.class) {
                    if (method.getName().equals("hashCode")) return System.identityHashCode(instance);
                    if (method.getName().equals("equals")) return instance == values[0];
                    return "DeviceMediaProbe";
                }
                if (method.getName().equals("onLoadError") || method.getName().equals("onPlayerError")) {
                    Map<String,Object> data = new LinkedHashMap<>(); data.put("method", method.getName());
                    if (values != null) for (Object value : values) {
                        if (value instanceof Throwable) {
                            StringWriter text = new StringWriter(); ((Throwable) value).printStackTrace(new PrintWriter(text)); data.put("stack", text.toString());
                        } else if (value != null) {
                            try { data.put("uri", String.valueOf(value.getClass().getField("uri").get(value))); }
                            catch (ReflectiveOperationException ignored) {}
                        }
                    }
                    record(id, "media_error_probe", data);
                    if (activeBase != null) upload(id, activeBase + "/media_error_probe", map("request_id", id, "details", data));
                }
                return null;
            });
            java.lang.reflect.Method add = player.getClass().getMethod("addAnalyticsListener", analytics); add.setAccessible(true); add.invoke(player, probe);
            record(id, "media_probe_attached", Collections.emptyMap());
        } catch (Exception error) { record(id, "media_probe_error", map("error", error.toString())); }
    }

    private static Map<String,Object> map(Object... values) {
        Map<String,Object> result = new LinkedHashMap<>();
        for (int index = 0; index < values.length; index += 2) result.put((String) values[index], values[index + 1]);
        return result;
    }
    private void terminal(String id, String outcome) {
        List<Map<String,Object>> batch;
        synchronized (traceBatches) { batch = traceBatches.remove(id); }
        if (batch != null && !batch.isEmpty() && activeBase != null)
            upload(id, activeBase + "/batch", map("request_id", id, "events", batch));
        record(id, "terminal", map("outcome", outcome));
        if (activeBase != null) upload(id, activeBase + "/terminal", map("request_id", id,
            "outcome", outcome, "callback_at_ms", SystemClock.elapsedRealtime()));
        try { write(id, "terminal.json", GSON.toJson(map("run_id", id, "outcome", outcome)), false); }
        catch (IOException failure) { throw new RuntimeException(failure); }
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
            if (!id.equals(activeId)) return;
            Map<String,Object> state = new LinkedHashMap<>();
            state.put("request_id", id); state.put("child_count", container.getChildCount());
            try {
                java.lang.reflect.Field field = GoogleImaAdManager.class.getDeclaredField("current"); field.setAccessible(true);
                state.put("session_released", field.get(manager) == null);
            } catch (Exception error) { state.put("inspection_error", error.toString()); }
            record(id, "cleanup", state);
            if (!reports.isShutdown()) upload(id, activeBase + "/cleanup", state);
        }, 750);
    }
    private synchronized void record(String id, String type, Map<String,?> fields) {
        Map<String,Object> entry = new LinkedHashMap<>();
        entry.put("at_ms", SystemClock.elapsedRealtime()); entry.put("run_id", id); entry.put("type", type); entry.put("fields", fields);
        try { write(id, "events.jsonl", GSON.toJson(entry) + "\n", true); }
        catch (IOException failure) { android.util.Log.e("ImaDeviceFixture", "Cannot save event", failure); }
    }
    private synchronized void write(String id, String name, String data, boolean append) throws IOException {
        File directory = new File(getFilesDir(), "device-validation/" + id); directory.mkdirs();
        try (FileOutputStream output = new FileOutputStream(new File(directory, name), append)) {
            output.write(data.getBytes(StandardCharsets.UTF_8));
        }
    }
    private void upload(String id, String url, Map<String,?> body) {
        if (reports.isShutdown()) return;
        reports.execute(() -> {
            for (int attempt = 1; attempt <= 3; attempt++) {
                HttpURLConnection connection = null;
                try {
                    byte[] data = GSON.toJson(body).getBytes(StandardCharsets.UTF_8);
                    connection = (HttpURLConnection) new URL(url).openConnection(Proxy.NO_PROXY);
                    connection.setConnectTimeout(15000); connection.setReadTimeout(15000);
                    connection.setRequestMethod("POST"); connection.setRequestProperty("Content-Type", "application/json");
                    connection.setDoOutput(true); connection.setFixedLengthStreamingMode(data.length);
                    try (OutputStream out = connection.getOutputStream()) { out.write(data); }
                    int code = connection.getResponseCode();
                    record(id, "upload_result", map("url", url, "http_status", code, "attempt", attempt));
                    if (code >= 200 && code < 300) return;
                } catch (Exception error) {
                    record(id, "upload_error", map("url", url, "error", error.toString(), "attempt", attempt));
                } finally { if (connection != null) connection.disconnect(); }
            }
        });
    }

    @Override public void onDestroy() {
        if (manager != null) manager.release();
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(reports::shutdown, 3_000);
        super.onDestroy();
    }
}
