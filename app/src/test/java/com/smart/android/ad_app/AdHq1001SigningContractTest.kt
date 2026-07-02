package com.smart.android.ad_app

import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

class AdHq1001SigningContractTest {

    @Test
    fun `adhq1001 signing config should be declared with dedicated keystore`() {
        val buildGradle = readProjectFile("app/build.gradle")

        assertTrue(buildGradle.contains("adhq1001Release {"))
        assertTrue(buildGradle.contains("storeFile file(\"signing_files/adhq1001_release.jks\")"))
        assertTrue(buildGradle.contains("storePassword \"adhq1001\""))
        assertTrue(buildGradle.contains("keyAlias \"adhq1001_release\""))
        assertTrue(buildGradle.contains("keyPassword \"adhq1001\""))
        assertTrue(buildGradle.contains("storeType \"JKS\""))

        val projectRoot = findProjectRoot("app/build.gradle")
        assertTrue(File(projectRoot, "app/signing_files/adhq1001_release.jks").exists())
    }

    private fun readProjectFile(relativePath: String): String {
        return File(findProjectRoot(relativePath), relativePath).readText()
    }

    private fun findProjectRoot(relativePath: String): File {
        val workingDir = File(System.getProperty("user.dir") ?: ".")
        val projectRoot = generateSequence(workingDir) { it.parentFile }
            .firstOrNull { File(it, relativePath).exists() }
        return projectRoot ?: throw AssertionError("无法定位项目根目录: ${workingDir.absolutePath}")
    }
}
