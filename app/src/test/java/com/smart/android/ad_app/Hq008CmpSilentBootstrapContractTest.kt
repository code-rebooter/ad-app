package com.smart.android.ad_app

import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

class Hq008CmpSilentBootstrapContractTest {

    @Test
    fun `hq008 silent bootstrap should generate tc string dynamically and dedupe user action upload`() {
        val source = readProjectFile("app/src/hq008/java/com/smart/android/ad_app/Hq008CmpManager.kt")

        assertTrue("不应再保留固定 REAL_TC_STRING 兜底", !source.contains("REAL_TC_STRING"))
        assertTrue("应按 SDK 规则动态生成 TC String", source.contains("ConsentUtils.a.a("))
        assertTrue("应从本地 consent 中读取 campaign_bean_string", source.contains("campaign_bean_string"))
        assertTrue("缺少本地种子时应走远端 campaign fallback", source.contains("fetchSilentConsentSeedFromRemote"))
        assertTrue("应优先尝试反射触发 SDK 原生 AcceptAll", source.contains("tryReflectiveAcceptAllIfPossible"))
        assertTrue("应反射使用 SDK 原生 CMPIntent.AcceptAll", source.contains("com.tcl.ff.component.oversea.b.a\$a"))
        assertTrue("应反射使用 SDK 原生 CMPViewModel", source.contains("com.tcl.ff.component.oversea.e.a"))
        assertTrue("应实现静默 user/action 去重", source.contains("KEY_LAST_SILENT_USER_ACTION_HASH"))
        assertTrue("应使用 SDK 的 user action 请求模型", source.contains("CmpUserActionRequestParams"))
        assertTrue("应有独立的静默 user/action 上报方法", source.contains("uploadSilentUserActionIfNeeded"))
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
