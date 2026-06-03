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
package com.google.devtools.build.lib.rules.proto

import com.google.devtools.build.lib.analysis.TransitiveInfoCollection

/** Unit tests for `proto_lang_toolchain`.  */
@RunWith(JUnit4::class)
class ProtoLangToolchainTest : BuildViewTestCase() {
    @Before
    @Throws(java.lang.Exception::class)
    fun setUp() {
        MockProtoSupport.setup(mockToolsConfig)
        useConfiguration("--protocopt=--myflag")
        invalidatePackages()
    }

    @Throws(java.lang.Exception::class)
    private fun validateProtoLangToolchain(toolchain: ProtoLangToolchainProvider) {
        Truth.assertThat(toolchain.outReplacementFormatFlag()).isEqualTo("cmd-line:%s")
        Truth.assertThat(toolchain.pluginFormatFlag()).isEqualTo("--plugin=%s")
        assertThat(toolchain.pluginExecutable().getExecutable().getRootRelativePathString())
            .isEqualTo("third_party/x/plugin")

        val runtimes: TransitiveInfoCollection? = toolchain.runtime()
        assertThat(runtimes.label).isEqualTo(Label.parseCanonical("//third_party/x:runtime"))

        Truth.assertThat(toolchain.protocOpts()).containsExactly("--myflag")

        Truth.assertThat(toolchain.progressMessage()).isEqualTo("Progress Message %{label}")
        Truth.assertThat(toolchain.mnemonic()).isEqualTo("MyMnemonic")
    }

    @Throws(java.lang.Exception::class)
    private fun validateProtoCompiler(toolchain: ProtoLangToolchainProvider, protocLabel: String?) {
        val actualProtocLabel: Label = getConfiguredTarget(protocLabel).getActual().getLabel()
        assertThat(toolchain.protoc().getExecutable().prettyPrint())
            .isEqualTo(
                actualProtocLabel
                    .getRepository()
                    .getExecPath(false)
                    .getRelative(actualProtocLabel.toPathFragment())
                    .getPathString()
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun protoToolchain() {
        scratch.file(
            "third_party/x/BUILD",
            """
        load("@rules_cc//cc:cc_binary.bzl", "cc_binary")
        load("@rules_cc//cc:cc_library.bzl", "cc_library")
        load('@com_google_protobuf//bazel:proto_library.bzl', 'proto_library')
        licenses(["unencumbered"])

        cc_binary(
            name = "plugin",
            srcs = ["plugin.cc"],
        )

        cc_library(
            name = "runtime",
            srcs = ["runtime.cc"],
        )

        filegroup(
            name = "descriptors",
            srcs = [
                "descriptor.proto",
                "metadata.proto",
            ],
        )

        filegroup(
            name = "any",
            srcs = ["any.proto"],
        )

        proto_library(
            name = "denied",
            srcs = [
                ":any",
                ":descriptors",
            ],
        )
        
        """.trimIndent()
        )

        scratch.file(
            "foo/BUILD",
            TestConstants.LOAD_PROTO_LANG_TOOLCHAIN,
            "licenses(['unencumbered'])",
            "proto_lang_toolchain(",
            "    name = 'toolchain',",
            "    command_line = 'cmd-line:$(OUT)',",
            "    plugin_format_flag = '--plugin=%s',",
            "    plugin = '//third_party/x:plugin',",
            "    runtime = '//third_party/x:runtime',",
            "    progress_message = 'Progress Message %{label}',",
            "    mnemonic = 'MyMnemonic',",
            ")"
        )

        update(
            com.google.common.collect.ImmutableList.of<String?>("//foo:toolchain"),
            false,
            1,
            true,
            com.google.common.eventbus.EventBus()
        )
        val toolchain: ProtoLangToolchainProvider =
            ProtoLangToolchainProvider.Companion.get(getConfiguredTarget("//foo:toolchain"))

        validateProtoLangToolchain(toolchain)
        validateProtoCompiler(toolchain, ProtoConstants.DEFAULT_PROTOC_LABEL)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun protoToolchainResolution_enabled() {
        setBuildLanguageOptions("--incompatible_enable_proto_toolchain_resolution")
        scratch.file(
            "third_party/x/BUILD",
            """
        load("@rules_cc//cc:cc_binary.bzl", "cc_binary")
        load("@rules_cc//cc:cc_library.bzl", "cc_library")
        load('@com_google_protobuf//bazel:proto_library.bzl', 'proto_library')
        licenses(["unencumbered"])

        cc_binary(
            name = "plugin",
            srcs = ["plugin.cc"],
        )

        cc_library(
            name = "runtime",
            srcs = ["runtime.cc"],
        )

        filegroup(
            name = "descriptors",
            srcs = [
                "descriptor.proto",
                "metadata.proto",
            ],
        )

        filegroup(
            name = "any",
            srcs = ["any.proto"],
        )

        proto_library(
            name = "denied",
            srcs = [
                ":any",
                ":descriptors",
            ],
        )
        
        """.trimIndent()
        )
        scratch.file(
            "foo/BUILD",
            TestConstants.LOAD_PROTO_LANG_TOOLCHAIN,
            "licenses(['unencumbered'])",
            "proto_lang_toolchain(",
            "    name = 'toolchain',",
            "    command_line = 'cmd-line:$(OUT)',",
            "    plugin_format_flag = '--plugin=%s',",
            "    plugin = '//third_party/x:plugin',",
            "    runtime = '//third_party/x:runtime',",
            "    progress_message = 'Progress Message %{label}',",
            "    mnemonic = 'MyMnemonic',",
            ")"
        )

        update(
            com.google.common.collect.ImmutableList.of<String?>("//foo:toolchain"),
            false,
            1,
            true,
            com.google.common.eventbus.EventBus()
        )
        val toolchain: ProtoLangToolchainProvider =
            ProtoLangToolchainProvider.Companion.get(getConfiguredTarget("//foo:toolchain"))

        validateProtoLangToolchain(toolchain)
        validateProtoCompiler(toolchain, ProtoConstants.DEFAULT_PROTOC_LABEL)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun protoToolchainBlacklistProtoLibraries() {
        scratch.file(
            "third_party/x/BUILD",
            "load('@com_google_protobuf//bazel:proto_library.bzl', 'proto_library')",
            "licenses(['unencumbered'])",
            "load('@rules_cc//cc:cc_binary.bzl', 'cc_binary')",
            "load('@rules_cc//cc:cc_library.bzl', 'cc_library')",
            "cc_binary(name = 'plugin', srcs = ['plugin.cc'])",
            "cc_library(name = 'runtime', srcs = ['runtime.cc'])",
            "proto_library(name = 'descriptors', srcs = ['metadata.proto', 'descriptor.proto'])",
            "proto_library(name = 'any', srcs = ['any.proto'], strip_import_prefix = '/third_party')"
        )

        scratch.file(
            "foo/BUILD",
            TestConstants.LOAD_PROTO_LANG_TOOLCHAIN,
            "proto_lang_toolchain(",
            "    name = 'toolchain',",
            "    command_line = 'cmd-line:$(OUT)',",
            "    plugin_format_flag = '--plugin=%s',",
            "    plugin = '//third_party/x:plugin',",
            "    runtime = '//third_party/x:runtime',",
            "    progress_message = 'Progress Message %{label}',",
            "    mnemonic = 'MyMnemonic',",
            ")"
        )

        update(
            com.google.common.collect.ImmutableList.of<String?>("//foo:toolchain"),
            false,
            1,
            true,
            com.google.common.eventbus.EventBus()
        )
        val toolchain: ProtoLangToolchainProvider =
            ProtoLangToolchainProvider.Companion.get(getConfiguredTarget("//foo:toolchain"))

        validateProtoLangToolchain(toolchain)
        validateProtoCompiler(toolchain, ProtoConstants.DEFAULT_PROTOC_LABEL)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun protoToolchainBlacklistTransitiveProtos() {
        scratch.file(
            "third_party/x/BUILD",
            "load('@com_google_protobuf//bazel:proto_library.bzl', 'proto_library')",
            "licenses(['unencumbered'])",
            "load('@rules_cc//cc:cc_binary.bzl', 'cc_binary')",
            "load('@rules_cc//cc:cc_library.bzl', 'cc_library')",
            "cc_binary(name = 'plugin', srcs = ['plugin.cc'])",
            "cc_library(name = 'runtime', srcs = ['runtime.cc'])",
            "proto_library(name = 'descriptors', srcs = ['metadata.proto', 'descriptor.proto'])",
            "proto_library(name = 'any', srcs = ['any.proto'], deps = [':descriptors'])"
        )

        scratch.file(
            "foo/BUILD",
            TestConstants.LOAD_PROTO_LANG_TOOLCHAIN,
            "proto_lang_toolchain(",
            "    name = 'toolchain',",
            "    command_line = 'cmd-line:$(OUT)',",
            "    plugin_format_flag = '--plugin=%s',",
            "    plugin = '//third_party/x:plugin',",
            "    runtime = '//third_party/x:runtime',",
            "    progress_message = 'Progress Message %{label}',",
            "    mnemonic = 'MyMnemonic',",
            ")"
        )

        update(
            com.google.common.collect.ImmutableList.of<String?>("//foo:toolchain"),
            false,
            1,
            true,
            com.google.common.eventbus.EventBus()
        )
        val toolchain: ProtoLangToolchainProvider =
            ProtoLangToolchainProvider.Companion.get(getConfiguredTarget("//foo:toolchain"))

        validateProtoLangToolchain(toolchain)
        validateProtoCompiler(toolchain, ProtoConstants.DEFAULT_PROTOC_LABEL)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun optionalFieldsAreEmpty() {
        scratch.file(
            "foo/BUILD",
            TestConstants.LOAD_PROTO_LANG_TOOLCHAIN,
            "proto_lang_toolchain(",
            "    name = 'toolchain',",
            "    command_line = 'cmd-line:$(OUT)',",
            ")"
        )

        update(
            com.google.common.collect.ImmutableList.of<String?>("//foo:toolchain"),
            false,
            1,
            true,
            com.google.common.eventbus.EventBus()
        )
        val toolchain: ProtoLangToolchainProvider =
            ProtoLangToolchainProvider.Companion.get(getConfiguredTarget("//foo:toolchain"))

        assertThat(toolchain.pluginExecutable()).isNull()
        assertThat(toolchain.runtime()).isNull()
        Truth.assertThat(toolchain.mnemonic()).isEqualTo("GenProto")
    }
}
