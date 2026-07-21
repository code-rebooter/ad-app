package com.smart.android.ad_app

internal enum class HaierUaNormalizationReason {
    UNCHANGED_VALID,
    REPLACED_BLANK,
    REPLACED_UNSAFE_CHARACTERS,
    REPLACED_UNPARSEABLE,
    REPLACED_VERSION_MISMATCH,
    REPLACED_GENERIC_MODEL,
    REPLACED_BUILD_MISMATCH,
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

    fun normalize(rawUa: String?, sdkInt: Int): HaierUaNormalizationResult {
        val original = rawUa.orEmpty()
        val profile = profiles[sdkInt]
            ?: return unchanged(original, HaierUaNormalizationReason.UNCHANGED_UNSUPPORTED_SDK)

        if (original.isBlank()) {
            return replaced(original, profile, HaierUaNormalizationReason.REPLACED_BLANK)
        }
        if (original.any { it.code < 32 || it.code == 127 }) {
            return replaced(
                original,
                profile,
                HaierUaNormalizationReason.REPLACED_UNSAFE_CHARACTERS
            )
        }

        val match = userAgentPattern.matchEntire(original)
            ?: return replaced(
                original,
                profile,
                HaierUaNormalizationReason.REPLACED_UNPARSEABLE
            )
        val androidVersion = match.groupValues[2].trim()
        val model = match.groupValues[3].trim()
        val buildId = match.groupValues[4].trim()

        if (androidVersion !in profile.acceptedVersions) {
            return replaced(
                original,
                profile,
                HaierUaNormalizationReason.REPLACED_VERSION_MISMATCH
            )
        }
        if (HaierDeviceModelNormalizer.isGeneric(model)) {
            return replaced(
                original,
                profile,
                HaierUaNormalizationReason.REPLACED_GENERIC_MODEL
            )
        }
        if (!safeBuildIdPattern.matches(buildId) || !isRecognizedBuildCompatible(buildId, sdkInt)) {
            return replaced(
                original,
                profile,
                HaierUaNormalizationReason.REPLACED_BUILD_MISMATCH
            )
        }

        return unchanged(original, HaierUaNormalizationReason.UNCHANGED_VALID)
    }

    fun canonicalUserAgentFor(sdkInt: Int): String? {
        return profiles[sdkInt]?.let(::buildCanonicalUserAgent)
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

    private fun replaced(
        original: String,
        profile: HaierUaProfile,
        reason: HaierUaNormalizationReason
    ): HaierUaNormalizationResult {
        return HaierUaNormalizationResult(
            originalUa = original,
            effectiveUa = buildCanonicalUserAgent(profile),
            changed = true,
            reason = reason
        )
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
