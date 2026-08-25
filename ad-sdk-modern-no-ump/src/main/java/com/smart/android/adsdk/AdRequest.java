package com.smart.android.adsdk;

public final class AdRequest {
    private final boolean soundEnabled;
    private final String requestId;

    private AdRequest(Builder builder) {
        this.soundEnabled = builder.soundEnabled;
        this.requestId = normalizeNullableText(builder.requestId);
    }

    public boolean isSoundEnabled() {
        return soundEnabled;
    }

    public String getRequestId() {
        return requestId;
    }

    private static String normalizeNullableText(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    public static final class Builder {
        private boolean soundEnabled;
        private String requestId;

        public Builder setSoundEnabled(boolean soundEnabled) {
            this.soundEnabled = soundEnabled;
            return this;
        }

        public Builder setRequestId(String requestId) {
            this.requestId = requestId;
            return this;
        }

        public AdRequest build() {
            return new AdRequest(this);
        }
    }
}
