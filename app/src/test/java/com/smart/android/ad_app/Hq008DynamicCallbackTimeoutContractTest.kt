package com.smart.android.ad_app

import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

class Hq008DynamicCallbackTimeoutContractTest {

    @Test
    fun `hq008 should carry authorize timeout into the pending playback request`() {
        val dtoSource = readProjectFile("app/src/main/java/com/smart/android/ad_app/bean/AdConfigDto.kt")
        val rendererSource = readProjectFile("app/src/main/java/com/smart/android/ad_app/AdRenderer.kt")
        val windowSource = readProjectFile("app/src/main/java/com/smart/android/ad_app/TvAdFloatingWindow.kt")
        val managerSource = readProjectFile("app/src/hq008/java/com/smart/android/ad_app/AdManagerImpl.kt")

        assertTrue(dtoSource.contains("val callbackTimeoutMs: Long? = null"))
        assertTrue(rendererSource.contains("callbackTimeoutMs = dto.callbackTimeoutMs"))
        assertTrue(windowSource.contains("callbackTimeoutMs = callbackTimeoutMs"))
        assertTrue(managerSource.contains("callbackTimeoutMs = callbackTimeoutMs"))
        assertTrue(managerSource.contains("val callbackTimeoutMs: Long"))
        assertTrue(managerSource.contains("\"callbackTimeoutMs\" to effectiveCallbackTimeoutMs"))
        assertTrue(managerSource.contains("mainHandler.postDelayed(currentTimeoutRunnable!!, request.callbackTimeoutMs)"))
    }

    @Test
    fun `all hq008 playback channels should schedule the per-request callback timeout`() {
        val channelSources = listOf(
            readProjectFile("app/src/google_ad_tv_desktop/java/com/smart/android/ad_app/GoogleAdTvDesktopAdManager.kt"),
            readProjectFile("app/src/haier_lsap/java/com/smart/android/ad_app/HaierLsapAdManager.kt")
        )

        channelSources.forEach { source ->
            assertTrue(source.contains("val effectiveCallbackTimeoutMs = callbackTimeoutMs ?: AdPlaybackPolicy.CALLBACK_TIMEOUT_MS"))
            assertTrue(source.contains("callbackTimeoutMs = effectiveCallbackTimeoutMs"))
            assertTrue(source.contains("val callbackTimeoutMs: Long"))
            assertTrue(source.contains("mainHandler.postDelayed(timeoutRunnable!!, request.callbackTimeoutMs)"))
            assertTrue(!source.contains("private const val REQUEST_TIMEOUT_MS"))
        }
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
