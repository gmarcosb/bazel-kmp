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

import com.google.devtools.build.lib.actions.Artifact

/** Tests for [PyRuntimeInfo].  */
@RunWith(JUnit4::class)
class PyRuntimeInfoTest : BuildViewTestCase() {
    private var dummyInterpreter: Artifact? = null
    private var dummyFile: Artifact? = null

    @Before
    @Throws(java.lang.Exception::class)
    fun setUp() {
        dummyInterpreter = getSourceArtifact("dummy_interpreter")
        dummyFile = getSourceArtifact("dummy_file")
    }

    @Throws(java.lang.Exception::class)
    private fun writeCreatePyRuntimeInfo(vararg lines: String?) {
        val builder: java.lang.StringBuilder = java.lang.StringBuilder()
        for (line in lines) {
            builder.append("    ").append(line).append(",\n")
        }
        scratch.overwriteFile(
            "defs.bzl",
            PythonTestUtils.getPyLoad("PyRuntimeInfo"),
            "def _impl(ctx):",
            "    dummy_file = ctx.file.dummy_file",
            "    dummy_interpreter = ctx.file.dummy_interpreter",
            "    info = PyRuntimeInfo(",
            builder.toString(),
            "    )",
            "    return [info]",
            "create_py_runtime_info = rule(implementation=_impl, attrs={",
            "  'dummy_file': attr.label(default='dummy_file', allow_single_file=True),",
            "  'dummy_interpreter': attr.label(default='dummy_interpreter', allow_single_file=True),",
            "})",
            ""
        )
        scratch.overwriteFile(
            "BUILD",
            "load(':defs.bzl', 'create_py_runtime_info')",
            "create_py_runtime_info(name='subject')"
        )
    }

    @get:Throws(java.lang.Exception::class)
    private val pyRuntimeInfo: PyRuntimeInfo
        get() = PyRuntimeInfo.Companion.fromTarget(getConfiguredTarget("//:subject"))

    @Throws(java.lang.Exception::class)
    private fun assertContainsError(pattern: String?) {
        reporter.removeHandler(failFastHandler) // expect errors

        getConfiguredTarget("//:subject")

        // The Starlark messages are within a long multi-line traceback string, so
        // add the implicit .* for convenience.
        // NOTE: failures and events are accumulated between getConfiguredTarget() calls.
        assertContainsEvent(java.util.regex.Pattern.compile(".*" + pattern))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun starlarkConstructor_inBuildRuntime() {
        writeCreatePyRuntimeInfo(
            "interpreter = dummy_interpreter",
            "files = depset([dummy_file])",
            "python_version = 'PY3'"
        )

        val info: PyRuntimeInfo = this.pyRuntimeInfo

        Truth.assertThat(info.getInterpreterPathString()).isNull()
        assertThat(info.getInterpreter()).isEqualTo(dummyInterpreter)
        assertHasOrderAndContainsExactly(info.getFiles(), Order.STABLE_ORDER, dummyFile)
        Truth.assertThat<PythonVersion?>(info.getPythonVersion()).isEqualTo(PythonVersion.PY3)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun starlarkConstructor_platformRuntime() {
        writeCreatePyRuntimeInfo("interpreter_path = '/system/interpreter'", "python_version = 'PY3'")

        val info: PyRuntimeInfo = this.pyRuntimeInfo

        Truth.assertThat(info.getInterpreterPathString()).isEqualTo("/system/interpreter")
        assertThat(info.getInterpreter()).isNull()
        assertThat(info.getFiles()).isNull()
        Truth.assertThat<PythonVersion?>(info.getPythonVersion()).isEqualTo(PythonVersion.PY3)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun starlarkConstructor_filesDefaultsToEmpty() {
        writeCreatePyRuntimeInfo("    interpreter = dummy_interpreter", "    python_version = 'PY3'")

        val info: PyRuntimeInfo = this.pyRuntimeInfo

        assertHasOrderAndContainsExactly(info.getFiles(), Order.STABLE_ORDER)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun starlarkConstructorErrors_inBuildXorPlatform_noInterpreter() {
        writeCreatePyRuntimeInfo("python_version = 'PY3'")

        assertContainsError("exactly one of.*interpreter.*interpreter_path.*must be specified")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun starlarkConstructorErrors_inBuildXorPlatform_bothInterpreters() {
        writeCreatePyRuntimeInfo(
            "interpreter_path = '/system/interpreter'",
            "interpreter = dummy_interpreter",
            "python_version = 'PY3'"
        )

        assertContainsError("exactly one of.*interpreter.*interpreter_path.*must be specified")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun starlarkConstructorErrors_files_invalidValue() {
        writeCreatePyRuntimeInfo(
            "interpreter = dummy_interpreter",  //
            "files = 'abc'",
            "python_version = 'PY3'"
        )

        assertContainsError("invalid files:.*got.*string.*want.*depset")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun starlarkConstructorErrors_files_cannotSpecify() {
        writeCreatePyRuntimeInfo(
            "interpreter_path = '/system/interpreter'",
            "files = depset([dummy_file])",
            "python_version = 'PY3'"
        )

        assertContainsError("cannot specify 'files' if 'interpreter_path' is given")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun starlarkConstructorErrors_pythonVersion_missingArg() {
        writeCreatePyRuntimeInfo("interpreter_path = '/system/interpreter'")

        assertContainsError("missing.*argument: python_version")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun starlarkConstructorErrors_pythonVersion_invalidValue() {
        writeCreatePyRuntimeInfo(
            "interpreter_path = '/system/interpreter'",  //
            "python_version = 'not a Python version'"
        )

        assertContainsError("invalid python_version")
    }

    companion object {
        /** We need this because `NestedSet`s don't have value equality.  */
        private fun assertHasOrderAndContainsExactly(
            set: NestedSet<*>, order: Order?, vararg values: Any?
        ) {
            assertThat(set.getOrder()).isEqualTo(order)
            assertThat(set.toList()).containsExactly(values)
        }
    }
}
