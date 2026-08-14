package com.smart.android.adsdk.internal;

interface FlowControlResolver {
    Cancellable resolve(String channelId, Callback callback);

    interface Callback {
        void onAllowed(boolean skipCmp);

        void onBlocked(String reason);

        void onError(Throwable error);
    }
}
