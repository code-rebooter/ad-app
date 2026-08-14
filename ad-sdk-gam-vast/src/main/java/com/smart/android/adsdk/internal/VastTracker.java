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
    private long adPlayheadMs;
    private String reason;

    VastTracker(OkHttpClient okHttpClient) {
        this.okHttpClient = okHttpClient;
    }

    void setMediaType(String mediaType) {
        this.mediaType = mediaType;
    }

    void setAdPlayheadMs(long adPlayheadMs) {
        this.adPlayheadMs = Math.max(0L, adPlayheadMs);
    }

    void fire(List<String> urls) {
        fire(urls, 900);
    }

    void fireError(List<String> urls, int errorCode) {
        fire(urls, errorCode);
    }

    void fireVerificationNotExecuted(List<String> urls, String reason) {
        this.reason = reason;
        fire(urls, 900);
        this.reason = null;
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
        String resolved = url
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
            .replace("%5BAD_MT%5D", encodeMacroValue(mediaTypeValue))
            .replace("[ADPLAYHEAD]", formatPlayhead(adPlayheadMs))
            .replace("%5BADPLAYHEAD%5D", formatPlayhead(adPlayheadMs))
            .replace("[CONTENTPLAYHEAD]", formatPlayhead(adPlayheadMs))
            .replace("%5BCONTENTPLAYHEAD%5D", formatPlayhead(adPlayheadMs))
            .replace("[REASON]", encodeMacroValue(safeReason()))
            .replace("%5BREASON%5D", encodeMacroValue(safeReason()));
        return replaceUnknownMacros(resolved);
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

    private String safeReason() {
        return reason == null ? "" : reason.trim();
    }

    private String formatPlayhead(long positionMs) {
        long safePositionMs = Math.max(0L, positionMs);
        long hours = safePositionMs / 3_600_000L;
        long minutes = (safePositionMs % 3_600_000L) / 60_000L;
        long seconds = (safePositionMs % 60_000L) / 1_000L;
        long millis = safePositionMs % 1_000L;
        return String.format(Locale.US, "%02d:%02d:%02d.%03d", hours, minutes, seconds, millis);
    }

    private String replaceUnknownMacros(String url) {
        return url.replaceAll("\\[[A-Za-z0-9_]+\\]", "-1")
            .replaceAll("(?i)%5B[A-Za-z0-9_]+%5D", "-1");
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
