package com.smart.android.ad_app

/** Only firmware model/release fields share the native policy; browser metadata stays as observed. */
internal object WebViewClientHintsNormalizer {
    fun normalize(
        observed: Map<String, Any?>?,
        effectiveModel: String,
        effectiveRelease: String,
        sdkInt: Int
    ): Map<String, Any?>? {
        if (observed == null) return null
        val corrected = LinkedHashMap(observed)
        if (observed["model"] is String) corrected["model"] = effectiveModel
        val platformVersion = observed["platformVersion"]
        if (observed["platform"] == "Android" && platformVersion is String &&
            platformVersion.isNotEmpty() && HaierUserAgentNormalizer.canonicalAndroidVersionFor(sdkInt) != null) {
            corrected["platformVersion"] = normalizePlatformVersion(
                platformVersion, effectiveRelease, sdkInt
            )
        }
        return if (corrected == observed) observed else corrected
    }

    internal fun canonicalPlatformVersion(release: String, sdkInt: Int): String? {
        if (HaierUserAgentNormalizer.canonicalAndroidVersionFor(sdkInt) == null) return null
        // Preserve the browser spelling when it is the same native release with zero padding.
        val numericRelease = if (release == "12L") "12" else release
        val parts = numericRelease.split('.')
        if (parts.size > 3 || parts.any { it.isEmpty() || it.any { c -> c !in '0'..'9' } }) {
            return null
        }
        return (parts + List(3 - parts.size) { "0" }).joinToString(".")
    }

    private fun normalizePlatformVersion(raw: String, release: String, sdkInt: Int): String {
        val replacement = canonicalPlatformVersion(release, sdkInt) ?: return raw
        val numericRelease = if (release == "12L") "12" else release
        fun withoutPadding(value: String): String {
            var version = value
            while (version.endsWith(".0")) version = version.removeSuffix(".0")
            return version
        }
        if (withoutPadding(raw) == withoutPadding(numericRelease)) return raw
        return replacement
    }
}
