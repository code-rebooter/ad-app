package com.smart.android.adsdk;

public final class SdkConfig {
    private static final long DEFAULT_AD_CALLBACK_TIMEOUT_MS = 180_000L;

    private final boolean debugLogging;
    private final long adCallbackTimeoutMs;

    private SdkConfig(Builder builder) {
        if (builder.adCallbackTimeoutMs <= 0L) {
            throw new IllegalArgumentException("adCallbackTimeoutMs must be > 0");
        }
        this.debugLogging = builder.debugLogging;
        this.adCallbackTimeoutMs = builder.adCallbackTimeoutMs;
    }

    public boolean isDebugLogging() {
        return debugLogging;
    }

    public long getAdCallbackTimeoutMs() {
        return adCallbackTimeoutMs;
    }

    public static final class Builder {
        private boolean debugLogging;
        private long adCallbackTimeoutMs = DEFAULT_AD_CALLBACK_TIMEOUT_MS;

        public Builder setDebugLogging(boolean debugLogging) {
            this.debugLogging = debugLogging;
            return this;
        }

        public Builder setAdCallbackTimeoutMs(long adCallbackTimeoutMs) {
            this.adCallbackTimeoutMs = adCallbackTimeoutMs;
            return this;
        }

        public SdkConfig build() {
            return new SdkConfig(this);
        }
    }
}
