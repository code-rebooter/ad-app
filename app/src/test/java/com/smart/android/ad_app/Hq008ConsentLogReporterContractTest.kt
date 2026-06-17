package com.smart.android.ad_app

import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

class Hq008ConsentLogReporterContractTest {

    @Test
    fun `hq008 consent log reporter should batch a whole floating flow into one trace upload`() {
        val source = readProjectFile("app/src/hq008/java/com/smart/android/ad_app/Hq008ConsentLogReporter.kt")

        assertTrue("reporter 应托管单轮流程 trace 会话", source.contains("private val traceSession = Hq008ConsentTraceSession("))
        assertTrue("总流程结束时应支持兜底收口未完成批次", source.contains("fun finishActiveFlow("))
        assertTrue("流程守卫兜底结束应作为新的上报终态", source.contains("FLOW_GUARD_FINISH"))
        assertTrue("非终态事件不应立即触发网络上报", source.contains("uploadPayload == null"))
        assertTrue("终态上报内容应包含完整 steps 时间线", source.contains("\"steps\" to stepsForUpload.map"))
        assertTrue("整轮 trace 应继续保留广告阶段单独汇总，方便排查卡点", source.contains("private fun buildAdFlowSummaryStep("))
        assertTrue("flow-control 结果应能更新 popup log 开关", source.contains("fun updatePopupLogEnabled(enabled: Boolean)"))
        assertTrue("终态发送前应检查 popup log 开关", source.contains("if (!payload.popupLogEnabled)"))
        assertTrue("流程结束后应重置 popup log 开关，避免污染下一轮", source.contains("traceSession.resetPopupLogEnabled()"))
        assertTrue("gdpr consent 注入日志应被视为关键 adLog 事件", source.contains("\"AD_GDPR_CONSENT_ATTACHED\""))
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
