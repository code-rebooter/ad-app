package com.smart.lsap

import groovy.transform.CompileStatic
import org.junit.Test
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.MethodNode
import com.smart.android.ad_app.Hq008XhsxAarRuntimeBridge

import static org.junit.Assert.assertArrayEquals
import static org.junit.Assert.assertEquals
import static org.junit.Assert.assertNotNull
import static org.objectweb.asm.Opcodes.ACC_PUBLIC
import static org.objectweb.asm.Opcodes.ALOAD
import static org.objectweb.asm.Opcodes.ICONST_0
import static org.objectweb.asm.Opcodes.ICONST_1
import static org.objectweb.asm.Opcodes.INVOKESPECIAL
import static org.objectweb.asm.Opcodes.IRETURN
import static org.objectweb.asm.Opcodes.RETURN
import static org.objectweb.asm.Opcodes.V1_8

class LsapClassPatcherAudioFocusTest {
    private static final String TCL_CONFIG_ENTRY =
        'com/tcl/uniplayer/tuniplayer/b.class'

    @Test
    void 'hq008 patch disables TCL 2_8_02 audio focus only for sxk channel'() {
        byte[] original = configClassWithBooleanGetters()
        assertEquals(ICONST_1, constantReturnedBy(original, 'w'))
        assertEquals(ICONST_1, constantReturnedBy(original, 'q'))

        byte[] patched = LsapClassPatcher.patchHq008Parameters(
            TCL_CONFIG_ENTRY,
            original
        )

        Hq008XhsxAarRuntimeBridge.sxkPlaybackPatchEnabled = false
        assertEquals(true, booleanReturnedBy(patched, 'w'))
        Hq008XhsxAarRuntimeBridge.sxkPlaybackPatchEnabled = true
        assertEquals(false, booleanReturnedBy(patched, 'w'))
        assertEquals(ICONST_1, constantReturnedBy(patched, 'q'))
    }

    @Test
    void 'hq008 patch leaves matching method name in unrelated class unchanged'() {
        byte[] original = configClassWithBooleanGetters()

        byte[] patched = LsapClassPatcher.patchHq008Parameters(
            'example/UnrelatedConfig.class',
            original
        )

        assertArrayEquals(original, patched)
    }

    private static byte[] configClassWithBooleanGetters() {
        ClassWriter writer = new ClassWriter(0)
        writer.visit(
            V1_8,
            ACC_PUBLIC,
            'com/tcl/uniplayer/tuniplayer/b',
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

        addTrueGetter(writer, 'w')
        addTrueGetter(writer, 'q')
        writer.visitEnd()
        return writer.toByteArray()
    }

    private static void addTrueGetter(ClassWriter writer, String name) {
        MethodVisitor getter = writer.visitMethod(ACC_PUBLIC, name, '()Z', null, null)
        getter.visitCode()
        getter.visitInsn(ICONST_1)
        getter.visitInsn(IRETURN)
        getter.visitMaxs(1, 1)
        getter.visitEnd()
    }

    private static int constantReturnedBy(byte[] bytes, String methodName) {
        ClassNode node = new ClassNode()
        new ClassReader(bytes).accept(node, 0)
        MethodNode method = node.methods.find {
            it.name == methodName && it.desc == '()Z'
        } as MethodNode
        assertNotNull("Missing ${methodName}()Z", method)

        List<Integer> opcodes = method.instructions.toArray()
            .findAll { it.opcode >= 0 }
            .collect { it.opcode }
        assertEquals("Unexpected ${methodName}()Z body", 2, opcodes.size())
        assertEquals(IRETURN, opcodes.last().intValue())
        return opcodes.first()
    }

    private static boolean booleanReturnedBy(byte[] bytes, String methodName) {
        Class<?> type = new ByteArrayClassLoader(
            LsapClassPatcherAudioFocusTest.class.getClassLoader()
        ).define(bytes)
        Object instance = type.getDeclaredConstructor().newInstance()
        return ((Boolean) type.getMethod(methodName).invoke(instance)).booleanValue()
    }

    @CompileStatic
    static final class ByteArrayClassLoader extends ClassLoader {
        ByteArrayClassLoader(ClassLoader parent) {
            super(parent)
        }

        Class<?> define(byte[] bytes) {
            return defineClass(null, bytes, 0, bytes.length)
        }
    }
}
