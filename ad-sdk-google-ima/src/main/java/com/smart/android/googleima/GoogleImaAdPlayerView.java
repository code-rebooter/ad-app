package com.smart.android.googleima;

import android.content.Context;
import android.graphics.Color;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.widget.FrameLayout;

import androidx.annotation.Nullable;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.Util;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.ima.ImaAdsLoader;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.exoplayer.source.SilenceMediaSource;
import androidx.media3.exoplayer.source.ads.AdsMediaSource;
import androidx.media3.ui.PlayerView;

import com.google.ads.interactivemedia.v3.api.AdEvent;
import com.smart.android.googleima.internal.GoogleImaRuntimeBridge;
import com.smart.android.googleima.logging.PropertyLog;

public final class GoogleImaAdPlayerView extends FrameLayout {
    private static final String TAG = "GoogleImaAdPlayer";
    private static final long SILENCE_CONTENT_DURATION_US = 60_000_000L;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final PlayerView playerView;

    private ExoPlayer player;
    private ImaAdsLoader adsLoader;
    private Runnable startupTimeout;
    private GoogleImaAdListener listener = new GoogleImaAdListener() {};
    private boolean terminal;
    private boolean loaded;
    private boolean started;
    private boolean firstFrameRendered;
    private boolean firstFrameDelivered;
    private boolean runtimeAuditActive;

    public GoogleImaAdPlayerView(Context context) {
        this(context, null);
    }

    public GoogleImaAdPlayerView(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public GoogleImaAdPlayerView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        setBackgroundColor(Color.BLACK);
        setFocusable(true);
        setClickable(true);
        playerView = new PlayerView(context);
        playerView.setLayoutParams(new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        playerView.setUseController(false);
        playerView.setControllerHideDuringAds(true);
        playerView.setKeepContentOnPlayerReset(false);
        playerView.setShutterBackgroundColor(Color.BLACK);
        addView(playerView);
    }

    public void play(GoogleImaAdRequest request, @Nullable GoogleImaAdListener listener) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            post(() -> play(request, listener));
            return;
        }
        releasePlayerOnly();
        this.listener = listener == null ? new GoogleImaAdListener() {} : listener;
        terminal = false;
        loaded = false;
        started = false;
        firstFrameRendered = false;
        firstFrameDelivered = false;

        GoogleImaRuntimeBridge.beginAudit(this.listener, request.getAdTagUrl());
        runtimeAuditActive = true;
        initializePlayer(request);
        player.setVolume(request.isSoundEnabled() ? 1f : 0f);
        player.setMediaSource(createAdMediaSource(request));
        player.setPlayWhenReady(true);
        player.prepare();

        startupTimeout = () -> {
            if (!started) {
                failOnce("ad_startup_timeout");
            }
        };
        mainHandler.postDelayed(startupTimeout, request.getAdStartupTimeoutMs());
    }

    public void release() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            post(this::release);
            return;
        }
        terminal = true;
        releasePlayerOnly();
        listener = new GoogleImaAdListener() {};
    }

    private void initializePlayer(GoogleImaAdRequest request) {
        adsLoader = new ImaAdsLoader.Builder(getContext())
            .setMediaLoadTimeoutMs(request.getAdLoadTimeoutMs())
            .setAdEventListener(event -> {
                PropertyLog.i(TAG, "IMA event=" + event.getType());
                AdEvent.AdEventType type = event.getType();
                if (type == AdEvent.AdEventType.LOADED) {
                    notifyLoadedOnce();
                } else if (type == AdEvent.AdEventType.STARTED) {
                    notifyLoadedOnce();
                    started = true;
                    clearStartupTimeout();
                    listener.onAdStarted();
                    notifyFirstFrameOnce();
                } else if (type == AdEvent.AdEventType.CONTENT_RESUME_REQUESTED) {
                    if (!started) failOnce("content_resumed_before_ad_start");
                } else if (type == AdEvent.AdEventType.COMPLETED
                    || type == AdEvent.AdEventType.ALL_ADS_COMPLETED) {
                    if (started) completeOnce(type.name());
                    else failOnce("ad_completed_before_start");
                } else if (type == AdEvent.AdEventType.SKIPPED) {
                    if (started) skipOnce(type.name());
                    else failOnce("ad_skipped_before_start");
                }
            })
            .setAdErrorListener(event -> failOnce(event == null ? "unknown_ima_error" : event.toString()))
            .build();

        DefaultMediaSourceFactory mediaSourceFactory =
            new DefaultMediaSourceFactory(createNetworkDataSourceFactory(request));
        mediaSourceFactory.setLocalAdInsertionComponents(configuration -> adsLoader, playerView);

        player = new ExoPlayer.Builder(getContext())
            .setMediaSourceFactory(mediaSourceFactory)
            .build();
        player.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int playbackState) {
                if (!terminal && !started && playbackState == Player.STATE_ENDED) {
                    failOnce("playback_ended_before_ad_start");
                }
            }

            @Override
            public void onRenderedFirstFrame() {
                firstFrameRendered = true;
                notifyFirstFrameOnce();
            }

            @Override
            public void onPlayerError(PlaybackException error) {
                failOnce(error.getMessage() == null ? "playback_error" : error.getMessage());
            }
        });
        playerView.setPlayer(player);
        adsLoader.setPlayer(player);
    }

    private AdsMediaSource createAdMediaSource(GoogleImaAdRequest request) {
        SilenceMediaSource contentSource = new SilenceMediaSource(SILENCE_CONTENT_DURATION_US);
        return new AdsMediaSource(
            contentSource,
            new DataSpec(Uri.parse(request.getAdTagUrl())),
            "google_ima_" + System.currentTimeMillis(),
            new DefaultMediaSourceFactory(createNetworkDataSourceFactory(request)),
            adsLoader,
            playerView
        );
    }

    private DefaultDataSource.Factory createNetworkDataSourceFactory(GoogleImaAdRequest request) {
        String userAgent = request.getUserAgent();
        if (userAgent == null) {
            userAgent = Util.getUserAgent(getContext(), "ad-sdk-google-ima");
        }
        DefaultHttpDataSource.Factory httpFactory = new DefaultHttpDataSource.Factory()
            .setUserAgent(userAgent);
        return new DefaultDataSource.Factory(getContext(), httpFactory);
    }

    private void notifyLoadedOnce() {
        if (terminal || loaded) return;
        loaded = true;
        listener.onAdLoaded();
    }

    private void notifyFirstFrameOnce() {
        if (terminal || firstFrameDelivered || !started || !firstFrameRendered) return;
        firstFrameDelivered = true;
        listener.onAdFirstFrameRendered();
    }

    private void completeOnce(String event) {
        if (terminal) return;
        terminal = true;
        clearStartupTimeout();
        listener.onAdCompleted(event);
    }

    private void skipOnce(String event) {
        if (terminal) return;
        terminal = true;
        clearStartupTimeout();
        listener.onAdSkipped(event);
    }

    private void failOnce(String message) {
        if (terminal) return;
        terminal = true;
        clearStartupTimeout();
        listener.onAdError(message);
    }

    private void releasePlayerOnly() {
        clearStartupTimeout();
        if (adsLoader != null) {
            adsLoader.setPlayer(null);
            adsLoader.release();
            adsLoader = null;
        }
        playerView.setPlayer(null);
        if (player != null) {
            player.stop();
            player.release();
            player = null;
        }
        if (runtimeAuditActive) {
            runtimeAuditActive = false;
            GoogleImaRuntimeBridge.endAudit(listener, "player_release");
        }
    }

    private void clearStartupTimeout() {
        if (startupTimeout != null) {
            mainHandler.removeCallbacks(startupTimeout);
            startupTimeout = null;
        }
    }
}
