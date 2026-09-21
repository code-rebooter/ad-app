package com.smart.android.adsdk.internal;

import com.smart.android.adsdk.AdErrorCode;
import com.smart.android.adsdk.AdErrorStage;
import com.smart.android.adsdk.AdListener;
import com.smart.android.adsdk.AdRequest;
import com.smart.android.adsdk.AdResult;
import com.smart.android.adsdk.AdSession;
import com.smart.android.adsdk.AdState;
import java.util.UUID;

/** One host call. No interval checks, timers, retries or backend aggregate ad events. */
final class FusionAdSession implements AdSession {
    interface Factory {
        ChannelSession create(AdRequest request, AdListener listener);
    }

    private final AdListener listener;
    private final CallbackDispatcher dispatcher;
    private final Factory[] factories;
    private final ChannelSession[] children = new ChannelSession[2];
    private final AdResult[] results = new AdResult[2];
    private final String requestId;
    private volatile ChannelSession active;
    private volatile boolean terminal;
    private volatile boolean cancelRequested;
    private volatile ChannelSession googleSession;
    private final Object terminalLock = new Object();
    private boolean started;
    private boolean loadedNotified;
    private boolean startedNotified;
    private boolean soundEnabled;

    FusionAdSession(AdRequest request, AdListener listener, CallbackDispatcher dispatcher,
                    Factory google, Factory tcl) {
        this.listener = listener;
        this.dispatcher = dispatcher;
        this.factories = new Factory[] {google, tcl};
        this.requestId = request.getRequestId() == null ? newRequestId() : request.getRequestId();
        this.soundEnabled = request.isSoundEnabled();
    }

    void start() {
        dispatcher.dispatch(() -> {
            if (started || terminal || cancelRequested) return;
            started = true;
            trace("start");
            begin(0);
        });
    }

    private void begin(int index) {
        if (terminal || cancelRequested) return;
        if (index == factories.length) {
            finish(FusionResultPolicy.combine(results[0], results[1]));
            return;
        }
        AdRequest request = new AdRequest.Builder()
            .setRequestId(index == 0 ? requestId : newRequestId())
            .setSoundEnabled(soundEnabled).build();
        try {
            ChannelSession child = factories[index].create(request, new AdListener() {
                @Override public void onLoaded(AdSession session) {
                    dispatcher.dispatch(() -> {
                        if (!isCurrent(index, session) || loadedNotified) return;
                        loadedNotified = true;
                        trace("onLoaded");
                        notifySafely(() -> listener.onLoaded(FusionAdSession.this));
                    });
                }
                @Override public void onStarted(AdSession session) {
                    dispatcher.dispatch(() -> {
                        if (!isCurrent(index, session) || startedNotified) return;
                        startedNotified = true;
                        trace("onStarted");
                        notifySafely(() -> listener.onStarted(FusionAdSession.this));
                    });
                }
                @Override public void onFinished(AdSession session, AdResult result) {
                    dispatcher.dispatch(() -> {
                        if (!isCurrent(index, session)) return;
                        completeChild(index, result);
                    });
                }
            });
            if (child == null) throw new IllegalStateException("Channel session factory returned null");
            synchronized (terminalLock) {
                children[index] = child;
                if (index == 0) googleSession = child;
                active = child;
                if (terminal || cancelRequested) return;
                trace("channelStart provider=" + provider(index));
                child.start();
            }
        } catch (RuntimeException error) {
            // A synchronous child completion may already have advanced to the next channel.
            if (!terminal && results[index] == null) {
                ChannelSession failed = children[index];
                AdResult result = AdResult.error(AdErrors.from(
                    AdErrorCode.INTERNAL_ERROR, AdErrorStage.INTERNAL, error, null));
                results[index] = result; // Disarm callbacks before releasing a partially started child.
                active = null;
                if (failed != null) notifySafely(failed::release);
                trace("channelException provider=" + provider(index) + " " + result);
                begin(index + 1);
            }
        }
    }

    private boolean isCurrent(int index, AdSession session) {
        return !terminal && !cancelRequested && results[index] == null && session == children[index] && session == active;
    }

    private void completeChild(int index, AdResult result) {
        if (result == null) {
            result = AdResult.error(AdErrors.from(AdErrorCode.INTERNAL_ERROR, AdErrorStage.INTERNAL,
                new IllegalStateException("Channel completed without a result"), null));
        }
        results[index] = result;
        active = null;
        trace("channelFinished provider=" + provider(index)
            + " requestId=" + children[index].getRequestId() + " " + result);
        // Child finish releases its player and reports its own terminal result before this callback.
        begin(index + 1);
    }

    private void finish(AdResult result) {
        synchronized (terminalLock) {
            if (terminal || cancelRequested) return;
            terminal = true;
        }
        active = null;
        trace("onFinished " + result);
        notifySafely(() -> listener.onFinished(this, result));
    }

    @Override public AdState getState() {
        ChannelSession current = active;
        if (terminal) return AdState.FINISHED;
        AdState childState = current == null ? AdState.RESOLVING_CONFIG : current.getState();
        return childState == AdState.FINISHED ? AdState.RESOLVING_CONFIG : childState;
    }

    /** Preserve the original Google request metadata; TCL cannot overwrite it. */
    @Override public String getRequestId() {
        ChannelSession google = googleSession;
        return google == null ? requestId : google.getRequestId();
    }

    @Override public long getNextRequestSeconds() {
        ChannelSession google = googleSession;
        return google == null ? 0L : google.getNextRequestSeconds();
    }

    @Override public void pause() {
        dispatcher.dispatch(() -> { if (!terminal && active != null) active.pause(); });
    }

    @Override public void resume() {
        dispatcher.dispatch(() -> { if (!terminal && active != null) active.resume(); });
    }

    @Override public void setSoundEnabled(boolean enabled) {
        dispatcher.dispatch(() -> {
            if (terminal) return;
            soundEnabled = enabled;
            if (active != null) active.setSoundEnabled(enabled);
        });
    }

    @Override public void release() {
        // Record intent at call time: completion may already be ahead of cleanup in the main queue.
        synchronized (terminalLock) {
            if (terminal || cancelRequested) return;
            cancelRequested = true;
            if (active != null) active.requestCancellation();
        }
        dispatcher.dispatch(() -> {
            synchronized (terminalLock) {
                if (terminal) return;
                terminal = true;
            }
            ChannelSession current = active;
            active = null;
            if (current != null) notifySafely(current::release);
            AdResult result = AdResult.cancelled();
            trace("onFinished " + result);
            notifySafely(() -> listener.onFinished(this, result));
        });
    }

    private void notifySafely(Runnable action) {
        try { action.run(); }
        catch (RuntimeException error) { SdkLog.e("AdSdkFusion", "callback/control failed", error); }
    }

    private void trace(String message) {
        SdkLog.i("AdSdkFusion", "fusionId=" + requestId + " " + message);
    }

    private static String provider(int index) { return index == 0 ? "GOOGLE" : "TCL"; }
    private static String newRequestId() { return "client-" + UUID.randomUUID(); }
}
