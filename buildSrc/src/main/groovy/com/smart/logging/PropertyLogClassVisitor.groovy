package com.smart.logging

import org.objectweb.asm.*
import org.objectweb.asm.tree.*

/** Gate diagnostic sinks even when vendor code has its own always-on debug switch. */
class PropertyLogClassVisitor extends ClassVisitor implements Opcodes {
    private final String bridge
    private String classOwner
    private static final Set<String> LOG_METHODS = ['v','d','i','w','e','wtf','println','isLoggable'] as Set
    private static final Set<String> JUL_METHODS = [
        'log','logp','logrb','severe','warning','info','config','fine','finer','finest',
        'entering','exiting','throwing'
    ] as Set

    PropertyLogClassVisitor(ClassVisitor next, String bridge) {
        super(ASM9, next)
        this.bridge = bridge
    }

    static boolean isBridge(String className) {
        String name = className.replace('/', '.')
        return name.endsWith('.logging.PropertyLog') || name.contains('.logging.PropertyLog$')
    }

    static byte[] patch(byte[] original, String bridge) {
        ClassReader reader = new ClassReader(original)
        if (isBridge(reader.className)) return original
        // Embedded JARs keep their original frames; the visitor emits full frames
        // at its new branch targets without resolving vendor inheritance trees.
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS)
        reader.accept(new PropertyLogClassVisitor(writer, bridge), ClassReader.EXPAND_FRAMES)
        return writer.toByteArray()
    }

    @Override void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
        classOwner = name
        super.visit(version, access, name, signature, superName, interfaces)
    }

    @Override MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
        MethodVisitor next = super.visitMethod(access, name, descriptor, signature, exceptions)
        MethodNode buffer = new MethodNode(ASM9, access, name, descriptor, signature, exceptions) {
            @Override void visitEnd() {
                FrameNode previousFrame = null
                for (AbstractInsnNode instruction : instructions.toArray()) {
                    if (instruction.opcode >= 0) previousFrame = null
                    if (instruction instanceof FrameNode) {
                        if (previousFrame != null) instructions.remove(previousFrame)
                        previousFrame = (FrameNode) instruction
                    }
                }
                accept(next)
            }
        }
        return new GatedMethodVisitor(classOwner, access, name, descriptor, buffer, bridge)
    }

    private static class GatedMethodVisitor extends org.objectweb.asm.commons.AnalyzerAdapter {
        private final String bridge
        GatedMethodVisitor(String owner, int access, String name, String descriptor, MethodVisitor next, String bridge) {
            super(ASM9, owner, access, name, descriptor, next)
            this.bridge = bridge
        }

        private static Object[] frameValues(List values) {
            List result = []
            for (int i = 0; i < values.size(); i++) {
                Object value = values[i]
                result.add(value)
                if (value == LONG || value == DOUBLE) i++
            }
            return result.toArray()
        }

        @Override void visitFieldInsn(int opcode, String owner, String field, String desc) {
            if (opcode == GETSTATIC && owner == 'java/lang/System' && field in ['out','err'] && desc == 'Ljava/io/PrintStream;') {
                super.visitMethodInsn(INVOKESTATIC, bridge, field == 'out' ? 'stdout' : 'stderr', '()Ljava/io/PrintStream;', false)
            } else {
                super.visitFieldInsn(opcode, owner, field, desc)
            }
        }

        @Override void visitMethodInsn(int opcode, String owner, String method, String desc, boolean isInterface) {
            if (opcode == INVOKESTATIC && owner == 'android/util/Log' && LOG_METHODS.contains(method)) {
                super.visitMethodInsn(opcode, bridge, method, desc, false)
                return
            }
            boolean stackTrace = opcode == INVOKEVIRTUAL && method == 'printStackTrace' && desc == '()V'
            boolean javaLogger = opcode == INVOKEVIRTUAL && owner == 'java/util/logging/Logger' && JUL_METHODS.contains(method)
            if (stackTrace || javaLogger) {
                // Guard the call while preserving evaluation of its arguments.
                List beforeLocals = locals == null ? null : new ArrayList(locals)
                List beforeStack = stack == null ? null : new ArrayList(stack)
                Label enabled = new Label(), end = new Label()
                super.visitMethodInsn(INVOKESTATIC, bridge, 'isEnabled', '()Z', false)
                super.visitJumpInsn(IFNE, enabled)
                Type[] arguments = Type.getArgumentTypes(desc)
                for (int i = arguments.length - 1; i >= 0; i--) {
                    super.visitInsn(arguments[i].size == 2 ? POP2 : POP)
                }
                super.visitInsn(POP) // receiver
                List afterStack = stack == null ? null : new ArrayList(stack)
                super.visitJumpInsn(GOTO, end)
                super.visitLabel(enabled)
                if (beforeLocals != null && beforeStack != null) {
                    super.visitFrame(F_NEW, frameValues(beforeLocals).length, frameValues(beforeLocals), frameValues(beforeStack).length, frameValues(beforeStack))
                }
                super.visitMethodInsn(opcode, owner, method, desc, isInterface)
                super.visitLabel(end)
                if (beforeLocals != null && afterStack != null) {
                    super.visitFrame(F_NEW, frameValues(beforeLocals).length, frameValues(beforeLocals), frameValues(afterStack).length, frameValues(afterStack))
                }
                return
            }
            super.visitMethodInsn(opcode, owner, method, desc, isInterface)
        }
    }
}
