package com.smart.lsap

import org.junit.Test
import org.objectweb.asm.*
import org.objectweb.asm.tree.*
import java.nio.file.Files
import static org.junit.Assert.*

class TclUniversalRuntimePatcherTest implements Opcodes {
    @Test void 'Google cannot enter business constructors but TCL original bodies remain executable'() {
        String name = 'com/tcl/test/Business'
        ClassWriter writer = new ClassWriter(0)
        writer.visit(V1_8, ACC_PUBLIC, name, null, 'java/lang/Object', null)
        def init = writer.visitMethod(ACC_PUBLIC, '<init>', '()V', null, null)
        init.visitCode(); init.visitVarInsn(ALOAD,0); init.visitMethodInsn(INVOKESPECIAL,'java/lang/Object','<init>','()V',false)
        init.visitInsn(RETURN); init.visitMaxs(1,1); init.visitEnd()
        def method=writer.visitMethod(ACC_PUBLIC | ACC_STATIC,'value','()I',null,null)
        method.visitCode(); method.visitIntInsn(BIPUSH,42); method.visitInsn(IRETURN); method.visitMaxs(1,0); method.visitEnd(); writer.visitEnd()
        File output=Files.createTempDirectory('universal-policy-test').toFile()
        byte[] patched=TclUniversalRuntimePatcher.patch([(name+'.class'):writer.toByteArray()],'cmp',output)[name+'.class']
        Loader loader=new Loader(); Class policy=loader.define(TclUniversalRuntimePatcher.POLICY.replace('/','.'),policyBytes())
        Class business=loader.define(name.replace('/','.'),patched)
        policy.getField('google').setBoolean(null,true)
        assertEquals(0,business.getMethod('value').invoke(null))
        try { business.getConstructor().newInstance(); fail('Google entered a TCL business constructor') }
        catch(java.lang.reflect.InvocationTargetException expected) { assertTrue(expected.cause instanceof IllegalStateException) }
        policy.getField('google').setBoolean(null,false)
        assertEquals(42,business.getMethod('value').invoke(null)); assertNotNull(business.getConstructor().newInstance())
    }
    @Test void 'abstract CMP interfaces never receive a Code attribute'() {
        String name='com/tcl/test/Callback'
        ClassWriter writer=new ClassWriter(0); writer.visit(V1_8,ACC_PUBLIC|ACC_INTERFACE|ACC_ABSTRACT,name,null,'java/lang/Object',null)
        writer.visitMethod(ACC_PUBLIC|ACC_ABSTRACT,'result','()V',null,null).visitEnd(); writer.visitEnd()
        File output=Files.createTempDirectory('universal-interface-test').toFile()
        byte[] result=TclUniversalRuntimePatcher.patch([(name+'.class'):writer.toByteArray()],'cmp',output)[name+'.class']
        ClassNode node=new ClassNode(); new ClassReader(result).accept(node,0)
        assertEquals(0,node.methods[0].instructions.size()); assertTrue((node.methods[0].access&ACC_ABSTRACT)!=0)
        assertTrue(new Loader().define(name.replace('/','.'),result).isInterface())
    }
    @Test void 'scoped constructor branches initialize a complete native object in either mode'() {
        String name='com/tcl/test/Scoped'
        ClassNode node=new ClassNode(); node.visit(V1_8,ACC_PUBLIC,name,null,'java/lang/Object',null)
        node.fields.add(new FieldNode(ACC_PUBLIC,'value','I',null,null))
        MethodNode original=constructor(name,42), alternate=constructor(name,7)
        TclUniversalRuntimePatcher.branch(original,alternate); node.methods.add(original)
        ClassWriter writer=new ClassWriter(ClassWriter.COMPUTE_FRAMES|ClassWriter.COMPUTE_MAXS); node.accept(writer)
        Loader loader=new Loader(); Class policy=loader.define(TclUniversalRuntimePatcher.POLICY.replace('/','.'),policyBytes())
        Class scoped=loader.define(name.replace('/','.'),writer.toByteArray())
        policy.getField('google').setBoolean(null,true); assertEquals(7,scoped.getField('value').getInt(scoped.getConstructor().newInstance()))
        policy.getField('google').setBoolean(null,false); assertEquals(42,scoped.getField('value').getInt(scoped.getConstructor().newInstance()))
    }
    private static MethodNode constructor(String owner,int value) {
        MethodNode method=new MethodNode(ACC_PUBLIC,'<init>','()V',null,null)
        method.instructions.add(new VarInsnNode(ALOAD,0)); method.instructions.add(new MethodInsnNode(INVOKESPECIAL,'java/lang/Object','<init>','()V',false))
        method.instructions.add(new VarInsnNode(ALOAD,0));method.instructions.add(new LdcInsnNode(value));method.instructions.add(new FieldInsnNode(PUTFIELD,owner,'value','I'))
        method.instructions.add(new InsnNode(RETURN)); method
    }
    private static byte[] policyBytes() {
        ClassWriter writer=new ClassWriter(0); writer.visit(V1_8,ACC_PUBLIC,TclUniversalRuntimePatcher.POLICY,null,'java/lang/Object',null)
        writer.visitField(ACC_PUBLIC|ACC_STATIC,'google','Z',null,null).visitEnd()
        def method=writer.visitMethod(ACC_PUBLIC|ACC_STATIC,'isGoogle','()Z',null,null)
        method.visitCode(); method.visitFieldInsn(GETSTATIC,TclUniversalRuntimePatcher.POLICY,'google','Z');method.visitInsn(IRETURN);method.visitMaxs(1,0);method.visitEnd();writer.visitEnd();writer.toByteArray()
    }
    @groovy.transform.CompileStatic
    private static class Loader extends ClassLoader {
        Class define(String name,byte[] bytes) { defineClass(name,bytes,0,bytes.length) }
    }
}
