package com.smart.android.googlevideoad;

import android.content.Context;
import android.view.ViewGroup;
import com.smart.android.googlevideoad.internal.SdkRuntime;

public final class GoogleVideoAds {
    private GoogleVideoAds() {
    }

    public static void initialize(
        Context context,
        SdkConfig config,
        InitializationListener listener
    ) {
        RuntimeHolder.RUNTIME.initialize(context, config, listener);
    }

    public static AdSession play(
        ViewGroup container,
        AdRequest request,
        AdListener listener
    ) {
        return RuntimeHolder.RUNTIME.play(container, request, listener);
    }

    private static final class RuntimeHolder {
        private static final SdkRuntime RUNTIME = SdkRuntime.createDefault();
    }
}
