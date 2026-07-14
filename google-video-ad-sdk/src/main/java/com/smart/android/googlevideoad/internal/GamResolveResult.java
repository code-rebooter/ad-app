package com.smart.android.googlevideoad.internal;

final class GamResolveResult {
    private final GamPlaybackConfig config;
    private final String skipReason;

    private GamResolveResult(GamPlaybackConfig config, String skipReason) {
        this.config = config;
        this.skipReason = skipReason;
    }

    static GamResolveResult withAd(GamPlaybackConfig config) {
        return new GamResolveResult(config, null);
    }

    static GamResolveResult skipped(String reason) {
        return new GamResolveResult(null, reason);
    }

    boolean hasAd() {
        return config != null;
    }

    GamPlaybackConfig getConfig() {
        return config;
    }

    String getSkipReason() {
        return skipReason;
    }
}
