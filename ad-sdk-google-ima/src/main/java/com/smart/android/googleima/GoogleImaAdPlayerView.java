package com.smart.android.googleima;

import android.content.Context;
import android.graphics.Color;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.widget.FrameLayout;

import androidx.annotation.Nullable;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.Util;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.datasource.HttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.ExoPlaybackException;
import androidx.media3.exoplayer.ima.ImaAdsLoader;
import androidx.media3.exoplayer.mediacodec.MediaCodecRenderer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.exoplayer.source.SilenceMediaSource;
import androidx.media3.exoplayer.source.ads.AdsMediaSource;
import androidx.media3.ui.PlayerView;

import com.google.ads.interactivemedia.v3.api.AdEvent;
import com.google.ads.interactivemedia.v3.api.Ad;
import com.google.ads.interactivemedia.v3.api.AdError;
import com.google.ads.interactivemedia.v3.api.AdPodInfo;
import com.google.ads.interactivemedia.v3.api.AdProgressInfo;
import com.google.ads.interactivemedia.v3.api.UniversalAdId;
import com.smart.android.googleima.internal.GoogleImaRuntimeBridge;
import com.smart.android.googleima.logging.PropertyLog;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.StringJoiner;

public final class GoogleImaAdPlayerView extends FrameLayout {
    private static final String TAG = "GoogleImaAdPlayer";
    private static final long SILENCE_CONTENT_DURATION_US = 60_000_000L;
    private static final long PROGRESS_INTERVAL_MS = 20_000L;
    private static final long AD_BREAK_COMPLETION_GRACE_MS = 5_000L;
    private static final long AD_POD_TRANSITION_TIMEOUT_MS = 30_000L;
    private static final long PLAYER_ENDED_GRACE_MS = 1_000L;
    private static final long MEDIA_START_FALLBACK_GRACE_MS = 2_000L;
    private static final long MEDIA_START_PROBE_INTERVAL_MS = 200L;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final PlayerView playerView;

    private ExoPlayer player;
    private ImaAdsLoader adsLoader;
    private Runnable startupTimeout;
    private Runnable progressReporter;
    private Runnable adBreakCompletionTimeout;
    private Runnable adPodTransitionTimeout;
    private Runnable playerEndedTimeout;
    private Runnable mediaStartFallback;
    private Runnable mediaStartProbePoll;
    private GoogleImaAdListener listener = new GoogleImaAdListener() {};
    private final GoogleImaPlaybackStartGate playbackStartGate =
            new GoogleImaPlaybackStartGate();
    private final GoogleImaMediaStartProbe mediaStartProbe =
            new GoogleImaMediaStartProbe();
    private final GoogleImaAdInstanceTracker adInstanceTracker =
            new GoogleImaAdInstanceTracker();
    private GoogleImaAdInfo currentAdInfo;
    private boolean terminal;
    private boolean loaded;
    private boolean started;
    private boolean currentAdStarted;
    private boolean currentAdCompleted;
    private boolean adBreakHadSkippedAd;
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
        currentAdStarted = false;
        currentAdCompleted = false;
        adBreakHadSkippedAd = false;
        firstFrameRendered = false;
        firstFrameDelivered = false;
        playbackStartGate.reset();
        adInstanceTracker.reset();
        currentAdInfo = null;

        GoogleImaRuntimeBridge.beginAudit(this.listener, request.getAdTagUrl());
        runtimeAuditActive = true;
        initializePlayer(request);
        player.setVolume(request.isSoundEnabled() ? 1f : 0f);
        player.setMediaSource(createAdMediaSource(request));
        player.setPlayWhenReady(true);
        player.prepare();

        startupTimeout = () -> {
            if (!started) {
                if (playbackStartGate.hasMediaPlaybackEvidence()) {
                    applyStartDecision(
                            playbackStartGate.onMediaFallbackDeadline(),
                            "startup_timeout",
                            currentAdInfo);
                } else {
                    failOnce(GoogleImaPlaybackError.timeout("ad_startup_timeout"));
                }
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
                    GoogleImaAdInfo adInfo = resolveAdInfo(event, true);
                    selectCurrentAd(adInfo);
                    emitAdEventAudit(event, adInfo, "current");
                    notifyLoadedOnce();
                    armMediaStartProbe(adInfo);
                } else if (type == AdEvent.AdEventType.STARTED) {
                    GoogleImaAdInfo adInfo = resolveAdInfo(event, false);
                    if (adInstanceTracker.isTerminal(adInfo.getInstanceId())) {
                        emitAdEventAudit(event, adInfo, "late_after_terminal");
                        return;
                    }
                    if (!adInstanceTracker.isCurrent(adInfo.getInstanceId())) {
                        selectCurrentAd(adInfo);
                    }
                    notifyLoadedOnce();
                    applyStartDecision(playbackStartGate.onImaStarted(), "ima_started", adInfo);
                    emitAdEventAudit(event, adInfo, "current");
                } else if (type == AdEvent.AdEventType.CONTENT_RESUME_REQUESTED) {
                    emitAdEventAudit(event, currentAdInfo, "ad_break");
                    emitPlayerSnapshot("player_state", "ima_" + type.name());
                    if (started) finishAdBreak(type.name());
                    else failOnce(GoogleImaPlaybackError.internal("content_resumed_before_ad_start"));
                } else if (type == AdEvent.AdEventType.COMPLETED) {
                    GoogleImaAdInfo adInfo = resolveAdInfo(event, false);
                    if (adInstanceTracker.isTerminal(adInfo.getInstanceId())
                            || !adInstanceTracker.isCurrent(adInfo.getInstanceId())) {
                        emitAdEventAudit(event, adInfo, "late_or_non_current_terminal");
                        return;
                    }
                    ensureMediaFallbackForTerminal(adInfo);
                    emitAdEventAudit(event, adInfo, "current");
                    emitPlayerSnapshot("player_state", "ima_" + type.name());
                    if (currentAdStarted) handleCurrentAdTerminal(event, adInfo, type.name());
                    else failOnce(GoogleImaPlaybackError.internal("ad_completed_before_start"));
                } else if (type == AdEvent.AdEventType.ALL_ADS_COMPLETED
                    || type == AdEvent.AdEventType.AD_BREAK_ENDED) {
                    emitAdEventAudit(event, currentAdInfo, "ad_break");
                    emitPlayerSnapshot("player_state", "ima_" + type.name());
                    if (started) finishAdBreak(type.name());
                    else failOnce(GoogleImaPlaybackError.internal("ad_break_completed_before_start"));
                } else if (type == AdEvent.AdEventType.SKIPPED) {
                    GoogleImaAdInfo adInfo = resolveAdInfo(event, false);
                    if (adInstanceTracker.isTerminal(adInfo.getInstanceId())
                            || !adInstanceTracker.isCurrent(adInfo.getInstanceId())) {
                        emitAdEventAudit(event, adInfo, "late_or_non_current_terminal");
                        return;
                    }
                    ensureMediaFallbackForTerminal(adInfo);
                    emitAdEventAudit(event, adInfo, "current");
                    emitPlayerSnapshot("player_state", "ima_" + type.name());
                    if (currentAdStarted) handleCurrentAdTerminal(event, adInfo, type.name());
                    else failOnce(GoogleImaPlaybackError.internal("ad_skipped_before_start"));
                } else {
                    emitAdEventAudit(event, resolveAdInfo(event, false), "current");
                }
            })
            .setAdErrorListener(event -> failOnce(buildImaError(event == null ? null : event.getError())))
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
                emitPlayerSnapshot("player_state", "state_changed");
                if (!terminal && playbackState == Player.STATE_ENDED) {
                    ensureMediaFallbackForTerminal(currentAdInfo);
                    if (!started) {
                        failOnce(GoogleImaPlaybackError.internal("playback_ended_before_ad_start"));
                    } else {
                        schedulePlayerEndedFallback();
                    }
                }
            }

            @Override
            public void onIsPlayingChanged(boolean isPlaying) {
                emitPlayerSnapshot("player_state", "is_playing_changed");
            }

            @Override
            public void onRenderedFirstFrame() {
                firstFrameRendered = true;
                emitPlayerSnapshot("first_frame", "rendered");
                if (player != null && player.isPlayingAd() && currentAdInfo != null) {
                    observeMediaPlayback("first_frame", currentAdInfo);
                }
                notifyFirstFrameOnce();
            }

            @Override
            public void onPlayerError(PlaybackException error) {
                failOnce(buildPlayerError(error));
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
        if (terminal || firstFrameDelivered || !firstFrameRendered || currentAdInfo == null
                || !adInstanceTracker.isCurrent(currentAdInfo.getInstanceId())
                || adInstanceTracker.isTerminal(currentAdInfo.getInstanceId())) {
            return;
        }
        firstFrameDelivered = true;
        listener.onAdFirstFrameRendered(currentAdInfo.getInstanceId());
    }

    private void observeMediaPlayback(String trigger, GoogleImaAdInfo adInfo) {
        if (adInfo == null
                || !adInstanceTracker.isCurrent(adInfo.getInstanceId())
                || adInstanceTracker.isTerminal(adInfo.getInstanceId())) {
            return;
        }
        GoogleImaPlaybackStartGate.Decision decision =
                playbackStartGate.onMediaPlaybackObserved();
        if (decision == GoogleImaPlaybackStartGate.Decision.IGNORE) return;
        clearMediaStartProbe();
        clearStartupTimeout();
        clearAdPodTransitionTimeout();
        listener.onAdMediaStartEvidence(trigger, adInfo.getInstanceId());
        if (mediaStartFallback != null) return;
        mediaStartFallback = () -> {
            mediaStartFallback = null;
            applyStartDecision(
                    playbackStartGate.onMediaFallbackDeadline(),
                    "media_" + trigger,
                    adInfo);
        };
        mainHandler.postDelayed(mediaStartFallback, MEDIA_START_FALLBACK_GRACE_MS);
    }

    private void armMediaStartProbe(GoogleImaAdInfo adInfo) {
        clearMediaStartProbe();
        if (terminal || player == null || currentAdStarted || adInfo == null) return;
        String expectedInstanceId = adInfo.getInstanceId();
        mediaStartProbe.arm(player.getCurrentPosition());
        mediaStartProbePoll = new Runnable() {
            @Override
            public void run() {
                if (terminal || player == null || currentAdStarted
                        || playbackStartGate.hasStarted()
                        || currentAdInfo == null
                        || !expectedInstanceId.equals(currentAdInfo.getInstanceId())) {
                    clearMediaStartProbe();
                    return;
                }
                if (mediaStartProbe.observe(
                        player.isPlayingAd(),
                        player.isPlaying(),
                        player.getCurrentPosition())) {
                    observeMediaPlayback("position_probe", adInfo);
                    return;
                }
                mainHandler.postDelayed(this, MEDIA_START_PROBE_INTERVAL_MS);
            }
        };
        mainHandler.post(mediaStartProbePoll);
    }

    private void ensureMediaFallbackForTerminal(GoogleImaAdInfo adInfo) {
        applyStartDecision(
                playbackStartGate.onTerminalBeforeImaStart(),
                "terminal_before_ima_started",
                adInfo);
    }

    private void applyStartDecision(
            GoogleImaPlaybackStartGate.Decision decision,
            String trigger,
            GoogleImaAdInfo adInfo) {
        if (decision != GoogleImaPlaybackStartGate.Decision.START_IMA
                && decision != GoogleImaPlaybackStartGate.Decision.START_MEDIA_FALLBACK) {
            return;
        }
        if (adInfo == null
                || !adInstanceTracker.isCurrent(adInfo.getInstanceId())
                || adInstanceTracker.isTerminal(adInfo.getInstanceId())) {
            return;
        }
        started = true;
        currentAdStarted = true;
        currentAdCompleted = false;
        clearAdBreakTimers();
        clearStartupTimeout();
        clearMediaStartFallback();
        clearMediaStartProbe();
        String source = decision == GoogleImaPlaybackStartGate.Decision.START_IMA
                ? "ima"
                : "media_fallback";
        PropertyLog.i(TAG, "ad start source=" + source + ", trigger=" + trigger);
        emitEffectiveAdStartAudit(source, trigger, adInfo);
        listener.onAdStarted(source, adInfo);
        notifyFirstFrameOnce();
        startProgressReporter();
    }

    private void handleCurrentAdTerminal(
            AdEvent event,
            GoogleImaAdInfo adInfo,
            String terminalEvent) {
        currentAdStarted = false;
        currentAdCompleted = true;
        firstFrameRendered = false;
        firstFrameDelivered = false;
        adInstanceTracker.markTerminal(adInfo.getInstanceId());
        clearMediaStartFallback();
        clearMediaStartProbe();
        AdPodSnapshot pod = AdPodSnapshot.from(event == null ? null : event.getAd());
        boolean skipped = "SKIPPED".equals(terminalEvent);
        if (skipped) adBreakHadSkippedAd = true;
        switch (GoogleImaAdPodPolicy.onCurrentAdTerminal(
                pod.totalAds,
                pod.adPosition,
                skipped)) {
            case COMPLETE_SINGLE:
                completeOnce(terminalEvent);
                break;
            case SKIP_SINGLE:
                skipOnce(terminalEvent);
                break;
            case WAIT_FOR_NEXT_AD:
                scheduleAdPodTransitionTimeout("next_ad_not_started");
                break;
            case WAIT_FOR_AD_BREAK_END:
                scheduleAdBreakCompletionFallback("last_ad_completed");
                break;
            case WAIT_FOR_METADATA:
            default:
                scheduleAdBreakCompletionFallback("unknown_ad_pod");
                break;
        }
    }

    private void scheduleAdBreakCompletionFallback(String reason) {
        clearAdBreakCompletionTimeout();
        adBreakCompletionTimeout = () -> {
            adBreakCompletionTimeout = null;
            if (terminal) return;
            if ("last_ad_completed".equals(reason)) {
                finishAdBreak("LAST_AD_COMPLETED_FALLBACK");
                return;
            }
            if (player == null || !player.isPlayingAd()
                || player.getPlaybackState() == Player.STATE_ENDED) {
                finishAdBreak("COMPLETED_UNKNOWN_POD_FALLBACK");
            } else {
                scheduleAdPodTransitionTimeout("unknown_ad_pod_terminal_missing");
            }
        };
        mainHandler.postDelayed(adBreakCompletionTimeout, AD_BREAK_COMPLETION_GRACE_MS);
    }

    private void scheduleAdPodTransitionTimeout(String reason) {
        clearAdPodTransitionTimeout();
        adPodTransitionTimeout = () -> {
            adPodTransitionTimeout = null;
            if (!terminal) {
                failOnce(GoogleImaPlaybackError.timeout("ad_pod_transition_timeout:" + reason));
            }
        };
        mainHandler.postDelayed(adPodTransitionTimeout, AD_POD_TRANSITION_TIMEOUT_MS);
    }

    private void schedulePlayerEndedFallback() {
        clearPlayerEndedTimeout();
        playerEndedTimeout = () -> {
            playerEndedTimeout = null;
            if (terminal) return;
            if (currentAdCompleted) finishAdBreak("PLAYER_ENDED_FALLBACK");
            else failOnce(GoogleImaPlaybackError.internal("player_ended_before_ad_complete"));
        };
        mainHandler.postDelayed(playerEndedTimeout, PLAYER_ENDED_GRACE_MS);
    }

    private void startProgressReporter() {
        emitPlayerSnapshot("playback_progress", "segment_started");
        if (progressReporter != null) return;
        progressReporter = new Runnable() {
            @Override
            public void run() {
                if (terminal || player == null) {
                    progressReporter = null;
                    return;
                }
                emitPlayerSnapshot("playback_progress", "periodic");
                mainHandler.postDelayed(this, PROGRESS_INTERVAL_MS);
            }
        };
        mainHandler.postDelayed(progressReporter, PROGRESS_INTERVAL_MS);
    }

    private void emitAdEventAudit(
            AdEvent event,
            @Nullable GoogleImaAdInfo adInfo,
            String disposition) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("ima_event", event.getType().name());
        fields.put("event_disposition", safe(disposition));
        fields.put("ad_instance_id", adInfo == null ? "" : adInfo.getInstanceId());
        fields.put("ad_duration_ms", Long.toString(
                adInfo == null ? -1L : adInfo.getDurationMs()));
        fields.put("pod_max_duration_ms", Long.toString(
                adInfo == null ? -1L : adInfo.getPodMaxDurationMs()));
        fields.put("total_ads", Integer.toString(
                adInfo == null ? -1 : adInfo.getTotalAds()));
        fields.put("ad_position", Integer.toString(
                adInfo == null ? -1 : adInfo.getAdPosition()));
        fields.put("pod_index", Integer.toString(
                adInfo == null ? -1 : adInfo.getPodIndex()));
        putAdIdentity(fields, event.getAd());
        Map<String, String> adData = event.getAdData();
        if (adData != null) {
            for (Map.Entry<String, String> item : adData.entrySet()) {
                fields.put("ad_data." + item.getKey(), safe(item.getValue()));
            }
        }
        if (event.getType() == AdEvent.AdEventType.AD_PROGRESS
                && adsLoader != null
                && adInfo != null
                && adInstanceTracker.isCurrent(adInfo.getInstanceId())
                && !adInstanceTracker.isTerminal(adInfo.getInstanceId())) {
            AdProgressInfo progress = adsLoader.getControlledAdProgressInfo();
            if (progress != null) {
                long currentTimeMs = AdPodSnapshot.secondsToMs(progress.getCurrentTime());
                long durationMs = AdPodSnapshot.secondsToMs(progress.getDuration());
                long adBreakDurationMs = AdPodSnapshot.secondsToMs(
                        progress.getAdBreakDuration());
                long adPeriodDurationMs = AdPodSnapshot.secondsToMs(
                        progress.getAdPeriodDuration());
                fields.put("ima_progress_current_time_ms", Long.toString(currentTimeMs));
                fields.put("ima_progress_duration_ms", Long.toString(durationMs));
                fields.put("ima_progress_ad_break_duration_ms",
                        Long.toString(adBreakDurationMs));
                fields.put("ima_progress_ad_period_duration_ms",
                        Long.toString(adPeriodDurationMs));
                fields.put("ima_progress_ad_position",
                        Integer.toString(progress.getAdPosition()));
                fields.put("ima_progress_total_ads",
                        Integer.toString(progress.getTotalAds()));
                if (durationMs > 0L) fields.put("ad_duration_ms", Long.toString(durationMs));
                if (progress.getAdPosition() > 0) {
                    fields.put("ad_position", Integer.toString(progress.getAdPosition()));
                }
                if (progress.getTotalAds() > 0) {
                    fields.put("total_ads", Integer.toString(progress.getTotalAds()));
                }
            }
        }
        listener.onImaAuditEvent(new GoogleImaAuditEvent(
            "ima_ad_event",
            SystemClock.elapsedRealtime(),
            fields,
            null
        ));
    }

    private void emitEffectiveAdStartAudit(
            String source,
            String trigger,
            GoogleImaAdInfo adInfo) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("start_source", safe(source));
        fields.put("start_trigger", safe(trigger));
        fields.put("ad_instance_id", adInfo.getInstanceId());
        fields.put("ima_ad_id", adInfo.getAdId());
        fields.put("creative_id", adInfo.getCreativeId());
        fields.put("creative_ad_id", adInfo.getCreativeAdId());
        fields.put("universal_ad_id_registry", adInfo.getUniversalAdIdRegistry());
        fields.put("universal_ad_id_value", adInfo.getUniversalAdIdValue());
        fields.put("ad_duration_ms", Long.toString(adInfo.getDurationMs()));
        fields.put("pod_max_duration_ms", Long.toString(adInfo.getPodMaxDurationMs()));
        fields.put("total_ads", Integer.toString(adInfo.getTotalAds()));
        fields.put("ad_position", Integer.toString(adInfo.getAdPosition()));
        fields.put("pod_index", Integer.toString(adInfo.getPodIndex()));
        listener.onImaAuditEvent(new GoogleImaAuditEvent(
                "effective_ad_start",
                SystemClock.elapsedRealtime(),
                fields,
                null
        ));
    }

    private void emitPlayerSnapshot(String eventType, String trigger) {
        if (player == null) return;
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("trigger", trigger);
        fields.put("ad_instance_id", currentAdInfo == null
                ? ""
                : currentAdInfo.getInstanceId());
        fields.put("playback_state", Integer.toString(player.getPlaybackState()));
        fields.put("is_playing", Boolean.toString(player.isPlaying()));
        fields.put("is_playing_ad", Boolean.toString(player.isPlayingAd()));
        fields.put("play_when_ready", Boolean.toString(player.getPlayWhenReady()));
        fields.put("physical_player_volume", Float.toString(player.getVolume()));
        boolean reportedVolumeObserved = GoogleImaRuntimeBridge.hasObservedImaCoreVolume();
        fields.put("reported_player_volume_observed", Boolean.toString(reportedVolumeObserved));
        if (reportedVolumeObserved) {
            fields.put("reported_player_volume", Float.toString(
                    GoogleImaRuntimeBridge.getObservedImaCoreReportedVolume()));
            fields.put("reported_muted", "false");
        }
        fields.put("playback_suppression_reason",
                Integer.toString(player.getPlaybackSuppressionReason()));
        fields.put("current_ad_group_index", Integer.toString(player.getCurrentAdGroupIndex()));
        fields.put("current_ad_index_in_group",
                Integer.toString(player.getCurrentAdIndexInAdGroup()));
        fields.put("position_ms", Long.toString(player.getCurrentPosition()));
        fields.put("duration_ms", Long.toString(player.getDuration()));
        fields.put("buffered_position_ms", Long.toString(player.getBufferedPosition()));
        listener.onImaAuditEvent(new GoogleImaAuditEvent(
            eventType,
            SystemClock.elapsedRealtime(),
            fields,
            null
        ));
    }

    private void completeOnce(String event) {
        if (terminal) return;
        terminal = true;
        clearTimers();
        listener.onAdCompleted(event);
    }

    private void skipOnce(String event) {
        if (terminal) return;
        terminal = true;
        clearTimers();
        listener.onAdSkipped(event);
    }

    private void finishAdBreak(String event) {
        if (adBreakHadSkippedAd) skipOnce(event);
        else completeOnce(event);
    }

    public void capturePlayerSnapshot(String trigger) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            post(() -> capturePlayerSnapshot(trigger));
            return;
        }
        emitPlayerSnapshot("terminal_snapshot", safe(trigger));
    }

    private void failOnce(GoogleImaPlaybackError error) {
        if (terminal) return;
        terminal = true;
        clearTimers();
        GoogleImaPlaybackError actual = error == null
                ? GoogleImaPlaybackError.internal("unknown_playback_error")
                : error;
        Map<String, String> fields = new LinkedHashMap<>(actual.getDetails());
        fields.put("category", actual.getCategory());
        fields.put("reason", actual.getReason());
        fields.put("message", actual.getMessage());
        listener.onImaAuditEvent(new GoogleImaAuditEvent(
            "playback_error",
            SystemClock.elapsedRealtime(),
            fields,
            null
        ));
        listener.onAdError(actual);
    }

    static GoogleImaPlaybackError buildImaError(@Nullable AdError error) {
        if (error == null) {
            return new GoogleImaPlaybackError(
                    GoogleImaPlaybackError.CATEGORY_IMA,
                    "unknown_ima_error",
                    "unknown_ima_error",
                    null);
        }
        Map<String, String> details = new LinkedHashMap<>();
        details.put("ima_error_type", String.valueOf(error.getErrorType()));
        details.put("ima_error_code", String.valueOf(error.getErrorCode()));
        details.put("ima_error_code_number", Integer.toString(error.getErrorCodeNumber()));
        details.put("cause_chain", causeChain(error));
        details.put("error_source", "ima");
        AdError.AdErrorCode errorCode = error.getErrorCode();
        boolean timedOut = errorCode == AdError.AdErrorCode.VAST_LOAD_TIMEOUT
                || errorCode == AdError.AdErrorCode.VAST_MEDIA_LOAD_TIMEOUT;
        String reason;
        if (errorCode == AdError.AdErrorCode.VAST_LOAD_TIMEOUT) {
            reason = "ima_vast_load_timeout";
        } else if (errorCode == AdError.AdErrorCode.VAST_MEDIA_LOAD_TIMEOUT) {
            reason = "ima_vast_media_load_timeout";
        } else {
            reason = "ima_sdk_error";
        }
        return new GoogleImaPlaybackError(
                timedOut
                        ? GoogleImaPlaybackError.CATEGORY_TIMEOUT
                        : GoogleImaPlaybackError.CATEGORY_IMA,
                reason,
                safe(error.getMessage()).isEmpty() ? error.toString() : error.getMessage(),
                details);
    }

    private static GoogleImaPlaybackError buildPlayerError(PlaybackException error) {
        if (error == null) return GoogleImaPlaybackError.internal("unknown_player_error");
        Map<String, String> details = new LinkedHashMap<>();
        details.put("playback_error_code", Integer.toString(error.errorCode));
        details.put("playback_error_code_name", error.getErrorCodeName());
        details.put("cause_chain", causeChain(error));
        Throwable http = findCause(error, HttpDataSource.InvalidResponseCodeException.class);
        if (http instanceof HttpDataSource.InvalidResponseCodeException) {
            HttpDataSource.InvalidResponseCodeException responseError =
                    (HttpDataSource.InvalidResponseCodeException) http;
            details.put("http_status", Integer.toString(responseError.responseCode));
            details.put("http_response_message", safe(responseError.responseMessage));
        }
        Throwable decoder = findCause(
                error,
                MediaCodecRenderer.DecoderInitializationException.class);
        if (decoder instanceof MediaCodecRenderer.DecoderInitializationException) {
            MediaCodecRenderer.DecoderInitializationException decoderError =
                    (MediaCodecRenderer.DecoderInitializationException) decoder;
            details.put("decoder_mime_type", safe(decoderError.mimeType));
            details.put("decoder_diagnostic_info", safe(decoderError.diagnosticInfo));
            details.put("decoder_name", decoderError.codecInfo == null
                    ? ""
                    : safe(decoderError.codecInfo.name));
        }
        if (error instanceof ExoPlaybackException) {
            ExoPlaybackException exoError = (ExoPlaybackException) error;
            details.put("exo_error_type", Integer.toString(exoError.type));
            details.put("renderer_name", safe(exoError.rendererName));
            if (exoError.rendererFormat != null) {
                details.put("renderer_sample_mime_type",
                        safe(exoError.rendererFormat.sampleMimeType));
                details.put("renderer_codecs", safe(exoError.rendererFormat.codecs));
            }
        }
        String reason = error.errorCode == PlaybackException.ERROR_CODE_TIMEOUT
                || error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT
                ? "player_timeout"
                : "player_error";
        String category = reason.equals("player_timeout")
                ? GoogleImaPlaybackError.CATEGORY_TIMEOUT
                : GoogleImaPlaybackError.CATEGORY_PLAYER;
        return new GoogleImaPlaybackError(
                category,
                reason,
                safe(error.getMessage()).isEmpty() ? error.getErrorCodeName() : error.getMessage(),
                details);
    }

    private static void putAdIdentity(Map<String, String> fields, @Nullable Ad ad) {
        if (ad == null) return;
        fields.put("ima_ad_id", safe(ad.getAdId()));
        fields.put("creative_id", safe(ad.getCreativeId()));
        fields.put("creative_ad_id", safe(ad.getCreativeAdId()));
        fields.put("universal_ad_id_registry", safe(ad.getUniversalAdIdRegistry()));
        fields.put("universal_ad_id_value", safe(ad.getUniversalAdIdValue()));
        UniversalAdId[] universalIds = ad.getUniversalAdIds();
        if (universalIds == null || universalIds.length == 0) return;
        StringJoiner values = new StringJoiner("|");
        for (UniversalAdId id : universalIds) {
            if (id != null) {
                values.add(safe(id.getAdIdRegistry()) + ":" + safe(id.getAdIdValue()));
            }
        }
        fields.put("universal_ad_ids", values.toString());
    }

    private GoogleImaAdInfo resolveAdInfo(AdEvent event, boolean loaded) {
        Ad ad = event == null ? null : event.getAd();
        if (ad == null) return currentAdInfo;
        String instanceId = loaded
                ? adInstanceTracker.onLoaded(ad, adFingerprint(ad))
                : adInstanceTracker.resolve(ad, adFingerprint(ad));
        return buildAdInfo(instanceId, ad);
    }

    private void selectCurrentAd(GoogleImaAdInfo adInfo) {
        if (adInfo == null) return;
        if (currentAdInfo != null
                && currentAdInfo.getInstanceId().equals(adInfo.getInstanceId())) {
            currentAdInfo = adInfo;
            return;
        }
        boolean awaitingNextAdStart = GoogleImaAdPodPolicy.shouldArmNextAdStartTimeout(
                currentAdCompleted,
                adPodTransitionTimeout != null,
                adBreakCompletionTimeout != null);
        currentAdInfo = adInfo;
        adInstanceTracker.select(adInfo.getInstanceId());
        currentAdStarted = false;
        currentAdCompleted = false;
        firstFrameRendered = false;
        firstFrameDelivered = false;
        playbackStartGate.reset();
        clearMediaStartFallback();
        clearMediaStartProbe();
        clearAdBreakCompletionTimeout();
        if (awaitingNextAdStart) {
            scheduleAdPodTransitionTimeout(
                    "loaded_ad_not_started:" + adInfo.getInstanceId());
        }
    }

    private static GoogleImaAdInfo buildAdInfo(String instanceId, Ad ad) {
        AdPodSnapshot pod = AdPodSnapshot.from(ad);
        return new GoogleImaAdInfo(
                instanceId,
                ad == null ? "" : ad.getAdId(),
                ad == null ? "" : ad.getCreativeId(),
                ad == null ? "" : ad.getCreativeAdId(),
                ad == null ? "" : ad.getUniversalAdIdRegistry(),
                ad == null ? "" : ad.getUniversalAdIdValue(),
                pod.adDurationMs,
                pod.podMaxDurationMs,
                pod.totalAds,
                pod.adPosition,
                pod.podIndex);
    }

    static String adFingerprint(Ad ad) {
        if (ad == null) return "";
        AdPodSnapshot pod = AdPodSnapshot.from(ad);
        return safe(ad.getAdId()) + "|"
                + safe(ad.getCreativeId()) + "|"
                + safe(ad.getCreativeAdId()) + "|"
                + safe(ad.getUniversalAdIdRegistry()) + "|"
                + safe(ad.getUniversalAdIdValue()) + "|"
                + pod.podIndex + "|"
                + pod.adPosition;
    }

    @Nullable
    private static Throwable findCause(Throwable error, Class<? extends Throwable> type) {
        Throwable current = error;
        for (int depth = 0; current != null && depth < 12; depth++) {
            if (type.isInstance(current)) return current;
            current = current.getCause();
        }
        return null;
    }

    private static String causeChain(Throwable error) {
        StringJoiner result = new StringJoiner(" <- ");
        Throwable current = error;
        for (int depth = 0; current != null && depth < 12; depth++) {
            String message = safe(current.getMessage());
            result.add(current.getClass().getName() + (message.isEmpty() ? "" : ": " + message));
            current = current.getCause();
        }
        return result.toString();
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private void releasePlayerOnly() {
        clearTimers();
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

    private void clearTimers() {
        clearStartupTimeout();
        clearMediaStartFallback();
        clearMediaStartProbe();
        clearProgressReporter();
        clearAdBreakTimers();
        clearPlayerEndedTimeout();
    }

    private void clearMediaStartFallback() {
        if (mediaStartFallback != null) {
            mainHandler.removeCallbacks(mediaStartFallback);
            mediaStartFallback = null;
        }
    }

    private void clearMediaStartProbe() {
        if (mediaStartProbePoll != null) {
            mainHandler.removeCallbacks(mediaStartProbePoll);
            mediaStartProbePoll = null;
        }
        mediaStartProbe.reset();
    }

    private void clearProgressReporter() {
        if (progressReporter != null) {
            mainHandler.removeCallbacks(progressReporter);
            progressReporter = null;
        }
    }

    private void clearAdBreakTimers() {
        clearAdBreakCompletionTimeout();
        clearAdPodTransitionTimeout();
    }

    private void clearAdBreakCompletionTimeout() {
        if (adBreakCompletionTimeout != null) {
            mainHandler.removeCallbacks(adBreakCompletionTimeout);
            adBreakCompletionTimeout = null;
        }
    }

    private void clearAdPodTransitionTimeout() {
        if (adPodTransitionTimeout != null) {
            mainHandler.removeCallbacks(adPodTransitionTimeout);
            adPodTransitionTimeout = null;
        }
    }

    private void clearPlayerEndedTimeout() {
        if (playerEndedTimeout != null) {
            mainHandler.removeCallbacks(playerEndedTimeout);
            playerEndedTimeout = null;
        }
    }

    private static final class AdPodSnapshot {
        final long adDurationMs;
        final long podMaxDurationMs;
        final int totalAds;
        final int adPosition;
        final int podIndex;

        private AdPodSnapshot(
                long adDurationMs,
                long podMaxDurationMs,
                int totalAds,
                int adPosition,
                int podIndex) {
            this.adDurationMs = adDurationMs;
            this.podMaxDurationMs = podMaxDurationMs;
            this.totalAds = totalAds;
            this.adPosition = adPosition;
            this.podIndex = podIndex;
        }

        static AdPodSnapshot from(@Nullable Ad ad) {
            if (ad == null) return new AdPodSnapshot(-1L, -1L, -1, -1, -1);
            AdPodInfo pod = ad.getAdPodInfo();
            return new AdPodSnapshot(
                secondsToMs(ad.getDuration()),
                pod == null ? -1L : secondsToMs(pod.getMaxDuration()),
                pod == null ? -1 : pod.getTotalAds(),
                pod == null ? -1 : pod.getAdPosition(),
                pod == null ? -1 : pod.getPodIndex()
            );
        }

        private static long secondsToMs(double seconds) {
            if (Double.isNaN(seconds) || Double.isInfinite(seconds) || seconds <= 0d) {
                return -1L;
            }
            return Math.round(seconds * 1_000d);
        }
    }
}
