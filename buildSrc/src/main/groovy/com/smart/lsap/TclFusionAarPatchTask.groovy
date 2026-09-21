package com.smart.lsap

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.*

import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Only the fusion module's supplied TCL 2.8.02 base AAR is transformed. */
abstract class TclFusionAarPatchTask extends DefaultTask implements Opcodes {
    private static final String BASE_SHA256 = '1e9660a0e335e8e87e98057f5a0df520791a9754a0eb47dff789f00f7ecb56de'
    private static final String BRIDGE = 'com/smart/android/adsdk/internal/TclIdentityBridge'
    private static final String BASIC = 'com/tcl/ff/component/overseabase/base/util/BasicParameters'
    private static final String GLOBAL = 'com/tcl/ff/component/overseabase/base/util/GlobalContext'
    private static final String BI = 'com/tcl/ff/component/adsdkbi/bean/BaseDataInfo'
    private static final String STRING = '()Ljava/lang/String;'

    @InputFile abstract RegularFileProperty getInputAar()
    @OutputFile abstract RegularFileProperty getOutputAar()

    @TaskAction
    void patch() {
        byte[] original = inputAar.get().asFile.bytes
        if (sha256(original) != BASE_SHA256) {
            throw new GradleException('Fusion TCL identity patch requires the original base SDK 2.8.02 AAR')
        }
        Map<String, byte[]> aar = unpack(original)
        if (!aar.containsKey('classes.jar')) throw new GradleException('TCL base AAR has no classes.jar')
        Map<String, byte[]> classes = unpack(aar['classes.jar'])
        List<String> changed = []
        [BASIC,
         'com/tcl/ff/component/overseabase/base/util/Md5Utils',
         'com/tcl/ff/component/adsdkbi/bean/GetBaseDataInfo', BI].each { name ->
            String entry = name + '.class'
            if (!classes.containsKey(entry)) throw new GradleException("TCL identity entry missing: ${entry}")
            classes[entry] = TclFusionAarPatchTask.transform(name, classes[entry])
            changed.add(entry)
        }
        aar['classes.jar'] = pack(classes)
        aar['META-INF/fusion-tcl-identity.properties'] = [
            'patchVersion=fusion-tcl-identity-1',
            "originalAarSha256=${BASE_SHA256}",
            "patchedClassesJarSha256=${sha256(aar['classes.jar'])}",
            "bridge=${BRIDGE.replace('/', '.')}",
            "modifiedClasses=${changed.size()}",
            'hostPackageManager=unchanged'
        ].join('\n').concat('\n').getBytes('UTF-8')
        File output = outputAar.get().asFile
        output.parentFile.mkdirs()
        output.bytes = pack(aar)
        logger.lifecycle("Patched ${changed.size()} TCL identity classes -> ${output}")
    }

    private static byte[] transform(String name, byte[] input) {
        ClassNode node = new ClassNode()
        new ClassReader(input).accept(node, 0)
        if (name == BASIC) {
            // These private routines query installed app metadata/resources. Keep the real host
            // package there even though the public identity getter now uses TCL's registration.
            node.methods.findAll { it.name in ['a', 'b'] && it.desc == '()V' }.each { method ->
                method.instructions.toArray().each { insn ->
                    if (insn instanceof MethodInsnNode && insn.owner == BASIC &&
                        insn.name == 'getPackageName' && insn.desc == STRING) {
                        InsnList localPackage = new InsnList()
                        localPackage.add(new InsnNode(POP))
                        localPackage.add(new MethodInsnNode(INVOKESTATIC, GLOBAL, 'getAppContext',
                            '()Landroid/content/Context;', false))
                        localPackage.add(new MethodInsnNode(INVOKEVIRTUAL, 'android/content/Context',
                            'getPackageName', STRING, false))
                        method.instructions.insertBefore(insn, localPackage)
                        method.instructions.remove(insn)
                    }
                }
            }
            ['getPackageName', 'getAppKey', 'getPartnerName'].each {
                replaceGetter(node, it, STRING, it, ARETURN)
            }
            replaceGetter(node, 'getSignature', STRING, 'getSignatureMd5', ARETURN)
            replaceGetter(node, 'getProjectId', '()I', 'getProjectId', IRETURN)
        } else if (name.endsWith('/Md5Utils')) {
            replaceGetter(node, 'getSignatureMd5', '(Landroid/content/Context;)Ljava/lang/String;',
                'getSignatureMd5', ARETURN)
        } else if (name.endsWith('/GetBaseDataInfo')) {
            replaceGetter(node, 'getAPPSecretString', STRING, 'getBiSignatureMd5', ARETURN)
        } else if (name == BI) {
            MethodNode method = requireMethod(node, 'init',
                '(Landroid/content/Context;Lcom/tcl/ff/component/adsdkbi/bean/BaseDataInfo;)V')
            method.instructions.toArray().findAll { it.opcode == RETURN }.each { insn ->
                InsnList values = new InsnList()
                ['appPackage': 'getPackageName', 'projectId': 'getProjectIdString'].each { field, getter ->
                    values.add(new VarInsnNode(ALOAD, 0))
                    values.add(new MethodInsnNode(INVOKESTATIC, BRIDGE, getter, STRING, false))
                    values.add(new FieldInsnNode(PUTFIELD, BI, field, 'Ljava/lang/String;'))
                }
                method.instructions.insertBefore(insn, values)
            }
        }
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS)
        node.accept(writer)
        return writer.toByteArray()
    }

    private static void replaceGetter(ClassNode node, String name, String descriptor,
                                      String bridgeMethod, int returnOpcode) {
        MethodNode method = requireMethod(node, name, descriptor)
        method.instructions.clear()
        method.tryCatchBlocks.clear()
        method.localVariables?.clear()
        method.visibleLocalVariableAnnotations = null
        method.invisibleLocalVariableAnnotations = null
        method.instructions.add(new MethodInsnNode(INVOKESTATIC, BRIDGE, bridgeMethod,
            returnOpcode == IRETURN ? '()I' : STRING, false))
        method.instructions.add(new InsnNode(returnOpcode))
    }

    private static MethodNode requireMethod(ClassNode node, String name, String descriptor) {
        MethodNode method = node.methods.find { it.name == name && it.desc == descriptor }
        if (method == null) throw new GradleException("TCL identity method missing: ${node.name}.${name}${descriptor}")
        return method
    }

    private static Map<String, byte[]> unpack(byte[] input) {
        Map<String, byte[]> entries = new LinkedHashMap<>()
        new ZipInputStream(new ByteArrayInputStream(input)).withCloseable { zip ->
            ZipEntry entry
            while ((entry = zip.nextEntry) != null) {
                if (!entry.directory) {
                    ByteArrayOutputStream data = new ByteArrayOutputStream()
                    byte[] buffer = new byte[8192]
                    int length
                    while ((length = zip.read(buffer)) != -1) data.write(buffer, 0, length)
                    entries[entry.name] = data.toByteArray()
                }
            }
        }
        return entries
    }

    private static byte[] pack(Map<String, byte[]> entries) {
        ByteArrayOutputStream result = new ByteArrayOutputStream()
        new ZipOutputStream(result).withCloseable { zip ->
            entries.keySet().sort().each { name ->
                ZipEntry entry = new ZipEntry(name)
                entry.time = 0L
                zip.putNextEntry(entry)
                zip.write(entries[name])
                zip.closeEntry()
            }
        }
        return result.toByteArray()
    }

    private static String sha256(byte[] value) {
        MessageDigest.getInstance('SHA-256').digest(value).collect { String.format('%02x', it) }.join()
    }
}
