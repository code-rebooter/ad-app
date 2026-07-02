package com.smart.android.ad_app

import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

class AdChannelResolverContractTest {

    @Test
    fun `channel resolver should prefer system property and fallback to build config`() {
        val resolverSource = readProjectFile("app/src/main/java/com/smart/android/ad_app/AdChannelResolver.kt")
        val appSource = readProjectFile("app/src/main/java/com/smart/android/ad_app/APP.kt")
        val adConfigManagerSource = readProjectFile("app/src/main/java/com/smart/android/ad_app/AdConfigManager.kt")

        assertTrue(resolverSource.contains("persist.vendor.ad.channel"))
        assertTrue(resolverSource.contains("fun currentChannel(): String"))
        assertTrue(resolverSource.contains("fun currentChannelSource(): String"))
        assertTrue(resolverSource.contains("BuildConfig.CHANNEL"))
        assertTrue(resolverSource.contains("readSystemPropertyReflective"))
        assertTrue(resolverSource.contains("readSystemPropertyViaGetprop"))
        assertTrue(resolverSource.contains("value?.trim()?.takeIf { it.isNotEmpty() }"))

        assertTrue(appSource.contains("AdChannelResolver.resolve()"))
        assertTrue(adConfigManagerSource.contains("val channel = AdChannelResolver.resolve()"))
        assertTrue(adConfigManagerSource.contains("channelSource="))
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
