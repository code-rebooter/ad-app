package com.smart.android.ad_app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

class Hq008SoundModeContractTest {

    @Test
    fun `authorize sound mode should default missing values to muted`() {
        val authorizeSource = readProjectFile(
            "app/src/hq008/java/com/smart/android/ad_app/Hq008SdkAuthorizeClient.kt"
        )
        val configDtoSource = readProjectFile(
            "app/src/main/java/com/smart/android/ad_app/bean/AdConfigDto.kt"
        )
        val configManagerSource = readProjectFile(
            "app/src/main/java/com/smart/android/ad_app/AdConfigManager.kt"
        )

        assertTrue(authorizeSource.contains("@field:SerializedName(\"sound_mode\")"))
        assertTrue(authorizeSource.contains("val sound_mode: Boolean? = null"))
        assertTrue(configDtoSource.contains("val soundEnabled: Boolean = false"))
        assertTrue(configManagerSource.contains("soundEnabled = dto.sound_mode == true"))
    }

    @Test
    fun `sound mode should follow the current ad request into the ad manager`() {
        val rendererSource = readProjectFile(
            "app/src/main/java/com/smart/android/ad_app/AdRenderer.kt"
        )
        val floatingWindowSource = readProjectFile(
            "app/src/main/java/com/smart/android/ad_app/TvAdFloatingWindow.kt"
        )
        val managerInterfaceSource = readProjectFile(
            "app/src/main/java/com/smart/android/ad_app/IAdManager.kt"
        )

        assertTrue(rendererSource.contains("soundEnabled = dto.soundEnabled"))
        assertTrue(floatingWindowSource.contains("private val soundEnabled: Boolean = false"))
        assertTrue(floatingWindowSource.contains("soundEnabled = soundEnabled"))
        assertTrue(managerInterfaceSource.contains("soundEnabled: Boolean = false"))
    }

    @Test
    fun `supported players should apply sound mode to volume without rewriting GAM URL`() {
        val tclManagerSource = readProjectFile(
            "app/src/hq008/java/com/smart/android/ad_app/AdManagerImpl.kt"
        )
        val googleManagerSource = readProjectFile(
            "app/src/google_ad_tv_desktop/java/com/smart/android/ad_app/GoogleAdTvDesktopAdManager.kt"
        )
        val googlePlayerSource = readProjectFile(
            "app/src/google_ad_tv_desktop/java/com/smart/android/ad_app/google/GoogleAdVastPlayerView.kt"
        )
        val googleGamConfigClientSource = readProjectFile(
            "app/src/google_ad_tv_desktop/java/com/smart/android/ad_app/google/GoogleGamAdConfigClient.kt"
        )
        val lsapManagerSource = readProjectFile(
            "app/src/haier_lsap/java/com/smart/android/ad_app/HaierLsapAdManager.kt"
        )

        assertTrue(tclManagerSource.contains(".setVolume(if (request.soundEnabled) 1f else 0f)"))
        assertTrue(googleManagerSource.contains("adTagUrl = request.adTagUrl"))
        assertTrue(googleManagerSource.contains("soundEnabled = request.soundEnabled"))
        assertTrue(googleManagerSource.contains("\"adTagUrl\" to request.adTagUrl"))
        assertTrue(googlePlayerSource.contains("soundEnabled: Boolean"))
        assertTrue(googlePlayerSource.contains("volume = if (soundEnabled) 1f else 0f"))
        assertFalse(googleManagerSource.contains("resolveAdTagUrl"))
        assertFalse(googleGamConfigClientSource.contains("vpmute="))
        assertFalse(googleGamConfigClientSource.contains("correlator="))
        assertTrue(lsapManagerSource.contains("UnifiedAdSdk.setAdVolume(if (request.soundEnabled) 1f else 0f)"))
    }

    @Test
    fun `sound mode should be logged at authorize and player request boundaries`() {
        val authorizeSource = readProjectFile(
            "app/src/hq008/java/com/smart/android/ad_app/Hq008SdkAuthorizeClient.kt"
        )
        val configManagerSource = readProjectFile(
            "app/src/main/java/com/smart/android/ad_app/AdConfigManager.kt"
        )
        val tclManagerSource = readProjectFile(
            "app/src/hq008/java/com/smart/android/ad_app/AdManagerImpl.kt"
        )
        val googleManagerSource = readProjectFile(
            "app/src/google_ad_tv_desktop/java/com/smart/android/ad_app/GoogleAdTvDesktopAdManager.kt"
        )

        assertTrue(authorizeSource.contains("sound_mode=\${resolvedResponse.sound_mode}"))
        assertTrue(configManagerSource.contains("effective_sound_mode=\$effectiveSoundEnabled"))
        assertTrue(tclManagerSource.contains("soundEnabled=\${request.soundEnabled}"))
        assertTrue(googleManagerSource.contains("soundEnabled=\${request.soundEnabled}"))
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
