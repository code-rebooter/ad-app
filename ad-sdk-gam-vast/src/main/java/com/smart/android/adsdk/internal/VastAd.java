package com.smart.android.adsdk.internal;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class VastAd {
    private final String mediaUrl;
    private final String mediaType;
    private final List<VastMediaFile> mediaFiles;
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
    private final boolean unsupportedInteractiveCreative;
    private final boolean unexecutedInteractiveCreativeFile;

    VastAd(
        String mediaUrl,
        String mediaType,
        List<VastMediaFile> mediaFiles,
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
        this(
            mediaUrl,
            mediaType,
            mediaFiles,
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
            false,
            false
        );
    }

    VastAd(
        String mediaUrl,
        String mediaType,
        List<VastMediaFile> mediaFiles,
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
        this(
            mediaUrl,
            mediaType,
            mediaFiles,
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
            false,
            false
        );
    }

    VastAd(
        String mediaUrl,
        String mediaType,
        List<VastMediaFile> mediaFiles,
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
        boolean unsupportedInteractiveCreative
    ) {
        this(
            mediaUrl,
            mediaType,
            mediaFiles,
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
            unsupportedInteractiveCreative,
            false
        );
    }

    VastAd(
        String mediaUrl,
        String mediaType,
        List<VastMediaFile> mediaFiles,
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
        boolean unsupportedInteractiveCreative,
        boolean unexecutedInteractiveCreativeFile
    ) {
        this.mediaUrl = mediaUrl;
        this.mediaType = mediaType;
        this.mediaFiles = immutableCopy(mediaFiles);
        this.impressions = immutableCopy(impressions);
        this.creativeViewTrackers = immutableCopy(creativeViewTrackers);
        this.loadedTrackers = immutableCopy(loadedTrackers);
        this.startTrackers = immutableCopy(startTrackers);
        this.firstQuartileTrackers = immutableCopy(firstQuartileTrackers);
        this.midpointTrackers = immutableCopy(midpointTrackers);
        this.thirdQuartileTrackers = immutableCopy(thirdQuartileTrackers);
        this.completeTrackers = immutableCopy(completeTrackers);
        this.muteTrackers = immutableCopy(muteTrackers);
        this.unmuteTrackers = immutableCopy(unmuteTrackers);
        this.pauseTrackers = immutableCopy(pauseTrackers);
        this.resumeTrackers = immutableCopy(resumeTrackers);
        this.skipTrackers = immutableCopy(skipTrackers);
        this.progressTrackers = immutableCopy(progressTrackers);
        this.skipOffsetMs = skipOffsetMs;
        this.skipOffsetPercent = skipOffsetPercent;
        this.clickThroughUrl = clickThroughUrl;
        this.clickTrackingUrls = immutableCopy(clickTrackingUrls);
        this.customClickUrls = immutableCopy(customClickUrls);
        this.verificationResources = immutableCopy(verificationResources);
        this.verificationNotExecutedTrackers = immutableCopy(verificationNotExecutedTrackers);
        this.viewableTrackers = immutableCopy(viewableTrackers);
        this.notViewableTrackers = immutableCopy(notViewableTrackers);
        this.viewUndeterminedTrackers = immutableCopy(viewUndeterminedTrackers);
        this.errorTrackers = immutableCopy(errorTrackers);
        this.unsupportedInteractiveCreative = unsupportedInteractiveCreative;
        this.unexecutedInteractiveCreativeFile = unexecutedInteractiveCreativeFile;
    }

    String getMediaUrl() {
        return mediaUrl;
    }

    String getMediaType() {
        return mediaType;
    }

    List<VastMediaFile> getMediaFiles() {
        return mediaFiles;
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

    boolean hasUnsupportedInteractiveCreative() {
        return unsupportedInteractiveCreative;
    }

    boolean hasUnexecutedInteractiveCreativeFile() {
        return unexecutedInteractiveCreativeFile;
    }

    VastAd withAdditionalStartTrackers(List<String> additionalTrackers) {
        return copyWithTrackers(
            merge(additionalTrackers, startTrackers),
            completeTrackers
        );
    }

    VastAd withAdditionalCompleteTrackers(List<String> additionalTrackers) {
        return copyWithTrackers(
            startTrackers,
            merge(completeTrackers, additionalTrackers)
        );
    }

    private VastAd copyWithTrackers(
        List<String> newStartTrackers,
        List<String> newCompleteTrackers
    ) {
        return new VastAd(
            mediaUrl,
            mediaType,
            mediaFiles,
            impressions,
            creativeViewTrackers,
            loadedTrackers,
            newStartTrackers,
            firstQuartileTrackers,
            midpointTrackers,
            thirdQuartileTrackers,
            newCompleteTrackers,
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
            unsupportedInteractiveCreative,
            unexecutedInteractiveCreativeFile
        );
    }

    private static <T> List<T> immutableCopy(List<T> values) {
        if (values == null || values.isEmpty()) {
            return Collections.emptyList();
        }
        return Collections.unmodifiableList(new ArrayList<>(values));
    }

    private static <T> List<T> merge(List<T> first, List<T> second) {
        List<T> merged = new ArrayList<>();
        if (first != null) {
            merged.addAll(first);
        }
        if (second != null) {
            merged.addAll(second);
        }
        return merged;
    }
}
