package com.smart.android.adsdk.universal;

import android.content.Context;
import android.view.ViewGroup;
import com.smart.android.adsdk.universal.internal.SdkRuntime;
import java.util.Objects;

public final class AdSdk {
    private AdSdk() {}
    /** Returns this SDK's release version without requiring initialization. */
    public static String getSdkVersion() {
        return BuildConfig.SDK_VERSION;
    }
    public static void initialize(Context context, String sdkKey, InitializationListener listener) {
        initialize(context, sdkKey, new SdkConfig.Builder().build(), listener);
    }
    public static void initialize(Context context, String sdkKey, SdkConfig config, InitializationListener listener) {
        Objects.requireNonNull(context, "context"); Objects.requireNonNull(config, "config"); Objects.requireNonNull(listener, "listener");
        if (sdkKey == null || sdkKey.trim().isEmpty()) throw new IllegalArgumentException("sdkKey must not be blank");
        Holder.RUNTIME.initialize(context, sdkKey.trim(), config, listener);
    }
    /** Returns whether key configuration is installed and the SDK is ready to accept play calls. */
    public static boolean isInitialized() {
        return Holder.RUNTIME.isInitialized();
    }
    public static AdSession play(ViewGroup container, AdRequest request, AdListener listener) {
        return Holder.RUNTIME.play(Objects.requireNonNull(container), Objects.requireNonNull(request), Objects.requireNonNull(listener));
    }
    private static final class Holder { static final SdkRuntime RUNTIME = new SdkRuntime(); }
}
