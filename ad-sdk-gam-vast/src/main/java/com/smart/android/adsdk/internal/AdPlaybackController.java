package com.smart.android.adsdk.internal;

import android.content.Context;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.ui.PlayerView;
import com.smart.android.adsdk.AdError;
import com.smart.android.adsdk.AdErrorCode;
import com.smart.android.adsdk.AdErrorStage;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import okhttp3.OkHttpClient;

final class AdPlaybackController implements AdPlayer {
    private static final long PROGRESS_POLL_MS = 250L;

    private final Context context;
    private final ViewGroup container;
    private final Listener listener;
    private final OkHttpClient okHttpClient;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final PlaybackEventGate eventGate = new PlaybackEventGate();

    private FrameLayout adRoot;
    private PlayerView playerView;
    private ExoPlayer player;
    private VastClient vastClient;
    private VastTracker tracker;
    private VastAd vastAd;
    private Cancellable vastCall;
    private long startupTimeoutMs;
    private Runnable startupTimeoutAction;
    private Runnable progressAction;
    private final Set<VastProgressTracker> reportedProgressTrackers = new HashSet<>();
    private final Set<Integer> failedMediaIndexes = new HashSet<>();
    private boolean firstQuartileReported;
    private boolean midpointReported;
    private boolean thirdQuartileReported;
    private boolean completeReported;
    private boolean soundEnabled;
    private boolean paused;
    private boolean fallbackInProgress;
    private int mediaIndex = -1;

    AdPlaybackController(
        Context context,
        ViewGroup container,
        Listener listener,
        OkHttpClient okHttpClient
    ) {
        this.context = context;
        this.container = container;
        this.listener = listener;
        this.okHttpClient = okHttpClient;
    }

    @Override
    public void play(AdPlaybackConfig config, boolean soundEnabled) {
        releasePlayerResources();
        startupTimeoutMs = config.getAdStartupTimeoutMs();
        tracker = new VastTracker(okHttpClient);
        vastClient = new VastClient(okHttpClient);
        createPlayer();
        attachPlayerView();
        armStartupTimeout();
        this.soundEnabled = soundEnabled;
        player.setVolume(soundEnabled ? 1f : 0f);
        vastCall = vastClient.load(
            config.getAdTagUrl(),
            config.getAdLoadTimeoutMs(),
            new VastClient.Callback() {
                @Override
                public void onLoaded(VastAd ad) {
                    mainHandler.post(() -> handleVastLoaded(ad));
                }

                @Override
                public void onError(VastLoadException error) {
                    mainHandler.post(() -> {
                        if (tracker != null) {
                            if (vastAd != null) {
                                tracker.fireError(
                                    vastAd.getErrorTrackers(),
                                    error.getVastErrorCode()
                                );
                            } else {
                                tracker.fireError(
                                    error.getErrorTrackers(),
                                    error.getVastErrorCode()
                                );
                            }
                        }
                        fail(
                            AdErrorCode.AD_LOAD_ERROR,
                            error.getMessage() == null ? "Unable to load VAST ad" : error.getMessage(),
                            error
                        );
                    });
                }
            }
        );
    }

    @Override
    public void pause() {
        if (player != null && eventGate.hasStarted() && player.isPlaying()) {
            player.pause();
            paused = true;
            if (vastAd != null && tracker != null) {
                tracker.fire(vastAd.getPauseTrackers());
            }
        }
    }

    @Override
    public void resume() {
        if (player != null && paused) {
            player.play();
            paused = false;
            if (vastAd != null && tracker != null) {
                tracker.fire(vastAd.getResumeTrackers());
            }
        }
    }

    @Override
    public void setSoundEnabled(boolean enabled) {
        if (player != null) {
            player.setVolume(enabled ? 1f : 0f);
            if (vastAd != null && tracker != null && soundEnabled != enabled) {
                tracker.fire(enabled ? vastAd.getUnmuteTrackers() : vastAd.getMuteTrackers());
            }
        }
        soundEnabled = enabled;
    }

    @Override
    public void release() {
        eventGate.markTerminal();
        releasePlayerResources();
    }

    private void createPlayer() {
        playerView = new PlayerView(context);
        playerView.setLayoutParams(new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ));
        playerView.setUseController(false);
        playerView.setKeepContentOnPlayerReset(false);
        playerView.setShutterBackgroundColor(Color.BLACK);

        DefaultMediaSourceFactory mediaSourceFactory =
            new DefaultMediaSourceFactory(new DefaultDataSource.Factory(context));
        player = new ExoPlayer.Builder(context)
            .setMediaSourceFactory(mediaSourceFactory)
            .build();
        player.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int playbackState) {
                if (eventGate.isTerminal()) {
                    return;
                }
                if (playbackState == Player.STATE_ENDED) {
                    if (fallbackInProgress) {
                        return;
                    }
                    if (eventGate.hasStarted()) {
                        reportComplete();
                        complete();
                    } else {
                        handleMediaFailure(
                            405,
                            "Ad playback ended before the ad started",
                            null
                        );
                    }
                }
            }

            @Override
            public void onIsPlayingChanged(boolean isPlaying) {
                if (isPlaying && eventGate.markStarted()) {
                    reportStart();
                    clearStartupTimeout();
                    listener.onStarted();
                    revealWhenReady();
                    startProgressPolling();
                }
            }

            @Override
            public void onRenderedFirstFrame() {
                eventGate.markFirstFrame();
                revealWhenReady();
            }

            @Override
            public void onPlayerError(PlaybackException error) {
                handleMediaFailure(
                    405,
                    error.getMessage() == null ? "Media3 playback failed" : error.getMessage(),
                    error
                );
            }
        });
        playerView.setPlayer(player);
    }

    private void handleVastLoaded(VastAd ad) {
        if (eventGate.isTerminal() || player == null) {
            return;
        }
        if (ad == null || ad.getMediaUrl() == null || ad.getMediaUrl().isEmpty()) {
            fail(AdErrorCode.AD_LOAD_ERROR, "VAST did not provide a media URL", null);
            return;
        }
        vastAd = ad;
        notifyLoaded();
        extendStartupTimeout();
        failedMediaIndexes.clear();
        mediaIndex = -1;
        prepareNextMediaFile(403, null);
    }

    private void prepareNextMediaFile(int errorCode, Throwable previousError) {
        if (vastAd == null || player == null) {
            fail(AdErrorCode.PLAYER_ERROR, "Ad player was released before media preparation", previousError);
            return;
        }
        List<VastMediaFile> mediaFiles = vastAd.getMediaFiles();
        if (mediaFiles.isEmpty()) {
            if (tracker != null) {
                tracker.fireError(vastAd.getErrorTrackers(), errorCode);
            }
            fail(
                AdErrorCode.PLAYER_ERROR,
                "VAST did not provide a playable media file",
                previousError
            );
            fallbackInProgress = false;
            return;
        }
        fallbackInProgress = true;
        while (mediaIndex + 1 < mediaFiles.size()) {
            mediaIndex += 1;
            if (failedMediaIndexes.contains(mediaIndex)) {
                continue;
            }
            VastMediaFile mediaFile = mediaFiles.get(mediaIndex);
            try {
                if (tracker != null) {
                    tracker.setMediaType(mediaFile.getPlayerMimeType());
                }
                MediaItem.Builder mediaItemBuilder = new MediaItem.Builder()
                    .setUri(mediaFile.getUrl());
                if (mediaFile.getPlayerMimeType() != null
                    && !mediaFile.getPlayerMimeType().isEmpty()) {
                    mediaItemBuilder.setMimeType(mediaFile.getPlayerMimeType());
                }
                player.setMediaItem(mediaItemBuilder.build());
                player.setPlayWhenReady(true);
                player.prepare();
                fallbackInProgress = false;
                return;
            } catch (RuntimeException error) {
                failedMediaIndexes.add(mediaIndex);
                previousError = error;
                errorCode = 403;
            }
        }
        if (tracker != null) {
            tracker.fireError(vastAd.getErrorTrackers(), errorCode);
        }
        fail(
            AdErrorCode.PLAYER_ERROR,
            previousError == null || previousError.getMessage() == null
                ? "Unable to prepare or play any VAST media file"
                : previousError.getMessage(),
            previousError
        );
        fallbackInProgress = false;
    }

    private void handleMediaFailure(int errorCode, String message, Throwable cause) {
        if (eventGate.isTerminal() || vastAd == null) {
            return;
        }
        if (failedMediaIndexes.add(mediaIndex)) {
            prepareNextMediaFile(errorCode, cause);
            return;
        }
        if (tracker != null) {
            tracker.fireError(vastAd.getErrorTrackers(), errorCode);
        }
        fail(AdErrorCode.PLAYER_ERROR, message, cause);
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

    private void reportStart() {
        tracker.fire(vastAd.getImpressions());
        tracker.fire(vastAd.getCreativeViewTrackers());
        tracker.fire(vastAd.getStartTrackers());
        if (!soundEnabled) {
            tracker.fire(vastAd.getMuteTrackers());
        }
    }

    private void startProgressPolling() {
        stopProgressPolling();
        progressAction = new Runnable() {
            @Override
            public void run() {
                reportProgressEvents();
                if (player != null && !eventGate.hasStarted()) {
                    return;
                }
                if (player != null) {
                    mainHandler.postDelayed(this, PROGRESS_POLL_MS);
                }
            }
        };
        mainHandler.post(progressAction);
    }

    private void reportProgressEvents() {
        if (player == null || vastAd == null) {
            return;
        }
        long durationMs = player.getDuration();
        long positionMs = player.getCurrentPosition();
        if (positionMs < 0L) {
            return;
        }
        reportOffsetProgressTrackers(durationMs, positionMs);
        if (durationMs > 0L) {
            float progress = Math.min(1f, positionMs / (float) durationMs);
            if (!firstQuartileReported && progress >= 0.25f) {
                firstQuartileReported = true;
                tracker.fire(vastAd.getFirstQuartileTrackers());
            }
            if (!midpointReported && progress >= 0.50f) {
                midpointReported = true;
                tracker.fire(vastAd.getMidpointTrackers());
            }
            if (!thirdQuartileReported && progress >= 0.75f) {
                thirdQuartileReported = true;
                tracker.fire(vastAd.getThirdQuartileTrackers());
            }
        }
    }

    private void reportOffsetProgressTrackers(long durationMs, long positionMs) {
        for (VastProgressTracker progressTracker : vastAd.getProgressTrackers()) {
            if (reportedProgressTrackers.contains(progressTracker)) {
                continue;
            }
            if (progressTracker.shouldFire(durationMs, positionMs)) {
                reportedProgressTrackers.add(progressTracker);
                tracker.fire(Collections.singletonList(progressTracker.getUrl()));
            }
        }
    }

    private void reportComplete() {
        if (vastAd != null && !completeReported) {
            completeReported = true;
            tracker.fire(vastAd.getCompleteTrackers());
        }
        stopProgressPolling();
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
            fallbackInProgress = false;
            clearStartupTimeout();
            listener.onCompleted();
        }
    }

    private void fail(AdErrorCode code, String message, Throwable cause) {
        if (eventGate.markTerminal()) {
            fallbackInProgress = false;
            clearStartupTimeout();
            listener.onError(new AdError(code, AdErrorStage.PLAYER, message, cause));
        }
    }

    private void armStartupTimeout() {
        clearStartupTimeout();
        startupTimeoutAction = () -> {
            if (vastAd != null && tracker != null) {
                tracker.fireError(vastAd.getErrorTrackers(), 402);
            }
            fail(
                AdErrorCode.TIMEOUT,
                "Ad playback did not start within " + startupTimeoutMs + " ms",
                null
            );
        };
        mainHandler.postDelayed(startupTimeoutAction, startupTimeoutMs);
    }

    private void extendStartupTimeout() {
        clearStartupTimeout();
        if (!eventGate.hasStarted()) {
            armStartupTimeout();
        }
    }

    private void clearStartupTimeout() {
        if (startupTimeoutAction != null) {
            mainHandler.removeCallbacks(startupTimeoutAction);
            startupTimeoutAction = null;
        }
    }

    private void stopProgressPolling() {
        if (progressAction != null) {
            mainHandler.removeCallbacks(progressAction);
            progressAction = null;
        }
    }

    private void releasePlayerResources() {
        clearStartupTimeout();
        stopProgressPolling();
        if (vastCall != null) {
            vastCall.cancel();
            vastCall = null;
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
        vastClient = null;
        tracker = null;
        vastAd = null;
        reportedProgressTrackers.clear();
        failedMediaIndexes.clear();
        firstQuartileReported = false;
        midpointReported = false;
        thirdQuartileReported = false;
        completeReported = false;
        soundEnabled = false;
        paused = false;
        fallbackInProgress = false;
        mediaIndex = -1;
    }
}
