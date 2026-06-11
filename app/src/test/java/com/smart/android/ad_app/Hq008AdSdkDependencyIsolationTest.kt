package com.smart.android.ad_app

import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

class Hq008AdSdkDependencyIsolationTest {

    @Test
    fun `hq008 ad sdk and poly origin deps should stay flavor scoped`() {
        val buildGradle = readProjectFile("app/build.gradle")

        assertTrue(buildGradle.contains("hq008Implementation fileTree(dir: tclDemoLibsDir"))
        assertTrue(buildGradle.contains("hq008NoneuImplementation fileTree(dir: tclDemoLibsDir"))
        assertTrue(buildGradle.contains("hq008Noneuc2Implementation fileTree(dir: tclDemoLibsDir"))
        assertTrue(buildGradle.contains("hq008polyImplementation fileTree(dir: tclDemoLibsDir"))
        assertTrue(buildGradle.contains("hq008polyImplementation 'org.poly-gamma.android.origin:origin:0.1.2.0.1778809170'"))

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
