package com.smart.lsap

import org.junit.Test
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.tree.AbstractInsnNode
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.MethodNode

import static org.junit.Assert.assertEquals
import static org.objectweb.asm.Opcodes.ACC_PUBLIC
import static org.objectweb.asm.Opcodes.ACC_PRIVATE
import static org.objectweb.asm.Opcodes.ACC_STATIC
import static org.objectweb.asm.Opcodes.ACONST_NULL
import static org.objectweb.asm.Opcodes.ALOAD
import static org.objectweb.asm.Opcodes.ARETURN
import static org.objectweb.asm.Opcodes.ICONST_0
import static org.objectweb.asm.Opcodes.IFEQ
import static org.objectweb.asm.Opcodes.IFNONNULL
import static org.objectweb.asm.Opcodes.INVOKESPECIAL
import static org.objectweb.asm.Opcodes.INVOKESTATIC
import static org.objectweb.asm.Opcodes.INVOKEVIRTUAL
import static org.objectweb.asm.Opcodes.POP
import static org.objectweb.asm.Opcodes.RETURN
import static org.objectweb.asm.Opcodes.V1_8

class LsapClassPatcherGoogleImaTest {
    private static final String ENTRY =
        'com/google/ads/interactivemedia/v3/internal/zzdz.class'

    @Test
    void 'omid reason replacement consumes the original view argument'() {
        byte[] original = zzdzClass()

        byte[] patched = LsapClassPatcher.patchGoogleImaParameters(ENTRY, original)

        ClassNode node = new ClassNode()
        new ClassReader(patched).accept(node, 0)
        MethodNode method = node.methods.find {
            it.name == 'zza' &&
                it.desc == '(Landroid/view/View;Lcom/google/ads/interactivemedia/v3/internal/zzdb;Lorg/json/JSONObject;Z)V'
        } as MethodNode
        List<Integer> opcodes = method.instructions.toArray()
            .findAll { AbstractInsnNode instruction -> instruction.opcode >= 0 }
            .collect { AbstractInsnNode instruction -> instruction.opcode }

        assertEquals(
            [ALOAD, POP, ACONST_NULL, IFNONNULL, RETURN, RETURN] as List<Integer>,
            opcodes
        )
    }

    @Test
    void 'companion webview navigation applies the runtime user agent bridge'() {
        String entry = 'com/google/ads/interactivemedia/v3/impl/zzap.class'

        byte[] patched = LsapClassPatcher.patchGoogleImaParameters(
            entry,
            companionWebViewClass()
        )

        String bridge = 'com/smart/android/ad_app/Hq008XhsxAarRuntimeBridge'
        assertEquals(1, LsapClassPatcher.countMethodCalls(
            patched,
            bridge,
            'loadWebViewUrl',
            '(Landroid/webkit/WebView;Ljava/lang/String;)V'
        ))
        assertEquals(1, LsapClassPatcher.countMethodCalls(
            patched,
            bridge,
            'loadWebViewData',
            '(Landroid/webkit/WebView;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V'
        ))
    }

    @Test
    void 'identifier builder overrides the SDK idless early return'() {
        String entry = 'com/google/ads/interactivemedia/v3/internal/zzez.class'

        byte[] patched = LsapClassPatcher.patchGoogleImaParameters(
            entry,
            identifierBuilderClass()
        )

        assertEquals(1, LsapClassPatcher.countMethodCalls(
            patched,
            'com/smart/android/googleima/internal/GoogleImaRuntimeBridge',
            'forceIdentifierInfoCollection',
            '(Z)Z'
        ))
    }

    @Test
    void 'media3 ima exposes the current ads manager progress without reflection'() {
        byte[] adTagLoader = LsapClassPatcher.patchGoogleMedia3ImaVolume(
            'androidx/media3/exoplayer/ima/AdTagLoader.class',
            classWithReferenceField(
                'androidx/media3/exoplayer/ima/AdTagLoader',
                'adsManager',
                'Lcom/google/ads/interactivemedia/v3/api/AdsManager;'
            )
        )
        byte[] imaAdsLoader = LsapClassPatcher.patchGoogleMedia3ImaVolume(
            'androidx/media3/exoplayer/ima/ImaAdsLoader.class',
            classWithReferenceField(
                'androidx/media3/exoplayer/ima/ImaAdsLoader',
                'currentAdTagLoader',
                'Landroidx/media3/exoplayer/ima/AdTagLoader;'
            )
        )

        assertEquals(1, LsapClassPatcher.countMethodCalls(
            adTagLoader,
            'com/google/ads/interactivemedia/v3/api/BaseManager',
            'getAdProgressInfo',
            '()Lcom/google/ads/interactivemedia/v3/api/AdProgressInfo;'
        ))
        assertEquals(1, LsapClassPatcher.countMethodCalls(
            imaAdsLoader,
            'androidx/media3/exoplayer/ima/AdTagLoader',
            'getControlledAdProgressInfo',
            '()Lcom/google/ads/interactivemedia/v3/api/AdProgressInfo;'
        ))
    }

    private static byte[] zzdzClass() {
        ClassWriter writer = new ClassWriter(0)
        writer.visit(
            V1_8,
            ACC_PUBLIC,
            'com/google/ads/interactivemedia/v3/internal/zzdz',
            null,
            'java/lang/Object',
            null
        )

        MethodVisitor constructor = writer.visitMethod(ACC_PUBLIC, '<init>', '()V', null, null)
        constructor.visitCode()
        constructor.visitVarInsn(ALOAD, 0)
        constructor.visitMethodInsn(INVOKESPECIAL, 'java/lang/Object', '<init>', '()V', false)
        constructor.visitInsn(RETURN)
        constructor.visitMaxs(1, 1)
        constructor.visitEnd()

        MethodVisitor method = writer.visitMethod(
            ACC_PUBLIC,
            'zza',
            '(Landroid/view/View;Lcom/google/ads/interactivemedia/v3/internal/zzdb;Lorg/json/JSONObject;Z)V',
            null,
            null
        )
        method.visitCode()
        method.visitVarInsn(ALOAD, 1)
        method.visitMethodInsn(
            INVOKESTATIC,
            'com/google/ads/interactivemedia/v3/internal/zzdq',
            'zza',
            '(Landroid/view/View;)Ljava/lang/String;',
            false
        )
        org.objectweb.asm.Label hasReason = new org.objectweb.asm.Label()
        method.visitJumpInsn(IFNONNULL, hasReason)
        method.visitInsn(RETURN)
        method.visitLabel(hasReason)
        method.visitInsn(RETURN)
        method.visitMaxs(1, 5)
        method.visitEnd()
        writer.visitEnd()
        return writer.toByteArray()
    }

    private static byte[] companionWebViewClass() {
        String owner = 'com/google/ads/interactivemedia/v3/impl/zzap'
        ClassWriter writer = new ClassWriter(0)
        writer.visit(V1_8, ACC_PUBLIC, owner, null, 'android/webkit/WebView', null)

        MethodVisitor method = writer.visitMethod(
            ACC_PUBLIC | ACC_STATIC,
            'zza',
            "(L${owner};Ljava/lang/String;Ljava/lang/String;)V",
            null,
            null
        )
        method.visitCode()
        method.visitVarInsn(ALOAD, 0)
        method.visitVarInsn(ALOAD, 1)
        method.visitMethodInsn(
            org.objectweb.asm.Opcodes.INVOKEVIRTUAL,
            owner,
            'loadUrl',
            '(Ljava/lang/String;)V',
            false
        )
        method.visitVarInsn(ALOAD, 0)
        method.visitVarInsn(ALOAD, 2)
        method.visitLdcInsn('text/html')
        method.visitLdcInsn('base64')
        method.visitMethodInsn(
            org.objectweb.asm.Opcodes.INVOKEVIRTUAL,
            owner,
            'loadData',
            '(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V',
            false
        )
        method.visitInsn(RETURN)
        method.visitMaxs(4, 3)
        method.visitEnd()
        writer.visitEnd()
        return writer.toByteArray()
    }

    private static byte[] identifierBuilderClass() {
        String owner = 'com/google/ads/interactivemedia/v3/internal/zzez'
        String request = 'com/google/ads/interactivemedia/v3/api/BaseRequest'
        String state = 'com/google/ads/interactivemedia/v3/internal/zzeu'
        String result = 'com/google/ads/interactivemedia/v3/impl/data/zzbn'
        ClassWriter writer = new ClassWriter(0)
        writer.visit(V1_8, ACC_PUBLIC, owner, null, 'java/lang/Object', null)

        MethodVisitor idless = writer.visitMethod(
            ACC_PRIVATE,
            'zzb',
            "(L${state};)Ljava/lang/Boolean;",
            null,
            null
        )
        idless.visitCode()
        idless.visitInsn(ICONST_0)
        idless.visitMethodInsn(
            INVOKESTATIC,
            'java/lang/Boolean',
            'valueOf',
            '(Z)Ljava/lang/Boolean;',
            false
        )
        idless.visitInsn(ARETURN)
        idless.visitMaxs(1, 2)
        idless.visitEnd()

        MethodVisitor method = writer.visitMethod(
            ACC_PUBLIC,
            'zza',
            "(L${request};L${state};Ljava/lang/String;)L${result};",
            null,
            null
        )
        method.visitCode()
        method.visitVarInsn(ALOAD, 0)
        method.visitVarInsn(ALOAD, 2)
        method.visitMethodInsn(
            INVOKESPECIAL,
            owner,
            'zzb',
            "(L${state};)Ljava/lang/Boolean;",
            false
        )
        method.visitMethodInsn(
            INVOKEVIRTUAL,
            'java/lang/Boolean',
            'booleanValue',
            '()Z',
            false
        )
        org.objectweb.asm.Label continueIdentity = new org.objectweb.asm.Label()
        method.visitJumpInsn(IFEQ, continueIdentity)
        method.visitInsn(ACONST_NULL)
        method.visitInsn(ARETURN)
        method.visitLabel(continueIdentity)
        method.visitInsn(ACONST_NULL)
        method.visitInsn(ARETURN)
        method.visitMaxs(2, 4)
        method.visitEnd()
        writer.visitEnd()
        return writer.toByteArray()
    }

    private static byte[] classWithReferenceField(
        String owner,
        String fieldName,
        String fieldDescriptor
    ) {
        ClassWriter writer = new ClassWriter(0)
        writer.visit(V1_8, ACC_PUBLIC, owner, null, 'java/lang/Object', null)
        writer.visitField(ACC_PRIVATE, fieldName, fieldDescriptor, null, null).visitEnd()

        MethodVisitor constructor = writer.visitMethod(ACC_PUBLIC, '<init>', '()V', null, null)
        constructor.visitCode()
        constructor.visitVarInsn(ALOAD, 0)
        constructor.visitMethodInsn(INVOKESPECIAL, 'java/lang/Object', '<init>', '()V', false)
        constructor.visitInsn(RETURN)
        constructor.visitMaxs(1, 1)
        constructor.visitEnd()
        writer.visitEnd()
        return writer.toByteArray()
    }
}
