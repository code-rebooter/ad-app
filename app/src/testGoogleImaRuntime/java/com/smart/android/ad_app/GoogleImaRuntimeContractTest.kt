package com.smart.android.ad_app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class GoogleImaRuntimeContractTest {

    @Test
    fun `variant selects isolated IMA runtime without TCL execution`() {
        assertEquals("ima", BuildConfig.GOOGLE_AD_RUNTIME)
        assertFalse(BuildFlavor.usesTclRuntime(BuildFlavor.CHANNEL_TYPE_GOOGLE, "ima"))
        assertTrue(BuildFlavor.needsIdentityPatch(BuildFlavor.CHANNEL_TYPE_GOOGLE))
        assertTrue(BuildFlavor.usesGoogleConsent(BuildFlavor.CHANNEL_TYPE_GOOGLE))
    }

    @Test
    fun `controlled runtime preserves business identifiers and blocks uncontrolled paths`() {
        val moduleBuild = readProjectFile("ad-sdk-google-ima/build.gradle")
        val firmwareInputs = readProjectFile(
            "ad-sdk-google-ima/src/main/java/com/smart/android/ad_app/google/ima/local/" +
                "ImaFirmwareInputs.java"
        )
        val runtimeBridge = readProjectFile(
            "ad-sdk-google-ima/src/main/java/com/smart/android/googleima/internal/" +
                "GoogleImaRuntimeBridge.java"
        )
        val sharedIdentity = readProjectFile(
            "ad-sdk-google-ima/src/main/java/com/smart/android/ad_app/google/ima/local/" +
                "ImaSharedAdvertisingIdentity.java"
        )
        val patcher = readProjectFile(
            "buildSrc/src/main/groovy/com/smart/lsap/LsapClassPatcher.groovy"
        )
        val auditReporter = readProjectFile(
            "app/src/googleImaRuntime/java/com/smart/android/ad_app/" +
                "GoogleImaRuntimeAuditReporter.kt"
        )
        val consentReporter = readProjectFile(
            "app/src/hq008/java/com/smart/android/ad_app/Hq008ConsentLogReporter.kt"
        )
        val imaManager = readProjectFile(
            "app/src/googleImaRuntime/java/com/smart/android/ad_app/GoogleImaAdManager.kt"
        )
        val imaPlayer = readProjectFile(
            "ad-sdk-google-ima/src/main/java/com/smart/android/googleima/" +
                "GoogleImaAdPlayerView.java"
        )
        val proguardRules = readProjectFile("app/proguard-rules.pro")

        assertTrue(moduleBuild.contains("com.smart.android.googleima.controlled:interactivemedia:3.35.1"))
        assertTrue(
            moduleBuild.contains(
                "com.smart.android.googleima.controlled:media3-exoplayer-ima:1.8.0"
            )
        )
        assertTrue(moduleBuild.contains("patchGoogleMedia3ImaAar"))
        assertTrue(moduleBuild.contains("verifyControlledGoogleImaAars"))
        assertTrue(moduleBuild.contains("dependsOn verifyControlledGoogleImaAars"))

        assertFalse(firmwareInputs.contains("withoutAppSet"))
        assertFalse(firmwareInputs.contains("\"pvid\""))
        assertFalse(firmwareInputs.contains("\"pvid_s\""))

        assertTrue(runtimeBridge.contains("localMsFor"))
        assertTrue(runtimeBridge.contains("emptyAdsIdentityToken"))
        assertTrue(runtimeBridge.contains("emptySecureSignals"))
        assertTrue(runtimeBridge.contains("normalizeGksFlag"))
        assertTrue(runtimeBridge.contains("recordGassBlocked"))
        assertTrue(runtimeBridge.contains("recordDynamicVmBlocked"))
        assertTrue(runtimeBridge.contains("auditNativeResponse"))
        assertTrue(runtimeBridge.contains("AUDIT_NETWORK_SCRIPT"))
        assertTrue(runtimeBridge.contains("onVastResponse"))
        assertTrue(runtimeBridge.contains("injectFallbackWebViewNetworkAudit"))
        assertTrue(runtimeBridge.contains("auditOmidBridge"))
        assertTrue(runtimeBridge.contains("setAuditedWebViewClient"))
        assertTrue(runtimeBridge.contains("IMA_CLICK_BYPASS_SCRIPT"))
        assertTrue(runtimeBridge.contains("onClickBypass"))
        assertTrue(runtimeBridge.contains("external_click"))
        assertTrue(runtimeBridge.contains("interceptImaCoreScript"))
        assertTrue(runtimeBridge.contains("core_script_injected"))
        assertTrue(runtimeBridge.contains("routeThroughUi"))
        assertTrue(runtimeBridge.contains("__smartImaClickRouteV2"))
        assertTrue(runtimeBridge.contains("ui_route"))
        assertTrue(runtimeBridge.contains("notifyWithoutPause"))
        assertTrue(runtimeBridge.contains("clickRouteEnabled"))
        assertTrue(runtimeBridge.contains("bindNativeRequestAudit"))
        assertTrue(runtimeBridge.contains("bindAsyncAuditOwner"))
        assertTrue(runtimeBridge.contains("auditForWebView"))
        assertTrue(runtimeBridge.contains("IdentityWeakReference"))
        assertTrue(runtimeBridge.contains("public boolean onRenderProcessGone"))
        assertTrue(runtimeBridge.contains("delegate.onRenderProcessGone(view, detail)"))
        assertTrue(runtimeBridge.contains("view.destroy()"))
        assertTrue(runtimeBridge.contains("render_process_gone handled="))
        assertTrue(runtimeBridge.contains("getAdvertisingIdInfoForUrl"))
        assertTrue(runtimeBridge.contains("getAdvertisingIdInfoForV2"))
        assertTrue(runtimeBridge.contains("shouldCollectAppSet"))
        assertTrue(runtimeBridge.contains("forceIdentifierInfoCollection"))
        assertTrue(runtimeBridge.contains("source=shared_local"))
        assertTrue(runtimeBridge.contains("source=tcl_persisted"))
        assertTrue(runtimeBridge.contains("forceUnmutedVolumePercentage"))
        assertTrue(runtimeBridge.contains("forceUnmutedAdsRequest"))

        assertTrue(sharedIdentity.contains("independent_local_adid_settings_v2"))
        assertTrue(sharedIdentity.contains("independent_local_per_app_lat_v1"))
        assertTrue(sharedIdentity.contains("google_tcl_ad"))
        assertTrue(sharedIdentity.contains("TCL_AID = \"aid\""))
        assertTrue(sharedIdentity.contains("UUID.randomUUID().toString()"))

        assertTrue(patcher.contains("patchGoogleAdsIdentifierDiagnostics"))
        assertTrue(patcher.contains("patchGoogleAppSetTelemetry"))
        assertTrue(patcher.contains("patchGoogleMedia3ImaVolume"))
        assertTrue(patcher.contains("getControlledAdProgressInfo"))
        assertTrue(patcher.contains("usesClientTelemetry"))
        assertTrue(patcher.contains("instruction.operand == 27601"))
        assertTrue(patcher.contains("auditNativeResponse"))
        assertTrue(patcher.contains("auditOmidBridge"))
        assertTrue(patcher.contains("getAdvertisingIdInfoForUrl"))
        assertTrue(patcher.contains("getAdvertisingIdInfoForV2"))
        assertTrue(patcher.contains("shouldCollectAppSet"))
        assertTrue(patcher.contains("forceIdentifierInfoCollection"))
        assertTrue(imaManager.contains("startSource=\$normalizedSource"))
        assertTrue(imaManager.contains("AD_IMA_STARTED_LATE"))
        assertTrue(imaManager.contains("lastImaProgressAt"))
        assertTrue(imaManager.contains("pausePlaybackBudget"))
        assertTrue(imaManager.contains("resetCurrentAdMetadata"))
        assertTrue(imaManager.contains("hardRemainingMs"))
        assertTrue(imaManager.contains("pendingRenderedFirstFrameAt"))
        assertTrue(imaManager.contains("pendingMaxPositionMs"))
        assertTrue(imaManager.contains("isCurrentSegmentActive(session)"))
        assertTrue(imaManager.contains("cancelPlaybackStartTimeout(session)"))
        assertTrue(imaManager.contains("click_bypass"))
        assertTrue(imaManager.contains("AD_FAKE_CTR_CONFIRMED"))
        assertTrue(imaManager.contains("AD_FAKE_CTR_UNCONFIRMED"))
        assertTrue(imaManager.contains("setFakeCtrEnabled"))
        assertTrue(proguardRules.contains("currentAdTagLoader"))
        assertTrue(proguardRules.contains("adsManager"))
        assertFalse(imaPlayer.contains("capturedAdsManager"))
        assertFalse(imaPlayer.contains("addAdsLoadedListener"))
        val fingerprintBody = Regex(
            "static String adFingerprint\\(Ad ad\\) \\{([\\s\\S]*?)\\n    \\}"
        ).find(imaPlayer)?.groupValues?.get(1).orEmpty()
        assertFalse(fingerprintBody.contains("pod.totalAds"))

        assertTrue(auditReporter.contains("IMA_RUNTIME_AUDIT"))
        assertTrue(auditReporter.contains("verification_scripts"))
        assertTrue(auditReporter.contains("omid_runtime_audit"))
        assertTrue(auditReporter.contains("identity_control_audit"))
        assertTrue(auditReporter.contains("vast_artifacts"))
        assertTrue(auditReporter.contains("gzip+base64"))
        assertTrue(auditReporter.contains("effective_ad_start"))
        assertTrue(auditReporter.contains("fields[\"ad_instance_id\"]"))
        assertFalse(consentReporter.contains("IMA_VAST_ARTIFACT"))
        assertFalse(consentReporter.contains("reportArtifact"))
        assertEquals(
            1,
            Regex("NetworkHelper\\.makeRequest<EmptyData>").findAll(consentReporter).count()
        )
        assertTrue(consentReporter.contains("\"IMA_RUNTIME_AUDIT\""))
        assertTrue(imaManager.contains("if (session.finishing) return"))
        assertTrue(imaManager.contains("if (!beginFinishing(session)) return"))
        assertTrue(imaManager.contains("if (session.completionPending)"))
        assertTrue(imaManager.contains("finishCompletedSession(session)"))
        assertFalse(imaManager.contains("if (!isActive(session)) return@Runnable"))
        assertTrue(imaManager.contains("terminal_snapshot\" -> handlePlayerSnapshot"))
    }

    private fun readProjectFile(relativePath: String): String {
        val workingDir = File(System.getProperty("user.dir") ?: ".")
        val root = generateSequence(workingDir) { it.parentFile }
            .firstOrNull { File(it, relativePath).isFile }
            ?: throw AssertionError("Cannot locate project file: $relativePath")
        return File(root, relativePath).readText()
    }
}
