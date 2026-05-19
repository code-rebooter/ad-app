package com.smart.android.ad_app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

class ForegroundAppWatcherContextContractTest {

    @Test
    fun `foreground watcher should use explicit application context instead of global appContext`() {
        val source = readProjectFile("app/src/main/java/com/smart/android/ad_app/ForegroundAppWatcher.kt")

        assertTrue(source.contains("private lateinit var applicationContext: Context"))
        assertTrue(source.contains("fun start(context: Context, onAppOpened: (String) -> Unit)"))
        assertTrue(source.contains("applicationContext = context.applicationContext"))
        assertTrue(source.contains("applicationContext.getSystemService"))
        assertFalse(source.contains("val am = appContext.getSystemService"))
        assertFalse(source.contains("ForegroundAppWatcher 检测失败: \${e.message}.printLog()"))
        assertFalse(source.contains(".printLog()"))
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
