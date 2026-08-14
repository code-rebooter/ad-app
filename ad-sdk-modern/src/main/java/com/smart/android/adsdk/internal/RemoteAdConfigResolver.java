package com.smart.android.adsdk.internal;

import com.smart.android.adsdk.AdError;

interface RemoteAdConfigResolver {
    Cancellable resolve(String channelId, String requestId, Callback callback);

    interface Callback {
        default void onAuthorized(FlowAuthorizedConfig config) {
        }

        void onResolved(RemoteAdConfigResult result);

        void onError(AdError error);
    }
}
