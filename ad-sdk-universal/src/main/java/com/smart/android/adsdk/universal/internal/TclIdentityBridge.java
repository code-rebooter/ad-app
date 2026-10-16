package com.smart.android.adsdk.universal.internal;

import java.util.Locale;

/** Only the new module's vendor classes target this bridge. */
public final class TclIdentityBridge {
    private static volatile TclIdentity identity;
    private TclIdentityBridge() {}
    static synchronized void install(TclIdentity value) {
        // SdkRuntime calls this only before a round, after native work becomes idle.
        identity = java.util.Objects.requireNonNull(value);
    }
    private static TclIdentity current() {
        TclIdentity value = identity;
        if (value == null) throw new IllegalStateException("SDK key configuration has not completed");
        return value;
    }
    public static String getRegistrationCacheKey() { return current().cacheKey(); }
    public static String getPackageName() { return current().packageName; }
    public static String getAppName() { return current().appName; }
    public static String getVersionName() { return current().versionName; }
    public static String getVersionCode() { return Integer.toString(current().versionCode); }
    public static String getSignatureMd5() { return current().signatureMd5.toUpperCase(Locale.ROOT); }
    public static String getBiSignatureMd5() { return current().signatureMd5.toLowerCase(Locale.ROOT); }
    public static String getAppKey() { return current().appKey; }
    public static int getProjectId() { return current().projectId; }
    public static String getProjectIdString() { return Integer.toString(current().projectId); }
    public static String getPartnerName() { return current().partnerName; }
}
