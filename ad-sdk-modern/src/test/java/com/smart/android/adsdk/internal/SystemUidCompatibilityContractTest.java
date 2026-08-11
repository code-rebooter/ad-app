package com.smart.android.adsdk.internal;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.nio.charset.StandardCharsets;
import org.junit.Test;

public class SystemUidCompatibilityContractTest {

    @Test
    public void consentResolverSkipsUmpBeforeStartingConsentFlowForSystemUid() {
        String resolverSource = readProjectFile(
            "ad-sdk-modern/src/main/java/com/smart/android/adsdk/internal/AdConsentResolver.java"
        );

        int systemUidBypassIndex = resolverSource.indexOf("if (SystemUidStorageCompat.isSystemUid())");
        int umpRequestIndex = resolverSource.indexOf("AdConsentManager.requestConsent(");

        assertTrue("system uid bypass must be present", systemUidBypassIndex >= 0);
        assertTrue("system uid bypass must run before UMP requestConsent", systemUidBypassIndex < umpRequestIndex);
        assertTrue("system uid bypass must allow playback", resolverSource.contains("callback.onAllowed();"));
    }

    @Test
    public void imaPlaybackUsesSystemUidStorageCompatibilityBeforeBuildingImaLoader() {
        String playbackSource = readProjectFile(
            "ad-sdk-modern/src/main/java/com/smart/android/adsdk/internal/AdPlaybackController.java"
        );

        int webViewCompatIndex = playbackSource.indexOf("SystemUidStorageCompat.prepareGoogleWebView(\"IMA\")");
        int contextCompatIndex = playbackSource.indexOf("SystemUidStorageCompat.resolveGoogleSdkContext(context)");
        int imaBuilderIndex = playbackSource.indexOf("new ImaAdsLoader.Builder(googleSdkContext)");

        assertTrue("IMA playback must prewarm WebViewFactory under system uid", webViewCompatIndex >= 0);
        assertTrue("IMA playback must resolve a Google SDK context", contextCompatIndex >= 0);
        assertTrue("IMA loader must be built with the resolved Google SDK context", imaBuilderIndex >= 0);
        assertTrue("WebView compatibility must run before IMA builder", webViewCompatIndex < imaBuilderIndex);
        assertTrue("Context compatibility must run before IMA builder", contextCompatIndex < imaBuilderIndex);
    }

    @Test
    public void compatibilityHelperContainsWhaleStyleSystemUidStorageHandling() {
        String helperSource = readProjectFile(
            "ad-sdk-modern/src/main/java/com/smart/android/adsdk/internal/SystemUidStorageCompat.java"
        );

        assertTrue("helper must detect uid 1000", helperSource.contains("SYSTEM_UID = 1000"));
        assertTrue("helper must read Process.myUid", helperSource.contains("Process.myUid()"));
        assertTrue(
            "helper must switch device protected storage to credential protected storage through reflection",
            helperSource.contains("getDeclaredMethod(\"createCredentialProtectedStorageContext\")")
        );
        assertTrue(
            "helper must use WhaleTV-style WebViewFactory prewarm",
            helperSource.contains("Class.forName(\"android.webkit.WebViewFactory\")")
        );
        assertTrue(
            "compatibility helper must not touch filesDir while avoiding storage hangs",
            !helperSource.contains("getFilesDir()")
        );
        assertTrue(
            "compatibility helper must not touch cacheDir while avoiding storage hangs",
            !helperSource.contains("getCacheDir()")
        );
    }

    private String readProjectFile(String relativePath) {
        File workingDir = new File(System.getProperty("user.dir", "."));
        File current = workingDir;
        while (current != null) {
            File candidate = new File(current, relativePath);
            if (candidate.exists()) {
                return readText(candidate);
            }
            current = current.getParentFile();
        }
        fail("Unable to locate project file: " + relativePath);
        return "";
    }

    private String readText(File file) {
        try {
            return new String(java.nio.file.Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        } catch (java.io.IOException error) {
            throw new AssertionError("Unable to read " + file, error);
        }
    }
}
