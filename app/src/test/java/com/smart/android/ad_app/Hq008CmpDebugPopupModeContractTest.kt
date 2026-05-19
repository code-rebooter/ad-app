package com.smart.android.ad_app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

class Hq008CmpDebugPopupModeContractTest {

    @Test
    fun `hq008 debug cmp activity should use pop mode for normal user popup testing`() {
        val source = readProjectFile("app/src/hq008/java/com/smart/android/ad_app/Hq008CmpManager.kt")

        assertTrue("调试页应走真实的 CMP_POP 弹窗模式", source.contains("CmpDisplayType.CMP_POP"))
        assertFalse("调试页不应继续走 CMP_NOT_POP", source.contains("CmpDisplayType.CMP_NOT_POP"))
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
