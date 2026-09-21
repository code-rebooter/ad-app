package com.smart.lsap

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.tree.ClassNode
import org.objectweb.asm.tree.FieldInsnNode

import java.security.MessageDigest
import java.util.zip.ZipInputStream

/** Prepare the supplied TCL binaries as embedded JARs, resources and consumer rules. */
abstract class TclFusionBundleTask extends DefaultTask {
    private static final String RESOURCE_NAMESPACE = 'com.smart.android.adsdk.fusion'
    private static final Map<String, String> ORIGINAL_HASHES = [
        media: '22426d990e7e27e1fea05b2c014ea4960d3fa5da5efe2b92f945704ab3abc9ca',
        player: '74d2c79e0af1a62697e36bc84929d28504ca264d69856505fdd3606e9a743907',
        cmp: '5a6b19906daaf715c48a079aa185c908b906d23536a709dc57589f85e07f833f'
    ]

    @InputFile abstract RegularFileProperty getBaseAar()
    @InputFile abstract RegularFileProperty getMediaAar()
    @InputFile abstract RegularFileProperty getPlayerAar()
    @InputFile abstract RegularFileProperty getCmpAar()
    @OutputDirectory abstract DirectoryProperty getOutputDirectory()

    @TaskAction
    void prepare() {
        Map<String, byte[]> inputs = [base: baseAar.get().asFile.bytes,
                                     media: mediaAar.get().asFile.bytes,
                                     player: playerAar.get().asFile.bytes,
                                     cmp: cmpAar.get().asFile.bytes]
        ORIGINAL_HASHES.each { name, hash ->
            if (TclFusionBundleTask.sha256(inputs[name]) != hash) {
                throw new GradleException("Embedded TCL ${name} must be the original 2.8.02 AAR")
            }
        }
        Map<String, Map<String, byte[]>> archives = inputs.collectEntries { name, bytes ->
            [(name): TclFusionBundleTask.unpack(bytes)]
        }
        byte[] identity = archives.base['META-INF/fusion-tcl-identity.properties']
        if (identity == null || !new String(identity, 'UTF-8').contains('patchVersion=fusion-tcl-identity-3')) {
            throw new GradleException('Embedded TCL base must contain the registered application identity patch')
        }

        File output = outputDirectory.get().asFile
        project.delete(output)
        output.mkdirs()
        Set<String> namespaces = new TreeSet<>()
        archives.each { name, entries ->
            def manifest = new XmlSlurper(false, false).parseText(new String(entries['AndroidManifest.xml'], 'UTF-8'))
            namespaces.add(manifest.@package.text().replace('.', '/'))
            if (entries.keySet().any { it.startsWith('jni/') || it.startsWith('libs/') }) {
                throw new GradleException("Unexpected native or nested library in TCL ${name}; update the bundler explicitly")
            }
            entries.each { path, bytes ->
                if (path.startsWith('res/')) TclFusionBundleTask.write(output, "${name}/${path}", bytes)
                if (path.startsWith('assets/')) TclFusionBundleTask.write(output, "${name}/${path}", bytes)
            }
        }

        Set<String> resourceReferences = new TreeSet<>()
        archives.each { name, entries ->
            Map<String, byte[]> classes = TclFusionBundleTask.unpack(entries['classes.jar'])
            classes.each { path, bytes ->
                if (path.endsWith('.class')) {
                    ClassNode node = new ClassNode()
                    new ClassReader(bytes).accept(node, 0)
                    boolean changed = false
                    node.methods.each { method ->
                        method.instructions.each { insn ->
                            if (insn instanceof FieldInsnNode) {
                                int separator = insn.owner.indexOf('/R$')
                                if (separator > 0 && namespaces.contains(insn.owner.substring(0, separator))) {
                                    String type = insn.owner.substring(separator + 3)
                                    if (!(insn.desc in ['I', '[I'])) throw new GradleException("Unexpected resource field: ${insn.owner}.${insn.name}")
                                    resourceReferences.add("${type}.${insn.name}")
                                    // The consumer generates one R class for the combined AAR.
                                    // Only its resource field owners change; retain all instructions,
                                    // constants, method signatures and application identity patches.
                                    insn.owner = RESOURCE_NAMESPACE.replace('.', '/') + '/R$' + type
                                    changed = true
                                }
                            }
                        }
                    }
                    if (changed) {
                        ClassWriter writer = new ClassWriter(0)
                        node.accept(writer)
                        classes[path] = writer.toByteArray()
                    }
                }
            }
            TclFusionBundleTask.write(output, "libs/tcl-${name}.jar", TclFusionBundleTask.pack(classes))
        }
        String rules = archives.collect { name, entries ->
            "# Embedded TCL ${name} consumer rules\n" + new String(entries['proguard.txt'], 'UTF-8')
        }.join('\n\n')
        TclFusionBundleTask.write(output, 'consumer-rules.pro', rules.getBytes('UTF-8'))
        TclFusionBundleTask.write(output, 'java-resources/META-INF/fusion-tcl-identity.properties', identity)
        TclFusionBundleTask.write(output, 'java-resources/META-INF/fusion-tcl-resource-references.txt',
            resourceReferences.join('\n').concat('\n').getBytes('UTF-8'))
        logger.lifecycle("Embedded ${archives.size()} TCL JARs, resources and consumer rules; redirected ${resourceReferences.size()} resource fields")
    }

    private static void write(File root, String path, byte[] bytes) {
        File target = new File(root, path)
        if (!target.toPath().normalize().startsWith(root.toPath().normalize())) throw new GradleException("Invalid archive path: ${path}")
        target.parentFile.mkdirs()
        target.bytes = bytes
    }

    private static Map<String, byte[]> unpack(byte[] bytes) {
        Map<String, byte[]> entries = new LinkedHashMap<>()
        new ZipInputStream(new ByteArrayInputStream(bytes)).withCloseable { zip ->
            def entry
            while ((entry = zip.nextEntry) != null) {
                if (!entry.directory) entries[entry.name] = zip.readAllBytes()
            }
        }
        return entries
    }

    private static String sha256(byte[] bytes) {
        MessageDigest.getInstance('SHA-256').digest(bytes).collect { String.format('%02x', it) }.join()
    }

    private static byte[] pack(Map<String, byte[]> entries) {
        ByteArrayOutputStream output = new ByteArrayOutputStream()
        new java.util.zip.ZipOutputStream(output).withCloseable { zip ->
            entries.keySet().sort().each { name ->
                def entry = new java.util.zip.ZipEntry(name)
                entry.time = 0L
                zip.putNextEntry(entry)
                zip.write(entries[name])
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }
}
