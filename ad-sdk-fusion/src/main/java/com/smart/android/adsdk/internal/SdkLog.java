package com.smart.android.adsdk.internal;

import android.util.Log;
import java.lang.reflect.Method;

final class SdkLog {
    private static final String PROPERTY = "persist.sys.ad.log";
    // At most 3 KB of UTF-8 text per chunk, including Chinese characters.
    private static final int CHUNK_CHARS = 1_000;
    private static volatile boolean debugLogging;

    private SdkLog() {}

    static void configure(boolean enabled) {
        debugLogging = enabled;
    }

    static boolean isEnabled() {
        String value = "";
        try {
            Class<?> type = Class.forName("android.os.SystemProperties");
            Method get = type.getMethod("get", String.class, String.class);
            value = (String) get.invoke(null, PROPERTY, "");
        } catch (Exception ignored) {
            value = System.getProperty(PROPERTY, "");
        }
        if (value != null && !value.trim().isEmpty()) {
            return "true".equalsIgnoreCase(value.trim());
        }
        return debugLogging;
    }

    static void d(String tag, String message) { write(Log.DEBUG, tag, message, null); }
    static void i(String tag, String message) { write(Log.INFO, tag, message, null); }
    static void w(String tag, String message) { write(Log.WARN, tag, message, null); }
    static void w(String tag, String message, Throwable error) { write(Log.WARN, tag, message, error); }
    static void e(String tag, String message, Throwable error) { write(Log.ERROR, tag, message, error); }

    private static void write(int priority, String tag, String message, Throwable error) {
        try {
            if (!isEnabled()) {
                return;
            }
            String text = String.valueOf(message);
            if (error != null) {
                text += "\n" + Log.getStackTraceString(error);
            }
            int chunks = Math.max(1, (text.length() + CHUNK_CHARS - 1) / CHUNK_CHARS);
            for (int i = 0; i < chunks; i++) {
                String prefix = chunks == 1 ? "" : "[" + (i + 1) + "/" + chunks + "] ";
                Log.println(priority, tag, prefix + text.substring(
                    i * CHUNK_CHARS, Math.min(text.length(), (i + 1) * CHUNK_CHARS)));
            }
        } catch (RuntimeException ignored) {
            // Diagnostics must never interrupt ad requests or callbacks.
        }
    }
}
