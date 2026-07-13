package com.smart.android.ad_app

import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

class Hq008MutePlaybackPolicyTest {

    @Test
    fun `hq008 should apply request sound mode independently from hidden mode`() {
        val source = readProjectFile("app/src/hq008/java/com/smart/android/ad_app/AdManagerImpl.kt")

        assertTrue(
            "hq008 播放链路应按本次 authorize 的 sound_mode 控制音量",
            source.contains(".setVolume(if (request.soundEnabled) 1f else 0f)")
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
