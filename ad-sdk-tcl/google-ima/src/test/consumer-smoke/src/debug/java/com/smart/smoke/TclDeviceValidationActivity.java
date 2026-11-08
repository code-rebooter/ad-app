package com.smart.smoke;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.WindowManager;
import android.widget.FrameLayout;
import com.google.gson.Gson;
import com.google.gson.JsonParser;
import com.smart.android.tclad.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/** Public-API consumer for both Debug and minified Release; never replaces TCL internals. */
public final class TclDeviceValidationActivity extends Activity {
    private static final Gson GSON = new Gson();
    private final ExecutorService reports = Executors.newFixedThreadPool(4);
    private final Map<String, List<Map<String,Object>>> executionBatches = new HashMap<>();
    private FrameLayout container;
    private Controller controller;
    private Controller previousController;
    private String activeId, activeBase;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        container = new FrameLayout(this); setContentView(container); command(getIntent());
    }
    @Override public void onNewIntent(Intent intent) { super.onNewIntent(intent); setIntent(intent); command(intent); }
    private void command(Intent intent) {
        String operation = intent.getStringExtra("command");
        if ("finish".equals(operation)) { finish(); return; }
        if ("release_previous".equals(operation)) { if (previousController != null) previousController.release(); return; }
        if ("cancel".equals(operation) || "release_twice".equals(operation)) {
            if (controller != null) { controller.release(); controller.release(); } return;
        }
        if ("pause".equals(operation) || "resume".equals(operation)) {
            if (controller != null) { if ("pause".equals(operation)) controller.pause(); else controller.resume(); }
            event(activeId, activeBase, "host_" + operation, map()); return;
        }
        if ("mute".equals(operation) || "unmute".equals(operation)) {
            Map<String,Object> probe = nativeVolume();
            probe.put("before_volume", probe.remove("delegate_volume"));
            if (controller != null) controller.setVolume("mute".equals(operation) ? 0f : 1f);
            probe.putAll(nativeVolume());
            event(activeId, activeBase, "host_volume_probe", probe);
            event(activeId, activeBase, "host_" + operation, map()); return;
        }
        final String id = intent.getStringExtra("run_id"), base = intent.getStringExtra("fixture_base");
        if (id == null || !id.matches("[A-Za-z0-9._-]+") || base == null || !base.startsWith("https://127.0.0.1:"))
            throw new IllegalArgumentException("Explicit local fixture/run required");
        activeId = id; activeBase = base;
        final boolean releaseOnCompletion = intent.getBooleanExtra("release_on_completion", false);
        final String reviewCase = intent.getStringExtra("review_case");
        ExecutionConfig.Builder config = ExecutionConfig.builder(this, id, "TCL_DEVICE_VALIDATION")
            .callbackTimeoutSeconds(intent.getLongExtra("callback_seconds", 180L))
            .auditEnabled(intent.getBooleanExtra("audit", true))
            .backendPatchEnabled(!intent.getBooleanExtra("disable_patch", false));
        if (!intent.getBooleanExtra("use_real_patch", false)) config.patchEndpoint(base + "/api/v2/ad/google-gam/patch");
        event(id, base, "test_started", map("sdkInt", android.os.Build.VERSION.SDK_INT,
            "apk_sha256", DeviceTestEvidence.apkSha256(this)));
        Ad.Builder builder = Ad.get().begin(this).lazyLoad().setPlayerType(0).setPlayerViewType(1)
            .setVolume(intent.getBooleanExtra("physical", false) ? 1f : 0f).setExecutionConfig(config.build())
            .listen(new AdStatusListener() {
                @Override public void onAdRequested(Map<String,Object> diagnostics) { report(id, base, "AD_REQUESTED", diagnostics); }
                @Override public void onAdFilled() { report(id, base, "AD_FILLED", map()); }
                @Override public void onAdLoaded(Controller loaded) {
                    report(id, base, "AD_LOADED", map()); loaded.start(container);
                }
                @Override public void onAdStartPlay() { report(id, base, "AD_STARTED", map()); }
                @Override public void onAdStartPlay(double seconds) { report(id, base, "AD_STARTED", map("seconds", seconds)); }
                @Override public void onAdExecutionEvent(String type, Map<String,Object> fields) {
                    event(id, base, "execution", map("event", type, "details", fields));
                    if ("filled".equals(type) && "cancel_filled".equals(reviewCase)) controller.release();
                    if ("filled".equals(type) && "replace_filled".equals(reviewCase))
                        TclDeviceValidationActivity.this.command(new Intent(intent).putExtra("run_id", id + "-next").putExtra("review_case", ""));
                }
                @Override public void onPlaybackDeadline(long deadline) { event(id, base, "deadline", map("deadline_ms", deadline)); }
                @Override public void onAdAudit(String json) { event(id, base, "audit", map("audit", JsonParser.parseString(json))); }
                @Override public void onAdDiagnostics(Map<String,Object> diagnostics) { event(id, base, "diagnostics", diagnostics); }
                @Override public void onAdFinished() {
                    report(id, base, "AD_COMPLETED", map());
                    if (releaseOnCompletion && controller != null) { controller.release(); controller.release(); }
                    terminal(id, base, "completed");
                }
                @Override public void onAdError(int code) { report(id, base, "AD_ERROR", map("code", code)); terminal(id, base, "failed"); }
                @Override public void onAdCancelled() { terminal(id, base, "cancelled"); }
            });
        String tag = intent.getStringExtra("tag_url");
        if (tag == null) tag = base + "/vast?run_id=" + id;
        if (intent.getBooleanExtra("provider", true)) {
            okhttp3.OkHttpClient client = new okhttp3.OkHttpClient.Builder().callTimeout(8, TimeUnit.SECONDS).build();
            builder.setAdTagProvider(new WaterfallAdTagProvider(base + "/address?run_id=" + id,
                new WaterfallRequest.Builder(this).build(new RequestParams.Builder().build()), client));
        } else builder.setResolvedAd(new ResolvedAd.Builder(tag).setGuaranteeLink(
            intent.getBooleanExtra("guarantee", false) ? base + "/inner?run_id=" + id + "&fixture_fallback=1" : "").build());
        previousController = controller;
        controller = builder.start();
    }
    private void report(String id, String base, String type, Map<String,?> data) {
        event(id, base, "report", map("event_type", type, "diagnostics", data));
    }
    private void terminal(String id, String base, String outcome) {
        event(id, base, "terminal", map("outcome", outcome));
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
            if (!id.equals(activeId)) return;
            Map<String,Object> state = map("child_count", container.getChildCount());
            try {
                java.lang.reflect.Field f = Controller.class.getDeclaredField("nativeReleased"); f.setAccessible(true);
                state.put("native_released", f.get(controller));
                f = Controller.class.getDeclaredField("owner"); f.setAccessible(true);
                state.put("owner_released", f.get(null) == null);
            } catch (Exception error) { state.put("inspection_error", error.toString()); }
            event(id, base, "cleanup", state);
        }, 750);
    }
    private static Map<String,Object> map(Object... pairs) {
        Map<String,Object> result = new LinkedHashMap<>();
        for (int i=0;i<pairs.length;i+=2) result.put((String)pairs[i], pairs[i+1]); return result;
    }
    private synchronized void event(String id, String base, String type, Map<String,?> fields) {
        Map<String,Object> body = map("request_id", id, "at_ms", SystemClock.elapsedRealtime()); body.putAll(fields);
        File dir = new File(getFilesDir(), "device-validation/" + id); dir.mkdirs();
        try (FileOutputStream out = new FileOutputStream(new File(dir, "events.jsonl"), true)) {
            out.write((GSON.toJson(map("type", type, "fields", body)) + "\n").getBytes(StandardCharsets.UTF_8));
        } catch (IOException error) { throw new RuntimeException(error); }
        if ("execution".equals(type)) {
            List<Map<String,Object>> batch = executionBatches.get(id);
            if (batch == null) { batch = new ArrayList<>(); executionBatches.put(id, batch); }
            batch.add(map("kind", type, "body", body));
            return;
        }
        if ("terminal".equals(type)) {
            List<Map<String,Object>> batch = executionBatches.remove(id);
            if (batch != null && !batch.isEmpty()) event(id, base, "batch", map("events", batch));
        }
        if (reports.isShutdown()) return;
        reports.execute(() -> {
            for (int attempt=0;attempt<3;attempt++) {
                HttpURLConnection connection = null;
                try {
                    connection = (HttpURLConnection)new URL(base + "/" + type).openConnection(Proxy.NO_PROXY);
                    connection.setRequestMethod("POST"); connection.setConnectTimeout(10000); connection.setReadTimeout(10000);
                    connection.setDoOutput(true); connection.setRequestProperty("Content-Type", "application/json");
                    byte[] data = GSON.toJson(body).getBytes(StandardCharsets.UTF_8); connection.setFixedLengthStreamingMode(data.length);
                    try (OutputStream out=connection.getOutputStream()) { out.write(data); }
                    if (connection.getResponseCode()==200) return;
                } catch (Exception error) { android.util.Log.w("TclDeviceFixture", type + " upload failed", error); }
                finally { if (connection != null) connection.disconnect(); }
            }
        });
    }
    private static Object field(Object instance, String name) throws Exception {
        for (Class<?> type=instance.getClass();type!=null;type=type.getSuperclass()) {
            try { java.lang.reflect.Field f=type.getDeclaredField(name);f.setAccessible(true);return f.get(instance); }
            catch (NoSuchFieldException ignored) { }
        }
        throw new NoSuchFieldException(name);
    }
    private Map<String,Object> nativeVolume() {
        try {
            Object engine=field(field(controller,"original"),"a"), player=field(engine,"f");
            Class<?> delegateType=Class.forName("com.tcl.ff.component.uniplayer.f.k");
            for (java.lang.reflect.Field f:player.getClass().getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                f.setAccessible(true);Object delegate=f.get(player);
                if (delegateType.isInstance(delegate)) return map("delegate_volume", delegateType.getMethod("getVolume").invoke(delegate),
                    "delegate_class",delegate.getClass().getName(),"player_class",player.getClass().getName());
            }
            return map("probe_error","Native delegate not found");
        } catch (Exception error) { return map("probe_error",error.toString()); }
    }
    @Override public void onDestroy() {
        if (controller != null) controller.release();
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(reports::shutdown, 3_000);
        super.onDestroy();
    }
}
