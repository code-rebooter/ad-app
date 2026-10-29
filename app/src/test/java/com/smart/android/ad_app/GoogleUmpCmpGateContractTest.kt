package com.smart.android.ad_app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

class GoogleUmpCmpGateContractTest {

    @Test
    fun `google ump gate should only skip remote popup when the stored state is terminal`() {
        val source = readProjectFile("app/src/google_ad_tv_desktop/java/com/smart/android/ad_app/Hq008CmpManager.kt")

        assertTrue(
            "A previously applied accepted action should skip the remote popup",
            source.contains("if (result.canRequestAds && isAcceptedAction(lastAppliedAction))")
        )
        assertTrue(
            "A state without required privacy options should skip the remote popup",
            source.contains("if (result.canRequestAds && result.privacyOptionsStatus != \"REQUIRED\")")
        )
        assertTrue(
            "Required privacy options should still enter the remote decision flow",
            source.contains("reason = if (result.canRequestAds) \"privacy_options_required\" else \"consent_required\"")
        )
    }

    @Test
    fun `google ump gate should reuse pending terminal decision before requesting remote popup again`() {
        val source = readProjectFile("app/src/google_ad_tv_desktop/java/com/smart/android/ad_app/Hq008CmpManager.kt")
        val pendingRead = source.indexOf("val pendingAction = getPendingRemoteAction(appContext)")
        val remoteRequest = source.indexOf("requestRemoteDecision(")

        assertTrue(
            "Terminal remote decisions should be kept while local UMP consent is still missing",
            source.contains("private const val KEY_PENDING_REMOTE_ACTION")
        )
        assertTrue(
            "A pending terminal decision should be retried locally before asking consent-popup again",
            pendingRead >= 0 && pendingRead < remoteRequest
        )
        assertTrue(
            "Pending retries should be visible in consent diagnostics",
            source.contains("UMP_DECISION_RETRY_PENDING")
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
    fun `google ump manager should apply explicit terminal decisions through privacy options`() {
        val source = readProjectFile("app/src/google_ad_tv_desktop/java/com/smart/android/ad_app/GoogleUmpConsentManager.kt")

        assertTrue(
            "Explicit accepted or rejected actions should be applied to an existing consent state",
            source.contains("showSilentPrivacyOptionsForm(activity, action)")
        )
        assertTrue(
            "Privacy options availability should gate stored-consent rewriting",
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
