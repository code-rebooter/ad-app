package com.smart.lsap

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction

import java.nio.file.Files
import java.security.MessageDigest
import java.util.jar.JarEntry
import java.util.jar.JarInputStream
import java.util.jar.JarOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

abstract class LsapAarPatchTask extends DefaultTask {
    @InputFile
    abstract RegularFileProperty getInputAar()

    @OutputFile
    abstract RegularFileProperty getOutputAar()

    @Input
    abstract org.gradle.api.provider.Property<String> getExpectedSha256()

    @Input
    abstract org.gradle.api.provider.Property<String> getTargetFlavor()

    @TaskAction
    void patch() {
        File input = inputAar.get().asFile
        File output = outputAar.get().asFile
        String actualHash = sha256(input.bytes)
        if (actualHash != expectedSha256.get()) {
            throw new GradleException("LSAP AAR SHA-256 mismatch for ${targetFlavor.get()}: ${actualHash}")
        }

        byte[] originalClasses
        Map<String, byte[]> aarEntries = [:]
        ZipFile zipFile = new ZipFile(input)
        try {
            zipFile.entries().each { entry ->
                byte[] bytes = zipFile.getInputStream(entry).bytes
                if (entry.name == 'classes.jar') {
                    originalClasses = bytes
                } else if (!entry.directory) {
                    aarEntries[entry.name] = bytes
                }
            }
        } finally {
            zipFile.close()
        }
        if (originalClasses == null) throw new GradleException("classes.jar missing: ${input}")

        Map<String, byte[]> classEntries = [:]
        int modifiedClasses = 0
        Map<String, Integer> networkSurface = [:].withDefault { 0 }
        List<String> residualNetworkCalls = []
        List<String> residualAndroidVersionReads = []
        JarInputStream jarInput = new JarInputStream(new ByteArrayInputStream(originalClasses))
        JarEntry jarEntry
        while ((jarEntry = jarInput.nextJarEntry) != null) {
            if (jarEntry.directory) continue
            byte[] bytes = readCurrentEntry(jarInput)
            if (jarEntry.name.endsWith('.class')) {
                LsapClassPatcher.scanPatchableNetworkCalls(jarEntry.name, bytes).each { category, count ->
                    networkSurface[category] = networkSurface[category] + count
                }
                byte[] patched = LsapClassPatcher.patch(jarEntry.name, bytes)
                if (!Arrays.equals(bytes, patched)) modifiedClasses++
                residualNetworkCalls.addAll(
                    LsapClassPatcher.findResidualNetworkCalls(jarEntry.name, patched)
                )
                residualAndroidVersionReads.addAll(
                    LsapClassPatcher.findResidualAndroidVersionReads(jarEntry.name, patched)
                )
                classEntries[jarEntry.name] = patched
            } else {
                classEntries[jarEntry.name] = bytes
            }
        }
        jarInput.close()
        List<String> requiredNetworkCategories = [
            'systemProperty',
            'nativeLoad',
            'titanStart',
            'dynamicDexInvoke',
            'webViewUa',
            'webViewNetwork',
            'urlOpenConnection',
            'urlConnectionLifecycle',
            'okhttp3Call',
            'udpSend',
            'spctvOkHttpFinal'
        ]
        List<String> missingCategories = requiredNetworkCategories.findAll {
            networkSurface[it] == 0
        }
        if (!missingCategories.isEmpty()) {
            throw new GradleException(
                "LSAP network surface mismatch for ${targetFlavor.get()}; missing ${missingCategories}"
            )
        }
        if (!residualNetworkCalls.isEmpty()) {
            throw new GradleException(
                "Unpatched LSAP network calls remain for ${targetFlavor.get()}:\n" +
                    residualNetworkCalls.take(50).join('\n')
            )
        }
        if (!residualAndroidVersionReads.isEmpty()) {
            throw new GradleException(
                "Unpatched LSAP Android version reads remain for ${targetFlavor.get()}:\n" +
                    residualAndroidVersionReads.take(50).join('\n')
            )
        }
        if (modifiedClasses < 20) {
            throw new GradleException("Only ${modifiedClasses} LSAP classes patched; expected at least 20")
        }

        ByteArrayOutputStream classesOutput = new ByteArrayOutputStream()
        JarOutputStream jarOutput = new JarOutputStream(classesOutput)
        classEntries.keySet().sort().each { name ->
            JarEntry entry = new JarEntry(name)
            entry.time = 0L
            jarOutput.putNextEntry(entry)
            jarOutput.write(classEntries[name])
            jarOutput.closeEntry()
        }
        jarOutput.close()
        byte[] patchedClasses = classesOutput.toByteArray()

        String metadata = [
            'patchVersion=lsap-full-network-audit-3',
            "originalAarSha256=${actualHash}",
            "patchedClassesJarSha256=${sha256(patchedClasses)}",
            "targetFlavor=${targetFlavor.get()}",
            'lsapSdkVersion=1.1.12',
            "modifiedClasses=${modifiedClasses}",
            "networkSurface=${networkSurface.keySet().sort().collect { key -> "${key}:${networkSurface[key]}" }.join(',')}"
        ].join('\n') + '\n'

        output.parentFile.mkdirs()
        ZipOutputStream zipOutput = new ZipOutputStream(new FileOutputStream(output))
        aarEntries.keySet().sort().each { name ->
            LsapAarPatchTask.writeZip(zipOutput, name, aarEntries[name])
        }
        LsapAarPatchTask.writeZip(zipOutput, 'classes.jar', patchedClasses)
        LsapAarPatchTask.writeZip(zipOutput, 'META-INF/lsap-ua-audit.properties', metadata.getBytes('UTF-8'))
        zipOutput.close()
        logger.lifecycle(
            "Patched ${modifiedClasses} LSAP classes for ${targetFlavor.get()} " +
                "with network surface ${networkSurface} -> ${output}"
        )
    }

    private static void writeZip(ZipOutputStream output, String name, byte[] bytes) {
        ZipEntry entry = new ZipEntry(name)
        entry.time = 0L
        output.putNextEntry(entry)
        output.write(bytes)
        output.closeEntry()
    }

    private static byte[] readCurrentEntry(InputStream input) {
        ByteArrayOutputStream output = new ByteArrayOutputStream()
        byte[] buffer = new byte[8192]
        int read
        while ((read = input.read(buffer)) != -1) {
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    private static String sha256(byte[] bytes) {
        MessageDigest.getInstance('SHA-256').digest(bytes).collect { String.format('%02x', it) }.join()
    }
}
