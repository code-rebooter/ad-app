package com.smart.android.adsdk.universal.internal;

/** The serial platform session owns this process-wide mode, including worker-thread boundaries. */
public final class NativePolicy {
    private static volatile boolean google = true;
    private NativePolicy() {}
    public static boolean isGoogle() { return google; }
    static void enter(String provider) {
        if (!"google".equals(provider) && !"tcl".equals(provider)) throw new IllegalArgumentException("Unknown provider");
        google = "google".equals(provider);
    }
}
