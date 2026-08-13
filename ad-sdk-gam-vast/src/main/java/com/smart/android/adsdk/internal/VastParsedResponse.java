package com.smart.android.adsdk.internal;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class VastParsedResponse {
    private final VastAd ad;
    private final String wrapperUrl;
    private final List<String> impressions;
    private final List<String> creativeViewTrackers;
    private final List<String> startTrackers;
    private final List<String> firstQuartileTrackers;
    private final List<String> midpointTrackers;
    private final List<String> thirdQuartileTrackers;
    private final List<String> completeTrackers;
    private final List<String> muteTrackers;
    private final List<String> unmuteTrackers;
    private final List<String> pauseTrackers;
    private final List<String> resumeTrackers;
    private final List<VastProgressTracker> progressTrackers;
    private final List<String> errorTrackers;

    private VastParsedResponse(
        VastAd ad,
        String wrapperUrl,
        List<String> impressions,
        List<String> creativeViewTrackers,
        List<String> startTrackers,
        List<String> firstQuartileTrackers,
        List<String> midpointTrackers,
        List<String> thirdQuartileTrackers,
        List<String> completeTrackers,
        List<String> muteTrackers,
        List<String> unmuteTrackers,
        List<String> pauseTrackers,
        List<String> resumeTrackers,
        List<VastProgressTracker> progressTrackers,
        List<String> errorTrackers
    ) {
        this.ad = ad;
        this.wrapperUrl = wrapperUrl;
        this.impressions = immutable(impressions);
        this.creativeViewTrackers = immutable(creativeViewTrackers);
        this.startTrackers = immutable(startTrackers);
        this.firstQuartileTrackers = immutable(firstQuartileTrackers);
        this.midpointTrackers = immutable(midpointTrackers);
        this.thirdQuartileTrackers = immutable(thirdQuartileTrackers);
        this.completeTrackers = immutable(completeTrackers);
        this.muteTrackers = immutable(muteTrackers);
        this.unmuteTrackers = immutable(unmuteTrackers);
        this.pauseTrackers = immutable(pauseTrackers);
        this.resumeTrackers = immutable(resumeTrackers);
        this.progressTrackers = immutable(progressTrackers);
        this.errorTrackers = immutable(errorTrackers);
    }

    static VastParsedResponse inline(VastAd ad) {
        return new VastParsedResponse(
            ad,
            null,
            Collections.emptyList(),
            Collections.emptyList(),
            Collections.emptyList(),
            Collections.emptyList(),
            Collections.emptyList(),
            Collections.emptyList(),
            Collections.emptyList(),
            Collections.emptyList(),
            Collections.emptyList(),
            Collections.emptyList(),
            Collections.emptyList(),
            Collections.emptyList(),
            Collections.emptyList()
        );
    }

    static VastParsedResponse wrapper(
        String wrapperUrl,
        List<String> impressions,
        List<String> creativeViewTrackers,
        List<String> startTrackers,
        List<String> firstQuartileTrackers,
        List<String> midpointTrackers,
        List<String> thirdQuartileTrackers,
        List<String> completeTrackers,
        List<String> muteTrackers,
        List<String> unmuteTrackers,
        List<String> pauseTrackers,
        List<String> resumeTrackers,
        List<VastProgressTracker> progressTrackers,
        List<String> errorTrackers
    ) {
        return new VastParsedResponse(
            null,
            wrapperUrl,
            impressions,
            creativeViewTrackers,
            startTrackers,
            firstQuartileTrackers,
            midpointTrackers,
            thirdQuartileTrackers,
            completeTrackers,
            muteTrackers,
            unmuteTrackers,
            pauseTrackers,
            resumeTrackers,
            progressTrackers,
            errorTrackers
        );
    }

    boolean hasInlineAd() {
        return ad != null;
    }

    boolean hasWrapper() {
        return wrapperUrl != null && !wrapperUrl.isEmpty();
    }

    VastAd getAd() {
        return ad;
    }

    String getWrapperUrl() {
        return wrapperUrl;
    }

    List<String> getImpressions() {
        return impressions;
    }

    List<String> getCreativeViewTrackers() {
        return creativeViewTrackers;
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

    List<String> getMuteTrackers() {
        return muteTrackers;
    }

    List<String> getUnmuteTrackers() {
        return unmuteTrackers;
    }

    List<String> getPauseTrackers() {
        return pauseTrackers;
    }

    List<String> getResumeTrackers() {
        return resumeTrackers;
    }

    List<VastProgressTracker> getProgressTrackers() {
        return progressTrackers;
    }

    List<String> getErrorTrackers() {
        return errorTrackers;
    }

    private static <T> List<T> immutable(List<T> values) {
        return values == null || values.isEmpty()
            ? Collections.emptyList()
            : Collections.unmodifiableList(new ArrayList<>(values));
    }
}
