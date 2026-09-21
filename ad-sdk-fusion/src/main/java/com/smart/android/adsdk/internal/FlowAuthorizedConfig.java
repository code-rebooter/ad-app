package com.smart.android.adsdk.internal;

final class FlowAuthorizedConfig {
    private final String requestId;
    private final boolean hiddenMode;
    private final Boolean soundEnabled;
    private final long nextRequestSeconds;
    private final Long adCallbackTimeoutMs;
    private final String clientIp;

    FlowAuthorizedConfig(
        String requestId,
        boolean hiddenMode,
        Boolean soundEnabled,
        long nextRequestSeconds
    ) {
        this(requestId, hiddenMode, soundEnabled, nextRequestSeconds, null);
    }

    FlowAuthorizedConfig(
        String requestId,
        boolean hiddenMode,
        Boolean soundEnabled,
        long nextRequestSeconds,
        Long adCallbackTimeoutMs
    ) {
        this(requestId, hiddenMode, soundEnabled, nextRequestSeconds, adCallbackTimeoutMs, null);
    }

    FlowAuthorizedConfig(String requestId, boolean hiddenMode, Boolean soundEnabled,
                         long nextRequestSeconds, Long adCallbackTimeoutMs, String clientIp) {
        this.requestId = requestId;
        this.hiddenMode = hiddenMode;
        this.soundEnabled = soundEnabled;
        this.nextRequestSeconds = nextRequestSeconds >= 10L && nextRequestSeconds <= 86_400L
            ? nextRequestSeconds : 0L;
        this.adCallbackTimeoutMs = adCallbackTimeoutMs;
        this.clientIp = clientIp;
    }

    String getClientIp() {
        return clientIp;
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

    Long getAdCallbackTimeoutMs() {
        return adCallbackTimeoutMs;
    }
}
