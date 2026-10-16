package com.smart.lsap

import groovy.json.JsonOutput
import org.gradle.api.GradleException
import org.objectweb.asm.*
import org.objectweb.asm.commons.ClassRemapper
import org.objectweb.asm.commons.SimpleRemapper
import org.objectweb.asm.tree.*
import java.util.zip.*

/** One vendor binary; Google uses the audited supplied-URL boundaries, TCL retains original bodies. */
final class TclUniversalRuntimePatcher implements Opcodes {
    static final String POLICY = 'com/smart/android/adsdk/universal/internal/NativePolicy'
    static final String HTTP = TclRuntimeIsolation.HTTP
    static Map<String, byte[]> patch(Map<String, byte[]> original, String component, File output) {
        Map isolated = component == 'cmp' ? null : TclRuntimeIsolation.isolate(pack(original))
        Map<String, byte[]> alternatives = isolated == null ? [:] : unpack(isolated.bytes as byte[])
        Map<String, List<String>> changes = isolated == null ? [:] : isolated.report.changedClasses.collectEntries { [(it.className): it.methods] }
        List report = []
        original.keySet().toList().each { path ->
            if (!path.endsWith('.class')) return
            byte[] before = original[path]
            ClassNode node = read(before)
            List<String> guarded = []
            if (changes.containsKey(path)) {
                ClassNode candidate = read(alternatives[path])
                changes[path].each { key ->
                    key = key.replace(' [permissions only]', '')
                    if (key.startsWith('removed ')) {
                        String methodKey = key.substring(8)
                        def method = node.methods.find { it.name + it.desc == methodKey }
                        if (method == null) throw new GradleException("Missing original TCL body: ${path}.${key}")
                        guardDefault(method); guarded.add(methodKey)
                    } else if (key.startsWith('added ')) {
                        String methodKey = key.substring(6)
                        node.methods.add(candidate.methods.find { it.name + it.desc == methodKey })
                        guarded.add(key)
                    } else {
                        MethodNode actual = node.methods.find { it.name + it.desc == key }
                        MethodNode alternate = candidate.methods.find { it.name + it.desc == key }
                        if (!actual || !alternate) throw new GradleException("Missing supplied-URL boundary: ${path}.${key}")
                        // The original config constructor only sets fields/path, with no file I/O.
                        // Retain it so TCL can subsequently use its own native config cache.
                        // The device constructor only generates/persists a local UUID; sharing an empty
                        // isolated device object would lose TCL's original identifier after Google runs.
                        if (actual.name == '<init>' && node.name in [TclRuntimeIsolation.BASE + 'AdConfigFetcher',
                            'com/tcl/ff/component/overseabase/base/device/DeviceInfoGeneratorImpl']) return
                        if (node.name == 'com/tcl/ff/component/media/processor/a/b' && actual.name == 'a') {
                            bridgeAddress(actual)
                        } else {
                            branch(actual, alternate)
                        }
                        guarded.add(key)
                    }
                }
            }
            if (TclRuntimeIsolation.removedName(node.name) || component == 'cmp') {
                node.methods.findAll { it.name != '<clinit>' && (it.access & (ACC_ABSTRACT | ACC_NATIVE)) == 0 }.each { method ->
                    guardDefault(method); guarded.add(method.name + method.desc)
                }
            }
            byte[] selected = before
            if (!guarded.empty) {
                ClassWriter writer = safeWriter(); node.accept(writer); selected = writer.toByteArray()
            }
            byte[] common = LsapClassPatcher.patchTclExperimentParameters(path, selected)
            Map mappings = [
                'com/smart/android/adsdk/internal/TclIdentityBridge': 'com/smart/android/adsdk/universal/internal/TclIdentityBridge',
                'com/smart/android/ad_app/Hq008XhsxAarRuntimeBridge': 'com/smart/android/adsdk/universal/internal/VendorRuntimeBridge'
            ]
            if (mappings.keySet().any { new String(common, 'ISO-8859-1').contains(it) }) {
                ClassWriter mapped = new ClassWriter(0)
                new ClassReader(common).accept(new ClassRemapper(mapped, new SimpleRemapper(mappings)), 0)
                original[path] = mapped.toByteArray()
            } else { original[path] = common }
            if (!guarded.empty || !Arrays.equals(before, original[path])) report.add([className:path,googleBoundaries:guarded])
        }
        File audit = new File(output, "audit/${component}-boundaries.json")
        audit.parentFile.mkdirs()
        audit.text = JsonOutput.prettyPrint(JsonOutput.toJson([component:component,profile:'universal-key-v1',classes:original.size(),changes:report])) + '\n'
        return original
    }
    static void branch(MethodNode original, MethodNode alternate) {
        LabelNode alternateStart = new LabelNode()
        InsnList gate = new InsnList()
        gate.add(new MethodInsnNode(INVOKESTATIC, POLICY, 'isGoogle', '()Z', false))
        gate.add(new JumpInsnNode(IFNE, alternateStart))
        original.instructions.insert(gate)
        original.instructions.add(alternateStart)
        original.instructions.add(alternate.instructions)
        original.tryCatchBlocks.addAll(alternate.tryCatchBlocks)
        original.localVariables?.clear()
        original.visibleLocalVariableAnnotations = null
        original.invisibleLocalVariableAnnotations = null
    }
    static void guardDefault(MethodNode method) {
        LabelNode nativeBody = new LabelNode()
        InsnList guard = new InsnList()
        guard.add(new MethodInsnNode(INVOKESTATIC, POLICY, 'isGoogle', '()Z', false))
        guard.add(new JumpInsnNode(IFEQ, nativeBody))
        if (method.name == '<init>') {
            guard.add(new TypeInsnNode(NEW, 'java/lang/IllegalStateException')); guard.add(new InsnNode(DUP))
            guard.add(new LdcInsnNode('TCL business component is unavailable during Google playback'))
            guard.add(new MethodInsnNode(INVOKESPECIAL,'java/lang/IllegalStateException','<init>','(Ljava/lang/String;)V',false))
            guard.add(new InsnNode(ATHROW))
        } else {
            Type type = Type.getReturnType(method.desc)
            switch (type.sort) {
                case Type.VOID: guard.add(new InsnNode(RETURN)); break
                case Type.LONG: guard.add(new InsnNode(LCONST_0)); guard.add(new InsnNode(LRETURN)); break
                case Type.DOUBLE: guard.add(new InsnNode(DCONST_0)); guard.add(new InsnNode(DRETURN)); break
                case Type.FLOAT: guard.add(new InsnNode(FCONST_0)); guard.add(new InsnNode(FRETURN)); break
                case Type.ARRAY: case Type.OBJECT: guard.add(new InsnNode(ACONST_NULL)); guard.add(new InsnNode(ARETURN)); break
                default: guard.add(new InsnNode(ICONST_0)); guard.add(new InsnNode(IRETURN))
            }
        }
        guard.add(nativeBody)
        method.instructions.insert(guard)
    }
    private static void bridgeAddress(MethodNode method) {
        LabelNode noAddress = new LabelNode(), nativeBody = new LabelNode()
        InsnList gate = new InsnList()
        gate.add(new VarInsnNode(ALOAD,1)); gate.add(new VarInsnNode(ALOAD,2)); gate.add(new LdcInsnNode('{}'))
        gate.add(new MethodInsnNode(INVOKEVIRTUAL,HTTP,'resolveSuppliedAdAddress','(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;',false))
        gate.add(new InsnNode(DUP)); gate.add(new JumpInsnNode(IFNULL,noAddress)); gate.add(new InsnNode(ARETURN))
        gate.add(noAddress); gate.add(new InsnNode(POP))
        gate.add(new MethodInsnNode(INVOKESTATIC,POLICY,'isGoogle','()Z',false)); gate.add(new JumpInsnNode(IFEQ,nativeBody))
        gate.add(new InsnNode(ACONST_NULL)); gate.add(new InsnNode(ARETURN)); gate.add(nativeBody)
        method.instructions.insert(gate)
    }
    private static ClassNode read(byte[] bytes) { def n = new ClassNode(); new ClassReader(bytes).accept(n,ClassReader.EXPAND_FRAMES); n }
    private static ClassWriter safeWriter() { new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
        @Override protected String getCommonSuperClass(String a,String b) { a == b ? a : 'java/lang/Object' }
    } }
    private static Map unpack(byte[] bytes) { Map result=[:]; new ZipInputStream(new ByteArrayInputStream(bytes)).withCloseable { zip -> def entry; while((entry=zip.nextEntry)!=null) if(!entry.directory) result[entry.name]=zip.readAllBytes() }; result }
    private static byte[] pack(Map entries) { def bytes=new ByteArrayOutputStream(); new ZipOutputStream(bytes).withCloseable { zip -> entries.each { name,data -> zip.putNextEntry(new ZipEntry(name)); zip.write(data); zip.closeEntry() } }; bytes.toByteArray() }
}
