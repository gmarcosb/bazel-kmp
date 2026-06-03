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

import com.google.devtools.build.lib.actions.Action

/** Unit tests for `proto_library`.  */
@RunWith(JUnit4::class)
class BazelProtoLibraryTest : BuildViewTestCase() {
    private val isThisBazel: Boolean
        get() = getAnalysisMock().isThisBazel

    @Before
    @Throws(java.lang.Exception::class)
    fun setUp() {
        useConfiguration("--proto_compiler=//proto:compiler")
        MockProtoSupport.setup(mockToolsConfig)
        scratch.file(
            "proto/BUILD",
            """
        licenses(["notice"])

        exports_files(["compiler"])
        
        """.trimIndent()
        )

        invalidatePackages()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun protoToolchainResolution_enabled() {
        setBuildLanguageOptions("--incompatible_enable_proto_toolchain_resolution")
        scratch.file(
            "x/BUILD",
            "load('@com_google_protobuf//bazel:proto_library.bzl', 'proto_library')",
            "proto_library(name='foo', srcs=['foo.proto'])"
        )

        getDescriptorOutput("//x:foo")

        assertNoEvents()
    }

    @Throws(java.lang.Exception::class)
    private fun testExternalRepoWithGeneratedProto(siblingRepoLayout: Boolean) {
        if (!this.isThisBazel) {
            return
        }

        scratch.appendFile(
            "MODULE.bazel",
            "bazel_dep(name = 'foo')",
            "local_path_override(module_name = 'foo', path = '/foo')"
        )
        if (siblingRepoLayout) {
            setBuildLanguageOptions("--experimental_sibling_repository_layout")
        }

        scratch.file("/foo/MODULE.bazel", "module(name = 'foo')")
        scratch.file(
            "/foo/x/BUILD",
            "load('@com_google_protobuf//bazel:proto_library.bzl', 'proto_library')",
            "proto_library(name='x', srcs=['generated.proto'])",
            "genrule(name='g', srcs=[], outs=['generated.proto'], cmd='')"
        )
        scratch.file(
            "a/BUILD",
            "load('@com_google_protobuf//bazel:proto_library.bzl', 'proto_library')",
            "proto_library(name='a', srcs=['a.proto'], deps=['@foo//x:x'])"
        )
        invalidatePackages()

        val genfiles: String? =
            targetConfiguration
                .getGenfilesFragment(
                    if (siblingRepoLayout) RepositoryName.create("foo+") else RepositoryName.MAIN
                )
                .toString()
        val fooProtoRoot: String?
        fooProtoRoot = (if (siblingRepoLayout) genfiles else genfiles + "/external/foo+")
        val a: ConfiguredTarget = getConfiguredTarget("//a:a")
        val aInfo: ProtoInfo = getProtoInfo(a)
        assertThat(aInfo.transitiveProtoSourceRoots.toList()).containsExactly(".", fooProtoRoot)

        val x: ConfiguredTarget = getConfiguredTarget("@@foo+//x:x")
        val xInfo: ProtoInfo = getProtoInfo(x)
        assertThat(xInfo.transitiveProtoSourceRoots.toList()).containsExactly(fooProtoRoot)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testExternalRepoWithGeneratedProto_withSubdirRepoLayout() {
        testExternalRepoWithGeneratedProto( /* siblingRepoLayout= */false)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun test_siblingRepoLayout_externalRepoWithGeneratedProto() {
        testExternalRepoWithGeneratedProto( /* siblingRepoLayout= */true)
    }

    @Throws(java.lang.Exception::class)
    private fun testImportPrefixInExternalRepo(siblingRepoLayout: Boolean) {
        if (!this.isThisBazel) {
            return
        }

        scratch.appendFile(
            "MODULE.bazel",
            "bazel_dep(name = 'yolo_repo')",
            "local_path_override(module_name = 'yolo_repo', path = '/yolo_repo')"
        )

        if (siblingRepoLayout) {
            setBuildLanguageOptions("--experimental_sibling_repository_layout")
        }

        scratch.file("/yolo_repo/MODULE.bazel", "module(name = 'yolo_repo')")
        scratch.file("/yolo_repo/yolo_pkg/yolo.proto")
        scratch.file(
            "/yolo_repo/yolo_pkg/BUILD",
            "load('@com_google_protobuf//bazel:proto_library.bzl', 'proto_library')",
            "proto_library(",
            "  name = 'yolo_proto',",
            "  srcs = ['yolo.proto'],",
            "  import_prefix = 'bazel.build/yolo',",
            "  visibility = ['//visibility:public'],",
            ")"
        )
        invalidatePackages()

        val target: ConfiguredTarget = getConfiguredTarget("@@yolo_repo+//yolo_pkg:yolo_proto")
        assertThat(
            com.google.common.collect.Iterables.getOnlyElement<T?>(
                getProtoInfo(target).strictImportableProtoSourcesForDependents.toList()
            )
                .getExecPathString()
        )
            .endsWith("/_virtual_imports/yolo_proto/bazel.build/yolo/yolo_pkg/yolo.proto")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testImportPrefixInExternalRepo_withSubdirRepoLayout() {
        testImportPrefixInExternalRepo( /*siblingRepoLayout=*/false)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testImportPrefixInExternalRepo_withSiblingRepoLayout() {
        testImportPrefixInExternalRepo( /*siblingRepoLayout=*/true)
    }

    @Throws(java.lang.Exception::class)
    private fun testImportPrefixAndStripInExternalRepo(siblingRepoLayout: Boolean) {
        if (!this.isThisBazel) {
            return
        }

        scratch.appendFile(
            "MODULE.bazel",
            "bazel_dep(name = 'yolo_repo')",
            "local_path_override(module_name = 'yolo_repo', path = '/yolo_repo')"
        )

        if (siblingRepoLayout) {
            setBuildLanguageOptions("--experimental_sibling_repository_layout")
        }

        scratch.file("/yolo_repo/MODULE.bazel", "module(name = 'yolo_repo')")
        scratch.file("/yolo_repo/yolo_pkg_to_be_stripped/yolo_pkg/yolo.proto")
        scratch.file(
            "/yolo_repo/yolo_pkg_to_be_stripped/yolo_pkg/BUILD",
            "load('@com_google_protobuf//bazel:proto_library.bzl', 'proto_library')",
            "proto_library(",
            "  name = 'yolo_proto',",
            "  srcs = ['yolo.proto'],",
            "  import_prefix = 'bazel.build/yolo',",
            "  strip_import_prefix = '/yolo_pkg_to_be_stripped',",
            "  visibility = ['//visibility:public'],",
            ")"
        )
        invalidatePackages()

        val target: ConfiguredTarget =
            getConfiguredTarget("@@yolo_repo+//yolo_pkg_to_be_stripped/yolo_pkg:yolo_proto")
        assertThat(
            com.google.common.collect.Iterables.getOnlyElement<T?>(
                getProtoInfo(target).strictImportableProtoSourcesForDependents.toList()
            )
                .getExecPathString()
        )
            .endsWith("/_virtual_imports/yolo_proto/bazel.build/yolo/yolo_pkg/yolo.proto")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testImportPrefixAndStripInExternalRepo_withSubdirRepoLayout() {
        testImportPrefixAndStripInExternalRepo( /*siblingRepoLayout=*/false)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testImportPrefixAndStripInExternalRepo_withSiblingRepoLayout() {
        testImportPrefixAndStripInExternalRepo( /*siblingRepoLayout=*/true)
    }

    @Throws(java.lang.Exception::class)
    private fun testStripImportPrefixInExternalRepo(siblingRepoLayout: Boolean) {
        if (!this.isThisBazel) {
            return
        }

        scratch.appendFile(
            "MODULE.bazel",
            "bazel_dep(name = 'yolo_repo')",
            "local_path_override(module_name = 'yolo_repo', path = '/yolo_repo')"
        )

        if (siblingRepoLayout) {
            setBuildLanguageOptions("--experimental_sibling_repository_layout")
        }

        scratch.file("/yolo_repo/MODULE.bazel", "module(name = 'yolo_repo')")
        scratch.file("/yolo_repo/yolo_pkg_to_be_stripped/yolo_pkg/yolo.proto")
        scratch.file(
            "/yolo_repo/yolo_pkg_to_be_stripped/yolo_pkg/BUILD",
            "load('@com_google_protobuf//bazel:proto_library.bzl', 'proto_library')",
            "proto_library(",
            "  name = 'yolo_proto',",
            "  srcs = ['yolo.proto'],",
            "  strip_import_prefix = '/yolo_pkg_to_be_stripped',",
            "  visibility = ['//visibility:public'],",
            ")"
        )
        invalidatePackages()

        val target: ConfiguredTarget =
            getConfiguredTarget("@@yolo_repo+//yolo_pkg_to_be_stripped/yolo_pkg:yolo_proto")
        assertThat(
            com.google.common.collect.Iterables.getOnlyElement<T?>(
                getProtoInfo(target).strictImportableProtoSourcesForDependents.toList()
            )
                .getExecPathString()
        )
            .endsWith("/_virtual_imports/yolo_proto/yolo_pkg/yolo.proto")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testStripImportPrefixInExternalRepo_withSubdirRepoLayout() {
        testStripImportPrefixInExternalRepo( /*siblingRepoLayout=*/false)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testStripImportPrefixInExternalRepo_withSiblingRepoLayout() {
        testStripImportPrefixInExternalRepo( /*siblingRepoLayout=*/true)
    }

    @Throws(java.lang.Exception::class)
    private fun testRelativeStripImportPrefixInExternalRepo(siblingRepoLayout: Boolean) {
        if (!this.isThisBazel) {
            return
        }

        scratch.appendFile(
            "MODULE.bazel",
            "bazel_dep(name = 'yolo_repo')",
            "local_path_override(module_name = 'yolo_repo', path = '/yolo_repo')"
        )

        if (siblingRepoLayout) {
            setBuildLanguageOptions("--experimental_sibling_repository_layout")
        }

        scratch.file("/yolo_repo/MODULE.bazel", "module(name = 'yolo_repo')")
        scratch.file("/yolo_repo/yolo_pkg_to_be_stripped/yolo_pkg/yolo.proto")
        scratch.file(
            "/yolo_repo/BUILD",
            "load('@com_google_protobuf//bazel:proto_library.bzl', 'proto_library')",
            "proto_library(",
            "  name = 'yolo_proto',",
            "  srcs = ['yolo_pkg_to_be_stripped/yolo_pkg/yolo.proto'],",
            "  strip_import_prefix = 'yolo_pkg_to_be_stripped',",
            "  visibility = ['//visibility:public'],",
            ")"
        )
        invalidatePackages()

        val target: ConfiguredTarget = getConfiguredTarget("@@yolo_repo+//:yolo_proto")
        assertThat(
            com.google.common.collect.Iterables.getOnlyElement<T?>(
                getProtoInfo(target).strictImportableProtoSourcesForDependents.toList()
            )
                .getExecPathString()
        )
            .endsWith("/_virtual_imports/yolo_proto/yolo_pkg/yolo.proto")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRelativeStripImportPrefixInExternalRepo_withSubdirRepoLayout() {
        testRelativeStripImportPrefixInExternalRepo( /*siblingRepoLayout=*/false)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRelativeStripImportPrefixInExternalRepo_withSiblingRepoLayout() {
        testRelativeStripImportPrefixInExternalRepo( /*siblingRepoLayout=*/true)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testIllegalImportPrefix() {
        if (!this.isThisBazel) {
            return
        }

        scratch.file(
            "a/BUILD",
            "load('@com_google_protobuf//bazel:proto_library.bzl', 'proto_library')",
            "proto_library(",
            "    name = 'a',",
            "    srcs = ['a.proto'],",
            "    import_prefix = '/foo')"
        )

        reporter.removeHandler(failFastHandler)
        getConfiguredTarget("//a")
        assertContainsEvent("should be a relative path")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testStripImportPrefixAndImportPrefix() {
        if (!this.isThisBazel) {
            return
        }

        scratch.file(
            "a/b/BUILD",
            "load('@com_google_protobuf//bazel:proto_library.bzl', 'proto_library')",
            "proto_library(",
            "    name = 'd',",
            "    srcs = ['c/d.proto'],",
            "    import_prefix = 'foo',",
            "    strip_import_prefix = 'c')"
        )

        val commandLine: com.google.common.collect.ImmutableList<String?> =
            allArgsForAction(getDescriptorWriteAction("//a/b:d") as SpawnAction)
        val genfiles: String? = targetConfiguration.getGenfilesFragment(RepositoryName.MAIN).toString()
        Truth.assertThat(commandLine).contains("-I" + genfiles + "/a/b/_virtual_imports/d")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testImportPrefixWithoutStripImportPrefix() {
        if (!this.isThisBazel) {
            return
        }

        scratch.file(
            "a/b/BUILD",
            "load('@com_google_protobuf//bazel:proto_library.bzl', 'proto_library')",
            "proto_library(",
            "    name = 'd',",
            "    srcs = ['c/d.proto'],",
            "    import_prefix = 'foo')"
        )

        val commandLine: com.google.common.collect.ImmutableList<String?> =
            allArgsForAction(getDescriptorWriteAction("//a/b:d") as SpawnAction)
        val genfiles: String? = targetConfiguration.getGenfilesFragment(RepositoryName.MAIN).toString()
        Truth.assertThat(commandLine).contains("-I" + genfiles + "/a/b/_virtual_imports/d")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDotInImportPrefix() {
        if (!this.isThisBazel) {
            return
        }

        scratch.file(
            "a/b/BUILD",
            "load('@com_google_protobuf//bazel:proto_library.bzl', 'proto_library')",
            "proto_library(",
            "    name = 'd',",
            "    srcs = ['c/d.proto'],",
            "    import_prefix = './e')"
        )

        reporter.removeHandler(failFastHandler)
        getConfiguredTarget("//a/b:d")
        assertContainsEvent("should be normalized")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDotDotInImportPrefix() {
        if (!this.isThisBazel) {
            return
        }

        scratch.file(
            "a/b/BUILD",
            "load('@com_google_protobuf//bazel:proto_library.bzl', 'proto_library')",
            "proto_library(",
            "    name = 'd',",
            "    srcs = ['c/d.proto'],",
            "    import_prefix = '../e')"
        )

        reporter.removeHandler(failFastHandler)
        getConfiguredTarget("//a/b:d")
        assertContainsEvent("should be normalized")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testStripImportPrefixAndImportPrefixWithStrictProtoDeps() {
        if (!this.isThisBazel) {
            return
        }

        useConfiguration("--strict_proto_deps=STRICT")
        scratch.file(
            "a/b/BUILD",
            "load('@com_google_protobuf//bazel:proto_library.bzl', 'proto_library')",
            "proto_library(",
            "    name = 'd',",
            "    srcs = ['c/d.proto'],",
            "    import_prefix = 'foo',",
            "    strip_import_prefix = 'c')"
        )

        val commandLine: com.google.common.collect.ImmutableList<String?> =
            allArgsForAction(getDescriptorWriteAction("//a/b:d") as SpawnAction)
        Truth.assertThat(commandLine).containsAtLeast("--direct_dependencies", "foo/d.proto").inOrder()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testStripImportPrefixForExternalRepositories() {
        if (!this.isThisBazel) {
            return
        }

        scratch.appendFile(
            "MODULE.bazel",
            "bazel_dep(name = 'foo')",
            "local_path_override(module_name = 'foo', path = '/foo')"
        )

        scratch.file("/foo/MODULE.bazel", "module(name = 'foo')")
        scratch.file(
            "/foo/x/y/BUILD",
            "load('@com_google_protobuf//bazel:proto_library.bzl', 'proto_library')",
            "proto_library(",
            "    name = 'q',",
            "    srcs = ['z/q.proto'],",
            "    strip_import_prefix = '/x')"
        )

        scratch.file(
            "a/BUILD",
            "load('@com_google_protobuf//bazel:proto_library.bzl', 'proto_library')",
            "proto_library(name='a', srcs=['a.proto'], deps=['@foo//x/y:q'])"
        )
        invalidatePackages()

        val commandLine: com.google.common.collect.ImmutableList<String?> =
            allArgsForAction(getDescriptorWriteAction("//a:a") as SpawnAction)
        val genfiles: String? = targetConfiguration.getGenfilesFragment(RepositoryName.MAIN).toString()
        Truth.assertThat(commandLine).contains("-I" + genfiles + "/external/foo+/x/y/_virtual_imports/q")
    }

    @com.google.errorprone.annotations.CanIgnoreReturnValue
    @Throws(java.lang.Exception::class)
    private fun getDescriptorOutput(label: String?): Artifact {
        return getFirstArtifactEndingWith(getFilesToBuild(getConfiguredTarget(label)), ".proto.bin")
    }

    @Throws(java.lang.Exception::class)
    private fun getDescriptorWriteAction(label: String?): Action {
        return getGeneratingAction(getDescriptorOutput(label))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDependencyOnProtoSourceInExternalRepo() {
        if (!this.isThisBazel) {
            return
        }

        scratch.file("third_party/foo/MODULE.bazel", "module(name = 'foo')")
        scratch.file(
            "third_party/foo/BUILD.bazel",
            "load('@com_google_protobuf//bazel:proto_library.bzl', 'proto_library')",
            "proto_library(name='a', srcs=['a.proto'])",
            "proto_library(name='c', srcs=['a/b/c.proto'])"
        )
        scratch.appendFile(
            "MODULE.bazel",
            "bazel_dep(name = 'foo')",
            "local_path_override(module_name = 'foo', path = 'third_party/foo')"
        )
        invalidatePackages()

        scratch.file(
            "x/BUILD",
            "load('@com_google_protobuf//bazel:proto_library.bzl', 'proto_library')",
            "proto_library(name='a', srcs=['a.proto'], deps=['@foo//:a'])",
            "proto_library(name='c', srcs=['c.proto'], deps=['@foo//:c'])"
        )

        run {
            val commandLine: com.google.common.collect.ImmutableList<String?> =
                allArgsForAction(getDescriptorWriteAction("//x:a") as SpawnAction)
            Truth.assertThat(commandLine).containsAtLeast("-Iexternal/foo+", "-I.")
        }

        run {
            val commandLine: com.google.common.collect.ImmutableList<String?> =
                allArgsForAction(getDescriptorWriteAction("//x:c") as SpawnAction)
            Truth.assertThat(commandLine).containsAtLeast("-Iexternal/foo+", "-I.")
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testProtoLibraryWithVirtualProtoSourceRoot() {
        if (!this.isThisBazel) {
            return
        }

        scratch.file(
            "x/BUILD",
            "load('@com_google_protobuf//bazel:proto_library.bzl', 'proto_library')",
            "proto_library(name='foo', srcs=['a.proto'], import_prefix='foo')"
        )

        val genfiles: String? = targetConfiguration.getGenfilesFragment(RepositoryName.MAIN).toString()
        val provider: ProtoInfo = getProtoInfo(getConfiguredTarget("//x:foo"))
        Truth.assertThat(
            com.google.common.collect.Iterables.transform<Any?, Any?>(
                provider.directProtoSources,
                com.google.common.base.Function { s: Any? -> s.getExecPathString() })
        )
            .containsExactly(genfiles + "/x/_virtual_imports/foo/foo/x/a.proto")
    }


    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun protoLibrary_reexport_allowed() {
        scratch.file(
            "x/BUILD",
            "load('@com_google_protobuf//bazel:proto_library.bzl', 'proto_library')",
            """
        proto_library(
            name = "foo",
            srcs = ["foo.proto"],
            allow_exports = ":test",
        )

        package_group(
            name = "test",
            packages = ["//allowed"],
        )
        
        """.trimIndent()
        )
        scratch.file(
            "allowed/BUILD",
            "load('@com_google_protobuf//bazel:proto_library.bzl', 'proto_library')",
            """
        proto_library(
            name = "test1",
            deps = ["//x:foo"],
        )

        proto_library(
            name = "test2",
            srcs = ["A.proto"],
            exports = ["//x:foo"],
        )
        
        """.trimIndent()
        )

        getConfiguredTarget("//allowed:test1")
        getConfiguredTarget("//allowed:test2")

        assertNoEvents()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun protoLibrary_implcitReexport_fails() {
        scratch.file(
            "x/BUILD",
            "load('@com_google_protobuf//bazel:proto_library.bzl', 'proto_library')",
            """
        proto_library(
            name = "foo",
            srcs = ["foo.proto"],
            allow_exports = ":test",
        )

        package_group(
            name = "test",
            packages = ["//allowed"],
        )
        
        """.trimIndent()
        )
        scratch.file(
            "notallowed/BUILD",
            "load('@com_google_protobuf//bazel:proto_library.bzl', 'proto_library')",
            "proto_library(name='test', deps = ['//x:foo'])"
        )

        reporter.removeHandler(failFastHandler)
        getConfiguredTarget("//notallowed:test")

        assertContainsEvent("proto_library '@@//x:foo' can't be reexported in package '//notallowed'")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun protoLibrary_explicitExport_fails() {
        scratch.file(
            "x/BUILD",
            """
        load('@com_google_protobuf//bazel:proto_library.bzl', 'proto_library')
        proto_library(
            name = "foo",
            srcs = ["foo.proto"],
            allow_exports = ":test",
        )

        package_group(
            name = "test",
            packages = ["//allowed"],
        )
        
        """.trimIndent()
        )
        scratch.file(
            "notallowed/BUILD",
            "load('@com_google_protobuf//bazel:proto_library.bzl', 'proto_library')",
            "proto_library(name='test', srcs = ['A.proto'], exports = ['//x:foo'])"
        )

        reporter.removeHandler(failFastHandler)
        getConfiguredTarget("//notallowed:test")

        assertContainsEvent("proto_library '@@//x:foo' can't be reexported in package '//notallowed'")
    }

    @Throws(java.lang.Exception::class)
    private fun getProtoInfo(target: ConfiguredTarget): ProtoInfo {
        for (key: @NotNull BzlLoadValue.Key in ProtoConstants.EXTERNAL_PROTO_INFO_KEYS) {
            val providerClass: ProtoInfoProvider = ProtoInfoProvider(key)
            val provider: ProtoInfo? = target.get(providerClass)
            if (provider != null) {
                return provider
            }
        }
        throw java.lang.IllegalStateException("ProtoInfo not found in " + target.toString())
    }
}
