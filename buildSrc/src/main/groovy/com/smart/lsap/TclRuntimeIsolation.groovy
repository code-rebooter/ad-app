package com.smart.lsap

import groovy.json.JsonOutput
import org.gradle.api.GradleException
import org.objectweb.asm.*
import org.objectweb.asm.tree.*
import java.security.MessageDigest
import java.util.zip.*

/** Explicitly removes non-advertising execution paths from the pinned 2.8.02 runtime. */
final class TclRuntimeIsolation implements Opcodes {
    static final List<String> REMOVED_PREFIXES = [
        'com/tcl/ff/component/adsdkbi/',
        'com/tcl/ff/component/overseabase/database/',
        'a/a/a/a/a/'
    ].asImmutable()
    static final String BASE = 'com/tcl/ff/component/overseabasebusiness/'
    static final String HTTP = 'com/tcl/ff/component/overseahttp/http/HttpRequester'

    static Map isolate(byte[] input) {
        Map<String,byte[]> entries = [:]
        new ZipInputStream(new ByteArrayInputStream(input)).withCloseable { zip ->
            ZipEntry entry
            while ((entry = zip.nextEntry) != null) if (!entry.directory) entries[entry.name] = zip.readAllBytes()
        }
        List<String> removed = []
        List<Map> changes = []
        entries.keySet().toList().each { path ->
            if (!path.endsWith('.class')) return
            String name = path.substring(0, path.length()-6)
            if (removedName(name)) { removed.add(path); entries.remove(path); return }
            ClassNode node = new ClassNode(); new ClassReader(entries[path]).accept(node, 0)
            List<String> methods = []
            if (name == BASE+'bi/BiInitialization') {
                replace(node, 'init', '(Landroid/content/Context;)V', methods) { retVoid(it) }
            } else if (name == BASE+'bi/BiReportUtil') {
                node.methods.findAll { (it.access & ACC_PRIVATE) != 0 }.each { methods.add('removed '+it.name+it.desc) }
                node.methods.removeAll { (it.access & ACC_PRIVATE) != 0 }
                node.methods.findAll { it.name.startsWith('report') }.each { reset(it); retVoid(it); methods.add(it.name+it.desc) }
            } else if (name == BASE+'AdConfigFetcher') {
                ['preloadConfig', 'b', 'd'].each { n -> replace(node,n,'()V',methods) { retVoid(it) } }
                replace(node,'a','(Ljava/lang/String;)V',methods) { retVoid(it) }
            } else if (name == BASE+'adutils/AdUtil') {
                replace(node,'recordAdRequestTime','(Lcom/tcl/ff/component/overseabase/base/constant/AdType;)V',methods) { retVoid(it) }
                replace(node,'getAdRequestTimes','(Lcom/tcl/ff/component/overseabase/base/constant/AdType;)I',methods) { it.instructions.add(new InsnNode(ICONST_0)); it.instructions.add(new InsnNode(IRETURN)) }
            } else if (name == BASE+'adutils/SharedPreferenceUtil') {
                replace(node,'savePrefString','(Ljava/lang/String;Ljava/lang/String;)V',methods) { retVoid(it) }
                replace(node,'getPrefStringValue','(Ljava/lang/String;)Ljava/lang/String;',methods) { it.instructions.add(new LdcInsnNode('')); it.instructions.add(new InsnNode(ARETURN)) }
            } else if (name == 'com/tcl/ff/component/overseabase/base/device/DeviceInfoGeneratorImpl') {
                replace(node,'<init>','(Landroid/content/Context;)V',methods) {
                    it.instructions.add(new VarInsnNode(ALOAD,0)); it.instructions.add(new MethodInsnNode(INVOKESPECIAL,'java/lang/Object','<init>','()V',false))
                    it.instructions.add(new VarInsnNode(ALOAD,0)); it.instructions.add(new LdcInsnNode('')); it.instructions.add(new FieldInsnNode(PUTFIELD,name,'a','Ljava/lang/String;')); retVoid(it)
                }
            } else if (name == HTTP) {
                replace(node,'postJsonSync','(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;',methods) {
                    it.instructions.add(new InsnNode(ACONST_NULL)); it.instructions.add(new InsnNode(ARETURN))
                }
            } else if (name == 'com/tcl/ff/component/media/ima/bi/a') {
                node.methods.findAll { it.name.startsWith('on') }.each { reset(it); retVoid(it); methods.add(it.name+it.desc) }
            } else if (name == 'com/tcl/ff/component/media/processor/a/b') {
                replace(node,'a','(L'+HTTP+';Ljava/lang/String;L'+BASE+'requestparams/VastAdRequestParams;)Ljava/lang/String;',methods) {
                    it.instructions.add(new VarInsnNode(ALOAD,1)); it.instructions.add(new VarInsnNode(ALOAD,2)); it.instructions.add(new LdcInsnNode('{}'))
                    it.instructions.add(new MethodInsnNode(INVOKEVIRTUAL,HTTP,'postJsonSync','(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;',false)); it.instructions.add(new InsnNode(ARETURN))
                }
            } else if (name == 'com/tcl/ff/component/media/processor/api/AdsFetcher') {
                // Backend scheduling/limits precede adurl; the host already supplied the chosen tag.
                replace(node,'interceptByConfig','(Lcom/tcl/ff/component/overseabase/base/constant/AdType;)Z',methods) {
                    it.instructions.add(new InsnNode(ICONST_0)); it.instructions.add(new InsnNode(IRETURN))
                }
            } else if (name == 'com/tcl/ff/component/media/ima/tcl/convert/XmlConverter') {
                MethodNode method = required(node,'convert','(Ljava/lang/String;Ljava/lang/Class;)Ljava/lang/Object;')
                int found=0
                method.instructions.toArray().each { insn ->
                    if (insn instanceof FieldInsnNode && insn.owner=='com/thoughtworks/xstream/security/AnyTypePermission' && insn.name=='ANY') {
                        insn.owner='com/thoughtworks/xstream/security/NoTypePermission'; insn.name='NONE'; found++
                    }
                    if (insn instanceof MethodInsnNode && insn.owner=='com/thoughtworks/xstream/XStream' && insn.name=='processAnnotations') {
                        InsnList allow=new InsnList(); allow.add(new VarInsnNode(ALOAD,0))
                        String[] patterns=['com.tcl.ff.component.media.ima.tcl.xmlmodels.**','java.lang.String','java.util.ArrayList','java.util.LinkedList','java.util.HashMap']
                        allow.add(new LdcInsnNode(patterns.length)); allow.add(new TypeInsnNode(ANEWARRAY,'java/lang/String'))
                        patterns.eachWithIndex { value,index -> allow.add(new InsnNode(DUP)); allow.add(new LdcInsnNode(index)); allow.add(new LdcInsnNode(value)); allow.add(new InsnNode(AASTORE)) }
                        allow.add(new MethodInsnNode(INVOKEVIRTUAL,'com/thoughtworks/xstream/XStream','allowTypesByWildcard','([Ljava/lang/String;)V',false))
                        method.instructions.insertBefore(insn,allow)
                    }
                }
                if (found!=1) throw new GradleException('Expected exactly one original XStream AnyTypePermission')
                methods.add(method.name+method.desc+' [permissions only]')
            } else if (name == 'com/google/android/exoplayer2/database/DefaultDatabaseProvider') {
                ['getWritableDatabase','getReadableDatabase'].each { n -> replace(node,n,'()Landroid/database/sqlite/SQLiteDatabase;',methods) { denied(it) } }
            } else if (name == 'com/google/android/exoplayer2/database/StandaloneDatabaseProvider') {
                ['getWritableDatabase','getReadableDatabase'].each { n ->
                    if (node.methods.any { it.name==n }) throw new GradleException('Unexpected original DB override')
                    MethodNode m=new MethodNode(ACC_PUBLIC,n,'()Landroid/database/sqlite/SQLiteDatabase;',null,null); denied(m); node.methods.add(m); methods.add('added '+m.name+m.desc)
                }
            }
            if (!methods.empty) {
                node.innerClasses.removeAll { removedName(it.name) || removedName(it.outerName) }
                ClassWriter writer=new ClassWriter(ClassWriter.COMPUTE_MAXS);node.accept(writer)
                byte[] result=writer.toByteArray()
                verifyUntouchedMethods(entries[path], result, methods)
                changes.add([className:path,methods:methods,originalSha256:sha(entries[path]),isolatedSha256:sha(result)])
                entries[path]=result
            }
        }
        // Any residual execution/type reference to removed BI/DB implementation rejects the build.
        List<String> dangling=[]
        entries.each { path,bytes ->
            if (!path.endsWith('.class')) return
            ClassNode n=new ClassNode();new ClassReader(bytes).accept(n,0)
            Set<String> refs=[] as Set
            Closure addDesc={ String d -> if(d) { def matcher=(d =~ /L([^;<>]+)/); matcher.each { refs.add(it[1]) } } }
            refs.add(n.superName);refs.addAll(n.interfaces);addDesc(n.signature)
            n.fields.each { addDesc(it.desc);addDesc(it.signature) }
            n.methods.each { m ->
                addDesc(m.desc);addDesc(m.signature);refs.addAll(m.exceptions ?: [])
                m.instructions.toArray().each { insn ->
                    if (insn instanceof MethodInsnNode || insn instanceof FieldInsnNode) { refs.add(insn.owner);addDesc(insn.desc) }
                    if (insn instanceof TypeInsnNode) refs.add(insn.desc)
                    if (insn instanceof LdcInsnNode && insn.cst instanceof Type) addDesc(insn.cst.descriptor)
                    if (insn instanceof LdcInsnNode && insn.cst instanceof String && removedName(insn.cst.replace('.','/'))) refs.add(insn.cst.replace('.','/'))
                }
            }
            refs.findAll { removedName(it) }.each { dangling.add(path+' -> '+it) }
        }
        if (!dangling.empty) throw new GradleException('Isolation has unresolved forbidden references:\n'+dangling.join('\n'))
        String component = entries.containsKey(HTTP+'.class') ? 'base' :
            (entries.containsKey('com/tcl/ff/component/media/ima/tcl/convert/XmlConverter.class') ? 'media' : 'player')
        entries['META-INF/tcl-adurl-only/'+component+'.marker'] = 'adurl-only-v1'.getBytes('UTF-8')
        ByteArrayOutputStream buffer=new ByteArrayOutputStream()
        new ZipOutputStream(buffer).withCloseable { zip -> entries.keySet().sort().each { name -> def e=new ZipEntry(name);e.time=0;zip.putNextEntry(e);zip.write(entries[name]);zip.closeEntry() } }
        return [bytes:buffer.toByteArray(),report:[profile:'adurl-only-v1',removedClasses:removed.sort(),changedClasses:changes,inputJarSha256:sha(input),outputJarSha256:sha(buffer.toByteArray())]]
    }
    private static void verifyUntouchedMethods(byte[] before, byte[] after, List<String> changes) {
        ClassNode a=new ClassNode(), b=new ClassNode()
        new ClassReader(before).accept(a,0);new ClassReader(after).accept(b,0)
        Set<String> allowed=changes.collect { it.replaceFirst(/^(removed|added) /,'').replace(' [permissions only]','') } as Set
        Map<String,MethodNode> originals=a.methods.collectEntries { [(it.name+it.desc):it] }
        Map<String,MethodNode> actual=b.methods.collectEntries { [(it.name+it.desc):it] }
        (originals.keySet()+actual.keySet()).each { key ->
            if (!allowed.contains(key)) {
                if (!originals[key] || !actual[key] || methodDigest(a.name,originals[key])!=methodDigest(b.name,actual[key]))
                    throw new GradleException("Unexpected method change outside isolation boundary: ${a.name}.${key}")
            }
        }
        if (a.fields.collect { [it.access,it.name,it.desc,it.signature,it.value] } != b.fields.collect { [it.access,it.name,it.desc,it.signature,it.value] })
            throw new GradleException("Unexpected field change: ${a.name}")
    }
    private static String methodDigest(String owner,MethodNode method) {
        // Rebuild a one-method container: normalizes constant-pool indexes and label offsets.
        // max stack/local counts are derived metadata, not method behavior.
        method.maxStack=0;method.maxLocals=0
        ClassWriter out=new ClassWriter(0)
        out.visit(V1_8,ACC_PUBLIC,owner,null,'java/lang/Object',null)
        method.accept(out);out.visitEnd();return sha(out.toByteArray())
    }
    static boolean removedName(String name) { name!=null && REMOVED_PREFIXES.any { name.startsWith(it) } }
    static String sha(byte[] b) { MessageDigest.getInstance('SHA-256').digest(b).encodeHex().toString() }
    private static MethodNode required(ClassNode n,String name,String desc) { def m=n.methods.find { it.name==name && it.desc==desc };if(m==null)throw new GradleException("Missing pinned ABI: ${n.name}.${name}${desc}");m }
    private static void reset(MethodNode m) { m.instructions.clear();m.tryCatchBlocks.clear();m.localVariables?.clear();m.visibleLocalVariableAnnotations=null;m.invisibleLocalVariableAnnotations=null }
    private static void replace(ClassNode n,String name,String desc,List names,Closure body) { def m=required(n,name,desc);reset(m);body(m);names.add(name+desc) }
    private static void retVoid(MethodNode m) { m.instructions.add(new InsnNode(RETURN)) }
    private static void denied(MethodNode m) { m.instructions.add(new TypeInsnNode(NEW,'java/lang/UnsupportedOperationException'));m.instructions.add(new InsnNode(DUP));m.instructions.add(new LdcInsnNode('Offline database is disabled in the adurl-only runtime'));m.instructions.add(new MethodInsnNode(INVOKESPECIAL,'java/lang/UnsupportedOperationException','<init>','(Ljava/lang/String;)V',false));m.instructions.add(new InsnNode(ATHROW)) }
}
