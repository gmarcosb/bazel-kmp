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

import com.google.devtools.build.lib.actions.Artifact

/** Tests that [CppLinkstampCompileHelper] creates sane compile actions for linkstamps  */
@RunWith(JUnit4::class)
class CppLinkstampCompileHelperTest : BuildViewTestCase() {
    /** Tests that linkstamp compilation applies expected command line options.  */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testLinkstampCompileOptionsForExecutable() {
        AnalysisMock.get()
            .ccSupport()
            .setupCcToolchainConfig(
                mockToolsConfig, CcToolchainConfig.builder().withSysroot("/usr/local/custom-sysroot")
            )
        setBuildLanguageOptions("--noincompatible_unambiguous_label_stringification")
        useConfiguration()
        scratch.file(
            "x/BUILD",
            """
        load("@rules_cc//cc:cc_binary.bzl", "cc_binary")
        load("@rules_cc//cc:cc_library.bzl", "cc_library")
        cc_binary(
            name = "foo",
            deps = ["a"],
        )

        cc_library(
            name = "a",
            srcs = ["a.cc"],
            linkstamp = "ls.cc",
        )
        
        """.trimIndent()
        )

        val target: ConfiguredTarget = getConfiguredTarget("//x:foo")
        val executable: Artifact = getExecutable(target)
        val generatingAction: SpawnAction = getGeneratingAction(executable) as SpawnAction

        val compiledLinkstamp: Artifact =
            ActionsTestUtil.getFirstArtifactEndingWith(generatingAction.getInputs(), "ls.o")
        val linkstampCompileAction: CppCompileAction =
            getGeneratingAction(compiledLinkstamp) as CppCompileAction

        val ccToolchainProvider: CcToolchainProvider =
            CcToolchainProvider.getFromTarget(
                getConfiguredTarget(
                    ruleClassProvider.getToolsRepository() + "//tools/cpp:current_cc_toolchain"
                )
            )

        val arguments: MutableList<String?>? = linkstampCompileAction.getArguments()
        assertThatArgumentsAreValid(
            arguments,
            ccToolchainProvider.getToolchainIdentifier(),
            target.getLabel().getCanonicalForm(),
            executable.getFilename()
        )
    }

    private fun assertThatArgumentsAreValid(
        arguments: MutableList<String?>?, platform: String?, targetName: String?, buildTargetNameSuffix: String
    ) {
        Truth.assertThat(arguments).contains("--sysroot=/usr/local/custom-sysroot")
        Truth.assertThat(arguments).contains("-include")
        Truth.assertThat(arguments).contains("-DG3_TARGET_NAME=\"" + targetName + "\"")
        Truth.assertThat(arguments).contains("-DGPLATFORM=\"" + platform + "\"")
        Truth.assertThat(arguments).contains("-I.")
        val correctG3BuildTargetPattern = "-DG3_BUILD_TARGET=\".*" + buildTargetNameSuffix + "\""
        Truth.assertWithMessage("in %s flag matching %s", arguments, correctG3BuildTargetPattern)
            .that(
                com.google.common.collect.Iterables.tryFind<String?>(
                    arguments,
                    com.google.common.base.Predicate { arg: String? -> arg.matches(correctG3BuildTargetPattern.toRegex()) })
            )
            .isPresent()
        val fdoStampPattern = "-D" + CppConfiguration.FDO_STAMP_MACRO + "=\".*\""
        Truth.assertWithMessage("in %s flag matching %s", arguments, fdoStampPattern)
            .that(
                com.google.common.collect.Iterables.tryFind<String?>(
                    arguments,
                    com.google.common.base.Predicate { arg: String? -> arg.matches(fdoStampPattern.toRegex()) })
            )
            .isAbsent()
    }

    /** Tests that linkstamp compilation applies expected command line options.  */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testLinkstampCompileOptionsForSharedLibrary() {
        AnalysisMock.get()
            .ccSupport()
            .setupCcToolchainConfig(
                mockToolsConfig, CcToolchainConfig.builder().withSysroot("/usr/local/custom-sysroot")
            )
        setBuildLanguageOptions("--noincompatible_unambiguous_label_stringification")
        useConfiguration()
        scratch.file(
            "x/BUILD",
            """
        load("@rules_cc//cc:cc_binary.bzl", "cc_binary")
        load("@rules_cc//cc:cc_library.bzl", "cc_library")
        cc_binary(
            name = "libfoo.so",
            linkshared = 1,
            deps = ["a"],
        )

        cc_library(
            name = "a",
            srcs = ["a.cc"],
            linkstamp = "ls.cc",
        )
        
        """.trimIndent()
        )

        val target: ConfiguredTarget = getConfiguredTarget("//x:libfoo.so")
        val executable: Artifact = getExecutable(target)
        val generatingAction: SpawnAction = getGeneratingAction(executable) as SpawnAction
        val compiledLinkstamp: Artifact =
            ActionsTestUtil.getFirstArtifactEndingWith(generatingAction.getInputs(), "ls.o")
        com.google.common.truth.Subject.contains(compiledLinkstamp)

        val linkstampCompileAction: CppCompileAction =
            getGeneratingAction(compiledLinkstamp) as CppCompileAction
        val ccToolchainProvider: CcToolchainProvider =
            CcToolchainProvider.getFromTarget(
                getConfiguredTarget(
                    ruleClassProvider.getToolsRepository() + "//tools/cpp:current_cc_toolchain"
                )
            )

        val arguments: MutableList<String?>? = linkstampCompileAction.getArguments()
        assertThatArgumentsAreValid(
            arguments,
            ccToolchainProvider.getToolchainIdentifier(),
            target.getLabel().getCanonicalForm(),
            executable.getFilename()
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testLinkstampRespectsPicnessFromConfiguration() {
        getAnalysisMock()
            .ccSupport()
            .setupCcToolchainConfig(
                mockToolsConfig,
                CcToolchainConfig.builder()
                    .withFeatures(CppRuleClasses.SUPPORTS_PIC, CppRuleClasses.PIC)
            )

        useConfiguration("--force_pic")
        scratch.file(
            "x/BUILD",
            """
        load("@rules_cc//cc:cc_binary.bzl", "cc_binary")
        load("@rules_cc//cc:cc_library.bzl", "cc_library")
        cc_binary(
            name = "foo",
            deps = ["a"],
        )

        cc_library(
            name = "a",
            srcs = ["a.cc"],
            linkstamp = "ls.cc",
        )
        
        """.trimIndent()
        )
        val target: ConfiguredTarget = getConfiguredTarget("//x:foo")
        val executable: Artifact = getExecutable(target)
        val generatingAction: SpawnAction = getGeneratingAction(executable) as SpawnAction
        val compiledLinkstamp: Artifact =
            ActionsTestUtil.getFirstArtifactEndingWith(generatingAction.getInputs(), "ls.o")
        com.google.common.truth.Subject.contains(compiledLinkstamp)

        val linkstampCompileAction: CppCompileAction =
            getGeneratingAction(compiledLinkstamp) as CppCompileAction
        com.google.common.truth.Subject.contains("-fPIC")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testLinkstampRespectsFdoFromConfiguration() {
        useConfiguration("--fdo_instrument=foo")
        scratch.file(
            "x/BUILD",
            """
        load("@rules_cc//cc:cc_binary.bzl", "cc_binary")
        load("@rules_cc//cc:cc_library.bzl", "cc_library")
        cc_binary(
            name = "foo",
            deps = ["a"],
        )

        cc_library(
            name = "a",
            srcs = ["a.cc"],
            linkstamp = "ls.cc",
        )
        
        """.trimIndent()
        )
        val target: ConfiguredTarget = getConfiguredTarget("//x:foo")
        val executable: Artifact = getExecutable(target)
        val generatingAction: SpawnAction = getGeneratingAction(executable) as SpawnAction
        val compiledLinkstamp: Artifact =
            ActionsTestUtil.getFirstArtifactEndingWith(generatingAction.getInputs(), "ls.o")
        com.google.common.truth.Subject.contains(compiledLinkstamp)

        val linkstampCompileAction: CppCompileAction =
            getGeneratingAction(compiledLinkstamp) as CppCompileAction
        com.google.common.truth.Subject.contains("-D" + CppConfiguration.FDO_STAMP_MACRO + "=\"FDO\"")
    }

    /**
     * Regression test for b/73447914: Linkstamps were not re-built when only volatile data changed,
     * i.e. when we modified cc_binary source, linkstamp was not recompiled so we got old timestamps.
     * The proper behavior is to recompile linkstamp whenever any input to cc_binary action changes.
     * And the current implementation solves this by adding all linking inputs as
     * inputsForInvalidation to linkstamp compile action.
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testLinkstampCompileDependsOnAllCcBinaryLinkingInputs() {
        scratch.file(
            "x/BUILD",
            """
        load("@rules_cc//cc:cc_binary.bzl", "cc_binary")
        load("@rules_cc//cc:cc_library.bzl", "cc_library")
        cc_binary(
            name = "foo",
            srcs = ["main.cc"],
            deps = ["bar"],
        )

        cc_library(
            name = "bar",
            srcs = ["bar.cc"],
            linkstamp = "ls.cc",
        )
        
        """.trimIndent()
        )
        useConfiguration()

        val target: ConfiguredTarget = getConfiguredTarget("//x:foo")
        val executable: Artifact = getExecutable(target)
        val toolchain: CcToolchainProvider? = CppHelper.getToolchain(getRuleContext(target))
        val cppConfiguration: CppConfiguration? = getRuleContext(target).getFragment(CppConfiguration::class.java)
        val featureConfiguration: FeatureConfiguration? =
            CcCommon.configureFeaturesOrThrowEvalException( /* requestedFeatures= */
                com.google.common.collect.ImmutableSet.of<E?>(),  /* unsupportedFeatures= */
                com.google.common.collect.ImmutableSet.of<E?>(),
                Language.CPP,
                toolchain,
                cppConfiguration
            )
        val usePic: Boolean = CppHelper.usePicForBinaries(cppConfiguration, featureConfiguration)

        val generatingAction: SpawnAction = getGeneratingAction(executable) as SpawnAction

        val compiledLinkstamp: Artifact =
            ActionsTestUtil.getFirstArtifactEndingWith(generatingAction.getInputs(), "ls.o")
        val linkstampCompileAction: CppCompileAction =
            getGeneratingAction(compiledLinkstamp) as CppCompileAction

        val mainObject: Artifact? =
            ActionsTestUtil.getFirstArtifactEndingWith(
                generatingAction.getInputs(), if (usePic) "main.pic.o" else "main.o"
            )
        val bar: Artifact? =
            generatingAction.getInputs().toList().stream()
                .filter({ a -> a.getExecPath().getBaseName().contains("bar") })
                .findFirst()
                .get()
        val linkstampInputs: com.google.common.collect.ImmutableList<Artifact?>? =
            linkstampCompileAction.getInputs().toList()
        Truth.assertThat(linkstampInputs).containsAtLeast(mainObject, bar)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testLinkstampGetsCoptsFromOptions() {
        useConfiguration("--copt=-foo_copt_from_option")
        scratch.file(
            "x/BUILD",
            """
        load("@rules_cc//cc:cc_binary.bzl", "cc_binary")
        load("@rules_cc//cc:cc_library.bzl", "cc_library")
        cc_binary(
            name = "foo",
            copts = ["-bar_copt_from_attribute"],
            deps = ["a"],
        )

        cc_library(
            name = "a",
            srcs = ["a.cc"],
            linkstamp = "ls.cc",
        )
        
        """.trimIndent()
        )
        val target: ConfiguredTarget = getConfiguredTarget("//x:foo")
        val executable: Artifact = getExecutable(target)
        val generatingAction: SpawnAction = getGeneratingAction(executable) as SpawnAction
        val compiledLinkstamp: Artifact =
            ActionsTestUtil.getFirstArtifactEndingWith(generatingAction.getInputs(), "ls.o")
        com.google.common.truth.Subject.contains(compiledLinkstamp)

        val linkstampCompileAction: CppCompileAction =
            getGeneratingAction(compiledLinkstamp) as CppCompileAction
        com.google.common.truth.Subject.contains("-foo_copt_from_option")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testLinkstampDoesNotGetCoptsFromAttribute() {
        useConfiguration("--copt=-foo_copt_from_option")
        scratch.file(
            "x/BUILD",
            """
        load("@rules_cc//cc:cc_binary.bzl", "cc_binary")
        load("@rules_cc//cc:cc_library.bzl", "cc_library")
        cc_binary(
            name = "foo",
            copts = ["-bar_copt_from_attribute"],
            deps = ["a"],
        )

        cc_library(
            name = "a",
            srcs = ["a.cc"],
            copts = ["-baz_copt_from_attribute"],
            linkstamp = "ls.cc",
        )
        
        """.trimIndent()
        )
        val target: ConfiguredTarget = getConfiguredTarget("//x:foo")
        val executable: Artifact = getExecutable(target)
        val generatingAction: SpawnAction = getGeneratingAction(executable) as SpawnAction
        val compiledLinkstamp: Artifact =
            ActionsTestUtil.getFirstArtifactEndingWith(generatingAction.getInputs(), "ls.o")
        com.google.common.truth.Subject.contains(compiledLinkstamp)

        val linkstampCompileAction: CppCompileAction =
            getGeneratingAction(compiledLinkstamp) as CppCompileAction
        assertThat(linkstampCompileAction.getArguments()).doesNotContain("-bar_copt_from_attribute")
        assertThat(linkstampCompileAction.getArguments()).doesNotContain("-baz_copt_from_attribute")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testLinkstampCompileIsUsingMemProf() {
        useConfiguration(
            "--compilation_mode=opt", "--features=memprof_optimize", "--fdo_profile=//x:prof"
        )
        scratch.file(
            "x/BUILD",
            """
        load("@rules_cc//cc:cc_binary.bzl", "cc_binary")
        load("@rules_cc//cc:cc_library.bzl", "cc_library")
        load("@rules_cc//cc/toolchains:fdo_profile.bzl", "fdo_profile")
        cc_binary(
            name = "foo",
            deps = ["a"],
        )

        cc_library(
            name = "a",
            srcs = ["a.cc"],
            linkstamp = "ls.cc",
        )

        fdo_profile(
          name = "prof",
          profile = "out.afdo",
          memprof_profile = "memprof.zip",
        )
        
        """.trimIndent()
        )
        val target: ConfiguredTarget = getConfiguredTarget("//x:foo")
        val executable: Artifact = getExecutable(target)
        val generatingAction: SpawnAction = getGeneratingAction(executable) as SpawnAction
        val compiledLinkstamp: Artifact =
            ActionsTestUtil.getFirstArtifactEndingWith(generatingAction.getInputs(), "ls.o")
        com.google.common.truth.Subject.contains(compiledLinkstamp)

        val linkstampCompileAction: CppCompileAction =
            getGeneratingAction(compiledLinkstamp) as CppCompileAction
        val cmdline: CompileCommandLine = linkstampCompileAction.getCompileCommandLine()
        val variables: CcToolchainVariables = cmdline.getVariables()
        assertThat(variables.isAvailable("is_using_memprof")).isTrue()
    }
}
