package com.smart.android.ad_app

import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

class Hq008AppVersionContractTest {

    @Test
    fun `hq008 self-owned backend requests should include ad_version with version code`() {
        val cmpDecisionSource = readProjectFile("app/src/hq008/java/com/smart/android/ad_app/Hq008CmpDecisionClient.kt")
        val flowControlSource = readProjectFile("app/src/hq008/java/com/smart/android/ad_app/Hq008SdkFlowControlClient.kt")
        val authorizeSource = readProjectFile("app/src/hq008/java/com/smart/android/ad_app/Hq008SdkAuthorizeClient.kt")

        assertTrue("consent-popup 请求应带 ad_version 版本号", cmpDecisionSource.contains("\"ad_version\" to BuildConfig.VERSION_CODE"))
        assertTrue("consent-report 请求应带 ad_version 版本号", cmpDecisionSource.contains("\"consent_action\" to consentAction"))
        assertTrue("flow-control 请求应带 ad_version 版本号", flowControlSource.contains("\"ad_version\" to BuildConfig.VERSION_CODE"))
        assertTrue("flow-control 请求应带 android_sdk_version", flowControlSource.contains("\"android_sdk_version\" to android.os.Build.VERSION.SDK_INT"))
        assertTrue("authorize 请求应带 ad_version 版本号", authorizeSource.contains("\"ad_version\" to BuildConfig.VERSION_CODE"))
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
