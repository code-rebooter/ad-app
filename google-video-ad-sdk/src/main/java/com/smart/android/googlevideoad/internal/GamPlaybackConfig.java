package com.smart.android.googlevideoad.internal;

final class GamPlaybackConfig {
    private final String adTagUrl;
    private final int adLoadTimeoutMs;
    private final long adStartupTimeoutMs;

    GamPlaybackConfig(String adTagUrl, int adLoadTimeoutMs, long adStartupTimeoutMs) {
        this.adTagUrl = adTagUrl;
        this.adLoadTimeoutMs = adLoadTimeoutMs;
        this.adStartupTimeoutMs = adStartupTimeoutMs;
    }

    String getAdTagUrl() {
        return adTagUrl;
    }

    int getAdLoadTimeoutMs() {
        return adLoadTimeoutMs;
    }

    long getAdStartupTimeoutMs() {
        return adStartupTimeoutMs;
    }
}
