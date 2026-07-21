package com.smart.android.ad_app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HaierUserAgentNormalizerTest {

    @Test
    fun `normal user agent remains byte for byte unchanged`() {
        val original =
            "Dalvik/2.1.0 (Linux; U; Android 11; Sony BRAVIA Build/SONY_11_001)"

        val result = HaierUserAgentNormalizer.normalize(original, 30)

        assertFalse(result.changed)
        assertEquals(original, result.effectiveUa)
        assertEquals(HaierUaNormalizationReason.UNCHANGED_VALID, result.reason)
    }

    @Test
    fun `official legacy decimal android versions remain valid`() {
        val cases = listOf(
            23 to "Dalvik/2.1.0 (Linux; U; Android 6.0.1; Brand TV Build/OEM_23)",
            25 to "Dalvik/2.1.0 (Linux; U; Android 7.1.2; Brand TV Build/OEM_25)",
            27 to "Dalvik/2.1.0 (Linux; U; Android 8.1; Brand TV Build/OEM_27)"
        )

        cases.forEach { (sdkInt, original) ->
            val result = HaierUserAgentNormalizer.normalize(original, sdkInt)
            assertFalse("SDK $sdkInt 不应修改官方版本 UA", result.changed)
            assertEquals(original, result.effectiveUa)
        }
    }

    @Test
    fun `android 11 point 1 is replaced on api 30`() {
        val result = HaierUserAgentNormalizer.normalize(
            "Dalvik/2.1.0 (Linux; U; Android 11.1; Brand TV Build/RP1A.200720.009)",
            30
        )

        assertTrue(result.changed)
        assertEquals(HaierUaNormalizationReason.REPLACED_VERSION_MISMATCH, result.reason)
        assertEquals(
            "Dalvik/2.1.0 (Linux; U; Android 11; X96_NEXT Build/RP1A.200720.009)",
            result.effectiveUa
        )
    }

    @Test
    fun `recognized android 10 build is replaced on api 30`() {
        val result = HaierUserAgentNormalizer.normalize(
            "Dalvik/2.1.0 (Linux; U; Android 11; X96_NEXT Build/QP1A.191105.004)",
            30
        )

        assertTrue(result.changed)
        assertEquals(HaierUaNormalizationReason.REPLACED_BUILD_MISMATCH, result.reason)
        assertEquals(
            "Dalvik/2.1.0 (Linux; U; Android 11; X96_NEXT Build/RP1A.200720.009)",
            result.effectiveUa
        )
    }

    @Test
    fun `generic and platform models are replaced`() {
        listOf(
            "TV BOX",
            "TVBOX",
            "TV Box 4K",
            "QTC TVBOX",
            "Android TV",
            "Android TV Box",
            "Smart TV",
            "TV Stick",
            "TV Dongle",
            "AOSP",
            "generic",
            "unknown",
            "mstar",
            "walley",
            "walleye"
        )
            .forEach { model ->
                val result = HaierUserAgentNormalizer.normalize(
                    "Dalvik/2.1.0 (Linux; U; Android 11; $model Build/RP1A.200720.009)",
                    30
                )

                assertTrue("型号 $model 应判定为异常", result.changed)
                assertEquals(HaierUaNormalizationReason.REPLACED_GENERIC_MODEL, result.reason)
            }
    }

    @Test
    fun `specific product model variants remain unchanged`() {
        listOf("TX9 PRO", "TV98 Pro", "X96Q PRO", "Z8 PRO", "ATB-01")
            .forEach { model ->
                val original =
                    "Dalvik/2.1.0 (Linux; U; Android 11; $model Build/RP1A.200720.009)"

                val result = HaierUserAgentNormalizer.normalize(original, 30)

                assertFalse("型号 $model 应保持不变", result.changed)
                assertEquals(original, result.effectiveUa)
            }
    }

    @Test
    fun `blank malformed and unsafe values are replaced`() {
        val blank = HaierUserAgentNormalizer.normalize("", 30)
        val malformed = HaierUserAgentNormalizer.normalize("Mozilla/5.0", 30)
        val unsafe = HaierUserAgentNormalizer.normalize(
            "Dalvik/2.1.0 (Linux; U; Android 11; X96_NEXT Build/RP1A.200720.009)\r\nInjected: true",
            30
        )

        assertEquals(HaierUaNormalizationReason.REPLACED_BLANK, blank.reason)
        assertEquals(HaierUaNormalizationReason.REPLACED_UNPARSEABLE, malformed.reason)
        assertEquals(HaierUaNormalizationReason.REPLACED_UNSAFE_CHARACTERS, unsafe.reason)
        assertTrue(blank.changed)
        assertTrue(malformed.changed)
        assertTrue(unsafe.changed)
    }

    @Test
    fun `unknown but safe oem build remains valid`() {
        val original =
            "Dalvik/2.1.0 (Linux; U; Android 14; Haier H65 Build/HAIER_U_20250101)"

        val result = HaierUserAgentNormalizer.normalize(original, 34)

        assertFalse(result.changed)
        assertEquals(original, result.effectiveUa)
    }

    @Test
    fun `canonical profiles cover api 23 through 36 and are idempotent`() {
        val expected = linkedMapOf(
            23 to "Dalvik/2.1.0 (Linux; U; Android 6.0; X96_NEXT Build/MRA58K)",
            24 to "Dalvik/2.1.0 (Linux; U; Android 7.0; X96_NEXT Build/NRD90M)",
            25 to "Dalvik/2.1.0 (Linux; U; Android 7.1; X96_NEXT Build/NDE63H)",
            26 to "Dalvik/2.1.0 (Linux; U; Android 8.0; X96_NEXT Build/OPR6.170623.010)",
            27 to "Dalvik/2.1.0 (Linux; U; Android 8.1; X96_NEXT Build/OPM1.171019.011)",
            28 to "Dalvik/2.1.0 (Linux; U; Android 9; X96_NEXT Build/PPR1.180610.009)",
            29 to "Dalvik/2.1.0 (Linux; U; Android 10; X96_NEXT Build/QP1A.190711.019)",
            30 to "Dalvik/2.1.0 (Linux; U; Android 11; X96_NEXT Build/RP1A.200720.009)",
            31 to "Dalvik/2.1.0 (Linux; U; Android 12; X96_NEXT Build/SP1A.210812.015)",
            32 to "Dalvik/2.1.0 (Linux; U; Android 12L; X96_NEXT Build/SP2A.220305.012)",
            33 to "Dalvik/2.1.0 (Linux; U; Android 13; X96_NEXT Build/TP1A.220624.014)",
            34 to "Dalvik/2.1.0 (Linux; U; Android 14; X96_NEXT Build/UP1A.231005.007)",
            35 to "Dalvik/2.1.0 (Linux; U; Android 15; X96_NEXT Build/AP3A.240905.015.A2)",
            36 to "Dalvik/2.1.0 (Linux; U; Android 16; X96_NEXT Build/BP2A.250605.031.A2)"
        )

        expected.forEach { (sdkInt, canonicalUa) ->
            assertEquals(canonicalUa, HaierUserAgentNormalizer.canonicalUserAgentFor(sdkInt))

            val replacement = HaierUserAgentNormalizer.normalize("bad", sdkInt)
            assertEquals(canonicalUa, replacement.effectiveUa)

            val secondPass = HaierUserAgentNormalizer.normalize(replacement.effectiveUa, sdkInt)
            assertFalse("SDK $sdkInt 的标准 UA 应满足幂等性", secondPass.changed)
            assertEquals(canonicalUa, secondPass.effectiveUa)
        }
    }

    @Test
    fun `unsupported sdk preserves original instead of guessing`() {
        val original = "bad"

        val result = HaierUserAgentNormalizer.normalize(original, 22)

        assertFalse(result.changed)
        assertEquals(original, result.effectiveUa)
        assertEquals(HaierUaNormalizationReason.UNCHANGED_UNSUPPORTED_SDK, result.reason)
    }
}
