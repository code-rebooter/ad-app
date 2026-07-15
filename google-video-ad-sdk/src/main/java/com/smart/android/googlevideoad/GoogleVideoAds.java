package com.smart.android.googlevideoad;

import android.content.Context;
import android.view.ViewGroup;
import com.smart.android.googlevideoad.internal.SdkRuntime;
import java.util.Objects;

public final class GoogleVideoAds {
    private GoogleVideoAds() {
    }

    public static void initialize(
        Context context,
        SdkConfig config,
        InitializationListener listener
    ) {
        Objects.requireNonNull(listener, "listener must not be null");
        Objects.requireNonNull(config, "config must not be null");
        Objects.requireNonNull(context, "context must not be null");
        RuntimeHolder.RUNTIME.initialize(context, config, listener);
    }

    public static AdSession play(
        ViewGroup container,
        AdRequest request,
        AdListener listener
    ) {
        Objects.requireNonNull(listener, "listener must not be null");
        Objects.requireNonNull(request, "request must not be null");
        Objects.requireNonNull(container, "container must not be null");
        return RuntimeHolder.RUNTIME.play(container, request, listener);
    }

    private static final class RuntimeHolder {
        private static final SdkRuntime RUNTIME = SdkRuntime.createDefault();
    }
}
