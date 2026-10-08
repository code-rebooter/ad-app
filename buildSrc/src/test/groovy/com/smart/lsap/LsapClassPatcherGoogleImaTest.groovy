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
import static org.objectweb.asm.Opcodes.ACC_STATIC
import static org.objectweb.asm.Opcodes.ACONST_NULL
import static org.objectweb.asm.Opcodes.ALOAD
import static org.objectweb.asm.Opcodes.IFNONNULL
import static org.objectweb.asm.Opcodes.INVOKESPECIAL
import static org.objectweb.asm.Opcodes.INVOKESTATIC
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
}
