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
package com.google.devtools.build.lib.rules.cpp

import com.google.devtools.build.lib.rules.cpp.CcToolchainFeaturesLib.actionConfigFromStarlark

/** Unit tests for the `cc_common` Starlark module.  */
@RunWith(JUnit4::class)
class StarlarkCcCommonTest : BuildViewTestCase() {
    @Before
    @Throws(java.lang.Exception::class)
    fun setUp() {
        scratch.file("myinfo/myinfo.bzl", "MyInfo = provider()")

        scratch.file("myinfo/BUILD")
    }

    private fun getLinkCommandLine(cppLinkAction: SpawnAction): LinkCommandLine? {
        val commandLines: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            cppLinkAction.getCommandLines().unpack()
        assertThat(commandLines).hasSize(2)
        assertThat(commandLines.get(1).commandLine).isInstanceOf(LinkCommandLine::class.java)
        return commandLines.get(1).commandLine as LinkCommandLine?
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAllFiles() {
        scratch.file(
            "a/BUILD",
            """
        load("@rules_cc//cc/toolchains:cc_toolchain_alias.bzl", "cc_toolchain_alias")
        load(":rule.bzl", "crule")

        cc_toolchain_alias(name = "alias")

        crule(name = "r")
        
        """.trimIndent()
        )

        scratch.file(
            "a/rule.bzl",
            "load('//myinfo:myinfo.bzl', 'MyInfo')",
            ("load('"
                    + TestConstants.RULES_CC
                    + ":find_cc_toolchain.bzl', 'find_cc_toolchain', 'use_cc_toolchain')"),
            "def _impl(ctx):",
            "  toolchain = find_cc_toolchain(ctx)",
            "  return [MyInfo(all_files = toolchain.all_files)]",
            "crule = rule(",
            "  _impl,",
            "  attrs = { ",
            "    '_cc_toolchain': attr.label(default=Label('//a:alias'))",
            "  },",
            "  toolchains = use_cc_toolchain()",
            ");"
        )

        val r: ConfiguredTarget = getConfiguredTarget("//a:r")
        val allFiles: Depset = getMyInfoFromTarget(r).getValue("all_files") as Depset
        val ruleContext: RuleContext = getRuleContext(r)
        val toolchain: CcToolchainProvider = CppHelper.getToolchain(ruleContext)
        assertThat(allFiles.getSet(Artifact::class.java)).isEqualTo(toolchain.getAllFiles())
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRuntimeLib() {
        scratch.file(
            "a/BUILD",
            """
        load("@rules_cc//cc/toolchains:cc_toolchain_alias.bzl", "cc_toolchain_alias")
        load(":rule.bzl", "crule")

        cc_toolchain_alias(name = "alias")

        crule(name = "r")
        
        """.trimIndent()
        )

        scratch.file(
            "a/rule.bzl",
            ("load('"
                    + TestConstants.RULES_CC
                    + ":find_cc_toolchain.bzl', 'find_cc_toolchain', 'use_cc_toolchain')"),
            "load('@rules_cc//cc/common:cc_common.bzl', 'cc_common')",
            "CruleInfo = provider(fields=['static', 'dynamic'])",
            "def _impl(ctx):",
            "  toolchain = find_cc_toolchain(ctx)",
            "  feature_configuration = cc_common.configure_features(",
            "    ctx = ctx,",
            "    cc_toolchain = toolchain,",
            "  )",
            "  return [CruleInfo(",
            "    static = toolchain.static_runtime_lib(feature_configuration = feature_configuration),",
            "    dynamic = toolchain.dynamic_runtime_lib(",
            "      feature_configuration = feature_configuration),",
            "  )]",
            "crule = rule(",
            "  _impl,",
            "  attrs = { ",
            "    '_cc_toolchain': attr.label(default=Label('//a:alias'))",
            "  },",
            "  fragments = ['cpp'],",
            "  toolchains = use_cc_toolchain()",
            ");"
        )

        // 1. Build without static_link_cpp_runtimes
        var r: ConfiguredTarget = getConfiguredTarget("//a:r")
        val key: Provider.Key =
            Key(
                keyForBuild(Label.create(r.getLabel().getPackageIdentifier(), "rule.bzl")),
                "CruleInfo"
            )
        var cruleInfo: StarlarkInfo = r.get(key) as StarlarkInfo
        var staticRuntimeLib: Depset = cruleInfo.getValue("static") as Depset
        var dynamicRuntimeLib: Depset = cruleInfo.getValue("dynamic") as Depset

        assertThat(staticRuntimeLib.getSet(Artifact::class.java).toList()).isEmpty()
        assertThat(dynamicRuntimeLib.getSet(Artifact::class.java).toList()).isEmpty()

        // 2. Build with static_link_cpp_runtimes
        getAnalysisMock()
            .ccSupport()
            .setupCcToolchainConfig(
                mockToolsConfig,
                CcToolchainConfig.builder().withFeatures(CppRuleClasses.STATIC_LINK_CPP_RUNTIMES)
            )
        invalidatePackages()
        r = getConfiguredTarget("//a:r")
        cruleInfo = r.get(key) as StarlarkInfo
        staticRuntimeLib = cruleInfo.getValue("static") as Depset
        dynamicRuntimeLib = cruleInfo.getValue("dynamic") as Depset

        val ruleContext: RuleContext = getRuleContext(r)
        val toolchain: CcToolchainProvider = CppHelper.getToolchain(ruleContext)
        assertThat(staticRuntimeLib.getSet(Artifact::class.java))
            .isEqualTo(toolchain.getStaticRuntimeLinkInputs())
        assertThat(dynamicRuntimeLib.getSet(Artifact::class.java))
            .isEqualTo(toolchain.getDynamicRuntimeLinkInputs())
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testGetValuesFromCcToolchain() {
        scratch.file(
            "a/BUILD",
            """
        load("@rules_cc//cc/toolchains:cc_toolchain_alias.bzl", "cc_toolchain_alias")
        load(":rule.bzl", "crule")

        cc_toolchain_alias(name = "alias")

        crule(name = "r")
        
        """.trimIndent()
        )

        scratch.file(
            "a/rule.bzl",
            "load('//myinfo:myinfo.bzl', 'MyInfo')",
            ("load('"
                    + TestConstants.RULES_CC
                    + ":find_cc_toolchain.bzl', 'find_cc_toolchain', 'use_cc_toolchain')"),
            "def _impl(ctx):",
            "  toolchain = find_cc_toolchain(ctx)",
            "  return [MyInfo(",
            "    dynamic_runtime_solib_dir = toolchain.dynamic_runtime_solib_dir,",
            "    toolchain_id = toolchain.toolchain_id,",
            "  )]",
            "crule = rule(",
            "  _impl,",
            "  attrs = { ",
            "    '_cc_toolchain': attr.label(default=Label('//a:alias'))",
            "  },",
            "  fragments = ['cpp'],",
            "  toolchains = use_cc_toolchain()",
            ");"
        )

        val r: ConfiguredTarget = getConfiguredTarget("//a:r")
        val info: StructImpl = getMyInfoFromTarget(r)
        val dynamicRuntimeSolibDir = info.getValue("dynamic_runtime_solib_dir") as String?
        val toolchainId = info.getValue("toolchain_id") as String?

        val ruleContext: RuleContext = getRuleContext(r)
        val toolchain: CcToolchainProvider = CppHelper.getToolchain(ruleContext)

        Truth.assertThat(dynamicRuntimeSolibDir)
            .isEqualTo(toolchain.getDynamicRuntimeSolibDir().getPathString())
        Truth.assertThat(toolchainId).isEqualTo(toolchain.getToolchainIdentifier())
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testGetToolForAction() {
        scratch.file(
            "a/BUILD",
            """
        load("@rules_cc//cc/toolchains:cc_toolchain_alias.bzl", "cc_toolchain_alias")
        load(":rule.bzl", "crule")

        cc_toolchain_alias(name = "alias")

        crule(name = "r")
        
        """.trimIndent()
        )

        scratch.file(
            "a/rule.bzl",
            "load('//myinfo:myinfo.bzl', 'MyInfo')",
            "load('@rules_cc//cc/common:cc_common.bzl', 'cc_common')",
            ("load('"
                    + TestConstants.RULES_CC
                    + ":find_cc_toolchain.bzl', 'find_cc_toolchain', 'use_cc_toolchain')"),
            "def _impl(ctx):",
            "  toolchain = find_cc_toolchain(ctx)",
            "  feature_configuration = cc_common.configure_features(",
            "    ctx = ctx,",
            "    cc_toolchain = toolchain,",
            "  )",
            "  return [MyInfo(",
            "    action_tool_path = cc_common.get_tool_for_action(",
            "        feature_configuration = feature_configuration,",
            "        action_name = 'c++-compile'))]",
            "crule = rule(",
            "  _impl,",
            "  attrs = { ",
            "    '_cc_toolchain': attr.label(default=Label('//a:alias'))",
            "  },",
            "  fragments = ['cpp'],",
            "  toolchains = use_cc_toolchain()",
            ");"
        )

        val r: ConfiguredTarget = getConfiguredTarget("//a:r")
        val actionToolPath = getMyInfoFromTarget(r).getValue("action_tool_path") as String?
        val ruleContext: RuleContext = getRuleContext(r)
        val toolchain: CcToolchainProvider? = CppHelper.getToolchain(ruleContext)
        val featureConfiguration: FeatureConfiguration =
            CcCommon.configureFeaturesOrThrowEvalException(
                com.google.common.collect.ImmutableSet.of<E?>(),
                com.google.common.collect.ImmutableSet.of<E?>(),
                Language.CPP,
                toolchain,
                ruleContext.getFragment(CppConfiguration::class.java)
            )
        Truth.assertThat(actionToolPath)
            .isEqualTo(featureConfiguration.getToolPathForAction(CppActionNames.CPP_COMPILE))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testExecutionRequirements() {
        AnalysisMock.get()
            .ccSupport()
            .setupCcToolchainConfig(
                mockToolsConfig,
                CcToolchainConfig.builder()
                    .withFeatures(MockCcSupport.CPP_COMPILE_ACTION_WITH_REQUIREMENTS)
            )
        scratch.file(
            "a/BUILD",
            """
        load("@rules_cc//cc/toolchains:cc_toolchain_alias.bzl", "cc_toolchain_alias")
        load(":rule.bzl", "crule")

        cc_toolchain_alias(name = "alias")

        crule(name = "r")
        
        """.trimIndent()
        )

        scratch.file(
            "a/rule.bzl",
            """
        load("//myinfo:myinfo.bzl", "MyInfo")
        load('@rules_cc//cc/common:cc_common.bzl', 'cc_common')

        def _impl(ctx):
            toolchain = ctx.attr._cc_toolchain[cc_common.CcToolchainInfo]
            feature_configuration = cc_common.configure_features(
                ctx = ctx,
                cc_toolchain = toolchain,
            )
            return [MyInfo(
                requirements = cc_common.get_execution_requirements(
                    feature_configuration = feature_configuration,
                    action_name = "yolo_action_with_requirements",
                ),
            )]

        crule = rule(
            _impl,
            attrs = {
                "_cc_toolchain": attr.label(default = Label("//a:alias")),
            },
            fragments = ["cpp"],
        )
        
        """.trimIndent()
        )

        val r: ConfiguredTarget = getConfiguredTarget("//a:r")
        val requirements: net.starlark.java.eval.Sequence<String?>? =
            getMyInfoFromTarget(r).getValue("requirements") as net.starlark.java.eval.Sequence<String?>?
        Truth.assertThat(requirements).containsExactly("requires-yolo")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testFeatureConfigurationWithAdditionalEnabledFeature() {
        AnalysisMock.get()
            .ccSupport()
            .setupCcToolchainConfig(
                mockToolsConfig, CcToolchainConfig.builder().withFeatures("foo_feature")
            )
        useConfiguration()
        scratch.file(
            "a/BUILD",
            """
        load("@rules_cc//cc/toolchains:cc_toolchain_alias.bzl", "cc_toolchain_alias")
        load(":rule.bzl", "crule")

        cc_toolchain_alias(name = "alias")

        crule(name = "r")
        
        """.trimIndent()
        )

        scratch.file(
            "a/rule.bzl",
            """
        load("//myinfo:myinfo.bzl", "MyInfo")
        load('@rules_cc//cc/common:cc_common.bzl', 'cc_common')

        def _impl(ctx):
            toolchain = ctx.attr._cc_toolchain[cc_common.CcToolchainInfo]
            feature_configuration = cc_common.configure_features(
                ctx = ctx,
                cc_toolchain = toolchain,
                requested_features = ["foo_feature"],
            )
            return [MyInfo(
                foo_feature_enabled = cc_common.is_enabled(
                    feature_configuration = feature_configuration,
                    feature_name = "foo_feature",
                ),
            )]

        crule = rule(
            _impl,
            attrs = {
                "_cc_toolchain": attr.label(default = Label("//a:alias")),
            },
            fragments = ["cpp"],
        )
        
        """.trimIndent()
        )

        val r: ConfiguredTarget = getConfiguredTarget("//a:r")
        val fooFeatureEnabled = getMyInfoFromTarget(r).getValue("foo_feature_enabled") as Boolean
        Truth.assertThat(fooFeatureEnabled).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testFeatureConfigurationWithAdditionalUnsupportedFeature() {
        AnalysisMock.get()
            .ccSupport()
            .setupCcToolchainConfig(
                mockToolsConfig, CcToolchainConfig.builder().withFeatures("foo_feature")
            )
        useConfiguration("--features=foo_feature")
        scratch.file(
            "a/BUILD",
            """
        load("@rules_cc//cc/toolchains:cc_toolchain_alias.bzl", "cc_toolchain_alias")
        load(":rule.bzl", "crule")

        cc_toolchain_alias(name = "alias")

        crule(name = "r")
        
        """.trimIndent()
        )

        scratch.file(
            "a/rule.bzl",
            """
        load("//myinfo:myinfo.bzl", "MyInfo")
        load('@rules_cc//cc/common:cc_common.bzl', 'cc_common')

        def _impl(ctx):
            toolchain = ctx.attr._cc_toolchain[cc_common.CcToolchainInfo]
            feature_configuration = cc_common.configure_features(
                ctx = ctx,
                cc_toolchain = toolchain,
                unsupported_features = ["foo_feature"],
            )
            return [MyInfo(
                foo_feature_enabled = cc_common.is_enabled(
                    feature_configuration = feature_configuration,
                    feature_name = "foo_feature",
                ),
            )]

        crule = rule(
            _impl,
            attrs = {
                "_cc_toolchain": attr.label(default = Label("//a:alias")),
            },
            fragments = ["cpp"],
        )
        
        """.trimIndent()
        )

        val r: ConfiguredTarget = getConfiguredTarget("//a:r")
        val fooFeatureEnabled = getMyInfoFromTarget(r).getValue("foo_feature_enabled") as Boolean
        Truth.assertThat(fooFeatureEnabled).isFalse()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testGetCommandLine() {
        scratch.file(
            "a/BUILD",
            """
        load("@rules_cc//cc/toolchains:cc_toolchain_alias.bzl", "cc_toolchain_alias")
        load(":rule.bzl", "crule")

        cc_toolchain_alias(name = "alias")

        crule(name = "r")
        
        """.trimIndent()
        )

        scratch.file(
            "a/rule.bzl",
            "load('//myinfo:myinfo.bzl', 'MyInfo')",
            ("load('"
                    + TestConstants.RULES_CC
                    + ":find_cc_toolchain.bzl', 'find_cc_toolchain', 'use_cc_toolchain')"),
            "load('@rules_cc//cc/common:cc_common.bzl', 'cc_common')",
            "def _impl(ctx):",
            "  toolchain = find_cc_toolchain(ctx)",
            "  feature_configuration = cc_common.configure_features(",
            "    ctx = ctx,",
            "    cc_toolchain = toolchain,",
            "  )",
            "  return [MyInfo(",
            "    command_line = cc_common.get_memory_inefficient_command_line(",
            "        feature_configuration = feature_configuration,",
            "        action_name = 'c++-link-executable',",
            "        variables = cc_common.empty_variables()))]",
            "crule = rule(",
            "  _impl,",
            "  attrs = { ",
            "    '_cc_toolchain': attr.label(default=Label('//a:alias'))",
            "  },",
            "  fragments = ['cpp'],",
            "  toolchains = use_cc_toolchain()",
            ");"
        )

        val r: ConfiguredTarget = getConfiguredTarget("//a:r")
        val commandLine: net.starlark.java.eval.Sequence<String?>? =
            getMyInfoFromTarget(r).getValue("command_line") as net.starlark.java.eval.Sequence<String?>?
        val ruleContext: RuleContext = getRuleContext(r)
        val toolchain: CcToolchainProvider? = CppHelper.getToolchain(ruleContext)
        val featureConfiguration: FeatureConfiguration =
            CcCommon.configureFeaturesOrThrowEvalException(
                com.google.common.collect.ImmutableSet.of<E?>(),
                com.google.common.collect.ImmutableSet.of<E?>(),
                Language.CPP,
                toolchain,
                ruleContext.getFragment(CppConfiguration::class.java)
            )
        Truth.assertThat(commandLine)
            .containsExactlyElementsIn(
                featureConfiguration.getCommandLine(
                    "c++-link-executable", CcToolchainVariables.empty()
                )
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testGetEnvironment() {
        scratch.file(
            "a/BUILD",
            """
        load("@rules_cc//cc/toolchains:cc_toolchain_alias.bzl", "cc_toolchain_alias")
        load(":rule.bzl", "crule")

        cc_toolchain_alias(name = "alias")

        crule(name = "r")
        
        """.trimIndent()
        )

        scratch.file(
            "a/rule.bzl",
            ("load('"
                    + TestConstants.RULES_CC
                    + ":find_cc_toolchain.bzl', 'find_cc_toolchain', 'use_cc_toolchain')"),
            "load('//myinfo:myinfo.bzl', 'MyInfo')",
            "load('@rules_cc//cc/common:cc_common.bzl', 'cc_common')",
            "def _impl(ctx):",
            "  toolchain = find_cc_toolchain(ctx)",
            "  feature_configuration = cc_common.configure_features(",
            "    ctx = ctx,",
            "    cc_toolchain = toolchain,",
            "  )",
            "  return [MyInfo(",
            "    environment_variables = cc_common.get_environment_variables(",
            "        feature_configuration = feature_configuration,",
            "        action_name = 'c++-compile',",
            "        variables = cc_common.empty_variables()))]",
            "crule = rule(",
            "  _impl,",
            "  attrs = { ",
            "    '_cc_toolchain': attr.label(default=Label('//a:alias'))",
            "  },",
            "  fragments = ['cpp'],",
            "  toolchains = use_cc_toolchain()",
            ");"
        )

        val r: ConfiguredTarget = getConfiguredTarget("//a:r")
        val environmentVariables =
            getMyInfoFromTarget(r).getValue("environment_variables") as MutableMap<String?, String?>?
        val ruleContext: RuleContext = getRuleContext(r)
        val toolchain: CcToolchainProvider? = CppHelper.getToolchain(ruleContext)
        val featureConfiguration: FeatureConfiguration =
            CcCommon.configureFeaturesOrThrowEvalException(
                com.google.common.collect.ImmutableSet.of<E?>(),
                com.google.common.collect.ImmutableSet.of<E?>(),
                Language.CPP,
                toolchain,
                ruleContext.getFragment(CppConfiguration::class.java)
            )
        Truth.assertThat(environmentVariables)
            .containsExactlyEntriesIn(
                featureConfiguration.getEnvironmentVariables(
                    CppActionNames.CPP_COMPILE, CcToolchainVariables.empty(), PathMapper.NOOP
                )
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testActionIsEnabled() {
        scratch.file(
            "a/BUILD",
            """
        load("@rules_cc//cc/toolchains:cc_toolchain_alias.bzl", "cc_toolchain_alias")
        load(":rule.bzl", "crule")

        cc_toolchain_alias(name = "alias")

        crule(name = "r")
        
        """.trimIndent()
        )

        scratch.file(
            "a/rule.bzl",
            """
        load("//myinfo:myinfo.bzl", "MyInfo")
        load('@rules_cc//cc/common:cc_common.bzl', 'cc_common')

        def _impl(ctx):
            toolchain = ctx.attr._cc_toolchain[cc_common.CcToolchainInfo]
            feature_configuration = cc_common.configure_features(
                ctx = ctx,
                cc_toolchain = toolchain,
            )
            return [MyInfo(
                enabled_action = cc_common.action_is_enabled(
                    feature_configuration = feature_configuration,
                    action_name = "c-compile",
                ),
                disabled_action = cc_common.action_is_enabled(
                    feature_configuration = feature_configuration,
                    action_name = "wololoo",
                ),
            )]

        crule = rule(
            _impl,
            attrs = {
                "_cc_toolchain": attr.label(default = Label("//a:alias")),
            },
            fragments = ["cpp"],
        )
        
        """.trimIndent()
        )

        val myInfo: StructImpl = getMyInfoFromTarget(getConfiguredTarget("//a:r"))
        val enabledActionIsEnabled = myInfo.getValue("enabled_action") as Boolean
        val disabledActionIsDisabled = myInfo.getValue("disabled_action") as Boolean
        Truth.assertThat(enabledActionIsEnabled).isTrue()
        Truth.assertThat(disabledActionIsDisabled).isFalse()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testIsEnabled() {
        scratch.file(
            "a/BUILD",
            """
        load("@rules_cc//cc/toolchains:cc_toolchain_alias.bzl", "cc_toolchain_alias")
        load(":rule.bzl", "crule")

        cc_toolchain_alias(name = "alias")

        crule(name = "r")
        
        """.trimIndent()
        )

        scratch.file(
            "a/rule.bzl",
            """
        load("//myinfo:myinfo.bzl", "MyInfo")
        load('@rules_cc//cc/common:cc_common.bzl', 'cc_common')

        def _impl(ctx):
            toolchain = ctx.attr._cc_toolchain[cc_common.CcToolchainInfo]
            feature_configuration = cc_common.configure_features(
                ctx = ctx,
                cc_toolchain = toolchain,
            )
            return [MyInfo(
                enabled_feature = cc_common.is_enabled(
                    feature_configuration = feature_configuration,
                    feature_name = "libraries_to_link",
                ),
                disabled_feature = cc_common.is_enabled(
                    feature_configuration = feature_configuration,
                    feature_name = "wololoo",
                ),
            )]

        crule = rule(
            _impl,
            attrs = {
                "_cc_toolchain": attr.label(default = Label("//a:alias")),
            },
            fragments = ["cpp"],
        )
        
        """.trimIndent()
        )

        val myInfo: StructImpl = getMyInfoFromTarget(getConfiguredTarget("//a:r"))

        val enabledFeatureIsEnabled = myInfo.getValue("enabled_feature") as Boolean
        val disabledFeatureIsDisabled = myInfo.getValue("disabled_feature") as Boolean
        Truth.assertThat(enabledFeatureIsEnabled).isTrue()
        Truth.assertThat(disabledFeatureIsDisabled).isFalse()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testFeatureConfigurationRequiresCtx() {
        scratch.file(
            "a/BUILD",
            """
        load("@rules_cc//cc/toolchains:cc_toolchain_alias.bzl", "cc_toolchain_alias")
        load(":rule.bzl", "crule")

        cc_toolchain_alias(name = "alias")

        crule(name = "r")
        
        """.trimIndent()
        )

        scratch.file(
            "a/rule.bzl",
            """
        load('@rules_cc//cc/common:cc_common.bzl', 'cc_common')
        def _impl(ctx):
            toolchain = ctx.attr._cc_toolchain[cc_common.CcToolchainInfo]
            feature_configuration = cc_common.configure_features(cc_toolchain = toolchain)

        crule = rule(
            _impl,
            attrs = {
                "_cc_toolchain": attr.label(default = Label("//a:alias")),
            },
            fragments = ["cpp"],
        )
        
        """.trimIndent()
        )
        reporter.removeHandler(failFastHandler)

        getConfiguredTarget("//a:r")
        assertContainsEvent("configure_features() missing 1 required keyword-only argument: ctx")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testActionNames() {
        scratch.file(
            "a/BUILD",
            """
        load("@rules_cc//cc/toolchains:cc_toolchain_alias.bzl", "cc_toolchain_alias")
        load(":rule.bzl", "crule")

        cc_toolchain_alias(name = "alias")

        crule(name = "r")
        
        """.trimIndent()
        )
        scratch.overwriteFile("tools/build_defs/cc/BUILD")
        scratch.overwriteFile(
            "tools/build_defs/cc/action_names.bzl",
            com.google.devtools.build.lib.packages.util.ResourceLoader.readFromResources(
                TestConstants.RULES_CC_REPOSITORY_EXECROOT + "cc/action_names.bzl"
            )
        )

        scratch.file(
            "a/rule.bzl",
            """
        load("//myinfo:myinfo.bzl", "MyInfo")
        load(
            "//tools/build_defs/cc:action_names.bzl",
            "ASSEMBLE_ACTION_NAME",
            "CC_FLAGS_MAKE_VARIABLE_ACTION_NAME",
            "CPP_COMPILE_ACTION_NAME",
            "CPP_HEADER_PARSING_ACTION_NAME",
            "CPP_LINK_DYNAMIC_LIBRARY_ACTION_NAME",
            "CPP_LINK_EXECUTABLE_ACTION_NAME",
            "CPP_LINK_NODEPS_DYNAMIC_LIBRARY_ACTION_NAME",
            "CPP_LINK_STATIC_LIBRARY_ACTION_NAME",
            "CPP_MODULE_CODEGEN_ACTION_NAME",
            "CPP_MODULE_COMPILE_ACTION_NAME",
            "C_COMPILE_ACTION_NAME",
            "LINKSTAMP_COMPILE_ACTION_NAME",
            "LTO_BACKEND_ACTION_NAME",
            "LTO_INDEXING_ACTION_NAME",
            "PREPROCESS_ASSEMBLE_ACTION_NAME",
            "STRIP_ACTION_NAME",
        )
        load('@rules_cc//cc/common:cc_common.bzl', 'cc_common')

        def _impl(ctx):
            toolchain = ctx.attr._cc_toolchain[cc_common.CcToolchainInfo]
            feature_configuration = cc_common.configure_features(
                ctx = ctx,
                cc_toolchain = toolchain,
            )
            return [MyInfo(
                c_compile_action_name = C_COMPILE_ACTION_NAME,
                cpp_compile_action_name = CPP_COMPILE_ACTION_NAME,
                linkstamp_compile_action_name = LINKSTAMP_COMPILE_ACTION_NAME,
                cc_flags_make_variable_action_name_action_name =
                    CC_FLAGS_MAKE_VARIABLE_ACTION_NAME,
                cpp_module_codegen_action_name = CPP_MODULE_CODEGEN_ACTION_NAME,
                cpp_header_parsing_action_name = CPP_HEADER_PARSING_ACTION_NAME,
                cpp_module_compile_action_name = CPP_MODULE_COMPILE_ACTION_NAME,
                assemble_action_name = ASSEMBLE_ACTION_NAME,
                preprocess_assemble_action_name = PREPROCESS_ASSEMBLE_ACTION_NAME,
                lto_indexing_action_name = LTO_INDEXING_ACTION_NAME,
                lto_backend_action_name = LTO_BACKEND_ACTION_NAME,
                cpp_link_executable_action_name = CPP_LINK_EXECUTABLE_ACTION_NAME,
                cpp_link_dynamic_library_action_name = CPP_LINK_DYNAMIC_LIBRARY_ACTION_NAME,
                cpp_link_nodeps_dynamic_library_action_name =
                    CPP_LINK_NODEPS_DYNAMIC_LIBRARY_ACTION_NAME,
                cpp_link_static_library_action_name = CPP_LINK_STATIC_LIBRARY_ACTION_NAME,
                strip_action_name = STRIP_ACTION_NAME,
            )]

        crule = rule(
            _impl,
            attrs = {
                "_cc_toolchain": attr.label(default = Label("//a:alias")),
            },
            fragments = ["cpp"],
        )
        
        """.trimIndent()
        )

        assertThat(getTarget("//a:r")).isNotNull()

        val myInfo: StructImpl = getMyInfoFromTarget(getConfiguredTarget("//a:r"))

        assertThat(myInfo.getValue("c_compile_action_name")).isEqualTo(CppActionNames.C_COMPILE)
        assertThat(myInfo.getValue("cpp_compile_action_name")).isEqualTo(CppActionNames.CPP_COMPILE)
        assertThat(myInfo.getValue("linkstamp_compile_action_name"))
            .isEqualTo(CppActionNames.LINKSTAMP_COMPILE)
        assertThat(myInfo.getValue("cc_flags_make_variable_action_name_action_name"))
            .isEqualTo(CppActionNames.CC_FLAGS_MAKE_VARIABLE)
        assertThat(myInfo.getValue("cpp_module_codegen_action_name"))
            .isEqualTo(CppActionNames.CPP_MODULE_CODEGEN)
        assertThat(myInfo.getValue("cpp_header_parsing_action_name"))
            .isEqualTo(CppActionNames.CPP_HEADER_PARSING)
        assertThat(myInfo.getValue("cpp_module_compile_action_name"))
            .isEqualTo(CppActionNames.CPP_MODULE_COMPILE)
        assertThat(myInfo.getValue("assemble_action_name")).isEqualTo(CppActionNames.ASSEMBLE)
        assertThat(myInfo.getValue("preprocess_assemble_action_name"))
            .isEqualTo(CppActionNames.PREPROCESS_ASSEMBLE)
        assertThat(myInfo.getValue("lto_indexing_action_name")).isEqualTo(CppActionNames.LTO_INDEXING)
        assertThat(myInfo.getValue("lto_backend_action_name")).isEqualTo(CppActionNames.LTO_BACKEND)
        assertThat(myInfo.getValue("cpp_link_executable_action_name"))
            .isEqualTo(CppActionNames.CPP_LINK_EXECUTABLE)
        assertThat(myInfo.getValue("cpp_link_dynamic_library_action_name"))
            .isEqualTo(CppActionNames.CPP_LINK_DYNAMIC_LIBRARY)
        assertThat(myInfo.getValue("cpp_link_nodeps_dynamic_library_action_name"))
            .isEqualTo(CppActionNames.CPP_LINK_NODEPS_DYNAMIC_LIBRARY)
        assertThat(myInfo.getValue("cpp_link_static_library_action_name"))
            .isEqualTo(CppActionNames.CPP_LINK_STATIC_LIBRARY)
        assertThat(myInfo.getValue("strip_action_name")).isEqualTo(CppActionNames.STRIP)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCompileBuildVariablesWithSourceFile() {
        Truth.assertThat(
            commandLineForVariables(
                CppActionNames.CPP_COMPILE,
                "cc_common.create_compile_variables(",
                "feature_configuration = feature_configuration,",
                "cc_toolchain = toolchain,",
                "source_file = 'foo/bar/hello'",
                ")"
            )
        )
            .containsAtLeast("-c", "foo/bar/hello")
            .inOrder()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCompileBuildVariablesWithOutputFile() {
        Truth.assertThat(
            commandLineForVariables(
                CppActionNames.CPP_COMPILE,
                "cc_common.create_compile_variables(",
                "feature_configuration = feature_configuration,",
                "cc_toolchain = toolchain,",
                "output_file = 'foo/bar/hello.o'",
                ")"
            )
        )
            .containsAtLeast("-o", "foo/bar/hello.o")
            .inOrder()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCompileBuildVariablesForIncludes() {
        Truth.assertThat(
            commandLineForVariables(
                CppActionNames.CPP_COMPILE,
                "cc_common.create_compile_variables(",
                "feature_configuration = feature_configuration,",
                "cc_toolchain = toolchain,",
                "include_directories = depset(['foo/bar/include'])",
                ")"
            )
        )
            .contains("-Ifoo/bar/include")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCompileBuildVariablesForFrameworkIncludes() {
        Truth.assertThat(
            commandLineForVariables(
                CppActionNames.CPP_COMPILE,
                "cc_common.create_compile_variables(",
                "feature_configuration = feature_configuration,",
                "cc_toolchain = toolchain,",
                "framework_include_directories = depset(['foo/bar'])",
                ")"
            )
        )
            .contains("-Ffoo/bar")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCompileBuildVariablesForDefines() {
        Truth.assertThat(
            commandLineForVariables(
                CppActionNames.CPP_COMPILE,
                "cc_common.create_compile_variables(",
                "feature_configuration = feature_configuration,",
                "cc_toolchain = toolchain,",
                "preprocessor_defines = depset(['DEBUG_FOO'])",
                ")"
            )
        )
            .contains("-DDEBUG_FOO")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCompileBuildVariablesForPic() {
        AnalysisMock.get()
            .ccSupport()
            .setupCcToolchainConfig(
                mockToolsConfig,
                CcToolchainConfig.builder()
                    .withFeatures(CppRuleClasses.SUPPORTS_PIC, CppRuleClasses.PIC)
            )
        useConfiguration()
        Truth.assertThat(
            commandLineForVariables(
                CppActionNames.CPP_COMPILE,
                "cc_common.create_compile_variables(",
                "feature_configuration = feature_configuration,",
                "cc_toolchain = toolchain,",
                "use_pic = True",
                ")"
            )
        )
            .contains("-fPIC")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testUserCompileFlags() {
        Truth.assertThat(
            commandLineForVariables(
                CppActionNames.CPP_COMPILE,
                "cc_common.create_compile_variables(",
                "feature_configuration = feature_configuration,",
                "cc_toolchain = toolchain,",
                "user_compile_flags = ['-foo']",
                ")"
            )
        )
            .contains("-foo")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCompileBuildVariablesForDummyLtoBackendAction() {
        AnalysisMock.get()
            .ccSupport()
            .setupCcToolchainConfig(
                mockToolsConfig, CcToolchainConfig.builder().withFeatures(CppRuleClasses.THIN_LTO)
            )
        useConfiguration("--features=thin_lto")
        val commandLine: net.starlark.java.eval.Sequence<String?>? =
            commandLineForVariables(
                CppActionNames.LTO_BACKEND,
                "cc_common.create_compile_variables(",
                "feature_configuration = feature_configuration,",
                "cc_toolchain = toolchain,",
                "thinlto_input_bitcode_file = 'path/to/input',",
                "thinlto_output_object_file = 'path/to/output',",
                "thinlto_index = '/dev/null'",
                ")"
            )

        Truth.assertThat(commandLine)
            .containsAtLeast(
                "thinlto_index=/dev/null",
                "thinlto_output_object_file=path/to/output",
                "thinlto_input_bitcode_file=path/to/input"
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCompileBuildVariablesWithVariablesExtension() {
        AnalysisMock.get()
            .ccSupport()
            .setupCcToolchainConfig(
                mockToolsConfig,
                CcToolchainConfig.builder().withFeatures("check_additional_variables_feature")
            )
        useConfiguration("--features=check_additional_variables_feature")
        Truth.assertThat(
            commandLineForVariables(
                CppActionNames.CPP_COMPILE,
                "cc_common.create_compile_variables(",
                "    feature_configuration = feature_configuration,",
                "    cc_toolchain = toolchain,",
                "    variables_extension = {",
                "        'string_variable': 'foo',",
                "        'list_variable': ['bar', 'baz']",
                "    }",
                ")"
            )
        )
            .containsAtLeast("--my_string=foo", "--my_list_element=bar", "--my_list_element=baz")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testEmptyLinkVariables() {
        Truth.assertThat(
            commandLineForVariables(
                CppActionNames.CPP_LINK_EXECUTABLE,
                "cc_common.create_link_variables(",
                "feature_configuration = feature_configuration,",
                "cc_toolchain = toolchain,",
                "user_link_flags = [ '-foo' ],",
                ")"
            )
        )
            .contains("-foo")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testEmptyLinkVariablesContainSysroot() {
        AnalysisMock.get()
            .ccSupport()
            .setupCcToolchainConfig(
                mockToolsConfig, CcToolchainConfig.builder().withSysroot("/foo/bar/sysroot")
            )
        useConfiguration()
        Truth.assertThat(
            commandLineForVariables(
                CppActionNames.CPP_LINK_EXECUTABLE,
                "cc_common.create_link_variables(",
                "feature_configuration = feature_configuration,",
                "cc_toolchain = toolchain,",
                ")"
            )
        )
            .contains("--sysroot=/foo/bar/sysroot")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testLibrarySearchDirectoriesLinkVariables() {
        AnalysisMock.get()
            .ccSupport()
            .setupCcToolchainConfig(
                mockToolsConfig,
                CcToolchainConfig.builder().withFeatures("library_search_directories")
            )
        useConfiguration()
        Truth.assertThat(
            commandLineForVariables(
                CppActionNames.CPP_LINK_EXECUTABLE,
                "cc_common.create_link_variables(",
                "feature_configuration = feature_configuration,",
                "cc_toolchain = toolchain,",
                "library_search_directories = depset([ 'a', 'b', 'c' ]),",
                ")"
            )
        )
            .containsAtLeast("--library=a", "--library=b", "--library=c")
            .inOrder()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRuntimeLibrarySearchDirectoriesLinkVariables() {
        AnalysisMock.get()
            .ccSupport()
            .setupCcToolchainConfig(
                mockToolsConfig,
                CcToolchainConfig.builder().withFeatures("runtime_library_search_directories")
            )
        useConfiguration()
        Truth.assertThat(
            commandLineForVariables(
                CppActionNames.CPP_LINK_EXECUTABLE,
                "cc_common.create_link_variables(",
                "feature_configuration = feature_configuration,",
                "cc_toolchain = toolchain,",
                "runtime_library_search_directories = depset([ 'a', 'b', 'c' ]),",
                ")"
            )
        )
            .containsAtLeast("--runtime_library=a", "--runtime_library=b", "--runtime_library=c")
            .inOrder()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testUserLinkFlagsLinkVariables() {
        Truth.assertThat(
            commandLineForVariables(
                CppActionNames.CPP_LINK_EXECUTABLE,
                "cc_common.create_link_variables(",
                "feature_configuration = feature_configuration,",
                "cc_toolchain = toolchain,",
                "user_link_flags = [ '-avocado' ],",
                ")"
            )
        )
            .contains("-avocado")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testIfsoRelatedVariablesAreNotExposed() {
        AnalysisMock.get()
            .ccSupport()
            .setupCcToolchainConfig(
                mockToolsConfig, CcToolchainConfig.builder().withFeatures("uses_ifso_variables")
            )
        useConfiguration()
        Truth.assertThat(
            commandLineForVariables(
                CppActionNames.CPP_LINK_DYNAMIC_LIBRARY,
                "cc_common.create_link_variables(",
                "feature_configuration = feature_configuration,",
                "cc_toolchain = toolchain,",
                ")"
            )
        )
            .doesNotContain("--generate_interface_library_was_available")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testOutputFileLinkVariables() {
        Truth.assertThat(
            commandLineForVariables(
                CppActionNames.CPP_LINK_EXECUTABLE,
                "cc_common.create_link_variables(",
                "feature_configuration = feature_configuration,",
                "cc_toolchain = toolchain,",
                "output_file = 'foo/bar/executable',",
                ")"
            )
        )
            .contains("foo/bar/executable")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testParamFileLinkVariables() {
        AnalysisMock.get().ccSupport().setupCcToolchainConfig(mockToolsConfig)
        Truth.assertThat(
            commandLineForVariables(
                CppActionNames.CPP_LINK_EXECUTABLE,
                "cc_common.create_link_variables(",
                "feature_configuration = feature_configuration,",
                "cc_toolchain = toolchain,",
                "param_file = 'foo/bar/params',",
                ")"
            )
        )
            .contains("@foo/bar/params")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testMustKeepDebugLinkVariables() {
        AnalysisMock.get()
            .ccSupport()
            .setupCcToolchainConfig(
                mockToolsConfig, CcToolchainConfig.builder().withFeatures("strip_debug_symbols")
            )

        useConfiguration()
        Truth.assertThat(
            commandLineForVariables(
                CppActionNames.CPP_LINK_EXECUTABLE,
                0,
                "cc_common.create_link_variables(",
                "feature_configuration = feature_configuration,",
                "cc_toolchain = toolchain,",
                "must_keep_debug = False,",
                ")"
            )
        )
            .contains("-strip_stuff")
        Truth.assertThat(
            commandLineForVariables(
                CppActionNames.CPP_LINK_EXECUTABLE,
                1,
                "cc_common.create_link_variables(",
                "feature_configuration = feature_configuration,",
                "cc_toolchain = toolchain,",
                "must_keep_debug = True,",
                ")"
            )
        )
            .doesNotContain("-strip_stuff")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testIsLinkingDynamicLibraryLinkVariables() {
        useConfiguration("--linkopt=-pie")
        Truth.assertThat(
            commandLineForVariables(
                CppActionNames.CPP_LINK_EXECUTABLE,
                0,
                "cc_common.create_link_variables(",
                "feature_configuration = feature_configuration,",
                "cc_toolchain = toolchain,",
                "is_linking_dynamic_library = True,",
                "user_link_flags = [ '-pie' ],",
                ")"
            )
        )
            .doesNotContain("-pie")
        Truth.assertThat(
            commandLineForVariables(
                CppActionNames.CPP_LINK_EXECUTABLE,
                1,
                "cc_common.create_link_variables(",
                "feature_configuration = feature_configuration,",
                "cc_toolchain = toolchain,",
                "is_linking_dynamic_library = False,",
                "user_link_flags = [ '-pie' ],",
                ")"
            )
        )
            .contains("-pie")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testIsUsingLinkerLinkVariables() {
        Truth.assertThat(
            commandLineForVariables(
                CppActionNames.CPP_LINK_EXECUTABLE,
                0,
                "cc_common.create_link_variables(",
                "feature_configuration = feature_configuration,",
                "cc_toolchain = toolchain,",
                "is_using_linker = True,",
                "user_link_flags = [ '-i_dont_want_to_see_this_on_archiver_command_line' ],",
                ")"
            )
        )
            .contains("-i_dont_want_to_see_this_on_archiver_command_line")
        Truth.assertThat(
            commandLineForVariables(
                CppActionNames.CPP_LINK_EXECUTABLE,
                1,
                "cc_common.create_link_variables(",
                "feature_configuration = feature_configuration,",
                "cc_toolchain = toolchain,",
                "is_using_linker = False,",
                "user_link_flags = [ '-i_dont_want_to_see_this_on_archiver_command_line' ],",
                ")"
            )
        )
            .doesNotContain("-i_dont_want_to_see_this_on_archiver_command_line")
    }

    @Throws(java.lang.Exception::class)
    private fun commandLineForVariables(
        actionName: String?,
        vararg variables: String?
    ): net.starlark.java.eval.Sequence<String?>? {
        return commandLineForVariables(actionName, 0, *variables)
    }

    // This method is only there to change the package to fix multiple runs of this method in a single
    // test.
    // TODO(b/109917616): Remove pkgSuffix argument when bzl files are not cached within single test
    @Throws(java.lang.Exception::class)
    private fun commandLineForVariables(
        actionName: String?, pkgSuffix: Int, vararg variables: String?
    ): net.starlark.java.eval.Sequence<String?>? {
        scratch.file(
            "a" + pkgSuffix + "/BUILD",
            "load('@rules_cc//cc/toolchains:cc_toolchain_alias.bzl',"
                    + " 'cc_toolchain_alias')",
            "load(':rule.bzl', 'crule')",
            "cc_toolchain_alias(name='alias')",
            "crule(name='r')"
        )

        scratch.file(
            "a" + pkgSuffix + "/rule.bzl",
            "load('//myinfo:myinfo.bzl', 'MyInfo')",
            "load('@rules_cc//cc/common:cc_common.bzl', 'cc_common')",
            "def _impl(ctx):",
            "  toolchain = ctx.attr._cc_toolchain[cc_common.CcToolchainInfo]",
            "  feature_configuration = cc_common.configure_features(",
            "    ctx = ctx,",
            "    cc_toolchain = toolchain,",
            "    requested_features = ctx.features,",
            "  )",
            "  variables = " + com.google.common.base.Joiner.on("\n").join(variables),
            "  return [MyInfo(",
            "    command_line = cc_common.get_memory_inefficient_command_line(",
            "        feature_configuration = feature_configuration,",
            "        action_name = '" + actionName + "',",
            "        variables = variables))]",
            "crule = rule(",
            "  _impl,",
            "  attrs = { ",
            "    '_cc_toolchain': attr.label(default=Label('//a" + pkgSuffix + ":alias'))",
            "  },",
            "  fragments = ['cpp'],",
            ");"
        )

        /* Calling {@link #getTarget} to get loading errors */
        getTarget("//a" + pkgSuffix + ":r")
        val r: ConfiguredTarget = getConfiguredTarget("//a" + pkgSuffix + ":r")
        if (r == null) {
            return null
        }
        val result: net.starlark.java.eval.Sequence<String?>? =
            getMyInfoFromTarget(r).getValue("command_line") as net.starlark.java.eval.Sequence<String?>?
        return result
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCcCompilationProvider() {
        scratch.file(
            "a/BUILD",
            """
        load("@rules_cc//cc:cc_library.bzl", "cc_library")
        load("//tools/build_defs/cc:rule.bzl", "crule")

        licenses(["notice"])

        cc_library(
            name = "lib",
            srcs = ["lib.cc"],
            hdrs = ["lib.h"],
            deps = ["r"],
        )

        cc_library(
            name = "dep1",
            srcs = ["dep1.cc"],
            hdrs = ["dep1.h"],
            defines = ["DEP1"],
            includes = ["dep1/baz"],
            local_defines = ["LOCALDEP1"],
        )

        cc_library(
            name = "dep2",
            srcs = ["dep2.cc"],
            hdrs = ["dep2.h"],
            defines = ["DEP2"],
            includes = ["dep2/qux"],
        )

        crule(name = "r")
        
        """.trimIndent()
        )
        scratch.overwriteFile("tools/build_defs/cc/BUILD", "")
        scratch.file(
            "tools/build_defs/cc/rule.bzl",
            """
        load("@rules_cc//cc/common:cc_info.bzl", "CcInfo")
        load('@rules_cc//cc/common:cc_common.bzl', 'cc_common')
        load("//myinfo:myinfo.bzl", "MyInfo")

        def _impl(ctx):
            compilation_context = cc_common.create_compilation_context(
                headers = depset([ctx.file._header]),
                direct_textual_headers = [ctx.file._textual_header],
                direct_public_headers = [ctx.file._public_header],
                direct_private_headers = [ctx.file._private_header],
                system_includes = depset([ctx.attr._system_include]),
                includes = depset([ctx.attr._include]),
                quote_includes = depset([ctx.attr._quote_include]),
                framework_includes = depset([ctx.attr._framework_include]),
                defines = depset([ctx.attr._define]),
            )
            cc_infos = [CcInfo(compilation_context = compilation_context)]
            for dep in ctx.attr._deps:
                cc_infos.append(dep[CcInfo])
            merged_cc_info = cc_common.merge_cc_infos(cc_infos = cc_infos)
            return [
                merged_cc_info,
                MyInfo(
                    merged_headers = merged_cc_info.compilation_context.headers,
                    textual_headers = compilation_context.direct_textual_headers,
                    public_headers = compilation_context.direct_public_headers,
                    private_headers = compilation_context.direct_private_headers,
                    merged_system_includes = merged_cc_info.compilation_context.system_includes,
                    merged_includes = merged_cc_info.compilation_context.includes,
                    merged_quote_includes = merged_cc_info.compilation_context.quote_includes,
                    merged_framework_includes =
                        merged_cc_info.compilation_context.framework_includes,
                    merged_defines = merged_cc_info.compilation_context.defines,
                ),
            ]

        crule = rule(
            _impl,
            attrs = {
                "_header": attr.label(
                    allow_single_file = True,
                    default = Label("//a:header.h"),
                ),
                "_textual_header": attr.label(
                    allow_single_file = True,
                    default = Label("//a:textual_header.h"),
                ),
                "_public_header": attr.label(
                    allow_single_file = True,
                    default = Label("//a:public_header.h"),
                ),
                "_private_header": attr.label(
                    allow_single_file = True,
                    default = Label("//a:private_header.h"),
                ),
                "_system_include": attr.string(default = "foo/bar"),
                "_include": attr.string(default = "baz/qux"),
                "_quote_include": attr.string(default = "quux/abc"),
                "_framework_include": attr.string(default = "fuux/fgh"),
                "_define": attr.string(default = "MYDEFINE"),
                "_local_define": attr.string(default = "MYLOCALDEFINE"),
                "_deps": attr.label_list(default = ["//a:dep1", "//a:dep2"]),
            },
            fragments = ["cpp"],
        )
        
        """.trimIndent()
        )

        val lib: ConfiguredTarget = getConfiguredTarget("//a:lib")
        val ccCompilationContext: CcCompilationContext = CcInfo.get(lib).getCcCompilationContext()
        assertThat(
            ccCompilationContext.getDeclaredIncludeSrcs().toList().stream()
                .map(Artifact::getFilename)
                .collect(com.google.common.collect.ImmutableList.toImmutableList<E?>())
        )
            .containsExactly("lib.h", "header.h", "dep1.h", "dep2.h")

        val myInfo: StructImpl = getMyInfoFromTarget(getConfiguredTarget("//a:r"))

        val mergedHeaders: MutableList<Artifact?> =
            (myInfo.getValue("merged_headers") as Depset).getSet(Artifact::class.java).toList()
        Truth.assertThat(
            mergedHeaders.stream()
                .map<Any?>(Artifact::getFilename)
                .collect(com.google.common.collect.ImmutableList.toImmutableList<Any?>())
        )
            .containsAtLeast("header.h", "dep1.h", "dep2.h")

        val textualHeaders: MutableList<Artifact?> =
            (myInfo.getValue("textual_headers") as net.starlark.java.eval.Sequence<Artifact?>).getImmutableList()
        Truth.assertThat(
            textualHeaders.stream()
                .map<Any?>(Artifact::getFilename)
                .collect(com.google.common.collect.ImmutableList.toImmutableList<Any?>())
        )
            .contains("textual_header.h")
        val publicHeaders: MutableList<Artifact?> =
            (myInfo.getValue("public_headers") as net.starlark.java.eval.Sequence<Artifact?>).getImmutableList()
        Truth.assertThat(
            publicHeaders.stream()
                .map<Any?>(Artifact::getFilename)
                .collect(com.google.common.collect.ImmutableList.toImmutableList<Any?>())
        )
            .contains("public_header.h")
        val privateHeaders: MutableList<Artifact?> =
            (myInfo.getValue("private_headers") as net.starlark.java.eval.Sequence<Artifact?>).getImmutableList()
        Truth.assertThat(
            privateHeaders.stream()
                .map<Any?>(Artifact::getFilename)
                .collect(com.google.common.collect.ImmutableList.toImmutableList<Any?>())
        )
            .contains("private_header.h")

        val mergedDefines: MutableList<String?>? =
            (myInfo.getValue("merged_defines") as Depset).getSet(String::class.java).toList()
        Truth.assertThat(mergedDefines).containsAtLeast("MYDEFINE", "DEP1", "DEP2")
        Truth.assertThat(mergedDefines).doesNotContain("LOCALDEP1")

        val mergedSystemIncludes: MutableList<String?>? =
            (myInfo.getValue("merged_system_includes") as Depset).getSet(String::class.java).toList()
        Truth.assertThat(mergedSystemIncludes).contains("foo/bar")

        val mergedIncludes: MutableList<String?>? =
            (myInfo.getValue("merged_includes") as Depset).getSet(String::class.java).toList()
        Truth.assertThat(mergedIncludes).containsAtLeast("baz/qux", "a/dep1/baz", "a/dep2/qux")

        val mergedQuoteIncludes: MutableList<String?>? =
            (myInfo.getValue("merged_quote_includes") as Depset).getSet(String::class.java).toList()
        Truth.assertThat(mergedQuoteIncludes).contains("quux/abc")

        val mergedFrameworkIncludes: MutableList<String?>? =
            (myInfo.getValue("merged_framework_includes") as Depset).getSet(String::class.java).toList()
        Truth.assertThat(mergedFrameworkIncludes).contains("fuux/fgh")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCcCompilationProviderDefaultValues() {
        scratch.file(
            "a/BUILD",
            """
        load("//tools/build_defs/cc:rule.bzl", "crule")

        licenses(["notice"])

        crule(name = "r")
        
        """.trimIndent()
        )
        scratch.overwriteFile("tools/build_defs/cc/BUILD", "")
        scratch.file(
            "tools/build_defs/cc/rule.bzl",
            """
        load('@rules_cc//cc/common:cc_common.bzl', 'cc_common')
        def _impl(ctx):
            compilation_context = cc_common.create_compilation_context()

        crule = rule(
            _impl,
            fragments = ["cpp"],
        )
        
        """.trimIndent()
        )

        assertThat(getConfiguredTarget("//a:r")).isNotNull()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCcCompilationProviderInvalidValues() {
        reporter.removeHandler(failFastHandler)
        scratch.file(
            "a/BUILD",
            """
        load("//tools/build_defs/cc:rule.bzl", "crule")

        licenses(["notice"])

        crule(name = "r")
        
        """.trimIndent()
        )
        scratch.overwriteFile("tools/build_defs/cc/BUILD", "")
        scratch.file(
            "tools/build_defs/cc/rule.bzl",
            """
        load('@rules_cc//cc/common:cc_common.bzl', 'cc_common')
        def _impl(ctx):
            compilation_context = cc_common.create_compilation_context(headers = [])

        crule = rule(
            _impl,
            fragments = ["cpp"],
        )
        
        """.trimIndent()
        )

        getConfiguredTarget("//a:r")
        assertContainsEvent("for headers, got list, want a depset of File")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCreateCompilationOutputs_invalidDepset() {
        reporter.removeHandler(failFastHandler)
        scratch.file(
            "test/BUILD",
            """
        load(":my_rule.bzl", "my_rule")

        my_rule(name = "x")
        
        """.trimIndent()
        )
        scratch.file(
            "test/my_rule.bzl",
            """
        load('@rules_cc//cc/common:cc_common.bzl', 'cc_common')
        def _impl(ctx):
            cc_common.create_compilation_outputs(
                objects = depset([1, 2]),
                pic_objects = depset([1, 2]),
            )

        my_rule = rule(
            _impl,
        )
        
        """.trimIndent()
        )

        assertThat(getConfiguredTarget("//test:x")).isNull()
        assertContainsEvent("for 'objects', got a depset of 'int', expected a depset of 'File'")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCreateCompilationOutputs_empty() {
        scratch.file(
            "test/BUILD",
            """
        load(":my_rule.bzl", "my_rule")

        my_rule(name = "x")
        
        """.trimIndent()
        )
        scratch.file(
            "test/my_rule.bzl",
            """
        load('@rules_cc//cc/common:cc_common.bzl', 'cc_common')
        def _impl(ctx):
            cc_common.create_compilation_outputs()

        my_rule = rule(
            _impl,
        )
        
        """.trimIndent()
        )

        assertThat(getConfiguredTarget("//test:x")).isNotNull()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCreateLtoCompilationContextIsPrivate() {
        scratch.file(
            "test/BUILD",
            """
        load(":my_rule.bzl", "my_rule")

        my_rule(name = "x")
        
        """.trimIndent()
        )
        scratch.file(
            "test/my_rule.bzl",
            """
        load('@rules_cc//cc/common:cc_common.bzl', 'cc_common')
        def _impl(ctx):
            obj = ctx.actions.declare_file("foo.o")
            thin_link_obj = ctx.actions.declare_file("foo.indexing.o")
            ctx.actions.write(obj, "this string is not a valid object file")
            ctx.actions.write(thin_link_obj, "this string is not a valid thin link object file")
            cc_common.create_lto_compilation_context(objects = {obj: (thin_link_obj, ["-O3"])})

        my_rule = rule(
            _impl,
        )
        
        """.trimIndent()
        )
        val e: java.lang.AssertionError? = org.junit.Assert.assertThrows<java.lang.AssertionError?>(
            java.lang.AssertionError::class.java,
            org.junit.function.ThrowingRunnable { getConfiguredTarget("//test:x") })
        Truth.assertThat(e).hasMessageThat().contains("cannot use private API")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCreateLtoCompilationContext() {
        val rustPrefix = "third_party/bazel_rules/rules_rust/rust/private"
        scratch.file(
            rustPrefix + "/BUILD",
            """
        load(":my_rule.bzl", "my_rule")

        my_rule(name = "x")
        
        """.trimIndent()
        )
        scratch.file(
            rustPrefix + "/my_rule.bzl",
            """
        load('@rules_cc//cc/common:cc_common.bzl', 'cc_common')
        def _impl(ctx):
            obj = ctx.actions.declare_file("foo.o")
            thin_link_obj = ctx.actions.declare_file("foo.indexing.o")
            ctx.actions.write(obj, "this string is not a valid object file")
            ctx.actions.write(thin_link_obj, "this string is not a valid thin link object file")
            cc_common.create_lto_compilation_context(objects = {obj: (thin_link_obj, ["-O3"])})

        my_rule = rule(
            _impl,
        )
        
        """.trimIndent()
        )
        getConfiguredTarget("//" + rustPrefix + ":x")
        assertNoEvents()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCcLinkingContextOnWindows() {
        if (!AnalysisMock.get().isThisBazel()) {
            return
        }
        AnalysisMock.get()
            .ccSupport()
            .setupCcToolchainConfig(
                mockToolsConfig,
                CcToolchainConfig.builder()
                    .withFeatures(
                        CppRuleClasses.COPY_DYNAMIC_LIBRARIES_TO_BINARY,
                        CppRuleClasses.TARGETS_WINDOWS,
                        CppRuleClasses.SUPPORTS_DYNAMIC_LINKER
                    )
            )
        doTestCcLinkingContext(
            com.google.common.collect.ImmutableList.of<String?>(
                "a.a",
                "libdep2.a",
                "b.rlib",
                "c.a",
                "d.a",
                "libdep1.a"
            ),
            com.google.common.collect.ImmutableList.of<String?>(
                "a.pic.a",
                "b.rlib",
                "c.pic.a",
                "e.pic.a"
            ),  // The suffix of dynamic library is calculated based on repository name and package path
            // to avoid conflicts with dynamic library from other packages.
            com.google.common.collect.ImmutableList.of<String?>(
                "a.so",
                "libdep2_61.so",
                "b.so",
                "e.so",
                "libdep1_61.so"
            )
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCcLinkingContext() {
        AnalysisMock.get()
            .ccSupport()
            .setupCcToolchainConfig(
                mockToolsConfig,
                CcToolchainConfig.builder()
                    .withFeatures(
                        CppRuleClasses.PIC,
                        CppRuleClasses.SUPPORTS_PIC,
                        CppRuleClasses.SUPPORTS_DYNAMIC_LINKER
                    )
            )
        val picStaticLibraries: com.google.common.collect.ImmutableList<String?> =
            com.google.common.collect.ImmutableList.of<String?>(
                "a.pic.a",
                "b.rlib",
                "e.pic.a",
                "libdep1.a",
                "c.pic.a",
                "libdep2.a"
            )
        doTestCcLinkingContext(
            com.google.common.collect.ImmutableList.of<String?>("a.a", "b.rlib", "c.a", "d.a"),
            picStaticLibraries,
            com.google.common.collect.ImmutableList.of<String?>(
                "a.so",
                "liba_Slibdep2.so",
                "b.so",
                "e.so",
                "liba_Slibdep1.so"
            )
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCcLinkingContextForExperimentalCcSharedLibrary() {
        AnalysisMock.get()
            .ccSupport()
            .setupCcToolchainConfig(
                mockToolsConfig,
                CcToolchainConfig.builder()
                    .withFeatures(
                        CppRuleClasses.PIC,
                        CppRuleClasses.SUPPORTS_PIC,
                        CppRuleClasses.SUPPORTS_DYNAMIC_LINKER
                    )
            )
        val picStaticLibraries: com.google.common.collect.ImmutableList<String?> =
            com.google.common.collect.ImmutableList.of<String?>(
                "a.pic.a",
                "libdep2.a",
                "b.rlib",
                "c.pic.a",
                "e.pic.a",
                "libdep1.a"
            )
        doTestCcLinkingContext(
            com.google.common.collect.ImmutableList.of<String?>("a.a", "b.rlib", "c.a", "d.a"),
            picStaticLibraries,
            com.google.common.collect.ImmutableList.of<String?>(
                "a.so",
                "liba_Slibdep2.so",
                "b.so",
                "e.so",
                "liba_Slibdep1.so"
            )
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSolibLinkDefault() {
        setUpCcLinkingContextTest()
        scratch.file(
            "foo/BUILD",
            """
        load("@rules_cc//cc:cc_binary.bzl", "cc_binary")
        load("//tools/build_defs/cc:rule.bzl", "crule")

        cc_binary(
            name = "bin",
            deps = [":a"],
        )

        crule(
            name = "a",
            dynamic_library = "a.so",
            interface_library = "a.ifso",
        )
        
        """.trimIndent()
        )
        val a: ConfiguredTarget = getConfiguredTarget("//foo:a")
        val ruleContext: RuleContext = getRuleContext(a)
        val toolchain: CcToolchainProvider = CppHelper.getToolchain(ruleContext)
        val info: StructImpl = (getMyInfoFromTarget(a).getValue("info") as StructImpl)
        val librariesToLink: Depset = info.getValue("libraries_to_link", Depset::class.java)
        val dynamicLibSolibRelativePathsBuilder: com.google.common.collect.ImmutableList.Builder<String?> =
            com.google.common.collect.ImmutableList.builder<String?>()
        val interfaceLibSolibRelativePathsBuilder: com.google.common.collect.ImmutableList.Builder<String?> =
            com.google.common.collect.ImmutableList.builder<String?>()
        for (libraryToLinkStr in librariesToLink.toList(StarlarkInfo::class.java)) {
            val libraryToLink: LibraryToLink = LibraryToLink.wrap(libraryToLinkStr)
            if (libraryToLink.getDynamicLibrary() != null) {
                dynamicLibSolibRelativePathsBuilder.add(
                    getSolibRelativePath(libraryToLink.getDynamicLibrary(), toolchain)
                )
            }
            if (libraryToLink.getInterfaceLibrary() != null) {
                interfaceLibSolibRelativePathsBuilder.add(
                    getSolibRelativePath(libraryToLink.getInterfaceLibrary(), toolchain)
                )
            }
        }
        Truth.assertThat(dynamicLibSolibRelativePathsBuilder.build())
            .containsExactly("_U_S_Sfoo_Ca___Ufoo/a.so")
        Truth.assertThat(interfaceLibSolibRelativePathsBuilder.build())
            .containsExactly("_U_S_Sfoo_Ca___Ufoo/a.ifso")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSolibLinkCustom() {
        setUpCcLinkingContextTest()
        scratch.file(
            "foo/BUILD",
            """
        load("@rules_cc//cc:cc_binary.bzl", "cc_binary")
        load("//tools/build_defs/cc:rule.bzl", "crule")

        cc_binary(
            name = "bin",
            deps = [":a"],
        )

        crule(
            name = "a",
            dynamic_library = "a.so",
            dynamic_library_symlink_path = "custom/libcustom.so",
            interface_library = "a.ifso",
            interface_library_symlink_path = "libcustom.ifso",
        )
        
        """.trimIndent()
        )
        val a: ConfiguredTarget = getConfiguredTarget("//foo:a")
        val ruleContext: RuleContext = getRuleContext(a)
        val toolchain: CcToolchainProvider = CppHelper.getToolchain(ruleContext)
        val info: StructImpl = (getMyInfoFromTarget(a).getValue("info") as StructImpl)
        val librariesToLink: Depset = info.getValue("libraries_to_link", Depset::class.java)
        val solibDir: String? = toolchain.getSolibDirectory()
        assertThat(
            librariesToLink.toList(StarlarkInfo::class.java).stream()
                .map(LibraryToLink::wrap)
                .filter({ x -> x.getDynamicLibrary() != null })
                .map(
                    { x ->
                        x.getDynamicLibrary()
                            .getRootRelativePath()
                            .relativeTo(solibDir)
                            .toString()
                    })
        )
            .containsExactly("custom/libcustom.so")
        assertThat(
            librariesToLink.toList(StarlarkInfo::class.java).stream()
                .map(LibraryToLink::wrap)
                .filter({ x -> x.getInterfaceLibrary() != null })
                .map(
                    { x ->
                        x.getInterfaceLibrary()
                            .getRootRelativePath()
                            .relativeTo(solibDir)
                            .toString()
                    })
        )
            .containsExactly("libcustom.ifso")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testReallyLongSolibLink() {
        setUpCcLinkingContextTest()

        val longpath =
            ("this/is/a/really/really/really/really/really/really/really/really/really/really/"
                    + "really/really/really/really/really/really/really/really/really/really/really/"
                    + "really/really/long/path/that/generates/really/long/solib/link/path")
        scratch.file(
            longpath + "/BUILD",
            "load('//tools/build_defs/cc:rule.bzl', 'crule')",
            "crule(name='a',",
            "   dynamic_library = 'a.so',",
            ")"
        )

        val a: ConfiguredTarget = getConfiguredTarget("//" + longpath + ":a")
        val info: StructImpl = (getMyInfoFromTarget(a).getValue("info") as StructImpl)
        val librariesToLink: Depset = info.getValue("libraries_to_link", Depset::class.java)
        val dynamicLibraryParentDirectories: com.google.common.collect.ImmutableList<String> =
            librariesToLink.toList(StarlarkInfo::class.java).stream()
                .map(LibraryToLink::wrap)
                .filter({ x -> x.getDynamicLibrary() != null })
                .map(
                    { x -> x.getDynamicLibrary().getRootRelativePath().getParentDirectory().getBaseName() })
                .collect(com.google.common.collect.ImmutableList.toImmutableList<E?>())
        for (dynamicLibraryParentDirectory in dynamicLibraryParentDirectories) {
            Truth.assertThat(dynamicLibraryParentDirectory.length).isLessThan(MAX_FILENAME_LENGTH + 1)
        }
    }

    @Throws(java.lang.Exception::class)
    private fun doTestCcLinkingContext(
        staticLibraryList: MutableList<String?>,
        picStaticLibraryList: MutableList<String?>,
        dynamicLibraryList: MutableList<String?>
    ) {
        useConfiguration("--features=-supports_interface_shared_libraries")
        setUpCcLinkingContextTest()
        val a: ConfiguredTarget = getConfiguredTarget("//a:a")

        val info: StructImpl = (getMyInfoFromTarget(a).getValue("info") as StructImpl)
        val userLinkFlags: net.starlark.java.eval.Sequence<String?> =
            info.getValue(
                "user_link_flags",
                net.starlark.java.eval.Sequence::class.java
            ) as net.starlark.java.eval.Sequence<String?>
        Truth.assertThat(userLinkFlags.getImmutableList())
            .containsExactly("-la", "-lc2", "-DEP2_LINKOPT", "-lc1", "-lc2", "-DEP1_LINKOPT")
        val additionalInputs: Depset = info.getValue("additional_inputs", Depset::class.java)
        assertThat(additionalInputs.toList(Artifact::class.java).stream().map(Artifact::getFilename))
            .containsAtLeast("b.lds", "d.lds") // On Windows also .def files
        val linkstamps: Depset = info.getValue("linkstamps", Depset::class.java)
        Truth.assertThat(
            artifactsToStrings(
                linkstamps.toList(StarlarkInfo::class.java).stream()
                    .map({ linkstamp: StarlarkInfo -> getLinkstampFile(linkstamp) })
                    .collect(com.google.common.collect.ImmutableList.toImmutableList<E?>())
            )
        )
            .containsExactly("src a/linkstamp.cc")
        val librariesToLink: com.google.common.collect.ImmutableList<StarlarkInfo?> =
            info.getValue("libraries_to_link", Depset::class.java).toList(StarlarkInfo::class.java)
        Truth.assertThat(
            librariesToLink.stream()
                .map<Any?>(LibraryToLink::wrap)
                .filter { x: Any? -> x.getStaticLibrary() != null }
                .map<Any?> { x: Any? -> x.getStaticLibrary().getFilename() })
            .containsExactlyElementsIn(staticLibraryList)
        Truth.assertThat(
            librariesToLink.stream()
                .map<Any?>(LibraryToLink::wrap)
                .filter { x: Any? -> x.getPicStaticLibrary() != null }
                .map<Any?> { x: Any? -> x.getPicStaticLibrary().getFilename() })
            .containsExactlyElementsIn(picStaticLibraryList)
        Truth.assertThat(
            librariesToLink.stream()
                .map<Any?>(LibraryToLink::wrap)
                .filter { x: Any? -> x.getDynamicLibrary() != null }
                .map<Any?> { x: Any? -> x.getDynamicLibrary().getFilename() })
            .containsExactlyElementsIn(dynamicLibraryList)
        Truth.assertThat(
            librariesToLink.stream()
                .map<Any?>(LibraryToLink::wrap)
                .filter { x: Any? -> x.getInterfaceLibrary() != null }
                .map<Any?> { x: Any? -> x.getInterfaceLibrary().getFilename() })
            .containsExactly("a.ifso")
        val staticLibrary: Artifact = info.getValue("static_library", Artifact::class.java)
        assertThat(staticLibrary.getFilename()).isEqualTo("a.a")
        val picStaticLibrary: Artifact = info.getValue("pic_static_library", Artifact::class.java)
        assertThat(picStaticLibrary.getFilename()).isEqualTo("a.pic.a")
        val dynamicLibrary: Artifact = info.getValue("dynamic_library", Artifact::class.java)
        assertThat(dynamicLibrary.getFilename()).isEqualTo("a.so")
        val interfaceLibrary: Artifact = info.getValue("interface_library", Artifact::class.java)
        assertThat(interfaceLibrary.getFilename()).isEqualTo("a.ifso")
        val alwayslink: Boolean = info.getValue("alwayslink", Boolean::class.java)
        Truth.assertThat(alwayslink).isTrue()

        val bin: ConfiguredTarget = getConfiguredTarget("//a:bin")
        assertThat(bin).isNotNull()
    }

    @Throws(java.lang.Exception::class)
    private fun setUpCcLinkingContextTest() {
        scratch.file(
            "a/BUILD",
            """
        load("@rules_cc//cc:cc_binary.bzl", "cc_binary")
        load("@rules_cc//cc:cc_library.bzl", "cc_library")
        load("@rules_cc//cc/toolchains:cc_toolchain_alias.bzl", "cc_toolchain_alias")
        load("//tools/build_defs/cc:rule.bzl", "crule")

        cc_binary(
            name = "bin",
            deps = [":a"],
        )

        crule(
            name = "a",
            dynamic_library = "a.so",
            interface_library = "a.ifso",
            pic_static_library = "a.pic.a",
            static_library = "a.a",
            user_link_flags = [
                "-la",
                "-lc2",
            ],
            deps = [
                ":b",
                ":c",
                ":dep2",
            ],
            alwayslink = True,
        )

        crule(
            name = "b",
            additional_inputs = ["b.lds"],
            dynamic_library = "b.so",
            pic_static_library = "b.rlib",
            static_library = "b.rlib",
            deps = [
                ":c",
                ":d",
            ],
        )

        crule(
            name = "c",
            pic_static_library = "c.pic.a",
            static_library = "c.a",
            user_link_flags = [
                "-lc1",
                "-lc2",
            ],
        )

        crule(
            name = "d",
            additional_inputs = ["d.lds"],
            static_library = "d.a",
            deps = [":e"],
            alwayslink = True,
        )

        crule(
            name = "e",
            dynamic_library = "e.so",
            pic_static_library = "e.pic.a",
            deps = [":dep1"],
        )

        cc_toolchain_alias(name = "alias")

        cc_library(
            name = "dep1",
            srcs = ["dep1.cc"],
            hdrs = ["dep1.h"],
            linkopts = ["-DEP1_LINKOPT"],
            linkstamp = "linkstamp.cc",
        )

        cc_library(
            name = "dep2",
            srcs = ["dep2.cc"],
            hdrs = ["dep2.h"],
            linkopts = ["-DEP2_LINKOPT"],
        )
        
        """.trimIndent()
        )
        scratch.file("a/lib.a", "")
        scratch.file("a/lib.so", "")
        scratch.overwriteFile("tools/build_defs/cc/BUILD", "")
        scratch.file(
            "tools/build_defs/cc/rule.bzl",
            "load('//myinfo:myinfo.bzl', 'MyInfo')",
            ("load('"
                    + TestConstants.RULES_CC
                    + ":find_cc_toolchain.bzl', 'find_cc_toolchain', 'use_cc_toolchain')"),
            "load('@rules_cc//cc/common:cc_info.bzl', 'CcInfo')",
            "load('@rules_cc//cc/common:cc_common.bzl', 'cc_common')",
            "linker_input = cc_common.create_linker_input(",
            "                 owner=Label('//toplevel'),",
            "                 user_link_flags=[['-first_flag'], ['-second_flag']])",
            "top_linking_context_smoke = cc_common.create_linking_context(",
            "   linker_inputs=depset([linker_input]))",
            "def _create(ctx, feature_configuration, static_library, pic_static_library,",
            "  dynamic_library,",
            "  interface_library, dynamic_library_symlink_path, interface_library_symlink_path,",
            "  alwayslink, objects, pic_objects):",
            "  return cc_common.create_library_to_link(",
            "    actions=ctx.actions, feature_configuration=feature_configuration, ",
            "    cc_toolchain = ctx.attr._cc_toolchain[cc_common.CcToolchainInfo], ",
            "    static_library=static_library, pic_static_library=pic_static_library,",
            "    dynamic_library=dynamic_library, interface_library=interface_library,",
            "    dynamic_library_symlink_path=dynamic_library_symlink_path,",
            "    interface_library_symlink_path=interface_library_symlink_path,",
            "    alwayslink=alwayslink, ",
            "    )",
            "def _impl(ctx):",
            "  toolchain = find_cc_toolchain(ctx)",
            "  feature_configuration = cc_common.configure_features(",
            "    ctx = ctx,",
            "    cc_toolchain = toolchain,",
            "  )",
            "  library_to_link = _create(ctx, feature_configuration, ctx.file.static_library, ",
            "     ctx.file.pic_static_library, ctx.file.dynamic_library, ctx.file.interface_library,",
            "     ctx.attr.dynamic_library_symlink_path,",
            "     ctx.attr.interface_library_symlink_path,",
            "     ctx.attr.alwayslink, ctx.files.objects, ctx.files.pic_objects)",
            "  linker_input = cc_common.create_linker_input(",
            "                   owner=ctx.label,",
            "                   libraries=depset([library_to_link]),",
            "                   user_link_flags=depset(ctx.attr.user_link_flags),",
            "                   additional_inputs=depset(ctx.files.additional_inputs))",
            "  linking_context = cc_common.create_linking_context(",
            "     linker_inputs=depset([linker_input]))",
            "  cc_infos = [CcInfo(linking_context=linking_context)]",
            "  for dep in ctx.attr.deps:",
            "      cc_infos.append(dep[CcInfo])",
            "  merged_cc_info = cc_common.merge_cc_infos(cc_infos=cc_infos)",
            "  merged_libraries = []",
            "  merged_additional_inputs = []",
            "  merged_user_link_flags = []",
            "  merged_linkstamps = []",
            "  for l in merged_cc_info.linking_context.linker_inputs.to_list():",
            "      merged_libraries.extend(l.libraries)",
            "      merged_additional_inputs.extend(l.additional_inputs)",
            "      merged_user_link_flags.extend(l.user_link_flags)",
            "      merged_linkstamps.extend(l.linkstamps)",
            "  linkstamps_linker_input = cc_common.create_linker_input(",
            "                   owner=ctx.label,",
            "                   linkstamps=depset(merged_linkstamps))",
            "  return [",
            "     MyInfo(",
            "         info = struct(",
            "             cc_info = merged_cc_info,",
            "             user_link_flags = merged_user_link_flags,",
            "             additional_inputs = depset(merged_additional_inputs),",
            "             libraries_to_link = depset(merged_libraries),",
            "             linkstamps = depset(linkstamps_linker_input.linkstamps),",
            "             static_library = library_to_link.static_library,",
            "             pic_static_library = library_to_link.pic_static_library,",
            "             dynamic_library = library_to_link.dynamic_library,",
            "             interface_library = library_to_link.interface_library,",
            "             alwayslink = library_to_link.alwayslink,",
            "             objects = library_to_link.objects,",
            "             pic_objects = library_to_link.pic_objects),",
            "      ),",
            "      merged_cc_info]",
            "crule = rule(",
            "  _impl,",
            "  attrs = { ",
            "    'user_link_flags' : attr.string_list(),",
            "    'additional_inputs': attr.label_list(allow_files=True),",
            "    'static_library': attr.label(allow_single_file=True),",
            "    'pic_static_library': attr.label(allow_single_file=True),",
            "    'dynamic_library': attr.label(allow_single_file=True),",
            "    'dynamic_library_symlink_path': attr.string(),",
            "    'interface_library': attr.label(allow_single_file=True),",
            "    'interface_library_symlink_path': attr.string(),",
            "    'objects': attr.label_list(allow_files=True),",
            "    'pic_objects': attr.label_list(allow_files=True),",
            "    'alwayslink': attr.bool(),",
            "    '_cc_toolchain': attr.label(default=Label('//a:alias')),",
            "    'deps': attr.label_list(),",
            "  },",
            "  fragments = ['cpp'],",
            "  toolchains = use_cc_toolchain()",
            ");"
        )
    }

    @Throws(IOException::class)
    private fun loadCcToolchainConfigLib() {
        scratch.appendFile("tools/cpp/BUILD", "")
        scratch.overwriteFile(
            "tools/cpp/cc_toolchain_config_lib.bzl",
            com.google.devtools.build.lib.packages.util.ResourceLoader.readFromResources(
                TestConstants.RULES_CC_REPOSITORY_EXECROOT + "cc/cc_toolchain_config_lib.bzl"
            )
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testVariableWithValue() {
        loadCcToolchainConfigLib()
        createVariableWithValueRule("one",  /* name= */"None",  /* value= */"None")

        var e: java.lang.AssertionError? = org.junit.Assert.assertThrows<java.lang.AssertionError?>(
            java.lang.AssertionError::class.java,
            org.junit.function.ThrowingRunnable { getConfiguredTarget("//one:a") })
        Truth.assertThat(e)
            .hasMessageThat()
            .contains("name parameter of variable_with_value should be a string, found NoneType")

        createVariableWithValueRule("two",  /* name= */"'abc'",  /* value= */"None")

        e = org.junit.Assert.assertThrows<java.lang.AssertionError?>(
            java.lang.AssertionError::class.java,
            org.junit.function.ThrowingRunnable { getConfiguredTarget("//two:a") })
        Truth.assertThat(e)
            .hasMessageThat()
            .contains("value parameter of variable_with_value should be a string, found NoneType")

        createVariableWithValueRule("three",  /* name= */"''",  /* value= */"None")

        e = org.junit.Assert.assertThrows<java.lang.AssertionError?>(
            java.lang.AssertionError::class.java,
            org.junit.function.ThrowingRunnable { getConfiguredTarget("//three:a") })
        Truth.assertThat(e)
            .hasMessageThat()
            .contains("name parameter of variable_with_value must be a nonempty string")

        createVariableWithValueRule("four",  /* name= */"'abc'",  /* value= */"''")

        e = org.junit.Assert.assertThrows<java.lang.AssertionError?>(
            java.lang.AssertionError::class.java,
            org.junit.function.ThrowingRunnable { getConfiguredTarget("//four:a") })
        Truth.assertThat(e)
            .hasMessageThat()
            .contains("value parameter of variable_with_value must be a nonempty string")

        createVariableWithValueRule("five",  /* name= */"'abc'",  /* value= */"'def'")

        var t: ConfiguredTarget = getConfiguredTarget("//five:a")
        val variable: StarlarkInfo? = getMyInfoFromTarget(t).getValue("variable") as StarlarkInfo?
        assertThat(variable).isNotNull()
        val v: VariableWithValue = variableWithValueFromStarlark(variable)
        assertThat(v).isNotNull()
        assertThat(v.variable).isEqualTo("abc")
        assertThat(v.value).isEqualTo("def")

        createEnvEntryRule("six",  /* key= */"'abc'",  /* value= */"'def'")
        t = getConfiguredTarget("//six:a")
        val envEntry: StarlarkInfo? = getMyInfoFromTarget(t).getValue("entry") as StarlarkInfo?
        val ee: net.starlark.java.eval.EvalException? =
            org.junit.Assert.assertThrows<net.starlark.java.eval.EvalException?>(
                net.starlark.java.eval.EvalException::class.java,
                org.junit.function.ThrowingRunnable { variableWithValueFromStarlark(envEntry) })
        Truth.assertThat(ee)
            .hasMessageThat()
            .contains("Expected object of type 'variable_with_value', received 'env_entry")
    }

    @Throws(IOException::class)
    private fun createVariableWithValueRule(pkg: String?, name: String?, value: String?) {
        scratch.file(
            pkg + "/foo.bzl",
            "load('//tools/cpp:cc_toolchain_config_lib.bzl', 'variable_with_value')",
            "load('//myinfo:myinfo.bzl', 'MyInfo')",
            "def _impl(ctx):",
            "   return [MyInfo(variable = variable_with_value(",
            "       name = " + name + ",",
            "       value = " + value + "))]",
            "crule = rule(implementation = _impl)"
        )
        scratch.file(pkg + "/BUILD", "load(':foo.bzl', 'crule')", "crule(name = 'a')")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCustomVariableWithValue_none_none() {
        loadCcToolchainConfigLib()
        createCustomVariableWithValueRule("one",  /* name= */"None",  /* value= */"None")
        val t: ConfiguredTarget = getConfiguredTarget("//one:a")
        val variable: StarlarkInfo? = getMyInfoFromTarget(t).getValue("variable") as StarlarkInfo?
        val e: net.starlark.java.eval.EvalException? =
            org.junit.Assert.assertThrows<net.starlark.java.eval.EvalException?>(
                net.starlark.java.eval.EvalException::class.java,
                org.junit.function.ThrowingRunnable { variableWithValueFromStarlark(variable) })
        Truth.assertThat(e)
            .hasMessageThat()
            .contains("'name' parameter of variable_with_value must be a nonempty string.")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCustomVariableWithValue_string_none() {
        loadCcToolchainConfigLib()
        createCustomVariableWithValueRule("two",  /* name= */"'abc'",  /* value= */"None")

        val t: ConfiguredTarget = getConfiguredTarget("//two:a")
        val variable: StarlarkInfo? = getMyInfoFromTarget(t).getValue("variable") as StarlarkInfo?
        val e: net.starlark.java.eval.EvalException? =
            org.junit.Assert.assertThrows<net.starlark.java.eval.EvalException?>(
                net.starlark.java.eval.EvalException::class.java,
                org.junit.function.ThrowingRunnable { variableWithValueFromStarlark(variable) })
        Truth.assertThat(e)
            .hasMessageThat()
            .contains("'value' parameter of variable_with_value must be a nonempty string.")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCustomVariableWithValue_string_struct() {
        loadCcToolchainConfigLib()
        createCustomVariableWithValueRule("three",  /* name= */"'abc'",  /* value= */"struct()")

        val t: ConfiguredTarget = getConfiguredTarget("//three:a")
        val variable: StarlarkInfo? = getMyInfoFromTarget(t).getValue("variable") as StarlarkInfo?
        val e: net.starlark.java.eval.EvalException? =
            org.junit.Assert.assertThrows<net.starlark.java.eval.EvalException?>(
                net.starlark.java.eval.EvalException::class.java,
                org.junit.function.ThrowingRunnable { variableWithValueFromStarlark(variable) })
        Truth.assertThat(e).hasMessageThat().contains("Field 'value' is not of 'java.lang.String' type.")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCustomVariableWithValue_boolean_string() {
        loadCcToolchainConfigLib()
        createCustomVariableWithValueRule("four",  /* name= */"True",  /* value= */"'abc'")

        val t: ConfiguredTarget = getConfiguredTarget("//four:a")
        val variable: StarlarkInfo? = getMyInfoFromTarget(t).getValue("variable") as StarlarkInfo?
        val e: net.starlark.java.eval.EvalException? =
            org.junit.Assert.assertThrows<net.starlark.java.eval.EvalException?>(
                net.starlark.java.eval.EvalException::class.java,
                org.junit.function.ThrowingRunnable { variableWithValueFromStarlark(variable) })
        Truth.assertThat(e).hasMessageThat().contains("Field 'name' is not of 'java.lang.String' type.")
    }

    @Throws(IOException::class)
    private fun createCustomVariableWithValueRule(pkg: String?, name: String?, value: String?) {
        scratch.file(
            pkg + "/foo.bzl",
            "load('//myinfo:myinfo.bzl', 'MyInfo')",
            "def _impl(ctx):",
            "   return [MyInfo(variable = struct(",
            "       name = " + name + ",",
            "       value = " + value + ",",
            "       type_name = 'variable_with_value'))]",
            "crule = rule(implementation = _impl)"
        )
        scratch.file(pkg + "/BUILD", "load(':foo.bzl', 'crule')", "crule(name = 'a')")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testEnvEntry_none_none() {
        loadCcToolchainConfigLib()
        createEnvEntryRule("one", "None",  /* value= */"None")

        val e: java.lang.AssertionError? = org.junit.Assert.assertThrows<java.lang.AssertionError?>(
            java.lang.AssertionError::class.java,
            org.junit.function.ThrowingRunnable { getConfiguredTarget("//one:a") })
        Truth.assertThat(e)
            .hasMessageThat()
            .contains("key parameter of env_entry should be a string, found NoneType")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testEnvEntry_string_none() {
        loadCcToolchainConfigLib()
        createEnvEntryRule("two", "'abc'",  /* value= */"None")

        val e: java.lang.AssertionError? = org.junit.Assert.assertThrows<java.lang.AssertionError?>(
            java.lang.AssertionError::class.java,
            org.junit.function.ThrowingRunnable { getConfiguredTarget("//two:a") })
        Truth.assertThat(e)
            .hasMessageThat()
            .contains("value parameter of env_entry should be a string, found NoneType")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testEnvEntry_emptyString_none() {
        loadCcToolchainConfigLib()
        createEnvEntryRule("three", "''",  /* value= */"None")

        val e: java.lang.AssertionError? = org.junit.Assert.assertThrows<java.lang.AssertionError?>(
            java.lang.AssertionError::class.java,
            org.junit.function.ThrowingRunnable { getConfiguredTarget("//three:a") })
        Truth.assertThat(e).hasMessageThat().contains("key parameter of env_entry must be a nonempty string")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testEnvEntry_string_emptyString() {
        loadCcToolchainConfigLib()
        createEnvEntryRule("four", "'abc'",  /* value= */"''")

        val e: java.lang.AssertionError? = org.junit.Assert.assertThrows<java.lang.AssertionError?>(
            java.lang.AssertionError::class.java,
            org.junit.function.ThrowingRunnable { getConfiguredTarget("//four:a") })
        Truth.assertThat(e)
            .hasMessageThat()
            .contains("value parameter of env_entry must be a nonempty string")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testEnvEntry_string_string() {
        loadCcToolchainConfigLib()
        createEnvEntryRule("five", "'abc'",  /* value= */"'def'")

        val t: ConfiguredTarget = getConfiguredTarget("//five:a")
        val entryProvider: StarlarkInfo? = getMyInfoFromTarget(t).getValue("entry") as StarlarkInfo?
        assertThat(entryProvider).isNotNull()
        val entry: EnvEntry? = envEntryFromStarlark(entryProvider)
        assertThat(entry).isNotNull()
        val parser: StringValueParser = StringValueParser("def")
        assertThat(entry).isEqualTo(
            EnvEntry(
                "abc",
                parser.getChunks(),
                com.google.common.collect.ImmutableSet.of<E?>()
            )
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testEnvEntryVariable_string_string() {
        loadCcToolchainConfigLib()
        createVariableWithValueRule("six",  /* name= */"'abc'",  /* value= */"'def'")
        val t: ConfiguredTarget = getConfiguredTarget("//six:a")
        val variable: StarlarkInfo? = getMyInfoFromTarget(t).getValue("variable") as StarlarkInfo?
        val e: net.starlark.java.eval.EvalException? =
            org.junit.Assert.assertThrows<net.starlark.java.eval.EvalException?>(
                net.starlark.java.eval.EvalException::class.java,
                org.junit.function.ThrowingRunnable { envEntryFromStarlark(variable) })
        Truth.assertThat(e)
            .hasMessageThat()
            .contains("Expected object of type 'env_entry', received 'variable_with_value")
    }

    @Throws(java.lang.Exception::class)
    private fun createEnvEntryRule(pkg: String?, key: String?, value: String?) {
        scratch.file(
            pkg + "/foo.bzl",
            "load('//tools/cpp:cc_toolchain_config_lib.bzl', 'env_entry')",
            "load('//myinfo:myinfo.bzl', 'MyInfo')",
            "def _impl(ctx):",
            "   return [MyInfo(entry = env_entry(",
            "       key = " + key + ",",
            "       value = " + value + "))]",
            "crule = rule(implementation = _impl)"
        )
        scratch.file(pkg + "/BUILD", "load(':foo.bzl', 'crule')", "crule(name = 'a')")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCustomEnvEntry_none_none() {
        loadCcToolchainConfigLib()
        createCustomEnvEntryRule("one",  /* key= */"None",  /* value= */"None")

        val t: ConfiguredTarget = getConfiguredTarget("//one:a")
        val entry: StarlarkInfo? = getMyInfoFromTarget(t).getValue("entry") as StarlarkInfo?
        val e: net.starlark.java.eval.EvalException? =
            org.junit.Assert.assertThrows<net.starlark.java.eval.EvalException?>(
                net.starlark.java.eval.EvalException::class.java,
                org.junit.function.ThrowingRunnable { envEntryFromStarlark(entry) })
        Truth.assertThat(e)
            .hasMessageThat()
            .contains("'key' parameter of env_entry must be a nonempty string.")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCustomEnvEntry_string_none() {
        loadCcToolchainConfigLib()
        createCustomEnvEntryRule("two",  /* key= */"'abc'",  /* value= */"None")

        val t: ConfiguredTarget = getConfiguredTarget("//two:a")
        val entry: StarlarkInfo? = getMyInfoFromTarget(t).getValue("entry") as StarlarkInfo?
        val e: net.starlark.java.eval.EvalException? =
            org.junit.Assert.assertThrows<net.starlark.java.eval.EvalException?>(
                "Should have failed because of empty string.",
                net.starlark.java.eval.EvalException::class.java,
                org.junit.function.ThrowingRunnable { envEntryFromStarlark(entry) })
        Truth.assertThat(e)
            .hasMessageThat()
            .contains("'value' parameter of env_entry must be a nonempty string.")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCustomEnvEntry_string_struct() {
        loadCcToolchainConfigLib()
        createCustomEnvEntryRule("three",  /* key= */"'abc'",  /* value= */"struct()")

        val t: ConfiguredTarget = getConfiguredTarget("//three:a")
        val entry: StarlarkInfo? = getMyInfoFromTarget(t).getValue("entry") as StarlarkInfo?
        val e: net.starlark.java.eval.EvalException? =
            org.junit.Assert.assertThrows<net.starlark.java.eval.EvalException?>(
                net.starlark.java.eval.EvalException::class.java,
                org.junit.function.ThrowingRunnable { envEntryFromStarlark(entry) })
        Truth.assertThat(e).hasMessageThat().contains("Field 'value' is not of 'java.lang.String' type.")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCustomEnvEntry_boolean_string() {
        loadCcToolchainConfigLib()
        createCustomEnvEntryRule("four",  /* key= */"True",  /* value= */"'abc'")

        val t: ConfiguredTarget = getConfiguredTarget("//four:a")
        val entry: StarlarkInfo? = getMyInfoFromTarget(t).getValue("entry") as StarlarkInfo?
        val e: net.starlark.java.eval.EvalException? =
            org.junit.Assert.assertThrows<net.starlark.java.eval.EvalException?>(
                net.starlark.java.eval.EvalException::class.java,
                org.junit.function.ThrowingRunnable { envEntryFromStarlark(entry) })
        Truth.assertThat(e).hasMessageThat().contains("Field 'key' is not of 'java.lang.String' type.")
    }

    @Throws(java.lang.Exception::class)
    private fun createCustomEnvEntryRule(pkg: String?, key: String?, value: String?) {
        scratch.file(
            pkg + "/foo.bzl",
            "load('//myinfo:myinfo.bzl', 'MyInfo')",
            "def _impl(ctx):",
            "   return [MyInfo(entry = struct(",
            "       key = " + key + ",",
            "       value = " + value + ",",
            "       type_name = 'env_entry'))]",
            "crule = rule(implementation = _impl)"
        )
        scratch.file(pkg + "/BUILD", "load(':foo.bzl', 'crule')", "crule(name = 'a')")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testToolPath_none_none() {
        loadCcToolchainConfigLib()
        createToolPathRule("one",  /* name= */"None", "None")

        val e: java.lang.AssertionError? = org.junit.Assert.assertThrows<java.lang.AssertionError?>(
            java.lang.AssertionError::class.java,
            org.junit.function.ThrowingRunnable { getConfiguredTarget("//one:a") })
        Truth.assertThat(e)
            .hasMessageThat()
            .contains("name parameter of tool_path should be a string, found NoneType")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testToolPath_string_none() {
        loadCcToolchainConfigLib()
        createToolPathRule("two",  /* name= */"'abc'", "None")

        val e: java.lang.AssertionError? = org.junit.Assert.assertThrows<java.lang.AssertionError?>(
            java.lang.AssertionError::class.java,
            org.junit.function.ThrowingRunnable { getConfiguredTarget("//two:a") })
        Truth.assertThat(e)
            .hasMessageThat()
            .contains("path parameter of tool_path should be a string, found NoneType")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testToolPath_emptyString_none() {
        loadCcToolchainConfigLib()
        createToolPathRule("three",  /* name= */"''", "None")

        val e: java.lang.AssertionError? = org.junit.Assert.assertThrows<java.lang.AssertionError?>(
            java.lang.AssertionError::class.java,
            org.junit.function.ThrowingRunnable { getConfiguredTarget("//three:a") })
        Truth.assertThat(e)
            .hasMessageThat()
            .contains("name parameter of tool_path must be a nonempty string")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testToolPath_string_emptyString() {
        loadCcToolchainConfigLib()
        createToolPathRule("four",  /* name= */"'abc'", "''")

        val e: java.lang.AssertionError? = org.junit.Assert.assertThrows<java.lang.AssertionError?>(
            java.lang.AssertionError::class.java,
            org.junit.function.ThrowingRunnable { getConfiguredTarget("//four:a") })
        Truth.assertThat(e)
            .hasMessageThat()
            .contains("path parameter of tool_path must be a nonempty string")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testToolPath_string_escapedString() {
        loadCcToolchainConfigLib()
        createToolPathRule("five",  /* name= */"'abc'", "'/d/e/f'")

        val t: ConfiguredTarget = getConfiguredTarget("//five:a")
        val toolPathProvider: StarlarkInfo? = getMyInfoFromTarget(t).getValue("toolpath") as StarlarkInfo?
        assertThat(toolPathProvider).isNotNull()
        val toolPath: Pair<String?, String?> = toolPathFromStarlark(toolPathProvider)
        assertThat(toolPath).isNotNull()
        assertThat(toolPath.first).isEqualTo("abc")
        assertThat(toolPath.second).isEqualTo("/d/e/f")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testToolPath_string_string() {
        loadCcToolchainConfigLib()
        createVariableWithValueRule("six",  /* name= */"'abc'",  /* value= */"'def'")
        val t: ConfiguredTarget = getConfiguredTarget("//six:a")
        val variable: StarlarkInfo? = getMyInfoFromTarget(t).getValue("variable") as StarlarkInfo?
        val e: net.starlark.java.eval.EvalException? =
            org.junit.Assert.assertThrows<net.starlark.java.eval.EvalException?>(
                net.starlark.java.eval.EvalException::class.java,
                org.junit.function.ThrowingRunnable { toolPathFromStarlark(variable) })
        Truth.assertThat(e)
            .hasMessageThat()
            .contains("Expected object of type 'tool_path', received 'variable_with_value")
    }

    @Throws(IOException::class)
    private fun createToolPathRule(pkg: String?, name: String?, path: String?) {
        scratch.file(
            pkg + "/foo.bzl",
            "load('//tools/cpp:cc_toolchain_config_lib.bzl', 'tool_path')",
            "load('//myinfo:myinfo.bzl', 'MyInfo')",
            "def _impl(ctx):",
            "   return [MyInfo(toolpath = tool_path(",
            "       name = " + name + ",",
            "       path = " + path + "))]",
            "crule = rule(implementation = _impl)"
        )
        scratch.file(pkg + "/BUILD", "load(':foo.bzl', 'crule')", "crule(name = 'a')")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCustomToolPath_name_mustBeNonEmpty() {
        loadCcToolchainConfigLib()
        createCustomToolPathRule("one",  /* name= */"None",  /* path= */"None")

        val t: ConfiguredTarget = getConfiguredTarget("//one:a")
        val toolPath: StarlarkInfo? = getMyInfoFromTarget(t).getValue("toolpath") as StarlarkInfo?
        val e: net.starlark.java.eval.EvalException? =
            org.junit.Assert.assertThrows<net.starlark.java.eval.EvalException?>(
                net.starlark.java.eval.EvalException::class.java,
                org.junit.function.ThrowingRunnable { toolPathFromStarlark(toolPath) })
        Truth.assertThat(e)
            .hasMessageThat()
            .contains("'name' parameter of tool_path must be a nonempty string.")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCustomToolPath_path_mustBeNonEmpty() {
        loadCcToolchainConfigLib()
        createCustomToolPathRule("two",  /* name= */"'abc'",  /* path= */"None")

        val t: ConfiguredTarget = getConfiguredTarget("//two:a")
        val toolPath: StarlarkInfo? = getMyInfoFromTarget(t).getValue("toolpath") as StarlarkInfo?
        val e: net.starlark.java.eval.EvalException? =
            org.junit.Assert.assertThrows<net.starlark.java.eval.EvalException?>(
                net.starlark.java.eval.EvalException::class.java,
                org.junit.function.ThrowingRunnable { toolPathFromStarlark(toolPath) })
        Truth.assertThat(e)
            .hasMessageThat()
            .contains("'path' parameter of tool_path must be a nonempty string.")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCustomToolPath_path_mustBeString() {
        loadCcToolchainConfigLib()
        createCustomToolPathRule("three",  /* name= */"'abc'",  /* path= */"struct()")

        val t: ConfiguredTarget = getConfiguredTarget("//three:a")
        val toolPath: StarlarkInfo? = getMyInfoFromTarget(t).getValue("toolpath") as StarlarkInfo?
        val e: net.starlark.java.eval.EvalException? =
            org.junit.Assert.assertThrows<net.starlark.java.eval.EvalException?>(
                net.starlark.java.eval.EvalException::class.java,
                org.junit.function.ThrowingRunnable { toolPathFromStarlark(toolPath) })
        Truth.assertThat(e).hasMessageThat().contains("Field 'path' is not of 'java.lang.String' type.")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCustomToolPath_name_mustBeString() {
        loadCcToolchainConfigLib()
        createCustomToolPathRule("four",  /* name= */"True",  /* path= */"'abc'")

        val t: ConfiguredTarget = getConfiguredTarget("//four:a")
        val toolPath: StarlarkInfo? = getMyInfoFromTarget(t).getValue("toolpath") as StarlarkInfo?
        val e: net.starlark.java.eval.EvalException? =
            org.junit.Assert.assertThrows<net.starlark.java.eval.EvalException?>(
                net.starlark.java.eval.EvalException::class.java,
                org.junit.function.ThrowingRunnable { toolPathFromStarlark(toolPath) })
        Truth.assertThat(e).hasMessageThat().contains("Field 'name' is not of 'java.lang.String' type.")
    }

    @Throws(IOException::class)
    private fun createCustomToolPathRule(pkg: String?, name: String?, path: String?) {
        scratch.file(
            pkg + "/foo.bzl",
            "load('//tools/cpp:cc_toolchain_config_lib.bzl', 'tool_path')",
            "load('//myinfo:myinfo.bzl', 'MyInfo')",
            "def _impl(ctx):",
            "   return [MyInfo(toolpath = struct(",
            "       name = " + name + ",",
            "       path = " + path + ",",
            "       type_name = 'tool_path'))]",
            "crule = rule(implementation = _impl)"
        )
        scratch.file(pkg + "/BUILD", "load(':foo.bzl', 'crule')", "crule(name = 'a')")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testMakeVariable() {
        loadCcToolchainConfigLib()
        createMakeVariablerule("one",  /* name= */"None",  /* value= */"None")

        var e: java.lang.AssertionError? = org.junit.Assert.assertThrows<java.lang.AssertionError?>(
            java.lang.AssertionError::class.java,
            org.junit.function.ThrowingRunnable { getConfiguredTarget("//one:a") })
        Truth.assertThat(e)
            .hasMessageThat()
            .contains("name parameter of make_variable should be a string, found NoneType")

        createMakeVariablerule("two",  /* name= */"'abc'",  /* value= */"None")

        e = org.junit.Assert.assertThrows<java.lang.AssertionError?>(
            java.lang.AssertionError::class.java,
            org.junit.function.ThrowingRunnable { getConfiguredTarget("//two:a") })
        Truth.assertThat(e)
            .hasMessageThat()
            .contains("value parameter of make_variable should be a string, found NoneType")

        createMakeVariablerule("three",  /* name= */"''",  /* value= */"None")

        e = org.junit.Assert.assertThrows<java.lang.AssertionError?>(
            java.lang.AssertionError::class.java,
            org.junit.function.ThrowingRunnable { getConfiguredTarget("//three:a") })
        Truth.assertThat(e)
            .hasMessageThat()
            .contains("name parameter of make_variable must be a nonempty string")

        createMakeVariablerule("four",  /* name= */"'abc'",  /* value= */"''")

        e = org.junit.Assert.assertThrows<java.lang.AssertionError?>(
            java.lang.AssertionError::class.java,
            org.junit.function.ThrowingRunnable { getConfiguredTarget("//four:a") })
        Truth.assertThat(e)
            .hasMessageThat()
            .contains("value parameter of make_variable must be a nonempty string")

        createMakeVariablerule("five",  /* name= */"'abc'",  /* value= */"'val'")

        var t: ConfiguredTarget = getConfiguredTarget("//five:a")
        val makeVariableProvider: StarlarkInfo? = getMyInfoFromTarget(t).getValue("variable") as StarlarkInfo?
        assertThat(makeVariableProvider).isNotNull()
        val makeVariable: Pair<String?, String?> = makeVariableFromStarlark(makeVariableProvider)
        assertThat(makeVariable).isNotNull()
        assertThat(makeVariable.first).isEqualTo("abc")
        assertThat(makeVariable.second).isEqualTo("val")

        createVariableWithValueRule("six",  /* name= */"'abc'",  /* value= */"'def'")
        t = getConfiguredTarget("//six:a")
        val variable: StarlarkInfo? = getMyInfoFromTarget(t).getValue("variable") as StarlarkInfo?
        val ee: net.starlark.java.eval.EvalException? =
            org.junit.Assert.assertThrows<net.starlark.java.eval.EvalException?>(
                net.starlark.java.eval.EvalException::class.java,
                org.junit.function.ThrowingRunnable { makeVariableFromStarlark(variable) })
        Truth.assertThat(ee)
            .hasMessageThat()
            .contains("Expected object of type 'make_variable', received 'variable_with_value")
    }

    @Throws(IOException::class)
    private fun createMakeVariablerule(pkg: String?, name: String?, value: String?) {
        scratch.file(
            pkg + "/foo.bzl",
            "load('//tools/cpp:cc_toolchain_config_lib.bzl', 'make_variable')",
            "load('//myinfo:myinfo.bzl', 'MyInfo')",
            "def _impl(ctx):",
            "   return [MyInfo(variable = make_variable(",
            "       name = " + name + ",",
            "       value = " + value + "))]",
            "crule = rule(implementation = _impl)"
        )
        scratch.file(pkg + "/BUILD", "load(':foo.bzl', 'crule')", "crule(name = 'a')")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCustomMakeVariable_none_none() {
        createCustomMakeVariableRule("one",  /* name= */"None",  /* value= */"None")

        val t: ConfiguredTarget = getConfiguredTarget("//one:a")
        val makeVariableProvider: StarlarkInfo? = getMyInfoFromTarget(t).getValue("variable") as StarlarkInfo?
        val e: net.starlark.java.eval.EvalException? =
            org.junit.Assert.assertThrows<net.starlark.java.eval.EvalException?>(
                net.starlark.java.eval.EvalException::class.java,
                org.junit.function.ThrowingRunnable { makeVariableFromStarlark(makeVariableProvider) })
        Truth.assertThat(e)
            .hasMessageThat()
            .contains("'name' parameter of make_variable must be a nonempty string.")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCustomMakeVariable_string_none() {
        createCustomMakeVariableRule("two",  /* name= */"'abc'",  /* value= */"None")

        val t: ConfiguredTarget = getConfiguredTarget("//two:a")
        val makeVariableProvider: StarlarkInfo? = getMyInfoFromTarget(t).getValue("variable") as StarlarkInfo?
        val e: net.starlark.java.eval.EvalException? =
            org.junit.Assert.assertThrows<net.starlark.java.eval.EvalException?>(
                net.starlark.java.eval.EvalException::class.java,
                org.junit.function.ThrowingRunnable { makeVariableFromStarlark(makeVariableProvider) })
        Truth.assertThat(e)
            .hasMessageThat()
            .contains("'value' parameter of make_variable must be a nonempty string.")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCustomMakeVariable_list_none() {
        createCustomMakeVariableRule("three",  /* name= */"[]",  /* value= */"None")

        val t: ConfiguredTarget = getConfiguredTarget("//three:a")
        val makeVariableProvider: StarlarkInfo? = getMyInfoFromTarget(t).getValue("variable") as StarlarkInfo?
        val e: net.starlark.java.eval.EvalException? =
            org.junit.Assert.assertThrows<net.starlark.java.eval.EvalException?>(
                net.starlark.java.eval.EvalException::class.java,
                org.junit.function.ThrowingRunnable { makeVariableFromStarlark(makeVariableProvider) })
        Truth.assertThat(e).hasMessageThat().contains("Field 'name' is not of 'java.lang.String' type.")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCustomMakeVariable_string_boolean() {
        createCustomMakeVariableRule("four",  /* name= */"'abc'",  /* value= */"True")

        val t: ConfiguredTarget = getConfiguredTarget("//four:a")
        val makeVariableProvider: StarlarkInfo? = getMyInfoFromTarget(t).getValue("variable") as StarlarkInfo?
        val e: net.starlark.java.eval.EvalException? =
            org.junit.Assert.assertThrows<net.starlark.java.eval.EvalException?>(
                net.starlark.java.eval.EvalException::class.java,
                org.junit.function.ThrowingRunnable { makeVariableFromStarlark(makeVariableProvider) })
        Truth.assertThat(e).hasMessageThat().contains("Field 'value' is not of 'java.lang.String' type.")
    }

    @Throws(java.lang.Exception::class)
    private fun createCustomMakeVariableRule(pkg: String?, name: String?, value: String?) {
        scratch.file(
            pkg + "/foo.bzl",
            "load('//myinfo:myinfo.bzl', 'MyInfo')",
            "def _impl(ctx):",
            "   return [MyInfo(variable = struct(",
            "       name = " + name + ",",
            "       value = " + value + ",",
            "       type_name = 'make_variable'))]",
            "crule = rule(implementation = _impl)"
        )
        scratch.file(pkg + "/BUILD", "load(':foo.bzl', 'crule')", "crule(name = 'a')")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testWithFeatureSet() {
        loadCcToolchainConfigLib()
        createWithFeatureSetRule("one",  /* features= */"None",  /* notFeatures= */"None")

        var e: java.lang.AssertionError? = org.junit.Assert.assertThrows<java.lang.AssertionError?>(
            java.lang.AssertionError::class.java,
            org.junit.function.ThrowingRunnable { getConfiguredTarget("//one:a") })
        Truth.assertThat(e)
            .hasMessageThat()
            .contains("features parameter of with_feature_set should be a list, found NoneType")

        createWithFeatureSetRule("two",  /* features= */"['abc']",  /* notFeatures= */"None")

        e = org.junit.Assert.assertThrows<java.lang.AssertionError?>(
            java.lang.AssertionError::class.java,
            org.junit.function.ThrowingRunnable { getConfiguredTarget("//two:a") })
        Truth.assertThat(e)
            .hasMessageThat()
            .contains("not_features parameter of with_feature_set should be a list, found NoneType")

        createWithFeatureSetRule("three",  /* features= */"'asdf'",  /* notFeatures= */"None")

        e = org.junit.Assert.assertThrows<java.lang.AssertionError?>(
            java.lang.AssertionError::class.java,
            org.junit.function.ThrowingRunnable { getConfiguredTarget("//three:a") })
        Truth.assertThat(e)
            .hasMessageThat()
            .contains("features parameter of with_feature_set should be a list, found string")

        createWithFeatureSetRule("four",  /* features= */"['abc']",  /* notFeatures= */"'def'")

        e = org.junit.Assert.assertThrows<java.lang.AssertionError?>(
            java.lang.AssertionError::class.java,
            org.junit.function.ThrowingRunnable { getConfiguredTarget("//four:a") })
        Truth.assertThat(e)
            .hasMessageThat()
            .contains("not_features parameter of with_feature_set should be a list, found string")

        createWithFeatureSetRule(
            "five",  /* features= */"['f1', 'f2']",  /* notFeatures= */"['nf1', 'nf2']"
        )

        var t: ConfiguredTarget = getConfiguredTarget("//five:a")
        val withFeatureSetProvider: StarlarkInfo? = getMyInfoFromTarget(t).getValue("wfs") as StarlarkInfo?
        assertThat(withFeatureSetProvider).isNotNull()
        val withFeatureSet: WithFeatureSet = withFeatureSetFromStarlark(withFeatureSetProvider)
        assertThat(withFeatureSet).isNotNull()
        assertThat(withFeatureSet.features()).containsExactly("f1", "f2")
        assertThat(withFeatureSet.notFeatures()).containsExactly("nf1", "nf2")

        createVariableWithValueRule("six",  /* name= */"'abc'",  /* value= */"'def'")
        t = getConfiguredTarget("//six:a")
        val variable: StarlarkInfo? = getMyInfoFromTarget(t).getValue("variable") as StarlarkInfo?
        val ee: net.starlark.java.eval.EvalException? =
            org.junit.Assert.assertThrows<net.starlark.java.eval.EvalException?>(
                net.starlark.java.eval.EvalException::class.java,
                org.junit.function.ThrowingRunnable { withFeatureSetFromStarlark(variable) })
        Truth.assertThat(ee)
            .hasMessageThat()
            .contains("Expected object of type 'with_feature_set', received 'variable_with_value")
    }

    @Throws(java.lang.Exception::class)
    private fun createWithFeatureSetRule(pkg: String?, features: String?, notFeatures: String?) {
        scratch.file(
            pkg + "/foo.bzl",
            "load('//tools/cpp:cc_toolchain_config_lib.bzl', 'with_feature_set')",
            "load('//myinfo:myinfo.bzl', 'MyInfo')",
            "def _impl(ctx):",
            "   return [MyInfo(wfs = with_feature_set(",
            "       features = " + features + ",",
            "       not_features = " + notFeatures + "))]",
            "crule = rule(implementation = _impl)"
        )
        scratch.file(pkg + "/BUILD", "load(':foo.bzl', 'crule')", "crule(name = 'a')")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCustomWithFeatureSet_struct_none() {
        createCustomWithFeatureSetRule("one",  /* features= */"struct()",  /* notFeatures= */"None")

        val t: ConfiguredTarget = getConfiguredTarget("//one:a")
        val withFeatureSetProvider: StarlarkInfo? = getMyInfoFromTarget(t).getValue("wfs") as StarlarkInfo?
        assertThat(withFeatureSetProvider).isNotNull()
        val e: net.starlark.java.eval.EvalException? =
            org.junit.Assert.assertThrows<net.starlark.java.eval.EvalException?>(
                net.starlark.java.eval.EvalException::class.java,
                org.junit.function.ThrowingRunnable { withFeatureSetFromStarlark(withFeatureSetProvider) })
        Truth.assertThat(e).hasMessageThat().contains("for features, got struct, want sequence")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCustomWithFeatureSet_listOfString_struct() {
        createCustomWithFeatureSetRule("two",  /* features= */"['abc']",  /* notFeatures= */"struct()")

        val t: ConfiguredTarget = getConfiguredTarget("//two:a")
        val withFeatureSetProvider: StarlarkInfo? = getMyInfoFromTarget(t).getValue("wfs") as StarlarkInfo?
        assertThat(withFeatureSetProvider).isNotNull()
        val e: net.starlark.java.eval.EvalException? =
            org.junit.Assert.assertThrows<net.starlark.java.eval.EvalException?>(
                net.starlark.java.eval.EvalException::class.java,
                org.junit.function.ThrowingRunnable { withFeatureSetFromStarlark(withFeatureSetProvider) })
        Truth.assertThat(e).hasMessageThat().contains("for not_features, got struct, want sequence")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCustomWithFeatureSet_listOfStruct_emptyList() {
        createCustomWithFeatureSetRule("three",  /* features= */"[struct()]",  /* notFeatures= */"[]")

        val t: ConfiguredTarget = getConfiguredTarget("//three:a")
        val withFeatureSetProvider: StarlarkInfo? = getMyInfoFromTarget(t).getValue("wfs") as StarlarkInfo?
        assertThat(withFeatureSetProvider).isNotNull()
        val e: net.starlark.java.eval.EvalException? =
            org.junit.Assert.assertThrows<net.starlark.java.eval.EvalException?>(
                net.starlark.java.eval.EvalException::class.java,
                org.junit.function.ThrowingRunnable { withFeatureSetFromStarlark(withFeatureSetProvider) })
        Truth.assertThat(e)
            .hasMessageThat()
            .contains("at index 0 of features, got element of type struct, want string")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCustomWithFeatureSet_emptyList_listOfStruct() {
        createCustomWithFeatureSetRule("four",  /* features= */"[]",  /* notFeatures= */"[struct()]")

        val t: ConfiguredTarget = getConfiguredTarget("//four:a")
        val withFeatureSetProvider: StarlarkInfo? = getMyInfoFromTarget(t).getValue("wfs") as StarlarkInfo?
        assertThat(withFeatureSetProvider).isNotNull()
        val e: net.starlark.java.eval.EvalException? =
            org.junit.Assert.assertThrows<net.starlark.java.eval.EvalException?>(
                net.starlark.java.eval.EvalException::class.java,
                org.junit.function.ThrowingRunnable { withFeatureSetFromStarlark(withFeatureSetProvider) })
        Truth.assertThat(e)
            .hasMessageThat()
            .contains("at index 0 of not_features, got element of type struct, want string")
    }

    @Throws(java.lang.Exception::class)
    private fun createCustomWithFeatureSetRule(pkg: String?, features: String?, notFeatures: String?) {
        scratch.file(
            pkg + "/foo.bzl",
            "load('//myinfo:myinfo.bzl', 'MyInfo')",
            "def _impl(ctx):",
            "   return [MyInfo(wfs = struct(",
            "       features = " + features + ",",
            "       not_features = " + notFeatures + ",",
            "       type_name = 'with_feature_set'))]",
            "crule = rule(implementation = _impl)"
        )
        scratch.file(pkg + "/BUILD", "load(':foo.bzl', 'crule')", "crule(name = 'a')")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testEnvSet_none_none() {
        loadCcToolchainConfigLib()
        createEnvSetRule(
            "one",  /* actions= */"['a1']",  /* envEntries= */"None",  /* withFeatures= */"None"
        )

        val e: java.lang.AssertionError? = org.junit.Assert.assertThrows<java.lang.AssertionError?>(
            java.lang.AssertionError::class.java,
            org.junit.function.ThrowingRunnable { getConfiguredTarget("//one:a") })
        Truth.assertThat(e)
            .hasMessageThat()
            .contains("env_entries parameter of env_set should be a list, found NoneType")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testEnvSet_list_none() {
        loadCcToolchainConfigLib()
        createEnvSetRule(
            "two",  /* actions= */"['a1']",  /* envEntries= */"['abc']",  /* withFeatures= */"None"
        )

        val e: java.lang.AssertionError? = org.junit.Assert.assertThrows<java.lang.AssertionError?>(
            java.lang.AssertionError::class.java,
            org.junit.function.ThrowingRunnable { getConfiguredTarget("//two:a") })
        Truth.assertThat(e)
            .hasMessageThat()
            .contains("with_features parameter of env_set should be a list, found NoneType")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testEnvSet_string_none() {
        loadCcToolchainConfigLib()
        createEnvSetRule(
            "three",  /* actions= */"['a1']",  /* envEntries= */"'asdf'",  /* withFeatures= */"None"
        )

        val e: java.lang.AssertionError? = org.junit.Assert.assertThrows<java.lang.AssertionError?>(
            java.lang.AssertionError::class.java,
            org.junit.function.ThrowingRunnable { getConfiguredTarget("//three:a") })
        Truth.assertThat(e)
            .hasMessageThat()
            .contains("env_entries parameter of env_set should be a list, found string")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testEnvSet_list_string() {
        loadCcToolchainConfigLib()
        createEnvSetRule(
            "four",  /* actions= */"['a1']",  /* envEntries= */"['abc']",  /* withFeatures= */"'def'"
        )

        val e: java.lang.AssertionError? = org.junit.Assert.assertThrows<java.lang.AssertionError?>(
            java.lang.AssertionError::class.java,
            org.junit.function.ThrowingRunnable { getConfiguredTarget("//four:a") })
        Truth.assertThat(e)
            .hasMessageThat()
            .contains("with_features parameter of env_set should be a list, found string")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testEnvSet_envEntry_emptyList() {
        loadCcToolchainConfigLib()
        createEnvSetRule(
            "five",  /* actions= */
            "['a1']",  /* envEntries= */
            "[env_entry(key = 'a', value = 'b'),"
                    + "variable_with_value(name = 'a', value = 'b')]",  /* withFeatures= */
            "[]"
        )

        val t: ConfiguredTarget = getConfiguredTarget("//five:a")
        val envSetProvider: StarlarkInfo? = getMyInfoFromTarget(t).getValue("envset") as StarlarkInfo?
        assertThat(envSetProvider).isNotNull()
        val e: net.starlark.java.eval.EvalException? =
            org.junit.Assert.assertThrows<net.starlark.java.eval.EvalException?>(
                net.starlark.java.eval.EvalException::class.java,
                org.junit.function.ThrowingRunnable { envSetFromStarlark(envSetProvider) })
        Truth.assertThat(e)
            .hasMessageThat()
            .contains("Expected object of type 'env_entry', received 'variable_with_value'")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testEnvSet_emptyList_emptyList() {
        loadCcToolchainConfigLib()
        createEnvSetRule("six",  /* actions= */"[]",  /* envEntries= */"[]",  /* withFeatures= */"[]")

        val e: java.lang.AssertionError? = org.junit.Assert.assertThrows<java.lang.AssertionError?>(
            java.lang.AssertionError::class.java,
            org.junit.function.ThrowingRunnable { getConfiguredTarget("//six:a") })
        Truth.assertThat(e).hasMessageThat().contains("actions parameter of env_set must be a nonempty list")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testEnvSet_envEntry_featureSet() {
        loadCcToolchainConfigLib()
        createEnvSetRule(
            "seven",  /* actions= */
            "['a1']",  /* envEntries= */
            "[env_entry(key = 'a', value = 'b')]",  /* withFeatures= */
            "[with_feature_set(features = ['a'])]"
        )

        val t: ConfiguredTarget = getConfiguredTarget("//seven:a")
        val envSetProvider: StarlarkInfo? = getMyInfoFromTarget(t).getValue("envset") as StarlarkInfo?
        assertThat(envSetProvider).isNotNull()
        val envSet: EnvSet? = envSetFromStarlark(envSetProvider)
        assertThat(envSet).isNotNull()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testEnvSet_string_string() {
        loadCcToolchainConfigLib()
        createVariableWithValueRule("eight",  /* name= */"'abc'",  /* value= */"'def'")
        val t: ConfiguredTarget = getConfiguredTarget("//eight:a")
        val variable: StarlarkInfo? = getMyInfoFromTarget(t).getValue("variable") as StarlarkInfo?
        val e: net.starlark.java.eval.EvalException? =
            org.junit.Assert.assertThrows<net.starlark.java.eval.EvalException?>(
                net.starlark.java.eval.EvalException::class.java,
                org.junit.function.ThrowingRunnable { envSetFromStarlark(variable) })
        Truth.assertThat(e)
            .hasMessageThat()
            .contains("Expected object of type 'env_set', received 'variable_with_value")
    }

    @Throws(java.lang.Exception::class)
    private fun createEnvSetRule(pkg: String?, actions: String?, envEntries: String?, withFeatures: String?) {
        scratch.file(
            pkg + "/foo.bzl",
            "load('//tools/cpp:cc_toolchain_config_lib.bzl',",
            "   'env_set', 'env_entry', 'with_feature_set', 'variable_with_value')",
            "load('//myinfo:myinfo.bzl', 'MyInfo')",
            "def _impl(ctx):",
            "   return [MyInfo(envset = env_set(",
            "       actions = " + actions + ",",
            "       env_entries = " + envEntries + ",",
            "       with_features = " + withFeatures + "))]",
            "crule = rule(implementation = _impl)"
        )
        scratch.file(pkg + "/BUILD", "load(':foo.bzl', 'crule')", "crule(name = 'a')")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCustomEnvSet_none_none() {
        loadCcToolchainConfigLib()
        createCustomEnvSetRule(
            "one",  /* actions= */"[]",  /* envEntries= */"None",  /* withFeatures= */"None"
        )
        val t: ConfiguredTarget = getConfiguredTarget("//one:a")
        val envSetProvider: StarlarkInfo? = getMyInfoFromTarget(t).getValue("envset") as StarlarkInfo?
        assertThat(envSetProvider).isNotNull()
        val e: net.starlark.java.eval.EvalException? =
            org.junit.Assert.assertThrows<net.starlark.java.eval.EvalException?>(
                net.starlark.java.eval.EvalException::class.java,
                org.junit.function.ThrowingRunnable { envSetFromStarlark(envSetProvider) })
        Truth.assertThat(e).hasMessageThat().contains("actions parameter of env_set must be a nonempty list")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCustomEnvSet_struct_none() {
        loadCcToolchainConfigLib()
        createCustomEnvSetRule(
            "two",  /* actions= */"['a1']",  /* envEntries= */"struct()",  /* withFeatures= */"None"
        )
        val t: ConfiguredTarget = getConfiguredTarget("//two:a")
        val envSetProvider: StarlarkInfo? = getMyInfoFromTarget(t).getValue("envset") as StarlarkInfo?
        assertThat(envSetProvider).isNotNull()
        val e: net.starlark.java.eval.EvalException? =
            org.junit.Assert.assertThrows<net.starlark.java.eval.EvalException?>(
                net.starlark.java.eval.EvalException::class.java,
                org.junit.function.ThrowingRunnable { envSetFromStarlark(envSetProvider) })
        Truth.assertThat(e).hasMessageThat().contains("for env_entries, got struct, want sequence")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCustomEnvSet_structList_none() {
        loadCcToolchainConfigLib()
        createCustomEnvSetRule(
            "three",  /* actions= */
            "['a1']",  /* envEntries= */
            "[struct()]",  /* withFeatures= */
            "None"
        )
        val t: ConfiguredTarget = getConfiguredTarget("//three:a")
        val envSetProvider: StarlarkInfo? = getMyInfoFromTarget(t).getValue("envset") as StarlarkInfo?
        assertThat(envSetProvider).isNotNull()
        val e: net.starlark.java.eval.EvalException? =
            org.junit.Assert.assertThrows<net.starlark.java.eval.EvalException?>(
                net.starlark.java.eval.EvalException::class.java,
                org.junit.function.ThrowingRunnable { envSetFromStarlark(envSetProvider) })
        Truth.assertThat(e)
            .hasMessageThat()
            .contains("Expected object of type 'env_entry', received 'struct'")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCustomEnvSet_envEntry_string() {
        loadCcToolchainConfigLib()
        createCustomEnvSetRule(
            "four",  /* actions= */
            "['a1']",  /* envEntries= */
            "[env_entry(key = 'a', value = 'b')]",  /* withFeatures= */
            "'a'"
        )
        val t: ConfiguredTarget = getConfiguredTarget("//four:a")
        val envSetProvider: StarlarkInfo? = getMyInfoFromTarget(t).getValue("envset") as StarlarkInfo?
        assertThat(envSetProvider).isNotNull()
        val e: net.starlark.java.eval.EvalException? =
            org.junit.Assert.assertThrows<net.starlark.java.eval.EvalException?>(
                net.starlark.java.eval.EvalException::class.java,
                org.junit.function.ThrowingRunnable { envSetFromStarlark(envSetProvider) })
        Truth.assertThat(e).hasMessageThat().contains("for with_features, got string, want sequence")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCustomEnvSet_envEntry_envEntry() {
        loadCcToolchainConfigLib()

        createCustomEnvSetRule(
            "five",  /* actions= */
            "['a1']",  /* envEntries= */
            "[env_entry(key = 'a', value = 'b')]",  /* withFeatures= */
            "[env_entry(key = 'a', value = 'b')]"
        )
        val t: ConfiguredTarget = getConfiguredTarget("//five:a")
        val envSetProvider: StarlarkInfo? = getMyInfoFromTarget(t).getValue("envset") as StarlarkInfo?
        assertThat(envSetProvider).isNotNull()
        val e: net.starlark.java.eval.EvalException? =
            org.junit.Assert.assertThrows<net.starlark.java.eval.EvalException?>(
                net.starlark.java.eval.EvalException::class.java,
                org.junit.function.ThrowingRunnable { envSetFromStarlark(envSetProvider) })
        Truth.assertThat(e)
            .hasMessageThat()
            .contains("Expected object of type 'with_feature_set', received 'env_entry'.")
    }

    @Throws(java.lang.Exception::class)
    private fun createCustomEnvSetRule(
        pkg: String?, actions: String?, envEntries: String?, withFeatures: String?
    ) {
        scratch.file(
            pkg + "/foo.bzl",
            "load('//tools/cpp:cc_toolchain_config_lib.bzl',",
            "   'env_entry', 'with_feature_set', 'variable_with_value')",
            "load('//myinfo:myinfo.bzl', 'MyInfo')",
            "def _impl(ctx):",
            "   return [MyInfo(envset = struct(",
            "       actions = " + actions + ",",
            "       env_entries = " + envEntries + ",",
            "       with_features = " + withFeatures + ",",
            "       type_name = 'env_set'))]",
            "crule = rule(implementation = _impl)"
        )
        scratch.file(pkg + "/BUILD", "load(':foo.bzl', 'crule')", "crule(name = 'a')")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testFlagGroup_flagGroup_notListofFlags() {
        loadCcToolchainConfigLib()
        createFlagGroupRule(
            "one",  /* flags= */
            "[]",  /* flagGroups= */
            "[]",  /* iterateOver= */
            "None",  /* expandIfTrue= */
            "None",  /* expandIfFalse= */
            "None",  /* expandIfAvailable= */
            "None",  /* expandIfNotAvailable= */
            "None",  /* expandIfEqual= */
            "None"
        )

        val e: java.lang.AssertionError? = org.junit.Assert.assertThrows<java.lang.AssertionError?>(
            java.lang.AssertionError::class.java,
            org.junit.function.ThrowingRunnable { getConfiguredTarget("//one:a") })
        Truth.assertThat(e)
            .hasMessageThat()
            .contains("flag_group must contain either a list of flags or a list of flag_groups")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testFlagGroup_iterateOver_notString() {
        loadCcToolchainConfigLib()
        createFlagGroupRule(
            "two",  /* flags= */
            "['a']",  /* flagGroups= */
            "[]",  /* iterateOver= */
            "struct(val = 'a')",  /* expandIfTrue= */
            "None",  /* expandIfFalse= */
            "None",  /* expandIfAvailable= */
            "None",  /* expandIfNotAvailable= */
            "None",  /* expandIfEqual= */
            "None"
        )

        val e: java.lang.AssertionError? = org.junit.Assert.assertThrows<java.lang.AssertionError?>(
            java.lang.AssertionError::class.java,
            org.junit.function.ThrowingRunnable { getConfiguredTarget("//two:a") })
        Truth.assertThat(e)
            .hasMessageThat()
            .contains("iterate_over parameter of flag_group should be a string, found struct")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testFlagGroup_expandIfTrue_notString() {
        loadCcToolchainConfigLib()
        createFlagGroupRule(
            "three",  /* flags= */
            "['a']",  /* flagGroups= */
            "[]",  /* iterateOver= */
            "None",  /* expandIfTrue= */
            "struct(val = 'a')",  /* expandIfFalse= */
            "None",  /* expandIfAvailable= */
            "None",  /* expandIfNotAvailable= */
            "None",  /* expandIfEqual= */
            "None"
        )

        val e: java.lang.AssertionError? = org.junit.Assert.assertThrows<java.lang.AssertionError?>(
            java.lang.AssertionError::class.java,
            org.junit.function.ThrowingRunnable { getConfiguredTarget("//three:a") })
        Truth.assertThat(e)
            .hasMessageThat()
            .contains("expand_if_true parameter of flag_group should be a string, found struct")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testFlagGroup_expandIfFalse_notString() {
        loadCcToolchainConfigLib()
        createFlagGroupRule(
            "four",  /* flags= */
            "['a']",  /* flagGroups= */
            "[]",  /* iterateOver= */
            "None",  /* expandIfTrue= */
            "None",  /* expandIfFalse= */
            "struct(val = 'a')",  /* expandIfAvailable= */
            "None",  /* expandIfNotAvailable= */
            "None",  /* expandIfEqual= */
            "None"
        )

        val e: java.lang.AssertionError? = org.junit.Assert.assertThrows<java.lang.AssertionError?>(
            java.lang.AssertionError::class.java,
            org.junit.function.ThrowingRunnable { getConfiguredTarget("//four:a") })
        Truth.assertThat(e)
            .hasMessageThat()
            .contains("expand_if_false parameter of flag_group should be a string, found struct")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testFlagGroup_expandIfAvailable_notString() {
        loadCcToolchainConfigLib()
        createFlagGroupRule(
            "five",  /* flags= */
            "['a']",  /* flagGroups= */
            "[]",  /* iterateOver= */
            "None",  /* expandIfTrue= */
            "None",  /* expandIfFalse= */
            "None",  /* expandIfAvailable= */
            "struct(val = 'a')",  /* expandIfNotAvailable= */
            "None",  /* expandIfEqual= */
            "None"
        )

        val e: java.lang.AssertionError? = org.junit.Assert.assertThrows<java.lang.AssertionError?>(
            java.lang.AssertionError::class.java,
            org.junit.function.ThrowingRunnable { getConfiguredTarget("//five:a") })
        Truth.assertThat(e)
            .hasMessageThat()
            .contains("expand_if_available parameter of flag_group should be a string, found struct")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testFlagGroup_expandIfNotAvailable_notString() {
        loadCcToolchainConfigLib()
        createFlagGroupRule(
            "six",  /* flags= */
            "['a']",  /* flagGroups= */
            "[]",  /* iterateOver= */
            "None",  /* expandIfTrue= */
            "None",  /* expandIfFalse= */
            "None",  /* expandIfAvailable= */
            "None",  /* expandIfNotAvailable= */
            "struct(val = 'a')",  /* expandIfEqual= */
            "None"
        )

        val e: java.lang.AssertionError? = org.junit.Assert.assertThrows<java.lang.AssertionError?>(
            java.lang.AssertionError::class.java,
            org.junit.function.ThrowingRunnable { getConfiguredTarget("//six:a") })
        Truth.assertThat(e)
            .hasMessageThat()
            .contains(
                "expand_if_not_available parameter of flag_group should be a string, found struct"
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testFlagGroup_flagGroup_cannotContainFlagAndGroup() {
        loadCcToolchainConfigLib()
        createFlagGroupRule(
            "seven",  /* flags= */
            "['a']",  /* flagGroups= */
            "['b']",  /* iterateOver= */
            "None",  /* expandIfTrue= */
            "None",  /* expandIfFalse= */
            "None",  /* expandIfAvailable= */
            "None",  /* expandIfNotAvailable= */
            "struct(val = 'a')",  /* expandIfEqual= */
            "None"
        )

        val e: java.lang.AssertionError? = org.junit.Assert.assertThrows<java.lang.AssertionError?>(
            java.lang.AssertionError::class.java,
            org.junit.function.ThrowingRunnable { getConfiguredTarget("//seven:a") })
        Truth.assertThat(e)
            .hasMessageThat()
            .contains("flag_group must not contain both a flag and another flag_group")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testFlagGroup_expandIfEqual_notStarlarkInfo() {
        loadCcToolchainConfigLib()
        createFlagGroupRule(
            "eight",  /* flags= */
            "['a']",  /* flagGroups= */
            "[]",  /* iterateOver= */
            "'a'",  /* expandIfTrue= */
            "'b'",  /* expandIfFalse= */
            "''",  /* expandIfAvailable= */
            "''",  /* expandIfNotAvailable= */
            "''",  /* expandIfEqual= */
            "'a'"
        )

        val t: ConfiguredTarget = getConfiguredTarget("//eight:a")
        val flagGroupProvider: StarlarkInfo? = getMyInfoFromTarget(t).getValue("flaggroup") as StarlarkInfo?
        assertThat(flagGroupProvider).isNotNull()
        val e: net.starlark.java.eval.EvalException? =
            org.junit.Assert.assertThrows<net.starlark.java.eval.EvalException?>(
                net.starlark.java.eval.EvalException::class.java,
                org.junit.function.ThrowingRunnable { flagGroupFromStarlark(flagGroupProvider) })
        Truth.assertThat(e)
            .hasMessageThat()
            .contains(
                "Field 'expand_if_equal' is not of "
                        + "'com.google.devtools.build.lib.packages.StarlarkInfo' type."
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testFlagGroup() {
        loadCcToolchainConfigLib()
        createFlagGroupRule(
            "nine",  /* flags= */
            "[]",  /* flagGroups= */
            "[flag_group(flags = ['a']), flag_group(flags = ['b'])]",  /* iterateOver= */
            "''",  /* expandIfTrue= */
            "''",  /* expandIfFalse= */
            "''",  /* expandIfAvailable= */
            "''",  /* expandIfNotAvailable= */
            "''",  /* expandIfEqual= */
            "variable_with_value(name = 'a', value = 'b')"
        )

        val t: ConfiguredTarget = getConfiguredTarget("//nine:a")
        val flagGroupProvider: StarlarkInfo? = getMyInfoFromTarget(t).getValue("flaggroup") as StarlarkInfo?
        assertThat(flagGroupProvider).isNotNull()
        val f: FlagGroup? = flagGroupFromStarlark(flagGroupProvider)
        assertThat(f).isNotNull()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testFlagGroup_flagGroup_notStruct() {
        loadCcToolchainConfigLib()
        createFlagGroupRule(
            "ten",  /* flags= */
            "[]",  /* flagGroups= */
            "[flag_group(flags = ['a']), struct(value = 'a')]",  /* iterateOver= */
            "''",  /* expandIfTrue= */
            "''",  /* expandIfFalse= */
            "''",  /* expandIfAvailable= */
            "''",  /* expandIfNotAvailable= */
            "''",  /* expandIfEqual= */
            "variable_with_value(name = 'a', value = 'b')"
        )

        val t: ConfiguredTarget = getConfiguredTarget("//ten:a")
        val flagGroupProvider: StarlarkInfo? = getMyInfoFromTarget(t).getValue("flaggroup") as StarlarkInfo?
        assertThat(flagGroupProvider).isNotNull()
        val e: net.starlark.java.eval.EvalException? =
            org.junit.Assert.assertThrows<net.starlark.java.eval.EvalException?>(
                net.starlark.java.eval.EvalException::class.java,
                org.junit.function.ThrowingRunnable { flagGroupFromStarlark(flagGroupProvider) })
        Truth.assertThat(e)
            .hasMessageThat()
            .contains("Expected object of type 'flag_group', received 'struct'")
    }

    @Throws(java.lang.Exception::class)
    private fun createFlagGroupRule(
        pkg: String?,
        flags: String?,
        flagGroups: String?,
        iterateOver: String?,
        expandIfTrue: String?,
        expandIfFalse: String?,
        expandIfAvailable: String?,
        expandIfNotAvailable: String?,
        expandIfEqual: String?
    ) {
        scratch.file(
            pkg + "/foo.bzl",
            "load('//tools/cpp:cc_toolchain_config_lib.bzl',",
            "   'env_set', 'env_entry', 'with_feature_set', 'variable_with_value', 'flag_group')",
            "load('//myinfo:myinfo.bzl', 'MyInfo')",
            "def _impl(ctx):",
            "   return [MyInfo(flaggroup = flag_group(",
            "       flags = " + flags + ",",
            "       flag_groups = " + flagGroups + ",",
            "       expand_if_true = " + expandIfTrue + ",",
            "       expand_if_false = " + expandIfFalse + ",",
            "       expand_if_available = " + expandIfAvailable + ",",
            "       expand_if_not_available = " + expandIfNotAvailable + ",",
            "       expand_if_equal = " + expandIfEqual + ",",
            "       iterate_over = " + iterateOver + "))]",
            "crule = rule(implementation = _impl)"
        )
        scratch.file(pkg + "/BUILD", "load(':foo.bzl', 'crule')", "crule(name = 'a')")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSingleChunkFlagIsUsed() {
        loadCcToolchainConfigLib()

        createCustomFlagGroupRule(
            "single_chunk_flag",  /* flags= */
            "['a']",  /* flagGroups= */
            "[]",  /* iterateOver= */
            "''",  /* expandIfTrue= */
            "''",  /* expandIfFalse= */
            "''",  /* expandIfAvailable= */
            "''",  /* expandIfNotAvailable= */
            "''",  /* expandIfEqual= */
            "None"
        )

        val t: ConfiguredTarget = getConfiguredTarget("//single_chunk_flag:a")
        val flagGroupProvider: StarlarkInfo? = getMyInfoFromTarget(t).getValue("flaggroup") as StarlarkInfo?
        assertThat(flagGroupProvider).isNotNull()
        val flagGroup: FlagGroup = flagGroupFromStarlark(flagGroupProvider)
        assertThat(flagGroup.expandables()).isNotEmpty()
        assertThat(flagGroup.expandables().get(0)).isInstanceOf(SingleChunkFlag::class.java)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCustomFlagGroup_iterateOver_notString() {
        loadCcToolchainConfigLib()
        createCustomFlagGroupRule(
            "one",  /* flags= */
            "['a']",  /* flagGroups= */
            "[]",  /* iterateOver= */
            "struct()",  /* expandIfTrue= */
            "'b'",  /* expandIfFalse= */
            "''",  /* expandIfAvailable= */
            "''",  /* expandIfNotAvailable= */
            "''",  /* expandIfEqual= */
            "None"
        )

        val t: ConfiguredTarget = getConfiguredTarget("//one:a")
        val flagGroupProvider: StarlarkInfo? = getMyInfoFromTarget(t).getValue("flaggroup") as StarlarkInfo?
        assertThat(flagGroupProvider).isNotNull()
        val e: net.starlark.java.eval.EvalException? =
            org.junit.Assert.assertThrows<net.starlark.java.eval.EvalException?>(
                net.starlark.java.eval.EvalException::class.java,
                org.junit.function.ThrowingRunnable { flagGroupFromStarlark(flagGroupProvider) })
        Truth.assertThat(e)
            .hasMessageThat()
            .contains("Field 'iterate_over' is not of 'java.lang.String' type.")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCustomFlagGroup_expandIfTrue_notString() {
        loadCcToolchainConfigLib()
        createCustomFlagGroupRule(
            "two",  /* flags= */
            "[]",  /* flagGroups= */
            "[flag_group(flags = ['a']), flag_group(flags = ['b'])]",  /* iterateOver= */
            "''",  /* expandIfTrue= */
            "struct()",  /* expandIfFalse= */
            "''",  /* expandIfAvailable= */
            "''",  /* expandIfNotAvailable= */
            "''",  /* expandIfEqual= */
            "variable_with_value(name = 'a', value = 'b')"
        )

        val t: ConfiguredTarget = getConfiguredTarget("//two:a")
        val flagGroupProvider: StarlarkInfo? = getMyInfoFromTarget(t).getValue("flaggroup") as StarlarkInfo?
        assertThat(flagGroupProvider).isNotNull()
        val e: net.starlark.java.eval.EvalException? =
            org.junit.Assert.assertThrows<net.starlark.java.eval.EvalException?>(
                net.starlark.java.eval.EvalException::class.java,
                org.junit.function.ThrowingRunnable { flagGroupFromStarlark(flagGroupProvider) })
        Truth.assertThat(e)
            .hasMessageThat()
            .contains("Field 'expand_if_true' is not of 'java.lang.String' type.")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCustomFlagGroup_expandIfFalse_notString() {
        loadCcToolchainConfigLib()
        createCustomFlagGroupRule(
            "three",  /* flags= */
            "[]",  /* flagGroups= */
            "[flag_group(flags = ['a'])]",  /* iterateOver= */
            "''",  /* expandIfTrue= */
            "''",  /* expandIfFalse= */
            "True",  /* expandIfAvailable= */
            "''",  /* expandIfNotAvailable= */
            "''",  /* expandIfEqual= */
            "variable_with_value(name = 'a', value = 'b')"
        )

        val t: ConfiguredTarget = getConfiguredTarget("//three:a")
        val flagGroupProvider: StarlarkInfo? = getMyInfoFromTarget(t).getValue("flaggroup") as StarlarkInfo?
        assertThat(flagGroupProvider).isNotNull()
        val e: net.starlark.java.eval.EvalException? =
            org.junit.Assert.assertThrows<net.starlark.java.eval.EvalException?>(
                net.starlark.java.eval.EvalException::class.java,
                org.junit.function.ThrowingRunnable { flagGroupFromStarlark(flagGroupProvider) })
        Truth.assertThat(e)
            .hasMessageThat()
            .contains("Field 'expand_if_false' is not of 'java.lang.String' type.")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCustomFlagGroup_expandIfAvailable_notString() {
        loadCcToolchainConfigLib()
        createCustomFlagGroupRule(
            "four",  /* flags= */
            "[]",  /* flagGroups= */
            "[flag_group(flags = ['a'])]",  /* iterateOver= */
            "''",  /* expandIfTrue= */
            "''",  /* expandIfFalse= */
            "''",  /* expandIfAvailable= */
            "struct()",  /* expandIfNotAvailable= */
            "''",  /* expandIfEqual= */
            "variable_with_value(name = 'a', value = 'b')"
        )

        val t: ConfiguredTarget = getConfiguredTarget("//four:a")
        val flagGroupProvider: StarlarkInfo? = getMyInfoFromTarget(t).getValue("flaggroup") as StarlarkInfo?
        assertThat(flagGroupProvider).isNotNull()
        val e: net.starlark.java.eval.EvalException? =
            org.junit.Assert.assertThrows<net.starlark.java.eval.EvalException?>(
                net.starlark.java.eval.EvalException::class.java,
                org.junit.function.ThrowingRunnable { flagGroupFromStarlark(flagGroupProvider) })
        Truth.assertThat(e)
            .hasMessageThat()
            .contains("Field 'expand_if_available' is not of 'java.lang.String' type.")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCustomFlagGroup_expandIfNotAvailable_notString() {
        loadCcToolchainConfigLib()
        createCustomFlagGroupRule(
            "five",  /* flags= */
            "[]",  /* flagGroups= */
            "[flag_group(flags = ['a'])]",  /* iterateOver= */
            "''",  /* expandIfTrue= */
            "''",  /* expandIfFalse= */
            "''",  /* expandIfAvailable= */
            "''",  /* expandIfNotAvailable= */
            "3",  /* expandIfEqual= */
            "variable_with_value(name = 'a', value = 'b')"
        )

        val t: ConfiguredTarget = getConfiguredTarget("//five:a")
        val flagGroupProvider: StarlarkInfo? = getMyInfoFromTarget(t).getValue("flaggroup") as StarlarkInfo?
        assertThat(flagGroupProvider).isNotNull()
        val e: net.starlark.java.eval.EvalException? =
            org.junit.Assert.assertThrows<net.starlark.java.eval.EvalException?>(
                net.starlark.java.eval.EvalException::class.java,
                org.junit.function.ThrowingRunnable { flagGroupFromStarlark(flagGroupProvider) })
        Truth.assertThat(e)
            .hasMessageThat()
            .contains("Field 'expand_if_not_available' is not of 'java.lang.String' type.")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCustomFlagGroup_expandIfEqual_notStruct() {
        loadCcToolchainConfigLib()
        createCustomFlagGroupRule(
            "six",  /* flags= */
            "[]",  /* flagGroups= */
            "[flag_group(flags = ['a'])]",  /* iterateOver= */
            "''",  /* expandIfTrue= */
            "''",  /* expandIfFalse= */
            "''",  /* expandIfAvailable= */
            "''",  /* expandIfNotAvailable= */
            "''",  /* expandIfEqual= */
            "struct(name = 'a', value = 'b')"
        )

        val t: ConfiguredTarget = getConfiguredTarget("//six:a")
        val flagGroupProvider: StarlarkInfo? = getMyInfoFromTarget(t).getValue("flaggroup") as StarlarkInfo?
        assertThat(flagGroupProvider).isNotNull()
        val e: net.starlark.java.eval.EvalException? =
            org.junit.Assert.assertThrows<net.starlark.java.eval.EvalException?>(
                net.starlark.java.eval.EvalException::class.java,
                org.junit.function.ThrowingRunnable { flagGroupFromStarlark(flagGroupProvider) })
        Truth.assertThat(e)
            .hasMessageThat()
            .contains("Expected object of type 'variable_with_value', received 'struct'.")
    }

    @Throws(java.lang.Exception::class)
    private fun createCustomFlagGroupRule(
        pkg: String?,
        flags: String?,
        flagGroups: String?,
        iterateOver: String?,
        expandIfTrue: String?,
        expandIfFalse: String?,
        expandIfAvailable: String?,
        expandIfNotAvailable: String?,
        expandIfEqual: String?
    ) {
        scratch.file(
            pkg + "/foo.bzl",
            "load('//tools/cpp:cc_toolchain_config_lib.bzl',",
            "   'env_set', 'env_entry', 'with_feature_set', 'variable_with_value', 'flag_group')",
            "load('//myinfo:myinfo.bzl', 'MyInfo')",
            "def _impl(ctx):",
            "   return [MyInfo(flaggroup = struct(",
            "       flags = " + flags + ",",
            "       flag_groups = " + flagGroups + ",",
            "       expand_if_true = " + expandIfTrue + ",",
            "       expand_if_false = " + expandIfFalse + ",",
            "       expand_if_available = " + expandIfAvailable + ",",
            "       expand_if_not_available = " + expandIfNotAvailable + ",",
            "       expand_if_equal = " + expandIfEqual + ",",
            "       iterate_over = " + iterateOver + ",",
            "       type_name = 'flag_group'))]",
            "crule = rule(implementation = _impl)"
        )
        scratch.file(pkg + "/BUILD", "load(':foo.bzl', 'crule')", "crule(name = 'a')")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTool_path_mustBeNonEmpty() {
        loadCcToolchainConfigLib()
        createToolRule("one",  /* path= */"''",  /* withFeatures= */"[]",  /* requirements= */"[]")

        val e: java.lang.AssertionError? = org.junit.Assert.assertThrows<java.lang.AssertionError?>(
            java.lang.AssertionError::class.java,
            org.junit.function.ThrowingRunnable { getConfiguredTarget("//one:a") })
        Truth.assertThat(e).hasMessageThat().contains("path parameter of tool must be a nonempty string")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTool_withFeatures_mustBeList() {
        loadCcToolchainConfigLib()
        createToolRule("two",  /* path= */"'a'",  /* withFeatures= */"None",  /* requirements= */"[]")

        val e: java.lang.AssertionError? = org.junit.Assert.assertThrows<java.lang.AssertionError?>(
            java.lang.AssertionError::class.java,
            org.junit.function.ThrowingRunnable { getConfiguredTarget("//two:a") })
        Truth.assertThat(e)
            .hasMessageThat()
            .contains("with_features parameter of tool should be a list, found NoneType")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTool_executionRequirements_mustBeList() {
        loadCcToolchainConfigLib()
        createToolRule(
            "three",  /* path= */"'a'",  /* withFeatures= */"[]",  /* requirements= */"None"
        )

        val e: java.lang.AssertionError? = org.junit.Assert.assertThrows<java.lang.AssertionError?>(
            java.lang.AssertionError::class.java,
            org.junit.function.ThrowingRunnable { getConfiguredTarget("//three:a") })
        Truth.assertThat(e)
            .hasMessageThat()
            .contains("execution_requirements parameter of tool should be a list, found NoneType")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTool_withFeatures_mustBeWithFeatureSet() {
        loadCcToolchainConfigLib()
        createToolRule(
            "four",  /* path= */
            "'a'",  /* withFeatures= */
            "[struct(val = 'a')]",  /* requirements= */
            "[]"
        )

        val t: ConfiguredTarget = getConfiguredTarget("//four:a")
        val toolStruct: StarlarkInfo? = getMyInfoFromTarget(t).getValue("tool") as StarlarkInfo?
        assertThat(toolStruct).isNotNull()
        val e: net.starlark.java.eval.EvalException? =
            org.junit.Assert.assertThrows<net.starlark.java.eval.EvalException?>(
                net.starlark.java.eval.EvalException::class.java,
                org.junit.function.ThrowingRunnable {
                    toolFromStarlark(
                        toolStruct,
                        com.google.devtools.build.lib.util.OS.getCurrent()
                    )
                })
        Truth.assertThat(e)
            .hasMessageThat()
            .contains("Expected object of type 'with_feature_set', received 'struct'")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTool_requirements_mustBeString() {
        loadCcToolchainConfigLib()
        createToolRule(
            "five",  /* path= */
            "'a'",  /* withFeatures= */
            "[]",  /* requirements= */
            "[struct(val = 'a')]"
        )

        val t: ConfiguredTarget = getConfiguredTarget("//five:a")
        val toolStruct: StarlarkInfo? = getMyInfoFromTarget(t).getValue("tool") as StarlarkInfo?
        assertThat(toolStruct).isNotNull()
        val e: net.starlark.java.eval.EvalException? =
            org.junit.Assert.assertThrows<net.starlark.java.eval.EvalException?>(
                net.starlark.java.eval.EvalException::class.java,
                org.junit.function.ThrowingRunnable {
                    toolFromStarlark(
                        toolStruct,
                        com.google.devtools.build.lib.util.OS.getCurrent()
                    )
                })
        Truth.assertThat(e)
            .hasMessageThat()
            .contains("at index 0 of execution_requirements, got element of type struct, want string")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTool() {
        loadCcToolchainConfigLib()
        createToolRule(
            "six",  /* path= */
            "'/a/b/c'",  /* withFeatures= */
            "[with_feature_set(features = ['a'])]",  /* requirements= */
            "['a', 'b']"
        )

        val t: ConfiguredTarget = getConfiguredTarget("//six:a")
        val toolStruct: StarlarkInfo? = getMyInfoFromTarget(t).getValue("tool") as StarlarkInfo?
        assertThat(toolStruct).isNotNull()
        val tool: Tool = toolFromStarlark(toolStruct, com.google.devtools.build.lib.util.OS.getCurrent())
        assertThat(tool.getExecutionRequirements()).containsExactly("a", "b")
        assertThat(tool.getToolPathString(PathFragment.EMPTY_FRAGMENT)).isEqualTo("/a/b/c")
        com.google.common.truth.Subject.contains(
            WithFeatureSet(
                com.google.common.collect.ImmutableSet.of<E?>("a"),
                com.google.common.collect.ImmutableSet.of<E?>()
            )
        )
    }

    @Throws(java.lang.Exception::class)
    private fun createToolRule(pkg: String?, path: String?, withFeatures: String?, requirements: String?) {
        scratch.file(
            pkg + "/foo.bzl",
            "load('//tools/cpp:cc_toolchain_config_lib.bzl', 'with_feature_set', 'tool')",
            "load('//myinfo:myinfo.bzl', 'MyInfo')",
            "def _impl(ctx):",
            "   return [MyInfo(tool = tool(",
            "       path = " + path + ",",
            "       with_features = " + withFeatures + ",",
            "       execution_requirements = " + requirements + "))]",
            "crule = rule(implementation = _impl)"
        )
        scratch.file(pkg + "/BUILD", "load(':foo.bzl', 'crule')", "crule(name = 'a')")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCustomTool_path_nonEmpty() {
        loadCcToolchainConfigLib()
        createCustomToolRule(
            "one",  /* path= */"''",  /* withFeatures= */"[]",  /* requirements= */"[]"
        )

        val t: ConfiguredTarget = getConfiguredTarget("//one:a")
        val toolStruct: StarlarkInfo? = getMyInfoFromTarget(t).getValue("tool") as StarlarkInfo?
        assertThat(toolStruct).isNotNull()
        val e: net.starlark.java.eval.EvalException? =
            org.junit.Assert.assertThrows<net.starlark.java.eval.EvalException?>(
                net.starlark.java.eval.EvalException::class.java,
                org.junit.function.ThrowingRunnable {
                    toolFromStarlark(
                        toolStruct,
                        com.google.devtools.build.lib.util.OS.getCurrent()
                    )
                })
        Truth.assertThat(e).hasMessageThat().contains("The 'path' field of tool must be a nonempty string.")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCustomTool_path_mustBeString() {
        loadCcToolchainConfigLib()
        createCustomToolRule(
            "two",  /* path= */"struct()",  /* withFeatures= */"[]",  /* requirements= */"[]"
        )

        val t: ConfiguredTarget = getConfiguredTarget("//two:a")
        val toolStruct: StarlarkInfo? = getMyInfoFromTarget(t).getValue("tool") as StarlarkInfo?
        assertThat(toolStruct).isNotNull()
        val e: net.starlark.java.eval.EvalException? =
            org.junit.Assert.assertThrows<net.starlark.java.eval.EvalException?>(
                net.starlark.java.eval.EvalException::class.java,
                org.junit.function.ThrowingRunnable {
                    toolFromStarlark(
                        toolStruct,
                        com.google.devtools.build.lib.util.OS.getCurrent()
                    )
                })
        Truth.assertThat(e).hasMessageThat().contains("Field 'path' is not of 'java.lang.String' type.")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCustomTool_withFeatures_mustBeList() {
        loadCcToolchainConfigLib()
        createCustomToolRule(
            "three",  /* path= */"'a'",  /* withFeatures= */"struct()",  /* requirements= */"[]"
        )

        val t: ConfiguredTarget = getConfiguredTarget("//three:a")
        val toolStruct: StarlarkInfo? = getMyInfoFromTarget(t).getValue("tool") as StarlarkInfo?
        assertThat(toolStruct).isNotNull()
        val e: net.starlark.java.eval.EvalException? =
            org.junit.Assert.assertThrows<net.starlark.java.eval.EvalException?>(
                net.starlark.java.eval.EvalException::class.java,
                org.junit.function.ThrowingRunnable {
                    toolFromStarlark(
                        toolStruct,
                        com.google.devtools.build.lib.util.OS.getCurrent()
                    )
                })
        Truth.assertThat(e).hasMessageThat().contains("for with_features, got struct, want sequence")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCustomTool_withFeatures_mustBeWithFeatureSet() {
        loadCcToolchainConfigLib()
        createCustomToolRule(
            "four",  /* path= */
            "'a'",  /* withFeatures= */
            "[struct(val = 'a')]",  /* requirements= */
            "[]"
        )

        val t: ConfiguredTarget = getConfiguredTarget("//four:a")
        val toolStruct: StarlarkInfo? = getMyInfoFromTarget(t).getValue("tool") as StarlarkInfo?
        assertThat(toolStruct).isNotNull()
        val e: net.starlark.java.eval.EvalException? =
            org.junit.Assert.assertThrows<net.starlark.java.eval.EvalException?>(
                net.starlark.java.eval.EvalException::class.java,
                org.junit.function.ThrowingRunnable {
                    toolFromStarlark(
                        toolStruct,
                        com.google.devtools.build.lib.util.OS.getCurrent()
                    )
                })
        Truth.assertThat(e)
            .hasMessageThat()
            .contains("Expected object of type 'with_feature_set', received 'struct'")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCustomTool_executionRequirements_mustBeList() {
        loadCcToolchainConfigLib()
        createCustomToolRule(
            "five",  /* path= */"'a'",  /* withFeatures= */"[]",  /* requirements= */"'a'"
        )

        val t: ConfiguredTarget = getConfiguredTarget("//five:a")
        val toolStruct: StarlarkInfo? = getMyInfoFromTarget(t).getValue("tool") as StarlarkInfo?
        assertThat(toolStruct).isNotNull()
        val e: net.starlark.java.eval.EvalException? =
            org.junit.Assert.assertThrows<net.starlark.java.eval.EvalException?>(
                net.starlark.java.eval.EvalException::class.java,
                org.junit.function.ThrowingRunnable {
                    toolFromStarlark(
                        toolStruct,
                        com.google.devtools.build.lib.util.OS.getCurrent()
                    )
                })
        Truth.assertThat(e)
            .hasMessageThat()
            .contains("for execution_requirements, got string, want sequence")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCustomTool_executionRequirements_mustBeString() {
        loadCcToolchainConfigLib()
        createCustomToolRule(
            "six",  /* path= */"'a'",  /* withFeatures= */"[]",  /* requirements= */"[struct()]"
        )

        val t: ConfiguredTarget = getConfiguredTarget("//six:a")
        val toolStruct: StarlarkInfo? = getMyInfoFromTarget(t).getValue("tool") as StarlarkInfo?
        assertThat(toolStruct).isNotNull()
        val e: net.starlark.java.eval.EvalException? =
            org.junit.Assert.assertThrows<net.starlark.java.eval.EvalException?>(
                net.starlark.java.eval.EvalException::class.java,
                org.junit.function.ThrowingRunnable {
                    toolFromStarlark(
                        toolStruct,
                        com.google.devtools.build.lib.util.OS.getCurrent()
                    )
                })
        Truth.assertThat(e)
            .hasMessageThat()
            .contains("at index 0 of execution_requirements, got element of type struct, want string")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCustomTool_windowsAbsolutePath() {
        loadCcToolchainConfigLib()
        createCustomToolRule(
            "seven",  /* path= */
            Starlark.repr(
                "C:\\Program Files\\Microsoft Visual"
                        + " Studio\\2022\\Community\\VC\\Tools\\MSVC\\14.39.33519\\bin/HostX64/x64/cl.exe",
                StarlarkSemantics.DEFAULT
            ),  /* withFeatures= */
            "[]",  /* requirements= */
            "[]"
        )

        val t: ConfiguredTarget = getConfiguredTarget("//seven:a")
        val toolStruct: StarlarkInfo? = getMyInfoFromTarget(t).getValue("tool") as StarlarkInfo?
        assertThat(toolStruct).isNotNull()
        val tool: Tool = toolFromStarlark(toolStruct, com.google.devtools.build.lib.util.OS.WINDOWS)
        assertThat(tool.getToolPathString(PathFragment.create("external/my_toolchain")))
            .isEqualTo(
                "C:/Program Files/Microsoft Visual"
                        + " Studio/2022/Community/VC/Tools/MSVC/14.39.33519/bin/HostX64/x64/cl.exe"
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCustomTool_windowsRelativePath() {
        loadCcToolchainConfigLib()
        createCustomToolRule(
            "seven",  /* path= */
            Starlark.repr("bin\\HostX64\\x64/cl.exe", StarlarkSemantics.DEFAULT),  /* withFeatures= */
            "[]",  /* requirements= */
            "[]"
        )

        val t: ConfiguredTarget = getConfiguredTarget("//seven:a")
        val toolStruct: StarlarkInfo? = getMyInfoFromTarget(t).getValue("tool") as StarlarkInfo?
        assertThat(toolStruct).isNotNull()
        val tool: Tool = toolFromStarlark(toolStruct, com.google.devtools.build.lib.util.OS.WINDOWS)
        assertThat(tool.getToolPathString(PathFragment.create("external/my_toolchain")))
            .isEqualTo("external/my_toolchain/bin/HostX64/x64/cl.exe")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCustomTool_windowsShortPath_preservedOnNonWindowsHostOs() {
        TruthJUnit.assume()
            .that<com.google.devtools.build.lib.util.OS?>(com.google.devtools.build.lib.util.OS.getCurrent())
            .isNotEqualTo(com.google.devtools.build.lib.util.OS.WINDOWS)

        loadCcToolchainConfigLib()
        createCustomToolRule(
            "seven",  /* path= */
            Starlark.repr(
                "C:\\PROGRA~1\\MICROS~1\\2022\\COMMUN~1\\VC\\TOOLS\\MSVC\\14.39.33519\\BIN\\HOSTX64\\X64\\CL.EXE",
                StarlarkSemantics.DEFAULT
            ),  /* withFeatures= */
            "[]",  /* requirements= */
            "[]"
        )

        val t: ConfiguredTarget = getConfiguredTarget("//seven:a")
        val toolStruct: StarlarkInfo? = getMyInfoFromTarget(t).getValue("tool") as StarlarkInfo?
        assertThat(toolStruct).isNotNull()
        val tool: Tool = toolFromStarlark(toolStruct, com.google.devtools.build.lib.util.OS.WINDOWS)
        assertThat(tool.getToolPathString(PathFragment.create("external/my_toolchain")))
            .isEqualTo(
                "C:/PROGRA~1/MICROS~1/2022/COMMUN~1/VC/TOOLS/MSVC/14.39.33519/BIN/HOSTX64/X64/CL.EXE"
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCustomTool_unixAbsolutePath() {
        loadCcToolchainConfigLib()
        createCustomToolRule(
            "seven",  /* path= */
            Starlark.repr("/usr/bin/gcc", StarlarkSemantics.DEFAULT),  /* withFeatures= */
            "[]",  /* requirements= */
            "[]"
        )

        val t: ConfiguredTarget = getConfiguredTarget("//seven:a")
        val toolStruct: StarlarkInfo? = getMyInfoFromTarget(t).getValue("tool") as StarlarkInfo?
        assertThat(toolStruct).isNotNull()
        val tool: Tool = toolFromStarlark(toolStruct, com.google.devtools.build.lib.util.OS.LINUX)
        assertThat(tool.getToolPathString(PathFragment.create("external/my_toolchain")))
            .isEqualTo("/usr/bin/gcc")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCustomTool_unixRelativePath() {
        loadCcToolchainConfigLib()
        createCustomToolRule(
            "seven",  /* path= */
            Starlark.repr("bin/gcc", StarlarkSemantics.DEFAULT),  /* withFeatures= */
            "[]",  /* requirements= */
            "[]"
        )

        val t: ConfiguredTarget = getConfiguredTarget("//seven:a")
        val toolStruct: StarlarkInfo? = getMyInfoFromTarget(t).getValue("tool") as StarlarkInfo?
        assertThat(toolStruct).isNotNull()
        val tool: Tool = toolFromStarlark(toolStruct, com.google.devtools.build.lib.util.OS.LINUX)
        assertThat(tool.getToolPathString(PathFragment.create("external/my_toolchain")))
            .isEqualTo("external/my_toolchain/bin/gcc")
    }

    @Throws(java.lang.Exception::class)
    private fun createCustomToolRule(
        pkg: String?, path: String?, withFeatures: String?, requirements: String?
    ) {
        scratch.file(
            pkg + "/foo.bzl",
            "load('//tools/cpp:cc_toolchain_config_lib.bzl', 'with_feature_set')",
            "load('//myinfo:myinfo.bzl', 'MyInfo')",
            "def _impl(ctx):",
            "   return [MyInfo(tool = struct(",
            "       path = " + path + ",",
            "       with_features = " + withFeatures + ",",
            "       execution_requirements = " + requirements + ",",
            "       type_name = 'tool'))]",
            "crule = rule(implementation = _impl)"
        )
        scratch.file(pkg + "/BUILD", "load(':foo.bzl', 'crule')", "crule(name = 'a')")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testFlagSet_withFeatures_mustBeList() {
        loadCcToolchainConfigLib()
        createFlagSetRule(
            "two",  /* actions= */"['a']",  /* flagGroups= */"[]",  /* withFeatures= */"None"
        )

        val e: java.lang.AssertionError? = org.junit.Assert.assertThrows<java.lang.AssertionError?>(
            java.lang.AssertionError::class.java,
            org.junit.function.ThrowingRunnable { getConfiguredTarget("//two:a") })
        Truth.assertThat(e)
            .hasMessageThat()
            .contains("with_features parameter of flag_set should be a list, found NoneType")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testFlagSet_flagGroups_mustBeList() {
        loadCcToolchainConfigLib()
        createFlagSetRule(
            "three",  /* actions= */"['a']",  /* flagGroups= */"None",  /* withFeatures= */"[]"
        )

        val e: java.lang.AssertionError? = org.junit.Assert.assertThrows<java.lang.AssertionError?>(
            java.lang.AssertionError::class.java,
            org.junit.function.ThrowingRunnable { getConfiguredTarget("//three:a") })
        Truth.assertThat(e)
            .hasMessageThat()
            .contains("flag_groups parameter of flag_set should be a list, found NoneType")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testFlagSet_actions_mustBeString() {
        loadCcToolchainConfigLib()
        createFlagSetRule(
            "four",  /* actions= */
            "['a', struct(val = 'a')]",  /* flagGroups= */
            "[]",  /* withFeatures= */
            "[]"
        )

        val t: ConfiguredTarget = getConfiguredTarget("//four:a")
        val flagSetStruct: StarlarkInfo? = getMyInfoFromTarget(t).getValue("flagset") as StarlarkInfo?
        assertThat(flagSetStruct).isNotNull()
        val e: net.starlark.java.eval.EvalException? =
            org.junit.Assert.assertThrows<net.starlark.java.eval.EvalException?>(
                net.starlark.java.eval.EvalException::class.java,
                org.junit.function.ThrowingRunnable { flagSetFromStarlark(flagSetStruct,  /* actionName= */null) })
        Truth.assertThat(e)
            .hasMessageThat()
            .contains("at index 1 of actions, got element of type struct, want string")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testFlagSet_flagGroups_mustBeFlagGroup() {
        loadCcToolchainConfigLib()
        createFlagSetRule(
            "five",  /* actions= */
            "['a']",  /* flagGroups= */
            "[flag_group(flags = ['a']), struct(value = 'a')]",  /* withFeatures= */
            "[]"
        )

        val t: ConfiguredTarget = getConfiguredTarget("//five:a")
        val flagSetStruct: StarlarkInfo? = getMyInfoFromTarget(t).getValue("flagset") as StarlarkInfo?
        assertThat(flagSetStruct).isNotNull()
        val e: net.starlark.java.eval.EvalException? =
            org.junit.Assert.assertThrows<net.starlark.java.eval.EvalException?>(
                net.starlark.java.eval.EvalException::class.java,
                org.junit.function.ThrowingRunnable { flagSetFromStarlark(flagSetStruct,  /* actionName= */null) })
        Truth.assertThat(e)
            .hasMessageThat()
            .contains("Expected object of type 'flag_group', received 'struct'")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testFlagSet_withFeatures_mustBeWithFeatureSet() {
        loadCcToolchainConfigLib()
        createFlagSetRule(
            "six",  /* actions= */
            "['a']",  /* flagGroups= */
            "[flag_group(flags = ['a'])]",  /* withFeatures= */
            "[struct(val = 'a')]"
        )

        val t: ConfiguredTarget = getConfiguredTarget("//six:a")
        val flagSetStruct: StarlarkInfo? = getMyInfoFromTarget(t).getValue("flagset") as StarlarkInfo?
        assertThat(flagSetStruct).isNotNull()
        val e: net.starlark.java.eval.EvalException? =
            org.junit.Assert.assertThrows<net.starlark.java.eval.EvalException?>(
                net.starlark.java.eval.EvalException::class.java,
                org.junit.function.ThrowingRunnable { flagSetFromStarlark(flagSetStruct,  /* actionName= */null) })
        Truth.assertThat(e)
            .hasMessageThat()
            .contains("Expected object of type 'with_feature_set', received 'struct'")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testFlagSet() {
        loadCcToolchainConfigLib()
        createFlagSetRule(
            "seven",  /* actions= */
            "['a']",  /* flagGroups= */
            "[flag_group(flags = ['a'])]",  /* withFeatures= */
            "[with_feature_set(features = ['a'])]"
        )
        val t: ConfiguredTarget = getConfiguredTarget("//seven:a")
        val flagSetStruct: StarlarkInfo? = getMyInfoFromTarget(t).getValue("flagset") as StarlarkInfo?
        assertThat(flagSetStruct).isNotNull()
        val f: FlagSet? = flagSetFromStarlark(flagSetStruct,  /* actionName= */null)
        assertThat(f).isNotNull()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testFlagSet_actionConfig_notActionList() {
        loadCcToolchainConfigLib()
        createFlagSetRule(
            "eight",  /* actions= */
            "['a']",  /* flagGroups= */
            "[flag_group(flags = ['a'])]",  /* withFeatures= */
            "[struct(val = 'a')]"
        )

        val t: ConfiguredTarget = getConfiguredTarget("//eight:a")
        val flagSetStruct: StarlarkInfo? = getMyInfoFromTarget(t).getValue("flagset") as StarlarkInfo?
        assertThat(flagSetStruct).isNotNull()
        val e: net.starlark.java.eval.EvalException? =
            org.junit.Assert.assertThrows<net.starlark.java.eval.EvalException?>(
                net.starlark.java.eval.EvalException::class.java,
                org.junit.function.ThrowingRunnable { flagSetFromStarlark(flagSetStruct,  /* actionName= */"action") })
        Truth.assertThat(e)
            .hasMessageThat()
            .contains("Thus, you must not specify action lists in an action_config's flag set.")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testFlagSet_emptyAction() {
        loadCcToolchainConfigLib()
        createFlagSetRule(
            "nine",  /* actions= */
            "[]",  /* flagGroups= */
            "[flag_group(flags = ['a'])]",  /* withFeatures= */
            "[with_feature_set(features = ['a'])]"
        )
        val t: ConfiguredTarget = getConfiguredTarget("//nine:a")
        val flagSetStruct: StarlarkInfo? = getMyInfoFromTarget(t).getValue("flagset") as StarlarkInfo?
        assertThat(flagSetStruct).isNotNull()
        val f: FlagSet = flagSetFromStarlark(flagSetStruct,  /* actionName= */"action")
        assertThat(f).isNotNull()
        assertThat(f.actions()).containsExactly("action")
    }

    @Throws(java.lang.Exception::class)
    private fun createFlagSetRule(pkg: String?, actions: String?, flagGroups: String?, withFeatures: String?) {
        scratch.file(
            pkg + "/foo.bzl",
            "load('//tools/cpp:cc_toolchain_config_lib.bzl',",
            "   'env_set', 'env_entry', 'with_feature_set', 'variable_with_value', 'flag_group',",
            "   'flag_set')",
            "load('//myinfo:myinfo.bzl', 'MyInfo')",
            "def _impl(ctx):",
            "   return [MyInfo(flagset = flag_set(",
            "       flag_groups = " + flagGroups + ",",
            "       actions = " + actions + ",",
            "       with_features = " + withFeatures + "))]",
            "crule = rule(implementation = _impl)"
        )
        scratch.file(pkg + "/BUILD", "load(':foo.bzl', 'crule')", "crule(name = 'a')")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCustomFlagSet() {
        loadCcToolchainConfigLib()
        createCustomFlagSetRule(
            "one",  /* actions= */"[]",  /* flagGroups= */"[]",  /* withFeatures= */"[]"
        )

        val target: ConfiguredTarget = getConfiguredTarget("//one:a")
        val flagSet: StarlarkInfo? = getMyInfoFromTarget(target).getValue("flagset") as StarlarkInfo?
        assertThat(flagSet).isNotNull()
        val flagSetObject: FlagSet? = flagSetFromStarlark(flagSet,  /* actionName */null)
        assertThat(flagSetObject).isNotNull()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCustomFlagSet_flagGroups_mustBeList() {
        loadCcToolchainConfigLib()
        createCustomFlagSetRule(
            "two",  /* actions= */"['a']",  /* flagGroups= */"struct()",  /* withFeatures= */"[]"
        )

        val t: ConfiguredTarget = getConfiguredTarget("//two:a")
        val flagSetStruct: StarlarkInfo? = getMyInfoFromTarget(t).getValue("flagset") as StarlarkInfo?
        assertThat(flagSetStruct).isNotNull()
        val e: net.starlark.java.eval.EvalException? =
            org.junit.Assert.assertThrows<net.starlark.java.eval.EvalException?>(
                net.starlark.java.eval.EvalException::class.java,
                org.junit.function.ThrowingRunnable { flagSetFromStarlark(flagSetStruct,  /* actionName */null) })
        Truth.assertThat(e).hasMessageThat().contains("for flag_groups, got struct, want sequence")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCustomFlagSet_withFeatures_mustBeList() {
        loadCcToolchainConfigLib()
        createCustomFlagSetRule(
            "three",  /* actions= */"['a']",  /* flagGroups= */"[]",  /* withFeatures= */"struct()"
        )

        val t: ConfiguredTarget = getConfiguredTarget("//three:a")
        val flagSetStruct: StarlarkInfo? = getMyInfoFromTarget(t).getValue("flagset") as StarlarkInfo?
        assertThat(flagSetStruct).isNotNull()
        val e: net.starlark.java.eval.EvalException? =
            org.junit.Assert.assertThrows<net.starlark.java.eval.EvalException?>(
                net.starlark.java.eval.EvalException::class.java,
                org.junit.function.ThrowingRunnable { flagSetFromStarlark(flagSetStruct,  /* actionName */null) })
        Truth.assertThat(e).hasMessageThat().contains("for with_features, got struct, want sequence")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCustomFlagSet_actions_mustBeList() {
        loadCcToolchainConfigLib()
        createCustomFlagSetRule(
            "four",  /* actions= */"struct()",  /* flagGroups= */"[]",  /* withFeatures= */"[]"
        )

        val t: ConfiguredTarget = getConfiguredTarget("//four:a")
        val flagSetStruct: StarlarkInfo? = getMyInfoFromTarget(t).getValue("flagset") as StarlarkInfo?
        assertThat(flagSetStruct).isNotNull()
        val e: net.starlark.java.eval.EvalException? =
            org.junit.Assert.assertThrows<net.starlark.java.eval.EvalException?>(
                net.starlark.java.eval.EvalException::class.java,
                org.junit.function.ThrowingRunnable { flagSetFromStarlark(flagSetStruct,  /* actionName */null) })
        Truth.assertThat(e).hasMessageThat().contains("for actions, got struct, want sequence")
    }

    @Throws(java.lang.Exception::class)
    private fun createCustomFlagSetRule(
        pkg: String?, actions: String?, flagGroups: String?, withFeatures: String?
    ) {
        scratch.file(
            pkg + "/foo.bzl",
            "load('//tools/cpp:cc_toolchain_config_lib.bzl',",
            "   'env_set', 'env_entry', 'with_feature_set', 'variable_with_value', 'flag_group',",
            "   'flag_set')",
            "load('//myinfo:myinfo.bzl', 'MyInfo')",
            "def _impl(ctx):",
            "   return [MyInfo(flagset = struct(",
            "       flag_groups = " + flagGroups + ",",
            "       actions = " + actions + ",",
            "       with_features = " + withFeatures + ",",
            "       type_name = 'flag_set'))]",
            "crule = rule(implementation = _impl)"
        )
        scratch.file(pkg + "/BUILD", "load(':foo.bzl', 'crule')", "crule(name = 'a')")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testActionConfig_actionName_mustBeNonEmpty() {
        loadCcToolchainConfigLib()
        createActionConfigRule(
            "one",  /* actionName= */
            "''",  /* enabled= */
            "True",  /* tools= */
            "[]",  /* flagSets= */
            "[]",  /* implies= */
            "[]"
        )

        val e: java.lang.AssertionError? = org.junit.Assert.assertThrows<java.lang.AssertionError?>(
            java.lang.AssertionError::class.java,
            org.junit.function.ThrowingRunnable { getConfiguredTarget("//one:a") })
        Truth.assertThat(e)
            .hasMessageThat()
            .contains("name parameter of action_config must be a nonempty string")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testActionConfig_enabled_mustBeBool() {
        loadCcToolchainConfigLib()
        createActionConfigRule(
            "two",  /* actionName= */
            "'actionname'",  /* enabled= */
            "['asd']",  /* tools= */
            "[]",  /* flagSets= */
            "[]",  /* implies= */
            "[]"
        )
        val e: java.lang.AssertionError? = org.junit.Assert.assertThrows<java.lang.AssertionError?>(
            java.lang.AssertionError::class.java,
            org.junit.function.ThrowingRunnable { getConfiguredTarget("//two:a") })
        Truth.assertThat(e)
            .hasMessageThat()
            .contains("enabled parameter of action_config should be a bool, found list")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testActionConfig_tools_mustBeTool() {
        loadCcToolchainConfigLib()
        createActionConfigRule(
            "three",  /* actionName= */
            "'actionname'",  /* enabled= */
            "True",  /* tools= */
            "[with_feature_set(features = ['a'])]",  /* flagSets= */
            "[]",  /* implies= */
            "[]"
        )

        val t: ConfiguredTarget = getConfiguredTarget("//three:a")
        val actionConfigStruct: StarlarkInfo? = getMyInfoFromTarget(t).getValue("config") as StarlarkInfo?
        assertThat(actionConfigStruct).isNotNull()
        val e: net.starlark.java.eval.EvalException? =
            org.junit.Assert.assertThrows<net.starlark.java.eval.EvalException?>(
                net.starlark.java.eval.EvalException::class.java,
                org.junit.function.ThrowingRunnable {
                    actionConfigFromStarlark(
                        actionConfigStruct,
                        com.google.devtools.build.lib.util.OS.getCurrent()
                    )
                })
        Truth.assertThat(e)
            .hasMessageThat()
            .contains("Expected object of type 'tool', received 'with_feature_set'")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testActionConfig_flagSets_mustBeFlagSet() {
        loadCcToolchainConfigLib()
        createActionConfigRule(
            "four",  /* actionName= */
            "'actionname'",  /* enabled= */
            "True",  /* tools= */
            "[tool(path = 'a/b/c')]",  /* flagSets= */
            "[tool(path = 'a/b/c')]",  /* implies= */
            "[]"
        )

        val t: ConfiguredTarget = getConfiguredTarget("//four:a")
        val actionConfigStruct: StarlarkInfo? = getMyInfoFromTarget(t).getValue("config") as StarlarkInfo?
        assertThat(actionConfigStruct).isNotNull()
        val e: net.starlark.java.eval.EvalException? =
            org.junit.Assert.assertThrows<net.starlark.java.eval.EvalException?>(
                net.starlark.java.eval.EvalException::class.java,
                org.junit.function.ThrowingRunnable {
                    actionConfigFromStarlark(
                        actionConfigStruct,
                        com.google.devtools.build.lib.util.OS.getCurrent()
                    )
                })
        Truth.assertThat(e).hasMessageThat().contains("Expected object of type 'flag_set', received 'tool'")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testActionConfig_implies_mustBeList() {
        loadCcToolchainConfigLib()
        createActionConfigRule(
            "five",  /* actionName= */
            "'actionname'",  /* enabled= */
            "True",  /* tools= */
            "[tool(path = 'a/b/c')]",  /* flagSets= */
            "[]",  /* implies= */
            "flag_set(actions = ['a', 'b'])"
        )

        val e: java.lang.AssertionError? = org.junit.Assert.assertThrows<java.lang.AssertionError?>(
            java.lang.AssertionError::class.java,
            org.junit.function.ThrowingRunnable { getConfiguredTarget("//five:a") })
        Truth.assertThat(e)
            .hasMessageThat()
            .contains("implies parameter of action_config should be a list, found struct")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testActionConfig_implies_mustContainString() {
        loadCcToolchainConfigLib()
        createActionConfigRule(
            "six",  /* actionName= */
            "'actionname'",  /* enabled= */
            "True",  /* tools= */
            "[tool(path = 'a/b/c')]",  /* flagSets= */
            "[]",  /* implies= */
            "[flag_set(actions = ['a', 'b'])]"
        )

        val t: ConfiguredTarget = getConfiguredTarget("//six:a")
        val actionConfigStruct: StarlarkInfo? = getMyInfoFromTarget(t).getValue("config") as StarlarkInfo?
        assertThat(actionConfigStruct).isNotNull()
        val e: net.starlark.java.eval.EvalException? =
            org.junit.Assert.assertThrows<net.starlark.java.eval.EvalException?>(
                net.starlark.java.eval.EvalException::class.java,
                org.junit.function.ThrowingRunnable {
                    actionConfigFromStarlark(
                        actionConfigStruct,
                        com.google.devtools.build.lib.util.OS.getCurrent()
                    )
                })
        Truth.assertThat(e)
            .hasMessageThat()
            .contains("at index 0 of implies, got element of type struct, want string")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testActionConfig_implies_mustContainString_notStruct() {
        loadCcToolchainConfigLib()
        createActionConfigRule(
            "seven",  /* actionName= */
            "'actionname'",  /* enabled= */
            "True",  /* tools= */
            "[tool(path = 'a/b/c')]",  /* flagSets= */
            "[]",  /* implies= */
            "[flag_set(actions = ['a', 'b'])]"
        )

        val t: ConfiguredTarget = getConfiguredTarget("//seven:a")
        val actionConfigStruct: StarlarkInfo? = getMyInfoFromTarget(t).getValue("config") as StarlarkInfo?
        assertThat(actionConfigStruct).isNotNull()
        val e: net.starlark.java.eval.EvalException? =
            org.junit.Assert.assertThrows<net.starlark.java.eval.EvalException?>(
                net.starlark.java.eval.EvalException::class.java,
                org.junit.function.ThrowingRunnable {
                    actionConfigFromStarlark(
                        actionConfigStruct,
                        com.google.devtools.build.lib.util.OS.getCurrent()
                    )
                })
        Truth.assertThat(e)
            .hasMessageThat()
            .contains("at index 0 of implies, got element of type struct, want string")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testActionConfig() {
        loadCcToolchainConfigLib()
        createActionConfigRule(
            "eight",  /* actionName= */
            "'actionname32._++-'",  /* enabled= */
            "True",  /* tools= */
            "[tool(path = 'a/b/c')]",  /* flagSets= */
            "[flag_set(flag_groups=[flag_group(flags=['a'])])]",  /* implies= */
            "['a', 'b']"
        )

        val t: ConfiguredTarget = getConfiguredTarget("//eight:a")
        val actionConfigStruct: StarlarkInfo? = getMyInfoFromTarget(t).getValue("config") as StarlarkInfo?
        assertThat(actionConfigStruct).isNotNull()
        val a: ActionConfig =
            actionConfigFromStarlark(actionConfigStruct, com.google.devtools.build.lib.util.OS.getCurrent())
        assertThat(a).isNotNull()
        assertThat(a.actionName).isEqualTo("actionname32._++-")
        assertThat(a.getImplies()).containsExactly("a", "b").inOrder()
        assertThat(com.google.common.collect.Iterables.getOnlyElement<T?>(a.getFlagSets()).actions())
            .containsExactly("actionname32._++-")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testActionConfig_actionName_validChars_notUpper() {
        loadCcToolchainConfigLib()
        createActionConfigRule(
            "nine",  /* actionName= */
            "'Upper'",  /* enabled= */
            "True",  /* tools= */
            "[tool(path = 'a/b/c')]",  /* flagSets= */
            "[]",  /* implies= */
            "[flag_set(actions = ['a', 'b'])]"
        )

        val t: ConfiguredTarget = getConfiguredTarget("//nine:a")
        val actionConfigStruct: StarlarkInfo? = getMyInfoFromTarget(t).getValue("config") as StarlarkInfo?
        assertThat(actionConfigStruct).isNotNull()
        val e: net.starlark.java.eval.EvalException? =
            org.junit.Assert.assertThrows<net.starlark.java.eval.EvalException?>(
                net.starlark.java.eval.EvalException::class.java,
                org.junit.function.ThrowingRunnable {
                    actionConfigFromStarlark(
                        actionConfigStruct,
                        com.google.devtools.build.lib.util.OS.getCurrent()
                    )
                })
        Truth.assertThat(e)
            .hasMessageThat()
            .contains(
                "An action_config's name must consist solely "
                        + "of lowercase ASCII letters, digits, '.', '_', '+', and '-', got 'Upper'"
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testActionConfig_actionName_validChars_notWhitespace() {
        loadCcToolchainConfigLib()
        createActionConfigRule(
            "ten",  /* actionName= */
            "'white\tspace'",  /* enabled= */
            "True",  /* tools= */
            "[tool(path = 'a/b/c')]",  /* flagSets= */
            "[]",  /* implies= */
            "[flag_set(actions = ['a', 'b'])]"
        )

        val t: ConfiguredTarget = getConfiguredTarget("//ten:a")
        val actionConfigStruct: StarlarkInfo? = getMyInfoFromTarget(t).getValue("config") as StarlarkInfo?
        assertThat(actionConfigStruct).isNotNull()
        val e: net.starlark.java.eval.EvalException? =
            org.junit.Assert.assertThrows<net.starlark.java.eval.EvalException?>(
                net.starlark.java.eval.EvalException::class.java,
                org.junit.function.ThrowingRunnable {
                    actionConfigFromStarlark(
                        actionConfigStruct,
                        com.google.devtools.build.lib.util.OS.getCurrent()
                    )
                })
        Truth.assertThat(e)
            .hasMessageThat()
            .contains(
                ("An action_config's name must consist solely "
                        + "of lowercase ASCII letters, digits, '.', '_', '+', and '-', "
                        + "got 'white\tspace'")
            )
    }

    @Throws(java.lang.Exception::class)
    private fun createActionConfigRule(
        pkg: String?, actionName: String?, enabled: String?, tools: String?, flagSets: String?, implies: String?
    ) {
        scratch.file(
            pkg + "/foo.bzl",
            "load('//tools/cpp:cc_toolchain_config_lib.bzl', 'with_feature_set',",
            "             'tool', 'flag_set', 'action_config', 'flag_group')",
            "load('//myinfo:myinfo.bzl', 'MyInfo')",
            "def _impl(ctx):",
            "   return [MyInfo(config = action_config(",
            "       action_name = " + actionName + ",",
            "       enabled = " + enabled + ",",
            "       tools = " + tools + ",",
            "       flag_sets = " + flagSets + ",",
            "       implies = " + implies + "))]",
            "crule = rule(implementation = _impl)"
        )
        scratch.file(pkg + "/BUILD", "load(':foo.bzl', 'crule')", "crule(name = 'a')")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCustomActionConfig_actionName_mustBeString() {
        loadCcToolchainConfigLib()
        createCustomActionConfigRule(
            "one",  /* actionName= */
            "struct()",  /* enabled= */
            "True",  /* tools= */
            "[]",  /* flagSets= */
            "[]",  /* implies= */
            "[]"
        )

        val t: ConfiguredTarget = getConfiguredTarget("//one:a")
        val actionConfigStruct: StarlarkInfo? = getMyInfoFromTarget(t).getValue("config") as StarlarkInfo?
        assertThat(actionConfigStruct).isNotNull()
        val e: net.starlark.java.eval.EvalException? =
            org.junit.Assert.assertThrows<net.starlark.java.eval.EvalException?>(
                net.starlark.java.eval.EvalException::class.java,
                org.junit.function.ThrowingRunnable {
                    actionConfigFromStarlark(
                        actionConfigStruct,
                        com.google.devtools.build.lib.util.OS.getCurrent()
                    )
                })
        Truth.assertThat(e)
            .hasMessageThat()
            .contains("Field 'action_name' is not of 'java.lang.String' type.")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCustomActionConfig_enabled_mustBeBool() {
        loadCcToolchainConfigLib()
        createCustomActionConfigRule(
            "two",  /* actionName= */
            "'actionname'",  /* enabled= */
            "['asd']",  /* tools= */
            "[]",  /* flagSets= */
            "[]",  /* implies= */
            "[]"
        )

        val t: ConfiguredTarget = getConfiguredTarget("//two:a")
        val actionConfigStruct: StarlarkInfo? = getMyInfoFromTarget(t).getValue("config") as StarlarkInfo?
        assertThat(actionConfigStruct).isNotNull()
        val e: net.starlark.java.eval.EvalException? =
            org.junit.Assert.assertThrows<net.starlark.java.eval.EvalException?>(
                net.starlark.java.eval.EvalException::class.java,
                org.junit.function.ThrowingRunnable {
                    actionConfigFromStarlark(
                        actionConfigStruct,
                        com.google.devtools.build.lib.util.OS.getCurrent()
                    )
                })
        Truth.assertThat(e).hasMessageThat().contains("Field 'enabled' is not of 'java.lang.Boolean' type.")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCustomActionConfig_tools_mustBeList() {
        loadCcToolchainConfigLib()
        createCustomActionConfigRule(
            "three",  /* actionName= */
            "'actionname'",  /* enabled= */
            "True",  /* tools= */
            "struct()",  /* flagSets= */
            "[]",  /* implies= */
            "[]"
        )

        val t: ConfiguredTarget = getConfiguredTarget("//three:a")
        val actionConfigStruct: StarlarkInfo? = getMyInfoFromTarget(t).getValue("config") as StarlarkInfo?
        assertThat(actionConfigStruct).isNotNull()
        val e: net.starlark.java.eval.EvalException? =
            org.junit.Assert.assertThrows<net.starlark.java.eval.EvalException?>(
                net.starlark.java.eval.EvalException::class.java,
                org.junit.function.ThrowingRunnable {
                    actionConfigFromStarlark(
                        actionConfigStruct,
                        com.google.devtools.build.lib.util.OS.getCurrent()
                    )
                })
        Truth.assertThat(e).hasMessageThat().contains("for tools, got struct, want sequence")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCustomActionConfig_flagSets_mustBeList() {
        loadCcToolchainConfigLib()
        createCustomActionConfigRule(
            "four",  /* actionName= */
            "'actionname'",  /* enabled= */
            "True",  /* tools= */
            "[tool(path = 'a/b/c')]",  /* flagSets= */
            "True",  /* implies= */
            "[]"
        )

        val t: ConfiguredTarget = getConfiguredTarget("//four:a")
        val actionConfigStruct: StarlarkInfo? = getMyInfoFromTarget(t).getValue("config") as StarlarkInfo?
        assertThat(actionConfigStruct).isNotNull()
        val e: net.starlark.java.eval.EvalException? =
            org.junit.Assert.assertThrows<net.starlark.java.eval.EvalException?>(
                net.starlark.java.eval.EvalException::class.java,
                org.junit.function.ThrowingRunnable {
                    actionConfigFromStarlark(
                        actionConfigStruct,
                        com.google.devtools.build.lib.util.OS.getCurrent()
                    )
                })
        Truth.assertThat(e).hasMessageThat().contains("for flag_sets, got bool, want sequence")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCustomActionConfig_implies_mustBeList() {
        loadCcToolchainConfigLib()
        createCustomActionConfigRule(
            "five",  /* actionName= */
            "'actionname'",  /* enabled= */
            "True",  /* tools= */
            "[tool(path = 'a/b/c')]",  /* flagSets= */
            "[]",  /* implies= */
            "flag_set(actions = ['a', 'b'])"
        )

        val t: ConfiguredTarget = getConfiguredTarget("//five:a")
        val actionConfigStruct: StarlarkInfo? = getMyInfoFromTarget(t).getValue("config") as StarlarkInfo?
        assertThat(actionConfigStruct).isNotNull()
        val e: net.starlark.java.eval.EvalException? =
            org.junit.Assert.assertThrows<net.starlark.java.eval.EvalException?>(
                net.starlark.java.eval.EvalException::class.java,
                org.junit.function.ThrowingRunnable {
                    actionConfigFromStarlark(
                        actionConfigStruct,
                        com.google.devtools.build.lib.util.OS.getCurrent()
                    )
                })
        Truth.assertThat(e).hasMessageThat().contains("for implies, got struct, want sequence")
    }

    @Throws(java.lang.Exception::class)
    private fun createCustomActionConfigRule(
        pkg: String?, actionName: String?, enabled: String?, tools: String?, flagSets: String?, implies: String?
    ) {
        scratch.file(
            pkg + "/foo.bzl",
            "load('//tools/cpp:cc_toolchain_config_lib.bzl', 'with_feature_set',",
            "             'tool', 'flag_set', 'action_config', )",
            "load('//myinfo:myinfo.bzl', 'MyInfo')",
            "def _impl(ctx):",
            "   return [MyInfo(config = struct(",
            "       action_name = " + actionName + ",",
            "       enabled = " + enabled + ",",
            "       tools = " + tools + ",",
            "       flag_sets = " + flagSets + ",",
            "       implies = " + implies + ",",
            "       type_name = 'action_config'))]",
            "crule = rule(implementation = _impl)"
        )
        scratch.file(pkg + "/BUILD", "load(':foo.bzl', 'crule')", "crule(name = 'a')")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testFeature_name_mustBeNonempty() {
        loadCcToolchainConfigLib()
        createFeatureRule(
            "one",  /* name= */
            "''",  /* enabled= */
            "False",  /* flagSets= */
            "[]",  /* envSets= */
            "[]",  /* requires= */
            "[]",  /* implies= */
            "[]",  /* provides= */
            "[]"
        )

        val t: ConfiguredTarget = getConfiguredTarget("//one:a")
        val featureStruct: StarlarkInfo? = getMyInfoFromTarget(t).getValue("f") as StarlarkInfo?
        assertThat(featureStruct).isNotNull()
        val e: net.starlark.java.eval.EvalException =
            org.junit.Assert.assertThrows<net.starlark.java.eval.EvalException>(
                net.starlark.java.eval.EvalException::class.java,
                org.junit.function.ThrowingRunnable { featureFromStarlark(featureStruct) })
        val msg: String? = e.message
        Truth.assertThat(msg).contains("A feature must either have a nonempty 'name' field or be enabled.")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testFeature_enabled_mustBeBool() {
        loadCcToolchainConfigLib()
        createFeatureRule(
            "two",  /* name= */
            "'featurename'",  /* enabled= */
            "None",  /* flagSets= */
            "[]",  /* envSets= */
            "[]",  /* requires= */
            "[]",  /* implies= */
            "[]",  /* provides= */
            "[]"
        )
        val e: java.lang.AssertionError? = org.junit.Assert.assertThrows<java.lang.AssertionError?>(
            java.lang.AssertionError::class.java,
            org.junit.function.ThrowingRunnable { getConfiguredTarget("//two:a") })
        Truth.assertThat(e)
            .hasMessageThat()
            .contains("enabled parameter of feature should be a bool, found NoneType")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testFeature_flagSets_mustBeFlagSet() {
        loadCcToolchainConfigLib()
        createFeatureRule(
            "three",  /* name= */
            "'featurename'",  /* enabled= */
            "True",  /* flagSets= */
            "[struct()]",  /* envSets= */
            "[]",  /* requires= */
            "[]",  /* implies= */
            "[]",  /* provides= */
            "[]"
        )

        val t: ConfiguredTarget = getConfiguredTarget("//three:a")
        val featureStruct: StarlarkInfo? = getMyInfoFromTarget(t).getValue("f") as StarlarkInfo?
        assertThat(featureStruct).isNotNull()
        val e: net.starlark.java.eval.EvalException? =
            org.junit.Assert.assertThrows<net.starlark.java.eval.EvalException?>(
                net.starlark.java.eval.EvalException::class.java,
                org.junit.function.ThrowingRunnable { featureFromStarlark(featureStruct) })
        Truth.assertThat(e)
            .hasMessageThat()
            .contains("Expected object of type 'flag_set', received 'struct'")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testFeature_envSets_mustBeEnvSet() {
        loadCcToolchainConfigLib()
        createFeatureRule(
            "four",  /* name= */
            "'featurename'",  /* enabled= */
            "True",  /* flagSets= */
            "[flag_set(actions = ['a'], flag_groups = [flag_group(flags = ['a'])])]",  /* envSets= */
            "[tool(path = 'a/b/c')]",  /* requires= */
            "[]",  /* implies= */
            "[]",  /* provides= */
            "[]"
        )

        val t: ConfiguredTarget = getConfiguredTarget("//four:a")
        val featureStruct: StarlarkInfo? = getMyInfoFromTarget(t).getValue("f") as StarlarkInfo?
        assertThat(featureStruct).isNotNull()
        val e: net.starlark.java.eval.EvalException? =
            org.junit.Assert.assertThrows<net.starlark.java.eval.EvalException?>(
                net.starlark.java.eval.EvalException::class.java,
                org.junit.function.ThrowingRunnable { featureFromStarlark(featureStruct) })
        Truth.assertThat(e).hasMessageThat().contains("Expected object of type 'env_set', received 'tool'")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testFeature_something_mustBeFeatureSet() {
        loadCcToolchainConfigLib()
        createFeatureRule(
            "five",  /* name= */
            "'featurename'",  /* enabled= */
            "True",  /* flagSets= */
            "[flag_set(actions = ['a'], flag_groups = [flag_group(flags = ['a'])])]",  /* envSets= */
            "[env_set(actions = ['a1'], "
                    + "env_entries = [env_entry(key = 'a', value = 'b')])]",  /* requires= */
            "[tool(path = 'a/b/c')]",  /* implies= */
            "[]",  /* provides= */
            "[]"
        )

        val t: ConfiguredTarget = getConfiguredTarget("//five:a")
        val featureStruct: StarlarkInfo? = getMyInfoFromTarget(t).getValue("f") as StarlarkInfo?
        assertThat(featureStruct).isNotNull()
        val e: net.starlark.java.eval.EvalException? =
            org.junit.Assert.assertThrows<net.starlark.java.eval.EvalException?>(
                net.starlark.java.eval.EvalException::class.java,
                org.junit.function.ThrowingRunnable { featureFromStarlark(featureStruct) })
        Truth.assertThat(e).hasMessageThat().contains("expected object of type 'feature_set'")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testFeature_implies_mustBeString() {
        loadCcToolchainConfigLib()
        createFeatureRule(
            "six",  /* name= */
            "'featurename'",  /* enabled= */
            "True",  /* flagSets= */
            "[flag_set(actions = ['a'], flag_groups = [flag_group(flags = ['a'])])]",  /* envSets= */
            "[env_set(actions = ['a1'], "
                    + "env_entries = [env_entry(key = 'a', value = 'b')])]",  /* requires= */
            "[feature_set(features = ['f1', 'f2'])]",  /* implies= */
            "[tool(path = 'a/b/c')]",  /* provides= */
            "[]"
        )

        val t: ConfiguredTarget = getConfiguredTarget("//six:a")
        val featureStruct: StarlarkInfo? = getMyInfoFromTarget(t).getValue("f") as StarlarkInfo?
        assertThat(featureStruct).isNotNull()
        val e: net.starlark.java.eval.EvalException? =
            org.junit.Assert.assertThrows<net.starlark.java.eval.EvalException?>(
                net.starlark.java.eval.EvalException::class.java,
                org.junit.function.ThrowingRunnable { featureFromStarlark(featureStruct) })
        Truth.assertThat(e)
            .hasMessageThat()
            .contains("at index 0 of implies, got element of type struct, want string")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testFeature_provides_mustBeString() {
        loadCcToolchainConfigLib()
        createFeatureRule(
            "seven",  /* name= */
            "'featurename'",  /* enabled= */
            "True",  /* flagSets= */
            "[flag_set(actions = ['a'], flag_groups = [flag_group(flags = ['a'])])]",  /* envSets= */
            "[env_set(actions = ['a1'], "
                    + "env_entries = [env_entry(key = 'a', value = 'b')])]",  /* requires= */
            "[feature_set(features = ['f1', 'f2'])]",  /* implies= */
            "['a', 'b', 'c']",  /* provides= */
            "[struct()]"
        )

        val t: ConfiguredTarget = getConfiguredTarget("//seven:a")
        val featureStruct: StarlarkInfo? = getMyInfoFromTarget(t).getValue("f") as StarlarkInfo?
        assertThat(featureStruct).isNotNull()
        val e: net.starlark.java.eval.EvalException? =
            org.junit.Assert.assertThrows<net.starlark.java.eval.EvalException?>(
                net.starlark.java.eval.EvalException::class.java,
                org.junit.function.ThrowingRunnable { featureFromStarlark(featureStruct) })
        Truth.assertThat(e)
            .hasMessageThat()
            .contains("at index 0 of provides, got element of type struct, want string")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testFeature() {
        loadCcToolchainConfigLib()
        createFeatureRule(
            "eight",  /* name= */
            "'featurename32+.-_'",  /* enabled= */
            "True",  /* flagSets= */
            "[flag_set(actions = ['a'], flag_groups = [flag_group(flags = ['a'])])]",  /* envSets= */
            "[env_set(actions = ['a1'], "
                    + "env_entries = [env_entry(key = 'a', value = 'b')])]",  /* requires= */
            "[feature_set(features = ['f1', 'f2'])]",  /* implies= */
            "['a', 'b', 'c']",  /* provides= */
            "['a', 'b', 'c']"
        )

        val t: ConfiguredTarget = getConfiguredTarget("//eight:a")
        val featureStruct: StarlarkInfo? = getMyInfoFromTarget(t).getValue("f") as StarlarkInfo?
        assertThat(featureStruct).isNotNull()
        val a: Feature? = featureFromStarlark(featureStruct)
        assertThat(a).isNotNull()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testFeature_name_validCharacters_notUpper() {
        loadCcToolchainConfigLib()
        createFeatureRule(
            "nine",  /* name= */
            "'UpperCase'",  /* enabled= */
            "False",  /* flagSets= */
            "[]",  /* envSets= */
            "[]",  /* requires= */
            "[]",  /* implies= */
            "[]",  /* provides= */
            "[]"
        )

        val t: ConfiguredTarget = getConfiguredTarget("//nine:a")
        val featureStruct: StarlarkInfo? = getMyInfoFromTarget(t).getValue("f") as StarlarkInfo?
        assertThat(featureStruct).isNotNull()
        val e: net.starlark.java.eval.EvalException? =
            org.junit.Assert.assertThrows<net.starlark.java.eval.EvalException?>(
                net.starlark.java.eval.EvalException::class.java,
                org.junit.function.ThrowingRunnable { featureFromStarlark(featureStruct) })
        Truth.assertThat(e)
            .hasMessageThat()
            .contains(
                "A feature's name must consist solely of lowercase ASCII letters, digits, "
                        + "'.', '_', '+', and '-', got 'UpperCase'"
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testFeature_name_validCharacters_notWhitespace() {
        loadCcToolchainConfigLib()
        createFeatureRule(
            "ten",  /* name= */
            "'white space'",  /* enabled= */
            "False",  /* flagSets= */
            "[]",  /* envSets= */
            "[]",  /* requires= */
            "[]",  /* implies= */
            "[]",  /* provides= */
            "[]"
        )

        val t: ConfiguredTarget = getConfiguredTarget("//ten:a")
        val featureStruct: StarlarkInfo? = getMyInfoFromTarget(t).getValue("f") as StarlarkInfo?
        assertThat(featureStruct).isNotNull()
        val e: net.starlark.java.eval.EvalException? =
            org.junit.Assert.assertThrows<net.starlark.java.eval.EvalException?>(
                net.starlark.java.eval.EvalException::class.java,
                org.junit.function.ThrowingRunnable { featureFromStarlark(featureStruct) })
        Truth.assertThat(e)
            .hasMessageThat()
            .contains(
                "A feature's name must consist solely of "
                        + "lowercase ASCII letters, digits, '.', '_', '+', and '-', got 'white space"
            )
    }

    @Throws(java.lang.Exception::class)
    private fun createFeatureRule(
        pkg: String?,
        name: String?,
        enabled: String?,
        flagSets: String?,
        envSets: String?,
        requires: String?,
        implies: String?,
        provides: String?
    ) {
        scratch.file(
            pkg + "/foo.bzl",
            "load('//tools/cpp:cc_toolchain_config_lib.bzl', 'with_feature_set', 'feature_set',",
            "             'flag_set', 'flag_group', 'tool', 'env_set', 'env_entry', 'feature')",
            "load('//myinfo:myinfo.bzl', 'MyInfo')",
            "def _impl(ctx):",
            "   return [MyInfo(f = feature(",
            "       name = " + name + ",",
            "       enabled = " + enabled + ",",
            "       flag_sets = " + flagSets + ",",
            "       env_sets = " + envSets + ",",
            "       requires = " + requires + ",",
            "       implies = " + implies + ",",
            "       provides = " + provides + "))]",
            "crule = rule(implementation = _impl)"
        )
        scratch.file(pkg + "/BUILD", "load(':foo.bzl', 'crule')", "crule(name = 'a')")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCustomFeature_name_mustBeString() {
        loadCcToolchainConfigLib()
        createCustomFeatureRule(
            "one",  /* name= */
            "struct()",  /* enabled= */
            "False",  /* flagSets= */
            "[]",  /* envSets= */
            "[]",  /* requires= */
            "[]",  /* implies= */
            "[]",  /* provides= */
            "[]"
        )

        val t: ConfiguredTarget = getConfiguredTarget("//one:a")
        val featureStruct: StarlarkInfo? = getMyInfoFromTarget(t).getValue("f") as StarlarkInfo?
        assertThat(featureStruct).isNotNull()
        val e: net.starlark.java.eval.EvalException? =
            org.junit.Assert.assertThrows<net.starlark.java.eval.EvalException?>(
                net.starlark.java.eval.EvalException::class.java,
                org.junit.function.ThrowingRunnable { featureFromStarlark(featureStruct) })
        Truth.assertThat(e).hasMessageThat().contains("Field 'name' is not of 'java.lang.String' type.")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCustomFeature_enabled_mustBeBool() {
        loadCcToolchainConfigLib()
        createCustomFeatureRule(
            "two",  /* name= */
            "'featurename'",  /* enabled= */
            "struct()",  /* flagSets= */
            "[]",  /* envSets= */
            "[]",  /* requires= */
            "[]",  /* implies= */
            "[]",  /* provides= */
            "[]"
        )

        val t: ConfiguredTarget = getConfiguredTarget("//two:a")
        val featureStruct: StarlarkInfo? = getMyInfoFromTarget(t).getValue("f") as StarlarkInfo?
        assertThat(featureStruct).isNotNull()
        val e: net.starlark.java.eval.EvalException? =
            org.junit.Assert.assertThrows<net.starlark.java.eval.EvalException?>(
                net.starlark.java.eval.EvalException::class.java,
                org.junit.function.ThrowingRunnable { featureFromStarlark(featureStruct) })
        Truth.assertThat(e).hasMessageThat().contains("Field 'enabled' is not of 'java.lang.Boolean' type.")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCustomFeature_flagSets_mustBeList() {
        loadCcToolchainConfigLib()
        createCustomFeatureRule(
            "three",  /* name= */
            "'featurename'",  /* enabled= */
            "True",  /* flagSets= */
            "struct()",  /* envSets= */
            "[]",  /* requires= */
            "[]",  /* implies= */
            "[]",  /* provides= */
            "[]"
        )

        val t: ConfiguredTarget = getConfiguredTarget("//three:a")
        val featureStruct: StarlarkInfo? = getMyInfoFromTarget(t).getValue("f") as StarlarkInfo?
        assertThat(featureStruct).isNotNull()
        val e: net.starlark.java.eval.EvalException? =
            org.junit.Assert.assertThrows<net.starlark.java.eval.EvalException?>(
                net.starlark.java.eval.EvalException::class.java,
                org.junit.function.ThrowingRunnable { featureFromStarlark(featureStruct) })
        Truth.assertThat(e).hasMessageThat().contains("for flag_sets, got struct, want sequence")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCustomFeature_envSets_mustBeList() {
        loadCcToolchainConfigLib()
        createCustomFeatureRule(
            "four",  /* name= */
            "'featurename'",  /* enabled= */
            "True",  /* flagSets= */
            "[]",  /* envSets= */
            "struct()",  /* requires= */
            "[]",  /* implies= */
            "[]",  /* provides= */
            "[]"
        )

        val t: ConfiguredTarget = getConfiguredTarget("//four:a")
        val featureStruct: StarlarkInfo? = getMyInfoFromTarget(t).getValue("f") as StarlarkInfo?
        assertThat(featureStruct).isNotNull()
        val e: net.starlark.java.eval.EvalException? =
            org.junit.Assert.assertThrows<net.starlark.java.eval.EvalException?>(
                net.starlark.java.eval.EvalException::class.java,
                org.junit.function.ThrowingRunnable { featureFromStarlark(featureStruct) })
        Truth.assertThat(e).hasMessageThat().contains("for env_sets, got struct, want sequence")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCustomFeature_requires_mustBeList() {
        loadCcToolchainConfigLib()
        createCustomFeatureRule(
            "five",  /* name= */
            "'featurename'",  /* enabled= */
            "True",  /* flagSets= */
            "[]",  /* envSets= */
            "[]",  /* requires= */
            "struct()",  /* implies= */
            "[]",  /* provides= */
            "[]"
        )

        val t: ConfiguredTarget = getConfiguredTarget("//five:a")
        val featureStruct: StarlarkInfo? = getMyInfoFromTarget(t).getValue("f") as StarlarkInfo?
        assertThat(featureStruct).isNotNull()
        val e: net.starlark.java.eval.EvalException? =
            org.junit.Assert.assertThrows<net.starlark.java.eval.EvalException?>(
                net.starlark.java.eval.EvalException::class.java,
                org.junit.function.ThrowingRunnable { featureFromStarlark(featureStruct) })
        Truth.assertThat(e).hasMessageThat().contains("for requires, got struct, want sequence")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCustomFeature_implies_mustBeList() {
        loadCcToolchainConfigLib()
        createCustomFeatureRule(
            "six",  /* name= */
            "'featurename'",  /* enabled= */
            "True",  /* flagSets= */
            "[]",  /* envSets= */
            "[]",  /* requires= */
            "[]",  /* implies= */
            "struct()",  /* provides= */
            "[]"
        )

        val t: ConfiguredTarget = getConfiguredTarget("//six:a")
        val featureStruct: StarlarkInfo? = getMyInfoFromTarget(t).getValue("f") as StarlarkInfo?
        assertThat(featureStruct).isNotNull()
        val e: net.starlark.java.eval.EvalException? =
            org.junit.Assert.assertThrows<net.starlark.java.eval.EvalException?>(
                net.starlark.java.eval.EvalException::class.java,
                org.junit.function.ThrowingRunnable { featureFromStarlark(featureStruct) })
        Truth.assertThat(e).hasMessageThat().contains("for implies, got struct, want sequence")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCustomFeature_provides_mustBeList() {
        loadCcToolchainConfigLib()
        createCustomFeatureRule(
            "seven",  /* name= */
            "'featurename'",  /* enabled= */
            "True",  /* flagSets= */
            "[]",  /* envSets= */
            "[]",  /* requires= */
            "[]",  /* implies= */
            "[]",  /* provides= */
            "struct()"
        )

        val t: ConfiguredTarget = getConfiguredTarget("//seven:a")
        val featureStruct: StarlarkInfo? = getMyInfoFromTarget(t).getValue("f") as StarlarkInfo?
        assertThat(featureStruct).isNotNull()
        val e: net.starlark.java.eval.EvalException? =
            org.junit.Assert.assertThrows<net.starlark.java.eval.EvalException?>(
                net.starlark.java.eval.EvalException::class.java,
                org.junit.function.ThrowingRunnable { featureFromStarlark(featureStruct) })
        Truth.assertThat(e).hasMessageThat().contains("for provides, got struct, want sequence")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCustomFeature_flagSet_musthaveActions() {
        loadCcToolchainConfigLib()
        createCustomFeatureRule(
            "eight",  /* name= */
            "'featurename'",  /* enabled= */
            "True",  /* flagSets= */
            "[flag_set()]",  /* envSets= */
            "[]",  /* requires= */
            "[]",  /* implies= */
            "[]",  /* provides= */
            "[]"
        )

        val t: ConfiguredTarget = getConfiguredTarget("//eight:a")
        val featureStruct: StarlarkInfo? = getMyInfoFromTarget(t).getValue("f") as StarlarkInfo?
        assertThat(featureStruct).isNotNull()
        val e: net.starlark.java.eval.EvalException? =
            org.junit.Assert.assertThrows<net.starlark.java.eval.EvalException?>(
                net.starlark.java.eval.EvalException::class.java,
                org.junit.function.ThrowingRunnable { featureFromStarlark(featureStruct) })
        Truth.assertThat(e)
            .hasMessageThat()
            .contains("A flag_set that belongs to a feature must have nonempty 'actions' parameter.")
    }

    @Throws(java.lang.Exception::class)
    private fun createCustomFeatureRule(
        pkg: String?,
        name: String?,
        enabled: String?,
        flagSets: String?,
        envSets: String?,
        requires: String?,
        implies: String?,
        provides: String?
    ) {
        scratch.file(
            pkg + "/foo.bzl",
            "load('//tools/cpp:cc_toolchain_config_lib.bzl', 'with_feature_set', 'feature_set',",
            "             'flag_set', 'flag_group', 'tool', 'env_set', 'env_entry', 'feature')",
            "load('//myinfo:myinfo.bzl', 'MyInfo')",
            "def _impl(ctx):",
            "   return [MyInfo(f = struct(",
            "       name = " + name + ",",
            "       enabled = " + enabled + ",",
            "       flag_sets = " + flagSets + ",",
            "       env_sets = " + envSets + ",",
            "       requires = " + requires + ",",
            "       implies = " + implies + ",",
            "       provides = " + provides + ",",
            "       type_name = 'feature'))]",
            "crule = rule(implementation = _impl)"
        )
        scratch.file(pkg + "/BUILD", "load(':foo.bzl', 'crule')", "crule(name = 'a')")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCustomArtifactNamePattern_categoryName_mustBeString() {
        loadCcToolchainConfigLib()
        createCustomArtifactNamePatternRule(
            "one",  /* categoryName= */"struct()",  /* extension= */"'a'",  /* prefix= */"'a'"
        )

        val t: ConfiguredTarget = getConfiguredTarget("//one:a")
        val artifactNamePatternStruct: StarlarkInfo? =
            getMyInfoFromTarget(t).getValue("namepattern") as StarlarkInfo?
        assertThat(artifactNamePatternStruct).isNotNull()
        val e: net.starlark.java.eval.EvalException? =
            org.junit.Assert.assertThrows<net.starlark.java.eval.EvalException?>(
                net.starlark.java.eval.EvalException::class.java,
                org.junit.function.ThrowingRunnable {
                    artifactNamePatternFromStarlark(
                        artifactNamePatternStruct,
                        { c, p, ext -> })
                })
        Truth.assertThat(e)
            .hasMessageThat()
            .contains("Field 'category_name' is not of 'java.lang.String' type.")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCustomArtifactNamePattern_extension_mustBeString() {
        loadCcToolchainConfigLib()
        createCustomArtifactNamePatternRule(
            "two",  /* categoryName= */
            "'static_library'",  /* extension= */
            "struct()",  /* prefix= */
            "'a'"
        )

        val t: ConfiguredTarget = getConfiguredTarget("//two:a")
        val artifactNamePatternStruct: StarlarkInfo? =
            getMyInfoFromTarget(t).getValue("namepattern") as StarlarkInfo?
        assertThat(artifactNamePatternStruct).isNotNull()
        val e: net.starlark.java.eval.EvalException? =
            org.junit.Assert.assertThrows<net.starlark.java.eval.EvalException?>(
                net.starlark.java.eval.EvalException::class.java,
                org.junit.function.ThrowingRunnable {
                    artifactNamePatternFromStarlark(
                        artifactNamePatternStruct,
                        { c, p, ext -> })
                })
        Truth.assertThat(e).hasMessageThat().contains("Field 'extension' is not of 'java.lang.String' type.")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCustomArtifactNamePattern_prefix_mustBeString() {
        loadCcToolchainConfigLib()
        createCustomArtifactNamePatternRule(
            "three",  /* categoryName= */
            "'static_library'",  /* extension= */
            "'.a'",  /* prefix= */
            "struct()"
        )

        val t: ConfiguredTarget = getConfiguredTarget("//three:a")
        val artifactNamePatternStruct: StarlarkInfo? =
            getMyInfoFromTarget(t).getValue("namepattern") as StarlarkInfo?
        assertThat(artifactNamePatternStruct).isNotNull()
        val e: net.starlark.java.eval.EvalException? =
            org.junit.Assert.assertThrows<net.starlark.java.eval.EvalException?>(
                net.starlark.java.eval.EvalException::class.java,
                org.junit.function.ThrowingRunnable {
                    artifactNamePatternFromStarlark(
                        artifactNamePatternStruct,
                        { c, p, ext -> })
                })
        Truth.assertThat(e).hasMessageThat().contains("Field 'prefix' is not of 'java.lang.String' type.")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCustomArtifactNamePattern_categoryName_mustBeNonempty() {
        loadCcToolchainConfigLib()
        createCustomArtifactNamePatternRule(
            "four",  /* categoryName= */"''",  /* extension= */"'.a'",  /* prefix= */"'a'"
        )

        val t: ConfiguredTarget = getConfiguredTarget("//four:a")
        val artifactNamePatternStruct: StarlarkInfo? =
            getMyInfoFromTarget(t).getValue("namepattern") as StarlarkInfo?
        assertThat(artifactNamePatternStruct).isNotNull()
        val e: net.starlark.java.eval.EvalException? =
            org.junit.Assert.assertThrows<net.starlark.java.eval.EvalException?>(
                net.starlark.java.eval.EvalException::class.java,
                org.junit.function.ThrowingRunnable {
                    artifactNamePatternFromStarlark(
                        artifactNamePatternStruct,
                        { c, p, ext -> })
                })
        Truth.assertThat(e)
            .hasMessageThat()
            .contains("The 'category_name' field of artifact_name_pattern must be a nonempty string.")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCustomArtifactNamePattern_emptyString_emptyString() {
        loadCcToolchainConfigLib()
        createCustomArtifactNamePatternRule(
            "five",  /* categoryName= */"'executable'",  /* extension= */"''",  /* prefix= */"''"
        )

        val t: ConfiguredTarget = getConfiguredTarget("//five:a")
        val artifactNamePatternStruct: StarlarkInfo? =
            getMyInfoFromTarget(t).getValue("namepattern") as StarlarkInfo?
        assertThat(artifactNamePatternStruct).isNotNull()
        val called: AtomicBoolean = AtomicBoolean(false)
        artifactNamePatternFromStarlark(artifactNamePatternStruct, { c, p, ext -> called.set(true) })
        Truth.assertThat(called.get()).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCustomArtifactNamePattern_none_none() {
        loadCcToolchainConfigLib()
        createCustomArtifactNamePatternRule(
            "six",  /* categoryName= */"'executable'",  /* extension= */"None",  /* prefix= */"None"
        )

        val t: ConfiguredTarget = getConfiguredTarget("//six:a")
        val artifactNamePatternStruct: StarlarkInfo? =
            getMyInfoFromTarget(t).getValue("namepattern") as StarlarkInfo?
        assertThat(artifactNamePatternStruct).isNotNull()
        val called: AtomicBoolean = AtomicBoolean(false)
        artifactNamePatternFromStarlark(artifactNamePatternStruct, { c, p, ext -> called.set(true) })
        Truth.assertThat(called.get()).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCustomArtifactNamePattern_categoryName_unknown() {
        loadCcToolchainConfigLib()
        createCustomArtifactNamePatternRule(
            "seven",  /* categoryName= */"'unknown'",  /* extension= */"'.a'",  /* prefix= */"'a'"
        )

        val t: ConfiguredTarget = getConfiguredTarget("//seven:a")
        val artifactNamePatternStruct: StarlarkInfo? =
            getMyInfoFromTarget(t).getValue("namepattern") as StarlarkInfo?
        assertThat(artifactNamePatternStruct).isNotNull()
        val e: net.starlark.java.eval.EvalException? =
            org.junit.Assert.assertThrows<net.starlark.java.eval.EvalException?>(
                net.starlark.java.eval.EvalException::class.java,
                org.junit.function.ThrowingRunnable {
                    artifactNamePatternFromStarlark(
                        artifactNamePatternStruct,
                        { c, p, ext -> })
                })
        Truth.assertThat(e).hasMessageThat().contains("Artifact category unknown not recognized")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCustomArtifactNamePattern_fileExtension_unknown() {
        loadCcToolchainConfigLib()
        createCustomArtifactNamePatternRule(
            "eight",  /* categoryName= */
            "'static_library'",  /* extension= */
            "'a'",  /* prefix= */
            "'a'"
        )

        val t: ConfiguredTarget = getConfiguredTarget("//eight:a")
        val artifactNamePatternStruct: StarlarkInfo? =
            getMyInfoFromTarget(t).getValue("namepattern") as StarlarkInfo?
        assertThat(artifactNamePatternStruct).isNotNull()
        val e: net.starlark.java.eval.EvalException? =
            org.junit.Assert.assertThrows<net.starlark.java.eval.EvalException?>(
                net.starlark.java.eval.EvalException::class.java,
                org.junit.function.ThrowingRunnable {
                    artifactNamePatternFromStarlark(
                        artifactNamePatternStruct,
                        { c, p, ext -> })
                })
        Truth.assertThat(e).hasMessageThat().contains("Unrecognized file extension 'a'")
    }

    @Throws(java.lang.Exception::class)
    private fun createCustomArtifactNamePatternRule(
        pkg: String?, categoryName: String?, extension: String?, prefix: String?
    ) {
        scratch.file(
            pkg + "/foo.bzl",
            "load('//myinfo:myinfo.bzl', 'MyInfo')",
            "def _impl(ctx):",
            "   return [MyInfo(namepattern = struct(",
            "       category_name = " + categoryName + ",",
            "       extension = " + extension + ",",
            "       prefix = " + prefix + ",",
            "       type_name = 'artifact_name_pattern'))]",
            "crule = rule(implementation = _impl)"
        )
        scratch.file(pkg + "/BUILD", "load(':foo.bzl', 'crule')", "crule(name = 'a')")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCcToolchainInfoFromStarlark() {
        loadCcToolchainConfigLib()
        scratch.file(
            "foo/crosstool.bzl",
            """
        load(
            "//tools/cpp:cc_toolchain_config_lib.bzl",
            "action_config",
            "artifact_name_pattern",
            "env_entry",
            "env_set",
            "feature",
            "feature_set",
            "flag_group",
            "flag_set",
            "make_variable",
            "tool",
            "tool_path",
            "variable_with_value",
            "with_feature_set",
        )
        load('@rules_cc//cc/common:cc_common.bzl', 'cc_common')
        load('@rules_cc//cc/toolchains:cc_toolchain_config_info.bzl', 'CcToolchainConfigInfo')

        def _impl(ctx):
            return cc_common.create_cc_toolchain_config_info(
                ctx = ctx,
                features = [feature(name = "featureone")],
                action_configs = [action_config(action_name = "action", enabled = True)],
                artifact_name_patterns = [artifact_name_pattern(
                    category_name = "static_library",
                    prefix = "prefix",
                    extension = ".a",
                )],
                cxx_builtin_include_directories = ["dir1", "dir2", "dir3"],
                toolchain_identifier = "toolchain",
                host_system_name = "host",
                target_system_name = "target",
                target_cpu = "cpu",
                target_libc = "libc",
                compiler = "compiler",
                abi_libc_version = "abi_libc",
                abi_version = "abi",
                tool_paths = [tool_path(name = "name1", path = "path1")],
                cc_target_os = "os",
                builtin_sysroot = "sysroot",
                make_variables = [make_variable(name = "acs", value = "asd")],
            )

        cc_toolchain_config_rule = rule(
            implementation = _impl,
            attrs = {},
            provides = [CcToolchainConfigInfo],
        )
        
        """.trimIndent()
        )

        scratch.file(
            "foo/BUILD",
            """
        load("@rules_cc//cc/toolchains:cc_toolchain_alias.bzl", "cc_toolchain_alias")
        load(":crosstool.bzl", "cc_toolchain_config_rule")

        cc_toolchain_alias(name = "alias")

        cc_toolchain_config_rule(name = "r")
        
        """.trimIndent()
        )
        val target: ConfiguredTarget = getConfiguredTarget("//foo:r")
        assertThat(target).isNotNull()
        val ccToolchainConfigInfo: CcToolchainConfigInfo? = CcToolchainConfigInfo.get(target)
        assertThat(ccToolchainConfigInfo).isNotNull()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCcToolchainInfoFromStarlarkRequiredToolchainIdentifier() {
        setupStarlarkRuleForStringFieldsTesting("toolchain_identifier")
        val e: java.lang.AssertionError? = org.junit.Assert.assertThrows<java.lang.AssertionError?>(
            java.lang.AssertionError::class.java,
            org.junit.function.ThrowingRunnable { getConfiguredTarget("//foo:r") })
        Truth.assertThat(e)
            .hasMessageThat()
            .contains("missing 1 required keyword-only argument: toolchain_identifier")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCcToolchainInfoFromStarlarkRequiredCompiler() {
        setupStarlarkRuleForStringFieldsTesting("compiler")
        val e: java.lang.AssertionError? = org.junit.Assert.assertThrows<java.lang.AssertionError?>(
            java.lang.AssertionError::class.java,
            org.junit.function.ThrowingRunnable { getConfiguredTarget("//foo:r") })
        Truth.assertThat(e).hasMessageThat().contains("missing 1 required keyword-only argument: compiler")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCcToolchainInfoFromStarlarkAllRequiredStringsPresent() {
        setupStarlarkRuleForStringFieldsTesting("")
        val target: ConfiguredTarget = getConfiguredTarget("//foo:r")
        assertThat(target).isNotNull()
        val ccToolchainConfigInfo: CcToolchainConfigInfo? = CcToolchainConfigInfo.get(target)
        assertThat(ccToolchainConfigInfo).isNotNull()
    }

    @Throws(java.lang.Exception::class)
    private fun setupStarlarkRuleForStringFieldsTesting(fieldToExclude: String?) {
        val fields: com.google.common.collect.ImmutableList<String?> =
            com.google.common.collect.ImmutableList.of<String?>(
                "toolchain_identifier = 'identifier'",
                "host_system_name = 'host_system_name'",
                "target_system_name = 'target_system_name'",
                "target_cpu = 'target_cpu'",
                "target_libc = 'target_libc'",
                "compiler = 'compiler'",
                "abi_version = 'abi'",
                "abi_libc_version = 'abi_libc'"
            )

        scratch.file(
            "foo/crosstool.bzl",
            "load('@rules_cc//cc/common:cc_common.bzl', 'cc_common')",
            "load('@rules_cc//cc/toolchains:cc_toolchain_config_info.bzl',"
                    + " 'CcToolchainConfigInfo')",
            "def _impl(ctx):",
            "    return cc_common.create_cc_toolchain_config_info(",
            "    ctx = ctx,",
            com.google.common.base.Joiner.on(",\n")
                .join(fields.stream().filter { el: String? -> !el.startsWith(fieldToExclude + " =") }.toArray()),
            ")",
            "cc_toolchain_config_rule = rule(",
            "    implementation = _impl,",
            "    attrs = {},",
            "    provides = [CcToolchainConfigInfo], ",
            ")")
        scratch.file(
            "foo/BUILD",
            """
        load("@rules_cc//cc/toolchains:cc_toolchain_alias.bzl", "cc_toolchain_alias")
        load(":crosstool.bzl", "cc_toolchain_config_rule")

        cc_toolchain_alias(name = "alias")

        cc_toolchain_config_rule(name = "r")
        
        """.trimIndent()
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCcToolchainInfoFromStarlarkNoLegacyFeatures() {
        loadCcToolchainConfigLib()
        scratch.file(
            "foo/crosstool.bzl",
            """
        load(
            "//tools/cpp:cc_toolchain_config_lib.bzl",
            "action_config",
            "artifact_name_pattern",
            "env_entry",
            "env_set",
            "feature",
            "feature_set",
            "flag_group",
            "flag_set",
            "make_variable",
            "tool",
            "tool_path",
            "variable_with_value",
            "with_feature_set",
        )
        load('@rules_cc//cc/common:cc_common.bzl', 'cc_common')
        load('@rules_cc//cc/toolchains:cc_toolchain_config_info.bzl', 'CcToolchainConfigInfo')

        def _impl(ctx):
            return cc_common.create_cc_toolchain_config_info(
                ctx = ctx,
                features = [
                    feature(name = "no_legacy_features"),
                    feature(name = "custom_feature"),
                ],
                action_configs = [action_config(action_name = "custom_action")],
                artifact_name_patterns = [artifact_name_pattern(
                    category_name = "static_library",
                    prefix = "prefix",
                    extension = ".a",
                )],
                toolchain_identifier = "toolchain",
                host_system_name = "host",
                target_system_name = "target",
                target_cpu = "cpu",
                target_libc = "libc",
                compiler = "compiler",
                abi_libc_version = "abi_libc",
                abi_version = "abi",
            )

        cc_toolchain_config_rule = rule(
            implementation = _impl,
            attrs = {},
            provides = [CcToolchainConfigInfo],
        )
        
        """.trimIndent()
        )

        scratch.file(
            "foo/BUILD",
            """
        load("@rules_cc//cc/toolchains:cc_toolchain_alias.bzl", "cc_toolchain_alias")
        load(":crosstool.bzl", "cc_toolchain_config_rule")

        cc_toolchain_alias(name = "alias")

        cc_toolchain_config_rule(name = "r")
        
        """.trimIndent()
        )
        val target: ConfiguredTarget = getConfiguredTarget("//foo:r")
        assertThat(target).isNotNull()
        val ccToolchainConfigInfo: CcToolchainConfigInfo = CcToolchainConfigInfo.get(target)
        val featureNames: com.google.common.collect.ImmutableSet<String?>? =
            ccToolchainConfigInfo.getFeatures().stream()
                .map(Feature::getName)
                .collect(com.google.common.collect.ImmutableSet.toImmutableSet<E?>())
        val actionConfigNames: com.google.common.collect.ImmutableSet<String?>? =
            ccToolchainConfigInfo.getActionConfigs().stream()
                .map(ActionConfig::getActionName)
                .collect(com.google.common.collect.ImmutableSet.toImmutableSet<E?>())
        Truth.assertThat(featureNames).containsExactly("no_legacy_features", "custom_feature")
        Truth.assertThat(actionConfigNames).containsExactly("custom_action")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCcToolchainInfoFromStarlarkWithLegacyFeatures() {
        loadCcToolchainConfigLib()
        scratch.file(
            "foo/crosstool.bzl",
            """
        load(
            "//tools/cpp:cc_toolchain_config_lib.bzl",
            "action_config",
            "artifact_name_pattern",
            "env_entry",
            "env_set",
            "feature",
            "feature_set",
            "flag_group",
            "flag_set",
            "make_variable",
            "tool",
            "tool_path",
            "variable_with_value",
            "with_feature_set",
        )
        load('@rules_cc//cc/common:cc_common.bzl', 'cc_common')
        load('@rules_cc//cc/toolchains:cc_toolchain_config_info.bzl', 'CcToolchainConfigInfo')

        def _impl(ctx):
            return cc_common.create_cc_toolchain_config_info(
                ctx = ctx,
                features = [
                    feature(name = "custom_feature"),
                    feature(name = "legacy_compile_flags"),
                    feature(name = "fdo_optimize"),
                    feature(name = "default_compile_flags"),
                ],
                action_configs = [action_config(action_name = "custom-action")],
                artifact_name_patterns = [artifact_name_pattern(
                    category_name = "static_library",
                    prefix = "prefix",
                    extension = ".a",
                )],
                toolchain_identifier = "toolchain",
                host_system_name = "host",
                target_system_name = "target",
                target_cpu = "cpu",
                target_libc = "libc",
                compiler = "compiler",
                abi_libc_version = "abi_libc",
                abi_version = "abi",
            )

        cc_toolchain_config_rule = rule(
            implementation = _impl,
            attrs = {},
            provides = [CcToolchainConfigInfo],
        )
        
        """.trimIndent()
        )

        scratch.file(
            "foo/BUILD",
            """
        load("@rules_cc//cc/toolchains:cc_toolchain_alias.bzl", "cc_toolchain_alias")
        load(":crosstool.bzl", "cc_toolchain_config_rule")

        cc_toolchain_alias(name = "alias")

        cc_toolchain_config_rule(name = "r")
        
        """.trimIndent()
        )
        val target: ConfiguredTarget = getConfiguredTarget("//foo:r")
        assertThat(target).isNotNull()
        val ccToolchainConfigInfo: CcToolchainConfigInfo = CcToolchainConfigInfo.get(target)
        val featureNames: com.google.common.collect.ImmutableList<String?>? =
            ccToolchainConfigInfo.getFeatures().stream()
                .map(Feature::getName)
                .collect(com.google.common.collect.ImmutableList.toImmutableList<E?>())
        val actionConfigNames: com.google.common.collect.ImmutableSet<String?>? =
            ccToolchainConfigInfo.getActionConfigs().stream()
                .map(ActionConfig::getActionName)
                .collect(com.google.common.collect.ImmutableSet.toImmutableSet<E?>())
        // fdo_optimize should not be re-added to the list of features by legacy behavior
        Truth.assertThat(featureNames).containsNoDuplicates()
        // legacy_compile_flags should appear first in the list of features, followed by
        // default_compile_flags.
        Truth.assertThat(featureNames)
            .containsAtLeast(
                "legacy_compile_flags", "default_compile_flags", "custom_feature", "fdo_optimize"
            )
            .inOrder()
        // assemble is one of the action_configs added as a legacy behavior, therefore it needs to be
        // prepended to the action configs defined by the user.
        Truth.assertThat(actionConfigNames).containsAtLeast("assemble", "custom-action").inOrder()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testGetLegacyCcFlagsMakeVariable() {
        AnalysisMock.get()
            .ccSupport()
            .setupCcToolchainConfig(
                mockToolsConfig,
                CcToolchainConfig.builder()
                    .withMakeVariables(Pair.of("CC_FLAGS", "-test-cflag1 -testcflag2"))
            )

        loadCcToolchainConfigLib()
        scratch.file(
            "a/rule.bzl",
            """
        load("//myinfo:myinfo.bzl", "MyInfo")
        load('@rules_cc//cc/common:cc_common.bzl', 'cc_common')

        def _impl(ctx):
            toolchain = ctx.attr._cc_toolchain[cc_common.CcToolchainInfo]
            cc_flags = cc_common.legacy_cc_flags_make_variable_do_not_use(
                cc_toolchain = toolchain,
            )
            return [MyInfo(
                cc_flags = cc_flags,
            )]

        cc_flags = rule(
            _impl,
            attrs = {
                "_cc_toolchain": attr.label(default = Label("//a:alias")),
            },
            fragments = ["cpp"],
        )
        
        """.trimIndent()
        )

        scratch.file(
            "a/BUILD",
            """
        load("@rules_cc//cc/toolchains:cc_toolchain_alias.bzl", "cc_toolchain_alias")
        load(":rule.bzl", "cc_flags")

        cc_toolchain_alias(name = "alias")

        cc_flags(name = "r")
        
        """.trimIndent()
        )

        val ccFlags =
            getMyInfoFromTarget(getConfiguredTarget("//a:r")).getValue("cc_flags") as String?

        Truth.assertThat(ccFlags).isEqualTo("-test-cflag1 -testcflag2")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testWrongExtensionThrowsError() {
        setUpCcLinkingContextTest()
        scratch.file(
            "foo/BUILD",
            """
        load("@rules_cc//cc:cc_binary.bzl", "cc_binary")
        load("//tools/build_defs/cc:rule.bzl", "crule")

        cc_binary(
            name = "bin",
            deps = [":a"],
        )

        crule(
            name = "a",
            dynamic_library = "a.ifso",
            dynamic_library_symlink_path = "a.lib",
            interface_library = "a.so",
            interface_library_symlink_path = "a.dll",
            pic_static_library = "a.pic.o",
            static_library = "a.o",
        )
        
        """.trimIndent()
        )
        val e: java.lang.AssertionError? = org.junit.Assert.assertThrows<java.lang.AssertionError?>(
            java.lang.AssertionError::class.java,
            org.junit.function.ThrowingRunnable { getConfiguredTarget("//foo:bin") })
        Truth.assertThat(e)
            .hasMessageThat()
            .contains("'a.o' does not have any of the allowed extensions .a, .lib, .rlib")
        Truth.assertThat(e)
            .hasMessageThat()
            .contains("'a.pic.o' does not have any of the allowed extensions .a, .lib, .rlib")
        Truth.assertThat(e)
            .hasMessageThat()
            .contains(
                "'a.ifso' does not have any of the allowed extensions .so, .dylib, .dll, .pyd, .wasm,"
                        + " .tgt, .vpi"
            )
        Truth.assertThat(e)
            .hasMessageThat()
            .contains(
                "'a.lib' does not have any of the allowed extensions .so, .dylib, .dll, .pyd, .wasm,"
                        + " .tgt, .vpi"
            )
        Truth.assertThat(e)
            .hasMessageThat()
            .contains("'a.dll' does not have any of the allowed extensions .ifso, .tbd, .lib, .dll.a")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCcOutputsMerging() {
        setupCcOutputsTest()
        scratch.file(
            "foo/BUILD",
            """
        load("//tools/build_defs/foo:extension.bzl", "cc_starlark_library")

        cc_starlark_library(
            name = "starlark_lib",
            object1 = "object1.o",
            object2 = "object2.o",
            pic_object1 = "pic_object1.o",
            pic_object2 = "pic_object2.o",
        )
        
        """.trimIndent()
        )
        val target: ConfiguredTarget = getConfiguredTarget("//foo:starlark_lib")
        val compilationOutputs: StarlarkInfo =
            getMyInfoFromTarget(target).getValue("compilation_outputs") as StarlarkInfo
        Truth.assertThat(
            AnalysisTestUtil.artifactsToStrings(
                targetConfig, compilationOutputs.getValue("pic_objects", Iterable::class.java)
            )
        )
            .containsExactly("src foo/pic_object1.o", "src foo/pic_object2.o")
        Truth.assertThat(
            AnalysisTestUtil.artifactsToStrings(
                targetConfig, compilationOutputs.getValue("objects", Iterable::class.java)
            )
        )
            .containsExactly("src foo/object1.o", "src foo/object2.o")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testObjectsWrongExtension() {
        doTestCcOutputsWrongExtension("object1", "objects")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testPicObjectsWrongExtension() {
        doTestCcOutputsWrongExtension("pic_object1", "pic_objects")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testObjectsRightExtension() {
        doTestCcOutputsRightExtension("object1")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testPicObjectsRightExtension() {
        doTestCcOutputsRightExtension("pic_object1")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCreateOnlyPic() {
        getAnalysisMock()
            .ccSupport()
            .setupCcToolchainConfig(
                mockToolsConfig, CcToolchainConfig.builder().withFeatures(CppRuleClasses.SUPPORTS_PIC)
            )
        createFilesForTestingCompilation(
            scratch, "tools/build_defs/foo", "disallow_nopic_outputs=True"
        )
        assertThat(getConfiguredTarget("//foo:bin")).isNotNull()
        val target: ConfiguredTarget = getConfiguredTarget("//foo:starlark_lib")
        Truth.assertThat(getFilenamesToBuild(target)).doesNotContain("starlark_lib.o")
        Truth.assertThat(getFilenamesToBuild(target)).contains("starlark_lib.pic.o")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCreateOnlyNoPic() {
        createFilesForTestingCompilation(scratch, "tools/build_defs/foo", "disallow_pic_outputs=True")
        assertThat(getConfiguredTarget("//foo:bin")).isNotNull()
        val target: ConfiguredTarget = getConfiguredTarget("//foo:starlark_lib")
        Truth.assertThat(getFilenamesToBuild(target)).contains("starlark_lib.o")
        Truth.assertThat(getFilenamesToBuild(target)).doesNotContain("starlark_lib.pic.o")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCreatePicAndNoPic() {
        getAnalysisMock()
            .ccSupport()
            .setupCcToolchainConfig(
                mockToolsConfig, CcToolchainConfig.builder().withFeatures(CppRuleClasses.SUPPORTS_PIC)
            )
        createFilesForTestingCompilation(scratch, "tools/build_defs/foo", "")
        useConfiguration("--compilation_mode=opt")
        assertThat(getConfiguredTarget("//foo:bin")).isNotNull()
        val target: ConfiguredTarget = getConfiguredTarget("//foo:starlark_lib")
        Truth.assertThat(getFilenamesToBuild(target)).contains("starlark_lib.pic.o")
        Truth.assertThat(getFilenamesToBuild(target)).contains("starlark_lib.o")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDoNotCreateEitherPicOrNoPic() {
        createFilesForTestingCompilation(
            scratch, "tools/build_defs/foo", "disallow_nopic_outputs=True, disallow_pic_outputs=True"
        )
        reporter.removeHandler(failFastHandler)
        getConfiguredTarget("//foo:bin")
        assertContainsEvent("Either PIC or no PIC actions have to be created.")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCreateStaticLibraries() {
        getAnalysisMock()
            .ccSupport()
            .setupCcToolchainConfig(
                mockToolsConfig,
                CcToolchainConfig.builder()
                    .withFeatures(CppRuleClasses.SUPPORTS_DYNAMIC_LINKER, CppRuleClasses.SUPPORTS_PIC)
            )
        createFilesForTestingLinking(scratch, "tools/build_defs/foo",  /* linkProviderLines= */"")
        assertThat(getConfiguredTarget("//foo:starlark_lib")).isNotNull()
        val target: ConfiguredTarget = getConfiguredTarget("//foo:starlark_lib")
        com.google.common.truth.Subject.contains("libstarlark_lib.a")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDoNotCreateStaticLibraries() {
        createFilesForTestingLinking(scratch, "tools/build_defs/foo", "disallow_static_libraries=True")
        assertThat(getConfiguredTarget("//foo:starlark_lib")).isNotNull()
        val target: ConfiguredTarget = getConfiguredTarget("//foo:starlark_lib")
        assertThat(
            getFilesToBuild(target).toList().stream()
                .map(Artifact::getFilename)
                .collect(com.google.common.collect.ImmutableList.toImmutableList<E?>())
        )
            .doesNotContain("libstarlark_lib.a")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCcNativeRuleDependingOnStarlarkDefinedRule() {
        createFiles(scratch, "tools/build_defs/cc")
        assertThat(getConfiguredTarget("//foo:bin")).isNotNull()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testUserCompileFlagsInRulesApi() {
        createFilesForTestingCompilation(
            scratch, "tools/build_defs/foo", "user_compile_flags=['-COMPILATION_OPTION']"
        )
        assertThat(getConfiguredTarget("//foo:bin")).isNotNull()
        val target: ConfiguredTarget = getConfiguredTarget("//foo:starlark_lib")
        val action: CppCompileAction =
            getGeneratingAction(artifactByPath(getFilesToBuild(target), ".o")) as CppCompileAction
        com.google.common.truth.Subject.contains("-COMPILATION_OPTION")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testIncludeDirs() {
        createFilesForTestingCompilation(
            scratch, "tools/build_defs/foo", "includes=['foo/bar', 'baz/qux']"
        )
        val target: ConfiguredTarget = getConfiguredTarget("//foo:starlark_lib")
        assertThat(target).isNotNull()
        val action: CppCompileAction =
            getGeneratingAction(artifactByPath(getFilesToBuild(target), ".o")) as CppCompileAction
        assertThat(action.getArguments()).containsAtLeast("-Ifoo/bar", "-Ibaz/qux")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSystemIncludeDirs() {
        createFilesForTestingCompilation(
            scratch, "tools/build_defs/foo", "system_includes=['foo/bar', 'baz/qux']"
        )
        val target: ConfiguredTarget = getConfiguredTarget("//foo:starlark_lib")
        assertThat(target).isNotNull()
        val action: CppCompileAction =
            getGeneratingAction(artifactByPath(getFilesToBuild(target), ".o")) as CppCompileAction
        assertThat(action.getArguments())
            .containsAtLeast("-isystem", "foo/bar", "-isystem", "baz/qux")
            .inOrder()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testQuoteIncludeDirs() {
        createFilesForTestingCompilation(
            scratch, "tools/build_defs/foo", "quote_includes=['foo/bar', 'baz/qux']"
        )
        val target: ConfiguredTarget = getConfiguredTarget("//foo:starlark_lib")
        assertThat(target).isNotNull()
        val action: CppCompileAction =
            getGeneratingAction(artifactByPath(getFilesToBuild(target), ".o")) as CppCompileAction
        assertThat(action.getArguments())
            .containsAtLeast("-iquote", "foo/bar", "-iquote", "baz/qux")
            .inOrder()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testFrameworkIncludeDirs() {
        createFilesForTestingCompilation(
            scratch, "tools/build_defs/foo", "framework_includes=['foo/bar', 'baz/qux']"
        )
        val target: ConfiguredTarget = getConfiguredTarget("//foo:starlark_lib")
        assertThat(target).isNotNull()
        val action: CppCompileAction =
            getGeneratingAction(artifactByPath(getFilesToBuild(target), ".o")) as CppCompileAction
        assertThat(action.getArguments()).containsAtLeast("-Ffoo/bar", "-Fbaz/qux").inOrder()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDefines() {
        createFilesForTestingCompilation(
            scratch, "tools/build_defs/foo", "defines=['DEFINE1', 'DEFINE2']"
        )
        val target: ConfiguredTarget = getConfiguredTarget("//foo:starlark_lib")
        assertThat(target).isNotNull()
        val action: CppCompileAction =
            getGeneratingAction(artifactByPath(getFilesToBuild(target), ".o")) as CppCompileAction
        assertThat(action.getArguments()).containsAtLeast("-DDEFINE1", "-DDEFINE2")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testLocalDefines() {
        createFilesForTestingCompilation(
            scratch, "tools/build_defs/foo", "local_defines=['DEFINE1', 'DEFINE2']"
        )
        val target: ConfiguredTarget = getConfiguredTarget("//foo:starlark_lib")
        assertThat(target).isNotNull()
        val action: CppCompileAction =
            getGeneratingAction(artifactByPath(getFilesToBuild(target), ".o")) as CppCompileAction
        assertThat(action.getArguments()).containsAtLeast("-DDEFINE1", "-DDEFINE2")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testIncludePrefix() {
        createFilesForTestingCompilation(
            scratch, "third_party/tools/build_defs/foo", "include_prefix='prefix'"
        )
        scratch.file(
            "third_party/bar/BUILD",
            """
        load("//third_party/tools/build_defs/foo:extension.bzl", "cc_starlark_library")

        cc_starlark_library(
            name = "starlark_lib",
            srcs = ["starlark_lib.cc"],
            private_hdrs = ["private_starlark_lib.h"],
            public_hdrs = ["starlark_lib.h"],
        )
        
        """.trimIndent()
        )
        val target: ConfiguredTarget = getConfiguredTarget("//third_party/bar:starlark_lib")
        assertThat(target).isNotNull()
        val ccInfo: CcInfo = CcInfo.get(target)
        Truth.assertThat(artifactsToStrings(ccInfo.getCcCompilationContext().getDirectPublicHdrs()))
            .contains(
                "bin third_party/bar/_virtual_includes/starlark_lib_suffix/prefix/starlark_lib.h"
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testStripIncludePrefix() {
        createFilesForTestingCompilation(
            scratch, "third_party/tools/build_defs/foo", "strip_include_prefix='v1'"
        )
        scratch.file(
            "third_party/bar/BUILD",
            """
        load("//third_party/tools/build_defs/foo:extension.bzl", "cc_starlark_library")

        cc_starlark_library(
            name = "starlark_lib",
            srcs = ["starlark_lib.cc"],
            private_hdrs = ["v1/private_starlark_lib.h"],
            public_hdrs = ["v1/starlark_lib.h"],
        )
        
        """.trimIndent()
        )
        val target: ConfiguredTarget = getConfiguredTarget("//third_party/bar:starlark_lib")
        assertThat(target).isNotNull()
        val ccInfo: CcInfo = CcInfo.get(target)
        Truth.assertThat(artifactsToStrings(ccInfo.getCcCompilationContext().getDirectPublicHdrs()))
            .contains("bin third_party/bar/_virtual_includes/starlark_lib_suffix/starlark_lib.h")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testStripIncludePrefixIncludePath() {
        createFilesForTestingCompilation(
            scratch, "third_party/tools/build_defs/foo", "strip_include_prefix='v1'"
        )
        scratch.file(
            "third_party/bar/BUILD",
            """
        load("//third_party/tools/build_defs/foo:extension.bzl", "cc_starlark_library")

        cc_starlark_library(
            name = "starlark_lib",
            srcs = ["starlark_lib.cc"],
            private_hdrs = ["v1/private_starlark_lib.h"],
            public_hdrs = ["v1/starlark_lib.h"],
        )
        
        """.trimIndent()
        )
        val target: ConfiguredTarget = getConfiguredTarget("//third_party/bar:starlark_lib")
        assertThat(target).isNotNull()
        val ccInfo: CcInfo = CcInfo.get(target)

        assertThat(ccInfo.getCcCompilationContext().getIncludeDirs())
            .containsExactly(
                targetConfiguration
                    .getBinFragment(RepositoryName.MAIN)
                    .getRelative("third_party/bar/_virtual_includes/starlark_lib_suffix")
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testStripIncludePrefixAndIncludePrefix() {
        createFilesForTestingCompilation(
            scratch,
            "third_party/tools/build_defs/foo",
            "strip_include_prefix='v1', include_prefix='prefix'"
        )
        scratch.file(
            "third_party/bar/BUILD",
            """
        load("//third_party/tools/build_defs/foo:extension.bzl", "cc_starlark_library")

        cc_starlark_library(
            name = "starlark_lib",
            srcs = ["starlark_lib.cc"],
            private_hdrs = ["v1/private_starlark_lib.h"],
            public_hdrs = ["v1/starlark_lib.h"],
        )
        
        """.trimIndent()
        )
        val target: ConfiguredTarget = getConfiguredTarget("//third_party/bar:starlark_lib")
        assertThat(target).isNotNull()
        val ccInfo: CcInfo = CcInfo.get(target)
        Truth.assertThat(artifactsToStrings(ccInfo.getCcCompilationContext().getDirectPublicHdrs()))
            .contains(
                "bin third_party/bar/_virtual_includes/starlark_lib_suffix/prefix/starlark_lib.h"
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testStripIncludePrefixAndIncludePrefixIncludePath() {
        createFilesForTestingCompilation(
            scratch,
            "third_party/tools/build_defs/foo",
            "strip_include_prefix='v1', include_prefix='prefix'"
        )
        scratch.file(
            "third_party/bar/BUILD",
            "load('//third_party/tools/build_defs/foo:extension.bzl', 'cc_starlark_library')",
            "cc_starlark_library(",
            "    name = 'starlark_lib',",
            "    srcs = ['starlark_lib.cc'],",
            "    public_hdrs = ['v1/starlark_lib.h'],",
            "    private_hdrs = ['v1/private_starlark_lib.h'],",
            ")"
        )
        val target: ConfiguredTarget = getConfiguredTarget("//third_party/bar:starlark_lib")
        assertThat(target).isNotNull()
        val ccInfo: CcInfo = CcInfo.get(target)
        assertThat(ccInfo.getCcCompilationContext().getIncludeDirs())
            .containsExactly(
                targetConfiguration
                    .getBinFragment(RepositoryName.MAIN)
                    .getRelative("third_party/bar/_virtual_includes/starlark_lib_suffix")
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testHeaders() {
        createFilesForTestingCompilation(
            scratch, "tools/build_defs/foo",  /* compileProviderLines= */""
        )
        val target: ConfiguredTarget = getConfiguredTarget("//foo:starlark_lib")
        assertThat(target).isNotNull()
        val ccInfo: CcInfo = CcInfo.get(target)
        Truth.assertThat(artifactsToStrings(ccInfo.getCcCompilationContext().getDeclaredIncludeSrcs()))
            .containsAtLeast(
                "src foo/dep2.h", "src foo/starlark_lib.h", "src foo/private_starlark_lib.h"
            )
        Truth.assertThat(artifactsToStrings(ccInfo.getCcCompilationContext().getTextualHdrs()))
            .containsExactly("src foo/textual_hdr.h")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCompileOutputHasSuffix() {
        createFilesForTestingCompilation(
            scratch, "tools/build_defs/foo",  /* compileProviderLines= */""
        )
        val target: ConfiguredTarget = getConfiguredTarget("//foo:starlark_lib")
        assertThat(target).isNotNull()
        Truth.assertThat(artifactsToStrings(getFilesToBuild(target)))
            .contains("bin foo/_objs/starlark_lib_suffix/starlark_lib.o")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCompilationContexts() {
        createFilesForTestingCompilation(
            scratch, "tools/build_defs/foo",  /* compileProviderLines= */""
        )
        assertThat(getConfiguredTarget("//foo:bin")).isNotNull()
        val target: ConfiguredTarget = getConfiguredTarget("//foo:starlark_lib")
        val action: CppCompileAction =
            getGeneratingAction(artifactByPath(getFilesToBuild(target), ".o")) as CppCompileAction
        assertThat(action.getArguments()).containsAtLeast("-DDEFINE_DEP1", "-DDEFINE_DEP2")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testLinkingOutputs() {
        createFiles(scratch, "tools/build_defs/foo")
        val target: ConfiguredTarget = getConfiguredTarget("//foo:starlark_lib")
        assertThat(target).isNotNull()
        val libraries: net.starlark.java.eval.Sequence<StarlarkInfo?> =
            getMyInfoFromTarget(target).getValue("libraries") as net.starlark.java.eval.Sequence<StarlarkInfo?>
        Truth.assertThat(
            libraries.stream()
                .map<Any?>(LibraryToLink::wrap)
                .map<Any?> { x: Any? -> x.getResolvedSymlinkDynamicLibrary().getFilename() }
                .collect(com.google.common.collect.ImmutableList.toImmutableList<Any?>()))
            .contains("libstarlark_lib.so")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testUserLinkFlags() {
        createFilesForTestingLinking(
            scratch, "tools/build_defs/foo", "user_link_flags=['-LINKING_OPTION']"
        )
        val target: ConfiguredTarget = getConfiguredTarget("//foo:starlark_lib")
        assertThat(target).isNotNull()
        com.google.common.truth.Subject.contains("-LINKING_OPTION")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testLinkingContexts() {
        createFilesForTestingLinking(scratch, "tools/build_defs/foo",  /* linkProviderLines= */"")
        assertThat(getConfiguredTarget("//foo:bin")).isNotNull()
        val target: ConfiguredTarget = getConfiguredTarget("//foo:bin")
        val action: SpawnAction =
            getGeneratingAction(artifactByPath(getFilesToBuild(target), "bin")) as SpawnAction
        assertThat(action.getArguments()).containsAtLeast("-DEP1_LINKOPT", "-DEP2_LINKOPT")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAlwayslinkTrue() {
        createFilesForTestingLinking(scratch, "tools/build_defs/foo", "alwayslink=True")
        val target: ConfiguredTarget = getConfiguredTarget("//foo:starlark_lib")
        assertThat(target).isNotNull()
        assertThat(
            CcInfo.get(target).getCcLinkingContext().getLibraries().toList().stream()
                .filter(LibraryToLink::getAlwayslink)
                .collect(com.google.common.collect.ImmutableList.toImmutableList<E?>())
        )
            .hasSize(1)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAlwayslinkFalse() {
        createFilesForTestingLinking(scratch, "tools/build_defs/foo", "alwayslink=False")
        val target: ConfiguredTarget = getConfiguredTarget("//foo:starlark_lib")
        assertThat(target).isNotNull()
        assertThat(
            CcInfo.get(target).getCcLinkingContext().getLibraries().toList().stream()
                .filter(LibraryToLink::getAlwayslink)
                .collect(com.google.common.collect.ImmutableList.toImmutableList<E?>())
        )
            .isEmpty()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAdditionalLinkingInputs() {
        createFilesForTestingLinking(
            scratch, "tools/build_defs/foo", "additional_inputs=ctx.files._additional_inputs"
        )
        val target: ConfiguredTarget = getConfiguredTarget("//foo:starlark_lib")
        assertThat(target).isNotNull()
        assertThat(CcInfo.get(target).getCcLinkingContext().getNonCodeInputs().toList()).hasSize(1)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAdditionalCompilationInputs() {
        createFilesForTestingCompilation(
            scratch, "tools/build_defs/foo", "additional_inputs=ctx.files._additional_compiler_inputs"
        )
        val target: ConfiguredTarget = getConfiguredTarget("//foo:starlark_lib")
        assertThat(target).isNotNull()
        val action: CppCompileAction =
            getGeneratingAction(artifactByPath(getFilesToBuild(target), ".o")) as CppCompileAction
        Truth.assertThat(artifactsToStrings(action.getMandatoryInputs()))
            .contains("src foo/extra_compiler_input")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testPossibleSrcsExtensions() {
        doTestPossibleExtensionsOfSrcsAndHdrs(
            "srcs", CppFileTypes.ALL_C_CLASS_SOURCE.including(CppFileTypes.ASSEMBLER).getExtensions()
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testPossiblePrivateHdrExtensions() {
        doTestPossibleExtensionsOfSrcsAndHdrs("private_hdrs", CppFileTypes.CPP_HEADER.getExtensions())
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testPossiblePublicHdrExtensions() {
        doTestPossibleExtensionsOfSrcsAndHdrs("public_hdrs", CppFileTypes.CPP_HEADER.getExtensions())
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTreeArtifactSrcs() {
        doTestTreeAtrifactInSrcsAndHdrs("srcs")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTreeArtifactPrivateHdrs() {
        doTestTreeAtrifactInSrcsAndHdrs("private_hdrs")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTreeArtifactPublicHdrs() {
        doTestTreeAtrifactInSrcsAndHdrs("public_hdrs")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTransitiveLinkWithDeps() {
        setupTestTransitiveLink(scratch, "linking_contexts = dep_linking_contexts")
        val target: ConfiguredTarget = getConfiguredTarget("//foo:bin")
        assertThat(target).isNotNull()
        val executable: Artifact = getMyInfoFromTarget(target).getValue("executable") as Artifact
        Truth.assertThat(artifactsToStrings(getGeneratingAction(executable).getInputs()))
            .containsAtLeast("bin foo/libdep1.a", "bin foo/libdep2.a")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTransitiveLinkForDynamicLibrary() {
        setupTestTransitiveLink(scratch, "output_type = 'dynamic_library'")
        val target: ConfiguredTarget = getConfiguredTarget("//foo:bin")
        assertThat(target).isNotNull()
        val library: LibraryToLink? =
            LibraryToLink.wrap(getMyInfoFromTarget(target).getValue("library") as StarlarkInfo?)
        assertThat(library).isNotNull()
        val executable: Any? = getMyInfoFromTarget(target).getValue("executable")
        Truth.assertThat(Starlark.isNullOrNone(executable)).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCustomNameOutputArtifact() {
        createCcBinRule(
            scratch,  /* internalApi= */
            true,
            "output_type = 'dynamic_library'",
            " main_output=ctx.actions.declare_file('custom_name.so')"
        )
        scratch.file(
            "foo/BUILD",
            """
        load("//bazel_internal/test_rules/cc:extension.bzl", "cc_bin")

        cc_bin(
            name = "bin",
        )
        
        """.trimIndent()
        )

        val target: ConfiguredTarget = getConfiguredTarget("//foo:bin")
        assertThat(target).isNotNull()

        val library: LibraryToLink =
            LibraryToLink.wrap(getMyInfoFromTarget(target).getValue("library") as StarlarkInfo?)
        var dynamicLibrary: Artifact? = library.getResolvedSymlinkDynamicLibrary()
        if (dynamicLibrary == null) {
            dynamicLibrary = library.getDynamicLibrary()
        }

        assertThat(dynamicLibrary).isNotNull()
        assertThat(dynamicLibrary.getFilename()).isEqualTo("custom_name.so")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testInterfaceLibraryProducedForTransitiveLinkOnWindows() {
        getAnalysisMock()
            .ccSupport()
            .setupCcToolchainConfig(
                mockToolsConfig,
                CcToolchainConfig.builder()
                    .withFeatures(
                        CppRuleClasses.SUPPORTS_DYNAMIC_LINKER,
                        CppRuleClasses.TARGETS_WINDOWS,
                        CppRuleClasses.SUPPORTS_INTERFACE_SHARED_LIBRARIES,
                        CppRuleClasses.COPY_DYNAMIC_LIBRARIES_TO_BINARY
                    )
            )
        setupTestTransitiveLink(scratch, "output_type = 'dynamic_library'")
        val target: ConfiguredTarget = getConfiguredTarget("//foo:bin")
        assertThat(target).isNotNull()
        val library: LibraryToLink =
            LibraryToLink.wrap(getMyInfoFromTarget(target).getValue("library") as StarlarkInfo?)
        assertThat(library).isNotNull()
        assertThat(library.getDynamicLibrary()).isNotNull()
        assertThat(library.getInterfaceLibrary()).isNotNull()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testEmitInterfaceLibrary() {
        getAnalysisMock()
            .ccSupport()
            .setupCcToolchainConfig(
                mockToolsConfig,
                CcToolchainConfig.builder()
                    .withFeatures(
                        CppRuleClasses.SUPPORTS_DYNAMIC_LINKER,
                        CppRuleClasses.SUPPORTS_INTERFACE_SHARED_LIBRARIES,
                        CppRuleClasses.COPY_DYNAMIC_LIBRARIES_TO_BINARY
                    )
            )
        setupTestTransitiveLinkInternal(
            scratch,  /* internalApi= */
            true,
            "output_type = 'dynamic_library'",
            "emit_interface_shared_library = True"
        )
        val target: ConfiguredTarget = getConfiguredTarget("//foo:bin")
        assertThat(target).isNotNull()
        val library: LibraryToLink =
            LibraryToLink.wrap(getMyInfoFromTarget(target).getValue("library") as StarlarkInfo?)
        assertThat(library).isNotNull()
        assertThat(library.getDynamicLibrary()).isNotNull()
        assertThat(library.getInterfaceLibrary()).isNotNull()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTransitiveLinkForExecutable() {
        setupTestTransitiveLink(scratch, "output_type = 'executable'")
        val target: ConfiguredTarget = getConfiguredTarget("//foo:bin")
        assertThat(target).isNotNull()
        val executable: Artifact? = getMyInfoFromTarget(target).getValue("executable") as Artifact?
        assertThat(executable).isNotNull()
        val library: Any? = getMyInfoFromTarget(target).getValue("library")
        Truth.assertThat(Starlark.isNullOrNone(library)).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTransitiveLinkWithCompilationOutputs() {
        setupTestTransitiveLink(scratch, "compilation_outputs=objects")
        val target: ConfiguredTarget = getConfiguredTarget("//foo:bin")
        assertThat(target).isNotNull()
        val executable: Artifact = getMyInfoFromTarget(target).getValue("executable") as Artifact
        Truth.assertThat(artifactsToStrings(getGeneratingAction(executable).getInputs()))
            .contains("src foo/file.o")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testLinkStampExpliciltyEnabledOverridesNoStampFlag() {
        useConfiguration(
            "--nostamp",
            "--platforms=" + TestConstants.PLATFORM_LABEL,
            "--experimental_platform_in_output_dir",
            String.format(
                "--experimental_override_name_platform_in_output_dir=%s=k8",
                TestConstants.PLATFORM_LABEL
            )
        )
        setupTestTransitiveLink(scratch, "stamp=1", "linking_contexts=dep_linking_contexts")
        assertStampEnabled(getLinkstampCompileAction("//foo:bin"))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testLinkExplicitlyDisabledOverridesStampFlag() {
        useConfiguration(
            "--nostamp",
            "--platforms=" + TestConstants.PLATFORM_LABEL,
            "--experimental_platform_in_output_dir",
            String.format(
                "--experimental_override_name_platform_in_output_dir=%s=k8",
                TestConstants.PLATFORM_LABEL
            )
        )
        setupTestTransitiveLink(scratch, "stamp=0", "linking_contexts=dep_linking_contexts")
        assertStampDisabled(getLinkstampCompileAction("//foo:bin"))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testLinkStampUseFlagStamp() {
        useConfiguration(
            "--stamp",
            "--platforms=" + TestConstants.PLATFORM_LABEL,
            "--experimental_platform_in_output_dir",
            String.format(
                "--experimental_override_name_platform_in_output_dir=%s=k8",
                TestConstants.PLATFORM_LABEL
            )
        )
        setupTestTransitiveLink(scratch, "stamp=-1", "linking_contexts=dep_linking_contexts")
        assertStampEnabled(getLinkstampCompileAction("//foo:bin"))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testLinkStampUseFlagNoStamp() {
        useConfiguration(
            "--nostamp",
            "--platforms=" + TestConstants.PLATFORM_LABEL,
            "--experimental_platform_in_output_dir",
            String.format(
                "--experimental_override_name_platform_in_output_dir=%s=k8",
                TestConstants.PLATFORM_LABEL
            )
        )
        setupTestTransitiveLink(scratch, "stamp=-1", "linking_contexts=dep_linking_contexts")
        assertStampDisabled(getLinkstampCompileAction("//foo:bin"))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testLinkStampDisabledByDefaultDespiteStampFlag() {
        useConfiguration(
            "--stamp",
            "--platforms=" + TestConstants.PLATFORM_LABEL,
            "--experimental_platform_in_output_dir",
            String.format(
                "--experimental_override_name_platform_in_output_dir=%s=k8",
                TestConstants.PLATFORM_LABEL
            )
        )
        setupTestTransitiveLink(scratch, "linking_contexts=dep_linking_contexts")
        assertStampDisabled(getLinkstampCompileAction("//foo:bin"))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testLinkStampInvalid() {
        setupTestTransitiveLink(scratch, "stamp=2")
        checkError(
            "//foo:bin",
            "stamp value 2 is not supported, must be 0 (disabled), 1 (enabled), or -1 (default)"
        )
    }

    @Throws(LabelSyntaxException::class, net.starlark.java.eval.EvalException::class)
    private fun getLinkstampCompileAction(label: String?): CppCompileAction {
        val target: ConfiguredTarget = getConfiguredTarget(label)
        val executable: Artifact = getMyInfoFromTarget(target).getValue("executable") as Artifact
        val generatingAction: SpawnAction = getGeneratingAction(executable) as SpawnAction
        val compiledLinkstamp: Artifact =
            ActionsTestUtil.getFirstArtifactEndingWith(generatingAction.getInputs(), "version.o")
        val linkstampCompileAction: CppCompileAction =
            getGeneratingAction(compiledLinkstamp) as CppCompileAction
        assertThat(linkstampCompileAction.getMnemonic()).isEqualTo("CppLinkstampCompile")
        return linkstampCompileAction
    }

    fun getBuildInfoFile(commonPath: String?): String {
        if (AnalysisMock.get().isThisBazel()) {
            return relativeOutputPath + "/k8-fastbuild/bin/external/bazel_tools/" + commonPath
        } else {
            return relativeOutputPath + "/k8-fastbuild/bin/" + commonPath
        }
    }

    @Throws(CommandLineExpansionException::class)
    private fun assertStampEnabled(linkstampAction: CppCompileAction) {
        com.google.common.truth.Subject.contains(getBuildInfoFile(NON_REDACTED_ARTIFACT_PATH))
    }

    @Throws(CommandLineExpansionException::class)
    private fun assertStampDisabled(linkstampAction: CppCompileAction) {
        com.google.common.truth.Subject.contains(getBuildInfoFile(REDACTED_ARTIFACT_PATH))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testApiWithAspectsOnTargetsInExternalRepos() {
        if (!AnalysisMock.get().isThisBazel()) {
            return
        }
        createFilesForTestingCompilation(
            scratch, "tools/build_defs/foo",  /* compileProviderLines= */""
        )
        scratch.appendFile(
            "MODULE.bazel",
            "bazel_dep(name = 'r')",
            "local_path_override(module_name = 'r', path = '/r')"
        )
        scratch.file("/r/MODULE.bazel", "module(name = 'r')")
        scratch.file(
            "/r/p/BUILD",
            """
        load("@rules_cc//cc:cc_library.bzl", "cc_library")
        cc_library(
            name = "a",
            srcs = ["a.cc"],
        )
        
        """.trimIndent()
        )
        invalidatePackages()
        scratch.file(
            "b/BUILD",
            """
        load("//tools/build_defs/foo:extension.bzl", "cc_starlark_library")

        cc_starlark_library(
            name = "b",
            srcs = ["b.cc"],
            aspect_deps = ["@r//p:a"],
        )
        
        """.trimIndent()
        )
        assertThat(getConfiguredTarget("//b:b")).isNotNull()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testObjectsApi() {
        useConfiguration("--compilation_mode=opt")
        AnalysisMock.get()
            .ccSupport()
            .setupCcToolchainConfig(
                mockToolsConfig,
                CcToolchainConfig.builder()
                    .withFeatures(CppRuleClasses.SUPPORTS_PIC, CppRuleClasses.PIC)
            )

        scratchObjectsProvidingRule()

        val key: Provider.Key =
            Key(keyForBuild(Label.parseCanonical("//foo:foo.bzl")), "FooInfo")
        val fooLibrary: LibraryToLink? =
            com.google.common.collect.Iterables.getFirst<T?>(
                CcInfo.get(getConfiguredTarget("//foo:dep"))
                    .getCcLinkingContext()
                    .getLibraries()
                    .toList(),
                null
            )
        val fooInfo: StarlarkInfo =
            getConfiguredTarget("//foo:foo").get(StarlarkProviderIdentifier.forKey(key)) as StarlarkInfo

        assertThat(fooLibrary.getObjectFiles()).isEqualTo(fooInfo.getValue("objects"))
        assertThat(fooLibrary.getPicObjectFiles()).isEqualTo(fooInfo.getValue("pic_objects"))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testObjectsApiNeverReturningNones() {
        scratchObjectsProvidingRule()

        val key: Provider.Key =
            Key(keyForBuild(Label.parseCanonical("//foo:foo.bzl")), "FooInfo")

        // Default toolchain is without PIC support, so pic_objects should be None
        val fooInfoForPic: StarlarkInfo =
            getConfiguredTarget("//foo:foo").get(StarlarkProviderIdentifier.forKey(key)) as StarlarkInfo

        val picObjects: Any? = fooInfoForPic.getValue("pic_objects")
        Truth.assertThat(picObjects).isNotEqualTo(Starlark.NONE)
        Truth.assertThat(picObjects as MutableList<*>?).isEmpty()

        // With PIC and the default compilation_mode which is fastbuild C++ rules only produce PIC
        // objects.
        AnalysisMock.get()
            .ccSupport()
            .setupCcToolchainConfig(
                mockToolsConfig,
                CcToolchainConfig.builder()
                    .withFeatures(CppRuleClasses.SUPPORTS_PIC, CppRuleClasses.PIC)
            )
        invalidatePackages()
        val fooInfoForNoPic: StarlarkInfo =
            getConfiguredTarget("//foo:foo").get(StarlarkProviderIdentifier.forKey(key)) as StarlarkInfo

        val objects: Any? = fooInfoForNoPic.getValue("objects")
        Truth.assertThat(objects).isNotEqualTo(Starlark.NONE)
        Truth.assertThat(objects as MutableList<*>?).isEmpty()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testLtoBitcodeFilesApi() {
        useConfiguration("--compilation_mode=opt", "--features=thin_lto")
        AnalysisMock.get()
            .ccSupport()
            .setupCcToolchainConfig(
                mockToolsConfig,
                CcToolchainConfig.builder()
                    .withFeatures(
                        CppRuleClasses.THIN_LTO, CppRuleClasses.SUPPORTS_PIC, CppRuleClasses.PIC
                    )
            )

        scratchObjectsProvidingRule()

        val key: Provider.Key =
            Key(keyForBuild(Label.parseCanonical("//foo:foo.bzl")), "FooInfo")
        val fooLibrary: LibraryToLink? =
            com.google.common.collect.Iterables.getFirst<T?>(
                CcInfo.get(getConfiguredTarget("//foo:dep"))
                    .getCcLinkingContext()
                    .getLibraries()
                    .toList(),
                null
            )
        val fooInfo: StarlarkInfo =
            getConfiguredTarget("//foo:foo").get(StarlarkProviderIdentifier.forKey(key)) as StarlarkInfo

        Truth.assertThat(com.google.common.collect.ImmutableList.copyOf(fooLibrary.getLtoCompilationContextBitcodeFiles()))
            .isEqualTo(fooInfo.getValue("lto_bitcode_files"))
        Truth.assertThat(fooLibrary.getLtoCompilationContextBitcodeFiles() as MutableMap<*, *>?).isNotEmpty()

        Truth.assertThat(com.google.common.collect.ImmutableList.copyOf(fooLibrary.getPicLtoCompilationContextBitcodeFiles()))
            .isEqualTo(fooInfo.getValue("pic_lto_bitcode_files"))
        Truth.assertThat(fooLibrary.getPicLtoCompilationContextBitcodeFiles() as MutableMap<*, *>?).isNotEmpty()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testLtoBitcodeFilesApiNeverReturningNones() {
        // We do not add --features=thin_lto for this test.
        useConfiguration("--compilation_mode=opt")
        AnalysisMock.get()
            .ccSupport()
            .setupCcToolchainConfig(
                mockToolsConfig,
                CcToolchainConfig.builder() // We do not enable the THIN_LTO feature for this test.
                    .withFeatures(CppRuleClasses.SUPPORTS_PIC, CppRuleClasses.PIC)
            )

        scratchObjectsProvidingRule()

        val key: Provider.Key =
            Key(keyForBuild(Label.parseCanonical("//foo:foo.bzl")), "FooInfo")
        val fooInfo: StarlarkInfo =
            getConfiguredTarget("//foo:foo").get(StarlarkProviderIdentifier.forKey(key)) as StarlarkInfo

        val picLtoBitcodeFiles: Any? = fooInfo.getValue("pic_lto_bitcode_files")
        Truth.assertThat(picLtoBitcodeFiles).isNotEqualTo(Starlark.NONE)
        Truth.assertThat(picLtoBitcodeFiles as MutableList<*>?).isEmpty()

        val ltoBitcodeFiles: Any? = fooInfo.getValue("lto_bitcode_files")
        Truth.assertThat(ltoBitcodeFiles).isNotEqualTo(Starlark.NONE)
        Truth.assertThat(ltoBitcodeFiles as MutableList<*>?).isEmpty()
    }

    @Throws(IOException::class)
    private fun scratchObjectsProvidingRule() {
        scratch.file(
            "foo/BUILD",
            """
        load("@rules_cc//cc:cc_library.bzl", "cc_library")
        load(":foo.bzl", "foo")

        foo(
            name = "foo",
            dep = ":dep",
        )

        cc_library(
            name = "dep",
            srcs = ["dep.cc"],
        )
        
        """.trimIndent()
        )
        scratch.file(
            "foo/foo.bzl",
            """
        load("@rules_cc//cc/common:cc_info.bzl", "CcInfo")

        FooInfo = provider(
            fields = ["objects", "pic_objects", "lto_bitcode_files", "pic_lto_bitcode_files"],
        )

        def _foo_impl(ctx):
            lib = ctx.attr.dep[CcInfo].linking_context.linker_inputs.to_list()[0].libraries[0]
            return [FooInfo(
                objects = lib.objects,
                pic_objects = lib.pic_objects,
                lto_bitcode_files = lib.lto_bitcode_files,
                pic_lto_bitcode_files = lib.pic_lto_bitcode_files,
            )]

        foo = rule(
            implementation = _foo_impl,
            attrs = {
                "dep": attr.label(),
            },
        )
        
        """.trimIndent()
        )
    }

    @Throws(java.lang.Exception::class)
    private fun doTestPossibleExtensionsOfSrcsAndHdrs(attrName: String?, extensions: MutableList<String?>) {
        createFiles(scratch, "tools/build_defs/foo")
        reporter.removeHandler(failFastHandler)

        for (extension in extensions) {
            scratch.deleteFile("bar/BUILD")
            scratch.file(
                "bar/BUILD",
                "load('//tools/build_defs/foo:extension.bzl', 'cc_starlark_library')",
                "cc_starlark_library(",
                "    name = 'starlark_lib',",
                "    " + attrName + " = ['file" + extension + "'],",
                ")"
            )
            getConfiguredTarget("//bar:starlark_lib")
            assertNoEvents()
        }
    }

    @Throws(java.lang.Exception::class)
    private fun doTestTreeAtrifactInSrcsAndHdrs(attrName: String?) {
        createFiles(scratch, "tools/build_defs/foo")
        reporter.removeHandler(failFastHandler)

        scratch.file(
            "bar/create_tree_artifact.bzl",
            """
        def _impl(ctx):
            tree = ctx.actions.declare_directory("dir")
            ctx.actions.run_shell(
                outputs = [tree],
                inputs = [],
                arguments = [tree.path],
                command = "mkdir ${'$'}1",
            )
            return [DefaultInfo(files = depset([tree]))]

        create_tree_artifact = rule(implementation = _impl)
        
        """.trimIndent()
        )
        scratch.file(
            "bar/BUILD",
            "load('//tools/build_defs/foo:extension.bzl', 'cc_starlark_library')",
            "load(':create_tree_artifact.bzl', 'create_tree_artifact')",
            "create_tree_artifact(name = 'tree_artifact')",
            "cc_starlark_library(",
            "    name = 'starlark_lib',",
            "    " + attrName + " = [':tree_artifact'],",
            ")"
        )
        getConfiguredTarget("//bar:starlark_lib")
        assertNoEvents()
    }

    @Throws(java.lang.Exception::class)
    private fun doTestCcOutputsWrongExtension(attrName: String?, paramName: String?) {
        setupCcOutputsTest()
        scratch.file(
            "foo/BUILD",
            "load('//tools/build_defs/foo:extension.bzl', 'cc_starlark_library')",
            "cc_starlark_library(",
            "    name = 'starlark_lib',",
            "    " + attrName + " = 'object.cannotpossiblybevalid',",
            ")"
        )
        reporter.removeHandler(failFastHandler)
        getConfiguredTarget("//foo:starlark_lib")
        assertContainsEvent(
            "has wrong extension. The list of possible extensions for '" + paramName + "'"
        )
    }

    @Throws(java.lang.Exception::class)
    private fun doTestCcOutputsRightExtension(paramName: String?) {
        setupCcOutputsTest()
        reporter.removeHandler(failFastHandler)

        for (extension in Link.OBJECT_FILETYPES.getExtensions()) {
            scratch.deleteFile("foo/BUILD")
            scratch.file(
                "foo/BUILD",
                "load('//tools/build_defs/foo:extension.bzl', 'cc_starlark_library')",
                "cc_starlark_library(",
                "    name = 'starlark_lib',",
                "    " + paramName + " = 'object1" + extension + "',",
                ")"
            )
            getConfiguredTarget("//foo:starlark_lib")
            assertNoEvents()
        }
    }

    @Throws(java.lang.Exception::class)
    private fun setupCcOutputsTest() {
        scratch.overwriteFile("tools/build_defs/foo/BUILD")
        scratch.file(
            "tools/build_defs/foo/extension.bzl",
            """
        load("//myinfo:myinfo.bzl", "MyInfo")
        load('@rules_cc//cc/common:cc_common.bzl', 'cc_common')

        def _cc_starlark_library_impl(ctx):
            objects = []
            pic_objects = []
            if ctx.file.object1 != None:
                objects.append(ctx.file.object1)
            if ctx.file.pic_object1 != None:
                pic_objects.append(ctx.file.pic_object1)
            c1 = cc_common.create_compilation_outputs(
                objects = depset(objects),
                pic_objects = depset(pic_objects),
            )
            objects = []
            pic_objects = []
            if ctx.file.object2 != None:
                objects.append(ctx.file.object2)
            if ctx.file.pic_object2 != None:
                pic_objects.append(ctx.file.pic_object2)
            c2 = cc_common.create_compilation_outputs(
                objects = depset(objects),
                pic_objects = depset(pic_objects),
            )
            compilation_outputs = cc_common.merge_compilation_outputs(
                compilation_outputs = [c1, c2],
            )
            return [MyInfo(compilation_outputs = compilation_outputs)]

        cc_starlark_library = rule(
            implementation = _cc_starlark_library_impl,
            attrs = {
                "object1": attr.label(allow_single_file = True),
                "pic_object1": attr.label(allow_single_file = True),
                "object2": attr.label(allow_single_file = True),
                "pic_object2": attr.label(allow_single_file = True),
            },
        )
        
        """.trimIndent()
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCcLibraryPropagatesCcInfoWithDirectHeaders() {
        setupDirectHeaderExtractionSupport(scratch)
        setupCcLibraryDirectPropagationTestTargets(scratch)

        val fooTarget: ConfiguredTarget = getConfiguredTarget("//direct:foo")
        val fooDirectHeaders: Iterable<Artifact?>? = getArtifactsFromMyInfo(fooTarget, "direct_headers")
        assertThat(baseArtifactNames(fooDirectHeaders)).containsExactly("foo.h", "foo_impl.h")

        val fooDirectPublicHeaders: Iterable<Artifact?>? =
            getArtifactsFromMyInfo(fooTarget, "direct_public_headers")
        assertThat(baseArtifactNames(fooDirectPublicHeaders)).containsExactly("foo.h")

        val fooDirectPrivateHeaders: Iterable<Artifact?>? =
            getArtifactsFromMyInfo(fooTarget, "direct_private_headers")
        assertThat(baseArtifactNames(fooDirectPrivateHeaders)).containsExactly("foo_impl.h")

        val barTarget: ConfiguredTarget = getConfiguredTarget("//direct:bar")
        val barDirectHeaders: Iterable<Artifact?>? = getArtifactsFromMyInfo(barTarget, "direct_headers")
        assertThat(baseArtifactNames(barDirectHeaders)).containsExactly("bar.h")

        val barDirectPublicHeaders: Iterable<Artifact?>? =
            getArtifactsFromMyInfo(barTarget, "direct_public_headers")
        assertThat(baseArtifactNames(barDirectPublicHeaders)).containsExactly("bar.h")

        val barDirectPrivateHeaders: Iterable<Artifact?>? =
            getArtifactsFromMyInfo(barTarget, "direct_private_headers")
        Truth.assertThat(barDirectPrivateHeaders).isEmpty()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCcLibraryPropagatesCcInfoWithDirectTextualHeaders() {
        setupDirectHeaderExtractionSupport(scratch)
        setupCcLibraryDirectPropagationTestTargets(scratch)

        val fooTarget: ConfiguredTarget = getConfiguredTarget("//direct:foo")
        val fooDirectTextualHeaders: Iterable<Artifact?>? =
            getArtifactsFromMyInfo(fooTarget, "direct_textual_headers")
        assertThat(baseArtifactNames(fooDirectTextualHeaders)).containsExactly("foo.def")

        val barTarget: ConfiguredTarget = getConfiguredTarget("//direct:bar")
        val barDirectTextualHeaders: Iterable<Artifact?>? =
            getArtifactsFromMyInfo(barTarget, "direct_textual_headers")
        assertThat(baseArtifactNames(barDirectTextualHeaders)).containsExactly("bar.def")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testMergeCcInfosWithDirects() {
        AnalysisMock.get()
            .ccSupport()
            .setupCcToolchainConfig(
                mockToolsConfig,
                CcToolchainConfig.builder().withFeatures(MockCcSupport.HEADER_MODULES_FEATURES)
            )

        scratch.file(
            "direct/cc_merger.bzl",
            """
        load('@rules_cc//cc/common:cc_common.bzl', 'cc_common')
        load("@rules_cc//cc/common:cc_info.bzl", "CcInfo")
        def _cc_merger_impl(ctx):
            direct_cc_infos = [dep[CcInfo] for dep in ctx.attr.exports]
            cc_infos = [dep[CcInfo] for dep in ctx.attr.deps]
            return [cc_common.merge_cc_infos(
                direct_cc_infos = direct_cc_infos,
                cc_infos = cc_infos,
            )]

        cc_merger = rule(
            _cc_merger_impl,
            attrs = {
                "deps": attr.label_list(providers = [[CcInfo]]),
                "exports": attr.label_list(providers = [[CcInfo]]),
            },
        )
        
        """.trimIndent()
        )
        scratch.file(
            "direct/BUILD",
            """
        load("@rules_cc//cc:cc_library.bzl", "cc_library")
        load("//direct:cc_merger.bzl", "cc_merger")

        cc_library(
            name = "public1",
            srcs = [
                "public1.cc",
                "public1_impl.h",
            ],
            hdrs = ["public1.h"],
            textual_hdrs = ["public1.inc"],
        )

        cc_library(
            name = "public2",
            srcs = [
                "public2.cc",
                "public2_impl.h",
            ],
            hdrs = ["public2.h"],
            textual_hdrs = ["public2.inc"],
        )

        cc_library(
            name = "private",
            srcs = [
                "private.cc",
                "private_impl.h",
            ],
            hdrs = ["private.h"],
            textual_hdrs = ["private.inc"],
        )

        cc_merger(
            name = "merge",
            exports = [
                ":public1",
                ":public2",
            ],
            deps = [":private"],
        )
        
        """.trimIndent()
        )

        val lib: ConfiguredTarget = getConfiguredTarget("//direct:merge")
        val ccCompilationContext: CcCompilationContext = CcInfo.get(lib).getCcCompilationContext()
        assertThat(
            baseArtifactNames(
                ccCompilationContext.getExportingModuleMaps().stream()
                    .map(CppModuleMap::getArtifact)
                    .collect(com.google.common.collect.ImmutableList.toImmutableList<E?>())
            )
        )
            .containsExactly("public1.cppmap", "public2.cppmap")
        assertThat(baseArtifactNames(ccCompilationContext.getDirectPublicHdrs()))
            .containsExactly("public1.h", "public2.h")
        assertThat(baseArtifactNames(ccCompilationContext.getDirectPrivateHdrs()))
            .containsExactly("public1_impl.h", "public2_impl.h")
        assertThat(baseArtifactNames(ccCompilationContext.getTextualHdrs()))
            .containsExactly("public1.inc", "public2.inc")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testMergeCompilationContexts() {
        AnalysisMock.get()
            .ccSupport()
            .setupCcToolchainConfig(
                mockToolsConfig,
                CcToolchainConfig.builder().withFeatures(MockCcSupport.HEADER_MODULES_FEATURES)
            )

        scratch.file(
            "direct/cc_merger.bzl",
            """
        load('@rules_cc//cc/common:cc_common.bzl', 'cc_common')
        load("@rules_cc//cc/common:cc_info.bzl", "CcInfo")
        def _cc_merger_impl(ctx):
            compilation_contexts = [dep[CcInfo].compilation_context for dep in ctx.attr.deps]
            merged_context = cc_common.merge_compilation_contexts(
                compilation_contexts = compilation_contexts,
            )
            return [CcInfo(compilation_context = merged_context)]

        cc_merger = rule(
            _cc_merger_impl,
            attrs = {
                "deps": attr.label_list(providers = [[CcInfo]]),
            },
        )
        
        """.trimIndent()
        )
        scratch.file(
            "direct/BUILD",
            """
        load("@rules_cc//cc:cc_library.bzl", "cc_library")
        load("//direct:cc_merger.bzl", "cc_merger")

        cc_library(
            name = "public1",
            srcs = [
                "public1.cc",
                "public1_impl.h",
            ],
            hdrs = ["public1.h"],
            textual_hdrs = ["public1.inc"],
        )

        cc_library(
            name = "public2",
            srcs = [
                "public2.cc",
                "public2_impl.h",
            ],
            hdrs = ["public2.h"],
            textual_hdrs = ["public2.inc"],
        )

        cc_merger(
            name = "merge",
            deps = [
                ":public1",
                ":public2",
            ],
        )
        
        """.trimIndent()
        )

        val lib: ConfiguredTarget = getConfiguredTarget("//direct:merge")
        val ccCompilationContext: CcCompilationContext = CcInfo.get(lib).getCcCompilationContext()
        assertThat(
            baseArtifactNames(
                ccCompilationContext.getExportingModuleMaps().stream()
                    .map(CppModuleMap::getArtifact)
                    .collect(com.google.common.collect.ImmutableList.toImmutableList<E?>())
            )
        )
            .containsExactly("public1.cppmap", "public2.cppmap")
        assertThat(baseArtifactNames(ccCompilationContext.getDirectPublicHdrs()))
            .containsExactly("public1.h", "public2.h")
        assertThat(baseArtifactNames(ccCompilationContext.getDirectPrivateHdrs()))
            .containsExactly("public1_impl.h", "public2_impl.h")
        assertThat(baseArtifactNames(ccCompilationContext.getTextualHdrs()))
            .containsExactly("public1.inc", "public2.inc")
    }

    @Throws(java.lang.Exception::class)
    private fun setupDebugPackageProviderTest(fission: String?) {
        getAnalysisMock()
            .ccSupport()
            .setupCcToolchainConfig(
                mockToolsConfig,
                CcToolchainConfig.builder().withFeatures(CppRuleClasses.PER_OBJECT_DEBUG_INFO)
            )
        useConfiguration(fission)
        scratch.file(
            "a/rule.bzl",
            """
        load("@rules_cc//cc/common:debug_package_info.bzl", "DebugPackageInfo")
        def _impl(ctx):
            out = ctx.actions.declare_file(ctx.label.name)
            ctx.actions.run_shell(
                inputs = [ctx.executable.cc_binary],
                tools = [],
                outputs = [out],
                command = "cp %s %s" % (ctx.executable.cc_binary.path, out.path),
            )
            wrapped_defaultinfo = ctx.attr.cc_binary[DefaultInfo]
            runfiles = ctx.runfiles(files = [out])
            wrapped_default_runfiles = wrapped_defaultinfo.default_runfiles.files.to_list()
            if ctx.executable.cc_binary in wrapped_default_runfiles:
                wrapped_default_runfiles.remove(ctx.executable.cc_binary)
            result = [
                DefaultInfo(
                    executable = out,
                    files = depset([out]),
                    runfiles = runfiles.merge(ctx.runfiles(files = wrapped_default_runfiles)),
                ),
            ]
            if ctx.file.stripped_file:
                wrapped_dbginfo = ctx.attr.cc_binary[DebugPackageInfo]
                result.append(
                    DebugPackageInfo(
                        target_label = ctx.label,
                        stripped_file =
                            ctx.file.stripped_file if wrapped_dbginfo.stripped_file else None,
                        unstripped_file = out,
                        dwp_file = ctx.file.dwp_file if wrapped_dbginfo.dwp_file else None,
                    ),
                )
            return result

        wrapped_binary = rule(
            _impl,
            attrs = {
                "cc_binary": attr.label(
                    allow_single_file = True,
                    mandatory = True,
                    executable = True,
                    cfg = "target",
                ),
                "stripped_file": attr.label(
                    allow_single_file = True,
                    default = None,
                ),
                "dwp_file": attr.label(
                    allow_single_file = True,
                    default = None,
                ),
            },
            executable = True,
        )
        
        """.trimIndent()
        )
        scratch.file(
            "a/BUILD",
            """
        load("@rules_cc//cc:cc_binary.bzl", "cc_binary")
        load(":rule.bzl", "wrapped_binary")

        wrapped_binary(
            name = "w",
            cc_binary = ":native_binary",
            dwp_file = ":w.dwp",
            stripped_file = ":w.stripped",
        )

        wrapped_binary(
            name = "w.stripped",
            cc_binary = ":native_binary.stripped",
        )

        wrapped_binary(
            name = "w.dwp",
            cc_binary = ":native_binary.dwp",
        )

        cc_binary(
            name = "native_binary",
            srcs = ["main.cc"],
        )
        
        """.trimIndent()
        )
        scratch.file("a/main.cc", "int main() {}")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDebugPackageProviderFissionDisabled() {
        setupDebugPackageProviderTest("--fission=no")
        val target: ConfiguredTarget = getConfiguredTarget("//a:w")
        assertNoEvents()
        assertThat(target).isNotNull()
        val debugPackageProvider: DebugPackageProvider = DebugPackageProvider.get(target)
        assertThat(debugPackageProvider.getStrippedArtifact().getFilename()).isEqualTo("w.stripped")
        assertThat(debugPackageProvider.getUnstrippedArtifact().getFilename()).isEqualTo("w")
        assertThat(debugPackageProvider.getDwpArtifact()).isNull()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDebugPackageProviderFissionEnabled() {
        setupDebugPackageProviderTest("--fission=yes")
        val target: ConfiguredTarget = getConfiguredTarget("//a:w")
        assertNoEvents()
        assertThat(target).isNotNull()
        val debugPackageProvider: DebugPackageProvider = DebugPackageProvider.get(target)
        assertThat(debugPackageProvider.getStrippedArtifact().getFilename()).isEqualTo("w.stripped")
        assertThat(debugPackageProvider.getUnstrippedArtifact().getFilename()).isEqualTo("w")
        assertThat(debugPackageProvider.getDwpArtifact().getFilename()).isEqualTo("w.dwp")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCcDebugContextDisabled() {
        scratch.file(
            "b/BUILD",
            """
        load("//my_rules:rule.bzl", "cc_compile_rule")

        cc_compile_rule(
            name = "b_lib",
            srcs = ["b_lib.cc"],
        )
        
        """.trimIndent()
        )
        scratch.file("my_rules/BUILD")
        scratch.file(
            "my_rules/rule.bzl",
            """
        load('@rules_cc//cc/common:cc_common.bzl', 'cc_common')
        load('@rules_cc//cc/common:cc_info.bzl', 'CcInfo')
        def _impl(ctx):
            comp_context = cc_common.create_compilation_context()
            comp_outputs = cc_common.create_compilation_outputs()
            debug_info = cc_common.create_debug_context(comp_outputs)
            return [CcInfo(compilation_context = comp_context, debug_info = debug_info)]

        cc_compile_rule = rule(
            implementation = _impl,
            attrs = {
                "srcs": attr.label_list(allow_files = [".cc"]),
            },
        )
        
        """.trimIndent()
        )
        val e: java.lang.AssertionError? = org.junit.Assert.assertThrows<java.lang.AssertionError?>(
            java.lang.AssertionError::class.java,
            org.junit.function.ThrowingRunnable { getConfiguredTarget("//b:b_lib") })
        Truth.assertThat(e).hasMessageThat().contains("cannot use private API")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCcDebugContext() {
        useConfiguration("--fission=yes")
        scratch.file(
            "b/BUILD",
            """
        load("@rules_cc//cc/toolchains:cc_toolchain_alias.bzl", "cc_toolchain_alias")
        load("//bazel_internal/test_rules/cc:rule.bzl", "cc_compile_rule")

        cc_toolchain_alias(name = "alias")

        cc_compile_rule(
            name = "b_lib",
            srcs = ["b_lib.cc"],
        )
        
        """.trimIndent()
        )
        scratch.file("bazel_internal/test_rules/cc/BUILD")
        scratch.file(
            "bazel_internal/test_rules/cc/rule.bzl",
            """
        load('@rules_cc//cc/common:cc_common.bzl', 'cc_common')
        load('@rules_cc//cc/common:cc_info.bzl', 'CcInfo')

        def _impl(ctx):
            toolchain = ctx.attr._cc_toolchain[cc_common.CcToolchainInfo]
            feature_configuration = cc_common.configure_features(
                ctx = ctx,
                cc_toolchain = toolchain,
                requested_features = ctx.features + ["per_object_debug_info"],
                unsupported_features = ctx.disabled_features,
            )
            (comp_context, comp_outputs) = cc_common.compile(
                name = ctx.label.name,
                actions = ctx.actions,
                feature_configuration = feature_configuration,
                cc_toolchain = toolchain,
                srcs = ctx.files.srcs,
            )
            debug_info = cc_common.create_debug_context(comp_outputs)
            return [CcInfo(compilation_context = comp_context, debug_context = debug_info)]

        cc_compile_rule = rule(
            implementation = _impl,
            attrs = {
                "_cc_toolchain": attr.label(default = Label("//b:alias")),
                "srcs": attr.label_list(allow_files = [".cc"]),
            },
            fragments = ["cpp"],
        )
        
        """.trimIndent()
        )
        val target: ConfiguredTarget = getConfiguredTarget("//b:b_lib")
        assertThat(
            CcInfo.get(target)
                .getCcDebugInfoContext()
                .getValue("files", Depset::class.java)
                .toList(Artifact::class.java)
                .stream()
                .map(Artifact::getFilename)
        )
            .containsExactly("b_lib.dwo")
        assertThat(
            CcInfo.get(target).getCcDebugInfoContext().getValue("pic_files", Depset::class.java).toList()
        )
            .isEmpty()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testExpandedCppConfigurationApiBlocked() {
        val cppConfigurationOptions: MutableList<String?> =
            com.google.common.collect.ImmutableList.of<String?>(
                "strip_opts()",
                "build_test_dwp()",
                "grte_top()",
                "experimental_cc_implementation_deps()",
                "experimental_cpp_modules()",
                "share_native_deps()"
            )
        scratch.file(
            "foo/BUILD",
            """
        load(":custom_rule.bzl", "cpp_config_rule")

        cpp_config_rule(name = "custom")
        
        """.trimIndent()
        )
        for (option in cppConfigurationOptions) {
            scratch.overwriteFile(
                "foo/custom_rule.bzl",
                "def _impl(ctx):",
                "  ctx.fragments.cpp." + option,
                "  return []",
                "cpp_config_rule = rule(",
                "  implementation = _impl,",
                "  fragments = [\"cpp\"],",
                ")"
            )
            invalidatePackages()

            val e: java.lang.AssertionError? =
                org.junit.Assert.assertThrows<java.lang.AssertionError?>(
                    java.lang.AssertionError::class.java,
                    org.junit.function.ThrowingRunnable { getConfiguredTarget("//foo:custom") })

            Truth.assertThat(e).hasMessageThat().contains("cannot use private API")
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testExpandedCompileApiBlocked() {
        scratch.file(
            "bazel_internal/test_rules/cc/BUILD",
            """
        load(":module_map.bzl", "module_map")

        module_map(
            name = "module_map",
            file = "a_file.txt",
        )
        
        """.trimIndent()
        )

        scratch.file(
            "bazel_internal/test_rules/cc/module_map.bzl",
            """
        load('@rules_cc//cc/common:cc_common.bzl', 'cc_common')
        ModuleMapInfo = provider(fields = ["module_map", "file"])

        def _impl(ctx):
            module_map = cc_common.create_module_map(
                file = ctx.file.file,
                name = "module",
            )
            return [ModuleMapInfo(module_map = module_map, file = ctx.file.file)]

        module_map = rule(
            _impl,
            attrs = {
                "file": attr.label(allow_single_file = True),
            },
            fragments = ["cpp"],
        )
        
        """.trimIndent()
        )

        val compileCall =
            ("cc_common.compile(actions = ctx.actions, feature_configuration = feature_configuration,"
                    + " name = 'name', cc_toolchain = toolchain, ")
        val calls: MutableList<String?> =
            com.google.common.collect.ImmutableList.of<String?>(
                "cc_common.create_module_map(file=file, name='name')",
                compileCall + " module_map = module_map)",
                compileCall + " additional_module_maps = [module_map])",
                compileCall + "additional_exported_hdrs = [])",
                compileCall + "do_not_generate_module_map = True)",
                compileCall + "code_coverage_enabled = True)",
                compileCall + "separate_module_headers = [])",
                compileCall + "hdrs_checking_mode = 'strict')"
            )
        scratch.overwriteFile(
            "a/BUILD",
            """
        load('@rules_cc//cc/common:cc_common.bzl', 'cc_common')
        load("@rules_cc//cc/toolchains:cc_toolchain_alias.bzl", "cc_toolchain_alias")
        load(":rule.bzl", "crule")

        cc_toolchain_alias(name = "alias")

        crule(
            name = "r",
            deps = ["//bazel_internal/test_rules/cc:module_map"],
        )
        
        """.trimIndent()
        )

        for (call in calls) {
            scratch.overwriteFile(
                "a/rule.bzl",
                "load('//bazel_internal/test_rules/cc:module_map.bzl', 'ModuleMapInfo')",
                "load('@rules_cc//cc/common:cc_common.bzl', 'cc_common')",
                "CruleInfo = provider()",
                "def _impl(ctx):",
                "  toolchain = ctx.attr._cc_toolchain[cc_common.CcToolchainInfo]",
                "  feature_configuration = cc_common.configure_features(",
                "    ctx = ctx,",
                "    cc_toolchain = toolchain,",
                "  )",
                "  module_map = ctx.attr.deps[0][ModuleMapInfo].module_map",
                "  file = ctx.attr.deps[0][ModuleMapInfo].file",
                "  " + call,
                "  return [CruleInfo()]",
                "crule = rule(",
                "  _impl,",
                "  attrs = { ",
                "    'deps': attr.label_list(),",
                "    '_cc_toolchain': attr.label(default=Label('//a:alias'))",
                "  },",
                "  fragments = ['cpp'],",
                ");"
            )
            initializeSkyframeExecutor()
            val e: java.lang.AssertionError? = org.junit.Assert.assertThrows<java.lang.AssertionError?>(
                java.lang.AssertionError::class.java,
                org.junit.function.ThrowingRunnable { getConfiguredTarget("//a:r") })
            Truth.assertThat(e).hasMessageThat().contains("cannot use private API")
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testExpandedLinkApiRaisesError() {
        scratch.file(
            "b/BUILD",
            """
        load("@rules_cc//cc/toolchains:cc_toolchain_alias.bzl", "cc_toolchain_alias")
        load("//b:rule.bzl", "link_rule")

        cc_toolchain_alias(name = "alias")

        link_rule(name = "foo")
        
        """.trimIndent()
        )
        val callFormatString =
            ("cc_common.link(name='test', actions=ctx.actions,"
                    + "feature_configuration=feature_configuration, cc_toolchain=toolchain, %s)")
        val calls: com.google.common.collect.ImmutableList<String?> =
            com.google.common.collect.ImmutableList.of<String?>(
                String.format(callFormatString, "never_link=False"),
                String.format(callFormatString, "test_only_target=False"),
                String.format(callFormatString, "always_link=False"),
                String.format(callFormatString, "additional_linkstamp_defines=[]"),
                String.format(callFormatString, "whole_archive=False"),
                String.format(callFormatString, "native_deps=False"),
                String.format(callFormatString, "emit_interface_shared_library=True")
            )
        for (call in calls) {
            scratch.overwriteFile(
                "b/rule.bzl",
                "load('@rules_cc//cc/common:cc_common.bzl', 'cc_common')",
                "def _impl(ctx):",
                "  toolchain = ctx.attr._cc_toolchain[cc_common.CcToolchainInfo]",
                "  feature_configuration = cc_common.configure_features(",
                "    ctx = ctx,",
                "    cc_toolchain = toolchain,",
                "  )",
                "  " + call,
                "  return [DefaultInfo()]",
                "link_rule = rule(",
                "  implementation = _impl,",
                "  attrs = {",
                "    '_cc_toolchain': attr.label(default=Label('//b:alias'))",
                "  },",
                "  fragments = ['cpp'],",
                ")"
            )
            initializeSkyframeExecutor()
            val e: java.lang.AssertionError? = org.junit.Assert.assertThrows<java.lang.AssertionError?>(
                java.lang.AssertionError::class.java,
                org.junit.function.ThrowingRunnable { getConfiguredTarget("//b:foo") })
            Truth.assertThat(e).hasMessageThat().contains("cannot use private API")
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testExpandedCcCompilationOutputsApiRaisesError() {
        scratch.file(
            "b/BUILD",
            """
        load("//b:rule.bzl", "cc_rule")

        cc_rule(
            name = "foo",
        )
        
        """.trimIndent()
        )
        val calls: com.google.common.collect.ImmutableList<String?> =
            com.google.common.collect.ImmutableList.of<String?>("comp_outputs.temps()")
        for (call in calls) {
            scratch.overwriteFile(
                "b/rule.bzl",
                "load('@rules_cc//cc/common:cc_common.bzl', 'cc_common')",
                "def _impl(ctx):",
                "  comp_outputs = cc_common.create_compilation_outputs()",
                "  " + call,
                "  return [DefaultInfo()]",
                "cc_rule = rule(",
                "  implementation = _impl,",
                ")"
            )
            invalidatePackages()
            val e: java.lang.AssertionError? = org.junit.Assert.assertThrows<java.lang.AssertionError?>(
                java.lang.AssertionError::class.java,
                org.junit.function.ThrowingRunnable { getConfiguredTarget("//b:foo") })
            Truth.assertThat(e).hasMessageThat().contains("cannot use private API")
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testExpandedLibraryToLinkApiRaisesError() {
        scratch.file(
            "b/BUILD",
            """
        load("@rules_cc//cc:cc_library.bzl", "cc_library")
        load("@rules_cc//cc/toolchains:cc_toolchain_alias.bzl", "cc_toolchain_alias")
        load("//b:rule.bzl", "cc_rule")

        cc_library(
            name = "cc_dep",
            srcs = ["cc_dep.cc"],
        )

        cc_toolchain_alias(name = "alias")

        cc_rule(
            name = "foo",
            cc_dep = ":cc_dep",
        )
        
        """.trimIndent()
        )
        val calls: com.google.common.collect.ImmutableList<String?> =
            com.google.common.collect.ImmutableList.of<String?>(
                ("cc_common.create_library_to_link(actions=ctx.actions,"
                        + "feature_configuration=feature_configuration, cc_toolchain=toolchain,"
                        + " must_keep_debug=False)"),
                ("cc_common.create_library_to_link(actions=ctx.actions,"
                        + "feature_configuration=feature_configuration, cc_toolchain=toolchain,"
                        + " lto_compilation_context=None)")
            )
        for (call in calls) {
            scratch.overwriteFile(
                "b/rule.bzl",
                "load('@rules_cc//cc/common:cc_common.bzl', 'cc_common')",
                "load('@rules_cc//cc/common:cc_info.bzl', 'CcInfo')",
                "def _impl(ctx):",
                "  toolchain = ctx.attr._cc_toolchain[cc_common.CcToolchainInfo]",
                "  feature_configuration = cc_common.configure_features(",
                "    ctx = ctx,",
                "    cc_toolchain = toolchain,",
                "  )",
                "  library_to_link = (ctx.attr.cc_dep[CcInfo].linking_context",
                "                                     .linker_inputs.to_list()[0].libraries[0])",
                "  " + call,
                "  return [DefaultInfo()]",
                "cc_rule = rule(",
                "  implementation = _impl,",
                "  attrs = { ",
                "    'cc_dep': attr.label(),",
                "    '_cc_toolchain': attr.label(default=Label('//b:alias'))",
                "  },",
                "  fragments = ['cpp'],",
                ")"
            )
            invalidatePackages()
            val e: java.lang.AssertionError? = org.junit.Assert.assertThrows<java.lang.AssertionError?>(
                java.lang.AssertionError::class.java,
                org.junit.function.ThrowingRunnable { getConfiguredTarget("//b:foo") })
            Truth.assertThat(e).hasMessageThat().contains("cannot use private API")
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testExpandedLinkstampApiRaisesError() {
        scratch.file(
            "bazel_internal/test_rules/cc/BUILD",
            """
        load("@rules_cc//cc:cc_library.bzl", "cc_library")
        load(":linkstamps.bzl", "linkstamps")

        cc_library(
            name = "cc_dep",
            srcs = ["cc_dep.cc"],
            linkstamp = "stamp.cc",
        )

        linkstamps(
            name = "linkstamps",
            deps = [":cc_dep"],
        )
        
        """.trimIndent()
        )

        scratch.file(
            "bazel_internal/test_rules/cc/linkstamps.bzl",
            """
        load("@rules_cc//cc/common:cc_info.bzl", "CcInfo")
        LinkstampsInfo = provider(fields = ["linkstamps"])

        def _impl(ctx):
            linkstamps = [
                linkstamp
                for linker_input in ctx.attr.deps[0][CcInfo].linking_context.linker_inputs.to_list()
                for linkstamp in linker_input.linkstamps
            ]
            return [LinkstampsInfo(linkstamps = linkstamps)]

        linkstamps = rule(
            _impl,
            attrs = {
                "deps": attr.label_list(),
            },
        )
        
        """.trimIndent()
        )

        scratch.file(
            "b/BUILD",
            """
        load("@rules_cc//cc:cc_library.bzl", "cc_library")
        load("@rules_cc//cc/toolchains:cc_toolchain_alias.bzl", "cc_toolchain_alias")
        load("//b:rule.bzl", "cc_rule")

        cc_library(
            name = "cc_dep",
            srcs = ["cc_dep.cc"],
        )

        cc_toolchain_alias(name = "alias")

        cc_rule(
            name = "foo",
            cc_dep = ":cc_dep",
            file = "file.cc",
            linkstamps_dep = "//bazel_internal/test_rules/cc:linkstamps",
        )
        
        """.trimIndent()
        )
        val calls: MutableList<String?> =
            java.util.ArrayList<String?>(mutableListOf<String?>("linkstamp.file()", "linkstamp.hdrs()"))
        if (!analysisMock.isThisBazel) {
            calls.add(
                ("cc_common.register_linkstamp_compile_action(actions=ctx.actions,cc_toolchain=toolchain,"
                        + " feature_configuration=feature_configuration, "
                        + " source_file=file, output_file=file,"
                        + " compilation_inputs=depset([]), inputs_for_validation=depset([]),"
                        + " label_replacement='', output_replacement='')")
            )
        }
        for (call in calls) {
            scratch.overwriteFile(
                "b/rule.bzl",
                "load('@rules_cc//cc/common:cc_common.bzl', 'cc_common')",
                "load('@rules_cc//cc/common:cc_info.bzl', 'CcInfo')",
                "load('//bazel_internal/test_rules/cc:linkstamps.bzl',",
                "             'LinkstampsInfo')",
                "def _impl(ctx):",
                "  toolchain = ctx.attr._cc_toolchain[cc_common.CcToolchainInfo]",
                "  feature_configuration = cc_common.configure_features(",
                "    ctx = ctx,",
                "    cc_toolchain = toolchain,",
                "  )",
                "  linkstamp = ctx.attr.linkstamps_dep[LinkstampsInfo].linkstamps[0]",
                "  linking_context = ctx.attr.cc_dep[CcInfo].linking_context",
                "  file = ctx.file.file",
                "  " + call,
                "  return [DefaultInfo()]",
                "cc_rule = rule(",
                "  implementation = _impl,",
                "  attrs = { ",
                "    'cc_dep': attr.label(),",
                "    'linkstamps_dep': attr.label(),",
                "    '_cc_toolchain': attr.label(default=Label('//b:alias')),",
                "    'file': attr.label(allow_single_file=True),",
                "  },",
                "  fragments = ['cpp'],",
                ")"
            )
            invalidatePackages()
            val e: java.lang.AssertionError? = org.junit.Assert.assertThrows<java.lang.AssertionError?>(
                java.lang.AssertionError::class.java,
                org.junit.function.ThrowingRunnable { getConfiguredTarget("//b:foo") })
            Truth.assertThat(e).hasMessageThat().contains("cannot use private API")
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testVariableExtensionCompileApi() {
        createFilesForTestingCompilation(
            scratch, "tools/build_defs/foo", "variables_extension = foo_dict"
        )
        assertThat(getConfiguredTarget("//foo:bin")).isNotNull()
        val target: ConfiguredTarget = getConfiguredTarget("//foo:starlark_lib")
        val action: CppCompileAction =
            getGeneratingAction(artifactByPath(getFilesToBuild(target), ".o")) as CppCompileAction

        val unused1: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            action
                .getCompileCommandLine()
                .getVariables()
                .getVariable("string_sequence_variable", PathMapper.NOOP)
        val unused2: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            action
                .getCompileCommandLine()
                .getVariables()
                .getStringVariable("string_variable", PathMapper.NOOP)
        val unused3: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            action
                .getCompileCommandLine()
                .getVariables()
                .getVariable("string_depset_variable", PathMapper.NOOP)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testVariableExtensionLinkingContextApi() {
        createFilesForTestingLinking(scratch, "tools/build_defs/foo", "variables_extension = foo_dict")
        assertThat(getConfiguredTarget("//foo:bin")).isNotNull()
        val target: ConfiguredTarget = getConfiguredTarget("//foo:starlark_lib")
        val action: SpawnAction =
            getGeneratingAction(artifactByPath(getFilesToBuild(target), ".a")) as SpawnAction

        val unused1: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            getLinkCommandLine(action)
                .getBuildVariables()
                .getVariable("string_sequence_variable", PathMapper.NOOP)
        val unused2: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            getLinkCommandLine(action)
                .getBuildVariables()
                .getStringVariable("string_variable", PathMapper.NOOP)
        val unused3: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            getLinkCommandLine(action)
                .getBuildVariables()
                .getVariable("string_depset_variable", PathMapper.NOOP)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testVariableExtensionLinkApi() {
        setupTestTransitiveLink(
            scratch, "output_type = 'executable'", "variables_extension = foo_dict"
        )
        val target: ConfiguredTarget = getConfiguredTarget("//foo:bin")
        assertThat(target).isNotNull()
        val action: SpawnAction =
            getGeneratingAction(getMyInfoFromTarget(target).getValue("executable") as Artifact?) as SpawnAction

        val unused1: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            getLinkCommandLine(action)
                .getBuildVariables()
                .getVariable("string_sequence_variable", PathMapper.NOOP)
        val unused2: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            getLinkCommandLine(action)
                .getBuildVariables()
                .getStringVariable("string_variable", PathMapper.NOOP)
        val unused3: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            getLinkCommandLine(action)
                .getBuildVariables()
                .getVariable("string_depset_variable", PathMapper.NOOP)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testVariablesExtensionInvalidValueTypesThrowsError() {
        val rulePkg = "b"
        scratch.overwriteFile(rulePkg + "/BUILD", "")
        scratch.overwriteFile(
            "b/BUILD",
            "load('@rules_cc//cc:cc_library.bzl', 'cc_library')",
            "load('@rules_cc//cc/toolchains:cc_toolchain_alias.bzl',"
                    + " 'cc_toolchain_alias')",
            "load('//" + rulePkg + ":rule.bzl', 'cc_rule')",
            "cc_library(name='cc_dep', srcs=['cc_dep.cc'])",
            "cc_toolchain_alias(name='alias')",
            "cc_rule(name='foo', cc_dep=':cc_dep')"
        )
        for (value in com.google.common.collect.ImmutableList.of<String?>("1", "[1]", "depset([1])")) {
            scratch.overwriteFile(
                rulePkg + "/rule.bzl",
                getVariablesExtensionStarlarkRule("compile", "dict = {'variable': " + value + "}")
            )
            invalidatePackages()
            val e: java.lang.AssertionError? = org.junit.Assert.assertThrows<java.lang.AssertionError?>(
                java.lang.AssertionError::class.java,
                org.junit.function.ThrowingRunnable { getConfiguredTarget("//b:foo") })
            Truth.assertThat(e).hasMessageThat().contains("got element of type int, want string")
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAdditionalLinkingOutputsAppearAsOutputsOfLinkAction() {
        createCcBinRule(
            scratch,  /* internalApi= */false, "additional_outputs=ctx.outputs.additional_outputs"
        )
        scratch.file(
            "foo/BUILD",
            """
        load("//tools/build_defs:extension.bzl", "cc_bin")

        cc_bin(
            name = "bin",
            additional_outputs = [":bin.map"],
            objects = ["file.o"],
            pic_objects = ["file.pic.o"],
        )
        
        """.trimIndent()
        )
        val target: ConfiguredTarget = getConfiguredTarget("//foo:bin")
        assertThat(target).isNotNull()
        val action: SpawnAction =
            getGeneratingAction(artifactByPath(getFilesToBuild(target), ".map")) as SpawnAction
        Truth.assertThat(artifactsToStrings(action.getOutputs())).contains("bin foo/bin.map")
    }

    private fun getVariablesExtensionStarlarkRule(call: String?, dictionaryEntries: String?): String {
        return com.google.common.base.Joiner.on("\n")
            .join(
                "load('@rules_cc//cc/common:cc_common.bzl', 'cc_common')",
                "def _impl(ctx):",
                "  toolchain = ctx.attr._cc_toolchain[cc_common.CcToolchainInfo]",
                "  feature_configuration = cc_common.configure_features(",
                "      ctx = ctx,",
                "      cc_toolchain = toolchain,",
                "      requested_features = ctx.features,",
                "      unsupported_features = ctx.disabled_features,",
                "  )",
                "  " + dictionaryEntries,
                "  cc_common." + call + "(",
                "      actions = ctx.actions,",
                "      feature_configuration = feature_configuration,",
                "      cc_toolchain = toolchain,",
                "      name = ctx.label.name + '_aspect',",
                "      variables_extension = dict,",
                "  )",
                "  return [DefaultInfo()]",
                "cc_rule = rule(",
                "  implementation = _impl,",
                "  attrs = { ",
                "    'cc_dep': attr.label(),",
                "    '_cc_toolchain': attr.label(default=Label('//b:alias'))",
                "  },",
                "  fragments = ['cpp'],",
                ")"
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testMissingToolchainAndFeatureConfigurationRaisesErrorInCreateLibraryToLink() {
        scratch.file(
            "b/BUILD",
            """
        load("@rules_cc//cc/toolchains:cc_toolchain_alias.bzl", "cc_toolchain_alias")
        load("//b:rule.bzl", "cc_rule")

        cc_toolchain_alias(name = "alias")

        cc_rule(name = "foo")
        
        """.trimIndent()
        )

        runTestMissingToolchainAndFeatureConfigurationRaisesErrorInCreateLibraryToLink(
            "None,", "feature_configuration,", "ctx.file._artifact,", "None,"
        )
        var e: java.lang.AssertionError? = org.junit.Assert.assertThrows<java.lang.AssertionError?>(
            java.lang.AssertionError::class.java,
            org.junit.function.ThrowingRunnable { getConfiguredTarget("//b:foo") })
        Truth.assertThat(e)
            .hasMessageThat()
            .contains("If you pass 'dynamic_library', you must also pass a 'cc_toolchain'")

        runTestMissingToolchainAndFeatureConfigurationRaisesErrorInCreateLibraryToLink(
            "toolchain,", "None,", "ctx.file._artifact,", "None,"
        )
        e = org.junit.Assert.assertThrows<java.lang.AssertionError?>(
            java.lang.AssertionError::class.java,
            org.junit.function.ThrowingRunnable { getConfiguredTarget("//b:foo") })
        Truth.assertThat(e)
            .hasMessageThat()
            .contains("If you pass 'dynamic_library', you must also pass a 'feature_configuration'")

        runTestMissingToolchainAndFeatureConfigurationRaisesErrorInCreateLibraryToLink(
            "None,", "feature_configuration,", "None,", "ctx.file._artifact,"
        )
        e = org.junit.Assert.assertThrows<java.lang.AssertionError?>(
            java.lang.AssertionError::class.java,
            org.junit.function.ThrowingRunnable { getConfiguredTarget("//b:foo") })
        Truth.assertThat(e)
            .hasMessageThat()
            .contains("If you pass 'interface_library', you must also pass a 'cc_toolchain'")

        runTestMissingToolchainAndFeatureConfigurationRaisesErrorInCreateLibraryToLink(
            "toolchain,", "None,", "None,", "ctx.file._artifact,"
        )
        e = org.junit.Assert.assertThrows<java.lang.AssertionError?>(
            java.lang.AssertionError::class.java,
            org.junit.function.ThrowingRunnable { getConfiguredTarget("//b:foo") })
        Truth.assertThat(e)
            .hasMessageThat()
            .contains("If you pass 'interface_library', you must also pass a 'feature_configuration'")
    }

    @Throws(java.lang.Exception::class)
    private fun runTestMissingToolchainAndFeatureConfigurationRaisesErrorInCreateLibraryToLink(
        toolchain: String?, featureConfiguration: String?, dynamicLibrary: String?, interfaceLibrary: String?
    ) {
        scratch.overwriteFile(
            "b/rule.bzl",
            "load('@rules_cc//cc/common:cc_common.bzl', 'cc_common')",
            "def _impl(ctx):",
            "  toolchain = ctx.attr._cc_toolchain[cc_common.CcToolchainInfo]",
            "  feature_configuration = cc_common.configure_features(",
            "    ctx = ctx,",
            "    cc_toolchain = toolchain,",
            "  )",
            "  cc_common.create_library_to_link(",
            "    actions=ctx.actions, ",
            "    cc_toolchain = " + toolchain,
            "    feature_configuration = " + featureConfiguration,
            "    dynamic_library =" + dynamicLibrary,
            "    interface_library =" + interfaceLibrary,
            "  )",
            "  return [DefaultInfo()]",
            "cc_rule = rule(",
            "  implementation = _impl,",
            "  attrs = { ",
            "    '_artifact': attr.label(allow_single_file=True, default=Label('//b:foo.soif.so')),",
            "    '_cc_toolchain': attr.label(default=Label('//b:alias'))",
            "  },",
            "  fragments = ['cpp'],",
            ")"
        )
        invalidatePackages()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCcInternalIsNotAccessibleFromOutsideBuiltins() {
        scratch.file(
            "a/BUILD",
            """
        load(":rule.bzl", "crule")

        crule(name = "r")
        
        """.trimIndent()
        )

        scratch.file(
            "a/rule.bzl",
            """
        def _impl(ctx):
            cc_internal
            return DefaultInfo()

        crule = rule(
            _impl,
        )
        
        """.trimIndent()
        )

        val e: java.lang.AssertionError? = org.junit.Assert.assertThrows<java.lang.AssertionError?>(
            java.lang.AssertionError::class.java,
            org.junit.function.ThrowingRunnable { getConfiguredTarget("//a:r") })
        Truth.assertThat(e).hasMessageThat().contains("name 'cc_internal' is not defined")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testExtendedBuildConfigurationApiBlocked() {
        scratch.file(
            "foo/BUILD",
            """
        load(":custom_rule.bzl", "build_config_rule")

        build_config_rule(name = "custom")
        
        """.trimIndent()
        )
        scratch.file(
            "foo/custom_rule.bzl",
            """
        def _impl(ctx):
            ctx.configuration.stamp_binaries()
            return []

        build_config_rule = rule(
            implementation = _impl,
        )
        
        """.trimIndent()
        )
        invalidatePackages()

        val e: java.lang.AssertionError? =
            org.junit.Assert.assertThrows<java.lang.AssertionError?>(
                java.lang.AssertionError::class.java,
                org.junit.function.ThrowingRunnable { getConfiguredTarget("//foo:custom") })

        Truth.assertThat(e).hasMessageThat().contains("file '//foo:custom_rule.bzl' cannot use private API")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testExtendedCcLinkingOutputsApiBlocked() {
        scratch.file(
            "foo/BUILD",
            """
        load("@rules_cc//cc/toolchains:cc_toolchain_alias.bzl", "cc_toolchain_alias")
        load(":custom_rule.bzl", "cc_linking_outputs_rule")

        cc_toolchain_alias(name = "alias")

        cc_linking_outputs_rule(name = "custom")
        
        """.trimIndent()
        )
        scratch.file(
            "foo/custom_rule.bzl",
            """
        load('@rules_cc//cc/common:cc_common.bzl', 'cc_common')
        def _impl(ctx):
            cc_toolchain = ctx.attr._cc_toolchain[cc_common.CcToolchainInfo]
            feature_configuration = cc_common.configure_features(
                ctx = ctx,
                cc_toolchain = cc_toolchain,
            )
            cc_linking_outputs = cc_common.link(
                actions = ctx.actions,
                feature_configuration = feature_configuration,
                cc_toolchain = cc_toolchain,
                name = ctx.label.name,
            )
            cc_linking_outputs.all_lto_artifacts()
            return []

        cc_linking_outputs_rule = rule(
            implementation = _impl,
            attrs = {"_cc_toolchain": attr.label(default = Label("//foo:alias"))},
            fragments = ["cpp"],
        )
        
        """.trimIndent()
        )
        invalidatePackages()

        val e: java.lang.AssertionError? =
            org.junit.Assert.assertThrows<java.lang.AssertionError?>(
                java.lang.AssertionError::class.java,
                org.junit.function.ThrowingRunnable { getConfiguredTarget("//foo:custom") })

        Truth.assertThat(e).hasMessageThat().contains("cannot use private API")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testToolRequirementForActionIsNotAccessibleFromOutsideBuiltins() {
        scratch.file(
            "foo/BUILD",
            """
        load("@rules_cc//cc/toolchains:cc_toolchain_alias.bzl", "cc_toolchain_alias")
        load(":custom_rule.bzl", "custom_rule")

        cc_toolchain_alias(name = "alias")

        custom_rule(name = "custom")
        
        """.trimIndent()
        )
        scratch.file(
            "foo/custom_rule.bzl",
            """
        load('@rules_cc//cc/common:cc_common.bzl', 'cc_common')
        def _impl(ctx):
            cc_toolchain = ctx.attr._cc_toolchain[cc_common.CcToolchainInfo]
            feature_configuration = cc_common.configure_features(
                ctx = ctx,
                cc_toolchain = cc_toolchain,
            )
            cc_common.get_tool_requirement_for_action(
                feature_configuration = feature_configuration,
                action_name = "test",
            )
            return []

        custom_rule = rule(
            implementation = _impl,
            attrs = {"_cc_toolchain": attr.label(default = Label("//foo:alias"))},
            fragments = ["cpp"],
        )
        
        """.trimIndent()
        )
        invalidatePackages()

        val e: java.lang.AssertionError? =
            org.junit.Assert.assertThrows<java.lang.AssertionError?>(
                java.lang.AssertionError::class.java,
                org.junit.function.ThrowingRunnable { getConfiguredTarget("//foo:custom") })

        Truth.assertThat(e).hasMessageThat().contains("cannot use private API")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCreateCompilationOutputsPrivateParameterIsNotAccessibleFromOutsideBuiltins() {
        scratch.file(
            "foo/BUILD",
            """
        load(":custom_rule.bzl", "custom_rule")

        custom_rule(name = "custom")
        
        """.trimIndent()
        )
        scratch.file(
            "foo/custom_rule.bzl",
            """
        load('@rules_cc//cc/common:cc_common.bzl', 'cc_common')
        def _impl(ctx):
            cc_common.create_compilation_outputs(
                objects = None,
                pic_objects = None,
                lto_compilation_context = None,
            )
            return []

        custom_rule = rule(
            implementation = _impl,
        )
        
        """.trimIndent()
        )
        invalidatePackages()

        val e: java.lang.AssertionError? =
            org.junit.Assert.assertThrows<java.lang.AssertionError?>(
                java.lang.AssertionError::class.java,
                org.junit.function.ThrowingRunnable { getConfiguredTarget("//foo:custom") })

        Truth.assertThat(e).hasMessageThat().contains("cannot use private API")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testGetCompileBuildVariablesStripOptsNotAccessibleFromOutsideBuiltins() {
        scratch.file(
            "foo/BUILD",
            """
        load("@rules_cc//cc/toolchains:cc_toolchain_alias.bzl", "cc_toolchain_alias")
        load(":custom_rule.bzl", "custom_rule")

        cc_toolchain_alias(name = "alias")

        custom_rule(name = "custom")
        
        """.trimIndent()
        )
        scratch.file(
            "foo/custom_rule.bzl",
            """
        load('@rules_cc//cc/common:cc_common.bzl', 'cc_common')
        def _impl(ctx):
            cc_toolchain = ctx.attr._cc_toolchain[cc_common.CcToolchainInfo]
            feature_configuration = cc_common.configure_features(
                ctx = ctx,
                cc_toolchain = cc_toolchain,
            )
            cc_common.create_compile_variables(
                cc_toolchain = cc_toolchain,
                feature_configuration = feature_configuration,
                strip_opts = [],
            )
            return []

        custom_rule = rule(
            implementation = _impl,
            attrs = {"_cc_toolchain": attr.label(default = Label("//foo:alias"))},
            fragments = ["cpp"],
        )
        
        """.trimIndent()
        )
        invalidatePackages()

        val e: java.lang.AssertionError? =
            org.junit.Assert.assertThrows<java.lang.AssertionError?>(
                java.lang.AssertionError::class.java,
                org.junit.function.ThrowingRunnable { getConfiguredTarget("//foo:custom") })

        Truth.assertThat(e).hasMessageThat().contains("cannot use private API")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testGetCompileBuildVariablesInputFileNotAccessibleFromOutsideBuiltins() {
        scratch.file(
            "foo/BUILD",
            """
        load("@rules_cc//cc/toolchains:cc_toolchain_alias.bzl", "cc_toolchain_alias")
        load(":custom_rule.bzl", "custom_rule")

        cc_toolchain_alias(name = "alias")

        custom_rule(name = "custom")
        
        """.trimIndent()
        )
        scratch.file(
            "foo/custom_rule.bzl",
            """
        load('@rules_cc//cc/common:cc_common.bzl', 'cc_common')
        def _impl(ctx):
            cc_toolchain = ctx.attr._cc_toolchain[cc_common.CcToolchainInfo]
            feature_configuration = cc_common.configure_features(
                ctx = ctx,
                cc_toolchain = cc_toolchain,
            )
            cc_common.create_compile_variables(
                cc_toolchain = cc_toolchain,
                feature_configuration = feature_configuration,
                input_file = "",
            )
            return []

        custom_rule = rule(
            implementation = _impl,
            attrs = {"_cc_toolchain": attr.label(default = Label("//foo:alias"))},
            fragments = ["cpp"],
        )
        
        """.trimIndent()
        )
        invalidatePackages()

        val e: java.lang.AssertionError? =
            org.junit.Assert.assertThrows<java.lang.AssertionError?>(
                java.lang.AssertionError::class.java,
                org.junit.function.ThrowingRunnable { getConfiguredTarget("//foo:custom") })

        Truth.assertThat(e).hasMessageThat().contains("cannot use private API")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCreateLinkingContextFromCompilationOutputsStampNotAccessibleFromOutsideBuiltins() {
        scratch.file(
            "foo/BUILD",
            """
        load("@rules_cc//cc/toolchains:cc_toolchain_alias.bzl", "cc_toolchain_alias")
        load(":custom_rule.bzl", "custom_rule")

        cc_toolchain_alias(name = "alias")

        custom_rule(name = "custom")
        
        """.trimIndent()
        )
        scratch.file(
            "foo/custom_rule.bzl",
            """
        load('@rules_cc//cc/common:cc_common.bzl', 'cc_common')
        def _impl(ctx):
            cc_toolchain = ctx.attr._cc_toolchain[cc_common.CcToolchainInfo]
            feature_configuration = cc_common.configure_features(
                ctx = ctx,
                cc_toolchain = cc_toolchain,
            )
            cc_common.create_linking_context_from_compilation_outputs(
                actions = ctx.actions,
                cc_toolchain = cc_toolchain,
                feature_configuration = feature_configuration,
                compilation_outputs = cc_common.create_compilation_outputs(),
                name = "test",
                stamp = 0,
            )
            return []

        custom_rule = rule(
            implementation = _impl,
            attrs = {"_cc_toolchain": attr.label(default = Label("//foo:alias"))},
            fragments = ["cpp"],
        )
        
        """.trimIndent()
        )
        invalidatePackages()

        val e: java.lang.AssertionError? =
            org.junit.Assert.assertThrows<java.lang.AssertionError?>(
                java.lang.AssertionError::class.java,
                org.junit.function.ThrowingRunnable { getConfiguredTarget("//foo:custom") })

        Truth.assertThat(e).hasMessageThat().contains("cannot use private API")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testLinkUseTestOnlyFlagNotAccessibleFromOutsideBuiltins() {
        scratch.file(
            "foo/BUILD",
            """
        load("@rules_cc//cc/toolchains:cc_toolchain_alias.bzl", "cc_toolchain_alias")
        load(":custom_rule.bzl", "custom_rule")

        cc_toolchain_alias(name = "alias")

        custom_rule(name = "custom")
        
        """.trimIndent()
        )
        scratch.file(
            "foo/custom_rule.bzl",
            """
        load('@rules_cc//cc/common:cc_common.bzl', 'cc_common')
        def _impl(ctx):
            cc_toolchain = ctx.attr._cc_toolchain[cc_common.CcToolchainInfo]
            feature_configuration = cc_common.configure_features(
                ctx = ctx,
                cc_toolchain = cc_toolchain,
            )
            cc_common.link(
                actions = ctx.actions,
                cc_toolchain = cc_toolchain,
                feature_configuration = feature_configuration,
                use_test_only_flags = True,
                name = "test",
            )
            return []

        custom_rule = rule(
            implementation = _impl,
            attrs = {"_cc_toolchain": attr.label(default = Label("//foo:alias"))},
            fragments = ["cpp"],
        )
        
        """.trimIndent()
        )
        invalidatePackages()

        val e: java.lang.AssertionError? =
            org.junit.Assert.assertThrows<java.lang.AssertionError?>(
                java.lang.AssertionError::class.java,
                org.junit.function.ThrowingRunnable { getConfiguredTarget("//foo:custom") })

        Truth.assertThat(e).hasMessageThat().contains("cannot use private API")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCheckPrivateApiCanOnlyBeCalledFromCcCommonBzl() {
        scratch.file(
            "foo/BUILD",
            """
        load(":custom_rule.bzl", "custom_rule")

        custom_rule(name = "custom")
        
        """.trimIndent()
        )
        scratch.file(
            "foo/custom_rule.bzl",
            """
        load('@rules_cc//cc/common:cc_common.bzl', 'cc_common')
        def _impl(ctx):
            cc_common.check_private_api(allowlist = [])
            return []

        custom_rule = rule(
            implementation = _impl,
        )
        
        """.trimIndent()
        )

        val e: java.lang.AssertionError? =
            org.junit.Assert.assertThrows<java.lang.AssertionError?>(
                java.lang.AssertionError::class.java,
                org.junit.function.ThrowingRunnable { getConfiguredTarget("//foo:custom") })

        Truth.assertThat(e)
            .hasMessageThat()
            .contains("'struct' value has no field or method 'check_private_api'")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDwoObjectsAllowlistBlocksPrivateParameter() {
        scratch.file(
            "foo/BUILD",
            """
        load(":custom_rule.bzl", "custom_rule")

        custom_rule(name = "custom")
        
        """.trimIndent()
        )
        scratch.file(
            "foo/custom_rule.bzl",
            """
        load('@rules_cc//cc/common:cc_common.bzl', 'cc_common')
        def _impl(ctx):
            cc_common.create_compilation_outputs(dwo_objects = depset())
            return []

        custom_rule = rule(
            implementation = _impl,
        )
        
        """.trimIndent()
        )

        val e: java.lang.AssertionError? =
            org.junit.Assert.assertThrows<java.lang.AssertionError?>(
                java.lang.AssertionError::class.java,
                org.junit.function.ThrowingRunnable { getConfiguredTarget("//foo:custom") })

        Truth.assertThat(e).hasMessageThat().contains("cannot use private API")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testPicDwoObjectsAllowlistBlocksPrivateParameter() {
        scratch.file(
            "foo/BUILD",
            """
        load(":custom_rule.bzl", "custom_rule")

        custom_rule(name = "custom")
        
        """.trimIndent()
        )
        scratch.file(
            "foo/custom_rule.bzl",
            """
        load('@rules_cc//cc/common:cc_common.bzl', 'cc_common')
        def _impl(ctx):
            cc_common.create_compilation_outputs(pic_dwo_objects = depset())
            return []

        custom_rule = rule(
            implementation = _impl,
        )
        
        """.trimIndent()
        )

        val e: java.lang.AssertionError? =
            org.junit.Assert.assertThrows<java.lang.AssertionError?>(
                java.lang.AssertionError::class.java,
                org.junit.function.ThrowingRunnable { getConfiguredTarget("//foo:custom") })

        Truth.assertThat(e).hasMessageThat().contains("cannot use private API")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testPicDwoObjectsAllowlistAllowsPrivateParameter() {
        scratch.file(
            "bazel_internal/test_rules/cc/BUILD",
            """
        load(":custom_rule.bzl", "custom_rule")

        custom_rule(name = "custom")
        
        """.trimIndent()
        )
        scratch.file(
            "bazel_internal/test_rules/cc/custom_rule.bzl",
            """
        load('@rules_cc//cc/common:cc_common.bzl', 'cc_common')
        def _impl(ctx):
            cc_common.create_compilation_outputs(
                dwo_objects = depset(),
                pic_dwo_objects = depset(),
            )
            return []

        custom_rule = rule(
            implementation = _impl,
        )
        
        """.trimIndent()
        )
        getConfiguredTarget("//bazel_internal/test_rules/cc:custom")
    }

    // grep_includes is not supported by Bazel.
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testGrepIncludesIsSetToNullInsideCcToolchain() {
        if (!AnalysisMock.get().isThisBazel()) {
            return
        }
        scratch.file(
            "foo/BUILD",
            """
        load(":extension.bzl", "cc_skylark_library")

        cc_skylark_library(
            name = "skylark_lib",
        )
        
        """.trimIndent()
        )
        scratch.file(
            "foo/extension.bzl",
            "def _cc_skylark_library_impl(ctx):",
            "    toolchain = ctx.toolchains['" + TestConstants.CPP_TOOLCHAIN_TYPE + "'].cc",
            "    return [toolchain]",
            "cc_skylark_library = rule(",
            "    implementation = _cc_skylark_library_impl,",
            "    fragments = ['cpp'],",
            "    toolchains = ['" + TestConstants.CPP_TOOLCHAIN_TYPE + "']",
            ")"
        )

        val target: ConfiguredTarget = getConfiguredTarget("//foo:skylark_lib")
        val toolchainProvider: CcToolchainProvider = CcToolchainProvider.getFromTarget(target)
        val grepIncludes: Artifact? = toolchainProvider.getGrepIncludes()

        assertThat(grepIncludes).isNull()
    }

    companion object {
        private const val REDACTED_ARTIFACT_PATH = "tools/build_defs/build_info/redacted_file.h"
        private const val NON_REDACTED_ARTIFACT_PATH = "tools/build_defs/build_info/volatile_file.h"

        @Throws(LabelSyntaxException::class)
        private fun getMyInfoFromTarget(configuredTarget: ConfiguredTarget): StructImpl {
            val key: Provider.Key =
                Key(
                    keyForBuild(Label.parseCanonical("//myinfo:myinfo.bzl")), "MyInfo"
                )
            return configuredTarget.get(key) as StructImpl
        }

        @Throws(java.lang.Exception::class)
        private fun getArtifactsFromMyInfo(target: ConfiguredTarget, field: String?): Iterable<Artifact?>? {
            val myInfo: StructImpl = getMyInfoFromTarget(target)
            val artifacts: Iterable<Artifact?>? = myInfo.getValue(field) as Iterable<Artifact?>?
            return artifacts
        }

        @Throws(net.starlark.java.eval.EvalException::class)
        private fun getSolibRelativePath(library: Artifact, toolchain: CcToolchainProvider): String {
            return library.getRootRelativePath().relativeTo(toolchain.getSolibDirectory()).toString()
        }

        private fun getLinkstampFile(linkstamp: StarlarkInfo): Artifact? {
            try {
                Mutability.create().use { mu ->
                    val func: StarlarkFunction? = linkstamp.getValue("file", StarlarkFunction::class.java)
                    val thread: StarlarkThread? = StarlarkThread.createTransient(mu, StarlarkSemantics.DEFAULT)
                    return Starlark.positionalOnlyCall(thread, func) as Artifact?
                }
            } catch (e: net.starlark.java.eval.EvalException) {
                throw java.lang.RuntimeException(e)
            } catch (e: java.lang.InterruptedException) {
                throw java.lang.RuntimeException(e)
            }
        }

        private fun getFilenamesToBuild(target: ConfiguredTarget?): com.google.common.collect.ImmutableList<String?> {
            return getFilesToBuild(target).toList().stream()
                .map(Artifact::getFilename)
                .collect(com.google.common.collect.ImmutableList.toImmutableList<E?>())
        }

        @Throws(java.lang.Exception::class)
        private fun createFilesForTestingCompilation(
            scratch: Scratch, bzlFilePath: String?, compileProviderLines: String
        ) {
            createFiles(scratch, bzlFilePath, compileProviderLines, "")
        }

        @Throws(java.lang.Exception::class)
        private fun createFilesForTestingLinking(
            scratch: Scratch, bzlFilePath: String?, linkProviderLines: String
        ) {
            createFiles(scratch, bzlFilePath, "", linkProviderLines)
        }

        @Throws(java.lang.Exception::class)
        private fun createFiles(
            scratch: Scratch, bzlFilePath: String?, compileProviderLines: String = "", linkProviderLines: String = ""
        ) {
            var fragments = "    fragments = ['google_cpp', 'cpp'],"
            if (AnalysisMock.get().isThisBazel()) {
                fragments = "    fragments = ['cpp'],"
            }
            scratch.overwriteFile(bzlFilePath + "/BUILD")
            scratch.file(
                bzlFilePath + "/extension.bzl",
                "load('//myinfo:myinfo.bzl', 'MyInfo')",
                "load('@rules_cc//cc/common:cc_info.bzl', 'CcInfo')",
                "load('@rules_cc//cc/common:cc_common.bzl', 'cc_common')",
                "def _cc_aspect_impl(target, ctx):",
                "    if ctx.attr._cc_toolchain:",
                "      toolchain = ctx.attr._cc_toolchain[cc_common.CcToolchainInfo]",
                "    else:",
                "      toolchain = ctx.toolchains['" + TestConstants.CPP_TOOLCHAIN_TYPE + "'].cc",
                "    feature_configuration = cc_common.configure_features(",
                "        ctx = ctx,",
                "        cc_toolchain = toolchain,",
                "        requested_features = ctx.features,",
                "        unsupported_features = ctx.disabled_features,",
                "    )",
                "    (compilation_context, compilation_outputs) = cc_common.compile(",
                "        actions = ctx.actions,",
                "        feature_configuration = feature_configuration,",
                "        cc_toolchain = toolchain,",
                "        name = ctx.label.name + '_aspect',",
                "        srcs = ctx.rule.files.srcs,",
                "        public_hdrs = ctx.rule.files.hdrs,",
                "    )",
                "    (linking_context, linking_outputs) = (",
                "        cc_common.create_linking_context_from_compilation_outputs(",
                "            actions = ctx.actions,",
                "            feature_configuration = feature_configuration,",
                "            name = ctx.label.name + '_aspect',",
                "            cc_toolchain = toolchain,",
                "            compilation_outputs = compilation_outputs,",
                "        )",
                "    )",
                "    return []",
                "_cc_aspect = aspect(",
                "    implementation = _cc_aspect_impl,",
                "    attrs = {",
                "        '_cc_toolchain': attr.label(default ="
                        + " '@bazel_tools//tools/cpp:current_cc_toolchain'),",
                "    },",
                fragments,
                "    toolchains = ['" + TestConstants.CPP_TOOLCHAIN_TYPE + "']",
                ")",
                "def _cc_starlark_library_impl(ctx):",
                "    dep_compilation_contexts = []",
                "    dep_linking_contexts = []",
                "    for dep in ctx.attr._deps:",
                "        dep_compilation_contexts.append(dep[CcInfo].compilation_context)",
                "        dep_linking_contexts.append(dep[CcInfo].linking_context)",
                "    toolchain = ctx.toolchains['" + TestConstants.CPP_TOOLCHAIN_TYPE + "'].cc",
                "    feature_configuration = cc_common.configure_features(",
                "        ctx = ctx,",
                "        cc_toolchain=toolchain,",
                "        requested_features = ctx.features,",
                "        unsupported_features = ctx.disabled_features)",
                "    foo_dict = {'string_variable': 'foo',",
                "            'string_sequence_variable' : ['foo'],",
                "            'string_depset_variable': depset(['foo'])}",
                "    (compilation_context, compilation_outputs) = cc_common.compile(",
                "        actions=ctx.actions,",
                "        feature_configuration=feature_configuration,",
                "        cc_toolchain=toolchain,",
                "        srcs=ctx.files.srcs,",
                "        name=ctx.label.name + '_suffix',",
                "        compilation_contexts = dep_compilation_contexts,",
                "        public_hdrs=ctx.files.public_hdrs,",
                "        textual_hdrs=ctx.files.textual_hdrs,",
                "        private_hdrs=ctx.files.private_hdrs" + (if (compileProviderLines.isEmpty()) "" else ","),
                "        " + compileProviderLines,
                "    )",
                "    (linking_context,",
                "     linking_outputs) = cc_common.create_linking_context_from_compilation_outputs(",
                "        actions=ctx.actions,",
                "        feature_configuration=feature_configuration,",
                "        compilation_outputs=compilation_outputs,",
                "        name = ctx.label.name,",
                "        linking_contexts = dep_linking_contexts,",
                "        cc_toolchain=toolchain" + (if (linkProviderLines.isEmpty()) "" else ","),
                "        " + linkProviderLines,
                "    )",
                "    files_to_build = []",
                "    files_to_build.extend(compilation_outputs.pic_objects)",
                "    files_to_build.extend(compilation_outputs.objects)",
                "    library_to_link = None",
                "    if len(ctx.files.srcs) > 0:",
                "        library_to_link = linking_outputs.library_to_link",
                "        if library_to_link.pic_static_library != None:",
                "            files_to_build.append(library_to_link.pic_static_library)",
                "        if library_to_link.static_library != None:",
                "            files_to_build.append(library_to_link.static_library)",
                "        if library_to_link.dynamic_library != None:",
                "            files_to_build.append(library_to_link.dynamic_library)",
                "    return [MyInfo(libraries=[library_to_link]),",
                "            DefaultInfo(files=depset(files_to_build)),",
                "            CcInfo(compilation_context=compilation_context,",
                "                   linking_context=linking_context)]",
                "cc_starlark_library = rule(",
                "    implementation = _cc_starlark_library_impl,",
                "    attrs = {",
                "      'srcs': attr.label_list(allow_files=True),",
                "      'public_hdrs': attr.label_list(allow_files=True),",
                "      'textual_hdrs': attr.label_list(allow_files=True),",
                "      'private_hdrs': attr.label_list(allow_files=True),",
                "      '_additional_inputs': attr.label_list(allow_files=True,"
                        + " default=['//foo:script.lds']),",
                "      '_additional_compiler_inputs': attr.label_list(allow_files=True,"
                        + " default=['//foo:extra_compiler_input']),",
                "      '_deps': attr.label_list(default=['//foo:dep1', '//foo:dep2']),",
                "      'aspect_deps': attr.label_list(aspects=[_cc_aspect]),",
                "    },",
                fragments,
                "    toolchains = ['" + TestConstants.CPP_TOOLCHAIN_TYPE + "']",
                ")"
            )
            scratch.file(
                "foo/BUILD",
                "load('//" + bzlFilePath + ":extension.bzl', 'cc_starlark_library')",
                "load('@rules_cc//cc:cc_binary.bzl', 'cc_binary')",
                "load('@rules_cc//cc:cc_library.bzl', 'cc_library')",
                "exports_files(['script.lds', 'extra_compiler_input'])",
                "cc_library(",
                "    name = 'dep1',",
                "    srcs = ['dep1.cc'],",
                "    hdrs = ['dep1.h'],",
                "    defines = ['DEFINE_DEP1'],",
                "    linkopts = ['-DEP1_LINKOPT'],",
                ")",
                "cc_library(",
                "    name = 'dep2',",
                "    srcs = ['dep2.cc'],",
                "    hdrs = ['dep2.h'],",
                "    defines = ['DEFINE_DEP2'],",
                "    linkopts = ['-DEP2_LINKOPT'],",
                ")",
                "cc_starlark_library(",
                "    name = 'starlark_lib',",
                "    srcs = ['starlark_lib.cc'],",
                "    public_hdrs = ['starlark_lib.h'],",
                "    textual_hdrs = ['textual_hdr.h'],",
                "    private_hdrs = ['private_starlark_lib.h'],",
                ")",
                "cc_binary(",
                "    name = 'bin',",
                "    deps = ['starlark_lib'],",
                ")"
            )
        }

        @Throws(java.lang.Exception::class)
        private fun createCcBinRule(
            scratch: Scratch, internalApi: Boolean, vararg additionalLines: String?
        ) {
            var fragments = "    fragments = ['google_cpp', 'cpp'],"
            if (AnalysisMock.get().isThisBazel()) {
                fragments = "    fragments = ['cpp'],"
            }
            scratch.overwriteFile("tools/build_defs/BUILD")

            var extensionDirectory = "tools/build_defs"
            if (internalApi) {
                extensionDirectory = "bazel_internal/test_rules/cc"
                scratch.overwriteFile(extensionDirectory + "/BUILD", "")
            }
            scratch.file(
                extensionDirectory + "/extension.bzl",
                "load('//myinfo:myinfo.bzl', 'MyInfo')",
                "load('@rules_cc//cc/common:cc_common.bzl', 'cc_common')",
                "load('@rules_cc//cc/common:cc_info.bzl', 'CcInfo')",
                "def _cc_bin_impl(ctx):",
                "    toolchain = ctx.toolchains['" + TestConstants.CPP_TOOLCHAIN_TYPE + "'].cc",
                "    feature_configuration = cc_common.configure_features(",
                "      ctx = ctx,",
                "      cc_toolchain = toolchain,",
                "    )",
                "    dep_linking_contexts = []",
                "    for dep in ctx.attr.deps:",
                "        dep_linking_contexts.append(dep[CcInfo].linking_context)",
                "    objects = cc_common.create_compilation_outputs(objects=depset(ctx.files.objects),",
                "        pic_objects=depset(ctx.files.pic_objects))",
                "    foo_dict = {'string_variable': 'foo',",
                "            'string_sequence_variable' : ['foo'],",
                "            'string_depset_variable': depset(['foo'])}",
                "    linking_outputs = cc_common.link(",
                "        actions=ctx.actions,",
                "        feature_configuration=feature_configuration,",
                "        name = ctx.label.name,",
                "        cc_toolchain=toolchain,",
                "        " + com.google.common.base.Joiner.on(",\n        ").join(additionalLines),
                "    )",
                "    return [",
                "      MyInfo(",
                "          library=linking_outputs.library_to_link,",
                "          executable=linking_outputs.executable",
                "      ),",
                "    ]",
                "cc_bin = rule(",
                "    implementation = _cc_bin_impl,",
                "    attrs = {",
                "      'objects': attr.label_list(allow_files=True),",
                "      'pic_objects': attr.label_list(allow_files=True),",
                "      'deps': attr.label_list(),",
                "      'additional_outputs': attr.output_list(),",
                "    },",
                fragments,
                "    toolchains = ['" + TestConstants.CPP_TOOLCHAIN_TYPE + "']",
                ")"
            )
        }

        @Throws(java.lang.Exception::class)
        private fun setupTestTransitiveLink(scratch: Scratch, vararg additionalLines: String?) {
            setupTestTransitiveLinkInternal(scratch,  /* internalApi= */false, *additionalLines)
        }

        @Throws(java.lang.Exception::class)
        private fun setupTestTransitiveLinkInternal(
            scratch: Scratch, internalApi: Boolean, vararg additionalLines: String?
        ) {
            createCcBinRule(scratch, internalApi, *additionalLines)
            val bzlPath: String?
            if (internalApi) {
                bzlPath = "bazel_internal/test_rules/cc"
            } else {
                bzlPath = "tools/build_defs"
            }
            scratch.file(
                "foo/BUILD",
                "load(\"//" + bzlPath + ":extension.bzl\", \"cc_bin\")",
                """
        load("@rules_cc//cc:cc_library.bzl", "cc_library")
        cc_library(
            name = "dep1",
            srcs = ["dep1.cc"],
            hdrs = ["dep1.h"],
            defines = ["DEP1"],
            includes = ["dep1/baz"],
            linkstamp = "version.cc",
        )

        cc_library(
            name = "dep2",
            srcs = ["dep2.cc"],
            hdrs = ["dep2.h"],
            defines = ["DEP2"],
            includes = ["dep2/qux"],
        )

        cc_bin(
            name = "bin",
            objects = ["file.o"],
            pic_objects = ["file.pic.o"],
            deps = [
                ":dep1",
                ":dep2",
            ],
        )
        
        """.trimIndent()
            )
        }

        @Throws(java.lang.Exception::class)
        private fun setupDirectHeaderExtractionSupport(scratch: Scratch) {
            scratch.file(
                "direct/cc_info_extractor.bzl",
                """
        load("//myinfo:myinfo.bzl", "MyInfo")
        load("@rules_cc//cc/common:cc_info.bzl", "CcInfo")

        def _cc_info_extractor_impl(ctx):
            compilation_context = ctx.attr.dep[CcInfo].compilation_context
            return [MyInfo(
                direct_headers = compilation_context.direct_headers,
                direct_public_headers = compilation_context.direct_public_headers,
                direct_private_headers = compilation_context.direct_private_headers,
                direct_textual_headers = compilation_context.direct_textual_headers,
            )]

        cc_info_extractor = rule(
            _cc_info_extractor_impl,
            attrs = {
                "dep": attr.label(providers = [[CcInfo]]),
            },
        )
        
        """.trimIndent()
            )
            scratch.file(
                "direct/BUILD",
                """
        load("//direct:cc_info_extractor.bzl", "cc_info_extractor")

        cc_info_extractor(
            name = "foo",
            dep = "//direct/libs:foo_lib",
        )

        cc_info_extractor(
            name = "bar",
            dep = "//direct/libs:bar_lib",
        )
        
        """.trimIndent()
            )
        }

        @Throws(java.lang.Exception::class)
        private fun setupCcLibraryDirectPropagationTestTargets(scratch: Scratch) {
            scratch.file(
                "direct/libs/BUILD",
                """
        load("@rules_cc//cc:cc_library.bzl", "cc_library")
        cc_library(
            name = "foo_lib",
            srcs = [
                "foo.cc",
                "foo_impl.h",
            ],
            hdrs = ["foo.h"],
            textual_hdrs = ["foo.def"],
        )

        cc_library(
            name = "bar_lib",
            hdrs = ["bar.h"],
            textual_hdrs = ["bar.def"],
            deps = [":foo_lib"],
        )
        
        """.trimIndent()
            )
        }
    }
}
