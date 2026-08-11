package com.smart.android.adsdk.internal;

import android.content.Context;
import android.os.Build;
import android.os.Process;
import android.util.Log;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

final class SystemUidStorageCompat {
    private static final String TAG = "SystemUidCompat";
    private static final int SYSTEM_UID = 1000;

    private SystemUidStorageCompat() {
    }

    static boolean isSystemUid() {
        return Process.myUid() == SYSTEM_UID;
    }

    static Context resolveGoogleSdkContext(Context context) {
        if (context == null) {
            return null;
        }
        if (!isSystemUid()) {
            return context;
        }
        if (Build.VERSION.SDK_INT < 24) {
            Log.i(TAG, "Google SDK context unchanged, device protected storage unsupported, " + describeContext(context));
            return context;
        }
        if (!context.isDeviceProtectedStorage()) {
            Log.i(TAG, "Google SDK context unchanged, already credential protected, " + describeContext(context));
            return context;
        }
        try {
            Context credentialContext = createCredentialProtectedStorageContext(context);
            if (credentialContext != null && !credentialContext.isDeviceProtectedStorage()) {
                Log.i(
                    TAG,
                    "Google SDK context switched from device protected to credential protected, original="
                        + describeContext(context)
                        + ", resolved="
                        + describeContext(credentialContext)
                );
                return credentialContext;
            }
            Log.w(TAG, "Unable to switch Google SDK context, resolved context is still device protected");
        } catch (RuntimeException error) {
            Log.w(TAG, "Unable to create credential protected context for Google SDK", error);
        }
        return context;
    }

    static void prepareGoogleWebView(String source) {
        if (!isSystemUid()) {
            return;
        }
        try {
            Class<?> factoryClass = Class.forName("android.webkit.WebViewFactory");
            Field providerField = factoryClass.getDeclaredField("sProviderInstance");
            providerField.setAccessible(true);
            Object provider = providerField.get(null);
            if (provider != null) {
                Log.i(TAG, "WebViewFactory already initialized for " + source);
                return;
            }

            Method providerClassMethod;
            if (Build.VERSION.SDK_INT > 22) {
                providerClassMethod = factoryClass.getDeclaredMethod("getProviderClass");
            } else if (Build.VERSION.SDK_INT == 22) {
                providerClassMethod = factoryClass.getDeclaredMethod("getFactoryClass");
            } else {
                Log.i(TAG, "WebViewFactory prewarm skipped for sdk=" + Build.VERSION.SDK_INT + ", source=" + source);
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
                Log.i(TAG, "WebViewFactory prewarmed for " + source);
            }
        } catch (Throwable error) {
            Log.w(TAG, "WebViewFactory prewarm failed for " + source, error);
        }
    }

    private static String describeContext(Context context) {
        StringBuilder builder = new StringBuilder();
        builder.append("uid=").append(Process.myUid());
        builder.append(",context=").append(context.getClass().getName());
        builder.append(",package=").append(context.getPackageName());
        if (Build.VERSION.SDK_INT >= 24) {
            builder.append(",deviceProtected=").append(context.isDeviceProtectedStorage());
        }
        return builder.toString();
    }

    private static Context createCredentialProtectedStorageContext(Context context) {
        try {
            Method method = Context.class.getDeclaredMethod("createCredentialProtectedStorageContext");
            method.setAccessible(true);
            return (Context) method.invoke(context);
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("createCredentialProtectedStorageContext unavailable", error);
        }
    }

}
