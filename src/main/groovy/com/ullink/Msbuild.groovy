package com.ullink

import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.InputDirectory

import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.nio.file.Files
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.TaskAction
import org.gradle.internal.os.OperatingSystem


abstract class Msbuild extends DefaultTask {

    @Input
    @Optional
    abstract Property<String> getVersion()

    @Input
    @Optional
    abstract Property<String> getMsbuildDir()

    @Input
    @Optional
    abstract Property<Object> getSolutionFile()

    @Input
    @Optional
    abstract Property<Object> getProjectFile()

    @Input
    @Optional
    abstract Property<String> getLoggerAssembly()

    @Input
    @Optional
    abstract Property<Boolean> getOptimize()

    @Input
    @Optional
    abstract Property<Boolean> getDebugSymbols()

    @Input
    @Optional
    abstract Property<String> getDebugType()

    @Input
    @Optional
    abstract Property<String> getPlatform()

    @InputDirectory
    @Optional
    abstract DirectoryProperty getDestinationDir()

    @InputDirectory
    @Optional
    abstract DirectoryProperty getIntermediateDir()

    @Input
    @Optional
    abstract Property<Boolean> getGenerateDoc()

    @Input
    @Optional
    abstract Property<String> getProjectName()

    @Input
    @Optional
    abstract Property<String> getConfiguration()

    @Input
    @Optional
    abstract ListProperty<String> getDefineConstants()

    @Input
    @Optional
    abstract ListProperty<String> getTargets()

    @Input
    @Optional
    abstract Property<String> getVerbosity()

    @Input
    @Optional
    abstract MapProperty<String, Object> parameters

    @Input
    @Optional
    abstract MapProperty<String, ProjectFileParser> allProjects

    @Input
    @Optional
    abstract Property<String> getExecutable()

    @Internal
    ProjectFileParser projectParsed

    @Internal
    IExecutableResolver resolver

    @Internal
    Boolean parseProject = true

    Msbuild() {
        description = 'Executes MSBuild on the specified project/solution'
        resolver = OperatingSystem.current().windows ? new MsbuildResolver() : new XbuildResolver()
        projectName.convention(project.name)

        File solution = project.file(project.name + '.sln')
        solutionFile.convention(
                solution.exists() ? solution : null
        )

        File csproj = project.file(project.name + '.csproj')
        projectFile.convention(
                csproj.exists() ? csproj : null
        )
    }

    @Internal
    boolean isSolutionBuild() {
        !projectFile.isPresent() && getSolutionFile().isPresent()
    }

    @Internal
    boolean isProjectBuild() {
        !solutionFile.isPresent() && getProjectFile().isPresent()
    }

    @Internal
    File getRootedProjectFile() {
        project.file(getProjectFile().get())
    }

    @Internal
    File getRootedSolutionFile() {
        project.file(getSolutionFile().get())
    }

    @Internal
    Map<String, ProjectFileParser> getProjects() {
        resolveProject()
        allProjects.get()
    }

    @Internal
    ProjectFileParser getMainProject() {
        if (resolveProject()) {
            projectParsed
        } else {
            logger.warn "Main project was resolved to null due to a parse error. The .sln file might be missing or incorrectly named."
            throw new GradleException("Failed to resolve main project. Make sure the name of the .sln file matches the one of the repository")
        }
    }

    def parseProjectFile(def file) {
        logger.info "Parsing file $file ..."
        if (!file.exists()) {
            throw new GradleException("Project/Solution file $file does not exist")
        }
        File tempDir = Files.createTempDirectory(temporaryDir.toPath(), 'ProjectFileParser').toFile()

        this.class.getResourceAsStream('/META-INF/ProjectFileParser.zip').withCloseable {
            ZipInputStream zis = new ZipInputStream(it)
            ZipEntry ze = zis.getNextEntry()
            while (ze != null) {
                String fileName = ze.getName()
                if (ze.isDirectory()) {
                    File subFolder = new File(tempDir, fileName)
                    subFolder.mkdir()
                    ze = zis.getNextEntry()
                    continue
                }
                File target = new File(tempDir, fileName)
                target.newOutputStream().leftShift(zis).close()
                ze = zis.getNextEntry()
            }
        }

        def executable = new File(tempDir, 'ProjectFileParser.exe')
        def stderrBuffer = new ByteArrayOutputStream()
        def stdoutBuffer = new ByteArrayOutputStream()
        def stdinBuffer = new ByteArrayInputStream()

        def result = project.providers.exec {
            commandLine executable.getCanonicalPath(), file.toString()
            errorOutput = stderrBuffer
            standardOutput = stdoutBuffer
            standardInput = stdinBuffer
        }.getResult()

        try {
            def initPropertiesJson = JsonOutput.toJson(getInitProperties())
            logger.debug "Sending ${initPropertiesJson} to ProjectFileParser"
            stdoutBuffer.leftShift(initPropertiesJson).close()
            return new JsonSlurper().parseText(new FilterJson(stdinBuffer).toString())
        } finally {
            def hasErrors = result.get().getExitValue() != 0

            logger.debug 'Output from ProjectFileParser: '
            stdoutBuffer.toString().eachLine { line ->
                logger.debug line
            }
            stderrBuffer.toString().eachLine { line ->
                if (hasErrors)
                    logger.error line
                else
                    logger.debug line
            }

            stdoutBuffer.close()
            stderrBuffer.close()
            stdinBuffer.close()

            if (hasErrors) {
                throw new GradleException('Project file parsing failed')
            }
        }
    }

    boolean resolveProject() {
        if (projectParsed == null && parseProject) {
            if (isSolutionBuild()) {
                def rootSolutionFile = getRootedSolutionFile()
                def result = parseProjectFile(rootSolutionFile)
                allProjects.set(result.collectEntries {
                    [it.key, new ProjectFileParser(msbuild: this, eval: it.value)]
                } as Map<String, ProjectFileParser>)

                def projectName = getProjectName()
                if (projectName.isPresent() || projectName.get().isEmpty()) {
                    parseProject = false
                } else {
                    projectParsed = allProjects.get()[projectName.get()] as ProjectFileParser
                    if (projectParsed == null) {
                        parseProject = false
                        logger.warn "Project ${projectName} not found in solution"
                    }
                }
            } else if (isProjectBuild()) {
                def rootProjectFile = getRootedProjectFile()
                def result = parseProjectFile(rootProjectFile)
                allProjects.set(result.collectEntries {
                    [it.key, new ProjectFileParser(msbuild: this, eval: it.value)]
                } as Map<String, ProjectFileParser>)
                projectParsed = allProjects.get().values().first()
                if (!projectParsed) {
                    logger.warn "Parsed project ${rootProjectFile} is null (not a solution / project build)"
                }
            }
        }

        projectParsed != null
    }

    void setTarget(String s) {
        targets.set([s])
    }

    @TaskAction
    def build() {
        project.exec {
            commandLine = getCommandLineArgs()
        }
    }

    @Internal
    List<String> getCommandLineArgs() {
        resolver.setupExecutable(this)

        if (!msbuildDir.isPresent()) {
            throw new GradleException("${executable.get()} not found")
        }
        List<String> commandLineArgs = resolver
                .executeDotNet(new File(msbuildDir.get(), executable.get()))
                .command()

        commandLineArgs += '/nologo'

        if (isSolutionBuild()) {
            commandLineArgs += getRootedSolutionFile()
        } else if (isProjectBuild()) {
            commandLineArgs += getRootedProjectFile()
        }

        if (loggerAssembly.isPresent()) {
            commandLineArgs += '/l:' + loggerAssembly.get()
        }
        if (targets.isPresent() && !targets.get().isEmpty()) {
            commandLineArgs += '/t:' + targets.get().join(';')
        }

        String verb = getMSVerbosity(verbosity.getOrNull())
        if (verb) {
            commandLineArgs += '/v:' + verb
        }

        def cmdParameters = getInitProperties()

        cmdParameters.each {
            if (it.value) {
                commandLineArgs += '/p:' + it.key + '=' + it.value
            }
        }

        def extMap = getExtensions()?.getExtraProperties()?.getProperties()
        if (extMap != null) {
            commandLineArgs += extMap.collect { k, v ->
                v ? "/$k:$v" : "/$k"
            }
        }

        commandLineArgs
    }

    String getMSVerbosity(String verbosity) {
        if (verbosity) return verbosity
        if (logger.debugEnabled) return 'detailed'
        if (logger.infoEnabled) return 'normal'
        return 'minimal' // 'quiet'
    }

    @Internal
    Map getInitProperties() {
        def cmdParameters = new HashMap<String, Object>()
        if (parameters?.isPresent()) {
            cmdParameters.putAll(parameters.get())
        }
        cmdParameters.Project = getProjectName().get()
        cmdParameters.GenerateDocumentation = generateDoc.getOrNull()
        cmdParameters.DebugType = debugType.getOrNull()
        cmdParameters.Optimize = optimize.getOrNull()
        cmdParameters.DebugSymbols = debugSymbols.getOrNull()
        cmdParameters.OutputPath = destinationDir.getOrNull()
        cmdParameters.IntermediateOutputPath = intermediateDir.getOrNull()
        cmdParameters.Configuration = configuration.getOrNull()
        cmdParameters.Platform = platform.getOrNull()
        if (defineConstants.isPresent() && !defineConstants.get().isEmpty()) {
            cmdParameters.DefineConstants = defineConstants.get().join(';')
        }
        def iter = cmdParameters.iterator()
        while (iter.hasNext()) {
            Map.Entry<String, Object> entry = iter.next()
            if (entry.value == null) {
                iter.remove()
            } else if (entry.value instanceof File) {
                entry.value = entry.value.path
            } else if (!entry.value instanceof String) {
                entry.value = entry.value.toString()
            }
        }
        ['OutDir', 'OutputPath', 'BaseIntermediateOutputPath', 'IntermediateOutputPath', 'PublishDir'].each {
            if (cmdParameters[it] && !cmdParameters[it].endsWith('\\')) {
                cmdParameters[it] += '\\'
            }
        }
        return cmdParameters
    }
}
