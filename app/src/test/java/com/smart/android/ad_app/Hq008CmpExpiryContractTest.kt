package com.smart.android.ad_app

import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

class Hq008CmpExpiryContractTest {

    @Test
    fun `hq008 consent expiry should use local create time plus cloud duration`() {
        val cmpSource = readProjectFile("app/src/hq008/java/com/smart/android/ad_app/Hq008CmpManager.kt")

        assertTrue("过期判断应使用本地落盘 create_time", cmpSource.contains("val createdAt = seed.tcStringCreateTime ?: return true"))
        assertTrue("过期判断应使用云端 consentCookieExpiration 对应的时长字段", cmpSource.contains("val expireDuration = seed.tcStringExpireTime ?: return true"))
        assertTrue("过期判断应基于 now - createTime > expireDuration", cmpSource.contains("System.currentTimeMillis() - createdAt > expireDuration"))
        assertTrue("静默落盘时应写入当前创建时间", cmpSource.contains("val now = System.currentTimeMillis()"))
        assertTrue("静默落盘时应持久化云端返回的过期时长", cmpSource.contains("consentFile.writeText(buildSilentConsentJson(seed, tcString, now, expireDuration))"))
        assertTrue("campaign seed 应直接承接云端 consentCookieExpiration", cmpSource.contains("tcStringExpireTime = campaign.consentCookieExpiration"))
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
