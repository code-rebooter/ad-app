package com.smart.android.ad_app

import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

class Hq008AdSdkDependencyIsolationTest {

    @Test
    fun `hq008 ad sdk and poly origin deps should stay flavor scoped`() {
        val buildGradle = readProjectFile("app/build.gradle")

        assertTrue(buildGradle.contains("hq008TclSharedPatchedAars.each { patchedAar ->"))
        listOf(
            "hq008Implementation patchedAar",
            "tcl_aishangImplementation patchedAar",
            "ad_ytx01Implementation patchedAar",
            "ad_ytx01_sxkImplementation patchedAar",
            "ad_ytx01_jxImplementation patchedAar",
            "ad_album_101_001Implementation patchedAar",
            "hq008NoneuImplementation patchedAar",
            "hq008Noneuc2Implementation patchedAar",
            "tcl_polyImplementation patchedAar",
            "hq008XHSXImplementation patchedAar"
        ).forEach { dependency ->
            assertTrue("TCL 2.8.02 渠道必须使用修补后的 AAR: $dependency", buildGradle.contains(dependency))
        }
        assertTrue(buildGradle.contains("tcl_polyImplementation 'org.poly-gamma.android.origin:origin:0.1.2.0.1778809170'"))

        assertNoPublicDependency(buildGradle, "implementation\\s+fileTree\\(dir: tclDemoLibsDir")
        assertNoPublicDependency(buildGradle, "implementation\\s+'org\\.poly-gamma\\.android\\.origin:origin:")
        assertNoPublicDependency(buildGradle, "api\\s+'org\\.poly-gamma\\.android\\.origin:origin:")
    }

    private fun assertNoPublicDependency(buildGradle: String, pattern: String) {
        assertTrue(
            "公共依赖区不应出现匹配: $pattern",
            !Regex("(?m)^\\s*$pattern").containsMatchIn(buildGradle)
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
