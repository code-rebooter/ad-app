package com.smart.android.googlevideoad.internal;

import com.smart.android.googlevideoad.AdError;

interface GamConfigResolver {
    Cancellable resolve(String channelId, Callback callback);

    interface Callback {
        void onResolved(GamResolveResult result);

        void onError(AdError error);
    }
}
