package com.smart.android.adsdk.internal;

import android.app.Application;
import android.content.Context;
import android.os.Build;
import android.os.Process;
import android.util.Log;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

final class SystemUidStorageCompat {
    private static final String TAG = "SystemUidCompat";
    private static final int SYSTEM_UID = 1000;
    private static final int MAX_WEBVIEW_SUFFIX_LENGTH = 120;
    private static final AtomicBoolean WEBVIEW_SUFFIX_ATTEMPTED = new AtomicBoolean(false);
    private static final AtomicBoolean WEBVIEW_PREWARMED = new AtomicBoolean(false);

    private SystemUidStorageCompat() {
    }

    static boolean isSystemUid() {
        try {
            return Process.myUid() == SYSTEM_UID;
        } catch (RuntimeException error) {
            return false;
        }
    }

    static Context resolveGoogleSdkContext(Context context) {
        if (context == null || !isSystemUid()) {
            return context;
        }
        if (Build.VERSION.SDK_INT < 24 || context.isDeviceProtectedStorage()) {
            return context;
        }
        try {
            Context deviceContext = createDeviceProtectedStorageContext(context);
            if (deviceContext != null && deviceContext.isDeviceProtectedStorage()) {
                Log.i(TAG, "Google SDK switched to device protected storage context");
                return deviceContext;
            }
        } catch (RuntimeException error) {
            Log.w(TAG, "Unable to create device protected context for Google SDK", error);
        }
        return context;
    }

    static void prepareSdkEntry(Context context, String source) {
        if (!isSystemUid()) {
            return;
        }
        prepareWebViewDataDirectory(context, source);
    }

    static void prepareGoogleWebView(Context context, String source) {
        if (!isSystemUid()) {
            return;
        }
        prepareWebViewDataDirectory(context, source);
        if (WEBVIEW_PREWARMED.get()) {
            return;
        }
        try {
            Class<?> factoryClass = Class.forName("android.webkit.WebViewFactory");
            Field providerField = factoryClass.getDeclaredField("sProviderInstance");
            providerField.setAccessible(true);
            Object provider = providerField.get(null);
            if (provider != null) {
                Log.i(TAG, "WebViewFactory already initialized for " + source);
                WEBVIEW_PREWARMED.set(true);
                return;
            }

            Method providerClassMethod;
            if (Build.VERSION.SDK_INT > 22) {
                providerClassMethod = factoryClass.getDeclaredMethod("getProviderClass");
            } else if (Build.VERSION.SDK_INT == 22) {
                providerClassMethod = factoryClass.getDeclaredMethod("getFactoryClass");
            } else {
                return;
            }
            providerClassMethod.setAccessible(true);
            Class<?> providerClass = (Class<?>) providerClassMethod.invoke(factoryClass);
            Class<?> delegateClass = Class.forName("android.webkit.WebViewDelegate");
            Constructor<?> delegateConstructor = delegateClass.getDeclaredConstructor();
            delegateConstructor.setAccessible(true);
            Object delegate = delegateConstructor.newInstance();

            Object initializedProvider;
            if (Build.VERSION.SDK_INT < 26) {
                Constructor<?> providerConstructor = providerClass.getConstructor(delegateClass);
                providerConstructor.setAccessible(true);
                initializedProvider = providerConstructor.newInstance(delegate);
            } else {
                Field createMethodField = factoryClass.getDeclaredField("CHROMIUM_WEBVIEW_FACTORY_METHOD");
                createMethodField.setAccessible(true);
                String createMethodName = (String) createMethodField.get(null);
                if (createMethodName == null) {
                    createMethodName = "create";
                }
                Method createMethod = providerClass.getMethod(createMethodName, delegateClass);
                initializedProvider = createMethod.invoke(null, delegate);
            }

            if (initializedProvider != null) {
                providerField.set(null, initializedProvider);
                WEBVIEW_PREWARMED.set(true);
                Log.i(TAG, "WebViewFactory prewarmed for " + source);
            }
        } catch (Throwable error) {
            Log.w(TAG, "WebViewFactory prewarm failed for " + source, error);
        }
    }

    static String buildWebViewDataDirectorySuffix(String packageName, String processName) {
        String packagePart = sanitizeForWebViewSuffix(packageName);
        String processPart = sanitizeForWebViewSuffix(processName);
        String base = processPart.isEmpty() ? packagePart : processPart;
        if (base.isEmpty()) {
            base = "unknown";
        } else if (!packagePart.isEmpty() && !base.startsWith(packagePart)) {
            base = packagePart + "_" + base;
        }
        String suffix = "ad_sdk_system_uid_" + base;
        return suffix.length() > MAX_WEBVIEW_SUFFIX_LENGTH
            ? suffix.substring(0, MAX_WEBVIEW_SUFFIX_LENGTH)
            : suffix;
    }

    private static void prepareWebViewDataDirectory(Context context, String source) {
        if (context == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            return;
        }
        if (!WEBVIEW_SUFFIX_ATTEMPTED.compareAndSet(false, true)) {
            return;
        }

        String suffix = buildWebViewDataDirectorySuffix(
            context.getPackageName(),
            currentProcessName(context)
        );
        try {
            Class<?> webViewClass = Class.forName("android.webkit.WebView");
            Method method = webViewClass.getMethod("setDataDirectorySuffix", String.class);
            method.invoke(null, suffix);
            Log.i(TAG, "WebView data directory suffix set for " + source + ", suffix=" + suffix);
        } catch (Throwable error) {
            if (isAlreadyConfigured(error)) {
                Log.i(TAG, "WebView data directory suffix already configured before " + source);
            } else {
                Log.w(TAG, "Unable to set WebView data directory suffix for " + source, error);
            }
        }
    }

    private static String currentProcessName(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                String processName = Application.getProcessName();
                if (processName != null && !processName.trim().isEmpty()) {
                    return processName;
                }
            } catch (RuntimeException ignored) {
            }
        }
        return context.getPackageName();
    }

    private static boolean isAlreadyConfigured(Throwable error) {
        Throwable current = error;
        while (current != null) {
            String message = current.getMessage();
            if (message != null) {
                String normalized = message.toLowerCase(Locale.US);
                if (normalized.contains("data directory suffix")
                    && (normalized.contains("already") || normalized.contains("initialized"))) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }

    private static String sanitizeForWebViewSuffix(String value) {
        if (value == null) {
            return "";
        }
        String normalized = value.trim().toLowerCase(Locale.US);
        if (normalized.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder(normalized.length());
        boolean lastWasSeparator = false;
        for (int index = 0; index < normalized.length(); index += 1) {
            char item = normalized.charAt(index);
            boolean alphaNumeric = (item >= 'a' && item <= 'z') || (item >= '0' && item <= '9');
            if (alphaNumeric) {
                builder.append(item);
                lastWasSeparator = false;
            } else if (!lastWasSeparator && builder.length() > 0) {
                builder.append('_');
                lastWasSeparator = true;
            }
        }
        int length = builder.length();
        if (length > 0 && builder.charAt(length - 1) == '_') {
            builder.deleteCharAt(length - 1);
        }
        return builder.toString();
    }

    private static Context createDeviceProtectedStorageContext(Context context) {
        try {
            Method method = Context.class.getDeclaredMethod("createDeviceProtectedStorageContext");
            method.setAccessible(true);
            return (Context) method.invoke(context);
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("createDeviceProtectedStorageContext unavailable", error);
        }
    }
}
