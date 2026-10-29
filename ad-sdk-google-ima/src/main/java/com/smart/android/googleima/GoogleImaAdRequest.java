package com.smart.android.googleima;

public final class GoogleImaAdRequest {
    private final String adTagUrl;
    private final boolean soundEnabled;
    private final int adLoadTimeoutMs;
    private final long adStartupTimeoutMs;
    private final String userAgent;

    private GoogleImaAdRequest(Builder builder) {
        this.adTagUrl = builder.adTagUrl;
        this.soundEnabled = builder.soundEnabled;
        this.adLoadTimeoutMs = builder.adLoadTimeoutMs;
        this.adStartupTimeoutMs = builder.adStartupTimeoutMs;
        this.userAgent = builder.userAgent;
    }

    public String getAdTagUrl() {
        return adTagUrl;
    }

    public boolean isSoundEnabled() {
        return soundEnabled;
    }

    public int getAdLoadTimeoutMs() {
        return adLoadTimeoutMs;
    }

    public long getAdStartupTimeoutMs() {
        return adStartupTimeoutMs;
    }

    public String getUserAgent() {
        return userAgent;
    }

    public static final class Builder {
        private final String adTagUrl;
        private boolean soundEnabled;
        private int adLoadTimeoutMs = 20_000;
        private long adStartupTimeoutMs = 35_000L;
        private String userAgent;

        public Builder(String adTagUrl) {
            if (adTagUrl == null || adTagUrl.trim().isEmpty()) {
                throw new IllegalArgumentException("adTagUrl must not be blank");
            }
            this.adTagUrl = adTagUrl;
        }

        public Builder setSoundEnabled(boolean soundEnabled) {
            this.soundEnabled = soundEnabled;
            return this;
        }

        public Builder setAdLoadTimeoutMs(int adLoadTimeoutMs) {
            this.adLoadTimeoutMs = Math.max(1, adLoadTimeoutMs);
            return this;
        }

        public Builder setAdStartupTimeoutMs(long adStartupTimeoutMs) {
            this.adStartupTimeoutMs = Math.max(1L, adStartupTimeoutMs);
            return this;
        }

        public Builder setUserAgent(String userAgent) {
            this.userAgent = userAgent == null || userAgent.trim().isEmpty() ? null : userAgent;
            return this;
        }

        public GoogleImaAdRequest build() {
            return new GoogleImaAdRequest(this);
        }
    }
}
