package com.smart.android.adsdk.internal;

interface TimeoutScheduler {
    default long nowMs() {
        return System.nanoTime() / 1_000_000L;
    }

    Cancellable schedule(Runnable action, long delayMs);
}
