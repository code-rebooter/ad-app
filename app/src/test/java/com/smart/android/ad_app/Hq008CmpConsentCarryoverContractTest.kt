package com.smart.android.ad_app

import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

class Hq008CmpConsentCarryoverContractTest {

    @Test
    fun `hq008 should recover and persist consent string for ad requests after local reset`() {
        val source = readProjectFile("app/src/hq008/java/com/smart/android/ad_app/Hq008CmpManager.kt")

        assertTrue("SDK 判定无需弹窗但本地 consent 缺失时，仍应继续执行广告门禁补校验", source.contains("shouldContinueAdGateRecoveryCheck("))
        assertTrue("无需弹窗且无需补校验时才应直接跳过远端决策", source.contains("if (!cmpNeedShowPop && !shouldContinueAdGateRecovery)"))
        assertTrue("更新 CMP 快照时应优先回填 seed 中已有的 tcString", source.contains("consentString?.takeIf { it.isNotBlank() } ?: seed?.currentTcString?.takeIf { it.isNotBlank() }"))
        assertTrue("本地缺少 seed 但远端已存在 tcString 时，应补拉 campaign seed 后再恢复本地状态", source.contains("localSeed ?: fetchSilentConsentSeedFromRemote(applicationContext)?.seed"))
        assertTrue("拿到远端 tcString 和恢复 seed 后，应走统一的持久化恢复逻辑", source.contains("recoverLocalConsentFromRemoteDecision("))
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
