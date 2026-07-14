package com.smart.android.googlevideoad.internal;

import android.view.ViewGroup;
import com.smart.android.googlevideoad.AdError;
import com.smart.android.googlevideoad.AdErrorCode;
import com.smart.android.googlevideoad.AdErrorStage;
import com.smart.android.googlevideoad.AdListener;
import com.smart.android.googlevideoad.AdRequest;
import com.smart.android.googlevideoad.AdResult;
import com.smart.android.googlevideoad.AdSession;
import com.smart.android.googlevideoad.AdState;

final class AdSessionImpl implements AdSession {
    private final Object lock = new Object();
    private final String channelId;
    private final ViewGroup container;
    private final AdListener listener;
    private final GamConfigResolver resolver;
    private final AdPlayerFactory playerFactory;
    private final CallbackDispatcher dispatcher;

    private volatile AdState state = AdState.RESOLVING_CONFIG;
    private boolean soundEnabled;
    private boolean startRequested;
    private boolean loadedNotified;
    private boolean startedNotified;
    private boolean terminal;
    private Cancellable configCall;
    private AdPlayer player;

    AdSessionImpl(
        String channelId,
        ViewGroup container,
        AdRequest request,
        AdListener listener,
        GamConfigResolver resolver,
        AdPlayerFactory playerFactory,
        CallbackDispatcher dispatcher
    ) {
        this.channelId = channelId;
        this.container = container;
        this.soundEnabled = request.isSoundEnabled();
        this.listener = listener;
        this.resolver = resolver;
        this.playerFactory = playerFactory;
        this.dispatcher = dispatcher;
    }

    void start() {
        synchronized (lock) {
            if (startRequested || terminal) {
                return;
            }
            startRequested = true;
        }

        Cancellable newConfigCall = resolver.resolve(channelId, new GamConfigResolver.Callback() {
            @Override
            public void onResolved(GamResolveResult result) {
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

    private void handleResolved(GamResolveResult result) {
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
        Cancellable activeConfigCall;
        AdPlayer activePlayer;
        synchronized (lock) {
            if (terminal) {
                return;
            }
            terminal = true;
            state = AdState.FINISHED;
            activeConfigCall = configCall;
            activePlayer = player;
            configCall = null;
            player = null;
        }
        if (activeConfigCall != null) {
            activeConfigCall.cancel();
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
}
