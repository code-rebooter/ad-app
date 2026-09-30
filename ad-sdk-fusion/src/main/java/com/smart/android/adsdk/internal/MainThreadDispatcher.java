package com.smart.android.adsdk.internal;

import android.os.Handler;
import android.os.Looper;

final class MainThreadDispatcher implements CallbackDispatcher {
    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override
    public void dispatch(Runnable action) {
        handler.post(action);
    }
}
