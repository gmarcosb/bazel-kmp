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

import com.google.devtools.build.lib.actions.Artifact

/** Tests for cc_binary with FDO.  */
@RunWith(JUnit4::class)
class CcBinaryFdoTest : BuildViewTestCase() {
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testActionGraph() {
        AnalysisMock.get()
            .ccSupport()
            .setupCcToolchainConfig(mockToolsConfig, CcToolchainConfig.builder())
        useConfiguration(
            "--fdo_profile=//:mock_profile",
            "--compilation_mode=opt",
            "--platforms=" + TestConstants.PLATFORM_LABEL,
            "--experimental_platform_in_output_dir",
            java.lang.String.format(
                "--experimental_override_name_platform_in_output_dir=%s=k8",
                TestConstants.PLATFORM_LABEL
            )
        )

        scratch.file("binary.cc", "int main() { return 0; }")
        scratch.file(
            "BUILD",
            "load('@rules_cc//cc:cc_binary.bzl', 'cc_binary')",
            "load('@rules_cc//cc/toolchains:fdo_profile.bzl', 'fdo_profile')",
            "genrule(name = 'generate-mock-profraw',",
            "    outs = ['mock.profraw'],",
            "    cmd = 'touch $@',",
            ")",
            "",
            "fdo_profile(name = 'mock_profile',",
            "     profile = 'mock.profraw',",
            ")",
            "",
            "cc_binary(name = 'binary',",
            "    srcs = ['binary.cc'],",
            ")"
        )

        // Check the compile action uses a profdata file
        val compileAction: CppCompileAction =
            getGeneratingAction(
                getBinArtifact("_objs/binary/binary.o", getConfiguredTarget("//:binary"))
            ) as CppCompileAction
        assertThat(compileAction).isNotNull()
        assertThat(compileAction.getArguments())
            .comparingElementsUsing(MATCHES_REGEX)
            .contains("-fprofile-use=bl?azel?-out/k8-opt/bin/.*/mock.profdata")
        val profData: Artifact =
            ActionsTestUtil.getFirstArtifactEndingWith(compileAction.getInputs(), ".profdata")
        assertThat(profData).isNotNull()

        // Get the action which generates the profdata file from the profraw file
        val profDataAction: SpawnAction = getGeneratingAction(profData) as SpawnAction
        assertThat(profDataAction).isNotNull()
        val profRawSymlink: Artifact =
            ActionsTestUtil.getFirstArtifactEndingWith(profDataAction.getInputs(), ".profraw")
        assertThat(profRawSymlink).isNotNull()

        // Make sure the profData action is from the genrule
        val profRawSymlinkAction: SymlinkAction = getGeneratingAction(profRawSymlink) as SymlinkAction
        assertThat(profRawSymlinkAction).isNotNull()
        val profRaw: Artifact =
            ActionsTestUtil.getFirstArtifactEndingWith(profRawSymlinkAction.getInputs(), ".profraw")
        assertThat(profRaw).isNotNull()

        // Make sure the symlink input is the genrule defined in the BUILD file
        val profRawAction: GenRuleAction = getGeneratingAction(profRaw) as GenRuleAction
        Truth.assertThat(profRawAction).isNotNull()
        assertThat(profRawAction.getOwner().getLabel().getCanonicalForm())
            .isEqualTo("//:generate-mock-profraw")
    }

    companion object {
        private val MATCHES_REGEX: Correspondence<String?, String?> =
            Correspondence.from<String?, String?>(BinaryPredicate { a: String?, b: String? ->
                java.util.regex.Pattern.matches(
                    b,
                    a
                )
            }, "matches")
    }
}
