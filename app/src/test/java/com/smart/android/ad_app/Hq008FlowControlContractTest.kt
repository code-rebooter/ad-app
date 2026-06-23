package com.smart.android.ad_app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

class Hq008FlowControlContractTest {

    @Test
    fun `hq008 floating ad request should call flow-control before cmp gate`() {
        val adConfigManagerSource = readProjectFile("app/src/main/java/com/smart/android/ad_app/AdConfigManager.kt")
        val flowControlClientSource = readProjectFile("app/src/hq008/java/com/smart/android/ad_app/Hq008SdkFlowControlClient.kt")
        val adReporterSource = readProjectFile("app/src/hq008/java/com/smart/android/ad_app/Hq008AdReporter.kt")

        assertTrue("hq008 浮窗广告入口应先请求 flow-control", adConfigManagerSource.contains("Hq008SdkFlowControlClient.request"))
        assertTrue("flow-control 关闭时应跳过整套客户流程", adConfigManagerSource.contains("客户SDK总开关关闭，本轮跳过CMP/授权/广告全链路"))
        assertTrue("flow-control 结果应驱动 CMP 是否跳过", adConfigManagerSource.contains("val skipCmp = dto?.skip_cmp == true"))
        assertFalse("CMP 是否跳过不应再由 noneu flavor 静态决定", adConfigManagerSource.contains("val skipCmp = BuildFlavor.isHq008Noneu()"))
        assertTrue("flow-control client 应请求 sdk/flow-control 接口", flowControlClientSource.contains("api/v2/ad/sdk/flow-control"))
        assertTrue("flow-control 响应应解析 popup_log_enabled 字段", flowControlClientSource.contains("@field:SerializedName(\"popup_log_enabled\")"))
        assertTrue("flow-control 响应应解析 skip_cmp 字段", flowControlClientSource.contains("@field:SerializedName(\"skip_cmp\")"))
        assertTrue("flow-control 成功后应同步日志开关给 reporter", flowControlClientSource.contains("Hq008ConsentLogReporter.updatePopupLogEnabled(popupLogEnabled)"))
        assertTrue("flow-control 结果日志应带上 popupLogEnabled 和 skipCmp 便于排查", flowControlClientSource.contains("popupLogEnabled=\$popupLogEnabled,skipCmp=\$skipCmp"))
        assertFalse("ad/report 不应受 popup log 开关控制", adReporterSource.contains("if (!Hq008ConsentLogReporter.isPopupLogEnabled())"))
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
