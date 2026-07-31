package com.smart.android.adsdk.internal;

import android.content.Context;
import com.smart.android.adsdk.AdError;

public interface ConsentResolver {
    Cancellable resolve(Context context, String channelId, Callback callback);

    interface Callback {
        void onAllowed();

        void onBlocked(String reason);

        void onError(AdError error);
    }
}
