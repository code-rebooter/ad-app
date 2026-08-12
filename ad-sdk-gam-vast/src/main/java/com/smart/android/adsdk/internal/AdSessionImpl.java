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

final class AdSessionImpl implements AdSession {
    private final Object lock = new Object();
    private final String channelId;
    private final Context context;
    private final ViewGroup container;
    private final AdListener listener;
    private final RemoteAdConfigResolver resolver;
    private final AdPlayerFactory playerFactory;
    private final ConsentResolver consentResolver;
    private final long adCallbackTimeoutMs;
    private final TimeoutScheduler timeoutScheduler;
    private final CallbackDispatcher dispatcher;

    private volatile AdState state = AdState.RESOLVING_CONFIG;
    private final String requestId;
    private boolean soundEnabled;
    private boolean startRequested;
    private boolean loadedNotified;
    private boolean startedNotified;
    private boolean terminal;
    private Cancellable consentCall;
    private Cancellable configCall;
    private Cancellable timeoutCall;
    private AdPlayer player;

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
        this.channelId = channelId;
        this.context = context;
        this.container = container;
        this.requestId = request.getRequestId();
        this.soundEnabled = request.isSoundEnabled();
        this.listener = listener;
        this.resolver = resolver;
        this.playerFactory = playerFactory;
        this.consentResolver = consentResolver;
        this.adCallbackTimeoutMs = adCallbackTimeoutMs;
        this.timeoutScheduler = timeoutScheduler;
        this.dispatcher = dispatcher;
    }

    void start() {
        synchronized (lock) {
            if (startRequested || terminal) {
                return;
            }
            startRequested = true;
        }

        armCallbackTimeout();
        try {
            Cancellable newConsentCall = consentResolver.resolve(context, channelId, new ConsentResolver.Callback() {
                @Override
                public void onAllowed() {
                    dispatcher.dispatch(AdSessionImpl.this::resolveConfig);
                }

                @Override
                public void onBlocked(String reason) {
                    dispatcher.dispatch(() -> finish(AdResult.skipped(
                        reason == null || reason.trim().isEmpty()
                            ? "UMP_CONSENT_BLOCKED"
                            : reason.trim()
                    )));
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
            });
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
            finish(AdResult.error(new AdError(
                AdErrorCode.INTERNAL_ERROR,
                AdErrorStage.INTERNAL,
                "Silent UMP consent resolver threw an exception",
                error
            )));
        }
    }

    private void resolveConfig() {
        synchronized (lock) {
            if (terminal) {
                return;
            }
        }

        Cancellable newConfigCall = resolver.resolve(channelId, requestId, new RemoteAdConfigResolver.Callback() {
            @Override
            public void onResolved(RemoteAdConfigResult result) {
                dispatcher.dispatch(() -> handleResolved(result));
            }

            @Override
            public void onError(AdError error) {
                dispatcher.dispatch(() -> finish(AdResult.error(error)));
            }
        });

        synchronized (lock) {
            if (terminal) {
                newConfigCall.cancel();
            } else {
                configCall = newConfigCall;
            }
        }
    }

    @Override
    public AdState getState() {
        return state;
    }

    @Override
    public void pause() {
        AdPlayer activePlayer;
        synchronized (lock) {
            if (terminal || state != AdState.PLAYING || player == null) {
                return;
            }
            state = AdState.PAUSED;
            activePlayer = player;
        }
        activePlayer.pause();
    }

    @Override
    public void resume() {
        AdPlayer activePlayer;
        synchronized (lock) {
            if (terminal || state != AdState.PAUSED || player == null) {
                return;
            }
            state = AdState.PLAYING;
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
        finish(AdResult.cancelled());
    }

    private void handleResolved(RemoteAdConfigResult result) {
        synchronized (lock) {
            if (terminal) {
                return;
            }
        }
        if (!result.hasAd()) {
            finish(AdResult.skipped(result.getSkipReason()));
            return;
        }

        AdPlayer newPlayer;
        try {
            newPlayer = playerFactory.create(container, new AdPlayer.Listener() {
                @Override
                public void onLoaded() {
                    dispatcher.dispatch(AdSessionImpl.this::notifyLoaded);
                }

                @Override
                public void onStarted() {
                    dispatcher.dispatch(AdSessionImpl.this::notifyStarted);
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
            newPlayer.play(result.getConfig(), currentSoundEnabled);
        } catch (RuntimeException error) {
            finish(AdResult.error(internalPlayerError("Unable to start ad player", error)));
        }
    }

    private void notifyLoaded() {
        synchronized (lock) {
            if (terminal || loadedNotified) {
                return;
            }
            loadedNotified = true;
        }
        listener.onLoaded(this);
    }

    private void notifyStarted() {
        synchronized (lock) {
            if (terminal || startedNotified) {
                return;
            }
            startedNotified = true;
            state = AdState.PLAYING;
        }
        listener.onStarted(this);
    }

    private void finish(AdResult result) {
        Cancellable activeConsentCall;
        Cancellable activeConfigCall;
        Cancellable activeTimeoutCall;
        AdPlayer activePlayer;
        synchronized (lock) {
            if (terminal) {
                return;
            }
            terminal = true;
            state = AdState.FINISHED;
            activeConsentCall = consentCall;
            activeConfigCall = configCall;
            activeTimeoutCall = timeoutCall;
            activePlayer = player;
            consentCall = null;
            configCall = null;
            timeoutCall = null;
            player = null;
        }
        if (activeConsentCall != null) {
            activeConsentCall.cancel();
        }
        if (activeConfigCall != null) {
            activeConfigCall.cancel();
        }
        if (activeTimeoutCall != null) {
            activeTimeoutCall.cancel();
        }
        if (activePlayer != null) {
            activePlayer.release();
        }
        dispatcher.dispatch(() -> listener.onFinished(this, result));
    }

    private AdError internalPlayerError(String message, Throwable cause) {
        return new AdError(
            AdErrorCode.PLAYER_ERROR,
            AdErrorStage.PLAYER,
            message,
            cause
        );
    }

    private void armCallbackTimeout() {
        synchronized (lock) {
            if (terminal || timeoutCall != null) {
                return;
            }
            timeoutCall = timeoutScheduler.schedule(
                () -> dispatcher.dispatch(() -> finish(AdResult.error(new AdError(
                    AdErrorCode.TIMEOUT,
                    AdErrorStage.INTERNAL,
                    "Ad session did not finish within " + adCallbackTimeoutMs + " ms",
                    null
                )))),
                adCallbackTimeoutMs
            );
        }
    }
}
