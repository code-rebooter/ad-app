package com.smart.android.ad_app;

/**
 * Pure state machine for one SDK stress run. It deliberately has no Android
 * dependency so request overlap and terminal-callback behavior stay unit tested.
 */
public final class Hq008StressRunState {
    public enum Mode {
        CUSTOMER_LIFECYCLE,
        IMMEDIATE_RELEASE,
        UI_ONLY
    }

    private final Mode mode;
    private final int maxRequests;
    private boolean running;
    private boolean requestInFlight;
    private int startedRequests;
    private int completedRequests;

    public Hq008StressRunState(Mode mode, int maxRequests) {
        if (mode == null) {
            throw new IllegalArgumentException("mode == null");
        }
        this.mode = mode;
        this.maxRequests = Math.max(0, maxRequests);
    }

    public boolean start() {
        if (running) {
            return false;
        }
        running = true;
        requestInFlight = false;
        startedRequests = 0;
        completedRequests = 0;
        return true;
    }

    public void stop() {
        running = false;
        requestInFlight = false;
    }

    public boolean canStartRequest() {
        return running
                && !requestInFlight
                && (maxRequests == 0 || startedRequests < maxRequests);
    }

    public boolean tryStartRequest() {
        if (!canStartRequest()) {
            return false;
        }
        requestInFlight = true;
        startedRequests++;
        return true;
    }

    /** Returns false when a duplicate terminal callback is received. */
    public boolean finishRequest() {
        if (!requestInFlight) {
            return false;
        }
        requestInFlight = false;
        completedRequests++;
        if (maxRequests > 0 && completedRequests >= maxRequests) {
            running = false;
        }
        return true;
    }

    public boolean shouldReleaseOnTerminal() {
        return mode == Mode.IMMEDIATE_RELEASE;
    }

    public Mode getMode() {
        return mode;
    }

    public boolean isRunning() {
        return running;
    }

    public boolean isRequestInFlight() {
        return requestInFlight;
    }

    public int getStartedRequests() {
        return startedRequests;
    }

    public int getCompletedRequests() {
        return completedRequests;
    }
}
