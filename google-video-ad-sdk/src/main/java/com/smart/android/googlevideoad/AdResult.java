package com.smart.android.googlevideoad;

public final class AdResult {
    private final AdResultStatus status;
    private final String reason;
    private final AdError error;

    private AdResult(AdResultStatus status, String reason, AdError error) {
        this.status = status;
        this.reason = reason;
        this.error = error;
    }

    public static AdResult completed() {
        return new AdResult(AdResultStatus.COMPLETED, null, null);
    }

    public static AdResult skipped(String reason) {
        return new AdResult(AdResultStatus.SKIPPED, reason, null);
    }

    public static AdResult error(AdError error) {
        return new AdResult(AdResultStatus.ERROR, null, error);
    }

    public static AdResult cancelled() {
        return new AdResult(AdResultStatus.CANCELLED, null, null);
    }

    public AdResultStatus getStatus() {
        return status;
    }

    public String getReason() {
        return reason;
    }

    public AdError getError() {
        return error;
    }
}
