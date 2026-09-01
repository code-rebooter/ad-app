package com.smart.android.adsdk.internal;

import android.content.Context;
import android.graphics.Color;
import android.net.Uri;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.FrameLayout;
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
import com.google.ads.interactivemedia.v3.api.AdEvent;

final class ImaAdPlaybackEngine implements AdPlaybackEngine {
    private static final String TAG = "ImaPlaybackEngine";
    private static final long SILENCE_CONTENT_DURATION_US = 60_000_000L;

    private final Context context;

    private Context googleSdkContext;
    private PlayerView playerView;
    private ExoPlayer player;
    private ImaAdsLoader adsLoader;
    private FrameLayout adRoot;
    private ViewGroup activeContainer;
    private Listener activeListener;
    private int configuredAdLoadTimeoutMs = -1;
    private boolean hiddenMode;
    private boolean adStarted;
    private boolean firstFrameRendered;

    ImaAdPlaybackEngine(Context context) {
        this.context = context;
    }

    @Override
    public void play(
        ViewGroup container,
        AdPlaybackConfig config,
        boolean soundEnabled,
        Listener listener
    ) {
        if (activeListener != null) {
            throw new IllegalStateException("Another ad session is already using the playback engine");
        }
        if (container == null) {
            throw new IllegalArgumentException("Ad container must not be null");
        }

        ensureInitialized(config.getAdLoadTimeoutMs());
        activeListener = listener;
        activeContainer = container;
        hiddenMode = config.isHiddenMode();
        adStarted = false;
        firstFrameRendered = false;
        attachPlayerView();

        player.setVolume(soundEnabled ? 1f : 0f);
        player.setMediaSource(createAdMediaSource(config.getAdTagUrl()));
        player.setPlayWhenReady(true);
        player.prepare();
    }

    @Override
    public void pause(Listener listener) {
        if (isActive(listener) && player != null) {
            player.pause();
        }
    }

    @Override
    public void resume(Listener listener) {
        if (isActive(listener) && player != null) {
            player.play();
        }
    }

    @Override
    public void setSoundEnabled(Listener listener, boolean enabled) {
        if (isActive(listener) && player != null) {
            player.setVolume(enabled ? 1f : 0f);
        }
    }

    @Override
    public void detach(Listener listener) {
        if (!isActive(listener)) {
            return;
        }
        activeListener = null;
        stopCurrentMedia();
        detachPlayerView();
        activeContainer = null;
        hiddenMode = false;
        adStarted = false;
        firstFrameRendered = false;
        Log.i(TAG, "Detached ad session; playback engine retained");
    }

    @Override
    public void release() {
        activeListener = null;
        stopCurrentMedia();
        detachPlayerView();
        activeContainer = null;

        if (adsLoader != null) {
            adsLoader.setPlayer(null);
            adsLoader.release();
            adsLoader = null;
        }
        if (playerView != null) {
            playerView.setPlayer(null);
            playerView = null;
        }
        if (player != null) {
            player.release();
            player = null;
        }
        googleSdkContext = null;
        configuredAdLoadTimeoutMs = -1;
        hiddenMode = false;
        adStarted = false;
        firstFrameRendered = false;
        Log.i(TAG, "Playback engine released");
    }

    private boolean isActive(Listener listener) {
        return listener != null && listener == activeListener;
    }

    private void ensureInitialized(int adLoadTimeoutMs) {
        if (player != null) {
            if (configuredAdLoadTimeoutMs != adLoadTimeoutMs) {
                Log.w(
                    TAG,
                    "Reused IMA engine keeps initial media load timeout "
                        + configuredAdLoadTimeoutMs
                        + " ms; requested "
                        + adLoadTimeoutMs
                        + " ms"
                );
            } else {
                Log.i(TAG, "Reusing process IMA playback engine");
            }
            return;
        }

        SystemUidStorageCompat.prepareGoogleWebView("IMA");
        googleSdkContext = SystemUidStorageCompat.resolveGoogleSdkContext(context);
        configuredAdLoadTimeoutMs = adLoadTimeoutMs;

        playerView = new PlayerView(context);
        playerView.setLayoutParams(new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ));
        playerView.setUseController(false);
        playerView.setControllerHideDuringAds(true);
        playerView.setKeepContentOnPlayerReset(false);
        playerView.setShutterBackgroundColor(Color.BLACK);

        adsLoader = new ImaAdsLoader.Builder(googleSdkContext)
            .setMediaLoadTimeoutMs(adLoadTimeoutMs)
            .setAdEventListener(this::handleAdEvent)
            .setAdErrorListener(error -> {
                Listener listener = activeListener;
                if (listener != null) {
                    listener.onAdError(
                        error == null ? "Unknown ad playback error" : error.toString()
                    );
                }
            })
            .build();

        DefaultMediaSourceFactory mediaSourceFactory =
            new DefaultMediaSourceFactory(new DefaultDataSource.Factory(googleSdkContext))
                .setAdsLoaderProvider(adsConfiguration -> adsLoader)
                .setAdViewProvider(playerView);

        player = new ExoPlayer.Builder(googleSdkContext)
            .setMediaSourceFactory(mediaSourceFactory)
            .build();
        player.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int playbackState) {
                Listener listener = activeListener;
                if (listener != null && playbackState == Player.STATE_ENDED) {
                    listener.onPlaybackEnded();
                }
            }

            @Override
            public void onRenderedFirstFrame() {
                if (activeListener == null) {
                    return;
                }
                firstFrameRendered = true;
                revealWhenReady();
            }

            @Override
            public void onPlayerError(PlaybackException error) {
                Listener listener = activeListener;
                if (listener != null) {
                    listener.onPlayerError(
                        error.getMessage() == null ? "Media3 playback failed" : error.getMessage(),
                        error
                    );
                }
            }
        });
        playerView.setPlayer(player);
        adsLoader.setPlayer(player);
        Log.i(TAG, "IMA playback engine initialized");
    }

    private void attachPlayerView() {
        detachViewFromParent(playerView);

        adRoot = new FrameLayout(context);
        adRoot.setLayoutParams(new ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ));
        adRoot.setBackgroundColor(Color.BLACK);
        adRoot.setAlpha(0f);
        adRoot.addView(playerView);
        activeContainer.addView(adRoot);
    }

    private AdsMediaSource createAdMediaSource(String adTagUrl) {
        SilenceMediaSource contentSource = new SilenceMediaSource(SILENCE_CONTENT_DURATION_US);
        DefaultMediaSourceFactory adMediaSourceFactory =
            new DefaultMediaSourceFactory(new DefaultDataSource.Factory(googleSdkContext));
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
        Listener listener = activeListener;
        if (listener == null || event == null) {
            return;
        }
        switch (event.getType()) {
            case LOADED:
                listener.onLoaded();
                break;
            case CONTENT_PAUSE_REQUESTED:
                listener.onContentPauseRequested();
                break;
            case STARTED:
                adStarted = true;
                listener.onStarted();
                revealWhenReady();
                break;
            case COMPLETED:
            case ALL_ADS_COMPLETED:
                listener.onCompleted();
                break;
            case SKIPPED:
                listener.onSkipped();
                break;
            case CONTENT_RESUME_REQUESTED:
                listener.onContentResumeRequested();
                break;
            default:
                break;
        }
    }

    private void revealWhenReady() {
        if (adStarted && firstFrameRendered && adRoot != null && !hiddenMode) {
            adRoot.animate().cancel();
            adRoot.animate().alpha(1f).setDuration(150L).start();
        }
    }

    private void stopCurrentMedia() {
        if (player == null) {
            return;
        }
        player.setPlayWhenReady(false);
        player.stop();
        player.clearMediaItems();
    }

    private void detachPlayerView() {
        if (adRoot != null) {
            adRoot.animate().cancel();
            detachViewFromParent(adRoot);
            adRoot.removeAllViews();
            adRoot = null;
        } else {
            detachViewFromParent(playerView);
        }
    }

    private void detachViewFromParent(View view) {
        if (view == null) {
            return;
        }
        ViewParent parent = view.getParent();
        if (parent instanceof ViewGroup) {
            ((ViewGroup) parent).removeView(view);
        }
    }
}
