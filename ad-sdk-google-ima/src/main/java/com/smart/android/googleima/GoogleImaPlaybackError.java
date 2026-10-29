package com.smart.android.googleima;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Structured playback failure emitted by the controlled IMA player. */
public final class GoogleImaPlaybackError {
    public static final String CATEGORY_TIMEOUT = "timeout";
    public static final String CATEGORY_IMA = "ima";
    public static final String CATEGORY_PLAYER = "player";
    public static final String CATEGORY_INTERNAL = "internal";

    private final String category;
    private final String reason;
    private final String message;
    private final Map<String, String> details;

    public GoogleImaPlaybackError(
            String category,
            String reason,
            String message,
            Map<String, String> details) {
        this.category = safe(category);
        this.reason = safe(reason);
        this.message = safe(message);
        this.details = Collections.unmodifiableMap(
                new LinkedHashMap<>(details == null ? Collections.emptyMap() : details));
    }

    public String getCategory() {
        return category;
    }

    public String getReason() {
        return reason;
    }

    public String getMessage() {
        return message;
    }

    public Map<String, String> getDetails() {
        return details;
    }

    public boolean isTimeout() {
        return CATEGORY_TIMEOUT.equals(category);
    }

    public static GoogleImaPlaybackError timeout(String reason) {
        return new GoogleImaPlaybackError(CATEGORY_TIMEOUT, reason, reason, null);
    }

    public static GoogleImaPlaybackError internal(String reason) {
        return new GoogleImaPlaybackError(CATEGORY_INTERNAL, reason, reason, null);
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
