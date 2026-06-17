package com.smart.android.ad_app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Hq008ConsentTraceSessionTest {

    @Test
    fun `authorize allowed should keep full flow trace open until ad terminal arrives`() {
        val session = Hq008ConsentTraceSession()

        assertNull(
            session.record(
                nowMs = 1_000L,
                eventType = "CMP_GATE_START",
                rawEventMessage = "adType=FLOATING",
                eventMessage = "广告类型=FLOATING",
                adLog = null
            )
        )
        assertNull(
            session.record(
                nowMs = 1_100L,
                eventType = "FLOW_CONTROL_RESULT",
                rawEventMessage = "enabled=true",
                eventMessage = "总开关开启=是",
                adLog = null
            )
        )
        assertNull(
            session.record(
                nowMs = 1_200L,
                eventType = "AUTHORIZE_ALLOWED",
                rawEventMessage = "requestId=req-1,hidden=true",
                eventMessage = "请求ID=req-1，隐藏模式=是",
                adLog = null
            )
        )
        assertTrue("授权通过后整轮 trace 仍应保持激活，等待广告阶段收口", session.hasPendingSteps())

        assertNull(
            session.record(
                nowMs = 1_250L,
                eventType = "AD_PHASE_START",
                rawEventMessage = "requestId=req-1,hidden=true",
                eventMessage = "请求ID=req-1，隐藏模式=是",
                adLog = null
            )
        )
        val snapshot = session.record(
            nowMs = 1_300L,
            eventType = "AD_PHASE_ERROR",
            rawEventMessage = "requestId=req-1,stage=sdk_callback,reason=execution_error",
            eventMessage = "请求ID=req-1，阶段=SDK 回调，原因=执行动作异常",
            adLog = null
        )

        assertNotNull("广告终态到达时应一次性收口整轮日志", snapshot)
        assertEquals("AD_PHASE_ERROR", snapshot?.finalEventType)
        assertEquals(
            listOf(
                "CMP_GATE_START",
                "FLOW_CONTROL_RESULT",
                "AUTHORIZE_ALLOWED",
                "AD_PHASE_START",
                "AD_PHASE_ERROR"
            ),
            snapshot?.steps?.map { it.eventType }
        )
        assertFalse("终态收口后不应继续保留旧批次步骤", session.hasPendingSteps())
    }

    @Test
    fun `force finish should upload unfinished batch and next flow must not inherit old steps`() {
        val session = Hq008ConsentTraceSession()

        session.record(
            nowMs = 2_000L,
            eventType = "CMP_GATE_START",
            rawEventMessage = "adType=FLOATING",
            eventMessage = "广告类型=FLOATING",
            adLog = null
        )
        session.record(
            nowMs = 2_050L,
            eventType = "AUTHORIZE_ALLOWED",
            rawEventMessage = "requestId=req-old,hidden=true",
            eventMessage = "请求ID=req-old，隐藏模式=是",
            adLog = null
        )
        session.record(
            nowMs = 2_100L,
            eventType = "AD_PHASE_START",
            rawEventMessage = "requestId=req-old,hidden=true",
            eventMessage = "请求ID=req-old，隐藏模式=是",
            adLog = null
        )

        val forcedSnapshot = session.forceFinish(
            nowMs = 2_150L,
            eventType = "FLOW_GUARD_FINISH",
            rawEventMessage = "reason=floating_ad_finished",
            eventMessage = "原因=floating_ad_finished",
            adLog = null
        )
        assertNotNull("流程守卫结束时应兜底收口未完成批次", forcedSnapshot)
        assertEquals(
            listOf(
                "CMP_GATE_START",
                "AUTHORIZE_ALLOWED",
                "AD_PHASE_START",
                "FLOW_GUARD_FINISH"
            ),
            forcedSnapshot?.steps?.map { it.eventType }
        )
        assertFalse(session.hasPendingSteps())

        assertNull(
            session.record(
                nowMs = 3_000L,
                eventType = "CMP_GATE_START",
                rawEventMessage = "adType=FLOATING",
                eventMessage = "广告类型=FLOATING",
                adLog = null
            )
        )
        val nextSnapshot = session.record(
            nowMs = 3_100L,
            eventType = "CMP_GATE_STOP",
            rawEventMessage = "reason=flow_control_disabled",
            eventMessage = "原因=流控开关关闭",
            adLog = null
        )

        assertEquals(
            listOf("CMP_GATE_START", "CMP_GATE_STOP"),
            nextSnapshot?.steps?.map { it.eventType }
        )
        assertFalse(
            "新一轮 trace 绝不能带上上一轮未完整结束的广告步骤",
            nextSnapshot?.steps?.any { it.rawEventMessage.contains("req-old") } == true
        )
    }

    @Test
    fun `events before cmp gate start should not open upload trace`() {
        val session = Hq008ConsentTraceSession()

        assertNull(
            session.record(
                nowMs = 500L,
                eventType = "CMP_INIT_START",
                rawEventMessage = "initialized=false",
                eventMessage = "已初始化=否",
                adLog = null
            )
        )
        assertFalse("总开关前的初始化日志不应进入本地上报 trace", session.hasPendingSteps())
    }
}
