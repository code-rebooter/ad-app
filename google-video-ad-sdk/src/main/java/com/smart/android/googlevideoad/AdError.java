package com.smart.android.googlevideoad;

public final class AdError {
    private final AdErrorCode code;
    private final AdErrorStage stage;
    private final String message;
    private final Throwable cause;

    public AdError(
        AdErrorCode code,
        AdErrorStage stage,
        String message,
        Throwable cause
    ) {
        this.code = code;
        this.stage = stage;
        this.message = message;
        this.cause = cause;
    }

    public AdErrorCode getCode() {
        return code;
    }

    public AdErrorStage getStage() {
        return stage;
    }

    public String getMessage() {
        return message;
    }

    public Throwable getCause() {
        return cause;
    }
}
