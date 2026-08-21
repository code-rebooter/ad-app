package com.smart.android.adsdk.internal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.nio.charset.StandardCharsets;
import org.junit.Test;

public class SystemUidStorageCompatTest {

    @Test
    public void systemUidGoogleSdkContextUsesDeviceProtectedStorage() {
        String source = readProjectFile(
            "ad-sdk-modern/src/main/java/com/smart/android/adsdk/internal/SystemUidStorageCompat.java"
        );

        assertTrue(
            "system uid should route Google SDK storage through device protected context",
            source.contains("createDeviceProtectedStorageContext(context)")
        );
        assertFalse(
            "system uid compatibility should not switch Google SDK back to credential protected storage",
            source.contains("createCredentialProtectedStorageContext")
        );
    }

    @Test
    public void webViewDataDirectorySuffixIsStableAndProcessScoped() {
        assertEquals(
            "ad_sdk_system_uid_com_example_app",
            SystemUidStorageCompat.buildWebViewDataDirectorySuffix(
                "com.example.app",
                "com.example.app"
            )
        );
        assertEquals(
            "ad_sdk_system_uid_com_example_app_remote_web",
            SystemUidStorageCompat.buildWebViewDataDirectorySuffix(
                "com.example.app",
                "com.example.app:remote/web"
            )
        );
        assertEquals(
            "ad_sdk_system_uid_unknown",
            SystemUidStorageCompat.buildWebViewDataDirectorySuffix("", "")
        );
    }

    @Test
    public void playbackPreparesWebViewWithHostContextBeforeImaConstruction() {
        String source = readProjectFile(
            "ad-sdk-modern/src/main/java/com/smart/android/adsdk/internal/AdPlaybackController.java"
        );
        int prepareIndex = source.indexOf("SystemUidStorageCompat.prepareGoogleWebView(context, \"IMA\")");
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
