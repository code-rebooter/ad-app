package com.smart.logging

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.*

@CacheableTask
abstract class GeneratePropertyLogTask extends DefaultTask {
    @InputFile @PathSensitive(PathSensitivity.NONE) abstract RegularFileProperty getTemplateFile()
    @Input abstract Property<String> getPackageName()
    @OutputDirectory abstract DirectoryProperty getOutputDirectory()

    @TaskAction void generate() {
        File root = outputDirectory.get().asFile
        project.delete(root)
        File source = new File(root, packageName.get().replace('.', '/') + '/PropertyLog.java')
        source.parentFile.mkdirs()
        source.setText(templateFile.get().asFile.getText('UTF-8').replace('@PACKAGE@', packageName.get()), 'UTF-8')
    }
}
