package com.smart.android.tclad;

/** Compatibility holder. TCL SDK telemetry stays disabled; VAST beacons are not controlled by these switches. */
public final class AdReportSwitchConfig {
    private boolean privacyAgreed;
    private boolean uxpEnabled;
    private boolean errorStatisticsEnabled;
    public void setPrivacyAgreed(boolean value) { privacyAgreed = value; }
    public boolean isPrivacyAgreed() { return privacyAgreed; }
    public void setUxpEnabled(boolean value) { uxpEnabled = value; }
    public boolean isUxpEnabled() { return uxpEnabled; }
    public void setErrorStatisticsEnabled(boolean value) { errorStatisticsEnabled = value; }
    public boolean isErrorStatisticsEnabled() { return errorStatisticsEnabled; }
}
