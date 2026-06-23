package com.smart.android.ad_app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

class Hq008CmpSilentStateCheckTest {

    @Test
    fun `hq008 should include cmp sdk and perform silent state check on app startup`() {
        val buildGradle = readProjectFile("app/build.gradle")
        val appSource = readProjectFile("app/src/main/java/com/smart/android/ad_app/APP.kt")
        val providerSource = readProjectFile("app/src/main/java/com/smart/android/ad_app/AdProvider.kt")
        val adConfigManagerSource = readProjectFile("app/src/main/java/com/smart/android/ad_app/AdConfigManager.kt")
        val cmpSource = readProjectFile("app/src/hq008/java/com/smart/android/ad_app/Hq008CmpManager.kt")
        val adManagerSource = readProjectFile("app/src/hq008/java/com/smart/android/ad_app/AdManagerImpl.kt")

        assertTrue(buildGradle.contains("adsdk_overseas_cmp_sdk-*.aar"))
        assertTrue(appSource.contains("Hq008CmpManager.init(this)"))
        assertTrue(providerSource.contains("Hq008CmpManager.init(it)"))
        assertTrue(adConfigManagerSource.contains("Hq008CmpManager.runWhenConsentStateReady"))
        assertTrue("CMP 初始化门禁应面向整个 hq008 family", cmpSource.contains("if (!BuildFlavor.isHq008Family())"))
        assertFalse("CMP 初始化门禁不应只认 hq008 单 flavor", cmpSource.contains("if (!BuildFlavor.isHq008())"))
        assertTrue(cmpSource.contains("prePopulateConsentIfNeed(context)"))
        assertTrue(cmpSource.contains("CmpPopStateManager"))
        assertTrue(cmpSource.contains("loadPopState"))
        assertTrue(cmpSource.contains("buildCmpConfig(context, forcePopup = false)"))
        assertTrue(cmpSource.contains("consentStateReady"))
        assertTrue(cmpSource.contains("pendingRemoteRecovery"))
        assertTrue(cmpSource.contains("CMP_REMOTE_RECOVERY"))
        assertTrue(cmpSource.contains("recoverLocalConsentFromRemoteDecision"))
        assertFalse(cmpSource.contains("shouldSkipDecisionForCurrentCycle("))
        assertTrue(adManagerSource.contains(".setGdprConsent("))
        assertTrue(adManagerSource.contains(".setGdprSource(\"CMP_TCL\")"))
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
