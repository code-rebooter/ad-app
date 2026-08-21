package androidx.preference;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.nio.charset.StandardCharsets;
import org.junit.Test;

public class PreferenceManagerStorageCompatTest {

    @Test
    public void defaultPreferencesUseDeviceProtectedContextForSystemUid() {
        String source = readProjectFile(
            "ad-sdk-modern/src/main/java/androidx/preference/PreferenceManager.java"
        );

        assertTrue(source.contains("private static final int SYSTEM_UID = 1000"));
        assertTrue(source.contains("Process.myUid() == SYSTEM_UID"));
        assertTrue(source.contains("createDeviceProtectedStorageContext"));
        assertTrue(source.contains("return storageContext(context).getSharedPreferences("));
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
