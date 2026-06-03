package com.smart.android.ad_app

import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

class Hq008Noneuc2FlavorContractTest {

    @Test
    fun `hq008Noneuc2 should stay in hq008 noneu family`() {
        assertTrue(BuildFlavor.isHq008Noneu("hq008Noneuc2"))
        assertTrue(BuildFlavor.isHq008Family("hq008Noneuc2"))
    }

    @Test
    fun `hq008Noneuc2 flavor config should use TCL_NONEY_C2 identifiers`() {
        val buildGradle = readProjectFile("app/build.gradle")

        assertTrue(buildGradle.contains("hq008Noneuc2 {"))
        assertTrue(buildGradle.contains("channel             : \"TCL_NONEY_C2\"") || buildGradle.contains("channel               : \"TCL_NONEY_C2\""))
        assertTrue(buildGradle.contains("cType               : \"TCL_NONEY_C2\"") || buildGradle.contains("cType                 : \"TCL_NONEY_C2\""))
        assertTrue(buildGradle.contains("model               : \"TCL_NONEY_C2\"") || buildGradle.contains("model                 : \"TCL_NONEY_C2\""))
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
