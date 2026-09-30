package com.smart.android.ad_app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

class AdYtx01SxkFlavorContractTest {

    @Test
    fun `playback patch runtime switch matches only the sxk build flavor`() {
        assertEquals(
            BuildConfig.FLAVOR == "ad_ytx01_sxk",
            Hq008XhsxAarRuntimeBridge.shouldApplySxkPlaybackPatch()
        )
    }

    @Test
    fun `sxk flavor copies ytx01 runtime config with independent channel and initial version`() {
        val buildGradle = readProjectFile("app/build.gradle")
        val sourceSetsBlock = extractBlock(buildGradle, "    sourceSets {")
        val productFlavorsBlock = extractBlock(buildGradle, "    productFlavors {")
        val oldFlavorBlock = extractBlock(productFlavorsBlock, "        ad_ytx01 {")
        val sxkFlavorBlock = extractBlock(productFlavorsBlock, "        ad_ytx01_sxk {")
        val sxkSourceSetBlock = extractBlock(sourceSetsBlock, "        ad_ytx01_sxk {")

        assertTrue(oldFlavorBlock.contains("versionCode         : 2"))
        assertTrue(oldFlavorBlock.contains("versionName         : \"1.0.2\""))
        assertTrue(oldFlavorBlock.contains("channel             : \"AD_YTX01\""))

        assertTrue(sxkFlavorBlock.contains("applicationId       : \"com.google.android.adytx01\""))
        assertTrue(sxkFlavorBlock.contains("versionCode         : 1"))
        assertTrue(sxkFlavorBlock.contains("versionName         : \"1.0.1\""))
        assertTrue(sxkFlavorBlock.contains("signingConfig       : signingConfigs.ytx01Release"))
        assertTrue(sxkFlavorBlock.contains("baseUrl             : \"https://api.bcytua.cc/\""))
        assertTrue(sxkFlavorBlock.contains("channel             : \"AD_YTX01_SXK\""))
        assertTrue(sxkFlavorBlock.contains("cType               : \"AD_YTX01_SXK\""))
        assertTrue(sxkFlavorBlock.contains("model               : \"AD_YTX01_SXK\""))
        assertTrue(sxkFlavorBlock.contains("backupDomain        : \"https://api.bcytua.cc/\""))
        assertTrue(sxkFlavorBlock.contains("partner_name: \"chhkj\""))
        assertTrue(sxkFlavorBlock.contains("project_id  : \"224\""))
        assertTrue(
            sxkFlavorBlock.contains(
                "tcl_app_key : \"DeB07Nx4JEnYX/0t4Dn4o0kvuTSirRJz/gWBkDXnEQaoK4V3uqXV3ReHRrBbXgK8nJSx3CPuMpLXAM3IRFEcuQ==\""
            )
        )

        assertTrue(sxkSourceSetBlock.contains("manifest.srcFile 'src/ad_ytx01/AndroidManifest.xml'"))
        assertTrue(sxkSourceSetBlock.contains("java.srcDirs = ['src/hq008/java']"))
        assertTrue(sxkSourceSetBlock.contains("res.srcDirs = ['src/hq008/res']"))
        assertTrue(
            buildGradle.contains(
                "onVariants(selector().withBuildType(\"debug\").withFlavor(\"ad\", \"ad_ytx01_sxk\"))"
            )
        )
    }

    @Test
    fun `sxk flavor stays in hq008 family and owns all ytx01 dependencies`() {
        val buildGradle = readProjectFile("app/build.gradle")

        assertTrue(BuildFlavor.isHq008("ad_ytx01_sxk"))
        assertTrue(BuildFlavor.isHq008Family("ad_ytx01_sxk"))
        listOf(
            "ad_ytx01_sxkImplementation 'com.google.guava:guava:31.1-android'",
            "ad_ytx01_sxkImplementation 'androidx.appcompat:appcompat:1.7.1'",
            "ad_ytx01_sxkImplementation 'androidx.leanback:leanback:1.0.0'",
            "ad_ytx01_sxkImplementation 'androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7'",
            "ad_ytx01_sxkImplementation 'com.github.bumptech.glide:glide:4.11.0'",
            "ad_ytx01_sxkImplementation 'com.iabtcf:iabtcf-encoder:2.0.10'",
            "ad_ytx01_sxkImplementation('com.thoughtworks.xstream:xstream:1.4.18')",
            "ad_ytx01_sxkImplementation patchedAar"
        ).forEach { dependency ->
            assertTrue("圣鑫科渠道缺少依赖: $dependency", buildGradle.contains(dependency))
        }

        assertTrue(HaierUserAgentInstaller.supportsFlavor("ad_ytx01_sxk"))
        assertTrue(Hq008XhsxAarRuntimeBridge.isSxkPlaybackPatchFlavor("ad_ytx01_sxk"))
        assertFalse(Hq008XhsxAarRuntimeBridge.isSxkPlaybackPatchFlavor("ad_ytx01"))
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
