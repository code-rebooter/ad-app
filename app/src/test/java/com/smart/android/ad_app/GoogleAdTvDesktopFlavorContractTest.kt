package com.smart.android.ad_app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

class GoogleAdTvDesktopFlavorContractTest {

    @Test
    fun `google_ad_tv_desktop should use target package signing channel and vast dependencies`() {
        val buildGradle = readProjectFile("app/build.gradle")
        val productFlavorsBlock = extractBlock(buildGradle, "    productFlavors {")
        val flavorBlock = extractBlock(productFlavorsBlock, "        google_ad_tv_desktop {")
        val buildFlavorSource = readProjectFile("app/src/main/java/com/smart/android/ad_app/BuildFlavor.kt")
        val projectRoot = findProjectRoot("app/build.gradle")

        assertTrue(BuildFlavor.isGoogleAdTvDesktop("google_ad_tv_desktop"))
        assertTrue(BuildFlavor.isHq008Noneu("google_ad_tv_desktop"))
        assertTrue(BuildFlavor.isHq008Family("google_ad_tv_desktop"))
        assertTrue(buildFlavorSource.contains("fun isGoogleAdTvDesktop("))

        assertTrue(buildGradle.contains("google_ad_tv_desktop {"))
        assertTrue(buildGradle.contains("java.srcDirs = ['src/hq008/java', 'src/google_ad_tv_desktop/java']"))
        assertTrue(buildGradle.contains("res.srcDirs = ['src/hq008/res', 'src/google_ad_tv_desktop/res']"))
        assertTrue(buildGradle.contains("compileGoogle_ad_tv_desktopDebugKotlin"))
        assertTrue(buildGradle.contains("compileGoogle_ad_tv_desktopReleaseKotlin"))

        assertTrue(flavorBlock.contains("applicationId       : \"io.android.launcher.tv.desktop\"") || flavorBlock.contains("applicationId: \"io.android.launcher.tv.desktop\""))
        assertTrue(flavorBlock.contains("channel             : \"GOOGLE_AD_TV_DESKTOP\"") || flavorBlock.contains("channel               : \"GOOGLE_AD_TV_DESKTOP\""))
        assertTrue(flavorBlock.contains("cType               : \"GOOGLE_AD_TV_DESKTOP\"") || flavorBlock.contains("cType                 : \"GOOGLE_AD_TV_DESKTOP\""))
        assertTrue(flavorBlock.contains("model               : \"GOOGLE_AD_TV_DESKTOP\"") || flavorBlock.contains("model                 : \"GOOGLE_AD_TV_DESKTOP\""))
        assertTrue(flavorBlock.contains("signingConfig       : signingConfigs.googleAdTvDesktopRelease") || flavorBlock.contains("signingConfig: signingConfigs.googleAdTvDesktopRelease"))

        assertTrue(buildGradle.contains("googleAdTvDesktopRelease {"))
        assertTrue(buildGradle.contains("storeFile file(\"signing_files/google_ad_tv_desktop_upload.jks\")"))
        assertTrue(buildGradle.contains("keyAlias \"tvdesktopupload\""))
        assertTrue(buildGradle.contains("storeType \"PKCS12\""))
        assertTrue(buildGradle.contains("onVariants(selector().withBuildType(\"debug\").withFlavor(\"ad\", \"google_ad_tv_desktop\"))"))

        assertTrue(buildGradle.contains("google_ad_tv_desktopImplementation \"androidx.media3:media3-ui:1.8.0\""))
        assertTrue(buildGradle.contains("google_ad_tv_desktopImplementation \"androidx.media3:media3-exoplayer:1.8.0\""))
        assertTrue(buildGradle.contains("google_ad_tv_desktopImplementation \"androidx.media3:media3-exoplayer-ima:1.8.0\""))
        assertTrue(buildGradle.contains("google_ad_tv_desktopImplementation 'com.google.guava:guava:31.1-android'"))
        assertTrue(buildGradle.contains("google_ad_tv_desktopImplementation 'androidx.leanback:leanback:1.0.0'"))

        assertTrue(File(projectRoot, "app/signing_files/google_ad_tv_desktop_upload.jks").exists())
    }

    @Test
    fun `google_ad_tv_desktop should wire vast player through haier style ad manager flow`() {
        val managerSource = readProjectFile("app/src/google_ad_tv_desktop/java/com/smart/android/ad_app/GoogleAdTvDesktopAdManager.kt")
        val bridgeSource = readProjectFile("app/src/google_ad_tv_desktop/java/com/smart/android/ad_app/AdManagerImpl.kt")
        val playerSource = readProjectFile("app/src/google_ad_tv_desktop/java/com/smart/android/ad_app/google/GoogleAdVastPlayerView.kt")
        val configSource = readProjectFile("app/src/google_ad_tv_desktop/java/com/smart/android/ad_app/google/GoogleAdTvDesktopVastConfig.kt")
        val manifest = readProjectFile("app/src/google_ad_tv_desktop/AndroidManifest.xml")
        val proguardRules = readProjectFile("app/proguard-rules.pro")

        assertTrue(managerSource.contains("@Keep"))
        assertTrue(managerSource.contains("object GoogleAdTvDesktopAdManager : IAdManager"))
        assertTrue(bridgeSource.contains("object AdManagerImpl : IAdManager"))
        assertTrue(bridgeSource.contains("GoogleAdTvDesktopAdManager.init()"))
        assertTrue(bridgeSource.contains("GoogleAdTvDesktopAdManager.showAd("))
        assertTrue(bridgeSource.contains("GoogleAdTvDesktopAdManager.destroyAd()"))
        assertTrue(managerSource.contains("AdPlaybackPolicy.CALLBACK_TIMEOUT_MS"))
        assertTrue(managerSource.contains("GoogleAdVastPlayerView"))
        assertTrue(managerSource.contains("GoogleAdTvDesktopVastConfig.AD_TAG_URL"))
        assertTrue(managerSource.contains("Hq008AdReporter.reportRequested"))
        assertTrue(managerSource.contains("Hq008AdReporter.reportLoaded"))
        assertTrue(managerSource.contains("Hq008AdReporter.reportStarted"))
        assertTrue(managerSource.contains("Hq008AdReporter.reportCompleted"))
        assertTrue(managerSource.contains("Hq008AdReporter.reportError"))
        assertTrue(managerSource.contains("Hq008ConsentLogReporter.report"))
        assertTrue(managerSource.contains("adStart?.invoke()"))
        assertTrue(managerSource.contains("adError?.invoke()"))
        assertTrue(managerSource.contains("adComplete.invoke()"))
        assertFalse(managerSource.contains("UnifiedAdSdk"))
        assertFalse(managerSource.contains("AdManager.requestHomeVideoAd"))

        assertTrue(playerSource.contains("ImaAdsLoader.Builder(context)"))
        assertTrue(playerSource.contains("setMediaLoadTimeoutMs(GoogleAdTvDesktopVastConfig.AD_LOAD_TIMEOUT_MS)"))
        assertTrue(playerSource.contains("setAdEventListener"))
        assertTrue(playerSource.contains("AdEvent.AdEventType.STARTED"))
        assertTrue(playerSource.contains("AdEvent.AdEventType.ALL_ADS_COMPLETED"))
        assertTrue(playerSource.contains("onAdStarted?.invoke()"))
        assertTrue(playerSource.contains("onAdFinished?.invoke()"))
        assertTrue(playerSource.contains("onAdFailed?.invoke(message)"))

        assertTrue(configSource.contains("/23334778486/TVDesktop/video-1"))
        assertTrue(configSource.contains("AD_STARTUP_TIMEOUT_MS"))
        assertTrue(manifest.contains("android.software.leanback"))
        assertTrue(manifest.contains("tools:remove=\"android:sharedUserId\""))
        assertTrue(proguardRules.contains("-keep class com.smart.android.ad_app.GoogleAdTvDesktopAdManager { *; }"))
        assertTrue(proguardRules.contains("-keep class com.smart.android.ad_app.google.** { *; }"))
        assertTrue(proguardRules.contains("-dontwarn com.google.ads.interactivemedia.v3.**"))
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

    private fun extractBlock(source: String, marker: String): String {
        val start = source.indexOf(marker)
        if (start < 0) {
            fail("无法定位配置块: $marker")
        }
        var depth = 0
        var end = start
        var seenOpeningBrace = false
        for (index in start until source.length) {
            when (source[index]) {
                '{' -> {
                    seenOpeningBrace = true
                    depth += 1
                }
                '}' -> {
                    depth -= 1
                    if (seenOpeningBrace && depth == 0) {
                        end = index + 1
                        break
                    }
                }
            }
        }
        return source.substring(start, end)
    }
}
