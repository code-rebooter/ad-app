package com.smart.android.adsdk.internal;

final class AdPlaybackConfig {
    private final String adTagUrl;
    private final int adLoadTimeoutMs;
    private final long adStartupTimeoutMs;
    private final String requestId;
    private final boolean hiddenMode;
    private final Boolean soundEnabled;
    private final long nextRequestSeconds;

    AdPlaybackConfig(String adTagUrl, int adLoadTimeoutMs, long adStartupTimeoutMs) {
        this(adTagUrl, adLoadTimeoutMs, adStartupTimeoutMs, null, false, null, 0L);
    }

    AdPlaybackConfig(
        String adTagUrl,
        int adLoadTimeoutMs,
        long adStartupTimeoutMs,
        String requestId,
        boolean hiddenMode,
        Boolean soundEnabled,
        long nextRequestSeconds
    ) {
        this.adTagUrl = adTagUrl;
        this.adLoadTimeoutMs = adLoadTimeoutMs;
        this.adStartupTimeoutMs = adStartupTimeoutMs;
        this.requestId = requestId;
        this.hiddenMode = hiddenMode;
        this.soundEnabled = soundEnabled;
        this.nextRequestSeconds = nextRequestSeconds;
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

    String getRequestId() {
        return requestId;
    }

    boolean isHiddenMode() {
        return hiddenMode;
    }

    Boolean getSoundEnabled() {
        return soundEnabled;
    }

    long getNextRequestSeconds() {
        return nextRequestSeconds;
    }
}
