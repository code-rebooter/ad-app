package com.smart.android.ad_app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

class Hq008LocalSchedulePolicyTest {

    @Test
    fun `hq008 local schedule policy should use 5s initial delay and stable randomized polling`() {
        val policyClass: Class<*>
        try {
            policyClass = Class.forName("com.smart.android.ad_app.Hq008LocalSchedulePolicy")
        } catch (_: ClassNotFoundException) {
            fail("缺少 Hq008LocalSchedulePolicy，当前无法提供 hq008 的本地随机轮询策略")
            return
        }

        val instance = policyClass.getField("INSTANCE").get(null)
        val initialDelayMethod = policyClass.getDeclaredMethod("initialDelayMs")
        val pollingSecondsMethod = policyClass.getDeclaredMethod("pollingSeconds")

        val initialDelay = initialDelayMethod.invoke(instance) as Long
        assertEquals(5_000L, initialDelay)

        val firstPolling = pollingSecondsMethod.invoke(instance) as Long
        assertTrue("hq008 默认轮询应落在 900..1200 秒之间，实际为 $firstPolling", firstPolling in 900L..1200L)

        val secondPolling = pollingSecondsMethod.invoke(instance) as Long
        assertEquals("同一进程内随机轮询值应保持稳定", firstPolling, secondPolling)
    }

    @Test
    fun `hq008 should ignore server next_request_seconds and use local schedule policy`() {
        val adConfigManagerSource = readProjectFile("app/src/main/java/com/smart/android/ad_app/AdConfigManager.kt")
        val scheduleManagerSource = readProjectFile("app/src/main/java/com/smart/android/ad_app/ScheduleManagerImpl.kt")

        assertFalse(
            "hq008 不应继续直接消费服务端 next_request_seconds",
            adConfigManagerSource.contains("HandlerAdTaskScheduler.startOrUpdateTask(dto.next_request_seconds)")
        )
        assertTrue(
            "ScheduleManagerImpl 应该对 hq008 使用本地策略",
            scheduleManagerSource.contains("Hq008LocalSchedulePolicy")
        )
        assertTrue(
            "ScheduleManagerImpl 应该对 hq008 做单独分支",
            scheduleManagerSource.contains("BuildConfig.FLAVOR == \"hq008\"")
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
