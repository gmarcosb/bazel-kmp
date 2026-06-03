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

import com.google.devtools.build.lib.analysis.ConfiguredTarget

/** Tests for platform-based toolchain selection in the c++ rules.  */
@RunWith(JUnit4::class)
class CcToolchainSelectionTest : BuildViewTestCase() {
    @Before
    @Throws(java.lang.Exception::class)
    fun setup() {
        MockPlatformSupport.addMockK8Platform(
            mockToolsConfig, analysisMock.ccSupport().getMockCrosstoolLabel()
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testResolvedCcToolchain() {
        useConfiguration(
            "--incompatible_enable_cc_toolchain_resolution",
            "--experimental_platforms=//mock_platform:mock-k8-platform",
            "--extra_toolchains=//mock_platform:toolchain_cc-compiler-k8"
        )
        val target: ConfiguredTarget =
            ScratchAttributeWriter.fromLabelString(
                this,
                "load('@rules_cc//cc:cc_library.bzl', 'cc_library')",
                "cc_library",
                "//lib"
            )
                .setList("srcs", "a.cc")
                .write()
        val toolchainInfo: ToolchainInfo =
            getRuleContext(target).getToolchainInfo(Label.parseCanonical(CPP_TOOLCHAIN_TYPE))
        val toolchain: CcToolchainProvider = CcToolchainProvider.wrap(toolchainInfo.getValue("cc") as Info?)

        assertThat(toolchain.getToolchainIdentifier()).endsWith("k8")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testToolchainSelectionWithPlatforms() {
        useConfiguration(
            "--incompatible_enable_cc_toolchain_resolution",
            "--experimental_platforms=//mock_platform:mock-k8-platform",
            "--extra_toolchains=//mock_platform:toolchain_cc-compiler-k8"
        )
        val target: ConfiguredTarget =
            ScratchAttributeWriter.fromLabelString(
                this,
                "load('@rules_cc//cc:cc_library.bzl', 'cc_library')",
                "cc_library",
                "//lib"
            )
                .setList("srcs", "a.cc")
                .write()
        val toolchainInfo: ToolchainInfo =
            getRuleContext(target).getToolchainInfo(Label.parseCanonical(CPP_TOOLCHAIN_TYPE))
        val toolchain: CcToolchainProvider = CcToolchainProvider.wrap(toolchainInfo.getValue("cc") as Info?)

        assertThat(toolchain.getToolchainIdentifier()).endsWith("k8")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testIncompleteCcToolchain() {
        mockToolsConfig.create(
            "incomplete_toolchain/BUILD",
            "toolchain(",
            "   name = 'incomplete_toolchain_cc-compiler-piii',",
            "   toolchain_type = '" + CPP_TOOLCHAIN_TYPE + "',",
            "   toolchain = ':incomplete_cc-compiler-piii',",
            "   target_compatible_with = ['//mock_platform:mock_value']",
            ")",
            "cc_toolchain(",
            "   name = 'incomplete_cc-compiler-piii',",
            "   cpu = 'piii',",
            "   ar_files = 'ar-piii',",
            "   as_files = 'as-piii',",
            "   compiler_files = 'compile-piii',",
            "   dwp_files = 'dwp-piii',",
            "   linker_files = 'link-piii',",
            "   strip_files = ':dummy_filegroup',",
            "   objcopy_files = 'objcopy-piii',",
            "   all_files = ':dummy_filegroup',",
            ")",
            "filegroup(name = 'dummy_filegroup')"
        )
        mockToolsConfig.append("mock_platform/BUILD", "platform(name = 'mock-piii-platform')")

        useConfiguration(
            "--incompatible_enable_cc_toolchain_resolution",
            "--experimental_platforms=//mock_platform:mock-piii-platform",
            "--extra_toolchains=//incomplete_toolchain:incomplete_toolchain_cc-compiler-piii"
        )

        // should not throw.
    }

    companion object {
        private val CPP_TOOLCHAIN_TYPE = TestConstants.TOOLS_REPOSITORY.toString() + "//tools/cpp:toolchain_type"
    }
}
