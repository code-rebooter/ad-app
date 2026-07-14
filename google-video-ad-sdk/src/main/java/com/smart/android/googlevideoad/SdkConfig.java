package com.smart.android.googlevideoad;

public final class SdkConfig {
    private final String channelId;
    private final boolean debugLogging;

    private SdkConfig(Builder builder) {
        String normalizedChannelId = builder.channelId == null ? "" : builder.channelId.trim();
        if (normalizedChannelId.isEmpty()) {
            throw new IllegalArgumentException("channelId must not be blank");
        }
        this.channelId = normalizedChannelId;
        this.debugLogging = builder.debugLogging;
    }

    public String getChannelId() {
        return channelId;
    }

    public boolean isDebugLogging() {
        return debugLogging;
    }

    public static final class Builder {
        private String channelId;
        private boolean debugLogging;

        public Builder setChannelId(String channelId) {
            this.channelId = channelId;
            return this;
        }

        public Builder setDebugLogging(boolean debugLogging) {
            this.debugLogging = debugLogging;
            return this;
        }

        public SdkConfig build() {
            return new SdkConfig(this);
        }
    }
}
