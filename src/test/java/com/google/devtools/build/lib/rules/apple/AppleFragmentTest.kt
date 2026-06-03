// Copyright 2022 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.rules.apple

import com.google.devtools.build.lib.skyframe.BzlLoadValue.keyForBuild
import org.junit.Test

/** Tests for the Starlark interface of Apple fragment.  */
@RunWith(JUnit4::class)
class AppleFragmentTest : BuildViewTestCase() {
    @Before
    @Throws(Exception::class)
    fun setup() {
        scratch.file(
            "rules.bzl",
            """
        MyInfo = provider()

        def _my_binary_impl(ctx):
            out = ctx.actions.declare_file(ctx.label.name)
            ctx.actions.write(out, "")
            return [
                DefaultInfo(executable = out),
                MyInfo(
                    exec_cpu = ctx.fragments.apple.single_arch_cpu,
                ),
            ]

        my_binary = rule(
            fragments = ["apple"],
            implementation = _my_binary_impl,
        )

        def _my_rule_impl(ctx):
            return ctx.attr._tool[MyInfo]

        my_rule = rule(
            _my_rule_impl,
            attrs = {
                "_tool": attr.label(
                    cfg = "exec",
                    executable = True,
                    default = ("//:bin"),
                ),
            },
        )
        
        """.trimIndent()
        )
        scratch.file(
            "BUILD",
            "load(':rules.bzl', 'my_binary', 'my_rule')",
            "my_binary(name = 'bin')",
            "my_rule(name = 'a')",
            "platform(",
            "    name = 'macos_arm64',",
            "    constraint_values = [",
            "        '" + TestConstants.CONSTRAINTS_PACKAGE_ROOT + "cpu:aarch64',",
            "        '" + TestConstants.CONSTRAINTS_PACKAGE_ROOT + "os:osx',",
            "    ],",
            ")"
        )
        scratch.file(
            "/workspace/platform_mappings",
            """
        platforms:
          //:macos_arm64
            --macos_cpus=arm64
        
        """.trimIndent()
        )
        invalidatePackages(false)
    }

    @Test
    @Throws(Exception::class)
    fun appleFragmentSingleArchCpuOnExtraExecPlatform() {
        // Test that ctx.fragments.apple.single_arch_cpu returns the execution
        // platform's cpu in a tool's rule context.
        useConfiguration("--extra_execution_platforms=//:macos_arm64")
        val configuredTarget: ConfiguredTarget = getConfiguredTarget("//:a")
        val key: Provider.Key =
            Key(keyForBuild(Label.parseCanonical("//:rules.bzl")), "MyInfo")
        val myInfo: StructImpl = configuredTarget.get(key) as StructImpl
        Truth.assertThat(myInfo.getValue("exec_cpu") as String?).isEqualTo("arm64")
    }
}
