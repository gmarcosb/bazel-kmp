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

import com.google.devtools.build.lib.actions.PathMapper

/** Tests that `CppCompileAction` is populated with the correct build variables.  */
@RunWith(JUnit4::class)
class CompileBuildVariablesTest : BuildViewTestCase() {
    @Throws(java.lang.Exception::class)
    private fun getCppCompileAction(target: ConfiguredTarget?, name: String?): CppCompileAction {
        return getGeneratingAction(
            com.google.common.collect.Iterables.find<T?>(
                getGeneratingAction(getFilesToBuild(target).getSingleton()).getInputs().toList(),
                com.google.common.base.Predicate { artifact: T? ->
                    artifact.getExecPath().getBaseName().startsWith(name)
                })
        ) as CppCompileAction
    }

    /** Returns active build variables for a compile action of given type for given target.  */
    @Throws(java.lang.Exception::class)
    protected fun getCompileBuildVariables(label: String?, name: String?): CcToolchainVariables {
        return getCppCompileAction(getConfiguredTarget(label), name)
            .getCompileCommandLine()
            .getVariables()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testPresenceOfBasicVariables() {
        scratch.file(
            "x/BUILD",
            "load('@rules_cc//cc:cc_binary.bzl', 'cc_binary')",
            "cc_binary(name = 'bin', srcs = ['bin.cc'])"
        )
        scratch.file("x/bin.cc")

        val variables: CcToolchainVariables = getCompileBuildVariables("//x:bin", "bin")

        com.google.common.truth.Subject.contains("x/bin.cc")
        com.google.common.truth.Subject.contains("_objs/bin/bin")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testPresenceOfConfigurationCompileFlags() {
        useConfiguration("--copt=-foo")

        scratch.file(
            "x/BUILD",
            "load('@rules_cc//cc:cc_binary.bzl', 'cc_binary')",
            "cc_binary(name = 'bin', srcs = ['bin.cc'], copts = ['-bar'],)"
        )
        scratch.file("x/bin.cc")

        val variables: CcToolchainVariables = getCompileBuildVariables("//x:bin", "bin")

        val userCopts: com.google.common.collect.ImmutableList<String?>? =
            CcToolchainVariables.toStringList(
                variables, CompileBuildVariables.USER_COMPILE_FLAGS.variableName, PathMapper.NOOP
            )
        Truth.assertThat(userCopts)
            .containsAtLeastElementsIn(com.google.common.collect.ImmutableList.of<String?>("-foo", "-bar"))
            .inOrder()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testPresenceOfUserCompileFlags() {
        useConfiguration()

        scratch.file(
            "x/BUILD",
            "load('@rules_cc//cc:cc_binary.bzl', 'cc_binary')",
            "cc_binary(name = 'bin', srcs = ['bin.cc'], copts = ['-foo'])"
        )
        scratch.file("x/bin.cc")

        val variables: CcToolchainVariables = getCompileBuildVariables("//x:bin", "bin")

        val copts: com.google.common.collect.ImmutableList<String?>? =
            CcToolchainVariables.toStringList(
                variables, CompileBuildVariables.USER_COMPILE_FLAGS.variableName, PathMapper.NOOP
            )
        Truth.assertThat(copts).contains("-foo")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testPresenceOfConlyFlags() {
        useConfiguration(
            "--conlyopt=-foo", "--cxxopt=-not-passed", "--per_file_copt=//x:bin@-per-file"
        )

        scratch.file(
            "x/BUILD",
            "load('@rules_cc//cc:cc_binary.bzl', 'cc_binary')",
            "cc_binary(name = 'bin', srcs = ['bin.c'], copts = ['-bar'], conlyopts = ['-baz'], cxxopts"
                    + " = ['-not-passed'])"
        )
        scratch.file("x/bin.c")

        val variables: CcToolchainVariables = getCompileBuildVariables("//x:bin", "bin")

        val copts: com.google.common.collect.ImmutableList<String?>? =
            CcToolchainVariables.toStringList(
                variables, CompileBuildVariables.USER_COMPILE_FLAGS.variableName, PathMapper.NOOP
            )
        Truth.assertThat(copts)
            .containsExactlyElementsIn(
                com.google.common.collect.ImmutableList.of<String?>(
                    "-foo",
                    "-bar",
                    "-baz",
                    "-per-file"
                )
            )
            .inOrder()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCxxFlagsOrder() {
        useConfiguration(
            "--cxxopt=-foo", "--conlyopt=-not-passed", "--per_file_copt=//x:bin@-per-file"
        )

        scratch.file(
            "x/BUILD",
            "load('@rules_cc//cc:cc_binary.bzl', 'cc_binary')",
            "cc_binary(name = 'bin', srcs = ['bin.cc'], copts = ['-bar'], cxxopts = ['-baz'], conlyopts"
                    + " = ['-not-passed'])"
        )
        scratch.file("x/bin.cc")

        val variables: CcToolchainVariables = getCompileBuildVariables("//x:bin", "bin")

        val copts: com.google.common.collect.ImmutableList<String?>? =
            CcToolchainVariables.toStringList(
                variables, CompileBuildVariables.USER_COMPILE_FLAGS.variableName, PathMapper.NOOP
            )
        Truth.assertThat(copts)
            .containsExactlyElementsIn(
                com.google.common.collect.ImmutableList.of<String?>(
                    "-foo",
                    "-bar",
                    "-baz",
                    "-per-file"
                )
            )
            .inOrder()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testPerFileCoptsAreInUserCompileFlags() {
        scratch.file(
            "x/BUILD",
            "load('@rules_cc//cc:cc_binary.bzl', 'cc_binary')",
            "cc_binary(name = 'bin', srcs = ['bin.cc'])"
        )
        scratch.file("x/bin.cc")
        useConfiguration(
            "--per_file_copt=//x:bin@-foo",
            "--per_file_copt=//x:bar\\.cc@-bar",
            "--host_per_file_copt=//x:bin@-baz"
        )

        val variables: CcToolchainVariables = getCompileBuildVariables("//x:bin", "bin")

        val copts: com.google.common.collect.ImmutableList<String?>? =
            CcToolchainVariables.toStringList(
                variables, CompileBuildVariables.USER_COMPILE_FLAGS.variableName, PathMapper.NOOP
            )
        Truth.assertThat(copts).containsExactly("-foo").inOrder()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testHostPerFileCoptsAreInUserCompileFlags() {
        scratch.file(
            "x/BUILD",
            "load('@rules_cc//cc:cc_binary.bzl', 'cc_binary')",
            "cc_binary(name = 'bin', srcs = ['bin.cc'])"
        )
        scratch.file("x/bin.cc")
        useConfiguration(
            "--host_per_file_copt=//x:bin@-foo",
            "--host_per_file_copt=//x:bar\\.cc@-bar",
            "--per_file_copt=//x:bin@-baz"
        )

        val target: ConfiguredTarget = getConfiguredTarget("//x:bin", execConfiguration)
        val variables: CcToolchainVariables? =
            getCppCompileAction(target, "bin").getCompileCommandLine().getVariables()

        val copts: com.google.common.collect.ImmutableList<String?>? =
            CcToolchainVariables.toStringList(
                variables, CompileBuildVariables.USER_COMPILE_FLAGS.variableName, PathMapper.NOOP
            )
        Truth.assertThat(copts).contains("-foo")
        Truth.assertThat(copts).doesNotContain("-bar")
        Truth.assertThat(copts).doesNotContain("-baz")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testPresenceOfSysrootBuildVariable() {
        AnalysisMock.get()
            .ccSupport()
            .setupCcToolchainConfig(
                mockToolsConfig, CcToolchainConfig.builder().withSysroot("/usr/local/custom-sysroot")
            )
        useConfiguration()

        scratch.file(
            "x/BUILD",
            "load('@rules_cc//cc:cc_binary.bzl', 'cc_binary')",
            "cc_binary(name = 'bin', srcs = ['bin.cc'])"
        )
        scratch.file("x/bin.cc")

        val variables: CcToolchainVariables = getCompileBuildVariables("//x:bin", "bin")

        assertThat(variables.getStringVariable(SYSROOT_VARIABLE_NAME, PathMapper.NOOP))
            .isEqualTo("/usr/local/custom-sysroot")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTargetSysrootWithoutPlatforms() {
        useConfiguration(
            "--grte_top=//target_libc",
            "--host_grte_top=//host_libc",
            "--noincompatible_enable_cc_toolchain_resolution"
        )

        scratch.file(
            "x/BUILD",
            "load('@rules_cc//cc:cc_binary.bzl', 'cc_binary')",
            "cc_binary(name = 'bin', srcs = ['bin.cc'])"
        )
        scratch.file("x/bin.cc")
        scratch.file("target_libc/BUILD", "filegroup(name = 'everything')")
        scratch.file("host_libc/BUILD", "filegroup(name = 'everything')")

        val variables: CcToolchainVariables = getCompileBuildVariables("//x:bin", "bin")

        assertThat(variables.getStringVariable(SYSROOT_VARIABLE_NAME, PathMapper.NOOP))
            .isEqualTo("target_libc")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testTargetSysrootWithPlatforms() {
        MockPlatformSupport.addMockK8Platform(
            mockToolsConfig, analysisMock.ccSupport().getMockCrosstoolLabel()
        )
        useConfiguration(
            "--experimental_platforms=//mock_platform:mock-k8-platform",
            "--extra_toolchains=//mock_platform:toolchain_cc-compiler-k8",
            "--incompatible_enable_cc_toolchain_resolution",
            "--grte_top=//target_libc",
            "--host_grte_top=//host_libc"
        )

        scratch.file(
            "x/BUILD",
            "load('@rules_cc//cc:cc_binary.bzl', 'cc_binary')",
            "cc_binary(name = 'bin', srcs = ['bin.cc'])"
        )
        scratch.file("x/bin.cc")
        scratch.file("target_libc/BUILD", "filegroup(name = 'everything')")
        scratch.file("host_libc/BUILD", "filegroup(name = 'everything')")

        val variables: CcToolchainVariables = getCompileBuildVariables("//x:bin", "bin")

        assertThat(variables.getStringVariable(SYSROOT_VARIABLE_NAME, PathMapper.NOOP))
            .isEqualTo("target_libc")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testPresenceOfPerObjectDebugFileBuildVariable() {
        AnalysisMock.get()
            .ccSupport()
            .setupCcToolchainConfig(
                mockToolsConfig,
                CcToolchainConfig.builder().withFeatures(CppRuleClasses.PER_OBJECT_DEBUG_INFO)
            )
        useConfiguration("--fission=yes")

        scratch.file(
            "x/BUILD",
            "load('@rules_cc//cc:cc_binary.bzl', 'cc_binary')",
            "cc_binary(name = 'bin', srcs = ['bin.cc'])"
        )
        scratch.file("x/bin.cc")

        val variables: CcToolchainVariables = getCompileBuildVariables("//x:bin", "bin")

        assertThat(
            variables.getStringVariable(
                CompileBuildVariables.PER_OBJECT_DEBUG_INFO_FILE.variableName,
                PathMapper.NOOP
            )
        )
            .isNotNull()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testPresenceOfIsUsingFissionVariable() {
        AnalysisMock.get()
            .ccSupport()
            .setupCcToolchainConfig(
                mockToolsConfig,
                CcToolchainConfig.builder().withFeatures(CppRuleClasses.PER_OBJECT_DEBUG_INFO)
            )
        useConfiguration("--fission=yes")

        scratch.file(
            "x/BUILD",
            "load('@rules_cc//cc:cc_binary.bzl', 'cc_binary')",
            "cc_binary(name = 'bin', srcs = ['bin.cc'])"
        )
        scratch.file("x/bin.cc")

        val variables: CcToolchainVariables = getCompileBuildVariables("//x:bin", "bin")

        assertThat(
            variables.getStringVariable(
                CompileBuildVariables.IS_USING_FISSION.variableName, PathMapper.NOOP
            )
        )
            .isNotNull()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testPresenceOfIsUsingFissionAndPerDebugObjectFileVariablesWithThinlto() {
        AnalysisMock.get()
            .ccSupport()
            .setupCcToolchainConfig(
                mockToolsConfig,
                CcToolchainConfig.builder()
                    .withFeatures(
                        "fission_flags_for_lto_backend",
                        CppRuleClasses.PER_OBJECT_DEBUG_INFO,
                        CppRuleClasses.SUPPORTS_START_END_LIB,
                        CppRuleClasses.THIN_LTO
                    )
            )
        useConfiguration("--fission=yes", "--features=thin_lto")

        scratch.file(
            "x/BUILD",
            "load('@rules_cc//cc:cc_binary.bzl', 'cc_binary')",
            "cc_binary(name = 'bin', srcs = ['bin.cc'])"
        )
        scratch.file("x/bin.cc")

        val target: RuleConfiguredTarget = getConfiguredTarget("//x:bin") as RuleConfiguredTarget
        val backendAction: LtoBackendAction =
            target.getActions().stream()
                .filter({ a -> a.getMnemonic().equals("CcLtoBackendCompile") })
                .findFirst()
                .get() as LtoBackendAction
        val bitcodeAction: CppCompileAction =
            target.getActions().stream()
                .filter({ a -> a.getMnemonic().equals("CppCompile") })
                .findFirst()
                .get() as CppCompileAction

        // We don't pass per_object_debug_info_file to bitcode compiles
        assertThat(
            bitcodeAction
                .getCompileCommandLine()
                .getVariables()
                .isAvailable(CompileBuildVariables.IS_USING_FISSION.variableName)
        )
            .isTrue()
        assertThat(
            bitcodeAction
                .getCompileCommandLine()
                .getVariables()
                .isAvailable(CompileBuildVariables.PER_OBJECT_DEBUG_INFO_FILE.variableName)
        )
            .isFalse()

        // We do pass per_object_debug_info_file to backend compiles
        com.google.common.truth.Subject.contains("-<PER_OBJECT_DEBUG_INFO_FILE>")
        com.google.common.truth.Subject.contains("-<IS_USING_FISSION>")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testPresenceOfPerObjectDebugFileBuildVariableUsingLegacyFields() {
        AnalysisMock.get()
            .ccSupport()
            .setupCcToolchainConfig(
                mockToolsConfig,
                CcToolchainConfig.builder().withFeatures(CppRuleClasses.PER_OBJECT_DEBUG_INFO)
            )
        useConfiguration("--fission=yes")

        scratch.file(
            "x/BUILD",
            "load('@rules_cc//cc:cc_binary.bzl', 'cc_binary')",
            "cc_binary(name = 'bin', srcs = ['bin.cc'])"
        )
        scratch.file("x/bin.cc")

        val variables: CcToolchainVariables = getCompileBuildVariables("//x:bin", "bin")

        assertThat(
            variables.getStringVariable(
                CompileBuildVariables.PER_OBJECT_DEBUG_INFO_FILE.variableName,
                PathMapper.NOOP
            )
        )
            .isNotNull()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testPresenceOfMinOsVersionBuildVariable() {
        AnalysisMock.get()
            .ccSupport()
            .setupCcToolchainConfig(
                mockToolsConfig, CcToolchainConfig.builder().withFeatures("min_os_version_flag")
            )
        useConfiguration("--minimum_os_version=6")
        scratch.file(
            "x/BUILD",
            "load('@rules_cc//cc:cc_binary.bzl', 'cc_binary')",
            "cc_binary(name = 'bin', srcs = ['bin.cc'])"
        )
        scratch.file("x/bin.cc")

        val variables: CcToolchainVariables = getCompileBuildVariables("//x:bin", "bin")
        assertThat(variables.getStringVariable(MINIMUM_OS_VERSION_VARIABLE_NAME, PathMapper.NOOP))
            .isEqualTo("6")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testExternalIncludePathsVariable() {
        if (!analysisMock.isThisBazel) {
            return
        }
        AnalysisMock.get()
            .ccSupport()
            .setupCcToolchainConfig(
                mockToolsConfig,
                CcToolchainConfig.builder().withFeatures(CppRuleClasses.EXTERNAL_INCLUDE_PATHS)
            )
        useConfiguration(
            "--features=external_include_paths",
            "--platforms=" + TestConstants.PLATFORM_LABEL,
            "--experimental_platform_in_output_dir",
            String.format(
                "--experimental_override_name_platform_in_output_dir=%s=k8",
                TestConstants.PLATFORM_LABEL
            )
        )
        scratch.appendFile(
            "MODULE.bazel",
            "bazel_dep(name = 'pkg')",
            "local_path_override(module_name = 'pkg', path = '/foo')"
        )
        getSkyframeExecutor()
            .invalidateFilesUnderPathForTesting(
                reporter,
                Builder().modify(PathFragment.create("MODULE.bazel")).build(),
                Root.fromPath(rootDirectory)
            )

        scratch.file("/foo/MODULE.bazel", "module(name = 'pkg')")
        AnalysisMock.get().ccSupport().setup(MockToolsConfig(scratch.resolve("/foo")))
        scratch.file(
            "/foo/third_party/BUILD",
            """
        load("@rules_cc//cc:cc_library.bzl", "cc_library")
        cc_library(
            name = "foo",
            hdrs = ["foo.hpp"],
        )

        cc_library(
            name = "foo2",
            hdrs = ["foo.hpp"],
            include_prefix = "prf",
        )
        
        """.trimIndent()
        )
        scratch.file(
            "x/BUILD",
            """
        load("@rules_cc//cc:cc_binary.bzl", "cc_binary")
        load("@rules_cc//cc:cc_library.bzl", "cc_library")
        cc_library(
            name = "bar",
            hdrs = ["bar.hpp"],
        )

        cc_binary(
            name = "bin",
            srcs = ["bin.cc"],
            deps = [
                "bar",
                "@pkg//third_party:foo",
                "@pkg//third_party:foo2",
            ],
        )
        
        """.trimIndent()
        )

        val variables: CcToolchainVariables = getCompileBuildVariables("//x:bin", "bin")

        val entries: com.google.common.collect.ImmutableList.Builder<String?> =
            com.google.common.collect.ImmutableList.builder<String?>()
                .add(
                    "/k8-fastbuild/bin/external/pkg+/third_party/_virtual_includes/foo2",
                    "external/pkg+",
                    "/k8-fastbuild/bin/external/pkg+"
                )
        if (analysisMock.isThisBazel) {
            entries.add("external/bazel_tools", "/k8-fastbuild/bin/external/bazel_tools")
        }

        assertThat(
            CcToolchainVariables.toStringList(
                variables,
                CompileBuildVariables.EXTERNAL_INCLUDE_PATHS.variableName,
                PathMapper.NOOP
            )
                .stream()
                .map({ x -> removeOutDirectory(x) })
                .collect(com.google.common.collect.ImmutableList.toImmutableList<E?>())
        )
            .containsExactlyElementsIn(entries.build())
    }

    private fun removeOutDirectory(s: String): String? {
        return s.replace("blaze-out", "").replace("bazel-out", "")
    }

    companion object {
        /** Name of the build variable for the sysroot path variable name.  */
        const val SYSROOT_VARIABLE_NAME: String = "sysroot"

        /** Name of the build variable for the minimum_os_version being targeted.  */
        const val MINIMUM_OS_VERSION_VARIABLE_NAME: String = "minimum_os_version"
    }
}
