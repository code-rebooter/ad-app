package com.smart.android.googleima.internal;

/** Per-ad state for the native player to IMA Core start handshake. */
final class GoogleImaStartHandshakeState {
    static final int ACTION_NONE = 0;
    static final int ACTION_BLOCK_START = 1;
    static final int ACTION_RELEASE_START = 2;

    private Object mediaInfo;
    private boolean mediaEvidenceObserved;
    private boolean coreStartSent;
    private boolean imaStarted;
    private boolean omidStarted;
    private boolean recoveryRequested;
    private int coreStartSendCount;
    private float lastProgressSeconds = -1f;
    private float lastDurationSeconds = -1f;

    void onPlay(Object currentMediaInfo, boolean omidSessionAlreadyActive) {
        mediaInfo = currentMediaInfo;
        mediaEvidenceObserved = false;
        coreStartSent = false;
        imaStarted = false;
        omidStarted = omidSessionAlreadyActive;
        recoveryRequested = false;
        coreStartSendCount = 0;
        lastProgressSeconds = -1f;
        lastDurationSeconds = -1f;
    }

    boolean markMediaEvidence() {
        if (mediaInfo == null || mediaEvidenceObserved) return false;
        mediaEvidenceObserved = true;
        return true;
    }

    int prepareStart(Object currentMediaInfo, float progressSeconds, float durationSeconds) {
        if (mediaInfo == null || mediaInfo != currentMediaInfo || imaStarted) {
            return ACTION_NONE;
        }
        lastProgressSeconds = progressSeconds;
        lastDurationSeconds = durationSeconds;
        if (!mediaEvidenceObserved) return ACTION_BLOCK_START;
        if (!coreStartSent || recoveryRequested) return ACTION_RELEASE_START;
        return ACTION_NONE;
    }

    boolean onCoreStartSent(Object currentMediaInfo) {
        if (mediaInfo == null || mediaInfo != currentMediaInfo) return false;
        coreStartSent = true;
        recoveryRequested = false;
        coreStartSendCount += 1;
        return true;
    }

    boolean markImaStarted() {
        if (mediaInfo == null || imaStarted) return false;
        imaStarted = true;
        recoveryRequested = false;
        return true;
    }

    boolean markOmidStarted() {
        if (mediaInfo == null || omidStarted) return false;
        omidStarted = true;
        return true;
    }

    boolean requestRecovery() {
        if (mediaInfo == null || imaStarted || recoveryRequested) return false;
        recoveryRequested = true;
        return true;
    }

    boolean isImaStarted() {
        return imaStarted;
    }

    boolean isOmidStarted() {
        return omidStarted;
    }

    boolean isMeasurementReady() {
        return imaStarted && omidStarted;
    }

    int getCoreStartSendCount() {
        return coreStartSendCount;
    }

    float getLastProgressSeconds() {
        return lastProgressSeconds;
    }

    float getLastDurationSeconds() {
        return lastDurationSeconds;
    }
}
