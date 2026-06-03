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

import com.google.devtools.build.lib.analysis.ConfiguredTarget

/** Tests the output groups of cc_library.  */
@RunWith(JUnit4::class)
class CppOutputGroupsTest : BuildViewTestCase() {
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testStaticLibraryOnlyOutputGroups() {
        getAnalysisMock()
            .ccSupport()
            .setupCcToolchainConfig(
                mockToolsConfig,
                CcToolchainConfig.builder().withFeatures(CppRuleClasses.SUPPORTS_DYNAMIC_LINKER)
            )
        scratch.file("src.cc")
        scratch.file(
            "a/BUILD",
            """
        load("@rules_cc//cc:cc_library.bzl", "cc_library")
        cc_library(
            name = "lib",
            srcs = ["src.cc"],
            linkstatic = 1,
            alwayslink = 0,
        )

        filegroup(
            name = "group_archive",
            srcs = [":lib"],
            output_group = "archive",
        )

        filegroup(
            name = "group_dynamic",
            srcs = [":lib"],
            output_group = "dynamic_library",
        )
        
        """.trimIndent()
        )

        val groupArchive: ConfiguredTarget = getConfiguredTarget("//a:group_archive")
        val groupDynamic: ConfiguredTarget = getConfiguredTarget("//a:group_dynamic")

        assertThat(ActionsTestUtil.prettyArtifactNames(getFilesToBuild(groupArchive)))
            .containsExactly("a/liblib.a")
        assertThat(ActionsTestUtil.prettyArtifactNames(getFilesToBuild(groupDynamic))).isEmpty()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSharedLibraryOnlyOutputGroups() {
        getAnalysisMock()
            .ccSupport()
            .setupCcToolchainConfig(
                mockToolsConfig,
                CcToolchainConfig.builder().withFeatures(CppRuleClasses.SUPPORTS_DYNAMIC_LINKER)
            )
        scratch.file("src.cc")
        scratch.file(
            "a/BUILD",
            """
        load("@rules_cc//cc:cc_library.bzl", "cc_library")
        cc_library(
            name = "lib",
            srcs = ["src.cc"],
            linkstatic = 1,
            alwayslink = 1,
        )

        filegroup(
            name = "group_archive",
            srcs = [":lib"],
            output_group = "archive",
        )

        filegroup(
            name = "group_dynamic",
            srcs = [":lib"],
            output_group = "dynamic_library",
        )
        
        """.trimIndent()
        )

        val groupArchive: ConfiguredTarget = getConfiguredTarget("//a:group_archive")
        val groupDynamic: ConfiguredTarget = getConfiguredTarget("//a:group_dynamic")

        assertThat(ActionsTestUtil.prettyArtifactNames(getFilesToBuild(groupArchive)))
            .containsExactly("a/liblib.lo")
        assertThat(ActionsTestUtil.prettyArtifactNames(getFilesToBuild(groupDynamic))).isEmpty()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testStaticAndDynamicLibraryOutputGroups() {
        getAnalysisMock()
            .ccSupport()
            .setupCcToolchainConfig(
                mockToolsConfig,
                CcToolchainConfig.builder().withFeatures(CppRuleClasses.SUPPORTS_DYNAMIC_LINKER)
            )
        scratch.file("src.cc")
        scratch.file(
            "a/BUILD",
            """
        load("@rules_cc//cc:cc_library.bzl", "cc_library")
        cc_library(
            name = "lib",
            srcs = ["src.cc"],
            linkstatic = 0,
            alwayslink = 0,
        )

        filegroup(
            name = "group_archive",
            srcs = [":lib"],
            output_group = "archive",
        )

        filegroup(
            name = "group_dynamic",
            srcs = [":lib"],
            output_group = "dynamic_library",
        )
        
        """.trimIndent()
        )

        val groupArchive: ConfiguredTarget = getConfiguredTarget("//a:group_archive")
        val groupDynamic: ConfiguredTarget = getConfiguredTarget("//a:group_dynamic")

        assertThat(ActionsTestUtil.prettyArtifactNames(getFilesToBuild(groupArchive)))
            .containsExactly("a/liblib.a")
        // If supports_interface_shared_objects is true, .ifso could also be generated.
        // So we here use contains instead containsExactly.
        com.google.common.truth.Subject.contains("a/liblib.so")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSharedAndDynamicLibraryOutputGroups() {
        getAnalysisMock()
            .ccSupport()
            .setupCcToolchainConfig(
                mockToolsConfig,
                CcToolchainConfig.builder().withFeatures(CppRuleClasses.SUPPORTS_DYNAMIC_LINKER)
            )
        scratch.file("src.cc")
        scratch.file(
            "a/BUILD",
            """
        load("@rules_cc//cc:cc_library.bzl", "cc_library")
        cc_library(
            name = "lib",
            srcs = ["src.cc"],
            linkstatic = 0,
            alwayslink = 1,
        )

        filegroup(
            name = "group_archive",
            srcs = [":lib"],
            output_group = "archive",
        )

        filegroup(
            name = "group_dynamic",
            srcs = [":lib"],
            output_group = "dynamic_library",
        )
        
        """.trimIndent()
        )

        val groupArchive: ConfiguredTarget = getConfiguredTarget("//a:group_archive")
        val groupDynamic: ConfiguredTarget = getConfiguredTarget("//a:group_dynamic")

        assertThat(ActionsTestUtil.prettyArtifactNames(getFilesToBuild(groupArchive)))
            .containsExactly("a/liblib.lo")
        // If supports_interface_shared_objects is true, .ifso could also be generated.
        // So we here use contains instead containsExactly.
        com.google.common.truth.Subject.contains("a/liblib.so")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testModuleOutputGroups() {
        getAnalysisMock()
            .ccSupport()
            .setupCcToolchainConfig(
                mockToolsConfig,
                CcToolchainConfig.builder().withFeatures("header_modules_feature_configuration")
            )
        scratch.file("header.h")
        scratch.file(
            "a/BUILD",
            """
        load("@rules_cc//cc:cc_library.bzl", "cc_library")
        cc_library(
            name = "lib",
            hdrs = ["src.h"],
            features = ["header_modules"],
        )

        filegroup(
            name = "group_modules",
            srcs = [":lib"],
            output_group = "module_files",
        )
        
        """.trimIndent()
        )

        val groupArchive: ConfiguredTarget = getConfiguredTarget("//a:group_modules")

        assertThat(ActionsTestUtil.prettyArtifactNames(getFilesToBuild(groupArchive)))
            .containsExactly("a/_objs/lib/lib.pcm")
    }
}
