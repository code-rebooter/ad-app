package com.smart.lsap

import com.android.build.api.instrumentation.*
import org.objectweb.asm.*
import org.objectweb.asm.tree.*

/** Observe the pinned TCL XML string immediately before its own converter. */
abstract class GoogleTclFillVisitorFactory implements AsmClassVisitorFactory<InstrumentationParameters.None> {
    private static final String TARGET = 'com/tcl/ff/component/media/ima/tcl/c/f'
    private static final String REQUEST = 'Lcom/tcl/ff/component/media/ima/api/VastRequest;'
    private static final String BRIDGE = 'com/smart/android/ad_app/google/GoogleTclFillBridge'
    @Override boolean isInstrumentable(ClassData data) {
        return data.className == TARGET.replace('/', '.')
    }
    @Override ClassVisitor createClassVisitor(ClassContext context, ClassVisitor next) {
        return new ClassNode(Opcodes.ASM9) {
            @Override void visitEnd() {
                super.visitEnd()
                methods.each { MethodNode method ->
                    if (method.name == 'a' && method.desc == '(Lcom/tcl/ff/component/overseahttp/http/HttpRequester;' + REQUEST + ')Lcom/tcl/ff/component/media/ima/tcl/xmlmodels/VAST;') {
                        InsnList entry = new InsnList()
                        entry.add(new VarInsnNode(Opcodes.ALOAD, 2))
                        entry.add(new MethodInsnNode(Opcodes.INVOKESTATIC, BRIDGE, 'beforeFetch', '(' + REQUEST + ')V', false))
                        method.instructions.insert(entry)
                        method.instructions.toArray().each { insn ->
                            if (insn instanceof MethodInsnNode && insn.owner == TARGET && insn.name == 'b' && insn.desc.endsWith(')Ljava/lang/String;')) {
                                InsnList hook = new InsnList()
                                hook.add(new InsnNode(Opcodes.DUP))
                                hook.add(new VarInsnNode(Opcodes.ALOAD, 2))
                                hook.add(new MethodInsnNode(Opcodes.INVOKESTATIC, BRIDGE, 'onXml', '(Ljava/lang/String;' + REQUEST + ')V', false))
                                method.instructions.insert(insn, hook)
                            }
                        }
                    }
                }
                accept(next)
            }
        }
    }
}
