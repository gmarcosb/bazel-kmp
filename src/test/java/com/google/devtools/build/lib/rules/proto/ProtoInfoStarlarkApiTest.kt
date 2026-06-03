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

import com.google.devtools.build.lib.skyframe.BzlLoadValue.keyForBuild

@RunWith(JUnit4::class)
class ProtoInfoStarlarkApiTest : BuildViewTestCase() {
    @Before
    @Throws(java.lang.Exception::class)
    fun setUp() {
        useConfiguration("--proto_compiler=//proto:compiler") // TODO check do we need that.
        scratch.file(
            "proto/BUILD",
            """
        licenses(["notice"])

        exports_files(["compiler"])
        
        """.trimIndent()
        )
        scratch.file("myinfo/myinfo.bzl", "MyInfo = provider()")
        scratch.file("myinfo/BUILD")
        MockProtoSupport.setup(mockToolsConfig)
        invalidatePackages()
    }

    @Throws(java.lang.Exception::class)
    private fun getMyInfoFromTarget(configuredTarget: ConfiguredTarget): StructImpl? {
        val key: Provider.Key =
            Key(
                keyForBuild(Label.parseCanonical("//myinfo:myinfo.bzl")), "MyInfo"
            )
        return configuredTarget.get(key) as StructImpl?
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testProvider() {
        scratch.file(
            "foo/test.bzl",
            """
        load('@com_google_protobuf//bazel/common:proto_info.bzl', 'ProtoInfo')
        load("//myinfo:myinfo.bzl", "MyInfo")

        def _impl(ctx):
            # NB: This is the modern provider
            provider = ctx.attr.dep[ProtoInfo]
            return MyInfo(direct_sources = provider.direct_sources)

        test = rule(implementation = _impl, attrs = {"dep": attr.label()})
        
        """.trimIndent()
        )

        scratch.file(
            "foo/BUILD",
            "load('@com_google_protobuf//bazel:proto_library.bzl', 'proto_library')",
            "load(':test.bzl', 'test')",
            "test(name='test', dep=':proto')",
            "proto_library(name='proto', srcs=['p.proto'])"
        )

        val test: ConfiguredTarget = getConfiguredTarget("//foo:test")
        val directSources: Iterable<Artifact?>? =
            getMyInfoFromTarget(test).getValue("direct_sources") as Iterable<Artifact?>?
        assertThat(ActionsTestUtil.baseArtifactNames(directSources)).containsExactly("p.proto")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testProtoSourceRootExportedInStarlark() {
        scratch.file(
            "third_party/foo/myTestRule.bzl",
            """
        load('@com_google_protobuf//bazel/common:proto_info.bzl', 'ProtoInfo')
        load("//myinfo:myinfo.bzl", "MyInfo")

        def _my_test_rule_impl(ctx):
            return MyInfo(
                fetched_proto_source_root = ctx.attr.protodep[ProtoInfo].proto_source_root,
            )

        my_test_rule = rule(
            implementation = _my_test_rule_impl,
            attrs = {"protodep": attr.label()},
        )
        
        """.trimIndent()
        )

        scratch.file(
            "third_party/foo/BUILD",
            "load('@com_google_protobuf//bazel:proto_library.bzl', 'proto_library')",
            "licenses(['unencumbered'])",
            "load(':myTestRule.bzl', 'my_test_rule')",
            "my_test_rule(",
            "  name = 'myRule',",
            "  protodep = ':myProto',",
            ")",
            "proto_library(",
            "  name = 'myProto',",
            "  srcs = ['myProto.proto'],",
            "  strip_import_prefix = '/third_party/foo',",
            ")"
        )

        val ct: ConfiguredTarget = getConfiguredTarget("//third_party/foo:myRule")
        val protoSourceRoot = getMyInfoFromTarget(ct).getValue("fetched_proto_source_root") as String?
        val genfiles: String? = targetConfiguration.getGenfilesFragment(RepositoryName.MAIN).toString()

        Truth.assertThat(protoSourceRoot).isEqualTo(genfiles + "/third_party/foo/_virtual_imports/myProto")
    }
}
