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
package com.google.devtools.build.lib.rules.objc

import com.google.common.base.Joiner
import com.google.common.truth.Subject
import com.google.devtools.build.lib.actions.Action
import org.junit.Test

/** Test case for the use of the OSX crosstool.  */
@RunWith(JUnit4::class)
class AppleToolchainSelectionTest : ObjcRuleTestCase() {
    @Test
    @Throws(Exception::class)
    fun testToolchainSelectionCcDepDefault() {
        ScratchAttributeWriter.fromLabelString(
            this,
            "load('@rules_cc//cc:cc_library.bzl', 'cc_library')",
            "cc_library",
            "//b:lib"
        )
            .setList("srcs", "b.cc")
            .write()
        ObjcRuleTestCase.Companion.addAppleBinaryStarlarkRule(scratch)
        scratch.file(
            "a/BUILD",
            """
        load("//test_starlark:apple_binary_starlark.bzl", "apple_binary_starlark")

        apple_binary_starlark(
            name = "bin",
            platform_type = "ios",
            deps = ["//b:lib"],
        )
        
        """.trimIndent()
        )
        val lipoAction: Action = actionProducingArtifact("//a:bin", "_lipobin")
        val binArtifact: Artifact = lipoAction.getInputs().getSingleton()
        val linkAction: SpawnAction = getGeneratingAction(binArtifact) as SpawnAction
        val ccArchiveAction: SpawnAction =
            getGeneratingAction(getFirstArtifactEndingWith(linkAction.getInputs(), "liblib.a")) as SpawnAction
        val ccObjectFile: Artifact = getFirstArtifactEndingWith(ccArchiveAction.getInputs(), ".o")
        val ccCompileAction: CommandAction = getGeneratingAction(ccObjectFile) as CommandAction
        Subject.contains("tools/osx/crosstool/iossim/wrapped_clang")
    }

    @Test
    @Throws(Exception::class)
    fun testToolchainSelectionCcDepDevice() {
        useConfiguration(
            "--apple_platform_type=ios",
            "--ios_multi_cpus=arm64",
            "--platforms=" + MockObjcSupport.IOS_ARM64
        )
        ScratchAttributeWriter.fromLabelString(
            this,
            "load('@rules_cc//cc:cc_library.bzl', 'cc_library')",
            "cc_library",
            "//b:lib"
        )
            .setList("srcs", "b.cc")
            .write()
        ObjcRuleTestCase.Companion.addAppleBinaryStarlarkRule(scratch)
        scratch.file(
            "a/BUILD",
            """
        load("//test_starlark:apple_binary_starlark.bzl", "apple_binary_starlark")

        apple_binary_starlark(
            name = "bin",
            platform_type = "ios",
            deps = ["//b:lib"],
        )
        
        """.trimIndent()
        )
        val lipoAction: Action = actionProducingArtifact("//a:bin", "_lipobin")
        val binArtifact: Artifact =
            lipoAction.getInputs().toList().stream()
                .filter({ artifact -> artifact.getPath().toString().contains("arm64") })
                .findAny()
                .get()
        val linkAction: SpawnAction = getGeneratingAction(binArtifact) as SpawnAction
        val ccArchiveAction: SpawnAction =
            getGeneratingAction(getFirstArtifactEndingWith(linkAction.getInputs(), "liblib.a")) as SpawnAction
        val ccObjectFile: Artifact = getFirstArtifactEndingWith(ccArchiveAction.getInputs(), ".o")
        val ccCompileAction: CommandAction = getGeneratingAction(ccObjectFile) as CommandAction
        Subject.contains("tools/osx/crosstool/ios/wrapped_clang")
    }

    @Test
    @Throws(Exception::class)
    fun testToolchainSelectionMultiArchIos() {
        useConfiguration("--ios_multi_cpus=arm64,arm64e")
        ScratchAttributeWriter.fromLabelString(
            this,
            "load('@rules_cc//cc:cc_library.bzl', 'cc_library')",
            "cc_library",
            "//b:lib"
        )
            .setList("srcs", "a.cc")
            .write()
        ObjcRuleTestCase.Companion.addAppleBinaryStarlarkRule(scratch)
        scratch.file(
            "a/BUILD",
            """
        load("//test_starlark:apple_binary_starlark.bzl", "apple_binary_starlark")

        apple_binary_starlark(
            name = "bin",
            platform_type = "ios",
            deps = ["//b:lib"],
        )
        
        """.trimIndent()
        )
        val lipoAction: Action = actionProducingArtifact("//a:bin", "_lipobin")
        val binArtifact: Artifact =
            lipoAction.getInputs().toList().stream()
                .filter({ artifact -> artifact.getPath().toString().contains("arm64") })
                .findAny()
                .get()
        val linkAction: SpawnAction = getGeneratingAction(binArtifact) as SpawnAction
        val objcLibArchiveAction: SpawnAction =
            getGeneratingAction(getFirstArtifactEndingWith(linkAction.getInputs(), "liblib.a")) as SpawnAction
        Truth.assertThat(Joiner.on(" ").join(objcLibArchiveAction.getArguments())).contains("ios_arm64")
    }

    @Test
    @Throws(Exception::class)
    fun testToolchainSelectionMultiArchWatchos() {
        useConfiguration("--ios_multi_cpus=arm64,arm64e", "--watchos_cpus=arm64_32")
        ScratchAttributeWriter.fromLabelString(
            this,
            "load('@rules_cc//cc:cc_library.bzl', 'cc_library')",
            "cc_library",
            "//b:lib"
        )
            .setList("srcs", "a.cc")
            .write()
        ObjcRuleTestCase.Companion.addAppleBinaryStarlarkRule(scratch)
        scratch.file(
            "a/BUILD",
            """
        load("//test_starlark:apple_binary_starlark.bzl", "apple_binary_starlark")

        apple_binary_starlark(
            name = "bin",
            platform_type = "watchos",
            deps = ["//b:lib"],
        )
        
        """.trimIndent()
        )

        val linkAction: CommandAction = linkAction("//a:bin")
        val objcLibCompileAction: SpawnAction =
            getGeneratingAction(getFirstArtifactEndingWith(linkAction.getInputs(), "liblib.a")) as SpawnAction
        Truth.assertThat(Joiner.on(" ").join(objcLibCompileAction.getArguments()))
            .contains("watchos_arm64_32")
    }
}
