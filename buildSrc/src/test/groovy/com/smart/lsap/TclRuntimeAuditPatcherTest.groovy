package com.smart.lsap

import org.junit.Test
import org.objectweb.asm.*
import org.objectweb.asm.tree.ClassNode
import java.lang.reflect.InvocationTargetException
import static org.junit.Assert.*
import static org.objectweb.asm.Opcodes.*

class TclRuntimeAuditPatcherTest {
    private static final String OWNER = 'com/tcl/ff/component/media/ima/tcl/c/d'
    private static final String REQUEST = 'com/tcl/ff/component/media/ima/api/VastRequest'
    private static final String AD = 'com/tcl/ff/component/media/ima/tcl/xmlmodels/Ad'
    private static final String MAX = 'com/tcl/ff/component/media/ima/flat/AdMaxDuration'
    private static final String VIDEO = 'com/tcl/ff/component/media/ima/tcl/data/VideoAdInfo'
    private static final String BRIDGE = 'com/smart/android/tclad/runtime/TclRuntimeBridge'

    @Test void 'SDK material recovery preserves valid return restores scope and does not swallow Errors'() {
        GroovyClassLoader loader = new GroovyClassLoader(getClass().classLoader)
        Map<String,Class> types = [:]
        [REQUEST, AD, MAX, VIDEO].each { name -> types[name] = loader.defineClass(name.replace('/','.'), emptyClass(name)) }
        Class bridge = loader.defineClass(BRIDGE.replace('/','.'), bridgeClass())
        byte[] patched = TclRuntimeAuditPatcher.patchForBridge(OWNER+'.class', nativeClass(), BRIDGE)
        Class target = loader.defineClass(OWNER.replace('/','.'), patched)
        Object instance = target.getDeclaredConstructor().newInstance()
        def method = target.getMethod('a', [types[REQUEST],types[AD],types[MAX]] as Class[])
        assertEquals(types[VIDEO], method.invoke(instance, [null,null,null] as Object[]).class)
        assertEquals(0, bridge.getField('failures').getInt(null))
        assertNull(method.invoke(instance, [null,types[AD].getDeclaredConstructor().newInstance(),null] as Object[]))
        assertEquals(1, bridge.getField('failures').getInt(null))
        try {
            method.invoke(instance, [null,types[AD].getDeclaredConstructor().newInstance(),types[MAX].getDeclaredConstructor().newInstance()] as Object[])
            fail('Fatal Error must propagate')
        } catch (InvocationTargetException error) { assertTrue(error.cause instanceof AssertionError) }
        assertEquals(3, bridge.getField('enters').getInt(null))
        assertEquals(3, bridge.getField('leaves').getInt(null))
        assertEquals(1, bridge.getField('failures').getInt(null))
        loader.close()
    }

    @Test void 'app observation-only path retains its original exception behavior'() {
        ClassNode node = new ClassNode()
        new ClassReader(TclRuntimeAuditPatcher.patch(OWNER+'.class', nativeClass())).accept(node,0)
        def method = node.methods.find { it.name=='a' }
        assertFalse(method.tryCatchBlocks.any { it.type=='java/lang/RuntimeException' })
    }

    @Test void 'app media patch explicitly enables the same material recovery boundary'() {
        ClassNode node = new ClassNode()
        new ClassReader(TclRuntimeAuditPatcher.patch(OWNER+'.class', nativeClass(), true)).accept(node,0)
        def method = node.methods.find { it.name=='a' }
        assertTrue(method.tryCatchBlocks.any { it.type=='java/lang/RuntimeException' })
        assertTrue(method.instructions.toArray().any { instruction ->
            instruction instanceof org.objectweb.asm.tree.MethodInsnNode &&
                instruction.owner=='com/smart/android/ad_app/TclRuntimeBridge' &&
                instruction.name=='onMaterialParseFailure'
        })
    }

    private static byte[] emptyClass(String name) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES|ClassWriter.COMPUTE_MAXS)
        writer.visit(V1_8, ACC_PUBLIC, name, null, 'java/lang/Object',null)
        constructor(writer); writer.visitEnd(); writer.toByteArray()
    }
    private static void constructor(ClassWriter writer) {
        MethodVisitor method = writer.visitMethod(ACC_PUBLIC,'<init>','()V',null,null)
        method.visitCode();method.visitVarInsn(ALOAD,0)
        method.visitMethodInsn(INVOKESPECIAL,'java/lang/Object','<init>','()V',false)
        method.visitInsn(RETURN);method.visitMaxs(0,0);method.visitEnd()
    }
    private static byte[] nativeClass() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES|ClassWriter.COMPUTE_MAXS)
        writer.visit(V1_8,ACC_PUBLIC,OWNER,null,'java/lang/Object',null);constructor(writer)
        MethodVisitor method = writer.visitMethod(ACC_PUBLIC|ACC_SYNCHRONIZED,'a','(L'+REQUEST+';L'+AD+';L'+MAX+';)L'+VIDEO+';',null,null)
        method.visitCode(); Label invalid = new Label(), fatal = new Label()
        method.visitVarInsn(ALOAD,2);method.visitJumpInsn(IFNONNULL,invalid)
        method.visitTypeInsn(NEW,VIDEO);method.visitInsn(DUP);method.visitMethodInsn(INVOKESPECIAL,VIDEO,'<init>','()V',false);method.visitInsn(ARETURN)
        method.visitLabel(invalid);method.visitVarInsn(ALOAD,3);method.visitJumpInsn(IFNONNULL,fatal)
        // Obfuscated native code reuses parameter slots, including reference -> int.
        method.visitInsn(ICONST_0);method.visitVarInsn(ISTORE,1)
        method.visitInsn(ICONST_0);method.visitVarInsn(ISTORE,2)
        method.visitTypeInsn(NEW,'java/lang/NullPointerException');method.visitInsn(DUP);method.visitMethodInsn(INVOKESPECIAL,'java/lang/NullPointerException','<init>','()V',false);method.visitInsn(ATHROW)
        method.visitLabel(fatal);method.visitTypeInsn(NEW,'java/lang/AssertionError');method.visitInsn(DUP);method.visitMethodInsn(INVOKESPECIAL,'java/lang/AssertionError','<init>','()V',false);method.visitInsn(ATHROW)
        method.visitMaxs(0,0);method.visitEnd();writer.visitEnd();writer.toByteArray()
    }
    private static byte[] bridgeClass() {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES|ClassWriter.COMPUTE_MAXS)
        writer.visit(V1_8,ACC_PUBLIC,BRIDGE,null,'java/lang/Object',null)
        ['enters','leaves','failures'].each { writer.visitField(ACC_PUBLIC|ACC_STATIC,it,'I',null,null).visitEnd() }
        countMethod(writer,'enterRequest','(L'+REQUEST+';)Ljava/lang/Object;','enters',true)
        countMethod(writer,'leaveScope','(Ljava/lang/Object;)V','leaves',false)
        countMethod(writer,'onMaterialParseFailure','(L'+REQUEST+';L'+AD+';Ljava/lang/RuntimeException;)V','failures',false)
        writer.visitEnd();writer.toByteArray()
    }
    private static void countMethod(ClassWriter writer,String name,String descriptor,String counter,boolean objectResult) {
        MethodVisitor method=writer.visitMethod(ACC_PUBLIC|ACC_STATIC,name,descriptor,null,null)
        method.visitCode();method.visitFieldInsn(GETSTATIC,BRIDGE,counter,'I');method.visitInsn(ICONST_1);method.visitInsn(IADD);method.visitFieldInsn(PUTSTATIC,BRIDGE,counter,'I')
        if (objectResult) { method.visitInsn(ACONST_NULL);method.visitInsn(ARETURN) } else method.visitInsn(RETURN)
        method.visitMaxs(0,0);method.visitEnd()
    }
}
