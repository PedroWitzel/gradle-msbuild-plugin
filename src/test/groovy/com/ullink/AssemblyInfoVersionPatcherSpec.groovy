/*************************************************************************
 * ULLINK CONFIDENTIAL INFORMATION
 * _______________________________
 *
 * All Rights Reserved.
 *
 * NOTICE: This file and its content are the property of Ullink. The
 * information included has been classified as Confidential and may
 * not be copied, modified, distributed, or otherwise disseminated, in
 * whole or part, without the express written permission of Ullink.
 ************************************************************************/
package com.ullink

import org.gradle.testfixtures.ProjectBuilder
import org.junit.Test

class AssemblyInfoVersionPatcherSpec {

    @Test
    void AssemblyInfoCanBePatched() {

        def project = ProjectBuilder.builder().build()
        def task = project.tasks.register('assemblyPatcher', AssemblyInfoVersionPatcher).get()

        def assemblyInfo = new File(task.temporaryDir, 'AssemblyInfo.cs')
        assemblyInfo.newWriter().withWriter {
            it << this.getClass().getResource('AssemblyInfo.cs').text
        }

        task.with {
            files.set([project.file(assemblyInfo)])
            fileVersion.set 'file version'
            version = 'version'
            assemblyDescription = 'description'
            company = 'company'
            copyright = 'copyright'
            trademark = 'trademark'
            title = 'title'
            product = 'product'
        }


        task.run()

        assemblyInfo.text.with {
            contains('[assembly: AssemblyTitle("title")]')
            contains('[assembly: AssemblyDescription("description")]')
            contains('[assembly: AssemblyCompany("company")]')
            contains('[assembly: AssemblyProduct("product")]')
            contains('[assembly: AssemblyCopyright("copyright")]')
            contains('[assembly: AssemblyTrademark("trademark")]')
            contains('[assembly: AssemblyVersion("version")]')
            contains('[assembly: AssemblyFileVersion("file version")]')
        }
    }

    @Test
    void coreCsprojWillBeReplaced() {

        def project = ProjectBuilder.builder().build()
        def task = project.tasks.register('assemblyPatcher', AssemblyInfoVersionPatcher).get()

        def assemblyInfo = new File(task.temporaryDir, 'core.csproj')
        assemblyInfo.newWriter().withWriter {
            it << this.getClass().getResource('core.csproj').text
        }

        task.with {
            files.set([project.file(assemblyInfo)])
            fileVersion.set 'file version'
            version = 'version'
            assemblyDescription = 'description'
            company = 'company'
            copyright = 'copyright'
            title = 'title'
            product = 'product'
        }

        task.run()

        // trademark is not yet supported in new format
        assemblyInfo.text.with {
            contains('    <AssemblyVersion>version</AssemblyVersion>')
            contains('    <Company>company</Company>')
            contains('    <Product>product</Product>')
            contains('    <Description>description</Description>')
            contains('    <FileVersion>file version</FileVersion>')
            contains('    <Copyright>copyright</Copyright>')
            contains('    <Title>title</Title>')
        }
    }
}
