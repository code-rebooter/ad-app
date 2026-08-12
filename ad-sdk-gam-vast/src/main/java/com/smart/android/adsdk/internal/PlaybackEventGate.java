package com.smart.android.adsdk.internal;

final class PlaybackEventGate {
    private boolean loaded;
    private boolean started;
    private boolean firstFrame;
    private boolean revealed;
    private boolean terminal;

    synchronized boolean markLoaded() {
        if (loaded || terminal) {
            return false;
        }
        loaded = true;
        return true;
    }

    synchronized boolean markStarted() {
        if (started || terminal) {
            return false;
        }
        started = true;
        return true;
    }

    synchronized boolean markFirstFrame() {
        if (firstFrame || terminal) {
            return false;
        }
        firstFrame = true;
        return true;
    }

    synchronized boolean hasStarted() {
        return started;
    }

    synchronized boolean isTerminal() {
        return terminal;
    }

    synchronized boolean consumeRevealReady() {
        if (terminal || revealed || !started || !firstFrame) {
            return false;
        }
        revealed = true;
        return true;
    }

    synchronized boolean markTerminal() {
        if (terminal) {
            return false;
        }
        terminal = true;
        return true;
    }
}
