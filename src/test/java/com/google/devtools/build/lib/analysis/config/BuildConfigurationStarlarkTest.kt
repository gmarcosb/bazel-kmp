// Copyright 2016 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.analysis.config

import com.google.devtools.build.lib.skyframe.BzlLoadValue.keyForBuild

/** Tests for [BuildConfigurationValue]'s integration with Starlark.  */
@RunWith(JUnit4::class)
class BuildConfigurationStarlarkTest : BuildViewTestCase() {
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testStarlarkWithTestEnvOptions() {
        useConfiguration("--test_env=TEST_ENV_VAR=my_value")
        scratch.file("examples/rule/BUILD")
        scratch.file(
            "examples/rule/config_test.bzl",
            """
        MyInfo = provider()

        def _test_rule_impl(ctx):
            out = ctx.actions.declare_file(ctx.label.name)
            ctx.actions.write(out, "exit 0", is_executable = True)
            return [
                DefaultInfo(executable = out),
                MyInfo(test_env = ctx.configuration.test_env),
            ]

        my_test = rule(
            implementation = _test_rule_impl,
            attrs = {},
            test = True,
        )
        
        """.trimIndent()
        )

        scratch.file(
            "examples/config_starlark/BUILD",
            """
        load("//examples/rule:config_test.bzl", "my_test")

        package(default_visibility = ["//visibility:public"])

        my_test(
            name = "my_target",
        )
        
        """.trimIndent()
        )

        val starlarkTarget: ConfiguredTarget? = getConfiguredTarget("//examples/config_starlark:my_target")
        val key: Provider.Key =
            Key(
                keyForBuild(Label.parseCanonical("//examples/rule:config_test.bzl")), "MyInfo"
            )
        val myInfo: StructImpl = starlarkTarget.get(key) as StructImpl
        Truth.assertThat((myInfo.getValue("test_env") as Dict<*, *>).get("TEST_ENV_VAR")).isEqualTo("my_value")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testIsToolConfiguration() {
        scratch.file(
            "example/BUILD",
            """
        load(":rule.bzl", "custom_rule")

        custom_rule(name = "custom")
        
        """.trimIndent()
        )

        scratch.file(
            "example/rule.bzl",
            """
        def _impl(ctx):
            if ctx.configuration.is_tool_configuration():
                fail("should not be tool configuration")
            return [DefaultInfo()]

        custom_rule = rule(implementation = _impl)
        
        """.trimIndent()
        )

        getConfiguredTarget("//example:custom")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRunfilesEnabledIsPrivateApi() {
        scratch.file(
            "example/BUILD",
            """
        load(":rule.bzl", "custom_rule")

        custom_rule(name = "custom")
        
        """.trimIndent()
        )

        scratch.file(
            "example/rule.bzl",
            """
        def _impl(ctx):
            ctx.configuration.runfiles_enabled()
            return [DefaultInfo()]

        custom_rule = rule(implementation = _impl)
        
        """.trimIndent()
        )

        val e: java.lang.AssertionError? =
            org.junit.Assert.assertThrows<java.lang.AssertionError?>(
                java.lang.AssertionError::class.java,
                org.junit.function.ThrowingRunnable { getConfiguredTarget("//example:custom") })
        Truth.assertThat(e).hasMessageThat().contains("file '//example:rule.bzl' cannot use private API")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testShortId() {
        scratch.file(
            "example/BUILD",
            """
        load(":rule.bzl", "custom_rule")

        custom_rule(name = "custom")
        
        """.trimIndent()
        )

        scratch.file(
            "example/rule.bzl",
            """
        MyInfo = provider()

        def _impl(ctx):
            return [MyInfo(short_id = ctx.configuration.short_id)]

        custom_rule = rule(implementation = _impl)
        
        """.trimIndent()
        )

        var target: ConfiguredTarget? = getConfiguredTarget("//example:custom")
        val key: Provider.Key =
            Key(keyForBuild(Label.parseCanonical("//example:rule.bzl")), "MyInfo")
        var myInfo: StructImpl = target.get(key) as StructImpl
        val firstShortId = myInfo.getValue("short_id") as String?
        Truth.assertThat(firstShortId).isEqualTo(target.getConfigurationKey().getOptions().shortId())

        useConfiguration("--compilation_mode=dbg")
        target = getConfiguredTarget("//example:custom")
        myInfo = target.get(key) as StructImpl
        val secondShortId = myInfo.getValue("short_id") as String?
        Truth.assertThat(secondShortId).isEqualTo(target.getConfigurationKey().getOptions().shortId())

        Truth.assertThat(firstShortId).isNotEqualTo(secondShortId)
    }
}
