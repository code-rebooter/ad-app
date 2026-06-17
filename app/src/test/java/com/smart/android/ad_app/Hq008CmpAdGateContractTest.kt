package com.smart.android.ad_app

import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

class Hq008CmpAdGateContractTest {

    @Test
    fun `hq008 floating ad request should wait for cmp remote decision before authorize`() {
        val adConfigManagerSource = readProjectFile("app/src/main/java/com/smart/android/ad_app/AdConfigManager.kt")
        val cmpManagerSource = readProjectFile("app/src/hq008/java/com/smart/android/ad_app/Hq008CmpManager.kt")

        assertTrue("广告请求前应先进入 CMP 远端决策门禁", adConfigManagerSource.contains("applyRemoteCmpDecisionIfNeeded"))
        assertTrue("CMP 决策完成后才应继续授权接口", adConfigManagerSource.contains("requestHq008Authorize(flowToken)"))
        assertTrue("CMP 管理器应暴露异步完成回调，供广告轮询复用", cmpManagerSource.contains("onCompleted: (() -> Unit)? = null"))
        assertTrue("广告门禁进入时应先刷新 SDK 当前 CMP 状态", cmpManagerSource.contains("refreshSdkCmpSnapshot("))
        assertTrue("广告门禁第一阶段应先刷新 SDK 当前 CMP 状态", cmpManagerSource.contains("reason = \"ad_gate_sdk\""))
        assertTrue("广告门禁第二阶段应继续执行补校验", cmpManagerSource.contains("refreshAdGateCmpSnapshot("))
        assertTrue("广告门禁应区分 SDK 原始 needShowPop 与补校验放行状态", cmpManagerSource.contains("cmpDecisionEligible"))
        assertTrue("SDK 判断需要处理 CMP 时应按条件补拉最新 campaign seed", cmpManagerSource.contains("val remoteCampaignResult = if (shouldFetchCampaign)"))
        assertTrue("补拉 campaign seed 时应走远端 campaign 接口", cmpManagerSource.contains("fetchSilentConsentSeedFromRemote(applicationContext)"))
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
