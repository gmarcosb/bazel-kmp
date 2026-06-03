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

import com.google.devtools.build.lib.actions.Action

/** Tests that are common to `py_binary` and `py_test`.  */
abstract class PyExecutableConfiguredTargetTestBase protected constructor(private val ruleName: String?) :
    PyBaseConfiguredTargetTestBase(
        ruleName
    ) {
    /**
     * Returns the configured target with the given label while asserting that, if it is an executable
     * target, the executable is not produced by [FailAction].
     * 
     * 
     * This serves as a drop-in replacement for [.getConfiguredTarget] that will also catch
     * unexpected deferred failures (e.g. `srcs_versions` validation failures) in `py_binary` and `py_test` targets.
     */
    @Throws(java.lang.Exception::class)
    protected fun getOkPyTarget(label: String?): ConfiguredTarget {
        val target: ConfiguredTarget = getConfiguredTarget(label)
        // It can be null without events due to b/26382502.
        com.google.common.base.Preconditions.checkNotNull<Any?>(
            target,
            "target was null (is it misspelled or in error?)"
        )
        val executable: Artifact = getExecutable(target)
        if (executable != null) {
            val action: Action = getGeneratingAction(executable)
            if (action is FailAction) {
                throw java.lang.AssertionError(
                    java.lang.String.format(
                        "execution of target would fail with error '%s'",
                        (action as FailAction).getErrorMessage()
                    )
                )
            }
        }
        return target
    }

    /**
     * Gets the configured target for an executable Python rule (generally `py_binary` or `py_test`) and asserts that it produces a deferred error via [FailAction].
     * 
     * @return the deferred error string
     */
    @Throws(java.lang.Exception::class)
    protected fun getPyExecutableDeferredError(label: String?): String {
        val target: ConfiguredTarget = getConfiguredTarget(label)
        // It can be null without events due to b/26382502.
        com.google.common.base.Preconditions.checkNotNull<Any?>(
            target,
            "target was null (is it misspelled or in error?)"
        )
        val executable: Artifact = getExecutable(target)
        com.google.common.base.Preconditions.checkNotNull<Any?>(
            executable, "executable was null (is this a py_binary/py_test target?)"
        )
        val action: Action = getGeneratingAction(executable)
        assertThat(action).isInstanceOf(FailAction::class.java)
        return (action as FailAction).getErrorMessage()
    }

    private fun ruleDeclWithPyVersionAttr(name: String?, version: String?): String {
        return join(
            ruleName + "(",
            "    name = '" + name + "',",
            "    srcs = ['" + name + ".py'],",
            "    python_version = '" + version + "'",
            ")"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun pyRuntimeInfoIsPresent() {
        scratch.file(
            "pkg/BUILD",  //
            bzlLoad,
            ruleName + "(",
            "    name = 'foo',",
            "    srcs = [':foo.py'],",
            ")"
        )
        Truth.assertThat(PyRuntimeInfo.Companion.fromTarget(getConfiguredTarget("//pkg:foo"))).isNotNull()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun versionAttr_GoodValue() {
        scratch.file(
            "pkg/BUILD",  //
            bzlLoad,
            ruleDeclWithPyVersionAttr("foo", "PY3")
        )
        getOkPyTarget("//pkg:foo")
        assertNoEvents()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun targetInPackageWithHyphensOkIfSrcsFromOtherPackage() {
        scratch.file(
            "pkg/BUILD",  //
            "exports_files(['foo.py', 'bar.py'])"
        )
        scratch.file(
            "pkg-with-hyphens/BUILD",
            bzlLoad,
            ruleName + "(",
            "    name = 'foo',",
            "    main = '//pkg:foo.py',",
            "    srcs = ['//pkg:foo.py', '//pkg:bar.py'])"
        )
        getOkPyTarget("//pkg-with-hyphens:foo") // should not fail
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun targetInPackageWithHyphensOkIfOnlyExplicitMainHasHyphens() {
        scratch.file(
            "pkg-with-hyphens/BUILD",
            bzlLoad,
            ruleName + "(",
            "    name = 'foo',",
            "    main = 'foo.py',",
            "    srcs = ['foo.py'])"
        )
        getOkPyTarget("//pkg-with-hyphens:foo") // should not fail
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun targetInPackageWithHyphensOkIfOnlyImplicitMainHasHyphens() {
        scratch.file(
            "pkg-with-hyphens/BUILD",  //
            bzlLoad,
            ruleName + "(",
            "    name = 'foo',",
            "    srcs = ['foo.py'])"
        )
        getOkPyTarget("//pkg-with-hyphens:foo") // should not fail
    }

    companion object {
        private fun join(vararg lines: String?): String {
            return java.lang.String.join("\n", *lines)
        }
    }
}
