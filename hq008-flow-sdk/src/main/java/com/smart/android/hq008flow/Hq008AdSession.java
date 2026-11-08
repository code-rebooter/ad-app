package com.smart.android.hq008flow;

/**
 * One authorized ad attempt. Call exactly one terminal method, completed() or
 * failed(...), after the ad finishes.
 */
public final class Hq008AdSession {
    private final Delegate delegate;
    private final Hq008Authorization authorization;

    public Hq008Authorization getAuthorization() { return authorization; }
    public String getRequestId() { return authorization.getRequestId(); }
    public String getChannelId() { return authorization.getChannelId(); }

    public void runtimeEvent(String event, java.util.Map<String, Object> fields) { delegate.runtimeEvent(event, fields); }
    public void runtimeAudit(String summaryJson) { delegate.runtimeAudit(summaryJson); }
    public void setCancellationHandler(Runnable handler) { delegate.cancellationHandler(handler); }

    /** Confirm only after the advertising SDK receives an address HTTP response. */
    public void requested(java.util.Map<String, Object> diagnostics) { delegate.requested(diagnostics); }
    public void filled() { delegate.filled(); }
    public void diagnostics(java.util.Map<String, Object> values) { delegate.diagnostics(values); }
    /** Supply a monotonic playback deadline from the advertising SDK's duration budget. */
    public void extendPlaybackDeadline(long elapsedRealtimeDeadlineMs) {
        delegate.extendPlaybackDeadline(elapsedRealtimeDeadlineMs);
    }

    Hq008AdSession(Delegate delegate, Hq008Authorization authorization) {
        this.delegate = delegate;
        this.authorization = authorization;
    }

    /** Call from TCL onAdLoaded(controller). */
    public void loaded() {
        delegate.loaded();
    }

    /** Call from TCL onAdStartPlay(). */
    public void started() {
        delegate.started(null);
    }

    /** TCL passes the maximum ad-break duration in seconds, not playback progress. */
    public void started(double progress) {
        delegate.started(progress);
    }

    /** Call from TCL onAdFinished(). */
    public void completed() {
        delegate.completed();
    }

    /** Call from TCL onAdError(errorCode). */
    public void failed(Integer code, String message) {
        delegate.failed(code, message);
    }

    /** Use for errors without an integer code, such as a container size error. */
    public void failed(String message) {
        delegate.failed(null, message);
    }

    interface Delegate {
        void runtimeEvent(String event, java.util.Map<String, Object> fields);
        void runtimeAudit(String summaryJson);
        void cancellationHandler(Runnable handler);
        void requested(java.util.Map<String, Object> diagnostics);
        void filled();
        void diagnostics(java.util.Map<String, Object> values);
        void extendPlaybackDeadline(long deadlineMs);
        void loaded();
        void started(Double progress);
        void completed();
        void failed(Integer code, String message);
    }
}
