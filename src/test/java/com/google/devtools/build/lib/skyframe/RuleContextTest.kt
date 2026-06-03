// Copyright 2017 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.skyframe

import com.google.devtools.build.lib.analysis.RuleContext

/** Tests for toolchains computed in BuildViewTestCase.  */
@RunWith(JUnit4::class)
class RuleContextTest : ToolchainTestCase() {
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testToolchains() {
        mockToolsConfig.create("x/BUILD", "mock_toolchain_rule(name='x')")
        useConfiguration("--host_platform=//platforms:linux", "--platforms=//platforms:mac")
        val ruleContext: RuleContext = getRuleContext(getConfiguredTarget("//x"))
        com.google.common.truth.Subject.contains(Label.parseCanonical("//toolchain:toolchain_1_impl"))

        assertThat(ruleContext.getToolchainContext()).hasToolchainType("//toolchain:test_toolchain")
        val toolchain: ToolchainInfo =
            ruleContext.getToolchainInfo(Label.parseCanonical("//toolchain:test_toolchain"))
        assertThat(toolchain.getValue("data")).isEqualTo("foo")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTargetPlatformHasConstraint_mac() {
        scratch.file("a/BUILD", "filegroup(name = 'a')")
        useConfiguration("--platforms=//platforms:mac")
        val ruleContext: RuleContext = getRuleContext(getConfiguredTarget("//a"))
        assertThat(ruleContext.targetPlatformHasConstraint(macConstraint)).isTrue()
        assertThat(ruleContext.targetPlatformHasConstraint(linuxConstraint)).isFalse()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTargetPlatformHasConstraint_linux() {
        scratch.file("a/BUILD", "filegroup(name = 'a')")
        useConfiguration("--platforms=//platforms:linux")
        val ruleContext: RuleContext = getRuleContext(getConfiguredTarget("//a"))
        assertThat(ruleContext.targetPlatformHasConstraint(macConstraint)).isFalse()
        assertThat(ruleContext.targetPlatformHasConstraint(linuxConstraint)).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTestonlyToolchain_allowed() {
        createTestonlyToolchain()

        scratch.file(
            "p0/BUILD",
            """
        load("//foo:rule_def.bzl", "foo_rule")

        foo_rule(
            name = "p0",
            testonly = True,
        )
        
        """.trimIndent()
        )
        // This should succeed.
        getConfiguredTarget("//p0:p0")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTestonlyToolchain_invalid() {
        createTestonlyToolchain()

        checkError(
            "p0",
            "p0",  // error:
            "non-test target '//p0:p0' depends on testonly target",  // build file:
            "load('//foo:rule_def.bzl', 'foo_rule')",
            "foo_rule(",
            "    name = 'p0',",
            "    testonly = False,",  // False is the default, we set it here for clarity.
            ")"
        )
    }

    @Throws(java.lang.Exception::class)
    private fun createTestonlyToolchain() {
        // Define a custom rule with a testonly toolchain.
        scratch.file(
            "foo/toolchain_def.bzl",
            """
        def _impl(ctx):
            return [platform_common.ToolchainInfo()]

        foo_toolchain = rule(
            implementation = _impl,
            attrs = {},
        )
        
        """.trimIndent()
        )
        scratch.file(
            "foo/rule_def.bzl",
            """
        def _impl(ctx):
            pass

        foo_rule = rule(
            implementation = _impl,
            toolchains = ["//foo:toolchain_type"],
        )
        
        """.trimIndent()
        )
        scratch.file("foo/BUILD", "toolchain_type(name = 'toolchain_type')")
        // Create an instance of the toolchain.
        scratch.file(
            "bar/BUILD",
            """
        load("//foo:toolchain_def.bzl", "foo_toolchain")

        toolchain(
            name = "foo_toolchain_impl",
            toolchain = ":foo_toolchain_def",
            toolchain_type = "//foo:toolchain_type",
        )

        foo_toolchain(
            name = "foo_toolchain_def",
            testonly = True,
        )
        
        """.trimIndent()
        )

        useConfiguration("--extra_toolchains=//bar:all")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testToolchainTypeVisibilityIsEnforced() {
        scratch.file(
            "private/BUILD",
            "toolchain_type(",
            "    name = 'private_toolchain_type',",
            "    visibility = ['//private:__pkg__'],",
            ")"
        )

        checkError(
            "other_pkg",
            "my_toolchain",
            "target '//private:private_toolchain_type' is not visible from\n"
                    + "target '//other_pkg:my_toolchain'",
            """
        toolchain(
            name = 'my_toolchain',
            toolchain_type = '//private:private_toolchain_type',
            toolchain = ':impl',
        )
        filegroup(name = 'impl')
        
        """.trimIndent()
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testPrivateToolchainImplementationCanResolveAnywhere() {
        scratch.file(
            "private/BUILD",
            "load('//private:toolchain_def.bzl', 'foo_toolchain')",
            "toolchain_type(name = 'type', visibility = ['//visibility:public'])",
            "foo_toolchain(name = 'impl', visibility = ['//visibility:private'])",
            "toolchain(name = 'toolchain',",
            "    toolchain_type = ':type',",
            "    toolchain = ':impl',",
            "    visibility = ['//visibility:public'],",
            ")"
        )

        scratch.file(
            "private/toolchain_def.bzl",
            "def _impl(ctx):",
            "    return [platform_common.ToolchainInfo()]",
            "foo_toolchain = rule(",
            "    implementation = _impl,",
            "    attrs = {},",
            ")"
        )

        scratch.file(
            "other_pkg/rule.bzl",
            "def _impl(ctx):",
            "    return []",
            "my_rule = rule(",
            "    implementation = _impl,",
            "    toolchains = ['//private:type'],",
            ")"
        )

        scratch.file("other_pkg/BUILD", "load(':rule.bzl', 'my_rule')", "my_rule(name = 'target')")

        useConfiguration("--extra_toolchains=//private:toolchain")
        getConfiguredTarget("//other_pkg:target")
    }
}
