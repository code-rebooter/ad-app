package com.smart.android.tclad.ima;

import java.util.Map;

/** Host reporting and window callbacks for one authorized IMA attempt. */
public interface GoogleImaSessionListener {
    default void onAdLoaded() {}
    default void onAdStarted() {}
    default void onAdCompleted() {}
    default void onAdFailed() {}
    default void onAdCancelled(String reason) {}
    /** Persist/send these events using the host's existing ad/report transport. */
    default void onAdReport(String requestId, String eventType, String message, Map<String, Object> diagnostics) {}
    default void onTrace(String requestId, String eventType, String message, String adLog) {}
    default void onAdAudit(String requestId, String summaryJson) {}
    default void onPlaybackDeadline(String requestId, long elapsedRealtimeDeadlineMs) {}
    default boolean isPlaybackAllowed() { return true; }
    default String playbackDeniedReason() { return "external_playback_denied"; }
}
