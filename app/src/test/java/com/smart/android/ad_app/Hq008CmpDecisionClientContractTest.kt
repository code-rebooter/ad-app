package com.smart.android.ad_app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

class Hq008CmpDecisionClientContractTest {

    @Test
    fun `hq008 should request consent-popup and map multiple consent actions to cmp decision`() {
        val clientSource = readProjectFile("app/src/hq008/java/com/smart/android/ad_app/Hq008CmpDecisionClient.kt")
        val adConfigManagerSource = readProjectFile("app/src/main/java/com/smart/android/ad_app/AdConfigManager.kt")

        assertTrue(clientSource.contains("api/v2/ad/consent-popup"))
        assertTrue(clientSource.contains("\"channel_id\" to BuildConfig.CHANNEL"))
        assertTrue(clientSource.contains("\"mac\" to"))
        assertTrue(clientSource.contains("\"consent_expired\" to Hq008CmpManager.isConsentExpired(context)"))
        assertTrue(clientSource.contains("@field:SerializedName(\"consent_action\")"))
        assertTrue(clientSource.contains("@field:SerializedName(\"consent_payload\")"))

        assertTrue(adConfigManagerSource.contains("Hq008CmpManager.setRemoteDecisionProvider"))
        assertTrue("远端 CMP provider 应面向整个 hq008 family 注入", adConfigManagerSource.contains("if (BuildFlavor.isHq008Family())"))
        assertFalse("远端 CMP provider 不应只对 hq008 单 flavor 生效", adConfigManagerSource.contains("if (BuildFlavor.isHq008())"))
        assertTrue(adConfigManagerSource.contains("Hq008CmpDecisionClient.request(appContext)"))
        assertTrue(adConfigManagerSource.contains("\"ACCEPT_ALL\" -> {"))
        assertTrue(adConfigManagerSource.contains("\"REJECT\" -> {"))
        assertTrue(adConfigManagerSource.contains("\"SAVE_SETTINGS\" -> {"))
        assertTrue(adConfigManagerSource.contains("if (payload == null)"))
        assertTrue(adConfigManagerSource.contains("POPUP_ACTION_INVALID"))
        assertTrue(adConfigManagerSource.contains("fallbackPopupAction(\"payload_missing\")"))
        assertTrue(adConfigManagerSource.contains("\"MAYBE_LATER\" -> {"))
        assertTrue(adConfigManagerSource.contains("\"SKIP_ALREADY_DECIDED\" -> {"))
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
