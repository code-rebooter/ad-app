package com.smart.android.ad_app

import android.annotation.SuppressLint
import android.os.Build
import android.util.Log

object WebViewProviderHook {

    @Volatile
    private var hookAttempted = false

    @SuppressLint("SoonBlockedPrivateApi")
    fun ensureHooked() {
        if (hookAttempted) {
            return
        }

        synchronized(this) {
            if (hookAttempted) {
                return
            }
            hookAttempted = true
        }

        val sdkInt = Build.VERSION.SDK_INT
        try {
            val factoryClass = Class.forName("android.webkit.WebViewFactory")
            val field = factoryClass.getDeclaredField("sProviderInstance")
            field.isAccessible = true
            var providerInstance = field.get(null)
            if (providerInstance != null) {
                return
            }

            val getProviderClassMethod = when {
                sdkInt > 22 -> factoryClass.getDeclaredMethod("getProviderClass")
                sdkInt == 22 -> factoryClass.getDeclaredMethod("getFactoryClass")
                else -> return
            }
            getProviderClassMethod.isAccessible = true
            val factoryProviderClass = getProviderClassMethod.invoke(factoryClass) as Class<*>

            val delegateClass = Class.forName("android.webkit.WebViewDelegate")
            val delegateConstructor = delegateClass.getDeclaredConstructor()
            delegateConstructor.isAccessible = true

            providerInstance = if (sdkInt < 26) {
                val providerConstructor = factoryProviderClass.getConstructor(delegateClass)
                providerConstructor.isAccessible = true
                providerConstructor.newInstance(delegateConstructor.newInstance())
            } else {
                val chromiumFactoryMethodField =
                    factoryClass.getDeclaredField("CHROMIUM_WEBVIEW_FACTORY_METHOD")
                chromiumFactoryMethodField.isAccessible = true
                val methodName = chromiumFactoryMethodField.get(null) as? String ?: "create"
                val staticFactory = factoryProviderClass.getMethod(methodName, delegateClass)
                staticFactory.invoke(null, delegateConstructor.newInstance())
            }

            if (providerInstance != null) {
                field.set(null, providerInstance)
            } else {
                Log.e("WebViewHook", "Failed to create sProviderInstance")
            }
        } catch (t: Throwable) {
            Log.e("WebViewHook", "Error during WebView hooking", t)
        }
    }
}
