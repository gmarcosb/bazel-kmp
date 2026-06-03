// Copyright 2021 The Bazel Authors. All rights reserved.
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

/** Unit tests for `CcToolchainInputsTransitionFactory`.  */
@RunWith(JUnit4::class)
class CcToolchainInputsTransitionFactoryTest : BuildViewTestCase() {
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testToolchain_usesTargetPlatform() {
        scratch.file(
            "a/BUILD",
            """
        load("@rules_cc//cc/toolchains:cc_toolchain.bzl", "cc_toolchain")
        load(":cc_toolchain_config.bzl", "cc_toolchain_config")

        filegroup(
            name = "all_files",
            srcs = ["a.txt"],
        )

        cc_toolchain(
            name = "toolchain",
            all_files = ":all_files",
            ar_files = ":all_files",
            as_files = ":all_files",
            compiler_files = ":all_files",
            compiler_files_without_includes = ":all_files",
            dwp_files = ":all_files",
            linker_files = ":all_files",
            objcopy_files = ":all_files",
            strip_files = ":all_files",
            toolchain_config = ":does-not-matter-config",
            toolchain_identifier = "does-not-matter",
        )

        cc_toolchain_config(name = "does-not-matter-config")
        
        """.trimIndent()
        )

        scratch.file("a/cc_toolchain_config.bzl", MockCcSupport.EMPTY_CC_TOOLCHAIN)

        val toolchainTarget: ConfiguredTarget = getConfiguredTarget("//a:toolchain")
        assertThat(toolchainTarget).isNotNull()

        val allFiles: ConfiguredTarget = getDirectPrerequisite(toolchainTarget, "//a:all_files")
        assertThat(allFiles).isNotNull()

        val coreOptions: CoreOptions = getConfiguration(allFiles).getOptions().get(CoreOptions::class.java)
        assertThat(coreOptions).isNotNull()
        assertThat(coreOptions.getIsExec()).isFalse()
        // if isExec is false, then allFiles is building for the target platform
    }
}
