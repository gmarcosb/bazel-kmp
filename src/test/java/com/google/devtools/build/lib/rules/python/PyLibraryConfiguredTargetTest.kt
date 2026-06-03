// Copyright 2018 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.rules.python

import com.google.devtools.build.lib.analysis.ConfiguredTarget

/** Tests for `py_library`.  */
@RunWith(JUnit4::class)
class PyLibraryConfiguredTargetTest : PyBaseConfiguredTargetTestBase("py_library") {
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun pyRuntimeInfoIsNotPresent() {
        scratch.file(
            "pkg/BUILD",  //
            PythonTestUtils.getPyLoad("py_library"),
            "py_library(",
            "    name = 'foo',",
            "    srcs = [':foo.py'],",
            ")"
        )
        Truth.assertThat(PyRuntimeInfo.Companion.fromTargetNullable(getConfiguredTarget("//pkg:foo"))).isNull()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun filesToBuild() {
        scratch.file(
            "pkg/BUILD",  //
            PythonTestUtils.getPyLoad("py_library"),
            "py_library(",
            "    name = 'foo',",
            "    srcs = ['foo.py'])"
        )
        val target: ConfiguredTarget = getConfiguredTarget("//pkg:foo")
        val srcFile: FileConfiguredTarget = getFileConfiguredTarget("//pkg:foo.py")
        assertThat(getFilesToBuild(target).toList()).containsExactly(srcFile.getArtifact())
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun filesToCompile() {
        val lib: ConfiguredTarget =
            scratchConfiguredTarget(
                "pkg",
                "lib",  // build file:
                PythonTestUtils.getPyLoad("py_library"),
                "py_library(name = 'lib', srcs = ['lib.py'], deps = [':bar'])",
                "py_library(name = 'bar', srcs = ['bar.py'], deps = [':baz'])",
                "py_library(name = 'baz', srcs = ['baz.py'])"
            )

        assertThat(
            ActionsTestUtil.baseNamesOf(
                getOutputGroup(lib, OutputGroupInfo.COMPILATION_PREREQUISITES)
            )
        )
            .isEqualTo("baz.py bar.py lib.py")

        // compilationPrerequisites should be included in filesToCompile.
        assertThat(ActionsTestUtil.baseNamesOf(getOutputGroup(lib, OutputGroupInfo.FILES_TO_COMPILE)))
            .isEqualTo("baz.py bar.py lib.py")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun libraryTargetCanBeInPackageWithHyphensIfSourcesAreRemote() {
        scratch.file(
            "pkg/BUILD",  //
            "exports_files(['foo.py'])"
        )
        scratchConfiguredTarget(
            "pkg-with-hyphens",  //
            "foo",
            PythonTestUtils.getPyLoad("py_library"),
            "py_library(",
            "    name = 'foo',",
            "    srcs = ['//pkg:foo.py'])"
        )
        assertNoEvents()
    }
}
