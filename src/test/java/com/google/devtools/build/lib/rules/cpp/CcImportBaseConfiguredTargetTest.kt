// Copyright 2015 The Bazel Authors. All rights reserved.
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
// Copyright 2006 Google Inc. All rights reserved.
package com.google.devtools.build.lib.rules.cpp

import com.google.devtools.build.lib.actions.Artifact

/** "White-box" unit test of cc_import rule.  */
@RunWith(JUnit4::class)
abstract class CcImportBaseConfiguredTargetTest : BuildViewTestCase() {
    protected var starlarkImplementationLoadStatement: String = "load('@rules_cc//cc:cc_import.bzl', 'cc_import')"

    @Before
    @Throws(java.lang.Exception::class)
    fun setStarlarkImplementationLoadStatement() {
        invalidatePackages()
        setIsStarlarkImplementation()
    }

    protected abstract fun setIsStarlarkImplementation()

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCcImportRule() {
        scratch.file(
            "third_party/BUILD",
            starlarkImplementationLoadStatement,
            "cc_import(",
            "  name = 'a_import',",
            "  static_library = 'A.a',",
            "  shared_library = 'A.so',",
            "  interface_library = 'A.ifso',",
            "  hdrs = ['a.h'],",
            "  alwayslink = 1,",
            "  system_provided = 0,",
            ")"
        )
        getConfiguredTarget("//third_party:a_import")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testWrongCcImportDefinitions() {
        checkError(
            "a",
            "foo",
            "does not produce any cc_import static_library files " + "(expected",
            starlarkImplementationLoadStatement,
            "cc_import(",
            "  name = 'foo',",
            "  static_library = 'libfoo.so',",
            ")"
        )
        checkError(
            "b",
            "foo",
            "does not produce any cc_import shared_library files (expected",
            starlarkImplementationLoadStatement,
            "cc_import(",
            "  name = 'foo',",
            "  shared_library = 'libfoo.a',",
            ")"
        )
        checkError(
            "c",
            "foo",
            "does not produce any cc_import interface_library files " + "(expected",
            starlarkImplementationLoadStatement,
            "cc_import(",
            "  name = 'foo',",
            "  shared_library = 'libfoo.dll',",
            "  interface_library = 'libfoo.a',",
            ")"
        )
        checkError(
            "d",
            "foo",
            "'shared_library' shouldn't be specified when 'system_provided' is true",
            starlarkImplementationLoadStatement,
            "cc_import(",
            "  name = 'foo',",
            "  shared_library = 'libfoo.so',",
            "  system_provided = 1,",
            ")"
        )
        checkError(
            "e",
            "foo",
            "'shared_library' should be specified when 'system_provided' is false",
            starlarkImplementationLoadStatement,
            "cc_import(",
            "  name = 'foo',",
            "  interface_library = 'libfoo.ifso',",
            "  system_provided = 0,",
            ")"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRuntimeOnlyCcImportDefinitionsOnWindows() {
        AnalysisMock.get()
            .ccSupport()
            .setupCcToolchainConfig(
                mockToolsConfig,
                CcToolchainConfig.builder()
                    .withFeatures(
                        CppRuleClasses.COPY_DYNAMIC_LIBRARIES_TO_BINARY,
                        CppRuleClasses.TARGETS_WINDOWS
                    )
            )
        useConfiguration()
        val target: ConfiguredTarget =
            scratchConfiguredTarget(
                "a",
                "foo",
                starlarkImplementationLoadStatement,
                "cc_import(name = 'foo', shared_library = 'libfoo.dll')"
            )
        val dynamicLibrary: Artifact? =
            CcInfo.get(target)
                .getCcLinkingContext()
                .getLibraries()
                .getSingleton()
                .getResolvedSymlinkDynamicLibrary()
        val dynamicLibrariesForRuntime: Iterable<Artifact?> =
            CcInfo.get(target)
                .getCcLinkingContext()
                .getDynamicLibrariesForRuntime( /* linkingStatically= */false)
        assertThat(dynamicLibrary).isEqualTo(null)
        Truth.assertThat(artifactsToStrings(dynamicLibrariesForRuntime)).containsExactly("src a/libfoo.dll")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCcImportWithStaticLibrary() {
        val target: ConfiguredTarget =
            scratchConfiguredTarget(
                "a",
                "foo",
                starlarkImplementationLoadStatement,
                "cc_import(name = 'foo', static_library = 'libfoo.a')"
            )
        val library: Artifact =
            CcInfo.get(target).getCcLinkingContext().getLibraries().getSingleton().getStaticLibrary()
        Truth.assertThat(artifactsToStrings(com.google.common.collect.ImmutableList.of<Artifact>(library)))
            .containsExactly("src a/libfoo.a")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCcImportWithSharedLibrary() {
        useConfiguration(
            "--platforms=" + TestConstants.PLATFORM_LABEL,
            "--noincompatible_enable_cc_toolchain_resolution"
        )
        val target: ConfiguredTarget =
            scratchConfiguredTarget(
                "a",
                "foo",
                starlarkImplementationLoadStatement,
                "cc_import(name = 'foo', shared_library = 'libfoo.so')"
            )
        val dynamicLibrary: Artifact =
            CcInfo.get(target)
                .getCcLinkingContext()
                .getLibraries()
                .getSingleton()
                .getResolvedSymlinkDynamicLibrary()
        val dynamicLibrariesForRuntime: Iterable<Artifact?> =
            CcInfo.get(target)
                .getCcLinkingContext()
                .getDynamicLibrariesForRuntime( /* linkingStatically= */false)
        Truth.assertThat(artifactsToStrings(com.google.common.collect.ImmutableList.of<Artifact>(dynamicLibrary)))
            .containsExactly("src a/libfoo.so")
        Truth.assertThat(artifactsToStrings(dynamicLibrariesForRuntime))
            .containsExactly("bin _solib_k8/_U_S_Sa_Cfoo___Ua/libfoo.so")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCcImportWithVersionedSharedLibrary() {
        useConfiguration(
            "--platforms=" + TestConstants.PLATFORM_LABEL,
            "--noincompatible_enable_cc_toolchain_resolution"
        )
        val target: ConfiguredTarget =
            scratchConfiguredTarget(
                "a",
                "foo",
                starlarkImplementationLoadStatement,
                "cc_import(name = 'foo', shared_library = 'libfoo.so.1ab2.1_a2')"
            )
        val dynamicLibrary: Artifact =
            CcInfo.get(target)
                .getCcLinkingContext()
                .getLibraries()
                .getSingleton()
                .getResolvedSymlinkDynamicLibrary()
        val dynamicLibrariesForRuntime: Iterable<Artifact?> =
            CcInfo.get(target)
                .getCcLinkingContext()
                .getDynamicLibrariesForRuntime( /* linkingStatically= */false)
        Truth.assertThat(artifactsToStrings(com.google.common.collect.ImmutableList.of<Artifact>(dynamicLibrary)))
            .containsExactly("src a/libfoo.so.1ab2.1_a2")
        Truth.assertThat(artifactsToStrings(dynamicLibrariesForRuntime))
            .containsExactly("bin _solib_k8/_U_S_Sa_Cfoo___Ua/libfoo.so.1ab2.1_a2")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCcImportWithVersionedSharedLibraryWithDotInTheName() {
        useConfiguration(
            "--platforms=" + TestConstants.PLATFORM_LABEL,
            "--noincompatible_enable_cc_toolchain_resolution"
        )

        val target: ConfiguredTarget =
            scratchConfiguredTarget(
                "a",
                "foo",
                starlarkImplementationLoadStatement,
                "cc_import(name = 'foo', shared_library = 'libfoo.qux.so.1ab2.1_a2')"
            )

        val dynamicLibrary: Artifact =
            CcInfo.get(target)
                .getCcLinkingContext()
                .getLibraries()
                .getSingleton()
                .getResolvedSymlinkDynamicLibrary()
        val dynamicLibrariesForRuntime: Iterable<Artifact?> =
            CcInfo.get(target)
                .getCcLinkingContext()
                .getDynamicLibrariesForRuntime( /* linkingStatically= */false)
        Truth.assertThat(artifactsToStrings(com.google.common.collect.ImmutableList.of<Artifact>(dynamicLibrary)))
            .containsExactly("src a/libfoo.qux.so.1ab2.1_a2")
        Truth.assertThat(artifactsToStrings(dynamicLibrariesForRuntime))
            .containsExactly("bin _solib_k8/_U_S_Sa_Cfoo___Ua/libfoo.qux.so.1ab2.1_a2")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCcImportWithInvalidVersionedSharedLibrary() {
        checkError(
            "a",
            "foo",
            "does not produce any cc_import shared_library files " + "(expected",
            starlarkImplementationLoadStatement,
            "cc_import(",
            "  name = 'foo',",
            "  shared_library = 'libfoo.so.1ab2.ab',",
            ")"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCcImportWithInvalidSharedLibraryNoExtension() {
        checkError(
            "a",
            "foo",
            "does not produce any cc_import shared_library files " + "(expected",
            starlarkImplementationLoadStatement,
            "cc_import(",
            "  name = 'foo',",
            "  shared_library = 'libfoo',",
            ")"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCcImportWithInterfaceSharedLibrary() {
        useConfiguration(
            "--platforms=" + TestConstants.PLATFORM_LABEL,
            "--noincompatible_enable_cc_toolchain_resolution"
        )
        val target: ConfiguredTarget =
            scratchConfiguredTarget(
                "b",
                "foo",
                starlarkImplementationLoadStatement,
                "cc_import(name = 'foo', shared_library = 'libfoo.so',"
                        + " interface_library = 'libfoo.ifso')"
            )

        val library: Artifact =
            CcInfo.get(target)
                .getCcLinkingContext()
                .getLibraries()
                .getSingleton()
                .getResolvedSymlinkInterfaceLibrary()
        Truth.assertThat(artifactsToStrings(com.google.common.collect.ImmutableList.of<Artifact>(library)))
            .containsExactly("src b/libfoo.ifso")
        val dynamicLibrariesForRuntime: Iterable<Artifact?> =
            CcInfo.get(target)
                .getCcLinkingContext()
                .getDynamicLibrariesForRuntime( /* linkingStatically= */false)
        Truth.assertThat(artifactsToStrings(dynamicLibrariesForRuntime))
            .containsExactly("bin _solib_k8/_U_S_Sb_Cfoo___Ub/libfoo.so")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCcImportWithBothStaticAndSharedLibraries() {
        useConfiguration(
            "--platforms=" + TestConstants.PLATFORM_LABEL,
            "--noincompatible_enable_cc_toolchain_resolution"
        )
        val target: ConfiguredTarget =
            scratchConfiguredTarget(
                "a",
                "foo",
                starlarkImplementationLoadStatement,
                "cc_import(name = 'foo', static_library = 'libfoo.a', shared_library = 'libfoo.so')"
            )

        val library: Artifact =
            CcInfo.get(target).getCcLinkingContext().getLibraries().getSingleton().getStaticLibrary()
        Truth.assertThat(artifactsToStrings(com.google.common.collect.ImmutableList.of<Artifact>(library)))
            .containsExactly("src a/libfoo.a")

        val dynamicLibrary: Artifact =
            CcInfo.get(target)
                .getCcLinkingContext()
                .getLibraries()
                .getSingleton()
                .getResolvedSymlinkDynamicLibrary()
        val dynamicLibrariesForRuntime: Iterable<Artifact?> =
            CcInfo.get(target)
                .getCcLinkingContext()
                .getDynamicLibrariesForRuntime( /* linkingStatically= */false)
        Truth.assertThat(artifactsToStrings(com.google.common.collect.ImmutableList.of<Artifact>(dynamicLibrary)))
            .containsExactly("src a/libfoo.so")
        Truth.assertThat(artifactsToStrings(dynamicLibrariesForRuntime))
            .containsExactly("bin _solib_k8/_U_S_Sa_Cfoo___Ua/libfoo.so")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCcImportWithAlwaysLinkStaticLibrary() {
        val target: ConfiguredTarget =
            scratchConfiguredTarget(
                "a",
                "foo",
                starlarkImplementationLoadStatement,
                "cc_import(name = 'foo', static_library = 'libfoo.a', alwayslink = 1)"
            )
        val alwayslink: Boolean =
            CcInfo.get(target).getCcLinkingContext().getLibraries().getSingleton().getAlwayslink()
        Truth.assertThat(alwayslink).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCcImportSystemProvidedIsTrue() {
        val target: ConfiguredTarget =
            scratchConfiguredTarget(
                "a",
                "foo",
                starlarkImplementationLoadStatement,
                "cc_import(name = 'foo', interface_library = 'libfoo.ifso', system_provided = 1)"
            )
        val library: Artifact =
            CcInfo.get(target)
                .getCcLinkingContext()
                .getLibraries()
                .getSingleton()
                .getResolvedSymlinkInterfaceLibrary()
        Truth.assertThat(artifactsToStrings(com.google.common.collect.ImmutableList.of<Artifact>(library)))
            .containsExactly("src a/libfoo.ifso")
        val dynamicLibrariesForRuntime: Iterable<Artifact?> =
            CcInfo.get(target)
                .getCcLinkingContext()
                .getDynamicLibrariesForRuntime( /* linkingStatically= */false)
        Truth.assertThat(artifactsToStrings(dynamicLibrariesForRuntime)).isEmpty()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCcImportProvideHeaderFiles() {
        val headers: NestedSet<Artifact?> =
            CcInfo.get(
                scratchConfiguredTarget(
                    "a",
                    "foo",
                    starlarkImplementationLoadStatement,
                    "cc_import(name = 'foo', static_library = 'libfoo.a', hdrs = ['foo.h'])"
                )
            )
                .getCcCompilationContext()
                .getDeclaredIncludeSrcs()
        Truth.assertThat(artifactsToStrings(headers)).containsExactly("src a/foo.h")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCcImportLoadedThroughMacro() {
        setupTestCcImportLoadedThroughMacro( /* loadMacro= */true)
        assertThat(getConfiguredTarget("//a:a")).isNotNull()
        assertNoEvents()
    }

    @Throws(java.lang.Exception::class)
    private fun setupTestCcImportLoadedThroughMacro(loadMacro: Boolean) {
        scratch.file(
            "a/BUILD",
            getAnalysisMock().ccSupport().getMacroLoadStatement(loadMacro, "cc_import"),
            "cc_import(name='a', static_library='a.a')"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCcImportWithSharedLibraryAddsRpathEntry() {
        AnalysisMock.get()
            .ccSupport()
            .setupCcToolchainConfig(
                mockToolsConfig,
                CcToolchainConfig.builder().withFeatures(CppRuleClasses.SUPPORTS_DYNAMIC_LINKER)
            )
        useConfiguration("--platforms=" + TestConstants.PLATFORM_LABEL)
        val target: ConfiguredTarget =
            scratchConfiguredTarget(
                "a",
                "foo",
                starlarkImplementationLoadStatement,
                "cc_import(name = 'foo', shared_library = 'libfoo.so')"
            )
        scratch.file(
            "bin/BUILD",
            "load('@rules_cc//cc:cc_binary.bzl', 'cc_binary')",
            "cc_binary(name='bin', deps=['//a:foo'])"
        )

        val dynamicLibrary: Artifact =
            CcInfo.get(target)
                .getCcLinkingContext()
                .getLibraries()
                .getSingleton()
                .getResolvedSymlinkDynamicLibrary()
        val dynamicLibrariesForRuntime: Iterable<Artifact?> =
            CcInfo.get(target)
                .getCcLinkingContext()
                .getDynamicLibrariesForRuntime( /* linkingStatically= */false)
        Truth.assertThat(artifactsToStrings(com.google.common.collect.ImmutableList.of<Artifact>(dynamicLibrary)))
            .containsExactly("src a/libfoo.so")
        Truth.assertThat(artifactsToStrings(dynamicLibrariesForRuntime))
            .containsExactly("bin _solib_k8/_U_S_Sa_Cfoo___Ua/libfoo.so")

        val main: ConfiguredTarget = getConfiguredTarget("//bin:bin")
        val mainBin: Artifact = getBinArtifact("bin", main)
        val action: SpawnAction = getGeneratingAction(mainBin) as SpawnAction
        val linkArgv: MutableList<String?>? = action.getArguments()
        Truth.assertThat(linkArgv)
            .containsAtLeast("-Xlinker", "-rpath", "-Xlinker", "\$ORIGIN/../_solib_k8/_U_S_Sa_Cfoo___Ua")
            .inOrder()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCcImportWithSharedLibraryWithTransitionAddsRpathEntry() {
        AnalysisMock.get()
            .ccSupport()
            .setupCcToolchainConfig(
                mockToolsConfig,
                CcToolchainConfig.builder().withFeatures(CppRuleClasses.SUPPORTS_DYNAMIC_LINKER)
            )
        useConfiguration("--platforms=" + TestConstants.PLATFORM_LABEL)
        val target: ConfiguredTarget =
            scratchConfiguredTarget(
                "a",
                "foo",
                starlarkImplementationLoadStatement,
                "cc_import(name = 'foo', shared_library = 'libfoo.so')"
            )

        scratch.file(
            "bin/custom_transition.bzl",
            """
        load("@rules_cc//cc/common:cc_info.bzl", "CcInfo")
        load('@rules_cc//cc/common:cc_common.bzl', 'cc_common')
        def _custom_transition_impl(settings, attr):
            _ignore = settings, attr

            return {"//command_line_option:copt": ["-DFLAG"]}

        custom_transition = transition(
            implementation = _custom_transition_impl,
            inputs = [],
            outputs = ["//command_line_option:copt"],
        )

        def _apply_custom_transition_impl(ctx):
            cc_infos = []
            for dep in ctx.attr.deps:
                cc_infos.append(dep[CcInfo])
            merged_cc_info = cc_common.merge_cc_infos(cc_infos = cc_infos)
            return merged_cc_info

        apply_custom_transition = rule(
            implementation = _apply_custom_transition_impl,
            attrs = {
                "deps": attr.label_list(cfg = custom_transition),
            },
        )
        
        """.trimIndent()
        )
        scratch.overwriteFile(
            "tools/allowlists/function_transition_allowlist/BUILD",
            """
        package_group(
            name = "function_transition_allowlist",
            packages = ["//..."],
        )
        
        """.trimIndent()
        )
        scratch.file(
            "bin/BUILD",
            """
        load("@rules_cc//cc:cc_binary.bzl", "cc_binary")
        load("@rules_cc//cc:cc_library.bzl", "cc_library")
        load(":custom_transition.bzl", "apply_custom_transition")

        cc_library(
            name = "lib",
            deps = ["//a:foo"],
        )

        apply_custom_transition(
            name = "transitioned_lib",
            deps = [":lib"],
        )

        cc_binary(
            name = "bin",
            deps = [":transitioned_lib"],
        )
        
        """.trimIndent()
        )

        val dynamicLibrary: Artifact =
            CcInfo.get(target)
                .getCcLinkingContext()
                .getLibraries()
                .getSingleton()
                .getResolvedSymlinkDynamicLibrary()
        val dynamicLibrariesForRuntime: Iterable<Artifact?> =
            CcInfo.get(target)
                .getCcLinkingContext()
                .getDynamicLibrariesForRuntime( /* linkingStatically= */false)
        Truth.assertThat(artifactsToStrings(com.google.common.collect.ImmutableList.of<Artifact>(dynamicLibrary)))
            .containsExactly("src a/libfoo.so")
        Truth.assertThat(artifactsToStrings(dynamicLibrariesForRuntime))
            .containsExactly("bin _solib_k8/_U_S_Sa_Cfoo___Ua/libfoo.so")

        val main: ConfiguredTarget = getConfiguredTarget("//bin:bin")
        val mainBin: Artifact = getBinArtifact("bin", main)
        val action: SpawnAction = getGeneratingAction(mainBin) as SpawnAction
        val linkArgv: MutableList<String?>? = action.getArguments()
        Truth.assertThat(linkArgv)
            .containsAtLeast("-Xlinker", "-rpath", "-Xlinker", "\$ORIGIN/../_solib_k8/_U_S_Sa_Cfoo___Ua")
            .inOrder()
    }
}
