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
        this.mediaUrl = mediaUrl;
        this.mediaType = mediaType;
        this.mediaFiles = immutableCopy(mediaFiles);
        this.impressions = immutableCopy(impressions);
        this.creativeViewTrackers = immutableCopy(creativeViewTrackers);
        this.startTrackers = immutableCopy(startTrackers);
        this.firstQuartileTrackers = immutableCopy(firstQuartileTrackers);
        this.midpointTrackers = immutableCopy(midpointTrackers);
        this.thirdQuartileTrackers = immutableCopy(thirdQuartileTrackers);
        this.completeTrackers = immutableCopy(completeTrackers);
        this.muteTrackers = immutableCopy(muteTrackers);
        this.unmuteTrackers = immutableCopy(unmuteTrackers);
        this.pauseTrackers = immutableCopy(pauseTrackers);
        this.resumeTrackers = immutableCopy(resumeTrackers);
        this.progressTrackers = immutableCopy(progressTrackers);
        this.errorTrackers = immutableCopy(errorTrackers);
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

    private static <T> List<T> immutableCopy(List<T> values) {
        if (values == null || values.isEmpty()) {
            return Collections.emptyList();
        }
        return Collections.unmodifiableList(new ArrayList<>(values));
    }
}
