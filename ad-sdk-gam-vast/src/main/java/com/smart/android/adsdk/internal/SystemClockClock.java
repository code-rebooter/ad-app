package com.smart.android.adsdk.internal;

final class SystemClockClock implements Clock {
    @Override
    public long elapsedRealtime() {
        return android.os.SystemClock.elapsedRealtime();
    }
}
