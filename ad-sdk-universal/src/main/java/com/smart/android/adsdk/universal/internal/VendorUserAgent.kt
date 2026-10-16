package com.smart.android.adsdk.universal.internal

import android.os.Build

/** The existing experimental host's supported-flavor normalization, without a host BuildConfig dependency. */
internal object VendorUserAgent {
    @JvmStatic
    @Synchronized
    fun effective(): String {
        val observed = System.getProperty("http.agent").orEmpty()
        val result = HaierUserAgentNormalizer.normalize(observed, Build.VERSION.SDK_INT)
        if (result.changed) System.setProperty("http.agent", result.effectiveUa)
        return System.getProperty("http.agent").orEmpty().ifBlank { result.effectiveUa }
    }
}
