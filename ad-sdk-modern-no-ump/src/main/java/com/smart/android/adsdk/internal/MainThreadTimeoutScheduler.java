package com.smart.android.adsdk.internal;

import android.os.Handler;
import android.os.Looper;

final class MainThreadTimeoutScheduler implements TimeoutScheduler {
    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override
    public Cancellable schedule(Runnable action, long delayMs) {
        handler.postDelayed(action, delayMs);
        return () -> handler.removeCallbacks(action);
    }
}
