package com.ullink

import com.google.common.io.Files
import org.gradle.api.DefaultTask
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFiles
import org.gradle.api.tasks.TaskAction

class AssemblyInfoVersionPatcher extends DefaultTask {
    @InputFiles
    ListProperty<File> files

    @Input
    final ListProperty<String> projects

    AssemblyInfoVersionPatcher() {
        projects = project.getObjects().listProperty(String)
        projects.set(project.provider({
            project.tasks.msbuild.projects.collect { it.key }
        }))

        files = project.getObjects().listProperty(File)
        files.set(project.provider({
            projects.get()
                .collect { project.tasks.msbuild.projects[it] }
                .collect {
                    if (it.properties.UsingMicrosoftNETSdk == 'true') {
                        it.properties.MSBuildProjectFullPath
                    } else {
                        it?.getItems('Compile')?.find { Files.getNameWithoutExtension(it.name) == 'AssemblyInfo' }
                    }
                }
                .findAll { it != null }
                .unique()
                .collect {
                    project.logger.info("AssemblyInfoPatcher: found file ${it} (${it?.class})")
                    project.file(it)
                }
        }))

        fileVersion = project.getObjects().property(String)
        informationalVersion = project.getObjects().property(String)
        fileVersion.set(project.provider ({ version }))
        informationalVersion.set(project.provider ({ version }))

        project.afterEvaluate {
            if (!version) return
            project.tasks.withType(Msbuild) { Msbuild task ->
                for (def proj in task.projects.collect { e -> e.value }) {
                    def parsedFiles = files.get()

                    if (proj.getItems('Compile')?.intersect(parsedFiles)) {
                        project.logger.info("Found matching AssemblyInfo file, Task[${task.name}] will depend on Task[${this.name}]")
                        task.dependsOn this
                        return
                    }

                    if (proj.properties.MSBuildProjectFullPath && parsedFiles.contains(project.file(proj.properties.MSBuildProjectFullPath))) {
                        project.logger.info("Found matching project file, Task[${task.name}] will depend on Task[${this.name}]")
                        task.dependsOn this
                        return
                    }
                }
            }
        }
    }

    @Input
    String version

    @Input
    final Property<String> fileVersion

    @Input
    final Property<String> informationalVersion

    @Input
    String title = ''

    @Input
    String company = ''

    @Input
    String product = ''

    @Input
    String copyright = ''

    @Input
    String trademark = ''

    @Input
    String assemblyDescription = ''

    @Input
    def charset = 'UTF-8'

    @TaskAction
    void run() {
        files.get().each {
            logger.info("Replacing version attributes in $it")
            replace(it, 'AssemblyVersion', version)
            replace(it, 'AssemblyFileVersion', fileVersion.get())
            replace(it, 'AssemblyInformationalVersion', informationalVersion.get())
            replace(it, 'AssemblyDescription', assemblyDescription)
            replace(it, 'AssemblyTitle', title)
            replace(it, 'AssemblyCompany', company)
            replace(it, 'AssemblyProduct', product)
            replace(it, 'AssemblyCopyright', copyright)
            replace(it, 'AssemblyTrademark', trademark)
        }
    }

    void replace(File file, def name, def value) {
        // only change the assembly values if they specified here (not blank or null)
        // if the parameters are blank, then keep whatever is already in the assemblyInfo file.
        if (!value) {
            return
        }

        def extension = Files.getFileExtension(file.absolutePath)
        switch (extension) {
            case 'fs':
                project.ant.replaceregexp(file: file, match: /^\[<assembly: $name\s*\(".*"\)\s*>\]$/, replace: "[<assembly: ${name}(\"${value}\")>]", byline: true, encoding: charset)
                break
            case 'vb':
                project.ant.replaceregexp(file: file, match: /^\[<assembly: $name\s*\(".*"\)\s*>\]$/, replace: "[<assembly: ${name}(\"${value}\")>]", byline: true, encoding: charset)
                break
            // project file
            case ~/.*proj$/:
                if (name != 'AssemblyVersion' && name != 'AssemblyTitle' && name.startsWith('Assembly')) {
                    name = name.substring('Assembly'.length())
                }
                project.ant.replaceregexp(file: file, match: /<$name>\s*([^\s]+)\s*\<\/$name>$/, replace: "<$name>$value</$name>", byline: true, encoding: charset)
                break
            default:
                project.ant.replaceregexp(file: file, match: /^\[assembly: $name\s*\(".*"\)\s*\]$/, replace: "[assembly: ${name}(\"${value}\")]", byline: true, encoding: charset)
                break

        }
    }
}
