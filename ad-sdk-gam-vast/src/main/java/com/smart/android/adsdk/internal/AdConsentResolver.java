package com.smart.android.adsdk.internal;

import android.content.Context;
import com.google.gson.Gson;
import okhttp3.OkHttpClient;

final class AdConsentResolver implements ConsentResolver {

    AdConsentResolver(
        OkHttpClient okHttpClient,
        Gson gson,
        String consentPopupUrl,
        String consentReportUrl
    ) {
    }

    @Override
    public Cancellable resolve(Context context, String channelId, ConsentResolver.Callback callback) {
        callback.onAllowed();
        return () -> {};
    }
}
