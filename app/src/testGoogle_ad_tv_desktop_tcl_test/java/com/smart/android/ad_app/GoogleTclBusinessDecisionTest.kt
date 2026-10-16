package com.smart.android.ad_app

import org.junit.Assert.*
import org.junit.Test

class GoogleTclBusinessDecisionTest {
    @Test fun requiredSdkActionsCannotPassOrReportSuccessWithoutUmp() {
        listOf("ACCEPT_ALL", "SAVE_SETTINGS", "REJECT").forEach { action ->
            val result = GoogleTclBusinessDecision.evaluate(action)
            assertFalse(action, result.canContinue)
            assertNull(action, result.reportAction)
            assertEquals("ump_unavailable", result.reason)
        }
    }
    @Test fun alreadyDecidedCannotFabricateAnUnavailableConsentState() {
        val result = GoogleTclBusinessDecision.evaluate("SKIP_ALREADY_DECIDED")
        assertFalse(result.canContinue)
        assertNull(result.reportAction)
        assertEquals("consent_state_unavailable", result.reason)
    }
    @Test fun existingServerDeferActionKeepsItsReportAndContinuation() {
        val result = GoogleTclBusinessDecision.evaluate("MAYBE_LATER")
        assertTrue(result.canContinue)
        assertEquals("MAYBE_LATER", result.reportAction)
    }
    @Test fun missingAndUnknownDecisionsCannotPassTheBusinessGate() {
        listOf(null, "", "NEW_UNKNOWN_ACTION").forEach { action ->
            val result = GoogleTclBusinessDecision.evaluate(action)
            assertFalse(result.canContinue)
            assertNull(result.reportAction)
        }
    }
    @Test fun onlyExperimentalFlavorUsesTheNewHostFamilyRoute() {
        assertTrue(HaierUserAgentInstaller.supportsFlavor("google_ad_tv_desktop_tcl_test"))
        assertEquals("GOOGLE_AD_TV_DESKTOP_V260904_1", BuildConfig.CHANNEL)
        assertEquals("GOOGLE_AD_TV_DESKTOP_V260904_1", BuildConfig.C_TYPE)
        assertEquals("GOOGLE_AD_TV_DESKTOP_V260904_1", BuildConfig.MODEL)
        assertEquals("com.localvideoplayer.tv", BuildConfig.APPLICATION_ID)
        assertTrue(BuildFlavor.isHq008Family("google_ad_tv_desktop_tcl_test"))
        assertFalse(BuildFlavor.isGoogleAdTvDesktop("google_ad_tv_desktop_tcl_test"))
        assertTrue(BuildFlavor.isGoogleAdTvDesktop("google_ad_tv_desktop"))
        assertFalse(BuildFlavor.isGoogleTclExperiment("google_ad_tv_desktop"))
    }
}
