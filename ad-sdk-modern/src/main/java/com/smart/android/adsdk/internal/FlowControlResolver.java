package com.smart.android.adsdk.internal;

interface FlowControlResolver {
    Cancellable resolve(String channelId, Callback callback);

    interface Callback {
        void onAllowed(boolean skipCmp);

        void onBlocked(String reason);

        default void onBlocked(String reason, com.smart.android.adsdk.AdError error) {
            onBlocked(reason);
        }

        void onError(Throwable error);
    }
}
