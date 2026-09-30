package com.smart.lsap

import groovy.transform.CompileStatic
import org.junit.Test
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.MethodVisitor
import com.smart.android.ad_app.Hq008XhsxAarRuntimeBridge

import static org.junit.Assert.assertArrayEquals
import static org.junit.Assert.assertEquals
import static org.objectweb.asm.Opcodes.ACC_PRIVATE
import static org.objectweb.asm.Opcodes.ACC_PUBLIC
import static org.objectweb.asm.Opcodes.ALOAD
import static org.objectweb.asm.Opcodes.GETFIELD
import static org.objectweb.asm.Opcodes.ILOAD
import static org.objectweb.asm.Opcodes.INVOKESPECIAL
import static org.objectweb.asm.Opcodes.IRETURN
import static org.objectweb.asm.Opcodes.PUTFIELD
import static org.objectweb.asm.Opcodes.RETURN
import static org.objectweb.asm.Opcodes.V1_8

class LsapClassPatcherSurfaceTypeTest {
    private static final String TCL_PLAYER_INTERNAL_NAME =
        'com/tcl/ff/component/uniplayer/f/k'
    private static final String TCL_PLAYER_ENTRY = "${TCL_PLAYER_INTERNAL_NAME}.class"

    @Test
    void 'hq008 patch maps surface type zero to texture view only for sxk channel'() {
        byte[] original = surfaceTypeClass(TCL_PLAYER_INTERNAL_NAME)
        assertEquals(0, setAndGetSurfaceType(original, 0))

        byte[] patched = LsapClassPatcher.patchHq008Parameters(TCL_PLAYER_ENTRY, original)

        Hq008XhsxAarRuntimeBridge.sxkPlaybackPatchEnabled = false
        assertEquals(0, setAndGetSurfaceType(patched, 0))
        Hq008XhsxAarRuntimeBridge.sxkPlaybackPatchEnabled = true
        assertEquals(1, setAndGetSurfaceType(patched, 0))
        assertEquals(1, setAndGetSurfaceType(patched, 1))
        assertEquals(-1, setAndGetSurfaceType(patched, -1))
    }

    @Test
    void 'hq008 patch leaves matching setter in unrelated class byte for byte unchanged'() {
        String internalName = 'example/UnrelatedSurfaceType'
        byte[] original = surfaceTypeClass(internalName)

        byte[] patched = LsapClassPatcher.patchHq008Parameters(
            "${internalName}.class",
            original
        )

        assertArrayEquals(original, patched)
    }

    private static byte[] surfaceTypeClass(String internalName) {
        ClassWriter writer = new ClassWriter(0)
        writer.visit(V1_8, ACC_PUBLIC, internalName, null, 'java/lang/Object', null)
        writer.visitField(ACC_PRIVATE, 'surfaceType', 'I', null, null).visitEnd()

        MethodVisitor constructor = writer.visitMethod(ACC_PUBLIC, '<init>', '()V', null, null)
        constructor.visitCode()
        constructor.visitVarInsn(ALOAD, 0)
        constructor.visitMethodInsn(INVOKESPECIAL, 'java/lang/Object', '<init>', '()V', false)
        constructor.visitInsn(RETURN)
        constructor.visitMaxs(1, 1)
        constructor.visitEnd()

        MethodVisitor setter = writer.visitMethod(
            ACC_PUBLIC,
            'setSurfaceType',
            '(IZ)V',
            null,
            null
        )
        setter.visitCode()
        setter.visitVarInsn(ALOAD, 0)
        setter.visitVarInsn(ILOAD, 1)
        setter.visitFieldInsn(PUTFIELD, internalName, 'surfaceType', 'I')
        setter.visitInsn(RETURN)
        setter.visitMaxs(2, 3)
        setter.visitEnd()

        MethodVisitor getter = writer.visitMethod(
            ACC_PUBLIC,
            'getSurfaceType',
            '()I',
            null,
            null
        )
        getter.visitCode()
        getter.visitVarInsn(ALOAD, 0)
        getter.visitFieldInsn(GETFIELD, internalName, 'surfaceType', 'I')
        getter.visitInsn(IRETURN)
        getter.visitMaxs(1, 1)
        getter.visitEnd()

        writer.visitEnd()
        return writer.toByteArray()
    }

    private static int setAndGetSurfaceType(byte[] bytes, int surfaceType) {
        Class<?> type = new ByteArrayClassLoader(
            LsapClassPatcherSurfaceTypeTest.class.getClassLoader()
        ).define(bytes)
        Object instance = type.getDeclaredConstructor().newInstance()
        type.getMethod('setSurfaceType', Integer.TYPE, Boolean.TYPE)
            .invoke(instance, surfaceType, false)
        return ((Integer) type.getMethod('getSurfaceType').invoke(instance)).intValue()
    }

    @CompileStatic
    private static final class ByteArrayClassLoader extends ClassLoader {
        ByteArrayClassLoader(ClassLoader parent) {
            super(parent)
        }

        Class<?> define(byte[] bytes) {
            return defineClass(null, bytes, 0, bytes.length)
        }
    }
}
