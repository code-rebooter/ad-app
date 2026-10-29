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
                    case 'google_media3_ima':
                        patched = LsapClassPatcher.patchGoogleMedia3ImaVolume(
                            jarEntry.name,
                            bytes
                        )
                        break
                    case 'google_tv_ads':
                        patched = LsapClassPatcher.patchGoogleTvAdsParameters(jarEntry.name, bytes)
                        break
                    case 'google_ads_identifier':
                        patched = LsapClassPatcher.patchGoogleAdsIdentifierDiagnostics(
                            jarEntry.name,
                            bytes
                        )
                        break
                    case 'google_appset':
                        patched = LsapClassPatcher.patchGoogleAppSetTelemetry(jarEntry.name, bytes)
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
        } else if (targetFlavor.get() == 'google_media3_ima') {
            verifyGoogleMedia3ImaPatch(classEntries, modifiedClasses)
        } else if (targetFlavor.get() == 'google_tv_ads') {
            verifyGoogleTvAdsPatch(classEntries, modifiedClasses)
        } else if (targetFlavor.get() == 'google_ads_identifier') {
            verifyGoogleAdsIdentifierPatch(classEntries, modifiedClasses)
        } else if (targetFlavor.get() == 'google_appset') {
            verifyGoogleAppSetPatch(classEntries, modifiedClasses)
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
                'patchVersion=google-ima-parameter-normalization-8',
                'visibilityPolicy=ignore-ima-view-visibility-gate',
                'viewabilityPolicy=ignore-ima-size-threshold-gate',
                'nativeViewabilityPolicy=force-ima-view-visible-bounds',
                'omidViewabilityPolicy=ignore-ima-visibility-reason-and-focus-gates',
                'lifecyclePolicy=keep-ima-activity-monitor-active',
                'systemPropertyPolicy=normalize-ima-system-property-overload',
                'webViewUaPolicy=normalize-before-every-navigation',
                'msPolicy=local-generator-no-gass-no-dynamic-vm',
                'adsIdentityTokenPolicy=blocked-no-signal-sdk-call',
                'nativeNetworkPolicy=force-local-no-gks-service',
                'secureSignalsPolicy=block-dynamic-core-adapters',
                'gassClearcutPolicy=disabled',
                'advertisingIdPolicy=gms-then-shared-local-then-tcl-persisted',
                'appSetPolicy=skip-when-local-advertising-id-fallback-is-active',
                'vastAuditPolicy=capture-native-xhr-root-and-wrapper-responses',
                'verificationAuditPolicy=observe-webview-resource-loads-and-errors',
                'omidAuditPolicy=observe-js-bridge-session-and-view-hierarchy-calls',
                'soundReportingPolicy=force-unmuted-request-player-activeview-and-omid-signals',
                'playbackVolumePolicy=preserve-host-selected-physical-player-volume',
                'nativeNetworkHeaderCoverage=http-url-connection-only',
                "originalAarSha256=${actualHash}",
                "patchedClassesJarSha256=${sha256(patchedClasses)}",
                "targetFlavor=${targetFlavor.get()}",
                "modifiedClasses=${modifiedClasses}"
            ]
        } else if (targetFlavor.get() == 'google_media3_ima') {
            metadataLines = [
                'patchVersion=google-media3-ima-volume-normalization-1',
                'soundReportingPolicy=force-unmuted-video-ad-player-volume-query',
                'playbackVolumePolicy=preserve-host-selected-physical-player-volume',
                "originalAarSha256=${actualHash}",
                "patchedClassesJarSha256=${sha256(patchedClasses)}",
                "targetFlavor=${targetFlavor.get()}",
                "modifiedClasses=${modifiedClasses}"
            ]
        } else if (targetFlavor.get() == 'google_tv_ads') {
            metadataLines = [
                'patchVersion=google-tv-ads-fingerprint-normalization-1',
                'platformSignalPolicy=preserve-tv-ads-collector-and-field-rules',
                'fingerprintPolicy=host-shared-fingerprint-before-atv-bfp',
                "originalAarSha256=${actualHash}",
                "patchedClassesJarSha256=${sha256(patchedClasses)}",
                "targetFlavor=${targetFlavor.get()}",
                "modifiedClasses=${modifiedClasses}"
            ]
        } else if (targetFlavor.get() == 'google_ads_identifier') {
            metadataLines = [
                'patchVersion=google-ads-identifier-client-diagnostics-1',
                'businessValuePolicy=preserve-aaid-and-lat',
                'diagnosticPolicy=disable-gen-204-client-diagnostic',
                "originalAarSha256=${actualHash}",
                "patchedClassesJarSha256=${sha256(patchedClasses)}",
                "targetFlavor=${targetFlavor.get()}",
                "modifiedClasses=${modifiedClasses}"
            ]
        } else if (targetFlavor.get() == 'google_appset') {
            metadataLines = [
                'patchVersion=google-appset-client-telemetry-1',
                'businessValuePolicy=preserve-appset-id-and-scope',
                'telemetryPolicy=disable-client-telemetry-and-method-key-27601',
                "originalAarSha256=${actualHash}",
                "patchedClassesJarSha256=${sha256(patchedClasses)}",
                "targetFlavor=${targetFlavor.get()}",
                "modifiedClasses=${modifiedClasses}"
            ]
        } else if (targetFlavor.get() == 'google_ump') {
            metadataLines = [
                'patchVersion=google-ump-parameter-normalization-6',
                'identityRequestBuilders=zzco,zzp',
                'consentPolicy=preserve',
                'deviceIdentityPolicy=normalize-request-model-android-version-and-test-environment-fields',
                'environmentApiPolicy=common-getter-real-sdk-int',
                'environmentClassificationPolicy=preserve-sdk-conditions-with-normalized-fingerprint-hardware-and-device',
                'explicitTestDeviceIdPolicy=preserve-android-id-hash-match',
                'wholeAarIdentityPolicy=reject-residual-build-system-property-and-identity-transport-reads',
                'webViewIdentityPolicy=normalize-ua-client-hints-and-document-start-script',
                'dynamicScriptPolicy=document-start-bootstrap-with-html-prefix-fallback',
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
        if (modifiedClasses != 45) {
            throw new GradleException(
                "Google IMA patch changed ${modifiedClasses} classes; expected exactly 45"
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
        String runtimeBridge = 'com/smart/android/googleima/internal/GoogleImaRuntimeBridge'
        requireCallCount(
            webViewLoader,
            runtimeBridge,
            'setAuditedWebViewClient',
            '(Landroid/webkit/WebView;Landroid/webkit/WebViewClient;)V',
            1,
            'IMA WebView resource audit client'
        )
        requireCallCount(
            webViewLoader,
            bridge,
            'setAuditedWebViewClient',
            '(Landroid/webkit/WebView;Landroid/webkit/WebViewClient;)V',
            0,
            'IMA WebView client without runtime resource audit'
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
        requireCallCount(nativeViewability, runtimeBridge, 'forceUnmutedNativeVolume',
            '()D', 1, 'IMA native ActiveView reported volume')
        requireCallCount(nativeViewability, 'android/media/AudioManager', 'getStreamVolume',
            '(I)I', 0, 'IMA native ActiveView physical stream volume read')
        requireCallCount(nativeViewability, 'android/media/AudioManager', 'getStreamMaxVolume',
            '(I)I', 0, 'IMA native ActiveView physical maximum volume read')

        byte[] adsRequest = requireClass(
            classEntries,
            'com/google/ads/interactivemedia/v3/impl/AdsRequestImpl.class'
        )
        requireCallCount(adsRequest, runtimeBridge, 'forceUnmutedAdTagUrl',
            '(Ljava/lang/String;)Ljava/lang/String;', 1,
            'IMA vpmute request normalization')
        requireStaticFieldReadCount(
            adsRequest,
            'com/google/ads/interactivemedia/v3/impl/AdsRequestImpl$MutePlayState',
            'UNMUTED',
            'Lcom/google/ads/interactivemedia/v3/impl/AdsRequestImpl$MutePlayState;',
            1,
            'IMA ad-will-play-muted request state'
        )

        byte[] clientVideoDisplay = requireClass(
            classEntries,
            'com/google/ads/interactivemedia/v3/impl/zzg.class'
        )
        requireCallCount(clientVideoDisplay, runtimeBridge, 'forceUnmutedVolumePercentage',
            '(I)I', 1, 'IMA client-side player volume events')

        byte[] streamVideoDisplay = requireClass(
            classEntries,
            'com/google/ads/interactivemedia/v3/impl/zzcb.class'
        )
        requireCallCount(streamVideoDisplay, runtimeBridge, 'forceUnmutedVolumePercentage',
            '(I)I', 2, 'IMA stream player start and volume events')

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
        requireCallCount(networkSelector, runtimeBridge, 'normalizeGksFlag', '(Z)Z', 1,
            'IMA GKS disable policy')
        requireAllocation(networkSelector,
            'com/google/ads/interactivemedia/v3/impl/zzbr',
            'IMA GKS network backend')
        requireAllocation(networkSelector,
            'com/google/ads/interactivemedia/v3/impl/zzbp',
            'IMA local network backend')

        byte[] msLoader = requireClass(
            classEntries,
            'com/google/ads/interactivemedia/v3/internal/zzgg.class'
        )
        requireCallCount(msLoader, runtimeBridge, 'bindLocalMsOwner',
            '(Ljava/lang/Object;Landroid/content/Context;)Z', 1,
            'IMA local ms owner binding')
        requireCallCount(msLoader, runtimeBridge, 'localMsFor',
            '(Ljava/lang/Object;Ljava/lang/Integer;)Ljava/lang/String;', 2,
            'IMA local ms generation')

        byte[] identityToken = requireClass(
            classEntries,
            'com/google/ads/interactivemedia/v3/internal/zzej.class'
        )
        requireCallCount(identityToken, runtimeBridge, 'emptyAdsIdentityToken',
            '(Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/String;', 1,
            'IMA SignalSdk token block')
        requireCallCount(identityToken, 'com/google/ads/interactivemedia/v3/internal/zznm',
            'doRead', '(Lcom/google/android/gms/common/api/internal/TaskApiCall;)' +
            'Lcom/google/android/gms/tasks/Task;', 0, 'IMA SignalSdk service call')

        byte[] secureSignals = requireClass(
            classEntries,
            'com/google/ads/interactivemedia/v3/internal/zzgd.class'
        )
        requireCallCount(secureSignals, runtimeBridge, 'completedEmptySecureSignals',
            '(Ljava/lang/String;)Lcom/google/android/gms/tasks/Task;', 2,
            'IMA dynamic secure-signal adapters')
        requireCallCount(secureSignals, runtimeBridge, 'emptySecureSignals',
            '(Ljava/lang/String;)Ljava/util/List;', 1,
            'IMA secure-signal collection')

        byte[] gassLogger = requireClass(
            classEntries,
            'com/google/ads/interactivemedia/v3/internal/zzpj.class'
        )
        requireCallCount(gassLogger, runtimeBridge, 'recordGassBlocked',
            '(Ljava/lang/String;)V', 1, 'IMA GASS Clearcut block')
        requireCallCount(gassLogger, 'com/google/android/gms/dynamite/DynamiteModule', 'load',
            '(Landroid/content/Context;Lcom/google/android/gms/dynamite/DynamiteModule\$VersionPolicy;' +
            'Ljava/lang/String;)Lcom/google/android/gms/dynamite/DynamiteModule;',
            0, 'IMA GASS Clearcut Dynamite load')

        byte[] dynamicVm = requireClass(
            classEntries,
            'com/google/ads/interactivemedia/v3/internal/zzjj.class'
        )
        requireCallCount(dynamicVm, runtimeBridge, 'recordDynamicVmBlocked',
            '(Ljava/lang/String;)V', 1, 'IMA dynamic VM block')

        byte[] nativeNetworkAudit = requireClass(
            classEntries,
            'com/google/ads/interactivemedia/v3/impl/zzbs.class'
        )
        requireCallCount(nativeNetworkAudit, runtimeBridge, 'auditNativeResponse',
            '(Ljava/lang/Object;Ljava/lang/Object;)V', 1,
            'IMA native XHR VAST response audit')

        byte[] omidBridgeAudit = requireClass(
            classEntries,
            'com/google/ads/interactivemedia/v3/internal/zzcy.class'
        )
        requireCallCount(omidBridgeAudit, runtimeBridge, 'auditOmidBridge',
            '(Ljava/lang/String;[Ljava/lang/Object;)V', 1,
            'IMA OMID JavaScript bridge audit')
        requireCallCount(omidBridgeAudit, runtimeBridge, 'forceUnmutedOmidArguments',
            '(Ljava/lang/String;[Ljava/lang/Object;)[Ljava/lang/Object;', 1,
            'IMA OMID reported device volume')

        byte[] ordinaryIdentity = requireClass(
            classEntries,
            'com/google/ads/interactivemedia/v3/internal/zzez.class'
        )
        requireCallCount(ordinaryIdentity, runtimeBridge, 'getAdvertisingIdInfoForUrl',
            '(Landroid/content/Context;)' +
            'Lcom/google/android/gms/ads/identifier/AdvertisingIdClient$Info;',
            1, 'IMA ordinary Advertising ID fallback')
        requireCallCount(ordinaryIdentity,
            'com/google/android/gms/ads/identifier/AdvertisingIdClient',
            'getAdvertisingIdInfo',
            '(Landroid/content/Context;)' +
            'Lcom/google/android/gms/ads/identifier/AdvertisingIdClient$Info;',
            0, 'IMA ordinary direct GMS Advertising ID read')
        requireCallCount(ordinaryIdentity, runtimeBridge, 'shouldCollectAppSet',
            '(Z)Z', 1, 'IMA AppSet fallback suppression')

        byte[] v2Identity = requireClass(
            classEntries,
            'com/google/ads/interactivemedia/v3/internal/zzok.class'
        )
        requireCallCount(v2Identity, runtimeBridge, 'getAdvertisingIdInfoForV2',
            '(Landroid/content/Context;)' +
            'Lcom/google/android/gms/ads/identifier/AdvertisingIdClient$Info;',
            1, 'IMA V2 Advertising ID fallback')
        requireCallCount(v2Identity,
            'com/google/android/gms/ads/identifier/AdvertisingIdClient',
            'getAdvertisingIdInfo',
            '(Landroid/content/Context;)' +
            'Lcom/google/android/gms/ads/identifier/AdvertisingIdClient$Info;',
            0, 'IMA V2 direct GMS Advertising ID read')
    }

    private static void verifyGoogleMedia3ImaPatch(
        Map<String, byte[]> classEntries,
        int modifiedClasses
    ) {
        if (modifiedClasses != 2) {
            throw new GradleException(
                "Media3 IMA patch changed ${modifiedClasses} classes; expected exactly 2"
            )
        }
        byte[] adTagLoader = requireClass(
            classEntries,
            'androidx/media3/exoplayer/ima/AdTagLoader.class'
        )
        requireCallCount(
            adTagLoader,
            'com/smart/android/googleima/internal/GoogleImaRuntimeBridge',
            'forceUnmutedAdsRequest',
            '(Lcom/google/ads/interactivemedia/v3/api/AdsRequest;)V',
            1,
            'Media3 IMA AdsRequest videoPlayMuted normalization'
        )
        byte[] videoAdPlayer = requireClass(
            classEntries,
            'androidx/media3/exoplayer/ima/AdTagLoader$VideoAdPlayerImpl.class'
        )
        requireCallCount(
            videoAdPlayer,
            'com/smart/android/googleima/internal/GoogleImaRuntimeBridge',
            'forceUnmutedVolumePercentage',
            '(I)I',
            1,
            'Media3 IMA VideoAdPlayer reported volume'
        )
    }

    private static void verifyGoogleTvAdsPatch(
        Map<String, byte[]> classEntries,
        int modifiedClasses
    ) {
        if (modifiedClasses != 1) {
            throw new GradleException(
                "Google TV Ads patch changed ${modifiedClasses} classes; expected exactly 1"
            )
        }
        byte[] collector = requireClass(
            classEntries,
            'com/google/android/gms/internal/atv_ads_framework/zzad.class'
        )
        String bridge = 'com/smart/android/ad_app/Hq008XhsxAarRuntimeBridge'
        requireCallCount(collector, bridge, 'getAndroidBuildFingerprint',
            '()Ljava/lang/String;', 1, 'TV Ads atv_bfp fingerprint bridge')
        requireStaticFieldReadCount(collector, 'android/os/Build', 'FINGERPRINT',
            'Ljava/lang/String;', 0, 'TV Ads raw fingerprint')
    }

    private static void verifyGoogleAdsIdentifierPatch(
        Map<String, byte[]> classEntries,
        int modifiedClasses
    ) {
        if (modifiedClasses != 1) {
            throw new GradleException(
                "Google Ads Identifier patch changed ${modifiedClasses} classes; expected exactly 1"
            )
        }
        byte[] client = requireClass(
            classEntries,
            'com/google/android/gms/ads/identifier/AdvertisingIdClient.class'
        )
        requireConstantBooleanMethod(
            client,
            'zzc',
            '(Lcom/google/android/gms/ads/identifier/AdvertisingIdClient$Info;ZFJLjava/lang/String;Ljava/lang/Throwable;)Z',
            false,
            'Advertising ID gen_204 diagnostic'
        )
    }

    private static void verifyGoogleAppSetPatch(
        Map<String, byte[]> classEntries,
        int modifiedClasses
    ) {
        if (modifiedClasses != 2) {
            throw new GradleException(
                "Google AppSet patch changed ${modifiedClasses} classes; expected exactly 2"
            )
        }
        byte[] features = requireClass(
            classEntries,
            'com/google/android/gms/internal/appset/zzd.class'
        )
        requireConstantBooleanMethod(
            features,
            'usesClientTelemetry',
            '()Z',
            false,
            'AppSet client telemetry feature'
        )
        byte[] client = requireClass(
            classEntries,
            'com/google/android/gms/internal/appset/zzp.class'
        )
        requireIntInstructionOperandCount(
            client,
            'getAppSetIdInfo',
            '()Lcom/google/android/gms/tasks/Task;',
            27601,
            0,
            'AppSet telemetry method key 27601'
        )
        requireIntInstructionOperandCount(
            client,
            'getAppSetIdInfo',
            '()Lcom/google/android/gms/tasks/Task;',
            0,
            1,
            'AppSet disabled telemetry method key'
        )
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

        byte[] alternatePayload = requireClass(
            classEntries, 'com/google/android/gms/internal/consent_sdk/zzp.class'
        )
        requireCallCount(alternatePayload, bridge, 'getAndroidDeviceModel',
            '()Ljava/lang/String;', 1, 'UMP alternate request model bridge')
        requireCallCount(alternatePayload, bridge, 'getAndroidSdkInt',
            '()I', 3, 'UMP alternate request API bridge')
        List<String> residualIdentity = LsapClassPatcher.findResidualBuildIdentityReads(
            'com/google/android/gms/internal/consent_sdk/zzp.class', alternatePayload
        )
        if (!residualIdentity.isEmpty()) {
            throw new GradleException("Unpatched UMP alternate request identity reads: ${residualIdentity}")
        }

        byte[] environment = requireClass(
            classEntries, 'com/google/android/gms/internal/consent_sdk/zzdb.class'
        )
        requireCallCount(environment, bridge, 'getAndroidSdkInt',
            '()I', 1, 'UMP environment real API bridge')
        requireCallCount(environment, bridge, 'getAndroidBuildFingerprint',
            '()Ljava/lang/String;', 2, 'UMP environment fingerprint bridge')
        requireCallCount(environment, bridge, 'getAndroidHardware',
            '()Ljava/lang/String;', 1, 'UMP environment hardware bridge')
        requireCallCount(environment, bridge, 'getAndroidDevice',
            '()Ljava/lang/String;', 1, 'UMP environment device bridge')
        requireStaticFieldReadCount(environment, 'android/os/Build$VERSION', 'SDK_INT', 'I',
            0, 'UMP environment original API')
        ['FINGERPRINT', 'HARDWARE', 'DEVICE'].each { fieldName ->
            requireStaticFieldReadCount(environment, 'android/os/Build', fieldName,
                'Ljava/lang/String;', 0, "UMP environment original ${fieldName.toLowerCase()}")
        }
        requireCallCount(environment, 'com/google/android/gms/internal/consent_sdk/zzdb',
            'hostOriginalTestEnvironment', '(Z)Z', 0, 'no additional UMP environment guard')

        byte[] testDeviceId = requireClass(
            classEntries, 'com/google/android/gms/internal/consent_sdk/zzct.class'
        )
        requireCallCount(testDeviceId, 'android/provider/Settings$Secure', 'getString',
            '(Landroid/content/ContentResolver;Ljava/lang/String;)Ljava/lang/String;', 1,
            'UMP explicit test-device Android ID hash source')

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

        List<String> residualWholeAarIdentity = []
        List<String> residualTransport = []
        classEntries.each { className, bytes ->
            if (!className.endsWith('.class')) return
            residualWholeAarIdentity.addAll(
                LsapClassPatcher.findResidualGooglePlatformIdentityReads(className, bytes)
            )
            residualTransport.addAll(
                LsapClassPatcher.findResidualGoogleUmpIdentityTransportCalls(className, bytes)
            )
        }
        if (!residualWholeAarIdentity.isEmpty()) {
            throw new GradleException(
                "Unpatched UMP platform identity reads:\n${residualWholeAarIdentity.take(50).join('\n')}"
            )
        }
        if (!residualTransport.isEmpty()) {
            throw new GradleException(
                "Unpatched UMP identity transport calls:\n${residualTransport.take(50).join('\n')}"
            )
        }
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

    private static void requireConstantBooleanMethod(
        byte[] bytes,
        String name,
        String desc,
        boolean expected,
        String label
    ) {
        if (!LsapClassPatcher.isConstantBooleanMethod(bytes, name, desc, expected)) {
            throw new GradleException(
                "Google AAR patch contract mismatch for ${label}: expected constant ${expected}"
            )
        }
    }

    private static void requireIntInstructionOperandCount(
        byte[] bytes,
        String methodName,
        String methodDesc,
        int operand,
        int expected,
        String label
    ) {
        int actual = LsapClassPatcher.countIntInstructionOperandInMethod(
            bytes,
            methodName,
            methodDesc,
            operand
        )
        if (actual != expected) {
            throw new GradleException(
                "Google AAR patch contract mismatch for ${label}: expected ${expected}, found ${actual}"
            )
        }
    }

    private static void requireStaticFieldReadCount(
        byte[] bytes,
        String owner,
        String name,
        String desc,
        int expected,
        String label
    ) {
        int actual = LsapClassPatcher.countStaticFieldReads(bytes, owner, name, desc)
        if (actual != expected) {
            throw new GradleException(
                "Google AAR patch contract mismatch for ${label}: expected ${expected}, found ${actual}"
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
