package com.smart.android.ad_app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

class Hq008AuthorizeDebugOverrideTest {

    @Test
    fun `hq008 authorize flow should use backend authorized and hidden_mode directly`() {
        val source = readProjectFile("app/src/main/java/com/smart/android/ad_app/AdConfigManager.kt")

        assertTrue(source.contains("val effectiveAuthorized = dto.authorized"))
        assertTrue(source.contains("val effectiveHiddenMode = dto.hidden_mode"))
        assertTrue(source.contains("AdDisplayConfig.setRemoteHiddenMode(effectiveHiddenMode)"))
        assertTrue(source.contains("effective_authorized=\$effectiveAuthorized"))
        assertTrue(source.contains("effective_hidden_mode=\$effectiveHiddenMode"))
        assertFalse(source.contains("effectiveAuthorized = true"))
        assertFalse(source.contains("effectiveHiddenMode = false"))
        assertFalse(source.contains("TEMP DEBUG OVERRIDE"))
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
