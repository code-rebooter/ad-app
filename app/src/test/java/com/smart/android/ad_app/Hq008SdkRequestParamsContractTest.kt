package com.smart.android.ad_app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

class Hq008SdkRequestParamsContractTest {

    @Test
    fun `hq008 sdk request params should use production neutral metadata`() {
        val source = readProjectFile("app/src/hq008/java/com/smart/android/ad_app/AdManagerImpl.kt")

        assertFalse("正式广告 SDK 请求参数不应继续使用 demo appCat", source.contains(".setAppCat(\"demo\")"))
        assertFalse("正式广告 SDK 请求参数不应继续使用 demo channelName", source.contains(".setChannelName(\"sdk-demo\")"))
        assertFalse("正式广告 SDK 请求参数不应继续使用 demo contentTitle", source.contains(".setContentTitle(\"TCL SDK demo\")"))

        assertTrue("appCat 应收口为通用正式常量", source.contains("private const val SDK_APP_CATEGORY = \"app\""))
        assertTrue("contentTitle 应收口为通用正式常量", source.contains("private const val SDK_CONTENT_TITLE = \"App Content\""))
        assertTrue("channelName 应使用当前解析后的渠道", source.contains(".setChannelName(channelName)"))
    }

    @Test
    fun `hq008 sdk request params should not default area when region is unavailable`() {
        val source = readProjectFile("app/src/hq008/java/com/smart/android/ad_app/AdManagerImpl.kt")

        assertFalse("area 不应硬编码为 DE", source.contains(".setArea(\"DE\")"))
        assertFalse("area 不应再保留默认兜底常量", source.contains("SDK_AREA_FALLBACK"))
        assertTrue("area 应从设备区域解析", source.contains("private fun resolveSdkArea(): String?"))
        assertTrue("只有区域存在时才应传给 SDK", source.contains("resolveSdkArea()?.let { area ->"))
        assertTrue("解析到区域后才调用 setArea", source.contains("builder.setArea(area)"))
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
