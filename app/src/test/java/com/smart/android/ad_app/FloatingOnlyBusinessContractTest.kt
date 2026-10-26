package com.smart.android.ad_app

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class FloatingOnlyBusinessContractTest {
    private val root = generateSequence(File(System.getProperty("user.dir") ?: ".")) { it.parentFile }
        .first { File(it, "app/build.gradle").exists() }

    @Test fun `production exposes only floating ads without legacy delivery routes or app watchers`() {
        assertEquals(listOf("FLOATING"), AdType.entries.map { it.name })
        val sources = File(root, "app/src").listFiles()!!.filterNot { it.name.startsWith("test") }
            .flatMap { dir -> dir.walkTopDown().filter { it.isFile && it.extension in listOf("kt", "java", "xml") }.toList() }
        val removed = listOf("api/v2/ad/delivery", "task/report", "showSplashAd", "ForegroundAppWatcher",
            "DesktopStatusReceiver", "android.permission.GET_TASKS")
        sources.forEach { file ->
            val text = file.readText()
            removed.forEach { route -> assertFalse("${file.relativeTo(root)} still contains $route", text.contains(route)) }
        }
        val config = File(root, "app/src/main/java/com/smart/android/ad_app/AdConfigManager.kt").readText()
        assertTrue(config.contains("Hq008SdkFlowControlClient.request"))
        assertTrue(config.contains("Hq008SdkAuthorizeClient.request"))
        assertTrue(config.contains("AdRenderer.showFloatingAd"))
    }
}
