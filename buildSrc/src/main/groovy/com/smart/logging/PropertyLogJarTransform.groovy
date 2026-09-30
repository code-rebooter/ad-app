package com.smart.logging

import org.gradle.api.artifacts.transform.*
import org.gradle.api.file.FileSystemLocation
import org.gradle.api.provider.*
import org.gradle.api.tasks.*
import java.util.zip.*

/** L8 adds JDK compatibility classes after normal Android class instrumentation. */
@CacheableTransform
abstract class PropertyLogJarTransform implements TransformAction<PropertyLogJarTransform.Parameters> {
    interface Parameters extends TransformParameters {
        @Input Property<String> getBridgeClass()
    }
    @InputArtifact @PathSensitive(PathSensitivity.NAME_ONLY)
    abstract Provider<FileSystemLocation> getInputArtifact()

    @Override void transform(TransformOutputs outputs) {
        File input = inputArtifact.get().asFile
        File output = outputs.file(input.name)
        new ZipFile(input).withCloseable { zip ->
            new ZipOutputStream(new FileOutputStream(output)).withCloseable { result ->
                zip.entries().each { entry ->
                    if (entry.directory || entry.name ==~ /META-INF\/.*\.(SF|RSA|DSA)/) return
                    byte[] bytes = zip.getInputStream(entry).withCloseable { it.bytes }
                    if (entry.name.endsWith('.class')) {
                        bytes = PropertyLogClassVisitor.patch(bytes, parameters.bridgeClass.get())
                    }
                    ZipEntry copy = new ZipEntry(entry.name)
                    copy.time = 0L
                    result.putNextEntry(copy)
                    result.write(bytes)
                    result.closeEntry()
                }
            }
        }
    }
}
