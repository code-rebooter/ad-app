package com.smart.android.adsdk.internal;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class VastLoadException extends Exception {
    private final int vastErrorCode;
    private final List<String> errorTrackers;

    VastLoadException(String message) {
        this(message, null, 900, Collections.emptyList());
    }

    VastLoadException(String message, Throwable cause) {
        this(message, cause, 900, Collections.emptyList());
    }

    VastLoadException(String message, int vastErrorCode, List<String> errorTrackers) {
        this(message, null, vastErrorCode, errorTrackers);
    }

    VastLoadException(
        String message,
        Throwable cause,
        int vastErrorCode,
        List<String> errorTrackers
    ) {
        super(message, cause);
        this.vastErrorCode = vastErrorCode;
        this.errorTrackers = errorTrackers == null || errorTrackers.isEmpty()
            ? Collections.emptyList()
            : Collections.unmodifiableList(new ArrayList<>(errorTrackers));
    }

    int getVastErrorCode() {
        return vastErrorCode;
    }

    List<String> getErrorTrackers() {
        return errorTrackers;
    }
}
