package com.smart.android.ad_app

import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

class AdYtx01JxFlavorContractTest {

    @Test
    fun `jx flavor copies ytx01 config with independent identifiers and initial version`() {
        val buildGradle = projectFile("app/build.gradle").readText()
        val productFlavors = extractBlock(buildGradle, "    productFlavors {")
        val flavor = extractBlock(productFlavors, "        ad_ytx01_jx {")
        val sourceSets = extractBlock(buildGradle, "    sourceSets {")
        val sourceSet = extractBlock(sourceSets, "        ad_ytx01_jx {")

        assertTrue(BuildFlavor.isHq008("ad_ytx01_jx"))
        assertTrue(flavor.contains("applicationId       : \"com.google.android.adytx01\""))
        assertTrue(flavor.contains("versionCode         : 1"))
        assertTrue(flavor.contains("versionName         : \"1.0.1\""))
        assertTrue(flavor.contains("signingConfig       : signingConfigs.ytx01Release"))
        assertTrue(flavor.contains("baseUrl             : \"https://api.bcytua.cc/\""))
        assertTrue(flavor.contains("channel             : \"AD_YTX01_JX\""))
        assertTrue(flavor.contains("cType               : \"AD_YTX01_JX\""))
        assertTrue(flavor.contains("model               : \"AD_YTX01_JX\""))
        assertTrue(flavor.contains("backupDomain        : \"https://api.bcytua.cc/\""))
        assertTrue(flavor.contains("partner_name: \"chhkj\""))
        assertTrue(flavor.contains("project_id  : \"224\""))
        assertTrue(
            flavor.contains(
                "tcl_app_key : \"DeB07Nx4JEnYX/0t4Dn4o0kvuTSirRJz/gWBkDXnEQaoK4V3uqXV3ReHRrBbXgK8nJSx3CPuMpLXAM3IRFEcuQ==\""
            )
        )

        assertTrue(sourceSet.contains("manifest.srcFile 'src/ad_ytx01_jx/AndroidManifest.xml'"))
        assertTrue(sourceSet.contains("java.srcDirs = ['src/hq008/java']"))
        assertTrue(sourceSet.contains("res.srcDirs = ['src/hq008/res']"))
        assertTrue(
            buildGradle.contains(
                "onVariants(selector().withBuildType(\"debug\").withFlavor(\"ad\", \"ad_ytx01_jx\"))"
            )
        )
    }

    @Test
    fun `jx flavor owns the same tcl dependencies as ytx01`() {
        val buildGradle = projectFile("app/build.gradle").readText()
        listOf(
            "ad_ytx01_jxImplementation 'com.google.guava:guava:31.1-android'",
            "ad_ytx01_jxImplementation 'androidx.appcompat:appcompat:1.7.1'",
            "ad_ytx01_jxImplementation 'androidx.leanback:leanback:1.0.0'",
            "ad_ytx01_jxImplementation 'androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7'",
            "ad_ytx01_jxImplementation 'com.github.bumptech.glide:glide:4.11.0'",
            "ad_ytx01_jxImplementation 'com.iabtcf:iabtcf-encoder:2.0.10'",
            "ad_ytx01_jxImplementation('com.thoughtworks.xstream:xstream:1.4.18')",
            "ad_ytx01_jxImplementation patchedAar"
        ).forEach { dependency ->
            assertTrue("极鑫渠道缺少依赖: $dependency", buildGradle.contains(dependency))
        }
        assertTrue(HaierUserAgentInstaller.supportsFlavor("ad_ytx01_jx"))
    }

    private fun projectFile(relativePath: String): File {
        val workingDir = File(System.getProperty("user.dir") ?: ".")
        val projectRoot = generateSequence(workingDir) { it.parentFile }
            .firstOrNull { File(it, relativePath).exists() }
        if (projectRoot == null) {
            fail("无法定位项目根目录: ${workingDir.absolutePath}")
        }
        return File(projectRoot, relativePath)
    }

    private fun extractBlock(source: String, marker: String): String {
        val start = source.indexOf(marker)
        if (start < 0) fail("无法定位配置块: $marker")
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
