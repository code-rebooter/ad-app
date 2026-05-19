package com.smart.android.ad_app

import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

class Hq008CmpRejectFallbackContractTest {

    @Test
    fun `hq008 should skip cmp decision when campaign seed is unavailable`() {
        val source = readProjectFile("app/src/hq008/java/com/smart/android/ad_app/Hq008CmpManager.kt")

        assertTrue("缺少 campaign seed 时应直接跳过远端静默决策", source.contains("缺少 CMP campaign 种子，无法执行远端静默决策"))
        assertFalse("缺少 campaign seed 时不应再按 REJECT 兜底收口", source.contains("缺少 CMP campaign 种子，本次按 REJECT 兜底收口"))
        assertFalse("缺少 campaign seed 时不应写入 REJECT 最终决策", source.contains("markFinalCmpDecision(applicationContext, SILENT_REJECT_ACTION)"))
        assertFalse("缺少 campaign seed 时不应上报 REJECT 结果", source.contains("reportConsentResult(SILENT_REJECT_ACTION)"))
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
