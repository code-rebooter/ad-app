package com.smart.android.adsdk.internal;

import android.view.ViewGroup;
import com.smart.android.adsdk.AdError;
import com.smart.android.adsdk.AdErrorCode;
import com.smart.android.adsdk.AdErrorStage;

final class AdPlaybackController implements AdPlayer, AdPlaybackEngine.Listener {
    private final ViewGroup container;
    private final Listener listener;
    private final AdPlaybackEngine engine;
    private final boolean ownsEngine;
    private final TimeoutScheduler timeoutScheduler;
    private final PlaybackEventGate eventGate = new PlaybackEventGate();

    private long startupTimeoutMs;
    private Cancellable startupTimeoutCall;

    AdPlaybackController(
        ViewGroup container,
        Listener listener,
        AdPlaybackEngine engine,
        boolean ownsEngine,
        TimeoutScheduler timeoutScheduler
    ) {
        this.container = container;
        this.listener = listener;
        this.engine = engine;
        this.ownsEngine = ownsEngine;
        this.timeoutScheduler = timeoutScheduler;
    }

    @Override
    public void play(AdPlaybackConfig config, boolean soundEnabled) {
        startupTimeoutMs = config.getAdStartupTimeoutMs();
        armStartupTimeout();
        try {
            engine.play(container, config, soundEnabled, this);
        } catch (RuntimeException error) {
            clearStartupTimeout();
            throw error;
        }
    }

    @Override
    public void pause() {
        engine.pause(this);
    }

    @Override
    public void resume() {
        engine.resume(this);
    }

    @Override
    public void setSoundEnabled(boolean enabled) {
        engine.setSoundEnabled(this, enabled);
    }

    @Override
    public void release() {
        eventGate.markTerminal();
        clearStartupTimeout();
        engine.detach(this);
        if (ownsEngine) {
            engine.release();
        }
    }

    @Override
    public void onLoaded() {
        notifyLoaded();
        extendStartupTimeout();
    }

    @Override
    public void onContentPauseRequested() {
        extendStartupTimeout();
    }

    @Override
    public void onStarted() {
        notifyLoaded();
        if (eventGate.markStarted()) {
            clearStartupTimeout();
            listener.onStarted();
        }
    }

    @Override
    public void onCompleted() {
        if (eventGateHasStarted()) {
            complete();
        } else {
            fail(
                AdErrorCode.AD_LOAD_ERROR,
                "Ad playback completed before the ad started",
                null
            );
        }
    }

    @Override
    public void onSkipped() {
        if (eventGateHasStarted()) {
            skip("AD_SKIPPED");
        } else {
            fail(
                AdErrorCode.AD_LOAD_ERROR,
                "Ad playback skipped before the ad started",
                null
            );
        }
    }

    @Override
    public void onContentResumeRequested() {
        if (!eventGateHasStarted()) {
            fail(
                AdErrorCode.AD_LOAD_ERROR,
                "Ad playback resumed content before the ad started",
                null
            );
        }
    }

    @Override
    public void onPlaybackEnded() {
        if (!eventGateHasStarted()) {
            fail(
                AdErrorCode.AD_LOAD_ERROR,
                "Ad playback ended before the ad started",
                null
            );
        }
    }

    @Override
    public void onAdError(String message) {
        fail(
            eventGateHasStarted()
                ? AdErrorCode.AD_PLAYBACK_ERROR
                : AdErrorCode.AD_LOAD_ERROR,
            message,
            null
        );
    }

    @Override
    public void onPlayerError(String message, Throwable cause) {
        fail(AdErrorCode.PLAYER_ERROR, message, cause);
    }

    private void notifyLoaded() {
        if (eventGate.markLoaded()) {
            listener.onLoaded();
        }
    }

    private void complete() {
        if (eventGate.markTerminal()) {
            clearStartupTimeout();
            listener.onCompleted();
        }
    }

    private void skip(String reason) {
        if (eventGate.markTerminal()) {
            clearStartupTimeout();
            listener.onSkipped(reason);
        }
    }

    private void fail(AdErrorCode code, String message, Throwable cause) {
        if (eventGate.markTerminal()) {
            clearStartupTimeout();
            listener.onError(new AdError(code, AdErrorStage.PLAYER, message, cause));
        }
    }

    private void armStartupTimeout() {
        clearStartupTimeout();
        startupTimeoutCall = timeoutScheduler.schedule(
            () -> fail(
                AdErrorCode.TIMEOUT,
                "Ad playback did not start within " + startupTimeoutMs + " ms",
                null
            ),
            startupTimeoutMs
        );
    }

    private void extendStartupTimeout() {
        clearStartupTimeout();
        if (!eventGateHasStarted()) {
            armStartupTimeout();
        }
    }

    private void clearStartupTimeout() {
        if (startupTimeoutCall != null) {
            startupTimeoutCall.cancel();
            startupTimeoutCall = null;
        }
    }

    private boolean eventGateHasStarted() {
        return eventGate.hasStarted();
    }

}
