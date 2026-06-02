// Copyright 2022 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.analysis

import com.google.devtools.build.lib.vfs.Path

/** Test for [RunfilesSupport].  */
abstract class AbstractRunfilesSupportTest : BuildViewTestCase() {
    protected abstract fun useJdkLauncher(): Boolean

    @Throws(java.lang.Exception::class)
    override fun useConfiguration(vararg args: String?) {
        if (useJdkLauncher()) {
            super.useConfiguration(*args)
        } else {
            super.useConfiguration(
                *com.google.common.collect.ObjectArrays.concat<String?>(
                    args,
                    "--java_launcher=//tools/java/launcher:run_java"
                )
            )
        }
    }

    @Before
    @Throws(java.lang.Exception::class)
    fun createDirectory() {
        scratch.dir(outputBase.getParentDirectory() + "/blaze-bin")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testWorkingDirectory() {
        scratch.file(
            "foo/BUILD",
            """
        load("@rules_cc//cc:cc_test.bzl", "cc_test")
        cc_test(
            name = "bar",
            srcs = ["bar.cc"],
        )
        
        """.trimIndent()
        )
        var foo_bar: ConfiguredTarget?
        useConfiguration("--build_runfile_links")
        // we get expected runfiles directory
        foo_bar = getConfiguredTarget("//foo:bar")
        val workDir1: Path = getRunfilesSupport(foo_bar).getRunfilesDirectory()
        assertThat(workDir1.asFragment().endsWith(PathFragment.create("foo/bar.runfiles"))).isTrue()

        // .. even when we change some options
        useConfiguration("--nobuild_runfile_links")
        // Reconfigured targets.
        foo_bar = getConfiguredTarget("//foo:bar")
        val workDir2: Path? = getRunfilesSupport(foo_bar).getRunfilesDirectory()
        assertThat(workDir2).isEqualTo(workDir1)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testVisitingPackageGroups() {
        scratch.file("honeydew/BUILD", "package_group(name='honeydew')")

        BuildViewTestCase.collectRunfiles(getConfiguredTarget("//honeydew"))
    }
}
