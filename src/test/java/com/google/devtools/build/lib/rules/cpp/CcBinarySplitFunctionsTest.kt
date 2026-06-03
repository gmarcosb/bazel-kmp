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

/** Tests for cc_binary with split functions.  */
@RunWith(JUnit4::class)
class CcBinarySplitFunctionsTest : BuildViewTestCase() {
    @Before
    @Throws(IOException::class)
    fun createBasePkg() {
        scratch.overwriteFile(
            "base/BUILD",
            "load('@rules_cc//cc:cc_library.bzl', 'cc_library')",
            "cc_library(name = 'system_malloc', visibility = ['//visibility:public'])"
        )
    }

    private fun getPredecessorByInputName(action: Action, str: String?): Action? {
        for (a in action.getInputs().toList()) {
            if (a.getExecPathString().contains(str)) {
                return getGeneratingAction(a)
            }
        }
        return null
    }

    @Throws(java.lang.Exception::class)
    private fun setupAndRunToolchainActions(fdoFlavor: String, vararg config: String?): LtoBackendAction {
        AnalysisMock.get()
            .ccSupport()
            .setupCcToolchainConfig(
                mockToolsConfig,
                CcToolchainConfig.builder()
                    .withFeatures(
                        CppRuleClasses.THIN_LTO,
                        MockCcSupport.HOST_AND_NONHOST_CONFIGURATION_FEATURES,
                        CppRuleClasses.SUPPORTS_START_END_LIB,
                        CppRuleClasses.FDO_OPTIMIZE,
                        CppRuleClasses.FSAFDO,
                        CppRuleClasses.ENABLE_FDO_SPLIT_FUNCTIONS,
                        MockCcSupport.FDO_SPLIT_FUNCTIONS,
                        MockCcSupport.SPLIT_FUNCTIONS
                    )
            )

        // "profile" affects whether FDO_OPTIMIZE or AUTOFDO is activated.
        val testConfig: MutableList<String?> =
            com.google.common.collect.Lists.newArrayList<String?>(
                "--fdo_optimize=" + getProfile(fdoFlavor),
                "--compilation_mode=opt"
            )
        Collections.addAll<String?>(testConfig, *config)
        useConfiguration(*com.google.common.collect.Iterables.toArray<String?>(testConfig, String::class.java))

        val binArtifact: Artifact = getFilesToBuild(getConfiguredTarget("//pkg:bin")).getSingleton()
        val rootExecPath: String? = binArtifact.getRoot().getExecPathString()
        val linkAction: SpawnAction = getGeneratingAction(binArtifact) as SpawnAction
        assertThat(linkAction.getOutputs()).containsExactly(binArtifact)

        val backendAction: LtoBackendAction? =
            getPredecessorByInputName(
                linkAction, "pkg/bin.lto/" + rootExecPath + "/pkg/_objs/bin/binfile.o"
            ) as LtoBackendAction?

        // We should have a ThinLTO backend action.
        assertThat(backendAction).isNotNull()

        return backendAction
    }

    /** Gets profile for fdoFlavor. The profile suffix differentiates FDO_OPTIMIZE and FSAFDO mode.  */
    private fun getProfile(fdoFlavor: String): String {
        if (fdoFlavor == CppRuleClasses.FSAFDO || fdoFlavor == CppRuleClasses.AUTOFDO) {
            return "/pkg/profile.afdo"
        }
        return "/pkg/profile.zip"
    }

    /**
     * Helps check that split_functions is enabled for fdoFlavor with LLVM with
     * --features=fdo_split_functions.
     */
    @Throws(java.lang.Exception::class)
    private fun implicitSplitFunctions(fdoFlavor: String) {
        scratch.file(
            "pkg/BUILD",
            """
        load("@rules_cc//cc:cc_binary.bzl", "cc_binary")
        package(features = ["thin_lto"])

        cc_binary(
            name = "bin",
            srcs = ["binfile.cc"],
            malloc = "//base:system_malloc",
        )
        
        """.trimIndent()
        )
        scratch.file("pkg/binfile.cc", "int main() {}")
        scratch.file("pkg/profile.zip", "")

        val backendAction: LtoBackendAction =
            setupAndRunToolchainActions(fdoFlavor, "--features=fdo_split_functions")

        Truth.assertThat(com.google.common.base.Joiner.on(" ").join(backendAction.getArguments()))
            .containsMatch("-fsplit-machine-functions")
        Truth.assertThat(com.google.common.base.Joiner.on(" ").join(backendAction.getArguments()))
            .containsMatch("-DBUILD_MFS_ENABLED=1")
    }

    /**
     * Tests that split_functions is enabled for FDO with LLVM with --features=fdo_split_functions.
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun fdoImplicitSplitFunctions() {
        implicitSplitFunctions(CppRuleClasses.FDO_OPTIMIZE)
    }

    /**
     * Tests that split_functions is enabled for FSAFDO with LLVM with --features=fdo_split_functions.
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun fsafdoImplicitSplitFunctions() {
        implicitSplitFunctions(CppRuleClasses.FSAFDO)
    }

    /**
     * Tests that split_functions is disabled for AutoFDO without FSAFDO with LLVM with
     * --features=fdo_split_functions.
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun noFSAFDODisablesSplitFunction() {
        scratch.file(
            "pkg/BUILD",
            """
        load("@rules_cc//cc:cc_binary.bzl", "cc_binary")
        package(features = ["thin_lto"])

        cc_binary(
            name = "bin",
            srcs = ["binfile.cc"],
            malloc = "//base:system_malloc",
        )
        
        """.trimIndent()
        )
        scratch.file("pkg/binfile.cc", "int main() {}")
        scratch.file("pkg/profile.afdo", "")

        val backendAction: LtoBackendAction =
            setupAndRunToolchainActions(
                CppRuleClasses.FSAFDO, "--features=-fsafdo", "--features=fdo_split_functions"
            )

        Truth.assertThat(com.google.common.base.Joiner.on(" ").join(backendAction.getArguments()))
            .doesNotContain("-fsplit-machine-functions")
    }

    /**
     * Helps check that split_functions is not enabled for fdoFlavor with LLVM without
     * --features=fdo_split_functions.
     */
    @Throws(java.lang.Exception::class)
    private fun noImplicitSplitFunctions(fdoFlavor: String) {
        scratch.file(
            "pkg/BUILD",
            """
        load("@rules_cc//cc:cc_binary.bzl", "cc_binary")
        package(features = ["thin_lto"])

        cc_binary(
            name = "bin",
            srcs = ["binfile.cc"],
            malloc = "//base:system_malloc",
        )
        
        """.trimIndent()
        )
        scratch.file("pkg/binfile.cc", "int main() {}")
        scratch.file("pkg/profile.zip", "")

        val backendAction: LtoBackendAction = setupAndRunToolchainActions(fdoFlavor)

        Truth.assertThat(com.google.common.base.Joiner.on(" ").join(backendAction.getArguments()))
            .doesNotContain("-fsplit-machine-functions")
    }

    /**
     * Tests that split_functions is not enabled for FDO with LLVM without
     * --features=fdo_split_functions.
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun fdoNoImplicitSplitFunctions() {
        noImplicitSplitFunctions(CppRuleClasses.FDO_OPTIMIZE)
    }

    /**
     * Tests that split_functions is not enabled for FSAFDO with LLVM without
     * --features=fdo_split_functions.
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun fsafdoNoImplicitSplitFunctions() {
        noImplicitSplitFunctions(CppRuleClasses.FSAFDO)
    }

    /**
     * Helps check that split_functions is not enabled for fdoFlavor with LLVM when
     * --features=fdo_split_functions is overridden by --features=-split_functions.
     */
    @Throws(java.lang.Exception::class)
    private fun implicitSplitFunctionsDisabledOption(fdoFlavor: String) {
        scratch.file(
            "pkg/BUILD",
            """
        load("@rules_cc//cc:cc_binary.bzl", "cc_binary")
        package(features = ["thin_lto"])

        cc_binary(
            name = "bin",
            srcs = ["binfile.cc"],
            malloc = "//base:system_malloc",
        )
        
        """.trimIndent()
        )
        scratch.file("pkg/binfile.cc", "int main() {}")
        scratch.file("pkg/profile.zip", "")

        val backendAction: LtoBackendAction =
            setupAndRunToolchainActions(
                fdoFlavor, "--features=fdo_split_functions", "--features=-split_functions"
            )

        Truth.assertThat(com.google.common.base.Joiner.on(" ").join(backendAction.getArguments()))
            .doesNotContain("-fsplit-machine-functions")
    }

    /**
     * Tests that split_functions is not enabled for FDO with LLVM when --features=fdo_split_functions
     * is overridden by --features=-split_functions.
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun fdoImplicitSplitFunctionsDisabledOption() {
        implicitSplitFunctionsDisabledOption(CppRuleClasses.FDO_OPTIMIZE)
    }

    /**
     * Tests that split_functions is not enabled for FSAFDO with LLVM when
     * --features=fdo_split_functions is overridden by --features=-split_functions.
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun fsafdoImplicitSplitFunctionsDisabledOption() {
        implicitSplitFunctionsDisabledOption(CppRuleClasses.FSAFDO)
    }

    /**
     * Helps check that split_functions is not enabled for fdoFlavor with LLVM when
     * --features=fdo_split_functions is overridden by --features=-split_functions in the build rule.
     */
    @Throws(java.lang.Exception::class)
    private fun implicitSplitFunctionsDisabledBuild(fdoFlavor: String) {
        scratch.file(
            "pkg/BUILD",
            """
        load("@rules_cc//cc:cc_binary.bzl", "cc_binary")
        package(features = ["thin_lto"])

        cc_binary(
            name = "bin",
            srcs = ["binfile.cc"],
            features = ["-split_functions"],
            malloc = "//base:system_malloc",
        )
        
        """.trimIndent()
        )
        scratch.file("pkg/binfile.cc", "int main() {}")
        scratch.file("pkg/profile.zip", "")

        val backendAction: LtoBackendAction =
            setupAndRunToolchainActions(fdoFlavor, "--features=fdo_split_functions")

        Truth.assertThat(com.google.common.base.Joiner.on(" ").join(backendAction.getArguments()))
            .doesNotContain("-fsplit-machine-functions")
    }

    /**
     * Tests that split_functions is not enabled for FDO with LLVM when --features=fdo_split_functions
     * is overridden by --features=-split_functions in the build rule.
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun fdoImplicitSplitFunctionsDisabledBuild() {
        implicitSplitFunctionsDisabledBuild(CppRuleClasses.FDO_OPTIMIZE)
    }

    /**
     * Tests that split_functions is not enabled for FSAFDO with LLVM when
     * --features=fdo_split_functions is overridden by --features=-split_functions in the build rule.
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun fsafdoImplicitSplitFunctionsDisabledBuild() {
        implicitSplitFunctionsDisabledBuild(CppRuleClasses.FSAFDO)
    }

    /** Helps check that using propeller_optimize automatically disables implicit split functions.  */
    @Throws(java.lang.Exception::class)
    private fun propellerOptimizeDisablesImplicitSplitFunctions(fdoFlavor: String) {
        scratch.file(
            "pkg/BUILD",
            """
        load("@rules_cc//cc:cc_binary.bzl", "cc_binary")
        package(features = ["thin_lto"])

        cc_binary(
            name = "bin",
            srcs = ["binfile.cc"],
            malloc = "//base:system_malloc",
        )
        
        """.trimIndent()
        )
        scratch.file("pkg/binfile.cc", "int main() {}")
        scratch.file("pkg/profile.zip", "")

        val backendAction: LtoBackendAction =
            setupAndRunToolchainActions(
                fdoFlavor,
                "--features=fdo_split_functions",
                "--propeller_optimize_absolute_cc_profile=/tmp/cc.txt"
            )

        Truth.assertThat(com.google.common.base.Joiner.on(" ").join(backendAction.getArguments()))
            .containsMatch("-fbasic-block-sections=list=")
        Truth.assertThat(com.google.common.base.Joiner.on(" ").join(backendAction.getArguments()))
            .containsMatch("-DBUILD_PROPELLER_ENABLED=1")
        Truth.assertThat(com.google.common.base.Joiner.on(" ").join(backendAction.getArguments()))
            .doesNotMatch("-DBUILD_PROPELLER_TYPE=\"split\"")
        Truth.assertThat(com.google.common.base.Joiner.on(" ").join(backendAction.getArguments()))
            .doesNotMatch("-fsplit-machine-functions")
    }

    /** Tests that using propeller_optimize automatically disables implicit split functions.  */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun fdoPropellerOptimizeDisablesImplicitSplitFunctions() {
        propellerOptimizeDisablesImplicitSplitFunctions(CppRuleClasses.FDO_OPTIMIZE)
    }

    /** Tests that using propeller_optimize automatically disables implicit split functions.  */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun fsafdoPropellerOptimizeDisablesImplicitSplitFunctions() {
        propellerOptimizeDisablesImplicitSplitFunctions(CppRuleClasses.FSAFDO)
    }

    /**
     * Helps check that split_functions can be mixed with propeller_optimize, when explicitly enabled.
     */
    @Throws(java.lang.Exception::class)
    private fun propellerOptimizeWithSplitFunctions(fdoFlavor: String) {
        scratch.file(
            "pkg/BUILD",
            """
        load("@rules_cc//cc:cc_binary.bzl", "cc_binary")
        package(features = ["thin_lto"])

        cc_binary(
            name = "bin",
            srcs = ["binfile.cc"],
            malloc = "//base:system_malloc",
        )
        
        """.trimIndent()
        )
        scratch.file("pkg/binfile.cc", "int main() {}")
        scratch.file("pkg/profile.zip", "")

        val backendAction: LtoBackendAction =
            setupAndRunToolchainActions(
                fdoFlavor,
                "--features=split_functions",
                "--propeller_optimize_absolute_cc_profile=/tmp/cc.txt"
            )

        Truth.assertThat(com.google.common.base.Joiner.on(" ").join(backendAction.getArguments()))
            .containsMatch("-fbasic-block-sections=list=")
        Truth.assertThat(com.google.common.base.Joiner.on(" ").join(backendAction.getArguments()))
            .containsMatch("-DBUILD_PROPELLER_ENABLED=1")
        Truth.assertThat(com.google.common.base.Joiner.on(" ").join(backendAction.getArguments()))
            .containsMatch("-DBUILD_MFS_ENABLED=1")
        Truth.assertThat(com.google.common.base.Joiner.on(" ").join(backendAction.getArguments()))
            .containsMatch("-fsplit-machine-functions")
    }

    /** Tests that split_functions can be mixed with propeller_optimize, when explicitly enabled.  */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun fdoPropellerOptimizeWithSplitFunctions() {
        propellerOptimizeWithSplitFunctions(CppRuleClasses.FDO_OPTIMIZE)
    }

    /**
     * Helps check that split_functions is not enabled for fdoFlavor with LLVM when
     * --features=fdo_split_functions is overridden by --features=-split_functions in the package.
     */
    @Throws(java.lang.Exception::class)
    private fun implicitSplitFunctionsDisabledPackage(fdoFlavor: String) {
        scratch.file(
            "pkg/BUILD",
            """
        load("@rules_cc//cc:cc_binary.bzl", "cc_binary")
        package(features = [
            "thin_lto",
            "-split_functions",
        ])

        cc_binary(
            name = "bin",
            srcs = ["binfile.cc"],
            malloc = "//base:system_malloc",
        )
        
        """.trimIndent()
        )
        scratch.file("pkg/binfile.cc", "int main() {}")
        scratch.file("pkg/profile.zip", "")

        val backendAction: LtoBackendAction =
            setupAndRunToolchainActions(fdoFlavor, "--features=fdo_split_functions")

        Truth.assertThat(com.google.common.base.Joiner.on(" ").join(backendAction.getArguments()))
            .doesNotContain("-fsplit-machine-functions")
    }

    /**
     * Tests that split_functions is not enabled for FDO with LLVM when --features=fdo_split_functions
     * is overridden by --features=-split_functions in the package.
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun fdoImplicitSplitFunctionsDisabledPackage() {
        implicitSplitFunctionsDisabledPackage(CppRuleClasses.FDO_OPTIMIZE)
    }

    /**
     * Tests that split_functions is not enabled for FSAFDO with LLVM when
     * --features=fdo_split_functions is overridden by --features=-split_functions in the package.
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun fsafdoImplicitSplitFunctionsDisabledPackage() {
        implicitSplitFunctionsDisabledPackage(CppRuleClasses.FSAFDO)
    }
}
