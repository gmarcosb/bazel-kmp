// Copyright 2019 The Bazel Authors. All rights reserved.
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

import com.google.common.truth.Truth
import com.google.devtools.build.lib.analysis.util.AnalysisMock.pySupport
import com.google.devtools.build.lib.analysis.util.AnalysisTestCase.getConfiguredTarget
import com.google.devtools.build.lib.analysis.util.BuildViewTestCase
import com.google.devtools.build.lib.analysis.util.BuildViewTestCase.getConfiguredTarget
import com.google.devtools.build.lib.packages.util.MockObjcSupport.setup
import com.google.devtools.build.lib.rules.python.PyRuntimeInfo
import com.google.devtools.build.lib.rules.python.PythonTestUtils
import com.google.devtools.build.lib.rules.python.PythonVersion
import org.junit.Before
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/** Tests for `py_runtime`.  */
@RunWith(JUnit4::class)
class PyRuntimeConfiguredTargetTest : BuildViewTestCase() {
    @Before
    @Throws(java.lang.Exception::class)
    fun setUpPython() {
        analysisMock.pySupport().setup(mockToolsConfig)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun nonhermeticRuntime() {
        scratch.file(
            "pkg/BUILD",
            PythonTestUtils.getPyLoad("py_runtime"),
            "py_runtime(",
            "    name = 'myruntime',",
            "    interpreter_path = '/system/interpreter',",
            "    python_version = 'PY3',",
            ")"
        )
        val info: PyRuntimeInfo = PyRuntimeInfo.Companion.fromTarget(getConfiguredTarget("//pkg:myruntime"))

        Truth.assertThat(info.getInterpreterPathString()).isEqualTo("/system/interpreter")
        assertThat(info.getInterpreter()).isNull()
        assertThat(info.getFiles()).isNull()
        Truth.assertThat<PythonVersion?>(info.getPythonVersion()).isEqualTo(PythonVersion.PY3)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun cannotUseBothInterpreterAndPath() {
        reporter.removeHandler(failFastHandler)
        scratch.file(
            "pkg/BUILD",
            PythonTestUtils.getPyLoad("py_runtime"),
            "py_runtime(",
            "    name = 'myruntime',",
            "    interpreter = ':myinterpreter',",
            "    interpreter_path = '/system/interpreter',",
            "    python_version = 'PY3',",
            ")"
        )
        getConfiguredTarget("//pkg:myruntime")

        assertContainsEvent(
            "exactly one of the 'interpreter' or 'interpreter_path' attributes must be specified"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun mustUseEitherInterpreterOrPath() {
        reporter.removeHandler(failFastHandler)
        scratch.file(
            "pkg/BUILD",  //
            PythonTestUtils.getPyLoad("py_runtime"),
            "py_runtime(",
            "    name = 'myruntime',",
            "    python_version = 'PY3',",
            ")"
        )
        getConfiguredTarget("//pkg:myruntime")

        assertContainsEvent(
            "exactly one of the 'interpreter' or 'interpreter_path' attributes must be specified"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun interpreterPathMustBeAbsolute() {
        reporter.removeHandler(failFastHandler)
        scratch.file(
            "pkg/BUILD",
            PythonTestUtils.getPyLoad("py_runtime"),
            "py_runtime(",
            "    name = 'myruntime',",
            "    interpreter_path = 'some/relative/path',",
            "    python_version = 'PY3',",
            ")"
        )
        getConfiguredTarget("//pkg:myruntime")

        assertContainsEvent("must be an absolute path")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun cannotSpecifyFilesForNonhermeticRuntime() {
        reporter.removeHandler(failFastHandler)
        scratch.file(
            "pkg/BUILD",
            PythonTestUtils.getPyLoad("py_runtime"),
            "py_runtime(",
            "    name = 'myruntime',",
            "    files = [':myfile'],",
            "    interpreter_path = '/system/interpreter',",
            "    python_version = 'PY3',",
            ")"
        )
        getConfiguredTarget("//pkg:myruntime")

        assertContainsEvent("if 'interpreter_path' is given then 'files' must be empty")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun badPythonVersionAttribute() {
        reporter.removeHandler(failFastHandler)
        scratch.file(
            "pkg/BUILD",
            PythonTestUtils.getPyLoad("py_runtime"),
            "py_runtime(",
            "    name = 'myruntime',",
            "    interpreter_path = '/system/interpreter',",
            "    python_version = 'not a Python version',",
            ")"
        )
        getConfiguredTarget("//pkg:myruntime")

        assertContainsEvent("invalid value in 'python_version' attribute")
    }
}
