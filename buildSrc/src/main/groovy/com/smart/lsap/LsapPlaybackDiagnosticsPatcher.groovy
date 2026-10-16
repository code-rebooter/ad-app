package com.smart.lsap

import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.*

/** Adds observations only. The AAR task enables this for ADDY_JAMS alone. */
final class LsapPlaybackDiagnosticsPatcher implements Opcodes {
    private static final String BRIDGE = 'com/smart/android/ad_app/HaierAarRuntimeBridge'
    static final Set<String> TARGETS = [
        'com/tcl/ff/component/vastad/c/e.class',
        'com/tcl/ff/component/vastad/convert/XmlConverter.class',
        'com/tcl/ff/component/vastad/core/g.class',
        'com/tcl/ff/component/vastad/core/h.class',
        'com/itv/component/unified/UnifiedAdPrefs.class',
        'com/itv/component/unified/UnifiedAdSdk$RequestSessionImpl.class',
        'com/itv/component/unified/UnifiedAdSdk$RequestSessionImpl$1.class'
    ] as Set

    static byte[] patch(String entry, byte[] bytes) {
        if (!TARGETS.contains(entry)) return bytes
        ClassNode node = new ClassNode()
        new ClassReader(bytes).accept(node, 0)
        boolean changed = false
        node.methods.each { MethodNode method ->
            method.instructions.toArray().each { AbstractInsnNode insn ->
                if (entry == 'com/tcl/ff/component/vastad/core/g.class' && method.name == '<init>' && insn.opcode == RETURN) {
                    InsnList hook = new InsnList()
                    hook.add(new VarInsnNode(ALOAD, 0))
                    hook.add(new MethodInsnNode(INVOKESTATIC, BRIDGE, 'bindAdDiagnosticController', '(Ljava/lang/Object;)V', false))
                    method.instructions.insertBefore(insn, hook)
                    changed = true
                }
                if (entry == 'com/tcl/ff/component/vastad/core/h.class' && method.name == 'a' &&
                    method.desc == '(Landroid/content/Context;F)Lcom/google/android/exoplayer2/SimpleExoPlayer;' &&
                    insn.opcode == ARETURN) {
                    InsnList hook = new InsnList()
                    hook.add(new InsnNode(DUP))
                    hook.add(new MethodInsnNode(INVOKESTATIC, BRIDGE, 'attachAdPlaybackProbe', '(Ljava/lang/Object;)V', false))
                    method.instructions.insertBefore(insn, hook)
                    changed = true
                }
                if (insn instanceof MethodInsnNode) {
                    MethodInsnNode call = (MethodInsnNode) insn
                    if (entry == 'com/tcl/ff/component/vastad/c/e.class') {
                        if (call.owner == 'okhttp3/ResponseBody' && call.name == 'string' &&
                            call.desc == '()Ljava/lang/String;') {
                            redirect(call, 'readVastResponseBody', '(Lokhttp3/ResponseBody;)Ljava/lang/String;')
                            changed = true
                        } else if (call.owner == 'com/tcl/ff/component/vastad/convert/XmlConverter' &&
                            call.name == 'convert' && call.desc == '(Ljava/lang/String;Ljava/lang/Class;)Ljava/lang/Object;') {
                            redirect(call, 'convertVastResponse', '(Ljava/lang/Object;Ljava/lang/String;Ljava/lang/Class;)Ljava/lang/Object;')
                            changed = true
                        }
                    }
                    if (entry == 'com/tcl/ff/component/vastad/convert/XmlConverter.class' &&
                        call.name == 'printStackTrace' && call.desc == '()V' && call.owner == 'java/lang/Exception') {
                        InsnList hook = new InsnList()
                        hook.add(new InsnNode(DUP))
                        hook.add(new MethodInsnNode(INVOKESTATIC, BRIDGE, 'recordVastParseException', '(Ljava/lang/Throwable;)V', false))
                        method.instructions.insertBefore(call, hook)
                        changed = true
                    }
                    if (entry == 'com/tcl/ff/component/vastad/core/g.class' &&
                        call.name == 'onContainerSizeError' && call.desc == '()V') {
                        method.instructions.insertBefore(call, controllerStage('container_size_error'))
                        changed = true
                    }
                }
                if (entry == 'com/itv/component/unified/UnifiedAdPrefs.class' && insn.opcode == IRETURN &&
                    method.name in ['isReadmeOpenEnabled', 'isLsapPlacementEnabled']) {
                    InsnList hook = new InsnList()
                    hook.add(new InsnNode(DUP))
                    hook.add(new LdcInsnNode(method.name))
                    hook.add(new MethodInsnNode(INVOKESTATIC, BRIDGE, 'recordSdkBranchFlag', '(ZLjava/lang/String;)V', false))
                    method.instructions.insertBefore(insn, hook)
                    changed = true
                }
            }
            if (entry == 'com/tcl/ff/component/vastad/c/e.class' && method.name == 'a' &&
                method.desc == '(Lcom/tcl/ff/component/overseahttp/http/HttpRequester;Lcom/tcl/ff/component/vastad/core/data/a;)Lcom/tcl/ff/component/vastad/loader/xmlmodels/VAST;') {
                InsnList hook = new InsnList()
                hook.add(new MethodInsnNode(INVOKESTATIC, BRIDGE, 'beginVastFetchDiagnostics', '()V', false))
                method.instructions.insert(hook)
                changed = true
            }
            if (entry == 'com/tcl/ff/component/vastad/core/g.class') {
                // The vendor factory caches g; refresh the binding at every request configuration.
                if (method.name == 'a' && method.desc == '(Landroid/content/Context;Lcom/tcl/ff/component/vastad/core/h;Lcom/tcl/ff/component/overseahttp/http/HttpRequester;Lcom/tcl/ff/component/vastad/core/i;Lcom/tcl/ff/component/overseabase/base/device/DeviceInfoGenerator;)V') {
                    InsnList binding = new InsnList()
                    binding.add(new VarInsnNode(ALOAD, 0))
                    binding.add(new MethodInsnNode(INVOKESTATIC, BRIDGE, 'bindAdDiagnosticController', '(Ljava/lang/Object;)V', false))
                    method.instructions.insert(binding)
                    changed = true
                }
                String bridgeMethod = null
                String descriptor = null
                int load = ALOAD
                if (method.name == 'a' && method.desc == '(I)V') {
                    bridgeMethod = 'recordVastRequestError'; descriptor = '(Ljava/lang/Object;I)V'; load = ILOAD
                } else if (method.name == 'onPlayerError' && method.desc == '(Lcom/google/android/exoplayer2/PlaybackException;)V') {
                    bridgeMethod = 'recordAdPlayerError'; descriptor = '(Ljava/lang/Object;Ljava/lang/Throwable;)V'
                } else if (method.name == 'onPlaybackStateChanged' && method.desc == '(I)V') {
                    bridgeMethod = 'recordAdPlayerState'; descriptor = '(Ljava/lang/Object;I)V'; load = ILOAD
                } else if (method.name == 'a' && method.desc == '(Lcom/tcl/ff/component/vastad/core/data/c;Lcom/tcl/ff/component/vastad/core/data/a;)V') {
                    bridgeMethod = 'recordPlayableAdPod'; descriptor = '(Ljava/lang/Object;Ljava/lang/Object;)V'
                }
                if (bridgeMethod != null) {
                    InsnList hook = new InsnList()
                    hook.add(new VarInsnNode(ALOAD, 0))
                    hook.add(new VarInsnNode(load, 1))
                    hook.add(new MethodInsnNode(INVOKESTATIC, BRIDGE, bridgeMethod, descriptor, false))
                    method.instructions.insert(hook)
                    changed = true
                }
                if (method.name == 'onViewDetachedFromWindow' && method.desc == '(Landroid/view/View;)V') {
                    method.instructions.insert(controllerStage('player_container_detached'))
                    changed = true
                }
            }
            if (entry == 'com/itv/component/unified/UnifiedAdSdk$RequestSessionImpl.class' &&
                method.name == 'startBackupAfterReadmeFail' && method.desc == '()V') {
                method.instructions.insert(stage('primary_fallback_requested'))
                changed = true
            }
            if (entry == 'com/itv/component/unified/UnifiedAdSdk$RequestSessionImpl$1.class' &&
                method.name == 'onAdPlayState' && method.desc == '(Lcom/mofeng/ff/component/AdPlayState;)V') {
                InsnList hook = new InsnList()
                hook.add(new VarInsnNode(ALOAD, 1))
                hook.add(new MethodInsnNode(INVOKESTATIC, BRIDGE, 'recordPrimaryAdState', '(Ljava/lang/Object;)V', false))
                method.instructions.insert(hook)
                changed = true
            }
        }
        if (!changed) return bytes
        // Hooks are stack balanced at existing boundaries; retain original stack map frames.
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS)
        node.accept(writer)
        return writer.toByteArray()
    }

    private static void redirect(MethodInsnNode call, String name, String descriptor) {
        call.opcode = INVOKESTATIC
        call.owner = BRIDGE
        call.name = name
        call.desc = descriptor
        call.itf = false
    }

    private static InsnList controllerStage(String name) {
        InsnList hook = new InsnList()
        hook.add(new VarInsnNode(ALOAD, 0))
        hook.add(new LdcInsnNode(name))
        hook.add(new MethodInsnNode(INVOKESTATIC, BRIDGE, 'recordAdControllerStage', '(Ljava/lang/Object;Ljava/lang/String;)V', false))
        return hook
    }

    private static InsnList stage(String name) {
        InsnList hook = new InsnList()
        hook.add(new LdcInsnNode(name))
        hook.add(new MethodInsnNode(INVOKESTATIC, BRIDGE, 'recordAdDiagnosticStage', '(Ljava/lang/String;)V', false))
        return hook
    }
}
