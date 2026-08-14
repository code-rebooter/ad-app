package com.smart.android.adsdk.internal;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class VmapAdBreak {
    private final VmapTimeOffset timeOffset;
    private final String breakId;
    private final String breakType;
    private final String adTagUrl;
    private final VastParsedResponse inlineVast;
    private final List<String> breakStartTrackers;
    private final List<String> breakEndTrackers;
    private final List<String> errorTrackers;

    VmapAdBreak(
        VmapTimeOffset timeOffset,
        String breakId,
        String breakType,
        String adTagUrl,
        VastParsedResponse inlineVast,
        List<String> breakStartTrackers,
        List<String> breakEndTrackers,
        List<String> errorTrackers
    ) {
        this.timeOffset = timeOffset;
        this.breakId = breakId;
        this.breakType = breakType;
        this.adTagUrl = adTagUrl;
        this.inlineVast = inlineVast;
        this.breakStartTrackers = immutableCopy(breakStartTrackers);
        this.breakEndTrackers = immutableCopy(breakEndTrackers);
        this.errorTrackers = immutableCopy(errorTrackers);
    }

    VmapTimeOffset getTimeOffset() {
        return timeOffset;
    }

    String getBreakId() {
        return breakId;
    }

    String getBreakType() {
        return breakType;
    }

    String getAdTagUrl() {
        return adTagUrl;
    }

    VastParsedResponse getInlineVast() {
        return inlineVast;
    }

    List<String> getBreakStartTrackers() {
        return breakStartTrackers;
    }

    List<String> getBreakEndTrackers() {
        return breakEndTrackers;
    }

    List<String> getErrorTrackers() {
        return errorTrackers;
    }

    boolean hasPlayableAdSource() {
        return (adTagUrl != null && !adTagUrl.isEmpty())
            || (inlineVast != null && (inlineVast.hasInlineAd() || inlineVast.hasWrapper()));
    }

    private static <T> List<T> immutableCopy(List<T> values) {
        if (values == null || values.isEmpty()) {
            return Collections.emptyList();
        }
        return Collections.unmodifiableList(new ArrayList<>(values));
    }
}
