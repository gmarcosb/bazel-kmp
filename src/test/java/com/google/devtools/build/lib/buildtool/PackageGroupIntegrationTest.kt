// Copyright 2020 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.lib.buildtool

import com.google.devtools.build.lib.analysis.ViewCreationFailedException

/** Integration test for package groups and visibility.  */
@RunWith(JUnit4::class)
class PackageGroupIntegrationTest : BuildIntegrationTestCase() {
    @Before
    @Throws(java.lang.Exception::class)
    fun setUpToolsConfigMock() {
        AnalysisMock.get().pySupport().setup(mockToolsConfig)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSimpleDeny() {
        write("z/BUILD", "package_group(name='bs', packages=['//z/c'])")
        write("z/a/BUILD", LOAD_FOO_LIBRARY, "foo_library(name='a', visibility=['//z:bs'])")
        write("z/b/BUILD", LOAD_FOO_LIBRARY, "foo_library(name='b', deps=['//z/a:a'])")
        org.junit.Assert.assertThrows<T?>(
            ViewCreationFailedException::class.java,
            org.junit.function.ThrowingRunnable { buildTarget("//z/b:b") })
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSimpleAllow() {
        write("z/BUILD", "package_group(name='bs', packages=['//z/b'])")
        write("z/a/BUILD", LOAD_FOO_LIBRARY, "foo_library(name='a', visibility=['//z:bs'])")
        write("z/b/BUILD", LOAD_FOO_LIBRARY, "foo_library(name='b', deps=['//z/a:a'])")
        buildTarget("//z/b:b")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testNoticesPackageGroupChangedToOk() {
        write("z/BUILD", "package_group(name='bs', packages=['//z/c'])")
        write("z/a/BUILD", LOAD_FOO_LIBRARY, "foo_library(name='a', visibility=['//z:bs'])")
        write("z/b/BUILD", LOAD_FOO_LIBRARY, "foo_library(name='b', deps=['//z/a:a'])")
        org.junit.Assert.assertThrows<T?>(
            ViewCreationFailedException::class.java,
            org.junit.function.ThrowingRunnable { buildTarget("//z/b:b") })

        BuildIntegrationTestCase.Companion.waitForTimestampGranularity()

        write("z/BUILD", "package_group(name='bs', packages=['//z/b'])")
        buildTarget("//z/b:b")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testNoticesPackageGroupChangedToBad() {
        write("z/BUILD", "package_group(name='bs', packages=['//z/b'])")
        write("z/a/BUILD", LOAD_FOO_LIBRARY, "foo_library(name='a', visibility=['//z:bs'])")
        write("z/b/BUILD", LOAD_FOO_LIBRARY, "foo_library(name='b', deps=['//z/a:a'])")
        buildTarget("//z/b:b")

        BuildIntegrationTestCase.Companion.waitForTimestampGranularity()

        write("z/BUILD", "package_group(name='bs', packages=['//z/c'])")
        org.junit.Assert.assertThrows<T?>(
            ViewCreationFailedException::class.java,
            org.junit.function.ThrowingRunnable { buildTarget("//z/b:b") })
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testNoticesChangeInDefaultVisibility() {
        write("z/BUILD", "package_group(name='bs', packages=['//z/c'])")
        write(
            "z/a/BUILD",
            String.format(
                """
            %s
            package(default_visibility = ["//z:bs"])

            foo_library(name = "a")
            
            """.trimIndent(),
                LOAD_FOO_LIBRARY
            )
        )
        write("z/b/BUILD", LOAD_FOO_LIBRARY, "foo_library(name='b', deps=['//z/a:a'])")
        org.junit.Assert.assertThrows<T?>(
            ViewCreationFailedException::class.java,
            org.junit.function.ThrowingRunnable { buildTarget("//z/b:b") })

        BuildIntegrationTestCase.Companion.waitForTimestampGranularity()

        write("z/BUILD", "package_group(name='bs', packages=['//z/b'])")
        buildTarget("//z/b:b")
    }

    // Regression test for bug #16303057: Building a package_group directly results in NPE
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testPackageGroupBuildDirectly() {
        write("npe/BUILD", "package_group(name = 'npe', packages = ['//npe'])")
        buildTarget("//npe")
    }

    companion object {
        private const val LOAD_FOO_LIBRARY = "load('//test_defs:foo_library.bzl', 'foo_library')"
    }
}
