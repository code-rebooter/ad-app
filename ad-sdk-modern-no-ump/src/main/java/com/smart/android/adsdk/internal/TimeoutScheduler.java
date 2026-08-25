package com.smart.android.adsdk.internal;

interface TimeoutScheduler {
    Cancellable schedule(Runnable action, long delayMs);
}
