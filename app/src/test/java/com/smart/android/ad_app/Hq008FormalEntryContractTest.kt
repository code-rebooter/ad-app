package com.smart.android.ad_app

import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

class Hq008FormalEntryContractTest {

    @Test
    fun `hq008 formal manifest should not expose launcher activity entry`() {
        val manifestSource = readProjectFile("app/src/hq008/AndroidManifest.xml")

        assertTrue("正式环境不应保留 MAIN 入口", !manifestSource.contains("android.intent.action.MAIN"))
        assertTrue("正式环境不应保留 LAUNCHER 入口", !manifestSource.contains("android.intent.category.LAUNCHER"))
        assertTrue("调试 Activity 可以保留，但不应作为入口暴露", manifestSource.contains("Hq008CmpDebugActivity"))
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
