package com.smart.android.ad_app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.security.MessageDigest

class AddyHaierLsapChannelContractTest {

    @Test
    fun `addy hq1002 and addy jams should declare independent haier lsap based channels`() {
        val buildGradle = readProjectFile("app/build.gradle")
        val sourceSetsBlock = extractBlock(buildGradle, "    sourceSets {")
        val signingConfigsBlock = extractBlock(buildGradle, "    signingConfigs {")
        val productFlavorsBlock = extractBlock(buildGradle, "    productFlavors {")
        val hq1002Block = extractBlock(productFlavorsBlock, "        addy_hq1002 {")
        val jamsBlock = extractBlock(productFlavorsBlock, "        addy_jams {")
        val buildFlavorSource = readProjectFile("app/src/main/java/com/smart/android/ad_app/BuildFlavor.kt")
        val managerSource = readProjectFile("app/src/haier_lsap/java/com/smart/android/ad_app/HaierLsapAdManager.kt")
        val projectRoot = findProjectRoot("app/build.gradle")

        assertTrue(sourceSetsBlock.contains("addy_hq1002 {"))
        assertTrue(sourceSetsBlock.contains("java.srcDirs = ['src/hq008/java', 'src/haier_lsap/java']"))
        assertTrue(sourceSetsBlock.contains("res.srcDirs = ['src/hq008/res', 'src/haier_lsap/res']"))
        assertTrue(sourceSetsBlock.contains("manifest.srcFile 'src/haier_lsap/AndroidManifest.xml'"))
        assertTrue(sourceSetsBlock.contains("addy_jams {"))
        assertTrue(sourceSetsBlock.contains("addy_hq1002Debug {"))
        assertTrue(sourceSetsBlock.contains("addy_jamsDebug {"))
        assertTrue(sourceSetsBlock.contains("manifest.srcFile 'src/haier_lsapDebug/AndroidManifest.xml'"))
        assertTrue(sourceSetsBlock.contains("java.srcDirs = ['src/haier_lsapDebug/java']"))
        assertTrue(sourceSetsBlock.contains("res.srcDirs = ['src/haier_lsapDebug/res']"))

        assertTrue(hq1002Block.contains("applicationId       : \"com.google.android.addyhq1002\""))
        assertTrue(hq1002Block.contains("versionCode         : 4"))
        assertTrue(hq1002Block.contains("versionName         : \"1.0.4\""))
        assertTrue(hq1002Block.contains("channel             : \"ADDY_HQ1002\""))
        assertTrue(hq1002Block.contains("cType               : \"ADDY_HQ1002\""))
        assertTrue(hq1002Block.contains("model               : \"ADDY_HQ1002\""))
        assertTrue(hq1002Block.contains("signingConfig       : signingConfigs.addyHq1002Release"))
        assertTrue(hq1002Block.contains("lsapAppKey          : \"com.dy.chhaddyhq1002\""))
        assertTrue(hq1002Block.contains("lsapTagId           : \"510000001501\""))
        assertTrue(hq1002Block.contains("lsapSdkName         : \"addy_hq1002\""))

        assertTrue(jamsBlock.contains("applicationId       : \"com.google.android.addyjams\""))
        assertTrue(jamsBlock.contains("versionCode         : 9"))
        assertTrue(jamsBlock.contains("versionName         : \"1.0.9\""))
        assertTrue(jamsBlock.contains("channel             : \"ADDY_JAMS\""))
        assertTrue(jamsBlock.contains("cType               : \"ADDY_JAMS\""))
        assertTrue(jamsBlock.contains("model               : \"ADDY_JAMS\""))
        assertTrue(jamsBlock.contains("signingConfig       : signingConfigs.addyJamsRelease"))
        assertTrue(jamsBlock.contains("lsapAppKey          : \"com.dy.chhaddyjams\""))
        assertTrue(jamsBlock.contains("lsapTagId           : \"510000001401\""))
        assertTrue(jamsBlock.contains("lsapSdkName         : \"addy_jams\""))

        assertTrue(signingConfigsBlock.contains("addyHq1002Release {"))
        assertTrue(signingConfigsBlock.contains("storeFile file(\"signing_files/addy_hq1002_release.jks\")"))
        assertTrue(signingConfigsBlock.contains("keyAlias \"addy_hq1002_release\""))
        assertTrue(signingConfigsBlock.contains("addyJamsRelease {"))
        assertTrue(signingConfigsBlock.contains("storeFile file(\"signing_files/addy_jams_release.jks\")"))
        assertTrue(signingConfigsBlock.contains("keyAlias \"addy_jams_release\""))

        assertTrue(buildGradle.contains("onVariants(selector().withBuildType(\"debug\").withFlavor(\"ad\", \"addy_hq1002\"))"))
        assertTrue(buildGradle.contains("onVariants(selector().withBuildType(\"debug\").withFlavor(\"ad\", \"addy_jams\"))"))
        assertTrue(buildGradle.contains("addy_hq1002Implementation lsapPatchedAars.addy_hq1002"))
        assertTrue(buildGradle.contains("addy_jamsImplementation lsapPatchedAars.addy_jams"))

        assertTrue(buildFlavorSource.contains("fun isAddyHq1002("))
        assertTrue(buildFlavorSource.contains("fun isAddyJams("))
        assertTrue(buildFlavorSource.contains("isAddyHq1002(flavor) ||"))
        assertTrue(buildFlavorSource.contains("isAddyJams(flavor)"))
        assertTrue(managerSource.contains("BuildConfig.UNIFIED_AD_APP_KEY"))
        assertTrue(managerSource.contains("BuildConfig.UNIFIED_AD_TAG_ID"))
        assertTrue(managerSource.contains("BuildConfig.UNIFIED_AD_SDK_NAME"))
        val debugActivitySource = readProjectFile("app/src/haier_lsapDebug/java/com/smart/android/ad_app/haier/HaierLsapDebugEntryActivity.kt")
        assertTrue(debugActivitySource.contains("BuildConfig.UNIFIED_AD_APP_KEY"))
        assertTrue(debugActivitySource.contains("BuildConfig.UNIFIED_AD_TAG_ID"))

        assertTrue(File(projectRoot, "app/signing_files/addy_hq1002_release.jks").exists())
        assertTrue(File(projectRoot, "app/signing_files/addy_jams_release.jks").exists())
        assertTrue(File(projectRoot, "app/libs/addy_hq1002/lsapsdk-combine-com.google.android.addyhq1002-1.1.12.aar").exists())
        assertTrue(File(projectRoot, "app/libs/addy_jams/lsapsdk-combine-com.google.android.addyjams-1.1.12.aar").exists())
        assertFalse(File(projectRoot, "app/libs/addy_hq1002/lsapsdk-combine-com.google.android.addyhq1002-1.1.11.aar").exists())
        assertFalse(File(projectRoot, "app/libs/addy_jams/lsapsdk-combine-com.google.android.addyjams-1.1.11.aar").exists())
        assertTrue(managerSource.contains("UnifiedAdSdk.setAdVolume(if (request.soundEnabled) 1f else 0f)"))
    }

    @Test
    fun `addy jams should use reissued r2 aar through patch task`() {
        val buildGradle = readProjectFile("app/build.gradle")
        val projectRoot = findProjectRoot("app/build.gradle")
        val originalAar = File(
            projectRoot,
            "app/libs/addy_jams/lsapsdk-combine-com.google.android.addyjams-1.1.12.aar"
        )
        val r2Aar = File(
            projectRoot,
            "app/libs/addy_jams/lsapsdk-combine-com.google.android.addyjams-1.1.12-r2.aar"
        )

        assertTrue("旧版 AAR 必须保留用于回滚", originalAar.exists())
        assertTrue("R2 AAR 必须与旧版并存", r2Aar.exists())
        assertEquals(
            "24e3651af8b1eb8e6f8313ad9225d6725cd64f24915430de07a89686bdeaee88",
            sha256(originalAar)
        )
        assertEquals(
            "b86909b03375df9048f1d6e6d54cad150f67407a280557882eab6d22289aca30",
            sha256(r2Aar)
        )
        assertTrue(
            buildGradle.contains(
                "input : \"libs/addy_jams/lsapsdk-combine-com.google.android.addyjams-1.1.12-r2.aar\""
            )
        )
        assertTrue(
            buildGradle.contains(
                "output: \"generated/lsap-patched/addy_jams/lsapsdk-combine-com.google.android.addyjams-1.1.12-r2-patched.aar\""
            )
        )
        assertTrue(
            buildGradle.contains(
                "sha256: \"b86909b03375df9048f1d6e6d54cad150f67407a280557882eab6d22289aca30\""
            )
        )
        assertTrue(buildGradle.contains("addy_jamsImplementation lsapPatchedAars.addy_jams"))
        assertFalse(buildGradle.contains("addy_jamsImplementation files("))
        assertFalse(buildGradle.contains("addy_jamsImplementation fileTree("))
    }

    private fun readProjectFile(relativePath: String): String {
        return File(findProjectRoot(relativePath), relativePath).readText()
    }

    private fun sha256(file: File): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(file.readBytes())
            .joinToString("") { "%02x".format(it) }
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
