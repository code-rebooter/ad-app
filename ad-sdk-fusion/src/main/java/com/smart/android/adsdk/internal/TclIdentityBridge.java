package com.smart.android.adsdk.internal;

import java.util.Locale;

/** Application identity for the TCL registration, shared by the patched TCL AAR and adapter. */
public final class TclIdentityBridge {
    private static final String PACKAGE_NAME = "com.google.android.adhq1001";
    // Application metadata supplied for the same TCL registration; never read the host's label.
    private static final String APP_NAME = "Adhq1001HISIA9";
    private static final String VERSION_NAME = "2.0.10";
    private static final String VERSION_CODE = "10";
    private static final String SIGNATURE_MD5 = "D2A9B2A8A9E0AF740267C0BC356DC1A4";
    private static final String APP_KEY = "DeB07Nx4JEnYX/0t4Dn4o2LnnF/WL8yyIC6qxfByHqlQ/FPaPWYTM+t6I+dq2RnjtJtjpDjNT/RFPcAkPxaEpQ==";
    private static final int PROJECT_ID = 213;
    private static final String PARTNER_NAME = "chhkj_1";

    private TclIdentityBridge() {}

    public static String getPackageName() { return PACKAGE_NAME; }
    public static String getAppName() { return APP_NAME; }
    public static String getVersionName() { return VERSION_NAME; }
    public static String getVersionCode() { return VERSION_CODE; }
    public static String getSignatureMd5() { return SIGNATURE_MD5; }
    // The TCL BI implementation originally emits lowercase, unlike its authorization getter.
    public static String getBiSignatureMd5() { return SIGNATURE_MD5.toLowerCase(Locale.ROOT); }
    public static String getAppKey() { return APP_KEY; }
    public static String getPartnerName() { return PARTNER_NAME; }
    public static int getProjectId() { return PROJECT_ID; }
    public static String getProjectIdString() { return Integer.toString(PROJECT_ID); }

}
