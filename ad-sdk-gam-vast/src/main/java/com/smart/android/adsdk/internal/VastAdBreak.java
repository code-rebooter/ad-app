package com.smart.android.adsdk.internal;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class VastAdBreak {
    private final List<VastAd> ads;
    private final List<String> breakStartTrackers;
    private final List<String> breakEndTrackers;
    private final List<String> breakErrorTrackers;

    VastAdBreak(List<VastAd> ads) {
        this(ads, Collections.emptyList(), Collections.emptyList(), Collections.emptyList());
    }

    VastAdBreak(
        List<VastAd> ads,
        List<String> breakStartTrackers,
        List<String> breakEndTrackers,
        List<String> breakErrorTrackers
    ) {
        if (ads == null || ads.isEmpty()) {
            this.ads = Collections.emptyList();
        } else {
            this.ads = Collections.unmodifiableList(new ArrayList<>(ads));
        }
        this.breakStartTrackers = immutableCopy(breakStartTrackers);
        this.breakEndTrackers = immutableCopy(breakEndTrackers);
        this.breakErrorTrackers = immutableCopy(breakErrorTrackers);
    }

    List<VastAd> getAds() {
        return ads;
    }

    VastAd firstAd() {
        return ads.isEmpty() ? null : ads.get(0);
    }

    List<String> getBreakStartTrackers() {
        return breakStartTrackers;
    }

    List<String> getBreakEndTrackers() {
        return breakEndTrackers;
    }

    List<String> getBreakErrorTrackers() {
        return breakErrorTrackers;
    }

    private static <T> List<T> immutableCopy(List<T> values) {
        if (values == null || values.isEmpty()) {
            return Collections.emptyList();
        }
        return Collections.unmodifiableList(new ArrayList<>(values));
    }
}
