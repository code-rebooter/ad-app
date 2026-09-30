package com.smart.android.adsdk.internal;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.nio.charset.StandardCharsets;
import org.junit.Test;

public class SystemUidStorageCompatTest {

    @Test
    public void systemUidGoogleSdkContextUsesCredentialProtectedStorage() {
        String source = readProjectFile(
            "ad-sdk-modern-no-ump/src/main/java/com/smart/android/adsdk/internal/SystemUidStorageCompat.java"
        );

        assertTrue(
            "system uid should route Google SDK storage through credential protected context",
            source.contains("createCredentialProtectedStorageContext(context)")
        );
    }

    @Test
    public void playbackPreparesWebViewWithHostContextBeforeImaConstruction() {
        String source = readProjectFile(
            "ad-sdk-modern-no-ump/src/main/java/com/smart/android/adsdk/internal/AdPlaybackController.java"
        );
        int prepareIndex = source.indexOf("SystemUidStorageCompat.prepareGoogleWebView(\"IMA\")");
        int builderIndex = source.indexOf("new ImaAdsLoader.Builder(googleSdkContext)");

        assertTrue("playback must prepare WebView storage before IMA is constructed", prepareIndex >= 0);
        assertTrue("IMA builder construction must remain present", builderIndex > 0);
        assertTrue("WebView preparation must happen before IMA builder construction", prepareIndex < builderIndex);
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
