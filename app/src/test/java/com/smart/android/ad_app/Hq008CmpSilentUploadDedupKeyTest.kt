package com.smart.android.ad_app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

class Hq008CmpSilentUploadDedupKeyTest {

    @Test
    fun `hq008 silent user action dedupe key should not depend on tc string`() {
        val source = readProjectFile("app/src/hq008/java/com/smart/android/ad_app/Hq008CmpManager.kt")

        assertTrue("应保留静默 user/action 去重函数", source.contains("buildSilentUserActionHash"))
        assertFalse(
            "静默 user/action 去重键不应直接拼接 tcString，否则每次重新生成 TC String 都会被当成新同意",
            source.contains("|${'$'}tcString")
        )
        assertFalse(
            "静默 user/action 去重函数不应继续接收 tcString 参数",
            source.contains("buildSilentUserActionHash(seed: SilentConsentSeed, tcString: String)")
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
