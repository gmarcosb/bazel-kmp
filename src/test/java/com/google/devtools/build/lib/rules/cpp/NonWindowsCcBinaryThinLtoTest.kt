// Copyright 2020 The Bazel Authors. All rights reserved.
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

/**
 * Tests for cc_binary with ThinLTO.
 * 
 * 
 * As of 2020-02-06, these tests do not work on Windows, hence the "NonWindows" part of the class
 * name.
 */
@RunWith(JUnit4::class)
class NonWindowsCcBinaryThinLtoTest : BuildViewTestCase() {
    @Throws(java.lang.Exception::class)
    fun createTestFiles(extraTestParameters: String?, extraLibraryParameters: String?) {
        scratch.overwriteFile(
            "base/BUILD",
            "load('@rules_cc//cc:cc_library.bzl', 'cc_library')",
            "cc_library(name = 'system_malloc', visibility = ['//visibility:public'])"
        )
        scratch.file(
            "pkg/BUILD",
            "load('@rules_cc//cc:cc_library.bzl', 'cc_library')",
            "load('@rules_cc//cc:cc_test.bzl', 'cc_test')",
            "package(features = ['thin_lto'])",
            "cc_test(",
            "    name = 'bin_test',",
            "    srcs = ['bin_test.cc', ],",
            "    deps = [ ':lib' ], ",
            extraTestParameters,
            "    malloc = '//base:system_malloc'",
            ")",
            "cc_test(",
            "    name = 'bin_test2',",
            "    srcs = ['bin_test2.cc', ],",
            "    deps = [ ':lib' ], ",
            extraTestParameters,
            "    malloc = '//base:system_malloc'",
            ")",
            "cc_library(",
            "    name = 'lib',",
            "    srcs = ['libfile.cc'],",
            "    hdrs = ['libfile.h'],",
            extraLibraryParameters,
            "    linkstamp = 'linkstamp.cc',",
            ")"
        )

        scratch.file("pkg/bin_test.cc", "#include \"pkg/libfile.h\"", "int main() { return pkg(); }")
        scratch.file("pkg/bin_test2.cc", "#include \"pkg/libfile.h\"", "int main() { return pkg(); }")
        scratch.file("pkg/libfile.cc", "int pkg() { return 42; }")
        scratch.file("pkg/libfile.h", "int pkg();")
        scratch.file("pkg/linkstamp.cc")
    }

    /** Helper method that checks that a .dwp has the expected generating action structure.  */
    @Throws(java.lang.Exception::class)
    private fun validateDwp(
        dwpFile: Artifact, toolchain: CcToolchainProvider, expectedInputs: MutableList<String?>?
    ) {
        val dwpAction: SpawnAction = getGeneratingAction(dwpFile) as SpawnAction
        val dwpToolPath: String? =
            CcToolchainProvider.getToolPathString(
                toolchain.getToolPaths(),
                Tool.DWP,
                toolchain.getCcToolchainLabel(),
                toolchain.getToolchainIdentifier()
            )
        assertThat(dwpAction.getMnemonic()).isEqualTo("CcGenerateDwp")
        Truth.assertThat(dwpToolPath).isEqualTo(dwpAction.getCommandFilename())
        val commandArgs: MutableList<String?> = dwpAction.getArguments()
        // The first argument should be the command being executed.
        Truth.assertThat(dwpToolPath).isEqualTo(commandArgs.get(0))
        // The final two arguments should be "-o dwpOutputFile".
        Truth.assertThat(commandArgs.subList(commandArgs.size - 2, commandArgs.size))
            .containsExactly("-o", dwpFile.getExecPathString())
            .inOrder()
        // The remaining arguments should be the set of .dwo inputs (in any order).
        Truth.assertThat(commandArgs.subList(1, commandArgs.size - 2))
            .containsExactlyElementsIn(expectedInputs)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testLinkstaticCcLibraryOnTestFission() {
        createTestFiles("", "linkstatic = 1,")

        AnalysisMock.get()
            .ccSupport()
            .setupCcToolchainConfig(
                mockToolsConfig,
                CcToolchainConfig.builder()
                    .withFeatures(
                        CppRuleClasses.THIN_LTO,
                        CppRuleClasses.SUPPORTS_PIC,
                        CppRuleClasses.SUPPORTS_START_END_LIB,
                        CppRuleClasses.THIN_LTO_LINKSTATIC_TESTS_USE_SHARED_NONLTO_BACKENDS,
                        MockCcSupport.HOST_AND_NONHOST_CONFIGURATION_FEATURES,
                        CppRuleClasses.PER_OBJECT_DEBUG_INFO
                    )
            )
        useConfiguration(
            "--fission=yes", "--features=thin_lto_linkstatic_tests_use_shared_nonlto_backends"
        )

        val pkg: ConfiguredTarget = getConfiguredTarget("//pkg:bin_test")
        val pkgArtifact: Artifact = getFilesToBuild(pkg).getSingleton()
        val rootExecPath: String? = pkgArtifact.getRoot().getExecPathString()
        val linkAction: SpawnAction = getGeneratingAction(pkgArtifact) as SpawnAction

        // The cc_test source should still get LTO in this case
        var backendAction: LtoBackendAction? =
            getPredecessorByInputName(
                linkAction, "bin_test.lto/" + rootExecPath + "/pkg/_objs/bin_test/bin_test.pic.o"
            ) as LtoBackendAction?
        assertThat(backendAction.getMnemonic()).isEqualTo("CcLtoBackendCompile")
        Truth.assertThat(artifactsToStrings(backendAction.getOutputs()))
            .containsExactly(
                "bin pkg/bin_test.lto/" + rootExecPath + "/pkg/_objs/bin_test/bin_test.pic.o",
                "bin pkg/bin_test.lto/" + rootExecPath + "/pkg/_objs/bin_test/bin_test.pic.dwo"
            )

        com.google.common.truth.Subject.contains("per_object_debug_info_option")

        // The linkstatic cc_library source should get shared non-LTO
        backendAction =
            getPredecessorByInputName(
                linkAction, "shared.nonlto/" + rootExecPath + "/pkg/_objs/lib/libfile.pic.o"
            ) as LtoBackendAction?
        assertThat(backendAction.getMnemonic()).isEqualTo("CcLtoBackendCompile")
        com.google.common.truth.Subject.contains("-fPIC")
        Truth.assertThat(artifactsToStrings(backendAction.getOutputs()))
            .containsExactly(
                "bin shared.nonlto/" + rootExecPath + "/pkg/_objs/lib/libfile.pic.o",
                "bin shared.nonlto/" + rootExecPath + "/pkg/_objs/lib/libfile.pic.dwo"
            )

        com.google.common.truth.Subject.contains("per_object_debug_info_option")

        // Now check the dwp action.
        val dwpFile: Artifact = getFileConfiguredTarget(pkg.getLabel() + ".dwp").getArtifact()
        val rootPrefix: PathFragment = dwpRootPrefix(dwpFile)
        val ruleContext: RuleContext = getRuleContext(pkg)
        val toolchain: CcToolchainProvider = CppHelper.getToolchain(ruleContext)
        validateDwp(
            dwpFile,
            toolchain,
            com.google.common.collect.ImmutableList.of<String?>(
                rootPrefix.toString() + "/shared.nonlto/" + rootExecPath + "/pkg/_objs/lib/libfile.pic.dwo",
                (rootPrefix
                    .toString() + "/pkg/bin_test.lto/"
                        + rootExecPath
                        + "/pkg/_objs/bin_test/bin_test.pic.dwo")
            )
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testLinkstaticCcLibraryOnTest() {
        createTestFiles("", "linkstatic = 1,")

        AnalysisMock.get()
            .ccSupport()
            .setupCcToolchainConfig(
                mockToolsConfig,
                CcToolchainConfig.builder()
                    .withFeatures(
                        CppRuleClasses.THIN_LTO,
                        CppRuleClasses.SUPPORTS_START_END_LIB,
                        CppRuleClasses.THIN_LTO_LINKSTATIC_TESTS_USE_SHARED_NONLTO_BACKENDS,
                        MockCcSupport.HOST_AND_NONHOST_CONFIGURATION_FEATURES,
                        CppRuleClasses.SUPPORTS_PIC,
                        CppRuleClasses.PER_OBJECT_DEBUG_INFO
                    )
            )
        useConfiguration("--features=thin_lto_linkstatic_tests_use_shared_nonlto_backends")

        val pkg: ConfiguredTarget = getConfiguredTarget("//pkg:bin_test")
        val pkgArtifact: Artifact = getFilesToBuild(pkg).getSingleton()
        val rootExecPath1: String? = pkgArtifact.getRoot().getExecPathString()
        val linkAction: SpawnAction = getGeneratingAction(pkgArtifact) as SpawnAction

        val pkg2: ConfiguredTarget = getConfiguredTarget("//pkg:bin_test2")
        val pkgArtifact2: Artifact = getFilesToBuild(pkg2).getSingleton()
        val rootExecPath2: String? = pkgArtifact2.getRoot().getExecPathString()
        val linkAction2: SpawnAction = getGeneratingAction(pkgArtifact2) as SpawnAction

        // The cc_test source should still get LTO in this case
        var backendAction: LtoBackendAction? =
            getPredecessorByInputName(
                linkAction, "bin_test.lto/" + rootExecPath1 + "/pkg/_objs/bin_test/bin_test.pic.o"
            ) as LtoBackendAction?
        assertThat(backendAction.getMnemonic()).isEqualTo("CcLtoBackendCompile")

        // The linkstatic cc_library sources should get shared non-LTO
        backendAction =
            getPredecessorByInputName(
                linkAction, "shared.nonlto/" + rootExecPath1 + "/pkg/_objs/lib/libfile.pic.o"
            ) as LtoBackendAction?
        assertThat(backendAction.getMnemonic()).isEqualTo("CcLtoBackendCompile")
        com.google.common.truth.Subject.contains("-fPIC")

        val backendAction2: LtoBackendAction? =
            getPredecessorByInputName(
                linkAction2, "shared.nonlto/" + rootExecPath2 + "/pkg/_objs/lib/libfile.pic.o"
            ) as LtoBackendAction?
        assertThat(backendAction2.getMnemonic()).isEqualTo("CcLtoBackendCompile")

        assertThat(backendAction).isEqualTo(backendAction2)
    }

    private fun getPredecessorByInputName(action: Action, str: String?): Action? {
        for (a in action.getInputs().toList()) {
            if (a.getExecPathString().contains(str)) {
                return getGeneratingAction(a)
            }
        }
        return null
    }

    companion object {
        /** Helper method to get the root prefix from the given dwpFile.  */
        @Throws(java.lang.Exception::class)
        private fun dwpRootPrefix(dwpFile: Artifact): PathFragment {
            return dwpFile
                .getExecPath()
                .subFragment(
                    0, dwpFile.getExecPath().segmentCount() - dwpFile.getRootRelativePath().segmentCount()
                )
        }
    }
}
