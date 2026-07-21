package com.smart.android.ad_app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HaierDeviceModelNormalizerTest {

    @Test
    fun `generic placeholder models are replaced`() {
        listOf(
            "TV BOX",
            "TVBOX",
            "TV Box 4K",
            "QTC TVBOX",
            "Android TV",
            "Android-TV-Box Pro",
            "Smart TV",
            "TV Stick",
            "TV Dongle",
            "Set Top Box",
            "generic",
            "unknown"
        ).forEach { model ->
            assertTrue("$model 应判定为通用占位型号", HaierDeviceModelNormalizer.isGeneric(model))
            assertEquals("X96_NEXT", HaierDeviceModelNormalizer.normalize(model))
        }
    }

    @Test
    fun `specific product models remain unchanged`() {
        listOf(
            "TX9 PRO",
            "TV98 Pro",
            "X96Q PRO",
            "Z8 PRO",
            "ATB-01",
            "Sony BRAVIA"
        ).forEach { model ->
            assertFalse("$model 应保留为具体型号", HaierDeviceModelNormalizer.isGeneric(model))
            assertEquals(model, HaierDeviceModelNormalizer.normalize(model))
        }
    }
}
