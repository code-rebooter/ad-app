package com.smart.android.ad_app

import org.junit.Assert.assertEquals
import org.junit.Test

class HaierBuildIdentityNormalizerTest {

    @Test
    fun `specific build identity values remain unchanged`() {
        assertEquals("Sony BRAVIA", HaierBuildIdentityNormalizer.normalizeModel("Sony BRAVIA"))
        assertEquals("Sony", HaierBuildIdentityNormalizer.normalizeBrand("Sony"))
        assertEquals("LivingRoom", HaierBuildIdentityNormalizer.normalizeDevice("LivingRoom"))
        assertEquals("MyProduct", HaierBuildIdentityNormalizer.normalizeProduct("MyProduct"))
        assertEquals("UP1A_CUSTOM", HaierBuildIdentityNormalizer.normalizeBuildId("UP1A_CUSTOM", 34))
        assertEquals("14", HaierBuildIdentityNormalizer.normalizeAndroidVersion("14", 34))
    }

    @Test
    fun `x88 rockchip identity remains unchanged because it is device specific`() {
        assertEquals(
            "X88Pro13.8800.F1010_1.0.0",
            HaierBuildIdentityNormalizer.normalizeModel("X88Pro13.8800.F1010_1.0.0")
        )
        assertEquals("RockChip", HaierBuildIdentityNormalizer.normalizeBrand("RockChip"))
        assertEquals(
            "RockChip",
            HaierBuildIdentityNormalizer.normalizeManufacturer("RockChip")
        )
        assertEquals("rk3528_box", HaierBuildIdentityNormalizer.normalizeDevice("rk3528_box"))
        assertEquals("rk3528_box", HaierBuildIdentityNormalizer.normalizeProduct("rk3528_box"))
    }

    @Test
    fun `generic or incompatible build identity values use the canonical fallback`() {
        assertEquals("X96_NEXT", HaierBuildIdentityNormalizer.normalizeModel("generic"))
        assertEquals("X96", HaierBuildIdentityNormalizer.normalizeBrand("unknown"))
        assertEquals("X96_NEXT", HaierBuildIdentityNormalizer.normalizeDevice(""))
        assertEquals("X96_NEXT", HaierBuildIdentityNormalizer.normalizeProduct("Android TV"))
        assertEquals("RP1A.200720.009", HaierBuildIdentityNormalizer.normalizeBuildId(
            "QP1A.191105.004",
            30
        ))
        assertEquals("11", HaierBuildIdentityNormalizer.normalizeAndroidVersion("11.1", 30))
    }

    @Test
    fun `model containing control characters uses the canonical fallback`() {
        assertEquals(
            "X96_NEXT",
            HaierBuildIdentityNormalizer.normalizeModel("X88Pro\r\nInjected")
        )
    }
}
