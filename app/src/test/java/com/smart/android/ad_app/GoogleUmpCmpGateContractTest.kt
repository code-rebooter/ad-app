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

    @Test
    fun `google ump gate should reuse pending terminal decision before requesting remote popup again`() {
        val source = readProjectFile("app/src/google_ad_tv_desktop/java/com/smart/android/ad_app/Hq008CmpManager.kt")
        val consentRequiredBlock = source
            .substringAfter("if (!result.formAvailable) {")
            .substringAfter("return@requestConsent")
            .substringBefore("requestRemoteDecision(")

        assertTrue(
            "Terminal remote decisions should be kept while local UMP consent is still missing",
            source.contains("private const val KEY_PENDING_REMOTE_ACTION")
        )
        assertTrue(
            "A pending terminal decision should be retried locally before asking consent-popup again",
            consentRequiredBlock.contains("getPendingRemoteAction(appContext)")
        )
        assertTrue(
            "Pending retries should be visible in consent diagnostics",
            consentRequiredBlock.contains("UMP_DECISION_RETRY_PENDING")
        )
        assertTrue(
            "Pending terminal action should be persisted before UMP execution",
            source.contains("persistPendingRemoteAction(appContext, reportAction)")
        )
        assertTrue(
            "Pending terminal action should be cleared once UMP action succeeds",
            source.contains("clearPendingRemoteAction(appContext)")
        )
    }

    @Test
    fun `google ump manager should not rewrite stored consent through privacy options`() {
        val source = readProjectFile("app/src/google_ad_tv_desktop/java/com/smart/android/ad_app/GoogleUmpConsentManager.kt")

        assertFalse(
            "Stored UMP consent should be returned as-is instead of reopening privacy options",
            source.contains("showSilentPrivacyOptionsForm(activity, action)")
        )
        assertFalse(
            "privacy options availability is not a trigger for silent consent rewriting",
            source.contains("shouldApplyStoredConsentViaPrivacyOptions(")
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
