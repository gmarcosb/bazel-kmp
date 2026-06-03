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
// Copyright 2006 Google Inc. All rights reserved.
package com.google.devtools.build.lib.rules.cpp

import com.google.devtools.build.lib.rules.cpp.SolibSymlinkAction.MAX_FILENAME_LENGTH

/** "White-box" unit test of cc_library rule.  */
@RunWith(JUnit4::class)
class CcLibraryConfiguredTargetTest : BuildViewTestCase() {
    override fun createRuleClassProvider(): ConfiguredRuleClassProvider {
        val builder: ConfiguredRuleClassProvider.Builder = Builder()
        TestRuleClassProvider.addStandardRules(builder)
        builder.addConfigurationFragment(DummyTestFragment::class.java)
        return builder.addRuleDefinition(MakeVariableTesterRule()).build()
    }

    @Before
    @Throws(java.lang.Exception::class)
    fun createFiles() {
        scratch.file(
            "hello/BUILD",
            """
        load("@rules_cc//cc:cc_binary.bzl", "cc_binary")
        load("@rules_cc//cc:cc_library.bzl", "cc_library")
        cc_library(
          name = 'hello',
          srcs = ['hello.cc'],
        )
        cc_library(
          name = 'hello_static',
          srcs = ['hello.cc'],
          linkstatic = 1,
        )
        cc_library(
          name = 'hello_alwayslink',
          srcs = ['hello.cc'],
          alwayslink = 1,
        )
        cc_binary(
          name = 'hello_bin',
          srcs = ['hello_main.cc'],
        )
        
        """.trimIndent()
        )
        scratch.file(
            "hello/hello.cc",
            "#include <stdio.h>",
            "int hello_world() { printf(\"Hello, world!\\n\"); }"
        )
        scratch.file(
            "hello/hello_main.cc",
            "#include <stdio.h>",
            "int main() { printf(\"Hello, world!\\n\"); }"
        )
    }

    @Throws(java.lang.Exception::class)
    private fun getCppCompileAction(label: String?): CppCompileAction {
        return getCppCompileAction(getConfiguredTarget(label))
    }

    @Throws(java.lang.Exception::class)
    private fun getCppCompileAction(target: ConfiguredTarget?): CppCompileAction {
        val compilationSteps: MutableList<CppCompileAction> =
            actionsTestUtil()
                .findTransitivePrerequisitesOf(
                    ActionsTestUtil.getFirstArtifactEndingWith(getFilesToBuild(target), ".a"),
                    CppCompileAction::class.java
                )
        return compilationSteps.get(0)
    }

    @Throws(java.lang.Exception::class)
    private fun getCppModuleMapData(moduleMap: Artifact): String? {
        val action: AbstractFileWriteAction = getGeneratingAction(moduleMap) as AbstractFileWriteAction
        val output: java.io.ByteArrayOutputStream = java.io.ByteArrayOutputStream()
        val actionContext: ActionExecutionContext? =
            ActionsTestUtil.createContextForFileWriteAction(reporter)
        action.newDeterministicWriter(actionContext).writeTo(output)
        return output.toString("utf-8")
    }

    @Throws(java.lang.Exception::class)
    private fun assertNoCppModuleMapAction(label: String?) {
        val target: ConfiguredTarget = getConfiguredTarget(label)
        assertThat(CcInfo.get(target).getCcCompilationContext().getCppModuleMap()).isNull()
    }

    @Throws(java.lang.Exception::class)
    fun checkWrongExtensionInArtifactNamePattern(
        categoryName: String?, correctExtensions: com.google.common.collect.ImmutableList<String?>?
    ) {
        reporter.removeHandler(failFastHandler)
        AnalysisMock.get()
            .ccSupport()
            .setupCcToolchainConfig(
                mockToolsConfig,
                CcToolchainConfig.builder()
                    .withFeatures(
                        CppRuleClasses.COPY_DYNAMIC_LIBRARIES_TO_BINARY, CppRuleClasses.TARGETS_WINDOWS
                    )
                    .withArtifactNamePatterns(
                        com.google.common.collect.ImmutableList.of<E?>(
                            categoryName,
                            "",
                            ".wrong_ext"
                        )
                    )
            )
        useConfiguration()
        getConfiguredTarget(
            ruleClassProvider.getToolsRepository() + "//tools/cpp:current_cc_toolchain"
        )
        assertContainsEvent(
            String.format(
                ("Unrecognized file extension '.wrong_ext', allowed "
                        + "extensions are %s, please check artifact_name_pattern configuration for "
                        + "%s in your rule."),
                com.google.devtools.build.lib.util.StringUtil.joinEnglishListSingleQuoted(correctExtensions),
                categoryName
            )
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDefinesAndMakeVariables() {
        val l: ConfiguredTarget =
            scratchConfiguredTarget(
                "a",
                "l",
                "load('@rules_cc//cc:cc_library.bzl', 'cc_library')",
                "cc_library(name='l', srcs=['l.cc'], defines=['V=$(FOO)'], toolchains=[':v'])",
                "make_variable_tester(name='v', variables={'FOO': 'BAR'})"
            )
        com.google.common.truth.Subject.contains("V=BAR")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testLocalDefinesAndMakeVariables() {
        val l: ConfiguredTarget =
            scratchConfiguredTarget(
                "a",
                "l",
                "load('@rules_cc//cc:cc_library.bzl', 'cc_library')",
                "cc_library(name='l', srcs=['l.cc'], local_defines=['V=$(FOO)'], toolchains=[':v'])",
                "make_variable_tester(name='v', variables={'FOO': 'BAR'})"
            )
        com.google.common.truth.Subject.contains("V=BAR")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testMisconfiguredCrosstoolRaisesErrorWhenLinking() {
        AnalysisMock.get()
            .ccSupport()
            .setupCcToolchainConfig(
                mockToolsConfig,
                CcToolchainConfig.builder()
                    .withFeatures(CppRuleClasses.NO_LEGACY_FEATURES, CppRuleClasses.PIC)
                    .withActionConfigs(CppActionNames.CPP_COMPILE)
            )
        useConfiguration()

        checkError(
            "test",
            "test",
            "Expected action_config for 'c++-link-static-library' to be configured",
            "load('@rules_cc//cc:cc_library.bzl', 'cc_library')",
            "cc_library(name = 'test', srcs = ['test.cc'])"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testMisconfiguredCrosstoolRaisesErrorWhenCompiling() {
        AnalysisMock.get()
            .ccSupport()
            .setupCcToolchainConfig(
                mockToolsConfig,
                CcToolchainConfig.builder()
                    .withFeatures(CppRuleClasses.NO_LEGACY_FEATURES, CppRuleClasses.PIC)
                    .withActionConfigs(CppActionNames.CPP_LINK_STATIC_LIBRARY)
            )
        useConfiguration()

        checkError(
            "test",
            "test",
            "Expected action_config for 'c++-compile' to be configured",
            "load('@rules_cc//cc:cc_library.bzl', 'cc_library')",
            "cc_library(name = 'test', srcs = ['test.cc'])"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testFilesToBuild() {
        AnalysisMock.get()
            .ccSupport()
            .setupCcToolchainConfig(
                mockToolsConfig,
                CcToolchainConfig.builder()
                    .withFeatures(
                        CppRuleClasses.SUPPORTS_DYNAMIC_LINKER,
                        CppRuleClasses.SUPPORTS_INTERFACE_SHARED_LIBRARIES
                    )
            )
        useConfiguration(
            "--platforms=" + TestConstants.PLATFORM_LABEL,
            "--experimental_platform_in_output_dir",
            String.format(
                "--experimental_override_name_platform_in_output_dir=%s=k8",
                TestConstants.PLATFORM_LABEL
            )
        )
        val hello: ConfiguredTarget = getConfiguredTarget("//hello:hello")
        val cpu = "k8" // CPU of the platform specified with --platforms
        val archive: Artifact = getBinArtifact("libhello.a", hello)
        val implSharedObject: Artifact = getBinArtifact("libhello.so", hello)
        val implInterfaceSharedObject: Artifact = getBinArtifact("libhello.ifso", hello)
        val implSharedObjectLink: Artifact =
            getSharedArtifact("_solib_" + cpu + "/libhello_Slibhello.so", hello)
        val implInterfaceSharedObjectLink: Artifact =
            getSharedArtifact("_solib_" + cpu + "/libhello_Slibhello.ifso", hello)
        assertThat(getFilesToBuild(hello).toList())
            .containsExactly(archive, implSharedObject, implInterfaceSharedObject)
        assertThat(
            LibraryToLink.getDynamicLibrariesForLinking(
                CcInfo.get(hello).getTransitiveCcNativeLibrariesForTests()
            )
        )
            .containsExactly(implInterfaceSharedObjectLink)
        assertThat(
            CcInfo.get(hello)
                .getCcLinkingContext()
                .getDynamicLibrariesForRuntime( /* linkingStatically= */false)
        )
            .containsExactly(implSharedObjectLink)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testFilesToBuildWithoutDSO() {
        // This is like the preceding test, but with a toolchain that can't build '.so' files
        useConfiguration(
            "--platforms=" + TestConstants.PLATFORM_LABEL,
            "--host_platform=" + TestConstants.PLATFORM_LABEL
        )
        val hello: ConfiguredTarget = getConfiguredTarget("//hello:hello")
        val archive: Artifact = getBinArtifact("libhello.a", hello)
        assertThat(getFilesToBuild(hello).toList()).containsExactly(archive)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testFilesToBuildWithInterfaceSharedObjects() {
        AnalysisMock.get()
            .ccSupport()
            .setupCcToolchainConfig(
                mockToolsConfig,
                CcToolchainConfig.builder()
                    .withFeatures(
                        CppRuleClasses.SUPPORTS_DYNAMIC_LINKER,
                        CppRuleClasses.SUPPORTS_INTERFACE_SHARED_LIBRARIES
                    )
            )
        useConfiguration(
            "--platforms=" + TestConstants.PLATFORM_LABEL,
            "--experimental_platform_in_output_dir",
            String.format(
                "--experimental_override_name_platform_in_output_dir=%s=k8",
                TestConstants.PLATFORM_LABEL
            )
        )
        val hello: ConfiguredTarget = getConfiguredTarget("//hello:hello")
        val cpu = "k8" // CPU of the platform specified with --platforms
        val archive: Artifact = getBinArtifact("libhello.a", hello)
        val sharedObject: Artifact = getBinArtifact("libhello.ifso", hello)
        val implSharedObject: Artifact = getBinArtifact("libhello.so", hello)
        val sharedObjectLink: Artifact =
            getSharedArtifact("_solib_" + cpu + "/libhello_Slibhello.ifso", hello)
        val implSharedObjectLink: Artifact =
            getSharedArtifact("_solib_" + cpu + "/libhello_Slibhello.so", hello)
        assertThat(getFilesToBuild(hello).toList())
            .containsExactly(archive, sharedObject, implSharedObject)
        assertThat(
            LibraryToLink.getDynamicLibrariesForLinking(
                CcInfo.get(hello).getTransitiveCcNativeLibrariesForTests()
            )
        )
            .containsExactly(sharedObjectLink)
        assertThat(
            CcInfo.get(hello)
                .getCcLinkingContext()
                .getDynamicLibrariesForRuntime( /* linkingStatically= */false)
        )
            .containsExactly(implSharedObjectLink)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testFilesToBuildWithSaveFeatureState() {
        useConfiguration("--experimental_save_feature_state")
        val hello: ConfiguredTarget = getConfiguredTarget("//hello:hello")
        val archive: Artifact = getBinArtifact("libhello.a", hello)
        assertThat(getFilesToBuild(hello).toList()).containsExactly(archive)
        com.google.common.truth.Subject.contains("hello_feature_state.txt")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testEmptyLinkopts() {
        val hello: ConfiguredTarget = getConfiguredTarget("//hello:hello")
        assertThat(
            CcInfo.get(hello).getCcLinkingContext().getLinkerInputs().toList().stream()
                .allMatch({ linkerInput -> LinkerInput.getUserLinkFlags(linkerInput).isEmpty() })
        )
            .isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSoName() {
        AnalysisMock.get()
            .ccSupport()
            .setupCcToolchainConfig(
                mockToolsConfig,
                CcToolchainConfig.builder()
                    .withFeatures(
                        CppRuleClasses.SUPPORTS_DYNAMIC_LINKER,
                        CppRuleClasses.SUPPORTS_INTERFACE_SHARED_LIBRARIES
                    )
            )
        // Without interface shared libraries.
        useConfiguration("--nointerface_shared_objects")
        var hello: ConfiguredTarget = getConfiguredTarget("//hello:hello")
        var sharedObject: Artifact? =
            com.google.common.collect.Iterables.getOnlyElement<T?>(
                FileType.filter(getFilesToBuild(hello).toList(), CppFileTypes.SHARED_LIBRARY)
            )
        var action: SpawnAction = getGeneratingAction(sharedObject) as SpawnAction
        for (option in action.getArguments()) {
            Truth.assertThat(option).doesNotContain("-Wl,-soname")
        }

        // With interface shared libraries.
        useConfiguration("--interface_shared_objects")
        useConfiguration("--platforms=" + TestConstants.PLATFORM_LABEL)
        hello = getConfiguredTarget("//hello:hello")
        sharedObject =
            FileType.filter(getFilesToBuild(hello).toList(), CppFileTypes.SHARED_LIBRARY)
                .iterator()
                .next()
        action = getGeneratingAction(sharedObject) as SpawnAction
        com.google.common.truth.Subject.contains("-Wl,-soname=libhello_Slibhello.so")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testLinkActionCanConsumeArtifactExtensions() {
        AnalysisMock.get()
            .ccSupport()
            .setupCcToolchainConfig(
                mockToolsConfig,
                CcToolchainConfig.builder()
                    .withArtifactNamePatterns(MockCcSupport.STATIC_LINK_TWEAKED_ARTIFACT_NAME_PATTERN)
            )
        useConfiguration("--features=" + Link.LinkTargetType.STATIC_LIBRARY.actionName)
        val hello: ConfiguredTarget = getConfiguredTarget("//hello:hello")
        val archive: Artifact =
            FileType.filter(getFilesToBuild(hello).toList(), FileType.of(".lib")).iterator().next()

        val action: SpawnAction = getGeneratingAction(archive) as SpawnAction

        com.google.common.truth.Subject.contains(archive.getExecPathString())
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testObjectFileNamesCanBeSpecifiedInToolchain() {
        AnalysisMock.get()
            .ccSupport()
            .setupCcToolchainConfig(
                mockToolsConfig,
                CcToolchainConfig.builder()
                    .withArtifactNamePatterns(com.google.common.collect.ImmutableList.of<E?>("object_file", "", ".obj"))
            )

        useConfiguration()
        val hello: ConfiguredTarget = getConfiguredTarget("//hello:hello")
        Truth.assertThat(artifactByPath(getFilesToBuild(hello), ".a", ".obj")).isNotNull()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testWindowsFileNamePatternsCanBeSpecifiedInToolchain() {
        if (!AnalysisMock.get().isThisBazel()) {
            return
        }
        AnalysisMock.get()
            .ccSupport()
            .setupCcToolchainConfig(
                mockToolsConfig,
                CcToolchainConfig.builder()
                    .withFeatures(
                        CppRuleClasses.SUPPORTS_DYNAMIC_LINKER,
                        CppRuleClasses.COPY_DYNAMIC_LIBRARIES_TO_BINARY,
                        CppRuleClasses.SUPPORTS_INTERFACE_SHARED_LIBRARIES,
                        CppRuleClasses.TARGETS_WINDOWS
                    )
                    .withArtifactNamePatterns(
                        com.google.common.collect.ImmutableList.of<E?>("object_file", "", ".obj"),
                        com.google.common.collect.ImmutableList.of<E?>("static_library", "", ".lib"),
                        com.google.common.collect.ImmutableList.of<E?>("alwayslink_static_library", "", ".lo.lib"),
                        com.google.common.collect.ImmutableList.of<E?>("executable", "", ".exe"),
                        com.google.common.collect.ImmutableList.of<E?>("dynamic_library", "", ".dll"),
                        com.google.common.collect.ImmutableList.of<E?>("interface_library", "", ".if.lib")
                    )
            )
        useConfiguration()

        val hello: ConfiguredTarget = getConfiguredTarget("//hello:hello")
        val helloObj: Artifact =
            getBinArtifact("_objs/hello/hello.obj", getConfiguredTarget("//hello:hello"))
        val helloObjAction: CppCompileAction = getGeneratingAction(helloObj) as CppCompileAction
        assertThat(helloObjAction).isNotNull()

        val helloLib: Artifact =
            FileType.filter(getFilesToBuild(hello).toList(), CppFileTypes.ARCHIVE).iterator().next()
        assertThat(helloLib.getExecPathString()).endsWith("hello.lib")

        val helloAlwaysLink: ConfiguredTarget = getConfiguredTarget("//hello:hello_alwayslink")
        val helloLibAlwaysLink: Artifact =
            FileType.filter(getFilesToBuild(helloAlwaysLink).toList(), CppFileTypes.ALWAYS_LINK_LIBRARY)
                .iterator()
                .next()
        assertThat(helloLibAlwaysLink.getExecPathString()).endsWith("hello_alwayslink.lo.lib")

        val helloBin: ConfiguredTarget = getConfiguredTarget("//hello:hello_bin")
        val helloBinExe: Artifact = getFilesToBuild(helloBin).toList().get(0)
        assertThat(helloBinExe.getExecPathString()).endsWith("hello_bin.exe")

        Truth.assertThat(artifactsToStrings(getOutputGroup(hello, "dynamic_library")))
            .containsExactly("bin hello/hello_5e918d2.dll", "bin hello/hello.if.lib")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testWrongObjectFileArtifactNamePattern() {
        checkWrongExtensionInArtifactNamePattern(
            "object_file", ArtifactCategory.OBJECT_FILE.getAllowedExtensions()
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testWrongStaticLibraryArtifactNamePattern() {
        checkWrongExtensionInArtifactNamePattern(
            "static_library", ArtifactCategory.STATIC_LIBRARY.getAllowedExtensions()
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testWrongAlwayslinkStaticLibraryArtifactNamePattern() {
        checkWrongExtensionInArtifactNamePattern(
            "alwayslink_static_library",
            ArtifactCategory.ALWAYSLINK_STATIC_LIBRARY.getAllowedExtensions()
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testWrongExecutableArtifactNamePattern() {
        checkWrongExtensionInArtifactNamePattern(
            "executable", ArtifactCategory.EXECUTABLE.getAllowedExtensions()
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testWrongDynamicLibraryArtifactNamePattern() {
        checkWrongExtensionInArtifactNamePattern(
            "dynamic_library", ArtifactCategory.DYNAMIC_LIBRARY.getAllowedExtensions()
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testWrongInterfaceLibraryArtifactNamePattern() {
        checkWrongExtensionInArtifactNamePattern(
            "interface_library", ArtifactCategory.INTERFACE_LIBRARY.getAllowedExtensions()
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testArtifactSelectionBaseNameTemplating() {
        AnalysisMock.get()
            .ccSupport()
            .setupCcToolchainConfig(
                mockToolsConfig,
                CcToolchainConfig.builder()
                    .withArtifactNamePatterns(
                        MockCcSupport.STATIC_LINK_AS_DOT_A_ARTIFACT_NAME_PATTERN
                    )
            )
        useConfiguration("--features=" + Link.LinkTargetType.STATIC_LIBRARY.actionName)
        val hello: ConfiguredTarget = getConfiguredTarget("//hello:hello")
        val archive: Artifact =
            FileType.filter(getFilesToBuild(hello).toList(), CppFileTypes.ARCHIVE).iterator().next()
        assertThat(archive.getExecPathString()).endsWith("libhello.a")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testArtifactsToAlwaysBuild() {
        AnalysisMock.get()
            .ccSupport()
            .setupCcToolchainConfig(
                mockToolsConfig,
                CcToolchainConfig.builder()
                    .withFeatures(CppRuleClasses.SUPPORTS_PIC, CppRuleClasses.SUPPORTS_DYNAMIC_LINKER)
            )
        useConfiguration("--platforms=" + TestConstants.PLATFORM_LABEL)
        // ArtifactsToAlwaysBuild should apply both for static libraries.
        val helloStatic: ConfiguredTarget = getConfiguredTarget("//hello:hello_static")
        Truth.assertThat(artifactsToStrings(getOutputGroup(helloStatic, OutputGroupInfo.HIDDEN_TOP_LEVEL)))
            .containsExactly("bin hello/_objs/hello_static/hello.pic.o")
        var implSharedObject: Artifact = getBinArtifact("libhello_static.so", helloStatic)
        assertThat(getFilesToBuild(helloStatic).toList()).doesNotContain(implSharedObject)

        // And for shared libraries.
        val hello: ConfiguredTarget = getConfiguredTarget("//hello:hello")
        Truth.assertThat(artifactsToStrings(getOutputGroup(helloStatic, OutputGroupInfo.HIDDEN_TOP_LEVEL)))
            .containsExactly("bin hello/_objs/hello_static/hello.pic.o")
        implSharedObject = getBinArtifact("libhello.so", hello)
        com.google.common.truth.Subject.contains(implSharedObject)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTransitiveArtifactsToAlwaysBuildStatic() {
        AnalysisMock.get()
            .ccSupport()
            .setupCcToolchainConfig(
                mockToolsConfig, CcToolchainConfig.builder().withFeatures(CppRuleClasses.SUPPORTS_PIC)
            )

        useConfiguration("--platforms=" + TestConstants.PLATFORM_LABEL)
        val x: ConfiguredTarget =
            scratchConfiguredTarget(
                "foo",
                "x",
                "load('@rules_cc//cc:cc_library.bzl', 'cc_library')",
                "cc_library(name = 'x', srcs = ['x.cc'], deps = [':y'], linkstatic = 1)",
                "cc_library(name = 'y', srcs = ['y.cc'], deps = [':z'])",
                "cc_library(name = 'z', srcs = ['z.cc'])"
            )
        Truth.assertThat(artifactsToStrings(getOutputGroup(x, OutputGroupInfo.HIDDEN_TOP_LEVEL)))
            .containsExactly(
                "bin foo/_objs/x/x.pic.o", "bin foo/_objs/y/y.pic.o", "bin foo/_objs/z/z.pic.o"
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testBuildHeaderModulesAsPrerequisites() {
        AnalysisMock.get()
            .ccSupport()
            .setupCcToolchainConfig(
                mockToolsConfig,
                CcToolchainConfig.builder()
                    .withFeatures(MockCcSupport.HEADER_MODULES_FEATURES, CppRuleClasses.SUPPORTS_PIC)
            )
        useConfiguration("--platforms=" + TestConstants.PLATFORM_LABEL)
        val x: ConfiguredTarget =
            scratchConfiguredTarget(
                "foo",
                "x",
                "load('@rules_cc//cc:cc_library.bzl', 'cc_library')",
                "package(features = ['header_modules'])",
                "cc_library(name = 'x', srcs = ['x.cc'], deps = [':y'])",
                "cc_library(name = 'y', hdrs = ['y.h'])"
            )
        assertThat(
            ActionsTestUtil.baseArtifactNames(
                getOutputGroup(x, OutputGroupInfo.COMPILATION_PREREQUISITES)
            )
        )
            .containsAtLeast("y.h", "y.cppmap", "crosstool.cppmap", "x.cppmap", "y.pic.pcm", "x.cc")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCodeCoverage() {
        AnalysisMock.get()
            .ccSupport()
            .setupCcToolchainConfig(
                mockToolsConfig,
                CcToolchainConfig.builder()
                    .withFeatures(MockCcSupport.HEADER_MODULES_FEATURES, CppRuleClasses.SUPPORTS_PIC)
            )
        useConfiguration("--platforms=" + TestConstants.PLATFORM_LABEL, "--collect_code_coverage")
        val x: ConfiguredTarget =
            scratchConfiguredTarget(
                "foo",
                "x",
                "load('@rules_cc//cc:cc_library.bzl', 'cc_library')",
                "package(features = ['header_modules'])",
                "cc_library(name = 'x', srcs = ['x.cc'])"
            )
        assertThat(
            ActionsTestUtil.baseArtifactNames(
                x.get(InstrumentedFilesInfo.provider)
                    .getInstrumentationMetadataFiles()
            )
        )
            .containsExactly("x.pic.gcno")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDisablingHeaderModulesWhenDependingOnModuleBuildTransitively() {
        AnalysisMock.get()
            .ccSupport()
            .setupCcToolchainConfig(
                mockToolsConfig,
                CcToolchainConfig.builder().withFeatures(MockCcSupport.HEADER_MODULES_FEATURES)
            )
        useConfiguration()
        scratch.file(
            "module/BUILD",
            """
        load("@rules_cc//cc:cc_library.bzl", "cc_library")
        package(features = ['header_modules'])
        cc_library(
            name = 'module',
            srcs = ['a.cc', 'a.h'],
        )
        
        """.trimIndent()
        )
        scratch.file(
            "nomodule/BUILD",
            """
        load("@rules_cc//cc:cc_library.bzl", "cc_library")
        package(features = ['-header_modules'])
        cc_library(
            name = 'nomodule',
            srcs = ['a.cc', 'a.h'],
            deps = ['//module']
        )
        
        """.trimIndent()
        )
        val moduleAction: CppCompileAction = getCppCompileAction("//module:module")
        com.google.common.truth.Subject.contains("module_name://module:module")
        val noModuleAction: CppCompileAction = getCppCompileAction("//nomodule:nomodule")
        assertThat(noModuleAction.getCompilerOptions()).doesNotContain("module_name://module:module")
    }

    /** Returns the flags in `input` that reference a header module.  */
    private fun getHeaderModuleFlags(input: Iterable<String?>): Iterable<String> {
        val names: MutableList<String> = java.util.ArrayList<String>()
        for (flag in input) {
            if (CppFileTypes.CPP_MODULE.matches(flag)) {
                names.add(PathFragment.create(flag).getBaseName())
            }
        }
        return names
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCompileHeaderModules() {
        AnalysisMock.get()
            .ccSupport()
            .setupCcToolchainConfig(
                mockToolsConfig, CcToolchainConfig.builder().withFeatures("compile_header_modules")
            )
        useConfiguration("--platforms=" + TestConstants.PLATFORM_LABEL)
        scratch.file(
            "module/BUILD",
            """
        load("@rules_cc//cc:cc_library.bzl", "cc_library")
        package(features = ['header_modules'])
        cc_library(
            name = 'a',
            srcs = ['a.h', 'a.cc'],
            deps = ['b']
        )
        cc_library(
            name = 'b',
            srcs = ['b.h'],
            textual_hdrs = ['t.h'],
        )
        
        """.trimIndent()
        )
        val moduleB: ConfiguredTarget = getConfiguredTarget("//module:b")
        val bModuleArtifact: Artifact = getBinArtifact("_objs/b/b.pic.pcm", moduleB)
        val bModuleAction: CppCompileAction = getGeneratingAction(bModuleArtifact) as CppCompileAction
        assertThat(bModuleAction.getIncludeScannerSources())
            .containsExactly(getSourceArtifact("module/b.h"), getSourceArtifact("module/t.h"))
        com.google.common.truth.Subject.contains(getGenfilesArtifact("b.cppmap", moduleB))

        val moduleA: ConfiguredTarget = getConfiguredTarget("//module:a")
        val aObjectArtifact: Artifact = getBinArtifact("_objs/a/a.pic.o", moduleA)
        val aObjectAction: CppCompileAction = getGeneratingAction(aObjectArtifact) as CppCompileAction
        assertThat(aObjectAction.getIncludeScannerSources())
            .containsExactly(getSourceArtifact("module/a.cc"))
        com.google.common.truth.Subject.contains(getBinArtifact("_objs/b/b.pic.pcm", moduleB))
        com.google.common.truth.Subject.contains(getGenfilesArtifact("b.cppmap", moduleB))
        assertNoEvents()
    }

    @Throws(java.lang.Exception::class)
    private fun setupPackagesForSourcesWithSameBaseNameTests() {
        scratch.file(
            "foo/BUILD",
            """
        load("@rules_cc//cc:cc_library.bzl", "cc_library")
        cc_library(
            name = 'lib',
            srcs = ['a.cc', 'subpkg1/b.cc', 'subpkg1/a.c', '//bar:srcs', 'subpkg2/A.c'],
        )
        
        """.trimIndent()
        )
        scratch.file("bar/BUILD", "filegroup(name = 'srcs', srcs = ['a.cpp'])")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testContainingSourcesWithSameBaseName() {
        AnalysisMock.get()
            .ccSupport()
            .setupCcToolchainConfig(
                mockToolsConfig, CcToolchainConfig.builder().withFeatures(CppRuleClasses.SUPPORTS_PIC)
            )
        useConfiguration("--platforms=" + TestConstants.PLATFORM_LABEL)
        setupPackagesForSourcesWithSameBaseNameTests()
        getConfiguredTarget("//foo:lib")

        val a0: Artifact = getBinArtifact("_objs/lib/0/a.pic.o", getConfiguredTarget("//foo:lib"))
        val a1: Artifact = getBinArtifact("_objs/lib/1/a.pic.o", getConfiguredTarget("//foo:lib"))
        val a2: Artifact = getBinArtifact("_objs/lib/2/a.pic.o", getConfiguredTarget("//foo:lib"))
        val a3: Artifact = getBinArtifact("_objs/lib/3/A.pic.o", getConfiguredTarget("//foo:lib"))
        val b: Artifact = getBinArtifact("_objs/lib/b.pic.o", getConfiguredTarget("//foo:lib"))

        assertThat(getGeneratingAction(a0)).isNotNull()
        assertThat(getGeneratingAction(a1)).isNotNull()
        assertThat(getGeneratingAction(a2)).isNotNull()
        assertThat(getGeneratingAction(a3)).isNotNull()
        assertThat(getGeneratingAction(b)).isNotNull()

        com.google.common.truth.Subject.contains(getSourceArtifact("foo/a.cc"))
        com.google.common.truth.Subject.contains(getSourceArtifact("foo/subpkg1/a.c"))
        com.google.common.truth.Subject.contains(getSourceArtifact("bar/a.cpp"))
        com.google.common.truth.Subject.contains(getSourceArtifact("foo/subpkg2/A.c"))
        com.google.common.truth.Subject.contains(getSourceArtifact("foo/subpkg1/b.cc"))
    }

    @Throws(java.lang.Exception::class)
    private fun setupPackagesForModuleTests(useHeaderModules: Boolean) {
        scratch.file(
            "module/BUILD",
            """
        load("@rules_cc//cc:cc_library.bzl", "cc_library")
        package(features = ['header_modules'])
        cc_library(
            name = 'b',
            srcs = ['b.h'],
            deps = ['//nomodule:a'],
        )
        cc_library(
            name = 'g',
            srcs = ['g.h', 'g.cc'],
            deps = ['//nomodule:c'],
        )
        cc_library(
            name = 'j',
            srcs = ['j.h', 'j.cc'],
            deps = ['//nomodule:c', '//nomodule:i'],
        )
        
        """.trimIndent()
        )
        scratch.file(
            "nomodule/BUILD",
            "load('@rules_cc//cc:cc_library.bzl', 'cc_library')",
            ("package(features = ['-header_modules'"
                    + (if (useHeaderModules) ", 'use_header_modules'" else "")
                    + "])"),
            "cc_library(",
            "    name = 'y',",
            "    srcs = ['y.h'],",
            ")",
            "cc_library(",
            "    name = 'z',",
            "    srcs = ['z.h'],",
            "    deps = [':y'],",
            ")",
            "cc_library(",
            "    name = 'a',",
            "    srcs = ['a.h'],",
            "    deps = [':z'],",
            ")",
            "cc_library(",
            "    name = 'c',",
            "    srcs = ['c.h', 'c.cc'],",
            "    deps = ['//module:b'],",
            ")",
            "cc_library(",
            "    name = 'd',",
            "    srcs = ['d.h', 'd.cc'],",
            "    deps = [':c'],",
            ")",
            "cc_library(",
            "    name = 'e',",
            "    srcs = ['e.h'],",
            "    deps = [':a'],",
            ")",
            "cc_library(",
            "    name = 'f',",
            "    srcs = ['f.h', 'f.cc'],",
            "    deps = [':e'],",
            ")",
            "cc_library(",
            "    name = 'h',",
            "    srcs = ['h.h', 'h.cc'],",
            "    deps = ['//module:g'],",
            ")",
            "cc_library(",
            "    name = 'i',",
            "    srcs = ['i.h', 'i.cc'],",
            "    deps = [':h'],",
            ")"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCompileHeaderModulesTransitively() {
        AnalysisMock.get()
            .ccSupport()
            .setupCcToolchainConfig(
                mockToolsConfig,
                CcToolchainConfig.builder()
                    .withFeatures(MockCcSupport.HEADER_MODULES_FEATURES, CppRuleClasses.SUPPORTS_PIC)
            )
        useConfiguration("--platforms=" + TestConstants.PLATFORM_LABEL)
        setupPackagesForModuleTests( /* useHeaderModules= */false)

        // The //nomodule:f target only depends on non-module targets, thus it should be module-free.
        val nomoduleF: ConfiguredTarget = getConfiguredTarget("//nomodule:f")
        val nomoduleE: ConfiguredTarget = getConfiguredTarget("//nomodule:e")
        assertThat(getGeneratingAction(getBinArtifact("_objs/f/f.pic.pcm", nomoduleF))).isNull()
        val fObjectArtifact: Artifact = getBinArtifact("_objs/f/f.pic.o", nomoduleF)
        val fObjectAction: CppCompileAction = getGeneratingAction(fObjectArtifact) as CppCompileAction
        // Only the module map of f itself itself and the direct dependencies are needed.
        Truth.assertThat(getNonSystemModuleMaps(fObjectAction.getInputs()))
            .containsExactly(
                getGenfilesArtifact("f.cppmap", nomoduleF), getGenfilesArtifact("e.cppmap", nomoduleE)
            )
        Truth.assertThat(getHeaderModules(fObjectAction.getInputs())).isEmpty()
        assertThat(fObjectAction.getIncludeScannerSources())
            .containsExactly(getSourceArtifact("nomodule/f.cc"))
        Truth.assertThat(getHeaderModuleFlags(fObjectAction.getCompilerOptions())).isEmpty()

        // The //nomodule:c target will get the header module for //module:b, which is a direct
        // dependency.
        val nomoduleC: ConfiguredTarget = getConfiguredTarget("//nomodule:c")
        assertThat(getGeneratingAction(getBinArtifact("_objs/c/c.pic.pcm", nomoduleC))).isNull()
        val cObjectArtifact: Artifact = getBinArtifact("_objs/c/c.pic.o", nomoduleC)
        val cObjectAction: CppCompileAction = getGeneratingAction(cObjectArtifact) as CppCompileAction
        Truth.assertThat(getNonSystemModuleMaps(cObjectAction.getInputs()))
            .containsExactly(
                getGenfilesArtifact("b.cppmap", "//module:b"),
                getGenfilesArtifact("c.cppmap", nomoduleC)
            )
        Truth.assertThat(getHeaderModules(cObjectAction.getInputs())).isEmpty()
        // All headers of transitive dependencies that are built as modules are needed as entry points
        // for include scanning.
        assertThat(cObjectAction.getIncludeScannerSources())
            .containsExactly(getSourceArtifact("nomodule/c.cc"))
        assertThat(cObjectAction.getMainIncludeScannerSource())
            .isEqualTo(getSourceArtifact("nomodule/c.cc"))
        Truth.assertThat(getHeaderModuleFlags(cObjectAction.getCompilerOptions())).isEmpty()

        // The //nomodule:d target depends on //module:b via one indirection (//nomodule:c).
        getConfiguredTarget("//nomodule:d")
        assertThat(
            getGeneratingAction(
                getBinArtifact("_objs/d/d.pic.pcm", getConfiguredTarget("//nomodule:d"))
            )
        )
            .isNull()
        val dObjectArtifact: Artifact =
            getBinArtifact("_objs/d/d.pic.o", getConfiguredTarget("//nomodule:d"))
        val dObjectAction: CppCompileAction = getGeneratingAction(dObjectArtifact) as CppCompileAction
        // Module map 'c.cppmap' is needed because it is a direct dependency.
        Truth.assertThat(getNonSystemModuleMaps(dObjectAction.getInputs()))
            .containsExactly(
                getGenfilesArtifact("c.cppmap", "//nomodule:c"),
                getGenfilesArtifact("d.cppmap", "//nomodule:d")
            )
        Truth.assertThat(getHeaderModules(dObjectAction.getInputs())).isEmpty()
        assertThat(dObjectAction.getIncludeScannerSources())
            .containsExactly(getSourceArtifact("nomodule/d.cc"))
        Truth.assertThat(getHeaderModuleFlags(dObjectAction.getCompilerOptions())).isEmpty()

        // The //module:j target depends on //module:g via //nomodule:h and on //module:b via
        // both //module:g and //nomodule:c.
        val moduleJ: ConfiguredTarget = getConfiguredTarget("//module:j")
        val jObjectArtifact: Artifact = getBinArtifact("_objs/j/j.pic.o", moduleJ)
        val jObjectAction: CppCompileAction = getGeneratingAction(jObjectArtifact) as CppCompileAction
        Truth.assertThat(getHeaderModules(jObjectAction.getCcCompilationContext().getTransitiveModules(true)))
            .containsExactly(
                getBinArtifact("_objs/b/b.pic.pcm", getConfiguredTarget("//module:b")),
                getBinArtifact("_objs/g/g.pic.pcm", getConfiguredTarget("//module:g"))
            )
        assertThat(jObjectAction.getIncludeScannerSources())
            .containsExactly(getSourceArtifact("module/j.cc"))
        assertThat(jObjectAction.getMainIncludeScannerSource())
            .isEqualTo(getSourceArtifact("module/j.cc"))
        Truth.assertThat(getHeaderModules(jObjectAction.getCcCompilationContext().getTransitiveModules(true)))
            .containsExactly(
                getBinArtifact("_objs/b/b.pic.pcm", getConfiguredTarget("//module:b")),
                getBinArtifact("_objs/g/g.pic.pcm", getConfiguredTarget("//module:g"))
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCompileUsingHeaderModulesTransitively() {
        AnalysisMock.get()
            .ccSupport()
            .setupCcToolchainConfig(
                mockToolsConfig,
                CcToolchainConfig.builder()
                    .withFeatures(MockCcSupport.HEADER_MODULES_FEATURES, CppRuleClasses.SUPPORTS_PIC)
            )
        useConfiguration("--platforms=" + TestConstants.PLATFORM_LABEL)
        setupPackagesForModuleTests( /* useHeaderModules= */true)
        invalidatePackages()

        val nomoduleF: ConfiguredTarget = getConfiguredTarget("//nomodule:f")
        val fObjectArtifact: Artifact =
            getBinArtifact("_objs/f/f.pic.o", getConfiguredTarget("//nomodule:f"))
        val fObjectAction: CppCompileAction = getGeneratingAction(fObjectArtifact) as CppCompileAction
        // Only the module map of f itself itself and the direct dependencies are needed.
        Truth.assertThat(getNonSystemModuleMaps(fObjectAction.getInputs()))
            .containsExactly(
                getGenfilesArtifact("f.cppmap", nomoduleF),
                getGenfilesArtifact("e.cppmap", "//nomodule:e")
            )

        getConfiguredTarget("//nomodule:c")
        val cObjectArtifact: Artifact =
            getBinArtifact("_objs/c/c.pic.o", getConfiguredTarget("//nomodule:c"))
        val cObjectAction: CppCompileAction = getGeneratingAction(cObjectArtifact) as CppCompileAction
        Truth.assertThat(getNonSystemModuleMaps(cObjectAction.getInputs()))
            .containsExactly(
                getGenfilesArtifact("b.cppmap", "//module:b"),
                getGenfilesArtifact("c.cppmap", "//nomodule:c")
            )
        Truth.assertThat(getHeaderModules(cObjectAction.getCcCompilationContext().getTransitiveModules(true)))
            .containsExactly(getBinArtifact("_objs/b/b.pic.pcm", getConfiguredTarget("//module:b")))

        getConfiguredTarget("//nomodule:d")
        val dObjectArtifact: Artifact =
            getBinArtifact("_objs/d/d.pic.o", getConfiguredTarget("//nomodule:d"))
        val dObjectAction: CppCompileAction = getGeneratingAction(dObjectArtifact) as CppCompileAction
        Truth.assertThat(getNonSystemModuleMaps(dObjectAction.getInputs()))
            .containsExactly(
                getGenfilesArtifact("c.cppmap", "//nomodule:c"),
                getGenfilesArtifact("d.cppmap", "//nomodule:d")
            )
        Truth.assertThat(getHeaderModules(dObjectAction.getCcCompilationContext().getTransitiveModules(true)))
            .containsExactly(getBinArtifact("_objs/b/b.pic.pcm", getConfiguredTarget("//module:b")))
    }

    @Throws(java.lang.Exception::class)
    private fun writeSimpleCcLibrary() {
        scratch.file(
            "module/BUILD",
            """
        load("@rules_cc//cc:cc_library.bzl", "cc_library")
        cc_library(
            name = 'map',
            srcs = ['a.cc', 'a.h'],
        )
        
        """.trimIndent()
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testToolchainWithoutPicForNoPicCompilation() {
        AnalysisMock.get()
            .ccSupport()
            .setupCcToolchainConfig(
                mockToolsConfig,
                CcToolchainConfig.builder()
                    .withFeatures(CppRuleClasses.NO_LEGACY_FEATURES)
                    .withActionConfigs(
                        CppActionNames.CPP_COMPILE,
                        CppActionNames.CPP_LINK_EXECUTABLE,
                        CppActionNames.CPP_LINK_NODEPS_DYNAMIC_LIBRARY,
                        CppActionNames.CPP_LINK_DYNAMIC_LIBRARY,
                        CppActionNames.CPP_LINK_STATIC_LIBRARY,
                        CppActionNames.STRIP
                    )
            )
        useConfiguration("--features=-supports_pic")
        scratchConfiguredTarget(
            "a",
            "a",
            "load('@rules_cc//cc:cc_binary.bzl', 'cc_binary')",
            "load('@rules_cc//cc:cc_library.bzl', 'cc_library')",
            "cc_binary(name='a', srcs=['a.cc'], deps=[':b'])",
            "cc_library(name='b', srcs=['b.cc'])"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testNoCppModuleMap() {
        AnalysisMock.get()
            .ccSupport()
            .setupCcToolchainConfig(
                mockToolsConfig,
                CcToolchainConfig.builder()
                    .withFeatures(CppRuleClasses.NO_LEGACY_FEATURES, CppRuleClasses.PIC)
                    .withActionConfigs(
                        CppActionNames.CPP_COMPILE,
                        CppActionNames.CPP_LINK_EXECUTABLE,
                        CppActionNames.CPP_LINK_NODEPS_DYNAMIC_LIBRARY,
                        CppActionNames.CPP_LINK_DYNAMIC_LIBRARY,
                        CppActionNames.CPP_LINK_STATIC_LIBRARY
                    )
            )
        useConfiguration()
        writeSimpleCcLibrary()
        assertNoCppModuleMapAction("//module:map")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCppModuleMap() {
        AnalysisMock.get()
            .ccSupport()
            .setupCcToolchainConfig(
                mockToolsConfig, CcToolchainConfig.builder().withFeatures(CppRuleClasses.MODULE_MAPS)
            )
        useConfiguration()
        writeSimpleCcLibrary()

        val lib: ConfiguredTarget = getConfiguredTarget("//module:map")
        val moduleMap: Artifact = CcInfo.get(lib).getCcCompilationContext().getCppModuleMap().getArtifact()
        val moduleMapData = getCppModuleMapData(moduleMap)
        Truth.assertThat(moduleMapData).contains("use \"crosstool\"")
        Truth.assertThat(moduleMapData).containsMatch("private textual header \".*module\\/a.h\"")
        // check there are no public headers
        Truth.assertThat(moduleMapData).doesNotContainMatch("(?<!(private textual )|(private ))header")
    }

    /**
     * Historically, blaze hasn't added the pre-compiled libraries from srcs to the files to build.
     * This test ensures that we do not accidentally break that - we may do so intentionally.
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testFilesToBuildWithPrecompiledStaticLibrary() {
        val hello: ConfiguredTarget =
            scratchConfiguredTarget(
                "precompiled",
                "library",
                "load('@rules_cc//cc:cc_library.bzl', 'cc_library')",
                "cc_library(name = 'library', ",
                "           srcs = ['missing.a'])"
            )
        Truth.assertThat(artifactsToStrings(getFilesToBuild(hello)))
            .doesNotContain("src precompiled/missing.a")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAllowDuplicateNonCompiledSources() {
        val x: ConfiguredTarget =
            scratchConfiguredTarget(
                "x",
                "x",
                "load('@rules_cc//cc:cc_library.bzl', 'cc_library')",
                "filegroup(name = 'xso', srcs = ['x.so'])",
                "cc_library(name = 'x', srcs = ['x.so', ':xso'])"
            )
        assertThat(x).isNotNull()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDoNotCompileSourceFilesInHeaders() {
        AnalysisMock.get()
            .ccSupport()
            .setupCcToolchainConfig(
                mockToolsConfig,
                CcToolchainConfig.builder().withFeatures(CppRuleClasses.PARSE_HEADERS)
            )
        useConfiguration("--features=parse_headers")
        val x: ConfiguredTarget =
            scratchConfiguredTarget(
                "x",
                "x",
                "load('@rules_cc//cc:cc_library.bzl', 'cc_library')",
                "cc_library(name = 'x', hdrs = ['x.cc'])"
            )
        assertThat(getGeneratingAction(getBinArtifact("_objs/x/x.o", x))).isNull()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testProcessHeadersInDependencies() {
        AnalysisMock.get()
            .ccSupport()
            .setupCcToolchainConfig(
                mockToolsConfig,
                CcToolchainConfig.builder().withFeatures(CppRuleClasses.PARSE_HEADERS)
            )
        useConfiguration("--features=parse_headers", "--process_headers_in_dependencies")
        val x: ConfiguredTarget =
            scratchConfiguredTarget(
                "foo",
                "x",
                "load('@rules_cc//cc:cc_library.bzl', 'cc_library')",
                "cc_library(name = 'x', deps = [':y'])",
                "cc_library(name = 'y', hdrs = ['y.h'])"
            )
        assertThat(ActionsTestUtil.baseNamesOf(getOutputGroup(x, OutputGroupInfo.HIDDEN_TOP_LEVEL)))
            .isEqualTo("y.h.processed")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testProcessHeadersInDependenciesOfBinaries() {
        AnalysisMock.get()
            .ccSupport()
            .setupCcToolchainConfig(
                mockToolsConfig,
                CcToolchainConfig.builder().withFeatures(CppRuleClasses.PARSE_HEADERS)
            )
        useConfiguration("--features=parse_headers", "--process_headers_in_dependencies")
        val x: ConfiguredTarget =
            scratchConfiguredTarget(
                "foo",
                "x",
                "load('@rules_cc//cc:cc_binary.bzl', 'cc_binary')",
                "load('@rules_cc//cc:cc_library.bzl', 'cc_library')",
                "cc_binary(name = 'x', deps = [':y', ':z'])",
                "cc_library(name = 'y', hdrs = ['y.h'])",
                "cc_library(name = 'z', srcs = ['z.cc'])"
            )
        val hiddenTopLevel: String? =
            ActionsTestUtil.baseNamesOf(getOutputGroup(x, OutputGroupInfo.HIDDEN_TOP_LEVEL))
        Truth.assertThat(hiddenTopLevel).doesNotContain("y.h.processed")
        Truth.assertThat(hiddenTopLevel).doesNotContain("z.pic.o")
        val validation: String? = ActionsTestUtil.baseNamesOf(getOutputGroup(x, OutputGroupInfo.VALIDATION))
        Truth.assertThat(validation).contains("y.h.processed")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDoNotProcessHeadersInDependencies() {
        AnalysisMock.get()
            .ccSupport()
            .setupCcToolchainConfig(
                mockToolsConfig,
                CcToolchainConfig.builder().withFeatures(CppRuleClasses.PARSE_HEADERS)
            )
        useConfiguration("--features=parse_headers")
        val x: ConfiguredTarget =
            scratchConfiguredTarget(
                "foo",
                "x",
                "load('@rules_cc//cc:cc_library.bzl', 'cc_library')",
                "cc_library(name = 'x', deps = [':y'])",
                "cc_library(name = 'y', hdrs = ['y.h'])"
            )
        assertThat(ActionsTestUtil.baseNamesOf(getOutputGroup(x, OutputGroupInfo.HIDDEN_TOP_LEVEL)))
            .isEmpty()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testProcessHeadersInCompileOnlyMode() {
        AnalysisMock.get()
            .ccSupport()
            .setupCcToolchainConfig(
                mockToolsConfig,
                CcToolchainConfig.builder().withFeatures(CppRuleClasses.PARSE_HEADERS)
            )
        useConfiguration("--features=parse_headers", "--process_headers_in_dependencies")
        val y: ConfiguredTarget =
            scratchConfiguredTarget(
                "foo",
                "y",
                "load('@rules_cc//cc:cc_library.bzl', 'cc_library')",
                "cc_library(name = 'x', deps = [':y'])",
                "cc_library(name = 'y', hdrs = ['y.h'])"
            )
        assertThat(ActionsTestUtil.baseNamesOf(getOutputGroup(y, OutputGroupInfo.FILES_TO_COMPILE)))
            .isEqualTo("y.h.processed")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSrcCompileActionMnemonic() {
        AnalysisMock.get()
            .ccSupport()
            .setupCcToolchainConfig(
                mockToolsConfig,
                CcToolchainConfig.builder().withFeatures(CppRuleClasses.PARSE_HEADERS)
            )
        useConfiguration("--features=parse_headers", "--process_headers_in_dependencies")

        val x: ConfiguredTarget =
            scratchConfiguredTarget(
                "foo",
                "x",
                "load('@rules_cc//cc:cc_library.bzl', 'cc_library')",
                "cc_library(name = 'x', srcs = ['a.cc'])"
            )

        assertThat(getGeneratingCompileAction("_objs/x/a.o", x).getMnemonic()).isEqualTo("CppCompile")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testHeaderCompileActionMnemonic() {
        AnalysisMock.get()
            .ccSupport()
            .setupCcToolchainConfig(
                mockToolsConfig,
                CcToolchainConfig.builder().withFeatures(CppRuleClasses.PARSE_HEADERS)
            )
        useConfiguration("--features=parse_headers", "--process_headers_in_dependencies")

        val x: ConfiguredTarget =
            scratchConfiguredTarget(
                "foo",
                "x",
                "load('@rules_cc//cc:cc_library.bzl', 'cc_library')",
                "cc_library(name = 'x', srcs = ['y.h'], hdrs = ['z.h'])"
            )

        assertThat(getGeneratingCompileAction("_objs/x/y.h.processed", x).getMnemonic())
            .isEqualTo("CppCompile")
        assertThat(getGeneratingCompileAction("_objs/x/z.h.processed", x).getMnemonic())
            .isEqualTo("CppCompile")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testIncompatibleUseCppCompileHeaderMnemonic() {
        AnalysisMock.get()
            .ccSupport()
            .setupCcToolchainConfig(
                mockToolsConfig,
                CcToolchainConfig.builder().withFeatures(CppRuleClasses.PARSE_HEADERS)
            )
        useConfiguration(
            "--incompatible_use_cpp_compile_header_mnemonic",
            "--features=parse_headers",
            "--process_headers_in_dependencies"
        )

        val x: ConfiguredTarget =
            scratchConfiguredTarget(
                "foo",
                "x",
                "load('@rules_cc//cc:cc_library.bzl', 'cc_library')",
                "cc_library(name = 'x', srcs = ['a.cc', 'y.h'], hdrs = ['z.h'])"
            )

        assertThat(getGeneratingCompileAction("_objs/x/a.o", x).getMnemonic()).isEqualTo("CppCompile")
        assertThat(getGeneratingCompileAction("_objs/x/y.h.processed", x).getMnemonic())
            .isEqualTo("CppCompileHeader")
        assertThat(getGeneratingCompileAction("_objs/x/z.h.processed", x).getMnemonic())
            .isEqualTo("CppCompileHeader")
    }

    private fun getGeneratingCompileAction(
        packageRelativePath: String?, owner: ConfiguredTarget
    ): CppCompileAction {
        return getGeneratingAction(getBinArtifact(packageRelativePath, owner)) as CppCompileAction
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testIncludePathOrder() {
        useConfiguration("--incompatible_merge_genfiles_directory=false")
        scratch.file(
            "foo/BUILD",
            """
        load("@rules_cc//cc:cc_library.bzl", "cc_library")
        cc_library(
            name = 'bar',
            includes = ['bar'],
        )
        cc_library(
            name = 'foo',
            srcs = ['foo.cc'],
            includes = ['foo'],
            deps = [':bar'],
        )
        
        """.trimIndent()
        )
        val target: ConfiguredTarget = getConfiguredTarget("//foo")
        val action: CppCompileAction = getCppCompileAction(target)
        val genfilesDir: String? =
            getConfiguration(target).getGenfilesFragment(RepositoryName.MAIN).toString()
        val binDir: String? = getConfiguration(target).getBinFragment(RepositoryName.MAIN).toString()
        // Local include paths come first.
        assertContainsSublist(
            action.getCompilerOptions(),
            com.google.common.collect.ImmutableList.of<E?>(
                "-Ifoo/foo",
                "-I" + genfilesDir + "/foo/foo",
                "-I" + binDir + "/foo/foo",
                "-Ifoo/bar",
                "-I" + genfilesDir + "/foo/bar",
                "-I" + binDir + "/foo/bar"
            )
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDefinesOrder() {
        scratch.file(
            "foo/BUILD",
            """
        load("@rules_cc//cc:cc_library.bzl", "cc_library")
        cc_library(
            name = 'bar',
            defines = ['BAR'],
        )
        cc_library(
            name = 'foo',
            srcs = ['foo.cc'],
            defines = ['FOO'],
            deps = [':bar'],
        )
        
        """.trimIndent()
        )
        val action: CppCompileAction = getCppCompileAction("//foo")
        // Inherited defines come first.
        assertContainsSublist(
            action.getCompilerOptions(),
            com.google.common.collect.ImmutableList.of<E?>("-DBAR", "-DFOO")
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testLocalDefinesNotPassedTransitively() {
        scratch.file(
            "foo/BUILD",
            """
        load("@rules_cc//cc:cc_library.bzl", "cc_library")
        cc_library(
            name = 'bar',
            defines = ['TRANSITIVE_BAR'],
            local_defines = ['LOCAL_BAR'],
        )
        cc_library(
            name = 'foo',
            srcs = ['foo.cc'],
            defines = ['TRANSITIVE_FOO'],
            local_defines = ['LOCAL_FOO'],
            deps = [':bar'],
        )
        
        """.trimIndent()
        )
        val action: CppCompileAction = getCppCompileAction("//foo")
        // Inherited defines come first.
        assertContainsSublist(
            action.getCompilerOptions(),
            com.google.common.collect.ImmutableList.of<E?>("-DTRANSITIVE_BAR", "-DTRANSITIVE_FOO", "-DLOCAL_FOO")
        )
        assertThat(action.getCompilerOptions()).doesNotContain("-DLOCAL_BAR")
    }

    // Regression test - setting "-shared" caused an exception when computing the link command.
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testLinkOptsNotPassedToStaticLink() {
        scratchConfiguredTarget(
            "foo",
            "foo",
            "load('@rules_cc//cc:cc_library.bzl', 'cc_library')",
            "cc_library(",
            "    name = 'foo',",
            "    srcs = ['foo.cc'],",
            "    linkopts = ['-shared'],",
            ")"
        )
    }

    @Throws(java.lang.Exception::class)
    private fun getCompilationModeFlags(vararg flags: String?): MutableList<String> {
        AnalysisMock.get()
            .ccSupport()
            .setupCcToolchainConfig(
                mockToolsConfig,
                CcToolchainConfig.builder()
                    .withFeatures(COMPILATION_MODE_FEATURES, CppRuleClasses.SUPPORTS_PIC)
            )
        useConfiguration(*flags)
        scratch.overwriteFile(
            "mode/BUILD",
            "load('@rules_cc//cc:cc_library.bzl', 'cc_library')",
            "cc_library(name = 'a', srcs = ['a.cc'])"
        )
        getConfiguredTarget("//mode:a")
        val objectArtifact: Artifact = getBinArtifact("_objs/a/a.pic.o", getConfiguredTarget("//mode:a"))
        val action: CppCompileAction = getGeneratingAction(objectArtifact) as CppCompileAction
        return action.getCompilerOptions()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCompilationModeFeatures() {
        var flags: MutableList<String>?
        flags = getCompilationModeFlags("--platforms=" + TestConstants.PLATFORM_LABEL)
        Truth.assertThat(flags).contains("-fastbuild")
        Truth.assertThat(flags).containsNoneOf("-opt", "-dbg")

        flags =
            getCompilationModeFlags(
                "--platforms=" + TestConstants.PLATFORM_LABEL, "--compilation_mode=fastbuild"
            )
        Truth.assertThat(flags).contains("-fastbuild")
        Truth.assertThat(flags).containsNoneOf("-opt", "-dbg")

        flags =
            getCompilationModeFlags(
                "--platforms=" + TestConstants.PLATFORM_LABEL, "--compilation_mode=opt"
            )
        Truth.assertThat(flags).contains("-opt")
        Truth.assertThat(flags).containsNoneOf("-fastbuild", "-dbg")

        flags =
            getCompilationModeFlags(
                "--platforms=" + TestConstants.PLATFORM_LABEL, "--compilation_mode=dbg"
            )
        Truth.assertThat(flags).contains("-dbg")
        Truth.assertThat(flags).containsNoneOf("-fastbuild", "-opt")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testIncludePathsOutsideExecutionRoot() {
        scratchRule(
            "root",
            "a",
            "load('@rules_cc//cc:cc_library.bzl', 'cc_library')",
            "cc_library(name='a', srcs=['a.cc'], copts=['-Id/../../somewhere'])"
        )
        val compileAction: CppCompileAction = getCppCompileAction("//root:a")
        try {
            compileAction.verifyActionIncludePaths(compileAction.getSystemIncludeDirs(), false)
        } catch (exception: ActionExecutionException) {
            assertThat(exception)
                .hasMessageThat()
                .isEqualTo(
                    "The include path '../somewhere' references a path outside of the execution root."
                )
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAbsoluteIncludePathsOutsideExecutionRoot() {
        scratchRule(
            "root",
            "a",
            "load('@rules_cc//cc:cc_library.bzl', 'cc_library')",
            "cc_library(name='a', srcs=['a.cc'], copts=['-I/somewhere'])"
        )
        val compileAction: CppCompileAction = getCppCompileAction("//root:a")
        try {
            compileAction.verifyActionIncludePaths(compileAction.getSystemIncludeDirs(), false)
        } catch (exception: ActionExecutionException) {
            assertThat(exception)
                .hasMessageThat()
                .isEqualTo(
                    "The include path '/somewhere' references a path outside of the execution root."
                )
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSystemIncludePathsOutsideExecutionRoot() {
        scratchRule(
            "root",
            "a",
            "load('@rules_cc//cc:cc_library.bzl', 'cc_library')",
            "cc_library(name='a', srcs=['a.cc'], copts=['-isystem../system'])"
        )
        val compileAction: CppCompileAction = getCppCompileAction("//root:a")
        try {
            compileAction.verifyActionIncludePaths(compileAction.getSystemIncludeDirs(), false)
        } catch (exception: ActionExecutionException) {
            assertThat(exception)
                .hasMessageThat()
                .isEqualTo(
                    "The include path '../system' references a path outside of the execution root."
                )
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAbsoluteSystemIncludePathsOutsideExecutionRoot() {
        scratchRule(
            "root",
            "a",
            "load('@rules_cc//cc:cc_library.bzl', 'cc_library')",
            "cc_library(name='a', srcs=['a.cc'], copts=['-isystem/system'])"
        )
        val compileAction: CppCompileAction = getCppCompileAction("//root:a")
        try {
            compileAction.verifyActionIncludePaths(compileAction.getSystemIncludeDirs(), false)
        } catch (exception: ActionExecutionException) {
            assertThat(exception)
                .hasMessageThat()
                .isEqualTo("The include path '/system' references a path outside of the execution root.")
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun alwaysAddStaticAndDynamicLibraryToFilesToBuildWhenBuilding() {
        AnalysisMock.get()
            .ccSupport()
            .setupCcToolchainConfig(
                mockToolsConfig,
                CcToolchainConfig.builder()
                    .withFeatures(
                        CppRuleClasses.SUPPORTS_DYNAMIC_LINKER,
                        CppRuleClasses.SUPPORTS_INTERFACE_SHARED_LIBRARIES
                    )
            )
        useConfiguration("--platforms=" + TestConstants.PLATFORM_LABEL)
        val target: ConfiguredTarget =
            scratchConfiguredTarget(
                "a",
                "b",
                "load('@rules_cc//cc:cc_library.bzl', 'cc_library')",
                "cc_library(name = 'b', srcs = ['source.cc'])"
            )

        Truth.assertThat(artifactsToStrings(getFilesToBuild(target)))
            .containsExactly("bin a/libb.a", "bin a/libb.ifso", "bin a/libb.so")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun addOnlyStaticLibraryToFilesToBuildWhenWrappingIffImplicitOutput() {
        // This shared library has the same name as the archive generated by this rule, so it should
        // override said archive. However, said archive should still be put in files to build.
        val target: ConfiguredTargetAndData =
            scratchConfiguredTargetAndData(
                "a",
                "b",
                "load('@rules_cc//cc:cc_library.bzl', 'cc_library')",
                "cc_library(name = 'b', srcs = ['libb.so'])"
            )

        Truth.assertThat(artifactsToStrings(getFilesToBuild(target.getConfiguredTarget()))).isEmpty()
    }

    // Returns the libraries to link from the CcLinkingContext of the given target, excluding
    // toolchain runtimes.
    @Throws(java.lang.Exception::class)
    private fun librariesToLinkExcludingCxxRuntimes(target: ConfiguredTarget?): com.google.common.collect.ImmutableList<LibraryToLink> {
        return CcInfo.get(target).getCcLinkingContext().getLibraries().toList().stream()
            .filter(
                { x ->
                    // A LibraryToLink object doesn't have a path we can check.
                    // We arbitrarily use its static library field to check against third_party/stl.
                    val staticLibrary: Artifact? = x.getStaticLibrary()
                    staticLibrary == null
                            || !staticLibrary
                        .getRootRelativePath()
                        .startsWith(PathFragment.create("third_party/stl"))
                })
            .collect(com.google.common.collect.ImmutableList.toImmutableList<E?>())
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun addStaticLibraryToStaticSharedLinkParamsWhenBuilding() {
        val target: ConfiguredTarget =
            scratchConfiguredTarget(
                "a",
                "foo",
                "load('@rules_cc//cc:cc_library.bzl', 'cc_library')",
                "cc_library(name = 'foo', srcs = ['foo.cc'])"
            )

        val libraries: com.google.common.collect.ImmutableList<LibraryToLink> =
            librariesToLinkExcludingCxxRuntimes(target)
        Truth.assertThat(libraries).hasSize(1)
        val library: LibraryToLink = libraries.get(0)
        var libraryToUse: Artifact? = library.getPicStaticLibrary()
        if (libraryToUse == null) {
            // We may get either a static library or pic static library depending on platform.
            libraryToUse = library.getStaticLibrary()
        }
        assertThat(libraryToUse).isNotNull()
        Truth.assertThat(artifactsToStrings(com.google.common.collect.ImmutableList.of<Artifact>(libraryToUse)))
            .contains("bin a/libfoo.a")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun dontAddStaticLibraryToStaticSharedLinkParamsWhenWrappingSameLibraryIdentifier() {
        val target: ConfiguredTarget =
            scratchConfiguredTarget(
                "a",
                "foo",
                "load('@rules_cc//cc:cc_library.bzl', 'cc_library')",
                "cc_library(name = 'foo', srcs = ['libfoo.so'])"
            )

        val libraries: com.google.common.collect.ImmutableList<LibraryToLink> =
            librariesToLinkExcludingCxxRuntimes(target)
        Truth.assertThat(libraries).hasSize(1)
        val library: LibraryToLink = libraries.get(0)
        assertThat(library.getStaticLibrary()).isNull()
        Truth.assertThat(artifactsToStrings(com.google.common.collect.ImmutableList.of<E?>(library.getResolvedSymlinkDynamicLibrary())))
            .contains("src a/libfoo.so")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun onlyAddOneWrappedLibraryWithSameLibraryIdentifierToLibraries() {
        val target: ConfiguredTarget =
            scratchConfiguredTarget(
                "a",
                "foo",
                "load('@rules_cc//cc:cc_library.bzl', 'cc_library')",
                "cc_library(name = 'foo', srcs = ['libfoo.lo', 'libfoo.so'])"
            )

        Truth.assertThat(librariesToLinkExcludingCxxRuntimes(target)).hasSize(1)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCcLinkParamsHasDynamicLibrariesForRuntime() {
        AnalysisMock.get()
            .ccSupport()
            .setupCcToolchainConfig(
                mockToolsConfig,
                CcToolchainConfig.builder()
                    .withFeatures(
                        CppRuleClasses.COPY_DYNAMIC_LIBRARIES_TO_BINARY,
                        CppRuleClasses.SUPPORTS_DYNAMIC_LINKER
                    )
            )
        useConfiguration(
            "--platforms=" + TestConstants.PLATFORM_LABEL,
            "--features=copy_dynamic_libraries_to_binary"
        )
        val target: ConfiguredTarget =
            scratchConfiguredTarget(
                "a",
                "foo",
                "load('@rules_cc//cc:cc_library.bzl', 'cc_library')",
                "cc_library(name = 'foo', srcs = ['foo.cc'])"
            )
        val libraries: Iterable<Artifact?> =
            CcInfo.get(target)
                .getCcLinkingContext()
                .getDynamicLibrariesForRuntime( /* linkingStatically= */false)
        Truth.assertThat(artifactsToStrings(libraries)).doesNotContain("bin a/libfoo.ifso")
        Truth.assertThat(artifactsToStrings(libraries)).contains("bin a/libfoo.so")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCcLinkParamsHasDynamicLibrariesForRuntimeWithoutCopyFeature() {
        AnalysisMock.get()
            .ccSupport()
            .setupCcToolchainConfig(
                mockToolsConfig,
                CcToolchainConfig.builder().withFeatures(CppRuleClasses.SUPPORTS_DYNAMIC_LINKER)
            )
        useConfiguration("--platforms=" + TestConstants.PLATFORM_LABEL)
        invalidatePackages()
        val target: ConfiguredTarget =
            scratchConfiguredTarget(
                "a",
                "foo",
                "load('@rules_cc//cc:cc_library.bzl', 'cc_library')",
                "cc_library(name = 'foo', srcs = ['foo.cc'])"
            )
        val libraries: Iterable<Artifact?> =
            CcInfo.get(target)
                .getCcLinkingContext()
                .getDynamicLibrariesForRuntime( /* linkingStatically= */false)
        Truth.assertThat(artifactsToStrings(libraries)).doesNotContain("bin _solib_k8/liba_Slibfoo.ifso")
        Truth.assertThat(artifactsToStrings(libraries)).contains("bin _solib_k8/liba_Slibfoo.so")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCcLinkParamsDoNotHaveDynamicLibrariesForRuntime() {
        useConfiguration("--platforms=" + TestConstants.PLATFORM_LABEL)
        val target: ConfiguredTarget =
            scratchConfiguredTarget(
                "a",
                "foo",
                "load('@rules_cc//cc:cc_library.bzl', 'cc_library')",
                "cc_library(name = 'foo', srcs = ['foo.cc'], linkstatic=1)"
            )
        val libraries: Iterable<Artifact?> =
            CcInfo.get(target)
                .getCcLinkingContext()
                .getDynamicLibrariesForRuntime( /* linkingStatically= */false)
        Truth.assertThat(artifactsToStrings(libraries)).isEmpty()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun forbidBuildingAndWrappingSameLibraryIdentifier() {
        useConfiguration("--platforms=" + TestConstants.PLATFORM_LABEL)
        checkError(
            "a",
            "foo",
            ("Can't put library with "
                    + "identifier 'a/libfoo' into the srcs of a cc_library with the same name (foo) which "
                    + "also contains other code or objects to link"),
            "load('@rules_cc//cc:cc_library.bzl', 'cc_library')",
            "cc_library(name = 'foo', srcs = ['foo.cc', 'libfoo.lo'])"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testProcessedHeadersWithPicSharedLibsAndNoPicBinaries() {
        AnalysisMock.get()
            .ccSupport()
            .setupCcToolchainConfig(
                mockToolsConfig,
                CcToolchainConfig.builder().withFeatures(CppRuleClasses.PARSE_HEADERS)
            )
        useConfiguration("--features=parse_headers", "-c", "opt")
        // Should not crash
        scratchConfiguredTarget(
            "a",
            "a",
            "load('@rules_cc//cc:cc_library.bzl', 'cc_library')",
            "cc_library(name='a', hdrs=['a.h'])"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAlwaysLinkAndDisableWholeArchiveError() {
        AnalysisMock.get()
            .ccSupport()
            .setupCcToolchainConfig(
                mockToolsConfig,
                CcToolchainConfig.builder()
                    .withFeatures("disable_whole_archive_for_static_lib_configuration")
            )

        useConfiguration("--features=disable_whole_archive_for_static_lib")
        // Should be fine.
        assertThat(
            scratchConfiguredTarget(
                "a",
                "a",
                "load('@rules_cc//cc:cc_library.bzl', 'cc_library')",
                "cc_library(name='a', hdrs=['a.h'], srcs=['a.cc'])"
            )
        )
            .isNotNull()
        // Should error out.
        reporter.removeHandler(failFastHandler)
        scratchConfiguredTarget(
            "b",
            "b",
            "load('@rules_cc//cc:cc_library.bzl', 'cc_library')",
            "cc_library(name='b', hdrs=['b.h'], srcs=['b.cc'], alwayslink=1)"
        )
        assertContainsEvent(
            "alwayslink should not be True for a target with the disable_whole_archive_for_static_lib"
                    + " feature enabled"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun checkWarningEmptyLibrary() {
        scratch.file(
            "a/BUILD",
            """
        package(features = ['header_modules'])
        load("@rules_cc//cc:cc_library.bzl", "cc_library")
        cc_library(
            name = 'foo',
            srcs = ['foo.o'],
        )
        
        """.trimIndent()
        )
        getConfiguredTarget("//a:foo")
        assertNoEvents()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testLinkerInputsHasRightLabels() {
        scratch.file(
            "foo/BUILD",
            """
        load("@rules_cc//cc:cc_library.bzl", "cc_library")
        cc_library(
            name = 'baz',
            srcs = ['baz.cc'],
        )
        cc_library(
            name = 'bar',
            srcs = ['bar.cc'],
            deps = [':baz'],
        )
        cc_library(
            name = 'foo',
            srcs = ['foo.cc'],
            deps = [':bar'],
        )
        
        """.trimIndent()
        )
        val target: ConfiguredTarget = getConfiguredTarget("//foo")
        assertThat(
            CcInfo.get(target).getCcLinkingContext().getLinkerInputs().toList().stream()
                .map({ x -> LinkerInput.getOwner(x).toString() })
                .filter({ x -> !x.startsWith("//third_party/stl") })
                .collect(com.google.common.collect.ImmutableList.toImmutableList<E?>())
        )
            .containsExactly("//foo:foo", "//foo:bar", "//foo:baz")
            .inOrder()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testPrecompiledFilesFromDifferentConfigs() {
        scratch.file(
            "foo/BUILD",
            """
        load("@rules_cc//cc:cc_library.bzl", "cc_library")
        load(':example_transition.bzl', 'transitioned_file')
        genrule(
           name = 'generated',
           outs = ['libbar.so'],
           cmd = 'echo foo > @',
        )
        transitioned_file(
           name = 'transitioned_libbar',
           src = 'generated',
        )
        cc_library(
           name = 'foo',
           srcs = [
               'generated',
               'transitioned_libbar',
           ],
        )
        
        """.trimIndent()
        )
        scratch.file(
            "foo/example_transition.bzl",
            """
        def _impl(settings, attr):
            _ignore = (settings, attr)
            return [
                {'//command_line_option:foo': 'foo'},
            ]
        cpu_transition = transition(
            implementation = _impl,
            inputs = [],
            outputs = ['//command_line_option:foo'],
        )
        def _transitioned_file_impl(ctx):
            return DefaultInfo(files = depset([ctx.file.src]))

        transitioned_file = rule(
            implementation = _transitioned_file_impl,
            attrs = {
                'src': attr.label(
                    allow_single_file = True,
                    cfg = cpu_transition,
                ),
            },
        )
        
        """.trimIndent()
        )
        scratch.overwriteFile(
            "tools/allowlists/function_transition_allowlist/BUILD",
            """
        package_group(
            name = 'function_transition_allowlist',
            packages = ['//...'],
        )
        filegroup(
            name = 'srcs',
            srcs = glob(['**']),
            visibility = ['//tools/allowlists:__pkg__'],
        )
        
        """.trimIndent()
        )
        checkError("//foo", "Trying to link twice")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testImplicitOutputsWhitelistOnWhitelist() {
        if (analysisMock.isThisBazel) {
            return
        }
        scratch.overwriteFile(
            "tools/build_defs/cc/whitelists/cc_lib_implicit_outputs/BUILD",
            """
        package_group(
            name = 'allowed_cc_lib_implicit_outputs',
            packages = ['//bar'])
        
        """.trimIndent()
        )

        scratch.file(
            "bar/BUILD",
            """
        load("@rules_cc//cc:cc_library.bzl", "cc_library")
        filegroup(
            name = 'allowed',
            srcs = [':liballowed_cc_lib.a'],
        )
        cc_library(
            name = 'allowed_cc_lib',
            srcs = ['allowed_cc_lib.cc'],
        )
        
        """.trimIndent()
        )
        getConfiguredTarget("//bar:allowed")
        assertNoEvents()
    }

    @Throws(java.lang.Exception::class)
    private fun prepareCustomTransition() {
        scratch.file(
            "transition/custom_transition.bzl",
            """
        load("@rules_cc//cc/common:cc_info.bzl", "CcInfo")
        load('@rules_cc//cc/common:cc_common.bzl', 'cc_common')
        def _custom_transition_impl(settings, attr):
            _ignore = settings, attr

            return {'//command_line_option:copt': ['-DFLAG']}

        custom_transition = transition(
            implementation = _custom_transition_impl,
            inputs = [],
            outputs = ['//command_line_option:copt'],
        )

        def _apply_custom_transition_impl(ctx):
            cc_infos = []
            for dep in ctx.attr.deps:
                cc_infos.append(dep[CcInfo])
            merged_cc_info = cc_common.merge_cc_infos(cc_infos = cc_infos)
            return merged_cc_info

        apply_custom_transition = rule(
            implementation = _apply_custom_transition_impl,
            attrs = {
                'deps': attr.label_list(cfg = custom_transition),
            },
        )
        
        """.trimIndent()
        )
        scratch.overwriteFile(
            "tools/allowlists/function_transition_allowlist/BUILD",
            """
        package_group(
            name = 'function_transition_allowlist',
            packages = ['//...'],
        )
        filegroup(
            name = 'srcs',
            srcs = glob(['**']),
            visibility = ['//tools/allowlists:__pkg__'],
        )
        
        """.trimIndent()
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDynamicLinkTwiceAfterTransition() {
        AnalysisMock.get()
            .ccSupport()
            .setupCcToolchainConfig(
                mockToolsConfig,
                CcToolchainConfig.builder()
                    .withFeatures(
                        CppRuleClasses.COPY_DYNAMIC_LIBRARIES_TO_BINARY,
                        CppRuleClasses.SUPPORTS_DYNAMIC_LINKER
                    )
            )

        prepareCustomTransition()

        scratch.file(
            "transition/BUILD",
            """
        load("@rules_cc//cc:cc_binary.bzl", "cc_binary")
        load("@rules_cc//cc:cc_library.bzl", "cc_library")
        load(':custom_transition.bzl', 'apply_custom_transition')
        cc_binary(
            name = 'main',
            srcs = ['main.cc'],
            linkstatic = 0,
            deps = [
                'dep1',
                'dep2',
            ],
        )

        apply_custom_transition(
            name = 'dep1',
            deps = [
                ':dep2',
            ],
        )

        cc_library(
            name = 'dep2',
            srcs = ['test.cc'],
            hdrs = ['test.h'],
        )
        
        """.trimIndent()
        )

        checkError("//transition:main", "built in a different configuration")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDynamicLinkUniqueAfterTransition() {
        AnalysisMock.get()
            .ccSupport()
            .setupCcToolchainConfig(
                mockToolsConfig,
                CcToolchainConfig.builder()
                    .withFeatures(
                        CppRuleClasses.COPY_DYNAMIC_LIBRARIES_TO_BINARY,
                        CppRuleClasses.SUPPORTS_DYNAMIC_LINKER
                    )
            )

        prepareCustomTransition()

        scratch.file(
            "transition/BUILD",
            """
        load("@rules_cc//cc:cc_binary.bzl", "cc_binary")
        load("@rules_cc//cc:cc_library.bzl", "cc_library")
        load(':custom_transition.bzl', 'apply_custom_transition')
        cc_binary(
            name = 'main',
            srcs = ['main.cc'],
            linkstatic = 0,
            deps = [
                'dep1',
                'dep3',
            ],
        )
        apply_custom_transition(
            name = 'dep1',
            deps = [
                ':dep2',
            ],
        )
        cc_library(
            name = 'dep2',
            srcs = ['test.cc'],
            hdrs = ['test.h'],
        )
        cc_library(
            name = 'dep3',
            srcs = ['other_test.cc'],
            hdrs = ['other_test.h'],
        )
        
        """.trimIndent()
        )

        getConfiguredTarget("//transition:main")
        assertNoEvents()
    }

    // b/162180592
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSameSymlinkedLibraryDoesNotGiveDuplicateError() {
        AnalysisMock.get()
            .ccSupport()
            .setupCcToolchainConfig(
                mockToolsConfig,
                CcToolchainConfig.builder()
                    .withFeatures(
                        CppRuleClasses.COPY_DYNAMIC_LIBRARIES_TO_BINARY,
                        CppRuleClasses.SUPPORTS_DYNAMIC_LINKER
                    )
            )

        scratch.file(
            "transition/BUILD",
            """
        load("@rules_cc//cc:cc_binary.bzl", "cc_binary")
        load("@rules_cc//cc:cc_library.bzl", "cc_library")
        cc_binary(
            name = 'main',
            srcs = ['main.cc'],
            deps = [
                'dep1',
                'dep2',
            ],
        )
        cc_binary(
            name = 'libshared.so',
            srcs = ['shared.cc'],
            linkshared = 1,
        )
        cc_library(
            name = 'dep1',
            srcs = ['test.cc', 'libshared.so'],
            hdrs = ['test.h'],
        )
        cc_library(
            name = 'dep2',
            srcs = ['other_test.cc', 'libshared.so'],
            hdrs = ['other_test.h'],
        )
        
        """.trimIndent()
        )

        getConfiguredTarget("//transition:main")
        assertNoEvents()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testImplementationDepsCompilationContextIsNotPropagated() {
        scratch.file(
            "foo/BUILD",
            """
        load("@rules_cc//cc:cc_binary.bzl", "cc_binary")
        load("@rules_cc//cc:cc_library.bzl", "cc_library")
        cc_binary(
            name = 'bin',
            srcs = ['bin.cc'],
            deps = ['lib'],
        )
        cc_library(
            name = 'lib',
            srcs = ['lib.cc'],
            deps = ['public_dep'],
        )
        cc_library(
            name = 'public_dep',
            srcs = ['public_dep.cc'],
            includes = ['public_dep'],
            hdrs = ['public_dep.h'],
            implementation_deps = ['implementation_dep'],
            deps = ['interface_dep'],
        )
        cc_library(
            name = 'interface_dep',
            srcs = ['interface_dep.cc'],
            includes = ['interface_dep'],
            hdrs = ['interface_dep.h'],
        )
        cc_library(
            name = 'implementation_dep',
            srcs = ['implementation_dep.cc'],
            includes = ['implementation_dep'],
            hdrs = ['implementation_dep.h'],
        )
        
        """.trimIndent()
        )

        val libCompilationContext: CcCompilationContext =
            getCppCompileAction("//foo:lib").getCcCompilationContext()
        Truth.assertThat(artifactsToStrings(libCompilationContext.getDeclaredIncludeSrcs()))
            .contains("src foo/public_dep.h")
        Truth.assertThat(artifactsToStrings(libCompilationContext.getDeclaredIncludeSrcs()))
            .contains("src foo/interface_dep.h")
        Truth.assertThat(artifactsToStrings(libCompilationContext.getDeclaredIncludeSrcs()))
            .doesNotContain("src foo/implementation_dep.h")

        com.google.common.truth.Subject.contains("foo/public_dep")
        com.google.common.truth.Subject.contains("foo/interface_dep")
        assertThat(pathfragmentsToStrings(libCompilationContext.getIncludeDirs()))
            .doesNotContain("foo/implementation_dep")
        assertThat(pathfragmentsToStrings(libCompilationContext.getSystemIncludeDirs()))
            .doesNotContain("foo/implementation_dep")

        val publicDepCompilationContext: CcCompilationContext =
            getCppCompileAction("//foo:public_dep").getCcCompilationContext()
        Truth.assertThat(artifactsToStrings(publicDepCompilationContext.getDeclaredIncludeSrcs()))
            .contains("src foo/interface_dep.h")
        com.google.common.truth.Subject.contains("foo/interface_dep")
        Truth.assertThat(artifactsToStrings(publicDepCompilationContext.getDeclaredIncludeSrcs()))
            .contains("src foo/implementation_dep.h")
        com.google.common.truth.Subject.contains("foo/implementation_dep")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testImplementationDepsLinkingContextIsPropagated() {
        scratch.file(
            "foo/BUILD",
            """
        load("@rules_cc//cc:cc_binary.bzl", "cc_binary")
        load("@rules_cc//cc:cc_library.bzl", "cc_library")
        cc_binary(
            name = 'bin',
            srcs = ['bin.cc'],
            deps = ['lib'],
        )
        cc_library(
            name = 'lib',
            srcs = ['lib.cc'],
            deps = ['public_dep'],
        )
        cc_library(
            name = 'public_dep',
            srcs = ['public_dep.cc'],
            hdrs = ['public_dep.h'],
            implementation_deps = ['implementation_dep'],
            deps = ['interface_dep'],
        )
        cc_library(
            name = 'interface_dep',
            srcs = ['interface_dep.cc'],
            hdrs = ['interface_dep.h'],
        )
        cc_library(
            name = 'implementation_dep',
            srcs = ['implementation_dep.cc'],
            hdrs = ['implementation_dep.h'],
        )
        
        """.trimIndent()
        )

        val lib: ConfiguredTarget = getConfiguredTarget("//foo:lib")
        Truth.assertThat(
            artifactsToStrings(
                CcInfo.get(lib).getCcLinkingContext().getStaticModeParamsForExecutableLibraries()
            )
        )
            .contains("bin foo/libpublic_dep.a")
        Truth.assertThat(
            artifactsToStrings(
                CcInfo.get(lib).getCcLinkingContext().getStaticModeParamsForExecutableLibraries()
            )
        )
            .contains("bin foo/libimplementation_dep.a")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testImplementationDepsDebugContextIsPropagated() {
        useConfiguration(
            "--fission=yes",
            "--features=per_object_debug_info"
        )
        scratch.file(
            "foo/BUILD",
            """
        load("@rules_cc//cc:cc_binary.bzl", "cc_binary")
        load("@rules_cc//cc:cc_library.bzl", "cc_library")
        cc_binary(
            name = 'bin',
            srcs = ['bin.cc'],
            deps = ['lib'],
        )
        cc_library(
            name = 'lib',
            srcs = ['lib.cc'],
            deps = ['public_dep'],
        )
        cc_library(
            name = 'public_dep',
            srcs = ['public_dep.cc'],
            hdrs = ['public_dep.h'],
            implementation_deps = ['implementation_dep'],
            deps = ['interface_dep'],
        )
        cc_library(
            name = 'interface_dep',
            srcs = ['interface_dep.cc'],
            hdrs = ['interface_dep.h'],
        )
        cc_library(
            name = 'implementation_dep',
            srcs = ['implementation_dep.cc'],
            hdrs = ['implementation_dep.h'],
        )
        
        """.trimIndent()
        )

        val lib: ConfiguredTarget = getConfiguredTarget("//foo:lib")
        com.google.common.truth.Subject.contains("public_dep.dwo")
        com.google.common.truth.Subject.contains("implementation_dep.dwo")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testImplementationDepsRunfilesArePropagated() {
        scratch.file(
            "foo/BUILD",
            """
        load("@rules_cc//cc:cc_binary.bzl", "cc_binary")
        load("@rules_cc//cc:cc_library.bzl", "cc_library")
        cc_binary(
            name = 'bin',
            srcs = ['bin.cc'],
            deps = ['lib'],
        )
        cc_library(
            name = 'lib',
            srcs = ['lib.cc'],
            deps = ['public_dep'],
        )
        cc_library(
            name = 'public_dep',
            srcs = ['public_dep.cc'],
            hdrs = ['public_dep.h'],
            implementation_deps = ['implementation_dep'],
            deps = ['interface_dep'],
        )
        cc_library(
            name = 'interface_dep',
            data = ['data/interface.txt'],
        )
        cc_library(
            name = 'implementation_dep',
            data = ['data/implementation.txt'],
        )
        
        """.trimIndent()
        )

        val lib: ConfiguredTarget = getConfiguredTarget("//foo:bin")
        Truth.assertThat(
            artifactsToStrings(
                lib.get(DefaultInfo.PROVIDER).getDefaultRunfiles().getAllArtifacts()
            )
        )
            .containsAtLeast("src foo/data/interface.txt", "src foo/data/implementation.txt")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testImplementationDepsConfigurationHostSucceeds() {
        scratch.file(
            "foo/BUILD",
            """
        load("@rules_cc//cc:cc_library.bzl", "cc_library")
        cc_library(
            name = 'public_dep',
            srcs = ['public_dep.cc'],
            hdrs = ['public_dep.h'],
            implementation_deps = ['implementation_dep'],
        )
        cc_library(
            name = 'implementation_dep',
            srcs = ['implementation_dep.cc'],
            hdrs = ['implementation_dep.h'],
        )
        
        """.trimIndent()
        )

        assertThat(getExecConfiguredTarget("//foo:public_dep")).isNotNull()

        assertDoesNotContainEvent("requires --experimental_cc_implementation_deps")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testImplementationDepsSucceedsWithoutFlag() {
        if (!analysisMock.isThisBazel) {
            return
        }
        scratch.file(
            "foo/BUILD",
            """
        load("@rules_cc//cc:cc_library.bzl", "cc_library")
        cc_library(
            name = 'lib',
            srcs = ['lib.cc'],
            implementation_deps = ['implementation_dep'],
        )
        cc_library(
            name = 'implementation_dep',
            srcs = ['implementation_dep.cc'],
            hdrs = ['implementation_dep.h'],
        )
        
        """.trimIndent()
        )
        assertThat(getConfiguredTarget("//foo:lib")).isNotNull()

        assertDoesNotContainEvent("requires --experimental_cc_implementation_deps")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testImplementationDepsNotInAllowlistThrowsError() {
        if (analysisMock.isThisBazel) {
            // In OSS usage is controlled only by a flag and not an allowlist.
            return
        }
        scratch.overwriteFile(
            "tools/build_defs/cc/whitelists/implementation_deps/BUILD",
            """
        package_group(
            name = 'cc_library_implementation_deps_attr_allowed',
            packages = []
        )
        
        """.trimIndent()
        )
        scratch.file(
            "foo/BUILD",
            """
        load("@rules_cc//cc:cc_library.bzl", "cc_library")
        cc_library(
            name = 'lib',
            srcs = ['lib.cc'],
            implementation_deps = ['implementation_dep'],
        )
        cc_library(
            name = 'implementation_dep',
            srcs = ['implementation_dep.cc'],
            hdrs = ['implementation_dep.h'],
        )
        
        """.trimIndent()
        )
        reporter.removeHandler(failFastHandler)
        getConfiguredTarget("//foo:lib")
        assertContainsEvent("Only targets in the following allowlist")
    }


    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRpathIsNotAddedWhenThereAreNoSoDeps() {
        AnalysisMock.get()
            .ccSupport()
            .setupCcToolchainConfig(
                mockToolsConfig,
                CcToolchainConfig.builder().withFeatures(CppRuleClasses.SUPPORTS_DYNAMIC_LINKER)
            )

        prepareCustomTransition()

        scratch.file(
            "BUILD",
            "load('@rules_cc//cc:cc_binary.bzl', 'cc_binary')",
            "load('@rules_cc//cc:cc_library.bzl', 'cc_library')",
            "cc_library(",
            "    name = 'malloc',",
            "    srcs = ['malloc.cc'],",
            "    linkstatic = 1,",
            ")",
            "cc_library(name = 'empty_lib')",
            "cc_binary(",
            "    name = 'main',",
            "    srcs = ['main.cc'],",
            "    malloc = ':malloc',",
            "    link_extra_lib = ':empty_lib',",
            "    linkstatic = 0,",
            ")"
        )

        val main: ConfiguredTarget = getConfiguredTarget("//:main")
        val mainBin: Artifact = getBinArtifact("main", main)
        val action: SpawnAction = getGeneratingAction(mainBin) as SpawnAction
        Truth.assertThat(com.google.common.base.Joiner.on(" ").join(action.getArguments()))
            .doesNotContain("-Xlinker -rpath")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRpathAndLinkPathsWithoutTransitions() {
        AnalysisMock.get()
            .ccSupport()
            .setupCcToolchainConfig(
                mockToolsConfig,
                CcToolchainConfig.builder().withFeatures(CppRuleClasses.SUPPORTS_DYNAMIC_LINKER)
            )

        prepareCustomTransition()
        useConfiguration(
            "--platforms=" + TestConstants.PLATFORM_LABEL,
            "--compilation_mode=fastbuild",
            "--experimental_platform_in_output_dir",
            String.format(
                "--experimental_override_name_platform_in_output_dir=%s=k8",
                TestConstants.PLATFORM_LABEL
            )
        )

        scratch.file(
            "no-transition/BUILD",
            """
        load("@rules_cc//cc:cc_binary.bzl", "cc_binary")
        load("@rules_cc//cc:cc_library.bzl", "cc_library")
        cc_binary(
            name = 'main',
            srcs = ['main.cc'],
            linkstatic = 0,
            deps = ['dep1'],
        )

        cc_library(
            name = 'dep1',
            srcs = ['test.cc'],
            hdrs = ['test.h'],
        )
        
        """.trimIndent()
        )

        val main: ConfiguredTarget = getConfiguredTarget("//no-transition:main")
        val mainBin: Artifact = getBinArtifact("main", main)
        val action: SpawnAction = getGeneratingAction(mainBin) as SpawnAction
        val linkArgv: MutableList<String>? = action.getArguments()
        Truth.assertThat(linkArgv)
            .containsAtLeast("-Xlinker", "-rpath", "-Xlinker", "\$ORIGIN/../_solib_k8/")
            .inOrder()
        Truth.assertThat(linkArgv)
            .containsAtLeast(
                "-Xlinker",
                "-rpath",
                "-Xlinker",
                "\$ORIGIN/main.runfiles/" + ruleClassProvider.getRunfilesPrefix() + "/_solib_k8/"
            )
            .inOrder()
        Truth.assertThat(linkArgv)
            .contains("-L" + TestConstants.PRODUCT_NAME + "-out/k8-fastbuild/bin/_solib_k8")
        Truth.assertThat(linkArgv).contains("-lno-transition_Slibdep1")
        Truth.assertThat(com.google.common.base.Joiner.on(" ").join(linkArgv))
            .doesNotContain("-Xlinker -rpath -Xlinker \$ORIGIN/../_solib_k8/../../../k8-fastbuild-ST-")
        Truth.assertThat(com.google.common.base.Joiner.on(" ").join(linkArgv))
            .doesNotContain("-L" + TestConstants.PRODUCT_NAME + "-out/k8-fastbuild-ST-")
        Truth.assertThat(com.google.common.base.Joiner.on(" ").join(linkArgv)).doesNotContain("-lST-")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRpathRootIsAddedEvenWithTransitionedDepsOnly() {
        AnalysisMock.get()
            .ccSupport()
            .setupCcToolchainConfig(
                mockToolsConfig,
                CcToolchainConfig.builder().withFeatures(CppRuleClasses.SUPPORTS_DYNAMIC_LINKER)
            )

        prepareCustomTransition()
        useConfiguration(
            "--platforms=" + TestConstants.PLATFORM_LABEL,
            "--compilation_mode=fastbuild",
            "--experimental_platform_in_output_dir",
            String.format(
                "--experimental_override_name_platform_in_output_dir=%s=k8",
                TestConstants.PLATFORM_LABEL
            )
        )

        scratch.file(
            "transition/BUILD",
            """
        load("@rules_cc//cc:cc_binary.bzl", "cc_binary")
        load("@rules_cc//cc:cc_library.bzl", "cc_library")
        load(':custom_transition.bzl', 'apply_custom_transition')
        cc_library(
            name = 'malloc',
            srcs = ['malloc.cc'],
            linkstatic = 1,
        )
        cc_library(name = 'empty_lib')
        cc_binary(
            name = 'main',
            srcs = ['main.cc'],
            linkstatic = 0,
            malloc = ':malloc',
            link_extra_lib = ':empty_lib',
            deps = ['dep1'],
        )

        apply_custom_transition(
            name = 'dep1',
            deps = [
                ':dep2',':dep3',
            ],
        )

        cc_library(
            name = 'dep2',
            srcs = ['test.cc'],
            hdrs = ['test.h'],
        )
        cc_library(
            name = 'dep3',
            srcs = ['test3.cc'],
            hdrs = ['test3.h'],
        )
        
        """.trimIndent()
        )

        val main: ConfiguredTarget = getConfiguredTarget("//transition:main")
        val mainBin: Artifact = getBinArtifact("main", main)
        val action: SpawnAction = getGeneratingAction(mainBin) as SpawnAction
        val linkArgv: MutableList<String> = action.getArguments()
        Truth.assertThat(linkArgv)
            .containsAtLeast("-Xlinker", "-rpath", "-Xlinker", "\$ORIGIN/../_solib_k8/")
            .inOrder()
        Truth.assertThat(linkArgv)
            .containsAtLeast(
                "-Xlinker",
                "-rpath",
                "-Xlinker",
                "\$ORIGIN/main.runfiles/" + ruleClassProvider.getRunfilesPrefix() + "/_solib_k8/"
            )
            .inOrder()
        Truth.assertThat(com.google.common.base.Joiner.on(" ").join(linkArgv))
            .contains("-Xlinker -rpath -Xlinker \$ORIGIN/../../../k8-fastbuild-ST-")
        Truth.assertThat(com.google.common.base.Joiner.on(" ").join(linkArgv))
            .contains("-L" + TestConstants.PRODUCT_NAME + "-out/k8-fastbuild-ST-")
        Truth.assertThat(com.google.common.base.Joiner.on(" ").join(linkArgv))
            .containsMatch("-lST-[0-9a-f]+_transition_Slibdep2")
        Truth.assertThat(com.google.common.base.Joiner.on(" ").join(linkArgv))
            .doesNotContain("-L" + TestConstants.PRODUCT_NAME + "-out/k8-fastbuild/bin/_solib_k8")
        Truth.assertThat(com.google.common.base.Joiner.on(" ").join(linkArgv)).doesNotContain("-ltransition_Slibdep2")
    }

    /**
     * Due to Windows forcing every dynamic library to link its dependencies, the
     * NODEPS_DYNAMIC_LIBRARY link target type actually does link in its transitive dependencies
     * statically on Windows. There is no reason why these cc_libraries should be link stamped.
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testWindowsCcLibrariesNoDepsDynamicLibrariesDoNotLinkstamp() {
        scratch.overwriteFile(
            "hello/BUILD",
            """
        load("@rules_cc//cc:cc_library.bzl", "cc_library")
        cc_library(
          name = 'hello',
          srcs = ['hello.cc'],
          deps = ['linkstamp']
        )
        cc_library(
          name = 'linkstamp',
          linkstamp = 'linkstamp.cc',
        )
        
        """.trimIndent()
        )
        AnalysisMock.get()
            .ccSupport()
            .setupCcToolchainConfig(
                mockToolsConfig,
                CcToolchainConfig.builder()
                    .withFeatures(
                        CppRuleClasses.SUPPORTS_DYNAMIC_LINKER,
                        CppRuleClasses.TARGETS_WINDOWS,
                        CppRuleClasses.COPY_DYNAMIC_LIBRARIES_TO_BINARY
                    )
            )
        val hello: ConfiguredTarget = getConfiguredTarget("//hello:hello")
        val sharedObject: Artifact =
            LinkerInput.getLibraries(
                CcInfo.get(hello).getCcLinkingContext().getLinkerInputs().toList().get(0)
            )
                .get(0)
                .getDynamicLibrary()
        val action: SpawnAction = getGeneratingAction(sharedObject) as SpawnAction
        Truth.assertThat(artifactsToStrings(action.getInputs()))
            .doesNotContain("bin hello/_objs/bin/hello/linkstamp.o")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testReallyLongSolibLink() {
        AnalysisMock.get()
            .ccSupport()
            .setupCcToolchainConfig(
                mockToolsConfig,
                CcToolchainConfig.builder().withFeatures(CppRuleClasses.SUPPORTS_DYNAMIC_LINKER)
            )

        val longpath =
            ("this/is/a/really/really/really/really/really/really/really/really/really/really/"
                    + "really/really/really/really/really/really/really/really/really/really/really/"
                    + "really/really/long/path/that/generates/really/long/solib/link/file")
        scratch.file(
            longpath + "/BUILD",
            "load('@rules_cc//cc:cc_library.bzl', 'cc_library')",
            "cc_library(",
            "    name = 'lib',",
            "    srcs = ['lib.cc'],",
            "    linkstatic = 0,",
            ")"
        )

        val lib: ConfiguredTarget = getConfiguredTarget("//" + longpath + ":lib")
        val libraries: MutableList<Artifact?>? =
            CcInfo.get(lib)
                .getCcLinkingContext()
                .getDynamicLibrariesForRuntime( /* linkingStatically= */false)
        val libraryBaseNames: MutableList<String> = ActionsTestUtil.baseArtifactNames(libraries)
        for (baseName in libraryBaseNames) {
            Truth.assertThat(baseName.length).isLessThan(MAX_FILENAME_LENGTH + 1)
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testLinkerInputAlwaysAddedEvenIfEmpty() {
        AnalysisMock.get().ccSupport().setupCcToolchainConfig(mockToolsConfig)
        scratch.file(
            "foo/BUILD",
            """
        load("@rules_cc//cc:cc_library.bzl", "cc_library")
        cc_library(
            name = 'lib',
        )
        
        """.trimIndent()
        )
        assertThat(
            CcInfo.get(getConfiguredTarget("//foo:lib"))
                .getCcLinkingContext()
                .getLinkerInputs()
                .toList()
                .stream()
                .map({ x -> LinkerInput.getOwner(x).toString() })
                .filter({ x -> !x.startsWith("//third_party/stl") })
        )
            .containsExactly("//foo:lib")
            .inOrder()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDataDepRunfilesArePropagated() {
        AnalysisMock.get().ccSupport().setupCcToolchainConfig(mockToolsConfig)
        scratch.file(
            "foo/data_dep.bzl",
            """
        def _my_data_dep_impl(ctx):
            return [
               DefaultInfo(
                runfiles = ctx.runfiles(
                     root_symlinks = { ctx.attr.dst: ctx.files.src[0] },
               ),
             )
           ]
        my_data_dep = rule(
           implementation = _my_data_dep_impl,
           attrs = {
             'src': attr.label(mandatory = True, allow_single_file = True),
             'dst': attr.string(mandatory = True),
           },
         )
        
        """.trimIndent()
        )
        scratch.file(
            "foo/BUILD",
            """
        load("@rules_cc//cc:cc_library.bzl", "cc_library")
        load(':data_dep.bzl', 'my_data_dep')
        my_data_dep(
            name = 'data_dep',
            src = ':file.txt',
            dst = 'data/file.txt',
        )
        cc_library(
            name = 'lib',
            data = [':data_dep'],
        )
        
        """.trimIndent()
        )

        val lib: ConfiguredTarget = getConfiguredTarget("//foo:lib")
        Truth.assertThat(
            artifactsToStrings(
                lib.get(DefaultInfo.PROVIDER).getDefaultRunfiles().getAllArtifacts()
            )
        )
            .containsExactly("src foo/file.txt")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAdditionalCompilerInputsArePassedToCompile() {
        AnalysisMock.get().ccSupport().setupCcToolchainConfig(mockToolsConfig)
        scratch.file(
            "foo/BUILD",
            """
        load("@rules_cc//cc:cc_library.bzl", "cc_library")
        cc_library(
            name = 'foo',
            srcs = ['hello.cc'],
            copts = ['${'$'}(location compiler_input.txt)'],
            additional_compiler_inputs = ['compiler_input.txt'],
        )
        
        """.trimIndent()
        )
        scratch.file("foo/compiler_input.txt", "hello world!")

        val lib: ConfiguredTarget = getConfiguredTarget("//foo:foo")
        val artifact: Artifact = getBinArtifact("_objs/foo/hello.o", lib)
        val action: CppCompileAction = getGeneratingAction(artifact) as CppCompileAction
        com.google.common.truth.Subject.contains(getSourceArtifact("foo/compiler_input.txt"))
        com.google.common.truth.Subject.contains("foo/compiler_input.txt")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAdditionalCompilerInputsArePassedToCompileFromLocalDefines() {
        AnalysisMock.get().ccSupport().setupCcToolchainConfig(mockToolsConfig)
        scratch.file(
            "foo/BUILD",
            """
        load("@rules_cc//cc:cc_library.bzl", "cc_library")
        cc_library(
            name = 'foo',
            srcs = ['hello.cc'],
            local_defines = ['FOO=${'$'}(location compiler_input.txt)'],
            additional_compiler_inputs = ['compiler_input.txt'],
        )
        
        """.trimIndent()
        )
        scratch.file("foo/compiler_input.txt", "hello world!")

        val lib: ConfiguredTarget = getConfiguredTarget("//foo:foo")
        val artifact: Artifact = getBinArtifact("_objs/foo/hello.o", lib)
        val action: CppCompileAction = getGeneratingAction(artifact) as CppCompileAction
        com.google.common.truth.Subject.contains(getSourceArtifact("foo/compiler_input.txt"))
        com.google.common.truth.Subject.contains("-DFOO=foo/compiler_input.txt")
    }

    companion object {
        private val STL_CPPMAP: PathFragment? = PathFragment.create("stl_cc_library.cppmap")
        private val CROSSTOOL_CPPMAP: PathFragment? = PathFragment.create("crosstool.cppmap")

        /** Returns the non-system module maps in `input`.  */
        private fun getNonSystemModuleMaps(input: NestedSet<Artifact?>): Iterable<Artifact> {
            return com.google.common.collect.Iterables.filter<T?>(
                input.toList(),
                com.google.common.base.Predicate { a: T? ->
                    val path: PathFragment = a.getExecPath()
                    CppFileTypes.CPP_MODULE_MAP.matches(path)
                            && !path.endsWith(STL_CPPMAP) && !path.endsWith(CROSSTOOL_CPPMAP)
                })
        }

        /** Returns the header module artifacts in `input`.  */
        private fun getHeaderModules(input: NestedSet<Artifact?>): Iterable<Artifact> {
            return com.google.common.collect.Iterables.filter<T?>(
                input.toList(),
                com.google.common.base.Predicate { artifact: T? -> CppFileTypes.CPP_MODULE.matches(artifact.getExecPath()) })
        }

        // cc_toolchain_config.bzl provides "dbg", "fastbuild" and "opt" feature when
        // compilation_mode_features are requested.
        private const val COMPILATION_MODE_FEATURES = "compilation_mode_features"
    }
}
