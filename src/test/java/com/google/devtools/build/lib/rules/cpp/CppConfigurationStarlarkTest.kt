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
package com.google.devtools.build.lib.rules.cpp

import com.google.common.truth.Truth
import com.google.devtools.build.lib.analysis.util.AnalysisTestCase.getConfiguredTarget
import com.google.devtools.build.lib.analysis.util.AnalysisTestCase.useConfiguration
import com.google.devtools.build.lib.analysis.util.BuildViewTestCase
import com.google.devtools.build.lib.analysis.util.BuildViewTestCase.getConfiguredTarget
import com.google.devtools.build.lib.analysis.util.BuildViewTestCase.getStarlarkProvider
import com.google.devtools.build.lib.analysis.util.BuildViewTestCase.useConfiguration
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import java.io.IOException

/** Tests for C++ fragments in Starlark.  */
@RunWith(JUnit4::class)
class CppConfigurationStarlarkTest : BuildViewTestCase() {
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testMinimumOsVersion() {
        useConfiguration("--minimum_os_version=-wololoo")
        writeRuleReturning("ctx.fragments.cpp.minimum_os_version()")

        val result = getResult<String?>(String::class.java)
        Truth.assertThat(result).isEqualTo("-wololoo")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testNullMinimumOsVersion() {
        writeRuleReturning("ctx.fragments.cpp.minimum_os_version()")

        val result = getResult<Any?>(Any::class.java)
        Truth.assertThat(result).isInstanceOf(net.starlark.java.eval.NoneType::class.java)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCopts() {
        writeRuleReturning("ctx.fragments.cpp.copts")
        useConfiguration("--copt=-wololoo")

        val result: net.starlark.java.eval.Sequence<String?>? =
            getResult<net.starlark.java.eval.Sequence?>(net.starlark.java.eval.Sequence::class.java)
        Truth.assertThat(result).containsExactly("-wololoo")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCxxopts() {
        writeRuleReturning("ctx.fragments.cpp.cxxopts")
        useConfiguration("--cxxopt=-wololoo")

        val result: net.starlark.java.eval.Sequence<String?>? =
            getResult<net.starlark.java.eval.Sequence?>(net.starlark.java.eval.Sequence::class.java)
        Truth.assertThat(result).containsExactly("-wololoo")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testConlyopts() {
        writeRuleReturning("ctx.fragments.cpp.conlyopts")
        useConfiguration("--conlyopt=-wololoo")

        val result: net.starlark.java.eval.Sequence<String?>? =
            getResult<net.starlark.java.eval.Sequence?>(net.starlark.java.eval.Sequence::class.java)
        Truth.assertThat(result).containsExactly("-wololoo")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testObjcopts() {
        writeRuleReturning("ctx.fragments.cpp.objccopts")
        useConfiguration("--objccopt=-wololoo")

        val result: net.starlark.java.eval.Sequence<String?>? =
            getResult<net.starlark.java.eval.Sequence?>(net.starlark.java.eval.Sequence::class.java)
        Truth.assertThat(result).containsExactly("-wololoo")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testLinkopts() {
        writeRuleReturning("ctx.fragments.cpp.linkopts")
        useConfiguration("--linkopt=-wololoo")

        val result: net.starlark.java.eval.Sequence<String?>? =
            getResult<net.starlark.java.eval.Sequence?>(net.starlark.java.eval.Sequence::class.java)
        Truth.assertThat(result).containsExactly("-wololoo")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testExpandedApiBlocked() {
        writeRuleReturning("foo", "pic.bzl", "pic", "ctx.fragments.cpp.force_pic()")
        writeRuleReturning("foo", "lcov.bzl", "lcov", "ctx.fragments.cpp.generate_llvm_lcov()")
        writeRuleReturning("foo", "fdo.bzl", "fdo", "ctx.fragments.cpp.fdo_instrument()")
        writeRuleReturning(
            "foo", "hdr_deps.bzl", "hdr_deps", "ctx.fragments.cpp.process_headers_in_dependencies()"
        )
        writeRuleReturning("foo", "save.bzl", "save", "ctx.fragments.cpp.save_feature_state()")
        writeRuleReturning(
            "foo",
            "fission.bzl",
            "fission",
            "ctx.fragments.cpp.fission_active_for_current_compilation_mode()"
        )
        var e: java.lang.AssertionError?
        e = org.junit.Assert.assertThrows<java.lang.AssertionError?>(
            java.lang.AssertionError::class.java,
            org.junit.function.ThrowingRunnable { getConfiguredTarget("//foo:pic") })
        assertBlockedFeature(e, "force_pic")
        e = org.junit.Assert.assertThrows<java.lang.AssertionError?>(
            java.lang.AssertionError::class.java,
            org.junit.function.ThrowingRunnable { getConfiguredTarget("//foo:lcov") })
        assertBlockedFeature(e, "generate_llvm_lcov")
        e = org.junit.Assert.assertThrows<java.lang.AssertionError?>(
            java.lang.AssertionError::class.java,
            org.junit.function.ThrowingRunnable { getConfiguredTarget("//foo:fdo") })
        assertBlockedFeature(e, "fdo_instrument")
        e = org.junit.Assert.assertThrows<java.lang.AssertionError?>(
            java.lang.AssertionError::class.java,
            org.junit.function.ThrowingRunnable { getConfiguredTarget("//foo:hdr_deps") })
        Truth.assertThat(e).hasMessageThat().contains("cannot use private API")
        e = org.junit.Assert.assertThrows<java.lang.AssertionError?>(
            java.lang.AssertionError::class.java,
            org.junit.function.ThrowingRunnable { getConfiguredTarget("//foo:save") })
        Truth.assertThat(e).hasMessageThat().contains("cannot use private API")
        e = org.junit.Assert.assertThrows<java.lang.AssertionError?>(
            java.lang.AssertionError::class.java,
            org.junit.function.ThrowingRunnable { getConfiguredTarget("//foo:fission") })
        Truth.assertThat(e).hasMessageThat().contains("cannot use private API")
    }

    @Throws(IOException::class)
    private fun writeRuleReturning(returns: String?) {
        writeRuleReturning("foo", "lib.bzl", "bar", returns)
    }

    @Throws(IOException::class)
    private fun writeRuleReturning(path: String?, lib: String?, target: String?, returns: String?) {
        scratch.file(
            path + "/" + lib,
            "Info = provider()",
            "def _impl(ctx):",
            "  return Info(",
            "    result = " + returns,
            "  )",
            "foo = rule(implementation=_impl, fragments = ['cpp'])"
        )
        scratch.appendFile(
            path + "/BUILD", "load(':" + lib + "', 'foo')", "foo(name='" + target + "')"
        )
    }

    @Throws(java.lang.Exception::class)
    private fun <T> getResult(type: java.lang.Class<T?>?): T? {
        return getStarlarkProvider(getConfiguredTarget("//foo:bar"), "Info").getValue("result", type)
    }

    companion object {
        private fun assertBlockedFeature(e: java.lang.AssertionError?, feature: String?) {
            Truth.assertThat(e)
                .hasMessageThat()
                .contains(
                    String.format("cannot use private API (feature '%s' in CppConfiguration)", feature)
                )
        }
    }
}
