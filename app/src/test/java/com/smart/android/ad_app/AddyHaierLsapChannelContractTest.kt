package com.smart.android.ad_app

import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

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
        assertTrue(sourceSetsBlock.contains("addy_jams {"))

        assertTrue(hq1002Block.contains("applicationId       : \"com.google.android.addyhq1002\""))
        assertTrue(hq1002Block.contains("channel             : \"ADDY_HQ1002\""))
        assertTrue(hq1002Block.contains("cType               : \"ADDY_HQ1002\""))
        assertTrue(hq1002Block.contains("model               : \"ADDY_HQ1002\""))
        assertTrue(hq1002Block.contains("signingConfig       : signingConfigs.addyHq1002Release"))
        assertTrue(hq1002Block.contains("lsapAppKey          : \"com.google.android.addyhq1002\""))
        assertTrue(hq1002Block.contains("lsapTagId           : \"ADDY_HQ1002\""))
        assertTrue(hq1002Block.contains("lsapSdkName         : \"addy_hq1002\""))

        assertTrue(jamsBlock.contains("applicationId       : \"com.google.android.addyjams\""))
        assertTrue(jamsBlock.contains("channel             : \"ADDY_JAMS\""))
        assertTrue(jamsBlock.contains("cType               : \"ADDY_JAMS\""))
        assertTrue(jamsBlock.contains("model               : \"ADDY_JAMS\""))
        assertTrue(jamsBlock.contains("signingConfig       : signingConfigs.addyJamsRelease"))
        assertTrue(jamsBlock.contains("lsapAppKey          : \"com.google.android.addyjams\""))
        assertTrue(jamsBlock.contains("lsapTagId           : \"ADDY_JAMS\""))
        assertTrue(jamsBlock.contains("lsapSdkName         : \"addy_jams\""))

        assertTrue(signingConfigsBlock.contains("addyHq1002Release {"))
        assertTrue(signingConfigsBlock.contains("storeFile file(\"signing_files/addy_hq1002_release.jks\")"))
        assertTrue(signingConfigsBlock.contains("keyAlias \"addy_hq1002_release\""))
        assertTrue(signingConfigsBlock.contains("addyJamsRelease {"))
        assertTrue(signingConfigsBlock.contains("storeFile file(\"signing_files/addy_jams_release.jks\")"))
        assertTrue(signingConfigsBlock.contains("keyAlias \"addy_jams_release\""))

        assertTrue(buildGradle.contains("onVariants(selector().withBuildType(\"debug\").withFlavor(\"ad\", \"addy_hq1002\"))"))
        assertTrue(buildGradle.contains("onVariants(selector().withBuildType(\"debug\").withFlavor(\"ad\", \"addy_jams\"))"))
        assertTrue(buildGradle.contains("addy_hq1002Implementation fileTree(dir: 'libs/addy_hq1002', include: ['*.aar'])"))
        assertTrue(buildGradle.contains("addy_jamsImplementation fileTree(dir: 'libs/addy_jams', include: ['*.aar'])"))

        assertTrue(buildFlavorSource.contains("fun isAddyHq1002("))
        assertTrue(buildFlavorSource.contains("fun isAddyJams("))
        assertTrue(buildFlavorSource.contains("isAddyHq1002(flavor) ||"))
        assertTrue(buildFlavorSource.contains("isAddyJams(flavor)"))
        assertTrue(managerSource.contains("BuildConfig.UNIFIED_AD_APP_KEY"))
        assertTrue(managerSource.contains("BuildConfig.UNIFIED_AD_TAG_ID"))
        assertTrue(managerSource.contains("BuildConfig.UNIFIED_AD_SDK_NAME"))

        assertTrue(File(projectRoot, "app/signing_files/addy_hq1002_release.jks").exists())
        assertTrue(File(projectRoot, "app/signing_files/addy_jams_release.jks").exists())
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
