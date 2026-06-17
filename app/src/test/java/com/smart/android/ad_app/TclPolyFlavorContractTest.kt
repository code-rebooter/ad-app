package com.smart.android.ad_app

import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

class TclPolyFlavorContractTest {

    @Test
    fun `tcl_poly should stay in hq008 noneu family`() {
        assertTrue(BuildFlavor.isHq008Noneu("tcl_poly"))
        assertTrue(BuildFlavor.isHq008Family("tcl_poly"))
    }

    @Test
    fun `tcl_poly flavor config should use TCL_POLY identifiers`() {
        val buildGradle = readProjectFile("app/build.gradle")

        assertTrue(buildGradle.contains("tcl_poly {"))
        assertTrue(buildGradle.contains("applicationId       : \"com.google.android.adpoly\"") || buildGradle.contains("applicationId: \"com.google.android.adpoly\""))
        assertTrue(buildGradle.contains("versionCode         : 1") || buildGradle.contains("versionCode: 1"))
        assertTrue(buildGradle.contains("versionName         : \"1.0.1\"") || buildGradle.contains("versionName: \"1.0.1\""))
        assertTrue(buildGradle.contains("channel             : \"TCL_POLY\"") || buildGradle.contains("channel               : \"TCL_POLY\""))
        assertTrue(buildGradle.contains("cType               : \"TCL_POLY\"") || buildGradle.contains("cType                 : \"TCL_POLY\""))
        assertTrue(buildGradle.contains("model               : \"TCL_POLY\"") || buildGradle.contains("model                 : \"TCL_POLY\""))
    }

    @Test
    fun `tcl_poly should keep origin sdk isolated to its flavor`() {
        val buildGradle = readProjectFile("app/build.gradle")
        val appSource = readProjectFile("app/src/main/java/com/smart/android/ad_app/APP.kt")
        val initializer = readProjectFile("app/src/tcl_poly/java/com/smart/android/ad_app/poly/PolyGammaOriginInitializer.kt")
        val manifest = readProjectFile("app/src/tcl_poly/AndroidManifest.xml")

        assertTrue(buildGradle.contains("tcl_polyImplementation 'org.poly-gamma.android.origin:origin:0.1.2.0.1778809170'"))
        assertTrue(buildGradle.contains("onVariants(selector().withBuildType(\"debug\").withFlavor(\"ad\", \"tcl_poly\"))"))
        assertTrue(appSource.contains("BuildFlavor.isTclPoly()"))
        assertTrue(initializer.contains("Origin.initializeWithOptions"))
        assertTrue(initializer.contains("addCapability(Origin.CAPABILITY_ANTIFRAUD)"))
        assertTrue(initializer.contains("addDynamicDeviceId(\"DeviceID\""))
        assertTrue(manifest.contains("poly-gamma.origin.publisher-id"))
        assertTrue(manifest.contains("android:value=\"test\""))
        assertTrue(manifest.contains("poly-gamma.origin.region"))
        assertTrue(manifest.contains("android:value=\"cn\""))
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
