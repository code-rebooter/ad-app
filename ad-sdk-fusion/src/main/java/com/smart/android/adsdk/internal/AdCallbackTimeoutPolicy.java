package com.smart.android.adsdk.internal;

final class AdCallbackTimeoutPolicy {
    private static final long MIN_TIMEOUT_SECONDS = 30L;
    private static final long MAX_TIMEOUT_SECONDS = 600L;

    private AdCallbackTimeoutPolicy() {
    }

    static Long resolveOverrideMs(Long timeoutSeconds) {
        if (timeoutSeconds == null
            || timeoutSeconds < MIN_TIMEOUT_SECONDS
            || timeoutSeconds > MAX_TIMEOUT_SECONDS) {
            return null;
        }
        return timeoutSeconds * 1_000L;
    }
}
