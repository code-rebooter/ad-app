package com.smart.android.ad_app

internal enum class HaierUaNormalizationReason {
    UNCHANGED_VALID,
    REPLACED_BLANK,
    REPLACED_UNSAFE_CHARACTERS,
    REPLACED_UNPARSEABLE,
    REPLACED_VERSION_MISMATCH,
    REPLACED_GENERIC_MODEL,
    REPLACED_BUILD_MISMATCH,
    REPLACED_IDENTITY_MISMATCH,
    UNCHANGED_UNSUPPORTED_SDK
}

internal data class HaierUaNormalizationResult(
    val originalUa: String,
    val effectiveUa: String,
    val changed: Boolean,
    val reason: HaierUaNormalizationReason
)

private data class HaierUaProfile(
    val androidVersion: String,
    val buildId: String,
    val acceptedVersions: Set<String>
)

internal object HaierUserAgentNormalizer {

    private const val FIXED_PREFIX = "Dalvik/2.1.0 (Linux; U; Android "

    private val userAgentPattern = Regex(
        """^Dalvik/([^\s()]+) \(Linux; U; Android ([^;()]+); ([^;()]+) Build/([^()]+)\)$"""
    )
    private val safeBuildIdPattern = Regex("^[A-Za-z0-9._-]+$")
    private val legacyBuildPattern = Regex("^([MN])[A-Z]{2}[0-9]{2}[A-Z]$")
    private val datedBuildPattern = Regex("""^([O-U])[A-Z0-9]{3}\.[0-9]{6}\.[A-Z0-9.]+$""")

    private val profiles = linkedMapOf(
        23 to HaierUaProfile("6.0", "MRA58K", setOf("6.0", "6.0.1")),
        24 to HaierUaProfile("7.0", "NRD90M", setOf("7.0")),
        25 to HaierUaProfile("7.1", "NDE63H", setOf("7.1", "7.1.1", "7.1.2")),
        26 to HaierUaProfile("8.0", "OPR6.170623.010", setOf("8.0", "8.0.0")),
        27 to HaierUaProfile("8.1", "OPM1.171019.011", setOf("8.1", "8.1.0")),
        28 to HaierUaProfile("9", "PPR1.180610.009", setOf("9")),
        29 to HaierUaProfile("10", "QP1A.190711.019", setOf("10")),
        30 to HaierUaProfile("11", "RP1A.200720.009", setOf("11")),
        31 to HaierUaProfile("12", "SP1A.210812.015", setOf("12")),
        32 to HaierUaProfile("12L", "SP2A.220305.012", setOf("12", "12L")),
        33 to HaierUaProfile("13", "TP1A.220624.014", setOf("13")),
        34 to HaierUaProfile("14", "UP1A.231005.007", setOf("14")),
        35 to HaierUaProfile("15", "AP3A.240905.015.A2", setOf("15")),
        36 to HaierUaProfile("16", "BP2A.250605.031.A2", setOf("16"))
    )

    fun normalize(rawUa: String?, sdkInt: Int): HaierUaNormalizationResult =
        normalize(rawUa, sdkInt, null)

    /** Runtime callers supply the same native identity used by the individual Build getters. */
    fun normalize(
        rawUa: String?,
        sdkInt: Int,
        identity: HaierUaIdentity?
    ): HaierUaNormalizationResult {
        val original = rawUa.orEmpty()
        if (sdkInt !in profiles) {
            return unchanged(original, HaierUaNormalizationReason.UNCHANGED_UNSUPPORTED_SDK)
        }

        val unsafe = original.any { it.code < 32 || it.code == 127 }
        val match = if (unsafe) null else userAgentPattern.matchEntire(original)
        val androidVersion = match?.groupValues?.get(2)?.trim()
        val model = match?.groupValues?.get(3)?.trim()
        val buildId = match?.groupValues?.get(4)?.trim()
        val effectiveIdentity = identity ?: HaierBuildIdentityNormalizer.normalizeUserAgentIdentity(
            androidVersion, model, buildId, sdkInt
        )
        val reason = when {
            original.isBlank() -> HaierUaNormalizationReason.REPLACED_BLANK
            unsafe -> HaierUaNormalizationReason.REPLACED_UNSAFE_CHARACTERS
            match == null -> HaierUaNormalizationReason.REPLACED_UNPARSEABLE
            !isCompatibleAndroidVersion(androidVersion, sdkInt) ->
                HaierUaNormalizationReason.REPLACED_VERSION_MISMATCH
            HaierDeviceModelNormalizer.isGeneric(model) ->
                HaierUaNormalizationReason.REPLACED_GENERIC_MODEL
            !isCompatibleBuildId(buildId, sdkInt) ->
                HaierUaNormalizationReason.REPLACED_BUILD_MISMATCH
            androidVersion != effectiveIdentity.androidVersion ||
                model != effectiveIdentity.model || buildId != effectiveIdentity.buildId ->
                HaierUaNormalizationReason.REPLACED_IDENTITY_MISMATCH
            else -> return unchanged(original, HaierUaNormalizationReason.UNCHANGED_VALID)
        }
        val dalvikVersion = match?.groupValues?.get(1) ?: "2.1.0"
        val effectiveUa = "Dalvik/$dalvikVersion (Linux; U; Android " +
            "${effectiveIdentity.androidVersion}; ${effectiveIdentity.model} Build/${effectiveIdentity.buildId})"
        return HaierUaNormalizationResult(
            originalUa = original,
            effectiveUa = effectiveUa,
            changed = original != effectiveUa,
            reason = reason
        )
    }

    fun canonicalUserAgentFor(sdkInt: Int): String? {
        return profiles[sdkInt]?.let(::buildCanonicalUserAgent)
    }

    fun canonicalAndroidVersionFor(sdkInt: Int): String? {
        return profiles[sdkInt]?.androidVersion
    }

    fun canonicalBuildIdFor(sdkInt: Int): String? {
        return profiles[sdkInt]?.buildId
    }

    fun isCompatibleAndroidVersion(androidVersion: String?, sdkInt: Int): Boolean {
        val version = androidVersion?.trim().orEmpty()
        return profiles[sdkInt]?.acceptedVersions?.contains(version) == true
    }

    fun isCompatibleBuildId(buildId: String?, sdkInt: Int): Boolean {
        val value = buildId?.trim().orEmpty()
        return value.isNotBlank() &&
            safeBuildIdPattern.matches(value) &&
            isRecognizedBuildCompatible(value, sdkInt)
    }

    private fun isRecognizedBuildCompatible(buildId: String, sdkInt: Int): Boolean {
        val compatibleSdkRange = recognizedBuildSdkRange(buildId) ?: return true
        return sdkInt in compatibleSdkRange
    }

    private fun recognizedBuildSdkRange(buildId: String): IntRange? {
        if (buildId.startsWith("AP3A.")) {
            return 35..35
        }
        if (buildId.startsWith("BP2A.") || buildId.startsWith("BP3A.") || buildId.startsWith("BP4A.")) {
            return 36..36
        }

        legacyBuildPattern.matchEntire(buildId)?.let { match ->
            return when (match.groupValues[1]) {
                "M" -> 23..23
                "N" -> 24..25
                else -> null
            }
        }

        datedBuildPattern.matchEntire(buildId)?.let { match ->
            return when (match.groupValues[1]) {
                "O" -> 26..27
                "P" -> 28..28
                "Q" -> 29..29
                "R" -> 30..30
                "S" -> 31..32
                "T" -> 33..33
                "U" -> 34..34
                else -> null
            }
        }
        return null
    }

    private fun unchanged(
        original: String,
        reason: HaierUaNormalizationReason
    ): HaierUaNormalizationResult {
        return HaierUaNormalizationResult(
            originalUa = original,
            effectiveUa = original,
            changed = false,
            reason = reason
        )
    }

    private fun buildCanonicalUserAgent(profile: HaierUaProfile): String {
        return "$FIXED_PREFIX${profile.androidVersion}; ${HaierDeviceModelNormalizer.FIXED_MODEL} Build/${profile.buildId})"
    }
}
