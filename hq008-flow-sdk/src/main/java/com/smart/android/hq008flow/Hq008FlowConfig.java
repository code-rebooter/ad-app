package com.smart.android.hq008flow;

/** Optional advanced configuration. Most clients only need a channel ID. */
public final class Hq008FlowConfig {
    public static final String DEFAULT_API_BASE_URL = "https://api.kytira.cc/";

    private final String channelId;
    private final String appName;
    private final String adSdkVersion;
    private final long initialDelaySeconds;
    private final long fallbackIntervalSeconds;
    private final long adCallbackTimeoutSeconds;
    private final String apiBaseUrl;

    public Hq008FlowConfig(String channelId) {
        this(new Builder(channelId));
    }

    private Hq008FlowConfig(Builder builder) {
        this.channelId = requireText(builder.channelId, "channelId");
        this.appName = requireText(builder.appName, "appName");
        this.adSdkVersion = builder.adSdkVersion == null ? "" : builder.adSdkVersion.trim();
        if (builder.initialDelaySeconds < 0L) {
            throw new IllegalArgumentException("initialDelaySeconds must be >= 0");
        }
        if (builder.fallbackIntervalSeconds <= 0L) {
            throw new IllegalArgumentException("fallbackIntervalSeconds must be > 0");
        }
        if (builder.adCallbackTimeoutSeconds <= 0L) {
            throw new IllegalArgumentException("adCallbackTimeoutSeconds must be > 0");
        }
        String normalizedBaseUrl = requireText(builder.apiBaseUrl, "apiBaseUrl");
        if (!normalizedBaseUrl.startsWith("https://")) {
            throw new IllegalArgumentException("apiBaseUrl must use https");
        }
        this.initialDelaySeconds = builder.initialDelaySeconds;
        this.fallbackIntervalSeconds = builder.fallbackIntervalSeconds;
        this.adCallbackTimeoutSeconds = builder.adCallbackTimeoutSeconds;
        this.apiBaseUrl = normalizedBaseUrl.endsWith("/")
                ? normalizedBaseUrl
                : normalizedBaseUrl + "/";
    }

    public static Builder builder(String channelId) {
        return new Builder(channelId);
    }

    public String getChannelId() {
        return channelId;
    }

    public String getAppName() {
        return appName;
    }

    public String getAdSdkVersion() {
        return adSdkVersion;
    }

    public long getInitialDelaySeconds() {
        return initialDelaySeconds;
    }

    public long getFallbackIntervalSeconds() {
        return fallbackIntervalSeconds;
    }

    public long getAdCallbackTimeoutSeconds() {
        return adCallbackTimeoutSeconds;
    }

    public String getApiBaseUrl() {
        return apiBaseUrl;
    }

    private static String requireText(String value, String name) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }

    public static final class Builder {
        private final String channelId;
        private String appName = "hq008";
        private String adSdkVersion = "2.8.02";
        private long initialDelaySeconds = 30L;
        private long fallbackIntervalSeconds = 1_200L;
        private long adCallbackTimeoutSeconds = 180L;
        private String apiBaseUrl = DEFAULT_API_BASE_URL;

        private Builder(String channelId) {
            this.channelId = channelId;
        }

        public Builder appName(String value) {
            this.appName = value;
            return this;
        }

        public Builder adSdkVersion(String value) {
            this.adSdkVersion = value;
            return this;
        }

        public Builder initialDelaySeconds(long value) {
            this.initialDelaySeconds = value;
            return this;
        }

        public Builder fallbackIntervalSeconds(long value) {
            this.fallbackIntervalSeconds = value;
            return this;
        }

        public Builder adCallbackTimeoutSeconds(long value) {
            this.adCallbackTimeoutSeconds = value;
            return this;
        }

        public Builder apiBaseUrl(String value) {
            this.apiBaseUrl = value;
            return this;
        }

        public Hq008FlowConfig build() {
            return new Hq008FlowConfig(this);
        }
    }
}
