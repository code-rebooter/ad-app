package com.smart.android.googleima;

public final class GoogleImaAdRequest {
    private final String adTagUrl;
    private final boolean soundEnabled;
    private final boolean reportedSoundEnabled;
    private final int adLoadTimeoutMs;
    private final long adStartupTimeoutMs;
    private final String userAgent;
    private final GoogleImaAdUrlResolver adUrlResolver;

    private GoogleImaAdRequest(Builder builder) {
        this.adTagUrl = builder.adTagUrl;
        this.soundEnabled = builder.soundEnabled;
        this.reportedSoundEnabled = builder.reportedSoundEnabled;
        this.adLoadTimeoutMs = builder.adLoadTimeoutMs;
        this.adStartupTimeoutMs = builder.adStartupTimeoutMs;
        this.userAgent = builder.userAgent;
        this.adUrlResolver = builder.adUrlResolver;
    }

    public String getAdTagUrl() {
        return adTagUrl;
    }

    public boolean isSoundEnabled() {
        return soundEnabled;
    }

    public boolean isReportedSoundEnabled() {
        return reportedSoundEnabled;
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

    public GoogleImaAdUrlResolver getAdUrlResolver() {
        return adUrlResolver;
    }

    public static final class Builder {
        private final String adTagUrl;
        private boolean soundEnabled;
        private boolean reportedSoundEnabled = true;
        private int adLoadTimeoutMs = 20_000;
        private long adStartupTimeoutMs = 35_000L;
        private String userAgent;
        private GoogleImaAdUrlResolver adUrlResolver;

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

        /** Controls the mute state reported by this request to Google. */
        public Builder setReportedSoundEnabled(boolean reportedSoundEnabled) {
            this.reportedSoundEnabled = reportedSoundEnabled;
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

        public Builder setAdUrlResolver(GoogleImaAdUrlResolver resolver) {
            this.adUrlResolver = resolver;
            return this;
        }
    }
}
