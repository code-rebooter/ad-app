package com.smart.android.adsdk.internal;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.nio.charset.StandardCharsets;
import org.junit.Test;

public class AdConsentAlreadyAuthorizedContractTest {

    @Test
    public void alreadyAuthorizedUmpStateShouldNotTriggerRemoteDecisionOrPrivacyOptionsRewrite() {
        String resolverSource = readProjectFile(
            "ad-sdk-modern/src/main/java/com/smart/android/adsdk/internal/AdConsentResolver.java"
        );
        String managerSource = readProjectFile(
            "ad-sdk-modern/src/main/java/com/smart/android/adsdk/internal/AdConsentManager.java"
        );

        assertFalse(
            "privacy options availability must not keep the remote consent-popup flow eligible",
            resolverSource.contains("result.canRequestAds && !\"REQUIRED\".equals(result.privacyOptionsStatus)")
        );
        assertTrue(
            "already authorized UMP state should be allowed locally",
            resolverSource.contains("if (result.canRequestAds) {\n"
                + "                        completion.complete(callback::onAllowed);")
        );
        assertFalse(
            "stored consent must not be silently rewritten through privacy options",
            managerSource.contains("showSilentPrivacyOptionsForm(activity, action);")
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
