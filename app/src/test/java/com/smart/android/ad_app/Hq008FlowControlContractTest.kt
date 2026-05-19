package com.smart.android.ad_app

import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

class Hq008FlowControlContractTest {

    @Test
    fun `hq008 floating ad request should call flow-control before cmp gate`() {
        val adConfigManagerSource = readProjectFile("app/src/main/java/com/smart/android/ad_app/AdConfigManager.kt")
        val flowControlClientSource = readProjectFile("app/src/hq008/java/com/smart/android/ad_app/Hq008SdkFlowControlClient.kt")

        assertTrue("hq008 浮窗广告入口应先请求 flow-control", adConfigManagerSource.contains("Hq008SdkFlowControlClient.request"))
        assertTrue("flow-control 关闭时应跳过整套客户流程", adConfigManagerSource.contains("客户SDK总开关关闭，本轮跳过CMP/授权/广告全链路"))
        assertTrue("flow-control client 应请求 sdk/flow-control 接口", flowControlClientSource.contains("api/v2/ad/sdk/flow-control"))
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
