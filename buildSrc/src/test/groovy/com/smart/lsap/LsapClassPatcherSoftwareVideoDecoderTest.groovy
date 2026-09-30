package com.smart.lsap

import org.junit.Test
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.MethodInsnNode
import org.objectweb.asm.tree.MethodNode

import static org.junit.Assert.assertArrayEquals
import static org.junit.Assert.assertEquals
import static org.junit.Assert.assertFalse
import static org.junit.Assert.assertNotNull
import static org.junit.Assert.assertTrue
import static org.objectweb.asm.Opcodes.ACC_PRIVATE
import static org.objectweb.asm.Opcodes.ACC_PUBLIC
import static org.objectweb.asm.Opcodes.ACONST_NULL
import static org.objectweb.asm.Opcodes.ALOAD
import static org.objectweb.asm.Opcodes.ARETURN
import static org.objectweb.asm.Opcodes.ASTORE
import static org.objectweb.asm.Opcodes.ICONST_0
import static org.objectweb.asm.Opcodes.ICONST_1
import static org.objectweb.asm.Opcodes.ICONST_2
import static org.objectweb.asm.Opcodes.INVOKESPECIAL
import static org.objectweb.asm.Opcodes.INVOKEVIRTUAL
import static org.objectweb.asm.Opcodes.NEW
import static org.objectweb.asm.Opcodes.POP
import static org.objectweb.asm.Opcodes.PUTFIELD
import static org.objectweb.asm.Opcodes.RETURN
import static org.objectweb.asm.Opcodes.V1_8

class LsapClassPatcherSoftwareVideoDecoderTest {
    private static final String PLAYER_IMPL =
        'com/tcl/ff/component/oversea/uniplayer/player/UniVideoPlayerImpl'
    private static final String PLAYER_BASE = 'com/tcl/ff/component/uniplayer/f/k'
    private static final String BRIDGE =
        'com/smart/android/ad_app/Hq008XhsxAarRuntimeBridge'

    @Test
    void 'hq008 patch keeps default video decoder without using inert track api'() {
        byte[] patched = LsapClassPatcher.patchHq008Parameters(
            "${PLAYER_IMPL}.class",
            playerClass(PLAYER_IMPL)
        )

        Object[] instructions = findCreatePlayer(patched).instructions.toArray()
        boolean hasSoftwareDecoderPolicyCall = instructions.any { instruction ->
            instruction instanceof MethodInsnNode &&
                instruction.owner == BRIDGE &&
                instruction.name == 'shouldForceYtx01SoftwareVideoDecoder' &&
                instruction.desc == '()Z'
        }
        boolean hasDecodeTypeCall = instructions.any { instruction ->
            instruction instanceof MethodInsnNode &&
                instruction.owner == PLAYER_BASE &&
                instruction.name == 'setDecodeType' &&
                instruction.desc == '(II)V'
        }
        boolean hasDisableTrackCall = instructions.any { instruction ->
            instruction instanceof MethodInsnNode &&
                instruction.owner == PLAYER_BASE &&
                instruction.name == 'setDisableTrack' &&
                instruction.desc == '(ZZZ)V'
        }
        assertFalse('Software decoder policy must not be injected', hasSoftwareDecoderPolicyCall)
        assertFalse('TCL setDecodeType must not be injected', hasDecodeTypeCall)
        assertFalse('Inert TCL setDisableTrack call must not be injected', hasDisableTrackCall)
    }

    @Test
    void 'hq008 patch leaves equivalent createPlayer in unrelated class unchanged'() {
        String unrelated = 'example/UnrelatedPlayerImpl'
        byte[] original = playerClass(unrelated)

        byte[] patched = LsapClassPatcher.patchHq008Parameters(
            "${unrelated}.class",
            original
        )

        assertArrayEquals(original, patched)
    }

    @Test
    void 'hq008 patch disables audio in exoplayer track selector for sxk channel'() {
        String player = 'com/tcl/tuniplayer_exo/a'
        byte[] patched = LsapClassPatcher.patchHq008Parameters(
            "${player}.class",
            exoPlayerDelegateClass(player)
        )

        ClassNode node = new ClassNode()
        new ClassReader(patched).accept(node, 0)
        MethodNode method = node.methods.find {
            it.name == 'a' &&
                it.desc == '(Landroid/content/Context;)Lcom/google/android/exoplayer2/ExoPlayer;'
        } as MethodNode
        assertNotNull('Missing ExoPlayer creation method', method)

        Object[] instructions = method.instructions.toArray()
        int policyCall = instructions.findIndexOf { instruction ->
            instruction instanceof MethodInsnNode &&
                instruction.owner == BRIDGE &&
                instruction.name == 'shouldApplySxkPlaybackPatch' &&
                instruction.desc == '()Z'
        }
        int disableAudioCall = instructions.findIndexOf { instruction ->
            instruction instanceof MethodInsnNode &&
                instruction.owner ==
                    'com/google/android/exoplayer2/trackselection/DefaultTrackSelector$Parameters$Builder' &&
                instruction.name == 'setTrackTypeDisabled' &&
                instruction.desc ==
                    '(IZ)Lcom/google/android/exoplayer2/trackselection/DefaultTrackSelector$Parameters$Builder;'
        }

        assertTrue('Missing sxk audio track policy check', policyCall >= 0)
        assertTrue('Missing ExoPlayer audio track disable call', disableAudioCall >= 0)
        assertTrue('Flavor policy must guard audio track disable', policyCall < disableAudioCall)
        assertEquals(ALOAD, instructions[disableAudioCall - 3].opcode)
        assertEquals(3, instructions[disableAudioCall - 3].var)
        assertEquals(ICONST_1, instructions[disableAudioCall - 2].opcode)
        assertEquals(ICONST_1, instructions[disableAudioCall - 1].opcode)
        assertEquals(POP, instructions[disableAudioCall + 1].opcode)
    }

    @Test
    void 'hq008 patch leaves inert outer track state unchanged'() {
        String player = 'com/tcl/uniplayer/tuniplayer/TUniBasePlayer'
        byte[] original = trackPlayerClass(player)

        byte[] patched = LsapClassPatcher.patchHq008Parameters(
            "${player}.class",
            original
        )

        assertArrayEquals(original, patched)
    }

    private static byte[] playerClass(String internalName) {
        ClassWriter writer = new ClassWriter(0)
        writer.visit(V1_8, ACC_PUBLIC, internalName, null, 'java/lang/Object', null)
        writer.visitField(ACC_PRIVATE, 'b', "L${PLAYER_BASE};", null, null).visitEnd()

        MethodVisitor constructor = writer.visitMethod(ACC_PUBLIC, '<init>', '()V', null, null)
        constructor.visitCode()
        constructor.visitVarInsn(ALOAD, 0)
        constructor.visitMethodInsn(INVOKESPECIAL, 'java/lang/Object', '<init>', '()V', false)
        constructor.visitInsn(RETURN)
        constructor.visitMaxs(1, 1)
        constructor.visitEnd()

        MethodVisitor init = writer.visitMethod(ACC_PRIVATE, 'a', '()V', null, null)
        init.visitCode()
        init.visitInsn(RETURN)
        init.visitMaxs(0, 1)
        init.visitEnd()

        MethodVisitor createPlayer = writer.visitMethod(
            ACC_PUBLIC,
            'createPlayer',
            '(Landroid/content/Context;)V',
            null,
            null
        )
        createPlayer.visitCode()
        createPlayer.visitVarInsn(ALOAD, 0)
        createPlayer.visitInsn(ACONST_NULL)
        createPlayer.visitFieldInsn(PUTFIELD, internalName, 'b', "L${PLAYER_BASE};")
        createPlayer.visitVarInsn(ALOAD, 0)
        createPlayer.visitMethodInsn(INVOKESPECIAL, internalName, 'a', '()V', false)
        createPlayer.visitInsn(RETURN)
        createPlayer.visitMaxs(2, 2)
        createPlayer.visitEnd()

        writer.visitEnd()
        return writer.toByteArray()
    }

    private static byte[] exoPlayerDelegateClass(String internalName) {
        ClassWriter writer = new ClassWriter(0)
        writer.visit(V1_8, ACC_PUBLIC, internalName, null, 'java/lang/Object', null)

        MethodVisitor method = writer.visitMethod(
            ACC_PUBLIC,
            'a',
            '(Landroid/content/Context;)Lcom/google/android/exoplayer2/ExoPlayer;',
            null,
            null
        )
        method.visitCode()
        method.visitTypeInsn(NEW, 'com/google/android/exoplayer2/trackselection/DefaultTrackSelector')
        method.visitInsn(org.objectweb.asm.Opcodes.DUP)
        method.visitVarInsn(ALOAD, 1)
        method.visitMethodInsn(
            INVOKESPECIAL,
            'com/google/android/exoplayer2/trackselection/DefaultTrackSelector',
            '<init>',
            '(Landroid/content/Context;)V',
            false
        )
        method.visitVarInsn(ASTORE, 2)
        method.visitVarInsn(ALOAD, 2)
        method.visitMethodInsn(
            INVOKEVIRTUAL,
            'com/google/android/exoplayer2/trackselection/DefaultTrackSelector',
            'buildUponParameters',
            '()Lcom/google/android/exoplayer2/trackselection/DefaultTrackSelector$Parameters$Builder;',
            false
        )
        method.visitVarInsn(ASTORE, 3)
        method.visitVarInsn(ALOAD, 3)
        method.visitInsn(ICONST_1)
        method.visitMethodInsn(
            INVOKEVIRTUAL,
            'com/google/android/exoplayer2/trackselection/DefaultTrackSelector$Parameters$Builder',
            'setAllowAudioMixedChannelCountAdaptiveness',
            '(Z)Lcom/google/android/exoplayer2/trackselection/DefaultTrackSelector$Parameters$Builder;',
            false
        )
        method.visitMethodInsn(
            INVOKEVIRTUAL,
            'com/google/android/exoplayer2/trackselection/DefaultTrackSelector$Parameters$Builder',
            'clearOverrides',
            '()Lcom/google/android/exoplayer2/trackselection/DefaultTrackSelector$Parameters$Builder;',
            false
        )
        method.visitInsn(POP)
        method.visitInsn(ACONST_NULL)
        method.visitInsn(ARETURN)
        method.visitMaxs(3, 4)
        method.visitEnd()

        writer.visitEnd()
        return writer.toByteArray()
    }

    private static byte[] trackPlayerClass(String internalName) {
        ClassWriter writer = new ClassWriter(0)
        writer.visit(V1_8, ACC_PUBLIC, internalName, null, 'java/lang/Object', null)
        writer.visitField(ACC_PRIVATE, 'disableVideoTrack', 'Z', null, null).visitEnd()
        writer.visitField(ACC_PRIVATE, 'disableAudioTrack', 'Z', null, null).visitEnd()
        writer.visitField(ACC_PRIVATE, 'disableSubtitleTrack', 'Z', null, null).visitEnd()

        MethodVisitor method = writer.visitMethod(
            ACC_PUBLIC,
            'setDisableTrack',
            '(ZZZ)V',
            null,
            null
        )
        method.visitCode()
        method.visitInsn(RETURN)
        method.visitMaxs(0, 4)
        method.visitEnd()

        writer.visitEnd()
        return writer.toByteArray()
    }

    private static MethodNode findCreatePlayer(byte[] bytes) {
        ClassNode node = new ClassNode()
        new ClassReader(bytes).accept(node, 0)
        MethodNode method = node.methods.find {
            it.name == 'createPlayer' && it.desc == '(Landroid/content/Context;)V'
        } as MethodNode
        assertNotNull('Missing UniVideoPlayerImpl.createPlayer', method)
        return method
    }
}
