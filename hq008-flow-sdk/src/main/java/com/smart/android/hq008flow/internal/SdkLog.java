package com.smart.android.hq008flow.internal;

import android.util.Log;

import com.smart.android.hq008flow.BuildConfig;

import java.lang.reflect.Method;

public final class SdkLog {
    private static final String PROPERTY_NAME = "persist.sys.ad.log";
    private static final String DEFAULT_VALUE = "false";

    private SdkLog() {
    }

    public static boolean isEnabled() {
        if (BuildConfig.DEBUG) {
            return true;
        }
        String value = readSystemProperty(PROPERTY_NAME, DEFAULT_VALUE);
        return "true".equalsIgnoreCase(value == null ? "" : value.trim());
    }

    public static void i(String tag, String message) {
        if (isEnabled()) {
            Log.i(tag, message);
        }
    }

    public static void w(String tag, String message) {
        if (isEnabled()) {
            Log.w(tag, message);
        }
    }

    public static void w(String tag, String message, Throwable error) {
        if (isEnabled()) {
            Log.w(tag, message, error);
        }
    }

    public static void e(String tag, String message, Throwable error) {
        if (isEnabled()) {
            Log.e(tag, message, error);
        }
    }

    private static String readSystemProperty(String key, String fallback) {
        try {
            Class<?> systemProperties = Class.forName("android.os.SystemProperties");
            Method get = systemProperties.getMethod("get", String.class, String.class);
            Object value = get.invoke(null, key, fallback);
            return value instanceof String ? (String) value : fallback;
        } catch (Throwable ignored) {
            return System.getProperty(key, fallback);
        }
    }
}
