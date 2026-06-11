package com.smart.android.ad_app

import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

class Hq008DynamicPollingContractTest {

    @Test
    fun `hq008 dynamic polling should persist server interval by channel and apply it to scheduler`() {
        val adConfigManagerSource = readProjectFile("app/src/main/java/com/smart/android/ad_app/AdConfigManager.kt")
        val policySource = readProjectFile("app/src/main/java/com/smart/android/ad_app/Hq008LocalSchedulePolicy.kt")
        val scheduleManagerSource = readProjectFile("app/src/main/java/com/smart/android/ad_app/ScheduleManagerImpl.kt")
        val flowGuardSource = readProjectFile("app/src/main/java/com/smart/android/ad_app/Hq008FloatingFlowGuard.kt")
        val rendererSource = readProjectFile("app/src/main/java/com/smart/android/ad_app/AdRenderer.kt")
        val floatingWindowSource = readProjectFile("app/src/main/java/com/smart/android/ad_app/TvAdFloatingWindow.kt")

        assertTrue(policySource.contains("fun updateServerPollingSeconds"))
        assertTrue(policySource.contains("fun clearServerPollingSeconds"))
        assertTrue(policySource.contains("fun resolveEffectivePollingSeconds"))
        assertTrue(policySource.contains("KEY_SERVER_POLLING_SECONDS_PREFIX"))
        assertTrue(policySource.contains("MIN_SERVER_POLLING_SECONDS = 10L"))
        assertTrue(policySource.contains("BuildConfig.CHANNEL") || scheduleManagerSource.contains("BuildConfig.CHANNEL"))

        assertTrue(adConfigManagerSource.contains("val nextPollingSeconds"))
        assertTrue(adConfigManagerSource.contains("Hq008LocalSchedulePolicy.updateServerPollingSeconds(BuildConfig.CHANNEL"))
        assertTrue(adConfigManagerSource.contains("HandlerAdTaskScheduler.startOrUpdateTask(nextPollingSeconds)"))

        assertTrue(flowGuardSource.contains("fun tryEnter"))
        assertTrue(flowGuardSource.contains("fun finish"))
        assertTrue(adConfigManagerSource.contains("Hq008FloatingFlowGuard.tryEnter(BuildConfig.CHANNEL)"))
        assertTrue(adConfigManagerSource.contains("requestHq008Authorize(flowToken)"))
        assertTrue(adConfigManagerSource.contains("Hq008FloatingFlowGuard.finish(flowToken"))
        assertTrue(rendererSource.contains("onFloatingFlowFinished"))
        assertTrue(floatingWindowSource.contains("dispatchFlowFinishedOnce"))
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
