package com.smart.android.adsdk.internal;

import android.content.Context;
import android.graphics.Color;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import com.google.ads.interactivemedia.v3.api.AdEvent;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.ima.ImaAdsLoader;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.exoplayer.source.SilenceMediaSource;
import androidx.media3.exoplayer.source.ads.AdsMediaSource;
import androidx.media3.ui.PlayerView;
import com.smart.android.adsdk.AdError;
import com.smart.android.adsdk.AdErrorCode;
import com.smart.android.adsdk.AdErrorStage;
import com.smart.android.adsdk.modern.R;

final class AdPlaybackController implements AdPlayer {
    private static final long SILENCE_CONTENT_DURATION_US = 60_000_000L;

    private final Context context;
    private final ViewGroup container;
    private final Listener listener;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final PlaybackEventGate eventGate = new PlaybackEventGate();

    private FrameLayout adRoot;
    private PlayerView playerView;
    private ExoPlayer player;
    private ImaAdsLoader adsLoader;
    private long startupTimeoutMs;
    private Runnable startupTimeoutAction;
    private boolean hiddenMode;

    AdPlaybackController(Context context, ViewGroup container, Listener listener) {
        this.context = context;
        this.container = container;
        this.listener = listener;
    }

    @Override
    public void play(AdPlaybackConfig config, boolean soundEnabled) {
        SdkLog.i("AdSdkPlayer", "play adTagUrl=" + config.getAdTagUrl()
            + " hiddenMode=" + config.isHiddenMode() + " soundEnabled=" + soundEnabled);
        releasePlayerResources();
        startupTimeoutMs = config.getAdStartupTimeoutMs();
        hiddenMode = config.isHiddenMode();
        createPlayer(config.getAdLoadTimeoutMs());
        attachPlayerView();
        armStartupTimeout();

        player.setVolume(soundEnabled ? 1f : 0f);
        player.setMediaSource(createAdMediaSource(config.getAdTagUrl()));
        player.setPlayWhenReady(true);
        player.prepare();
    }

    @Override
    public void pause() {
        if (player != null) {
            player.pause();
        }
    }

    @Override
    public void resume() {
        if (player != null) {
            player.play();
        }
    }

    @Override
    public void setSoundEnabled(boolean enabled) {
        if (player != null) {
            player.setVolume(enabled ? 1f : 0f);
        }
    }

    @Override
    public void release() {
        eventGate.markTerminal();
        releasePlayerResources();
    }

    private void createPlayer(int adLoadTimeoutMs) {
        // TextureView participates in the normal view hierarchy, so the entire video obeys
        // adRoot alpha on every supported Android version (SurfaceView uses a separate surface).
        playerView = (PlayerView) LayoutInflater.from(container.getContext()).inflate(
            R.layout.ad_sdk_modern_player_view, container, false
        );
        View videoSurface = playerView.getVideoSurfaceView();
        if (!(videoSurface instanceof TextureView)) {
            throw new IllegalStateException("SDK player layout must use TextureView");
        }
        ((TextureView) videoSurface).setOpaque(false);
        videoSurface.setAlpha(hiddenMode ? 0f : 1f);
        playerView.setLayoutParams(new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ));
        playerView.setUseController(false);
        playerView.setControllerHideDuringAds(true);
        playerView.setKeepContentOnPlayerReset(false);
        playerView.setShutterBackgroundColor(Color.TRANSPARENT);
        playerView.setBackgroundColor(Color.TRANSPARENT);

        adsLoader = new ImaAdsLoader.Builder(context)
            .setMediaLoadTimeoutMs(adLoadTimeoutMs)
            .setAdEventListener(this::handleAdEvent)
            .setAdErrorListener(event -> {
                com.google.ads.interactivemedia.v3.api.AdError error = event == null ? null : event.getError();
                fail(new AdError(
                    eventGateHasStarted() ? AdErrorCode.AD_PLAYBACK_ERROR : AdErrorCode.AD_LOAD_ERROR,
                    AdErrorStage.PLAYER,
                    error == null ? null : error.getMessage(),
                    error,
                    "IMA",
                    error == null ? null : String.valueOf(error.getErrorCodeNumber()),
                    null
                ));
            })
            .build();

        DefaultMediaSourceFactory mediaSourceFactory =
            new DefaultMediaSourceFactory(new DefaultDataSource.Factory(context))
                .setAdsLoaderProvider(adsConfiguration -> adsLoader)
                .setAdViewProvider(playerView);

        player = new ExoPlayer.Builder(context)
            .setMediaSourceFactory(mediaSourceFactory)
            .build();
        player.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int playbackState) {
                if (playbackState == Player.STATE_ENDED && !eventGateHasStarted()) {
                    fail(
                        AdErrorCode.AD_LOAD_ERROR,
                        "Ad playback ended before the ad started",
                        null
                    );
                }
            }

            @Override
            public void onRenderedFirstFrame() {
                listener.onTrace("AD_FIRST_FRAME", "hiddenMode=" + hiddenMode);
                eventGate.markFirstFrame();
                revealWhenReady();
            }

            @Override
            public void onPlayerError(PlaybackException error) {
                fail(new AdError(
                    AdErrorCode.PLAYER_ERROR,
                    AdErrorStage.PLAYER,
                    error.getMessage(),
                    error,
                    "MEDIA3",
                    String.valueOf(error.errorCode),
                    null
                ));
            }
        });
        playerView.setPlayer(player);
        adsLoader.setPlayer(player);
    }

    private void attachPlayerView() {
        adRoot = new FrameLayout(context);
        adRoot.setLayoutParams(new ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ));
        adRoot.setBackgroundColor(Color.TRANSPARENT);
        adRoot.setAlpha(0f);
        adRoot.addView(playerView);
        container.addView(adRoot);
        traceDisplay("attached");
    }

    private AdsMediaSource createAdMediaSource(String adTagUrl) {
        SilenceMediaSource contentSource = new SilenceMediaSource(SILENCE_CONTENT_DURATION_US);
        DefaultMediaSourceFactory adMediaSourceFactory =
            new DefaultMediaSourceFactory(new DefaultDataSource.Factory(context));
        return new AdsMediaSource(
            contentSource,
            new DataSpec(Uri.parse(adTagUrl)),
            "ad_sdk_" + System.currentTimeMillis(),
            adMediaSourceFactory,
            adsLoader,
            playerView
        );
    }

    private void handleAdEvent(AdEvent event) {
        listener.onTrace("IMA_EVENT", "type=" + event.getType() + " data=" + event.getAdData());
        SdkLog.i("AdSdkPlayer", "IMA event=" + event.getType() + " data=" + event.getAdData());
        switch (event.getType()) {
            case LOADED:
                notifyLoaded();
                extendStartupTimeout();
                break;
            case CONTENT_PAUSE_REQUESTED:
                extendStartupTimeout();
                break;
            case STARTED:
                notifyLoaded();
                if (eventGate.markStarted()) {
                    clearStartupTimeout();
                    listener.onStarted();
                }
                revealWhenReady();
                break;
            case COMPLETED:
            case ALL_ADS_COMPLETED:
                if (eventGateHasStarted()) {
                    complete();
                } else {
                    fail(
                        AdErrorCode.AD_LOAD_ERROR,
                        "Ad playback completed before the ad started",
                        null
                    );
                }
                break;
            case SKIPPED:
                if (eventGateHasStarted()) {
                    skip("AD_SKIPPED");
                } else {
                    fail(
                        AdErrorCode.AD_LOAD_ERROR,
                        "Ad playback skipped before the ad started",
                        null
                    );
                }
                break;
            case CONTENT_RESUME_REQUESTED:
                if (!eventGateHasStarted()) {
                    fail(
                        AdErrorCode.AD_LOAD_ERROR,
                        "Ad playback resumed content before the ad started",
                        null
                    );
                }
                break;
            default:
                break;
        }
    }

    private void notifyLoaded() {
        if (eventGate.markLoaded()) {
            listener.onLoaded();
        }
    }

    private void revealWhenReady() {
        if (adRoot == null) return;
        if (hiddenMode) {
            adRoot.animate().cancel();
            adRoot.setAlpha(0f);
            traceDisplay("kept_hidden");
            return;
        }
        if (eventGate.consumeRevealReady()) {
            adRoot.animate().cancel();
            adRoot.animate().alpha(1f).setDuration(150L).start();
            traceDisplay("revealing_after_first_frame");
        }
    }

    private void traceDisplay(String action) {
        View surface = playerView == null ? null : playerView.getVideoSurfaceView();
        String details = "action=" + action + " hiddenMode=" + hiddenMode
            + " surface=" + (surface == null ? "none" : surface.getClass().getSimpleName())
            + " adRootAlpha=" + (adRoot == null ? "none" : adRoot.getAlpha())
            + " surfaceAlpha=" + (surface == null ? "none" : surface.getAlpha())
            + " hostAlpha=" + container.getAlpha()
            + " hostVisibility=" + container.getVisibility()
            + " hardwareAccelerated=" + container.isHardwareAccelerated();
        SdkLog.i("AdSdkPlayer", details);
        listener.onTrace("AD_DISPLAY_STATE", details);
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
        fail(new AdError(code, AdErrorStage.PLAYER,
            cause == null ? message : cause.getMessage(), cause));
    }

    private void fail(AdError error) {
        SdkLog.e("AdSdkPlayer", "error=" + error, error.getCause());
        if (eventGate.markTerminal()) {
            clearStartupTimeout();
            listener.onError(error);
        }
    }

    private void armStartupTimeout() {
        clearStartupTimeout();
        startupTimeoutAction = () -> fail(
            AdErrorCode.TIMEOUT,
            "Ad playback did not start within " + startupTimeoutMs + " ms",
            null
        );
        mainHandler.postDelayed(startupTimeoutAction, startupTimeoutMs);
    }

    private void extendStartupTimeout() {
        clearStartupTimeout();
        if (!eventGateHasStarted()) {
            armStartupTimeout();
        }
    }

    private void clearStartupTimeout() {
        if (startupTimeoutAction != null) {
            mainHandler.removeCallbacks(startupTimeoutAction);
            startupTimeoutAction = null;
        }
    }

    private boolean eventGateHasStarted() {
        return eventGate.hasStarted();
    }

    private void releasePlayerResources() {
        clearStartupTimeout();
        // Hide before tearing down the texture to avoid exposing an empty frame during release.
        if (adRoot != null) {
            adRoot.animate().cancel();
            adRoot.setAlpha(0f);
        }
        if (adsLoader != null) {
            adsLoader.setPlayer(null);
            adsLoader.release();
            adsLoader = null;
        }
        if (playerView != null) {
            playerView.setPlayer(null);
        }
        if (player != null) {
            player.stop();
            player.release();
            player = null;
        }
        if (adRoot != null) {
            adRoot.animate().cancel();
            container.removeView(adRoot);
            adRoot.removeAllViews();
            adRoot = null;
        }
        playerView = null;
        hiddenMode = false;
    }
}
