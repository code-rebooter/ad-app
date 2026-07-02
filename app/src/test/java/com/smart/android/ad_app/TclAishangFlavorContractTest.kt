package com.smart.android.ad_app

import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

class TclAishangFlavorContractTest {

    @Test
    fun `tcl_aishang should stay in hq008 family with dedicated package signing and placeholders`() {
        val buildGradle = readProjectFile("app/build.gradle")
        val productFlavorsBlock = extractBlock(buildGradle, "    productFlavors {")
        val flavorBlock = extractBlock(productFlavorsBlock, "        tcl_aishang {")
        val manifest = readProjectFile("app/src/tcl_aishang/AndroidManifest.xml")
        val buildFlavor = readProjectFile("app/src/main/java/com/smart/android/ad_app/BuildFlavor.kt")

        assertTrue(BuildFlavor.isHq008("tcl_aishang"))
        assertTrue(BuildFlavor.isHq008Family("tcl_aishang"))
        assertTrue(buildFlavor.contains("flavor == \"hq008\" || flavor == \"hq008XHSX\" || flavor == \"tcl_aishang\""))

        assertTrue(flavorBlock.contains("applicationId       : \"com.google.android.adais\"") || flavorBlock.contains("applicationId: \"com.google.android.adais\""))
        assertTrue(flavorBlock.contains("channel             : \"TCL_AISHANG\"") || flavorBlock.contains("channel               : \"TCL_AISHANG\""))
        assertTrue(flavorBlock.contains("cType               : \"TCL_AISHANG\"") || flavorBlock.contains("cType                 : \"TCL_AISHANG\""))
        assertTrue(flavorBlock.contains("model               : \"TCL_AISHANG\"") || flavorBlock.contains("model                 : \"TCL_AISHANG\""))
        assertTrue(flavorBlock.contains("signingConfig       : signingConfigs.tclAishangRelease") || flavorBlock.contains("signingConfig: signingConfigs.tclAishangRelease"))
        assertTrue(buildGradle.contains("storeFile file(\"signing_files/tcl_aishang_release.jks\")"))
        assertTrue(buildGradle.contains("keyAlias \"tcl_aishang_release\""))
        assertTrue(flavorBlock.contains("tcl_app_key : \"DeB07Nx4JEnYX/0t4Dn4o4+Ecwg3JX5EWmSKpnM980Rn13sY2Vs2lgrh7IMWn9E/ZwjmKylW/ecd4j+ig1h1bA==\""))
        assertTrue(flavorBlock.contains("project_id  : \"212\""))
        assertTrue(buildGradle.contains("onVariants(selector().withBuildType(\"debug\").withFlavor(\"ad\", \"tcl_aishang\"))"))
        assertTrue(buildGradle.contains("tcl_aishangImplementation fileTree(dir: tclDemoLibsDir"))
        assertTrue(buildGradle.contains("tcl_aishangImplementation 'com.google.guava:guava:31.1-android'"))
        assertTrue(buildGradle.contains("tcl_aishangImplementation 'androidx.appcompat:appcompat:1.7.1'"))
        assertTrue(buildGradle.contains("tcl_aishangImplementation 'androidx.leanback:leanback:1.0.0'"))
        assertTrue(buildGradle.contains("tcl_aishangImplementation 'androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7'"))
        assertTrue(buildGradle.contains("tcl_aishangImplementation 'com.github.bumptech.glide:glide:4.11.0'"))
        assertTrue(buildGradle.contains("tcl_aishangImplementation 'com.iabtcf:iabtcf-encoder:2.0.10'"))
        assertTrue(buildGradle.contains("tcl_aishangImplementation('com.thoughtworks.xstream:xstream:1.4.18')"))

        assertTrue(manifest.contains("tools:remove=\"android:sharedUserId\""))
        assertTrue(manifest.contains("android:name=\"tcl_app_key\""))
        assertTrue(manifest.contains("android:value=\"\${tcl_app_key}\""))
        assertTrue(manifest.contains("android:name=\"partner_name\""))
        assertTrue(manifest.contains("android:value=\"\${partner_name}\""))
        assertTrue(manifest.contains("android:name=\"project_id\""))
        assertTrue(manifest.contains("android:value=\"\${project_id}\""))

        val workingDir = File(System.getProperty("user.dir") ?: ".")
        val projectRoot = generateSequence(workingDir) { it.parentFile }
            .firstOrNull { File(it, "app/build.gradle").exists() }
        if (projectRoot == null) {
            fail("无法定位项目根目录: ${workingDir.absolutePath}")
        }
        assertTrue(File(projectRoot, "app/signing_files/tcl_aishang_release.jks").exists())
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
