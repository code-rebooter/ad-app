package com.smart.android.ad_app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class GoogleParameterCoverageContractTest {

    @Test
    fun `google server payloads use the centralized build identity normalizer`() {
        val allowedIdentitySource =
            "app/src/main/java/com/smart/android/ad_app/HaierBuildIdentityNormalizer.kt"
        val forbiddenReads = listOf(
            "Build.MODEL",
            "Build.BRAND",
            "Build.MANUFACTURER",
            "Build.DEVICE",
            "Build.PRODUCT",
            "Build.ID",
            "Build.VERSION.RELEASE"
        )

        googleTargetSources()
            .filterNot { it.first == allowedIdentitySource }
            .forEach { (relativePath, source) ->
                forbiddenReads.forEach { rawRead ->
                    assertFalse(
                        "$relativePath must not read $rawRead outside the centralized normalizer",
                        source.contains(rawRead)
                    )
                }
            }

        listOf(
            "app/src/hq008/java/com/smart/android/ad_app/Hq008SdkAuthorizeClient.kt",
            "app/src/hq008/java/com/smart/android/ad_app/Hq008AdReporter.kt",
            "app/src/main/java/com/smart/android/ad_app/AdTouchClickReporter.kt"
        ).forEach { relativePath ->
            assertTrue(
                "$relativePath must use the centralized build identity normalizer",
                readProjectFile(relativePath).contains("HaierBuildIdentityNormalizer.")
            )
        }
    }

    @Test
    fun `application webview diagnostics and ump host use the effective user agent`() {
        val collector = readProjectFile(
            "app/src/main/java/com/smart/android/ad_app/HaierUserAgentReportCollector.kt"
        )
        assertTrue(collector.contains("Hq008XhsxAarRuntimeBridge.getEffectiveUserAgent()"))
        assertFalse(collector.contains("WebSettings.getDefaultUserAgent"))

        val umpRunner = readProjectFile(
            "app/src/google_ad_tv_desktop/java/com/smart/android/ad_app/GoogleUmpSilentConsentFormRunner.kt"
        )
        assertTrue(umpRunner.contains("Hq008XhsxAarRuntimeBridge.getEffectiveUserAgent()"))
        assertTrue(umpRunner.contains("webView.settings.userAgentString"))
    }

    @Test
    fun `remote display configuration uses the shared network entry point`() {
        val source = readProjectFile(
            "app/src/main/java/com/smart/android/ad_app/AdDisplayConfig.kt"
        )
        assertFalse(source.contains("URL(configUrl).openConnection()"))
        assertTrue(source.contains("Hq008XhsxAarRuntimeBridge.openUrlConnection(URL(configUrl))"))
    }

    private fun readProjectFile(relativePath: String): String {
        return File(projectRoot(), relativePath).readText()
    }

    private fun googleTargetSources(): Sequence<Pair<String, String>> {
        val root = projectRoot()
        return sequenceOf(
            "app/src/main/java",
            "app/src/hq008/java",
            "app/src/google_ad_tv_desktop/java"
        ).flatMap { relativeRoot ->
            File(root, relativeRoot)
                .walkTopDown()
                .filter { file -> file.isFile && file.extension in setOf("kt", "java") }
                .map { file ->
                    file.relativeTo(root).invariantSeparatorsPath to file.readText()
                }
        }
    }

    private fun projectRoot(): File {
        val workingDir = File(System.getProperty("user.dir") ?: ".")
        return generateSequence(workingDir) { it.parentFile }
            .firstOrNull { File(it, "app/src/main").exists() }
            ?: error("无法定位项目根目录: ${workingDir.absolutePath}")
    }
}
