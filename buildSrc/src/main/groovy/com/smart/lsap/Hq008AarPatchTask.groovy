package com.smart.lsap

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction

import java.security.MessageDigest
import java.util.jar.JarEntry
import java.util.jar.JarInputStream
import java.util.jar.JarOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

abstract class Hq008AarPatchTask extends DefaultTask {
    @InputFile
    abstract RegularFileProperty getInputAar()

    @OutputFile
    abstract RegularFileProperty getOutputAar()

    @Input
    abstract Property<String> getExpectedSha256()

    @Input
    abstract Property<String> getTargetFlavor()

    @TaskAction
    void patch() {
        File input = inputAar.get().asFile
        File output = outputAar.get().asFile
        String actualHash = sha256(input.bytes)
        if (actualHash != expectedSha256.get()) {
            throw new GradleException("AAR SHA-256 mismatch for ${targetFlavor.get()}: ${actualHash}")
        }

        byte[] originalClasses = null
        Map<String, byte[]> aarEntries = [:]
        ZipFile zipFile = new ZipFile(input)
        try {
            zipFile.entries().each { entry ->
                byte[] bytes = zipFile.getInputStream(entry).bytes
                if (entry.name == 'classes.jar') {
                    originalClasses = bytes
                } else if (!entry.directory) {
                    aarEntries[entry.name] = bytes
                }
            }
        } finally {
            zipFile.close()
        }
        if (originalClasses == null) {
            throw new GradleException("classes.jar missing: ${input}")
        }

        Map<String, byte[]> classEntries = [:]
        int modifiedClasses = 0
        JarInputStream jarInput = new JarInputStream(new ByteArrayInputStream(originalClasses))
        JarEntry jarEntry
        while ((jarEntry = jarInput.nextJarEntry) != null) {
            if (jarEntry.directory) continue
            byte[] bytes = readCurrentEntry(jarInput)
            if (jarEntry.name.endsWith('.class')) {
                byte[] patched
                switch (targetFlavor.get()) {
                    case 'google_ima':
                        patched = LsapClassPatcher.patchGoogleImaParameters(jarEntry.name, bytes)
                        break
                    case 'google_ump':
                        patched = LsapClassPatcher.patchGoogleUmpParameters(jarEntry.name, bytes)
                        break
                    default:
                        patched = LsapClassPatcher.patchHq008Parameters(jarEntry.name, bytes)
                        break
                }
                if (!Arrays.equals(bytes, patched)) {
                    modifiedClasses++
                }
                classEntries[jarEntry.name] = patched
            } else {
                classEntries[jarEntry.name] = bytes
            }
        }
        jarInput.close()
        if (targetFlavor.get() == 'google_ima') {
            verifyGoogleImaPatch(classEntries, modifiedClasses)
        } else if (targetFlavor.get() == 'google_ump') {
            verifyGoogleUmpPatch(classEntries)
        }
        ByteArrayOutputStream classesOutput = new ByteArrayOutputStream()
        JarOutputStream jarOutput = new JarOutputStream(classesOutput)
        classEntries.keySet().sort().each { name ->
            JarEntry entry = new JarEntry(name)
            entry.time = 0L
            jarOutput.putNextEntry(entry)
            jarOutput.write(classEntries[name])
            jarOutput.closeEntry()
        }
        jarOutput.close()
        byte[] patchedClasses = classesOutput.toByteArray()

        List<String> metadataLines
        if (targetFlavor.get() == 'google_ima') {
            metadataLines = [
                'patchVersion=google-ima-parameter-normalization-5',
                'visibilityPolicy=ignore-ima-view-visibility-gate',
                'viewabilityPolicy=ignore-ima-size-threshold-gate',
                'nativeViewabilityPolicy=force-ima-view-visible-bounds',
                'omidViewabilityPolicy=ignore-ima-visibility-reason-and-focus-gates',
                'lifecyclePolicy=keep-ima-activity-monitor-active',
                'systemPropertyPolicy=normalize-ima-system-property-overload',
                'webViewUaPolicy=normalize-before-every-navigation',
                'nativeNetworkPolicy=preserve-gks-selection',
                'nativeNetworkHeaderCoverage=http-url-connection-only',
                "originalAarSha256=${actualHash}",
                "patchedClassesJarSha256=${sha256(patchedClasses)}",
                "targetFlavor=${targetFlavor.get()}",
                "modifiedClasses=${modifiedClasses}"
            ]
        } else if (targetFlavor.get() == 'google_ump') {
            metadataLines = [
                'patchVersion=google-ump-parameter-normalization-1',
                'consentPolicy=preserve',
                'deviceIdentityPolicy=normalize-request-model-and-android-version',
                'webViewUaPolicy=normalize-before-initial-html-load-and-navigation',
                'nativeNetworkHeaderCoverage=http-url-connection',
                "originalAarSha256=${actualHash}",
                "patchedClassesJarSha256=${sha256(patchedClasses)}",
                "targetFlavor=${targetFlavor.get()}",
                "modifiedClasses=${modifiedClasses}"
            ]
        } else {
            metadataLines = [
                'patchVersion=hq008-parameter-normalization-11',
                'audioFocusPolicy=ad_ytx01_sxk-disabled',
                'renderSurfacePolicy=ad_ytx01_sxk-texture-view-for-surface-view',
                'videoOutputFrameRatePolicy=ad_ytx01_sxk-12fps',
                'videoDecoderPolicy=platform-default',
                'audioTrackPolicy=ad_ytx01_sxk-disabled-in-exoplayer-track-selector',
                "originalAarSha256=${actualHash}",
                "patchedClassesJarSha256=${sha256(patchedClasses)}",
                "targetFlavor=${targetFlavor.get()}",
                "modifiedClasses=${modifiedClasses}"
            ]
        }
        String metadata = metadataLines.join('\n') + '\n'

        output.parentFile.mkdirs()
        ZipOutputStream zipOutput = new ZipOutputStream(new FileOutputStream(output))
        aarEntries.keySet().sort().each { name ->
            Hq008AarPatchTask.writeZip(zipOutput, name, aarEntries[name])
        }
        Hq008AarPatchTask.writeZip(zipOutput, 'classes.jar', patchedClasses)
        Hq008AarPatchTask.writeZip(
            zipOutput,
            'META-INF/hq008-parameter-patch.properties',
            metadata.getBytes('UTF-8')
        )
        zipOutput.close()
        logger.lifecycle(
            "Patched ${modifiedClasses} classes for ${targetFlavor.get()} -> ${output}"
        )
    }

    private static void verifyGoogleImaPatch(
        Map<String, byte[]> classEntries,
        int modifiedClasses
    ) {
        if (modifiedClasses != 34) {
            throw new GradleException(
                "Google IMA patch changed ${modifiedClasses} classes; expected exactly 34"
            )
        }

        String bridge = 'com/smart/android/ad_app/Hq008XhsxAarRuntimeBridge'
        byte[] webViewLoader = requireClass(
            classEntries,
            'com/google/ads/interactivemedia/v3/impl/zzbk.class'
        )
        requireCallCount(
            webViewLoader,
            bridge,
            'loadWebViewUrl',
            '(Landroid/webkit/WebView;Ljava/lang/String;)V',
            2,
            'IMA Core WebView navigation bridge'
        )
        requireCallCount(
            webViewLoader,
            'android/webkit/WebView',
            'loadUrl',
            '(Ljava/lang/String;)V',
            0,
            'unpatched IMA Core WebView navigation'
        )

        byte[] companionWebView = requireClass(
            classEntries,
            'com/google/ads/interactivemedia/v3/impl/zzap.class'
        )
        requireCallCount(
            companionWebView,
            bridge,
            'loadWebViewUrl',
            '(Landroid/webkit/WebView;Ljava/lang/String;)V',
            1,
            'IMA companion WebView navigation bridge'
        )
        requireCallCount(
            companionWebView,
            bridge,
            'loadWebViewData',
            '(Landroid/webkit/WebView;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V',
            1,
            'IMA companion WebView data bridge'
        )
        requireCallCount(
            companionWebView,
            'com/google/ads/interactivemedia/v3/impl/zzap',
            'loadUrl',
            '(Ljava/lang/String;)V',
            0,
            'unpatched IMA companion WebView navigation'
        )
        requireCallCount(
            companionWebView,
            'com/google/ads/interactivemedia/v3/impl/zzap',
            'loadData',
            '(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V',
            0,
            'unpatched IMA companion WebView data'
        )

        byte[] localNetwork = requireClass(
            classEntries,
            'com/google/ads/interactivemedia/v3/impl/zzbp.class'
        )
        requireCallCount(
            localNetwork,
            bridge,
            'openUrlConnection',
            '(Ljava/net/URL;)Ljava/net/URLConnection;',
            1,
            'IMA local URL connection bridge'
        )
        requireCallCount(
            localNetwork,
            bridge,
            'setUrlConnectionRequestProperty',
            '(Ljava/net/URLConnection;Ljava/lang/String;Ljava/lang/String;)V',
            1,
            'IMA local User-Agent header bridge'
        )

        requireCallCount(
            requireClass(classEntries, 'com/google/ads/interactivemedia/v3/impl/zzy.class'),
            bridge,
            'getAndroidVersionRelease',
            '()Ljava/lang/String;',
            1,
            'IMA request environment Android version bridge'
        )

        byte[] instrumentation = requireClass(
            classEntries,
            'com/google/ads/interactivemedia/v3/internal/zzfj.class'
        )
        requireCallCount(instrumentation, bridge, 'getAndroidDeviceModel',
            '()Ljava/lang/String;', 1, 'IMA instrumentation model bridge')
        requireCallCount(instrumentation, bridge, 'getAndroidManufacturer',
            '()Ljava/lang/String;', 1, 'IMA instrumentation manufacturer bridge')
        requireCallCount(instrumentation, bridge, 'getAndroidVersionRelease',
            '()Ljava/lang/String;', 1, 'IMA instrumentation Android version bridge')

        byte[] omid = requireClass(
            classEntries,
            'com/google/ads/interactivemedia/v3/internal/zzdf.class'
        )
        requireCallCount(omid, bridge, 'getAndroidDeviceModel',
            '()Ljava/lang/String;', 1, 'IMA OMID model bridge')
        requireCallCount(omid, bridge, 'getAndroidManufacturer',
            '()Ljava/lang/String;', 1, 'IMA OMID manufacturer bridge')
        requireCallCount(omid, bridge, 'getAndroidSdkInt',
            '()I', 1, 'IMA OMID SDK bridge')

        requireCallCount(
            requireClass(classEntries, 'com/google/ads/interactivemedia/v3/internal/zzjq.class'),
            'android/view/View',
            'getVisibility',
            '()I',
            0,
            'IMA native visibility-state gate'
        )
        requireCallCount(
            requireClass(classEntries, 'com/google/ads/interactivemedia/v3/internal/zzjq.class'),
            'android/view/View',
            'isShown',
            '()Z',
            0,
            'IMA native shown-state gate'
        )
        requireCallCount(
            requireClass(classEntries, 'com/google/ads/interactivemedia/v3/internal/zzjq.class'),
            'android/os/PowerManager',
            'isScreenOn',
            '()Z',
            0,
            'IMA native screen-state gate'
        )
        requireCallCount(
            requireClass(classEntries, 'com/google/ads/interactivemedia/v3/internal/zzjq.class'),
            'android/app/KeyguardManager',
            'inKeyguardRestrictedInputMode',
            '()Z',
            0,
            'IMA native keyguard gate'
        )

        byte[] sizeViewability = requireClass(
            classEntries,
            'com/google/ads/interactivemedia/v3/internal/zzjk.class'
        )
        requireCallCount(sizeViewability, 'android/view/View', 'getWidth', '()I', 0,
            'IMA 50-percent width gate')
        requireCallCount(sizeViewability, 'android/view/View', 'getHeight', '()I', 0,
            'IMA 50-percent height gate')

        byte[] nativeViewability = requireClass(
            classEntries,
            'com/google/ads/interactivemedia/v3/impl/zzb.class'
        )
        requireCallCount(nativeViewability, 'android/view/View', 'getGlobalVisibleRect',
            '(Landroid/graphics/Rect;)Z', 0, 'IMA native visible-rectangle gate')
        requireCallCount(nativeViewability, 'android/view/View', 'getWindowToken',
            '()Landroid/os/IBinder;', 0, 'IMA native window-token gate')
        requireCallCount(nativeViewability, 'android/view/View', 'isShown', '()Z', 0,
            'IMA native view-hidden gate')

        byte[] omidVisibilityReason = requireClass(
            classEntries,
            'com/google/ads/interactivemedia/v3/internal/zzdq.class'
        )
        requireCallCount(omidVisibilityReason, 'android/view/View', 'isAttachedToWindow',
            '()Z', 0, 'IMA OMID attached-state reason')
        requireCallCount(omidVisibilityReason, 'android/view/View', 'getVisibility',
            '()I', 0, 'IMA OMID visibility reason')
        requireCallCount(omidVisibilityReason, 'android/view/View', 'getAlpha',
            '()F', 0, 'IMA OMID alpha reason')

        byte[] omidSessionState = requireClass(
            classEntries,
            'com/google/ads/interactivemedia/v3/internal/zzds.class'
        )
        requireCallCount(omidSessionState, 'android/view/View', 'isAttachedToWindow',
            '()Z', 0, 'IMA OMID session attached-state gate')
        requireCallCount(omidSessionState, 'android/view/View', 'hasWindowFocus',
            '()Z', 0, 'IMA OMID session focus gate')

        byte[] omidHierarchy = requireClass(
            classEntries,
            'com/google/ads/interactivemedia/v3/internal/zzdd.class'
        )
        requireCallCount(omidHierarchy, 'android/view/View', 'isAttachedToWindow',
            '()Z', 0, 'IMA OMID hierarchy attached-state gate')
        requireCallCount(omidHierarchy, 'android/view/View', 'isShown',
            '()Z', 0, 'IMA OMID hierarchy shown-state gate')
        requireCallCount(omidHierarchy, 'android/view/View', 'getAlpha',
            '()F', 0, 'IMA OMID hierarchy alpha gate')

        requireCallCount(
            requireClass(classEntries, 'com/google/ads/interactivemedia/v3/internal/zzdz.class'),
            'com/google/ads/interactivemedia/v3/internal/zzdq',
            'zza',
            '(Landroid/view/View;)Ljava/lang/String;',
            0,
            'IMA OMID not-visible reason propagation'
        )
        requireCallCount(
            requireClass(classEntries, 'com/google/ads/interactivemedia/v3/internal/zzcq.class'),
            'android/view/View',
            'hasWindowFocus',
            '()Z',
            0,
            'IMA OMID foreground focus gate'
        )
        requireCallCount(
            requireClass(classEntries, 'com/google/ads/interactivemedia/v3/internal/zzct.class'),
            'android/app/ActivityManager',
            'getMyMemoryState',
            '(Landroid/app/ActivityManager$RunningAppProcessInfo;)V',
            0,
            'IMA OMID process-foreground gate'
        )

        byte[] networkSelector = requireClass(
            classEntries,
            'com/google/ads/interactivemedia/v3/impl/zzba.class'
        )
        requireAllocation(networkSelector,
            'com/google/ads/interactivemedia/v3/impl/zzbr',
            'IMA GKS network backend')
        requireAllocation(networkSelector,
            'com/google/ads/interactivemedia/v3/impl/zzbp',
            'IMA local network backend')
    }

    private static void verifyGoogleUmpPatch(Map<String, byte[]> classEntries) {
        String bridge = 'com/smart/android/ad_app/Hq008XhsxAarRuntimeBridge'
        byte[] requestPayload = requireClass(
            classEntries,
            'com/google/android/gms/internal/consent_sdk/zzco.class'
        )
        requireCallCount(requestPayload, bridge, 'getAndroidDeviceModel',
            '()Ljava/lang/String;', 1, 'UMP request model bridge')
        requireCallCount(requestPayload, bridge, 'getAndroidVersionRelease',
            '()Ljava/lang/String;', 1, 'UMP request Android version bridge')

        [
            'com/google/android/gms/internal/consent_sdk/zzw.class': 'UMP consent request',
            'com/google/android/gms/internal/consent_sdk/zzcr.class': 'UMP metrics request'
        ].each { className, label ->
            byte[] network = requireClass(classEntries, className)
            requireCallCount(network, bridge, 'getDefaultWebViewUserAgent',
                '(Landroid/content/Context;)Ljava/lang/String;', 1, "${label} UA bridge")
            requireCallCount(network, bridge, 'openUrlConnection',
                '(Ljava/net/URL;)Ljava/net/URLConnection;', 1, "${label} connection bridge")
            requireCallCount(network, bridge, 'setUrlConnectionRequestProperty',
                '(Ljava/net/URLConnection;Ljava/lang/String;Ljava/lang/String;)V', 2,
                "${label} request-header bridge")
        }

        byte[] formLoader = requireClass(
            classEntries,
            'com/google/android/gms/internal/consent_sdk/zzbe.class'
        )
        requireCallCount(
            formLoader,
            bridge,
            'loadWebViewDataWithBaseUrl',
            '(Landroid/webkit/WebView;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V',
            1,
            'UMP initial consent HTML load bridge'
        )
        requireCallCount(
            formLoader,
            'com/google/android/gms/internal/consent_sdk/zzbx',
            'loadDataWithBaseURL',
            '(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V',
            0,
            'unpatched UMP initial consent HTML load'
        )

        byte[] javascriptLoader = requireClass(
            classEntries,
            'com/google/android/gms/internal/consent_sdk/zzda.class'
        )
        requireCallCount(javascriptLoader, bridge, 'loadWebViewUrl',
            '(Landroid/webkit/WebView;Ljava/lang/String;)V', 1,
            'UMP fallback WebView navigation bridge')
    }

    private static byte[] requireClass(Map<String, byte[]> classEntries, String name) {
        byte[] bytes = classEntries[name]
        if (bytes == null) {
            throw new GradleException("Google AAR patch contract class missing: ${name}")
        }
        return bytes
    }

    private static void requireCallCount(
        byte[] bytes,
        String owner,
        String name,
        String desc,
        int expected,
        String label
    ) {
        int actual = LsapClassPatcher.countMethodCalls(bytes, owner, name, desc)
        if (actual != expected) {
            throw new GradleException(
                "Google AAR patch contract mismatch for ${label}: expected ${expected}, found ${actual}"
            )
        }
    }

    private static void requireAllocation(byte[] bytes, String owner, String label) {
        int actual = LsapClassPatcher.countTypeAllocations(bytes, owner)
        if (actual == 0) {
            throw new GradleException(
                "Google IMA patch contract mismatch for ${label}: allocation missing"
            )
        }
    }

    private static void writeZip(ZipOutputStream output, String name, byte[] bytes) {
        ZipEntry entry = new ZipEntry(name)
        entry.time = 0L
        output.putNextEntry(entry)
        output.write(bytes)
        output.closeEntry()
    }

    private static byte[] readCurrentEntry(InputStream input) {
        ByteArrayOutputStream output = new ByteArrayOutputStream()
        byte[] buffer = new byte[8192]
        int read
        while ((read = input.read(buffer)) != -1) {
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    private static String sha256(byte[] bytes) {
        MessageDigest.getInstance('SHA-256').digest(bytes).collect {
            String.format('%02x', it)
        }.join()
    }
}
