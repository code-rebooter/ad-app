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
        return normalizeAndroidVersion(Build.VERSION.RELEASE, Build.VERSION.SDK_INT)
    }

    fun sdkInt(): Int = Build.VERSION.SDK_INT

    fun buildId(): String {
        return normalizeBuildId(Build.ID, Build.VERSION.SDK_INT)
    }

    fun model(): String = normalizeModel(Build.MODEL)

    fun brand(): String = normalizeBrand(Build.BRAND)

    fun manufacturer(): String = normalizeManufacturer(Build.MANUFACTURER)

    fun device(): String = normalizeDevice(Build.DEVICE)

    fun product(): String = normalizeProduct(Build.PRODUCT)

    fun normalizeModel(raw: String?): String = normalizeIdentityValue(
        raw,
        HaierDeviceModelNormalizer.FIXED_MODEL
    )

    fun normalizeBrand(raw: String?): String = normalizeIdentityValue(raw, FIXED_BRAND)

    fun normalizeManufacturer(raw: String?): String = normalizeIdentityValue(raw, FIXED_MANUFACTURER)

    fun normalizeDevice(raw: String?): String = normalizeIdentityValue(raw, FIXED_DEVICE)

    fun normalizeProduct(raw: String?): String = normalizeIdentityValue(raw, FIXED_PRODUCT)

    fun normalizeAndroidVersion(raw: String?, sdkInt: Int): String {
        val value = raw?.trim().orEmpty()
        return if (HaierUserAgentNormalizer.isCompatibleAndroidVersion(value, sdkInt)) {
            value
        } else {
            HaierUserAgentNormalizer.canonicalAndroidVersionFor(sdkInt) ?: value
        }
    }

    fun normalizeBuildId(raw: String?, sdkInt: Int): String {
        val value = raw?.trim().orEmpty()
        return if (HaierUserAgentNormalizer.isCompatibleBuildId(value, sdkInt)) {
            value
        } else {
            HaierUserAgentNormalizer.canonicalBuildIdFor(sdkInt) ?: value
        }
    }

    fun systemVersionLabel(): String = "Android : ${androidVersion()}"

    fun systemProperty(name: String?, defaultValue: String? = null): String? {
        val key = name?.trim().orEmpty()
        if (key.isEmpty()) return defaultValue
        canonicalSystemPropertyValue(key)?.let { return it }
        return readActualSystemProperty(key, defaultValue) ?: defaultValue
    }

    private fun canonicalSystemPropertyValue(name: String): String? {
        val actual = readActualSystemProperty(name, null)
        return when (name.lowercase(Locale.ROOT)) {
            "ro.product.model",
            "ro.product.cust.model",
            "ro.product.vendor.model",
            "ro.product.system.model" -> normalizeModel(actual)

            "ro.product.name",
            "ro.build.product" -> normalizeProduct(actual)

            "ro.product.device",
            "ro.product.system.device",
            "ro.product.vendor.device" -> normalizeDevice(actual)

            "ro.product.brand",
            "ro.product.system.brand",
            "ro.product.vendor.brand" -> normalizeBrand(actual)

            "ro.product.manufacturer",
            "ro.product.system.manufacturer",
            "ro.product.vendor.manufacturer" -> normalizeManufacturer(actual)

            "ro.build.version.release" -> androidVersion()
            "ro.build.version.sdk",
            "ro.system.build.version.sdk",
            "ro.vendor.build.version.sdk" -> sdkInt().toString()

            "ro.build.version.incremental",
            "ro.build.id",
            "ro.build.display.id",
            "ro.software.version_id" -> normalizeBuildId(actual, sdkInt())

            else -> null
        }
    }

    private fun normalizeIdentityValue(raw: String?, fallback: String): String {
        val value = raw?.trim().orEmpty()
        return if (value.isBlank() || value.any { it.code < 32 || it.code == 127 } ||
            HaierDeviceModelNormalizer.isGeneric(value)) {
            fallback
        } else {
            value
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
