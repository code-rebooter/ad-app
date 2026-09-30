package com.smart.android.ad_app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AdPlaybackBroadcastRemovalContractTest {

    @Test
    fun `playback status receiver should be exposed only by jx manifest`() {
        val ytxManifest = projectFile("app/src/ad_ytx01/AndroidManifest.xml").readText()
        val jxManifest = projectFile("app/src/ad_ytx01_jx/AndroidManifest.xml").readText()

        assertFalse(ytxManifest.contains("AdPlaybackStatusReceiver"))
        assertFalse(ytxManifest.contains("io.permission.AD_PLAYBACK_STATUS"))
        assertTrue(jxManifest.contains("android:name=\".AdPlaybackStatusReceiver\""))
        assertTrue(jxManifest.contains("android:exported=\"true\""))
        assertTrue(jxManifest.contains("io.permission.AD_PLAYBACK_STATUS"))
    }

    @Test
    fun `jx playback denial should cancel the flow and stop the active floating ad`() {
        val configManager = projectFile(
            "app/src/main/java/com/smart/android/ad_app/AdConfigManager.kt"
        ).readText()
        val renderer = projectFile(
            "app/src/main/java/com/smart/android/ad_app/AdRenderer.kt"
        ).readText()
        val flowGuard = projectFile(
            "app/src/main/java/com/smart/android/ad_app/Hq008FloatingFlowGuard.kt"
        ).readText()

        val receiver = projectFile(
            "app/src/main/java/com/smart/android/ad_app/AdPlaybackStatusReceiver.kt"
        ).readText()

        assertTrue(configManager.contains("AdPlaybackAvailability.isPlaybackAllowed()"))
        assertTrue(configManager.contains("canContinueHq008FloatingFlow"))
        assertTrue(renderer.contains("stopFloatingAd"))
        assertTrue(renderer.contains("if (!AdPlaybackAvailability.isPlaybackAllowed())"))
        assertTrue(flowGuard.contains("cancelActive"))
        assertTrue(receiver.contains("Hq008FloatingFlowGuard.cancelActive"))
        assertTrue(receiver.contains("AdRenderer.stopFloatingAd"))
    }

    private fun projectFile(relativePath: String): File {
        val workingDir = File(System.getProperty("user.dir") ?: ".")
        val projectRoot = generateSequence(workingDir) { it.parentFile }
            .first { File(it, "app/build.gradle").exists() }
        return File(projectRoot, relativePath)
    }
}
