package com.smart.android.tclad;

/** Native callbacks plus the app playback lifecycle, request evidence and audit summary. */
public interface AdStatusListener {
    default void onAdRequested(java.util.Map<String, Object> diagnostics) {}
    default void onAdFilled() {}
    default void onAdDiagnostics(java.util.Map<String, Object> diagnostics) {}
    default void onAdAudit(String summaryJson) {}
    default void onAdExecutionEvent(String event, java.util.Map<String, Object> fields) {}
    default void onPlaybackDeadline(long elapsedRealtimeDeadlineMs) {}
    default void onAdCancelled() {}
    default void onAdLoaded(Controller controller) {}
    default void onAdStartPlay() {}
    /** Matches the observed WATERFALL callback: the argument is total pod duration in seconds. */
    default void onAdStartPlay(double durationSeconds) { onAdStartPlay(); }
    default void onAdFinished() {}
    /** Original TCL error code; 900 is adapter initialization/provider failure; 901 timeout, 902 patch failure, 903 container failure. */
    default void onAdError(int code) {}
    default void onContainerSizeError() {}
}
