// Copyright 2014 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.actions.ActionAnalysisMetadata

/** Tests for toolchain features.  */
@RunWith(JUnit4::class)
class CcToolchainTest : BuildViewTestCase() {
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testFilesToBuild() {
        scratch.file(
            "a/BUILD",
            "load('@rules_cc//cc/toolchains:cc_toolchain_alias.bzl',"
                    + " 'cc_toolchain_alias')",
            "cc_toolchain_alias(name = 'b')"
        )
        val b: ConfiguredTarget = getConfiguredTarget("//a:b")
        assertThat(ActionsTestUtil.baseArtifactNames(getFilesToBuild(b))).isNotNull()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testInterfaceSharedObjects() {
        scratch.file(
            "a/BUILD",
            "load('@rules_cc//cc/toolchains:cc_toolchain_alias.bzl',"
                    + " 'cc_toolchain_alias')",
            "cc_toolchain_alias(name = 'b')"
        )
        getAnalysisMock()
            .ccSupport()
            .setupCcToolchainConfig(
                mockToolsConfig,
                CcToolchainConfig.builder()
                    .withFeatures(CppRuleClasses.SUPPORTS_INTERFACE_SHARED_LIBRARIES)
            )
        useConfiguration("--features=-supports_interface_shared_libraries")
        invalidatePackages()

        var target: ConfiguredTarget = getConfiguredTarget("//a:b")
        Truth.assertThat(
            useInterfaceSharedLibraries(
                getConfiguration(target).getFragment(CppConfiguration::class.java),
                FeatureConfiguration.EMPTY
            )
        )
            .isFalse()

        useConfiguration()
        invalidatePackages()
        target = getConfiguredTarget("//a:b")
        Truth.assertThat(
            useInterfaceSharedLibraries(
                getConfiguration(target).getFragment(CppConfiguration::class.java),
                FeatureConfiguration.EMPTY
            )
        )
            .isFalse()

        useConfiguration("--nointerface_shared_objects")
        invalidatePackages()
        target = getConfiguredTarget("//a:b")
        Truth.assertThat(
            useInterfaceSharedLibraries(
                getConfiguration(target).getFragment(CppConfiguration::class.java),
                FeatureConfiguration.EMPTY
            )
        )
            .isFalse()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testFission() {
        scratch.file(
            "a/BUILD",
            "load('@rules_cc//cc:cc_library.bzl', 'cc_library')",
            "cc_library(name = 'a', srcs = ['a.cc'])"
        )

        // Default configuration: disabled.
        getAnalysisMock()
            .ccSupport()
            .setupCcToolchainConfig(
                mockToolsConfig,
                CcToolchainConfig.builder().withFeatures(CppRuleClasses.PER_OBJECT_DEBUG_INFO)
            )
        useConfiguration()

        Truth.assertThat(this.cppCompileOutputs).doesNotContain("yolo")

        // Mode-specific settings.
        useConfiguration("-c", "dbg", "--fission=dbg")
        Truth.assertThat(this.cppCompileOutputs).contains("a.dwo")

        useConfiguration("-c", "dbg", "--fission=opt")
        Truth.assertThat(this.cppCompileOutputs).doesNotContain("a.dwo")

        useConfiguration("-c", "dbg", "--fission=opt,dbg")
        Truth.assertThat(this.cppCompileOutputs).contains("a.dwo")

        useConfiguration("-c", "fastbuild", "--fission=opt,dbg")
        Truth.assertThat(this.cppCompileOutputs).doesNotContain("a.dwo")

        useConfiguration("-c", "fastbuild", "--fission=opt,dbg")
        Truth.assertThat(this.cppCompileOutputs).doesNotContain("a.dwo")

        // Universally enabled
        useConfiguration("-c", "dbg", "--fission=yes")
        Truth.assertThat(this.cppCompileOutputs).contains("a.dwo")

        useConfiguration("-c", "opt", "--fission=yes")
        Truth.assertThat(this.cppCompileOutputs).contains("a.dwo")

        useConfiguration("-c", "fastbuild", "--fission=yes")
        Truth.assertThat(this.cppCompileOutputs).contains("a.dwo")

        // Universally disabled
        useConfiguration("-c", "dbg", "--fission=no")
        Truth.assertThat(this.cppCompileOutputs).doesNotContain("a.dwo")

        useConfiguration("-c", "opt", "--fission=no")
        Truth.assertThat(this.cppCompileOutputs).doesNotContain("a.dwo")

        useConfiguration("-c", "fastbuild", "--fission=no")
        Truth.assertThat(this.cppCompileOutputs).doesNotContain("a.dwo")
    }

    @get:Throws(java.lang.Exception::class)
    private val cppCompileOutputs: com.google.common.collect.ImmutableList<String?>
        get() {
            val target: RuleConfiguredTarget = getConfiguredTarget("//a:a") as RuleConfiguredTarget
            return target.getActions().stream()
                .filter({ a -> a.getMnemonic().equals("CppCompile") })
                .findFirst()
                .get()
                .getOutputs()
                .stream()
                .map(Artifact::getFilename)
                .collect(com.google.common.collect.ImmutableList.toImmutableList<E?>())
        }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testPic() {
        scratch.file(
            "a/BUILD",
            "load('@rules_cc//cc/toolchains:cc_toolchain_alias.bzl',"
                    + " 'cc_toolchain_alias')",
            "cc_toolchain_alias(name = 'b')"
        )

        Truth.assertThat(usePicForBinariesWithConfiguration("--platforms=" + TestConstants.PLATFORM_LABEL))
            .isFalse()
        Truth.assertThat(
            usePicForBinariesWithConfiguration(
                "--platforms=" + TestConstants.PLATFORM_LABEL, "-c", "opt"
            )
        )
            .isFalse()

        getAnalysisMock()
            .ccSupport()
            .setupCcToolchainConfig(
                mockToolsConfig, CcToolchainConfig.builder().withFeatures(CppRuleClasses.SUPPORTS_PIC)
            )
        invalidatePackages()

        Truth.assertThat(usePicForBinariesWithConfiguration("--platforms=" + TestConstants.PLATFORM_LABEL))
            .isTrue()
        Truth.assertThat(
            usePicForBinariesWithConfiguration(
                "--platforms=" + TestConstants.PLATFORM_LABEL, "-c", "opt"
            )
        )
            .isFalse()
    }

    @Throws(java.lang.Exception::class)
    private fun usePicForBinariesWithConfiguration(vararg configuration: String?): Boolean {
        useConfiguration(*configuration)
        val target: ConfiguredTarget = getConfiguredTarget("//a:b")
        val toolchainProvider: CcToolchainProvider? = CcToolchainProvider.getFromTarget(target)
        val cppConfiguration: CppConfiguration? = getRuleContext(target).getFragment(CppConfiguration::class.java)
        val featureConfiguration: FeatureConfiguration? =
            CcCommon.configureFeaturesOrThrowEvalException( /* requestedFeatures= */
                com.google.common.collect.ImmutableSet.of<E?>(),  /* unsupportedFeatures= */
                com.google.common.collect.ImmutableSet.of<E?>(),
                Language.CPP,
                toolchainProvider,
                cppConfiguration
            )
        return CppHelper.usePicForBinaries(cppConfiguration, featureConfiguration)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testBadDynamicRuntimeLib() {
        scratch.file("a/cc_toolchain_config.bzl", MockCcSupport.EMPTY_CC_TOOLCHAIN)
        scratch.file(
            "a/BUILD",
            """
        load("@rules_cc//cc/toolchains:cc_toolchain.bzl", "cc_toolchain")
        load(":cc_toolchain_config.bzl", "cc_toolchain_config")

        filegroup(
            name = "dynamic",
            srcs = [
                "not-an-so",
                "so.so",
            ],
        )

        filegroup(
            name = "static",
            srcs = [
                "a.a",
                "not-an-a",
            ],
        )

        cc_toolchain(
            name = "a",
            all_files = "all-a",
            ar_files = "ar-a",
            as_files = "as-a",
            compiler_files = "compile-a",
            coverage_files = "gcov-a",
            dwp_files = "dwp-a",
            dynamic_runtime_lib = ":dynamic",
            linker_files = "link-a",
            module_map = "map",
            objcopy_files = "objcopy-a",
            static_runtime_lib = ":static",
            strip_files = "strip-a",
            toolchain_config = ":toolchain_config",
        )

        cc_toolchain_config(name = "toolchain_config")
        
        """.trimIndent()
        )

        getAnalysisMock()
            .ccSupport()
            .setupCcToolchainConfig(
                mockToolsConfig,
                CcToolchainConfig.builder().withFeatures(CppRuleClasses.STATIC_LINK_CPP_RUNTIMES)
            )

        useConfiguration()

        getConfiguredTarget("//a:a")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDynamicMode() {
        scratch.file(
            "a/BUILD",
            """
        load("@rules_cc//cc/toolchains:cc_toolchain.bzl", "cc_toolchain")
        load(":cc_toolchain_config.bzl", "cc_toolchain_config")

        filegroup(
            name = "empty",
        )

        filegroup(
            name = "banana",
            srcs = [
                "banana1",
                "banana2",
            ],
        )

        cc_toolchain(
            name = "b",
            all_files = ":banana",
            ar_files = ":empty",
            as_files = ":empty",
            compiler_files = ":empty",
            dwp_files = ":empty",
            dynamic_runtime_lib = ":empty",
            linker_files = ":empty",
            objcopy_files = ":empty",
            static_runtime_lib = ":empty",
            strip_files = ":empty",
            toolchain_config = ":toolchain_config",
            toolchain_identifier = "toolchain-identifier-k8",
        )

        cc_toolchain_config(name = "toolchain_config")
        
        """.trimIndent()
        )
        scratch.file("a/cc_toolchain_config.bzl", MockCcSupport.EMPTY_CC_TOOLCHAIN)

        // Check defaults.
        useConfiguration()
        var target: ConfiguredTarget = getConfiguredTarget("//a:b")
        var cppConfiguration: CppConfiguration =
            getConfiguration(target).getFragment(CppConfiguration::class.java)

        assertThat(cppConfiguration.getDynamicModeFlag()).isEqualTo(DynamicMode.DEFAULT)

        // Test "off"
        useConfiguration("--dynamic_mode=off")
        target = getConfiguredTarget("//a:b")
        cppConfiguration = getConfiguration(target).getFragment(CppConfiguration::class.java)

        assertThat(cppConfiguration.getDynamicModeFlag()).isEqualTo(DynamicMode.OFF)

        // Test "fully"
        useConfiguration("--dynamic_mode=fully")
        target = getConfiguredTarget("//a:b")
        cppConfiguration = getConfiguration(target).getFragment(CppConfiguration::class.java)

        assertThat(cppConfiguration.getDynamicModeFlag()).isEqualTo(DynamicMode.FULLY)

        // Check an invalid value for --dynamic_mode.
        val e: T? =
            org.junit.Assert.assertThrows<T?>(
                InvalidConfigurationException::class.java,
                org.junit.function.ThrowingRunnable { useConfiguration("--dynamic_mode=very") })
        assertThat(e)
            .hasMessageThat()
            .isEqualTo(
                "While parsing option --dynamic_mode=very: Not a valid dynamic mode: 'very' "
                        + "(should be off, default or fully)"
            )
    }

    @Throws(java.lang.Exception::class)
    private fun assertInvalidIncludeDirectoryMessage(entry: String?, messageRegex: String?) {
        scratch.overwriteFile(
            "a/BUILD",
            "load('@rules_cc//cc/toolchains:cc_toolchain_alias.bzl',"
                    + " 'cc_toolchain_alias')",
            "cc_toolchain_alias(name = 'b')"
        )
        getAnalysisMock()
            .ccSupport()
            .setupCcToolchainConfig(
                mockToolsConfig, CcToolchainConfig.builder().withCxxBuiltinIncludeDirectories(entry)
            )

        useConfiguration()
        invalidatePackages()

        val e: java.lang.AssertionError? = org.junit.Assert.assertThrows<java.lang.AssertionError?>(
            java.lang.AssertionError::class.java,
            org.junit.function.ThrowingRunnable { getConfiguredTarget("//a:b") })
        Truth.assertThat(e).hasMessageThat().containsMatch(messageRegex)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testInvalidIncludeDirectory() {
        assertInvalidIncludeDirectoryMessage("%package(//a", "has an unrecognized %prefix%")
        assertInvalidIncludeDirectoryMessage(
            "%package(//a:@@a)%", "invalid package identifier '//a:@@a': contains ':'"
        )
        assertInvalidIncludeDirectoryMessage(
            "%package(//a)%foo", "The path in the package.*is not valid"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testModuleMapAttribute() {
        scratch.file("modules/map/cc_toolchain_config.bzl", MockCcSupport.EMPTY_CC_TOOLCHAIN)
        scratchConfiguredTarget(
            "modules/map",
            "c",
            "load('@rules_cc//cc/toolchains:cc_toolchain.bzl', 'cc_toolchain')",
            "load(':cc_toolchain_config.bzl', 'cc_toolchain_config')",
            "cc_toolchain(",
            "    name = 'c',",
            "    toolchain_identifier = 'toolchain-identifier-k8',",
            "    toolchain_config = ':toolchain_config',",
            "    module_map = 'map',",
            "    ar_files = 'ar-cherry',",
            "    as_files = 'as-cherry',",
            "    compiler_files = 'compile-cherry',",
            "    dwp_files = 'dwp-cherry',",
            "    coverage_files = 'gcov-cherry',",
            "    linker_files = 'link-cherry',",
            "    strip_files = ':every-file',",
            "    objcopy_files = 'objcopy-cherry',",
            "    all_files = ':every-file',",
            "    dynamic_runtime_lib = 'dynamic-runtime-libs-cherry',",
            "    static_runtime_lib = 'static-runtime-libs-cherry')",
            "cc_toolchain_config(name = 'toolchain_config')"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testModuleMapAttributeOptional() {
        scratch.file("modules/map/cc_toolchain_config.bzl", MockCcSupport.EMPTY_CC_TOOLCHAIN)
        scratchConfiguredTarget(
            "modules/map",
            "c",
            "load('@rules_cc//cc/toolchains:cc_toolchain.bzl', 'cc_toolchain')",
            "load(':cc_toolchain_config.bzl', 'cc_toolchain_config')",
            "cc_toolchain(",
            "    name = 'c',",
            "    toolchain_identifier = 'toolchain-identifier-k8',",
            "    toolchain_config = ':toolchain_config',",
            "    ar_files = 'ar-cherry',",
            "    as_files = 'as-cherry',",
            "    compiler_files = 'compile-cherry',",
            "    dwp_files = 'dwp-cherry',",
            "    linker_files = 'link-cherry',",
            "    strip_files = ':every-file',",
            "    objcopy_files = 'objcopy-cherry',",
            "    all_files = ':every-file',",
            "    dynamic_runtime_lib = 'dynamic-runtime-libs-cherry',",
            "    static_runtime_lib = 'static-runtime-libs-cherry')",
            "cc_toolchain_config(name = 'toolchain_config')"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testFailWithMultipleModuleMaps() {
        scratch.file("modules/multiple/cc_toolchain_config.bzl", MockCcSupport.EMPTY_CC_TOOLCHAIN)
        checkError(
            "modules/multiple",
            "c",
            "expected a single artifact",
            "load('@rules_cc//cc/toolchains:cc_toolchain.bzl', 'cc_toolchain')",
            "load(':cc_toolchain_config.bzl', 'cc_toolchain_config')",
            "filegroup(name = 'multiple-maps', srcs = ['a.cppmap', 'b.cppmap'])",
            "cc_toolchain(",
            "    name = 'c',",
            "    toolchain_identifier = 'toolchain-identifier-k8',",
            "    toolchain_config = ':toolchain_config',",
            "    module_map = ':multiple-maps',",
            "    ar_files = 'ar-cherry',",
            "    as_files = 'as-cherry',",
            "    compiler_files = 'compile-cherry',",
            "    dwp_files = 'dwp-cherry',",
            "    coverage_files = 'gcov-cherry',",
            "    linker_files = 'link-cherry',",
            "    strip_files = ':every-file',",
            "    objcopy_files = 'objcopy-cherry',",
            "    all_files = ':every-file',",
            "    dynamic_runtime_lib = 'dynamic-runtime-libs-cherry',",
            "    static_runtime_lib = 'static-runtime-libs-cherry')",
            "cc_toolchain_config(name = 'toolchain_config')"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testToolchainAlias() {
        val reference: ConfiguredTarget =
            scratchConfiguredTarget(
                "a",
                "ref",
                "load('@rules_cc//cc/toolchains:cc_toolchain_alias.bzl',"
                        + " 'cc_toolchain_alias')",
                "cc_toolchain_alias(name='ref')"
            )
        assertThat(CcToolchainProvider.getFromTarget(reference)).isNotNull()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testFdoOptimizeInvalidUseGeneratedArtifact() {
        reporter.removeHandler(failFastHandler)
        scratch.file(
            "a/BUILD",
            """
        load("@rules_cc//cc/toolchains:cc_toolchain_alias.bzl", "cc_toolchain_alias")
        cc_toolchain_alias(name = "b")

        genrule(
            name = "gen_artifact",
            outs = ["profile.profdata"],
            cmd = "touch ${'$'}@",
        )
        
        """.trimIndent()
        )
        useConfiguration("-c", "opt", "--fdo_optimize=//a:gen_artifact")
        assertThat(getConfiguredTarget("//a:b")).isNull()
        assertContainsEvent(
            "--fdo_optimize points to a target that is not an input file or an fdo_profile rule"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testFdoOptimizeUnexpectedExtension() {
        reporter.removeHandler(failFastHandler)
        scratch.file(
            "a/BUILD",
            """
        load("@rules_cc//cc/toolchains:cc_toolchain_alias.bzl", "cc_toolchain_alias")
        cc_toolchain_alias(name = "b")

        exports_files(["profile.unexpected"])
        
        """.trimIndent()
        )
        scratch.file("a/profile.unexpected", "")
        useConfiguration("-c", "opt", "--fdo_optimize=//a:profile.unexpected")
        assertThat(getConfiguredTarget("//a:b")).isNull()
        assertContainsEvent("invalid extension for FDO profile file")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testFdoOptimizeNotInputFile() {
        reporter.removeHandler(failFastHandler)
        scratch.file(
            "a/BUILD",
            """
        load("@rules_cc//cc/toolchains:cc_toolchain_alias.bzl", "cc_toolchain_alias")
        cc_toolchain_alias(name = "b")

        filegroup(
            name = "profile",
            srcs = ["my_profile.afdo"],
        )
        
        """.trimIndent()
        )
        scratch.file("my_profile.afdo", "")
        useConfiguration("-c", "opt", "--fdo_optimize=//a:profile")
        assertThat(getConfiguredTarget("//a:b")).isNull()
        assertContainsEvent(
            "--fdo_optimize points to a target that is not an input file or an fdo_profile rule"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testFdoOptimizeNotCompatibleWithCoverage() {
        reporter.removeHandler(failFastHandler)
        scratch.file(
            "a/BUILD",
            """
        load("@rules_cc//cc/toolchains:cc_toolchain_alias.bzl", "cc_toolchain_alias")
        cc_toolchain_alias(name = "b")

        exports_files(["profile.afdo"])
        
        """.trimIndent()
        )
        scratch.file("a/profile.afdo", "")
        useConfiguration("-c", "opt", "--fdo_optimize=//a:profile.afdo", "--collect_code_coverage")
        assertThat(getConfiguredTarget("//a:b")).isNull()
        assertContainsEvent("coverage mode is not compatible with FDO optimization")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCSFdoRejectRelativePath() {
        reporter.removeHandler(failFastHandler)
        scratch.file(
            "a/BUILD",
            "load('@rules_cc//cc/toolchains:cc_toolchain_alias.bzl',"
                    + " 'cc_toolchain_alias')",
            "cc_toolchain_alias(name = 'b')"
        )
        scratch.file("a/profile.profdata", "")
        scratch.file("a/csprofile.profdata", "")
        val e: java.lang.Exception? =
            org.junit.Assert.assertThrows<java.lang.Exception?>(
                java.lang.Exception::class.java,
                org.junit.function.ThrowingRunnable {
                    useConfiguration(
                        "-c",
                        "opt",
                        "--fdo_optimize=/a/profile.profdata",
                        "--cs_fdo_absolute_path=a/csprofile.profdata"
                    )
                })
        Truth.assertThat(e).hasMessageThat().contains("in --cs_fdo_absolute_path is not an absolute path")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testXFdoOptimizeNotProvider() {
        reporter.removeHandler(failFastHandler)
        scratch.file(
            "a/BUILD",
            """
        load("@rules_cc//cc/toolchains:cc_toolchain_alias.bzl", "cc_toolchain_alias")
        cc_toolchain_alias(name = "b")

        genrule(
            name = "profile.xfdo",
            outs = ["c.txt"],
            cmd = "",
        )
        
        """.trimIndent()
        )
        useConfiguration("-c", "opt", "--xbinary_fdo=//a:profile.xfdo")
        assertThat(getConfiguredTarget("//a:b")).isNull()
        assertContainsEvent("does not have mandatory providers: 'FdoProfileInfo'")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testXFdoOptimizeAcceptAFdoInput() {
        reporter.removeHandler(failFastHandler)
        scratch.file(
            "a/BUILD",
            """
        load("@rules_cc//cc/toolchains:cc_toolchain_alias.bzl", "cc_toolchain_alias")
        load("@rules_cc//cc/toolchains:fdo_profile.bzl", "fdo_profile")
        cc_toolchain_alias(name = "b")

        fdo_profile(
            name = "out.afdo",
            profile = "profile.afdo",
        )
        
        """.trimIndent()
        )
        useConfiguration("-c", "opt", "--xbinary_fdo=//a:out.afdo")
        assertThat(getConfiguredTarget("//a:b")).isNotNull()
        assertNoEvents()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testXFdoOptimizeRejectFdoInput() {
        reporter.removeHandler(failFastHandler)
        scratch.file(
            "a/BUILD",
            """
        load("@rules_cc//cc/toolchains:cc_toolchain_alias.bzl", "cc_toolchain_alias")
        load("@rules_cc//cc/toolchains:fdo_profile.bzl", "fdo_profile")
        cc_toolchain_alias(name = "b")

        fdo_profile(
            name = "out.fdo",
            profile = "profile.profdata",
        )
        
        """.trimIndent()
        )
        useConfiguration("-c", "opt", "--xbinary_fdo=//a:out.fdo")
        assertThat(getConfiguredTarget("//a:b")).isNull()
        assertContainsEvent("--xbinary_fdo only accepts")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testZipperInclusionDependsOnFdoOptimization_notSet() {
        setUpFdoZipper()

        useConfiguration()
        Truth.assertThat(getPrerequisites(getConfiguredTarget("//a:b"), ":zipper")).isEmpty()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testZipperInclusionDependsOnFdoOptimization_viaFdoOptimize_packageLabel() {
        setUpFdoZipper()

        useConfiguration("-c", "opt", "--fdo_optimize=//fdo:fdo")
        Truth.assertThat(getPrerequisites(getConfiguredTarget("//a:b"), ":zipper")).isNotEmpty()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testZipperInclusionDependsOnFdoOptimization_viaFdoOptimize_packageFile() {
        setUpFdoZipper()

        useConfiguration("-c", "opt", "--fdo_optimize=//fdo:my_profile.afdo")
        Truth.assertThat(getPrerequisites(getConfiguredTarget("//a:b"), ":zipper")).isNotEmpty()
    }

    // Regression test for #29002.
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testZipperInclusionDependsOnFdoOptimization_viaFdoOptimize_repoLabel() {
        if (!analysisMock.isThisBazel) {
            // Separate repos only work in bazel.
            return
        }

        // Set up a repo with the profile data.
        scratch.appendFile(
            "MODULE.bazel",
            """
        bazel_dep(name = "fdo")
        local_path_override(module_name = "fdo", path = "/fdo")
        
        """.trimIndent()
        )

        scratch.file("/fdo/MODULE.bazel", "module(name = 'fdo')")
        setUpFdoZipper("a", "/fdo")
        invalidatePackages()

        useConfiguration("-c", "opt", "--fdo_optimize=@@fdo+//:fdo")
        Truth.assertThat(getPrerequisites(getConfiguredTarget("//a:b"), ":zipper")).isNotEmpty()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testZipperInclusionDependsOnFdoOptimization_viaFdoProfile() {
        setUpFdoZipper()

        useConfiguration("-c", "opt", "--fdo_profile=//fdo:fdo")
        Truth.assertThat(getPrerequisites(getConfiguredTarget("//a:b"), ":zipper")).isNotEmpty()
    }

    @Throws(IOException::class)
    private fun setUpFdoZipper() {
        setUpFdoZipper("a", "fdo")
    }

    @Throws(IOException::class)
    private fun setUpFdoZipper(pkgDir: String?, fdoDir: String?) {
        scratch.file(
            pkgDir + "/BUILD",
            """
        load("@rules_cc//cc/toolchains:cc_toolchain.bzl", "cc_toolchain")
        load(":cc_toolchain_config.bzl", "cc_toolchain_config")

        filegroup(
            name = "empty",
        )

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
            toolchain_config = ":toolchain_config",
            toolchain_identifier = "toolchain-identifier-k8",
        )

        cc_toolchain_config(name = "toolchain_config")
        
        """.trimIndent()
        )
        scratch.file(pkgDir + "/cc_toolchain_config.bzl", MockCcSupport.EMPTY_CC_TOOLCHAIN)

        scratch.file(fdoDir + "/my_profile.afdo", "")
        scratch.file(
            fdoDir + "/BUILD",
            """
        load("@rules_cc//cc/toolchains:fdo_profile.bzl", "fdo_profile")
        exports_files(["my_profile.afdo"])

        fdo_profile(
            name = "fdo",
            profile = ":my_profile.profdata",
        )
        
        """.trimIndent()
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
    fun testToolPathsInToolchainFromStarlarkRule() {
        loadCcToolchainConfigLib()
        writeStarlarkRule()

        useConfiguration("--platforms=" + TestConstants.PLATFORM_LABEL)

        val target: ConfiguredTarget = getConfiguredTarget("//a:b")
        val toolchainProvider: CcToolchainProvider = CcToolchainProvider.getFromTarget(target)
        assertThat(
            CcToolchainProvider.getToolPathString(
                toolchainProvider.getToolPaths(),
                Tool.AR,
                toolchainProvider.getCcToolchainLabel(),
                toolchainProvider.getToolchainIdentifier()
            )
        )
            .isEqualTo("/absolute/path")
        assertThat(
            CcToolchainProvider.getToolPathString(
                toolchainProvider.getToolPaths(),
                Tool.CPP,
                toolchainProvider.getCcToolchainLabel(),
                toolchainProvider.getToolchainIdentifier()
            )
        )
            .isEqualTo("a/relative/path")
    }

    @Throws(IOException::class)
    private fun writeStarlarkRule() {
        scratch.file(
            "a/BUILD",
            """
        load("@rules_cc//cc/toolchains:cc_toolchain.bzl", "cc_toolchain")
        load(":crosstool_rule.bzl", "cc_toolchain_config_rule")

        cc_toolchain_config_rule(name = "toolchain_config")

        filegroup(
            name = "empty",
        )

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
            toolchain_config = ":toolchain_config",
        )
        
        """.trimIndent()
        )

        scratch.file(
            "a/crosstool_rule.bzl",
            """
        load("@rules_cc//cc/toolchains:cc_toolchain_config_info.bzl", "CcToolchainConfigInfo")
        load('@rules_cc//cc/common:cc_common.bzl', 'cc_common')
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

        def _impl(ctx):
            return cc_common.create_cc_toolchain_config_info(
                ctx = ctx,
                features = [
                    feature(name = "simple_feature"),
                    feature(name = "no_legacy_features"),
                ],
                action_configs = [
                    action_config(action_name = "simple_action", enabled = True),
                ],
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
                abi_version = "banana",
                tool_paths = [
                    tool_path(name = "ar", path = "/absolute/path"),
                    tool_path(name = "cpp", path = "relative/path"),
                    tool_path(name = "gcc", path = "/some/path"),
                    tool_path(name = "gcov", path = "/some/path"),
                    tool_path(name = "gcovtool", path = "/some/path"),
                    tool_path(name = "ld", path = "/some/path"),
                    tool_path(name = "nm", path = "/some/path"),
                    tool_path(name = "objcopy", path = "/some/path"),
                    tool_path(name = "objdump", path = "/some/path"),
                    tool_path(name = "strip", path = "/some/path"),
                    tool_path(name = "dwp", path = "/some/path"),
                    tool_path(name = "llvm_profdata", path = "/some/path"),
                ],
                cc_target_os = "os",
                builtin_sysroot = "sysroot",
            )

        cc_toolchain_config_rule = rule(
            implementation = _impl,
            attrs = {},
            provides = [CcToolchainConfigInfo],
            fragments = ["cpp"],
        )
        
        """.trimIndent()
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSysroot_fromCrosstool_unset() {
        scratch.file(
            "a/BUILD",
            "load('@rules_cc//cc/toolchains:cc_toolchain_alias.bzl',"
                    + " 'cc_toolchain_alias')",
            "cc_toolchain_alias(name = 'b')"
        )
        scratch.file("libc1/BUILD", "filegroup(name = 'everything', srcs = ['header1.h'])")
        scratch.file("libc1/header1.h", "#define FOO 1")
        val target: ConfiguredTarget = getConfiguredTarget("//a:b")
        val toolchainProvider: CcToolchainProvider = CcToolchainProvider.getFromTarget(target)

        assertThat(toolchainProvider.getSysroot()).isEqualTo("/usr/grte/v1")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun correctToolFilesUsed() {
        scratch.file(
            "a/BUILD",
            """
        load("@rules_cc//cc:cc_library.bzl", "cc_library")
        load("@rules_cc//cc/toolchains:cc_toolchain_alias.bzl", "cc_toolchain_alias")
        cc_toolchain_alias(name = "a")

        cc_library(
            name = "l",
            srcs = ["l.c"],
        )

        cc_library(
            name = "asm",
            srcs = ["a.s"],
        )

        cc_library(
            name = "preprocessed-asm",
            srcs = ["a.S"],
        )
        
        """.trimIndent()
        )
        getAnalysisMock()
            .ccSupport()
            .setupCcToolchainConfig(
                mockToolsConfig,
                CcToolchainConfig.builder().withFeatures(CppRuleClasses.SUPPORTS_DYNAMIC_LINKER)
            )
        useConfiguration("--incompatible_use_specific_tool_files")
        val target: ConfiguredTarget = getConfiguredTarget("//a:a")
        val toolchainProvider: CcToolchainProvider = CcToolchainProvider.getFromTarget(target)
        val libTarget: RuleConfiguredTarget = getConfiguredTarget("//a:l") as RuleConfiguredTarget
        val staticLib: Artifact =
            getOutputGroup(libTarget, "archive").toList().stream()
                .collect(com.google.common.collect.MoreCollectors.onlyElement<T?>())
        val staticAction: ActionAnalysisMetadata = getGeneratingAction(staticLib)
        assertThat(staticAction.getInputs().toList())
            .containsAtLeastElementsIn(toolchainProvider.getArFiles().toList())
        val dynamicLib: Artifact =
            getOutputGroup(libTarget, "dynamic_library").toList().stream()
                .collect(com.google.common.collect.MoreCollectors.onlyElement<T?>())
        val dynamicAction: ActionAnalysisMetadata = getGeneratingAction(dynamicLib)
        assertThat(dynamicAction.getInputs().toList())
            .containsAtLeastElementsIn(toolchainProvider.getLinkerFiles().toList())
        val cCompileAction: ActionAnalysisMetadata =
            libTarget.getActions().stream()
                .filter({ a -> a.getMnemonic().equals("CppCompile") })
                .collect(com.google.common.collect.MoreCollectors.onlyElement<T?>())
        assertThat(cCompileAction.getInputs().toList())
            .containsAtLeastElementsIn(toolchainProvider.getCompilerFiles().toList())
        val asmAction: ActionAnalysisMetadata =
            (getConfiguredTarget("//a:asm") as RuleConfiguredTarget)
                .getActions().stream()
                .filter({ a -> a.getMnemonic().equals("CppCompile") })
                .collect(com.google.common.collect.MoreCollectors.onlyElement<T?>())
        assertThat(asmAction.getInputs().toList())
            .containsAtLeastElementsIn(toolchainProvider.getAsFiles().toList())
        val preprocessedAsmAction: ActionAnalysisMetadata =
            (getConfiguredTarget("//a:preprocessed-asm") as RuleConfiguredTarget)
                .getActions().stream()
                .filter({ a -> a.getMnemonic().equals("CppCompile") })
                .collect(com.google.common.collect.MoreCollectors.onlyElement<T?>())
        assertThat(preprocessedAsmAction.getInputs().toList())
            .containsAtLeastElementsIn(toolchainProvider.getCompilerFiles().toList())
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCcToolchainLoadedThroughMacro() {
        setupTestCcToolchainLoadedThroughMacro( /* loadMacro= */true)
        assertThat(getConfiguredTarget("//a:a")).isNotNull()
        assertNoEvents()
    }

    @Throws(java.lang.Exception::class)
    private fun setupTestCcToolchainLoadedThroughMacro(loadMacro: Boolean) {
        scratch.file("a/cc_toolchain_config.bzl", MockCcSupport.EMPTY_CC_TOOLCHAIN)
        scratch.file(
            "a/BUILD",
            "load(':cc_toolchain_config.bzl', 'cc_toolchain_config')",
            getAnalysisMock().ccSupport().getMacroLoadStatement(loadMacro, "cc_toolchain"),
            getToolchainRule("a")
        )
    }

    companion object {
        /**
         * Returns true if interface shared objects should be used in the build implied by the given
         * cppConfiguration and toolchain.
         */
        fun useInterfaceSharedLibraries(
            cppConfiguration: CppConfiguration, featureConfiguration: FeatureConfiguration?
        ): Boolean {
            return CcToolchainProvider.supportsInterfaceSharedLibraries(featureConfiguration)
                    && cppConfiguration.getUseInterfaceSharedLibraries()
        }

        private fun getToolchainRule(targetName: String?): String {
            return com.google.common.base.Joiner.on("\n")
                .join(
                    "cc_toolchain(",
                    "    name = '" + targetName + "',",
                    "    toolchain_identifier = 'toolchain-identifier-k8',",
                    "    toolchain_config = ':toolchain_config',",
                    "    all_files = ':banana',",
                    "    ar_files = ':empty',",
                    "    as_files = ':empty',",
                    "    compiler_files = ':empty',",
                    "    dwp_files = ':empty',",
                    "    linker_files = ':empty',",
                    "    strip_files = ':empty',",
                    "    objcopy_files = ':empty',",
                    "    dynamic_runtime_lib = ':empty',",
                    "    static_runtime_lib = ':empty')",
                    "filegroup(",
                    "   name='empty')",
                    "filegroup(",
                    "    name = 'banana',",
                    "    srcs = ['banana1', 'banana2'])",
                    "cc_toolchain_config(name='toolchain_config')"
                )
        }
    }
}
