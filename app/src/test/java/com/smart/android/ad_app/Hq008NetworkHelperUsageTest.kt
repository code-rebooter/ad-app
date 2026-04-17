package com.smart.android.ad_app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

class Hq008NetworkHelperUsageTest {

    @Test
    fun `hq008 authorize client should use NetworkHelper instead of raw OkHttp`() {
        val source = readProjectFile("app/src/hq008/java/com/smart/android/ad_app/Hq008SdkAuthorizeClient.kt")

        assertTrue(
            "Hq008SdkAuthorizeClient 应该使用 NetworkHelper.makeRequest",
            source.contains("NetworkHelper.makeRequest")
        )
        assertFalse(
            "Hq008SdkAuthorizeClient 不应继续直接构建 OkHttpClient",
            source.contains("OkHttpClient.Builder()")
        )
        assertFalse(
            "Hq008SdkAuthorizeClient 不应继续直接 newCall/enqueue",
            source.contains(".newCall(request).enqueue")
        )
    }

    @Test
    fun `hq008 report client should use NetworkHelper instead of raw OkHttp`() {
        val source = readProjectFile("app/src/hq008/java/com/smart/android/ad_app/Hq008AdReporter.kt")

        assertTrue(
            "Hq008AdReporter 应该使用 NetworkHelper.makeRequest",
            source.contains("NetworkHelper.makeRequest")
        )
        assertFalse(
            "Hq008AdReporter 不应继续直接构建 OkHttpClient",
            source.contains("OkHttpClient.Builder()")
        )
        assertFalse(
            "Hq008AdReporter 不应继续直接 newCall/enqueue",
            source.contains(".newCall(request).enqueue")
        )
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
