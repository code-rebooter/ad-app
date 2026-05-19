package com.smart.android.ad_app

import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

class Hq008CmpCampaignPayloadContractTest {

    @Test
    fun `hq008 campaign response parsing should support wrapped and flat payload`() {
        val source = readProjectFile("app/src/hq008/java/com/smart/android/ad_app/Hq008CmpManager.kt")

        assertTrue("应存在独立的 campaign 响应提取方法", source.contains("extractCampaignPayload(responseBody: String)"))
        assertTrue("应优先兼容 data 包裹结构", source.contains("responseJson.get(\"data\")"))
        assertTrue("应兼容直接返回 campaign 对象的结构", source.contains("responseJson.looksLikeCampaignPayload()"))
        assertTrue("响应结构异常时应输出原始 body 便于排查", source.contains("远端 CMP campaign 响应结构不符合预期"))
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
