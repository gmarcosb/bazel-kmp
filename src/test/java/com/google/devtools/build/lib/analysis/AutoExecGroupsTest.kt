// Copyright 2023 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.rules.cpp.CppRuleClasses.CPP_LINK_EXEC_GROUP

/** Test for automatic exec groups.  */
@RunWith(TestParameterInjector::class)
class AutoExecGroupsTest : BuildViewTestCase() {
    /**
     * Sets up two toolchains types, each with a single toolchain implementation and a single
     * exec_compatible_with platform.
     * 
     * 
     * toolchain_type_1 -> foo_toolchain -> exec_compatible_with platform_1; toolchain_type_1 ->
     * baz_toolchain -> exec_compatible_with platform_2; toolchain_type_2 -> bar_toolchain ->
     * exec_compatible_with platform_2
     */
    @Throws(java.lang.Exception::class)
    fun createToolchainsAndPlatforms() {
        scratch.overwriteFile(
            "rule/test_toolchain.bzl",
            """
        def _impl(ctx):
            return [platform_common.ToolchainInfo(
                tool = ctx.executable._tool,
                files_to_run = ctx.attr._tool[DefaultInfo].files_to_run,
            )]

        test_toolchain = rule(
            implementation = _impl,
            attrs = {
                "_tool": attr.label(
                    default = "//toolchain:b_tool",
                    executable = True,
                    cfg = "exec",
                ),
            },
        )
        
        """.trimIndent()
        )
        scratch.overwriteFile(
            "rule/BUILD",
            """
        load("@rules_java//java:defs.bzl", "java_runtime")
        exports_files(["test_toolchain/bzl"])

        toolchain_type(name = "toolchain_type_1")

        toolchain_type(name = "toolchain_type_2")

        java_runtime(
            name = "jvm-k8",
            srcs = [
                "k8/a",
                "k8/b",
            ],
            java_home = "k8",
        )
        
        """.trimIndent()
        )
        scratch.overwriteFile(
            "toolchain/BUILD",
            """
        load("//rule:test_toolchain.bzl", "test_toolchain")

        genrule(
            name = "a_tool",
            outs = ["atool"],
            cmd = "",
            executable = True,
        )

        genrule(
            name = "b_tool",
            outs = ["btool"],
            cmd = "",
            executable = True,
        )

        test_toolchain(
            name = "foo",
        )

        toolchain(
            name = "foo_toolchain",
            exec_compatible_with = ["//platforms:constraint_1"],
            target_compatible_with = ["//platforms:constraint_1"],
            toolchain = ":foo",
            toolchain_type = "//rule:toolchain_type_1",
        )

        toolchain(
            name = "baz_toolchain",
            exec_compatible_with = ["//platforms:constraint_2"],
            target_compatible_with = ["//platforms:constraint_1"],
            toolchain = ":foo",
            toolchain_type = "//rule:toolchain_type_1",
        )

        toolchain(
            name = "qux_toolchain",
            exec_compatible_with = ["//platforms:constraint_3"],
            target_compatible_with = ["//platforms:constraint_1"],
            toolchain = ":foo",
            toolchain_type = "//rule:toolchain_type_1",
        )

        test_toolchain(
            name = "bar",
        )

        toolchain(
            name = "bar_toolchain",
            exec_compatible_with = ["//platforms:constraint_2"],
            target_compatible_with = ["//platforms:constraint_1"],
            toolchain = ":bar",
            toolchain_type = "//rule:toolchain_type_2",
        )

        toolchain(
            name = "quz_toolchain",
            exec_compatible_with = ["//platforms:constraint_4"],
            target_compatible_with = ["//platforms:constraint_1"],
            toolchain = ":bar",
            toolchain_type = "//rule:toolchain_type_2",
        )
        
        """.trimIndent()
        )

        scratch.overwriteFile(
            "platforms/BUILD",
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

        constraint_value(
            name = "constraint_3",
            constraint_setting = ":setting",
        )

        constraint_value(
            name = "constraint_4",
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

        platform(
            name = "platform_3",
            constraint_values = [":constraint_3"],
        )

        platform(
            name = "platform_4",
            constraint_values = [":constraint_4"],
        )
        
        """.trimIndent()
        )

        getAnalysisMock()
            .ccSupport()
            .setupCcToolchainConfig(
                mockToolsConfig,
                CcToolchainConfig.builder()
                    .withToolchainTargetConstraints()
                    .withToolchainExecConstraints()
                    .withCpu("fake")
            )
    }

    @Before
    @Throws(java.lang.Exception::class)
    fun setup() {
        useConfiguration()
    }

    @Throws(java.lang.Exception::class)
    public override fun useConfiguration(vararg args: String?) {
        // These need to be defined before the configuration is parsed.
        createToolchainsAndPlatforms()
        val flags = arrayOf<String?>(
            "--extra_toolchains=//toolchain:foo_toolchain,//toolchain:bar_toolchain,//toolchain:baz_toolchain,//toolchain:quz_toolchain,toolchain:qux_toolchain",
            "--platforms=//platforms:platform_1",
            "--extra_execution_platforms=//platforms:platform_1,//platforms:platform_2,//platforms:platform_3,//platforms:platform_4",
            "--incompatible_enable_cc_toolchain_resolution"
        )

        super.useConfiguration(*com.google.common.collect.ObjectArrays.concat<String?>(flags, args, String::class.java))
    }

    /**
     * Creates custom rule which produces action with `actionParameters`, adds `extraAttributes`,
     * defines `toolchains`, and adds custom exec groups from `execGroups`. Depending on
     * `actionRunCommand` parameter, `actions.run` or `actions.run_shell` is created.
     */
    @Throws(java.lang.Exception::class)
    private fun createCustomRule(
        action: String,
        actionParameters: String,
        extraAttributes: String?,
        toolchains: String?,
        execGroups: String?,
        execCompatibleWith: String?
    ) {
        scratch.file(
            "test/defs.bzl",
            "def _impl(ctx):",
            "  output_jar = ctx.actions.declare_file(ctx.label.name + '_dummy_output.jar')",
            "  " + action + "(",
            actionParameters,
            "    outputs = [output_jar],",
            if (action == "ctx.actions.run")
                (if (actionParameters.contains("executable =") // avoid adding executable parameter twice
                )
                    ""
                else
                    "executable = ctx.toolchains['//rule:toolchain_type_1'].tool,")
            else
                "    command = 'echo',",
            "  )",
            "  return [DefaultInfo(files = depset([output_jar]))]",
            "custom_rule = rule(",
            "  implementation = _impl,",
            "  attrs = {",
            "    '_tool': attr.label(default = '//toolchain:a_tool', cfg = 'exec', executable = True),",
            "    '_nonexecutable_tool': attr.label(default = '//toolchain:b_tool', cfg = 'exec'),",
            extraAttributes,
            "  },",
            "  exec_groups = {",
            execGroups,
            "  },",
            "  toolchains = " + toolchains + ",",
            execCompatibleWith,
            ")"
        )
        scratch.file(
            "test/BUILD",
            """
        load("//test:defs.bzl", "custom_rule")

        custom_rule(name = "custom_rule_name")
        
        """.trimIndent()
        )
    }

    @org.junit.Test
    @TestParameters(
        "{action: ctx.actions.run}", "{action: ctx.actions.run_shell}"
    )
    @Throws(java.lang.Exception::class)
    fun automaticExecutionGroups_disabledAndAttributeFalse_disabled(action: String) {
        createCustomRule( /* action= */
            action,  /* actionParameters= */
            "toolchain = '//rule:toolchain_type_1',",  /* extraAttributes= */
            "'_use_auto_exec_groups': attr.bool(default = False),",  /* toolchains= */
            "['//rule:toolchain_type_1']",  /* execGroups= */
            "",  /* execCompatibleWith= */
            ""
        )

        val target: ConfiguredTarget? = getConfiguredTarget("//test:custom_rule_name")
        val execGroups: com.google.common.collect.ImmutableMap<String?, DeclaredExecGroup?>? =
            getRuleContext(target).getExecGroups().execGroups()

        Truth.assertThat(execGroups).isEmpty()
    }

    @org.junit.Test
    @TestParameters(
        "{action: ctx.actions.run}", "{action: ctx.actions.run_shell}"
    )
    @Throws(java.lang.Exception::class)
    fun automaticExecutionGroups_disabledAndAttributeTrue_enabled(action: String) {
        createCustomRule( /* action= */
            action,  /* actionParameters= */
            "toolchain = '//rule:toolchain_type_1',",  /* extraAttributes= */
            "'_use_auto_exec_groups': attr.bool(default = True),",  /* toolchains= */
            "['//rule:toolchain_type_1']",  /* execGroups= */
            "",  /* execCompatibleWith= */
            ""
        )

        val target: ConfiguredTarget? = getConfiguredTarget("//test:custom_rule_name")
        val execGroups: com.google.common.collect.ImmutableMap<String?, DeclaredExecGroup?>? =
            getRuleContext(target).getExecGroups().execGroups()

        Truth.assertThat(execGroups).isNotEmpty()
    }

    @org.junit.Test
    @TestParameters(
        "{action: ctx.actions.run}", "{action: ctx.actions.run_shell}"
    )
    @Throws(java.lang.Exception::class)
    fun automaticExecutionGroups_disabledAndAttributeNotSet_disabled(action: String) {
        createCustomRule( /* action= */
            action,  /* actionParameters= */
            "toolchain = '//rule:toolchain_type_1',",  /* extraAttributes= */
            "",  /* toolchains= */
            "['//rule:toolchain_type_1']",  /* execGroups= */
            "",  /* execCompatibleWith= */
            ""
        )
        useConfiguration("--incompatible_auto_exec_groups=False")

        val target: ConfiguredTarget? = getConfiguredTarget("//test:custom_rule_name")
        val execGroups: com.google.common.collect.ImmutableMap<String?, DeclaredExecGroup?>? =
            getRuleContext(target).getExecGroups().execGroups()

        Truth.assertThat(execGroups).isEmpty()
    }

    @org.junit.Test
    @TestParameters(
        "{action: ctx.actions.run}", "{action: ctx.actions.run_shell}"
    )
    @Throws(java.lang.Exception::class)
    fun automaticExecutionGroups_enabledAndAttributeFalse_disabled(action: String) {
        createCustomRule( /* action= */
            action,  /* actionParameters= */
            "toolchain = '//rule:toolchain_type_1',",  /* extraAttributes= */
            "'_use_auto_exec_groups': attr.bool(default = False),",  /* toolchains= */
            "['//rule:toolchain_type_1']",  /* execGroups= */
            "",  /* execCompatibleWith= */
            ""
        )
        useConfiguration("--incompatible_auto_exec_groups")

        val target: ConfiguredTarget? = getConfiguredTarget("//test:custom_rule_name")
        val execGroups: com.google.common.collect.ImmutableMap<String?, DeclaredExecGroup?>? =
            getRuleContext(target).getExecGroups().execGroups()

        Truth.assertThat(execGroups).isEmpty()
    }

    @org.junit.Test
    @TestParameters(
        "{action: ctx.actions.run}", "{action: ctx.actions.run_shell}"
    )
    @Throws(java.lang.Exception::class)
    fun automaticExecutionGroups_enabledAndAttributeTrue_enabled(action: String) {
        createCustomRule( /* action= */
            action,  /* actionParameters= */
            "toolchain = '//rule:toolchain_type_1',",  /* extraAttributes= */
            "'_use_auto_exec_groups': attr.bool(default = True)",  /* toolchains= */
            "['//rule:toolchain_type_1']",  /* execGroups= */
            "",  /* execCompatibleWith= */
            ""
        )
        useConfiguration("--incompatible_auto_exec_groups")

        val target: ConfiguredTarget? = getConfiguredTarget("//test:custom_rule_name")
        val execGroups: com.google.common.collect.ImmutableMap<String?, DeclaredExecGroup?>? =
            getRuleContext(target).getExecGroups().execGroups()

        Truth.assertThat(execGroups).isNotEmpty()
    }

    @org.junit.Test
    @TestParameters(
        "{action: ctx.actions.run}", "{action: ctx.actions.run_shell}"
    )
    @Throws(java.lang.Exception::class)
    fun automaticExecutionGroups_enabledAndAttributeNotSet_enabled(action: String) {
        createCustomRule( /* action= */
            action,  /* actionParameters= */
            "toolchain = '//rule:toolchain_type_1',",  /* extraAttributes= */
            "",  /* toolchains= */
            "['//rule:toolchain_type_1']",  /* execGroups= */
            "",  /* execCompatibleWith= */
            ""
        )
        useConfiguration("--incompatible_auto_exec_groups")

        val target: ConfiguredTarget? = getConfiguredTarget("//test:custom_rule_name")
        val execGroups: com.google.common.collect.ImmutableMap<String?, DeclaredExecGroup?>? =
            getRuleContext(target).getExecGroups().execGroups()

        Truth.assertThat(execGroups).isNotEmpty()
    }

    @org.junit.Test
    @TestParameters(
        "{action: ctx.actions.run}", "{action: ctx.actions.run_shell}"
    )
    @Throws(java.lang.Exception::class)
    fun getToolchainInfoAndContext_automaticExecGroupsEnabled(action: String) {
        createCustomRule( /* action= */
            action,  /* actionParameters= */
            "toolchain = '//rule:toolchain_type_1',",  /* extraAttributes= */
            "",  /* toolchains= */
            "['//rule:toolchain_type_1']",  /* execGroups= */
            "",  /* execCompatibleWith= */
            ""
        )
        useConfiguration("--incompatible_auto_exec_groups")

        val target: ConfiguredTarget? = getConfiguredTarget("//test:custom_rule_name")
        val ruleContext: RuleContext = getRuleContext(target)
        val defaultExecGroupToolchains: com.google.common.collect.ImmutableMap<ToolchainTypeInfo?, ToolchainInfo?>? =
            ruleContext.getToolchainContext().toolchains()
        val toolchainInfo: ToolchainInfo? =
            ruleContext.getToolchainInfo(Label.parseCanonical("//rule:toolchain_type_1"))

        Truth.assertThat(defaultExecGroupToolchains).isEmpty()
        assertThat(toolchainInfo).isNotNull()
    }

    @org.junit.Test
    @TestParameters(
        "{action: ctx.actions.run}", "{action: ctx.actions.run_shell}"
    )
    @Throws(java.lang.Exception::class)
    fun getToolchainInfoAndContext_automaticExecGroupsDisabled(action: String) {
        createCustomRule( /* action= */
            action,  /* actionParameters= */
            "toolchain = '//rule:toolchain_type_1',",  /* extraAttributes= */
            "",  /* toolchains= */
            "['//rule:toolchain_type_1']",  /* execGroups= */
            "",  /* execCompatibleWith= */
            ""
        )
        useConfiguration("--incompatible_auto_exec_groups=False")

        val target: ConfiguredTarget? = getConfiguredTarget("//test:custom_rule_name")
        val ruleContext: RuleContext = getRuleContext(target)
        val defaultExecGroupToolchains: com.google.common.collect.ImmutableMap<ToolchainTypeInfo?, ToolchainInfo?>? =
            ruleContext.getToolchainContext().toolchains()
        val toolchainInfo: ToolchainInfo? =
            ruleContext.getToolchainInfo(Label.parseCanonical("//rule:toolchain_type_1"))

        Truth.assertThat(defaultExecGroupToolchains).isNotEmpty()
        assertThat(toolchainInfo).isNotNull()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun toolInExecutableIdentified_noToolchainParameter_noError() {
        createCustomRule( /* action= */
            "ctx.actions.run",  /* actionParameters= */
            "executable = ctx.executable._tool, ",  /* extraAttributes= */
            "",  /* toolchains= */
            "['//rule:toolchain_type_1', '//rule:toolchain_type_2']",  /* execGroups= */
            "",  /* execCompatibleWith= */
            ""
        )
        useConfiguration("--incompatible_auto_exec_groups")

        getConfiguredTarget("//test:custom_rule_name")

        assertNoEvents()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun toolWithFilesToRunExecutable_noToolchainParameter_reportsError() {
        createCustomRule( /* action= */
            "ctx.actions.run",  /* actionParameters= */
            "executable ="
                    + " ctx.attr._nonexecutable_tool[DefaultInfo].files_to_run.executable,",  /* extraAttributes= */
            "",  /* toolchains= */
            "['//rule:toolchain_type_1', '//rule:toolchain_type_2']",  /* execGroups= */
            "",  /* execCompatibleWith= */
            ""
        )
        useConfiguration("--incompatible_auto_exec_groups")

        reporter.removeHandler(FoundationTestCase.failFastHandler)
        getConfiguredTarget("//test:custom_rule_name")

        assertContainsEvent(
            "Couldn't identify if tools are from implicit dependencies or a toolchain. Please set"
                    + " the toolchain parameter. If you're not using a toolchain, set it to 'None'."
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun toolWithFilesToRunExecutable_toolchainParameterSetToNone_noError() {
        createCustomRule( /* action= */
            "ctx.actions.run",  /* actionParameters= */
            "toolchain = None,"
                    + " executable = ctx.attr._nonexecutable_tool[DefaultInfo].files_to_run.executable,",  /* extraAttributes= */
            "",  /* toolchains= */
            "['//rule:toolchain_type_1', '//rule:toolchain_type_2']",  /* execGroups= */
            "",  /* execCompatibleWith= */
            ""
        )
        useConfiguration("--incompatible_auto_exec_groups")

        getConfiguredTarget("//test:custom_rule_name")

        assertNoEvents()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun toolWithFilesToRunExecutable_noToolchains_noError() {
        createCustomRule( /* action= */
            "ctx.actions.run",  /* actionParameters= */
            " executable ="
                    + " ctx.attr._nonexecutable_tool[DefaultInfo].files_to_run.executable,",  /* extraAttributes= */
            "",  /* toolchains= */
            "[]",  /* execGroups= */
            "",  /* execCompatibleWith= */
            ""
        )
        useConfiguration("--incompatible_auto_exec_groups")

        getConfiguredTarget("//test:custom_rule_name")

        assertNoEvents()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun toolInExecutableUnidentified_noToolchainParameter_reportsError() {
        createCustomRule( /* action= */
            "ctx.actions.run",  /* actionParameters= */
            "executable = ctx.toolchains['//rule:toolchain_type_1'].tool, ",  /* extraAttributes= */
            "",  /* toolchains= */
            "['//rule:toolchain_type_1', '//rule:toolchain_type_2']",  /* execGroups= */
            "",  /* execCompatibleWith= */
            ""
        )
        useConfiguration("--incompatible_auto_exec_groups")

        reporter.removeHandler(FoundationTestCase.failFastHandler)
        getConfiguredTarget("//test:custom_rule_name")

        assertContainsEvent(
            "Couldn't identify if tools are from implicit dependencies or a toolchain. Please set"
                    + " the toolchain parameter. If you're not using a toolchain, set it to 'None'."
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun toolWithFilesToRunInExecutableUnidentified_noToolchainParameter_reportsError() {
        createCustomRule( /* action= */
            "ctx.actions.run",  /* actionParameters= */
            "executable ="
                    + " ctx.toolchains['//rule:toolchain_type_1'].files_to_run, ",  /* extraAttributes= */
            "",  /* toolchains= */
            "['//rule:toolchain_type_1', '//rule:toolchain_type_2']",  /* execGroups= */
            "",  /* execCompatibleWith= */
            ""
        )
        useConfiguration("--incompatible_auto_exec_groups")

        reporter.removeHandler(FoundationTestCase.failFastHandler)
        getConfiguredTarget("//test:custom_rule_name")

        assertContainsEvent(
            "Couldn't identify if tools are from implicit dependencies or a toolchain. Please set"
                    + " the toolchain parameter. If you're not using a toolchain, set it to 'None'."
        )
    }

    @org.junit.Test
    @TestParameters(
        "{action: ctx.actions.run}", "{action: ctx.actions.run_shell}"
    )
    @Throws(java.lang.Exception::class)
    fun toolInToolsUnidentified_noToolchainParameter_reportsError(action: String) {
        createCustomRule( /* action= */
            action,  /* actionParameters= */
            "tools = [ctx.toolchains['//rule:toolchain_type_1'].tool],",  /* extraAttributes= */
            "",  /* toolchains= */
            "['//rule:toolchain_type_1', '//rule:toolchain_type_2']",  /* execGroups= */
            "",  /* execCompatibleWith= */
            ""
        )
        useConfiguration("--incompatible_auto_exec_groups")

        reporter.removeHandler(FoundationTestCase.failFastHandler)
        getConfiguredTarget("//test:custom_rule_name")

        assertContainsEvent(
            "Couldn't identify if tools are from implicit dependencies or a toolchain. Please set"
                    + " the toolchain parameter. If you're not using a toolchain, set it to 'None'."
        )
    }

    @org.junit.Test
    @TestParameters(
        "{action: ctx.actions.run}", "{action: ctx.actions.run_shell}"
    )
    @Throws(java.lang.Exception::class)
    fun toolWithFilesToRunInToolsUnidentified_noToolchainParameter_reportsError(action: String) {
        createCustomRule( /* action= */
            action,  /* actionParameters= */
            "tools = [ctx.toolchains['//rule:toolchain_type_1'].files_to_run],",  /* extraAttributes= */
            "",  /* toolchains= */
            "['//rule:toolchain_type_1', '//rule:toolchain_type_2']",  /* execGroups= */
            "",  /* execCompatibleWith= */
            ""
        )
        useConfiguration("--incompatible_auto_exec_groups")

        reporter.removeHandler(FoundationTestCase.failFastHandler)
        getConfiguredTarget("//test:custom_rule_name")

        assertContainsEvent(
            "Couldn't identify if tools are from implicit dependencies or a toolchain. Please set"
                    + " the toolchain parameter. If you're not using a toolchain, set it to 'None'."
        )
    }

    @org.junit.Test
    @TestParameters(
        "{action: ctx.actions.run}", "{action: ctx.actions.run_shell}"
    )
    @Throws(java.lang.Exception::class)
    fun depsetInTools_noToolchainParameter_reportsError(action: String) {
        createCustomRule( /* action= */
            action,  /* actionParameters= */
            "tools = [depset([ctx.executable._tool])], ",  /* extraAttributes= */
            "",  /* toolchains= */
            "['//rule:toolchain_type_1', '//rule:toolchain_type_2']",  /* execGroups= */
            "",  /* execCompatibleWith= */
            ""
        )
        useConfiguration("--incompatible_auto_exec_groups")

        reporter.removeHandler(FoundationTestCase.failFastHandler)
        getConfiguredTarget("//test:custom_rule_name")

        assertContainsEvent(
            "Couldn't identify if tools are from implicit dependencies or a toolchain. Please set"
                    + " the toolchain parameter. If you're not using a toolchain, set it to 'None'."
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun toolInExecutableUnidentified_toolchainParameter_noError() {
        createCustomRule( /* action= */
            "ctx.actions.run",  /* actionParameters= */
            "executable = ctx.toolchains['//rule:toolchain_type_1'].tool, "
                    + "toolchain = '//rule:toolchain_type_1',",  /* extraAttributes= */
            "",  /* toolchains= */
            "['//rule:toolchain_type_1', '//rule:toolchain_type_2']",  /* execGroups= */
            "",  /* execCompatibleWith= */
            ""
        )
        useConfiguration("--incompatible_auto_exec_groups")

        getConfiguredTarget("//test:custom_rule_name")

        assertNoEvents()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun toolInExecutableUnidentified_toolchainParameterNone_noError() {
        // Setting toolchain parameter that doesn't match what is used in the executable is technically
        // an error. However, we cannot detect this error at analysis time.
        // It's possible to construct a correct case where executable is from a provider from a
        // dependency that is not a toolchain (like proto_lang_toolchain). In this case the user should
        // set `toolchain = None` (because we wouldn't/couldn't detect where executable is coming from)
        createCustomRule( /* action= */
            "ctx.actions.run",  /* actionParameters= */
            "executable = ctx.toolchains['//rule:toolchain_type_1'].tool, "
                    + "toolchain = None,",  /* extraAttributes= */
            "",  /* toolchains= */
            "['//rule:toolchain_type_1', '//rule:toolchain_type_2']",  /* execGroups= */
            "",  /* execCompatibleWith= */
            ""
        )
        useConfiguration("--incompatible_auto_exec_groups")

        getConfiguredTarget("//test:custom_rule_name")

        assertNoEvents()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun execGroupSetOnAction_noToolchainParameter_noError() {
        scratch.file(
            "test/defs.bzl",
            """
        def _impl(ctx):
            output_jar = ctx.actions.declare_file("test_" + ctx.label.name + ".jar")
            ctx.actions.run(
                outputs = [output_jar],
                executable = ctx.toolchains["//rule:toolchain_type_2"].tool,
                exec_group = "custom_exec_group",
            )
            return []

        custom_rule = rule(
            implementation = _impl,
            exec_groups = {
                "custom_exec_group": exec_group(toolchains = ["//rule:toolchain_type_2"]),
            },
            toolchains = ["//rule:toolchain_type_2"],
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load("//test:defs.bzl", "custom_rule")

        custom_rule(name = "custom_rule_name")
        
        """.trimIndent()
        )
        useConfiguration("--incompatible_auto_exec_groups")

        reporter.removeHandler(FoundationTestCase.failFastHandler)
        getConfiguredTarget("//test:custom_rule_name")

        assertNoEvents()
    }

    @org.junit.Test
    @TestParameters(
        "{action: ctx.actions.run}", "{action: ctx.actions.run_shell}"
    )
    @Throws(java.lang.Exception::class)
    fun twoToolchains_createTwoExecutionGroups(action: String) {
        createCustomRule( /* action= */
            action,  /* actionParameters= */
            "toolchain = '//rule:toolchain_type_1',",  /* extraAttributes= */
            "",  /* toolchains= */
            "['//rule:toolchain_type_1', '//rule:toolchain_type_2']",  /* execGroups= */
            "",  /* execCompatibleWith= */
            ""
        )
        useConfiguration("--incompatible_auto_exec_groups")

        val target: ConfiguredTarget? = getConfiguredTarget("//test:custom_rule_name")
        val execGroups: ExecGroupCollection = getRuleContext(target).getExecGroups()

        assertThat(execGroups.execGroups().keySet())
            .containsExactly("//rule:toolchain_type_1", "//rule:toolchain_type_2")
        val declaredExecGroupTT1: DeclaredExecGroup = execGroups.getExecGroup("//rule:toolchain_type_1")
        assertThat(declaredExecGroupTT1.toolchainTypes())
            .containsExactly(
                ToolchainTypeRequirement.create(Label.parseCanonical("//rule:toolchain_type_1"))
            )
        assertThat(declaredExecGroupTT1.execCompatibleWith()).isEmpty()
        val declaredExecGroupTT2: DeclaredExecGroup = execGroups.getExecGroup("//rule:toolchain_type_2")
        assertThat(declaredExecGroupTT2.toolchainTypes())
            .containsExactly(
                ToolchainTypeRequirement.create(Label.parseCanonical("//rule:toolchain_type_2"))
            )
        assertThat(declaredExecGroupTT2.execCompatibleWith()).isEmpty()
    }

    @org.junit.Test
    @TestParameters(
        "{action: ctx.actions.run}", "{action: ctx.actions.run_shell}"
    )
    @Throws(java.lang.Exception::class)
    fun twoToolchains_threeToolchainContexts(action: String) {
        createCustomRule( /* action= */
            action,  /* actionParameters= */
            "toolchain = '//rule:toolchain_type_1',",  /* extraAttributes= */
            "",  /* toolchains= */
            "['//rule:toolchain_type_1', '//rule:toolchain_type_2']",  /* execGroups= */
            "",  /* execCompatibleWith= */
            ""
        )
        useConfiguration("--incompatible_auto_exec_groups")

        val target: ConfiguredTarget? = getConfiguredTarget("//test:custom_rule_name")
        val toolchainContextsKeys: com.google.common.collect.ImmutableSet<String?>? =
            getRuleContext(target).getToolchainContexts().contextMap().keySet()

        Truth.assertThat(toolchainContextsKeys)
            .containsExactly(
                DeclaredExecGroup.DEFAULT_EXEC_GROUP_NAME,
                "//rule:toolchain_type_1",
                "//rule:toolchain_type_2"
            )
    }

    @org.junit.Test
    @TestParameters(
        "{action: ctx.actions.run}", "{action: ctx.actions.run_shell}"
    )
    @Throws(java.lang.Exception::class)
    fun defaultExecGroupHasNoToolchains(action: String) {
        createCustomRule( /* action= */
            action,  /* actionParameters= */
            "toolchain = '//rule:toolchain_type_1',",  /* extraAttributes= */
            "",  /* toolchains= */
            "['//rule:toolchain_type_1', '//rule:toolchain_type_2']",  /* execGroups= */
            "",  /* execCompatibleWith= */
            ""
        )
        useConfiguration("--incompatible_auto_exec_groups")

        val target: ConfiguredTarget? = getConfiguredTarget("//test:custom_rule_name")
        val defaultExecGroupContext: ResolvedToolchainContext =
            getRuleContext(target).getToolchainContexts().getDefaultToolchainContext()

        assertThat(defaultExecGroupContext).isNotNull()
        assertThat(defaultExecGroupContext.toolchainTypes()).isEmpty()
    }

    @org.junit.Test
    @TestParameters(
        "{action: ctx.actions.run}", "{action: ctx.actions.run_shell}"
    )
    @Throws(java.lang.Exception::class)
    fun defaultExecGroupHasBasicExecutionPlatform(action: String) {
        createCustomRule( /* action= */
            action,  /* actionParameters= */
            "toolchain = '//rule:toolchain_type_1',",  /* extraAttributes= */
            "",  /* toolchains= */
            "['//rule:toolchain_type_1', '//rule:toolchain_type_2']",  /* execGroups= */
            "",  /* execCompatibleWith= */
            ""
        )
        useConfiguration("--incompatible_auto_exec_groups")

        val target: ConfiguredTarget? = getConfiguredTarget("//test:custom_rule_name")
        val defaultExecGroupContext: ResolvedToolchainContext =
            getRuleContext(target).getToolchainContexts().getDefaultToolchainContext()

        assertThat(defaultExecGroupContext).isNotNull()
        assertThat(defaultExecGroupContext.executionPlatform().label())
            .isEqualTo(Label.parseCanonical("//platforms:platform_1"))
    }

    @org.junit.Test
    @TestParameters(
        "{action: ctx.actions.run}", "{action: ctx.actions.run_shell}"
    )
    @Throws(java.lang.Exception::class)
    fun toolchainParameterAsLabel_correctParsingOfToolchain(action: String) {
        createCustomRule( /* action= */
            action,  /* actionParameters= */
            "toolchain = Label('@//rule:toolchain_type_1'),",  /* extraAttributes= */
            "",  /* toolchains= */
            "['//rule:toolchain_type_1', '//rule:toolchain_type_2']",  /* execGroups= */
            "",  /* execCompatibleWith= */
            ""
        )
        useConfiguration("--incompatible_auto_exec_groups")

        val target: ConfiguredTarget? = getConfiguredTarget("//test:custom_rule_name")
        val generatedAction: Action = getGeneratingAction(target, "test/custom_rule_name_dummy_output.jar")

        assertThat(generatedAction.getOwner().getExecutionPlatform().label())
            .isEqualTo(Label.parseCanonical("//platforms:platform_1"))
    }

    @org.junit.Test
    @TestParameters(
        "{action: ctx.actions.run}", "{action: ctx.actions.run_shell}"
    )
    @Throws(java.lang.Exception::class)
    fun toolchainParameterAsString_correctParsingOfToolchain(action: String) {
        createCustomRule( /* action= */
            action,  /* actionParameters= */
            "toolchain = '@//rule:toolchain_type_1',",  /* extraAttributes= */
            "",  /* toolchains= */
            "['//rule:toolchain_type_1', '//rule:toolchain_type_2']",  /* execGroups= */
            "",  /* execCompatibleWith= */
            ""
        )
        useConfiguration("--incompatible_auto_exec_groups")

        val target: ConfiguredTarget? = getConfiguredTarget("//test:custom_rule_name")
        val generatedAction: Action = getGeneratingAction(target, "test/custom_rule_name_dummy_output.jar")

        assertThat(generatedAction.getOwner().getExecutionPlatform().label())
            .isEqualTo(Label.parseCanonical("//platforms:platform_1"))
    }

    @org.junit.Test
    @TestParameters(
        "{action: ctx.actions.run}", "{action: ctx.actions.run_shell}"
    )
    @Throws(java.lang.Exception::class)
    fun toolchainParameterAsString_syntaxErrorInParsingOfToolchain(action: String) {
        createCustomRule( /* action= */
            action,  /* actionParameters= */
            "toolchain = 'rule:toolchain_type_1',",  /* extraAttributes= */
            "",  /* toolchains= */
            "['//rule:toolchain_type_1', '//rule:toolchain_type_2']",  /* execGroups= */
            "",  /* execCompatibleWith= */
            ""
        )
        useConfiguration("--incompatible_auto_exec_groups")

        reporter.removeHandler(FoundationTestCase.failFastHandler)
        getConfiguredTarget("//test:custom_rule_name")

        assertContainsEvent(
            "invalid label 'rule:toolchain_type_1': absolute label must begin with '@'" + " or '//'"
        )
    }

    @org.junit.Test
    @TestParameters(
        "{action: ctx.actions.run}", "{action: ctx.actions.run_shell}"
    )
    @Throws(java.lang.Exception::class)
    fun independentExecPlatformForAction_toolchainType1(action: String) {
        createCustomRule( /* action= */
            action,  /* actionParameters= */
            "toolchain = '//rule:toolchain_type_1',",  /* extraAttributes= */
            "",  /* toolchains= */
            "['//rule:toolchain_type_1', '//rule:toolchain_type_2']",  /* execGroups= */
            "",  /* execCompatibleWith= */
            ""
        )
        useConfiguration("--incompatible_auto_exec_groups")

        val target: ConfiguredTarget? = getConfiguredTarget("//test:custom_rule_name")
        val generatedAction: Action = getGeneratingAction(target, "test/custom_rule_name_dummy_output.jar")

        assertThat(generatedAction.getOwner().getExecutionPlatform().label())
            .isEqualTo(Label.parseCanonical("//platforms:platform_1"))
    }

    @org.junit.Test
    @TestParameters(
        "{action: ctx.actions.run}", "{action: ctx.actions.run_shell}"
    )
    @Throws(java.lang.Exception::class)
    fun independentExecPlatformForAction_toolchainType2(action: String) {
        createCustomRule( /* action= */
            action,  /* actionParameters= */
            "toolchain = '//rule:toolchain_type_2',",  /* extraAttributes= */
            "",  /* toolchains= */
            "['//rule:toolchain_type_1', '//rule:toolchain_type_2']",  /* execGroups= */
            "",  /* execCompatibleWith= */
            ""
        )
        useConfiguration("--incompatible_auto_exec_groups")

        val target: ConfiguredTarget? = getConfiguredTarget("//test:custom_rule_name")
        val generatedAction: Action = getGeneratingAction(target, "test/custom_rule_name_dummy_output.jar")

        assertThat(generatedAction.getOwner().getExecutionPlatform().label())
            .isEqualTo(Label.parseCanonical("//platforms:platform_2"))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun ctxToolchains_automaticExecGroupsEnabled() {
        scratch.file(
            "test/defs.bzl",
            """
        def _impl(ctx):
            toolchain_info = ctx.toolchains["//rule:toolchain_type_1"]
            if toolchain_info == None:
                fail("Toolchain info is None.")
            return []

        custom_rule = rule(
            implementation = _impl,
            attrs = {
                "dep": attr.label(cfg = "exec"),
            },
            toolchains = ["//rule:toolchain_type_1"],
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load("//test:defs.bzl", "custom_rule")

        custom_rule(name = "custom_rule_name")
        
        """.trimIndent()
        )
        useConfiguration("--incompatible_auto_exec_groups")

        getConfiguredTarget("//test:custom_rule_name")

        assertNoEvents()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun ctxToolchains_automaticExecGroupsEnabled_wrongToolchainError() {
        scratch.file(
            "test/defs.bzl",
            """
        def _impl(ctx):
            toolchain_info = ctx.toolchains["//rule:wrong_toolchain_type"]
            if toolchain_info == None:
                fail("Toolchain info is None.")
            return []

        custom_rule = rule(
            implementation = _impl,
            attrs = {
                "dep": attr.label(cfg = "exec"),
            },
            toolchains = ["//rule:toolchain_type_1"],
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load("//test:defs.bzl", "custom_rule")

        custom_rule(name = "custom_rule_name")
        
        """.trimIndent()
        )
        useConfiguration("--incompatible_auto_exec_groups")

        reporter.removeHandler(FoundationTestCase.failFastHandler)
        getConfiguredTarget("//test:custom_rule_name")

        assertContainsEvent(
            "In custom_rule rule //test:custom_rule_name, toolchain type //rule:wrong_toolchain_type"
                    + " was requested but only types [//rule:toolchain_type_1] are configured"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun ctxToolchainsPrint_automaticExecGroupsEnabled() {
        scratch.file(
            "test/defs.bzl",
            """
        def _impl(ctx):
            print(ctx.toolchains)
            return []

        custom_rule = rule(
            implementation = _impl,
            attrs = {
                "dep": attr.label(cfg = "exec"),
            },
            toolchains = ["//rule:toolchain_type_1"],
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load("//test:defs.bzl", "custom_rule")

        custom_rule(name = "custom_rule_name")
        
        """.trimIndent()
        )
        useConfiguration("--incompatible_auto_exec_groups")

        getConfiguredTarget("//test:custom_rule_name")

        assertContainsEvent("<toolchain_context.resolved_labels: //rule:toolchain_type_1>")
    }

    @org.junit.Test
    @TestParameters(
        "{action: ctx.actions.run}", "{action: ctx.actions.run_shell}"
    )
    @Throws(java.lang.Exception::class)
    fun toolchainNotDefinedButUsedInAction(action: String) {
        createCustomRule( /* action= */
            action,  /* actionParameters= */
            (if (action == "ctx.actions.run")
                "executable = ctx.executable._tool, "
            else
                "")
                    + "toolchain = '//rule:toolchain_type_1',",  /* extraAttributes= */
            "",  /* toolchains= */
            "[]",  /* execGroups= */
            "",  /* execCompatibleWith= */
            ""
        )
        useConfiguration("--incompatible_auto_exec_groups")

        reporter.removeHandler(FoundationTestCase.failFastHandler)
        getConfiguredTarget("//test:custom_rule_name")

        assertContainsEvent("Action declared for non-existent toolchain '//rule:toolchain_type_1'")
    }

    @org.junit.Test
    @TestParameters(
        "{action: ctx.actions.run}", "{action: ctx.actions.run_shell}"
    )
    @Throws(java.lang.Exception::class)
    fun customExecGroupsAndToolchain(action: String) {
        val customExecGroups =
            ("    'custom_exec_group': exec_group(\n"
                    + "      exec_compatible_with = ['//platforms:constraint_1'],\n"
                    + "      toolchains = ['//rule:toolchain_type_1'],\n"
                    + "    ),\n")
        createCustomRule( /* action= */
            action,  /* actionParameters= */
            "toolchain = '//rule:toolchain_type_1', "
                    + "exec_group = 'custom_exec_group',",  /* extraAttributes= */
            "",  /* toolchains= */
            "['//rule:toolchain_type_1']",  /* execGroups= */
            customExecGroups,  /* execCompatibleWith= */
            ""
        )
        useConfiguration("--incompatible_auto_exec_groups")

        val target: ConfiguredTarget? = getConfiguredTarget("//test:custom_rule_name")
        val execGroups: com.google.common.collect.ImmutableMap<String?, DeclaredExecGroup?> =
            getRuleContext(target).getExecGroups().execGroups()
        val generatedAction: Action = getGeneratingAction(target, "test/custom_rule_name_dummy_output.jar")

        Truth.assertThat(execGroups.keySet()).containsExactly("//rule:toolchain_type_1", "custom_exec_group")
        assertThat(generatedAction.getOwner().getExecutionPlatform().label())
            .isEqualTo(Label.parseCanonical("//platforms:platform_1"))
    }

    @org.junit.Test
    @TestParameters(
        "{action: ctx.actions.run}", "{action: ctx.actions.run_shell}"
    )
    @Throws(java.lang.Exception::class)
    fun customExecGroups_execCompatibleWith(action: String) {
        val customExecGroups =
            ("    'custom_exec_group': exec_group(\n"
                    + "      exec_compatible_with = ['//platforms:constraint_1'],\n"
                    + "      toolchains = ['//rule:toolchain_type_1'],\n"
                    + "    ),\n")
        val executable =
            if (action == "ctx.actions.run") "executable = ctx.executable._tool," else ""
        val execCompatibleWith = "  exec_compatible_with = ['//platforms:constraint_1'],"
        createCustomRule( /* action= */
            action,  /* actionParameters= */
            "exec_group = 'custom_exec_group'," + executable,  /* extraAttributes= */
            "",  /* toolchains= */
            "['//rule:toolchain_type_1']",  /* execGroups= */
            customExecGroups,  /* execCompatibleWith= */
            execCompatibleWith
        )
        scratch.overwriteFile(
            "test/BUILD",
            """
        load("//test:defs.bzl", "custom_rule")

        custom_rule(
            name = "custom_rule_name",
            exec_properties = {"custom_exec_group.mem": "64"},
        )
        
        """.trimIndent()
        )
        useConfiguration("--incompatible_auto_exec_groups")

        val target: ConfiguredTarget? = getConfiguredTarget("//test:custom_rule_name")
        val execGroups: com.google.common.collect.ImmutableMap<String?, DeclaredExecGroup?> =
            getRuleContext(target).getExecGroups().execGroups()
        val ruleAction: Action = (target as RuleConfiguredTarget).getActions().get(0) as Action

        Truth.assertThat(execGroups.keySet()).containsExactly("custom_exec_group", "//rule:toolchain_type_1")
        assertThat(execGroups.get("custom_exec_group").toolchainTypes())
            .containsExactly(
                ToolchainTypeRequirement.create(Label.parseCanonical("//rule:toolchain_type_1"))
            )
        assertThat(execGroups.get("custom_exec_group").execCompatibleWith())
            .isEqualTo(com.google.common.collect.ImmutableSet.of<E?>(Label.parseCanonical("//platforms:constraint_1")))
        assertThat(ruleAction.getOwner().getExecProperties()).containsExactly("mem", "64")
    }

    @org.junit.Test
    @TestParameters(
        "{action: ctx.actions.run}", "{action: ctx.actions.run_shell}"
    )
    @Throws(java.lang.Exception::class)
    fun customRule_execCompatibleWith(action: String) {
        createCustomRule( /* action= */
            action,  /* actionParameters= */
            "toolchain = '//rule:toolchain_type_1',",  /* extraAttributes= */
            "",  /* toolchains= */
            "['//rule:toolchain_type_1']",  /* execGroups= */
            "",  /* execCompatibleWith= */
            ""
        )
        scratch.overwriteFile(
            "test/BUILD",
            """
        load("//test:defs.bzl", "custom_rule")

        custom_rule(
            name = "custom_rule_name",
            exec_compatible_with = ["//platforms:constraint_2"],
        )
        
        """.trimIndent()
        )
        useConfiguration("--incompatible_auto_exec_groups")

        val target: ConfiguredTarget? = getConfiguredTarget("//test:custom_rule_name")
        val ruleAction: Action = (target as RuleConfiguredTarget).getActions().get(0) as Action

        assertThat(ruleAction.getOwner().getExecutionPlatform().label())
            .isEqualTo(Label.parseCanonical("//platforms:platform_2"))
    }

    @org.junit.Test
    @TestParameters(
        "{action: ctx.actions.run}", "{action: ctx.actions.run_shell}"
    )
    @Throws(java.lang.Exception::class)
    fun customRule_execGroupCompatibleWith(action: String) {
        createCustomRule( /* action= */
            action,  /* actionParameters= */
            "toolchain = '//rule:toolchain_type_1',",  /* extraAttributes= */
            "",  /* toolchains= */
            "['//rule:toolchain_type_1', '//rule:toolchain_type_2']",  /* execGroups= */
            "  'custom_exec_group': exec_group(),",  /* execCompatibleWith= */
            ""
        )
        scratch.overwriteFile(
            "test/BUILD",
            """
        load("//test:defs.bzl", "custom_rule")

        custom_rule(
            name = "custom_rule_name",
            exec_group_compatible_with = {
              "custom_exec_group": ["//platforms:constraint_2"],
              "//rule:toolchain_type_1": ["//platforms:constraint_3"],
              "@//rule:toolchain_type_2": ["//platforms:constraint_4"],
            },
        )
        
        """.trimIndent()
        )
        useConfiguration("--incompatible_auto_exec_groups")

        val target: ConfiguredTarget? = getConfiguredTarget("//test:custom_rule_name")

        assertThat(getRuleContext(target).getExecGroups().execGroups().keySet())
            .containsExactly("custom_exec_group", "//rule:toolchain_type_1", "//rule:toolchain_type_2")
        assertThat(getRuleContext(target).getExecutionPlatform().label())
            .isEqualTo(Label.parseCanonical("//platforms:platform_1"))
        assertThat(getRuleContext(target).getExecutionPlatform("custom_exec_group").label())
            .isEqualTo(Label.parseCanonical("//platforms:platform_2"))
        assertThat(getRuleContext(target).getExecutionPlatform("//rule:toolchain_type_1").label())
            .isEqualTo(Label.parseCanonical("//platforms:platform_3"))
        assertThat(getRuleContext(target).getExecutionPlatform("//rule:toolchain_type_2").label())
            .isEqualTo(Label.parseCanonical("//platforms:platform_4"))
    }

    @org.junit.Test
    @TestParameters(
        "{action: ctx.actions.run}", "{action: ctx.actions.run_shell}"
    )
    @Throws(java.lang.Exception::class)
    fun customRule_invalidExecGroupCompatibleWith(action: String) {
        createCustomRule( /* action= */
            action,  /* actionParameters= */
            "toolchain = '//rule:toolchain_type_1',",  /* extraAttributes= */
            "",  /* toolchains= */
            "['//rule:toolchain_type_1']",  /* execGroups= */
            "",  /* execCompatibleWith= */
            ""
        )
        scratch.overwriteFile(
            "test/BUILD",
            """
        load("//test:defs.bzl", "custom_rule")

        custom_rule(
            name = "custom_rule_name",
            exec_group_compatible_with = {
              "//rule:toolchain_type_2": ["//platforms:constraint_3"],
            },
        )
        
        """.trimIndent()
        )
        useConfiguration("--incompatible_auto_exec_groups")

        reporter.removeHandler(FoundationTestCase.failFastHandler)
        getConfiguredTarget("//test:custom_rule_name")

        assertContainsEvent(
            ("Tried to set execution constraints for non-existent exec groups on"
                    + " //test:custom_rule_name: //rule:toolchain_type_2 (did you mean"
                    + " '//rule:toolchain_type_1'?)")
        )
    }

    @org.junit.Test
    @TestParameters(
        "{action: ctx.actions.run}", "{action: ctx.actions.run_shell}"
    )
    @Throws(java.lang.Exception::class)
    fun customExecGroupsAndToolchain_notCompatibleError(action: String) {
        val customExecGroups =
            ("    'custom_exec_group': exec_group(\n"
                    + "      exec_compatible_with = ['//platforms:constraint_1'],\n"
                    + "      toolchains = ['//rule:toolchain_type_1'],\n"
                    + "    ),\n")
        createCustomRule( /* action= */
            action,  /* actionParameters= */
            ("toolchain = '//rule:toolchain_type_2', "
                    + "exec_group = 'custom_exec_group',"
                    + (if (action == "ctx.actions.run")
                "executable = ctx.toolchains['//rule:toolchain_type_2'].tool, "
            else
                "")),  /* extraAttributes= */
            "",  /* toolchains= */
            "['//rule:toolchain_type_2']",  /* execGroups= */
            customExecGroups,  /* execCompatibleWith= */
            ""
        )
        useConfiguration("--incompatible_auto_exec_groups")

        reporter.removeHandler(FoundationTestCase.failFastHandler)
        getConfiguredTarget("//test:custom_rule_name")

        assertContainsEvent(
            ("`toolchain` and `exec_group` parameters inside actions.{run, run_shell} are not"
                    + " compatible; use one of them or define `toolchain` which is compatible with the"
                    + " exec_group (already exists inside the `exec_group`)")
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun javaCommonCompile_automaticExecGroupsEnabled_optimizationJarActionExecutesOnFirstPlatform() {
        scratch.file("java/com/google/optimizationtest/config.txt")
        scratch.file(
            "java/com/google/optimizationtest/BUILD",
            """
        load("@rules_java//java:defs.bzl", "java_binary")
        java_binary(
            name = "optimizer",
            srcs = ["Foo.java"],
        )

        exports_files(["config.txt"])
        
        """.trimIndent()
        )
        scratch.file(
            "test/defs.bzl",
            "load('@rules_java//java:defs.bzl', 'java_common')",
            "def _impl(ctx):",
            "  output_jar = ctx.actions.declare_file('lib_' + ctx.label.name + '.jar')",
            "  java_info = java_common.compile(",
            "    ctx,",
            "    output = output_jar,",
            "    java_toolchain = ctx.toolchains['" + TestConstants.JAVA_TOOLCHAIN_TYPE + "'].java,",
            "  )",
            "  return [DefaultInfo(files = depset([output_jar]))]",
            "custom_rule = rule(",
            "  implementation = _impl,",
            "  attrs = {",
            "    '_use_auto_exec_groups': attr.bool(default = True),",
            "  },",
            "  toolchains = ['//rule:toolchain_type_2', '" + TestConstants.JAVA_TOOLCHAIN_TYPE + "'],",
            "  fragments = ['java']",
            ")"
        )
        scratch.file(
            "test/BUILD",
            """
        load("//test:defs.bzl", "custom_rule")

        custom_rule(name = "custom_rule_name")
        
        """.trimIndent()
        )
        useConfiguration(
            "--experimental_local_java_optimizations",
            "--experimental_bytecode_optimizers=Optimizer=//java/com/google/optimizationtest:optimizer",
            "--experimental_local_java_optimization_configuration=//java/com/google/optimizationtest:config.txt"
        )

        val target: ConfiguredTarget? = getConfiguredTarget("//test:custom_rule_name")
        val action: Action = getGeneratingAction(target, "test/lib_custom_rule_name.jar")

        assertThat(action.getMnemonic()).isEqualTo("Optimizer")
        assertThat(action.getOwner().getExecutionPlatform().label())
            .isEqualTo(Label.parseCanonical("//platforms:platform_1"))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun javaCommonCompile_automaticExecGroupsDisabled_optimizationJarActionExecutesOnSecondPlatform() {
        scratch.file("java/com/google/optimizationtest/config.txt")
        scratch.file(
            "java/com/google/optimizationtest/BUILD",
            """
        load("@rules_java//java:defs.bzl", "java_binary")
        java_binary(
            name = "optimizer",
            srcs = ["Foo.java"],
        )

        exports_files(["config.txt"])
        
        """.trimIndent()
        )
        scratch.file(
            "test/defs.bzl",
            "load('@rules_java//java:defs.bzl', 'java_common', 'JavaInfo')",
            "def _impl(ctx):",
            "  output_jar = ctx.actions.declare_file('lib_' + ctx.label.name + '.jar')",
            "  java_info = java_common.compile(",
            "    ctx,",
            "    output = output_jar,",
            "    java_toolchain = ctx.toolchains['" + TestConstants.JAVA_TOOLCHAIN_TYPE + "'].java,",
            "  )",
            "  return [java_info, DefaultInfo(files = depset([output_jar]))]",
            "custom_rule = rule(",
            "  implementation = _impl,",
            "  toolchains = ['//rule:toolchain_type_2', '" + TestConstants.JAVA_TOOLCHAIN_TYPE + "'],",
            "  provides = [JavaInfo],",
            "  fragments = ['java']",
            ")"
        )
        scratch.file(
            "test/BUILD",
            """
        load("//test:defs.bzl", "custom_rule")

        custom_rule(name = "custom_rule_name")
        
        """.trimIndent()
        )
        useConfiguration(
            "--experimental_local_java_optimizations",
            "--experimental_bytecode_optimizers=Optimizer=//java/com/google/optimizationtest:optimizer",
            "--experimental_local_java_optimization_configuration=//java/com/google/optimizationtest:config.txt",
            "--incompatible_auto_exec_groups=False"
        )

        val target: ConfiguredTarget? = getConfiguredTarget("//test:custom_rule_name")
        val action: Action = getGeneratingAction(target, "test/lib_custom_rule_name.jar")

        assertThat(action.getMnemonic()).isEqualTo("Optimizer")
        assertThat(action.getOwner().getExecutionPlatform().label())
            .isEqualTo(Label.parseCanonical("//platforms:platform_2"))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun javaCommonCompile_automaticExecGroupsEnabled_outputActionExecutesOnFirstPlatform() {
        scratch.file(
            "test/defs.bzl",
            "load('@rules_java//java:defs.bzl', 'java_common')",
            "def _impl(ctx):",
            "  output_jar = ctx.actions.declare_file('lib_' + ctx.label.name + '.jar')",
            "  java_info = java_common.compile(",
            "    ctx,",
            "    output = output_jar,",
            "    java_toolchain = ctx.toolchains['" + TestConstants.JAVA_TOOLCHAIN_TYPE + "'].java,",
            "  )",
            "  return [DefaultInfo(files = depset([output_jar]))]",
            "custom_rule = rule(",
            "  implementation = _impl,",
            "  toolchains = ['//rule:toolchain_type_2', '" + TestConstants.JAVA_TOOLCHAIN_TYPE + "'],",
            "  fragments = ['java']",
            ")"
        )
        scratch.file(
            "test/BUILD",
            """
        load("//test:defs.bzl", "custom_rule")

        custom_rule(name = "custom_rule_name")
        
        """.trimIndent()
        )
        useConfiguration("--incompatible_auto_exec_groups")

        val target: ConfiguredTarget? = getConfiguredTarget("//test:custom_rule_name")
        val action: Action = getGeneratingAction(target, "test/lib_custom_rule_name.jar")

        assertThat(action.getMnemonic()).isEqualTo("Javac")
        assertThat(action.getOwner().getExecutionPlatform().label())
            .isEqualTo(Label.parseCanonical("//platforms:platform_1"))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun javaCommonCompile_automaticExecGroupsDisabled_outputActionExecutesOnSecondPlatform() {
        scratch.file(
            "test/defs.bzl",
            "load('@rules_java//java:defs.bzl', 'java_common')",
            "def _impl(ctx):",
            "  output_jar = ctx.actions.declare_file('lib_' + ctx.label.name + '.jar')",
            "  java_info = java_common.compile(",
            "    ctx,",
            "    output = output_jar,",
            "    java_toolchain = ctx.toolchains['" + TestConstants.JAVA_TOOLCHAIN_TYPE + "'].java,",
            "  )",
            "  return [DefaultInfo(files = depset([output_jar]))]",
            "custom_rule = rule(",
            "  implementation = _impl,",
            "  toolchains = ['//rule:toolchain_type_2', '" + TestConstants.JAVA_TOOLCHAIN_TYPE + "'],",
            "  fragments = ['java']",
            ")"
        )
        scratch.file(
            "test/BUILD",
            """
        load("//test:defs.bzl", "custom_rule")

        custom_rule(name = "custom_rule_name")
        
        """.trimIndent()
        )
        useConfiguration("--incompatible_auto_exec_groups=False")

        val target: ConfiguredTarget? = getConfiguredTarget("//test:custom_rule_name")
        val action: Action = getGeneratingAction(target, "test/lib_custom_rule_name.jar")

        assertThat(action.getMnemonic()).isEqualTo("Javac")
        assertThat(action.getOwner().getExecutionPlatform().label())
            .isEqualTo(Label.parseCanonical("//platforms:platform_2"))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun javaCommonCompile_automaticExecGroupsEnabled_javaInfoActionsExecuteOnFirstPlatform() {
        scratch.file(
            "test/defs.bzl",
            "load('@rules_java//java:defs.bzl',"
                    + " 'java_common', 'JavaInfo', 'JavaPluginInfo')",
            "def _impl(ctx):",
            "  output_jar = ctx.actions.declare_file('lib_' + ctx.label.name + '.jar')",
            "  java_info = java_common.compile(",
            "    ctx,",
            "    output = output_jar,",
            "    java_toolchain = ctx.toolchains['" + TestConstants.JAVA_TOOLCHAIN_TYPE + "'].java,",
            "    plugins = [ctx.attr._plugins[JavaPluginInfo]],",
            "  )",
            "  return [java_info]",
            "custom_rule = rule(",
            "  implementation = _impl,",
            "  attrs = {",
            "    '_plugins': attr.label(",
            "      default = Label('//test:test_plugin'),",
            "    ),",
            "  },",
            "  toolchains = ['//rule:toolchain_type_2', '" + TestConstants.JAVA_TOOLCHAIN_TYPE + "'],",
            "  provides = [JavaInfo],",
            "  fragments = ['java']",
            ")"
        )
        scratch.file(
            "test/BUILD",
            """
        load("@rules_java//java:defs.bzl", "java_plugin")
        load("//test:defs.bzl", "custom_rule")

        java_plugin(
            name = "test_plugin",
            processor_class = "GeneratedProcessor",
        )

        custom_rule(name = "custom_rule_name")
        
        """.trimIndent()
        )
        useConfiguration("--incompatible_auto_exec_groups")

        val target: ConfiguredTarget? = getConfiguredTarget("//test:custom_rule_name")
        val javaInfo: JavaInfo = JavaInfo.getJavaInfo(target)
        val genSrcOutputAction: Action? =
            getGeneratingAction(javaInfo.outputJars.getAllSrcOutputJars().get(0))
        val javaGenJarsProvider: JavaGenJarsProvider? = javaInfo.genJarsProvider
        val genClassAction: Action? = getGeneratingAction(javaGenJarsProvider.genClassJar)
        val genSourceAction: Action? = getGeneratingAction(javaGenJarsProvider.genSourceJar)

        assertThat(genSrcOutputAction.getOwner().getExecutionPlatform().label())
            .isEqualTo(Label.parseCanonical("//platforms:platform_1"))
        assertThat(genClassAction.getOwner().getExecutionPlatform().label())
            .isEqualTo(Label.parseCanonical("//platforms:platform_1"))
        assertThat(genSourceAction.getOwner().getExecutionPlatform().label())
            .isEqualTo(Label.parseCanonical("//platforms:platform_1"))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun javaCommonCompile_automaticExecGroupsDisabled_javaInfoActionsExecuteOnSecondPlatform() {
        scratch.file(
            "test/defs.bzl",
            "load('@rules_java//java:defs.bzl',"
                    + " 'java_common', 'JavaInfo', 'JavaPluginInfo')",
            "def _impl(ctx):",
            "  output_jar = ctx.actions.declare_file('lib_' + ctx.label.name + '.jar')",
            "  java_info = java_common.compile(",
            "    ctx,",
            "    output = output_jar,",
            "    java_toolchain = ctx.toolchains['" + TestConstants.JAVA_TOOLCHAIN_TYPE + "'].java,",
            "    plugins = [ctx.attr._plugins[JavaPluginInfo]],",
            "  )",
            "  return [java_info]",
            "custom_rule = rule(",
            "  implementation = _impl,",
            "  attrs = {",
            "    '_plugins': attr.label(",
            "      default = Label('//test:test_plugin'),",
            "    ),",
            "  },",
            "  toolchains = ['//rule:toolchain_type_2', '" + TestConstants.JAVA_TOOLCHAIN_TYPE + "'],",
            "  provides = [JavaInfo],",
            "  fragments = ['java']",
            ")"
        )
        scratch.file(
            "test/BUILD",
            """
        load("@rules_java//java:defs.bzl", "java_plugin")
        load("//test:defs.bzl", "custom_rule")

        java_plugin(
            name = "test_plugin",
            processor_class = "GeneratedProcessor",
        )

        custom_rule(name = "custom_rule_name")
        
        """.trimIndent()
        )
        useConfiguration("--incompatible_auto_exec_groups=False")

        val target: ConfiguredTarget? = getConfiguredTarget("//test:custom_rule_name")
        val javaInfo: JavaInfo = JavaInfo.getJavaInfo(target)
        val genSrcOutputAction: Action? =
            getGeneratingAction(javaInfo.outputJars.getAllSrcOutputJars().get(0))
        val javaGenJarsProvider: JavaGenJarsProvider? = javaInfo.genJarsProvider
        val genClassAction: Action? = getGeneratingAction(javaGenJarsProvider.genClassJar)
        val genSourceAction: Action? = getGeneratingAction(javaGenJarsProvider.genSourceJar)

        assertThat(genSrcOutputAction.getOwner().getExecutionPlatform().label())
            .isEqualTo(Label.parseCanonical("//platforms:platform_2"))
        assertThat(genClassAction.getOwner().getExecutionPlatform().label())
            .isEqualTo(Label.parseCanonical("//platforms:platform_2"))
        assertThat(genSourceAction.getOwner().getExecutionPlatform().label())
            .isEqualTo(Label.parseCanonical("//platforms:platform_2"))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun javaCommonCompile_automaticExecGroupsEnabled_lazyActionExecutesOnFirstPlatform() {
        scratch.file(
            "test/defs.bzl",
            "load('@rules_java//java:defs.bzl', 'java_common', 'JavaInfo')",
            "def _impl(ctx):",
            "  output_jar = ctx.actions.declare_file('lib_' + ctx.label.name + '.jar')",
            "  java_info = java_common.compile(",
            "    ctx,",
            "    output = output_jar,",
            "    java_toolchain = ctx.toolchains['" + TestConstants.JAVA_TOOLCHAIN_TYPE + "'].java,",
            "    source_files = ctx.files.srcs,",
            "  )",
            "  return [java_info, DefaultInfo(files = depset([output_jar]))]",
            "custom_rule = rule(",
            "  implementation = _impl,",
            "  toolchains = ['//rule:toolchain_type_2', '" + TestConstants.JAVA_TOOLCHAIN_TYPE + "'],",
            "  attrs = {",
            "    'srcs': attr.label_list(allow_files=['.java']),",
            "  },",
            "  provides = [JavaInfo],",
            "  fragments = ['java']",
            ")"
        )
        scratch.file(
            "test/BUILD",
            """
        load("//test:defs.bzl", "custom_rule")

        custom_rule(
            name = "custom_rule_name",
            srcs = ["Main.java"],
        )
        
        """.trimIndent()
        )
        useConfiguration("--incompatible_auto_exec_groups", "--collect_code_coverage")

        val actions: com.google.common.collect.ImmutableList<Action?> =
            getActions("//test:custom_rule_name", LazyWritePathsFileAction::class.java)

        Truth.assertThat(actions).hasSize(1)
        assertThat(actions.get(0).getOwner().getExecutionPlatform().label())
            .isEqualTo(Label.parseCanonical("//platforms:platform_1"))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun javaCommonCompile_automaticExecGroupsDisabled_lazyActionExecutesOnSecondPlatform() {
        scratch.file(
            "test/defs.bzl",
            "load('@rules_java//java:defs.bzl', 'java_common', 'JavaInfo')",
            "def _impl(ctx):",
            "  output_jar = ctx.actions.declare_file('lib_' + ctx.label.name + '.jar')",
            "  java_info = java_common.compile(",
            "    ctx,",
            "    output = output_jar,",
            "    java_toolchain = ctx.toolchains['" + TestConstants.JAVA_TOOLCHAIN_TYPE + "'].java,",
            "    source_files = ctx.files.srcs,",
            "  )",
            "  return [java_info, DefaultInfo(files = depset([output_jar]))]",
            "custom_rule = rule(",
            "  implementation = _impl,",
            "  toolchains = ['//rule:toolchain_type_2', '" + TestConstants.JAVA_TOOLCHAIN_TYPE + "'],",
            "  attrs = {",
            "    'srcs': attr.label_list(allow_files=['.java']),",
            "  },",
            "  provides = [JavaInfo],",
            "  fragments = ['java']",
            ")"
        )
        scratch.file(
            "test/BUILD",
            """
        load("//test:defs.bzl", "custom_rule")

        custom_rule(
            name = "custom_rule_name",
            srcs = ["Main.java"],
        )
        
        """.trimIndent()
        )
        useConfiguration("--collect_code_coverage", "--incompatible_auto_exec_groups=False")

        val actions: com.google.common.collect.ImmutableList<Action?> =
            getActions("//test:custom_rule_name", LazyWritePathsFileAction::class.java)

        Truth.assertThat(actions).hasSize(1)
        assertThat(actions.get(0).getOwner().getExecutionPlatform().label())
            .isEqualTo(Label.parseCanonical("//platforms:platform_2"))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun javaCommonCompile_automaticExecGroupsEnabled_javaResourceActionsExecuteOnFirstPlatform() {
        scratch.file(
            "bazel_internal/test_rules/defs.bzl",
            "load('@rules_java//java:defs.bzl', 'java_common', 'JavaInfo')",
            "def _impl(ctx):",
            "  output_jar = ctx.actions.declare_file('lib_' + ctx.label.name + '.jar')",
            "  java_info = java_common.compile(",
            "    ctx,",
            "    output = output_jar,",
            "    java_toolchain = ctx.toolchains['" + TestConstants.JAVA_TOOLCHAIN_TYPE + "'].java,",
            "    resources = ctx.files.resources,",
            "  )",
            "  return [java_info, DefaultInfo(files = depset([output_jar]))]",
            "custom_rule = rule(",
            "  implementation = _impl,",
            "  toolchains = ['//rule:toolchain_type_2', '" + TestConstants.JAVA_TOOLCHAIN_TYPE + "'],",
            "  attrs = {",
            "    'resources': attr.label_list(allow_files = True),",
            "  },",
            "  provides = [JavaInfo],",
            "  fragments = ['java']",
            ")"
        )
        scratch.file(
            "bazel_internal/test_rules/BUILD",
            """
        load("//bazel_internal/test_rules:defs.bzl", "custom_rule")

        custom_rule(
            name = "custom_rule_name",
            resources = ["Resources.java"],
        )
        
        """.trimIndent()
        )
        useConfiguration(
            "--incompatible_auto_exec_groups", "--experimental_turbine_annotation_processing"
        )

        val actions: com.google.common.collect.ImmutableList<Action?> =
            getActions("//bazel_internal/test_rules:custom_rule_name")
        val javaResourceActions: com.google.common.collect.ImmutableList<Action?> =
            actions.stream()
                .filter(java.util.function.Predicate { action: Action? ->
                    action.getMnemonic().equals("JavaResourceJar")
                })
                .collect(com.google.common.collect.ImmutableList.toImmutableList<Action?>())

        Truth.assertThat(javaResourceActions).hasSize(1)
        assertThat(javaResourceActions.get(0).getOwner().getExecutionPlatform().label())
            .isEqualTo(Label.parseCanonical("//platforms:platform_1"))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun javaCommonCompile_automaticExecGroupsDisabled_javaResourceActionsExecuteOnSecondPlatform() {
        scratch.file(
            "bazel_internal/test_rules/defs.bzl",
            "load('@rules_java//java:defs.bzl', 'java_common', 'JavaInfo')",
            "def _impl(ctx):",
            "  output_jar = ctx.actions.declare_file('lib_' + ctx.label.name + '.jar')",
            "  java_info = java_common.compile(",
            "    ctx,",
            "    output = output_jar,",
            "    java_toolchain = ctx.toolchains['" + TestConstants.JAVA_TOOLCHAIN_TYPE + "'].java,",
            "    resources = ctx.files.resources,",
            "  )",
            "  return [java_info, DefaultInfo(files = depset([output_jar]))]",
            "custom_rule = rule(",
            "  implementation = _impl,",
            "  toolchains = ['//rule:toolchain_type_2', '" + TestConstants.JAVA_TOOLCHAIN_TYPE + "'],",
            "  attrs = {",
            "    'resources': attr.label_list(allow_files = True),",
            "  },",
            "  provides = [JavaInfo],",
            "  fragments = ['java']",
            ")"
        )
        scratch.file(
            "bazel_internal/test_rules/BUILD",
            """
        load("//bazel_internal/test_rules:defs.bzl", "custom_rule")

        custom_rule(
            name = "custom_rule_name",
            resources = ["Resources.java"],
        )
        
        """.trimIndent()
        )
        useConfiguration(
            "--experimental_turbine_annotation_processing", "--incompatible_auto_exec_groups=False"
        )

        val actions: com.google.common.collect.ImmutableList<Action?> =
            getActions("//bazel_internal/test_rules:custom_rule_name")
        val javaResourceActions: com.google.common.collect.ImmutableList<Action?> =
            actions.stream()
                .filter(java.util.function.Predicate { action: Action? ->
                    action.getMnemonic().equals("JavaResourceJar")
                })
                .collect(com.google.common.collect.ImmutableList.toImmutableList<Action?>())

        Truth.assertThat(javaResourceActions).hasSize(1)
        assertThat(javaResourceActions.get(0).getOwner().getExecutionPlatform().label())
            .isEqualTo(Label.parseCanonical("//platforms:platform_2"))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun javaCommonBuildIjar_automaticExecGroupsEnabled_ijarActionsExecuteOnFirstPlatform() {
        scratch.file(
            "test/defs.bzl",
            "load('@rules_java//java:defs.bzl', 'java_common')",
            "def _impl(ctx):",
            "  output_jar = ctx.actions.declare_file('lib_' + ctx.label.name + '.jar')",
            "  ctx.actions.run(",
            "    outputs = [output_jar],",
            "    executable = ctx.toolchains['//rule:toolchain_type_2'].tool,",
            "    toolchain = '//rule:toolchain_type_2',",
            "  )",
            "  compile_jar = java_common.run_ijar(",
            "    actions = ctx.actions,",
            "    jar = output_jar,",
            "    java_toolchain = ctx.toolchains['" + TestConstants.JAVA_TOOLCHAIN_TYPE + "'].java,",
            "  )",
            "  return []",
            "custom_rule = rule(",
            "  implementation = _impl,",
            "  toolchains = ['//rule:toolchain_type_2', '" + TestConstants.JAVA_TOOLCHAIN_TYPE + "'],",
            "  fragments = ['java']",
            ")"
        )
        scratch.file(
            "test/BUILD",
            """
        load("//test:defs.bzl", "custom_rule")

        custom_rule(name = "custom_rule_name")
        
        """.trimIndent()
        )
        useConfiguration("--incompatible_auto_exec_groups")

        val actions: com.google.common.collect.ImmutableList<Action?> =
            getActions("//test:custom_rule_name").stream()
                .filter(java.util.function.Predicate { action: Action? -> action.getMnemonic().equals("JavaIjar") })
                .collect(com.google.common.collect.ImmutableList.toImmutableList<Action?>())

        Truth.assertThat(actions).hasSize(1)
        assertThat(actions.get(0).getOwner().getExecutionPlatform().label())
            .isEqualTo(Label.parseCanonical("//platforms:platform_1"))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun javaCommonPackSources_automaticExecGroupsEnabled_sourceActionExecutesOnFirstPlatform() {
        scratch.file(
            "test/defs.bzl",
            "load('@rules_java//java:defs.bzl', 'java_common')",
            "def _impl(ctx):",
            "  output_jar = ctx.actions.declare_file('lib_' + ctx.label.name + '.jar')",
            "  source_jar = java_common.pack_sources(",
            "    ctx.actions,",
            "    output_source_jar = output_jar,",
            "    java_toolchain = ctx.toolchains['" + TestConstants.JAVA_TOOLCHAIN_TYPE + "'].java,",
            "  )",
            "  return []",
            "custom_rule = rule(",
            "  implementation = _impl,",
            "  toolchains = ['//rule:toolchain_type_2', '" + TestConstants.JAVA_TOOLCHAIN_TYPE + "'],",
            "  fragments = ['java']",
            ")"
        )
        scratch.file(
            "test/BUILD",
            """
        load("//test:defs.bzl", "custom_rule")

        custom_rule(name = "custom_rule_name")
        
        """.trimIndent()
        )
        useConfiguration("--incompatible_auto_exec_groups")

        val actions: com.google.common.collect.ImmutableList<Action?> =
            getActions("//test:custom_rule_name").stream()
                .filter(java.util.function.Predicate { action: Action? ->
                    action.getMnemonic().equals("JavaSourceJar")
                })
                .collect(com.google.common.collect.ImmutableList.toImmutableList<Action?>())

        Truth.assertThat(actions).hasSize(1)
        assertThat(actions.get(0).getOwner().getExecutionPlatform().label())
            .isEqualTo(Label.parseCanonical("//platforms:platform_1"))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun javaCommonStampJar_automaticExecGroupsEnabled_actionExecutesOnFirstPlatform() {
        scratch.file(
            "test/defs.bzl",
            "load('@rules_java//java:defs.bzl', 'java_common')",
            "def _impl(ctx):",
            "  output_jar = ctx.actions.declare_file('lib_' + ctx.label.name + '.jar')",
            "  ctx.actions.run(",
            "    outputs = [output_jar],",
            "    executable = ctx.toolchains['//rule:toolchain_type_2'].tool,",
            "    toolchain = '//rule:toolchain_type_2',",
            "  )",
            "  source_jar = java_common.stamp_jar(",
            "    ctx.actions,",
            "    jar = output_jar,",
            "    target_label = ctx.label,",
            "    java_toolchain = ctx.toolchains['" + TestConstants.JAVA_TOOLCHAIN_TYPE + "'].java,",
            "  )",
            "  return []",
            "custom_rule = rule(",
            "  implementation = _impl,",
            "  toolchains = ['//rule:toolchain_type_2', '" + TestConstants.JAVA_TOOLCHAIN_TYPE + "'],",
            "  fragments = ['java']",
            ")"
        )
        scratch.file(
            "test/BUILD",
            """
        load("//test:defs.bzl", "custom_rule")

        custom_rule(name = "custom_rule_name")
        
        """.trimIndent()
        )
        useConfiguration("--incompatible_auto_exec_groups")

        val actions: com.google.common.collect.ImmutableList<Action?> =
            getActions("//test:custom_rule_name").stream()
                .filter(java.util.function.Predicate { action: Action? -> action.getMnemonic().equals("JavaIjar") })
                .collect(com.google.common.collect.ImmutableList.toImmutableList<Action?>())

        Truth.assertThat(actions).hasSize(1)
        assertThat(actions.get(0).getProgressMessage())
            .matches("Stamping target label into jar .*/lib_custom_rule_name.jar")
        assertThat(actions.get(0).getOwner().getExecutionPlatform().label())
            .isEqualTo(Label.parseCanonical("//platforms:platform_1"))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun ccCommonLink_cppLinkExecGroupNotDefined_cppLinkActionExecutesOnFirstPlatform() {
        scratch.file(
            "test/defs.bzl",
            "load('@rules_cc//cc/common:cc_common.bzl', 'cc_common')",
            "def _use_cpp_toolchain():",
            "   return [",
            ("      config_common.toolchain_type('"
                    + TestConstants.CPP_TOOLCHAIN_TYPE
                    + "', mandatory = False),"),
            "   ]",
            "def _impl(ctx):",
            "  cc_toolchain = ctx.toolchains['" + TestConstants.CPP_TOOLCHAIN_TYPE + "'].cc",
            "  feature_configuration = cc_common.configure_features(",
            "      ctx = ctx,",
            "      cc_toolchain = cc_toolchain,",
            "      requested_features = ctx.features,",
            "     unsupported_features = ctx.disabled_features,",
            "  )",
            "  linking_outputs = cc_common.link(",
            "    name = ctx.label.name,",
            "    actions = ctx.actions,",
            "    feature_configuration = feature_configuration,",
            "    cc_toolchain = cc_toolchain,",
            "  )",
            "  return []",
            "custom_rule = rule(",
            "  implementation = _impl,",
            "  exec_groups = { ",
            "    '" + CPP_LINK_EXEC_GROUP + "': exec_group(toolchains = _use_cpp_toolchain()),",
            "  },",
            "  toolchains = ['//rule:toolchain_type_2'] + _use_cpp_toolchain(),",
            "  fragments = ['cpp']",
            ")"
        )
        scratch.file(
            "test/BUILD",
            """
        load("//test:defs.bzl", "custom_rule")

        custom_rule(name = "custom_rule_name")
        
        """.trimIndent()
        )
        useConfiguration("--incompatible_auto_exec_groups")

        val actions: com.google.common.collect.ImmutableList<Action?>? =
            getActions("//test:custom_rule_name", "CppLink")

        Truth.assertThat(actions).hasSize(1)
        assertThat(actions.get(0).getOwner().getExecutionPlatform().label())
            .isEqualTo(Label.parseCanonical("//platforms:platform_1"))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun ccCommonLink_cppLinkExecGroupDefined_cppLinkActionExecutesOnFirstPlatform() {
        scratch.file(
            "test/defs.bzl",
            "load('@rules_cc//cc/common:cc_common.bzl', 'cc_common')",
            "def _use_cpp_toolchain():",
            "   return [",
            ("      config_common.toolchain_type('"
                    + TestConstants.CPP_TOOLCHAIN_TYPE
                    + "', mandatory = False),"),
            "   ]",
            "def _impl(ctx):",
            "  cc_toolchain = ctx.toolchains['" + TestConstants.CPP_TOOLCHAIN_TYPE + "'].cc",
            "  feature_configuration = cc_common.configure_features(",
            "      ctx = ctx,",
            "      cc_toolchain = cc_toolchain,",
            "      requested_features = ctx.features,",
            "     unsupported_features = ctx.disabled_features,",
            "  )",
            "  linking_outputs = cc_common.link(",
            "    name = ctx.label.name,",
            "    actions = ctx.actions,",
            "    feature_configuration = feature_configuration,",
            "    cc_toolchain = cc_toolchain,",
            "  )",
            "  return []",
            "custom_rule = rule(",
            "  implementation = _impl,",
            "  exec_groups = { ",
            "    '" + CPP_LINK_EXEC_GROUP + "': exec_group(toolchains = _use_cpp_toolchain()),",
            "  },",
            "  toolchains = ['//rule:toolchain_type_2'] + _use_cpp_toolchain(),",
            "  fragments = ['cpp']",
            ")"
        )
        scratch.file(
            "test/BUILD",
            """
        load("//test:defs.bzl", "custom_rule")

        custom_rule(name = "custom_rule_name")
        
        """.trimIndent()
        )
        useConfiguration("--incompatible_auto_exec_groups")

        val actions: com.google.common.collect.ImmutableList<Action?>? =
            getActions("//test:custom_rule_name", "CppLink")

        Truth.assertThat(actions).hasSize(1)
        assertThat(actions.get(0).getOwner().getExecutionPlatform().label())
            .isEqualTo(Label.parseCanonical("//platforms:platform_1"))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun ccCommonLink_cppLTOActionExecutesOnFirstPlatform() {
        scratch.file(
            "test/defs.bzl",
            "load('@rules_cc//cc/common:cc_info.bzl', 'CcInfo')",
            "load('@rules_cc//cc/common:cc_common.bzl', 'cc_common')",
            "def _use_cpp_toolchain():",
            "   return [",
            ("      config_common.toolchain_type('"
                    + TestConstants.CPP_TOOLCHAIN_TYPE
                    + "', mandatory = False),"),
            "   ]",
            "def _impl(ctx):",
            "  cc_toolchain = ctx.toolchains['" + TestConstants.CPP_TOOLCHAIN_TYPE + "'].cc",
            "  feature_configuration = cc_common.configure_features(",
            "      ctx = ctx,",
            "      cc_toolchain = cc_toolchain,",
            "      requested_features = ctx.features,",
            "      unsupported_features = ctx.disabled_features,",
            "  )",
            "  linking_outputs = cc_common.link(",
            "    name = ctx.label.name,",
            "    actions = ctx.actions,",
            "    feature_configuration = feature_configuration,",
            "    cc_toolchain = cc_toolchain,",
            "    linking_contexts = [dep[CcInfo].linking_context for dep in ctx.attr.deps if"
                    + " CcInfo in dep]",
            "  )",
            "  return []",
            "custom_rule = rule(",
            "  implementation = _impl,",
            "  attrs = {",
            "    'deps': attr.label_list(),",
            "    'srcs': attr.label_list(allow_files = ['.cc']),",
            "  },",
            "  toolchains = ['//rule:toolchain_type_2'] + _use_cpp_toolchain(),",
            "  fragments = ['cpp']",
            ")"
        )
        scratch.file(
            "test/BUILD",
            """
        load("@rules_cc//cc:cc_library.bzl", "cc_library")
        load("//test:defs.bzl", "custom_rule")

        cc_library(
            name = "dep",
            srcs = ["dep.cc"],
        )

        custom_rule(
            name = "custom_rule_name",
            srcs = ["custom.cc"],
            deps = ["dep"],
        )
        
        """.trimIndent()
        )
        useConfiguration("--incompatible_auto_exec_groups", "--features=thin_lto")
        AnalysisMock.get()
            .ccSupport()
            .setupCcToolchainConfig(
                mockToolsConfig,
                CcToolchainConfig.builder()
                    .withFeatures(CppRuleClasses.THIN_LTO, CppRuleClasses.SUPPORTS_START_END_LIB)
                    .withToolchainTargetConstraints("@@//platforms:constraint_1")
                    .withToolchainExecConstraints("@@//platforms:constraint_1")
            )

        val cppLtoActions: com.google.common.collect.ImmutableList<Action?>? =
            getActions("//test:custom_rule_name", "CppLTOIndexing")

        Truth.assertThat(cppLtoActions).hasSize(1)
        assertThat(cppLtoActions.get(0).getOwner().getExecutionPlatform().label())
            .isEqualTo(Label.parseCanonical("//platforms:platform_1"))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun ccCommonLink_linkstampCompileActionExecutesOnFirstPlatform() {
        scratch.file(
            "bazel_internal/test_rules/cc/defs.bzl",
            "load('@rules_cc//cc/common:cc_common.bzl', 'cc_common')",
            "load('@rules_cc//cc/common:cc_info.bzl', 'CcInfo')",
            "def _use_cpp_toolchain():",
            "   return [",
            ("      config_common.toolchain_type('"
                    + TestConstants.CPP_TOOLCHAIN_TYPE
                    + "', mandatory = False),"),
            "   ]",
            "def _impl(ctx):",
            "  cc_toolchain = ctx.toolchains['" + TestConstants.CPP_TOOLCHAIN_TYPE + "'].cc",
            "  feature_configuration = cc_common.configure_features(",
            "      ctx = ctx,",
            "      cc_toolchain = cc_toolchain,",
            "      requested_features = ctx.features,",
            "      unsupported_features = ctx.disabled_features,",
            "  )",
            "  linking_outputs = cc_common.link(",
            "    name = ctx.label.name,",
            "    actions = ctx.actions,",
            "    feature_configuration = feature_configuration,",
            "    cc_toolchain = cc_toolchain,",
            "    linking_contexts = [dep[CcInfo].linking_context for dep in ctx.attr.deps if"
                    + " CcInfo in dep]",
            "  )",
            "  return []",
            "custom_rule = rule(",
            "  implementation = _impl,",
            "  attrs = {",
            "    'deps': attr.label_list(),",
            "  },",
            "  toolchains = ['//rule:toolchain_type_2'] + _use_cpp_toolchain(),",
            "  fragments = ['cpp']",
            ")"
        )
        scratch.file(
            "bazel_internal/test_rules/cc/BUILD",
            """
        load("@rules_cc//cc:cc_library.bzl", "cc_library")
        load("//bazel_internal/test_rules/cc:defs.bzl", "custom_rule")

        cc_library(
            name = "dep",
            linkstamp = "stamp.cc",
        )

        custom_rule(
            name = "custom_rule_name",
            deps = ["dep"],
        )
        
        """.trimIndent()
        )
        useConfiguration("--incompatible_auto_exec_groups")

        val cppCompileActions: com.google.common.collect.ImmutableList<Action?>? =
            getActions("//bazel_internal/test_rules/cc:custom_rule_name", "CppLinkstampCompile")

        Truth.assertThat(cppCompileActions).hasSize(1)
        assertThat(cppCompileActions.get(0).getOwner().getExecutionPlatform().label())
            .isEqualTo(Label.parseCanonical("//platforms:platform_1"))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun ccCommonCompile_cppCompileActionExecutesOnFirstPlatform() {
        scratch.file(
            "bazel_internal/test_rules/cc/defs.bzl",
            "load('@rules_cc//cc/common:cc_common.bzl', 'cc_common')",
            "def _use_cpp_toolchain():",
            "   return [",
            ("      config_common.toolchain_type('"
                    + TestConstants.CPP_TOOLCHAIN_TYPE
                    + "', mandatory = False),"),
            "   ]",
            "def _impl(ctx):",
            "  cc_toolchain = ctx.toolchains['" + TestConstants.CPP_TOOLCHAIN_TYPE + "'].cc",
            "  feature_configuration = cc_common.configure_features(",
            "      ctx = ctx,",
            "      cc_toolchain = cc_toolchain,",
            "      requested_features = ctx.features,",
            "      unsupported_features = ctx.disabled_features,",
            "  )",
            "  (compilation_context, compilation_outputs) = cc_common.compile(",
            "    name = ctx.label.name,",
            "    actions = ctx.actions,",
            "    feature_configuration = feature_configuration,",
            "    cc_toolchain = cc_toolchain,",
            "    srcs = ctx.files.srcs,",
            "  )",
            "  return []",
            "custom_rule = rule(",
            "  implementation = _impl,",
            "  attrs = {",
            "    'srcs': attr.label_list(allow_files = ['.cc']),",
            "  },",
            "  toolchains = ['//rule:toolchain_type_2'] + _use_cpp_toolchain(),",
            "  fragments = ['cpp']",
            ")"
        )
        scratch.file(
            "bazel_internal/test_rules/cc/BUILD",
            """
        load("//bazel_internal/test_rules/cc:defs.bzl", "custom_rule")

        custom_rule(
            name = "custom_rule_name",
            srcs = ["custom.cc"],
        )
        
        """.trimIndent()
        )
        useConfiguration("--incompatible_auto_exec_groups")

        val cppCompileActions: com.google.common.collect.ImmutableList<Action?> =
            getActions("//bazel_internal/test_rules/cc:custom_rule_name", CppCompileAction::class.java)

        Truth.assertThat(cppCompileActions).hasSize(1)
        assertThat(cppCompileActions.get(0).getProgressMessage())
            .isEqualTo("Compiling bazel_internal/test_rules/cc/custom.cc")
        assertThat(cppCompileActions.get(0).getOwner().getExecutionPlatform().label())
            .isEqualTo(Label.parseCanonical("//platforms:platform_1"))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun ccCommonCompile_moduleActionsExecuteOnFirstPlatform() {
        scratch.file(
            "bazel_internal/test_rules/cc/defs.bzl",
            "load('@rules_cc//cc/common:cc_common.bzl', 'cc_common')",
            "def _use_cpp_toolchain():",
            "   return [",
            ("      config_common.toolchain_type('"
                    + TestConstants.CPP_TOOLCHAIN_TYPE
                    + "', mandatory = False),"),
            "   ]",
            "def _impl(ctx):",
            "  cc_toolchain = ctx.toolchains['" + TestConstants.CPP_TOOLCHAIN_TYPE + "'].cc",
            "  feature_configuration = cc_common.configure_features(",
            "      ctx = ctx,",
            "      cc_toolchain = cc_toolchain,",
            "      requested_features = ctx.features,",
            "      unsupported_features = ctx.disabled_features,",
            "  )",
            "  (compilation_context, compilation_outputs) = cc_common.compile(",
            "    name = ctx.label.name,",
            "    actions = ctx.actions,",
            "    feature_configuration = feature_configuration,",
            "    cc_toolchain = cc_toolchain,",
            "    srcs = ctx.files.srcs,",
            "    public_hdrs = ctx.files.hdrs,",
            "    separate_module_headers = ctx.files.hdrs,",
            "  )",
            "  return []",
            "custom_rule = rule(",
            "  implementation = _impl,",
            "  attrs = {",
            "    'srcs': attr.label_list(allow_files = ['.cc']),",
            "    'hdrs': attr.label_list(allow_files = ['.h']),",
            "  },",
            "  toolchains = ['//rule:toolchain_type_2'] + _use_cpp_toolchain(),",
            "  fragments = ['cpp']",
            ")"
        )
        scratch.file(
            "bazel_internal/test_rules/cc/BUILD",
            """
        load("//bazel_internal/test_rules/cc:defs.bzl", "custom_rule")

        custom_rule(
            name = "custom_rule_name",
            srcs = ["custom.cc"],
            hdrs = ["custom.h"],
        )
        
        """.trimIndent()
        )
        useConfiguration("--incompatible_auto_exec_groups", "--features=header_modules")
        AnalysisMock.get()
            .ccSupport()
            .setupCcToolchainConfig(
                mockToolsConfig,
                CcToolchainConfig.builder()
                    .withFeatures(MockCcSupport.HEADER_MODULES_FEATURES)
                    .withToolchainTargetConstraints("@@//platforms:constraint_1")
                    .withToolchainExecConstraints("@@//platforms:constraint_1")
            )

        val cppCompileActions: com.google.common.collect.ImmutableList<Action?> =
            getActions("//bazel_internal/test_rules/cc:custom_rule_name", CppCompileAction::class.java)
        val moduleActions: com.google.common.collect.ImmutableList<Action?> =
            cppCompileActions.stream()
                .filter(java.util.function.Predicate { action: Action? ->
                    action.getProgressMessage().contains("custom_rule_name.cppmap")
                })
                .collect(com.google.common.collect.ImmutableList.toImmutableList<Action?>())

        Truth.assertThat(moduleActions).hasSize(2)
        assertThat(moduleActions.get(0).getOwner().getExecutionPlatform().label())
            .isEqualTo(Label.parseCanonical("//platforms:platform_1"))
        assertThat(moduleActions.get(1).getOwner().getExecutionPlatform().label())
            .isEqualTo(Label.parseCanonical("//platforms:platform_1"))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun ccCommonCompile_codeGenModuleActionExecutesOnFirstPlatform() {
        scratch.file(
            "bazel_internal/test_rules/cc/defs.bzl",
            "load('@rules_cc//cc/common:cc_common.bzl', 'cc_common')",
            "def _use_cpp_toolchain():",
            "   return [",
            ("      config_common.toolchain_type('"
                    + TestConstants.CPP_TOOLCHAIN_TYPE
                    + "', mandatory = False),"),
            "   ]",
            "def _impl(ctx):",
            "  cc_toolchain = ctx.toolchains['" + TestConstants.CPP_TOOLCHAIN_TYPE + "'].cc",
            "  feature_configuration = cc_common.configure_features(",
            "      ctx = ctx,",
            "      cc_toolchain = cc_toolchain,",
            "      requested_features = ctx.features,",
            "      unsupported_features = ctx.disabled_features,",
            "  )",
            "  (compilation_context, compilation_outputs) = cc_common.compile(",
            "    name = ctx.label.name,",
            "    actions = ctx.actions,",
            "    feature_configuration = feature_configuration,",
            "    cc_toolchain = cc_toolchain,",
            "    srcs = ctx.files.srcs,",
            "    public_hdrs = ctx.files.hdrs,",
            "  )",
            "  return []",
            "custom_rule = rule(",
            "  implementation = _impl,",
            "  attrs = {",
            "    'srcs': attr.label_list(allow_files = ['.cc']),",
            "    'hdrs': attr.label_list(allow_files = ['.h']),",
            "  },",
            "  toolchains = ['//rule:toolchain_type_2'] + _use_cpp_toolchain(),",
            "  fragments = ['cpp']",
            ")"
        )
        scratch.file(
            "bazel_internal/test_rules/cc/BUILD",
            """
        load("//bazel_internal/test_rules/cc:defs.bzl", "custom_rule")

        custom_rule(
            name = "custom_rule_name",
            srcs = ["custom.cc"],
            hdrs = ["custom.h"],
        )
        
        """.trimIndent()
        )
        useConfiguration(
            "--incompatible_auto_exec_groups",
            "--features=header_modules",
            "--features=header_module_codegen"
        )
        AnalysisMock.get()
            .ccSupport()
            .setupCcToolchainConfig(
                mockToolsConfig,
                CcToolchainConfig.builder()
                    .withFeatures(MockCcSupport.HEADER_MODULES_FEATURES)
                    .withToolchainTargetConstraints("@@//platforms:constraint_1")
                    .withToolchainExecConstraints("@@//platforms:constraint_1")
            )

        val cppCompileActions: com.google.common.collect.ImmutableList<Action?> =
            getActions("//bazel_internal/test_rules/cc:custom_rule_name", CppCompileAction::class.java)
        val codeGenCompileActions: com.google.common.collect.ImmutableList<Action?> =
            cppCompileActions.stream()
                .filter(java.util.function.Predicate { action: Action? ->
                    action.getProgressMessage().contains("custom_rule_name.pcm")
                })
                .collect(com.google.common.collect.ImmutableList.toImmutableList<Action?>())

        Truth.assertThat(codeGenCompileActions).hasSize(1)
        assertThat(codeGenCompileActions.get(0).getOwner().getExecutionPlatform().label())
            .isEqualTo(Label.parseCanonical("//platforms:platform_1"))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun ccCommonCompile_compileHeaderActionExecutesOnFirstPlatform() {
        scratch.file(
            "bazel_internal/test_rules/cc/defs.bzl",
            "load('@rules_cc//cc/common:cc_common.bzl', 'cc_common')",
            "def _use_cpp_toolchain():",
            "   return [",
            ("      config_common.toolchain_type('"
                    + TestConstants.CPP_TOOLCHAIN_TYPE
                    + "', mandatory = False),"),
            "   ]",
            "def _impl(ctx):",
            "  cc_toolchain = ctx.toolchains['" + TestConstants.CPP_TOOLCHAIN_TYPE + "'].cc",
            "  feature_configuration = cc_common.configure_features(",
            "      ctx = ctx,",
            "      cc_toolchain = cc_toolchain,",
            "      requested_features = ctx.features,",
            "      unsupported_features = ctx.disabled_features,",
            "  )",
            "  (compilation_context, compilation_outputs) = cc_common.compile(",
            "    name = ctx.label.name,",
            "    actions = ctx.actions,",
            "    feature_configuration = feature_configuration,",
            "    cc_toolchain = cc_toolchain,",
            "    srcs = ctx.files.srcs,",
            "    private_hdrs = ctx.files.hdrs,",
            "  )",
            "  return []",
            "custom_rule = rule(",
            "  implementation = _impl,",
            "  attrs = {",
            "    'srcs': attr.label_list(allow_files = ['.cc']),",
            "    'hdrs': attr.label_list(allow_files = ['.h']),",
            "  },",
            "  toolchains = ['//rule:toolchain_type_2'] + _use_cpp_toolchain(),",
            "  fragments = ['cpp']",
            ")"
        )
        scratch.file(
            "bazel_internal/test_rules/cc/BUILD",
            """
        load("//bazel_internal/test_rules/cc:defs.bzl", "custom_rule")

        custom_rule(
            name = "custom_rule_name",
            srcs = ["custom.cc"],
            hdrs = ["custom.h"],
        )
        
        """.trimIndent()
        )
        useConfiguration("--incompatible_auto_exec_groups", "--features=parse_headers")
        AnalysisMock.get()
            .ccSupport()
            .setupCcToolchainConfig(
                mockToolsConfig,
                CcToolchainConfig.builder()
                    .withFeatures(CppRuleClasses.PARSE_HEADERS)
                    .withToolchainTargetConstraints("@@//platforms:constraint_1")
                    .withToolchainExecConstraints("@@//platforms:constraint_1")
            )

        val cppCompileActions: com.google.common.collect.ImmutableList<Action?> =
            getActions("//bazel_internal/test_rules/cc:custom_rule_name", CppCompileAction::class.java)
        val compileHeaderActions: com.google.common.collect.ImmutableList<Action?> =
            cppCompileActions.stream()
                .filter(
                    java.util.function.Predicate { action: Action? ->
                        action
                            .getProgressMessage()
                            .equals("Compiling bazel_internal/test_rules/cc/custom.h")
                    })
                .collect(com.google.common.collect.ImmutableList.toImmutableList<Action?>())

        Truth.assertThat(compileHeaderActions).hasSize(1)
        assertThat(compileHeaderActions.get(0).getOwner().getExecutionPlatform().label())
            .isEqualTo(Label.parseCanonical("//platforms:platform_1"))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun ccCommonCompile_treeArtifactActionExecutesOnFirstPlatform() {
        scratch.file(
            "test/defs.bzl",
            "load('@rules_cc//cc/common:cc_common.bzl', 'cc_common')",
            "def _use_cpp_toolchain():",
            "   return [",
            ("      config_common.toolchain_type('"
                    + TestConstants.CPP_TOOLCHAIN_TYPE
                    + "', mandatory = False),"),
            "   ]",
            "def _ta_impl(ctx):",
            "    tree = ctx.actions.declare_directory('dir')",
            "    ctx.actions.run_shell(",
            "        outputs = [tree],",
            "        inputs = [],",
            "        arguments = [tree.path],",
            "        command = 'mkdir $1',",
            "    )",
            "    return [DefaultInfo(files = depset([tree]))]",
            "create_tree_artifact = rule(implementation = _ta_impl)",
            "def _impl(ctx):",
            "  cc_toolchain = ctx.toolchains['" + TestConstants.CPP_TOOLCHAIN_TYPE + "'].cc",
            "  feature_configuration = cc_common.configure_features(",
            "      ctx = ctx,",
            "      cc_toolchain = cc_toolchain,",
            "      requested_features = ctx.features,",
            "      unsupported_features = ctx.disabled_features,",
            "  )",
            "  (compilation_context, compilation_outputs) = cc_common.compile(",
            "    name = ctx.label.name,",
            "    actions = ctx.actions,",
            "    feature_configuration = feature_configuration,",
            "    cc_toolchain = cc_toolchain,",
            "    srcs = ctx.files.srcs,",
            "  )",
            "  return []",
            "custom_rule = rule(",
            "  implementation = _impl,",
            "  attrs = {",
            "    'srcs': attr.label_list(allow_files = ['.cc']),",
            "  },",
            "  toolchains = ['//rule:toolchain_type_2'] + _use_cpp_toolchain(),",
            "  fragments = ['cpp']",
            ")"
        )
        scratch.file(
            "test/BUILD",
            """
        load("//test:defs.bzl", "create_tree_artifact", "custom_rule")

        package(default_visibility = ["//visibility:public"])

        create_tree_artifact(name = "tree_artifact")

        custom_rule(
            name = "custom_rule_name",
            srcs = ["tree_artifact"],
        )
        
        """.trimIndent()
        )
        useConfiguration("--incompatible_auto_exec_groups")

        val actions: com.google.common.collect.ImmutableList<ActionAnalysisMetadata?>? =
            (getConfiguredTarget("//test:custom_rule_name") as RuleConfiguredTarget).getActions()

        Truth.assertThat(actions).hasSize(1)
        assertThat(actions.get(0).getOwner().getExecutionPlatform().label())
            .isEqualTo(Label.parseCanonical("//platforms:platform_1"))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testToolchainAsAlias() {
        scratch.file(
            "test/alias/BUILD",
            """
        alias(
            name = "alias_toolchain_type_1",
            actual = "//rule:toolchain_type_1",
        )
        alias(
            name = "alias_toolchain_type_2",
            actual = "//rule:toolchain_type_2",
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/defs.bzl",
            """
        def _impl(ctx):
            return []

        custom_rule = rule(
            implementation = _impl,
            toolchains = ["//test/alias:alias_toolchain_type_1",
             "//test/alias:alias_toolchain_type_2"],
            exec_groups = {
                "custom_exec_group": exec_group(
                    toolchains = ["//rule:toolchain_type_1"],
                ),
            },
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load("//test:defs.bzl", "custom_rule")

        package(default_visibility = ["//visibility:public"])

        custom_rule(
            name = "custom_rule_name",
        )
        
        """.trimIndent()
        )
        useConfiguration("--incompatible_auto_exec_groups")

        val target: ConfiguredTarget? = getConfiguredTarget("//test:custom_rule_name")
        val ruleContext: RuleContext = getRuleContext(target)
        val realToolchainInfo1: ToolchainInfo? =
            ruleContext.getToolchainInfo(Label.parseCanonical("//rule:toolchain_type_1"))
        val aliasToolchainInfo1: ToolchainInfo? =
            ruleContext.getToolchainInfo(Label.parseCanonical("//test/alias:alias_toolchain_type_1"))

        assertThat(realToolchainInfo1).isEqualTo(aliasToolchainInfo1)

        val realToolchainInfo2: ToolchainInfo? =
            ruleContext.getToolchainInfo(Label.parseCanonical("//rule:toolchain_type_2"))
        val aliasToolchainInfo2: ToolchainInfo? =
            ruleContext.getToolchainInfo(Label.parseCanonical("//test/alias:alias_toolchain_type_2"))

        assertThat(realToolchainInfo2).isEqualTo(aliasToolchainInfo2)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testHeaderCompilationAction_automaticExecGroupsEnabled() {
        scratch.file(TestConstants.TOOLS_REPOSITORY_SCRATCH + "tools/jdk/turbine_canary_deploy.jar")
        scratch.file(TestConstants.TOOLS_REPOSITORY_SCRATCH + "tools/jdk/tzdata.jar")
        scratch.overwriteFile(
            TestConstants.TOOLS_REPOSITORY_SCRATCH + "tools/jdk/BUILD",
            "load('@rules_java//java:defs.bzl', 'java_runtime',"
                    + " 'java_toolchain')",
            "load(",
            "    ':java_toolchain_alias.bzl',",
            "    'java_toolchain_alias',",
            "    'java_runtime_alias',",
            "    'java_host_runtime_alias',",
            ")",
            "toolchain_type(name = 'toolchain_type')",
            "java_toolchain_alias(name='current_java_toolchain')",
            "java_plugins_flag_alias(name = 'java_plugins_flag_alias')",
            "filegroup(name = 'message_translations')",
            "java_toolchain(name = 'toolchain',",
            "    source_version = '6',",
            "    target_version = '6',",
            "    bootclasspath = ['rt.jar'],",
            "    xlint = ['toto'],",
            "    javacopts =['-Xmaxerrs 500'],",
            "    compatible_javacopts = {",
            "        'appengine': ['-XDappengineCompatible'],",
            "        'android': ['-XDandroidCompatible'],",
            "    },",
            "    tools = [':javac_canary.jar'],",
            "    javabuilder = ':JavaBuilder_deploy.jar',",
            "    jacocorunner = ':jacocorunner.jar',",
            "    header_compiler = ':turbine_canary_deploy.jar',",
            "    header_compiler_direct = ':turbine_graal',",
            "    singlejar = 'singlejar',",
            "    ijar = 'ijar',",
            "    genclass = 'GenClass_deploy.jar',",
            "    timezone_data = 'tzdata.jar',",
            "    java_runtime = ':jvm-k8',",
            "    exec_compatible_with = ['@@//platforms:constraint_2'],",
            ")",
            "java_runtime(",
            "    name = 'jvm-k8',",
            "    srcs = [",
            "        'k8/a', ",
            "        'k8/b',",
            "    ], ",
            "    java_home = 'k8',",
            ")",
            "toolchain(",
            "    name = 'java_toolchain',",
            "    toolchain = ':toolchain',",
            "    toolchain_type = '" + TestConstants.JAVA_TOOLCHAIN_TYPE + "',",
            "    exec_compatible_with = ['@@//platforms:constraint_2'],",
            ")"
        )
        scratch.file(
            "foo/custom_rule.bzl",
            "load('@rules_java//java:defs.bzl', 'java_common', 'JavaInfo',"
                    + " 'JavaPluginInfo')",
            "def _impl(ctx):",
            "  output_jar = ctx.actions.declare_file('lib' + ctx.label.name + '.jar')",
            "  compilation_provider = java_common.compile(",
            "    ctx,",
            "    source_files = ctx.files.srcs,",
            "    output = output_jar,",
            "    java_toolchain = ctx.toolchains['" + TestConstants.JAVA_TOOLCHAIN_TYPE + "'].java,",
            "    deps = [p[JavaInfo] for p in ctx.attr.deps],",
            "    plugins = [p[JavaPluginInfo] for p in ctx.attr.plugins],",
            "    enable_annotation_processing = False,",
            "  )",
            "  return [DefaultInfo(files = depset([output_jar])), compilation_provider]",
            "java_custom_library = rule(",
            "  implementation = _impl,",
            "  outputs = {",
            "    'my_output': 'lib%{name}.jar'",
            "  },",
            "  attrs = {",
            "    'srcs': attr.label_list(allow_files=True),",
            "    'deps': attr.label_list(providers=[JavaInfo]),",
            "    'plugins': attr.label_list(providers=[JavaPluginInfo]),",
            "  },",
            "  toolchains = ['//rule:toolchain_type_1', '" + TestConstants.JAVA_TOOLCHAIN_TYPE + "'],",
            "  fragments = ['java']",
            ")"
        )
        scratch.file(
            "foo/BUILD",
            """
        load("@rules_java//java:defs.bzl", "java_library", "java_plugin")
        load(":custom_rule.bzl", "java_custom_library")

        java_plugin(
            name = "processor",
            srcs = ["processor.java"],
            data = ["processor_data.txt"],
            generates_api = 1,  # so Turbine would normally run it
            processor_class = "Foo",
        )

        java_library(
            name = "exports_processor",
            exported_plugins = [":processor"],
        )

        java_custom_library(
            name = "custom",
            srcs = ["custom.java"],
            plugins = [":processor"],
            deps = [":exports_processor"],
        )

        java_custom_library(
            name = "custom_noproc",
            srcs = ["custom.java"],
        )
        
        """.trimIndent()
        )
        useConfiguration("--java_header_compilation=true", "--incompatible_auto_exec_groups=True")

        val custom: ConfiguredTarget? = getConfiguredTarget("//foo:custom")
        val customNoproc: ConfiguredTarget? = getConfiguredTarget("//foo:custom_noproc")
        val turbineAction: JavaCompileAction? =
            getGeneratingAction(getBinArtifact("libcustom-hjar.jar", custom)) as JavaCompileAction?
        val turbineActionNoProc: SpawnAction? =
            getGeneratingAction(getBinArtifact("libcustom_noproc-hjar.jar", customNoproc)) as SpawnAction?

        Truth.assertThat(turbineAction.mnemonic).isEqualTo("JavacTurbine")
        assertThat(turbineAction.getOwner().getExecutionPlatform().label())
            .isEqualTo(Label.parseCanonical("//platforms:platform_2"))
        assertThat(turbineActionNoProc.getMnemonic()).isEqualTo("Turbine")
        assertThat(turbineActionNoProc.getOwner().getExecutionPlatform().label())
            .isEqualTo(Label.parseCanonical("//platforms:platform_2"))
    }
}
