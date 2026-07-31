package com.smart.android.adsdk.internal;

final class AdPlaybackConfig {
    private final String adTagUrl;
    private final int adLoadTimeoutMs;
    private final long adStartupTimeoutMs;

    AdPlaybackConfig(String adTagUrl, int adLoadTimeoutMs, long adStartupTimeoutMs) {
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
