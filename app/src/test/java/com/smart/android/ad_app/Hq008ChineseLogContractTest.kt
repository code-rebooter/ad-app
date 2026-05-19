package com.smart.android.ad_app

import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

class Hq008ChineseLogContractTest {

    @Test
    fun `hq008 critical runtime logs should be chinese and descriptive`() {
        val providerSource = readProjectFile("app/src/main/java/com/smart/android/ad_app/AdProvider.kt")
        val cmpSource = readProjectFile("app/src/hq008/java/com/smart/android/ad_app/Hq008CmpManager.kt")
        val configSource = readProjectFile("app/src/main/java/com/smart/android/ad_app/AdConfigManager.kt")
        val adManagerSource = readProjectFile("app/src/hq008/java/com/smart/android/ad_app/AdManagerImpl.kt")
        val authorizeSource = readProjectFile("app/src/hq008/java/com/smart/android/ad_app/Hq008SdkAuthorizeClient.kt")
        val reporterSource = readProjectFile("app/src/hq008/java/com/smart/android/ad_app/Hq008AdReporter.kt")

        assertTrue(providerSource.contains("正式链路：AdProvider 已创建"))
        assertTrue(cmpSource.contains("静默同意链路"))
        assertTrue(configSource.contains("广告链路：CMP 初始状态已就绪"))
        assertTrue(adManagerSource.contains("播放链路：开始请求广告"))
        assertTrue(authorizeSource.contains("authorize request request_id="))
        assertTrue(reporterSource.contains("上报链路：准备上报"))
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
