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
package com.google.devtools.build.lib.rules.cpp

/**
 * Tests for [com.google.devtools.build.lib.rules.cpp.CompileCommandLine], for example testing
 * the ordering of individual command line flags, or that command line is emitted differently
 * subject to the presence of certain build variables. Also used to test migration logic (removing
 * hardcoded flags and expressing them using feature configuration.
 */
@RunWith(JUnit4::class)
class CompileCommandLineTest : BuildViewTestCase() {
    @Before
    @Throws(java.lang.Exception::class)
    fun initializeRuleContext() {
        scratch.file("foo/BUILD", "cc_library(name = 'foo')")
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

    @Throws(java.lang.Exception::class)
    private fun getCcToolchainFeatures(vararg starlark: String?): CcToolchainFeatures? {
        loadCcToolchainConfigLib()
        scratch.overwriteFile(
            "mock_crosstool/crosstool.bzl",
            "load(",
            "    '//tools/cpp:cc_toolchain_config_lib.bzl',",
            "    'action_config',",
            "    'feature',",
            "    'flag_group',",
            "    'flag_set',",
            "    'tool',",
            ")",
            "load('@rules_cc//cc/toolchains:cc_toolchain_config_info.bzl',"
                    + " 'CcToolchainConfigInfo')",
            "load('@rules_cc//cc/common:cc_common.bzl', 'cc_common')",
            "def _impl(ctx):",
            "    return cc_common.create_cc_toolchain_config_info(",
            "        ctx = ctx,",
            java.lang.String.join("\n", *starlark) + ",",
            "        toolchain_identifier = 'toolchain',",
            "        host_system_name = 'host',",
            "        target_system_name = 'target',",
            "        target_cpu = 'cpu',",
            "        target_libc = 'libc',",
            "        compiler = 'compiler',",
            "    )",
            "cc_toolchain_config_rule = rule(implementation = _impl, provides ="
                    + " [CcToolchainConfigInfo])"
        )
        scratch.overwriteFile("bazel_internal/test_rules/cc/BUILD")
        scratch.overwriteFile(
            "bazel_internal/test_rules/cc/ctf_rule.bzl",
            """
        load('@rules_cc//cc/toolchains:cc_toolchain_config_info.bzl', 'CcToolchainConfigInfo')
        load('@rules_cc//cc/common:cc_common.bzl', 'cc_common')
        MyInfo = provider()
        def _impl(ctx):
          return [MyInfo(f = cc_common.cc_toolchain_features(
                    toolchain_config_info = ctx.attr.config[CcToolchainConfigInfo],
                    tools_directory = "",
                  ))]
        cc_toolchain_features = rule(_impl, attrs = {"config":attr.label()})
        
        """.trimIndent()
        )
        scratch.overwriteFile(
            "mock_crosstool/BUILD",
            "load(':crosstool.bzl', 'cc_toolchain_config_rule')",
            "load('//bazel_internal/test_rules/cc:ctf_rule.bzl', 'cc_toolchain_features')",
            "cc_toolchain_features(name = 'f', config = ':r')",
            "cc_toolchain_config_rule(name = 'r')"
        )

        val target: ConfiguredTarget = getConfiguredTarget("//mock_crosstool:f")
        assertThat(target).isNotNull()
        return getStarlarkProvider(target, "MyInfo").getValue("f") as CcToolchainFeatures?
    }

    @Throws(java.lang.Exception::class)
    private fun getMockFeatureConfigurationFromStarlark(vararg starlark: String?): FeatureConfiguration {
        return getCcToolchainFeatures(*starlark)
            .getFeatureConfiguration(
                com.google.common.collect.ImmutableSet.< E > of < E ? > (
                        CppActionNames.ASSEMBLE,
                CppActionNames.PREPROCESS_ASSEMBLE,
                CppActionNames.C_COMPILE,
                CppActionNames.CPP_COMPILE,
                CppActionNames.CPP_HEADER_PARSING,
                CppActionNames.CPP_MODULE_CODEGEN,
                CppActionNames.CPP_MODULE_COMPILE
            ))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testFeatureConfigurationCommandLineIsUsed() {
        val compileCommandLine: CompileCommandLine =
            makeCompileCommandLineBuilder()
                .setFeatureConfiguration(
                    getMockFeatureConfigurationFromStarlark(
                        "action_configs = [",
                        "    action_config(",
                        "        action_name = 'c++-compile',",
                        "        implies = ['some_foo_feature'],",
                        "        tools = [tool(path = 'foo/bar/DUMMY_COMPILER')],",
                        "    ),",
                        "],",
                        "features = [",
                        "    feature(",
                        "        name = 'some_foo_feature',",
                        "        flag_sets = [",
                        "            flag_set(",
                        "                actions = ['c++-compile'],",
                        "                flag_groups = [flag_group(flags = ['-some_foo_flag'])],",
                        "            ),",
                        "        ],",
                        "    ),",
                        "]"
                    )
                )
                .build()
        com.google.common.truth.Subject.contains("-some_foo_flag")
    }

    @Throws(java.lang.Exception::class)
    private fun makeCompileCommandLineBuilder(): CompileCommandLine.Builder {
        return CompileCommandLine.builder(CoptsFilter.alwaysPasses(), "c++-compile")
    }
}
