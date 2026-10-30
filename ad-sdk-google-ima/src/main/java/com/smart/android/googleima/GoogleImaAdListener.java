package com.smart.android.googleima;

public interface GoogleImaAdListener {
    default void onImaAuditEvent(GoogleImaAuditEvent event) {}

    default void onAdLoaded() {}

    default void onAdStarted() {}

    default void onAdStarted(String source) {
        onAdStarted();
    }

    default void onAdMediaStartEvidence(String source, String adInstanceId) {}

    default void onAdStarted(String source, GoogleImaAdInfo adInfo) {
        onAdStarted(source);
    }

    default void onAdFirstFrameRendered() {}

    default void onAdFirstFrameRendered(String adInstanceId) {
        onAdFirstFrameRendered();
    }

    default void onAdCompleted(String event) {}

    default void onAdSkipped(String event) {}

    default void onAdError(GoogleImaPlaybackError error) {
        onAdError(error == null ? "" : error.getMessage());
    }

    /** Compatibility callback for existing hosts. */
    default void onAdError(String message) {}
}
