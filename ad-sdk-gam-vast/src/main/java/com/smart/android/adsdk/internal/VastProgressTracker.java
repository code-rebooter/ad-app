package com.smart.android.adsdk.internal;

final class VastProgressTracker {
    private final String url;
    private final long offsetMs;
    private final float offsetPercent;

    private VastProgressTracker(String url, long offsetMs, float offsetPercent) {
        this.url = url;
        this.offsetMs = offsetMs;
        this.offsetPercent = offsetPercent;
    }

    static VastProgressTracker absolute(String url, long offsetMs) {
        return new VastProgressTracker(url, Math.max(0L, offsetMs), -1f);
    }

    static VastProgressTracker percentage(String url, float offsetPercent) {
        return new VastProgressTracker(url, -1L, offsetPercent);
    }

    String getUrl() {
        return url;
    }

    long getOffsetMs() {
        return offsetMs;
    }

    float getOffsetPercent() {
        return offsetPercent;
    }

    boolean shouldFire(long durationMs, long positionMs) {
        if (positionMs < 0L) {
            return false;
        }
        if (offsetMs >= 0L) {
            return positionMs >= offsetMs;
        }
        return durationMs > 0L && offsetPercent >= 0f
            && positionMs / (float) durationMs >= offsetPercent;
    }
}
