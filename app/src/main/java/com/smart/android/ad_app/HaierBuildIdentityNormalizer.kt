package com.smart.android.ad_app

import android.os.Build
import java.lang.reflect.Method
import java.util.Locale

internal object HaierBuildIdentityNormalizer {
    const val FIXED_BRAND = "X96"
    const val FIXED_MANUFACTURER = "X96"
    const val FIXED_DEVICE = HaierDeviceModelNormalizer.FIXED_MODEL
    const val FIXED_PRODUCT = HaierDeviceModelNormalizer.FIXED_MODEL

    @Volatile
    private var systemPropertyGetMethod: Method? = null

    fun androidVersion(): String {
        return HaierUserAgentNormalizer.canonicalAndroidVersionFor(Build.VERSION.SDK_INT)
            ?: Build.VERSION.RELEASE.orEmpty()
    }

    fun sdkInt(): Int = Build.VERSION.SDK_INT

    fun buildId(): String {
        return HaierUserAgentNormalizer.canonicalBuildIdFor(Build.VERSION.SDK_INT)
            ?: Build.ID.orEmpty()
    }

    fun brand(): String = FIXED_BRAND

    fun manufacturer(): String = FIXED_MANUFACTURER

    fun device(): String = FIXED_DEVICE

    fun product(): String = FIXED_PRODUCT

    fun systemVersionLabel(): String = "Android : ${androidVersion()}"

    fun systemProperty(name: String?, defaultValue: String? = null): String? {
        val key = name?.trim().orEmpty()
        if (key.isEmpty()) return defaultValue
        canonicalSystemPropertyValue(key)?.let { return it }
        return readActualSystemProperty(key, defaultValue) ?: defaultValue
    }

    private fun canonicalSystemPropertyValue(name: String): String? {
        return when (name.lowercase(Locale.ROOT)) {
            "ro.product.model",
            "ro.product.cust.model",
            "ro.product.vendor.model",
            "ro.product.system.model" -> HaierDeviceModelNormalizer.FIXED_MODEL

            "ro.product.name",
            "ro.build.product" -> FIXED_PRODUCT

            "ro.product.device",
            "ro.product.system.device",
            "ro.product.vendor.device" -> FIXED_DEVICE

            "ro.product.brand",
            "ro.product.system.brand",
            "ro.product.vendor.brand" -> FIXED_BRAND

            "ro.product.manufacturer",
            "ro.product.system.manufacturer",
            "ro.product.vendor.manufacturer" -> FIXED_MANUFACTURER

            "ro.build.version.release" -> androidVersion()
            "ro.build.version.sdk",
            "ro.system.build.version.sdk",
            "ro.vendor.build.version.sdk" -> sdkInt().toString()

            "ro.build.version.incremental",
            "ro.build.id",
            "ro.build.display.id",
            "ro.software.version_id" -> buildId()

            else -> null
        }
    }

    private fun readActualSystemProperty(name: String, defaultValue: String?): String? {
        return runCatching {
            val method = cachedSystemPropertyGetMethod()
            method.invoke(null, name, defaultValue) as? String
        }.getOrDefault(defaultValue)
    }

    private fun cachedSystemPropertyGetMethod(): Method {
        systemPropertyGetMethod?.let { return it }
        return synchronized(this) {
            systemPropertyGetMethod?.let { return@synchronized it }
            Class.forName("android.os.SystemProperties")
                .getMethod("get", String::class.java, String::class.java)
                .also { systemPropertyGetMethod = it }
        }
    }
}
