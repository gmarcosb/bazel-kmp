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

import com.google.devtools.build.lib.skyframe.BzlLoadValue.keyForBuild

/** Unit tests for `CcToolchainProvider`  */
@RunWith(JUnit4::class)
class CcToolchainProviderTest : BuildViewTestCase() {
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testStarlarkCallables() {
        AnalysisMock.get()
            .ccSupport()
            .setupCcToolchainConfig(
                mockToolsConfig, CcToolchainConfig.builder().withFeatures(CppRuleClasses.SUPPORTS_PIC)
            )
        useConfiguration("--force_pic", "--platforms=" + TestConstants.PLATFORM_LABEL)
        scratch.file(
            "test/rule.bzl",
            """
        load('@rules_cc//cc/common:cc_common.bzl', 'cc_common')
        MyInfo = provider()

        def _impl(ctx):
            provider = ctx.attr._cc_toolchain[cc_common.CcToolchainInfo]
            feature_configuration = cc_common.configure_features(
                ctx = ctx,
                cc_toolchain = provider,
            )
            return MyInfo(
                dirs = provider.built_in_include_directories,
                sysroot = provider.sysroot,
                cpu = provider.cpu,
                ar_executable = provider.ar_executable,
                use_pic_for_dynamic_libraries = provider.needs_pic_for_dynamic_libraries(
                    feature_configuration = feature_configuration,
                ),
            )

        my_rule = rule(
            _impl,
            attrs = {"_cc_toolchain": attr.label(default = Label("//test:toolchain"))},
            fragments = ["cpp"],
        )
        
        """.trimIndent()
        )

        scratch.file(
            "test/BUILD",
            """
        load("@rules_cc//cc/toolchains:cc_toolchain_alias.bzl", "cc_toolchain_alias")
        load(":rule.bzl", "my_rule")

        cc_toolchain_alias(name = "toolchain")

        my_rule(name = "target")
        
        """.trimIndent()
        )

        val ct: ConfiguredTarget = getConfiguredTarget("//test:target")
        val key: Provider.Key =
            Key(keyForBuild(Label.parseCanonical("//test:rule.bzl")), "MyInfo")
        val info: StructImpl = ct.get(key) as StructImpl

        Truth.assertThat(info.getValue("ar_executable") as String?).endsWith("/usr/bin/mock-ar")

        assertThat(info.getValue("cpu")).isEqualTo("k8")

        assertThat(info.getValue("sysroot")).isEqualTo("/usr/grte/v1")

        val usePicForDynamicLibraries = info.getValue("use_pic_for_dynamic_libraries") as Boolean
        Truth.assertThat(usePicForDynamicLibraries).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testToolchainAndSuiteDifferentPackages() {
        scratch.file("suite/BUILD", "filegroup(name = 'empty')")
        scratch.file(
            "toolchain/BUILD",
            """
        load("@rules_cc//cc/toolchains:cc_toolchain.bzl", "cc_toolchain")
        load(":cc_toolchain_config.bzl", "cc_toolchain_config")

        cc_toolchain(
            name = "toolchain",
            all_files = ":empty",
            ar_files = ":empty",
            as_files = ":empty",
            compiler_files = ":empty",
            dwp_files = ":empty",
            linker_files = ":empty",
            objcopy_files = ":empty",
            strip_files = ":empty",
            toolchain_config = ":banana_config",
            toolchain_identifier = "banana",
        )

        cc_toolchain_config(name = "banana_config")
        
        """.trimIndent()
        )

        scratch.appendFile("tools/cpp/BUILD", "")
        scratch.overwriteFile(
            "tools/cpp/cc_toolchain_config_lib.bzl",
            com.google.devtools.build.lib.packages.util.ResourceLoader.readFromResources(
                TestConstants.RULES_CC_REPOSITORY_EXECROOT + "cc/cc_toolchain_config_lib.bzl"
            )
        )
        scratch.file(
            "toolchain/cc_toolchain_config.bzl",
            """
        load("//tools/cpp:cc_toolchain_config_lib.bzl", "tool_path")
        load('@rules_cc//cc/common:cc_common.bzl', 'cc_common')
        load("@rules_cc//cc/toolchains:cc_toolchain_config_info.bzl", "CcToolchainConfigInfo")

        def _impl(ctx):
            return cc_common.create_cc_toolchain_config_info(
                ctx = ctx,
                features = [],
                action_configs = [],
                artifact_name_patterns = [],
                cxx_builtin_include_directories = [],
                toolchain_identifier = "toolchain",
                host_system_name = "host",
                target_system_name = "target",
                target_cpu = "cpu",
                target_libc = "libc",
                compiler = "compiler",
                abi_libc_version = "abi_libc",
                abi_version = "banana",
                tool_paths = [
                    tool_path(name = "ar", path = "some/ar"),
                    tool_path(name = "cpp", path = "some/cpp"),
                    tool_path(name = "gcc", path = "some/gcc"),
                    tool_path(name = "gcov", path = "some/gcov"),
                    tool_path(name = "gcovtool", path = "some/gcovtool"),
                    tool_path(name = "ld", path = "some/ld"),
                    tool_path(name = "nm", path = "some/nm"),
                    tool_path(name = "objcopy", path = "some/objcopy"),
                    tool_path(name = "objdump", path = "some/objdump"),
                    tool_path(name = "strip", path = "some/strip"),
                    tool_path(name = "dwp", path = "some/dwp"),
                ],
                cc_target_os = "os",
                builtin_sysroot = "sysroot",
            )

        cc_toolchain_config = rule(
            implementation = _impl,
            attrs = {},
            provides = [CcToolchainConfigInfo],
            fragments = ["cpp"],
        )
        
        """.trimIndent()
        )

        val target: ConfiguredTarget = getConfiguredTarget("//toolchain")
        val toolchainProvider: CcToolchainProvider = CcToolchainProvider.getFromTarget(target)

        assertThat(
            CcToolchainProvider.getToolPathString(
                toolchainProvider.getToolPaths(),
                CppConfiguration.Tool.CPP,
                toolchainProvider.getCcToolchainLabel(),
                toolchainProvider.getToolchainIdentifier()
            )
        )
            .isEqualTo("toolchain/some/cpp")
    }

    @Throws(java.lang.Exception::class)
    private fun getMakeVariables(ccToolchainProvider: CcToolchainProvider): com.google.common.collect.ImmutableMap<String?, String?> {
        scratch.overwriteFile(
            "test/BUILD",
            """
        load("@rules_cc//cc/toolchains:cc_toolchain_alias.bzl", "cc_toolchain_alias")
        cc_toolchain_alias(name = "toolchain")
        
        """.trimIndent()
        )
        scratch.overwriteFile(
            "bazel_testing/fake_test_utils/util.bzl",
            """
        load("@rules_cc//cc/common:cc_common.bzl", "cc_common")
        load("@rules_cc//cc/common:cc_helper.bzl", "cc_helper")
        FuncInfo = provider()
        def _impl(ctx):
          feature_configuration = cc_common.configure_features(
              ctx = ctx,
              cc_toolchain = ctx.attr._cc_toolchain[cc_common.CcToolchainInfo],
          )
          def func(cc_toolchain):
            return cc_helper.get_toolchain_global_make_variables(cc_toolchain, feature_configuration)
          return [FuncInfo(func = func)]
        func_exporting_rule = rule(
            _impl,
            attrs = {"_cc_toolchain": attr.label(default = Label("//test:toolchain"))},
            fragments = ["cpp"],
        )
        
        """.trimIndent()
        )
        scratch.overwriteFile(
            "bazel_testing/fake_test_utils/BUILD",
            """
        load(":util.bzl", "func_exporting_rule")
        func_exporting_rule(name = "func_rule")
        
        """.trimIndent()
        )
        val getMakeVariables: StarlarkCallable? =
            getStarlarkProvider(
                getConfiguredTarget("//bazel_testing/fake_test_utils:func_rule"), "FuncInfo"
            )
                .getValue("func", StarlarkCallable::class.java)
        Mutability.create("test").use { mu ->
            val thread: StarlarkThread? = StarlarkThread.createTransient(mu, StarlarkSemantics.DEFAULT)
            val makeVarsDict: Dict<*, *>? =
                Starlark.positionalOnlyCall(thread, getMakeVariables, ccToolchainProvider.getValue()) as Dict<*, *>?
            return com.google.common.collect.ImmutableMap.copyOf<String?, String?>(
                Dict.cast<String?, String?>(makeVarsDict, String::class.java, String::class.java, "make_vars_for_test")
            )
        }
    }

    /*
   * Crosstools should load fine with or without 'gcov-tool'. Those that define 'gcov-tool'
   * should also add a make variable.
   */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testGcovToolNotDefined() {
        if (analysisMock.isThisBazel) {
            // TODO(b/507033784): Remove once https://github.com/bazelbuild/rules_cc/pull/699 is in a
            // released rules_cc used by BazelCI.
            return
        }
        // Crosstool with gcov-tool
        scratch.file(
            "a/BUILD",
            "load('@rules_cc//cc/toolchains:cc_toolchain.bzl', 'cc_toolchain')",
            "load(':cc_toolchain_config.bzl', 'cc_toolchain_config')",
            "filegroup(",
            "   name='empty')",
            "cc_toolchain(",
            "    name = 'b',",
            "    all_files = ':empty',",
            "    ar_files = ':empty',",
            "    as_files = ':empty',",
            "    compiler_files = ':empty',",
            "    dwp_files = ':empty',",
            "    linker_files = ':empty',",
            "    strip_files = ':empty',",
            "    objcopy_files = ':empty',",
            "    toolchain_identifier = 'banana',",
            "    toolchain_config = ':k8-compiler_config',",
            ")",
            CcToolchainConfig.builder()
                .withToolPaths(
                    Pair.of("gcc", "path-to-gcc-tool"),
                    Pair.of("ar", "ar"),
                    Pair.of("cpp", "cpp"),
                    Pair.of("gcov", "gcov"),
                    Pair.of("ld", "ld"),
                    Pair.of("nm", "nm"),
                    Pair.of("objdump", "objdump"),
                    Pair.of("strip", "strip")
                )
                .build()
                .getCcToolchainConfigRule()
        )
        analysisMock.ccSupport().setupCcToolchainConfig(mockToolsConfig, CcToolchainConfig.builder())
        mockToolsConfig.create(
            "a/cc_toolchain_config.bzl",
            com.google.devtools.build.lib.packages.util.ResourceLoader.readFromResources(
                "com/google/devtools/build/lib/analysis/mock/cc_toolchain_config.bzl"
            )
        )
        val ccToolchainProvider: CcToolchainProvider =
            CcToolchainProvider.getFromTarget(getConfiguredTarget("//a:b"))
        Truth.assertThat(getMakeVariables(ccToolchainProvider)).doesNotContainKey("GCOVTOOL")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testGcovToolDefined() {
        // Crosstool with gcov-tool
        if (analysisMock.isThisBazel) {
            // TODO(b/507033784): Remove once https://github.com/bazelbuild/rules_cc/pull/699 is in a
            // released rules_cc used by BazelCI.
            return
        }
        scratch.file(
            "a/BUILD",
            "load('@rules_cc//cc/toolchains:cc_toolchain.bzl', 'cc_toolchain')",
            "load(':cc_toolchain_config.bzl', 'cc_toolchain_config')",
            "filegroup(",
            "   name='empty')",
            "cc_toolchain(",
            "    name = 'b',",
            "    all_files = ':empty',",
            "    ar_files = ':empty',",
            "    as_files = ':empty',",
            "    compiler_files = ':empty',",
            "    dwp_files = ':empty',",
            "    linker_files = ':empty',",
            "    strip_files = ':empty',",
            "    objcopy_files = ':empty',",
            "    toolchain_identifier = 'banana',",
            "    toolchain_config = ':k8-compiler_config',",
            ")",
            CcToolchainConfig.builder()
                .withToolPaths(
                    Pair.of("gcc", "path-to-gcc-tool"),
                    Pair.of("gcov-tool", "path-to-gcov-tool"),
                    Pair.of("ar", "ar"),
                    Pair.of("cpp", "cpp"),
                    Pair.of("gcov", "gcov"),
                    Pair.of("ld", "ld"),
                    Pair.of("nm", "nm"),
                    Pair.of("objdump", "objdump"),
                    Pair.of("strip", "strip")
                )
                .build()
                .getCcToolchainConfigRule()
        )
        analysisMock.ccSupport().setupCcToolchainConfig(mockToolsConfig, CcToolchainConfig.builder())
        mockToolsConfig.create(
            "a/cc_toolchain_config.bzl",
            com.google.devtools.build.lib.packages.util.ResourceLoader.readFromResources(
                "com/google/devtools/build/lib/analysis/mock/cc_toolchain_config.bzl"
            )
        )
        useConfiguration(
            "--platforms=" + TestConstants.PLATFORM_LABEL,
            "--host_platform=" + TestConstants.PLATFORM_LABEL
        )
        val ccToolchainProvider: CcToolchainProvider =
            CcToolchainProvider.getFromTarget(getConfiguredTarget("//a:b"))
        Truth.assertThat(getMakeVariables(ccToolchainProvider)).containsKey("GCOVTOOL")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testGcovNotDefined() {
        val ccToolchainConfigBuilder: com.google.devtools.build.lib.packages.util.Crosstool.CcToolchainConfig.Builder =
            CcToolchainConfig.builder()
                .withToolPaths(
                    Pair.of("gcc", "path-to-gcc-tool"),
                    Pair.of("gcov-tool", "path-to-gcov-tool"),
                    Pair.of("ar", "ar"),
                    Pair.of("cpp", "cpp"),  // No path for gcov
                    Pair.of("ld", "ld"),
                    Pair.of("nm", "nm"),
                    Pair.of("objdump", "objdump"),
                    Pair.of("strip", "strip")
                )
        scratch.file(
            "a/BUILD",
            "load('@rules_cc//cc:cc_library.bzl', 'cc_library')",
            "load('@rules_cc//cc/toolchains:cc_toolchain.bzl', 'cc_toolchain')",
            "load(':cc_toolchain_config.bzl', 'cc_toolchain_config')",
            "filegroup(",
            "   name='empty')",
            "cc_toolchain(",
            "    name = 'b',",
            "    all_files = ':empty',",
            "    ar_files = ':empty',",
            "    as_files = ':empty',",
            "    compiler_files = ':empty',",
            "    dwp_files = ':empty',",
            "    linker_files = ':empty',",
            "    strip_files = ':empty',",
            "    objcopy_files = ':empty',",
            "    toolchain_identifier = 'banana',",
            "    toolchain_config = ':k8-compiler_config',",
            ")",
            ccToolchainConfigBuilder.build().ccToolchainConfigRule,
            "cc_library(",
            "    name = 'lib',",
            "    toolchains = [':b'],",
            ")"
        )
        analysisMock.ccSupport().setupCcToolchainConfig(mockToolsConfig, ccToolchainConfigBuilder)
        mockToolsConfig.create(
            "a/cc_toolchain_config.bzl",
            com.google.devtools.build.lib.packages.util.ResourceLoader.readFromResources(
                "com/google/devtools/build/lib/analysis/mock/cc_toolchain_config.bzl"
            )
        )
        useConfiguration("--collect_code_coverage", "--instrumentation_filter=//a[:/]")
        val instrumentedFilesInfo: InstrumentedFilesInfo =
            getConfiguredTarget("//a:lib").get(InstrumentedFilesInfo.provider)

        assertThat(instrumentedFilesInfo.getCoverageEnvironment())
            .doesNotContainKey("COVERAGE_GCOV_PATH")
    }

    // regression test for b/319501294
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testEmptyCoverageFilesDefaultsToAllFiles() {
        val ccToolchainConfigBuilder: com.google.devtools.build.lib.packages.util.Crosstool.CcToolchainConfig.Builder =
            CcToolchainConfig.builder()
        scratch.file(
            "a/BUILD",
            "load('@rules_cc//cc:cc_library.bzl', 'cc_library')",
            "load('@rules_cc//cc/toolchains:cc_toolchain.bzl', 'cc_toolchain')",
            "load(':cc_toolchain_config.bzl', 'cc_toolchain_config')",
            "filegroup(name='empty')",
            "filegroup(name='my_files', srcs = ['file1', 'file2'])",
            "cc_toolchain(",
            "    name = 'b',",
            "    all_files = ':my_files',",
            "    ar_files = ':empty',",
            "    as_files = ':empty',",
            "    compiler_files = ':empty',",
            "    dwp_files = ':empty',",
            "    linker_files = ':empty',",
            "    strip_files = ':empty',",
            "    objcopy_files = ':empty',",
            "    toolchain_identifier = 'banana',",
            "    toolchain_config = ':k8-compiler_config',",
            ")",
            ccToolchainConfigBuilder.build().ccToolchainConfigRule,
            "cc_library(",
            "    name = 'lib',",
            "    toolchains = [':b'],",
            ")"
        )
        analysisMock.ccSupport().setupCcToolchainConfig(mockToolsConfig, ccToolchainConfigBuilder)
        mockToolsConfig.create(
            "a/cc_toolchain_config.bzl",
            com.google.devtools.build.lib.packages.util.ResourceLoader.readFromResources(
                "com/google/devtools/build/lib/analysis/mock/cc_toolchain_config.bzl"
            )
        )

        val provider: CcToolchainProvider = CcToolchainProvider.getFromTarget(getConfiguredTarget("//a:b"))

        Truth.assertThat(artifactsToStrings(provider.getCoverageFiles()))
            .containsExactly("src a/file1", "src a/file2")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testLlvmCoverageToolsDefined() {
        val ccToolchainConfigBuilder: com.google.devtools.build.lib.packages.util.Crosstool.CcToolchainConfig.Builder =
            CcToolchainConfig.builder()
                .withToolPaths(
                    Pair.of("gcc", "path-to-gcc-tool"),
                    Pair.of("gcov-tool", "path-to-gcov-tool"),
                    Pair.of("ar", "ar"),
                    Pair.of("cpp", "cpp"),
                    Pair.of("ld", "ld"),
                    Pair.of("llvm-cov", "path-to-llvm-cov"),
                    Pair.of("llvm-profdata", "path-to-llvm-profdata"),
                    Pair.of("nm", "nm"),
                    Pair.of("objdump", "objdump"),
                    Pair.of("strip", "strip")
                )
        scratch.file(
            "a/BUILD",
            "load('@rules_cc//cc:cc_library.bzl', 'cc_library')",
            "load('@rules_cc//cc/toolchains:cc_toolchain.bzl', 'cc_toolchain')",
            "load(':cc_toolchain_config.bzl', 'cc_toolchain_config')",
            "filegroup(",
            "   name='empty')",
            "cc_toolchain(",
            "    name = 'b',",
            "    all_files = ':empty',",
            "    ar_files = ':empty',",
            "    as_files = ':empty',",
            "    compiler_files = ':empty',",
            "    dwp_files = ':empty',",
            "    linker_files = ':empty',",
            "    strip_files = ':empty',",
            "    objcopy_files = ':empty',",
            "    toolchain_identifier = 'banana',",
            "    toolchain_config = ':k8-compiler_config',",
            ")",
            ccToolchainConfigBuilder.build().ccToolchainConfigRule,
            "cc_library(",
            "    name = 'lib',",
            "    toolchains = [':b'],",
            ")"
        )
        analysisMock.ccSupport().setupCcToolchainConfig(mockToolsConfig, ccToolchainConfigBuilder)
        mockToolsConfig.create(
            "a/cc_toolchain_config.bzl",
            com.google.devtools.build.lib.packages.util.ResourceLoader.readFromResources(
                "com/google/devtools/build/lib/analysis/mock/cc_toolchain_config.bzl"
            )
        )
        useConfiguration("--collect_code_coverage", "--instrumentation_filter=//a[:/]")

        val coverageEnv: com.google.common.collect.ImmutableMap<String?, String?> =
            getConfiguredTarget("//a:lib")
                .get(InstrumentedFilesInfo.provider)
                .getCoverageEnvironment()

        Truth.assertThat(coverageEnv).containsKey("LLVM_COV")
        Truth.assertThat(coverageEnv.get("LLVM_COV")).isNotEmpty()
        Truth.assertThat(coverageEnv).containsKey("LLVM_PROFDATA")
        Truth.assertThat(coverageEnv.get("LLVM_PROFDATA")).isNotEmpty()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testLlvmCoverageToolsNotDefined() {
        val ccToolchainConfigBuilder: com.google.devtools.build.lib.packages.util.Crosstool.CcToolchainConfig.Builder =
            CcToolchainConfig.builder()
                .withToolPaths(
                    Pair.of("gcc", "path-to-gcc-tool"),
                    Pair.of("gcov-tool", "path-to-gcov-tool"),
                    Pair.of("ar", "ar"),
                    Pair.of("cpp", "cpp"),  // No paths for llvm-cov, llvm-profdata
                    Pair.of("ld", "ld"),
                    Pair.of("nm", "nm"),
                    Pair.of("objdump", "objdump"),
                    Pair.of("strip", "strip")
                )
        scratch.file(
            "a/BUILD",
            "load('@rules_cc//cc:cc_library.bzl', 'cc_library')",
            "load('@rules_cc//cc/toolchains:cc_toolchain.bzl', 'cc_toolchain')",
            "load(':cc_toolchain_config.bzl', 'cc_toolchain_config')",
            "filegroup(",
            "   name='empty')",
            "cc_toolchain(",
            "    name = 'b',",
            "    all_files = ':empty',",
            "    ar_files = ':empty',",
            "    as_files = ':empty',",
            "    compiler_files = ':empty',",
            "    dwp_files = ':empty',",
            "    linker_files = ':empty',",
            "    strip_files = ':empty',",
            "    objcopy_files = ':empty',",
            "    toolchain_identifier = 'banana',",
            "    toolchain_config = ':k8-compiler_config',",
            ")",
            ccToolchainConfigBuilder.build().ccToolchainConfigRule,
            "cc_library(",
            "    name = 'lib',",
            "    toolchains = [':b'],",
            ")"
        )
        analysisMock.ccSupport().setupCcToolchainConfig(mockToolsConfig, ccToolchainConfigBuilder)
        mockToolsConfig.create(
            "a/cc_toolchain_config.bzl",
            com.google.devtools.build.lib.packages.util.ResourceLoader.readFromResources(
                "com/google/devtools/build/lib/analysis/mock/cc_toolchain_config.bzl"
            )
        )
        useConfiguration("--collect_code_coverage", "--instrumentation_filter=//a[:/]")

        val coverageEnv: com.google.common.collect.ImmutableMap<String?, String?>? =
            getConfiguredTarget("//a:lib")
                .get(InstrumentedFilesInfo.provider)
                .getCoverageEnvironment()

        Truth.assertThat(coverageEnv).doesNotContainKey("LLVM_COV")
        Truth.assertThat(coverageEnv).doesNotContainKey("LLVM_PROFDATA")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testEnableCoveragePropagatesSupportFiles() {
        scratch.file(
            "a/BUILD",
            """
        load("@rules_cc//cc:cc_library.bzl", "cc_library")
        load("@rules_cc//cc/toolchains:cc_toolchain_alias.bzl", "cc_toolchain_alias")
        cc_toolchain_alias(name = "toolchain")

        cc_library(
            name = "lib",
        )
        
        """.trimIndent()
        )
        useConfiguration("--collect_code_coverage", "--instrumentation_filter=//a[:/]")

        val ccToolchainProvider: CcToolchainProvider =
            CcToolchainProvider.getFromTarget(getConfiguredTarget("//a:toolchain"))
        val instrumentedFilesInfo: InstrumentedFilesInfo =
            getConfiguredTarget("//a:lib").get(InstrumentedFilesInfo.provider)

        assertThat(instrumentedFilesInfo.getCoverageSupportFiles().toList()).isNotEmpty()
        assertThat(instrumentedFilesInfo.getCoverageSupportFiles().toList())
            .containsExactlyElementsIn(ccToolchainProvider.getCoverageFiles().toList())
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDisableCoverageDoesNotPropagateSupportFiles() {
        scratch.file(
            "a/BUILD",
            """
        load("@rules_cc//cc:cc_library.bzl", "cc_library")
        load("@rules_cc//cc/toolchains:cc_toolchain_alias.bzl", "cc_toolchain_alias")
        cc_toolchain_alias(name = "toolchain")

        cc_library(
            name = "lib",
        )
        
        """.trimIndent()
        )

        val instrumentedFilesInfo: InstrumentedFilesInfo =
            getConfiguredTarget("//a:lib").get(InstrumentedFilesInfo.provider)

        assertThat(instrumentedFilesInfo.getCoverageSupportFiles().toList()).isEmpty()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRuntimeLibsAttributesAreNotObligatory() {
        scratch.file(
            "a/BUILD",
            """
        load("@rules_cc//cc/toolchains:cc_toolchain.bzl", "cc_toolchain")
        load(":cc_toolchain_config.bzl", "cc_toolchain_config")

        filegroup(name = "empty")

        cc_toolchain(
            name = "b",
            all_files = ":empty",
            ar_files = ":empty",
            as_files = ":empty",
            compiler_files = ":empty",
            dwp_files = ":empty",
            linker_files = ":empty",
            objcopy_files = ":empty",
            strip_files = ":empty",
            toolchain_config = ":banana_config",
            toolchain_identifier = "banana",
        )

        cc_toolchain_config(name = "banana_config")
        
        """.trimIndent()
        )
        scratch.file("a/cc_toolchain_config.bzl", MockCcSupport.EMPTY_CC_TOOLCHAIN)
        analysisMock.ccSupport().setupCcToolchainConfig(mockToolsConfig, CcToolchainConfig.builder())
        reporter.removeHandler(failFastHandler)
        getConfiguredTarget("//a:b")
        assertNoEvents()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testWhenStaticRuntimeLibAttributeMandatoryWhenSupportsEmbeddedRuntimes() {
        scratch.file(
            "a/BUILD",
            "load('@rules_cc//cc:cc_binary.bzl', 'cc_binary')",
            "load('@rules_cc//cc/toolchains:cc_toolchain.bzl', 'cc_toolchain')",
            "load(':cc_toolchain_config.bzl', 'cc_toolchain_config')",
            "filegroup(name = 'empty')",
            "cc_binary(name = 'main', srcs = [ 'main.cc' ],)",
            "cc_binary(name = 'test', linkstatic = 0, srcs = [ 'test.cc' ],)",
            "cc_toolchain(",
            "    name = 'b',",
            "    all_files = ':empty',",
            "    ar_files = ':empty',",
            "    as_files = ':empty',",
            "    compiler_files = ':empty',",
            "    dwp_files = ':empty',",
            "    linker_files = ':empty',",
            "    strip_files = ':empty',",
            "    objcopy_files = ':empty',",
            "    toolchain_identifier = 'banana',",
            "    toolchain_config = ':k8-compiler_config',",
            ")",
            CcToolchainConfig.builder()
                .withFeatures(CppRuleClasses.STATIC_LINK_CPP_RUNTIMES)
                .build()
                .getCcToolchainConfigRule(),
            "toolchain(",
            "  name = 'cc-toolchain-b',",
            "  toolchain_type = '" + TestConstants.TOOLS_REPOSITORY + "//tools/cpp:toolchain_type',",
            "  toolchain = ':b',",
            "  target_compatible_with = [],",
            "  exec_compatible_with = [],",
            ")"
        )
        analysisMock.ccSupport().setupCcToolchainConfig(mockToolsConfig, CcToolchainConfig.builder())
        mockToolsConfig.create(
            "a/cc_toolchain_config.bzl",
            com.google.devtools.build.lib.packages.util.ResourceLoader.readFromResources(
                "com/google/devtools/build/lib/analysis/mock/cc_toolchain_config.bzl"
            )
        )
        reporter.removeHandler(failFastHandler)
        useConfiguration(
            "--extra_toolchains=//a:cc-toolchain-b",
            "--platforms=" + TestConstants.PLATFORM_LABEL,
            "--host_platform=" + TestConstants.PLATFORM_LABEL
        )
        assertThat(getConfiguredTarget("//a:main")).isNull()
        assertContainsEvent(
            "Toolchain supports embedded runtimes, but didn't provide static_runtime_lib attribute."
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testWhenDynamicRuntimeLibAttributeMandatoryWhenSupportsEmbeddedRuntimes() {
        scratch.file(
            "a/BUILD",
            "load(':cc_toolchain_config.bzl', 'cc_toolchain_config')",
            "load('@rules_cc//cc:cc_binary.bzl', 'cc_binary')",
            "load('@rules_cc//cc/toolchains:cc_toolchain.bzl', 'cc_toolchain')",
            "filegroup(name = 'empty')",
            "cc_binary(name = 'main', srcs = [ 'main.cc' ],)",
            "cc_binary(name = 'test', linkstatic = 0, srcs = [ 'test.cc' ],)",
            "cc_toolchain(",
            "    name = 'b',",
            "    all_files = ':empty',",
            "    ar_files = ':empty',",
            "    as_files = ':empty',",
            "    compiler_files = ':empty',",
            "    dwp_files = ':empty',",
            "    linker_files = ':empty',",
            "    strip_files = ':empty',",
            "    objcopy_files = ':empty',",
            "    static_runtime_lib = ':empty',",
            "    toolchain_identifier = 'banana',",
            "    toolchain_config = ':k8-compiler_config',",
            ")",
            CcToolchainConfig.builder()
                .withFeatures(
                    CppRuleClasses.STATIC_LINK_CPP_RUNTIMES, CppRuleClasses.SUPPORTS_DYNAMIC_LINKER
                )
                .build()
                .getCcToolchainConfigRule(),
            "toolchain(",
            "  name = 'cc-toolchain-b',",
            "  toolchain_type = '" + TestConstants.TOOLS_REPOSITORY + "//tools/cpp:toolchain_type',",
            "  toolchain = ':b',",
            "  target_compatible_with = [],",
            "  exec_compatible_with = [],",
            ")"
        )
        analysisMock.ccSupport().setupCcToolchainConfig(mockToolsConfig, CcToolchainConfig.builder())
        mockToolsConfig.create(
            "a/cc_toolchain_config.bzl",
            com.google.devtools.build.lib.packages.util.ResourceLoader.readFromResources(
                "com/google/devtools/build/lib/analysis/mock/cc_toolchain_config.bzl"
            )
        )
        reporter.removeHandler(failFastHandler)
        useConfiguration(
            "--extra_toolchains=//a:cc-toolchain-b",
            "--platforms=" + TestConstants.PLATFORM_LABEL,
            "--host_platform=" + TestConstants.PLATFORM_LABEL,
            "--dynamic_mode=fully"
        )
        assertThat(getConfiguredTarget("//a:test")).isNull()
        assertContainsEvent(
            "Toolchain supports embedded runtimes, but didn't provide dynamic_runtime_lib attribute."
        )
    }
}
