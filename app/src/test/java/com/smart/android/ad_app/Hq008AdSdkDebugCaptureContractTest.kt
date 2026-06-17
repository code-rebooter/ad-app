package com.smart.android.ad_app

import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

class Hq008AdSdkDebugCaptureContractTest {

    @Test
    fun `hq008 ad sdk should enable release diagnostics through dedicated helper`() {
        val adManagerSource = readProjectFile("app/src/hq008/java/com/smart/android/ad_app/AdManagerImpl.kt")

        assertTrue(
            "广告 SDK 日志开关应交给专用 helper，而不是直接绑死 BuildConfig.DEBUG",
            adManagerSource.contains("Ad.get().setEnableLog(Hq008AdSdkDebugCapture.isSdkVerboseLogEnabled())")
        )
        assertTrue(
            "广告请求开始前应安装广告 SDK 原始请求抓取器",
            adManagerSource.contains("Hq008AdSdkDebugCapture.ensureInstalled()")
        )
    }

    @Test
    fun `hq008 ad sdk raw request capture should be wired into consent trace logs`() {
        val helperSource = readProjectFile("app/src/hq008/java/com/smart/android/ad_app/Hq008AdSdkDebugCapture.kt")

        assertTrue("应通过 HttpRequester 单例注入广告 SDK 抓取拦截器", helperSource.contains("HttpRequester.get()"))
        assertTrue("注入前应先补齐广告 SDK GlobalContext", helperSource.contains("GlobalContext.setAppContext(appContext)"))
        assertTrue("应通过 OkHttpClient 反射替换底层客户端", helperSource.contains("OkHttpClient::class.java.isAssignableFrom"))
        assertTrue("应在拦截器里抓取 postJsonSync 的原始请求体", helperSource.contains("requestBody.writeTo(buffer)"))
        assertTrue("应在匹配到广告请求后输出中文事件 AD_SDK_HTTP_CAPTURE", helperSource.contains("eventType = \"AD_SDK_HTTP_CAPTURE\""))
        assertTrue("应把 gdprConsent 是否存在与长度写入 adLog", helperSource.contains("put(\"gdprConsentPresent\""))
        assertTrue("应记录广告 SDK 原始请求地址", helperSource.contains("put(\"requestUrl\", requestUrl)"))
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
