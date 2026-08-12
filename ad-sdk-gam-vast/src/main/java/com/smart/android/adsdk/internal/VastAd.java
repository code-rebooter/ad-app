package com.smart.android.adsdk.internal;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class VastAd {
    private final String mediaUrl;
    private final List<String> impressions;
    private final List<String> startTrackers;
    private final List<String> firstQuartileTrackers;
    private final List<String> midpointTrackers;
    private final List<String> thirdQuartileTrackers;
    private final List<String> completeTrackers;
    private final List<String> errorTrackers;

    VastAd(
        String mediaUrl,
        List<String> impressions,
        List<String> startTrackers,
        List<String> firstQuartileTrackers,
        List<String> midpointTrackers,
        List<String> thirdQuartileTrackers,
        List<String> completeTrackers,
        List<String> errorTrackers
    ) {
        this.mediaUrl = mediaUrl;
        this.impressions = immutableCopy(impressions);
        this.startTrackers = immutableCopy(startTrackers);
        this.firstQuartileTrackers = immutableCopy(firstQuartileTrackers);
        this.midpointTrackers = immutableCopy(midpointTrackers);
        this.thirdQuartileTrackers = immutableCopy(thirdQuartileTrackers);
        this.completeTrackers = immutableCopy(completeTrackers);
        this.errorTrackers = immutableCopy(errorTrackers);
    }

    String getMediaUrl() {
        return mediaUrl;
    }

    List<String> getImpressions() {
        return impressions;
    }

    List<String> getStartTrackers() {
        return startTrackers;
    }

    List<String> getFirstQuartileTrackers() {
        return firstQuartileTrackers;
    }

    List<String> getMidpointTrackers() {
        return midpointTrackers;
    }

    List<String> getThirdQuartileTrackers() {
        return thirdQuartileTrackers;
    }

    List<String> getCompleteTrackers() {
        return completeTrackers;
    }

    List<String> getErrorTrackers() {
        return errorTrackers;
    }

    private static List<String> immutableCopy(List<String> values) {
        if (values == null || values.isEmpty()) {
            return Collections.emptyList();
        }
        return Collections.unmodifiableList(new ArrayList<>(values));
    }
}
