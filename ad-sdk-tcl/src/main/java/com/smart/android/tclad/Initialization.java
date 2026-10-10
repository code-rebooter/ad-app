package com.smart.android.tclad;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

public final class Initialization {
    private static volatile Context applicationContext;
    private static volatile AdTagProvider provider;
    private Initialization() {}
    /** Supply the original getALLConfig response (or equivalent settings) before init. */
    public static void setOriginalConfiguration(String json) {
        com.smart.android.tclad.internal.NativeRuntime.setConfiguration(json);
    }
    public static void init(Context context) { init(context, null, null); }
    public static void init(Context context, AdReportSwitchConfig switches, MediaAdInitListener listener) {
        if (context == null) throw new IllegalArgumentException("context is required");
        com.smart.android.tclad.internal.NativeRuntime.setReportSwitches(switches);
        Context app = context.getApplicationContext();
        Handler main = new Handler(Looper.getMainLooper());
        com.smart.android.tclad.internal.NativeRuntime.initializeAsync(app, Ad.get().isLogEnabled(), () -> {
            applicationContext = app;
            if (listener != null) main.post(listener::onInitComplete);
        }, error -> {
            if (listener != null) main.post(() -> listener.onInitError(900));
        });
    }
    public static boolean isHasInit() { return applicationContext != null; }
    public static void setAdTagProvider(AdTagProvider value) { provider = value; }
    static AdTagProvider getAdTagProvider() { return provider; }
}
