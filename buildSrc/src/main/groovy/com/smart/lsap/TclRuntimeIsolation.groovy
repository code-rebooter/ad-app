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
                ['preloadConfig', 'a', 'b', 'd'].each { n -> replace(node,n,'()V',methods) { retVoid(it) } }
                replace(node,'a','(Ljava/lang/String;)V',methods) { retVoid(it) }
                replace(node,'<init>','()V',methods) {
                    it.instructions.add(new VarInsnNode(ALOAD,0)); it.instructions.add(new MethodInsnNode(INVOKESPECIAL,'java/lang/Object','<init>','()V',false))
                    it.instructions.add(new VarInsnNode(ALOAD,0)); it.instructions.add(new TypeInsnNode(NEW,'java/lang/Object')); it.instructions.add(new InsnNode(DUP))
                    it.instructions.add(new MethodInsnNode(INVOKESPECIAL,'java/lang/Object','<init>','()V',false)); it.instructions.add(new FieldInsnNode(PUTFIELD,name,'c','Ljava/lang/Object;'))
                    it.instructions.add(new VarInsnNode(ALOAD,0)); it.instructions.add(new TypeInsnNode(NEW,BASE+'ConfigInitObserver')); it.instructions.add(new InsnNode(DUP))
                    it.instructions.add(new MethodInsnNode(INVOKESPECIAL,BASE+'ConfigInitObserver','<init>','()V',false)); it.instructions.add(new FieldInsnNode(PUTFIELD,name,'h','L'+BASE+'ConfigInitObserver;'))
                    it.instructions.add(new VarInsnNode(ALOAD,0)); it.instructions.add(new LdcInsnNode('')); it.instructions.add(new FieldInsnNode(PUTFIELD,name,'g','Ljava/lang/String;')); retVoid(it)
                }
            } else if (name == 'com/tcl/ff/component/overseabase/base/util/AppUtils') {
                // Keep the user's ad landing URL. Remove promotion of the TCL browser.
                MethodNode method=required(node,'gotoWeb','(Landroid/content/Context;Ljava/lang/String;)V')
                int found=0,packageFound=0
                method.instructions.toArray().each { insn ->
                    if (insn instanceof LdcInsnNode && insn.cst=='https://play.google.com/store/apps/details?id=com.tcl.browser') {
                        method.instructions.set(insn,new VarInsnNode(ALOAD,1)); found++
                    }
                    if (insn instanceof LdcInsnNode && insn.cst=='com.tcl.browser') {
                        method.instructions.set(insn,new InsnNode(ACONST_NULL)); packageFound++
                    }
                }
                if (found!=1 || packageFound!=1) throw new GradleException('Unexpected TCL browser promotion/selection boundary')
                methods.add(method.name+method.desc)
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
                // Isolate the business address boundary, preserving every original HTTP method.
                if (node.methods.any { it.name=='resolveSuppliedAdAddress' }) throw new GradleException('Unexpected supplied-address ABI')
                MethodNode boundary=new MethodNode(ACC_PUBLIC,'resolveSuppliedAdAddress','(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;',null,null)
                boundary.instructions.add(new InsnNode(ACONST_NULL)); boundary.instructions.add(new InsnNode(ARETURN))
                node.methods.add(boundary); methods.add('added '+boundary.name+boundary.desc)
            } else if (name == 'com/tcl/ff/component/media/ima/bi/a') {
                node.methods.findAll { it.name.startsWith('on') }.each { reset(it); retVoid(it); methods.add(it.name+it.desc) }
            } else if (name == 'com/tcl/ff/component/media/processor/a/b') {
                replace(node,'a','(L'+HTTP+';Ljava/lang/String;L'+BASE+'requestparams/VastAdRequestParams;)Ljava/lang/String;',methods) {
                    it.instructions.add(new VarInsnNode(ALOAD,1)); it.instructions.add(new VarInsnNode(ALOAD,2)); it.instructions.add(new LdcInsnNode('{}'))
                    it.instructions.add(new MethodInsnNode(INVOKEVIRTUAL,HTTP,'resolveSuppliedAdAddress','(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;',false)); it.instructions.add(new InsnNode(ARETURN))
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
            } else if (name == 'com/tcl/tuniplayer_base/d') {
                // This is UniPlayer vendor telemetry, not XML VAST tracking.
                replace(node,'a','(Z)V',methods) {
                    it.instructions.add(new VarInsnNode(ALOAD,0)); it.instructions.add(new InsnNode(ACONST_NULL))
                    it.instructions.add(new FieldInsnNode(PUTFIELD,name,'c','Lcom/tcl/tuniplayer_base/i/k;')); retVoid(it)
                }
                ['(Ljava/lang/String;Ljava/lang/String;Ljava/lang/Object;[Ljava/lang/Object;)V',
                 '(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/util/Map;)V'].each { descriptor ->
                    replace(node,'a',descriptor,methods) { retVoid(it) }
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
        entries['META-INF/tcl-adurl-only/'+component+'.marker'] = 'adurl-only-v4'.getBytes('UTF-8')
        ByteArrayOutputStream buffer=new ByteArrayOutputStream()
        new ZipOutputStream(buffer).withCloseable { zip -> entries.keySet().sort().each { name -> def e=new ZipEntry(name);e.time=0;zip.putNextEntry(e);zip.write(entries[name]);zip.closeEntry() } }
        return [bytes:buffer.toByteArray(),report:[profile:'adurl-only-v4',removedClasses:removed.sort(),changedClasses:changes,inputJarSha256:sha(input),outputJarSha256:sha(buffer.toByteArray())]]
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
    private static void denied(MethodNode m,String reason='Offline database is disabled in the adurl-only runtime') { m.instructions.add(new TypeInsnNode(NEW,'java/lang/UnsupportedOperationException'));m.instructions.add(new InsnNode(DUP));m.instructions.add(new LdcInsnNode(reason));m.instructions.add(new MethodInsnNode(INVOKESPECIAL,'java/lang/UnsupportedOperationException','<init>','(Ljava/lang/String;)V',false));m.instructions.add(new InsnNode(ATHROW)) }
}
