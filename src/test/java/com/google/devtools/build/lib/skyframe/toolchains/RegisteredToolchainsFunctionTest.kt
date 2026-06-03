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
package com.google.devtools.build.lib.skyframe.toolchains

import com.google.devtools.build.lib.bazel.bzlmod.BzlmodTestUtil.createModuleKey

/** Tests for [RegisteredToolchainsFunction] and [RegisteredToolchainsValue].  */
@RunWith(JUnit4::class)
class RegisteredToolchainsFunctionTest : ToolchainTestCase() {
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRegisteredToolchains() {
        // Request the toolchains.
        val toolchainsKey: SkyKey? = RegisteredToolchainsValue.key(targetConfigKey,  /* debug= */false)
        val result: EvaluationResult<RegisteredToolchainsValue?> =
            requestToolchainsFromSkyframe(toolchainsKey)
        EvaluationResultSubjectFactory.assertThatEvaluationResult(result).hasNoError()
        EvaluationResultSubjectFactory.assertThatEvaluationResult(result).hasEntryThat(toolchainsKey).isNotNull()

        val value: RegisteredToolchainsValue = result.get(toolchainsKey)

        // Check that the number of toolchains created for this test is correct.
        assertThat(
            value.registeredToolchains().stream()
                .filter({ toolchain -> toolchain.toolchainType().equals(testToolchainTypeInfo) })
                .collect(Collectors.toList())
        )
            .hasSize(2)

        assertThat(
            value.registeredToolchains().stream()
                .anyMatch(
                    { toolchain ->
                        toolchain.toolchainType().equals(testToolchainTypeInfo)
                                && toolchain.execConstraints().get(setting).equals(linuxConstraint)
                                && toolchain.targetConstraints().get(setting).equals(macConstraint)
                                && toolchain
                            .resolvedToolchainLabel()
                            .equals(
                                Label.parseCanonicalUnchecked("//toolchain:toolchain_1_impl")
                            )
                    })
        )
            .isTrue()

        assertThat(
            value.registeredToolchains().stream()
                .anyMatch(
                    { toolchain ->
                        toolchain.toolchainType().equals(testToolchainTypeInfo)
                                && toolchain.execConstraints().get(setting).equals(macConstraint)
                                && toolchain.targetConstraints().get(setting).equals(linuxConstraint)
                                && toolchain
                            .resolvedToolchainLabel()
                            .equals(
                                Label.parseCanonicalUnchecked("//toolchain:toolchain_2_impl")
                            )
                    })
        )
            .isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRegisteredToolchains_flagOverride() {
        // Add an extra toolchain.

        scratch.file(
            "extra/BUILD",
            """
        load("//toolchain:toolchain_def.bzl", "test_toolchain")

        toolchain(
            name = "extra_toolchain",
            exec_compatible_with = ["//constraints:linux"],
            target_compatible_with = ["//constraints:linux"],
            toolchain = ":extra_toolchain_impl",
            toolchain_type = "//toolchain:test_toolchain",
        )

        test_toolchain(
            name = "extra_toolchain_impl",
            data = "extra",
        )
        
        """.trimIndent()
        )

        rewriteModuleDotBazel(
            """
        register_toolchains('//toolchain:toolchain_1')
        
        """.trimIndent()
        )
        useConfiguration("--extra_toolchains=//extra:extra_toolchain")

        val toolchainsKey: SkyKey? = RegisteredToolchainsValue.key(targetConfigKey,  /* debug= */false)
        val result: EvaluationResult<RegisteredToolchainsValue?> =
            requestToolchainsFromSkyframe(toolchainsKey)
        EvaluationResultSubjectFactory.assertThatEvaluationResult(result).hasNoError()

        // Verify that the target registered with the extra_toolchains flag is first in the list.
        assertToolchainLabels(result.get(toolchainsKey))
            .containsAtLeast(
                Label.parseCanonicalUnchecked("//extra:extra_toolchain_impl"),
                Label.parseCanonicalUnchecked("//toolchain:toolchain_1_impl")
            )
            .inOrder()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRegisteredToolchains_flagOverride_multiple() {
        // Add an extra toolchain.

        scratch.file(
            "extra/BUILD",
            """
        load("//toolchain:toolchain_def.bzl", "test_toolchain")

        toolchain(
            name = "extra_toolchain_1",
            exec_compatible_with = ["//constraints:linux"],
            target_compatible_with = ["//constraints:linux"],
            toolchain = ":extra_toolchain_impl_1",
            toolchain_type = "//toolchain:test_toolchain",
        )

        test_toolchain(
            name = "extra_toolchain_impl_1",
            data = "extra",
        )

        toolchain(
            name = "extra_toolchain_2",
            exec_compatible_with = ["//constraints:mac"],
            target_compatible_with = ["//constraints:linux"],
            toolchain = ":extra_toolchain_impl_2",
            toolchain_type = "//toolchain:test_toolchain",
        )

        test_toolchain(
            name = "extra_toolchain_impl_2",
            data = "extra2",
        )
        
        """.trimIndent()
        )

        useConfiguration(
            "--extra_toolchains=//extra:extra_toolchain_1",
            "--extra_toolchains=//extra:extra_toolchain_2"
        )

        val toolchainsKey: SkyKey? = RegisteredToolchainsValue.key(targetConfigKey,  /* debug= */false)
        val result: EvaluationResult<RegisteredToolchainsValue?> =
            requestToolchainsFromSkyframe(toolchainsKey)
        EvaluationResultSubjectFactory.assertThatEvaluationResult(result).hasNoError()

        // Verify that the target registered with the extra_toolchains flag is first in the list.
        assertToolchainLabels(result.get(toolchainsKey))
            .containsAtLeast(
                Label.parseCanonicalUnchecked("//extra:extra_toolchain_impl_2"),
                Label.parseCanonicalUnchecked("//extra:extra_toolchain_impl_1"),
                Label.parseCanonicalUnchecked("//toolchain:toolchain_1_impl")
            )
            .inOrder()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRegisteredToolchains_notToolchain() {
        rewriteModuleDotBazel(
            """
        register_toolchains("//error:not_a_toolchain")
        
        """.trimIndent()
        )
        scratch.file("error/BUILD", "filegroup(name = 'not_a_toolchain')")

        // Request the toolchains.
        val toolchainsKey: SkyKey? = RegisteredToolchainsValue.key(targetConfigKey,  /* debug= */false)
        val result: EvaluationResult<RegisteredToolchainsValue?>? =
            requestToolchainsFromSkyframe(toolchainsKey)
        EvaluationResultSubjectFactory.assertThatEvaluationResult(result)
            .hasErrorEntryForKeyThat(toolchainsKey)
            .hasExceptionThat()
            .hasMessageThat()
            .contains(
                "invalid registered toolchain '//error:not_a_toolchain': "
                        + "target does not provide the DeclaredToolchainInfo provider"
            )
    }

    // Test confirming that targets with the kind `toolchain rule` will be properly rejected if they
    // don't provide the DeclaredToolchainInfo provider.
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRegisteredToolchains_fakeToolchain() {
        rewriteModuleDotBazel(
            """
        register_toolchains("//error:not_a_toolchain")
        
        """.trimIndent()
        )
        scratch.file(
            "error/fake_toolchain.bzl",
            """
        def _fake_impl(ctx):
          pass

        toolchain = rule(implementation = _fake_impl)
        
        """.trimIndent()
        )
        scratch.file(
            "error/BUILD",
            """
        load(':fake_toolchain.bzl', fake_toolchain='toolchain')
        fake_toolchain(name = 'not_a_toolchain')
        
        """.trimIndent()
        )

        // Request the toolchains.
        val toolchainsKey: SkyKey? = RegisteredToolchainsValue.key(targetConfigKey,  /* debug= */false)
        val result: EvaluationResult<RegisteredToolchainsValue?>? =
            requestToolchainsFromSkyframe(toolchainsKey)
        EvaluationResultSubjectFactory.assertThatEvaluationResult(result)
            .hasErrorEntryForKeyThat(toolchainsKey)
            .hasExceptionThat()
            .hasMessageThat()
            .contains(
                "invalid registered toolchain '//error:not_a_toolchain': "
                        + "target does not provide the DeclaredToolchainInfo provider"
            )
    }

    // Test exercising an edge case in the current RegisteredToolchainsFunction logic: if a target
    // has the kind `toolchain rule`, it must provide the DeclaredToolchainInfo provider, or the
    // RegisteredToolchainsFunction will fail.
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRegisteredToolchains_wildcard_fakeToolchain() {
        rewriteModuleDotBazel(
            """
        register_toolchains("//error:all")
        
        """.trimIndent()
        )
        scratch.file(
            "error/fake_toolchain.bzl",
            """
        def _fake_impl(ctx):
          pass

        toolchain = rule(implementation = _fake_impl)
        
        """.trimIndent()
        )
        scratch.file(
            "error/BUILD",
            """
        load(':fake_toolchain.bzl', fake_toolchain='toolchain')
        fake_toolchain(name = 'not_a_toolchain')
        
        """.trimIndent()
        )

        // Request the toolchains.
        val toolchainsKey: SkyKey? = RegisteredToolchainsValue.key(targetConfigKey,  /* debug= */false)
        val result: EvaluationResult<RegisteredToolchainsValue?>? =
            requestToolchainsFromSkyframe(toolchainsKey)
        EvaluationResultSubjectFactory.assertThatEvaluationResult(result)
            .hasErrorEntryForKeyThat(toolchainsKey)
            .hasExceptionThat()
            .hasMessageThat()
            .contains(
                "invalid registered toolchain '//error:not_a_toolchain': "
                        + "target does not provide the DeclaredToolchainInfo provider"
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRegisteredToolchains_targetPattern_workspace() {
        scratch.appendFile("extra/BUILD", "filegroup(name = 'not_a_platform')")
        addToolchain(
            "extra",
            "extra_toolchain1",
            com.google.common.collect.ImmutableList.of<E?>("//constraints:linux"),
            com.google.common.collect.ImmutableList.of<E?>("//constraints:linux"),
            "foo"
        )
        addToolchain(
            "extra",
            "extra_toolchain2",
            com.google.common.collect.ImmutableList.of<E?>("//constraints:linux"),
            com.google.common.collect.ImmutableList.of<E?>("//constraints:mac"),
            "bar"
        )
        addToolchain(
            "extra/more",
            "more_toolchain",
            com.google.common.collect.ImmutableList.of<E?>("//constraints:mac"),
            com.google.common.collect.ImmutableList.of<E?>("//constraints:linux"),
            "baz"
        )
        rewriteModuleDotBazel(
            """
        register_toolchains("//extra/...")
        
        """.trimIndent()
        )

        val toolchainsKey: SkyKey? = RegisteredToolchainsValue.key(targetConfigKey,  /* debug= */false)
        val result: EvaluationResult<RegisteredToolchainsValue?> =
            requestToolchainsFromSkyframe(toolchainsKey)
        EvaluationResultSubjectFactory.assertThatEvaluationResult(result).hasNoError()
        assertToolchainLabels(result.get(toolchainsKey), PackageIdentifier.createInMainRepo("extra"))
            .containsExactly(
                Label.parseCanonicalUnchecked("//extra:extra_toolchain1_impl"),
                Label.parseCanonicalUnchecked("//extra:extra_toolchain2_impl"),
                Label.parseCanonicalUnchecked("//extra/more:more_toolchain_impl")
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRegisteredToolchains_targetPattern_flagOverride() {
        scratch.appendFile("extra/BUILD", "filegroup(name = 'not_a_platform')")
        addToolchain(
            "extra",
            "extra_toolchain1",
            com.google.common.collect.ImmutableList.of<E?>("//constraints:linux"),
            com.google.common.collect.ImmutableList.of<E?>("//constraints:linux"),
            "foo"
        )
        addToolchain(
            "extra",
            "extra_toolchain2",
            com.google.common.collect.ImmutableList.of<E?>("//constraints:linux"),
            com.google.common.collect.ImmutableList.of<E?>("//constraints:mac"),
            "bar"
        )
        addToolchain(
            "extra/more",
            "more_toolchain",
            com.google.common.collect.ImmutableList.of<E?>("//constraints:mac"),
            com.google.common.collect.ImmutableList.of<E?>("//constraints:linux"),
            "baz"
        )
        useConfiguration("--extra_toolchains=//extra/...")

        val toolchainsKey: SkyKey? = RegisteredToolchainsValue.key(targetConfigKey,  /* debug= */false)
        val result: EvaluationResult<RegisteredToolchainsValue?> =
            requestToolchainsFromSkyframe(toolchainsKey)
        EvaluationResultSubjectFactory.assertThatEvaluationResult(result).hasNoError()
        assertToolchainLabels(result.get(toolchainsKey))
            .containsAtLeast(
                Label.parseCanonicalUnchecked("//extra:extra_toolchain1_impl"),
                Label.parseCanonicalUnchecked("//extra:extra_toolchain2_impl"),
                Label.parseCanonicalUnchecked("//extra/more:more_toolchain_impl")
            )
    }

    @Throws(java.lang.Exception::class)
    private fun addSimpleToolchain(packageName: String?, toolchainName: String?) {
        addToolchain(
            packageName,
            toolchainName,
            com.google.common.collect.ImmutableList.of<E?>(),
            com.google.common.collect.ImmutableList.of<E?>(),
            "foo"
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRegisteredToolchains_targetPattern_order() {
        addSimpleToolchain("extra", "bbb")
        addSimpleToolchain("extra", "ccc")
        addSimpleToolchain("extra", "aaa")
        addSimpleToolchain("extra/yyy", "bbb")
        addSimpleToolchain("extra/yyy", "ccc")
        addSimpleToolchain("extra/yyy", "aaa")
        addSimpleToolchain("extra/xxx", "bbb")
        addSimpleToolchain("extra/xxx", "ccc")
        addSimpleToolchain("extra/xxx", "aaa")
        addSimpleToolchain("extra/zzz", "bbb")
        addSimpleToolchain("extra/zzz", "ccc")
        addSimpleToolchain("extra/zzz", "aaa")
        addSimpleToolchain("extra/yyy/yyy", "bbb")
        addSimpleToolchain("extra/yyy/yyy", "ccc")
        addSimpleToolchain("extra/yyy/yyy", "aaa")
        addSimpleToolchain("extra/yyy/xxx", "bbb")
        addSimpleToolchain("extra/yyy/xxx", "ccc")
        addSimpleToolchain("extra/yyy/xxx", "aaa")
        addSimpleToolchain("extra/yyy/zzz", "bbb")
        addSimpleToolchain("extra/yyy/zzz", "ccc")
        addSimpleToolchain("extra/yyy/zzz", "aaa")
        addSimpleToolchain("extra/xxx/yyy", "bbb")
        addSimpleToolchain("extra/xxx/yyy", "ccc")
        addSimpleToolchain("extra/xxx/yyy", "aaa")
        addSimpleToolchain("extra/xxx/xxx", "bbb")
        addSimpleToolchain("extra/xxx/xxx", "ccc")
        addSimpleToolchain("extra/xxx/xxx", "aaa")
        addSimpleToolchain("extra/xxx/zzz", "bbb")
        addSimpleToolchain("extra/xxx/zzz", "ccc")
        addSimpleToolchain("extra/xxx/zzz", "aaa")
        rewriteModuleDotBazel(
            """
        register_toolchains("//extra/...")
        
        """.trimIndent()
        )

        val toolchainsKey: SkyKey? = RegisteredToolchainsValue.key(targetConfigKey,  /* debug= */false)
        val result: EvaluationResult<RegisteredToolchainsValue?> =
            requestToolchainsFromSkyframe(toolchainsKey)
        EvaluationResultSubjectFactory.assertThatEvaluationResult(result).hasNoError()
        assertToolchainLabels(result.get(toolchainsKey), PackageIdentifier.createInMainRepo("extra"))
            .containsExactly(
                Label.parseCanonicalUnchecked("//extra/xxx/xxx:aaa_impl"),
                Label.parseCanonicalUnchecked("//extra/xxx/xxx:bbb_impl"),
                Label.parseCanonicalUnchecked("//extra/xxx/xxx:ccc_impl"),
                Label.parseCanonicalUnchecked("//extra/xxx/yyy:aaa_impl"),
                Label.parseCanonicalUnchecked("//extra/xxx/yyy:bbb_impl"),
                Label.parseCanonicalUnchecked("//extra/xxx/yyy:ccc_impl"),
                Label.parseCanonicalUnchecked("//extra/xxx/zzz:aaa_impl"),
                Label.parseCanonicalUnchecked("//extra/xxx/zzz:bbb_impl"),
                Label.parseCanonicalUnchecked("//extra/xxx/zzz:ccc_impl"),
                Label.parseCanonicalUnchecked("//extra/xxx:aaa_impl"),
                Label.parseCanonicalUnchecked("//extra/xxx:bbb_impl"),
                Label.parseCanonicalUnchecked("//extra/xxx:ccc_impl"),
                Label.parseCanonicalUnchecked("//extra/yyy/xxx:aaa_impl"),
                Label.parseCanonicalUnchecked("//extra/yyy/xxx:bbb_impl"),
                Label.parseCanonicalUnchecked("//extra/yyy/xxx:ccc_impl"),
                Label.parseCanonicalUnchecked("//extra/yyy/yyy:aaa_impl"),
                Label.parseCanonicalUnchecked("//extra/yyy/yyy:bbb_impl"),
                Label.parseCanonicalUnchecked("//extra/yyy/yyy:ccc_impl"),
                Label.parseCanonicalUnchecked("//extra/yyy/zzz:aaa_impl"),
                Label.parseCanonicalUnchecked("//extra/yyy/zzz:bbb_impl"),
                Label.parseCanonicalUnchecked("//extra/yyy/zzz:ccc_impl"),
                Label.parseCanonicalUnchecked("//extra/yyy:aaa_impl"),
                Label.parseCanonicalUnchecked("//extra/yyy:bbb_impl"),
                Label.parseCanonicalUnchecked("//extra/yyy:ccc_impl"),
                Label.parseCanonicalUnchecked("//extra/zzz:aaa_impl"),
                Label.parseCanonicalUnchecked("//extra/zzz:bbb_impl"),
                Label.parseCanonicalUnchecked("//extra/zzz:ccc_impl"),
                Label.parseCanonicalUnchecked("//extra:aaa_impl"),
                Label.parseCanonicalUnchecked("//extra:bbb_impl"),
                Label.parseCanonicalUnchecked("//extra:ccc_impl")
            )
            .inOrder()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRegisteredToolchains_reload() {
        rewriteModuleDotBazel(
            """
        register_toolchains("//toolchain:toolchain_1")
        
        """.trimIndent()
        )

        var toolchainsKey: SkyKey? = RegisteredToolchainsValue.key(targetConfigKey,  /* debug= */false)
        var result: EvaluationResult<RegisteredToolchainsValue?> =
            requestToolchainsFromSkyframe(toolchainsKey)
        EvaluationResultSubjectFactory.assertThatEvaluationResult(result).hasNoError()
        assertToolchainLabels(result.get(toolchainsKey))
            .contains(Label.parseCanonicalUnchecked("//toolchain:toolchain_1_impl"))

        // Re-write the MODULE.bazel.
        rewriteModuleDotBazel(
            """
        register_toolchains("//toolchain:toolchain_2")
        
        """.trimIndent()
        )

        toolchainsKey = RegisteredToolchainsValue.key(targetConfigKey,  /* debug= */false)
        result = requestToolchainsFromSkyframe(toolchainsKey)
        EvaluationResultSubjectFactory.assertThatEvaluationResult(result).hasNoError()
        assertToolchainLabels(result.get(toolchainsKey))
            .contains(Label.parseCanonicalUnchecked("//toolchain:toolchain_2_impl"))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRegisteredToolchains_bzlmod() {
        scratch.overwriteFile(
            "MODULE.bazel",
            "register_toolchains('//:tool')",
            "register_toolchains('//:dev_tool',dev_dependency=True)",
            "bazel_dep(name='bbb',version='1.0')",
            "bazel_dep(name='ccc',version='1.1')",
            "bazel_dep(name='toolchain_def',version='1.0')"
        )
        registry
            .addModule(
                createModuleKey("bbb", "1.0"),
                "module(name='bbb',version='1.0')",
                "register_toolchains('//:tool')",
                "register_toolchains('//:dev_tool',dev_dependency=True)",
                "bazel_dep(name='ddd',version='1.0')",
                "bazel_dep(name='toolchain_def',version='1.0')"
            )
            .addModule(
                createModuleKey("ccc", "1.1"),
                "module(name='ccc',version='1.1')",
                "register_toolchains('//:tool')",
                "register_toolchains('//:dev_tool',dev_dependency=True)",
                "bazel_dep(name='ddd',version='1.1')",
                "bazel_dep(name='toolchain_def',version='1.0')"
            ) // ddd@1.0 is not selected
            .addModule(
                createModuleKey("ddd", "1.0"),
                "module(name='ddd',version='1.0')",
                "register_toolchains('//:tool')",
                "register_toolchains('//:dev_tool',dev_dependency=True)",
                "bazel_dep(name='toolchain_def',version='1.0')"
            )
            .addModule(
                createModuleKey("ddd", "1.1"),
                "module(name='ddd',version='1.1')",
                "register_toolchains('@eee//:tool', '//:tool')",
                "register_toolchains('@eee//:dev_tool',dev_dependency=True)",
                "bazel_dep(name='eee',version='1.0')",
                "bazel_dep(name='toolchain_def',version='1.0')"
            )
            .addModule(
                createModuleKey("eee", "1.0"),
                "module(name='eee',version='1.0')",
                "bazel_dep(name='toolchain_def',version='1.0')"
            )
            .addModule(
                createModuleKey("toolchain_def", "1.0"), "module(name='toolchain_def',version='1.0')"
            )

        // Everyone depends on toolchain_def@1.0 for the declare_toolchain macro.
        val toolchainDefDir: Path = moduleRoot.getRelative("toolchain_def+1.0")
        scratch.file(toolchainDefDir.getRelative("REPO.bazel").getPathString())
        scratch.file(
            toolchainDefDir.getRelative("BUILD").getPathString(),
            "toolchain_type(name = 'test_toolchain')"
        )
        scratch.file(
            toolchainDefDir.getRelative("toolchain_def.bzl").getPathString(),
            "def _impl(ctx):",
            "    toolchain = platform_common.ToolchainInfo(data = ctx.attr.data)",
            "    return [toolchain]",
            "test_toolchain = rule(implementation = _impl, attrs = {'data': attr.string()})",
            "def declare_toolchain(name):",
            "    native.toolchain(",
            "        name = name,",
            "        toolchain_type = Label('//:test_toolchain'),",
            "        toolchain = ':' + name + '_impl')",
            "    test_toolchain(",
            "        name = name + '_impl',",
            "        data = 'stuff')"
        )

        // Now create the toolchains for each module.
        for (repo in com.google.common.collect.ImmutableList.of<String?>(
            "bbb+1.0",
            "ccc+1.1",
            "ddd+1.0",
            "ddd+1.1",
            "eee+1.0"
        )) {
            scratch.file(moduleRoot.getRelative(repo).getRelative("REPO.bazel").getPathString())
            scratch.file(
                moduleRoot.getRelative(repo).getRelative("BUILD").getPathString(),
                "load('@toolchain_def//:toolchain_def.bzl', 'declare_toolchain')",
                "declare_toolchain(name='tool')",
                "declare_toolchain(name='dev_tool')"
            )
        }
        scratch.overwriteFile(
            "BUILD",
            "load('@toolchain_def//:toolchain_def.bzl', 'declare_toolchain')",
            "declare_toolchain(name='dev_tool')",
            "declare_toolchain(name='tool')"
        )
        invalidatePackages()

        val toolchainsKey: SkyKey? = RegisteredToolchainsValue.key(targetConfigKey,  /* debug= */false)
        val result: EvaluationResult<RegisteredToolchainsValue?> =
            requestToolchainsFromSkyframe(toolchainsKey)
        if (result.hasError()) {
            throw result.getError().getException()
        }
        EvaluationResultSubjectFactory.assertThatEvaluationResult(result).hasNoError()

        // Verify that the toolchains registered with bzlmod come in the BFS order
        assertToolchainLabels(result.get(toolchainsKey))
            .containsAtLeast( // Root module toolchains
                Label.parseCanonical("//:tool_impl"),
                Label.parseCanonical("//:dev_tool_impl"),  // Other modules' toolchains
                Label.parseCanonical("@@bbb+//:tool_impl"),
                Label.parseCanonical("@@ccc+//:tool_impl"),
                Label.parseCanonical("@@eee+//:tool_impl"),
                Label.parseCanonical("@@ddd+//:tool_impl")
            )
            .inOrder()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRegisteredToolchains_targetSetting() {
        // Add an extra toolchain with a target_setting
        scratch.file(
            "extra/BUILD",
            """
        load("//toolchain:toolchain_def.bzl", "test_toolchain")

        config_setting(
            name = "optimized",
            values = {
               "compilation_mode": "opt",
            },
        )

        toolchain(
            name = "extra_toolchain",
            exec_compatible_with = ["//constraints:linux"],
            target_compatible_with = ["//constraints:linux"],
            target_settings = [
                ":optimized",
            ],
            toolchain = ":extra_toolchain_impl",
            toolchain_type = "//toolchain:test_toolchain",
        )

        test_toolchain(
            name = "extra_toolchain_impl",
            data = "extra",
        )
        
        """.trimIndent()
        )

        rewriteModuleDotBazel(
            """
        register_toolchains("//toolchain:toolchain_1", "//extra:extra_toolchain")
        
        """.trimIndent()
        )

        val toolchainsKey: SkyKey? = RegisteredToolchainsValue.key(targetConfigKey,  /* debug= */false)
        val result: EvaluationResult<RegisteredToolchainsValue?> =
            requestToolchainsFromSkyframe(toolchainsKey)
        EvaluationResultSubjectFactory.assertThatEvaluationResult(result).hasNoError()

        // Verify that the target registered with the extra_toolchains flag is not present, because of
        // the configuration.
        val registeredToolchainsValue: RegisteredToolchainsValue = result.get(toolchainsKey)
        assertToolchainLabels(registeredToolchainsValue)
            .contains(Label.parseCanonicalUnchecked("//toolchain:toolchain_1_impl"))
        assertToolchainLabels(registeredToolchainsValue)
            .doesNotContain(Label.parseCanonicalUnchecked("//extra:extra_toolchain_impl"))
        assertThat(registeredToolchainsValue.rejectedToolchains()).isNull()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRegisteredToolchains_targetSetting_debug() {
        // Add an extra toolchain with a target_setting
        scratch.file(
            "extra/BUILD",
            """
        load("//toolchain:toolchain_def.bzl", "test_toolchain")

        config_setting(
            name = "optimized",
            values = {
               "compilation_mode": "opt",
            },
        )

        toolchain(
            name = "extra_toolchain",
            exec_compatible_with = ["//constraints:linux"],
            target_compatible_with = ["//constraints:linux"],
            target_settings = [
                ":optimized",
            ],
            toolchain = ":extra_toolchain_impl",
            toolchain_type = "//toolchain:test_toolchain",
        )

        test_toolchain(
            name = "extra_toolchain_impl",
            data = "extra",
        )
        
        """.trimIndent()
        )

        rewriteModuleDotBazel(
            """
        register_toolchains("//toolchain:toolchain_1", "//extra:extra_toolchain")
        
        """.trimIndent()
        )

        val toolchainsKey: SkyKey? = RegisteredToolchainsValue.key(targetConfigKey,  /* debug= */true)
        val result: EvaluationResult<RegisteredToolchainsValue?> =
            requestToolchainsFromSkyframe(toolchainsKey)
        EvaluationResultSubjectFactory.assertThatEvaluationResult(result).hasNoError()

        // Verify that the message about the unmatched config_setting is present.
        val registeredToolchainsValue: RegisteredToolchainsValue = result.get(toolchainsKey)
        assertThat(registeredToolchainsValue.rejectedToolchains()).isNotNull()
        assertThat(registeredToolchainsValue.rejectedToolchains())
            .containsCell(
                testToolchainTypeLabel,
                Label.parseCanonicalUnchecked("//extra:extra_toolchain"),
                "mismatching target_settings: optimized"
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRegisteredToolchains_targetSetting_error() {
        // Add an extra toolchain with a target_setting
        scratch.file(
            "extra/BUILD",
            """
        load("//toolchain:toolchain_def.bzl", "test_toolchain")

        config_setting(
            name = "flagged",
            flag_values = {":flag": "default"},
            transitive_configs = [":flag"],
        )

        config_feature_flag(
            name = "flag",
            allowed_values = [
                "default",
                "left",
                "right",
            ],
            default_value = "default",
        )

        toolchain(
            name = "extra_toolchain",
            exec_compatible_with = ["//constraints:linux"],
            target_compatible_with = ["//constraints:linux"],
            target_settings = [
                ":flagged",
            ],
            toolchain = ":extra_toolchain_impl",
            toolchain_type = "//toolchain:test_toolchain",
        )

        test_toolchain(
            name = "extra_toolchain_impl",
            data = "extra",
        )
        
        """.trimIndent()
        )

        rewriteModuleDotBazel(
            """
        register_toolchains("//toolchain:toolchain_1", "//extra:extra_toolchain")
        
        """.trimIndent()
        )

        // Need this so the feature flag is actually gone from the configuration.
        useConfiguration("--enforce_transitive_configs_for_config_feature_flag")
        val toolchainsKey: SkyKey? = RegisteredToolchainsValue.key(targetConfigKey,  /* debug= */false)
        val result: EvaluationResult<RegisteredToolchainsValue?>? =
            requestToolchainsFromSkyframe(toolchainsKey)
        EvaluationResultSubjectFactory.assertThatEvaluationResult(result)
            .hasErrorEntryForKeyThat(toolchainsKey)
            .hasExceptionThat()
            .hasMessageThat()
            .contains(
                ("Unrecoverable errors resolving config_setting associated with"
                        + " //extra:extra_toolchain: For config_setting flagged: Feature flag"
                        + " //extra:flag was accessed in a configuration it is not present in.")
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRegisteredToolchainsValue_equalsAndHashCode() {
        val toolchain1: DeclaredToolchainInfo? =
            DeclaredToolchainInfo.builder()
                .toolchainType(
                    ToolchainTypeInfo.create(Label.parseCanonicalUnchecked("//test:toolchain"))
                )
                .addExecConstraints(com.google.common.collect.ImmutableList.of<E?>())
                .addTargetConstraints(com.google.common.collect.ImmutableList.of<E?>())
                .resolvedToolchainLabel(Label.parseCanonicalUnchecked("//test/toolchain_impl_1"))
                .targetLabel(Label.parseCanonicalUnchecked("//test/toolchain_1"))
                .build()
        val toolchain2: DeclaredToolchainInfo? =
            DeclaredToolchainInfo.builder()
                .toolchainType(
                    ToolchainTypeInfo.create(Label.parseCanonicalUnchecked("//test:toolchain"))
                )
                .addExecConstraints(com.google.common.collect.ImmutableList.of<E?>())
                .addTargetConstraints(com.google.common.collect.ImmutableList.of<E?>())
                .resolvedToolchainLabel(Label.parseCanonicalUnchecked("//test/toolchain_impl_2"))
                .targetLabel(Label.parseCanonicalUnchecked("//test/toolchain_2"))
                .build()

        EqualsTester()
            .addEqualityGroup(
                RegisteredToolchainsValue.create(
                    com.google.common.collect.ImmutableList.of<E?>(toolchain1, toolchain2),  /* rejectedToolchains= */
                    null
                ),
                RegisteredToolchainsValue.create(
                    com.google.common.collect.ImmutableList.of<E?>(toolchain1, toolchain2),  /* rejectedToolchains= */
                    null
                )
            )
            .addEqualityGroup(
                RegisteredToolchainsValue.create(
                    com.google.common.collect.ImmutableList.of<E?>(toolchain1),  /* rejectedToolchains= */null
                )
            )
            .addEqualityGroup(
                RegisteredToolchainsValue.create(
                    com.google.common.collect.ImmutableList.of<E?>(toolchain2),  /* rejectedToolchains= */null
                )
            )
            .addEqualityGroup(
                RegisteredToolchainsValue.create(
                    com.google.common.collect.ImmutableList.of<E?>(toolchain2, toolchain1),  /* rejectedToolchains= */
                    null
                )
            )
            .testEquals()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testRegisteredToolchains_wildcard_nonRuleTargets() {
        // Add a toolchain and a non-rule target in the root package.
        scratch.file(
            "BUILD",
            """
        load('//toolchain:toolchain_def.bzl', 'test_toolchain')

        exports_files(['some_file'])

        toolchain(
            name = 'root_toolchain',
            exec_compatible_with = ['//constraints:linux'],
            target_compatible_with = ['//constraints:linux'],
            toolchain = ':root_toolchain_impl',
            toolchain_type = '//toolchain:test_toolchain',
        )

        test_toolchain(
            name = 'root_toolchain_impl',
            data = 'root',
        )
        
        """.trimIndent()
        )

        useConfiguration("--extra_toolchains=//:*")

        val toolchainsKey: SkyKey? = RegisteredToolchainsValue.key(targetConfigKey,  /* debug= */false)
        val result: EvaluationResult<RegisteredToolchainsValue?> =
            requestToolchainsFromSkyframe(toolchainsKey)
        EvaluationResultSubjectFactory.assertThatEvaluationResult(result).hasNoError()

        // Verify that the toolchain was registered and the filegroup was filtered out.
        assertToolchainLabels(result.get(toolchainsKey))
            .contains(Label.parseCanonicalUnchecked("//:root_toolchain_impl"))
    }
}
