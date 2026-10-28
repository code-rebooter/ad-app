package com.smart.android.ad_app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

class HaierUserAgentInstallerTest {

    @Test
    fun `installer supports all patched channel types`() {
        listOf(
            BuildFlavor.CHANNEL_TYPE_TCL,
            BuildFlavor.CHANNEL_TYPE_LSAP,
            BuildFlavor.CHANNEL_TYPE_GOOGLE
        ).forEach { channelType ->
            assertTrue(HaierUserAgentInstaller.supportsChannelType(channelType))
        }
        assertFalse(HaierUserAgentInstaller.supportsChannelType("OTHER"))
    }

    @Test
    fun `installer replaces abnormal process user agent for supported flavor`() {
        withRestoredHttpAgent {
            System.setProperty(
                "http.agent",
                "Dalvik/2.1.0 (Linux; U; Android 11.1; TV BOX Build/QP1A.191105.004)"
            )

            val result = HaierUserAgentInstaller.installForProcess(
                channelType = BuildFlavor.CHANNEL_TYPE_LSAP,
                sdkInt = 30,
                identity = HaierBuildIdentityNormalizer.normalizeUserAgentIdentity(
                    "11.1", "TV BOX", "QP1A.191105.004", 30
                ),
                logger = {}
            )

            assertNotNull(result)
            assertTrue(result!!.changed)
            assertEquals(
                "Dalvik/2.1.0 (Linux; U; Android 11; X96_NEXT Build/RP1A.200720.009)",
                System.getProperty("http.agent")
            )
            assertEquals(result, HaierUserAgentInstaller.currentResult())
        }
    }

    @Test
    fun `installer preserves normal process user agent`() {
        withRestoredHttpAgent {
            val original =
                "Dalvik/2.1.0 (Linux; U; Android 11; Sony BRAVIA Build/SONY_11_001)"
            System.setProperty("http.agent", original)

            val result = HaierUserAgentInstaller.installForProcess(
                channelType = BuildFlavor.CHANNEL_TYPE_LSAP,
                sdkInt = 30,
                identity = HaierBuildIdentityNormalizer.normalizeUserAgentIdentity(
                    "11", "Sony BRAVIA", "SONY_11_001", 30
                ),
                logger = {}
            )

            assertNotNull(result)
            assertFalse(result!!.changed)
            assertEquals(original, System.getProperty("http.agent"))
        }
    }

    @Test
    fun `installation and late property repair always use the same native identity`() {
        withRestoredHttpAgent {
            val identity = HaierBuildIdentityNormalizer.normalizeUserAgentIdentity(
                "15", "mstar", "QP1A.191105.004", 29
            )
            val expected = "Dalvik/2.1.0 (Linux; U; Android 10; X96_NEXT Build/QP1A.191105.004)"
            System.setProperty("http.agent", "Dalvik/2.1.0 (Linux; U; Android 15; mstar Build/QP1A.191105.004)")
            HaierUserAgentInstaller.installForProcess(
                BuildFlavor.CHANNEL_TYPE_GOOGLE, 29, identity, logger = {}
            )
            assertEquals(expected, System.getProperty("http.agent"))
            val stable = HaierUserAgentInstaller.ensureEffectiveForCurrentProcess(
                BuildFlavor.CHANNEL_TYPE_GOOGLE, 29, identity
            )
            assertFalse(stable.repaired)
            for (overwritten in listOf("bad", HaierUserAgentNormalizer.canonicalUserAgentFor(29)!!)) {
                System.setProperty("http.agent", overwritten)
                val repaired = HaierUserAgentInstaller.ensureEffectiveForCurrentProcess(
                    BuildFlavor.CHANNEL_TYPE_GOOGLE, 29, identity
                )
                assertTrue(repaired.repaired)
                assertEquals(expected, repaired.effectiveUa)
                assertEquals(expected, System.getProperty("http.agent"))
            }
        }
    }

    @Test
    fun `installer ignores unsupported flavor`() {
        withRestoredHttpAgent {
            val original = "bad"
            System.setProperty("http.agent", original)

            val result = HaierUserAgentInstaller.installForProcess(
                channelType = "OTHER",
                sdkInt = 30,
                logger = {}
            )

            assertNull(result)
            assertNull(HaierUserAgentInstaller.currentResult())
            assertEquals(original, System.getProperty("http.agent"))
        }
    }

    @Test
    fun `application installs user agent before base context attachment`() {
        val source = readProjectFile("app/src/main/java/com/smart/android/ad_app/APP.kt")
        val methodIndex = source.indexOf("override fun attachBaseContext(base: Context)")
        val installIndex = source.indexOf(
            "HaierUserAgentInstaller.installForCurrentProcess(BuildConfig.AD_CHANNEL_TYPE)",
            startIndex = methodIndex
        )
        val superIndex = source.indexOf("super.attachBaseContext(base)", startIndex = methodIndex)

        assertTrue("APP 必须实现 attachBaseContext", methodIndex >= 0)
        assertTrue("UA 安装调用必须存在", installIndex > methodIndex)
        assertTrue("UA 安装必须早于 super.attachBaseContext", installIndex < superIndex)
    }

    private fun withRestoredHttpAgent(block: () -> Unit) {
        val previous = System.getProperty("http.agent")
        try {
            block()
        } finally {
            if (previous == null) {
                System.clearProperty("http.agent")
            } else {
                System.setProperty("http.agent", previous)
            }
        }
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
