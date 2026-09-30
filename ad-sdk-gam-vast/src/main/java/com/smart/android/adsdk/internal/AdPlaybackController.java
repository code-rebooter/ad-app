package com.smart.android.adsdk.internal;

import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import com.smart.android.adsdk.gamvast.logging.PropertyLog;
import android.view.Gravity;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
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
    private static final String TAG = "GamVastPlayer";
    private static final long PROGRESS_POLL_MS = 250L;
    private static final long PLAYER_RELEASE_TIMEOUT_MS = 500L;
    private static final long SURFACE_DETACH_TIMEOUT_MS = 500L;

    private final Context context;
    private final ViewGroup container;
    private final Listener listener;
    private final OkHttpClient okHttpClient;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final PlaybackEventGate eventGate = new PlaybackEventGate();
    private final String controllerId = Integer.toHexString(System.identityHashCode(this));

    private FrameLayout adRoot;
    private PlayerView playerView;
    private ExoPlayer player;
    private VastClient vastClient;
    private VastTracker tracker;
    private VastAdBreak vastAdBreak;
    private VastAd vastAd;
    private Cancellable vastCall;
    private long startupTimeoutMs;
    private Runnable startupTimeoutAction;
    private Runnable progressAction;
    private final Set<VastProgressTracker> reportedProgressTrackers = new HashSet<>();
    private final Set<Integer> failedMediaIndexes = new HashSet<>();
    private Button skipButton;
    private boolean firstQuartileReported;
    private boolean midpointReported;
    private boolean thirdQuartileReported;
    private boolean completeReported;
    private boolean currentAdStartedReported;
    private boolean currentAdLoadedReported;
    private boolean breakStartReported;
    private boolean breakEndReported;
    private boolean skipButtonShown;
    private boolean soundEnabled;
    private boolean paused;
    private boolean fallbackInProgress;
    private boolean hiddenMode;
    private boolean resourcesReleased;
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
        runOnMainThread(() -> playOnMain(config, soundEnabled));
    }

    private void playOnMain(AdPlaybackConfig config, boolean soundEnabled) {
        if (resourcesReleased || eventGate.isTerminal()) {
            logEvent("playIgnored", "released=" + resourcesReleased
                + ", terminal=" + eventGate.isTerminal());
            return;
        }
        long playStartedAtMs = opStart("play.initialize");
        startupTimeoutMs = config.getAdStartupTimeoutMs();
        hiddenMode = config.isHiddenMode();
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
                public void onLoaded(VastAdBreak adBreak) {
                    mainHandler.post(() -> handleVastLoaded(adBreak));
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
        opEnd("play.initialize", playStartedAtMs);
    }

    @Override
    public void pause() {
        runOnMainThread(this::pauseOnMain);
    }

    private void pauseOnMain() {
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
        runOnMainThread(this::resumeOnMain);
    }

    private void resumeOnMain() {
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
        runOnMainThread(() -> setSoundEnabledOnMain(enabled));
    }

    private void setSoundEnabledOnMain(boolean enabled) {
        if (resourcesReleased) {
            return;
        }
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
        runOnMainThread(this::releasePlayerResources);
    }

    private void createPlayer() {
        long startedAtMs = opStart("player.create");
        try {
            playerView = new PlayerView(context);
            playerView.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            ));
            playerView.setUseController(false);
            playerView.setKeepContentOnPlayerReset(false);
            playerView.setShutterBackgroundColor(Color.BLACK);
            playerView.setOnClickListener(view -> handleAdClick());

            DefaultMediaSourceFactory mediaSourceFactory =
                new DefaultMediaSourceFactory(new DefaultDataSource.Factory(context));
            AudioAttributes adAudioAttributes = new AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .build();
            player = new ExoPlayer.Builder(context)
                .setMediaSourceFactory(mediaSourceFactory)
                .setAudioAttributes(adAudioAttributes, true)
                .setReleaseTimeoutMs(PLAYER_RELEASE_TIMEOUT_MS)
                .setDetachSurfaceTimeoutMs(SURFACE_DETACH_TIMEOUT_MS)
                .build();
            player.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int playbackState) {
                logEvent("stateChanged", "state=" + playerStateName(playbackState));
                if (eventGate.isTerminal()) {
                    return;
                }
                if (playbackState == Player.STATE_READY) {
                    reportLoadedForCurrentAd();
                    return;
                }
                if (playbackState == Player.STATE_ENDED) {
                    if (fallbackInProgress) {
                        return;
                    }
                    if (currentAdStartedReported) {
                        reportComplete();
                        playNextAdOrComplete();
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
                logEvent("isPlayingChanged", "isPlaying=" + isPlaying);
                maybeReportCurrentAdStarted(isPlaying);
            }

            @Override
            public void onRenderedFirstFrame() {
                logEvent("firstFrame", "state=" + currentPlayerState());
                eventGate.markFirstFrame();
                revealWhenReady();
            }

            @Override
            public void onPlayerError(PlaybackException error) {
                PropertyLog.e(TAG, eventPrefix("playerError")
                    + ", errorCode=" + error.errorCode
                    + ", message=" + error.getMessage(), error);
                handleMediaFailure(
                    405,
                    error.getMessage() == null ? "Media3 playback failed" : error.getMessage(),
                    error
                );
            }
            });
            long setPlayerStartedAtMs = opStart("view.setPlayer");
            playerView.setPlayer(player);
            opEnd("view.setPlayer", setPlayerStartedAtMs);
            installSurfaceDiagnostics();
        } finally {
            opEnd("player.create", startedAtMs);
        }
    }

    private void maybeReportCurrentAdStarted(boolean isPlaying) {
        if (!isPlaying || currentAdStartedReported) {
            return;
        }
        currentAdStartedReported = true;
        reportStart();
        clearStartupTimeout();
        if (eventGate.markStarted()) {
            listener.onStarted();
        }
        revealWhenReady();
        startProgressPolling();
    }

    private void handleVastLoaded(VastAdBreak adBreak) {
        if (eventGate.isTerminal() || player == null) {
            logEvent("vastLoadedIgnored", "terminal=" + eventGate.isTerminal()
                + ", hasPlayer=" + (player != null));
            return;
        }
        if (adBreak == null || adBreak.getAds().isEmpty()) {
            fail(AdErrorCode.AD_LOAD_ERROR, "VAST did not provide a media URL", null);
            return;
        }
        vastAdBreak = adBreak;
        vastAd = adBreak.firstAd();
        logEvent("vastLoaded", "adCount=" + adBreak.getAds().size());
        if (vastAd == null || vastAd.getMediaUrl() == null || vastAd.getMediaUrl().isEmpty()) {
            fail(AdErrorCode.AD_LOAD_ERROR, "VAST did not provide a media URL", null);
            return;
        }
        reportBreakStart();
        notifyLoaded();
        armStartupTimeout();
        failedMediaIndexes.clear();
        mediaIndex = -1;
        resetCurrentAdProgressState();
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
                logEvent("mediaCandidate", "candidate=" + (mediaIndex + 1)
                    + "/" + mediaFiles.size()
                    + ", mime=" + valueOrEmpty(mediaFile.getPlayerMimeType()));
                if (tracker != null) {
                    tracker.setMediaType(mediaFile.getPlayerMimeType());
                }
                MediaItem.Builder mediaItemBuilder = new MediaItem.Builder()
                    .setUri(mediaFile.getUrl());
                if (mediaFile.getPlayerMimeType() != null
                    && !mediaFile.getPlayerMimeType().isEmpty()) {
                    mediaItemBuilder.setMimeType(mediaFile.getPlayerMimeType());
                }
                long setMediaItemStartedAtMs = opStart("media.setItem");
                try {
                    player.setMediaItem(mediaItemBuilder.build());
                } finally {
                    opEnd("media.setItem", setMediaItemStartedAtMs);
                }
                long playWhenReadyStartedAtMs = opStart("media.setPlayWhenReady");
                try {
                    player.setPlayWhenReady(true);
                } finally {
                    opEnd("media.setPlayWhenReady", playWhenReadyStartedAtMs);
                }
                long prepareStartedAtMs = opStart("media.prepare");
                try {
                    player.prepare();
                } finally {
                    opEnd("media.prepare", prepareStartedAtMs);
                }
                maybeReportCurrentAdStarted(player.isPlaying());
                fallbackInProgress = false;
                return;
            } catch (RuntimeException error) {
                PropertyLog.e(TAG, eventPrefix("mediaCandidateFailed")
                    + ", candidate=" + (mediaIndex + 1)
                    + "/" + mediaFiles.size()
                    + ", mime=" + valueOrEmpty(mediaFile.getPlayerMimeType()), error);
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
        long startedAtMs = opStart("view.attachRoot");
        try {
            adRoot = new FrameLayout(context);
            adRoot.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            ));
            adRoot.setBackgroundColor(Color.BLACK);
            adRoot.setAlpha(0f);
            adRoot.addView(playerView);
            container.addView(adRoot);
        } finally {
            opEnd("view.attachRoot", startedAtMs);
        }
    }

    private void reportStart() {
        tracker.fire(vastAd.getImpressions());
        tracker.fire(vastAd.getCreativeViewTrackers());
        tracker.fire(vastAd.getStartTrackers());
        tracker.fire(vastAd.getViewUndeterminedTrackers());
        tracker.fireVerificationNotExecuted(
            vastAd.getVerificationNotExecutedTrackers(),
            "verification_not_supported"
        );
        if (vastAd.hasUnexecutedInteractiveCreativeFile()) {
            tracker.fireError(vastAd.getErrorTrackers(), 409);
        }
        if (!soundEnabled) {
            tracker.fire(vastAd.getMuteTrackers());
        }
    }

    private void reportBreakStart() {
        if (!breakStartReported && tracker != null && vastAdBreak != null) {
            breakStartReported = true;
            tracker.fire(vastAdBreak.getBreakStartTrackers());
        }
    }

    private void reportBreakEnd() {
        if (!breakEndReported && tracker != null && vastAdBreak != null) {
            breakEndReported = true;
            tracker.fire(vastAdBreak.getBreakEndTrackers());
        }
    }

    private void reportBreakError(int errorCode) {
        if (tracker != null && vastAdBreak != null) {
            tracker.fireError(vastAdBreak.getBreakErrorTrackers(), errorCode);
        }
    }

    private void reportLoadedForCurrentAd() {
        if (!currentAdLoadedReported && tracker != null && vastAd != null) {
            currentAdLoadedReported = true;
            tracker.fire(vastAd.getLoadedTrackers());
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
        tracker.setAdPlayheadMs(positionMs);
        revealSkipButtonIfNeeded(durationMs, positionMs);
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

    private void playNextAdOrComplete() {
        VastAd nextAd = nextAd();
        if (nextAd == null) {
            complete();
            return;
        }
        vastAd = nextAd;
        resetCurrentAdProgressState();
        failedMediaIndexes.clear();
        mediaIndex = -1;
        armStartupTimeout();
        prepareNextMediaFile(403, null);
    }

    private VastAd nextAd() {
        if (vastAdBreak == null || vastAd == null) {
            return null;
        }
        List<VastAd> ads = vastAdBreak.getAds();
        int currentIndex = ads.indexOf(vastAd);
        int nextIndex = currentIndex + 1;
        return currentIndex >= 0 && nextIndex < ads.size() ? ads.get(nextIndex) : null;
    }

    private void revealSkipButtonIfNeeded(long durationMs, long positionMs) {
        if (skipButtonShown || vastAd == null) {
            return;
        }
        if (vastAd.getSkipOffsetMs() >= 0L && positionMs >= vastAd.getSkipOffsetMs()) {
            showSkipButton();
            return;
        }
        if (durationMs > 0L
            && vastAd.getSkipOffsetPercent() >= 0f
            && positionMs / (float) durationMs >= vastAd.getSkipOffsetPercent()) {
            showSkipButton();
        }
    }

    private void showSkipButton() {
        if (skipButtonShown) {
            return;
        }
        skipButtonShown = true;
        ensureSkipButton();
        skipButton.setVisibility(View.VISIBLE);
    }

    private void ensureSkipButton() {
        if (skipButton != null || adRoot == null) {
            return;
        }
        skipButton = new Button(context);
        skipButton.setText("Skip");
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP | Gravity.END
        );
        int marginPx = dp(16);
        params.setMargins(marginPx, marginPx, marginPx, marginPx);
        skipButton.setLayoutParams(params);
        skipButton.setVisibility(View.GONE);
        skipButton.setOnClickListener(view -> skipAd());
        adRoot.addView(skipButton);
    }

    private void skipAd() {
        if (eventGate.markTerminal()) {
            if (tracker != null && vastAd != null) {
                if (player != null) {
                    tracker.setAdPlayheadMs(player.getCurrentPosition());
                }
                tracker.fire(vastAd.getSkipTrackers());
            }
            reportBreakEnd();
            fallbackInProgress = false;
            clearStartupTimeout();
            stopProgressPolling();
            listener.onSkipped("USER_SKIPPED");
        }
    }

    private void handleAdClick() {
        if (eventGate.isTerminal() || tracker == null || vastAd == null) {
            return;
        }
        if (player != null) {
            tracker.setAdPlayheadMs(player.getCurrentPosition());
        }
        tracker.fire(vastAd.getClickTrackingUrls());
        if (!TextUtils.isEmpty(vastAd.getClickThroughUrl())) {
            openClickThrough(vastAd.getClickThroughUrl());
        }
    }

    private void openClickThrough(String url) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        } catch (RuntimeException ignored) {
        }
    }

    private void notifyLoaded() {
        if (eventGate.markLoaded()) {
            listener.onLoaded();
        }
    }

    private void revealWhenReady() {
        if (eventGate.consumeRevealReady() && adRoot != null && !hiddenMode) {
            adRoot.animate().cancel();
            adRoot.animate().alpha(1f).setDuration(150L).start();
        }
    }

    private void complete() {
        if (eventGate.markTerminal()) {
            reportBreakEnd();
            fallbackInProgress = false;
            clearStartupTimeout();
            listener.onCompleted();
        }
    }

    private void fail(AdErrorCode code, String message, Throwable cause) {
        if (eventGate.markTerminal()) {
            reportBreakError(code == AdErrorCode.TIMEOUT ? 402 : 900);
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
        if (!currentAdStartedReported) {
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
        if (resourcesReleased) {
            return;
        }
        resourcesReleased = true;
        clearStartupTimeout();
        stopProgressPolling();
        if (vastCall != null) {
            vastCall.cancel();
            vastCall = null;
        }
        ExoPlayer playerToRelease = player;
        if (playerToRelease != null) {
            long releaseAllStartedAtMs = opStart("release.all");
            long stopStartedAtMs = opStart("player.stop");
            try {
                playerToRelease.stop();
            } catch (RuntimeException | LinkageError error) {
                PropertyLog.e(TAG, "ExoPlayer stop failed during release", error);
            } finally {
                opEnd("player.stop", stopStartedAtMs);
            }
            long releaseStartedAtMs = opStart("player.release");
            try {
                playerToRelease.release();
            } catch (RuntimeException | LinkageError error) {
                PropertyLog.e(TAG, "ExoPlayer release failed", error);
            } finally {
                opEnd("player.release", releaseStartedAtMs);
            }
            opEnd("release.all", releaseAllStartedAtMs);
        }
        player = null;
        if (playerView != null) {
            long detachStartedAtMs = opStart("view.detachPlayer");
            try {
                playerView.setPlayer(null);
            } finally {
                opEnd("view.detachPlayer", detachStartedAtMs);
            }
        }
        if (adRoot != null) {
            long removeStartedAtMs = opStart("view.removeRoot");
            try {
                adRoot.animate().cancel();
                container.removeView(adRoot);
                adRoot.removeAllViews();
                adRoot = null;
            } finally {
                opEnd("view.removeRoot", removeStartedAtMs);
            }
        }
        skipButton = null;
        playerView = null;
        vastClient = null;
        vastAdBreak = null;
        tracker = null;
        vastAd = null;
        reportedProgressTrackers.clear();
        failedMediaIndexes.clear();
        firstQuartileReported = false;
        midpointReported = false;
        thirdQuartileReported = false;
        completeReported = false;
        currentAdStartedReported = false;
        currentAdLoadedReported = false;
        breakStartReported = false;
        breakEndReported = false;
        skipButtonShown = false;
        soundEnabled = false;
        paused = false;
        fallbackInProgress = false;
        hiddenMode = false;
        mediaIndex = -1;
    }

    private void runOnMainThread(Runnable action) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action.run();
        } else {
            mainHandler.post(action);
        }
    }

    private void installSurfaceDiagnostics() {
        View videoSurfaceView = playerView == null ? null : playerView.getVideoSurfaceView();
        logEvent("surfaceView", "class="
            + (videoSurfaceView == null ? "null" : videoSurfaceView.getClass().getName()));
        if (!(videoSurfaceView instanceof SurfaceView)) {
            return;
        }
        ((SurfaceView) videoSurfaceView).getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(SurfaceHolder holder) {
                PropertyLog.i(TAG, "SURFACE_EVENT created" + diagnosticSuffix()
                    + ", valid=" + holder.getSurface().isValid());
            }

            @Override
            public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
                PropertyLog.i(TAG, "SURFACE_EVENT changed" + diagnosticSuffix()
                    + ", format=" + format
                    + ", size=" + width + "x" + height
                    + ", valid=" + holder.getSurface().isValid());
            }

            @Override
            public void surfaceDestroyed(SurfaceHolder holder) {
                PropertyLog.i(TAG, "SURFACE_EVENT destroyed" + diagnosticSuffix()
                    + ", valid=" + holder.getSurface().isValid());
            }
        });
    }

    private long opStart(String operation) {
        long startedAtMs = SystemClock.elapsedRealtime();
        PropertyLog.i(TAG, "PLAYER_OP begin" + diagnosticSuffix()
            + ", op=" + operation
            + ", state=" + currentPlayerState());
        return startedAtMs;
    }

    private void opEnd(String operation, long startedAtMs) {
        PropertyLog.i(TAG, "PLAYER_OP end" + diagnosticSuffix()
            + ", op=" + operation
            + ", elapsedMs=" + (SystemClock.elapsedRealtime() - startedAtMs)
            + ", state=" + currentPlayerState());
    }

    private void logEvent(String event, String detail) {
        PropertyLog.i(TAG, eventPrefix(event) + (TextUtils.isEmpty(detail) ? "" : ", " + detail));
    }

    private String eventPrefix(String event) {
        return "PLAYER_EVENT " + event + diagnosticSuffix();
    }

    private String diagnosticSuffix() {
        return ", controller=" + controllerId
            + ", mediaIndex=" + mediaIndex
            + ", thread=" + Thread.currentThread().getName();
    }

    private String currentPlayerState() {
        return player == null ? "NO_PLAYER" : playerStateName(player.getPlaybackState());
    }

    private String playerStateName(int state) {
        switch (state) {
            case Player.STATE_IDLE:
                return "IDLE";
            case Player.STATE_BUFFERING:
                return "BUFFERING";
            case Player.STATE_READY:
                return "READY";
            case Player.STATE_ENDED:
                return "ENDED";
            default:
                return "UNKNOWN(" + state + ")";
        }
    }

    private String valueOrEmpty(String value) {
        return value == null ? "" : value;
    }

    private int dp(int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    private void resetCurrentAdProgressState() {
        reportedProgressTrackers.clear();
        firstQuartileReported = false;
        midpointReported = false;
        thirdQuartileReported = false;
        completeReported = false;
        currentAdStartedReported = false;
        currentAdLoadedReported = false;
        skipButtonShown = false;
        if (skipButton != null) {
            skipButton.setVisibility(View.GONE);
        }
    }
}
