package com.smart.android.ad_app

import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

class HaierLsapDebugEntryContractTest {

    @Test
    fun `haier_lsap flavor should declare dedicated sdk dependency and identifiers`() {
        val buildGradle = readProjectFile("app/build.gradle")

        assertTrue(buildGradle.contains("haier_lsap {"))
        assertTrue(buildGradle.contains("java.srcDirs = ['src/hq008/java', 'src/haier_lsap/java']"))
        assertTrue(buildGradle.contains("res.srcDirs = ['src/hq008/res', 'src/haier_lsap/res']"))
        assertTrue(buildGradle.contains("haier_lsapImplementation files('libs/haier_lsap/lsapsdk-release_v1.1.8.jar')"))
        assertTrue(buildGradle.contains("channel             : \"HAIER_LSAP\"") || buildGradle.contains("channel               : \"HAIER_LSAP\""))
        assertTrue(buildGradle.contains("cType               : \"HAIER_LSAP\"") || buildGradle.contains("cType                 : \"HAIER_LSAP\""))
        assertTrue(buildGradle.contains("model               : \"HAIER_LSAP\"") || buildGradle.contains("model                 : \"HAIER_LSAP\""))
    }

    @Test
    fun `haier_lsap debug manifest should expose launcher test activity`() {
        val flavorManifest = readProjectFile("app/src/haier_lsap/AndroidManifest.xml")
        val debugManifest = readProjectFile("app/src/haier_lsapDebug/AndroidManifest.xml")

        assertTrue(flavorManifest.contains("android.software.leanback"))
        assertTrue(flavorManifest.contains("android:banner=\"@drawable/haier_lsap_tv_banner\""))
        assertTrue(debugManifest.contains(".haier.HaierLsapDebugEntryActivity"))
        assertTrue(debugManifest.contains("Haier LSAP Test"))
        assertTrue(debugManifest.contains("android.intent.category.LAUNCHER"))
        assertTrue(debugManifest.contains("android.intent.category.LEANBACK_LAUNCHER"))
    }

    @Test
    fun `haier_lsap debug controls should be focusable for tv remote navigation`() {
        val layoutSource = readProjectFile("app/src/haier_lsap/res/layout/activity_haier_lsap_test_entry.xml")
        val activitySource = readProjectFile("app/src/haier_lsap/java/com/smart/android/ad_app/haier/HaierLsapDebugEntryActivity.kt")

        assertTrue(layoutSource.contains("android:id=\"@+id/init_button\""))
        assertTrue(layoutSource.contains("android:id=\"@+id/attach_button\""))
        assertTrue(layoutSource.contains("android:id=\"@+id/detach_button\""))
        assertTrue(layoutSource.contains("android:focusable=\"true\""))
        assertTrue(layoutSource.contains("android:clickable=\"true\""))
        assertTrue(layoutSource.contains("@drawable/haier_lsap_debug_init_button_bg"))
        assertTrue(layoutSource.contains("@drawable/haier_lsap_debug_attach_button_bg"))
        assertTrue(layoutSource.contains("@drawable/haier_lsap_debug_detach_button_bg"))
        assertTrue(activitySource.contains("requestFocus()"))
    }

    @Test
    fun `haier_lsap debug activity should init sdk and attach vast player to a container`() {
        val activitySource = readProjectFile("app/src/haier_lsap/java/com/smart/android/ad_app/haier/HaierLsapDebugEntryActivity.kt")

        assertTrue(activitySource.contains("LSAPAPI.init("))
        assertTrue(activitySource.contains("LSAPAPI.requestAd("))
        assertTrue(activitySource.contains("VastAdPlayer.attach("))
        assertTrue(activitySource.contains("onAdLoaded("))
        assertTrue(activitySource.contains("onAdFailed("))
        assertTrue(activitySource.contains("setOnPreparedListener"))
        assertTrue(activitySource.contains("setOnCompletionListener"))
        assertTrue(activitySource.contains("setOnErrorListener"))
        assertTrue(activitySource.contains("handleKeyEvent("))
        assertTrue(activitySource.contains("detach()"))
        assertTrue(activitySource.contains("510000001301"))
        assertTrue(activitySource.contains("com.ctv.hetv"))
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
