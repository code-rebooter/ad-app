package com.smart.logging

import org.junit.Test
import org.objectweb.asm.*
import org.objectweb.asm.tree.*
import static org.junit.Assert.*

class PropertyLogClassVisitorTest implements Opcodes {
    @Test void 'rewrites log sinks but preserves explicit file streams'() {
        ClassWriter input = new ClassWriter(0)
        input.visit(V1_8, ACC_PUBLIC, 'fixture/Caller', null, 'java/lang/Object', null)
        def m = input.visitMethod(ACC_PUBLIC | ACC_STATIC, 'sample', '(Ljava/lang/String;Ljava/lang/String;Ljava/io/IOException;Ljava/io/PrintWriter;Ljava/util/logging/Logger;Ljava/util/logging/Level;)V', null, null)
        m.visitCode()
        m.visitVarInsn(ALOAD, 0); m.visitVarInsn(ALOAD, 1)
        m.visitMethodInsn(INVOKESTATIC, 'android/util/Log', 'i', '(Ljava/lang/String;Ljava/lang/String;)I', false)
        m.visitInsn(POP)
        m.visitFieldInsn(GETSTATIC, 'java/lang/System', 'out', 'Ljava/io/PrintStream;')
        m.visitInsn(POP)
        m.visitVarInsn(ALOAD, 2)
        m.visitMethodInsn(INVOKEVIRTUAL, 'java/io/IOException', 'printStackTrace', '()V', false)
        m.visitVarInsn(ALOAD, 2); m.visitVarInsn(ALOAD, 3)
        m.visitMethodInsn(INVOKEVIRTUAL, 'java/lang/Throwable', 'printStackTrace', '(Ljava/io/PrintWriter;)V', false)
        m.visitVarInsn(ALOAD, 4); m.visitVarInsn(ALOAD, 5); m.visitVarInsn(ALOAD, 1)
        m.visitMethodInsn(INVOKEVIRTUAL, 'java/util/logging/Logger', 'log', '(Ljava/util/logging/Level;Ljava/lang/String;)V', false)
        m.visitVarInsn(ALOAD, 4); m.visitVarInsn(ALOAD, 5)
        m.visitMethodInsn(INVOKEVIRTUAL, 'java/util/logging/Logger', 'setLevel', '(Ljava/util/logging/Level;)V', false)
        m.visitInsn(RETURN); m.visitMaxs(3, 6)
        m.visitEnd(); input.visitEnd()
        def type = Class.forName('com.smart.logging.PropertyLogClassVisitor')
        ClassWriter output = new ClassWriter(0)
        def visitor = type.getConstructor(ClassVisitor, String).newInstance(output, 'fixture/PropertyLog')
        new ClassReader(input.toByteArray()).accept((ClassVisitor) visitor, 0)
        ClassNode node = new ClassNode(); new ClassReader(output.toByteArray()).accept(node, 0)
        def calls = node.methods[0].instructions.findAll { it instanceof MethodInsnNode }
        assertTrue(calls.any { it.owner == 'fixture/PropertyLog' && it.name == 'i' })
        assertTrue(calls.any { it.owner == 'fixture/PropertyLog' && it.name == 'stdout' })
        assertEquals(2, calls.count { it.owner == 'fixture/PropertyLog' && it.name == 'isEnabled' })
        assertTrue(calls.any { it.owner == 'java/lang/Throwable' && it.desc == '(Ljava/io/PrintWriter;)V' })
        assertTrue(calls.any { it.owner == 'java/util/logging/Logger' && it.name == 'setLevel' })
    }
}
