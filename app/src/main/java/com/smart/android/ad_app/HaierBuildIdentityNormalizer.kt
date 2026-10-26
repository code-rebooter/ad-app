package com.smart.android.ad_app

import android.os.Build
import java.lang.reflect.Method
import java.util.Locale

internal data class HaierUaIdentity(
    val androidVersion: String,
    val model: String,
    val buildId: String
)

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

    fun userAgentIdentity(sdkInt: Int = Build.VERSION.SDK_INT): HaierUaIdentity =
        normalizeUserAgentIdentity(Build.VERSION.RELEASE, Build.MODEL, Build.ID, sdkInt)

    fun normalizeUserAgentIdentity(
        rawAndroidVersion: String?,
        rawModel: String?,
        rawBuildId: String?,
        sdkInt: Int
    ): HaierUaIdentity = HaierUaIdentity(
        androidVersion = normalizeAndroidVersion(rawAndroidVersion, sdkInt),
        model = normalizeModel(rawModel),
        buildId = normalizeBuildId(rawBuildId, sdkInt)
    )

    fun brand(): String = normalizeBrand(Build.BRAND)

    fun manufacturer(): String = normalizeManufacturer(Build.MANUFACTURER)

    fun device(): String = normalizeDevice(Build.DEVICE)

    fun product(): String = normalizeProduct(Build.PRODUCT)

    fun hardware(): String = normalizeHardware(Build.HARDWARE)

    private val fingerprintFormat = Regex(
        "^([^/:\\s]+)/([^/:\\s]+)/([^/:\\s]+):([^/:\\s]+)/([^/:\\s]+)/([^/:\\s]+):([^/:\\s]+)/([^/:\\s]+)$"
    )
    private val fingerprintPropertyNames = setOf(
        "ro.build.fingerprint", "ro.system.build.fingerprint", "ro.system_ext.build.fingerprint",
        "ro.product.build.fingerprint", "ro.vendor.build.fingerprint", "ro.odm.build.fingerprint",
        "ro.bootimage.build.fingerprint"
    )
    private val emulatorHardwareMarkers = setOf("generic", "emulator", "ranchu", "goldfish")

    fun fingerprint(): String = correctFingerprint(Build.FINGERPRINT)

    fun correctFingerprint(raw: String?): String = normalizeFingerprint(
        raw, brand(), product(), device(), androidVersion(), buildId(),
        Build.VERSION.INCREMENTAL, Build.TYPE, Build.TAGS
    )

    /** Rebuild a missing/malformed value from native build components, as AOSP does.
     * A complete original retains its incremental/type/tags; no labels are invented.
     * An unusable source fails explicitly instead of returning inconsistent input.
     */
    internal fun normalizeFingerprint(
        raw: String?, brand: String, product: String, device: String, release: String, buildId: String,
        nativeIncremental: String? = null, nativeType: String? = null, nativeTags: String? = null
    ): String {
        val parts = fingerprintFormat.matchEntire(raw.orEmpty())?.groupValues
        val incremental = parts?.get(6)?.takeIf(::isFingerprintComponent) ?: nativeIncremental
        val type = parts?.get(7)?.takeIf(::isFingerprintComponent) ?: nativeType
        val tags = parts?.get(8)?.takeIf(::isFingerprintComponent) ?: nativeTags
        val components = linkedMapOf(
            "brand" to brand, "product" to product, "device" to device, "release" to release,
            "build_id" to buildId, "incremental" to incremental, "type" to type, "tags" to tags
        )
        components.forEach { (name, value) ->
            check(isFingerprintComponent(value)) { "firmware_fingerprint_source_unavailable:$name" }
        }
        return "$brand/$product/$device:$release/$buildId/$incremental:$type/$tags"
    }

    private fun isFingerprintComponent(value: String?): Boolean =
        !value.isNullOrEmpty() && !value.equals("unknown", ignoreCase = true) && value.none {
            it.isWhitespace() || it.isISOControl() || it == '/' || it == ':'
        }

    fun isFingerprintProperty(name: String?): Boolean =
        name?.trim()?.lowercase(Locale.ROOT) in fingerprintPropertyNames

    /** Google callers rebuild missing property values from the same native components. */
    fun correctFingerprintProperty(name: String?, value: String?): String? =
        if (isFingerprintProperty(name)) correctFingerprint(value) else value

    fun normalizeModel(raw: String?): String = normalizeIdentityValue(
        raw,
        HaierDeviceModelNormalizer.FIXED_MODEL
    )

    fun normalizeBrand(raw: String?): String = normalizeIdentityValue(raw, FIXED_BRAND)

    fun normalizeManufacturer(raw: String?): String = normalizeIdentityValue(raw, FIXED_MANUFACTURER)

    fun normalizeDevice(raw: String?): String = normalizeIdentityValue(raw, FIXED_DEVICE)

    fun normalizeProduct(raw: String?): String = normalizeIdentityValue(raw, FIXED_PRODUCT)

    internal fun normalizeHardware(raw: String?): String {
        val value = raw?.trim().orEmpty()
        val normalized = value.lowercase(Locale.ROOT)
        return if (value.isBlank() || value.any { it.code < 32 || it.code == 127 } ||
            HaierDeviceModelNormalizer.isGeneric(value) ||
            emulatorHardwareMarkers.any(normalized::contains)) {
            FIXED_DEVICE
        } else {
            value
        }
    }

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

    private fun canonicalSystemPropertyValue(name: String): String? =
        correctedSystemPropertyValue(
            name, readActualSystemProperty(name, null),
            androidVersion(), sdkInt(), model(), buildId(), brand(), manufacturer(), device(), product()
        )

    /** Known aliases expose the same app-facing identity as the Build getters. */
    internal fun correctedSystemPropertyValue(
        name: String,
        actual: String?,
        release: String,
        sdk: Int,
        model: String,
        buildId: String,
        brand: String,
        manufacturer: String,
        device: String,
        product: String
    ): String? = when (name.lowercase(Locale.ROOT)) {
        "ro.product.model", "ro.product.cust.model", "ro.product.vendor.model",
        "ro.product.system.model" -> model
        "ro.product.name", "ro.build.product" -> product
        "ro.product.device", "ro.product.system.device", "ro.product.vendor.device" -> device
        "ro.product.brand", "ro.product.system.brand", "ro.product.vendor.brand" -> brand
        "ro.product.manufacturer", "ro.product.system.manufacturer",
        "ro.product.vendor.manufacturer" -> manufacturer
        "ro.build.version.release" -> release
        "ro.build.version.sdk", "ro.system.build.version.sdk", "ro.vendor.build.version.sdk" -> sdk.toString()
        "ro.build.id" -> buildId
        // Incremental/display/software labels are distinct fields, not aliases of Build.ID.
        "ro.build.version.incremental", "ro.build.display.id", "ro.software.version_id" ->
            normalizeBuildId(actual, sdk)
        else -> null
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
