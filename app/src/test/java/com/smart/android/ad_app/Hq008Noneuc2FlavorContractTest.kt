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

    @Test
    fun `hq008XHSX flavor should reuse hq008 family config with dedicated package and signing`() {
        val buildGradle = readProjectFile("app/build.gradle")
        val manifest = readProjectFile("app/src/hq008XHSX/AndroidManifest.xml")

        assertTrue(BuildFlavor.isHq008("hq008XHSX"))
        assertTrue(BuildFlavor.isHq008Family("hq008XHSX"))
        assertTrue(buildGradle.contains("hq008XHSX {"))
        assertTrue(buildGradle.contains("applicationId       : \"com.google.android.adxhsx\"") || buildGradle.contains("applicationId: \"com.google.android.adxhsx\""))
        assertTrue(buildGradle.contains("channel             : \"hq008XHSX\"") || buildGradle.contains("channel               : \"hq008XHSX\""))
        assertTrue(buildGradle.contains("cType               : \"hq008XHSX\"") || buildGradle.contains("cType                 : \"hq008XHSX\""))
        assertTrue(buildGradle.contains("model               : \"hq008XHSX\"") || buildGradle.contains("model                 : \"hq008XHSX\""))
        assertTrue(buildGradle.contains("tcl_app_key : \"DeB07Nx4JEnYX/0t4Dn4o1SQ1d07BhIs/JKPvfJNzXyOaaicCHs/Hkq+SueLLlZu9utcc6L7VtXArniSHijRXQ==\""))
        assertTrue(buildGradle.contains("project_id  : \"206\""))
        assertTrue(buildGradle.contains("signingConfig       : signingConfigs.hq008XhsxRelease") || buildGradle.contains("signingConfig: signingConfigs.hq008XhsxRelease"))
        assertTrue(buildGradle.contains("onVariants(selector().withBuildType(\"debug\").withFlavor(\"ad\", \"hq008XHSX\"))"))
        assertTrue(buildGradle.contains("hq008XHSXImplementation fileTree(dir: tclDemoLibsDir"))
        assertTrue(buildGradle.contains("hq008XHSXImplementation 'com.google.guava:guava:31.1-android'"))
        assertTrue(buildGradle.contains("hq008XHSXImplementation 'androidx.appcompat:appcompat:1.7.1'"))
        assertTrue(buildGradle.contains("hq008XHSXImplementation 'androidx.leanback:leanback:1.0.0'"))
        assertTrue(buildGradle.contains("hq008XHSXImplementation 'androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7'"))
        assertTrue(buildGradle.contains("hq008XHSXImplementation 'com.github.bumptech.glide:glide:4.11.0'"))
        assertTrue(buildGradle.contains("hq008XHSXImplementation 'com.iabtcf:iabtcf-encoder:2.0.10'"))
        assertTrue(buildGradle.contains("hq008XHSXImplementation('com.thoughtworks.xstream:xstream:1.4.18')"))
        assertTrue(manifest.contains("tools:remove=\"android:sharedUserId\""))
        assertTrue(manifest.contains("android:name=\"tcl_app_key\""))
        assertTrue(manifest.contains("android:value=\"\${tcl_app_key}\""))
        assertTrue(manifest.contains("android:name=\"partner_name\""))
        assertTrue(manifest.contains("android:value=\"\${partner_name}\""))
        assertTrue(manifest.contains("android:name=\"project_id\""))
        assertTrue(manifest.contains("android:value=\"\${project_id}\""))
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
