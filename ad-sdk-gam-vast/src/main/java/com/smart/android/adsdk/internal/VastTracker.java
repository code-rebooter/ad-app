package com.smart.android.adsdk.internal;

import java.util.List;
import java.util.Locale;
import java.util.UUID;
import okhttp3.OkHttpClient;
import okhttp3.Request;

final class VastTracker {
    private final OkHttpClient okHttpClient;

    VastTracker(OkHttpClient okHttpClient) {
        this.okHttpClient = okHttpClient;
    }

    void fire(List<String> urls) {
        if (urls == null) {
            return;
        }
        for (String url : urls) {
            if (url == null || url.trim().isEmpty()) {
                continue;
            }
            String resolvedUrl = replaceMacros(url.trim());
            Request request = new Request.Builder().url(resolvedUrl).get().build();
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

    private String replaceMacros(String url) {
        String cacheBuster = String.format(
            Locale.US,
            "%08d",
            Math.abs(UUID.randomUUID().hashCode())
        );
        return url
            .replace("[CACHEBUSTING]", cacheBuster)
            .replace("[CACHEBUSTER]", cacheBuster)
            .replace("%%CACHEBUSTING%%", cacheBuster)
            .replace("[TIMESTAMP]", String.valueOf(System.currentTimeMillis()));
    }
}
