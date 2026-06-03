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
package com.google.devtools.build.lib.rules.test

import com.google.common.truth.Subject
import com.google.devtools.build.lib.analysis.ConfiguredTarget
import org.junit.Test

/** Tests for Starlark interaction with testing support.  */
@RunWith(JUnit4::class)
class StarlarkTestingModuleTest : BuildViewTestCase() {
    @Test
    @Throws(Exception::class)
    fun testStarlarkRulePropagatesExecutionInfoProvider() {
        scratch.file("examples/rule/BUILD")
        scratch.file(
            "examples/rule/apple_rules.bzl",
            """
        def my_rule_impl(ctx):
            exec_info = testing.ExecutionInfo({"requires-darwin": "1"})
            return [exec_info]

        my_rule = rule(
            implementation = my_rule_impl,
            attrs = {},
        )
        
        """.trimIndent()
        )
        scratch.file(
            "examples/apple_starlark/BUILD",
            """
        load("//examples/rule:apple_rules.bzl", "my_rule")

        package(default_visibility = ["//visibility:public"])

        my_rule(
            name = "my_target",
        )
        
        """.trimIndent()
        )

        val starlarkTarget: ConfiguredTarget = getConfiguredTarget("//examples/apple_starlark:my_target")
        val provider: ExecutionInfo = starlarkTarget.get(ExecutionInfo.PROVIDER)

        assertThat(provider.getExecutionInfo().get("requires-darwin")).isEqualTo("1")
    }

    @Test
    @Throws(Exception::class)
    fun testStarlarkRulePropagatesTestEnvironmentProvider() {
        scratch.file("examples/rule/BUILD")
        scratch.file(
            "examples/rule/apple_rules.bzl",
            """
        def my_rule_impl(ctx):
            test_env = testing.TestEnvironment({"XCODE_VERSION_OVERRIDE": "7.3.1"})
            return [test_env]

        my_rule = rule(
            implementation = my_rule_impl,
            attrs = {},
        )
        
        """.trimIndent()
        )
        scratch.file(
            "examples/apple_starlark/BUILD",
            """
        load("//examples/rule:apple_rules.bzl", "my_rule")

        package(default_visibility = ["//visibility:public"])

        my_rule(
            name = "my_target",
        )
        
        """.trimIndent()
        )

        val starlarkTarget: ConfiguredTarget = getConfiguredTarget("//examples/apple_starlark:my_target")
        val provider: RunEnvironmentInfo = starlarkTarget.get(RunEnvironmentInfo.provider)

        assertThat(provider.getEnvironment().get("XCODE_VERSION_OVERRIDE")).isEqualTo("7.3.1")
    }

    @Test
    @Throws(Exception::class)
    fun testStarlarkRulePropagatesTestEnvironmentProviderWithInheritedEnv() {
        scratch.file("examples/rule/BUILD")
        scratch.file(
            "examples/rule/apple_rules.bzl",
            """
        def my_rule_impl(ctx):
            test_env = testing.TestEnvironment(
                {"XCODE_VERSION_OVERRIDE": "7.3.1"},
                [
                    "DEVELOPER_DIR",
                    "XCODE_VERSION_OVERRIDE",
                ],
            )
            return [test_env]

        my_rule = rule(
            implementation = my_rule_impl,
            attrs = {},
        )
        
        """.trimIndent()
        )
        scratch.file(
            "examples/apple_starlark/BUILD",
            """
        load("//examples/rule:apple_rules.bzl", "my_rule")

        package(default_visibility = ["//visibility:public"])

        my_rule(
            name = "my_target",
        )
        
        """.trimIndent()
        )

        val starlarkTarget: ConfiguredTarget = getConfiguredTarget("//examples/apple_starlark:my_target")
        val provider: RunEnvironmentInfo =
            starlarkTarget.get(RunEnvironmentInfo.provider.getKey()) as RunEnvironmentInfo

        assertThat(provider.getEnvironment()).containsEntry("XCODE_VERSION_OVERRIDE", "7.3.1")
        Subject.contains("DEVELOPER_DIR")
        Subject.contains("XCODE_VERSION_OVERRIDE")
    }

    @Test
    @Throws(Exception::class)
    fun testExecutionInfoProviderCanMarkTestAsLocal() {
        scratch.file("examples/rule/BUILD")
        scratch.file(
            "examples/rule/apple_rules.bzl",
            """
        def my_rule_test_impl(ctx):
            exec_info = testing.ExecutionInfo({"local": ""})
            ctx.actions.write(ctx.outputs.executable, "", True)
            return [exec_info]

        my_rule_test = rule(
            implementation = my_rule_test_impl,
            test = True,
            attrs = {},
        )
        
        """.trimIndent()
        )
        scratch.file(
            "examples/apple_starlark/BUILD",
            """
        load("//examples/rule:apple_rules.bzl", "my_rule_test")

        package(default_visibility = ["//visibility:public"])

        my_rule_test(
            name = "my_target",
        )
        
        """.trimIndent()
        )

        val starlarkTarget: ConfiguredTarget = getConfiguredTarget("//examples/apple_starlark:my_target")
        val testAction: TestRunnerAction =
            getGeneratingAction(TestProvider.getTestStatusArtifacts(starlarkTarget).get(0)) as TestRunnerAction

        assertThat(testAction.getTestProperties().isRemotable()).isFalse()
    }
}
