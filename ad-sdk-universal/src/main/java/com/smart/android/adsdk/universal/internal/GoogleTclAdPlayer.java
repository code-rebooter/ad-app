package com.smart.android.adsdk.universal.internal;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import com.smart.android.adsdk.universal.AdError;
import com.smart.android.adsdk.universal.AdErrorCode;
import com.smart.android.adsdk.universal.AdErrorStage;
import com.tcl.ff.component.overseabase.base.constant.AdType;
import com.tcl.ff.component.vastad.Ad;
import com.tcl.ff.component.vastad.Controller;
import com.tcl.ff.component.vastad.core.callbacks.AdStatusListener;

/** TCL 2.8.02 adapter. The surrounding channel session owns authorization and total timeout. */
final class GoogleTclAdPlayer implements AdPlayer {
    // TCL caches its underlying lazy-load engine. Java Controller wrappers are not ownership IDs.
    private static Object requestOwner;
    private final Object ownerToken = new Object();
    private boolean requestCallInProgress, engineReleased;
    private final Context context;
    private final ViewGroup host;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final View.OnLayoutChangeListener layoutListener = (v, l, t, r, b, ol, ot, or, ob) -> startLoadedAd();
    private TclDisplayLayer layer;
    private Controller controller;
    private volatile boolean cancellationRequested;
    private boolean released, requestIssued, loaded, started, controllerStarted, soundEnabled;

    GoogleTclAdPlayer(Context context, ViewGroup host, Listener listener) {
        this.context = context;
        this.host = host;
        this.listener = listener;
    }

    @Override public void play(AdPlaybackConfig config, boolean soundEnabled) {
        if (released || cancellationRequested) return;
        this.soundEnabled = soundEnabled;
        layer = new TclDisplayLayer(host.getContext(), host, config.isHiddenMode(), listener);
        layer.addOnLayoutChangeListener(layoutListener);
        listener.onTrace("AD_DISPLAY_STATE", "provider=google hiddenMode=" + config.isHiddenMode()
            + " soundEnabled=" + soundEnabled + " textureView=true");
        try {
            Ad.get().setEnableLog(SdkLog.isEnabled());
            NativeRuntime.initializeGoogle(context, () -> main.post(() -> requestAd(config)),
                error -> main.post(() -> fail(error, AdErrorStage.INITIALIZATION)));
        } catch (RuntimeException | LinkageError error) {
            fail(error, AdErrorStage.INITIALIZATION);
        }
    }

    private void requestAd(AdPlaybackConfig playback) {
        if (released || cancellationRequested || requestIssued) return;
        requestIssued = true;
        requestOwner = ownerToken;
        requestCallInProgress = true;
        try {
            listener.onTrace("AD_SDK_REQUEST", "provider=google,sdkInternalTimeoutSource=original_configuration");
            var nativeConfig = Ad.get().begin(context).lazyLoad()
                .setAdType(AdType.WATERFALL)
                .setPlayerType(0)
                // TCL UniPlayer 2.8.02: surfaceType 0 = SurfaceView, 1 = TextureView.
                .setPlayerViewType(1)
                .setVolume(soundEnabled ? 1f : 0f)
                .listen(new AdStatusListener() {
                    @Override public void onAdLoaded(Controller value) { raw("onAdLoaded"); dispatch(() -> handleLoaded(value)); }
                    @Override public void onAdStartPlay() { raw("onAdStartPlay()"); dispatch(GoogleTclAdPlayer.this::handleStarted); }
                    @Override public void onAdStartPlay(double progress) { raw("onAdStartPlay(double),seconds=" + progress); dispatch(GoogleTclAdPlayer.this::handleStarted); }
                    @Override public void onAdFinished() {
                        raw("onAdFinished");
                        main.post(() -> { if (ownsRequest()) listener.onCompleted(); });
                    }
                    @Override public void onAdError(int code) {
                        raw("onAdError,code=" + code);
                        main.post(() -> {
                            if (ownsRequest()) listener.onError(new AdError(AdErrorCode.AD_PLAYBACK_ERROR,
                                AdErrorStage.PLAYER, Integer.toString(code), null, "TCL", Integer.toString(code), null));
                        });
                    }
                    @Override public void onContainerSizeError() {
                        raw("onContainerSizeError");
                        main.post(() -> {
                            if (ownsRequest()) listener.onError(new AdError(AdErrorCode.INVALID_ARGUMENT,
                                AdErrorStage.PLAYER, "onContainerSizeError", null, "TCL", null, null));
                        });
                    }
                });
            Controller pending = OriginalBoundary.start(nativeConfig, new ResolvedAd.Builder(playback.getAdTagUrl()).build());
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

    private void raw(String callback) { listener.onTrace("AD_TCL_CALLBACK", "provider=google,callback=" + callback); }

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
