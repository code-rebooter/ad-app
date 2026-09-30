package com.smart.android.adsdk.internal;

import android.content.Context;
import android.view.ViewGroup;
import com.smart.android.adsdk.AdError;
import com.smart.android.adsdk.AdErrorCode;
import com.smart.android.adsdk.AdErrorStage;
import com.smart.android.adsdk.AdListener;
import com.smart.android.adsdk.AdRequest;
import com.smart.android.adsdk.AdResult;
import com.smart.android.adsdk.AdSession;
import com.smart.android.adsdk.AdState;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

final class AdSessionImpl implements AdSession {
    private final Object lock = new Object();
    private final String channelId;
    private final Context context;
    private final ViewGroup container;
    private final AdListener listener;
    private final RemoteAdConfigResolver resolver;
    private final AdPlayerFactory playerFactory;
    private final FlowControlResolver flowControlResolver;
    private final ConsentResolver consentResolver;
    private final Hq008AdReporter reporter;
    private final FlowTrace flowTrace;
    private boolean hiddenMode;
    private String clientIp;
    private String phase = "FLOW_CONTROL";
    private boolean authorizationResponseReceived;
    private String authorizationOutcome;
    private long nextRequestSeconds;
    private long requestedAtMs = -1L;
    private long loadedAtMs = -1L;
    private long startedAtMs = -1L;
    private long finishedAtMs = -1L;
    private int adTagLength;
    private String adTagHash;
    private long adCallbackTimeoutMs;
    private final TimeoutScheduler timeoutScheduler;
    private final CallbackDispatcher dispatcher;

    private volatile AdState state = AdState.RESOLVING_CONFIG;
    private final long createdAtMs;
    private String requestId;
    private boolean soundEnabled;
    private boolean startRequested;
    private boolean requestedReported;
    private boolean loadedNotified;
    private boolean startedNotified;
    private boolean terminal;
    private Cancellable flowControlCall;
    private Cancellable consentCall;
    private Cancellable configCall;
    private Cancellable timeoutCall;
    private AdPlayer player;
    private long callbackTimeoutStartedAtMs;

    /**
     * Kept for existing internal tests and package-local callers.
     */
    AdSessionImpl(
        String channelId,
        Context context,
        ViewGroup container,
        AdRequest request,
        AdListener listener,
        RemoteAdConfigResolver resolver,
        AdPlayerFactory playerFactory,
        ConsentResolver consentResolver,
        long adCallbackTimeoutMs,
        TimeoutScheduler timeoutScheduler,
        CallbackDispatcher dispatcher
    ) {
        this(
            channelId,
            context,
            container,
            request,
            listener,
            resolver,
            playerFactory,
            (ignoredChannelId, callback) -> {
                callback.onAllowed(false);
                return () -> {};
            },
            consentResolver,
            new Hq008AdReporter(),
            adCallbackTimeoutMs,
            timeoutScheduler,
            dispatcher
        );
    }

    AdSessionImpl(
        String channelId,
        Context context,
        ViewGroup container,
        AdRequest request,
        AdListener listener,
        RemoteAdConfigResolver resolver,
        AdPlayerFactory playerFactory,
        FlowControlResolver flowControlResolver,
        ConsentResolver consentResolver,
        Hq008AdReporter reporter,
        long adCallbackTimeoutMs,
        TimeoutScheduler timeoutScheduler,
        CallbackDispatcher dispatcher
    ) {
        this.channelId = channelId;
        this.context = context;
        this.container = container;
        this.requestId = resolveRequestId(request.getRequestId());
        this.createdAtMs = System.currentTimeMillis();
        this.soundEnabled = request.isSoundEnabled();
        this.listener = listener;
        this.resolver = resolver;
        this.playerFactory = playerFactory;
        this.flowControlResolver = flowControlResolver;
        this.consentResolver = consentResolver;
        this.reporter = reporter;
        this.adCallbackTimeoutMs = adCallbackTimeoutMs;
        this.timeoutScheduler = timeoutScheduler;
        this.dispatcher = dispatcher;
        this.flowTrace = new FlowTrace(timeoutScheduler);
    }

    void start() {
        synchronized (lock) {
            if (startRequested || terminal) {
                return;
            }
            startRequested = true;
            callbackTimeoutStartedAtMs = timeoutScheduler.nowMs();
        }
        flowTrace.record("CMP_GATE_START", "channel=" + channelId);
        trace("start channel=" + channelId + " timeoutMs=" + adCallbackTimeoutMs);
        armCallbackTimeout();
        resolveFlowControl();
    }

    private void resolveFlowControl() {
        trace("flow-control request");
        try {
            Cancellable newFlowControlCall = flowControlResolver.resolve(
                channelId,
                new FlowControlResolver.Callback() {
                    @Override
                    public void onPopupLogEnabled(boolean enabled) {
                        flowTrace.setEnabled(enabled);
                    }

                    @Override
                    public void onAllowed(boolean skipCmp) {
                        dispatcher.dispatch(() -> {
                            recordTrace("FLOW_CONTROL_ALLOWED", "skip_cmp=" + skipCmp);
                            continueAfterFlowControl(skipCmp);
                        });
                    }

                    @Override
                    public void onBlocked(String reason) {
                        dispatcher.dispatch(() -> finish(AdResult.skipped(
                            reason == null || reason.trim().isEmpty()
                                ? "FLOW_CONTROL_DISABLED"
                                : reason
                        )));
                    }

                    @Override
                    public void onBlocked(String reason, AdError error) {
                        dispatcher.dispatch(() -> finish(AdResult.skipped(reason, error)));
                    }

                    @Override
                    public void onError(Throwable error) {
                        dispatcher.dispatch(() -> finishFlowControlFailure(error));
                    }
                }
            );
            boolean cancelImmediately = false;
            synchronized (lock) {
                if (terminal) {
                    cancelImmediately = true;
                } else {
                    flowControlCall = newFlowControlCall;
                }
            }
            if (cancelImmediately && newFlowControlCall != null) {
                newFlowControlCall.cancel();
            }
        } catch (RuntimeException error) {
            finishFlowControlFailure(error);
        }
    }

    private void finishFlowControlFailure(Throwable cause) {
        AdError error = AdErrors.from(AdErrorCode.CONFIG_NETWORK_ERROR, AdErrorStage.CONFIG, cause, null);
        finish(AdResult.skipped(error.getMessage(), error));
    }

    private void continueAfterFlowControl(boolean skipCmp) {
        synchronized (lock) {
            if (terminal) {
                return;
            }
        }
        if (skipCmp) {
            trace("CMP bypassed by flow-control");
            resolveConfig();
            return;
        }

        try {
            synchronized (lock) {
                if (terminal) return;
                phase = "CMP";
            }
            trace("CMP request");
            Cancellable newConsentCall = consentResolver.resolve(
                context,
                channelId,
                new ConsentResolver.Callback() {
                    @Override
                    public void onTrace(String eventType, String message) {
                        dispatcher.dispatch(() -> recordTrace(eventType, message));
                    }

                    @Override
                    public void onAllowed() {
                        dispatcher.dispatch(() -> {
                            recordTrace("CMP_ALLOWED", "CMP allowed");
                            resolveConfig();
                        });
                    }

                    @Override
                    public void onBlocked(String reason) {
                        dispatcher.dispatch(() -> finish(AdResult.skipped(
                            reason == null || reason.trim().isEmpty()
                                ? "UMP_CONSENT_BLOCKED"
                                : reason
                        )));
                    }

                    @Override
                    public void onBlocked(String reason, AdError error) {
                        dispatcher.dispatch(() -> finish(AdResult.skipped(reason, error)));
                    }

                    @Override
                    public void onError(AdError error) {
                        dispatcher.dispatch(() -> finish(AdResult.error(
                            error == null
                                ? new AdError(
                                    AdErrorCode.INTERNAL_ERROR,
                                    AdErrorStage.INTERNAL,
                                    "Silent UMP consent failed without an error",
                                    null
                                )
                                : error
                        )));
                    }
                }
            );
            boolean cancelImmediately = false;
            synchronized (lock) {
                if (terminal) {
                    cancelImmediately = true;
                } else {
                    consentCall = newConsentCall;
                }
            }
            if (cancelImmediately && newConsentCall != null) {
                newConsentCall.cancel();
            }
        } catch (RuntimeException error) {
            finish(AdResult.error(AdErrors.from(AdErrorCode.INTERNAL_ERROR, AdErrorStage.INTERNAL, error, null)));
        }
    }

    private void resolveConfig() {
        synchronized (lock) {
            if (terminal) {
                return;
            }
            phase = "AUTHORIZE";
        }

        trace("authorize/config request");
        Cancellable newConfigCall;
        try {
            newConfigCall = resolver.resolve(
                channelId,
                requestId,
                new RemoteAdConfigResolver.Callback() {
                    @Override
                    public void onTrace(String eventType, String message) {
                        dispatcher.dispatch(() -> {
                            synchronized (lock) {
                                if (terminal) return;
                                if ("AUTHORIZE_DENIED".equals(eventType)) authorizationOutcome = "DENIED";
                                if ("AUTHORIZE_CALLBACK_FAIL".equals(eventType)) authorizationOutcome = "FAILED";
                            }
                            recordTrace(eventType, message);
                        });
                    }

                    @Override
                    public void onAuthorizationResponse(FlowAuthorizedConfig config) {
                        dispatcher.dispatch(() -> applyAuthorizationResponse(config));
                    }

                    @Override
                    public void onAuthorized(FlowAuthorizedConfig config) {
                        long receivedAtMs = timeoutScheduler.nowMs();
                        dispatcher.dispatch(() -> handleAuthorized(config, receivedAtMs));
                    }

                    @Override
                    public void onResolved(RemoteAdConfigResult result) {
                        dispatcher.dispatch(() -> handleResolved(result));
                    }

                    @Override
                    public void onError(AdError error) {
                        dispatcher.dispatch(() -> finish(AdResult.error(error)));
                    }
                }
            );
        } catch (RuntimeException error) {
            finish(AdResult.error(AdErrors.from(AdErrorCode.CONFIG_NETWORK_ERROR, AdErrorStage.CONFIG, error, null)));
            return;
        }

        synchronized (lock) {
            if (terminal) {
                newConfigCall.cancel();
            } else {
                configCall = newConfigCall;
            }
        }
    }

    private void applyAuthorizationResponse(FlowAuthorizedConfig config) {
        if (config == null) return;
        synchronized (lock) {
            if (terminal || authorizationResponseReceived) return;
            authorizationResponseReceived = true;
            String serverRequestId = config.getRequestId();
            if (serverRequestId != null && !serverRequestId.trim().isEmpty()) {
                requestId = serverRequestId.trim();
            }
            hiddenMode = config.isHiddenMode();
            clientIp = config.getClientIp();
            nextRequestSeconds = config.getNextRequestSeconds();
        }
        recordTrace("AUTHORIZE_RESPONSE", "requestId=" + requestId + " hiddenMode="
            + hiddenMode + " nextRequestSeconds=" + nextRequestSeconds
            + " soundEnabled=" + config.getSoundEnabled() + " clientIp=" + clientIp);
    }

    private void handleAuthorized(FlowAuthorizedConfig config, long receivedAtMs) {
        Long callbackTimeoutOverrideMs;
        synchronized (lock) {
            if (terminal || requestedReported) return;
            applyAuthorizationResponse(config);
            callbackTimeoutOverrideMs = config == null ? null : config.getAdCallbackTimeoutMs();
            requestedReported = true;
            authorizationOutcome = "ALLOWED";
            requestedAtMs = receivedAtMs;
            phase = "GAM_CONFIG";
        }
        flowTrace.record("AUTHORIZE_ALLOWED", "requestId=" + requestId);
        if (callbackTimeoutOverrideMs != null) replaceCallbackTimeout(callbackTimeoutOverrideMs);
        flowTrace.record("AD_REQUESTED", "requestId=" + requestId);
        safely("report requested", () -> reporter.requested(
            requestId, createdAtMs,
            container == null ? 0 : container.getWidth(),
            container == null ? 0 : container.getHeight(), diagnostics()
        ));
    }

    @Override
    public String getRequestId() {
        synchronized (lock) { return requestId; }
    }

    @Override
    public long getNextRequestSeconds() {
        synchronized (lock) { return nextRequestSeconds; }
    }

    @Override
    public AdState getState() {
        return state;
    }

    @Override
    public void pause() {
        trace("pause state=" + state);
        AdPlayer activePlayer;
        synchronized (lock) {
            if (terminal || state != AdState.PLAYING || player == null) {
                return;
            }
            state = AdState.PAUSED;
            phase = "PAUSED";
            activePlayer = player;
        }
        activePlayer.pause();
    }

    @Override
    public void resume() {
        trace("resume state=" + state);
        AdPlayer activePlayer;
        synchronized (lock) {
            if (terminal || state != AdState.PAUSED || player == null) {
                trace("resume ignored; only a paused active session can resume; next ad requires AdSdk.play");
                return;
            }
            state = AdState.PLAYING;
            phase = "PLAYING";
            activePlayer = player;
        }
        activePlayer.resume();
    }

    @Override
    public void setSoundEnabled(boolean enabled) {
        AdPlayer activePlayer;
        synchronized (lock) {
            if (terminal) {
                return;
            }
            soundEnabled = enabled;
            activePlayer = player;
        }
        if (activePlayer != null) {
            activePlayer.setSoundEnabled(enabled);
        }
    }

    @Override
    public void release() {
        trace("release state=" + state);
        finish(AdResult.cancelled());
    }

    private void handleResolved(RemoteAdConfigResult result) {
        synchronized (lock) {
            if (terminal) {
                return;
            }
        }
        if (!result.hasAd()) {
            finish(AdResult.skipped(result.getSkipReason(), result.getError()));
            return;
        }

        AdPlayer newPlayer;
        synchronized (lock) {
            if (terminal) return;
            phase = "PLAYER_LOADING";
            hiddenMode = result.getConfig().isHiddenMode();
            String adTag = result.getConfig().getAdTagUrl();
            adTagLength = adTag == null ? 0 : adTag.length();
            adTagHash = adTag == null ? null : Integer.toHexString(adTag.hashCode());
        }
        flowTrace.record("AD_PHASE_START", "requestId=" + requestId + " hidden=" + hiddenMode);
        trace("config resolved; creating player");
        try {
            newPlayer = playerFactory.create(container, new AdPlayer.Listener() {
                @Override
                public void onTrace(String eventType, String message) {
                    dispatcher.dispatch(() -> recordTrace(eventType, message));
                }

                @Override
                public void onLoaded() {
                    long receivedAtMs = timeoutScheduler.nowMs();
                    dispatcher.dispatch(() -> notifyLoaded(receivedAtMs));
                }

                @Override
                public void onStarted() {
                    long receivedAtMs = timeoutScheduler.nowMs();
                    dispatcher.dispatch(() -> notifyStarted(receivedAtMs));
                }

                @Override
                public void onCompleted() {
                    dispatcher.dispatch(() -> finish(AdResult.completed()));
                }

                @Override
                public void onSkipped(String reason) {
                    dispatcher.dispatch(() -> finish(AdResult.skipped(reason)));
                }

                @Override
                public void onError(AdError error) {
                    dispatcher.dispatch(() -> finish(AdResult.error(error)));
                }
            });
        } catch (RuntimeException error) {
            finish(AdResult.error(internalPlayerError("Unable to create ad player", error)));
            return;
        }

        boolean shouldRelease;
        boolean currentSoundEnabled;
        synchronized (lock) {
            shouldRelease = terminal;
            if (!shouldRelease) {
                player = newPlayer;
                state = AdState.LOADING;
            }
            currentSoundEnabled = soundEnabled;
        }
        if (shouldRelease) {
            newPlayer.release();
            return;
        }

        try {
            Boolean authorizedSoundEnabled = result.getConfig().getSoundEnabled();
            newPlayer.play(
                result.getConfig(),
                authorizedSoundEnabled == null ? currentSoundEnabled : authorizedSoundEnabled
            );
        } catch (RuntimeException error) {
            finish(AdResult.error(internalPlayerError("Unable to start ad player", error)));
        }
    }

    private void notifyLoaded(long receivedAtMs) {
        synchronized (lock) {
            if (terminal || loadedNotified) {
                return;
            }
            loadedNotified = true;
            loadedAtMs = receivedAtMs;
        }
        trace("onLoaded");
        flowTrace.record("AD_LOADED", "requestId=" + requestId);
        safely("report loaded", () -> reporter.loaded(requestId, createdAtMs, diagnostics()));
        listener.onLoaded(this);
    }

    private void notifyStarted(long receivedAtMs) {
        synchronized (lock) {
            if (terminal || startedNotified) {
                return;
            }
            startedNotified = true;
            startedAtMs = receivedAtMs;
            state = AdState.PLAYING;
            phase = "PLAYING";
        }
        trace("onStarted");
        flowTrace.record("AD_STARTED", "requestId=" + requestId);
        safely("report started", () -> reporter.started(requestId, createdAtMs, diagnostics()));
        listener.onStarted(this);
    }

    private void finish(AdResult result) {
        Cancellable activeFlowControlCall;
        Cancellable activeConsentCall;
        Cancellable activeConfigCall;
        Cancellable activeTimeoutCall;
        AdPlayer activePlayer;
        synchronized (lock) {
            if (terminal) {
                trace("duplicate finish ignored result=" + result);
                return;
            }
            terminal = true;
            finishedAtMs = timeoutScheduler.nowMs();
            state = AdState.FINISHED;
            activeFlowControlCall = flowControlCall;
            activeConsentCall = consentCall;
            activeConfigCall = configCall;
            activeTimeoutCall = timeoutCall;
            activePlayer = player;
            flowControlCall = null;
            consentCall = null;
            configCall = null;
            timeoutCall = null;
            player = null;
        }
        trace("finish phase=" + phase + " elapsedMs="
            + Math.max(0L, finishedAtMs - callbackTimeoutStartedAtMs) + " " + result);
        if (activeFlowControlCall != null) safely("cancel flow-control", activeFlowControlCall::cancel);
        if (activeConsentCall != null) safely("cancel CMP", activeConsentCall::cancel);
        if (activeConfigCall != null) safely("cancel config", activeConfigCall::cancel);
        if (activeTimeoutCall != null) safely("cancel timeout", activeTimeoutCall::cancel);
        if (activePlayer != null) safely("release player", activePlayer::release);
        safely("report finished", () -> reporter.finished(requestId, createdAtMs, result, diagnostics()));
        safely("report flow trace", () -> flowTrace.finish(reporter, requestId, result, diagnostics()));
        dispatcher.dispatch(() -> {
            trace("onFinished dispatch " + result);
            listener.onFinished(this, result);
            trace("onFinished returned; session ended");
        });
    }

    private Map<String, Object> diagnostics() {
        synchronized (lock) {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("phase", phase);
            details.put("authorizationOutcome", authorizationOutcome);
            details.put("hiddenMode", hiddenMode);
            details.put("clientIp", clientIp);
            details.put("nextRequestSeconds", nextRequestSeconds);
            details.put("callbackTimeoutMs", adCallbackTimeoutMs);
            details.put("timeoutScope", "whole_session");
            details.put("adTagLength", adTagLength);
            details.put("adTagHash", adTagHash);
            details.put("sessionElapsedMs", Math.max(0L,
                (finishedAtMs >= 0L ? finishedAtMs : timeoutScheduler.nowMs()) - callbackTimeoutStartedAtMs));
            if (requestedAtMs >= 0L) details.put("requestCreatedAtMs", requestedAtMs);
            if (loadedAtMs >= 0L) details.put("loadedAtMs", loadedAtMs);
            if (startedAtMs >= 0L) details.put("startedAtMs", startedAtMs);
            if (finishedAtMs >= 0L) details.put("finishedAtMs", finishedAtMs);
            if (requestedAtMs >= 0L && loadedAtMs >= 0L)
                details.put("requestToLoadDurationMs", Math.max(0L, loadedAtMs - requestedAtMs));
            if (requestedAtMs >= 0L && startedAtMs >= 0L)
                details.put("requestToStartDurationMs", Math.max(0L, startedAtMs - requestedAtMs));
            if (startedAtMs >= 0L && finishedAtMs >= 0L)
                details.put("playbackDurationMs", Math.max(0L, finishedAtMs - startedAtMs));
            if (requestedAtMs >= 0L && finishedAtMs >= 0L)
                details.put("requestTotalDurationMs", Math.max(0L, finishedAtMs - requestedAtMs));
            return details;
        }
    }

    private void safely(String action, Runnable task) {
        try {
            task.run();
        } catch (RuntimeException error) {
            SdkLog.e("AdSdk", "session=" + System.identityHashCode(this)
                + " requestId=" + requestId + " " + action + " failed", error);
        }
    }

    private void trace(String message) {
        recordTrace("SDK_FLOW", message);
    }

    private void recordTrace(String eventType, String message) {
        flowTrace.record(eventType, message);
        SdkLog.i("AdSdk", "session=" + System.identityHashCode(this)
            + " requestId=" + requestId + " " + eventType + " " + message);
    }

    private AdError internalPlayerError(String message, Throwable cause) {
        return new AdError(
            AdErrorCode.PLAYER_ERROR,
            AdErrorStage.PLAYER,
            cause == null ? message : cause.getMessage(),
            cause
        );
    }

    private void armCallbackTimeout() {
        synchronized (lock) {
            if (terminal || timeoutCall != null) {
                return;
            }
            long elapsedMs = Math.max(0L, timeoutScheduler.nowMs() - callbackTimeoutStartedAtMs);
            long remainingMs = Math.max(0L, adCallbackTimeoutMs - elapsedMs);
            timeoutCall = timeoutScheduler.schedule(
                () -> dispatcher.dispatch(() -> finish(AdResult.error(new AdError(
                    AdErrorCode.TIMEOUT,
                    AdErrorStage.INTERNAL,
                    "Ad session did not finish within " + adCallbackTimeoutMs + " ms",
                    null
                )))),
                remainingMs
            );
        }
    }

    private void replaceCallbackTimeout(long timeoutMs) {
        synchronized (lock) {
            if (terminal) {
                return;
            }
            adCallbackTimeoutMs = timeoutMs;
            if (timeoutCall != null) {
                timeoutCall.cancel();
                timeoutCall = null;
            }
        }
        armCallbackTimeout();
    }

    private String resolveRequestId(String value) {
        if (value != null && !value.trim().isEmpty()) {
            return value.trim();
        }
        return "client-"
            + System.currentTimeMillis()
            + "-"
            + UUID.randomUUID().toString().substring(0, 8).toLowerCase(Locale.US);
    }
}
