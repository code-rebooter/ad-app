package com.smart.android.adsdk.internal;

import com.smart.android.adsdk.AdError;

final class RemoteAdConfigResult {
    private final AdPlaybackConfig config;
    private final String skipReason;
    private final AdError error;

    private RemoteAdConfigResult(AdPlaybackConfig config, String skipReason) {
        this(config, skipReason, null);
    }

    private RemoteAdConfigResult(AdPlaybackConfig config, String skipReason, AdError error) {
        this.config = config;
        this.skipReason = skipReason;
        this.error = error;
    }

    static RemoteAdConfigResult withAd(AdPlaybackConfig config) {
        return new RemoteAdConfigResult(config, null);
    }

    static RemoteAdConfigResult skipped(String reason) {
        return new RemoteAdConfigResult(null, reason);
    }

    static RemoteAdConfigResult skipped(String reason, AdError error) {
        return new RemoteAdConfigResult(null, reason, error);
    }

    AdError getError() {
        return error;
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
