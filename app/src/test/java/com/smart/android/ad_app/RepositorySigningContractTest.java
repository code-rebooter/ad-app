package com.smart.android.ad_app;

import org.junit.Test;

import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyStore;
import java.util.Properties;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class RepositorySigningContractTest {

    @Test
    public void signingPropertiesAndPrivateKeysAreAvailableInsideRepository() throws Exception {
        File signingDirectory = new File(findProjectRoot(), "app/signing_files").getCanonicalFile();
        String[] propertiesNames = {
                "local-video-player-release.properties",
                "tcl-ad-demo-release.properties",
                "tcl-ad-demo-noneu-release.properties"
        };

        for (String propertiesName : propertiesNames) {
            File propertiesFile = new File(signingDirectory, propertiesName);
            assertTrue("Missing signing properties: " + propertiesName, propertiesFile.isFile());

            Properties properties = new Properties();
            try (InputStream propertiesStream = Files.newInputStream(propertiesFile.toPath())) {
                properties.load(propertiesStream);
            }

            for (String propertyName : new String[]{"storeFile", "storePassword", "keyAlias", "keyPassword"}) {
                assertFalse("Missing " + propertyName + " in " + propertiesName,
                        properties.getProperty(propertyName, "").trim().isEmpty());
            }

            File keystoreFile = new File(signingDirectory, properties.getProperty("storeFile")).getCanonicalFile();
            assertTrue("Keystore must remain inside signing_files: " + propertiesName,
                    keystoreFile.toPath().startsWith(signingDirectory.toPath()));
            assertTrue("Missing keystore: " + propertiesName, keystoreFile.isFile());

            KeyStore keyStore = KeyStore.getInstance(properties.getProperty("storeType", "JKS"));
            try (InputStream keystoreStream = Files.newInputStream(keystoreFile.toPath())) {
                keyStore.load(keystoreStream, properties.getProperty("storePassword").toCharArray());
            }
            String keyAlias = properties.getProperty("keyAlias");
            assertTrue("Signing alias must contain a private key: " + propertiesName, keyStore.isKeyEntry(keyAlias));
            assertNotNull("Missing signing certificate: " + propertiesName, keyStore.getCertificate(keyAlias));
            assertNotNull("Cannot unlock signing key: " + propertiesName,
                    keyStore.getKey(keyAlias, properties.getProperty("keyPassword").toCharArray()));
        }
    }

    @Test
    public void gradleLoadsSigningFilesWithoutSiblingProjectDependencies() throws Exception {
        String buildGradle = readBuildGradle();
        String[][] signingFiles = {
                {"localVideoPlayer", "local-video-player-release.properties"},
                {"tclDemo", "tcl-ad-demo-release.properties"},
                {"tclDemoNoneu", "tcl-ad-demo-noneu-release.properties"}
        };

        for (String[] signingFile : signingFiles) {
            String propertiesVariable = signingFile[0] + "SigningProperties";
            assertTrue("Missing repository-local property loader: " + signingFile[0],
                    buildGradle.contains("def " + propertiesVariable + "File = file(\"signing_files/"
                            + signingFile[1] + "\")"));
            assertTrue("Keystore must resolve relative to its properties file: " + signingFile[0],
                    buildGradle.contains("storeFile new File(" + propertiesVariable + "File.parentFile, "
                            + propertiesVariable + ".getProperty(\"storeFile\"))"));
        }

        assertFalse(buildGradle.contains("../GooglePlayApps"));
        assertFalse(buildGradle.contains("new File(tclDemoProjectDir, \"signing/"));
        assertFalse(buildGradle.contains("storeFile new File(tclDemoProjectDir,"));
    }

    @Test
    public void customerFlavorKeepsItsOriginalDebugAndReleaseSigningConfig() throws Exception {
        String buildGradle = readBuildGradle();
        int flavorStart = buildGradle.indexOf("google_ad_tv_desktop_v260904_1 {",
                buildGradle.indexOf("productFlavors"));
        assertTrue("Missing customer flavor", flavorStart >= 0);
        int flavorEnd = buildGradle.indexOf("\n        }", flavorStart);
        String flavor = buildGradle.substring(flavorStart, flavorEnd);
        assertTrue(flavor.contains("signingConfigs.localVideoPlayerRelease"));

        int debugOverrideStart = buildGradle.indexOf(
                "onVariants(selector().withBuildType(\"debug\").withFlavor(\"ad\", \"google_ad_tv_desktop_v260904_1\"))");
        assertTrue("Missing customer debug signing override", debugOverrideStart >= 0);
        int debugOverrideEnd = buildGradle.indexOf("\n    }", debugOverrideStart);
        String debugOverride = buildGradle.substring(debugOverrideStart, debugOverrideEnd);
        assertTrue(debugOverride.contains("getByName(\"localVideoPlayerRelease\")"));
    }

    private String readBuildGradle() throws Exception {
        return new String(Files.readAllBytes(new File(findProjectRoot(), "app/build.gradle").toPath()),
                StandardCharsets.UTF_8);
    }

    private File findProjectRoot() {
        File directory = new File(System.getProperty("user.dir", ".")).getAbsoluteFile();
        while (directory != null) {
            if (new File(directory, "app/build.gradle").isFile()) {
                return directory;
            }
            directory = directory.getParentFile();
        }
        throw new AssertionError("Cannot locate AD_APP project root");
    }
}
