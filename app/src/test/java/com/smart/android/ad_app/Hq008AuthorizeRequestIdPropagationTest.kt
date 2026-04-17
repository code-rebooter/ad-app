package com.smart.android.ad_app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

class Hq008AuthorizeRequestIdPropagationTest {

    @Test
    fun `authorize request_id should be reused as report request_id when present`() {
        val resolverClass: Class<*>
        try {
            resolverClass = Class.forName("com.smart.android.ad_app.Hq008ReportRequestIdResolver")
        } catch (_: ClassNotFoundException) {
            fail("缺少 Hq008ReportRequestIdResolver，当前无法保证 authorize request_id 透传到 report request_id")
            return
        }

        val resolver = resolverClass.getField("INSTANCE").get(null)
        val resolveMethod = resolverClass.getDeclaredMethod("resolve", String::class.java)

        val reused = resolveMethod.invoke(resolver, "server-request-id-001") as String
        assertEquals("server-request-id-001", reused)

        val fallback = resolveMethod.invoke(resolver, "   ") as String
        assertFalse("空白 request_id 必须回退到本地生成值", fallback.isBlank())
        assertFalse("空白 request_id 不应原样透传", fallback == "   ")
    }

    @Test
    fun `hq008 ad manager should not generate a fresh report request_id when authorize request_id exists`() {
        val workingDir = File(System.getProperty("user.dir") ?: ".")
        val projectRootCandidate = generateSequence(workingDir) { it.parentFile }
            .firstOrNull {
                File(it, "app/src/hq008/java/com/smart/android/ad_app/AdManagerImpl.kt").exists()
            }
        if (projectRootCandidate == null) {
            fail("无法定位项目根目录: ${workingDir.absolutePath}")
            return
        }
        val projectRoot = projectRootCandidate
        val adManagerFile = File(
            projectRoot,
            "app/src/hq008/java/com/smart/android/ad_app/AdManagerImpl.kt"
        )
        val source = adManagerFile.readText()

        assertTrue("AdManagerImpl 应该通过解析器决定 report request_id", source.contains("Hq008ReportRequestIdResolver.resolve(adId)"))
        assertFalse("AdManagerImpl 不应直接新生成 report request_id", source.contains("val requestId = Hq008AdReporter.newRequestId()"))
    }
}
