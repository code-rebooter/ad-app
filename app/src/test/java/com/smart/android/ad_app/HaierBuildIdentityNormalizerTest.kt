package com.smart.android.ad_app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
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
    fun `test environment hardware uses the same device fallback policy`() {
        for (value in listOf(null, "", "unknown", "generic_x86", "ranchu", "goldfish_x86", "emulator")) {
            assertEquals(value, "X96_NEXT", HaierBuildIdentityNormalizer.normalizeHardware(value))
        }
        for (value in listOf("rk3528", "amlogic", "mt5896")) {
            assertEquals(value, value, HaierBuildIdentityNormalizer.normalizeHardware(value))
        }
    }

    @Test
    fun `model containing control characters uses the canonical fallback`() {
        assertEquals(
            "X96_NEXT",
            HaierBuildIdentityNormalizer.normalizeModel("X88Pro\r\nInjected")
        )
    }
    @Test
    fun `property aliases share the corrected native source even when a partition reports another value`() {
        val groups = mapOf(
            "Sony BRAVIA" to listOf("ro.product.model", "ro.product.cust.model", "ro.product.vendor.model", "ro.product.system.model"),
            "Sony" to listOf("ro.product.brand", "ro.product.system.brand", "ro.product.vendor.brand"),
            "Sony Corp" to listOf("ro.product.manufacturer", "ro.product.system.manufacturer", "ro.product.vendor.manufacturer"),
            "LivingRoom" to listOf("ro.product.device", "ro.product.system.device", "ro.product.vendor.device"),
            "MyProduct" to listOf("ro.product.name", "ro.build.product"),
            "10" to listOf("ro.build.version.release"),
            "29" to listOf("ro.build.version.sdk", "ro.system.build.version.sdk", "ro.vendor.build.version.sdk"),
            "QP1A.191105.004" to listOf("ro.build.id")
        )
        for ((expected, aliases) in groups) for (key in aliases) for (raw in listOf(null, "", "generic", "OTHER_VALID_VALUE")) {
            assertEquals(key, expected, HaierBuildIdentityNormalizer.correctedSystemPropertyValue(
                key, raw, "10", 29, "Sony BRAVIA", "QP1A.191105.004", "Sony", "Sony Corp", "LivingRoom", "MyProduct"
            ))
        }
        for (key in listOf("ro.build.version.incremental", "ro.build.display.id", "ro.software.version_id")) {
            assertEquals("15.1.X36-0119", HaierBuildIdentityNormalizer.correctedSystemPropertyValue(
                key, "15.1.X36-0119", "10", 29, "Sony BRAVIA", "QP1A.191105.004", "Sony", "Sony Corp", "LivingRoom", "MyProduct"
            ))
        }
    }

    @Test
    fun `fingerprint components agree with corrected native fields and retain the build suffix`() {
        val raw = "google/walley/walleye:15/QP1A.191105.004/eng.cmj.20260119.112106:userdebug/test-keys"
        val effective = HaierBuildIdentityNormalizer.normalizeFingerprint(
            raw, "google", "X96_NEXT", "X96_NEXT", "10", "QP1A.191105.004"
        )
        assertEquals(
            "google/X96_NEXT/X96_NEXT:10/QP1A.191105.004/eng.cmj.20260119.112106:userdebug/test-keys",
            effective
        )
        assertEquals(effective, HaierBuildIdentityNormalizer.normalizeFingerprint(
            effective, "google", "X96_NEXT", "X96_NEXT", "10", "QP1A.191105.004"
        ))
    }

    @Test
    fun `matching firmware fingerprint and distinct build labels are preserved`() {
        val raw = "google/R69-1/R69-1:14/UTT2.241219.001/20260101:user/release-keys"
        assertEquals(raw, HaierBuildIdentityNormalizer.normalizeFingerprint(
            raw, "google", "R69-1", "R69-1", "14", "UTT2.241219.001"
        ))
        val partition = "vendor/product/device:15/OLD/vendor.incremental:eng/dev-keys"
        assertEquals("google/R69-1/R69-1:14/UTT2.241219.001/vendor.incremental:eng/dev-keys",
            HaierBuildIdentityNormalizer.normalizeFingerprint(
                partition, "google", "R69-1", "R69-1", "14", "UTT2.241219.001"
            ))
    }

    @Test
    fun `missing malformed fingerprints are rebuilt from corrected identity and actual native labels`() {
        val expected = "google/X96_NEXT/X96_NEXT:10/QP1A.191105.004/eng.cmj.20260119.112106:userdebug/test-keys"
        for (raw in listOf(null, "", "unknown", "vendor/product/device:15/OLD/inc:user", "a/b/c:15/OLD/inc:user/test-keys\n",
            "a/b/c:15/OLD/unknown:unknown/unknown")) {
            assertEquals(expected, HaierBuildIdentityNormalizer.normalizeFingerprint(
                raw, "google", "X96_NEXT", "X96_NEXT", "10", "QP1A.191105.004",
                "eng.cmj.20260119.112106", "userdebug", "test-keys"
            ))
        }
    }

    @Test
    fun `unrecoverable fingerprint fails instead of returning broken or fabricated labels`() {
        for (labels in listOf(
            listOf(null, "userdebug", "test-keys"), listOf("inc", "", "test-keys"),
            listOf("inc", "userdebug", "unknown"), listOf("bad/path", "userdebug", "test-keys"),
            listOf("inc", "user\n", "test-keys")
        )) {
            val failure = assertThrows(IllegalStateException::class.java) {
                HaierBuildIdentityNormalizer.normalizeFingerprint(
                    "broken:15", "google", "product", "device", "10", "QP1A.191105.004",
                    labels[0], labels[1], labels[2]
                )
            }
            org.junit.Assert.assertTrue(failure.message.orEmpty().startsWith("firmware_fingerprint_source_unavailable:"))
        }
        val raw = "google/product/device:15/OLD/inc:user/test-keys"
        for (brand in listOf("", "unknown", "brand with spaces", "brand/path", "brand:other", "brand\u0000")) {
            assertEquals("firmware_fingerprint_source_unavailable:brand", assertThrows(IllegalStateException::class.java) {
                HaierBuildIdentityNormalizer.normalizeFingerprint(
                    raw, brand, "product", "device", "10", "QP1A.191105.004"
                )
            }.message)
        }
    }

    @Test
    fun `fingerprint aliases are recognized independently of locale without rewriting other properties`() {
        for (name in listOf("ro.build.fingerprint", "ro.system.build.fingerprint", "ro.system_ext.build.fingerprint",
            "ro.product.build.fingerprint", "ro.vendor.build.fingerprint", "ro.odm.build.fingerprint", "ro.bootimage.build.fingerprint")) {
            org.junit.Assert.assertTrue(name, HaierBuildIdentityNormalizer.isFingerprintProperty(name))
            org.junit.Assert.assertTrue(name, HaierBuildIdentityNormalizer.isFingerprintProperty(name.uppercase(java.util.Locale.ROOT)))
        }
        for (name in listOf(null, "", "ro.build.display.id", "ro.build.version.incremental", "other.fingerprint")) {
            org.junit.Assert.assertFalse(HaierBuildIdentityNormalizer.isFingerprintProperty(name))
            assertEquals("unchanged", HaierBuildIdentityNormalizer.correctFingerprintProperty(name, "unchanged"))
        }
    }
}
