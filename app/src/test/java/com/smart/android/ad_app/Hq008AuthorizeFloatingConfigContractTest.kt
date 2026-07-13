package com.smart.android.ad_app

import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

class Hq008AuthorizeFloatingConfigContractTest {

    @Test
    fun `hq008 authorize flow should accept backend floating overrides`() {
        val source = readProjectFile("app/src/main/java/com/smart/android/ad_app/AdConfigManager.kt")

        assertTrue(source.contains("private const val HQ008_DEFAULT_FLOATING_WIDTH = 210"))
        assertTrue(source.contains("private const val HQ008_DEFAULT_FLOATING_HEIGHT = 131"))
        assertTrue(source.contains("private const val HQ008_DEFAULT_FLOATING_X = 0"))
        assertTrue(source.contains("private const val HQ008_DEFAULT_FLOATING_Y = 0"))
        assertTrue(source.contains("private const val HQ008_DEFAULT_FLOATING_POSITION = 0"))
        assertTrue(source.contains("private fun buildHq008FloatingAdConfig("))
        assertTrue(source.contains("floatingWidth = dto.floating_width ?: HQ008_DEFAULT_FLOATING_WIDTH"))
        assertTrue(source.contains("floatingHeight = dto.floating_height ?: HQ008_DEFAULT_FLOATING_HEIGHT"))
        assertTrue(source.contains("floatingX = dto.floating_x ?: HQ008_DEFAULT_FLOATING_X"))
        assertTrue(source.contains("floatingY = dto.floating_y ?: HQ008_DEFAULT_FLOATING_Y"))
        assertTrue(source.contains("position = dto.position ?: HQ008_DEFAULT_FLOATING_POSITION"))
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
