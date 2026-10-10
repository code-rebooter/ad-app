package com.smart.lsap

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import groovy.json.JsonOutput
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/** Unpack a verified original runtime, preserving every class and its R namespace. */
abstract class TclOriginalRuntimeTask extends DefaultTask {
    @InputFile abstract RegularFileProperty getInputAar()
    @Input abstract Property<String> getExpectedSha256()
    @Input abstract Property<Boolean> getIsolated()
    TclOriginalRuntimeTask() { isolated.convention(false) }
    @OutputDirectory abstract DirectoryProperty getOutputDirectory()

    @TaskAction void prepare() {
        byte[] input = inputAar.get().asFile.bytes
        String hash = MessageDigest.getInstance('SHA-256').digest(input).encodeHex().toString()
        if (hash != expectedSha256.get()) throw new GradleException("Unexpected TCL runtime binary: ${inputAar.get().asFile}")
        File output = outputDirectory.get().asFile
        project.delete(output)
        output.mkdirs()
        new ZipInputStream(new ByteArrayInputStream(input)).withCloseable { zip ->
            def entry
            while ((entry = zip.nextEntry) != null) {
                if (entry.directory) continue
                String path = entry.name
                if (path.startsWith('jni/') || path.startsWith('libs/'))
                    throw new GradleException("Unexpected nested/native input: ${path}")
                if (path == 'classes.jar' || path == 'proguard.txt' || path == 'AndroidManifest.xml' || path.startsWith('res/') || path.startsWith('assets/')) {
                    File target = new File(output, path)
                    if (!target.toPath().normalize().startsWith(output.toPath().normalize())) throw new GradleException("Invalid archive entry: ${path}")
                    target.parentFile.mkdirs()
                    byte[] bytes = zip.readAllBytes()
                    // AGP requires the namespace in Gradle; the original namespace is retained there.
                    if (path == 'AndroidManifest.xml') bytes = new String(bytes, 'UTF-8').replaceAll(/\s+package="[^"]+"/, '').getBytes('UTF-8')
                    if (path == 'classes.jar' && isolated.get()) {
                        def result = TclRuntimeIsolation.isolate(bytes)
                        bytes = result.bytes
                        new File(output, 'isolation-manifest.json').text = JsonOutput.prettyPrint(JsonOutput.toJson(result.report)) + '\n'
                    }
                    target.bytes = bytes
                }
            }
        }
    }
}
