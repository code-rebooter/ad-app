package com.smart.android.adsdk.internal;

final class FlowAuthorizedConfig {
    private final String requestId;
    private final boolean hiddenMode;
    private final Boolean soundEnabled;
    private final long nextRequestSeconds;

    FlowAuthorizedConfig(
        String requestId,
        boolean hiddenMode,
        Boolean soundEnabled,
        long nextRequestSeconds
    ) {
        this.requestId = requestId;
        this.hiddenMode = hiddenMode;
        this.soundEnabled = soundEnabled;
        this.nextRequestSeconds = nextRequestSeconds;
    }

    String getRequestId() {
        return requestId;
    }

    boolean isHiddenMode() {
        return hiddenMode;
    }

    Boolean getSoundEnabled() {
        return soundEnabled;
    }

    long getNextRequestSeconds() {
        return nextRequestSeconds;
    }
}
