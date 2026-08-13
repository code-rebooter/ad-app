package com.smart.android.adsdk.internal;

import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.TimeZone;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;

final class VastTracker {
    private final OkHttpClient okHttpClient;
    private String mediaType;

    VastTracker(OkHttpClient okHttpClient) {
        this.okHttpClient = okHttpClient;
    }

    void setMediaType(String mediaType) {
        this.mediaType = mediaType;
    }

    void fire(List<String> urls) {
        fire(urls, 900);
    }

    void fireError(List<String> urls, int errorCode) {
        fire(urls, errorCode);
    }

    private void fire(List<String> urls, int errorCode) {
        if (urls == null) {
            return;
        }
        for (String url : urls) {
            if (url == null || url.trim().isEmpty()) {
                continue;
            }
            String resolvedUrl = replaceMacros(url.trim(), errorCode);
            Request request;
            try {
                request = new Request.Builder().url(resolvedUrl).get().build();
            } catch (IllegalArgumentException ignored) {
                continue;
            }
            okHttpClient.newCall(request).enqueue(new okhttp3.Callback() {
                @Override
                public void onFailure(okhttp3.Call call, java.io.IOException error) {
                }

                @Override
                public void onResponse(okhttp3.Call call, okhttp3.Response response) {
                    response.close();
                }
            });
        }
    }

    private String replaceMacros(String url, int errorCode) {
        String cacheBuster = String.format(
            Locale.US,
            "%08d",
            Math.abs(UUID.randomUUID().hashCode())
        );
        String timestamp = isoTimestamp();
        String mediaTypeValue = safeMediaType();
        return url
            .replace("[CACHEBUSTING]", cacheBuster)
            .replace("[CACHEBUSTER]", cacheBuster)
            .replace("%5BCACHEBUSTING%5D", cacheBuster)
            .replace("%5BCACHEBUSTER%5D", cacheBuster)
            .replace("%%CACHEBUSTING%%", cacheBuster)
            .replace("[TIMESTAMP]", encodeMacroValue(timestamp))
            .replace("%5BTIMESTAMP%5D", encodeMacroValue(timestamp))
            .replace("[ERRORCODE]", String.valueOf(errorCode))
            .replace("%5BERRORCODE%5D", String.valueOf(errorCode))
            .replace("[AD_MT]", encodeMacroValue(mediaTypeValue))
            .replace("%5BAD_MT%5D", encodeMacroValue(mediaTypeValue));
    }

    private String isoTimestamp() {
        SimpleDateFormat format = new SimpleDateFormat(
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
            Locale.US
        );
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return format.format(new Date());
    }

    private String safeMediaType() {
        return mediaType == null || mediaType.trim().isEmpty()
            ? ""
            : mediaType.trim();
    }

    private String encodeMacroValue(String value) {
        return new HttpUrl.Builder()
            .scheme("https")
            .host("macro.test")
            .addQueryParameter("v", value)
            .build()
            .encodedQuery()
            .substring(2);
    }
}
