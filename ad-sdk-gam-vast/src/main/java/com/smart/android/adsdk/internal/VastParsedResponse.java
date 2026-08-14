package com.smart.android.adsdk.internal;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class VastParsedResponse {
    private final VastAd ad;
    private final VastAdBreak adBreak;
    private final List<VastAdSource> adSources;
    private final String wrapperUrl;
    private final List<String> impressions;
    private final List<String> creativeViewTrackers;
    private final List<String> loadedTrackers;
    private final List<String> startTrackers;
    private final List<String> firstQuartileTrackers;
    private final List<String> midpointTrackers;
    private final List<String> thirdQuartileTrackers;
    private final List<String> completeTrackers;
    private final List<String> muteTrackers;
    private final List<String> unmuteTrackers;
    private final List<String> pauseTrackers;
    private final List<String> resumeTrackers;
    private final List<String> skipTrackers;
    private final List<VastProgressTracker> progressTrackers;
    private final long skipOffsetMs;
    private final float skipOffsetPercent;
    private final String clickThroughUrl;
    private final List<String> clickTrackingUrls;
    private final List<String> customClickUrls;
    private final List<VastVerificationResource> verificationResources;
    private final List<String> verificationNotExecutedTrackers;
    private final List<String> viewableTrackers;
    private final List<String> notViewableTrackers;
    private final List<String> viewUndeterminedTrackers;
    private final List<String> errorTrackers;
    private final boolean followAdditionalWrappers;
    private final boolean allowMultipleAds;

    private VastParsedResponse(
        VastAd ad,
        VastAdBreak adBreak,
        List<VastAdSource> adSources,
        String wrapperUrl,
        List<String> impressions,
        List<String> creativeViewTrackers,
        List<String> loadedTrackers,
        List<String> startTrackers,
        List<String> firstQuartileTrackers,
        List<String> midpointTrackers,
        List<String> thirdQuartileTrackers,
        List<String> completeTrackers,
        List<String> muteTrackers,
        List<String> unmuteTrackers,
        List<String> pauseTrackers,
        List<String> resumeTrackers,
        List<String> skipTrackers,
        List<VastProgressTracker> progressTrackers,
        long skipOffsetMs,
        float skipOffsetPercent,
        String clickThroughUrl,
        List<String> clickTrackingUrls,
        List<String> customClickUrls,
        List<VastVerificationResource> verificationResources,
        List<String> verificationNotExecutedTrackers,
        List<String> viewableTrackers,
        List<String> notViewableTrackers,
        List<String> viewUndeterminedTrackers,
        List<String> errorTrackers,
        boolean followAdditionalWrappers,
        boolean allowMultipleAds
    ) {
        this.ad = ad;
        this.adBreak = adBreak;
        this.adSources = immutable(adSources);
        this.wrapperUrl = wrapperUrl;
        this.impressions = immutable(impressions);
        this.creativeViewTrackers = immutable(creativeViewTrackers);
        this.loadedTrackers = immutable(loadedTrackers);
        this.startTrackers = immutable(startTrackers);
        this.firstQuartileTrackers = immutable(firstQuartileTrackers);
        this.midpointTrackers = immutable(midpointTrackers);
        this.thirdQuartileTrackers = immutable(thirdQuartileTrackers);
        this.completeTrackers = immutable(completeTrackers);
        this.muteTrackers = immutable(muteTrackers);
        this.unmuteTrackers = immutable(unmuteTrackers);
        this.pauseTrackers = immutable(pauseTrackers);
        this.resumeTrackers = immutable(resumeTrackers);
        this.skipTrackers = immutable(skipTrackers);
        this.progressTrackers = immutable(progressTrackers);
        this.skipOffsetMs = skipOffsetMs;
        this.skipOffsetPercent = skipOffsetPercent;
        this.clickThroughUrl = clickThroughUrl;
        this.clickTrackingUrls = immutable(clickTrackingUrls);
        this.customClickUrls = immutable(customClickUrls);
        this.verificationResources = immutable(verificationResources);
        this.verificationNotExecutedTrackers = immutable(verificationNotExecutedTrackers);
        this.viewableTrackers = immutable(viewableTrackers);
        this.notViewableTrackers = immutable(notViewableTrackers);
        this.viewUndeterminedTrackers = immutable(viewUndeterminedTrackers);
        this.errorTrackers = immutable(errorTrackers);
        this.followAdditionalWrappers = followAdditionalWrappers;
        this.allowMultipleAds = allowMultipleAds;
    }

    static VastParsedResponse inline(VastAd ad) {
        return inline(new VastAdBreak(Collections.singletonList(ad)));
    }

    static VastParsedResponse inline(VastAdBreak adBreak) {
        return new VastParsedResponse(
            adBreak == null ? null : adBreak.firstAd(),
            adBreak,
            Collections.emptyList(),
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
            Collections.emptyList(),
            Collections.emptyList(),
            -1L,
            -1f,
            null,
            Collections.emptyList(),
            Collections.emptyList(),
            Collections.emptyList(),
            Collections.emptyList(),
            Collections.emptyList(),
            Collections.emptyList(),
            Collections.emptyList(),
            Collections.emptyList(),
            true,
            false
        );
    }

    static VastParsedResponse wrapper(
        String wrapperUrl,
        List<String> impressions,
        List<String> creativeViewTrackers,
        List<String> loadedTrackers,
        List<String> startTrackers,
        List<String> firstQuartileTrackers,
        List<String> midpointTrackers,
        List<String> thirdQuartileTrackers,
        List<String> completeTrackers,
        List<String> muteTrackers,
        List<String> unmuteTrackers,
        List<String> pauseTrackers,
        List<String> resumeTrackers,
        List<String> skipTrackers,
        List<VastProgressTracker> progressTrackers,
        long skipOffsetMs,
        float skipOffsetPercent,
        String clickThroughUrl,
        List<String> clickTrackingUrls,
        List<String> customClickUrls,
        List<VastVerificationResource> verificationResources,
        List<String> verificationNotExecutedTrackers,
        List<String> viewableTrackers,
        List<String> notViewableTrackers,
        List<String> viewUndeterminedTrackers,
        List<String> errorTrackers
    ) {
        return wrapper(
            wrapperUrl,
            impressions,
            creativeViewTrackers,
            loadedTrackers,
            startTrackers,
            firstQuartileTrackers,
            midpointTrackers,
            thirdQuartileTrackers,
            completeTrackers,
            muteTrackers,
            unmuteTrackers,
            pauseTrackers,
            resumeTrackers,
            skipTrackers,
            progressTrackers,
            skipOffsetMs,
            skipOffsetPercent,
            clickThroughUrl,
            clickTrackingUrls,
            customClickUrls,
            verificationResources,
            verificationNotExecutedTrackers,
            viewableTrackers,
            notViewableTrackers,
            viewUndeterminedTrackers,
            errorTrackers,
            true,
            false
        );
    }

    static VastParsedResponse wrapper(
        String wrapperUrl,
        List<String> impressions,
        List<String> creativeViewTrackers,
        List<String> loadedTrackers,
        List<String> startTrackers,
        List<String> firstQuartileTrackers,
        List<String> midpointTrackers,
        List<String> thirdQuartileTrackers,
        List<String> completeTrackers,
        List<String> muteTrackers,
        List<String> unmuteTrackers,
        List<String> pauseTrackers,
        List<String> resumeTrackers,
        List<String> skipTrackers,
        List<VastProgressTracker> progressTrackers,
        long skipOffsetMs,
        float skipOffsetPercent,
        String clickThroughUrl,
        List<String> clickTrackingUrls,
        List<String> customClickUrls,
        List<VastVerificationResource> verificationResources,
        List<String> verificationNotExecutedTrackers,
        List<String> viewableTrackers,
        List<String> notViewableTrackers,
        List<String> viewUndeterminedTrackers,
        List<String> errorTrackers,
        boolean followAdditionalWrappers,
        boolean allowMultipleAds
    ) {
        return new VastParsedResponse(
            null,
            null,
            Collections.emptyList(),
            wrapperUrl,
            impressions,
            creativeViewTrackers,
            loadedTrackers,
            startTrackers,
            firstQuartileTrackers,
            midpointTrackers,
            thirdQuartileTrackers,
            completeTrackers,
            muteTrackers,
            unmuteTrackers,
            pauseTrackers,
            resumeTrackers,
            skipTrackers,
            progressTrackers,
            skipOffsetMs,
            skipOffsetPercent,
            clickThroughUrl,
            clickTrackingUrls,
            customClickUrls,
            verificationResources,
            verificationNotExecutedTrackers,
            viewableTrackers,
            notViewableTrackers,
            viewUndeterminedTrackers,
            errorTrackers,
            followAdditionalWrappers,
            allowMultipleAds
        );
    }

    static VastParsedResponse sequencedSources(List<VastAdSource> adSources) {
        List<VastAd> ads = new ArrayList<>();
        if (adSources != null) {
            for (VastAdSource source : adSources) {
                if (source == null || source.getResponse() == null) {
                    continue;
                }
                VastParsedResponse response = source.getResponse();
                if (response.getAdBreak() != null && !response.getAdBreak().getAds().isEmpty()) {
                    ads.addAll(response.getAdBreak().getAds());
                } else if (response.getAd() != null) {
                    ads.add(response.getAd());
                }
            }
        }
        return new VastParsedResponse(
            ads.isEmpty() ? null : ads.get(0),
            ads.isEmpty() ? null : new VastAdBreak(ads),
            adSources,
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
            Collections.emptyList(),
            Collections.emptyList(),
            -1L,
            -1f,
            null,
            Collections.emptyList(),
            Collections.emptyList(),
            Collections.emptyList(),
            Collections.emptyList(),
            Collections.emptyList(),
            Collections.emptyList(),
            Collections.emptyList(),
            Collections.emptyList(),
            true,
            false
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
        return wrapper(
            wrapperUrl,
            impressions,
            creativeViewTrackers,
            Collections.emptyList(),
            startTrackers,
            firstQuartileTrackers,
            midpointTrackers,
            thirdQuartileTrackers,
            completeTrackers,
            muteTrackers,
            unmuteTrackers,
            pauseTrackers,
            resumeTrackers,
            Collections.emptyList(),
            progressTrackers,
            -1L,
            -1f,
            null,
            Collections.emptyList(),
            Collections.emptyList(),
            Collections.emptyList(),
            Collections.emptyList(),
            Collections.emptyList(),
            Collections.emptyList(),
            Collections.emptyList(),
            errorTrackers,
            true,
            false
        );
    }

    boolean hasInlineAd() {
        return adBreak != null && !adBreak.getAds().isEmpty();
    }

    boolean hasSequencedSources() {
        return !adSources.isEmpty();
    }

    List<VastAdSource> getSequencedAdSources() {
        return adSources;
    }

    boolean hasWrapper() {
        return wrapperUrl != null && !wrapperUrl.isEmpty();
    }

    VastAd getAd() {
        return ad;
    }

    VastAdBreak getAdBreak() {
        return adBreak;
    }

    List<VastAdSource> getAdSources() {
        return adSources;
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

    List<String> getLoadedTrackers() {
        return loadedTrackers;
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

    List<String> getSkipTrackers() {
        return skipTrackers;
    }

    List<VastProgressTracker> getProgressTrackers() {
        return progressTrackers;
    }

    long getSkipOffsetMs() {
        return skipOffsetMs;
    }

    float getSkipOffsetPercent() {
        return skipOffsetPercent;
    }

    String getClickThroughUrl() {
        return clickThroughUrl;
    }

    List<String> getClickTrackingUrls() {
        return clickTrackingUrls;
    }

    List<String> getCustomClickUrls() {
        return customClickUrls;
    }

    List<VastVerificationResource> getVerificationResources() {
        return verificationResources;
    }

    List<String> getVerificationNotExecutedTrackers() {
        return verificationNotExecutedTrackers;
    }

    List<String> getViewableTrackers() {
        return viewableTrackers;
    }

    List<String> getNotViewableTrackers() {
        return notViewableTrackers;
    }

    List<String> getViewUndeterminedTrackers() {
        return viewUndeterminedTrackers;
    }

    List<String> getErrorTrackers() {
        return errorTrackers;
    }

    boolean shouldFollowAdditionalWrappers() {
        return followAdditionalWrappers;
    }

    boolean allowsMultipleAds() {
        return allowMultipleAds;
    }

    private static <T> List<T> immutable(List<T> values) {
        return values == null || values.isEmpty()
            ? Collections.emptyList()
            : Collections.unmodifiableList(new ArrayList<>(values));
    }
}
