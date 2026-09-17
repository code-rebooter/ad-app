package com.smart.android.adsdk;

public final class AdError {
    private final AdErrorCode code;
    private final AdErrorStage stage;
    private final String message;
    private final Throwable cause;
    private final String source;
    private final String originalCode;
    private final String responseBody;

    public AdError(
        AdErrorCode code,
        AdErrorStage stage,
        String message,
        Throwable cause
    ) {
        this(code, stage, message, cause, "SDK", null, null);
    }

    public AdError(
        AdErrorCode code,
        AdErrorStage stage,
        String message,
        Throwable cause,
        String source,
        String originalCode,
        String responseBody
    ) {
        this.code = code;
        this.stage = stage;
        this.message = message;
        this.cause = cause;
        this.source = source;
        this.originalCode = originalCode;
        this.responseBody = responseBody;
    }

    /** Existing SDK category. Use getOriginalCode() for the upstream error code. */
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

    /** Error origin, for example IMA, MEDIA3, HTTP, API, NETWORK or SDK. */
    public String getSource() {
        return source;
    }

    /** Unmodified upstream code, e.g. "303" from IMA. Null if no upstream code exists. */
    public String getOriginalCode() {
        return originalCode;
    }

    public String getResponseBody() {
        return responseBody;
    }

    @Override
    public String toString() {
        return "AdError{source=" + source + ", originalCode=" + originalCode
            + ", stage=" + stage + ", category=" + code + ", message=" + message
            + ", cause=" + cause + ", responseBody=" + responseBody + "}";
    }
}
