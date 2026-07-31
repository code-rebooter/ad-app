package com.smart.android.adsdk.internal;

import android.content.Context;
import android.graphics.Color;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.PlaybackException;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.ext.ima.ImaAdsLoader;
import com.google.android.exoplayer2.source.DefaultMediaSourceFactory;
import com.google.android.exoplayer2.source.SilenceMediaSource;
import com.google.android.exoplayer2.source.ads.AdsMediaSource;
import com.google.android.exoplayer2.ui.PlayerView;
import com.google.android.exoplayer2.upstream.DataSpec;
import com.google.android.exoplayer2.upstream.DefaultDataSource;
import com.google.ads.interactivemedia.v3.api.AdEvent;
import com.smart.android.adsdk.AdError;
import com.smart.android.adsdk.AdErrorCode;
import com.smart.android.adsdk.AdErrorStage;

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

    AdPlaybackController(Context context, ViewGroup container, Listener listener) {
        this.context = context;
        this.container = container;
        this.listener = listener;
    }

    @Override
    public void play(AdPlaybackConfig config, boolean soundEnabled) {
        releasePlayerResources();
        startupTimeoutMs = config.getAdStartupTimeoutMs();
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
        playerView = new PlayerView(context);
        playerView.setLayoutParams(new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ));
        playerView.setUseController(false);
        playerView.setControllerHideDuringAds(true);
        playerView.setKeepContentOnPlayerReset(false);
        playerView.setShutterBackgroundColor(Color.BLACK);

        adsLoader = new ImaAdsLoader.Builder(context)
            .setMediaLoadTimeoutMs(adLoadTimeoutMs)
            .setAdEventListener(this::handleAdEvent)
            .setAdErrorListener(error -> fail(
                eventGateHasStarted()
                    ? AdErrorCode.AD_PLAYBACK_ERROR
                    : AdErrorCode.AD_LOAD_ERROR,
                error == null ? "Unknown ad playback error" : error.toString(),
                null
            ))
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
                eventGate.markFirstFrame();
                revealWhenReady();
            }

            @Override
            public void onPlayerError(PlaybackException error) {
                fail(
                    AdErrorCode.PLAYER_ERROR,
                    error.getMessage() == null ? "Media3 playback failed" : error.getMessage(),
                    error
                );
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
        adRoot.setBackgroundColor(Color.BLACK);
        adRoot.setAlpha(0f);
        adRoot.addView(playerView);
        container.addView(adRoot);
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
        if (eventGate.consumeRevealReady() && adRoot != null) {
            adRoot.animate().cancel();
            adRoot.animate().alpha(1f).setDuration(150L).start();
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
    }
}
