package com.smart.android.adsdk.universal.internal;

import com.smart.android.adsdk.universal.AdSession;

/** A single channel's original flow, created before it is started. */
interface ChannelSession extends AdSession {
    void start();

    /** Mark cancellation immediately; UI teardown stays on the callback dispatcher. */
    default void requestCancellation() {}
}
