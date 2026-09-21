package com.smart.android.adsdk.internal;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import com.smart.android.adsdk.AdError;
import com.smart.android.adsdk.AdErrorCode;
import com.smart.android.adsdk.AdErrorStage;
import com.tcl.ff.component.overseabase.base.constant.AdReportSwitchConfig;
import com.tcl.ff.component.overseabase.base.constant.AdType;
import com.tcl.ff.component.overseabasebusiness.requestparams.RequestParams;
import com.tcl.ff.component.vastad.Ad;
import com.tcl.ff.component.vastad.Controller;
import com.tcl.ff.component.vastad.Initialization;
import com.tcl.ff.component.vastad.core.callbacks.AdStatusListener;
import java.util.Locale;

/** TCL 2.8.02 adapter. The surrounding channel session owns authorization and total timeout. */
final class TclAdPlayer implements AdPlayer {
    // TCL caches its underlying lazy-load engine. Java Controller wrappers are not ownership IDs.
    private static Object requestOwner;
    private final Object ownerToken = new Object();
    private boolean requestCallInProgress, engineReleased;
    private final Context context;
    private final ViewGroup host;
    private final String channel;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final View.OnLayoutChangeListener layoutListener = (v, l, t, r, b, ol, ot, or, ob) -> startLoadedAd();
    private final Runnable initPoll = this::pollInitialization;
    private TclDisplayLayer layer;
    private Controller controller;
    private volatile boolean cancellationRequested;
    private boolean released, requestIssued, loaded, started, controllerStarted, soundEnabled;

    TclAdPlayer(Context context, ViewGroup host, String channel, Listener listener) {
        this.context = context;
        this.host = host;
        this.channel = channel;
        this.listener = listener;
    }

    @Override public void play(AdPlaybackConfig config, boolean soundEnabled) {
        if (released || cancellationRequested) return;
        this.soundEnabled = soundEnabled;
        layer = new TclDisplayLayer(host.getContext(), host, config.isHiddenMode(), listener);
        layer.addOnLayoutChangeListener(layoutListener);
        listener.onTrace("AD_DISPLAY_STATE", "provider=TCL hiddenMode=" + config.isHiddenMode()
            + " soundEnabled=" + soundEnabled + " textureView=true");
        try {
            Ad.get().setEnableLog(SdkLog.isEnabled());
            if (Initialization.isHasInit()) {
                requestAd();
                return;
            }
            validateInitializationMetadata();
            AdReportSwitchConfig switches = new AdReportSwitchConfig();
            switches.setPrivacyAgreed(true);
            switches.setUxpEnabled(true);
            switches.setErrorStatisticsEnabled(true);
            listener.onTrace("TCL_INITIALIZE", "channel=" + channel);
            Initialization.init(context, switches, () -> dispatch(() -> {
                main.removeCallbacks(initPoll);
                requestAd();
            }));
            if (!released && !cancellationRequested && !requestIssued) main.postDelayed(initPoll, 500L);
        } catch (RuntimeException | LinkageError error) {
            fail(error, AdErrorStage.INITIALIZATION);
        }
    }

    private void pollInitialization() {
        if (released || cancellationRequested || requestIssued) return;
        try {
            if (Initialization.isHasInit()) requestAd();
            else main.postDelayed(initPoll, 500L);
        } catch (RuntimeException | LinkageError error) { fail(error, AdErrorStage.INITIALIZATION); }
    }

    @SuppressWarnings("deprecation")
    private void validateInitializationMetadata() {
        try {
            ApplicationInfo info = context.getPackageManager().getApplicationInfo(
                context.getPackageName(), PackageManager.GET_META_DATA);
            Bundle values = info.metaData;
            for (String key : new String[] {"tcl_app_key", "partner_name", "project_id"}) {
                Object value = values == null ? null : values.get(key);
                if (value == null || value.toString().trim().isEmpty() || value.toString().startsWith("${")) {
                    throw new IllegalArgumentException("Missing TCL initialization metadata: " + key);
                }
            }
        } catch (PackageManager.NameNotFoundException error) {
            throw new IllegalArgumentException("Unable to read TCL initialization metadata", error);
        }
    }

    private void requestAd() {
        if (released || cancellationRequested || requestIssued) return;
        requestIssued = true;
        requestOwner = ownerToken;
        requestCallInProgress = true;
        main.removeCallbacks(initPoll);
        try {
            listener.onTrace("TCL_AD_REQUEST", "channel=" + channel);
            Controller pending = Ad.get().begin(context).lazyLoad()
                .setAdType(AdType.WATERFALL)
                .setPlayerType(0)
                // TCL UniPlayer 2.8.02: surfaceType 0 = SurfaceView, 1 = TextureView.
                .setPlayerViewType(1)
                .setVolume(soundEnabled ? 1f : 0f)
                .setRequestParams(buildRequestParams())
                .listen(new AdStatusListener() {
                    @Override public void onAdLoaded(Controller value) { dispatch(() -> handleLoaded(value)); }
                    @Override public void onAdStartPlay() { dispatch(TclAdPlayer.this::handleStarted); }
                    @Override public void onAdStartPlay(double progress) { dispatch(TclAdPlayer.this::handleStarted); }
                    @Override public void onAdFinished() {
                        dispatch(() -> { if (ownsRequest()) listener.onCompleted(); });
                    }
                    @Override public void onAdError(int code) {
                        dispatch(() -> {
                            if (ownsRequest()) listener.onError(new AdError(AdErrorCode.AD_PLAYBACK_ERROR,
                                AdErrorStage.PLAYER, Integer.toString(code), null, "TCL", Integer.toString(code), null));
                        });
                    }
                    @Override public void onContainerSizeError() {
                        dispatch(() -> {
                            if (ownsRequest()) listener.onError(new AdError(AdErrorCode.INVALID_ARGUMENT,
                                AdErrorStage.PLAYER, "onContainerSizeError", null, "TCL", null, null));
                        });
                    }
                }).start();
            if (released) {
                releaseController(pending);
            } else if (controller == null) {
                controller = pending;
            }
        } catch (RuntimeException | LinkageError error) { fail(error, AdErrorStage.PLAYER); }
        finally {
            requestCallInProgress = false;
            if (released && requestOwner == ownerToken) requestOwner = null;
        }
    }

    private boolean ownsRequest() { return !released && !cancellationRequested && requestOwner == ownerToken; }

    private void handleLoaded(Controller value) {
        // Repeated/late wrappers can point at the same engine, or one now reused by a new request.
        // Its owning request releases it once; these notifications must never stop it.
        if (!ownsRequest() || loaded) return;
        if (value == null) {
            fail(new IllegalStateException("TCL returned no controller"), AdErrorStage.PLAYER);
            return;
        }
        if (controller == null) controller = value;
        loaded = true;
        listener.onLoaded();
        // A host onLoaded callback can release the entire flow before playback begins.
        startLoadedAd();
    }

    private void startLoadedAd() {
        if (!ownsRequest() || !loaded || controller == null || controllerStarted || layer == null
            || layer.getWidth() <= 0 || layer.getHeight() <= 0) return;
        try {
            controllerStarted = true;
            controller.setVolume(soundEnabled ? 1f : 0f);
            controller.start(layer);
        } catch (RuntimeException | LinkageError error) { fail(error, AdErrorStage.PLAYER); }
    }

    private void handleStarted() {
        if (!ownsRequest() || started) return;
        started = true;
        layer.markStarted();
        listener.onStarted();
    }

    private RequestParams buildRequestParams() {
        RequestParams.Builder builder = new RequestParams.Builder()
            .setAppCat("app").setAppDomain(context.getPackageName()).setChannelName(channel)
            .setContentLanguage(Locale.getDefault().getLanguage()).setContentTitle("App Content")
            .setDevice("android").setDeviceLanguage(Locale.getDefault().toLanguageTag())
            .setDeviceMake(Build.MANUFACTURER).setDeviceModel(Build.MODEL);
        String country = Locale.getDefault().getCountry();
        if (country != null && country.length() == 2) builder.setArea(country.toUpperCase(Locale.US));
        SharedPreferences consent = context.getSharedPreferences(context.getPackageName() + "_preferences", Context.MODE_PRIVATE);
        Object applies = consent.getAll().get("IABTCF_gdprApplies");
        if (applies != null && ("0".equals(applies.toString()) || "1".equals(applies.toString()))) {
            builder.setGdpr(applies.toString());
        }
        String tc = consent.getString("IABTCF_TCString", "");
        if (tc != null && !tc.isEmpty()) builder.setGdprConsent(tc).setGdprSource("IABTCF");
        return builder.build();
    }

    @Override public void pause() {
        if (ownsRequest() && controller != null) controller.pause();
    }
    @Override public void resume() {
        if (ownsRequest() && controller != null) controller.resume();
    }
    @Override public void setSoundEnabled(boolean enabled) {
        soundEnabled = enabled;
        if (ownsRequest() && controller != null) controller.setVolume(enabled ? 1f : 0f);
    }
    @Override public void requestCancellation() { cancellationRequested = true; }
    @Override public void release() {
        if (released) return;
        released = true;
        main.removeCallbacks(initPoll);
        if (layer != null) {
            layer.hideForRelease();
            layer.removeOnLayoutChangeListener(layoutListener);
        }
        Controller old = controller;
        controller = null;
        releaseController(old);
        if (!requestCallInProgress && requestOwner == ownerToken) requestOwner = null;
        if (layer != null) layer.dispose();
    }

    private void releaseController(Controller value) {
        if (value == null || engineReleased || requestOwner != ownerToken) return;
        engineReleased = true;
        try { if (layer != null) value.stop(layer); }
        catch (RuntimeException | LinkageError error) { SdkLog.w("AdSdkTcl", "controller stop failed", error); }
        try { value.release(); }
        catch (RuntimeException | LinkageError error) { SdkLog.w("AdSdkTcl", "controller release failed", error); }
    }

    private void fail(Throwable error, AdErrorStage stage) {
        // Initialization can fail before a native request has acquired the engine.
        if (!released && !cancellationRequested && (!requestIssued || ownsRequest())) listener.onError(new AdError(AdErrorCode.PLAYER_ERROR, stage,
            error.getMessage(), error, "TCL", null, null));
    }
    private void dispatch(Runnable action) {
        if (Looper.myLooper() == Looper.getMainLooper()) action.run(); else main.post(action);
    }
}
