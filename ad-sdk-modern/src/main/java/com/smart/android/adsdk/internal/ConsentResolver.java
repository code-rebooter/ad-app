package com.smart.android.adsdk.internal;

import android.content.Context;
import com.smart.android.adsdk.AdError;

public interface ConsentResolver {
    Cancellable resolve(Context context, String channelId, Callback callback);

    interface Callback {
        default void onTrace(String eventType, String message) {
        }

        void onAllowed();

        void onBlocked(String reason);

        default void onBlocked(String reason, AdError error) {
            onBlocked(reason);
        }

        void onError(AdError error);
    }
}
