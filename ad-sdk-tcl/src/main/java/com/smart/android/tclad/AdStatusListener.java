package com.smart.android.tclad;

/** Callbacks are forwarded directly, preserving original TCL callback timing and multiplicity. */
public interface AdStatusListener {
    default void onAdLoaded(Controller controller) {}
    default void onAdStartPlay() {}
    /** Matches the observed WATERFALL callback: the argument is total pod duration in seconds. */
    default void onAdStartPlay(double durationSeconds) { onAdStartPlay(); }
    default void onAdFinished() {}
    /** Original TCL error code; 900 is reserved for adapter initialization/provider failures. */
    default void onAdError(int code) {}
    default void onContainerSizeError() {}
}
