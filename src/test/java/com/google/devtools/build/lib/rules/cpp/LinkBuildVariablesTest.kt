// Copyright 2016 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.actions.Action

/** Tests that C++ linking action is populated with the correct build variables.  */
@RunWith(JUnit4::class)
class LinkBuildVariablesTest : LinkBuildVariablesTestCase() {
    @Before
    @Throws(IOException::class)
    fun createFooFooCcLibraryForRuleContext() {
        scratch.file(
            "foo/BUILD",
            "load('@rules_cc//cc:cc_library.bzl', 'cc_library')",
            "cc_library(name = 'foo')"
        )
    }

    @org.junit.Test
    fun testIsUsingFissionIsIdenticalForCompileAndLink() {
        Truth.assertThat(LinkBuildVariables.IS_USING_FISSION.getVariableName())
            .isEqualTo(CompileBuildVariables.IS_USING_FISSION.variableName)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testForcePicBuildVariable() {
        AnalysisMock.get()
            .ccSupport()
            .setupCcToolchainConfig(
                mockToolsConfig, CcToolchainConfig.builder().withFeatures(CppRuleClasses.SUPPORTS_PIC)
            )
        useConfiguration("--force_pic")
        scratch.file(
            "x/BUILD",
            "load('@rules_cc//cc:cc_binary.bzl', 'cc_binary')",
            "cc_binary(name = 'bin', srcs = ['a.cc'])"
        )
        scratch.file("x/a.cc")

        val target: ConfiguredTarget = getConfiguredTarget("//x:bin")
        val variables: CcToolchainVariables = getLinkBuildVariables(target, Link.LinkTargetType.EXECUTABLE)
        val variableValue: String? =
            getVariableValue(variables, LinkBuildVariables.FORCE_PIC.getVariableName())
        Truth.assertThat(variableValue).contains("")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testLibrariesToLinkAreExported() {
        AnalysisMock.get()
            .ccSupport()
            .setupCcToolchainConfig(
                mockToolsConfig,
                CcToolchainConfig.builder().withFeatures(CppRuleClasses.SUPPORTS_DYNAMIC_LINKER)
            )
        useConfiguration()

        scratch.file(
            "x/BUILD",
            "load('@rules_cc//cc:cc_library.bzl', 'cc_library')",
            "cc_library(name = 'foo', srcs = ['a.cc'])"
        )
        scratch.file("x/a.cc")

        val target: ConfiguredTarget = getConfiguredTarget("//x:foo")
        val variables: CcToolchainVariables =
            getLinkBuildVariables(target, LinkTargetType.NODEPS_DYNAMIC_LIBRARY)
        val librariesToLinkSequence: VariableValue? =
            variables.getVariable(
                LinkBuildVariables.LIBRARIES_TO_LINK.getVariableName(), PathMapper.NOOP
            )
        assertThat(librariesToLinkSequence).isNotNull()
        val librariesToLink: Iterable<out VariableValue?>? =
            CcToolchainVariables.getSequenceValue(
                LinkBuildVariables.LIBRARIES_TO_LINK.getVariableName(), librariesToLinkSequence
            )
        Truth.assertThat(librariesToLink).hasSize(1)
        val nameValue: VariableValue =
            librariesToLink!!
                .iterator()
                .next()
                .getFieldValue(LinkBuildVariables.LIBRARIES_TO_LINK.getVariableName(), "name")
        assertThat(nameValue).isNotNull()
        val name: String? = nameValue.getStringValue("name", PathMapper.NOOP)
        Truth.assertThat(name).matches(".*a\\..*o")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testLibrarySearchDirectoriesAreExported() {
        useConfiguration()

        scratch.file(
            "x/BUILD",
            "load('@rules_cc//cc:cc_binary.bzl', 'cc_binary')",
            "cc_binary(name = 'bin', srcs = ['some-dir/bar.so'])"
        )
        scratch.file("x/some-dir/bar.so")

        val target: ConfiguredTarget = getConfiguredTarget("//x:bin")
        val variables: CcToolchainVariables = getLinkBuildVariables(target, Link.LinkTargetType.EXECUTABLE)
        assertThat(
            variables
                .getVariable(
                    LinkBuildVariables.LIBRARY_SEARCH_DIRECTORIES.getVariableName(),
                    PathMapper.NOOP
                ).isTruthy
        )
            .isTrue()
        val variableValue: MutableList<String?> =
            getSequenceVariableValue(
                variables,
                LinkBuildVariables.LIBRARY_SEARCH_DIRECTORIES.getVariableName()
            )
        Truth.assertThat(com.google.common.collect.Iterables.getOnlyElement<String?>(variableValue))
            .contains("some-dir")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testLinkSimpleLibName() {
        useConfiguration()

        scratch.file(
            "x/BUILD",
            "load('@rules_cc//cc:cc_binary.bzl', 'cc_binary')",
            "cc_binary(name = 'bin', srcs = ['some-dir/libbar.so'])"
        )
        scratch.file("x/some-dir/bar.so")

        val target: ConfiguredTarget = getConfiguredTarget("//x:bin")

        val variables: CcToolchainVariables = getLinkBuildVariables(target, LinkTargetType.EXECUTABLE)
        val librariesToLinkSequence: VariableValue? =
            variables.getVariable(
                LinkBuildVariables.LIBRARIES_TO_LINK.getVariableName(), PathMapper.NOOP
            )
        val librariestoLink: Iterable<out VariableValue?> =
            CcToolchainVariables.getSequenceValue(
                LinkBuildVariables.LIBRARIES_TO_LINK.getVariableName(), librariesToLinkSequence
            )
        val nameValue: VariableValue =
            librariestoLink
                .iterator()
                .next()
                .getFieldValue(LinkBuildVariables.LIBRARIES_TO_LINK.getVariableName(), "name")
        assertThat(nameValue).isNotNull()
        val name: String? = nameValue.getStringValue("name", PathMapper.NOOP)
        Truth.assertThat(name).isEqualTo("bar")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testLinkVersionedLibName() {
        useConfiguration()

        scratch.file(
            "x/BUILD",
            "load('@rules_cc//cc:cc_binary.bzl', 'cc_binary')",
            "cc_binary(name = 'bin', srcs = ['some-dir/libbar.so.1a.2'])"
        )
        scratch.file("x/some-dir/bar.so")

        val target: ConfiguredTarget = getConfiguredTarget("//x:bin")

        val variables: CcToolchainVariables = getLinkBuildVariables(target, LinkTargetType.EXECUTABLE)
        val librariesToLinkSequence: VariableValue? =
            variables.getVariable(
                LinkBuildVariables.LIBRARIES_TO_LINK.getVariableName(), PathMapper.NOOP
            )
        val librariestoLink: Iterable<out VariableValue?> =
            CcToolchainVariables.getSequenceValue(
                LinkBuildVariables.LIBRARIES_TO_LINK.getVariableName(), librariesToLinkSequence
            )
        val nameValue: VariableValue =
            librariestoLink
                .iterator()
                .next()
                .getFieldValue(LinkBuildVariables.LIBRARIES_TO_LINK.getVariableName(), "name")
        assertThat(nameValue).isNotNull()
        val name: String? = nameValue.getStringValue("name", PathMapper.NOOP)
        Truth.assertThat(name).isEqualTo("libbar.so.1a.2")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testLinkUnusualLibName() {
        useConfiguration()

        scratch.file(
            "x/BUILD",
            "load('@rules_cc//cc:cc_binary.bzl', 'cc_binary')",
            "cc_binary(name = 'bin', srcs = ['some-dir/_libbar.so'])"
        )
        scratch.file("x/some-dir/_libbar.so")

        val target: ConfiguredTarget = getConfiguredTarget("//x:bin")

        val variables: CcToolchainVariables = getLinkBuildVariables(target, LinkTargetType.EXECUTABLE)
        val librariesToLinkSequence: VariableValue? =
            variables.getVariable(
                LinkBuildVariables.LIBRARIES_TO_LINK.getVariableName(), PathMapper.NOOP
            )
        val librariestoLink: Iterable<out VariableValue?> =
            CcToolchainVariables.getSequenceValue(
                LinkBuildVariables.LIBRARIES_TO_LINK.getVariableName(), librariesToLinkSequence
            )
        val nameValue: VariableValue =
            librariestoLink
                .iterator()
                .next()
                .getFieldValue(LinkBuildVariables.LIBRARIES_TO_LINK.getVariableName(), "name")
        assertThat(nameValue).isNotNull()
        val name: String? = nameValue.getStringValue("name", PathMapper.NOOP)
        Truth.assertThat(name).isEqualTo("_libbar.so")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testInterfaceLibraryBuildingVariablesWhenLegacyGenerationPossible() {
        AnalysisMock.get()
            .ccSupport()
            .setupCcToolchainConfig(
                mockToolsConfig,
                CcToolchainConfig.builder()
                    .withFeatures(
                        CppRuleClasses.SUPPORTS_INTERFACE_SHARED_LIBRARIES,
                        CppRuleClasses.SUPPORTS_DYNAMIC_LINKER
                    )
            )
        useConfiguration()

        verifyIfsoVariables()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testInterfaceLibraryBuildingVariablesWhenGenerationPossible() {
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
        useConfiguration()

        verifyIfsoVariables()
    }

    @Throws(java.lang.Exception::class)
    private fun verifyIfsoVariables() {
        scratch.file(
            "x/BUILD",
            "load('@rules_cc//cc:cc_library.bzl', 'cc_library')",
            "cc_library(name = 'foo', srcs = ['a.cc'])"
        )
        scratch.file("x/a.cc")

        val target: ConfiguredTarget = getConfiguredTarget("//x:foo")
        val variables: CcToolchainVariables =
            getLinkBuildVariables(target, LinkTargetType.NODEPS_DYNAMIC_LIBRARY)

        val interfaceLibraryBuilder: String? =
            getVariableValue(
                variables,
                LinkBuildVariables.INTERFACE_LIBRARY_BUILDER.getVariableName()
            )
        val interfaceLibraryInput: String? =
            getVariableValue(
                variables,
                LinkBuildVariables.INTERFACE_LIBRARY_INPUT.getVariableName()
            )
        val interfaceLibraryOutput: String? =
            getVariableValue(
                variables,
                LinkBuildVariables.INTERFACE_LIBRARY_OUTPUT.getVariableName()
            )
        val generateInterfaceLibrary: String? =
            getVariableValue(
                variables,
                LinkBuildVariables.GENERATE_INTERFACE_LIBRARY.getVariableName()
            )

        Truth.assertThat(generateInterfaceLibrary).isEqualTo("yes")
        Truth.assertThat(interfaceLibraryInput).endsWith("libfoo.so")
        Truth.assertThat(interfaceLibraryOutput).endsWith("libfoo.ifso")
        Truth.assertThat(interfaceLibraryBuilder).endsWith("build_interface_so")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testNoIfsoBuildingWhenWhenThinLtoIndexing() {
        // Make sure the interface shared object generation is enabled in the configuration
        // (which it is not by default for some windows toolchains)
        invalidatePackages(true)
        AnalysisMock.get()
            .ccSupport()
            .setupCcToolchainConfig(
                mockToolsConfig,
                CcToolchainConfig.builder()
                    .withFeatures(
                        CppRuleClasses.THIN_LTO,
                        CppRuleClasses.SUPPORTS_PIC,
                        CppRuleClasses.SUPPORTS_INTERFACE_SHARED_LIBRARIES,
                        CppRuleClasses.SUPPORTS_DYNAMIC_LINKER,
                        CppRuleClasses.SUPPORTS_START_END_LIB
                    )
            )
        useConfiguration("--features=thin_lto")

        scratch.file(
            "x/BUILD",
            "load('@rules_cc//cc:cc_library.bzl', 'cc_library')",
            "cc_library(name = 'foo', srcs = ['a.cc'])"
        )
        scratch.file("x/a.cc")

        val target: ConfiguredTarget = getConfiguredTarget("//x:foo")
        val linkAction: SpawnAction = getCppLinkAction(target, LinkTargetType.NODEPS_DYNAMIC_LIBRARY)
        val rootExecPath: String? = linkAction.getPrimaryOutput().getRoot().getExecPathString()

        val backendAction: LtoBackendAction? =
            getPredecessorByInputName(
                linkAction, "x/libfoo.so.lto/" + rootExecPath + "/x/_objs/foo/a.pic.o"
            ) as LtoBackendAction?
        assertThat(backendAction.getMnemonic()).isEqualTo("CcLtoBackendCompile")

        val indexAction: SpawnAction? =
            getPredecessorByInputName(
                backendAction,
                "x/libfoo.so.lto/" + rootExecPath + "/x/_objs/foo/a.pic.o.thinlto.bc"
            ) as SpawnAction?
        val variables: CcToolchainVariables? = getLinkCommandLine(indexAction).getBuildVariables()

        val interfaceLibraryBuilder: String? =
            getVariableValue(
                variables,
                LinkBuildVariables.INTERFACE_LIBRARY_BUILDER.getVariableName()
            )
        val interfaceLibraryInput: String? =
            getVariableValue(
                variables,
                LinkBuildVariables.INTERFACE_LIBRARY_INPUT.getVariableName()
            )
        val interfaceLibraryOutput: String? =
            getVariableValue(
                variables,
                LinkBuildVariables.INTERFACE_LIBRARY_OUTPUT.getVariableName()
            )
        val generateInterfaceLibrary: String? =
            getVariableValue(
                variables,
                LinkBuildVariables.GENERATE_INTERFACE_LIBRARY.getVariableName()
            )

        Truth.assertThat(generateInterfaceLibrary).isEqualTo("no")
        Truth.assertThat(interfaceLibraryInput).endsWith("ignored")
        Truth.assertThat(interfaceLibraryOutput).endsWith("ignored")
        Truth.assertThat(interfaceLibraryBuilder).endsWith("ignored")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testInterfaceLibraryBuildingVariablesWhenGenerationNotAllowed() {
        // Make sure the interface shared object generation is enabled in the configuration
        // (which it is not by default for some windows toolchains)
        AnalysisMock.get()
            .ccSupport()
            .setupCcToolchainConfig(
                mockToolsConfig,
                CcToolchainConfig.builder()
                    .withFeatures(CppRuleClasses.SUPPORTS_INTERFACE_SHARED_LIBRARIES)
            )
        useConfiguration()

        scratch.file(
            "x/BUILD",
            "load('@rules_cc//cc:cc_library.bzl', 'cc_library')",
            "cc_library(name = 'foo', srcs = ['a.cc'])"
        )
        scratch.file("x/a.cc")

        val target: ConfiguredTarget = getConfiguredTarget("//x:foo")
        val variables: CcToolchainVariables = getLinkBuildVariables(target, LinkTargetType.STATIC_LIBRARY)

        val interfaceLibraryBuilder: String? =
            getVariableValue(
                variables,
                LinkBuildVariables.INTERFACE_LIBRARY_BUILDER.getVariableName()
            )
        val interfaceLibraryInput: String? =
            getVariableValue(
                variables,
                LinkBuildVariables.INTERFACE_LIBRARY_INPUT.getVariableName()
            )
        val interfaceLibraryOutput: String? =
            getVariableValue(
                variables,
                LinkBuildVariables.INTERFACE_LIBRARY_OUTPUT.getVariableName()
            )
        val generateInterfaceLibrary: String? =
            getVariableValue(
                variables,
                LinkBuildVariables.GENERATE_INTERFACE_LIBRARY.getVariableName()
            )

        Truth.assertThat(generateInterfaceLibrary).isEqualTo("no")
        Truth.assertThat(interfaceLibraryInput).endsWith("ignored")
        Truth.assertThat(interfaceLibraryOutput).endsWith("ignored")
        Truth.assertThat(interfaceLibraryBuilder).endsWith("ignored")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testOutputExecpath() {
        AnalysisMock.get()
            .ccSupport()
            .setupCcToolchainConfig(
                mockToolsConfig,
                CcToolchainConfig.builder().withFeatures(CppRuleClasses.SUPPORTS_DYNAMIC_LINKER)
            )
        // Make sure the interface shared object generation is enabled in the configuration
        // (which it is not by default for some windows toolchains)
        scratch.file(
            "x/BUILD",
            "load('@rules_cc//cc:cc_library.bzl', 'cc_library')",
            "cc_library(name = 'foo', srcs = ['a.cc'])"
        )
        scratch.file("x/a.cc")

        val target: ConfiguredTarget = getConfiguredTarget("//x:foo")
        val variables: CcToolchainVariables =
            getLinkBuildVariables(target, LinkTargetType.NODEPS_DYNAMIC_LIBRARY)

        Truth.assertThat(getVariableValue(variables, LinkBuildVariables.OUTPUT_EXECPATH.getVariableName()))
            .endsWith("x/libfoo.so")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testOutputExecpathIsNotExposedWhenThinLtoIndexing() {
        AnalysisMock.get()
            .ccSupport()
            .setupCcToolchainConfig(
                mockToolsConfig,
                CcToolchainConfig.builder()
                    .withFeatures(
                        CppRuleClasses.THIN_LTO,
                        CppRuleClasses.SUPPORTS_DYNAMIC_LINKER,
                        CppRuleClasses.SUPPORTS_PIC,
                        CppRuleClasses.SUPPORTS_INTERFACE_SHARED_LIBRARIES,
                        CppRuleClasses.SUPPORTS_START_END_LIB
                    )
            )
        useConfiguration("--features=thin_lto")

        scratch.file(
            "x/BUILD",
            "load('@rules_cc//cc:cc_library.bzl', 'cc_library')",
            "cc_library(name = 'foo', srcs = ['a.cc'])"
        )
        scratch.file("x/a.cc")

        val target: ConfiguredTarget = getConfiguredTarget("//x:foo")
        val linkAction: SpawnAction = getCppLinkAction(target, LinkTargetType.NODEPS_DYNAMIC_LIBRARY)
        val rootExecPath: String? = linkAction.getPrimaryOutput().getRoot().getExecPathString()

        val backendAction: LtoBackendAction? =
            getPredecessorByInputName(
                linkAction, "x/libfoo.so.lto/" + rootExecPath + "/x/_objs/foo/a.pic.o"
            ) as LtoBackendAction?
        assertThat(backendAction.getMnemonic()).isEqualTo("CcLtoBackendCompile")

        val indexAction: SpawnAction? =
            getPredecessorByInputName(
                backendAction,
                "x/libfoo.so.lto/" + rootExecPath + "/x/_objs/foo/a.pic.o.thinlto.bc"
            ) as SpawnAction?
        val variables: CcToolchainVariables = getLinkCommandLine(indexAction).getBuildVariables()

        assertThat(variables.isAvailable(LinkBuildVariables.OUTPUT_EXECPATH.getVariableName()))
            .isFalse()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testIsCcTestLinkActionBuildVariable() {
        scratch.file(
            "x/BUILD",
            """
        load("@rules_cc//cc:cc_binary.bzl", "cc_binary")
        load("@rules_cc//cc:cc_test.bzl", "cc_test")
        cc_test(
            name = "foo_test",
            srcs = ["a.cc"],
        )

        cc_binary(
            name = "foo",
            srcs = ["a.cc"],
        )
        
        """.trimIndent()
        )
        scratch.file("x/a.cc")

        val testTarget: ConfiguredTarget = getConfiguredTarget("//x:foo_test")
        val testVariables: CcToolchainVariables =
            getLinkBuildVariables(testTarget, LinkTargetType.EXECUTABLE)

        assertThat(
            testVariables
                .getVariable(LinkBuildVariables.IS_CC_TEST.getVariableName(), PathMapper.NOOP).isTruthy
        )
            .isTrue()

        val binaryTarget: ConfiguredTarget = getConfiguredTarget("//x:foo")
        val binaryVariables: CcToolchainVariables =
            getLinkBuildVariables(binaryTarget, LinkTargetType.EXECUTABLE)

        assertThat(
            binaryVariables
                .getVariable(LinkBuildVariables.IS_CC_TEST.getVariableName(), PathMapper.NOOP).isTruthy
        )
            .isFalse()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testStripBinariesIsEnabledWhenStripModeIsAlwaysNoMatterWhat() {
        scratch.file(
            "x/BUILD",
            "load('@rules_cc//cc:cc_binary.bzl', 'cc_binary')",
            "cc_binary(name = 'foo', srcs = ['a.cc'])"
        )
        scratch.file("x/a.cc")

        assertStripBinaryVariableIsPresent("always", "opt", true)
        assertStripBinaryVariableIsPresent("always", "fastbuild", true)
        assertStripBinaryVariableIsPresent("always", "dbg", true)
        assertStripBinaryVariableIsPresent("sometimes", "opt", false)
        assertStripBinaryVariableIsPresent("sometimes", "fastbuild", true)
        assertStripBinaryVariableIsPresent("sometimes", "dbg", false)
        assertStripBinaryVariableIsPresent("never", "opt", false)
        assertStripBinaryVariableIsPresent("never", "fastbuild", false)
        assertStripBinaryVariableIsPresent("never", "dbg", false)
    }

    @Throws(java.lang.Exception::class)
    private fun assertStripBinaryVariableIsPresent(
        stripMode: String?, compilationMode: String?, isEnabled: Boolean
    ) {
        useConfiguration("--strip=" + stripMode, "--compilation_mode=" + compilationMode)
        val target: ConfiguredTarget = getConfiguredTarget("//x:foo")
        val variables: CcToolchainVariables = getLinkBuildVariables(target, LinkTargetType.EXECUTABLE)
        assertThat(variables.isAvailable(LinkBuildVariables.STRIP_DEBUG_SYMBOLS.getVariableName()))
            .isEqualTo(isEnabled)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testIsUsingFissionVariableUsingLegacyFields() {
        scratch.file(
            "x/BUILD",
            "load('@rules_cc//cc:cc_binary.bzl', 'cc_binary')",
            "cc_binary(name = 'foo', srcs = ['foo.cc'])"
        )
        scratch.file("x/foo.cc")

        AnalysisMock.get()
            .ccSupport()
            .setupCcToolchainConfig(
                mockToolsConfig,
                CcToolchainConfig.builder().withFeatures(CppRuleClasses.PER_OBJECT_DEBUG_INFO)
            )

        useConfiguration("--fission=no")
        val target: ConfiguredTarget = getConfiguredTarget("//x:foo")
        val variables: CcToolchainVariables = getLinkBuildVariables(target, LinkTargetType.EXECUTABLE)
        assertThat(variables.isAvailable(LinkBuildVariables.IS_USING_FISSION.getVariableName()))
            .isFalse()

        useConfiguration("--fission=yes")
        val fissionTarget: ConfiguredTarget = getConfiguredTarget("//x:foo")
        val fissionVariables: CcToolchainVariables =
            getLinkBuildVariables(fissionTarget, LinkTargetType.EXECUTABLE)
        assertThat(fissionVariables.isAvailable(LinkBuildVariables.IS_USING_FISSION.getVariableName()))
            .isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testIsUsingFissionVariable() {
        scratch.file(
            "x/BUILD",
            "load('@rules_cc//cc:cc_binary.bzl', 'cc_binary')",
            "cc_binary(name = 'foo', srcs = ['foo.cc'])"
        )
        scratch.file("x/foo.cc")

        AnalysisMock.get()
            .ccSupport()
            .setupCcToolchainConfig(
                mockToolsConfig,
                CcToolchainConfig.builder().withFeatures(CppRuleClasses.PER_OBJECT_DEBUG_INFO)
            )

        useConfiguration("--fission=no")
        val target: ConfiguredTarget = getConfiguredTarget("//x:foo")
        val variables: CcToolchainVariables = getLinkBuildVariables(target, LinkTargetType.EXECUTABLE)
        assertThat(variables.isAvailable(LinkBuildVariables.IS_USING_FISSION.getVariableName()))
            .isFalse()

        useConfiguration("--fission=yes")
        val fissionTarget: ConfiguredTarget = getConfiguredTarget("//x:foo")
        val fissionVariables: CcToolchainVariables =
            getLinkBuildVariables(fissionTarget, LinkTargetType.EXECUTABLE)
        assertThat(fissionVariables.isAvailable(LinkBuildVariables.IS_USING_FISSION.getVariableName()))
            .isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSysrootVariable() {
        AnalysisMock.get()
            .ccSupport()
            .setupCcToolchainConfig(
                mockToolsConfig, CcToolchainConfig.builder().withSysroot("/usr/local/custom-sysroot")
            )
        useConfiguration()

        scratch.file(
            "x/BUILD",
            "load('@rules_cc//cc:cc_binary.bzl', 'cc_binary')",
            "cc_binary(name = 'foo', srcs = ['a.cc'])"
        )
        scratch.file("x/a.cc")

        val testTarget: ConfiguredTarget = getConfiguredTarget("//x:foo")
        val testVariables: CcToolchainVariables =
            getLinkBuildVariables(testTarget, LinkTargetType.EXECUTABLE)

        assertThat(testVariables.isAvailable(LinkBuildVariablesTestCase.Companion.SYSROOT_VARIABLE_NAME)).isTrue()
    }

    private fun getPredecessorByInputName(action: Action, str: String?): Action? {
        for (a in action.getInputs().toList()) {
            if (a.getExecPathString().contains(str)) {
                return getGeneratingAction(a)
            }
        }
        return null
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testUserLinkFlagsWithLinkoptOption() {
        useConfiguration("--linkopt=-bar")

        scratch.file(
            "x/BUILD",
            "load('@rules_cc//cc:cc_binary.bzl', 'cc_binary')",
            "cc_binary(name = 'foo', srcs = ['a.cc'], linkopts = ['-foo'])"
        )
        scratch.file("x/a.cc")

        val testTarget: ConfiguredTarget = getConfiguredTarget("//x:foo")
        val testVariables: CcToolchainVariables =
            getLinkBuildVariables(testTarget, LinkTargetType.EXECUTABLE)

        val userLinkFlags: com.google.common.collect.ImmutableList<String?>? =
            CcToolchainVariables.toStringList(
                testVariables, LinkBuildVariables.USER_LINK_FLAGS.getVariableName(), PathMapper.NOOP
            )
        Truth.assertThat(userLinkFlags).containsAtLeast("-foo", "-bar").inOrder()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testLinkerInputsOverrideWholeArchive() {
        AnalysisMock.get()
            .ccSupport()
            .setupCcToolchainConfig(
                mockToolsConfig,
                CcToolchainConfig.builder()
                    .withFeatures("disable_whole_archive_for_static_lib_configuration")
            )

        scratch.file(
            "x/BUILD",
            "load('@rules_cc//cc:cc_binary.bzl', 'cc_binary')",
            "load('@rules_cc//cc:cc_library.bzl', 'cc_library')",
            "cc_library(name='a', hdrs=['a.h'], srcs = ['a.cc'], "
                    + " features=['disable_whole_archive_for_static_lib'])",
            "cc_library(name='b', hdrs=['b.h'], srcs = ['b.cc'], alwayslink=1)",
            "cc_binary(name = 'c.so', linkstatic=1, linkshared=1, deps=[':a', ':b'])"
        )

        val testTarget: ConfiguredTarget = getConfiguredTarget("//x:c.so")
        val testVariables: CcToolchainVariables =
            getLinkBuildVariables(testTarget, LinkTargetType.DYNAMIC_LIBRARY)

        val librariesToLinkSequence: VariableValue? =
            testVariables.getVariable(
                LinkBuildVariables.LIBRARIES_TO_LINK.getVariableName(), PathMapper.NOOP
            )
        assertThat(librariesToLinkSequence).isNotNull()
        val librariesToLink: Iterable<out VariableValue?> =
            CcToolchainVariables.getSequenceValue(
                LinkBuildVariables.LIBRARIES_TO_LINK.getVariableName(), librariesToLinkSequence
            )
        Truth.assertThat(com.google.common.collect.Iterables.size(librariesToLink)).isAnyOf(2, 3)

        val librariesToLinkIterator: MutableIterator<out VariableValue?> = librariesToLink.iterator()
        // :a should not be whole archive
        val aWholeArchiveValue: VariableValue =
            librariesToLinkIterator
                .next()
                .getFieldValue(
                    LinkBuildVariables.LIBRARIES_TO_LINK.getVariableName(), "is_whole_archive"
                )
        assertThat(aWholeArchiveValue).isNotNull()
        assertThat(aWholeArchiveValue.isTruthy).isFalse()

        // :b should be whole archive
        val bWholeArchiveValue: VariableValue =
            librariesToLinkIterator
                .next()
                .getFieldValue(
                    LinkBuildVariables.LIBRARIES_TO_LINK.getVariableName(), "is_whole_archive"
                )
        assertThat(bWholeArchiveValue).isNotNull()
        assertThat(bWholeArchiveValue.isTruthy).isTrue()
    }
}
