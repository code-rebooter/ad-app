package com.smart.android.adsdk.internal;

final class RemoteAdConfigResult {
    private final AdPlaybackConfig config;
    private final String skipReason;

    private RemoteAdConfigResult(AdPlaybackConfig config, String skipReason) {
        this.config = config;
        this.skipReason = skipReason;
    }

    static RemoteAdConfigResult withAd(AdPlaybackConfig config) {
        return new RemoteAdConfigResult(config, null);
    }

    static RemoteAdConfigResult skipped(String reason) {
        return new RemoteAdConfigResult(null, reason);
    }

    boolean hasAd() {
        return config != null;
    }

    AdPlaybackConfig getConfig() {
        return config;
    }

    String getSkipReason() {
        return skipReason;
    }
}
