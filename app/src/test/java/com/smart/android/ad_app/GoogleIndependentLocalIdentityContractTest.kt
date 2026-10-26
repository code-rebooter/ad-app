package com.smart.android.ad_app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.jar.JarInputStream
import java.util.zip.ZipFile

class GoogleIndependentLocalIdentityContractTest {

    @Test
    fun `independent aar owns advertising and app set identity without gms clients`() {
        val root = projectRoot()
        val aar = File(
            root,
            "app/libs/google-url-independent/independent-google-tv-url-local-identity-20261025.aar"
        )
        assertTrue("Selected independent AAR is missing", aar.isFile)

        val classes = linkedMapOf<String, ByteArray>()
        val manifest = ZipFile(aar).use { zip ->
            val manifestEntry = zip.getEntry("AndroidManifest.xml")
            assertNotNull("AAR manifest is missing", manifestEntry)
            val text = zip.getInputStream(manifestEntry).bufferedReader().use { it.readText() }
            val classesJar = zip.getInputStream(zip.getEntry("classes.jar")).readBytes()
            JarInputStream(ByteArrayInputStream(classesJar)).use { jar ->
                while (true) {
                    val entry = jar.nextJarEntry ?: break
                    if (entry.name.endsWith(".class")) classes[entry.name] = jar.readBytes()
                }
            }
            text
        }

        assertTrue(manifest.contains("com.google.android.gms.permission.AD_ID"))
        assertFalse(manifest.contains("com.google.android.gms.version"))
        assertTrue(classes.containsKey("io/codex/imarepro/IndependentLocalIdentityStore.class"))

        val forbiddenOwners = listOf(
            "com/google/android/gms/ads/identifier",
            "com/google/android/gms/appset",
            "GoogleApiAvailabilityLight"
        )
        classes.forEach { (name, bytes) ->
            val constants = bytes.toString(StandardCharsets.ISO_8859_1)
            forbiddenOwners.forEach { owner ->
                assertFalse("$name retains forbidden GMS identity owner $owner", constants.contains(owner))
            }
        }

        val storeOwner = "io/codex/imarepro/IndependentLocalIdentityStore"
        assertTrue(classConstants(classes, "io/codex/imarepro/AndroidOrdinaryNativeSources.class").contains(storeOwner))
        assertTrue(classConstants(classes, "io/codex/imarepro/independent/android/V2QueryLiveSources.class").contains(storeOwner))
    }

    @Test
    fun `selected independent aar matches the reviewed binary`() {
        val aar = File(
            projectRoot(),
            "app/libs/google-url-independent/independent-google-tv-url-local-identity-20261025.aar"
        )
        val actualSha256 = MessageDigest.getInstance("SHA-256")
            .digest(aar.readBytes())
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

        assertEquals(
            "6035713c0ae1263a6cac26ac37b34807b64ca385b8087ab417ad5c5d562ac060",
            actualSha256
        )
    }

    private fun classConstants(classes: Map<String, ByteArray>, name: String): String {
        val bytes = classes[name] ?: error("Missing class $name")
        return bytes.toString(StandardCharsets.ISO_8859_1)
    }

    private fun projectRoot(): File {
        val workingDir = File(System.getProperty("user.dir") ?: ".")
        return generateSequence(workingDir) { it.parentFile }
            .firstOrNull { File(it, "app/build.gradle").isFile }
            ?: error("Unable to locate project root from ${workingDir.absolutePath}")
    }
}
