package com.smart.lsap

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction

import java.security.MessageDigest
import java.util.jar.JarEntry
import java.util.jar.JarInputStream
import java.util.jar.JarOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

abstract class Hq008AarPatchTask extends DefaultTask {
    @InputFile
    abstract RegularFileProperty getInputAar()

    @OutputFile
    abstract RegularFileProperty getOutputAar()

    @Input
    abstract Property<String> getExpectedSha256()

    @Input
    abstract Property<String> getTargetFlavor()

    @TaskAction
    void patch() {
        File input = inputAar.get().asFile
        File output = outputAar.get().asFile
        String actualHash = sha256(input.bytes)
        if (actualHash != expectedSha256.get()) {
            throw new GradleException("HQ008 AAR SHA-256 mismatch for ${targetFlavor.get()}: ${actualHash}")
        }

        byte[] originalClasses = null
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
        if (originalClasses == null) {
            throw new GradleException("classes.jar missing: ${input}")
        }

        Map<String, byte[]> classEntries = [:]
        int modifiedClasses = 0
        JarInputStream jarInput = new JarInputStream(new ByteArrayInputStream(originalClasses))
        JarEntry jarEntry
        while ((jarEntry = jarInput.nextJarEntry) != null) {
            if (jarEntry.directory) continue
            byte[] bytes = readCurrentEntry(jarInput)
            if (jarEntry.name.endsWith('.class')) {
                byte[] patched = LsapClassPatcher.patchHq008Parameters(jarEntry.name, bytes)
                if (!Arrays.equals(bytes, patched)) {
                    modifiedClasses++
                }
                classEntries[jarEntry.name] = patched
            } else {
                classEntries[jarEntry.name] = bytes
            }
        }
        jarInput.close()
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
            'patchVersion=hq008-parameter-normalization-11',
            'audioFocusPolicy=ad_ytx01_sxk-disabled',
            'renderSurfacePolicy=ad_ytx01_sxk-texture-view-for-surface-view',
            'videoOutputFrameRatePolicy=ad_ytx01_sxk-12fps',
            'videoDecoderPolicy=platform-default',
            'audioTrackPolicy=ad_ytx01_sxk-disabled-in-exoplayer-track-selector',
            "originalAarSha256=${actualHash}",
            "patchedClassesJarSha256=${sha256(patchedClasses)}",
            "targetFlavor=${targetFlavor.get()}",
            "modifiedClasses=${modifiedClasses}"
        ].join('\n') + '\n'

        output.parentFile.mkdirs()
        ZipOutputStream zipOutput = new ZipOutputStream(new FileOutputStream(output))
        aarEntries.keySet().sort().each { name ->
            Hq008AarPatchTask.writeZip(zipOutput, name, aarEntries[name])
        }
        Hq008AarPatchTask.writeZip(zipOutput, 'classes.jar', patchedClasses)
        Hq008AarPatchTask.writeZip(
            zipOutput,
            'META-INF/hq008-parameter-patch.properties',
            metadata.getBytes('UTF-8')
        )
        zipOutput.close()
        logger.lifecycle(
            "Patched ${modifiedClasses} HQ008 classes for ${targetFlavor.get()} -> ${output}"
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
        MessageDigest.getInstance('SHA-256').digest(bytes).collect {
            String.format('%02x', it)
        }.join()
    }
}
