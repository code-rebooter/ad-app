package com.smart.android.hq008flow;

/**
 * One authorized ad attempt. Call exactly one terminal method, completed() or
 * failed(...), after the ad finishes.
 */
public final class Hq008AdSession {
    private final Delegate delegate;

    Hq008AdSession(Delegate delegate) {
        this.delegate = delegate;
    }

    /** Call from TCL onAdLoaded(controller). */
    public void loaded() {
        delegate.loaded();
    }

    /** Call from TCL onAdStartPlay(). */
    public void started() {
        delegate.started(null);
    }

    /** Call from TCL onAdStartPlay(progress). */
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
        void loaded();
        void started(Double progress);
        void completed();
        void failed(Integer code, String message);
    }
}
