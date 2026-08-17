package com.smart.android.adsdk.internal;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Test;

public class GamVastSdkFlowContractTest {

    @Test
    public void remoteConfigClientRunsBusinessFlowBeforeGamResolve() throws Exception {
        String source = readProjectFile(
            "ad-sdk-gam-vast/src/main/java/com/smart/android/adsdk/internal/RemoteAdConfigClient.java"
        );

        assertTrue(source.contains("api/v2/ad/sdk/flow-control"));
        assertTrue(source.contains("api/v2/ad/sdk/authorize"));
        assertTrue(source.contains("api/v2/ad/google-gam/resolve"));
        assertTrue(source.indexOf("requestFlowControl") < source.indexOf("requestAuthorize"));
        assertTrue(source.indexOf("requestAuthorize") < source.indexOf("requestGamConfig"));
        assertTrue(source.contains("\"sound_mode\""));
        assertTrue(source.contains("\"hidden_mode\""));
    }

    @Test
    public void gamVastSdkDoesNotContainUmpRuntimeCodeOrDependency() throws Exception {
        String moduleSource = readProjectFile("ad-sdk-gam-vast/build.gradle")
            + readProjectFile("ad-sdk-gam-vast/src/main/AndroidManifest.xml")
            + readProjectFile("ad-sdk-gam-vast/src/main/java/com/smart/android/adsdk/internal/SdkRuntime.java")
            + readProjectFile("ad-sdk-gam-vast/src/main/java/com/smart/android/adsdk/internal/AdSessionImpl.java");

        assertFalse(moduleSource.contains("UserMessagingPlatform"));
        assertFalse(moduleSource.contains("user-messaging-platform"));
        assertFalse(moduleSource.contains("com.google.android.ump"));
        assertFalse(moduleSource.contains("UMP"));
        assertFalse(moduleSource.contains("AdConsent"));
        assertFalse(moduleSource.contains("ConsentResolver"));
    }

    private String readProjectFile(String path) throws IOException {
        Path relative = Paths.get(path);
        Path directory = Paths.get("").toAbsolutePath();
        while (directory != null) {
            Path candidate = directory.resolve(relative);
            if (Files.exists(candidate)) {
                return new String(Files.readAllBytes(candidate), StandardCharsets.UTF_8);
            }
            directory = directory.getParent();
        }
        throw new IOException("Unable to find " + path);
    }
}
