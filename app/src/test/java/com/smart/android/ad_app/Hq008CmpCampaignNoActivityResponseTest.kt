package com.smart.android.ad_app

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Hq008CmpCampaignNoActivityResponseTest {

    @Test
    fun `hq008 should treat no active cmp campaign response as missing payload`() {
        val source = readProjectFile("app/src/hq008/java/com/smart/android/ad_app/Hq008CmpManager.kt")

        assertTrue(
            "campaign 响应必须只接受非空 data 或直接 campaign 对象",
            source.contains("responseJson.get(\"data\")")
        )
        assertTrue(
            "data=null / 暂无活动不应被解析为可用 campaign payload",
            source.contains("远端 CMP campaign 响应结构不符合预期")
        )
    }

    @Test
    fun `hq008 should not continue popup decision without a valid campaign seed`() {
        val source = readProjectFile("app/src/hq008/java/com/smart/android/ad_app/Hq008CmpManager.kt")

        assertTrue(
            "需要拉 campaign 但未拿到有效 seed 时，必须进入缺失种子分支",
            source.contains("val campaignSeedAvailable = refreshedSeed != null") &&
                source.contains("val missingRequiredSeed = needsDecisionFlow && !suppressDecisionFlow && !campaignSeedAvailable")
        )
        assertTrue(
            "缺少 campaign seed 时应明确上报并阻断后续 popup 决策",
            source.contains("missingRequiredSeed -> \"campaign_seed_missing\"")
        )
        assertTrue(
            "远端已存在统一记录时应直接跳过 popup，并走本地恢复",
            source.contains("remoteRecoveryEligible -> \"remote_already_decided\"")
        )
        assertTrue(
            "广告门禁补校验只有在已拿到有效 campaign seed 且远端未决策时才允许继续 popup 决策",
            source.contains("campaignSeedAvailable &&") && source.contains("!remoteRecoveryEligible")
        )
    }

    private fun readProjectFile(relativePath: String): String {
        val workingDir = File(System.getProperty("user.dir") ?: ".")
        val projectRoot = generateSequence(workingDir) { it.parentFile }
            .firstOrNull { File(it, relativePath).exists() }
            ?: error("无法定位项目根目录: ${workingDir.absolutePath}")
        return File(projectRoot, relativePath).readText()
    }
}
