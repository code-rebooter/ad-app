package com.smart.android.ad_app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

class Hq008MutePlaybackPolicyTest {

    @Test
    fun `hq008 should keep playback muted even when hidden mode is false`() {
        val source = readProjectFile("app/src/hq008/java/com/smart/android/ad_app/AdManagerImpl.kt")

        assertTrue(
            "hq008 播放链路应固定静音",
            source.contains(".setVolume(0f)")
        )
        assertFalse(
            "hq008 不应再根据 hiddenMode 决定音量",
            source.contains(".setVolume(if (hiddenMode) 0f else 1f)")
        )
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
