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

/** Tests for [PyInfo].  */
@RunWith(JUnit4::class)
class PyInfoTest : BuildViewTestCase() {
    private var dummyArtifact: Artifact? = null

    @Before
    @Throws(java.lang.Exception::class)
    fun setUp() {
        dummyArtifact = getSourceArtifact("dummy")
    }

    @Throws(java.lang.Exception::class)
    private fun writeCreatePyInfo(vararg lines: String?) {
        val builder: java.lang.StringBuilder = java.lang.StringBuilder()
        for (line in lines) {
            builder.append("    ").append(line).append(",\n")
        }
        scratch.overwriteFile(
            "defs.bzl",
            PythonTestUtils.getPyLoad("PyInfo"),
            "def _impl(ctx):",
            "    dummy_file = ctx.file.dummy_file",
            "    info = PyInfo(",
            builder.toString(),
            "    )",
            "    return [info]",
            "create_py_info = rule(implementation=_impl, attrs={",
            "  'dummy_file': attr.label(default='dummy', allow_single_file=True),",
            "})",
            ""
        )
        scratch.overwriteFile(
            "BUILD", "load(':defs.bzl', 'create_py_info')", "create_py_info(name='subject')"
        )
    }

    @get:Throws(java.lang.Exception::class)
    private val pyInfo: PyInfo
        get() = PyInfo.Companion.fromTarget(getConfiguredTarget("//:subject"))

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
    fun starlarkConstructor() {
        writeCreatePyInfo(
            "    transitive_sources = depset(direct=[dummy_file])",
            "    uses_shared_libraries = True",
            "    imports = depset(direct=['abc'])",
            "    has_py2_only_sources = False",
            "    has_py3_only_sources = True"
        )

        val info: PyInfo = this.pyInfo

        assertHasOrderAndContainsExactly(
            info.getTransitiveSourcesSet(), Order.STABLE_ORDER, dummyArtifact
        )
        Truth.assertThat(info.getUsesSharedLibraries()).isTrue()
        assertHasOrderAndContainsExactly(info.getImportsSet(), Order.STABLE_ORDER, "abc")
        Truth.assertThat(info.getHasPy2OnlySources()).isFalse()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun starlarkConstructorDefaults() {
        writeCreatePyInfo("transitive_sources = depset(direct=[dummy_file])")

        val info: PyInfo = this.pyInfo

        assertHasOrderAndContainsExactly(
            info.getTransitiveSourcesSet(), Order.STABLE_ORDER, dummyArtifact
        )
        Truth.assertThat(info.getUsesSharedLibraries()).isFalse()
        assertHasOrderAndContainsExactly(info.getImportsSet(), Order.STABLE_ORDER)
        Truth.assertThat(info.getHasPy2OnlySources()).isFalse()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun starlarkConstructorErrors_transitiveSources_missing() {
        writeCreatePyInfo()

        assertContainsError("missing.*argument.*transitive_sources")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun starlarkConstructorErrors_transitiveSources_badType() {
        writeCreatePyInfo("transitive_sources = 'abc'")

        assertContainsError("transitive_sources.*got.*string.*want.*depset")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun starlarkConstructorErrors_transitiveSources_rejectsPreOrder() {
        writeCreatePyInfo("transitive_sources = depset(direct=[dummy_file], order='preorder')")

        assertContainsError("Order.*postorder.*incompatible.*preorder")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun starlarkConstructorErrors_UsesSharedLibraries() {
        writeCreatePyInfo("transitive_sources = depset()", "uses_shared_libraries = 'abc'")

        assertContainsError("uses_shared_libraries.*got.*string.*want.*bool")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun starlarkConstructorErrors_imports_badType() {
        writeCreatePyInfo("transitive_sources = depset()", "imports = 'abc'")

        assertContainsError("imports.*got.*string.*want.*depset")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun starlarkConstructorErrors_HasPy2OnlySources() {
        writeCreatePyInfo("transitive_sources = depset()", "has_py2_only_sources = 'abc'")

        assertContainsError("has_py2_only_sources.*got.*string.*want.*bool")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun starlarkConstructorErrors_HasPy3OnlySources() {
        writeCreatePyInfo("transitive_sources = depset()", "has_py3_only_sources = 'abc'")

        assertContainsError("has_py3_only_sources.*got.*string.*want.*bool")
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
