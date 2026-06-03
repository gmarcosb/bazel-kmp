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

/** Tests for `py_binary`.  */
@RunWith(JUnit4::class)
class PyBinaryConfiguredTargetTest : PyExecutableConfiguredTargetTestBase("py_binary") {
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun filesToBuild() {
        scratch.file(
            "pkg/BUILD",
            PythonTestUtils.getPyLoad("py_binary"),
            "py_binary(",
            "    name = 'foo',",
            "    srcs = ['foo.py'])"
        )
        val target: ConfiguredTarget? = getOkPyTarget("//pkg:foo")
        val srcFile: FileConfiguredTarget = getFileConfiguredTarget("//pkg:foo.py")
        assertThat(getFilesToBuild(target).toList())
            .containsExactly(getExecutable(target), srcFile.getArtifact())
        assertThat(getExecutable(target).getExecPath().getPathString())
            .containsMatch(TestConstants.PRODUCT_NAME + "-out/.*/bin/pkg/foo")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun defaultMainMustBeInSrcs() {
        checkError(
            "pkg",
            "app",  // error:
            "corresponding default 'app.py' does not appear",  // build file:
            PythonTestUtils.getPyLoad("py_binary"),
            "py_binary(",
            "    name = 'app',",
            "    srcs = ['foo.py', 'bar.py'])"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun explicitMain() {
        scratch.file(
            "pkg/BUILD",
            PythonTestUtils.getPyLoad("py_binary"),
            "py_binary(",
            "    name = 'foo',",
            "    main = 'foo.py',",
            "    srcs = ['foo.py', 'bar.py'])"
        )
        getOkPyTarget("//pkg:foo") // should not fail
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun explicitMainMustBeInSrcs() {
        checkError(
            "pkg",
            "foo",  // error:
            "could not find 'foo.py'",  // build file:
            PythonTestUtils.getPyLoad("py_binary"),
            "py_binary(",
            "    name = 'foo',",
            "    main = 'foo.py',",
            "    srcs = ['bar.py', 'baz.py'])"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun defaultMainCannotBeAmbiguous() {
        scratch.file(
            "pkg1/BUILD",
            PythonTestUtils.getPyLoad("py_binary"),
            "exports_files(['bar.py'])",
            "py_binary(",
            "    name = 'foo',",
            "    srcs = ['bar.py'])"
        )
        checkError(
            "pkg2",
            "bar",  // error:
            java.util.regex.Pattern.compile(".*bar.py.*matches multiple.*"),  // build file:
            PythonTestUtils.getPyLoad("py_binary"),
            "py_binary(",
            "    name = 'bar',",
            "    srcs = ['bar.py', '//pkg1:bar.py'])"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun explicitMainCannotBeAmbiguous() {
        scratch.file(
            "pkg1/BUILD",
            PythonTestUtils.getPyLoad("py_binary"),
            "exports_files(['bar.py'])",
            "py_binary(",
            "    name = 'foo',",
            "    srcs = ['bar.py'])"
        )
        checkError(
            "pkg2",
            "foo",  // error:
            java.util.regex.Pattern.compile(".*bar.py.*matches multiple.*"),  // build file:
            PythonTestUtils.getPyLoad("py_binary"),
            "py_binary(",
            "    name = 'foo',",
            "    main = 'bar.py',",
            "    srcs = ['bar.py', '//pkg1:bar.py'])"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun nameCannotEndInPy() {
        checkError(
            "pkg",
            "foo.py",  // error:
            "name must not end in '.py'",  // build file:
            PythonTestUtils.getPyLoad("py_binary"),
            "py_binary(",
            "    name = 'foo.py',",
            "    srcs = ['bar.py'])"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun defaultMainCanBeGenerated() {
        scratch.file(
            "pkg/BUILD",
            PythonTestUtils.getPyLoad("py_binary"),
            "genrule(",
            "    name = 'gen_py',",
            "    cmd = 'touch $(location foo.py)',",
            "    outs = ['foo.py'])",
            "py_binary(",
            "    name = 'foo',",
            "    srcs = [':gen_py'])"
        )
        getOkPyTarget("//pkg:foo") // should not fail
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun defaultMainCanHaveMultiplePathSegments() {
        // Regression test for crash caused by use of getChild on a multi-segment rule name.
        scratch.file(
            "pkg/BUILD",
            PythonTestUtils.getPyLoad("py_binary"),
            "py_binary(",
            "    name = 'foo/bar',",
            "    srcs = ['foo/bar.py'])"
        )
        getOkPyTarget("//pkg:foo/bar") // should not fail
    } // TODO(brandjon): Add tests for content of stub Python script (particularly for choosing python
    // 2 or 3).
}
