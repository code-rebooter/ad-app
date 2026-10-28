package com.smart.android.ad_app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

class GoogleAdTvDesktopFlavorContractTest {

    @Test
    fun `channel capabilities should remain independent`() {
        assertTrue(BuildFlavor.usesTclRuntime(BuildFlavor.CHANNEL_TYPE_GOOGLE))
        assertTrue(BuildFlavor.needsIdentityPatch(BuildFlavor.CHANNEL_TYPE_GOOGLE))
        assertTrue(BuildFlavor.usesGoogleConsent(BuildFlavor.CHANNEL_TYPE_GOOGLE))

        assertTrue(BuildFlavor.usesTclRuntime(BuildFlavor.CHANNEL_TYPE_TCL))
        assertTrue(BuildFlavor.needsIdentityPatch(BuildFlavor.CHANNEL_TYPE_TCL))
        assertFalse(BuildFlavor.usesGoogleConsent(BuildFlavor.CHANNEL_TYPE_TCL))

        assertFalse(BuildFlavor.usesTclRuntime(BuildFlavor.CHANNEL_TYPE_LSAP))
        assertTrue(BuildFlavor.needsIdentityPatch(BuildFlavor.CHANNEL_TYPE_LSAP))
        assertFalse(BuildFlavor.usesGoogleConsent(BuildFlavor.CHANNEL_TYPE_LSAP))

        assertFalse(BuildFlavor.usesTclRuntime("OTHER"))
        assertFalse(BuildFlavor.needsIdentityPatch("OTHER"))
        assertFalse(BuildFlavor.usesGoogleConsent("OTHER"))
    }

    @Test
    fun `google_ad_tv_desktop should keep target package signing channel and shared Google runtime`() {
        val buildGradle = readProjectFile("app/build.gradle")
        val productFlavorsBlock = extractBlock(buildGradle, "    productFlavors {")
        val flavorBlock = extractBlock(productFlavorsBlock, "        google_ad_tv_desktop {")
        val buildFlavorSource = readProjectFile("app/src/main/java/com/smart/android/ad_app/BuildFlavor.kt")
        val projectRoot = findProjectRoot("app/build.gradle")

        assertTrue(BuildFlavor.isGoogleAd(BuildFlavor.CHANNEL_TYPE_GOOGLE))
        assertTrue(BuildFlavor.usesSharedAdRuntime(BuildFlavor.CHANNEL_TYPE_GOOGLE))
        assertTrue(buildFlavorSource.contains("fun isGoogleAd("))

        assertTrue(buildGradle.contains("google_ad_tv_desktop {"))
        val sharedBuild = readProjectFile("app/gradle/google-url-tcl.gradle")
        assertTrue(sharedBuild.contains("'src/google_ad_tv_desktop/java'"))
        assertTrue(sharedBuild.contains("'src/googleUrlIndependent/java'"))
        assertFalse(sharedBuild.contains("'src/googleUrlIma/java'"))
        assertFalse(sharedBuild.contains("GoogleImaUrlVisitorFactory"))
        assertTrue(sharedBuild.contains("res.srcDirs = ['src/hq008/res', 'src/google_ad_tv_desktop/res']"))

        assertTrue(flavorBlock.contains("applicationId       : \"io.android.launcher.tv.desktop\"") || flavorBlock.contains("applicationId: \"io.android.launcher.tv.desktop\""))
        assertTrue(flavorBlock.contains("adChannelType      : adChannelTypeGoogle"))
        assertTrue(flavorBlock.contains("versionCode         : 10") || flavorBlock.contains("versionCode: 10"))
        assertTrue(flavorBlock.contains("versionName         : \"1.0.9\"") || flavorBlock.contains("versionName: \"1.0.9\""))
        assertTrue(flavorBlock.contains("channel             : \"GOOGLE_AD_TV_DESKTOP\"") || flavorBlock.contains("channel               : \"GOOGLE_AD_TV_DESKTOP\""))
        assertTrue(flavorBlock.contains("cType               : \"GOOGLE_AD_TV_DESKTOP\"") || flavorBlock.contains("cType                 : \"GOOGLE_AD_TV_DESKTOP\""))
        assertTrue(flavorBlock.contains("model               : \"GOOGLE_AD_TV_DESKTOP\"") || flavorBlock.contains("model                 : \"GOOGLE_AD_TV_DESKTOP\""))
        assertTrue(flavorBlock.contains("signingConfig       : signingConfigs.googleAdTvDesktopRelease") || flavorBlock.contains("signingConfig: signingConfigs.googleAdTvDesktopRelease"))

        assertTrue(buildGradle.contains("googleAdTvDesktopRelease {"))
        assertTrue(buildGradle.contains("storeFile file(\"signing_files/google_ad_tv_desktop_upload.jks\")"))
        assertTrue(buildGradle.contains("keyAlias \"tvdesktopupload\""))
        assertTrue(buildGradle.contains("storeType \"PKCS12\""))
        assertTrue(buildGradle.contains("flavor.buildConfigField \"String\", \"AD_CHANNEL_TYPE\""))
        assertTrue(buildGradle.contains("def googleAdFlavors = adChannelTypesByFlavor.findAll"))
        assertTrue(buildGradle.contains("googleAdSigningConfigNames.each { flavorName, signingConfigName ->"))
        assertTrue(buildGradle.contains("withFlavor(\"ad\", flavorName)"))

        assertFalse(buildGradle.contains("androidx.media3:media3-exoplayer-ima"))
        assertFalse(buildGradle.contains("providers.gradleProperty('googleUrlEngine')"))
        assertTrue(buildGradle.contains("independent-google-tv-url-local-identity-20261025.aar"))
        assertTrue(buildGradle.contains("add(googleAdCommonImplementation.name, project(':ad-sdk-tcl'))"))
        assertTrue(buildGradle.contains("extendsFrom googleAdCommonImplementation"))

        assertTrue(File(projectRoot, "app/signing_files/google_ad_tv_desktop_upload.jks").exists())
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
