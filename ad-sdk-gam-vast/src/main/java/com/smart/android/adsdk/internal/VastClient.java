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

    VastClient(OkHttpClient okHttpClient) {
        this.okHttpClient = okHttpClient;
    }

    Cancellable load(String url, int timeoutMs, Callback callback) {
        LoadOperation operation = new LoadOperation(timeoutMs, callback);
        operation.load(url, 0, new TrackingBundle());
        return operation;
    }

    interface Callback {
        void onLoaded(VastAd ad);

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
            if (cancelled) {
                return;
            }
            if (depth > MAX_WRAPPER_DEPTH) {
                callback.onError(new VastLoadException("VAST wrapper depth exceeded " + MAX_WRAPPER_DEPTH));
                return;
            }
            HttpUrl httpUrl = HttpUrl.parse(url);
            if (httpUrl == null) {
                callback.onError(new VastLoadException("Invalid VAST URL: " + url));
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
                        callback.onError(new VastLoadException("VAST request failed", error));
                    }
                }

                @Override
                public void onResponse(Call call, Response response) {
                    try (Response closeableResponse = response) {
                        if (cancelled) {
                            return;
                        }
                        if (!closeableResponse.isSuccessful()) {
                            callback.onError(new VastLoadException(
                                "VAST HTTP status " + closeableResponse.code()
                            ));
                            return;
                        }
                        ResponseBody body = closeableResponse.body();
                        if (body == null) {
                            callback.onError(new VastLoadException("VAST response body was empty"));
                            return;
                        }
                        VastParsedResponse parsed = parser.parse(body.string());
                        if (parsed.hasInlineAd()) {
                            callback.onLoaded(inherited.toAd(parsed.getAd()));
                        } else if (parsed.hasWrapper()) {
                            TrackingBundle merged = inherited.merge(parsed);
                            String nextUrl = resolveNextUrl(httpUrl, parsed.getWrapperUrl());
                            if (nextUrl == null) {
                                callback.onError(new VastLoadException("VAST wrapper URL was invalid"));
                            } else {
                                load(nextUrl, depth + 1, merged);
                            }
                        } else {
                            callback.onError(new VastLoadException("VAST response was not playable"));
                        }
                    } catch (IOException | VastLoadException error) {
                        if (!cancelled) {
                            callback.onError(error instanceof VastLoadException
                                ? (VastLoadException) error
                                : new VastLoadException("Unable to read VAST response", error));
                        }
                    }
                }
            });
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

    private String resolveNextUrl(HttpUrl baseUrl, String value) {
        HttpUrl resolved = baseUrl.resolve(value);
        return resolved == null ? null : resolved.toString();
    }

    static final class TrackingBundle {
        private final List<String> impressions = new ArrayList<>();
        private final List<String> start = new ArrayList<>();
        private final List<String> firstQuartile = new ArrayList<>();
        private final List<String> midpoint = new ArrayList<>();
        private final List<String> thirdQuartile = new ArrayList<>();
        private final List<String> complete = new ArrayList<>();
        private final List<String> errors = new ArrayList<>();

        TrackingBundle merge(VastParsedResponse response) {
            addAll(impressions, response.getImpressions());
            addAll(start, response.getStartTrackers());
            addAll(firstQuartile, response.getFirstQuartileTrackers());
            addAll(midpoint, response.getMidpointTrackers());
            addAll(thirdQuartile, response.getThirdQuartileTrackers());
            addAll(complete, response.getCompleteTrackers());
            addAll(errors, response.getErrorTrackers());
            return this;
        }

        VastAd toAd(VastAd ad) {
            return new VastAd(
                ad.getMediaUrl(),
                mergeList(impressions, ad.getImpressions()),
                mergeList(start, ad.getStartTrackers()),
                mergeList(firstQuartile, ad.getFirstQuartileTrackers()),
                mergeList(midpoint, ad.getMidpointTrackers()),
                mergeList(thirdQuartile, ad.getThirdQuartileTrackers()),
                mergeList(complete, ad.getCompleteTrackers()),
                mergeList(errors, ad.getErrorTrackers())
            );
        }

        private static void addAll(List<String> target, List<String> values) {
            if (values != null) {
                target.addAll(values);
            }
        }

        private static List<String> mergeList(List<String> first, List<String> second) {
            List<String> merged = new ArrayList<>(first);
            addAll(merged, second);
            return merged;
        }
    }
}
