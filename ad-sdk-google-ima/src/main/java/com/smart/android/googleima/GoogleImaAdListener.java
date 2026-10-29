package com.smart.android.googleima;

public interface GoogleImaAdListener {
    default void onImaAuditEvent(GoogleImaAuditEvent event) {}

    default void onAdLoaded() {}

    default void onAdStarted() {}

    default void onAdFirstFrameRendered() {}

    default void onAdCompleted(String event) {}

    default void onAdSkipped(String event) {}

    default void onAdError(String message) {}
}
