package com.smart.android.adsdk.internal;

import com.smart.android.adsdk.modern.logging.PropertyLog;

final class SdkLog {
    // At most 3 KB of UTF-8 text per chunk, including Chinese characters.
    private static final int CHUNK_CHARS = 1_000;

    private SdkLog() {}

    static boolean isEnabled() {
        return PropertyLog.isEnabled();
    }

    static void d(String tag, String message) { write(3, tag, message, null); }
    static void i(String tag, String message) { write(4, tag, message, null); }
    static void w(String tag, String message) { write(5, tag, message, null); }
    static void w(String tag, String message, Throwable error) { write(5, tag, message, error); }
    static void e(String tag, String message, Throwable error) { write(6, tag, message, error); }

    private static void write(int priority, String tag, String message, Throwable error) {
        try {
            if (!isEnabled()) {
                return;
            }
            String text = String.valueOf(message);
            if (error != null) {
                text += "\n" + PropertyLog.getStackTraceString(error);
            }
            int chunks = Math.max(1, (text.length() + CHUNK_CHARS - 1) / CHUNK_CHARS);
            for (int i = 0; i < chunks; i++) {
                String prefix = chunks == 1 ? "" : "[" + (i + 1) + "/" + chunks + "] ";
                PropertyLog.println(priority, tag, prefix + text.substring(
                    i * CHUNK_CHARS, Math.min(text.length(), (i + 1) * CHUNK_CHARS)));
            }
        } catch (RuntimeException ignored) {
            // Diagnostics must never interrupt ad requests or callbacks.
        }
    }
}
