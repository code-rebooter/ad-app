package com.smart.android.ad_app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

class GoogleUmpCmpGateContractTest {

    @Test
    fun `google ump gate should not request remote consent popup after UMP already allows ads`() {
        val source = readProjectFile("app/src/google_ad_tv_desktop/java/com/smart/android/ad_app/Hq008CmpManager.kt")
        val authorizedBlock = source
            .substringAfter("if (result.canRequestAds) {")
            .substringBefore("return@requestConsent")

        assertTrue(
            "Authorized UMP state should be reported as a local skip instead of entering remote popup flow",
            authorizedBlock.contains("UMP_GATE_SKIP_REMOTE")
        )
        assertFalse(
            "Authorized UMP state must not request consent-popup only because privacy options are required",
            authorizedBlock.contains("requestRemoteDecision(")
        )
        assertFalse(
            "privacy options availability is not a consent-popup eligibility signal",
            source.contains("reason = \"privacy_options_required\"")
        )
    }

    private fun readProjectFile(relativePath: String): String {
        val workingDir = File(System.getProperty("user.dir") ?: ".")
        val projectRoot = generateSequence(workingDir) { it.parentFile }
            .firstOrNull { File(it, relativePath).exists() }
        if (projectRoot == null) {
            fail("Unable to locate project root: ${workingDir.absolutePath}")
        }
        return File(projectRoot, relativePath).readText()
    }
}
