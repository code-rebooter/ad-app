package com.smart.lsap

import org.junit.Test
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.FieldInsnNode
import org.objectweb.asm.tree.JumpInsnNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.MethodNode

import static org.junit.Assert.assertArrayEquals
import static org.junit.Assert.assertNotNull
import static org.junit.Assert.assertTrue
import static org.objectweb.asm.Opcodes.ACC_PROTECTED
import static org.objectweb.asm.Opcodes.ACC_PRIVATE
import static org.objectweb.asm.Opcodes.ACC_PUBLIC
import static org.objectweb.asm.Opcodes.ALOAD
import static org.objectweb.asm.Opcodes.DUP
import static org.objectweb.asm.Opcodes.GETFIELD
import static org.objectweb.asm.Opcodes.ICONST_0
import static org.objectweb.asm.Opcodes.ICONST_1
import static org.objectweb.asm.Opcodes.IFEQ
import static org.objectweb.asm.Opcodes.IRETURN
import static org.objectweb.asm.Opcodes.ISTORE
import static org.objectweb.asm.Opcodes.LCONST_0
import static org.objectweb.asm.Opcodes.LLOAD
import static org.objectweb.asm.Opcodes.LSTORE
import static org.objectweb.asm.Opcodes.POP
import static org.objectweb.asm.Opcodes.POP2
import static org.objectweb.asm.Opcodes.RETURN
import static org.objectweb.asm.Opcodes.V1_8

class LsapClassPatcherVideoFrameThrottleTest {
    private static final String RENDERER =
        'com/google/android/exoplayer2/video/MediaCodecVideoRenderer'
    private static final String RENDERER_ENTRY = "${RENDERER}.class"
    private static final String CODEC_ADAPTER =
        'com/google/android/exoplayer2/mediacodec/MediaCodecAdapter'
    private static final String FORMAT = 'com/google/android/exoplayer2/Format'
    private static final String PROCESS_DESC =
        "(JJL${CODEC_ADAPTER};Ljava/nio/ByteBuffer;IIIJZZL${FORMAT};)Z"
    private static final String BRIDGE =
        'com/smart/android/ad_app/Hq008XhsxAarRuntimeBridge'

    @Test
    void 'hq008 patch throttles TCL ExoPlayer output frames through runtime bridge'() {
        byte[] patched = LsapClassPatcher.patchHq008Parameters(
            RENDERER_ENTRY,
            rendererClass(RENDERER)
        )

        MethodNode method = findProcessMethod(patched)
        assertTrue(method.instructions.toArray().any { instruction ->
                instruction instanceof MethodInsnNode &&
                instruction.owner == BRIDGE &&
                instruction.name == 'shouldRenderSxkVideoFrame' &&
                instruction.desc == '(Ljava/lang/Object;J)Z'
        })
        assertTrue(method.instructions.toArray().any { instruction ->
            instruction instanceof MethodInsnNode &&
                instruction.owner == RENDERER &&
                instruction.name == 'skipOutputBuffer' &&
                instruction.desc == "(L${CODEC_ADAPTER};IJ)V"
        })
    }

    @Test
    void 'hq008 patch always renders the first frame after renderer reset'() {
        byte[] patched = LsapClassPatcher.patchHq008Parameters(
            RENDERER_ENTRY,
            rendererClass(RENDERER)
        )

        Object[] instructions = findProcessMethod(patched).instructions.toArray()
        int firstFrameRead = instructions.findIndexOf { instruction ->
            instruction instanceof FieldInsnNode &&
                instruction.opcode == GETFIELD &&
                instruction.owner == RENDERER &&
                instruction.name == 'renderedFirstFrameAfterReset' &&
                instruction.desc == 'Z'
        }
        int bridgeCall = instructions.findIndexOf { instruction ->
            instruction instanceof MethodInsnNode &&
                instruction.owner == BRIDGE &&
                instruction.name == 'shouldRenderSxkVideoFrame'
        }

        assertTrue('Missing renderedFirstFrameAfterReset check', firstFrameRead >= 0)
        assertTrue(
            'First-frame check must branch directly to normal rendering',
            instructions[firstFrameRead + 1] instanceof JumpInsnNode &&
                instructions[firstFrameRead + 1].opcode == IFEQ
        )
        assertTrue('First-frame check must run before throttling', firstFrameRead < bridgeCall)
    }

    @Test
    void 'hq008 patch leaves equivalent method in unrelated class unchanged'() {
        String unrelated = 'example/UnrelatedVideoRenderer'
        byte[] original = rendererClass(unrelated)

        byte[] patched = LsapClassPatcher.patchHq008Parameters(
            "${unrelated}.class",
            original
        )

        assertArrayEquals(original, patched)
    }

    private static byte[] rendererClass(String internalName) {
        ClassWriter writer = new ClassWriter(0)
        writer.visit(V1_8, ACC_PUBLIC, internalName, null, 'java/lang/Object', null)
        writer.visitField(
            ACC_PRIVATE,
            'renderedFirstFrameAfterReset',
            'Z',
            null,
            null
        ).visitEnd()

        MethodVisitor getState = writer.visitMethod(
            ACC_PROTECTED,
            'getState',
            '()I',
            null,
            null
        )
        getState.visitCode()
        getState.visitInsn(ICONST_0)
        getState.visitInsn(IRETURN)
        getState.visitMaxs(1, 1)
        getState.visitEnd()

        MethodVisitor skip = writer.visitMethod(
            ACC_PROTECTED,
            'skipOutputBuffer',
            "(L${CODEC_ADAPTER};IJ)V",
            null,
            null
        )
        skip.visitCode()
        skip.visitInsn(RETURN)
        skip.visitMaxs(0, 5)
        skip.visitEnd()

        MethodVisitor process = writer.visitMethod(
            ACC_PROTECTED,
            'processOutputBuffer',
            PROCESS_DESC,
            null,
            null
        )
        process.visitCode()
        process.visitInsn(LCONST_0)
        process.visitVarInsn(LSTORE, 8)
        process.visitInsn(ICONST_1)
        process.visitVarInsn(ISTORE, 6)
        process.visitVarInsn(ALOAD, 0)
        process.visitInsn(DUP)
        process.visitVarInsn(LLOAD, 1)
        process.visitMethodInsn(
            org.objectweb.asm.Opcodes.INVOKESTATIC,
            'android/os/SystemClock',
            'elapsedRealtime',
            '()J',
            false
        )
        process.visitInsn(POP2)
        process.visitInsn(POP2)
        process.visitInsn(POP)
        process.visitInsn(POP)
        process.visitInsn(ICONST_0)
        process.visitInsn(IRETURN)
        process.visitMaxs(6, 15)
        process.visitEnd()

        writer.visitEnd()
        return writer.toByteArray()
    }

    private static MethodNode findProcessMethod(byte[] bytes) {
        ClassNode node = new ClassNode()
        new ClassReader(bytes).accept(node, 0)
        MethodNode method = node.methods.find {
            it.name == 'processOutputBuffer' && it.desc == PROCESS_DESC
        } as MethodNode
        assertNotNull('Missing MediaCodecVideoRenderer.processOutputBuffer', method)
        return method
    }
}
