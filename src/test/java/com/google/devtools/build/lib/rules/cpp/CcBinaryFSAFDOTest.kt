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

/** Tests for cc_binary with fsafdo features.  */
@RunWith(JUnit4::class)
class CcBinaryFSAFDOTest : BuildViewTestCase() {
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
    private fun setupAndRunToolchainActions(vararg config: String?): LtoBackendAction {
        AnalysisMock.get()
            .ccSupport()
            .setupCcToolchainConfig(
                mockToolsConfig,
                CcToolchainConfig.builder()
                    .withFeatures(
                        CppRuleClasses.THIN_LTO,
                        CppRuleClasses.AUTOFDO,
                        CppRuleClasses.ENABLE_FSAFDO,
                        MockCcSupport.HOST_AND_NONHOST_CONFIGURATION_FEATURES,
                        CppRuleClasses.SUPPORTS_START_END_LIB,
                        MockCcSupport.IMPLICIT_FSAFDO,
                        MockCcSupport.FSAFDO
                    )
            )

        val testConfig: MutableList<String?> =
            com.google.common.collect.Lists.newArrayList<String?>(
                "--fdo_optimize=/pkg/profile.afdo",
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

    /** Tests that fsafdo is enabled with LLVM with --features=implicit_fsafdo.  */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun fsafdoEnabledWithImplicit() {
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

        val backendAction: LtoBackendAction = setupAndRunToolchainActions("--features=implicit_fsafdo")

        Truth.assertThat(com.google.common.base.Joiner.on(" ").join(backendAction.getArguments()))
            .containsMatch("-fsafdo")
    }

    /** Tests that fsafdo is enabled with LLVM with --features=-implicit_fsafdo --features=fsafdo.  */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun fsafdoEnabledWithFeatureWithoutImplicit() {
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

        val backendAction: LtoBackendAction =
            setupAndRunToolchainActions("--features=-implicit_fsafdo", "--features=fsafdo")

        Truth.assertThat(com.google.common.base.Joiner.on(" ").join(backendAction.getArguments()))
            .containsMatch("-fsafdo")
    }

    /** Tests that fsafdo is enabled with LLVM with --features=fsafdo.  */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun fsafdoEnabledWithExplicitFeature() {
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

        val backendAction: LtoBackendAction = setupAndRunToolchainActions("--features=fsafdo")

        Truth.assertThat(com.google.common.base.Joiner.on(" ").join(backendAction.getArguments()))
            .containsMatch("-fsafdo")
    }

    /** Tests that FSAFDO is not enabled in LLVM without --features=implicit_fsafdo.  */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun fsafdoDisabledWithFeatureWithoutImplicit() {
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

        val backendAction: LtoBackendAction = setupAndRunToolchainActions()

        Truth.assertThat(com.google.common.base.Joiner.on(" ").join(backendAction.getArguments()))
            .doesNotContain("-fsafdo")
    }

    /**
     * Tests that fsafdo is not enabled in LLVM with --features=implicit_fsafdo and
     * --features=-fsafdo.
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun fsafdoDisabledWithExplicitFeature() {
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

        val backendAction: LtoBackendAction =
            setupAndRunToolchainActions("--features=implicit_fsafdo", "--features=-fsafdo")

        Truth.assertThat(com.google.common.base.Joiner.on(" ").join(backendAction.getArguments()))
            .doesNotContain("-fsafdo")
    }

    /** Test that fsafdo is not enable with --features=fsafdo without autofdo.  */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun fsafdoDisabledForNonAutoFDO() {
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

        AnalysisMock.get()
            .ccSupport()
            .setupCcToolchainConfig(
                mockToolsConfig,
                CcToolchainConfig.builder()
                    .withFeatures(
                        CppRuleClasses.THIN_LTO,
                        CppRuleClasses.ENABLE_FSAFDO,
                        MockCcSupport.HOST_AND_NONHOST_CONFIGURATION_FEATURES,
                        CppRuleClasses.SUPPORTS_START_END_LIB,
                        MockCcSupport.IMPLICIT_FSAFDO,
                        MockCcSupport.FSAFDO
                    )
            )

        val testConfig: MutableList<String?> =
            com.google.common.collect.Lists.newArrayList<String?>("--compilation_mode=opt")
        Collections.addAll<String?>(testConfig, "--features=fsafdo")
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

        Truth.assertThat(com.google.common.base.Joiner.on(" ").join(backendAction.getArguments()))
            .doesNotContain("-fsafdo")
    }

    /** Test that fsafdo is not enable with --features=fsafdo for XBinaryFDO.  */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun fsafdoDisabledForXFdo() {
        scratch.file(
            "pkg/BUILD",
            """
        load("@rules_cc//cc:cc_binary.bzl", "cc_binary")
        load("@rules_cc//cc/toolchains:fdo_profile.bzl", "fdo_profile")
        package(features = ["thin_lto"])

        cc_binary(
            name = "bin",
            srcs = ["binfile.cc"],
            malloc = "//base:system_malloc",
        )

        fdo_profile(
            name = "out.xfdo",
            profile = "profiles.xfdo",
        )
        
        """.trimIndent()
        )
        scratch.file("pkg/binfile.cc", "int main() {}")

        AnalysisMock.get()
            .ccSupport()
            .setupCcToolchainConfig(
                mockToolsConfig,
                CcToolchainConfig.builder()
                    .withFeatures(
                        CppRuleClasses.THIN_LTO,
                        CppRuleClasses.XBINARYFDO,
                        CppRuleClasses.ENABLE_XFDO_THINLTO,
                        CppRuleClasses.ENABLE_FSAFDO,
                        MockCcSupport.HOST_AND_NONHOST_CONFIGURATION_FEATURES,
                        CppRuleClasses.SUPPORTS_START_END_LIB,
                        MockCcSupport.IMPLICIT_FSAFDO,
                        MockCcSupport.FSAFDO,
                        MockCcSupport.XFDO_IMPLICIT_THINLTO
                    )
            )

        val testConfig: MutableList<String?> =
            com.google.common.collect.Lists.newArrayList<String?>(
                "--xbinary_fdo=//pkg:out.xfdo",
                "--compilation_mode=opt"
            )
        Collections.addAll<String?>(testConfig, "--features=fsafdo")
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

        Truth.assertThat(com.google.common.base.Joiner.on(" ").join(backendAction.getArguments()))
            .doesNotContain("-fsafdo")
    }
}
