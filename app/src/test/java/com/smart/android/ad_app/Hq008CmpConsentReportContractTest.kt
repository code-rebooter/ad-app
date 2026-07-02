package com.smart.android.ad_app

import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

class Hq008CmpConsentReportContractTest {

    @Test
    fun `hq008 should report final consent action back to our server after cmp success`() {
        val clientSource = readProjectFile("app/src/hq008/java/com/smart/android/ad_app/Hq008CmpDecisionClient.kt")
        val cmpManagerSource = readProjectFile("app/src/hq008/java/com/smart/android/ad_app/Hq008CmpManager.kt")

        assertTrue("应新增 consent-report 接口地址", clientSource.contains("api/v2/ad/consent-report"))
        assertTrue("上报参数应包含 channel_id", clientSource.contains("\"channel_id\" to channelId"))
        assertTrue("上报参数应包含 mac", clientSource.contains("\"mac\" to"))
        assertTrue("上报参数应包含 android_sdk_version", clientSource.contains("\"android_sdk_version\" to Build.VERSION.SDK_INT"))
        assertTrue("上报参数应包含 consent_action", clientSource.contains("\"consent_action\" to consentAction"))
        assertTrue("CMP 成功后应触发 consent-report 上报", cmpManagerSource.contains("reportConsentResult"))
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
