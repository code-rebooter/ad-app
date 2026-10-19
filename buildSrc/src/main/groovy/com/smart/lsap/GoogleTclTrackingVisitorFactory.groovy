package com.smart.lsap

import com.android.build.api.instrumentation.*
import org.objectweb.asm.*
import org.objectweb.asm.tree.*

/** Macro formatting hooks for IMA-to-TCL channels using the isolated TCL 2.8.02 ABI. */
abstract class GoogleTclTrackingVisitorFactory implements AsmClassVisitorFactory<InstrumentationParameters.None> {
    private static final String BRIDGE = 'com/smart/android/ad_app/google/GoogleTclTrackingBridge'
    @Override boolean isInstrumentable(ClassData data) {
        return data.className in ['com.smart.android.tclad.internal.OriginalBoundary',
                                  'com.tcl.ff.component.media.ima.tcl.b.c',
                                  'com.tcl.ff.component.vastad.core.d.a']
    }
    @Override ClassVisitor createClassVisitor(ClassContext context, ClassVisitor next) {
        return new ClassNode(Opcodes.ASM9) {
            @Override void visitEnd() {
                super.visitEnd()
                methods.each { MethodNode method ->
                    if (name == 'com/smart/android/tclad/internal/OriginalBoundary' && method.name == 'start') {
                        method.instructions.toArray().each { insn ->
                            if (insn.opcode == Opcodes.ARETURN) {
                                InsnList hook = new InsnList()
                                hook.add(new InsnNode(Opcodes.DUP))
                                hook.add(new VarInsnNode(Opcodes.ALOAD, 1))
                                hook.add(new MethodInsnNode(Opcodes.INVOKESTATIC, BRIDGE, 'bindController',
                                    '(Ljava/lang/Object;Lcom/smart/android/tclad/ResolvedAd;)V', false))
                                method.instructions.insertBefore(insn, hook)
                            }
                        }
                    }
                    if (name == 'com/tcl/ff/component/vastad/core/d/a') {
                        method.instructions.toArray().each { insn ->
                            if (insn instanceof MethodInsnNode && insn.name == 'getCurrentPosition' && insn.desc == '()J') {
                                InsnList hook = new InsnList()
                                hook.add(new InsnNode(Opcodes.DUP2))
                                hook.add(new VarInsnNode(Opcodes.ALOAD, 0))
                                hook.add(new MethodInsnNode(Opcodes.INVOKESTATIC, BRIDGE, 'recordPosition',
                                    '(JLjava/lang/Object;)V', false))
                                method.instructions.insert(insn, hook)
                            }
                        }
                    }
                    if (name == 'com/tcl/ff/component/media/ima/tcl/b/c') {
                        if (method.name == '<init>') {
                            method.instructions.toArray().each { insn ->
                                if (insn.opcode == Opcodes.RETURN) {
                                    InsnList hook = new InsnList()
                                    hook.add(new VarInsnNode(Opcodes.ALOAD, 0))
                                    hook.add(new MethodInsnNode(Opcodes.INVOKESTATIC, BRIDGE, 'bindReporter',
                                        '(Ljava/lang/Object;)V', false))
                                    method.instructions.insertBefore(insn, hook)
                                }
                            }
                        }
                        if (method.name == 'a' && method.desc == '()V') {
                            LabelNode original = new LabelNode()
                            InsnList hook = new InsnList()
                            hook.add(new VarInsnNode(Opcodes.ALOAD, 0))
                            hook.add(new VarInsnNode(Opcodes.ALOAD, 0))
                            hook.add(new FieldInsnNode(Opcodes.GETFIELD, name, 'a', 'Ljava/lang/String;'))
                            hook.add(new VarInsnNode(Opcodes.ALOAD, 0))
                            hook.add(new FieldInsnNode(Opcodes.GETFIELD, name, 'b', 'Lcom/tcl/ff/component/overseahttp/http/HttpRequester;'))
                            hook.add(new MethodInsnNode(Opcodes.INVOKESTATIC, BRIDGE, 'dispatchIfNeeded',
                                '(Ljava/lang/Object;Ljava/lang/String;Lcom/tcl/ff/component/overseahttp/http/HttpRequester;)Z', false))
                            hook.add(new JumpInsnNode(Opcodes.IFEQ, original))
                            hook.add(new InsnNode(Opcodes.RETURN))
                            hook.add(original)
                            method.instructions.insert(hook)
                        }
                    }
                }
                accept(next)
            }
        }
    }
}
