package com.smart.android.hq008flow.internal;

import com.smart.android.hq008flow.logging.PropertyLog;

public final class SdkLog {
    private SdkLog() {
    }

    public static boolean isEnabled() {
        return PropertyLog.isEnabled();
    }

    public static void i(String tag, String message) {
        if (isEnabled()) {
            PropertyLog.i(tag, message);
        }
    }

    public static void w(String tag, String message) {
        if (isEnabled()) {
            PropertyLog.w(tag, message);
        }
    }

    public static void w(String tag, String message, Throwable error) {
        if (isEnabled()) {
            PropertyLog.w(tag, message, error);
        }
    }

    public static void e(String tag, String message, Throwable error) {
        if (isEnabled()) {
            PropertyLog.e(tag, message, error);
        }
    }

}
