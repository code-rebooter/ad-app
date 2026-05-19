package com.smart.android.ad_app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

class Hq008CmpPopupFlowTest {

    @Test
    fun `hq008 should expose a debug cmp popup flow`() {
        val providerSource = readProjectFile("app/src/main/java/com/smart/android/ad_app/AdProvider.kt")
        val cmpManagerSource = readProjectFile("app/src/hq008/java/com/smart/android/ad_app/Hq008CmpManager.kt")
        val hq008ManifestSource = readProjectFile("app/src/hq008/AndroidManifest.xml")
        val cmpDebugActivitySource = readProjectFile("app/src/hq008/java/com/smart/android/ad_app/Hq008CmpDebugActivity.kt")

        assertTrue(providerSource.contains("showCmp"))
        assertTrue(providerSource.contains("Hq008CmpDebugActivity"))
        assertTrue(cmpManagerSource.contains("loadCmpPrivacy"))
        assertTrue(cmpManagerSource.contains("CmpDisplayType.CMP_POP"))
        assertFalse(cmpManagerSource.contains("CmpDisplayType.CMP_NOT_POP"))
        assertTrue(cmpManagerSource.contains("buildCmpConfig(activity, forcePopup = false)"))
        assertTrue(hq008ManifestSource.contains("android:screenOrientation=\"landscape\""))
        assertTrue(cmpDebugActivitySource.contains("ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE"))
    }

    private fun readProjectFile(relativePath: String): String {
        val workingDir = File(System.getProperty("user.dir") ?: ".")
        val projectRoot = generateSequence(workingDir) { it.parentFile }
            .firstOrNull { File(it, relativePath).exists() }
        if (projectRoot == null) {
            fail("无法定位项目根目录: ${workingDir.absolutePath}")
        }
        return File(projectRoot, relativePath).readText()
    }
}
