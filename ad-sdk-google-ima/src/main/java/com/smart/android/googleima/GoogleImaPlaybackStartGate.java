package com.smart.android.googleima;

/** Keeps IMA as the preferred start signal while accepting proven Media3 playback. */
final class GoogleImaPlaybackStartGate {
    enum Decision {
        START_IMA,
        START_MEDIA_FALLBACK,
        WAIT_FOR_IMA,
        IGNORE
    }

    private boolean started;
    private boolean mediaPlaybackObserved;

    Decision onImaStarted() {
        if (started) return Decision.IGNORE;
        started = true;
        return Decision.START_IMA;
    }

    Decision onMediaPlaybackObserved() {
        mediaPlaybackObserved = true;
        return started ? Decision.IGNORE : Decision.WAIT_FOR_IMA;
    }

    Decision onMediaFallbackDeadline() {
        if (started || !mediaPlaybackObserved) return Decision.IGNORE;
        started = true;
        return Decision.START_MEDIA_FALLBACK;
    }

    Decision onTerminalBeforeImaStart() {
        return onMediaFallbackDeadline();
    }

    boolean hasStarted() {
        return started;
    }

    boolean hasMediaPlaybackEvidence() {
        return mediaPlaybackObserved;
    }

    void reset() {
        started = false;
        mediaPlaybackObserved = false;
    }
}
