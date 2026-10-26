package com.smart.lsap

import org.junit.Test
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

import static org.junit.Assert.assertEquals
import static org.junit.Assert.assertTrue
import static org.junit.Assume.assumeTrue

class GoogleUmpWholeAarCoverageTest {
    @Test
    void 'actual pinned UMP has no residual corrected identity or raw identity transport calls'() {
        File cache = new File(System.getProperty('user.home'),
            '.gradle/caches/modules-2/files-2.1/com.google.android.ump/user-messaging-platform/4.0.0')
        File aar = null
        assumeTrue('Pinned UMP dependency not cached', cache.exists())
        cache.eachFileRecurse { if (it.name == 'user-messaging-platform-4.0.0.aar') aar = it }
        assumeTrue('Pinned UMP dependency not cached', aar != null)

        byte[] classesJar
        new ZipFile(aar).withCloseable { zip ->
            classesJar = zip.getInputStream(zip.getEntry('classes.jar')).bytes
        }

        List<String> residualIdentity = []
        List<String> residualTransport = []
        Map<String, byte[]> patchedClasses = [:]
        new ZipInputStream(new ByteArrayInputStream(classesJar)).withCloseable { zip ->
            def entry
            while ((entry = zip.nextEntry) != null) {
                if (!entry.name.endsWith('.class')) continue
                byte[] patched = LsapClassPatcher.patchGoogleUmpParameters(
                    entry.name, zip.readAllBytes()
                )
                patchedClasses[entry.name] = patched
                residualIdentity.addAll(
                    LsapClassPatcher.findResidualGooglePlatformIdentityReads(entry.name, patched)
                )
                residualTransport.addAll(
                    LsapClassPatcher.findResidualGoogleUmpIdentityTransportCalls(entry.name, patched)
                )
            }
        }

        assertTrue("Residual UMP identity reads: ${residualIdentity}", residualIdentity.isEmpty())
        assertTrue("Residual UMP identity transport calls: ${residualTransport}",
            residualTransport.isEmpty())

        byte[] environment = patchedClasses[
            'com/google/android/gms/internal/consent_sdk/zzdb.class'
        ]
        assertEquals(2, LsapClassPatcher.countMethodCalls(environment,
            'com/smart/android/ad_app/Hq008XhsxAarRuntimeBridge',
            'getAndroidBuildFingerprint', '()Ljava/lang/String;'))
        assertEquals(1, LsapClassPatcher.countMethodCalls(environment,
            'com/smart/android/ad_app/Hq008XhsxAarRuntimeBridge',
            'getAndroidHardware', '()Ljava/lang/String;'))
        assertEquals(1, LsapClassPatcher.countMethodCalls(environment,
            'com/smart/android/ad_app/Hq008XhsxAarRuntimeBridge',
            'getAndroidDevice', '()Ljava/lang/String;'))

        byte[] testDeviceId = patchedClasses[
            'com/google/android/gms/internal/consent_sdk/zzct.class'
        ]
        assertEquals(1, LsapClassPatcher.countMethodCalls(testDeviceId,
            'android/provider/Settings$Secure', 'getString',
            '(Landroid/content/ContentResolver;Ljava/lang/String;)Ljava/lang/String;'))
    }
}
