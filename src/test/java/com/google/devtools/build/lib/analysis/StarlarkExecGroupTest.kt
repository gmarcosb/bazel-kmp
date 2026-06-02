// Copyright 2015 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.analysis

import com.google.devtools.build.lib.packages.DeclaredExecGroup.DEFAULT_EXEC_GROUP_NAME

/**
 * Test for exec groups. Functionality related to rule context tested in [ ].
 */
@RunWith(JUnit4::class)
class StarlarkExecGroupTest : BuildViewTestCase() {
    /**
     * Sets up two toolchains types, each with a single toolchain implementation and a single
     * exec_compatible_with platform.
     * 
     * 
     * toolchain_type_1 -> foo_toolchain -> exec_compatible_with platform_1 toolchain_type_2 ->
     * bar_toolchain -> exec_compatible_with platform_2
     */
    @Throws(java.lang.Exception::class)
    private fun createToolchainsAndPlatforms() {
        scratch.file(
            "rule/test_toolchain.bzl",
            """
        def _impl(ctx):
            return [platform_common.ToolchainInfo()]

        test_toolchain = rule(
            implementation = _impl,
        )
        
        """.trimIndent()
        )
        scratch.file(
            "rule/BUILD",
            """
        exports_files(["test_toolchain/bzl"])

        toolchain_type(name = "toolchain_type_1")

        toolchain_type(name = "toolchain_type_2")
        
        """.trimIndent()
        )
        scratch.file(
            "toolchain/BUILD",
            """
        load("//rule:test_toolchain.bzl", "test_toolchain")

        test_toolchain(
            name = "foo",
        )

        toolchain(
            name = "foo_toolchain",
            exec_compatible_with = ["//platform:constraint_1"],
            target_compatible_with = ["//platform:constraint_1"],
            toolchain = ":foo",
            toolchain_type = "//rule:toolchain_type_1",
        )

        test_toolchain(
            name = "bar",
        )

        toolchain(
            name = "bar_toolchain",
            exec_compatible_with = ["//platform:constraint_2"],
            target_compatible_with = ["//platform:constraint_1"],
            toolchain = ":bar",
            toolchain_type = "//rule:toolchain_type_2",
        )
        
        """.trimIndent()
        )

        scratch.overwriteFile(
            "platform/BUILD",
            """
        constraint_setting(name = "setting")

        constraint_value(
            name = "constraint_1",
            constraint_setting = ":setting",
        )

        constraint_value(
            name = "constraint_2",
            constraint_setting = ":setting",
        )

        platform(
            name = "platform_1",
            constraint_values = [":constraint_1"],
        )

        platform(
            name = "platform_2",
            constraint_values = [":constraint_2"],
            exec_properties = {
                "watermelon.ripeness": "unripe",
                "watermelon.color": "red",
            },
        )
        
        """.trimIndent()
        )

        useConfiguration(
            "--extra_toolchains=//toolchain:foo_toolchain,//toolchain:bar_toolchain",
            "--platforms=//platform:platform_1",
            "--extra_execution_platforms=//platform:platform_1,//platform:platform_2"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDirectExecTransitionWithToolchains() {
        // toolchain_2 is available on platform_2, so exec transition also needs to be to platform_2
        createToolchainsAndPlatforms()

        scratch.file(
            "test/defs.bzl",
            """
        MyInfo = provider()

        def _impl(ctx):
            return [MyInfo(dep = ctx.attr.dep)]

        with_transition = rule(
            implementation = _impl,
            attrs = {
                "dep": attr.label(cfg = "exec"),
            },
            toolchains = ["//rule:toolchain_type_2"],
        )

        def _impl2(ctx):
            return []

        simple_rule = rule(implementation = _impl2)
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load("//test:defs.bzl", "simple_rule", "with_transition")

        with_transition(
            name = "parent",
            dep = ":child",
        )

        simple_rule(name = "child")
        
        """.trimIndent()
        )

        val target: ConfiguredTarget? = getConfiguredTarget("//test:parent")
        val key: Provider.Key =
            Key(keyForBuild(Label.parseCanonical("//test:defs.bzl")), "MyInfo")
        val dep: BuildConfigurationValue =
            getConfiguration((target.get(key) as StructImpl).getValue("dep") as ConfiguredTarget?)

        assertThat(dep.getFragment(PlatformConfiguration::class.java).getTargetPlatform())
            .isEqualTo(Label.parseCanonicalUnchecked("//platform:platform_2"))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testIndirectExecTransitionWithToolchains() {
        createToolchainsAndPlatforms()
        useConfiguration(
            "--extra_toolchains=//toolchain:foo_toolchain,//toolchain:bar_toolchain",
            "--platforms=//platform:platform_1",
            "--extra_execution_platforms=//platform:platform_1,//platform:platform_2",
            "--incompatible_auto_exec_groups"
        )

        scratch.file(
            "test/defs.bzl",
            """
        MyInfo = provider()

        def _impl_parent(ctx):
            output = ctx.actions.declare_file("parent.out")
            ctx.actions.run(
                executable = "",
                progress_message = "Test with AEG.",
                outputs = [output],
            )
            return [MyInfo(dep = ctx.attr.dep), DefaultInfo(files = depset([output]))]

        parent_rule = rule(
            implementation = _impl_parent,
            attrs = {
                "dep": attr.label(),
                "_use_auto_exec_groups": attr.bool(default = True),
            },
            toolchains = ["//rule:toolchain_type_2"],
        )

        def _impl(ctx):
            return [MyInfo(dep = ctx.attr.dep)]

        pass_thru = rule(
            implementation = _impl,
            attrs = {
                "dep": attr.label(cfg = "exec"),
            },
        )

        def _impl2(ctx):
            return []

        simple_rule = rule(implementation = _impl2)
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load("//test:defs.bzl", "parent_rule", "pass_thru", "simple_rule")

        parent_rule(
            name = "parent",
            dep = ":passthru",
        )

        pass_thru(
            name = "passthru",
            dep = ":child",
        )

        simple_rule(name = "child")
        
        """.trimIndent()
        )

        val target: ConfiguredTarget? = getConfiguredTarget("//test:parent")
        val key: Provider.Key =
            Key(keyForBuild(Label.parseCanonical("//test:defs.bzl")), "MyInfo")
        val dep: ConfiguredTarget = (target.get(key) as StructImpl).getValue("dep") as ConfiguredTarget
        val passthruDepConfig: BuildConfigurationValue =
            getConfiguration((dep.get(key) as StructImpl).getValue("dep") as ConfiguredTarget?)

        // Action will be executed on '//platform:platform_1' platform.
        assertThat(
            getGeneratingAction(target, "test/parent.out")
                .getOwner()
                .getExecutionPlatform()
                .label()
        )
            .isEqualTo(passthruDepConfig.getFragment(PlatformConfiguration::class.java).getTargetPlatform())
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testExecGroupTransition() {
        createToolchainsAndPlatforms()

        scratch.file(
            "test/defs.bzl",
            """
        MyInfo = provider()

        def _impl(ctx):
            return [MyInfo(dep = ctx.attr.dep, exec_group_dep = ctx.attr.exec_group_dep)]

        with_transition = rule(
            implementation = _impl,
            attrs = {
                "exec_group_dep": attr.label(cfg = config.exec("watermelon")),
                "dep": attr.label(cfg = "exec"),
            },
            exec_groups = {
                "watermelon": exec_group(toolchains = ["//rule:toolchain_type_2"]),
            },
            toolchains = ["//rule:toolchain_type_1"],
        )

        def _impl2(ctx):
            return []

        simple_rule = rule(implementation = _impl2)
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load("//test:defs.bzl", "simple_rule", "with_transition")

        with_transition(
            name = "parent",
            dep = ":child",
            exec_group_dep = ":other-child",
        )

        simple_rule(name = "child")

        simple_rule(name = "other-child")
        
        """.trimIndent()
        )

        val target: ConfiguredTarget? = getConfiguredTarget("//test:parent")
        val key: Provider.Key =
            Key(keyForBuild(Label.parseCanonical("//test:defs.bzl")), "MyInfo")
        val dep: BuildConfigurationValue =
            getConfiguration((target.get(key) as StructImpl).getValue("dep") as ConfiguredTarget?)
        val execGroupDep: BuildConfigurationValue =
            getConfiguration(
                (target.get(key) as StructImpl).getValue("exec_group_dep") as ConfiguredTarget?
            )

        assertThat(dep.getFragment(PlatformConfiguration::class.java).getTargetPlatform())
            .isEqualTo(Label.parseCanonicalUnchecked("//platform:platform_1"))
        assertThat(execGroupDep.getFragment(PlatformConfiguration::class.java).getTargetPlatform())
            .isEqualTo(Label.parseCanonicalUnchecked("//platform:platform_2"))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testInvalidExecGroupTransition() {
        scratch.file(
            "test/defs.bzl",
            """
        MyInfo = provider()

        def _impl(ctx):
            return []

        with_transition = rule(
            implementation = _impl,
            attrs = {
                "exec_group_dep": attr.label(cfg = config.exec("blueberry")),
            },
        )

        def _impl2(ctx):
            return []

        simple_rule = rule(implementation = _impl2)
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load("//test:defs.bzl", "simple_rule", "with_transition")

        with_transition(
            name = "parent",
            exec_group_dep = ":child",
        )

        simple_rule(name = "child")
        
        """.trimIndent()
        )

        reporter.removeHandler(FoundationTestCase.failFastHandler)
        getConfiguredTarget("//test:parent")
        assertContainsEvent(
            "Attr 'exec_group_dep' declares a transition for non-existent exec group 'blueberry'"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testExecGroupActionHasExecGroupPlatform() {
        createToolchainsAndPlatforms()
        writeRuleWithActionsAndWatermelonExecGroup()

        scratch.file(
            "test/BUILD",
            """
        load("//test:defs.bzl", "with_actions")

        with_actions(
            name = "papaya",
            output = "out.txt",
            watermelon_output = "watermelon_out.txt",
        )
        
        """.trimIndent()
        )

        val target: ConfiguredTarget? = getConfiguredTarget("//test:papaya")

        assertThat(
            getGeneratingAction(target, "test/watermelon_out.txt")
                .getOwner()
                .getExecutionPlatform()
                .label()
        )
            .isEqualTo(Label.parseCanonicalUnchecked("//platform:platform_2"))
        assertThat(
            getGeneratingAction(target, "test/out.txt").getOwner().getExecutionPlatform().label()
        )
            .isEqualTo(Label.parseCanonicalUnchecked("//platform:platform_1"))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testActionDeclaresInvalidExecGroup() {
        createToolchainsAndPlatforms()

        scratch.file(
            "test/defs.bzl",
            """
        MyInfo = provider()

        def _impl(ctx):
            watermelon_out_file = ctx.outputs.watermelon_output
            ctx.actions.run_shell(
                inputs = [],
                outputs = [watermelon_out_file],
                arguments = [watermelon_out_file.path],
                command = 'echo hello > "${'$'}1"',
                exec_group = "honeydew",
            )

        with_actions = rule(
            implementation = _impl,
            attrs = {
                "watermelon_output": attr.output(),
            },
            exec_groups = {
                "watermelon": exec_group(toolchains = ["//rule:toolchain_type_2"]),
            },
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load("//test:defs.bzl", "with_actions")

        with_actions(
            name = "papaya",
            watermelon_output = "watermelon_out.txt",
        )
        
        """.trimIndent()
        )

        reporter.removeHandler(FoundationTestCase.failFastHandler)
        getConfiguredTarget("//test:papaya")
        assertContainsEvent("Action declared for non-existent exec group 'honeydew'")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun ruleCannotNameExecGroupDefaultName() {
        createToolchainsAndPlatforms()

        scratch.file(
            "test/defs.bzl",
            "def _impl(ctx):",
            "  return []",
            "my_rule = rule(",
            "  implementation = _impl,",
            "  exec_groups = {",
            ("    '"
                    + DEFAULT_EXEC_GROUP_NAME
                    + "': exec_group(toolchains = ['//rule:toolchain_type_2']),"),
            "  },",
            ")"
        )
        scratch.file(
            "test/BUILD",
            """
        load("//test:defs.bzl", "my_rule")

        my_rule(name = "papaya")
        
        """.trimIndent()
        )

        reporter.removeHandler(FoundationTestCase.failFastHandler)
        getConfiguredTarget("//test:papaya")
        assertContainsEvent("Exec group name '" + DEFAULT_EXEC_GROUP_NAME + "' is not a valid name")
    }

    @Throws(IOException::class)
    private fun createAspectRuleWithExecGroup(execGroupName: String?) {
        scratch.file(
            "test/defs.bzl",
            "def _aspect_impl(target, ctx):",
            "    return []",
            "my_aspect = aspect(",
            "    implementation = _aspect_impl,",
            "    exec_groups = {",
            "        '" + execGroupName + "': exec_group(toolchains = ['//rule:toolchain_type_2']),",
            "    },",
            "    toolchains = ['//rule:toolchain_type_1'],",
            ")",
            "def _rule_impl(ctx):",
            "    return []",
            "my_rule = rule(",
            "    implementation = _rule_impl,",
            "    attrs = {",
            "        'srcs': attr.label_list(aspects = [my_aspect])",
            "    },",
            ")"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun aspectUsesExecGroup() {
        createToolchainsAndPlatforms()
        createAspectRuleWithExecGroup("watermelon")

        scratch.file(
            "test/BUILD",
            """
        load(":defs.bzl", "my_rule")

        filegroup(name = "banana")

        my_rule(
            name = "papaya",
            srcs = [":banana"],
        )
        
        """.trimIndent()
        )

        val configuredTarget: ConfiguredTarget? = getConfiguredTarget("//test:papaya")
        assertThat(configuredTarget).isNotNull()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun aspectCannotNameExecGroupDefaultName() {
        createToolchainsAndPlatforms()
        createAspectRuleWithExecGroup(DEFAULT_EXEC_GROUP_NAME)

        scratch.file(
            "test/BUILD",
            """
        load(":defs.bzl", "my_rule")

        filegroup(name = "banana")

        my_rule(
            name = "papaya",
            srcs = [":banana"],
        )
        
        """.trimIndent()
        )

        reporter.removeHandler(FoundationTestCase.failFastHandler)
        getConfiguredTarget("//test:papaya")
        assertContainsEvent("Exec group name '" + DEFAULT_EXEC_GROUP_NAME + "' is not a valid name")
    }

    @Throws(java.lang.Exception::class)
    private fun writeRuleWithActionsAndWatermelonExecGroup() {
        scratch.file(
            "test/defs.bzl",
            """
        MyInfo = provider()

        def _impl(ctx):
            watermelon_out_file = ctx.outputs.watermelon_output
            ctx.actions.run_shell(
                inputs = [],
                outputs = [watermelon_out_file],
                arguments = [watermelon_out_file.path],
                command = 'echo hello > "${'$'}1"',
                exec_group = "watermelon",
            )
            out_file = ctx.outputs.output
            ctx.actions.run_shell(
                inputs = [],
                outputs = [out_file],
                arguments = [out_file.path],
                command = 'echo hello > "${'$'}1"',
            )

        with_actions = rule(
            implementation = _impl,
            attrs = {
                "watermelon_output": attr.output(),
                "output": attr.output(),
            },
            exec_groups = {
                "watermelon": exec_group(toolchains = ["//rule:toolchain_type_2"]),
            },
            toolchains = ["//rule:toolchain_type_1"],
        )
        
        """.trimIndent()
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSetExecGroupExecProperty() {
        createToolchainsAndPlatforms()
        writeRuleWithActionsAndWatermelonExecGroup()

        scratch.file(
            "test/BUILD",
            """
        load("//test:defs.bzl", "with_actions")

        with_actions(
            name = "papaya",
            exec_properties = {
                "color": "orange",
                "ripeness": "ripe",
                "watermelon.color": "pink",
                "watermelon.season": "summer",
            },
            output = "out.txt",
            watermelon_output = "watermelon_out.txt",
        )
        
        """.trimIndent()
        )

        val target: ConfiguredTarget? = getConfiguredTarget("//test:papaya")

        assertThat(
            getGeneratingAction(target, "test/watermelon_out.txt").getOwner().getExecProperties()
        )
            .containsExactly("color", "pink", "season", "summer", "ripeness", "ripe")
        assertThat(getGeneratingAction(target, "test/out.txt").getOwner().getExecProperties())
            .containsExactly("color", "orange", "ripeness", "ripe")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSetUnknownExecGroup() {
        createToolchainsAndPlatforms()
        writeRuleWithActionsAndWatermelonExecGroup()

        scratch.file(
            "test/BUILD",
            """
        load("//test:defs.bzl", "with_actions")

        with_actions(
            name = "papaya",
            exec_properties = {
                "color": "orange",
                "watermelon.color": "pink",
                "blueberry.season": "summer",  # non-existent exec group
            },
            output = "out.txt",
            watermelon_output = "watermelon_out.txt",
        )
        
        """.trimIndent()
        )

        reporter.removeHandler(FoundationTestCase.failFastHandler)
        getConfiguredTarget("//test:papaya")
        assertContainsEvent("errors encountered while analyzing target '//test:papaya'")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun ruleInheritsPlatformExecGroupExecProperty() {
        createToolchainsAndPlatforms()
        writeRuleWithActionsAndWatermelonExecGroup()

        scratch.file(
            "test/BUILD",
            """
        load("//test:defs.bzl", "with_actions")

        with_actions(
            name = "papaya",
            output = "out.txt",
            watermelon_output = "watermelon_out.txt",
        )
        
        """.trimIndent()
        )

        val target: ConfiguredTarget? = getConfiguredTarget("//test:papaya")

        assertThat(
            getGeneratingAction(target, "test/watermelon_out.txt").getOwner().getExecProperties()
        )
            .containsExactly("ripeness", "unripe", "color", "red")
        assertThat(getGeneratingAction(target, "test/out.txt").getOwner().getExecProperties())
            .containsExactly()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun aspectInheritsPlatformExecGroupExecProperty() {
        createToolchainsAndPlatforms()
        writeRuleWithActionsAndWatermelonExecGroup()

        scratch.file(
            "test/BUILD",
            """
        load("//test:defs.bzl", "with_actions")

        with_actions(
            name = "papaya",
            output = "out.txt",
            watermelon_output = "watermelon_out.txt",
        )
        
        """.trimIndent()
        )

        val target: ConfiguredTarget? = getConfiguredTarget("//test:papaya")

        assertThat(
            getGeneratingAction(target, "test/watermelon_out.txt").getOwner().getExecProperties()
        )
            .containsExactly("ripeness", "unripe", "color", "red")
        assertThat(getGeneratingAction(target, "test/out.txt").getOwner().getExecProperties())
            .containsExactly()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun ruleOverridePlatformExecGroupExecProperty() {
        createToolchainsAndPlatforms()
        writeRuleWithActionsAndWatermelonExecGroup()

        scratch.file(
            "test/BUILD",
            """
        load("//test:defs.bzl", "with_actions")

        with_actions(
            name = "papaya",
            exec_properties = {
                "watermelon.ripeness": "ripe",
                "ripeness": "unknown",
            },
            output = "out.txt",
            watermelon_output = "watermelon_out.txt",
        )
        
        """.trimIndent()
        )

        val target: ConfiguredTarget? = getConfiguredTarget("//test:papaya")

        assertThat(
            getGeneratingAction(target, "test/watermelon_out.txt").getOwner().getExecProperties()
        )
            .containsExactly("ripeness", "ripe", "color", "red")
        assertThat(getGeneratingAction(target, "test/out.txt").getOwner().getExecProperties())
            .containsExactly("ripeness", "unknown")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRuleExecGroup() {
        scratch.file(
            "rule/my_toolchain.bzl",
            """
        def _impl(ctx):
            return [platform_common.ToolchainInfo(label = ctx.label)]

        my_toolchain = rule(
            implementation = _impl,
        )
        
        """.trimIndent()
        )
        scratch.file(
            "rule/BUILD",
            """
        toolchain_type(name = "toolchain_type")
        
        """.trimIndent()
        )
        scratch.file(
            "toolchain/BUILD",
            """
        load("//rule:my_toolchain.bzl", "my_toolchain")

        my_toolchain(
            name = "target_target",
        )

        toolchain(
            name = "target_target_toolchain",
            exec_compatible_with = ["CONSTRAINTS_PACKAGE_ROOTos:linux"],
            target_compatible_with = ["CONSTRAINTS_PACKAGE_ROOTos:linux"],
            toolchain = ":target_target",
            toolchain_type = "//rule:toolchain_type",
        )

        my_toolchain(
            name = "exec_target",
        )

        toolchain(
            name = "exec_target_toolchain",
            exec_compatible_with = ["CONSTRAINTS_PACKAGE_ROOTos:macos"],
            target_compatible_with = ["CONSTRAINTS_PACKAGE_ROOTos:linux"],
            toolchain = ":exec_target",
            toolchain_type = "//rule:toolchain_type",
        )
        
        """
                .trimIndent()
                .replace("CONSTRAINTS_PACKAGE_ROOT", TestConstants.CONSTRAINTS_PACKAGE_ROOT)
        )

        scratch.overwriteFile(
            "platform/BUILD",
            """
        constraint_setting(
            name = "fast_cpu",
            default_constraint_value = ":no_fast_cpu",
        )

        constraint_value(
            name = "no_fast_cpu",
            constraint_setting = ":fast_cpu",
        )

        constraint_value(
            name = "has_fast_cpu",
            constraint_setting = ":fast_cpu",
        )

        constraint_setting(
            name = "gpu",
            default_constraint_value = ":no_gpu",
        )

        constraint_value(
            name = "no_gpu",
            constraint_setting = ":gpu",
        )

        constraint_value(
            name = "has_gpu",
            constraint_setting = ":gpu",
        )

        platform(
            name = "target_platform",
            constraint_values = [
                "CONSTRAINTS_PACKAGE_ROOTos:linux",
            ],
        )

        platform(
            name = "fast_cpu_platform",
            constraint_values = [
                "CONSTRAINTS_PACKAGE_ROOTos:macos",
                ":has_fast_cpu",
            ],
            exec_properties = {
                "require_fast_cpu": "true",
            },
        )

        platform(
            name = "gpu_platform",
            constraint_values = [
                "CONSTRAINTS_PACKAGE_ROOTos:linux",
                ":has_gpu",
            ],
            exec_properties = {
                "require_gpu": "true",
            },
        )
        
        """
                .trimIndent()
                .replace("CONSTRAINTS_PACKAGE_ROOT", TestConstants.CONSTRAINTS_PACKAGE_ROOT)
        )

        scratch.file(
            "test/defs.bzl",
            """
        MyInfo = provider(fields = ["toolchain_label"])

        def _impl(ctx):
            executable = ctx.actions.declare_file(ctx.label.name)
            ctx.actions.run_shell(
                outputs = [executable],
                command = "touch ${'$'}1",
                arguments = [executable.path],
            )
            return [
                DefaultInfo(
                    executable = executable,
                ),
                MyInfo(
                    toolchain_label = ctx.toolchains["//rule:toolchain_type"].label,
                ),
            ]

        my_cc_test = rule(
            implementation = _impl,
            test = True,
            toolchains = ["//rule:toolchain_type"],
        )
        
        """.trimIndent()
        )

        scratch.file(
            "test/BUILD",
            """
        load("//test:defs.bzl", "my_cc_test")

        my_cc_test(
            name = "my_test",
            exec_compatible_with = [
                "//platform:has_fast_cpu",
            ],
            exec_group_compatible_with = {
                "test": [
                    "//platform:has_gpu",
                ],
            },
        )
        
        """.trimIndent()
        )

        useConfiguration(
            "--extra_toolchains=//toolchain:target_target_toolchain,//toolchain:exec_target_toolchain",
            "--platforms=//platform:target_platform",
            "--extra_execution_platforms=//platform:target_platform,//platform:fast_cpu_platform,//platform:gpu_platform"
        )

        val target: ConfiguredTarget? = getConfiguredTarget("//test:my_test")

        val key: Provider.Key =
            Key(keyForBuild(Label.parseCanonical("//test:defs.bzl")), "MyInfo")
        val toolchainLabel: Label? = (target.get(key) as StructImpl).getValue("toolchain_label") as Label?
        assertThat(toolchainLabel).isEqualTo(Label.parseCanonicalUnchecked("//toolchain:exec_target"))

        val compileAction: Action = getGeneratingAction(target, "test/my_test")
        assertThat(compileAction.getExecutionPlatform().label())
            .isEqualTo(Label.parseCanonicalUnchecked("//platform:fast_cpu_platform"))
        assertThat(compileAction.getExecProperties()).containsExactly("require_fast_cpu", "true")

        val testAction: Action =
            getActions("//test:my_test").stream()
                .filter { action: Action -> action.getMnemonic().equals("TestRunner") }
                .findFirst()
                .orElseThrow()
        assertThat(testAction.getExecutionPlatform().label())
            .isEqualTo(Label.parseCanonicalUnchecked("//platform:gpu_platform"))
        assertThat(testAction.getExecProperties()).containsExactly("require_gpu", "true")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun invalidExecGroupCompatibleWith() {
        scratch.file(
            "test/defs.bzl",
            """
        def _impl(ctx):
            pass

        my_rule = rule(
            implementation = _impl,
            exec_groups = {
                "my_group": exec_group(),
            },
        )
        
        """.trimIndent()
        )

        scratch.file(
            "test/BUILD",
            """
        load("//test:defs.bzl", "my_rule")

        my_rule(
            name = "a",
            exec_group_compatible_with = {
                "my_grou": [
                    "CONSTRAINTS_PACKAGE_ROOTos:linux",
                ],
            },
        )
        
        """
                .trimIndent()
                .replace("CONSTRAINTS_PACKAGE_ROOT".toRegex(), TestConstants.CONSTRAINTS_PACKAGE_ROOT)
        )

        reporter.removeHandler(FoundationTestCase.failFastHandler)
        getConfiguredTarget("//test:a")
        assertContainsEvent(
            "Tried to set execution constraints for non-existent exec groups on"
                    + " //test:a: my_grou (did you mean 'my_group'?)"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun multipleExecGroups() {
        scratch.file(
            "test/defs.bzl",
            """
        def _impl(ctx):
            outs = []
            for i in range(1, 5):
                out = ctx.actions.declare_file(ctx.label.name + str(i))
                ctx.actions.run_shell(
                    outputs = [out],
                    command = "echo hello > ${'$'}1",
                    arguments = [out.path],
                    exec_group = "exec" + str(i),
                )
                outs.append(out)
            return DefaultInfo(files = depset(outs))

        my_rule = rule(
            implementation = _impl,
            exec_groups = {
                "exec1": exec_group(),
                "exec2": exec_group(),
                "exec3": exec_group(),
                "exec4": exec_group(),
            },
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load("//test:defs.bzl", "my_rule")

        platform(
            name = "linux_x86_64",
            constraint_values = [
                "CONSTRAINTS_PACKAGE_ROOTos:linux",
                "CONSTRAINTS_PACKAGE_ROOTcpu:x86_64",
            ],
        )

        platform(
            name = "linux_arm64",
            constraint_values = [
                "CONSTRAINTS_PACKAGE_ROOTos:linux",
                "CONSTRAINTS_PACKAGE_ROOTcpu:arm64",
            ],
        )

        platform(
            name = "macos_x86_64",
            constraint_values = [
                "CONSTRAINTS_PACKAGE_ROOTos:macos",
                "CONSTRAINTS_PACKAGE_ROOTcpu:x86_64",
            ],
        )

        platform(
            name = "macos_arm64",
            constraint_values = [
                "CONSTRAINTS_PACKAGE_ROOTos:macos",
                "CONSTRAINTS_PACKAGE_ROOTcpu:arm64",
            ],
        )

        my_rule(
            name = "a",
            exec_group_compatible_with = {
                "exec1": [
                    "CONSTRAINTS_PACKAGE_ROOTos:linux",
                    "CONSTRAINTS_PACKAGE_ROOTcpu:x86_64",
                ],
                "exec2": [
                    "CONSTRAINTS_PACKAGE_ROOTos:linux",
                    "CONSTRAINTS_PACKAGE_ROOTcpu:arm64",
                ],
                "exec3": [
                    "CONSTRAINTS_PACKAGE_ROOTos:macos",
                    "CONSTRAINTS_PACKAGE_ROOTcpu:x86_64",
                ],
                "exec4": [
                    "CONSTRAINTS_PACKAGE_ROOTos:macos",
                    "CONSTRAINTS_PACKAGE_ROOTcpu:arm64",
                ],
            },
        )
        
        """
                .trimIndent()
                .replace("CONSTRAINTS_PACKAGE_ROOT".toRegex(), TestConstants.CONSTRAINTS_PACKAGE_ROOT)
        )

        useConfiguration(
            "--extra_execution_platforms=//test:linux_x86_64,//test:linux_arm64,"
                    + "//test:macos_x86_64,//test:macos_arm64"
        )

        val target: ConfiguredTarget? = getConfiguredTarget("//test:a")

        val action1: Action = getGeneratingAction(target, "test/a1")
        assertThat(action1.getExecutionPlatform().label())
            .isEqualTo(Label.parseCanonicalUnchecked("//test:linux_x86_64"))

        val action2: Action = getGeneratingAction(target, "test/a2")
        assertThat(action2.getExecutionPlatform().label())
            .isEqualTo(Label.parseCanonicalUnchecked("//test:linux_arm64"))

        val action3: Action = getGeneratingAction(target, "test/a3")
        assertThat(action3.getExecutionPlatform().label())
            .isEqualTo(Label.parseCanonicalUnchecked("//test:macos_x86_64"))

        val action4: Action = getGeneratingAction(target, "test/a4")
        assertThat(action4.getExecutionPlatform().label())
            .isEqualTo(Label.parseCanonicalUnchecked("//test:macos_arm64"))
    }
}
