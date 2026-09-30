package com.smart.android.adsdk.internal;

final class HttpUserAgent {
    private HttpUserAgent() {
    }

    static String get() {
        String value = System.getProperty("http.agent");
        return value == null || value.trim().isEmpty()
            ? "Mozilla/5.0 (Linux; Android)"
            : value;
    }
}
