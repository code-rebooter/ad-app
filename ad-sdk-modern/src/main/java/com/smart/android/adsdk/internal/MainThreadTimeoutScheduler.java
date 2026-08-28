package com.smart.android.adsdk.internal;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

final class MainThreadTimeoutScheduler implements TimeoutScheduler {
    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override
    public long nowMs() {
        return SystemClock.elapsedRealtime();
    }

    @Override
    public Cancellable schedule(Runnable action, long delayMs) {
        handler.postDelayed(action, delayMs);
        return () -> handler.removeCallbacks(action);
    }
}
