package com.smart.android.adsdk.internal;

import com.smart.android.adsdk.AdError;
import com.smart.android.adsdk.AdErrorCode;
import com.smart.android.adsdk.AdErrorStage;
import java.io.IOException;

final class AdErrors {
    private AdErrors() {}

    static AdError from(AdErrorCode category, AdErrorStage stage, Throwable error, String body) {
        if (error instanceof AdResponseException) {
            AdResponseException response = (AdResponseException) error;
            return new AdError(category, stage, response.getMessage(), response,
                response.source, response.originalCode, response.responseBody);
        }
        return new AdError(category, stage, error == null ? null : error.getMessage(), error,
            error instanceof IOException ? "NETWORK" : "SDK", null, body);
    }
}
