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

import com.google.devtools.build.lib.analysis.util.AnalysisTestCase.getConfiguredTarget
import com.google.devtools.build.lib.analysis.util.AnalysisTestCase.useConfiguration
import com.google.devtools.build.lib.analysis.util.BuildViewTestCase
import com.google.devtools.build.lib.analysis.util.BuildViewTestCase.getConfiguredTarget
import com.google.devtools.build.lib.analysis.util.BuildViewTestCase.useConfiguration
import com.google.devtools.build.lib.rules.python.PythonTestUtils
import com.google.devtools.build.lib.testutil.TestConstants
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/**
 * Tests for the standard Python toolchain definitions.
 * 
 * 
 * This covers invariants of the toolchain definitions, and the interaction with a user-defined
 * consuming rule, but not the behavior of `py_binary` and `py_test`. Those tests are
 * under [PyExecutableConfiguredTargetTestBase] instead.
 */
@RunWith(JUnit4::class)
class PythonToolchainTest : BuildViewTestCase() {
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun userDefinedConsumerUsingToolchainResolution() {
        // A simple platform with unique constraint values.
        scratch.file(
            "platforms/BUILD",
            "constraint_value(",
            "    name = 'my_py2_path',",
            "    constraint_setting = '" + PY2_PATH_CONSTRAINT + "',",
            ")",
            "constraint_value(",
            "    name = 'my_py3_path',",
            "    constraint_setting = '" + PY3_PATH_CONSTRAINT + "',",
            ")",
            "platform(",
            "    name = 'my_platform',",
            "    constraint_values = [':my_py2_path', ':my_py3_path'],",
            ")"
        )
        // A user rule that requires the Python toolchain type and spits out the resulting info.
        scratch.file(
            "pkg/rules.bzl",
            "def _myrule_impl(ctx):",
            "    info = ctx.toolchains['" + TOOLCHAIN_TYPE + "']",
            "    print('PY3 path: ' + info.py3_runtime.interpreter_path)",
            "myrule = rule(",
            "    implementation = _myrule_impl,",
            "    toolchains = ['" + TOOLCHAIN_TYPE + "'],",
            ")"
        )
        // A toolchain implementation and an instance of the rule that will use it.
        scratch.file(
            "pkg/BUILD",
            PythonTestUtils.getPyLoad("py_runtime"),
            PythonTestUtils.getPyLoad("py_runtime_pair"),
            "load(':rules.bzl', 'myrule')",
            "py_runtime(",
            "    name = 'my_py3_runtime',",
            "    interpreter_path = '/system/python3',",
            "    python_version = 'PY3',",
            ")",
            "py_runtime_pair(",
            "    name = 'my_py_runtime_pair',",
            "    py3_runtime = ':my_py3_runtime',",
            ")",
            "toolchain(",
            "    name = 'my_toolchain',",
            "    target_compatible_with = ['//platforms:my_py2_path', '//platforms:my_py3_path'],",
            "    toolchain = ':my_py_runtime_pair',",
            "    toolchain_type = '" + TOOLCHAIN_TYPE + "',",
            ")",
            "myrule(",
            "    name = 'mytarget',",
            ")"
        )
        // Register the toolchain and ask for the platform.
        useConfiguration(
            "--platforms=//platforms:my_platform", "--extra_toolchains=//pkg:my_toolchain"
        )

        getConfiguredTarget("//pkg:mytarget")
        assertContainsEvent("PY3 path: /system/python3")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun missingProviderInToolchainAttribute() {
        reporter.removeHandler(failFastHandler)
        scratch.file(
            "pkg/BUILD",
            PythonTestUtils.getPyLoad("py_runtime_pair"),
            "filegroup(",
            "    name = 'not_a_runtime',",
            "    srcs = ['not_a_runtime.sh'],",
            ")",
            "py_runtime_pair(",
            "    name = 'bad_py_runtime_pair',",
            "    py3_runtime = ':not_a_runtime',",
            ")"
        )
        getConfiguredTarget("//pkg:bad_py_runtime_pair")
        assertContainsEvent("'//pkg:not_a_runtime' does not have mandatory providers: 'PyRuntimeInfo'")
    }

    companion object {
        private val TOOLCHAIN_TYPE = TestConstants.TOOLS_REPOSITORY.toString() + "//tools/python:toolchain_type"

        private val PY2_PATH_CONSTRAINT =
            TestConstants.TOOLS_REPOSITORY.toString() + "//tools/python:py2_interpreter_path"

        private val PY3_PATH_CONSTRAINT =
            TestConstants.TOOLS_REPOSITORY.toString() + "//tools/python:py3_interpreter_path"
    }
}
