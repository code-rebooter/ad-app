package com.smart.android.ad_app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

class Hq008CmpFinalDecisionContractTest {

    @Test
    fun `hq008 cmp should follow sdk cycle state and support extended actions`() {
        val source = readProjectFile("app/src/hq008/java/com/smart/android/ad_app/Hq008CmpManager.kt")

        assertTrue("应记录 SDK 当前是否需要重新弹窗", source.contains("cmpNeedShowPop"))
        assertTrue("应记录当前 CMP 轮次 key", source.contains("cmpCycleKey"))
        assertTrue("应保留云端 consent 恢复态，支持本地状态丢失后的补回", source.contains("pendingRemoteRecovery"))
        assertTrue("本地丢失且云端已决策时，应走恢复本地而非重放动作", source.contains("recoverLocalConsentFromRemoteDecision"))
        assertFalse("不应再用错误的轮次级终态去重拦截执行链路", source.contains("shouldSkipDecisionForCurrentCycle"))
        assertTrue("应支持静默拒绝动作常量", source.contains("SILENT_REJECT_ACTION"))
        assertTrue("应支持静默保存设置动作", source.contains("SILENT_SAVE_SETTINGS_ACTION"))
        assertTrue("应支持远端 MAYBE_LATER 动作", source.contains("REMOTE_MAYBE_LATER_ACTION"))
        assertTrue("应支持远端 SKIP_ALREADY_DECIDED 动作", source.contains("REMOTE_SKIP_ALREADY_DECIDED_ACTION"))
        assertTrue("应提供统一的后台策略执行入口", source.contains("applyRemoteCmpDecisionIfNeeded"))
        assertTrue("MAYBE_LATER 应具备本地冷却状态", source.contains("KEY_MAYBE_LATER_COOLDOWN_STATE"))
        assertTrue("MAYBE_LATER 冷却应在前置门禁中拦截", source.contains("reason=maybe_later_cooldown"))
        assertTrue("MAYBE_LATER 冷却命中时应记录 trace", source.contains("CMP_MAYBE_LATER_COOLDOWN_HIT"))
        assertTrue("静默拒绝应复用 SDK Reject user/action 上报", source.contains("resolveSilentUserActionCode(state.seed.actionType)"))
        assertTrue("终态动作构建或执行前失败时，应回退到 MAYBE_LATER", source.contains("fallbackToMaybeLaterDecision("))
        assertTrue("TC String 为空时应继续执行原动作，不应强制回退 MAYBE_LATER", source.contains("continueDecisionWithoutTcString("))
        assertTrue("TC String 为空时应继续补发 user/action，保障动作执行", source.contains("eventType = \"SDK_ACTION_CONTINUE_NO_TC\""))
        assertFalse("TC String 为空时不应再走 MAYBE_LATER 回退分支", source.contains("reason = \"tc_string_empty\""))
        assertTrue("动作种子构建失败时，应回退 MAYBE_LATER", source.contains("reason = \"decision_build_failed\""))
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
