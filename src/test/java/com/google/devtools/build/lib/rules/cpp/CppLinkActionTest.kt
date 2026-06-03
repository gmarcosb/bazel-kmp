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
package com.google.devtools.build.lib.rules.cpp

import com.google.devtools.build.lib.actions.Artifact

/** Tests for [CppLinkAction].  */
@RunWith(JUnit4::class)
class CppLinkActionTest : BuildViewTestCase() {
    @Before
    @Throws(IOException::class)
    fun setupCcToolchainConfig() {
        scratch.overwriteFile(
            "tools/cpp/cc_toolchain_config_lib.bzl",
            com.google.devtools.build.lib.packages.util.ResourceLoader.readFromResources(
                TestConstants.RULES_CC_REPOSITORY_EXECROOT + "cc/cc_toolchain_config_lib.bzl"
            )
        )
        scratch.appendFile("tools/cpp/BUILD")
    }

    @Throws(IOException::class)
    fun registerToolchainWithConfig(vararg config: String?) {
        scratch.file(
            "toolchain/crosstool_rule.bzl",
            """
        load("@rules_cc//cc/toolchains:cc_toolchain_config_info.bzl", "CcToolchainConfigInfo")
        load('@rules_cc//cc/common:cc_common.bzl', 'cc_common')
        load(
            "//tools/cpp:cc_toolchain_config_lib.bzl",
            "action_config",
            "env_entry",
            "env_set",
            "feature",
            "feature_set",
            "flag_group",
            "flag_set",
            "tool",
            "tool_path",
        )

        def _impl(ctx):
            return cc_common.create_cc_toolchain_config_info(
                ctx = ctx,
                toolchain_identifier = "",
                compiler = "",
        
        """.trimIndent(),
            java.lang.String.join("\\n", *config),
            """
            )

        cc_toolchain_config_rule = rule(
            implementation = _impl,
            attrs = {},
            provides = [CcToolchainConfigInfo],
            fragments = ["cpp"],
        )
        
        """.trimIndent()
        )
        scratch.file(
            "toolchain/BUILD",
            ("""
load("@rules_cc//cc/toolchains:cc_toolchain.bzl", "cc_toolchain")
load(":crosstool_rule.bzl", "cc_toolchain_config_rule")
cc_toolchain_config_rule(name = "toolchain_config")
filegroup(name = "empty")
cc_toolchain(
    name = "cc_toolchain",
    all_files = ":empty",
    ar_files = ":empty",
    as_files = ":empty",
    compiler_files = ":empty",
    dwp_files = ":empty",
    linker_files = ":empty",
    objcopy_files = ":empty",
    strip_files = ":empty",
    toolchain_config = ":toolchain_config",
)
toolchain(name = "toolchain", toolchain = ":cc_toolchain", toolchain_type = '
"""
                .trimIndent()
                    + TestConstants.TOOLS_REPOSITORY
                    + "//tools/cpp:toolchain_type')")
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testToolchainFeatureFlags() {
        registerToolchainWithConfig(
            """
        features = [feature(
            name = "a",
            flag_sets = [flag_set(
                actions = ["c++-link-executable"],
                flag_groups = [flag_group(flags = ["some_flag"])],
            )],
        )]
        
        """.trimIndent()
        )
        useConfiguration("--features=a", "--extra_toolchains=//toolchain")
        scratch.file(
            "foo/BUILD",
            "load('@rules_cc//cc:cc_binary.bzl', 'cc_binary')",
            "cc_binary(name = 'foo')"
        )

        val linkAction: SpawnAction =
            com.google.common.collect.Iterables.getOnlyElement<Action>(getActions("//foo", "CppLink")) as SpawnAction
        com.google.common.truth.Subject.contains("some_flag")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testExecutionRequirementsFromCrosstool() {
        registerToolchainWithConfig(
            """
        action_configs = [action_config(
            action_name = "c++-link-executable",
            tools = [tool(
                path = "DUMMY_TOOL",
                execution_requirements = ["supports-exec-requirement"],
            )],
        )]
        
        """.trimIndent()
        )
        useConfiguration("--extra_toolchains=//toolchain")
        scratch.file(
            "foo/BUILD",
            "load('@rules_cc//cc:cc_binary.bzl', 'cc_binary')",
            "cc_binary(name = 'foo')"
        )

        val linkAction: SpawnAction =
            com.google.common.collect.Iterables.getOnlyElement<Action>(getActions("//foo", "CppLink")) as SpawnAction
        assertThat(linkAction.getExecutionInfo()).containsEntry("supports-exec-requirement", "")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testLinkoptsAndLibSrcsAreInCorrectOrder() {
        scratch.file(
            "x/BUILD",
            """
        load("@rules_cc//cc:cc_binary.bzl", "cc_binary")
        cc_binary(
            name = "foo",
            srcs = [
                "some-dir/libbar.so",
                "some-other-dir/qux.so",
            ],
            linkopts = [
                "-ldl",
                "-lutil",
            ],
        )
        
        """.trimIndent()
        )
        scratch.file("x/some-dir/libbar.so")
        scratch.file("x/some-other-dir/qux.so")

        val configuredTarget: ConfiguredTarget = getConfiguredTarget("//x:foo")
        val linkAction: SpawnAction = getGeneratingAction(configuredTarget, "x/foo") as SpawnAction

        val arguments: MutableList<String?> = linkAction.getArguments()

        Truth.assertThat(com.google.common.base.Joiner.on(" ").join(arguments))
            .matches(
                ".* -L[^ ]*some-dir(?= ).* -L[^ ]*some-other-dir(?= ).* "
                        + "-lbar -l:qux.so(?= ).* -ldl -lutil .*"
            )
        Truth.assertThat(com.google.common.base.Joiner.on(" ").join(arguments))
            .matches(
                ".* -Xlinker -rpath -Xlinker [^ ]*some-dir(?= ).* -Xlinker -rpath -Xlinker [^"
                        + " ]*some-other-dir .*"
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testLegacyWholeArchiveHasNoEffectOnDynamicModeDynamicLibraries() {
        getAnalysisMock()
            .ccSupport()
            .setupCcToolchainConfig(
                mockToolsConfig,
                CcToolchainConfig.builder().withFeatures(CppRuleClasses.SUPPORTS_DYNAMIC_LINKER)
            )
        scratch.file(
            "x/BUILD",
            """
        load("@rules_cc//cc:cc_binary.bzl", "cc_binary")
        cc_binary(
            name = "libfoo.so",
            srcs = ["foo.cc"],
            linkshared = 1,
            linkstatic = 0,
        )
        
        """.trimIndent()
        )
        useConfiguration("--legacy_whole_archive")
        Truth.assertThat(this.libfooArguments).doesNotContain("-Wl,-whole-archive")
    }

    @get:Throws(java.lang.Exception::class)
    private val libfooArguments: MutableList<String?>
        get() {
            val configuredTarget: ConfiguredTarget = getConfiguredTarget("//x:libfoo.so")
            val linkAction: SpawnAction = getGeneratingAction(configuredTarget, "x/libfoo.so") as SpawnAction
            return linkAction.getArguments()
        }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testExposesRuntimeLibrarySearchDirectoriesVariable() {
        scratch.file(
            "x/BUILD",
            """
        load("@rules_cc//cc:cc_binary.bzl", "cc_binary")
        cc_binary(
            name = "foo",
            srcs = [
                "some-dir/bar.so",
                "some-other-dir/qux.so",
            ],
        )
        
        """.trimIndent()
        )
        scratch.file("x/some-dir/bar.so")
        scratch.file("x/some-other-dir/qux.so")

        val configuredTarget: ConfiguredTarget = getConfiguredTarget("//x:foo")
        val linkAction: SpawnAction = getGeneratingAction(configuredTarget, "x/foo") as SpawnAction

        Truth.assertThat(com.google.common.base.Joiner.on(" ").join(linkAction.getArguments()))
            .matches(".*some-dir .*some-other-dir.*")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCompilesDynamicModeTestSourcesWithFeatureIntoDynamicLibrary() {
        if (com.google.devtools.build.lib.util.OS.getCurrent() == com.google.devtools.build.lib.util.OS.WINDOWS) {
            // Skip the test on Windows.
            // TODO(#7524): This test should work on Windows just fine, investigate and fix.
            return
        }
        getAnalysisMock()
            .ccSupport()
            .setupCcToolchainConfig(
                mockToolsConfig,
                CcToolchainConfig.builder()
                    .withFeatures(
                        CppRuleClasses.SUPPORTS_PIC,
                        CppRuleClasses.SUPPORTS_DYNAMIC_LINKER,
                        CppRuleClasses.SUPPORTS_INTERFACE_SHARED_LIBRARIES
                    )
            )
        scratch.file(
            "x/BUILD",
            """
        load("@rules_cc//cc:cc_binary.bzl", "cc_binary")
        load("@rules_cc//cc:cc_test.bzl", "cc_test")
        cc_test(
            name = "a",
            srcs = ["a.cc"],
            features = ["dynamic_link_test_srcs"],
        )

        cc_binary(
            name = "b",
            srcs = ["a.cc"],
        )

        cc_test(
            name = "c",
            srcs = ["a.cc"],
            features = ["dynamic_link_test_srcs"],
            linkstatic = 1,
        )
        
        """.trimIndent()
        )
        scratch.file("x/a.cc", "int main() {}")
        useConfiguration("--force_pic")

        var configuredTarget: ConfiguredTarget = getConfiguredTarget("//x:a")
        var linkAction: SpawnAction = getGeneratingAction(configuredTarget, "x/a") as SpawnAction
        Truth.assertThat(artifactsToStrings(linkAction.getInputs()))
            .contains("bin _solib_k8/libx_Sliba.ifso")
        com.google.common.truth.Subject.contains(getBinArtifactWithNoOwner("_solib_k8/libx_Sliba.ifso").getExecPathString())
        var runfilesProvider: RunfilesProvider = configuredTarget.getProvider(RunfilesProvider::class.java)
        Truth.assertThat(artifactsToStrings(runfilesProvider.getDefaultRunfiles().getArtifacts()))
            .contains("bin _solib_k8/libx_Sliba.so")

        configuredTarget = getConfiguredTarget("//x:b")
        linkAction = getGeneratingAction(configuredTarget, "x/b") as SpawnAction
        Truth.assertThat(artifactsToStrings(linkAction.getInputs())).contains("bin x/_objs/b/a.pic.o")
        runfilesProvider = configuredTarget.getProvider(RunfilesProvider::class.java)
        Truth.assertThat(artifactsToStrings(runfilesProvider.getDefaultRunfiles().getArtifacts()))
            .containsExactly("bin x/b")

        configuredTarget = getConfiguredTarget("//x:c")
        linkAction = getGeneratingAction(configuredTarget, "x/c") as SpawnAction
        Truth.assertThat(artifactsToStrings(linkAction.getInputs())).contains("bin x/_objs/c/a.pic.o")
        runfilesProvider = configuredTarget.getProvider(RunfilesProvider::class.java)
        Truth.assertThat(artifactsToStrings(runfilesProvider.getDefaultRunfiles().getArtifacts()))
            .containsExactly("bin x/c")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCompilesDynamicModeBinarySourcesWithoutFeatureIntoDynamicLibrary() {
        if (com.google.devtools.build.lib.util.OS.getCurrent() == com.google.devtools.build.lib.util.OS.WINDOWS) {
            // Skip the test on Windows.
            // TODO(#7524): This test should work on Windows just fine, investigate and fix.
            return
        }
        getAnalysisMock()
            .ccSupport()
            .setupCcToolchainConfig(
                mockToolsConfig,
                CcToolchainConfig.builder()
                    .withFeatures(CppRuleClasses.SUPPORTS_DYNAMIC_LINKER, CppRuleClasses.SUPPORTS_PIC)
            )
        scratch.file(
            "x/BUILD",
            "load('@rules_cc//cc:cc_binary.bzl', 'cc_binary')",
            "cc_binary(name = 'a', srcs = ['a.cc'], features = ['-static_link_test_srcs'])"
        )
        scratch.file("x/a.cc", "int main() {}")
        useConfiguration("--force_pic", "--dynamic_mode=default")

        val configuredTarget: ConfiguredTarget = getConfiguredTarget("//x:a")
        val linkAction: SpawnAction = getGeneratingAction(configuredTarget, "x/a") as SpawnAction
        Truth.assertThat(artifactsToStrings(linkAction.getInputs()))
            .doesNotContain("bin _solib_k8/libx_Sliba.ifso")
        Truth.assertThat(artifactsToStrings(linkAction.getInputs())).contains("bin x/_objs/a/a.pic.o")
        val runfilesProvider: RunfilesProvider = configuredTarget.getProvider(RunfilesProvider::class.java)
        Truth.assertThat(artifactsToStrings(runfilesProvider.getDefaultRunfiles().getArtifacts()))
            .containsExactly("bin x/a")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testToolchainFeatureEnv() {
        registerToolchainWithConfig(
            """
        features = [feature(
            name = "a",
            env_sets = [env_set(
                actions = ["c++-link-executable"],
                env_entries = [env_entry(key = "foo", value = "bar")],
            )],
        )]
        
        """.trimIndent()
        )
        useConfiguration("--features=a", "--extra_toolchains=//toolchain")
        scratch.file(
            "foo/BUILD",
            "load('@rules_cc//cc:cc_binary.bzl', 'cc_binary')",
            "cc_binary(name = 'foo')"
        )

        val linkAction: SpawnAction =
            com.google.common.collect.Iterables.getOnlyElement<Action>(getActions("//foo", "CppLink")) as SpawnAction
        assertThat(linkAction.getEffectiveEnvironment(com.google.common.collect.ImmutableMap.of<K?, V?>())).containsEntry(
            "foo",
            "bar"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCommandLineSplittingWithoutArchiveParamFileFeature_shouldBeOffForCcLibrary() {
        useConfiguration("--features=-archive_param_file")
        scratch.file(
            "foo/BUILD",
            "load('@rules_cc//cc:cc_library.bzl', 'cc_library')",
            "cc_library(name = 'foo', srcs = ['foo.cc'])"
        )

        val linkAction: SpawnAction =
            com.google.common.collect.Iterables.getOnlyElement<Action>(getActions("//foo", "CppArchive")) as SpawnAction

        assertThat(getCommandLine(linkAction).paramFileInfo).isNull()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCommandLineSplittingWithoutArchiveParamFileFeature_shouldBeOffForObjcLibrary() {
        MockObjcSupport.setup(mockToolsConfig)
        useConfiguration(
            "--features=-archive_param_file", "--platforms=" + MockObjcSupport.DARWIN_X86_64
        )
        invalidatePackages()
        scratch.file(
            "foo/BUILD",
            "load('@rules_cc//cc:objc_library.bzl', 'objc_library')",
            "objc_library(name = 'foo', srcs = ['foo.m'])"
        )

        val linkAction: SpawnAction =
            com.google.common.collect.Iterables.getOnlyElement<Action>(getActions("//foo", "CppArchive")) as SpawnAction

        assertThat(getCommandLine(linkAction).paramFileInfo).isNull()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCommandLineSplittingWithoutArchiveParamFileFeature_shouldBeOffForIfSo() {
        getAnalysisMock()
            .ccSupport()
            .setupCcToolchainConfig(
                mockToolsConfig,
                CcToolchainConfig.builder()
                    .withFeatures(
                        CppRuleClasses.SUPPORTS_DYNAMIC_LINKER,
                        CppRuleClasses.SUPPORTS_INTERFACE_SHARED_LIBRARIES
                    )
            )
        useConfiguration()
        scratch.file(
            "foo/BUILD",
            "load('@rules_cc//cc:cc_library.bzl', 'cc_library')",
            "cc_library(name = 'foo', srcs = ['foo.cc'])"
        )

        val linkAction: SpawnAction =
            com.google.common.collect.Iterables.getOnlyElement<Action>(getActions("//foo", "CppLink")) as SpawnAction
        assertThat(getCommandLine(linkAction).paramFileInfo).isNull()

        val archiveAction: SpawnAction =
            com.google.common.collect.Iterables.getOnlyElement<Action>(getActions("//foo", "CppArchive")) as SpawnAction
        assertThat(getCommandLine(archiveAction).paramFileInfo).isNull()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCommandLineSplittingWithoutArchiveParamFileFeature_shouldBeOffForPicStaticLibrary() {
        useConfiguration("--features=-archive_param_file", "--force_pic")
        scratch.file(
            "foo/BUILD",
            "load('@rules_cc//cc:cc_library.bzl', 'cc_library')",
            "cc_library(name = 'foo', srcs = ['foo.cc'])"
        )

        val archiveAction: SpawnAction =
            com.google.common.collect.Iterables.getOnlyElement<Action>(getActions("//foo", "CppArchive")) as SpawnAction
        assertThat(getCommandLine(archiveAction).paramFileInfo).isNull()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCommandLineSplittingWithoutArchiveParamFileFeature_shouldBeOffForAlwayslinkStaticLibrary() {
        useConfiguration("--features=-archive_param_file")
        scratch.file(
            "foo/BUILD",
            "load('@rules_cc//cc:cc_library.bzl', 'cc_library')",
            "cc_library(name = 'foo', srcs = ['foo.cc'], alwayslink = True)"
        )

        val archiveAction: SpawnAction =
            com.google.common.collect.Iterables.getOnlyElement<Action>(getActions("//foo", "CppArchive")) as SpawnAction
        assertThat(getCommandLine(archiveAction).paramFileInfo).isNull()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCommandLineSplittingWithoutArchiveParamFileFeature_shouldBeOffForAlwayslinkPicStaticLibrary() {
        useConfiguration("--features=*archive_param_file", "--force_pic")
        scratch.file(
            "foo/BUILD",
            "load('@rules_cc//cc:cc_library.bzl', 'cc_library')",
            "cc_library(name = 'foo', srcs = ['foo.cc'], alwayslink = True)"
        )

        val archiveAction: SpawnAction =
            com.google.common.collect.Iterables.getOnlyElement<Action>(getActions("//foo", "CppArchive")) as SpawnAction
        assertThat(getCommandLine(archiveAction).paramFileInfo).isNull()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCommandLineSplittingWithArchiveParamFileFeature_shouldBeOnForCcLibrary() {
        AnalysisMock.get()
            .ccSupport()
            .setupCcToolchainConfig(
                mockToolsConfig,
                CcToolchainConfig.builder().withFeatures(CppRuleClasses.ARCHIVE_PARAM_FILE)
            )
        useConfiguration("--features=archive_param_file")
        scratch.file(
            "foo/BUILD",
            "load('@rules_cc//cc:cc_library.bzl', 'cc_library')",
            "cc_library(name = 'foo', srcs = ['foo.cc'])"
        )

        val linkAction: SpawnAction =
            com.google.common.collect.Iterables.getOnlyElement<Action>(getActions("//foo", "CppArchive")) as SpawnAction

        val commandLine: CommandLineAndParamFileInfo = getCommandLine(linkAction)
        assertThat(commandLine.paramFileInfo).isNotNull()
        assertThat(commandLine.paramFileInfo.always()).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCommandLineSplittingWithArchiveParamFileFeature_shouldBeOffForIfSo() {
        getAnalysisMock()
            .ccSupport()
            .setupCcToolchainConfig(
                mockToolsConfig,
                CcToolchainConfig.builder()
                    .withFeatures(
                        CppRuleClasses.SUPPORTS_DYNAMIC_LINKER,
                        CppRuleClasses.SUPPORTS_INTERFACE_SHARED_LIBRARIES,
                        CppRuleClasses.ARCHIVE_PARAM_FILE
                    )
            )
        useConfiguration("--features=archive_param_file,supports_dynamic_linker")
        scratch.file(
            "foo/BUILD",
            "load('@rules_cc//cc:cc_library.bzl', 'cc_library')",
            "cc_library(name = 'foo', srcs = ['foo.cc'])"
        )

        val linkAction: SpawnAction =
            com.google.common.collect.Iterables.getOnlyElement<Action>(getActions("//foo", "CppLink")) as SpawnAction
        assertThat(getCommandLine(linkAction).paramFileInfo).isNull()

        val archiveAction: SpawnAction =
            com.google.common.collect.Iterables.getOnlyElement<Action>(getActions("//foo", "CppArchive")) as SpawnAction
        assertThat(getCommandLine(archiveAction).paramFileInfo).isNull()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCommandLineSplittingWithArchiveParamFileFeature_shouldBeOnForStaticLibrary() {
        getAnalysisMock()
            .ccSupport()
            .setupCcToolchainConfig(
                mockToolsConfig,
                CcToolchainConfig.builder().withFeatures(CppRuleClasses.ARCHIVE_PARAM_FILE)
            )
        useConfiguration("--features=archive_param_file")
        scratch.file(
            "foo/BUILD",
            "load('@rules_cc//cc:cc_library.bzl', 'cc_library')",
            "cc_library(name = 'foo', srcs = ['foo.cc'])"
        )

        val archiveAction: SpawnAction =
            com.google.common.collect.Iterables.getOnlyElement<Action>(getActions("//foo", "CppArchive")) as SpawnAction
        val archiveCommandLine: CommandLineAndParamFileInfo = getCommandLine(archiveAction)
        assertThat(archiveCommandLine.paramFileInfo).isNotNull()
        assertThat(archiveCommandLine.paramFileInfo.always()).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCommandLineSplittingWithArchiveParamFileFeature_shouldBeOnForPicStaticLibrary() {
        getAnalysisMock()
            .ccSupport()
            .setupCcToolchainConfig(
                mockToolsConfig,
                CcToolchainConfig.builder().withFeatures(CppRuleClasses.ARCHIVE_PARAM_FILE)
            )
        useConfiguration("--features=archive_param_file", "--force_pic")
        scratch.file(
            "foo/BUILD",
            "load('@rules_cc//cc:cc_library.bzl', 'cc_library')",
            "cc_library(name = 'foo', srcs = ['foo.cc'])"
        )

        val archiveAction: SpawnAction =
            com.google.common.collect.Iterables.getOnlyElement<Action>(getActions("//foo", "CppArchive")) as SpawnAction
        val archiveCommandLine: CommandLineAndParamFileInfo = getCommandLine(archiveAction)
        assertThat(archiveCommandLine.paramFileInfo).isNotNull()
        assertThat(archiveCommandLine.paramFileInfo.always()).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCommandLineSplittingWithArchiveParamFileFeature_shouldBeOnForAlwayslinkStaticLibrary() {
        getAnalysisMock()
            .ccSupport()
            .setupCcToolchainConfig(
                mockToolsConfig,
                CcToolchainConfig.builder().withFeatures(CppRuleClasses.ARCHIVE_PARAM_FILE)
            )
        useConfiguration("--features=archive_param_file")
        scratch.file(
            "foo/BUILD",
            "load('@rules_cc//cc:cc_library.bzl', 'cc_library')",
            "cc_library(name = 'foo', srcs = ['foo.cc'], alwayslink = True)"
        )

        val archiveAction: SpawnAction =
            com.google.common.collect.Iterables.getOnlyElement<Action>(getActions("//foo", "CppArchive")) as SpawnAction
        val archiveCommandLine: CommandLineAndParamFileInfo = getCommandLine(archiveAction)
        assertThat(archiveCommandLine.paramFileInfo).isNotNull()
        assertThat(archiveCommandLine.paramFileInfo.always()).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCommandLineSplittingWithArchiveParamFileFeature_shouldBeOnForAlwayslinkPicStaticLibrary() {
        getAnalysisMock()
            .ccSupport()
            .setupCcToolchainConfig(
                mockToolsConfig,
                CcToolchainConfig.builder().withFeatures(CppRuleClasses.ARCHIVE_PARAM_FILE)
            )
        useConfiguration("--features=archive_param_file", "--force_pic")
        scratch.file(
            "foo/BUILD",
            "load('@rules_cc//cc:cc_library.bzl', 'cc_library')",
            "cc_library(name = 'foo', srcs = ['foo.cc'], alwayslink = True)"
        )

        val archiveAction: SpawnAction =
            com.google.common.collect.Iterables.getOnlyElement<Action>(getActions("//foo", "CppArchive")) as SpawnAction
        val archiveCommandLine: CommandLineAndParamFileInfo = getCommandLine(archiveAction)
        assertThat(archiveCommandLine.paramFileInfo).isNotNull()
        assertThat(archiveCommandLine.paramFileInfo.always()).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCommandLineSplittingWithArchiveParamFileFeature_shouldBeOnForObjcLibrary() {
        MockObjcSupport.setup(mockToolsConfig)
        MockObjcSupport.setupCcToolchainConfig(
            mockToolsConfig,
            MockObjcSupport.darwinX86_64().withFeatures(CppRuleClasses.ARCHIVE_PARAM_FILE)
        )
        invalidatePackages()
        useConfiguration(
            "--features=archive_param_file", "--platforms=" + MockObjcSupport.DARWIN_X86_64
        )
        scratch.file(
            "foo/BUILD",
            "load('@rules_cc//cc:objc_library.bzl', 'objc_library')",
            "objc_library(name = 'foo', srcs = ['foo.m'])"
        )

        val archiveAction: SpawnAction =
            com.google.common.collect.Iterables.getOnlyElement<Action>(getActions("//foo", "CppArchive")) as SpawnAction
        val archiveCommandLine: CommandLineAndParamFileInfo = getCommandLine(archiveAction)
        assertThat(archiveCommandLine.paramFileInfo).isNotNull()
        assertThat(archiveCommandLine.paramFileInfo.always()).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testLocalLinkResourceEstimate() {
        scratch.file(
            "foo/BUILD",
            "load('@rules_cc//cc:cc_binary.bzl', 'cc_binary')",
            "cc_binary(name = 'foo')"
        )

        val linkAction: SpawnAction =
            com.google.common.collect.Iterables.getOnlyElement<Action>(getActions("//foo", "CppLink")) as SpawnAction

        val resourceSetOrBuilder: ResourceSetOrBuilder = linkAction.getResourceSetOrBuilder()
        assertThat(resourceSetOrBuilder.buildResourceSet(com.google.devtools.build.lib.util.OS.DARWIN, 100))
            .isEqualTo(ResourceSet.createWithRamCpu(20, 1))
        assertThat(resourceSetOrBuilder.buildResourceSet(com.google.devtools.build.lib.util.OS.DARWIN, 1000))
            .isEqualTo(ResourceSet.createWithRamCpu(65, 1))
        assertThat(resourceSetOrBuilder.buildResourceSet(com.google.devtools.build.lib.util.OS.LINUX, 100))
            .isEqualTo(ResourceSet.createWithRamCpu(50, 1))
        assertThat(resourceSetOrBuilder.buildResourceSet(com.google.devtools.build.lib.util.OS.LINUX, 10000))
            .isEqualTo(ResourceSet.createWithRamCpu(900, 1))
        assertThat(resourceSetOrBuilder.buildResourceSet(com.google.devtools.build.lib.util.OS.WINDOWS, 0))
            .isEqualTo(ResourceSet.createWithRamCpu(1500, 1))
        assertThat(resourceSetOrBuilder.buildResourceSet(com.google.devtools.build.lib.util.OS.WINDOWS, 1000))
            .isEqualTo(ResourceSet.createWithRamCpu(2500, 1))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testInterfaceOutputForDynamicLibrary() {
        getAnalysisMock()
            .ccSupport()
            .setupCcToolchainConfig(
                mockToolsConfig,
                CcToolchainConfig.builder()
                    .withFeatures(
                        CppRuleClasses.SUPPORTS_DYNAMIC_LINKER,
                        CppRuleClasses.SUPPORTS_INTERFACE_SHARED_LIBRARIES
                    )
            )
        useConfiguration()

        scratch.file(
            "foo/BUILD",
            "load('@rules_cc//cc:cc_library.bzl', 'cc_library')",
            "cc_library(name = 'foo', srcs = ['foo.cc'])"
        )
        val configuredTarget: ConfiguredTarget = getConfiguredTarget("//foo:foo")
        assertThat(configuredTarget).isNotNull()
        val inputs: com.google.common.collect.ImmutableList<String?> =
            getGeneratingAction(configuredTarget, "foo/libfoo.so").getInputs().toList().stream()
                .map(Artifact::getExecPathString)
                .collect(com.google.common.collect.ImmutableList.toImmutableList<E?>())
        Truth.assertThat(inputs.stream().anyMatch { i: String? -> i.contains("tools/cpp/link_dynamic_library") })
            .isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testInterfaceOutputForDynamicLibraryLegacy() {
        registerToolchainWithConfig(
            """
features = [
    feature(name = "supports_dynamic_linker", enabled = True),
    feature(name = "supports_interface_shared_libraries", enabled = True),
    feature(
        name = "build_interface_libraries",
        flag_sets = [flag_set(
            actions = ["c++-link-nodeps-dynamic-library"],
            flag_groups = [flag_group(flags = [
                "%{generate_interface_library}",
                "%{interface_library_builder_path}",
                "%{interface_library_input_path}",
                "%{interface_library_output_path}",
            ])],
        )],
    ),
    feature(
        name = "dynamic_library_linker_tool",
        flag_sets = [flag_set(
            actions = ["c++-link-nodeps-dynamic-library"],
            flag_groups = [flag_group(flags = ["dynamic_library_linker_tool"])],
        )],
    ),
    feature(name = "has_configured_linker_path"),
],
action_configs = [action_config(
    action_name = "c++-link-nodeps-dynamic-library",
    tools = [tool(
        path = "custom/crosstool/scripts/link_dynamic_library.sh",
    )],
    implies = ["has_configured_linker_path", "build_interface_libraries", "dynamic_library_linker_tool"],
)]

""".trimIndent()
        )
        useConfiguration(
            "--extra_toolchains=//toolchain",
            "--features=build_interface_libraries,dynamic_library_linker_tool"
        )
        scratch.file(
            "foo/BUILD",
            "load('@rules_cc//cc:cc_library.bzl', 'cc_library')",
            "cc_library(name = 'foo', srcs = ['a.c'])"
        )

        val linkAction: SpawnAction =
            com.google.common.collect.Iterables.getOnlyElement<Action>(getActions("//foo", "CppLink")) as SpawnAction

        val commandLine: MutableList<String?>? = linkAction.getArguments()
        Truth.assertThat(commandLine).hasSize(12)
        Truth.assertThat(commandLine!!.get(0)).endsWith("custom/crosstool/scripts/link_dynamic_library.sh")
        Truth.assertThat(commandLine.get(7)).isEqualTo("yes")
        Truth.assertThat(commandLine.get(8)).endsWith("tools/cpp/build_interface_so")
        Truth.assertThat(commandLine.get(9)).endsWith("foo.so")
        Truth.assertThat(commandLine.get(10)).endsWith("bin/foo/libfoo.ifso")
        Truth.assertThat(commandLine.get(11)).isEqualTo("dynamic_library_linker_tool")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testStaticLinkWithNativeDepsIsError() {
        getAnalysisMock()
            .ccSupport()
            .setupCcToolchainConfig(
                mockToolsConfig,
                CcToolchainConfig.builder().withActionConfigs(CppActionNames.OBJC_FULLY_LINK)
            )
        scratch.file("bazel_internal/test_rules/cc/BUILD")
        scratch.file(
            "bazel_internal/test_rules/cc/link_rule.bzl",
            """
load("@rules_cc//cc:find_cc_toolchain.bzl", "find_cc_toolchain", "use_cc_toolchain")
load('@rules_cc//cc/common:cc_common.bzl', 'cc_common')
def _impl(ctx):
    cc_toolchain = find_cc_toolchain(ctx)
    feature_configuration = cc_common.configure_features(
        ctx = ctx,
        cc_toolchain = cc_toolchain,
    )
    cc_linking_outputs = cc_common.link(
        actions = ctx.actions,
        feature_configuration = feature_configuration,
        cc_toolchain = cc_toolchain,
        name = ctx.label.name,
        native_deps = True,
        language = "objc",
        output_type = "archive",
    )
    cc_linking_outputs.all_lto_artifacts()
    return []

cc_link_rule = rule(
    implementation = _impl,
    fragments = ["cpp"],
    toolchains = use_cc_toolchain(),
)

""".trimIndent()
        )

        scratch.file(
            "foo/BUILD",
            "load('//bazel_internal/test_rules/cc:link_rule.bzl', 'cc_link_rule')",
            "cc_link_rule(name = 'foo')"
        )

        checkError("//foo", "the native deps flag must be false for static links")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testStaticLinkWithWholeArchiveIsError() {
        getAnalysisMock()
            .ccSupport()
            .setupCcToolchainConfig(
                mockToolsConfig,
                CcToolchainConfig.builder().withActionConfigs(CppActionNames.OBJC_FULLY_LINK)
            )
        scratch.file("bazel_internal/test_rules/cc/BUILD")
        scratch.file(
            "bazel_internal/test_rules/cc/link_rule.bzl",
            """
load("@rules_cc//cc:find_cc_toolchain.bzl", "find_cc_toolchain", "use_cc_toolchain")
load('@rules_cc//cc/common:cc_common.bzl', 'cc_common')
def _impl(ctx):
    cc_toolchain = find_cc_toolchain(ctx)
    feature_configuration = cc_common.configure_features(
        ctx = ctx,
        cc_toolchain = cc_toolchain,
    )
    cc_linking_outputs = cc_common.link(
        actions = ctx.actions,
        feature_configuration = feature_configuration,
        cc_toolchain = cc_toolchain,
        name = ctx.label.name,
        whole_archive = True,
        language = "objc",
        output_type = "archive",
    )
    cc_linking_outputs.all_lto_artifacts()
    return []

cc_link_rule = rule(
    implementation = _impl,
    fragments = ["cpp"],
    toolchains = use_cc_toolchain(),
)

""".trimIndent()
        )

        scratch.file(
            "foo/BUILD",
            "load('//bazel_internal/test_rules/cc:link_rule.bzl', 'cc_link_rule')",
            "cc_link_rule(name = 'foo')"
        )

        checkError("//foo", "the need whole archive flag must be false for static links")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testLinksTreeArtifactLibraries() {
        scratch.file("bazel_internal/test_rules/cc/BUILD")
        scratch.file(
            "bazel_internal/test_rules/cc/foo.bzl",
            """
load("@rules_cc//cc:find_cc_toolchain.bzl", "find_cc_toolchain", "use_cc_toolchain")
load('@rules_cc//cc/common:cc_common.bzl', 'cc_common')
def _impl(ctx):
    cc_toolchain = find_cc_toolchain(ctx)
    feature_configuration = cc_common.configure_features(
        ctx = ctx,
        cc_toolchain = cc_toolchain,
    )
    dir = ctx.actions.declare_directory("library_directory")
    ctx.actions.run(executable = ctx.executable._tool, outputs = [dir])
    compilation_outputs = cc_common.create_compilation_outputs(objects = depset([dir]))
    cc_common.link(
        actions = ctx.actions,
        feature_configuration = feature_configuration,
        cc_toolchain = cc_toolchain,
        name = ctx.label.name,
        compilation_outputs = compilation_outputs
    )

cc_link_rule = rule(
    implementation = _impl,
    attrs = {
        "_tool": attr.label(default = "//foo:tool", executable = True, cfg = "exec"),
    },
    fragments = ["cpp"],
    toolchains = use_cc_toolchain(),
)

""".trimIndent()
        )
        scratch.file(
            "foo/BUILD",
            """
load("@rules_cc//cc:cc_binary.bzl", "cc_binary")
load("//bazel_internal/test_rules/cc:foo.bzl", "cc_link_rule")
cc_link_rule(name = "foo")
cc_binary(name = "tool")

""".trimIndent()
        )

        val linkAction: SpawnAction =
            com.google.common.collect.Iterables.getOnlyElement<Action>(getActions("//foo", "CppLink")) as SpawnAction

        val testTreeArtifact: SpecialArtifact =
            ActionsTestUtil.getInput(linkAction, "library_directory") as SpecialArtifact
        val library0: TreeFileArtifact = TreeFileArtifact.createTreeOutput(testTreeArtifact, "library0.o")
        val library1: TreeFileArtifact = TreeFileArtifact.createTreeOutput(testTreeArtifact, "library1.o")

        // We don't read the tree artifact or its contents, so MISSING_FILE_MARKER is OK
        val treeArtifactValue: TreeArtifactValue =
            TreeArtifactValue.newBuilder(testTreeArtifact)
                .putChild(library0, FileArtifactValue.MISSING_FILE_MARKER)
                .putChild(library1, FileArtifactValue.MISSING_FILE_MARKER)
                .build()

        val fakeActionInputFileCache: FakeActionInputFileCache = FakeActionInputFileCache()
        fakeActionInputFileCache.putTreeArtifact(testTreeArtifact, treeArtifactValue)

        val treeArtifactsPaths: com.google.common.collect.ImmutableList<String?> =
            com.google.common.collect.ImmutableList.of<E?>(testTreeArtifact.getExecPathString())
        val treeFileArtifactsPaths: com.google.common.collect.ImmutableList<String?> =
            com.google.common.collect.ImmutableList.of<E?>(library0.getExecPathString(), library1.getExecPathString())

        // Should only reference the tree artifact.
        verifyArguments(linkAction.getArguments(), treeArtifactsPaths, treeFileArtifactsPaths)

        // Should only reference tree file artifacts.
        val expandedCommandLines: ExpandedCommandLines =
            linkAction
                .getCommandLines()
                .expand(
                    fakeActionInputFileCache,
                    linkAction.getPrimaryOutput().getExecPath(),
                    PathMapper.NOOP,
                    CommandLineLimits.UNLIMITED
                )
        verifyArguments(
            expandedCommandLines.getParamFiles().get(0).getArguments(),
            treeFileArtifactsPaths,
            treeArtifactsPaths
        )
    }

    /** Tests that -pie is removed when -shared is also present (http://b/5611891#).  */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testPieOptionDisabledForSharedLibraries() {
        scratch.file(
            "foo/BUILD",
            "load('@rules_cc//cc:cc_binary.bzl', 'cc_binary')",
            "cc_binary(name = 'foo', srcs = ['foo.cc'], linkopts = ['-pie', '-other', '-pie'],"
                    + " linkshared = True)"
        )

        val linkAction: SpawnAction =
            com.google.common.collect.Iterables.getOnlyElement<Action>(getActions("//foo", "CppLink")) as SpawnAction

        val argv: MutableList<String?>? = linkAction.getArguments()
        Truth.assertThat(argv).doesNotContain("-pie")
        Truth.assertThat(argv).contains("-other")
    }

    /** Tests that -pie is kept when -shared is not present (http://b/5611891#).  */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testPieOptionKeptForExecutables() {
        scratch.file(
            "foo/BUILD",
            "load('@rules_cc//cc:cc_binary.bzl', 'cc_binary')",
            "cc_binary(name = 'foo', srcs = ['foo.cc'], linkopts = ['-pie', '-other', '-pie'],"
                    + " linkshared = False)"
        )

        val linkAction: SpawnAction =
            com.google.common.collect.Iterables.getOnlyElement<Action>(getActions("//foo", "CppLink")) as SpawnAction

        val argv: MutableList<String?>? = linkAction.getArguments()
        Truth.assertThat(argv).contains("-pie")
        Truth.assertThat(argv).contains("-other")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testLinkoptsComeAfterLinkerInputs() {
        scratch.file(
            "foo/BUILD",
            "load('@rules_cc//cc:cc_binary.bzl', 'cc_binary')",
            "load('@rules_cc//cc:cc_library.bzl', 'cc_library')",
            "cc_library(name = 'bar1', srcs = ['bar.cc'])",
            "cc_library(name = 'bar2', srcs = ['bar.cc'])",
            "cc_binary(name = 'foo', srcs = ['foo.cc'], deps = [':bar1', ':bar2'], linkopts ="
                    + " ['FakeLinkopt1', 'FakeLinkopt2'])"
        )

        val linkAction: SpawnAction =
            com.google.common.collect.Iterables.getOnlyElement<Action>(getActions("//foo", "CppLink")) as SpawnAction

        val linkerInput1: Artifact = linkAction.getInputs().toList().get(0)
        val linkerInput2: Artifact = linkAction.getInputs().toList().get(1)
        val linkerInput3: Artifact = linkAction.getInputs().toList().get(2)
        val argv: MutableList<String?> = linkAction.getArguments()
        Truth.assertThat(argv)
            .containsAtLeast(
                linkerInput1.getExecPathString(),
                linkerInput2.getExecPathString(),
                linkerInput3.getExecPathString(),
                "FakeLinkopt1",
                "FakeLinkopt2"
            )
        val lastLinkerInputIndex: Int =
            com.google.common.primitives.Ints.max(
                argv.indexOf(linkerInput1.getExecPathString()),
                argv.indexOf(linkerInput2.getExecPathString()),
                argv.indexOf(linkerInput3.getExecPathString())
            )
        val firstLinkoptIndex: Int = min(argv.indexOf("FakeLinkopt1"), argv.indexOf("FakeLinkopt2"))
        Truth.assertThat(lastLinkerInputIndex).isLessThan(firstLinkoptIndex)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testLinkoptsAreOmittedForStaticLibrary() {
        registerToolchainWithConfig(
            """
        features = [feature(
            name = "user_link_flags",
            flag_sets = [flag_set(
                actions = ["c++-link-static-library"],
                flag_groups = [flag_group(
                    flags = ["%{user_link_flags}"],
                    iterate_over = 'user_link_flags',
                    expand_if_available = 'user_link_flags',
                )],
            )],
        )]
        
        """.trimIndent()
        )
        useConfiguration("--extra_toolchains=//toolchain")
        scratch.file(
            "foo/BUILD",
            "load('@rules_cc//cc:cc_library.bzl', 'cc_library')",
            "cc_library(name = 'foo', srcs = ['foo.cc'], linkopts = ['FakeLinkopt1'])"
        )

        val linkAction: SpawnAction =
            com.google.common.collect.Iterables.getOnlyElement<Action>(getActions("//foo", "CppArchive")) as SpawnAction

        assertThat(linkAction.getArguments()).doesNotContain("FakeLinkopt1")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testExposesLinkstampObjects() {
        scratch.file(
            "x/BUILD",
            """
        load("@rules_cc//cc:cc_binary.bzl", "cc_binary")
        load("@rules_cc//cc:cc_library.bzl", "cc_library")
        cc_binary(
            name = "bin",
            deps = [":lib"],
        )

        cc_library(
            name = "lib",
            linkstamp = "linkstamp.cc",
        )
        
        """.trimIndent()
        )
        val configuredTarget: ConfiguredTarget = getConfiguredTarget("//x:bin")
        val linkAction: SpawnAction = getGeneratingAction(configuredTarget, "x/bin") as SpawnAction
        Truth.assertThat(artifactsToStrings(linkAction.getInputs()))
            .contains("bin x/_objs/bin/x/linkstamp.o")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testGccQuotingForParamFilesFeature_enablesGccQuoting() {
        getAnalysisMock()
            .ccSupport()
            .setupCcToolchainConfig(
                mockToolsConfig,
                CcToolchainConfig.builder().withFeatures(CppRuleClasses.GCC_QUOTING_FOR_PARAM_FILES)
            )
        useConfiguration()

        scratch.file(
            "foo/BUILD",
            """
        load("@rules_cc//cc:cc_binary.bzl", "cc_binary")
        cc_binary(
            name = "foo",
            srcs = [
                'quote".cc',
                "space .cc",
            ],
        )
        
        """.trimIndent()
        )
        val configuredTarget: ConfiguredTarget = getConfiguredTarget("//foo:foo")
        val linkAction: SpawnAction = getGeneratingAction(configuredTarget, "foo/foo") as SpawnAction

        assertThat(getCommandLine(linkAction).paramFileInfo.getFileType())
            .isEqualTo(ParameterFileType.GCC_QUOTED)
    }

    private fun getCommandLine(linkOrArchiveAction: SpawnAction): CommandLineAndParamFileInfo {
        // Commandlines are a pair of a SingletonCommandLine with tool path and
        // a CommandLine with rest of command line
        // The latter optionally specifies a ParamFile
        return linkOrArchiveAction.getCommandLines().unpack().get(1)
    }

    companion object {
        private fun verifyArguments(
            arguments: Iterable<String?>?,
            allowedArguments: Iterable<String?>,
            disallowedArguments: Iterable<String?>
        ) {
            Truth.assertThat(arguments).containsAtLeastElementsIn(allowedArguments)
            Truth.assertThat(arguments).containsNoneIn(disallowedArguments)
        }
    }
}
