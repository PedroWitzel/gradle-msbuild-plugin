package com.ullink

import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.internal.os.OperatingSystem
import org.gradle.testfixtures.ProjectBuilder
import org.junit.Before
import org.junit.Test

import static org.junit.Assert.assertNotNull
import static org.junit.Assert.assertThrows

class XbuildLocateTest {

    @Before
    public void beforeMethod() {
        org.junit.Assume.assumeTrue OperatingSystem.current().unix
        org.junit.Assume.assumeTrue "mono --version".execute().in.text != null
    }

    @Test
    public void testXBuildCanBeFound() {
        def resolver = new XbuildResolver()

        // Unspecified version
        Project p = ProjectBuilder.builder().build()
        p.apply plugin: MsbuildPlugin
        def xbuildDir = resolver.getXBuildDir(p.tasks.msbuild)
        assertNotNull(xbuildDir)

        // Specified version
        p = ProjectBuilder.builder().build()
        p.apply plugin: MsbuildPlugin
        def xbuildVersion = new BigDecimal(new File(xbuildDir).name)
        p.msbuild {
            version = xbuildVersion
        }
        xbuildDir = resolver.getXBuildDir(p.tasks.msbuild)
        assertNotNull(xbuildDir)
    }

    @Test
    public void givenInvalidMSBuildVersion_throwsException() {
        def resolver = new XbuildResolver()
        Project p = ProjectBuilder.builder().build()
        p.apply plugin: MsbuildPlugin
        p.msbuild {
            version = 999.3
        }

        assertThrows("Cannot find an xbuild binary", GradleException.class, {
            resolver.getXBuildDir(p.tasks.msbuild)
        })

    }
}
