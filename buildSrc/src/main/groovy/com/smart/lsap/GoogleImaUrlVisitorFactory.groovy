package com.smart.lsap

import com.android.build.api.instrumentation.*
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.*

/** URL handoff hooks for the pinned IMA 3.35.1 in IMA-to-TCL channels. */
abstract class GoogleImaUrlVisitorFactory implements AsmClassVisitorFactory<InstrumentationParameters.None> {
    private static final String BRIDGE = 'com/smart/android/ad_app/google/GoogleImaUrlBridge'
    private static final String CORE = 'com/google/ads/interactivemedia/v3/impl/zzba'

    @Override boolean isInstrumentable(ClassData data) {
        return data.className == 'com.google.ads.interactivemedia.v3.impl.zzy' ||
                data.className == 'com.google.ads.interactivemedia.v3.impl.zzba'
    }

    @Override ClassVisitor createClassVisitor(ClassContext context, ClassVisitor next) {
        return new ClassNode(Opcodes.ASM9) {
            @Override void visitEnd() {
                super.visitEnd()
                methods.each { MethodNode method ->
                    InsnList hook = new InsnList()
                    LabelNode proceed = new LabelNode()
                    if (name == 'com/google/ads/interactivemedia/v3/impl/zzy' &&
                            method.name == 'zzp' && method.desc ==
                            '(Lcom/google/ads/interactivemedia/v3/internal/zzuu;Lcom/google/ads/interactivemedia/v3/api/AdDisplayContainer;Lcom/google/ads/interactivemedia/v3/api/AdsRequest;Ljava/lang/String;)V') {
                        hook.add(new VarInsnNode(Opcodes.ALOAD, 3))
                        hook.add(new VarInsnNode(Opcodes.ALOAD, 4))
                        hook.add(new VarInsnNode(Opcodes.ALOAD, 0))
                        hook.add(new FieldInsnNode(Opcodes.GETFIELD, name, 'zzc', 'L' + CORE + ';'))
                        hook.add(new MethodInsnNode(Opcodes.INVOKESTATIC, BRIDGE, 'bindRequest',
                                '(Lcom/google/ads/interactivemedia/v3/api/AdsRequest;Ljava/lang/String;Ljava/lang/Object;)Z', false))
                        hook.add(new JumpInsnNode(Opcodes.IFNE, proceed))
                    } else if (name == CORE && method.name == 'zza' && method.desc ==
                            '(Lcom/google/ads/interactivemedia/v3/impl/JavaScriptMessage;)V') {
                        hook.add(new VarInsnNode(Opcodes.ALOAD, 0))
                        hook.add(new VarInsnNode(Opcodes.ALOAD, 1))
                        hook.add(new MethodInsnNode(Opcodes.INVOKESTATIC, BRIDGE, 'consumeNativeRequest',
                                '(Ljava/lang/Object;Lcom/google/ads/interactivemedia/v3/impl/JavaScriptMessage;)Z', false))
                        hook.add(new JumpInsnNode(Opcodes.IFEQ, proceed))
                    } else {
                        return
                    }
                    hook.add(new InsnNode(Opcodes.RETURN))
                    hook.add(proceed)
                    method.instructions.insert(hook)
                }
                accept(next)
            }
        }
    }
}
