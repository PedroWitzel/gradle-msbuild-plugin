package com.ullink

import org.gradle.api.Plugin
import org.gradle.api.Project

class MsbuildPlugin implements Plugin<Project> {
    void apply(Project project) {
        project.apply plugin: 'base'
        project.tasks.register('msbuild', Msbuild)
        project.tasks.named('clean').configure {
            dependsOn project.tasks.cleanMsbuild
        }
        project.tasks.register('assemblyInfoPatcher', AssemblyInfoVersionPatcher)
    }
}

