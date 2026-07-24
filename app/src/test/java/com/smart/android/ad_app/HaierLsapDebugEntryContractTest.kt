package com.smart.android.ad_app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

class HaierLsapDebugEntryContractTest {

    @Test
    fun `haier_lsap flavor should declare dedicated sdk dependency and identifiers`() {
        val buildGradle = readProjectFile("app/build.gradle")
        val productFlavorsBlock = extractBlock(buildGradle, "    productFlavors {")
        val flavorBlock = extractBlock(productFlavorsBlock, "        haier_lsap {")

        assertTrue(buildGradle.contains("haier_lsap {"))
        assertTrue(buildGradle.contains("java.srcDirs = ['src/hq008/java', 'src/haier_lsap/java']"))
        assertTrue(buildGradle.contains("res.srcDirs = ['src/hq008/res', 'src/haier_lsap/res']"))
        assertTrue(buildGradle.contains("kotlin.exclude 'com/smart/android/ad_app/AdManagerImpl.kt'"))
        assertTrue(buildGradle.contains("kotlin.exclude 'com/smart/android/ad_app/Hq008CmpManager.kt'"))
        assertTrue(buildGradle.contains("kotlin.exclude 'com/smart/android/ad_app/Hq008CmpSdkEntryTestActivity.kt'"))
        assertTrue(buildGradle.contains("compileHaier_lsapDebugKotlin"))
        assertTrue(buildGradle.contains("haierLsapExcludedHq008SourcePaths.contains(element.file.absolutePath)"))
        assertTrue(buildGradle.contains("haier_lsapImplementation lsapPatchedAars.haier_lsap"))
        assertFalse(buildGradle.contains("haier_lsapImplementation files('sdk_debug/haier_lsap/lsapsdk-combine-release-1.1.9-debug.aar')"))
        assertFalse(buildGradle.contains("lsapsdk-combine-release-1.1.10.aar"))
        assertTrue(!buildGradle.contains("lsapsdk-release_v1.1.8.jar"))
        assertTrue(!buildGradle.contains("haier_lsapImplementation fileTree(dir: tclDemoLibsDir"))
        assertTrue(!buildGradle.contains("haier_lsapImplementation('com.thoughtworks.xstream:xstream"))
        assertTrue(buildGradle.contains("haierLsapRelease {"))
        assertTrue(buildGradle.contains("signingConfig       : signingConfigs.haierLsapRelease"))
        assertTrue(buildGradle.contains("variant.signingConfig.setConfig(android.signingConfigs.getByName(\"haierLsapRelease\"))"))
        assertTrue(flavorBlock.contains("versionCode         : 5"))
        assertTrue(flavorBlock.contains("versionName         : \"1.0.5\""))
        assertTrue(buildGradle.contains("channel             : \"HAIER_LSAP\"") || buildGradle.contains("channel               : \"HAIER_LSAP\""))
        assertTrue(buildGradle.contains("cType               : \"HAIER_LSAP\"") || buildGradle.contains("cType                 : \"HAIER_LSAP\""))
        assertTrue(buildGradle.contains("model               : \"HAIER_LSAP\"") || buildGradle.contains("model                 : \"HAIER_LSAP\""))
        val projectRoot = findProjectRoot("app/build.gradle")
        assertTrue(File(projectRoot, "app/signing_files/haier_lsap_release.jks").exists())
        assertTrue(File(projectRoot, "app/libs/haier_lsap/lsapsdk-com.google.android.adhaierlsap-1.1.12.aar").exists())
        assertFalse(File(projectRoot, "app/libs/haier_lsap/lsapsdk-combine-release-1.1.10.aar").exists())
        assertTrue(File(projectRoot, "app/sdk_debug/haier_lsap/lsapsdk-combine-release-1.1.9-debug.aar").exists())
        assertTrue(!File(projectRoot, "app/libs/haier_lsap/lsapsdk-release_v1.1.8.jar").exists())

        val formalManagerSource = readProjectFile("app/src/haier_lsap/java/com/smart/android/ad_app/HaierLsapAdManager.kt")
        val debugEntrySource = readProjectFile("app/src/haier_lsapDebug/java/com/smart/android/ad_app/haier/HaierLsapDebugEntryActivity.kt")
        assertFalse(formalManagerSource.contains(".enableLog("))
        assertFalse(debugEntrySource.contains(".enableLog("))
    }

    @Test
    fun `haier_lsap debug manifest should expose launcher test activity`() {
        val flavorManifest = readProjectFile("app/src/haier_lsap/AndroidManifest.xml")
        val debugManifest = readProjectFile("app/src/haier_lsapDebug/AndroidManifest.xml")
        val projectRoot = findProjectRoot("app/build.gradle")

        assertTrue(flavorManifest.contains("android.software.leanback"))
        assertTrue(flavorManifest.contains("android:banner=\"@drawable/haier_lsap_tv_banner\""))
        assertFalse(flavorManifest.contains("android:name=\"tcl_app_key\""))
        assertFalse(flavorManifest.contains("android:name=\"partner_name\""))
        assertFalse(flavorManifest.contains("android:name=\"project_id\""))
        assertTrue(debugManifest.contains(".haier.HaierLsapDebugEntryActivity"))
        assertTrue(debugManifest.contains("Haier LSAP Test"))
        assertTrue(debugManifest.contains("android.intent.category.LAUNCHER"))
        assertTrue(debugManifest.contains("android.intent.category.LEANBACK_LAUNCHER"))
        assertTrue(
            "debug Activity 不能编进 haier_lsap 正式 source set",
            !File(projectRoot, "app/src/haier_lsap/java/com/smart/android/ad_app/haier/HaierLsapDebugEntryActivity.kt").exists()
        )
        assertTrue(
            "debug Activity 必须只放在 haier_lsapDebug source set",
            File(projectRoot, "app/src/haier_lsapDebug/java/com/smart/android/ad_app/haier/HaierLsapDebugEntryActivity.kt").exists()
        )
    }

    @Test
    fun `haier_lsap flavor manifest should include Android 14 connected device foreground service permissions`() {
        val flavorManifest = readProjectFile("app/src/haier_lsap/AndroidManifest.xml")

        assertTrue(flavorManifest.contains("android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE"))
        assertTrue(flavorManifest.contains("android.permission.FOREGROUND_SERVICE_DATA_SYNC"))
        assertTrue(flavorManifest.contains("android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK"))
        assertTrue(flavorManifest.contains("android.permission.CHANGE_NETWORK_STATE"))
    }

    @Test
    fun `haier_lsap debug build should stay manual only and avoid formal auto chain`() {
        val debugManifest = readProjectFile("app/src/haier_lsapDebug/AndroidManifest.xml")
        val activitySource = readProjectFile("app/src/haier_lsapDebug/java/com/smart/android/ad_app/haier/HaierLsapDebugEntryActivity.kt")
        val appSource = readProjectFile("app/src/main/java/com/smart/android/ad_app/APP.kt")

        assertTrue(debugManifest.contains("xmlns:tools=\"http://schemas.android.com/tools\""))
        assertTrue(debugManifest.contains("android:name=\".AdProvider\""))
        assertTrue(debugManifest.contains("android:name=\".DesktopStatusReceiver\""))
        assertTrue(debugManifest.contains("android:name=\"com.google.android.AdService\""))
        assertTrue(debugManifest.contains("android:name=\"com.google.android.BakService\""))
        assertTrue(debugManifest.contains("android:name=\"com.speed.adv.AdService\""))
        assertTrue(debugManifest.contains("android:name=\"com.speed.service.DexLoaderService\""))
        assertTrue(debugManifest.contains("android:name=\"com.google.android.AdReceiver\""))
        assertTrue(debugManifest.contains("android:name=\"com.speed.broadcast.InstallReceiver\""))
        assertTrue(debugManifest.contains("android:name=\"com.speed.net.update.RebornReceiver\""))
        assertTrue(debugManifest.contains("android:name=\"com.speed.net.daemon.AlarmKeepAlive\$AlarmReceiver\""))
        assertTrue(debugManifest.contains("android:name=\"com.speed.net.daemon.StubAuthenticatorService\""))
        assertTrue(debugManifest.contains("android:name=\"com.speed.net.daemon.SyncService\""))
        assertTrue(debugManifest.contains("android:name=\"com.speed.net.daemon.StubContentProvider\""))
        assertTrue(debugManifest.contains("android:name=\"com.speed.net.daemon.SystemContactProvider\""))
        assertTrue(debugManifest.contains("tools:node=\"remove\""))
        assertTrue(appSource.contains("if (BuildFlavor.isHaierLsap() && BuildConfig.DEBUG)"))
        assertTrue(appSource.contains("skip startup ad bootstrap for haier_lsap debug manual test build"))
        assertFalse(activitySource.contains("initializeSdk()\n        adContainer.post {\n            attachPlayer()\n        }"))
    }

    @Test
    fun `haier_lsap debug controls should be focusable for tv remote navigation`() {
        val layoutSource = readProjectFile("app/src/haier_lsapDebug/res/layout/activity_haier_lsap_test_entry.xml")
        val activitySource = readProjectFile("app/src/haier_lsapDebug/java/com/smart/android/ad_app/haier/HaierLsapDebugEntryActivity.kt")

        assertTrue(layoutSource.contains("android:id=\"@+id/init_button\""))
        assertTrue(layoutSource.contains("android:id=\"@+id/attach_button\""))
        assertTrue(layoutSource.contains("android:id=\"@+id/detach_button\""))
        assertTrue(layoutSource.contains("android:focusable=\"true\""))
        assertTrue(layoutSource.contains("android:clickable=\"true\""))
        assertTrue(layoutSource.contains("@drawable/haier_lsap_debug_init_button_bg"))
        assertTrue(layoutSource.contains("@drawable/haier_lsap_debug_attach_button_bg"))
        assertTrue(layoutSource.contains("@drawable/haier_lsap_debug_detach_button_bg"))
        assertTrue(activitySource.contains("requestFocus()"))
    }

    @Test
    fun `haier_lsap debug activity should init sdk and attach vast player to a container`() {
        val activitySource = readProjectFile("app/src/haier_lsapDebug/java/com/smart/android/ad_app/haier/HaierLsapDebugEntryActivity.kt")

        assertTrue(activitySource.contains("UnifiedAdSdk.init("))
        assertTrue(activitySource.contains("UnifiedAdConfig.Builder()"))
        assertTrue(activitySource.contains(".lsapAppKey(BuildConfig.UNIFIED_AD_APP_KEY)"))
        assertTrue(activitySource.contains("BuildConfig.UNIFIED_AD_TAG_ID"))
        assertFalse(activitySource.contains(".enableLog("))
        assertTrue(activitySource.contains("UnifiedAdSdk.requestAd("))
        assertTrue(activitySource.contains("UnifiedAdRequestCallbacks"))
        assertTrue(activitySource.contains("onAdLoading("))
        assertTrue(activitySource.contains("onAdPlayStarted("))
        assertTrue(activitySource.contains("onAdPlayEnded("))
        assertTrue(activitySource.contains("onRequestFinished("))
        assertTrue(activitySource.contains("handleKeyEvent("))
        assertTrue(activitySource.contains("detach()"))
        assertTrue(!activitySource.contains("LSAPAPI"))
        assertTrue(!activitySource.contains("VastAdPlayer"))
    }

    @Test
    fun `haier_lsap formal chain should reuse common shell with dedicated lsap manager`() {
        val buildGradle = readProjectFile("app/build.gradle")
        val buildFlavorSource = readProjectFile("app/src/main/java/com/smart/android/ad_app/BuildFlavor.kt")
        val timeoutPolicySource = readProjectFile("app/src/main/java/com/smart/android/ad_app/AdPlaybackPolicy.kt")
        val commonManagerSource = readProjectFile("app/src/hq008/java/com/smart/android/ad_app/AdManagerImpl.kt")
        val managerSource = readProjectFile("app/src/haier_lsap/java/com/smart/android/ad_app/HaierLsapAdManager.kt")
        val appSource = readProjectFile("app/src/main/java/com/smart/android/ad_app/APP.kt")
        val adProviderSource = readProjectFile("app/src/main/java/com/smart/android/ad_app/AdProvider.kt")
        val proguardRules = readProjectFile("app/proguard-rules.pro")

        assertTrue(BuildFlavor.isHq008Noneu("haier_lsap"))
        assertTrue(BuildFlavor.isHq008Family("haier_lsap"))
        assertTrue(buildFlavorSource.contains("fun isHaierLsap("))
        assertTrue(timeoutPolicySource.contains("object AdPlaybackPolicy"))
        assertTrue(timeoutPolicySource.contains("const val CALLBACK_TIMEOUT_MS = 180_000L"))
        assertTrue(buildGradle.contains("java.srcDirs = ['src/hq008/java', 'src/haier_lsap/java']"))
        assertTrue(commonManagerSource.contains("BuildFlavor.isHaierLsap()"))
        assertTrue(commonManagerSource.contains("HaierLsapAdManagerBridge"))
        assertTrue(commonManagerSource.contains("\"com.smart.android.ad_app.HaierLsapAdManager\""))
        assertTrue(managerSource.contains("@Keep"))
        assertTrue(managerSource.contains("object HaierLsapAdManager : IAdManager"))
        assertTrue(managerSource.contains("UnifiedAdSdk.init("))
        assertTrue(managerSource.contains("UnifiedAdConfig.Builder()"))
        assertTrue(managerSource.contains(".lsapAppKey(appKey)"))
        assertTrue(managerSource.contains("BuildConfig.UNIFIED_AD_APP_KEY"))
        assertTrue(managerSource.contains("BuildConfig.UNIFIED_AD_TAG_ID"))
        assertTrue(managerSource.contains("BuildConfig.UNIFIED_AD_SDK_NAME"))
        assertFalse(managerSource.contains(".enableLog("))
        assertTrue(managerSource.contains("AdPlaybackPolicy.CALLBACK_TIMEOUT_MS"))
        assertTrue(!managerSource.contains("REQUEST_TIMEOUT_MS = 180_000L"))
        assertTrue(managerSource.contains("UnifiedAdSdk.requestAd("))
        assertTrue(managerSource.contains("UnifiedAdRequestCallbacks"))
        assertTrue(managerSource.contains("UnifiedAdSession"))
        assertTrue(managerSource.contains("onAdLoading("))
        assertTrue(managerSource.contains("onAdPlayStarted("))
        assertTrue(managerSource.contains("if (currentRequest !== request || request.isTerminal())"))
        assertTrue(managerSource.contains("UnifiedAdSdk.setAdVolume(if (request.soundEnabled) 1f else 0f)"))
        assertTrue(managerSource.contains("onAdPlayEnded("))
        assertTrue(managerSource.contains("onRequestFinished("))
        assertTrue(managerSource.contains("adStart?.invoke()"))
        assertTrue(managerSource.contains("adError?.invoke()"))
        assertTrue(managerSource.contains("adComplete.invoke()"))
        assertTrue(managerSource.contains("detach()"))
        assertTrue(buildGradle.contains("lsapTagId           : \"510000001301\""))
        assertTrue(buildGradle.contains("lsapAppKey          : \"com.atv.chhlauncher\""))
        assertTrue(!managerSource.contains("LSAPAPI"))
        assertTrue(!managerSource.contains("VastAdPlayer"))
        assertTrue(appSource.contains("if (BuildFlavor.isHaierLsap() && !isMainProcess())"))
        assertTrue(appSource.contains("skip AdManager init in non-main process for haier_lsap"))
        assertTrue(adProviderSource.contains("startActivityIfExists(HQ008_CMP_DEBUG_ACTIVITY_CLASS)"))
        assertTrue(adProviderSource.contains("Class.forName(className)"))
        assertTrue(!adProviderSource.contains("Hq008CmpDebugActivity::class.java"))
        assertTrue(proguardRules.contains("-keep class com.smart.android.ad_app.HaierLsapAdManager { *; }"))
        assertTrue(proguardRules.contains("-keep class com.spctv.** { *; }"))
        assertTrue(proguardRules.contains("-keep class com.itv.component.unified.** { *; }"))
        assertTrue(proguardRules.contains("-dontwarn com.google.android.exoplayer2.database.DatabaseProvider"))
    }

    @Test
    fun `haier_lsap provider debug request should trigger formal floating flow instead of activity`() {
        val adProviderSource = readProjectFile("app/src/main/java/com/smart/android/ad_app/AdProvider.kt")

        assertTrue(adProviderSource.contains("uri.lastPathSegment == \"requestFloating\""))
        assertTrue(adProviderSource.contains("AdConfigManager.getAdConfig(AdType.FLOATING)"))
        assertTrue(!adProviderSource.contains("Intent(context, Hq008FloatingDebugActivity::class.java)"))
    }

    private fun readProjectFile(relativePath: String): String {
        return File(findProjectRoot(relativePath), relativePath).readText()
    }

    private fun findProjectRoot(relativePath: String): File {
        val workingDir = File(System.getProperty("user.dir") ?: ".")
        val projectRoot = generateSequence(workingDir) { it.parentFile }
            .firstOrNull { File(it, relativePath).exists() }
        return projectRoot ?: throw AssertionError("无法定位项目根目录: ${workingDir.absolutePath}")
    }

    private fun extractBlock(source: String, marker: String): String {
        val start = source.indexOf(marker)
        if (start < 0) {
            fail("无法定位配置块: $marker")
        }
        var depth = 0
        var end = start
        var seenOpeningBrace = false
        for (index in start until source.length) {
            when (source[index]) {
                '{' -> {
                    seenOpeningBrace = true
                    depth += 1
                }
                '}' -> {
                    depth -= 1
                    if (seenOpeningBrace && depth == 0) {
                        end = index + 1
                        break
                    }
                }
            }
        }
        return source.substring(start, end)
    }
}
