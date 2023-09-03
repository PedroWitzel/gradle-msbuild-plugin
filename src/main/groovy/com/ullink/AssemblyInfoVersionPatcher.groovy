package com.ullink

import com.google.common.io.Files
import org.gradle.api.DefaultTask
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.TaskAction

abstract class AssemblyInfoVersionPatcher extends DefaultTask {
    @InputFiles
    abstract ListProperty<File> getFiles()

    @Input
    abstract ListProperty<String> getProjects()

    AssemblyInfoVersionPatcher() {
        projects.set(project.provider({
            project.tasks.msbuild.projects.collect { it.key }
        }))

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

        fileVersion.set(version.getOrElse(''))
        informationalVersion.set(version.getOrElse(''))

        project.afterEvaluate {
            if (!version.isPresent()) return
            project.tasks.withType(Msbuild).configureEach { Msbuild msbuildTask ->
                for (def proj in msbuildTask.projects.collect { e -> e.value }) {
                    def parsedFiles = files.get().collect { it.asFile }

                    if (proj.getItems('Compile')?.intersect(parsedFiles)) {
                        project.logger.info("Found matching AssemblyInfo file, Task[${msbuildTask.name}] will depend on Task[${this.name}]")
                        msbuildTask.dependsOn this
                        return
                    }

                    if (proj.properties.MSBuildProjectFullPath && parsedFiles.contains(project.file(proj.properties.MSBuildProjectFullPath))) {
                        project.logger.info("Found matching project file, Task[${msbuildTask.name}] will depend on Task[${this.name}]")
                        msbuildTask.dependsOn this
                        return
                    }
                }
            }
        }
    }

    @Input
    abstract Property<String> getVersion()

    @Input
    abstract Property<String> getFileVersion()

    @Input
    abstract Property<String> getInformationalVersion()

    @Input
    abstract Property<String> getTitle()

    @Input
    abstract Property<String> getCompany()

    @Input
    abstract Property<String> getProduct()

    @Input
    abstract Property<String> getCopyright()

    @Input
    abstract Property<String> getTrademark()

    @Input
    abstract Property<String> getAssemblyDescription()

    @Input
    abstract Property<String> getCharset()

    @TaskAction
    void run() {
        files.get().each {
            logger.info("Replacing version attributes in $it")
            replace(it, 'AssemblyVersion', version.getOrElse(''))
            replace(it, 'AssemblyFileVersion', fileVersion.getOrElse(''))
            replace(it, 'AssemblyInformationalVersion', informationalVersion.getOrElse(''))
            replace(it, 'AssemblyDescription', assemblyDescription.getOrElse(''))
            replace(it, 'AssemblyTitle', title.getOrElse(''))
            replace(it, 'AssemblyCompany', company.getOrElse(''))
            replace(it, 'AssemblyProduct', product.getOrElse(''))
            replace(it, 'AssemblyCopyright', copyright.getOrElse(''))
            replace(it, 'AssemblyTrademark', trademark.getOrElse(''))
        }
    }

    void replace(File file, def name, def value) {
        // only change the assembly values if they specified here (not blank or null)
        // if the parameters are blank, then keep whatever is already in the assemblyInfo file.
        if (!value) {
            return
        }

        def extension = Files.getFileExtension(file.absolutePath)
        final String encoding = charset.getOrElse('UTF-8')
        switch (extension) {
            case 'fs':
                project.ant.replaceregexp(file: file, match: /^\[<assembly: $name\s*\(".*"\)\s*>\]$/, replace: "[<assembly: ${name}(\"${value}\")>]", byline: true, encoding: encoding)
                break
            case 'vb':
                project.ant.replaceregexp(file: file, match: /^\[<assembly: $name\s*\(".*"\)\s*>\]$/, replace: "[<assembly: ${name}(\"${value}\")>]", byline: true, encoding: encoding)
                break
                // project file
            case ~/.*proj$/:
                if (name != 'AssemblyVersion' && name != 'AssemblyTitle' && name.startsWith('Assembly')) {
                    name = name.substring('Assembly'.length())
                }
                project.ant.replaceregexp(file: file, match: /<$name>\s*([^\s]+)\s*\<\/$name>$/, replace: "<$name>$value</$name>", byline: true, encoding: encoding)
                break
            default:
                project.ant.replaceregexp(file: file, match: /^\[assembly: $name\s*\(".*"\)\s*\]$/, replace: "[assembly: ${name}(\"${value}\")]", byline: true, encoding: encoding)
                break

        }
    }
}
