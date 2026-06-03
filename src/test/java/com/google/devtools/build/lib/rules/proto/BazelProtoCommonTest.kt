// Copyright 2022 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.actions.ResourceSet

/** Unit test for proto_common module.  */
@RunWith(TestParameterInjector::class)
class BazelProtoCommonTest : BuildViewTestCase() {
    @Before
    @Throws(java.lang.Exception::class)
    fun setup() {
        MockProtoSupport.setup(mockToolsConfig)
        invalidatePackages()

        scratch.file(
            "third_party/x/BUILD",
            """
        load("@rules_cc//cc:cc_binary.bzl", "cc_binary")
        load("@rules_cc//cc:cc_library.bzl", "cc_library")
        load("@com_google_protobuf//bazel:proto_library.bzl", "proto_library")
        licenses(['unencumbered'])
        cc_binary(name = 'plugin', srcs = ['plugin.cc'])
        cc_library(name = 'runtime', srcs = ['runtime.cc'])
        filegroup(name = 'descriptors', srcs = ['metadata.proto', 'descriptor.proto'])
        filegroup(name = 'any', srcs = ['any.proto'])
        filegroup(name = 'something', srcs = ['something.proto'])
        proto_library(name = 'mixed', srcs = [':descriptors', ':something'])
        proto_library(name = 'denied', srcs = [':descriptors', ':any'])
        
        """.trimIndent()
        )
        scratch.file(
            "foo/BUILD",
            TestConstants.LOAD_PROTO_LANG_TOOLCHAIN,
            "proto_lang_toolchain(",
            "    name = 'toolchain',",
            "    command_line = '--java_out=param1,param2:$(OUT)',",
            "    plugin_format_flag = '--plugin=%s',",
            "    plugin = '//third_party/x:plugin',",
            "    runtime = '//third_party/x:runtime',",
            "    blacklisted_protos = ['//third_party/x:denied'],",
            "    progress_message = 'Progress Message %{label}',",
            "    mnemonic = 'MyMnemonic',",
            "    allowlist_different_package ="
                    + " '//tools/allowlists/proto_library_allowlists:lang_proto_library_allowed_in_different_package'",
            ")",
            "proto_lang_toolchain(",
            "    name = 'toolchain_noplugin',",
            "    command_line = '--java_out=param1,param2:$(OUT)',",
            "    runtime = '//third_party/x:runtime',",
            "    blacklisted_protos = ['//third_party/x:denied'],",
            "    progress_message = 'Progress Message %{label}',",
            "    mnemonic = 'MyMnemonic',",
            ")"
        )

        mockToolsConfig.overwrite(
            "tools/allowlists/proto_library_allowlists/BUILD",
            """
        package_group(
            name='lang_proto_library_allowed_in_different_package',
            packages=['//...', '-//test/...'],
        )
        
        """.trimIndent()
        )

        scratch.file(
            "foo/generate.bzl",
            """
load("@com_google_protobuf//bazel/common:proto_info.bzl", "ProtoInfo")
load("@com_google_protobuf//bazel/common:proto_common.bzl", "proto_common")
load("@com_google_protobuf//bazel/common:proto_lang_toolchain_info.bzl", "ProtoLangToolchainInfo")
def _resource_set_callback(os, inputs_size):
   return {'memory': 25 + 0.15 * inputs_size, 'cpu': 1}
def _impl(ctx):
  outfile = ctx.actions.declare_file('out')
  kwargs = {}
  if ctx.attr.plugin_output == 'single':
    kwargs['plugin_output'] = outfile.path
  elif ctx.attr.plugin_output == 'multiple':
    kwargs['plugin_output'] = ctx.genfiles_dir.path
  elif ctx.attr.plugin_output == 'wrong':
    kwargs['plugin_output'] = ctx.genfiles_dir.path + '///'
  if ctx.attr.additional_args:
    additional_args = ctx.actions.args()
    additional_args.add_all(ctx.attr.additional_args)
    kwargs['additional_args'] = additional_args
  if ctx.files.additional_tools:
    kwargs['additional_tools'] = ctx.files.additional_tools
  if ctx.files.additional_inputs:
    kwargs['additional_inputs'] = depset(ctx.files.additional_inputs)
  if ctx.attr.use_resource_set:
    kwargs['resource_set'] = _resource_set_callback
  if ctx.attr.progress_message:
    kwargs['experimental_progress_message'] = ctx.attr.progress_message
  proto_common.compile(
    ctx.actions,
    ctx.attr.proto_dep[ProtoInfo],
    ctx.attr.toolchain[ProtoLangToolchainInfo],
    [outfile],
    **kwargs)
  return [DefaultInfo(files = depset([outfile]))]
compile_rule = rule(_impl,
  attrs = {
     'proto_dep': attr.label(),
     'plugin_output': attr.string(),
     'toolchain': attr.label(default = '//foo:toolchain'),
     'additional_args': attr.string_list(),
     'additional_tools': attr.label_list(cfg = 'exec'),
     'additional_inputs': attr.label_list(allow_files = True),
     'use_resource_set': attr.bool(),
     'progress_message': attr.string(),
  })

""".trimIndent()
        )
    }

    /**
     * Verifies usage of `proto_common.compile` with `resource_set` parameter.
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun protoCommonCompile_resourceSet() {
        scratch.file(
            "bar/BUILD",
            "load('@com_google_protobuf//bazel:proto_library.bzl', 'proto_library')",
            "load('//foo:generate.bzl', 'compile_rule')",
            "proto_library(name = 'proto', srcs = ['A.proto'])",
            "compile_rule(name = 'simple', proto_dep = ':proto', use_resource_set = True)"
        )

        val target: ConfiguredTarget = getConfiguredTarget("//bar:simple")

        val spawnAction: SpawnAction = getGeneratingSpawnAction(getBinArtifact("out", target))
        assertThat(
            spawnAction.getResourceSetOrBuilder().buildResourceSet(com.google.devtools.build.lib.util.OS.DARWIN, 0)
        )
            .isEqualTo(ResourceSet.createWithRamCpu(25, 1))
        assertThat(
            spawnAction.getResourceSetOrBuilder().buildResourceSet(com.google.devtools.build.lib.util.OS.LINUX, 2)
        )
            .isEqualTo(ResourceSet.createWithRamCpu(25.3, 1))
    }

    /**
     * Verifies `proto_common.compile` correctly handles external `proto_library
    ` * -es.
     */
    @org.junit.Test
    @TestParameters(
        "{sibling: false, generated: false, expectedFlags:" + " ['-Iexternal/foo\\+']}",
        ("{sibling: false, generated: true, expectedFlags:"
                + " ['-Ibl?azel?-out/k8-fastbuild/bin/external/foo\\+']}"),
        "{sibling: true, generated: false,expectedFlags:" + " ['-I../foo\\+']}",
        ("{sibling: true, generated: true, expectedFlags:"
                + " ['-Ibl?azel?-out/foo\\+/k8-fastbuild/bin']}")
    )
    @Throws(java.lang.Exception::class)
    fun protoCommonCompile_externalProtoLibrary(
        sibling: Boolean, generated: Boolean, expectedFlags: MutableList<String?>
    ) {
        if (!analysisMock.isThisBazel) {
            return
        }
        if (sibling) {
            setBuildLanguageOptions("--experimental_sibling_repository_layout")
        }
        scratch.appendFile(
            "MODULE.bazel",
            "bazel_dep(name = 'foo')",
            "local_path_override(module_name = 'foo', path = '/foo')"
        )
        scratch.file("/foo/MODULE.bazel", "module(name = 'foo')")
        scratch.file(
            "/foo/e/BUILD",
            "load('@com_google_protobuf//bazel:proto_library.bzl', 'proto_library')",
            "proto_library(name='e', srcs=['E.proto'])",
            if (generated)
                "genrule(name = 'generate', srcs = ['A.txt'], cmd = '', outs = ['E.proto'])"
            else
                ""
        )
        scratch.file(
            "bar/BUILD",
            "load('@com_google_protobuf//bazel:proto_library.bzl', 'proto_library')",
            "load('//foo:generate.bzl', 'compile_rule')",
            "proto_library(name = 'proto', srcs = ['A.proto'], deps = ['@foo//e:e'])",
            "compile_rule(name = 'simple', proto_dep = ':proto')"
        )
        invalidatePackages()
        useConfiguration(
            "--platforms=" + TestConstants.PLATFORM_LABEL,
            "--experimental_platform_in_output_dir",
            String.format(
                "--experimental_override_name_platform_in_output_dir=%s=k8",
                TestConstants.PLATFORM_LABEL
            )
        )

        val target: ConfiguredTarget = getConfiguredTarget("//bar:simple")

        val spawnAction: SpawnAction = getGeneratingSpawnAction(getBinArtifact("out", target))
        val cmdLine: MutableList<String?>? = spawnAction.getRemainingArguments()
        Truth.assertThat(cmdLine)
            .comparingElementsUsing<String?, String?>(MATCHES_REGEX)
            .containsExactly(
                "--plugin=bl?azel?-out/[^/]*-exec/bin/third_party/x/plugin",
                expectedFlags.get(0),
                "-I.",
                "bar/A.proto"
            )
            .inOrder()
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
