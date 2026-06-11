package com.smart.android.ad_app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

class Hq008LocalSchedulePolicyTest {

    @Test
    fun `hq008 local schedule policy should use 30s initial delay and 20to25min randomized polling`() {
        val initialDelay = Hq008LocalSchedulePolicy.initialDelayMs()
        assertEquals(30_000L, initialDelay)

        val firstPolling = Hq008LocalSchedulePolicy.pollingSeconds()
        assertTrue("hq008 默认轮询应落在 1200..1500 秒之间，实际为 $firstPolling", firstPolling in 1_200L..1_500L)

        val secondPolling = Hq008LocalSchedulePolicy.pollingSeconds()
        assertEquals("同一进程内随机轮询值应保持稳定", firstPolling, secondPolling)

        assertEquals(
            "首次启动应保持默认 30 秒冷启动延迟",
            30_000L,
            Hq008LocalSchedulePolicy.resolveInitialDelayMs(
                lastPollAtMs = null,
                nowMs = 1_000_000L,
                pollingSeconds = 1_200L
            )
        )
        assertEquals(
            "上次轮询距今不足最小间隔时，应等待剩余时间后再触发",
            1_080_000L,
            Hq008LocalSchedulePolicy.resolveInitialDelayMs(
                lastPollAtMs = 880_000L,
                nowMs = 1_000_000L,
                pollingSeconds = 1_200L
            )
        )
        assertEquals(
            "如果已超过本地轮询间隔，重启后仍保留默认 30 秒冷启动等待",
            30_000L,
            Hq008LocalSchedulePolicy.resolveInitialDelayMs(
                lastPollAtMs = 90_000L,
                nowMs = 1_500_000L,
                pollingSeconds = 1_200L
            )
        )
    }

    @Test
    fun `hq008 should apply server next_request_seconds through local schedule policy`() {
        val adConfigManagerSource = readProjectFile("app/src/main/java/com/smart/android/ad_app/AdConfigManager.kt")
        val scheduleManagerSource = readProjectFile("app/src/main/java/com/smart/android/ad_app/ScheduleManagerImpl.kt")
        val schedulerSource = readProjectFile("app/src/main/java/com/smart/android/ad_app/HandlerAdTaskScheduler.kt")
        val runtimeCoordinatorSource = readProjectFile("app/src/main/java/com/smart/android/ad_app/AdRuntimeCoordinator.kt")
        val policySource = readProjectFile("app/src/main/java/com/smart/android/ad_app/Hq008LocalSchedulePolicy.kt")

        assertTrue(
            "hq008 authorize 回调应接入服务端 next_request_seconds",
            adConfigManagerSource.contains("dto.next_request_seconds")
        )
        assertTrue(
            "hq008 authorize 回调应把 next_request_seconds 写入本地调度策略",
            adConfigManagerSource.contains("Hq008LocalSchedulePolicy.updateServerPollingSeconds")
        )
        assertTrue(
            "hq008 authorize 回调应动态更新当前 scheduler 间隔",
            adConfigManagerSource.contains("HandlerAdTaskScheduler.startOrUpdateTask")
        )
        assertTrue(
            "ScheduleManagerImpl 应该继续通过 Hq008LocalSchedulePolicy 读取 hq008 轮询间隔",
            scheduleManagerSource.contains("Hq008LocalSchedulePolicy.pollingSeconds()")
        )
        assertTrue(
            "ScheduleManagerImpl 应该对 hq008 family 做单独分支",
            scheduleManagerSource.contains("BuildFlavor.isHq008Family()")
        )
        assertTrue(
            "hq008 本地策略应持久化最近一次 floating 轮询时间",
            policySource.contains("last_floating_poll_at_ms")
        )
        assertTrue(
            "hq008 本地策略应具备服务端轮询时间持久化能力",
            policySource.contains("server_polling_seconds")
        )
        assertTrue(
            "AdRuntimeCoordinator 应在启动时初始化 hq008 本地调度策略",
            runtimeCoordinatorSource.contains("Hq008LocalSchedulePolicy.initialize(appContext)")
        )
        assertTrue(
            "HandlerAdTaskScheduler 应在真正触发 floating 拉取前记录本地时间戳",
            schedulerSource.contains("Hq008LocalSchedulePolicy.markFloatingPollTriggered()")
        )
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
