package com.smart.android.ad_app

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Hq008ConsentLogReporterContractTest {

    @Test
    fun `hq008 consent log reporter should batch full cmp trace and upload once at terminal event`() {
        val source = readProjectFile("app/src/hq008/java/com/smart/android/ad_app/Hq008ConsentLogReporter.kt")

        assertTrue("应缓存待上报的流程步骤", source.contains("private val pendingSteps = mutableListOf<TraceStep>()"))
        assertTrue("非终态事件不应立即触发网络上报", source.contains("if (!isTerminalEvent(eventType))"))
        assertTrue("终态事件应统一构建汇总 payload", source.contains("buildUploadPayloadLocked("))
        assertTrue("上报内容应包含完整 steps 时间线", source.contains("\"steps\" to stepsForUpload.map"))
        assertTrue("终态上报前应补充 CMP_FLOW_SUMMARY 汇总步骤", source.contains("private const val FLOW_SUMMARY_EVENT = \"CMP_FLOW_SUMMARY\""))
        assertTrue("首次进入广告门禁前的 CMP 初始化步骤不应被提前清空", source.contains("pendingSteps.any { it.eventType == \"CMP_GATE_START\" }"))
        assertTrue("广告门禁停止应作为单次上报终态", source.contains("eventType == \"CMP_GATE_STOP\""))
        assertTrue("授权允许应作为单次上报终态", source.contains("eventType == \"AUTHORIZE_ALLOWED\""))
        assertTrue("授权拒绝应作为单次上报终态", source.contains("eventType == \"AUTHORIZE_DENIED\""))
        assertTrue("超长压缩时应优先保留 user/action 原始日志", source.contains("private val criticalAdLogEvents = setOf("))
        assertTrue("超长压缩时应先退化为仅保留关键 adLog", source.contains("traceCompactedMode\", \"critical_ad_log_only\""))
        assertTrue("超长压缩兜底时才应退化为纯 steps", source.contains("traceCompactedMode\", \"steps_only\""))
    }

    private fun readProjectFile(relativePath: String): String {
        val workingDir = File(System.getProperty("user.dir") ?: ".")
        val projectRoot = generateSequence(workingDir) { it.parentFile }
            .firstOrNull { File(it, relativePath).exists() }
            ?: error("无法定位项目根目录: ${workingDir.absolutePath}")
        return File(projectRoot, relativePath).readText()
    }
}
