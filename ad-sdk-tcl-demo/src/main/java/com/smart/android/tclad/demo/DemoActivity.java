package com.smart.android.tclad.demo;

import android.app.Activity;
import android.os.Bundle;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.smart.android.tclad.*;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/** Runs the original TCL adurl engine; reference mode enters through the original public Ad API. */
public final class DemoActivity extends Activity {
    private java.net.CookieHandler initialCookieHandler;
    private javax.net.ssl.HostnameVerifier initialHostnameVerifier;
    private Controller controller;
    private com.tcl.ff.component.vastad.Controller reference;
    private TextView status;
    private FrameLayout video;
    private LiveCapture capture;
    private NetworkAudit networkAudit;
    private boolean paused;
    private boolean actionsScheduled;
    private boolean playbackScheduled;
    private boolean secondAdScheduled;
    private AdTagProvider.Cancellable pendingConfig;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        if (getIntent().getBooleanExtra("automated", false)) {
            if (android.os.Build.VERSION.SDK_INT >= 27) { setTurnScreenOn(true); setShowWhenLocked(true); }
            else getWindow().addFlags(WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED);
        }
        LocalhostProxySelector.install();
        networkAudit = NetworkAudit.install(this);
        initialCookieHandler = java.net.CookieHandler.getDefault();
        initialHostnameVerifier = javax.net.ssl.HttpsURLConnection.getDefaultHostnameVerifier();
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL); root.setPadding(24, 16, 24, 16);
        status = new TextView(this); status.setTextSize(20); status.setText("正在初始化 TCL 原厂广告引擎…"); root.addView(status);
        video = new FrameLayout(this); root.addView(video, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout controls = new LinearLayout(this); controls.setGravity(Gravity.CENTER);
        Button pause = new Button(this); pause.setText("暂停 / 继续");
        pause.setOnClickListener(v -> {
            paused = !paused;
            if (controller != null) { if (paused) controller.pause(); else controller.resume(); }
            if (reference != null) { if (paused) reference.pause(); else reference.resume(); }
            record(paused ? "HOST_PAUSE" : "HOST_RESUME");
        });
        Button release = new Button(this); release.setText("结束测试"); release.setOnClickListener(v -> finish());
        if (getIntent().getBooleanExtra("automated", false)) { pause.setEnabled(false); release.setEnabled(false); }
        controls.addView(pause); controls.addView(release); root.addView(controls); setContentView(root);
        Ad.get().setEnableLog(true);
        try { if (!getIntent().getBooleanExtra("use_default_config", false)) Initialization.setOriginalConfiguration(assetText("original-config.json")); }
        catch (Exception error) { record("CONFIG_ERROR missing original configuration snapshot"); return; }
        Initialization.init(this, null, new MediaAdInitListener() {
            @Override public void onInitComplete() {
                if (isFinishing() || isDestroyed()) return;
                if (getIntent().getBooleanExtra("isolation_probe", false)) {
                    record("ISOLATION_INITIALIZED");
                    video.postDelayed(() -> {
                        try { IsolationProbe.run(DemoActivity.this,networkAudit); record("ISOLATION_PASS"); }
                        catch (RuntimeException error) { android.util.Log.e("TclNativeDemo","ISOLATION_FAIL",error); record("ISOLATION_FAIL"); }
                    }, 10000);
                    return;
                }
                if (networkAudit.initializationAttempts()!=0) { record("INIT_ERROR unexpected network"); return; }
                networkAudit.allowAdTraffic();
                resolveInput();
            }
            @Override public void onInitError(int code) { record("INIT_ERROR code=" + code); }
        });
    }
    private void resolveInput() {
        boolean sound = getIntent().getBooleanExtra("sound_enabled", false);
        String resolve = getIntent().getStringExtra("gam_resolve_url");
        if (resolve != null) {
            pendingConfig = new GoogleGamClient(resolve).resolve(getIntent().getStringExtra("channel_id"), getIntent().getStringExtra("mac"), new GoogleGamClient.Callback() {
                @Override public void onResolved(GoogleGamClient.Config config) { runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    if (config == null) { record("NO_AD"); return; }
                    runTag("resolve", new ResolvedAd.Builder(googleParameters(sound).applyTo(config.getAdTagUrl())).build(), sound);
                }); }
                @Override public void onError(int code) { runOnUiThread(() -> record("CONFIG_ERROR code=" + code)); }
            });
            return;
        }
        try {
            String source = getIntent().getStringExtra("live_source");
            String url = getIntent().getStringExtra("vast_url");
            String google = getIntent().getStringExtra("google_tag_url");
            if (source != null) {
                if (!"google".equals(source) && !"leiniao".equals(source)) throw new IllegalArgumentException("Unknown source");
                url = new org.json.JSONObject(assetText("live-tags.json")).getString(source);
                if ("google".equals(source)) url = googleParameters(sound).applyTo(url);
                else url = okhttp3.HttpUrl.get(url).newBuilder().setQueryParameter("cb", Long.toString(System.currentTimeMillis())).build().toString();
            } else if (google != null) { source = "google-custom"; url = googleParameters(sound).applyTo(google); }
            else { source = getIntent().getStringExtra("case_name"); if (source == null) source = "fixture"; if (url == null) url = "http://127.0.0.1:18888/tag"; }
            ResolvedAd.Builder input = new ResolvedAd.Builder(url);
            if ("leiniao".equals(source)) input.setAdUnitId("21202");
            String fallback = getIntent().getStringExtra("guarantee_url"); if (fallback != null) input.setGuaranteeLink(fallback);
            runTag(source, input.build(), sound);
        } catch (Exception error) { record("CONFIG_ERROR " + error); }
    }
    private void runTag(String source, ResolvedAd input, boolean sound) {
        boolean referenceMode = getIntent().getBooleanExtra("reference", false);
        capture = new LiveCapture(this, source + (referenceMode ? "-reference" : "-candidate"));
        capture.install(referenceMode ? input.toJson() : null);
        int releaseAfterVast = getIntent().getIntExtra("release_after_vast_ms", 0);
        if (releaseAfterVast > 0) {
            var once = new java.util.concurrent.atomic.AtomicBoolean();
            capture.onVastRequest = () -> { if (once.compareAndSet(false, true)) runOnUiThread(() -> video.postDelayed(() -> {
                record("HOST_RELEASE"); if (controller != null) controller.release(); if (reference != null) reference.release();
            }, releaseAfterVast)); };
        }
        record("SOURCE " + source + " engine=" + (referenceMode ? "original-public-api" : "original-adurl-boundary"));
        if (referenceMode) {
            var originalConfig = com.tcl.ff.component.vastad.Ad.get().begin(this).lazyLoad()
                .setAdType(com.tcl.ff.component.overseabase.base.constant.AdType.WATERFALL)
                .setPlayerType(0).setPlayerViewType(1).setVolume(sound ? 1f : 0f)
                .listen(new com.tcl.ff.component.vastad.core.callbacks.AdStatusListener() {
                    @Override public void onAdLoaded(com.tcl.ff.component.vastad.Controller loaded) { reference = loaded; record("LOADED"); startWhenRequested(() -> loaded.start(video)); }
                    @Override public void onAdStartPlay() { record("STARTED_NO_DURATION"); }
                    @Override public void onAdStartPlay(double seconds) { record("STARTED durationSeconds=" + seconds); }
                    @Override public void onAdFinished() { record("FINISHED"); }
                    @Override public void onAdError(int code) { record("ERROR code=" + code); }
                    @Override public void onContainerSizeError() { record("CONTAINER_ERROR"); }
                });
            reference = com.smart.android.tclad.internal.OriginalBoundary.start(originalConfig, input);
        } else {
            controller = Ad.get().begin(this).lazyLoad().setAdType(AdType.WATERFALL)
                .setPlayerType(0).setPlayerViewType(1).setVolume(sound ? 1f : 0f).setResolvedAd(input)
                .listen(new AdStatusListener() {
                    @Override public void onAdLoaded(Controller loaded) { controller = loaded; record("LOADED"); startWhenRequested(() -> loaded.start(video)); }
                    @Override public void onAdStartPlay() { record("STARTED_NO_DURATION"); }
                    @Override public void onAdStartPlay(double seconds) { record("STARTED durationSeconds=" + seconds); }
                    @Override public void onAdFinished() { record("FINISHED"); }
                    @Override public void onAdError(int code) { record("ERROR code=" + code); }
                    @Override public void onContainerSizeError() { record("CONTAINER_ERROR"); }
                }).start();
        }
        if (getIntent().getIntExtra("release_after_ms", 0) > 0) video.postDelayed(() -> {
            record("HOST_RELEASE"); if (controller != null) controller.release(); if (reference != null) reference.release();
        }, getIntent().getIntExtra("release_after_ms", 0));
    }
    private void startWhenRequested(Runnable start) {
        int delay = getIntent().getIntExtra("start_delay_ms", 0);
        if (delay == 0) { start.run(); return; }
        if (!playbackScheduled) { playbackScheduled = true; video.postDelayed(start, delay); }
    }
    private String assetText(String name) throws java.io.IOException {
        try (InputStream input = getAssets().open(name); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192]; for (int n; (n = input.read(buffer)) >= 0;) bytes.write(buffer, 0, n);
            return bytes.toString(StandardCharsets.UTF_8.name());
        }
    }
    private GoogleAdTagParameters googleParameters(boolean sound) {
        var builder = new GoogleAdTagParameters.Builder().setApplication(getPackageName(), getApplicationInfo().loadLabel(getPackageManager()).toString()).setAutoplay(true).setMuted(!sound);
        String id = getIntent().getStringExtra("advertising_id");
        if (id == null || id.isEmpty()) builder.clearAdvertisingId();
        else builder.setAndroidAdvertisingId(id, getIntent().getBooleanExtra("limit_ad_tracking", false));
        String gdpr = getIntent().getStringExtra("gdpr");
        if ("0".equals(gdpr) || "1".equals(gdpr)) builder.setGdprConsent("1".equals(gdpr), getIntent().getStringExtra("gdpr_consent"));
        return builder.setPageUrl(getIntent().getStringExtra("page_url")).build();
    }
    private void record(String event) {
        recordGlobalNetworkState(event);
        status.setText(event.equals("FINISHED") ? "本轮广告已播放完成" : event);
        if (capture != null) capture.event(event);
        android.util.Log.i("TclNativeDemo", event);
        String second = getIntent().getStringExtra("second_vast_url");
        if (second != null && !secondAdScheduled && (event.equals("FINISHED") || event.startsWith("ERROR"))) {
            secondAdScheduled = true;
            boolean releaseBetween = event.equals("FINISHED") && getIntent().getBooleanExtra("release_between_ads", false);
            video.postDelayed(() -> {
                if (releaseBetween) {
                    record("HOST_RELEASE_BEFORE_NEXT");
                    if (controller != null) { controller.release(); controller = null; }
                    if (reference != null) { reference.release(); reference = null; }
                }
                try { runTag("second", new ResolvedAd.Builder(second).build(), false); }
                catch (RuntimeException error) { record("CONFIG_ERROR " + error); }
            }, releaseBetween ? 1500 : 1000);
        }

        if (event.startsWith("STARTED durationSeconds=") && !actionsScheduled && getIntent().getBooleanExtra("exercise_controls", false)) {
            actionsScheduled = true;
            video.postDelayed(() -> { record("HOST_PAUSE"); if (controller != null) controller.pause(); if (reference != null) reference.pause(); }, 2000);
            video.postDelayed(() -> { record("HOST_RESUME"); if (controller != null) controller.resume(); if (reference != null) reference.resume(); }, 3000);
            video.postDelayed(() -> { record("HOST_HIDE"); video.setVisibility(android.view.View.INVISIBLE); }, 5000);
            video.postDelayed(() -> { record("HOST_SHOW"); video.setVisibility(android.view.View.VISIBLE); }, 6000);
            video.postDelayed(() -> { record("HOST_UNMUTE"); if (controller != null) controller.setVolume(1f); if (reference != null) reference.setVolume(1f); }, 7000);
            video.postDelayed(() -> { record("HOST_MUTE"); if (controller != null) controller.setVolume(0f); if (reference != null) reference.setVolume(0f); }, 8000);
        }
    }
    private void recordGlobalNetworkState(String event) {
        boolean cookieUnchanged = java.net.CookieHandler.getDefault() == initialCookieHandler;
        boolean hostnameUnchanged = javax.net.ssl.HttpsURLConnection.getDefaultHostnameVerifier() == initialHostnameVerifier;
        try {
            org.json.JSONObject report = new org.json.JSONObject().put("phase", event)
                .put("cookieHandlerUnchanged", cookieUnchanged).put("hostnameVerifierUnchanged", hostnameUnchanged)
                .put("policy", "original-player-network-behavior-preserved")
                .put("observationOnly", true);
            try (java.io.FileOutputStream out = new java.io.FileOutputStream(new java.io.File(getFilesDir(), "global-network-state.json"))) {
                out.write(report.toString(2).getBytes(StandardCharsets.UTF_8));
            }
        } catch (java.io.IOException | org.json.JSONException error) { throw new IllegalStateException("Cannot save global network probe", error); }
    }
    @Override public void onPause() { super.onPause(); if (controller != null) controller.pause(); if (reference != null) reference.pause(); }
    @Override public void onResume() { super.onResume(); if (!paused) { if (controller != null) controller.resume(); if (reference != null) reference.resume(); } }
    @Override public void onDestroy() {
        if (pendingConfig != null) pendingConfig.cancel();
        if (controller != null) controller.release(); if (reference != null) reference.release(); recordGlobalNetworkState("DESTROYED_AFTER_RELEASE"); super.onDestroy();
    }
}
