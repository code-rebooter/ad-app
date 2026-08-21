package com.smart.android.adsdk.internal;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.nio.charset.StandardCharsets;
import org.junit.Test;

public class SdkRuntimeSystemUidCompatContractTest {

    @Test
    public void initializePreparesSystemUidStorageBeforeComponentCreation() {
        String source = readProjectFile(
            "ad-sdk-modern/src/main/java/com/smart/android/adsdk/internal/SdkRuntime.java"
        );
        int prepareIndex = source.indexOf("SystemUidStorageCompat.prepareSdkEntry(context, \"initialize\")");
        int createIndex = source.indexOf("sessionCreator = componentsFactory.create(context, config, dispatcher)");

        assertTrue("SDK 初始化入口必须自己处理 system uid 存储兼容", prepareIndex >= 0);
        assertTrue("组件创建仍必须存在", createIndex > 0);
        assertTrue("system uid 存储兼容必须早于任何组件创建", prepareIndex < createIndex);
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
