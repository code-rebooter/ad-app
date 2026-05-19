package com.smart.android.ad_app

import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

class Hq008FlowLoggingContractTest {

    @Test
    fun `hq008 cmp and ad flow should expose traceable logs for testing`() {
        val cmpManagerSource = readProjectFile("app/src/hq008/java/com/smart/android/ad_app/Hq008CmpManager.kt")
        val adConfigManagerSource = readProjectFile("app/src/main/java/com/smart/android/ad_app/AdConfigManager.kt")
        val authorizeClientSource = readProjectFile("app/src/hq008/java/com/smart/android/ad_app/Hq008SdkAuthorizeClient.kt")
        val adManagerSource = readProjectFile("app/src/hq008/java/com/smart/android/ad_app/AdManagerImpl.kt")
        val reporterSource = readProjectFile("app/src/hq008/java/com/smart/android/ad_app/Hq008AdReporter.kt")

        assertTrue(cmpManagerSource.contains("CMP_FLOW init start"))
        assertTrue(cmpManagerSource.contains("CMP_FLOW state ready"))
        assertTrue(cmpManagerSource.contains("CMP_FLOW runWhenConsentStateReady"))
        assertTrue(cmpManagerSource.contains("CMP_FLOW upload pending silent bootstrap"))
        assertTrue(cmpManagerSource.contains("eventType = \"CMP_INIT_START\""))
        assertTrue(cmpManagerSource.contains("eventType = \"CMP_GATE_EVALUATED\""))
        assertTrue(cmpManagerSource.contains("eventType = \"CMP_STATE_PERSISTED\""))
        assertTrue(cmpManagerSource.contains("eventType = \"CONSENT_REPORT_START\""))
        assertTrue(cmpManagerSource.contains("eventType = \"CONSENT_REPORT_SUCCESS\""))

        assertTrue(adConfigManagerSource.contains("AD_FLOW hq008 waiting consent gate"))
        assertTrue(adConfigManagerSource.contains("AD_FLOW hq008 authorize callback"))
        assertTrue(adConfigManagerSource.contains("AD_FLOW hq008 dispatch floating ad"))

        assertTrue(authorizeClientSource.contains("eventType = \"AUTHORIZE_START\""))
        assertTrue(authorizeClientSource.contains("eventType = \"AUTHORIZE_RESULT\""))
        assertTrue(authorizeClientSource.contains("request_id"))

        assertTrue(adManagerSource.contains("PLAY_FLOW showAd entry"))
        assertTrue(adManagerSource.contains("PLAY_FLOW startAd begin"))
        assertTrue(adManagerSource.contains("PLAY_FLOW onAdFinished"))

        assertTrue(reporterSource.contains("REPORT_FLOW request"))
        assertTrue(reporterSource.contains("REPORT_FLOW success"))
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
