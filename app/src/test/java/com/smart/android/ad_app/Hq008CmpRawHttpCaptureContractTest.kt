package com.smart.android.ad_app

import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

class Hq008CmpRawHttpCaptureContractTest {

    @Test
    fun `hq008 cmp sdk flow should capture raw http request and response`() {
        val source = readProjectFile("app/src/hq008/java/com/smart/android/ad_app/Hq008CmpManager.kt")

        assertTrue("应通过 HttpRequester 单例注入拦截器", source.contains("HttpRequester.get()"))
        assertTrue("应通过 OkHttpClient 类型反射定位客户端字段", source.contains("OkHttpClient::class.java.isAssignableFrom"))
        assertTrue("应在拦截器中抓取原始响应 body", source.contains("response.peekBody(MAX_CMP_HTTP_CAPTURE_BYTES).string()"))
        assertTrue("应在发起 getCampaign 前注册 HTTP 抓取槽位", source.contains("captureSlotForLog = registerCmpHttpCapture("))
        assertTrue("应在 SDK 请求结束后等待原始 HTTP 抓取结果", source.contains("awaitCmpHttpCapture(captureSlotForLog)"))
        assertTrue("campaign 抑制判断应优先读取原始 HTTP 响应", source.contains("responseBody?.let(::resolveCampaignSuppressionReason)"))
        assertTrue("结果日志应优先使用抓到的原始 HTTP 响应", source.contains("capture?.responseBody?.takeIf { it.isNotBlank() }"))
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
