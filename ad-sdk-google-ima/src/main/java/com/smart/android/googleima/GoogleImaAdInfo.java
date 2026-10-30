package com.smart.android.googleima;

/** Immutable identity and pod metadata for one concrete IMA ad instance. */
public final class GoogleImaAdInfo {
    private final String instanceId;
    private final String adId;
    private final String creativeId;
    private final String creativeAdId;
    private final String universalAdIdRegistry;
    private final String universalAdIdValue;
    private final long durationMs;
    private final long podMaxDurationMs;
    private final int totalAds;
    private final int adPosition;
    private final int podIndex;

    GoogleImaAdInfo(
            String instanceId,
            String adId,
            String creativeId,
            String creativeAdId,
            String universalAdIdRegistry,
            String universalAdIdValue,
            long durationMs,
            long podMaxDurationMs,
            int totalAds,
            int adPosition,
            int podIndex) {
        this.instanceId = safe(instanceId);
        this.adId = safe(adId);
        this.creativeId = safe(creativeId);
        this.creativeAdId = safe(creativeAdId);
        this.universalAdIdRegistry = safe(universalAdIdRegistry);
        this.universalAdIdValue = safe(universalAdIdValue);
        this.durationMs = durationMs;
        this.podMaxDurationMs = podMaxDurationMs;
        this.totalAds = totalAds;
        this.adPosition = adPosition;
        this.podIndex = podIndex;
    }

    public String getInstanceId() {
        return instanceId;
    }

    public String getAdId() {
        return adId;
    }

    public String getCreativeId() {
        return creativeId;
    }

    public String getCreativeAdId() {
        return creativeAdId;
    }

    public String getUniversalAdIdRegistry() {
        return universalAdIdRegistry;
    }

    public String getUniversalAdIdValue() {
        return universalAdIdValue;
    }

    public long getDurationMs() {
        return durationMs;
    }

    public long getPodMaxDurationMs() {
        return podMaxDurationMs;
    }

    public int getTotalAds() {
        return totalAds;
    }

    public int getAdPosition() {
        return adPosition;
    }

    public int getPodIndex() {
        return podIndex;
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
