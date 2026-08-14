package com.smart.android.adsdk.internal;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import okhttp3.Call;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

final class VastClient {
    private static final int MAX_WRAPPER_DEPTH = 5;

    private final OkHttpClient okHttpClient;
    private final VastParser parser = new VastParser();
    private final VmapParser vmapParser = new VmapParser();

    VastClient(OkHttpClient okHttpClient) {
        this.okHttpClient = okHttpClient;
    }

    Cancellable load(String url, int timeoutMs, Callback callback) {
        LoadOperation operation = new LoadOperation(timeoutMs, callback);
        operation.load(url, 0, new TrackingBundle());
        return operation;
    }

    interface Callback {
        void onLoaded(VastAdBreak adBreak);

        void onError(VastLoadException error);
    }

    private final class LoadOperation implements Cancellable {
        private final int timeoutMs;
        private final Callback callback;
        private volatile boolean cancelled;
        private volatile Call activeCall;

        LoadOperation(int timeoutMs, Callback callback) {
            this.timeoutMs = timeoutMs;
            this.callback = callback;
        }

        void load(String url, int depth, TrackingBundle inherited) {
            loadInternal(url, depth, inherited, new InternalCallback() {
                @Override
                public void onLoaded(VastAdBreak adBreak) {
                    callback.onLoaded(adBreak);
                }

                @Override
                public void onError(VastLoadException error) {
                    callback.onError(error);
                }
            });
        }

        private void loadInternal(
            String url,
            int depth,
            TrackingBundle inherited,
            InternalCallback internalCallback
        ) {
            if (cancelled) {
                return;
            }
            if (depth > MAX_WRAPPER_DEPTH) {
                internalCallback.onError(inherited.toError(new VastLoadException(
                    "VAST wrapper depth exceeded " + MAX_WRAPPER_DEPTH,
                    302,
                    java.util.Collections.emptyList()
                )));
                return;
            }
            HttpUrl httpUrl = HttpUrl.parse(url);
            if (httpUrl == null) {
                internalCallback.onError(inherited.toError(new VastLoadException(
                    "Invalid VAST URL: " + url,
                    301,
                    java.util.Collections.emptyList()
                )));
                return;
            }
            OkHttpClient requestClient = okHttpClient.newBuilder()
                .callTimeout(Math.max(1_000, timeoutMs), TimeUnit.MILLISECONDS)
                .build();
            Request request = new Request.Builder().url(httpUrl).get().build();
            Call call = requestClient.newCall(request);
            activeCall = call;
            call.enqueue(new okhttp3.Callback() {
                @Override
                public void onFailure(Call call, IOException error) {
                    if (!cancelled) {
                        internalCallback.onError(inherited.toError(new VastLoadException(
                            "VAST request failed",
                            error,
                            301,
                            java.util.Collections.emptyList()
                        )));
                    }
                }

                @Override
                public void onResponse(Call call, Response response) {
                    try (Response closeableResponse = response) {
                        if (cancelled) {
                            return;
                        }
                        if (!closeableResponse.isSuccessful()) {
                            internalCallback.onError(inherited.toError(new VastLoadException(
                                "VAST HTTP status " + closeableResponse.code(),
                                301,
                                java.util.Collections.emptyList()
                            )));
                            return;
                        }
                        ResponseBody body = closeableResponse.body();
                        if (body == null) {
                            internalCallback.onError(inherited.toError(new VastLoadException(
                                "VAST response body was empty",
                                100,
                                java.util.Collections.emptyList()
                            )));
                            return;
                        }
                        String responseBody = body.string();
                        if (isVmap(responseBody)) {
                            loadVmap(httpUrl, responseBody, depth, inherited, internalCallback);
                            return;
                        }
                        VastParsedResponse parsed = parser.parse(responseBody);
                        handleParsedResponse(httpUrl, parsed, depth, inherited, internalCallback);
                    } catch (IOException | VastLoadException error) {
                        if (!cancelled) {
                            VastLoadException vastError = error instanceof VastLoadException
                                ? (VastLoadException) error
                                : new VastLoadException(
                                    "Unable to read VAST response",
                                    error,
                                    301,
                                    java.util.Collections.emptyList()
                                );
                            internalCallback.onError(inherited.toError(vastError));
                        }
                    }
                }
            });
        }

        private void handleParsedResponse(
            HttpUrl baseUrl,
            VastParsedResponse parsed,
            int depth,
            TrackingBundle inherited,
            InternalCallback internalCallback
        ) {
            if (parsed.hasSequencedSources()) {
                if (inherited.hasWrapperLayers() && !inherited.allowsMultipleAds()) {
                    internalCallback.onError(inherited.toError(new VastLoadException(
                        "VAST wrapper resolved to an ad pod, which is not supported",
                        303,
                        java.util.Collections.emptyList()
                    )));
                    return;
                }
                loadSequencedSources(
                    baseUrl,
                    parsed.getSequencedAdSources(),
                    0,
                    depth,
                    inherited,
                    new ArrayList<>(),
                    internalCallback
                );
            } else if (parsed.hasInlineAd()) {
                if (inherited.hasWrapperLayers()
                    && parsed.getAdBreak().getAds().size() > 1
                    && !inherited.allowsMultipleAds()) {
                    internalCallback.onError(inherited.toError(new VastLoadException(
                        "VAST wrapper resolved to an ad pod, which is not supported",
                        303,
                        java.util.Collections.emptyList()
                    )));
                    return;
                }
                internalCallback.onLoaded(inherited.toAdBreak(parsed.getAdBreak()));
            } else if (parsed.hasWrapper()) {
                loadWrapper(baseUrl, parsed, depth, inherited, internalCallback);
            } else {
                internalCallback.onError(inherited.toError(new VastLoadException("VAST response was not playable")));
            }
        }

        private void loadWrapper(
            HttpUrl baseUrl,
            VastParsedResponse wrapper,
            int depth,
            TrackingBundle inherited,
            InternalCallback internalCallback
        ) {
            TrackingBundle merged = inherited.copy().merge(wrapper);
            String nextUrl = resolveNextUrl(baseUrl, wrapper.getWrapperUrl());
            if (nextUrl == null) {
                internalCallback.onError(merged.toError(new VastLoadException(
                    "VAST wrapper URL was invalid",
                    300,
                    java.util.Collections.emptyList()
                )));
            } else if (depth > 0 && !inherited.shouldFollowAdditionalWrappers()) {
                internalCallback.onError(merged.toError(new VastLoadException(
                    "VAST wrapper disallowed additional wrappers",
                    302,
                    java.util.Collections.emptyList()
                )));
            } else {
                loadInternal(nextUrl, depth + 1, merged, internalCallback);
            }
        }

        private void loadSequencedSources(
            HttpUrl baseUrl,
            List<VastAdSource> sources,
            int index,
            int depth,
            TrackingBundle inherited,
            List<VastAd> resolvedAds,
            InternalCallback internalCallback
        ) {
            if (cancelled) {
                return;
            }
            if (index >= sources.size()) {
                if (resolvedAds.isEmpty()) {
                    internalCallback.onError(inherited.toError(new VastLoadException(
                        "VAST ad pod did not resolve to playable ads",
                        303,
                        java.util.Collections.emptyList()
                    )));
                    return;
                }
                internalCallback.onLoaded(inherited.toBreakWithTrackers(resolvedAds));
                return;
            }
            VastAdSource source = sources.get(index);
            if (source == null || source.getResponse() == null) {
                loadSequencedSources(
                    baseUrl,
                    sources,
                    index + 1,
                    depth,
                    inherited,
                    resolvedAds,
                    internalCallback
                );
                return;
            }
            handleParsedResponse(
                baseUrl,
                source.getResponse(),
                depth,
                inherited.copy(),
                new InternalCallback() {
                    @Override
                    public void onLoaded(VastAdBreak adBreak) {
                        resolvedAds.addAll(adBreak.getAds());
                        loadSequencedSources(
                            baseUrl,
                            sources,
                            index + 1,
                            depth,
                            inherited,
                            resolvedAds,
                            internalCallback
                        );
                    }

                    @Override
                    public void onError(VastLoadException error) {
                        internalCallback.onError(error);
                    }
                }
            );
        }

        private void loadVmap(
            HttpUrl baseUrl,
            String responseBody,
            int depth,
            TrackingBundle inherited,
            InternalCallback internalCallback
        ) throws VastLoadException {
            VmapAdSchedule schedule = vmapParser.parse(responseBody);
            VmapAdBreak adBreak = firstPlayableBreak(schedule);
            if (adBreak == null) {
                internalCallback.onError(inherited.toError(new VastLoadException(
                    "VMAP response contained no playable ad break",
                    303,
                    java.util.Collections.emptyList()
                )));
                return;
            }
            TrackingBundle merged = inherited.merge(adBreak);
            if (adBreak.getInlineVast() != null) {
                VastParsedResponse inlineVast = adBreak.getInlineVast();
                handleParsedResponse(baseUrl, inlineVast, depth, merged, internalCallback);
                return;
            }
            String nextUrl = resolveNextUrl(baseUrl, adBreak.getAdTagUrl());
            if (nextUrl == null) {
                internalCallback.onError(merged.toError(new VastLoadException(
                    "VMAP ad tag URL was invalid",
                    300,
                    java.util.Collections.emptyList()
                )));
                return;
            }
            loadInternal(nextUrl, depth + 1, merged, internalCallback);
        }

        private VmapAdBreak firstPlayableBreak(VmapAdSchedule schedule) {
            for (VmapAdBreak adBreak : schedule.getBreaks()) {
                if (adBreak.hasPlayableAdSource()
                    && adBreak.getTimeOffset().getType() == VmapTimeOffset.Type.START) {
                    return adBreak;
                }
            }
            return null;
        }

        private boolean isVmap(String body) {
            String normalized = body == null ? "" : body.trim();
            return normalized.startsWith("<VMAP")
                || normalized.startsWith("<vmap:VMAP")
                || normalized.contains("<VMAP")
                || normalized.contains("<vmap:VMAP");
        }

        @Override
        public void cancel() {
            cancelled = true;
            Call call = activeCall;
            if (call != null) {
                call.cancel();
            }
        }
    }

    private interface InternalCallback {
        void onLoaded(VastAdBreak adBreak);

        void onError(VastLoadException error);
    }

    private String resolveNextUrl(HttpUrl baseUrl, String value) {
        HttpUrl resolved = baseUrl.resolve(value);
        return resolved == null ? null : resolved.toString();
    }

    static final class TrackingBundle {
        private final List<String> impressions = new ArrayList<>();
        private final List<String> creativeView = new ArrayList<>();
        private final List<String> loaded = new ArrayList<>();
        private final List<String> start = new ArrayList<>();
        private final List<String> firstQuartile = new ArrayList<>();
        private final List<String> midpoint = new ArrayList<>();
        private final List<String> thirdQuartile = new ArrayList<>();
        private final List<String> complete = new ArrayList<>();
        private final List<String> mute = new ArrayList<>();
        private final List<String> unmute = new ArrayList<>();
        private final List<String> pause = new ArrayList<>();
        private final List<String> resume = new ArrayList<>();
        private final List<String> skip = new ArrayList<>();
        private final List<VastProgressTracker> progress = new ArrayList<>();
        private final List<String> clickTracking = new ArrayList<>();
        private final List<String> customClick = new ArrayList<>();
        private final List<VastVerificationResource> verificationResources = new ArrayList<>();
        private final List<String> verificationNotExecuted = new ArrayList<>();
        private final List<String> viewable = new ArrayList<>();
        private final List<String> notViewable = new ArrayList<>();
        private final List<String> viewUndetermined = new ArrayList<>();
        private final List<String> errors = new ArrayList<>();
        private final List<String> breakStart = new ArrayList<>();
        private final List<String> breakEnd = new ArrayList<>();
        private final List<String> breakError = new ArrayList<>();
        private long skipOffsetMs = -1L;
        private float skipOffsetPercent = -1f;
        private String clickThroughUrl;
        private int wrapperLayerCount;
        private boolean followAdditionalWrappers = true;
        private boolean allowMultipleAds = true;

        TrackingBundle copy() {
            TrackingBundle copy = new TrackingBundle();
            copy.impressions.addAll(impressions);
            copy.creativeView.addAll(creativeView);
            copy.loaded.addAll(loaded);
            copy.start.addAll(start);
            copy.firstQuartile.addAll(firstQuartile);
            copy.midpoint.addAll(midpoint);
            copy.thirdQuartile.addAll(thirdQuartile);
            copy.complete.addAll(complete);
            copy.mute.addAll(mute);
            copy.unmute.addAll(unmute);
            copy.pause.addAll(pause);
            copy.resume.addAll(resume);
            copy.skip.addAll(skip);
            copy.progress.addAll(progress);
            copy.clickTracking.addAll(clickTracking);
            copy.customClick.addAll(customClick);
            copy.verificationResources.addAll(verificationResources);
            copy.verificationNotExecuted.addAll(verificationNotExecuted);
            copy.viewable.addAll(viewable);
            copy.notViewable.addAll(notViewable);
            copy.viewUndetermined.addAll(viewUndetermined);
            copy.errors.addAll(errors);
            copy.breakStart.addAll(breakStart);
            copy.breakEnd.addAll(breakEnd);
            copy.breakError.addAll(breakError);
            copy.skipOffsetMs = skipOffsetMs;
            copy.skipOffsetPercent = skipOffsetPercent;
            copy.clickThroughUrl = clickThroughUrl;
            copy.wrapperLayerCount = wrapperLayerCount;
            copy.followAdditionalWrappers = followAdditionalWrappers;
            copy.allowMultipleAds = allowMultipleAds;
            return copy;
        }

        boolean hasWrapperLayers() {
            return wrapperLayerCount > 0;
        }

        TrackingBundle merge(VastParsedResponse response) {
            if (response.hasWrapper()) {
                wrapperLayerCount += 1;
            }
            addAll(impressions, response.getImpressions());
            addAll(creativeView, response.getCreativeViewTrackers());
            addAll(loaded, response.getLoadedTrackers());
            addAll(start, response.getStartTrackers());
            addAll(firstQuartile, response.getFirstQuartileTrackers());
            addAll(midpoint, response.getMidpointTrackers());
            addAll(thirdQuartile, response.getThirdQuartileTrackers());
            addAll(complete, response.getCompleteTrackers());
            addAll(mute, response.getMuteTrackers());
            addAll(unmute, response.getUnmuteTrackers());
            addAll(pause, response.getPauseTrackers());
            addAll(resume, response.getResumeTrackers());
            addAll(skip, response.getSkipTrackers());
            addAll(progress, response.getProgressTrackers());
            addAll(clickTracking, response.getClickTrackingUrls());
            addAll(customClick, response.getCustomClickUrls());
            addAll(verificationResources, response.getVerificationResources());
            addAll(verificationNotExecuted, response.getVerificationNotExecutedTrackers());
            addAll(viewable, response.getViewableTrackers());
            addAll(notViewable, response.getNotViewableTrackers());
            addAll(viewUndetermined, response.getViewUndeterminedTrackers());
            addAll(errors, response.getErrorTrackers());
            if (skipOffsetMs < 0L && response.getSkipOffsetMs() >= 0L) {
                skipOffsetMs = response.getSkipOffsetMs();
            }
            if (skipOffsetPercent < 0f && response.getSkipOffsetPercent() >= 0f) {
                skipOffsetPercent = response.getSkipOffsetPercent();
            }
            if (clickThroughUrl == null && response.getClickThroughUrl() != null) {
                clickThroughUrl = response.getClickThroughUrl();
            }
            followAdditionalWrappers =
                followAdditionalWrappers && response.shouldFollowAdditionalWrappers();
            allowMultipleAds = allowMultipleAds && response.allowsMultipleAds();
            return this;
        }

        TrackingBundle merge(VmapAdBreak adBreak) {
            addAll(breakStart, adBreak.getBreakStartTrackers());
            addAll(breakEnd, adBreak.getBreakEndTrackers());
            addAll(breakError, adBreak.getErrorTrackers());
            addAll(errors, adBreak.getErrorTrackers());
            return this;
        }

        VastAdBreak toAdBreak(VastAdBreak adBreak) {
            List<VastAd> ads = new ArrayList<>();
            List<VastAd> sourceAds = adBreak == null ? java.util.Collections.emptyList() : adBreak.getAds();
            for (int i = 0; i < sourceAds.size(); i++) {
                ads.add(toAd(sourceAds.get(i)));
            }
            return toBreakWithTrackers(ads);
        }

        VastAdBreak toBreakWithTrackers(List<VastAd> ads) {
            return new VastAdBreak(ads, breakStart, breakEnd, breakError);
        }

        VastAd toAd(VastAd ad) {
            return new VastAd(
                ad.getMediaUrl(),
                ad.getMediaType(),
                ad.getMediaFiles(),
                mergeList(impressions, ad.getImpressions()),
                mergeList(creativeView, ad.getCreativeViewTrackers()),
                mergeList(loaded, ad.getLoadedTrackers()),
                mergeList(start, ad.getStartTrackers()),
                mergeList(firstQuartile, ad.getFirstQuartileTrackers()),
                mergeList(midpoint, ad.getMidpointTrackers()),
                mergeList(thirdQuartile, ad.getThirdQuartileTrackers()),
                mergeList(complete, ad.getCompleteTrackers()),
                mergeList(mute, ad.getMuteTrackers()),
                mergeList(unmute, ad.getUnmuteTrackers()),
                mergeList(pause, ad.getPauseTrackers()),
                mergeList(resume, ad.getResumeTrackers()),
                mergeList(skip, ad.getSkipTrackers()),
                mergeList(progress, ad.getProgressTrackers()),
                ad.getSkipOffsetMs() >= 0L ? ad.getSkipOffsetMs() : skipOffsetMs,
                ad.getSkipOffsetPercent() >= 0f ? ad.getSkipOffsetPercent() : skipOffsetPercent,
                ad.getClickThroughUrl() == null ? clickThroughUrl : ad.getClickThroughUrl(),
                mergeList(clickTracking, ad.getClickTrackingUrls()),
                mergeList(customClick, ad.getCustomClickUrls()),
                mergeList(verificationResources, ad.getVerificationResources()),
                mergeList(verificationNotExecuted, ad.getVerificationNotExecutedTrackers()),
                mergeList(viewable, ad.getViewableTrackers()),
                mergeList(notViewable, ad.getNotViewableTrackers()),
                mergeList(viewUndetermined, ad.getViewUndeterminedTrackers()),
                mergeList(errors, ad.getErrorTrackers()),
                ad.hasUnsupportedInteractiveCreative(),
                ad.hasUnexecutedInteractiveCreativeFile()
            );
        }

        VastLoadException toError(VastLoadException error) {
            return new VastLoadException(
                error.getMessage(),
                error.getCause(),
                error.getVastErrorCode(),
                mergeList(errors, error.getErrorTrackers())
            );
        }

        boolean shouldFollowAdditionalWrappers() {
            return followAdditionalWrappers;
        }

        boolean allowsMultipleAds() {
            return allowMultipleAds;
        }

        private static <T> void addAll(List<T> target, List<T> values) {
            if (values != null) {
                target.addAll(values);
            }
        }

        private static <T> List<T> mergeList(List<T> first, List<T> second) {
            List<T> merged = new ArrayList<>(first);
            addAll(merged, second);
            return merged;
        }
    }
}
