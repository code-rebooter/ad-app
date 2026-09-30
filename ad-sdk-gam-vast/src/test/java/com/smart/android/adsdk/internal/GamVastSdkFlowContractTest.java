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

    @Test
    public void playerLifecycleRunsOnMainThreadAndReleasesBeforeSurfaceDetach() throws Exception {
        String controllerSource = readProjectFile(
            "ad-sdk-gam-vast/src/main/java/com/smart/android/adsdk/internal/AdPlaybackController.java"
        );
        String factorySource = readProjectFile(
            "ad-sdk-gam-vast/src/main/java/com/smart/android/adsdk/internal/AdPlaybackControllerFactory.java"
        );

        assertTrue(controllerSource.contains("runOnMainThread(() -> playOnMain("));
        assertTrue(controllerSource.contains("runOnMainThread(this::pauseOnMain)"));
        assertTrue(controllerSource.contains("runOnMainThread(this::resumeOnMain)"));
        assertTrue(controllerSource.contains("runOnMainThread(() -> setSoundEnabledOnMain(enabled))"));
        assertTrue(controllerSource.contains("runOnMainThread(this::releasePlayerResources)"));
        assertFalse(controllerSource.contains("releaseAsync"));
        assertFalse(controllerSource.contains("Media3ReleaseThreadGuard"));
        assertFalse(factorySource.contains("PlayerReleaseCoordinator"));

        int stopPlayer = controllerSource.indexOf("playerToRelease.stop()");
        int releasePlayer = controllerSource.indexOf("playerToRelease.release()");
        int detachPlayerView = controllerSource.indexOf("playerView.setPlayer(null)");
        int removeAdView = controllerSource.indexOf("container.removeView(adRoot)");
        assertTrue(stopPlayer >= 0);
        assertTrue(releasePlayer > stopPlayer);
        assertTrue(detachPlayerView > releasePlayer);
        assertTrue(removeAdView > detachPlayerView);
    }

    @Test
    public void playerDelegatesAudioFocusLifecycleToMedia3() throws Exception {
        String controllerSource = readProjectFile(
            "ad-sdk-gam-vast/src/main/java/com/smart/android/adsdk/internal/AdPlaybackController.java"
        );

        assertTrue(controllerSource.contains(".setAudioAttributes(adAudioAttributes, true)"));
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
