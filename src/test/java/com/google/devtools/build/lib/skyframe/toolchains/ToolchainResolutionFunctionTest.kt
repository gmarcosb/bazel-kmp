// Copyright 2019 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.analysis.PlatformOptions

/** Tests for [UnloadedToolchainContext] and [ToolchainResolutionFunction].  */
@RunWith(JUnit4::class)
class ToolchainResolutionFunctionTest : ToolchainTestCase() {
    @Throws(java.lang.InterruptedException::class)
    private fun invokeToolchainResolution(key: SkyKey?): EvaluationResult<UnloadedToolchainContext?> {
        try {
            getSkyframeExecutor().getSkyframeBuildView().enableAnalysis(true)
            return SkyframeExecutorTestUtils.evaluate<T?>(
                getSkyframeExecutor(), key,  /*keepGoing=*/false, reporter
            )
        } finally {
            getSkyframeExecutor().getSkyframeBuildView().enableAnalysis(false)
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun resolve() {
        // This should select platform mac, toolchain extra_toolchain_mac, because platform
        // mac is listed first.
        addToolchain(
            "extra",
            "extra_toolchain_linux",
            com.google.common.collect.ImmutableList.of<E?>("//constraints:linux"),
            com.google.common.collect.ImmutableList.of<E?>("//constraints:linux"),
            "baz"
        )
        addToolchain(
            "extra",
            "extra_toolchain_mac",
            com.google.common.collect.ImmutableList.of<E?>("//constraints:mac"),
            com.google.common.collect.ImmutableList.of<E?>("//constraints:linux"),
            "baz"
        )
        rewriteModuleDotBazel(
            """
        register_toolchains("//extra:extra_toolchain_linux", "//extra:extra_toolchain_mac")
        register_execution_platforms("//platforms:mac", "//platforms:linux")
        
        """.trimIndent()
        )

        useConfiguration("--platforms=//platforms:linux")
        val key: ToolchainContextKey? =
            ToolchainContextKey.key()
                .configurationKey(targetConfigKey)
                .toolchainTypes(testToolchainType)
                .build()

        val result: EvaluationResult<UnloadedToolchainContext?> = invokeToolchainResolution(key)

        EvaluationResultSubjectFactory.assertThatEvaluationResult(result).hasNoError()
        val unloadedToolchainContext: UnloadedToolchainContext? = result.get(key)
        assertThat(unloadedToolchainContext).isNotNull()

        assertThat(unloadedToolchainContext).hasToolchainType(testToolchainTypeLabel)
        assertThat(unloadedToolchainContext).hasResolvedToolchain("//extra:extra_toolchain_mac_impl")
        assertThat(unloadedToolchainContext).hasExecutionPlatform("//platforms:mac")
        assertThat(unloadedToolchainContext).hasTargetPlatform("//platforms:linux")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun resolve_hostPlatform() {
        addToolchain(
            "extra",
            "extra_toolchain_linux",
            com.google.common.collect.ImmutableList.of<E?>("//constraints:linux"),
            com.google.common.collect.ImmutableList.of<E?>("//constraints:linux"),
            "baz"
        )
        addToolchain(
            "extra",
            "extra_toolchain_mac",
            com.google.common.collect.ImmutableList.of<E?>("//constraints:mac"),
            com.google.common.collect.ImmutableList.of<E?>("//constraints:linux"),
            "baz"
        )
        rewriteModuleDotBazel(
            """
        register_toolchains("//extra:extra_toolchain_linux", "//extra:extra_toolchain_mac")
        
        """.trimIndent()
        )

        useConfiguration("--platforms=//platforms:linux", "--host_platform=//platforms:linux")
        val key: ToolchainContextKey? =
            ToolchainContextKey.key()
                .configurationKey(targetConfigKey)
                .toolchainTypes(testToolchainType)
                .build()

        val result: EvaluationResult<UnloadedToolchainContext?> = invokeToolchainResolution(key)

        EvaluationResultSubjectFactory.assertThatEvaluationResult(result).hasNoError()
        val unloadedToolchainContext: UnloadedToolchainContext? = result.get(key)
        assertThat(unloadedToolchainContext).isNotNull()

        assertThat(unloadedToolchainContext).hasToolchainType(testToolchainTypeLabel)
        assertThat(unloadedToolchainContext).hasResolvedToolchain("//extra:extra_toolchain_linux_impl")
        assertThat(unloadedToolchainContext).hasExecutionPlatform("//platforms:linux")
        assertThat(unloadedToolchainContext).hasTargetPlatform("//platforms:linux")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun resolve_hostPlatform_alias() {
        addToolchain(
            "extra",
            "extra_toolchain_linux",
            com.google.common.collect.ImmutableList.of<E?>("//constraints:linux"),
            com.google.common.collect.ImmutableList.of<E?>("//constraints:linux"),
            "baz"
        )
        addToolchain(
            "extra",
            "extra_toolchain_mac",
            com.google.common.collect.ImmutableList.of<E?>("//constraints:mac"),
            com.google.common.collect.ImmutableList.of<E?>("//constraints:linux"),
            "baz"
        )
        // Set up aliases for the platforms.
        scratch.file(
            "alias/BUILD",
            """
        alias(name = 'mac', actual = '//platforms:mac')
        alias(name = 'linux', actual = '//platforms:linux')
        
        """.trimIndent()
        )
        rewriteModuleDotBazel(
            """
        register_toolchains("//extra:extra_toolchain_linux", "//extra:extra_toolchain_mac")
        
        """.trimIndent()
        )

        useConfiguration("--platforms=//platforms:linux", "--host_platform=//alias:linux")
        val key: ToolchainContextKey? =
            ToolchainContextKey.key()
                .configurationKey(targetConfigKey)
                .toolchainTypes(testToolchainType)
                .build()

        val result: EvaluationResult<UnloadedToolchainContext?> = invokeToolchainResolution(key)

        EvaluationResultSubjectFactory.assertThatEvaluationResult(result).hasNoError()
        val unloadedToolchainContext: UnloadedToolchainContext? = result.get(key)
        assertThat(unloadedToolchainContext).isNotNull()

        assertThat(unloadedToolchainContext).hasToolchainType(testToolchainTypeLabel)
        assertThat(unloadedToolchainContext).hasResolvedToolchain("//extra:extra_toolchain_linux_impl")
        assertThat(unloadedToolchainContext).hasExecutionPlatform("//platforms:linux")
        assertThat(unloadedToolchainContext).hasTargetPlatform("//platforms:linux")
    }

    // TODO(katre): Add further tests for optional/mandatory/mixed toolchains.
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun resolve_optional() {
        // This should select platform mac, toolchain extra_toolchain_mac, because platform
        // mac is listed first.
        addOptionalToolchain(
            "extra",
            "extra_toolchain_linux",
            com.google.common.collect.ImmutableList.of<E?>("//constraints:linux"),
            com.google.common.collect.ImmutableList.of<E?>("//constraints:linux"),
            "baz"
        )
        addOptionalToolchain(
            "extra",
            "extra_toolchain_mac",
            com.google.common.collect.ImmutableList.of<E?>("//constraints:mac"),
            com.google.common.collect.ImmutableList.of<E?>("//constraints:linux"),
            "baz"
        )
        rewriteModuleDotBazel(
            """
        register_toolchains("//extra:extra_toolchain_linux", "//extra:extra_toolchain_mac")
        register_execution_platforms("//platforms:mac", "//platforms:linux")
        
        """.trimIndent()
        )

        useConfiguration("--platforms=//platforms:linux")
        val key: ToolchainContextKey? =
            ToolchainContextKey.key()
                .configurationKey(targetConfigKey)
                .toolchainTypes(optionalToolchainType)
                .build()

        val result: EvaluationResult<UnloadedToolchainContext?> = invokeToolchainResolution(key)

        EvaluationResultSubjectFactory.assertThatEvaluationResult(result).hasNoError()
        val unloadedToolchainContext: UnloadedToolchainContext? = result.get(key)
        assertThat(unloadedToolchainContext).isNotNull()

        assertThat(unloadedToolchainContext).hasToolchainType(optionalToolchainTypeLabel)
        assertThat(unloadedToolchainContext).hasResolvedToolchain("//extra:extra_toolchain_mac_impl")
        assertThat(unloadedToolchainContext).hasExecutionPlatform("//platforms:mac")
        assertThat(unloadedToolchainContext).hasTargetPlatform("//platforms:linux")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun resolve_optional_on_first_platform() {
        // This should select platform mac, toolchain extra_toolchain_mac, independent of platform order
        addOptionalToolchain(
            "extra",
            "extra_toolchain_mac",
            com.google.common.collect.ImmutableList.of<E?>("//constraints:mac"),
            com.google.common.collect.ImmutableList.of<E?>("//constraints:linux"),
            "baz"
        )
        rewriteModuleDotBazel(
            """
        register_toolchains("//extra:extra_toolchain_mac")
        register_execution_platforms("//platforms:mac", "//platforms:linux")
        
        """.trimIndent()
        )

        useConfiguration("--platforms=//platforms:linux")
        val key: ToolchainContextKey? =
            ToolchainContextKey.key()
                .configurationKey(targetConfigKey)
                .toolchainTypes(optionalToolchainType)
                .build()

        val result: EvaluationResult<UnloadedToolchainContext?> = invokeToolchainResolution(key)

        EvaluationResultSubjectFactory.assertThatEvaluationResult(result).hasNoError()
        val unloadedToolchainContext: UnloadedToolchainContext? = result.get(key)
        assertThat(unloadedToolchainContext).isNotNull()

        assertThat(unloadedToolchainContext).hasToolchainType(optionalToolchainTypeLabel)
        assertThat(unloadedToolchainContext).hasResolvedToolchain("//extra:extra_toolchain_mac_impl")
        assertThat(unloadedToolchainContext).hasExecutionPlatform("//platforms:mac")
        assertThat(unloadedToolchainContext).hasTargetPlatform("//platforms:linux")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun resolve_optional_on_second_platform() {
        // This should select platform mac, toolchain extra_toolchain_mac, independent of platform order
        addOptionalToolchain(
            "extra",
            "extra_toolchain_mac",
            com.google.common.collect.ImmutableList.of<E?>("//constraints:mac"),
            com.google.common.collect.ImmutableList.of<E?>("//constraints:linux"),
            "baz"
        )
        rewriteModuleDotBazel(
            """
        register_toolchains("//extra:extra_toolchain_mac")
        register_execution_platforms("//platforms:linux", "//platforms:mac")
        
        """.trimIndent()
        )

        useConfiguration("--platforms=//platforms:linux")
        val key: ToolchainContextKey? =
            ToolchainContextKey.key()
                .configurationKey(targetConfigKey)
                .toolchainTypes(optionalToolchainType)
                .build()

        val result: EvaluationResult<UnloadedToolchainContext?> = invokeToolchainResolution(key)

        EvaluationResultSubjectFactory.assertThatEvaluationResult(result).hasNoError()
        val unloadedToolchainContext: UnloadedToolchainContext? = result.get(key)
        assertThat(unloadedToolchainContext).isNotNull()

        assertThat(unloadedToolchainContext).hasToolchainType(optionalToolchainTypeLabel)
        assertThat(unloadedToolchainContext).hasResolvedToolchain("//extra:extra_toolchain_mac_impl")
        assertThat(unloadedToolchainContext).hasExecutionPlatform("//platforms:mac")
        assertThat(unloadedToolchainContext).hasTargetPlatform("//platforms:linux")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun resolve_max_optional_on_second_platform() {
        // This should select platform mac, toolchain extra_toolchain_mac, independent of platform order
        // and independent of non-existence of the second optional toolchain
        addOptionalToolchain(
            "extra",
            "extra_toolchain_mac",
            com.google.common.collect.ImmutableList.of<E?>("//constraints:mac"),
            com.google.common.collect.ImmutableList.of<E?>("//constraints:linux"),
            "baz"
        )
        scratch.appendFile("toolchain/BUILD", "toolchain_type(name = 'extra_optional_toolchain')")
        val extraOptionalToolchainTypeLabel: Label? =
            Label.parseCanonicalUnchecked("//toolchain:extra_optional_toolchain")
        val extraOptionalToolchainType: ToolchainTypeRequirement? =
            ToolchainTypeRequirement.builder(extraOptionalToolchainTypeLabel).mandatory(false).build()
        rewriteModuleDotBazel(
            """
        register_toolchains("//extra:extra_toolchain_mac")
        register_execution_platforms('//platforms:linux', '//platforms:mac')
        
        """.trimIndent()
        )

        useConfiguration("--platforms=//platforms:linux")
        val key: ToolchainContextKey? =
            ToolchainContextKey.key()
                .configurationKey(targetConfigKey)
                .toolchainTypes(optionalToolchainType, extraOptionalToolchainType)
                .build()

        val result: EvaluationResult<UnloadedToolchainContext?> = invokeToolchainResolution(key)

        EvaluationResultSubjectFactory.assertThatEvaluationResult(result).hasNoError()
        val unloadedToolchainContext: UnloadedToolchainContext? = result.get(key)
        assertThat(unloadedToolchainContext).isNotNull()

        assertThat(unloadedToolchainContext).hasToolchainType(optionalToolchainTypeLabel)
        assertThat(unloadedToolchainContext).hasResolvedToolchain("//extra:extra_toolchain_mac_impl")
        assertThat(unloadedToolchainContext).hasExecutionPlatform("//platforms:mac")
        assertThat(unloadedToolchainContext).hasTargetPlatform("//platforms:linux")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun resolve_multiple() {
        val secondToolchainTypeLabel: Label? = Label.parseCanonicalUnchecked("//second:toolchain_type")
        val secondToolchainTypeRequirement: ToolchainTypeRequirement? =
            ToolchainTypeRequirement.create(secondToolchainTypeLabel)
        scratch.file("second/BUILD", "toolchain_type(name = 'toolchain_type')")

        addToolchain(
            "main",
            "main_toolchain_linux",
            com.google.common.collect.ImmutableList.of<E?>("//constraints:linux"),
            com.google.common.collect.ImmutableList.of<E?>("//constraints:linux"),
            "baz"
        )
        addToolchain(
            "main",
            "second_toolchain_linux",
            secondToolchainTypeLabel,
            com.google.common.collect.ImmutableList.of<E?>("//constraints:linux"),
            com.google.common.collect.ImmutableList.of<E?>("//constraints:linux"),
            "baz"
        )
        rewriteModuleDotBazel(
            """
        register_toolchains("//main:all")
        register_execution_platforms("//platforms:linux")
        
        """.trimIndent()
        )

        useConfiguration("--platforms=//platforms:linux")
        val key: ToolchainContextKey? =
            ToolchainContextKey.key()
                .configurationKey(targetConfigKey)
                .toolchainTypes(testToolchainType, secondToolchainTypeRequirement)
                .build()

        val result: EvaluationResult<UnloadedToolchainContext?> = invokeToolchainResolution(key)

        EvaluationResultSubjectFactory.assertThatEvaluationResult(result).hasNoError()
        val unloadedToolchainContext: UnloadedToolchainContext? = result.get(key)
        assertThat(unloadedToolchainContext).isNotNull()

        assertThat(unloadedToolchainContext).hasToolchainType(testToolchainTypeLabel)
        assertThat(unloadedToolchainContext).hasResolvedToolchain("//main:main_toolchain_linux_impl")
        assertThat(unloadedToolchainContext).hasToolchainType(secondToolchainTypeLabel)
        assertThat(unloadedToolchainContext).hasResolvedToolchain("//main:second_toolchain_linux_impl")
        assertThat(unloadedToolchainContext).hasExecutionPlatform("//platforms:linux")
        assertThat(unloadedToolchainContext).hasTargetPlatform("//platforms:linux")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun resolve_mandatory_missing() {
        // There is no toolchain for the requested type.
        useConfiguration("--platforms=//platforms:linux")
        val key: ToolchainContextKey? =
            ToolchainContextKey.key()
                .configurationKey(targetConfigKey)
                .toolchainTypes(testToolchainType)
                .build()

        val result: EvaluationResult<UnloadedToolchainContext?> = invokeToolchainResolution(key)

        EvaluationResultSubjectFactory.assertThatEvaluationResult(result)
            .hasErrorEntryForKeyThat(key)
            .hasExceptionThat()
            .hasMessageThat()
            .isEqualTo(
                """
No matching toolchains found for types:
  //toolchain:test_toolchain
To debug, rerun with --toolchain_resolution_debug='//toolchain:test_toolchain'
For more information on platforms or toolchains see https://bazel.build/concepts/platforms-intro.
""".trimIndent()
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun unresolved_toolchain_message_regex_quotes() {
        val platformInfo: PlatformInfo? =
            PlatformInfo.builder()
                .setLabel(Label.parseCanonicalUnchecked("//platforms:test_platform"))
                .build()
        val toolchainTypeInfo: ToolchainTypeInfo? =
            ToolchainTypeInfo.create(
                Label.parseCanonicalUnchecked("@@repo+//toolchain:test_toolchain")
            )
        val missingToolchainTypes: LinkedHashSet<ToolchainTypeInfo?> = LinkedHashSet<ToolchainTypeInfo?>()
        missingToolchainTypes.add(toolchainTypeInfo)

        val exception: ToolchainResolutionFunction.UnresolvedToolchainsException =
            UnresolvedToolchainsException(
                platformInfo, missingToolchainTypes
            )

        assertThat(exception)
            .hasMessageThat()
            .isEqualTo(
                """
No matching toolchains found for types:
  @@repo+//toolchain:test_toolchain
To debug, rerun with --toolchain_resolution_debug='\Q@@repo+//toolchain:test_toolchain\E'

""".trimIndent()
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun resolve_mandatory_missing_customPlatformMessage() {
        scratch.appendFile(
            "platforms/BUILD",
            """
        platform(
            name = "linux_custom_message",
            parents = [":linux"],
            missing_toolchain_error = "Check custom docs for setup instructions",
        )
        
        """.trimIndent()
        )

        // There is no toolchain for the requested type.
        useConfiguration("--platforms=//platforms:linux_custom_message")
        val key: ToolchainContextKey? =
            ToolchainContextKey.key()
                .configurationKey(targetConfigKey)
                .toolchainTypes(testToolchainType)
                .build()

        val result: EvaluationResult<UnloadedToolchainContext?> = invokeToolchainResolution(key)

        EvaluationResultSubjectFactory.assertThatEvaluationResult(result)
            .hasErrorEntryForKeyThat(key)
            .hasExceptionThat()
            .hasMessageThat()
            .contains("Check custom docs for setup instructions")
        EvaluationResultSubjectFactory.assertThatEvaluationResult(result)
            .hasErrorEntryForKeyThat(key)
            .hasExceptionThat()
            .hasMessageThat()
            .doesNotContain("see https://bazel.build/concepts/platforms-intro")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun resolve_multiple_optional() {
        val secondToolchainTypeLabel: Label? = Label.parseCanonicalUnchecked("//second:toolchain_type")
        val secondToolchainTypeRequirement: ToolchainTypeRequirement? =
            ToolchainTypeRequirement.builder(secondToolchainTypeLabel).mandatory(false).build()
        scratch.file("second/BUILD", "toolchain_type(name = 'toolchain_type')")

        addToolchain(
            "main",
            "main_toolchain_linux",
            com.google.common.collect.ImmutableList.of<E?>("//constraints:linux"),
            com.google.common.collect.ImmutableList.of<E?>("//constraints:linux"),
            "baz"
        )
        addToolchain(
            "main",
            "second_toolchain_linux",
            secondToolchainTypeLabel,
            com.google.common.collect.ImmutableList.of<E?>("//constraints:linux"),
            com.google.common.collect.ImmutableList.of<E?>("//constraints:linux"),
            "baz"
        )
        rewriteModuleDotBazel(
            """
        register_toolchains("//main:all")
        register_execution_platforms("//platforms:linux")
        
        """.trimIndent()
        )

        useConfiguration("--platforms=//platforms:linux")
        val key: ToolchainContextKey? =
            ToolchainContextKey.key()
                .configurationKey(targetConfigKey)
                .toolchainTypes(testToolchainType, secondToolchainTypeRequirement)
                .build()

        val result: EvaluationResult<UnloadedToolchainContext?> = invokeToolchainResolution(key)

        EvaluationResultSubjectFactory.assertThatEvaluationResult(result).hasNoError()
        val unloadedToolchainContext: UnloadedToolchainContext? = result.get(key)
        assertThat(unloadedToolchainContext).isNotNull()

        assertThat(unloadedToolchainContext).hasToolchainType(testToolchainTypeLabel)
        assertThat(unloadedToolchainContext).hasResolvedToolchain("//main:main_toolchain_linux_impl")
        assertThat(unloadedToolchainContext).hasToolchainType(secondToolchainTypeLabel)
        assertThat(unloadedToolchainContext).hasResolvedToolchain("//main:second_toolchain_linux_impl")
        assertThat(unloadedToolchainContext).hasExecutionPlatform("//platforms:linux")
        assertThat(unloadedToolchainContext).hasTargetPlatform("//platforms:linux")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun resolve_multiple_optional_missing() {
        val secondToolchainTypeLabel: Label? = Label.parseCanonicalUnchecked("//second:toolchain_type")
        val secondToolchainTypeRequirement: ToolchainTypeRequirement? =
            ToolchainTypeRequirement.builder(secondToolchainTypeLabel).mandatory(false).build()
        scratch.file("second/BUILD", "toolchain_type(name = 'toolchain_type')")

        addToolchain(
            "main",
            "main_toolchain_linux",
            com.google.common.collect.ImmutableList.of<E?>("//constraints:linux"),
            com.google.common.collect.ImmutableList.of<E?>("//constraints:linux"),
            "baz"
        )
        rewriteModuleDotBazel(
            """
        register_toolchains("//main:all")
        register_execution_platforms("//platforms:linux")
        
        """.trimIndent()
        )

        useConfiguration("--platforms=//platforms:linux")
        val key: ToolchainContextKey? =
            ToolchainContextKey.key()
                .configurationKey(targetConfigKey)
                .toolchainTypes(testToolchainType, secondToolchainTypeRequirement)
                .build()

        val result: EvaluationResult<UnloadedToolchainContext?> = invokeToolchainResolution(key)

        EvaluationResultSubjectFactory.assertThatEvaluationResult(result).hasNoError()
        val unloadedToolchainContext: UnloadedToolchainContext? = result.get(key)
        assertThat(unloadedToolchainContext).isNotNull()

        assertThat(unloadedToolchainContext).hasToolchainType(testToolchainTypeLabel)
        assertThat(unloadedToolchainContext).hasResolvedToolchain("//main:main_toolchain_linux_impl")
        assertThat(unloadedToolchainContext).hasToolchainType(secondToolchainTypeLabel)
        assertThat(unloadedToolchainContext)
            .resolvedToolchainLabels()
            .doesNotContain(Label.parseCanonicalUnchecked("//main:second_toolchain_linux_impl"))
        assertThat(unloadedToolchainContext).hasExecutionPlatform("//platforms:linux")
        assertThat(unloadedToolchainContext).hasTargetPlatform("//platforms:linux")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun resolve_toolchainTypeAlias() {
        addToolchain(
            "extra",
            "extra_toolchain_linux",
            com.google.common.collect.ImmutableList.of<E?>("//constraints:linux"),
            com.google.common.collect.ImmutableList.of<E?>("//constraints:linux"),
            "baz"
        )
        rewriteModuleDotBazel(
            """
        register_toolchains("//extra:extra_toolchain_linux")
        register_execution_platforms("//platforms:linux")
        
        """.trimIndent()
        )

        // Set up an alias for the toolchain type.
        val aliasedToolchainTypeLabel: Label? = Label.parseCanonicalUnchecked("//alias:toolchain_type")
        scratch.file(
            "alias/BUILD", "alias(name = 'toolchain_type', actual = '//toolchain:test_toolchain')"
        )

        useConfiguration("--platforms=//platforms:linux")
        val key: ToolchainContextKey? =
            ToolchainContextKey.key()
                .configurationKey(targetConfigKey)
                .toolchainTypes(ToolchainTypeRequirement.create(aliasedToolchainTypeLabel))
                .build()

        val result: EvaluationResult<UnloadedToolchainContext?> = invokeToolchainResolution(key)

        EvaluationResultSubjectFactory.assertThatEvaluationResult(result).hasNoError()
        val unloadedToolchainContext: UnloadedToolchainContext? = result.get(key)
        assertThat(unloadedToolchainContext).isNotNull()

        assertThat(unloadedToolchainContext).hasToolchainType(testToolchainTypeLabel)
        assertThat(unloadedToolchainContext).hasResolvedToolchain("//extra:extra_toolchain_linux_impl")
        assertThat(unloadedToolchainContext).hasExecutionPlatform("//platforms:linux")
        assertThat(unloadedToolchainContext).hasTargetPlatform("//platforms:linux")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun resolve_noToolchainType() {
        scratch.file("host/BUILD", "platform(name = 'host')")
        rewriteModuleDotBazel(
            """
        register_execution_platforms("//platforms:mac", "//platforms:linux")
        
        """.trimIndent()
        )

        useConfiguration("--host_platform=//host:host", "--platforms=//platforms:linux")
        val key: ToolchainContextKey? = ToolchainContextKey.key().configurationKey(targetConfigKey).build()

        val result: EvaluationResult<UnloadedToolchainContext?> = invokeToolchainResolution(key)

        EvaluationResultSubjectFactory.assertThatEvaluationResult(result).hasNoError()
        val unloadedToolchainContext: UnloadedToolchainContext = result.get(key)
        assertThat(unloadedToolchainContext).isNotNull()

        assertThat(unloadedToolchainContext.toolchainTypes()).isEmpty()
        // Even with no toolchains requested, should still select the first execution platform.
        assertThat(unloadedToolchainContext).hasExecutionPlatform("//platforms:mac")
        assertThat(unloadedToolchainContext).hasTargetPlatform("//platforms:linux")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun resolve_noToolchainType_hostNotAvailable() {
        scratch.file("host/BUILD", "platform(name = 'host')")
        scratch.file(
            "sample/BUILD",
            """
        constraint_setting(name = "demo")

        constraint_value(
            name = "demo_a",
            constraint_setting = ":demo",
        )

        constraint_value(
            name = "demo_b",
            constraint_setting = ":demo",
        )

        platform(
            name = "sample_a",
            constraint_values = [":demo_a"],
        )

        platform(
            name = "sample_b",
            constraint_values = [":demo_b"],
        )
        
        """.trimIndent()
        )
        rewriteModuleDotBazel(
            """
        register_execution_platforms(
            "//platforms:mac",
            "//platforms:linux",
            "//sample:sample_a",
            "//sample:sample_b",
        )
        
        """.trimIndent()
        )

        useConfiguration("--host_platform=//host:host", "--platforms=//platforms:linux")
        val key: ToolchainContextKey? =
            ToolchainContextKey.key()
                .configurationKey(targetConfigKey)
                .execConstraintLabels(Label.parseCanonicalUnchecked("//sample:demo_b"))
                .build()

        val result: EvaluationResult<UnloadedToolchainContext?> = invokeToolchainResolution(key)

        EvaluationResultSubjectFactory.assertThatEvaluationResult(result).hasNoError()
        val unloadedToolchainContext: UnloadedToolchainContext = result.get(key)
        assertThat(unloadedToolchainContext).isNotNull()

        assertThat(unloadedToolchainContext.toolchainTypes()).isEmpty()
        assertThat(unloadedToolchainContext).hasExecutionPlatform("//sample:sample_b")
        assertThat(unloadedToolchainContext).hasTargetPlatform("//platforms:linux")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun resolve_unavailableToolchainType_single() {
        reporter.removeHandler(failFastHandler)
        scratch.file("fake/toolchain/BUILD", "")
        useConfiguration("--host_platform=//platforms:linux", "--platforms=//platforms:mac")
        val key: ToolchainContextKey? =
            ToolchainContextKey.key()
                .configurationKey(targetConfigKey)
                .toolchainTypes(
                    testToolchainType,
                    ToolchainTypeRequirement.create(
                        Label.parseCanonicalUnchecked("//fake/toolchain:type_1")
                    )
                )
                .build()

        val result: EvaluationResult<UnloadedToolchainContext?> = invokeToolchainResolution(key)

        EvaluationResultSubjectFactory.assertThatEvaluationResult(result)
            .hasErrorEntryForKeyThat(key)
            .hasExceptionThat()
            .isInstanceOf(InvalidToolchainTypeException::class.java)
        assertContainsEvent("no such target '//fake/toolchain:type_1'")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun resolve_optional_unavailableToolchainType_single() {
        reporter.removeHandler(failFastHandler)
        scratch.file("fake/toolchain/BUILD", "")
        useConfiguration("--host_platform=//platforms:linux", "--platforms=//platforms:linux")
        val key: ToolchainContextKey? =
            ToolchainContextKey.key()
                .configurationKey(targetConfigKey)
                .toolchainTypes(optionalToolchainType)
                .build()

        val result: EvaluationResult<UnloadedToolchainContext?> = invokeToolchainResolution(key)

        EvaluationResultSubjectFactory.assertThatEvaluationResult(result).hasNoError()
        val unloadedToolchainContext: UnloadedToolchainContext? = result.get(key)
        assertThat(unloadedToolchainContext).isNotNull()

        assertThat(unloadedToolchainContext).hasToolchainType(optionalToolchainTypeLabel)
        assertThat(unloadedToolchainContext).resolvedToolchainLabels().isEmpty()
        assertThat(unloadedToolchainContext).hasExecutionPlatform("//platforms:linux")
        assertThat(unloadedToolchainContext).hasTargetPlatform("//platforms:linux")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun resolve_unavailableToolchainType_multiple() {
        reporter.removeHandler(failFastHandler)
        scratch.file("fake/toolchain/BUILD", "")
        useConfiguration("--host_platform=//platforms:linux", "--platforms=//platforms:mac")
        val key: ToolchainContextKey? =
            ToolchainContextKey.key()
                .configurationKey(targetConfigKey)
                .toolchainTypes(
                    testToolchainType,
                    ToolchainTypeRequirement.create(
                        Label.parseCanonicalUnchecked("//fake/toolchain:type_1")
                    ),
                    ToolchainTypeRequirement.create(
                        Label.parseCanonicalUnchecked("//fake/toolchain:type_2")
                    )
                )
                .build()

        val result: EvaluationResult<UnloadedToolchainContext?> = invokeToolchainResolution(key)

        EvaluationResultSubjectFactory.assertThatEvaluationResult(result)
            .hasErrorEntryForKeyThat(key)
            .hasExceptionThat()
            .isInstanceOf(InvalidToolchainTypeException::class.java)
        // Only one of the missing types will be reported, so do not check the specific error message.
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun resolve_invalidToolchainType() {
        reporter.removeHandler(failFastHandler)
        scratch.file("fake/toolchain/BUILD", "filegroup(name = 'not_a_toolchain')")
        useConfiguration("--host_platform=//platforms:linux", "--platforms=//platforms:mac")
        val key: ToolchainContextKey? =
            ToolchainContextKey.key()
                .configurationKey(targetConfigKey)
                .toolchainTypes(
                    ToolchainTypeRequirement.create(
                        Label.parseCanonicalUnchecked("//fake/toolchain:not_a_toolchain")
                    )
                )
                .build()

        val result: EvaluationResult<UnloadedToolchainContext?> = invokeToolchainResolution(key)

        EvaluationResultSubjectFactory.assertThatEvaluationResult(result)
            .hasErrorEntryForKeyThat(key)
            .hasExceptionThat()
            .isInstanceOf(InvalidToolchainTypeException::class.java)
        EvaluationResultSubjectFactory.assertThatEvaluationResult(result)
            .hasErrorEntryForKeyThat(key)
            .hasExceptionThat()
            .hasMessageThat()
            .contains("but does not provide ToolchainTypeInfo")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun resolve_invalidToolchainType_ignored() {
        reporter.removeHandler(failFastHandler)
        scratch.file("fake/toolchain/BUILD", "filegroup(name = 'not_a_toolchain')")
        useConfiguration("--host_platform=//platforms:linux", "--platforms=//platforms:mac")
        val key: ToolchainContextKey? =
            ToolchainContextKey.key()
                .configurationKey(targetConfigKey)
                .toolchainTypes(
                    ToolchainTypeRequirement.builder(
                        Label.parseCanonicalUnchecked("//fake/toolchain:not_a_toolchain")
                    )
                        .ignoreIfInvalid(true)
                        .build()
                )
                .build()

        val result: EvaluationResult<UnloadedToolchainContext?> = invokeToolchainResolution(key)

        EvaluationResultSubjectFactory.assertThatEvaluationResult(result).hasNoError()
        val unloadedToolchainContext: UnloadedToolchainContext? = result.get(key)
        assertThat(unloadedToolchainContext).isNotNull()
        assertThat(unloadedToolchainContext)
            .doesntHaveToolchainType("//fake/toolchain:not_a_toolchain")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun resolve_invalidTargetPlatform_badTarget() {
        scratch.file("invalid/BUILD", "filegroup(name = 'not_a_platform')")

        // Manually create a configuration key: trying to call `useConfiguration` will immediately throw
        // the exception this is checking for.
        val newOptions: BuildOptions = targetConfigKey.getOptions().clone()
        newOptions
            .get(PlatformOptions::class.java)
            .setPlatforms(com.google.common.collect.ImmutableList.of<E?>(Label.parseCanonicalUnchecked("//invalid:not_a_platform")))
        val configKey: BuildConfigurationKey? = BuildConfigurationKey.create(newOptions)

        // Create the toolchain context key and evaluate it.
        val key: ToolchainContextKey? =
            ToolchainContextKey.key()
                .configurationKey(configKey)
                .toolchainTypes(testToolchainType)
                .build()

        reporter.removeHandler(failFastHandler) // expect errors
        val result: EvaluationResult<UnloadedToolchainContext?> = invokeToolchainResolution(key)

        EvaluationResultSubjectFactory.assertThatEvaluationResult(result).hasError()
        EvaluationResultSubjectFactory.assertThatEvaluationResult(result)
            .hasErrorEntryForKeyThat(key)
            .hasExceptionThat()
            .isInstanceOf(InvalidPlatformException::class.java)
        EvaluationResultSubjectFactory.assertThatEvaluationResult(result)
            .hasErrorEntryForKeyThat(key)
            .hasExceptionThat()
            .hasMessageThat()
            .contains(
                "//invalid:not_a_platform was referenced as a platform, "
                        + "but does not provide PlatformInfo"
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun resolve_invalidTargetPlatform_badPackage() {
        scratch.resolve("invalid").delete()

        // Manually create a configuration key: trying to call `useConfiguration` will immediately throw
        // the exception this is checking for.
        val newOptions: BuildOptions = targetConfigKey.getOptions().clone()
        newOptions
            .get(PlatformOptions::class.java)
            .setPlatforms(com.google.common.collect.ImmutableList.of<E?>(Label.parseCanonicalUnchecked("//invalid:not_a_platform")))
        val configKey: BuildConfigurationKey? = BuildConfigurationKey.create(newOptions)

        // Create the toolchain context key and evaluate it.
        val key: ToolchainContextKey? =
            ToolchainContextKey.key()
                .configurationKey(configKey)
                .toolchainTypes(testToolchainType)
                .build()

        reporter.removeHandler(failFastHandler) // expect errors
        val result: EvaluationResult<UnloadedToolchainContext?> = invokeToolchainResolution(key)

        EvaluationResultSubjectFactory.assertThatEvaluationResult(result).hasError()
        EvaluationResultSubjectFactory.assertThatEvaluationResult(result)
            .hasErrorEntryForKeyThat(key)
            .hasExceptionThat()
            .isInstanceOf(InvalidPlatformException::class.java)
        EvaluationResultSubjectFactory.assertThatEvaluationResult(result)
            .hasErrorEntryForKeyThat(key)
            .hasExceptionThat()
            .hasMessageThat()
            .contains("BUILD file not found")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun resolve_executionPlatform_alias() {
        // This should select platform mac, toolchain extra_toolchain_mac, because platform
        // mac is listed first.
        addToolchain(
            "extra",
            "extra_toolchain_linux",
            com.google.common.collect.ImmutableList.of<E?>("//constraints:linux"),
            com.google.common.collect.ImmutableList.of<E?>("//constraints:linux"),
            "baz"
        )
        addToolchain(
            "extra",
            "extra_toolchain_mac",
            com.google.common.collect.ImmutableList.of<E?>("//constraints:mac"),
            com.google.common.collect.ImmutableList.of<E?>("//constraints:linux"),
            "baz"
        )
        // Set up aliases for the platforms.
        scratch.file(
            "alias/BUILD",
            """
        alias(name = 'mac', actual = '//platforms:mac')
        alias(name = 'linux', actual = '//platforms:linux')
        
        """.trimIndent()
        )
        rewriteModuleDotBazel(
            """
        register_toolchains("//extra:extra_toolchain_linux", "//extra:extra_toolchain_mac")
        register_execution_platforms('//alias:mac', '//alias:linux')
        
        """.trimIndent()
        )

        useConfiguration("--platforms=//platforms:linux")
        val key: ToolchainContextKey? =
            ToolchainContextKey.key()
                .configurationKey(targetConfigKey)
                .toolchainTypes(testToolchainType)
                .build()

        val result: EvaluationResult<UnloadedToolchainContext?> = invokeToolchainResolution(key)

        EvaluationResultSubjectFactory.assertThatEvaluationResult(result).hasNoError()
        val unloadedToolchainContext: UnloadedToolchainContext? = result.get(key)
        assertThat(unloadedToolchainContext).isNotNull()

        assertThat(unloadedToolchainContext).hasToolchainType(testToolchainTypeLabel)
        assertThat(unloadedToolchainContext).hasResolvedToolchain("//extra:extra_toolchain_mac_impl")
        assertThat(unloadedToolchainContext).hasExecutionPlatform("//platforms:mac")
        assertThat(unloadedToolchainContext).hasTargetPlatform("//platforms:linux")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun resolve_invalidHostPlatform() {
        scratch.file("invalid/BUILD", "filegroup(name = 'not_a_platform')")

        // Manually create a configuration key: trying to call `useConfiguration` will immediately throw
        // the exception this is checking for.
        val newOptions: BuildOptions = targetConfigKey.getOptions().clone()
        newOptions
            .get(PlatformOptions::class.java)
            .setHostPlatform(Label.parseCanonicalUnchecked("//invalid:not_a_platform"))
        val configKey: BuildConfigurationKey? = BuildConfigurationKey.create(newOptions)

        val key: ToolchainContextKey? =
            ToolchainContextKey.key()
                .configurationKey(configKey)
                .toolchainTypes(testToolchainType)
                .build()

        val result: EvaluationResult<UnloadedToolchainContext?> = invokeToolchainResolution(key)

        EvaluationResultSubjectFactory.assertThatEvaluationResult(result).hasError()
        EvaluationResultSubjectFactory.assertThatEvaluationResult(result)
            .hasErrorEntryForKeyThat(key)
            .hasExceptionThat()
            .isInstanceOf(InvalidPlatformException::class.java)
        EvaluationResultSubjectFactory.assertThatEvaluationResult(result)
            .hasErrorEntryForKeyThat(key)
            .hasExceptionThat()
            .hasMessageThat()
            .contains("//invalid:not_a_platform")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun resolve_invalidExecutionPlatform() {
        // Have to use a rule that doesn't require a target platform, or else there will be a cycle.
        scratch.file("invalid/BUILD", "toolchain_type(name = 'not_a_platform')")
        useConfiguration("--extra_execution_platforms=//invalid:not_a_platform")
        val key: ToolchainContextKey? =
            ToolchainContextKey.key()
                .configurationKey(targetConfigKey)
                .toolchainTypes(testToolchainType)
                .build()

        val result: EvaluationResult<UnloadedToolchainContext?> = invokeToolchainResolution(key)

        EvaluationResultSubjectFactory.assertThatEvaluationResult(result).hasError()
        EvaluationResultSubjectFactory.assertThatEvaluationResult(result)
            .hasErrorEntryForKeyThat(key)
            .hasExceptionThat()
            .isInstanceOf(InvalidPlatformException::class.java)
        EvaluationResultSubjectFactory.assertThatEvaluationResult(result)
            .hasErrorEntryForKeyThat(key)
            .hasExceptionThat()
            .hasMessageThat()
            .contains("//invalid:not_a_platform")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun resolve_execConstraints() {
        // This should select platform linux, toolchain extra_toolchain_linux, due to extra constraints,
        // even though platform mac is registered first.
        addToolchain( /* packageName= */
            "extra",  /* toolchainName= */
            "extra_toolchain_linux",  /* execConstraints= */
            com.google.common.collect.ImmutableList.of<E?>("//constraints:linux"),  /* targetConstraints= */
            com.google.common.collect.ImmutableList.of<E?>("//constraints:linux"),  /* data= */
            "baz"
        )
        addToolchain( /* packageName= */
            "extra",  /* toolchainName= */
            "extra_toolchain_mac",  /* execConstraints= */
            com.google.common.collect.ImmutableList.of<E?>("//constraints:mac"),  /* targetConstraints= */
            com.google.common.collect.ImmutableList.of<E?>("//constraints:linux"),  /* data= */
            "baz"
        )
        rewriteModuleDotBazel(
            """
        register_toolchains("//extra:extra_toolchain_linux", "//extra:extra_toolchain_mac")
        register_execution_platforms("//platforms:mac", "//platforms:linux")
        
        """.trimIndent()
        )

        useConfiguration("--platforms=//platforms:linux")
        val key: ToolchainContextKey? =
            ToolchainContextKey.key()
                .configurationKey(targetConfigKey)
                .toolchainTypes(testToolchainType)
                .execConstraintLabels(Label.parseCanonicalUnchecked("//constraints:linux"))
                .build()

        val result: EvaluationResult<UnloadedToolchainContext?> = invokeToolchainResolution(key)

        EvaluationResultSubjectFactory.assertThatEvaluationResult(result).hasNoError()
        val unloadedToolchainContext: UnloadedToolchainContext? = result.get(key)
        assertThat(unloadedToolchainContext).isNotNull()

        assertThat(unloadedToolchainContext).hasToolchainType(testToolchainTypeLabel)
        assertThat(unloadedToolchainContext).hasResolvedToolchain("//extra:extra_toolchain_linux_impl")
        assertThat(unloadedToolchainContext).hasExecutionPlatform("//platforms:linux")
        assertThat(unloadedToolchainContext).hasTargetPlatform("//platforms:linux")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun resolve_execConstraints_invalid() {
        val key: ToolchainContextKey? =
            ToolchainContextKey.key()
                .configurationKey(targetConfigKey)
                .toolchainTypes(testToolchainType)
                .execConstraintLabels(Label.parseCanonicalUnchecked("//platforms:linux"))
                .build()

        val result: EvaluationResult<UnloadedToolchainContext?> = invokeToolchainResolution(key)

        EvaluationResultSubjectFactory.assertThatEvaluationResult(result).hasError()
        EvaluationResultSubjectFactory.assertThatEvaluationResult(result)
            .hasErrorEntryForKeyThat(key)
            .hasExceptionThat()
            .isInstanceOf(InvalidConstraintValueException::class.java)
        EvaluationResultSubjectFactory.assertThatEvaluationResult(result)
            .hasErrorEntryForKeyThat(key)
            .hasExceptionThat()
            .hasMessageThat()
            .contains("//platforms:linux")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun resolve_noMatchingPlatform() {
        // Write toolchain A, and a toolchain implementing it.
        scratch.appendFile(
            "a/BUILD",
            """
        toolchain_type(name = "toolchain_type_A")

        toolchain(
            name = "toolchain",
            exec_compatible_with = ["//constraints:mac"],
            target_compatible_with = [],
            toolchain = ":toolchain_impl",
            toolchain_type = ":toolchain_type_A",
        )

        filegroup(name = "toolchain_impl")
        
        """.trimIndent()
        )
        // Write toolchain B, and a toolchain implementing it.
        scratch.appendFile(
            "b/BUILD",
            """
        load("//toolchain:toolchain_def.bzl", "test_toolchain")

        toolchain_type(name = "toolchain_type_B")

        toolchain(
            name = "toolchain",
            exec_compatible_with = ["//constraints:linux"],
            target_compatible_with = [],
            toolchain = ":toolchain_impl",
            toolchain_type = ":toolchain_type_B",
        )

        filegroup(name = "toolchain_impl")
        
        """.trimIndent()
        )

        rewriteModuleDotBazel(
            """
        register_toolchains("//a:toolchain", "//b:toolchain")
        register_execution_platforms("//platforms:mac", "//platforms:linux")
        
        """.trimIndent()
        )

        useConfiguration("--platforms=//platforms:linux")
        val key: ToolchainContextKey? =
            ToolchainContextKey.key()
                .configurationKey(targetConfigKey)
                .toolchainTypes(
                    ToolchainTypeRequirement.create(
                        Label.parseCanonicalUnchecked("//a:toolchain_type_A")
                    ),
                    ToolchainTypeRequirement.create(
                        Label.parseCanonicalUnchecked("//b:toolchain_type_B")
                    )
                )
                .build()

        val result: EvaluationResult<UnloadedToolchainContext?> = invokeToolchainResolution(key)
        EvaluationResultSubjectFactory.assertThatEvaluationResult(result).hasNoError()
        val unloadedToolchainContext: UnloadedToolchainContext = result.get(key)
        assertThat(unloadedToolchainContext.errorData()).isNotNull()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun resolve_forceExecutionPlatform() {
        // This should select execution platform linux, toolchain extra_toolchain_linux, due to the
        // forced execution platform, even though execution platform mac is registered first.
        addToolchain( /* packageName= */
            "extra",  /* toolchainName= */
            "extra_toolchain_linux",  /* execConstraints= */
            com.google.common.collect.ImmutableList.of<E?>("//constraints:linux"),  /* targetConstraints= */
            com.google.common.collect.ImmutableList.of<E?>("//constraints:linux"),  /* data= */
            "baz"
        )
        addToolchain( /* packageName= */
            "extra",  /* toolchainName= */
            "extra_toolchain_mac",  /* execConstraints= */
            com.google.common.collect.ImmutableList.of<E?>("//constraints:mac"),  /* targetConstraints= */
            com.google.common.collect.ImmutableList.of<E?>("//constraints:linux"),  /* data= */
            "baz"
        )
        rewriteModuleDotBazel(
            """
        register_toolchains("//extra:extra_toolchain_linux", "//extra:extra_toolchain_mac")
        register_execution_platforms("//platforms:mac", "//platforms:linux")
        
        """.trimIndent()
        )

        useConfiguration("--platforms=//platforms:linux")
        val key: ToolchainContextKey? =
            ToolchainContextKey.key()
                .configurationKey(targetConfigKey)
                .toolchainTypes(testToolchainType)
                .forceExecutionPlatform(Label.parseCanonicalUnchecked("//platforms:linux"))
                .build()

        val result: EvaluationResult<UnloadedToolchainContext?> = invokeToolchainResolution(key)

        EvaluationResultSubjectFactory.assertThatEvaluationResult(result).hasNoError()
        val unloadedToolchainContext: UnloadedToolchainContext? = result.get(key)
        assertThat(unloadedToolchainContext).isNotNull()

        assertThat(unloadedToolchainContext).hasToolchainType(testToolchainTypeLabel)
        assertThat(unloadedToolchainContext).hasResolvedToolchain("//extra:extra_toolchain_linux_impl")
        assertThat(unloadedToolchainContext).hasExecutionPlatform("//platforms:linux")
        assertThat(unloadedToolchainContext).hasTargetPlatform("//platforms:linux")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun resolve_forceExecutionPlatform_alias() {
        // This should select execution platform linux, toolchain extra_toolchain_linux, due to the
        // forced execution platform, even though execution platform mac is registered first.
        addToolchain( /* packageName= */
            "extra",  /* toolchainName= */
            "extra_toolchain_linux",  /* execConstraints= */
            com.google.common.collect.ImmutableList.of<E?>("//constraints:linux"),  /* targetConstraints= */
            com.google.common.collect.ImmutableList.of<E?>("//constraints:linux"),  /* data= */
            "baz"
        )
        addToolchain( /* packageName= */
            "extra",  /* toolchainName= */
            "extra_toolchain_mac",  /* execConstraints= */
            com.google.common.collect.ImmutableList.of<E?>("//constraints:mac"),  /* targetConstraints= */
            com.google.common.collect.ImmutableList.of<E?>("//constraints:linux"),  /* data= */
            "baz"
        )
        // Set up aliases for the platforms.
        scratch.file(
            "alias/BUILD",
            """
        alias(name = 'mac', actual = '//platforms:mac')
        alias(name = 'linux', actual = '//platforms:linux')
        
        """.trimIndent()
        )
        rewriteModuleDotBazel(
            """
        register_toolchains("//extra:extra_toolchain_linux", "//extra:extra_toolchain_mac")
        register_execution_platforms("//alias:mac", "//alias:linux")
        
        """.trimIndent()
        )

        useConfiguration("--platforms=//platforms:linux")
        val key: ToolchainContextKey? =
            ToolchainContextKey.key()
                .configurationKey(targetConfigKey)
                .toolchainTypes(testToolchainType)
                .forceExecutionPlatform(Label.parseCanonicalUnchecked("//platforms:linux"))
                .build()

        val result: EvaluationResult<UnloadedToolchainContext?> = invokeToolchainResolution(key)

        EvaluationResultSubjectFactory.assertThatEvaluationResult(result).hasNoError()
        val unloadedToolchainContext: UnloadedToolchainContext? = result.get(key)
        assertThat(unloadedToolchainContext).isNotNull()

        assertThat(unloadedToolchainContext).hasToolchainType(testToolchainTypeLabel)
        assertThat(unloadedToolchainContext).hasResolvedToolchain("//extra:extra_toolchain_linux_impl")
        assertThat(unloadedToolchainContext).hasExecutionPlatform("//platforms:linux")
        assertThat(unloadedToolchainContext).hasTargetPlatform("//platforms:linux")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun resolve_forceExecutionPlatform_host() {
        // This should select execution platform linux, toolchain extra_toolchain_linux, due to the
        // forced execution platform, even though execution platform mac is registered first.
        addToolchain( /* packageName= */
            "extra",  /* toolchainName= */
            "extra_toolchain_linux",  /* execConstraints= */
            com.google.common.collect.ImmutableList.of<E?>("//constraints:linux"),  /* targetConstraints= */
            com.google.common.collect.ImmutableList.of<E?>("//constraints:linux"),  /* data= */
            "baz"
        )
        addToolchain( /* packageName= */
            "extra",  /* toolchainName= */
            "extra_toolchain_mac",  /* execConstraints= */
            com.google.common.collect.ImmutableList.of<E?>("//constraints:mac"),  /* targetConstraints= */
            com.google.common.collect.ImmutableList.of<E?>("//constraints:linux"),  /* data= */
            "baz"
        )
        rewriteModuleDotBazel(
            """
        register_toolchains("//extra:extra_toolchain_linux", "//extra:extra_toolchain_mac")
        
        """.trimIndent()
        )

        useConfiguration("--platforms=//platforms:linux", "--host_platform=//platforms:linux")
        val key: ToolchainContextKey? =
            ToolchainContextKey.key()
                .configurationKey(targetConfigKey)
                .toolchainTypes(testToolchainType)
                .forceExecutionPlatform(Label.parseCanonicalUnchecked("//platforms:linux"))
                .build()

        val result: EvaluationResult<UnloadedToolchainContext?> = invokeToolchainResolution(key)

        EvaluationResultSubjectFactory.assertThatEvaluationResult(result).hasNoError()
        val unloadedToolchainContext: UnloadedToolchainContext? = result.get(key)
        assertThat(unloadedToolchainContext).isNotNull()

        assertThat(unloadedToolchainContext).hasToolchainType(testToolchainTypeLabel)
        assertThat(unloadedToolchainContext).hasResolvedToolchain("//extra:extra_toolchain_linux_impl")
        assertThat(unloadedToolchainContext).hasExecutionPlatform("//platforms:linux")
        assertThat(unloadedToolchainContext).hasTargetPlatform("//platforms:linux")
    }

    // Regression test for https://github.com/bazelbuild/bazel/issues/22607, where the aliased host
    // platform didn't match with the dereferenced forced execution platform and so no toolchain
    // was selected.
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun resolve_forceExecutionPlatform_host_alias() {
        // This should select execution platform linux, toolchain extra_toolchain_linux, due to the
        // forced execution platform, even though execution platform mac is registered first.
        addToolchain( /* packageName= */
            "extra",  /* toolchainName= */
            "extra_toolchain_linux",  /* execConstraints= */
            com.google.common.collect.ImmutableList.of<E?>("//constraints:linux"),  /* targetConstraints= */
            com.google.common.collect.ImmutableList.of<E?>("//constraints:linux"),  /* data= */
            "baz"
        )
        addToolchain( /* packageName= */
            "extra",  /* toolchainName= */
            "extra_toolchain_mac",  /* execConstraints= */
            com.google.common.collect.ImmutableList.of<E?>("//constraints:mac"),  /* targetConstraints= */
            com.google.common.collect.ImmutableList.of<E?>("//constraints:linux"),  /* data= */
            "baz"
        )
        // Set up aliases for the platforms.
        scratch.file(
            "alias/BUILD",
            """
        alias(name = 'mac', actual = '//platforms:mac')
        alias(name = 'linux', actual = '//platforms:linux')
        
        """.trimIndent()
        )
        rewriteModuleDotBazel(
            """
        register_toolchains("//extra:extra_toolchain_linux", "//extra:extra_toolchain_mac")

        # This test requires an execution platform that isn't the forced platform in order to
        # trigger.
        register_execution_platforms("//alias:mac")
        
        """.trimIndent()
        )

        useConfiguration("--platforms=//platforms:linux", "--host_platform=//alias:linux")
        val key: ToolchainContextKey? =
            ToolchainContextKey.key()
                .configurationKey(targetConfigKey)
                .toolchainTypes(testToolchainType) // Use the actual label for the forced exec platform, since this was redeferenced
                // earlier in analysis.
                .forceExecutionPlatform(Label.parseCanonicalUnchecked("//platforms:linux"))
                .build()

        val result: EvaluationResult<UnloadedToolchainContext?> = invokeToolchainResolution(key)

        EvaluationResultSubjectFactory.assertThatEvaluationResult(result).hasNoError()
        val unloadedToolchainContext: UnloadedToolchainContext? = result.get(key)
        assertThat(unloadedToolchainContext).isNotNull()

        assertThat(unloadedToolchainContext).hasToolchainType(testToolchainTypeLabel)
        assertThat(unloadedToolchainContext).hasResolvedToolchain("//extra:extra_toolchain_linux_impl")
        assertThat(unloadedToolchainContext).hasExecutionPlatform("//platforms:linux")
        assertThat(unloadedToolchainContext).hasTargetPlatform("//platforms:linux")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun resolve_forceExecutionPlatform_noRequiredToolchains() {
        // This should select execution platform linux, due to the forced execution platform, even
        // though execution platform mac is registered first.
        rewriteModuleDotBazel(
            """
        register_execution_platforms("//platforms:mac", "//platforms:linux")
        
        """.trimIndent()
        )

        useConfiguration("--platforms=//platforms:linux")
        val key: ToolchainContextKey? =
            ToolchainContextKey.key()
                .configurationKey(targetConfigKey)
                .forceExecutionPlatform(Label.parseCanonicalUnchecked("//platforms:linux"))
                .build()

        val result: EvaluationResult<UnloadedToolchainContext?> = invokeToolchainResolution(key)

        EvaluationResultSubjectFactory.assertThatEvaluationResult(result).hasNoError()
        val unloadedToolchainContext: UnloadedToolchainContext? = result.get(key)
        assertThat(unloadedToolchainContext).isNotNull()

        assertThat(unloadedToolchainContext).hasExecutionPlatform("//platforms:linux")
        assertThat(unloadedToolchainContext).hasTargetPlatform("//platforms:linux")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun errorProperlyReportedWhenInvalidConfigurationConfiguration() {
        // It would be absolutely insane for a user to have a toolchain w/ a config_setting that reads a
        // config_feature_flag; however, should still test the InvalidConfigurationException codepath.
        rewriteModuleDotBazel(
            """
        register_toolchains("//strange:strange_toolchain")
        register_execution_platforms("//platforms:mac", "//platforms:linux")
        
        """.trimIndent()
        )
        scratch.file(
            "strange/BUILD",
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
            name = "strange_toolchain",
            target_settings = [":flagged"],
            toolchain = ":strange_test_toolchain",
            toolchain_type = "//toolchain:test_toolchain",
        )

        test_toolchain(
            name = "strange_test_toolchain",
            data = "foo",
        )
        
        """.trimIndent()
        )
        scratch.file(
            "rule/rule_def.bzl",
            """
        def _impl(ctx):
            pass

        my_rule = rule(
            implementation = _impl,
            toolchains = ["//toolchain:test_toolchain"],
        )
        
        """.trimIndent()
        )
        scratch.file(
            "rule/BUILD",
            """
        load("//rule:rule_def.bzl", "my_rule")

        my_rule(
            name = "me",
        )
        
        """.trimIndent()
        )
        // Need this so the feature flag actually gone from the configuration.
        useConfiguration("--enforce_transitive_configs_for_config_feature_flag")
        reporter.removeHandler(failFastHandler) // expect errors
        assertThat(getConfiguredTarget("//rule:me")).isNull()
        assertContainsEvent(
            ("Unrecoverable errors resolving config_setting associated with"
                    + " //strange:strange_toolchain: For config_setting flagged: Feature flag"
                    + " //strange:flag was accessed in a configuration it is not present in.")
        )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun resolve_checkPlatformAllowedToolchains_match() {
        // Define two new execution platforms, only one of which is compatible with the test toolchain.
        scratch.file(
            "allowed/BUILD",
            """
        platform(
            name = "allows_single_toolchain",
            check_toolchain_types = True,
            allowed_toolchain_types = [
                "//toolchain:test_toolchain",
            ],
        )

        platform(
            name = "allows_all",
        )
        
        """.trimIndent()
        )
        addToolchain(
            "toolchain",
            "toolchain_impl",
            com.google.common.collect.ImmutableList.of<E?>(),
            com.google.common.collect.ImmutableList.of<E?>(),
            "baz"
        )
        rewriteModuleDotBazel(
            """
        register_toolchains("//toolchain:toolchain_impl")
        register_execution_platforms("//allowed:allows_single_toolchain", "//allowed:allows_all")
        
        """.trimIndent()
        )

        val key: ToolchainContextKey? =
            ToolchainContextKey.key()
                .configurationKey(targetConfigKey)
                .toolchainTypes(testToolchainType)
                .build()

        val result: EvaluationResult<UnloadedToolchainContext?> = invokeToolchainResolution(key)

        EvaluationResultSubjectFactory.assertThatEvaluationResult(result).hasNoError()
        val unloadedToolchainContext: UnloadedToolchainContext? = result.get(key)
        assertThat(unloadedToolchainContext).isNotNull()

        // The platform allows the required toolchain type, so it is selected.
        assertThat(unloadedToolchainContext).hasExecutionPlatform("//allowed:allows_single_toolchain")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun resolve_checkPlatformAllowedToolchains_failsMatch() {
        // Define two new execution platforms, only one of which is compatible with the test toolchain.
        scratch.file(
            "allowed/BUILD",
            """
        toolchain_type(name = "other_toolchain")

        platform(
            name = "allows_single_toolchain",
            check_toolchain_types = True,
            allowed_toolchain_types = [
                ":other_toolchain",
            ],
        )

        platform(
            name = "allows_all",
        )
        
        """.trimIndent()
        )
        addToolchain(
            "toolchain",
            "toolchain_impl",
            com.google.common.collect.ImmutableList.of<E?>(),
            com.google.common.collect.ImmutableList.of<E?>(),
            "baz"
        )
        rewriteModuleDotBazel(
            """
        register_toolchains("//toolchain:toolchain_impl")
        register_execution_platforms("//allowed:allows_single_toolchain", "//allowed:allows_all")
        
        """.trimIndent()
        )

        val key: ToolchainContextKey? =
            ToolchainContextKey.key()
                .configurationKey(targetConfigKey)
                .toolchainTypes(testToolchainType)
                .build()

        val result: EvaluationResult<UnloadedToolchainContext?> = invokeToolchainResolution(key)

        EvaluationResultSubjectFactory.assertThatEvaluationResult(result).hasNoError()
        val unloadedToolchainContext: UnloadedToolchainContext? = result.get(key)
        assertThat(unloadedToolchainContext).isNotNull()

        // The platform does not allow the required toolchain type, so it is not selected.
        assertThat(unloadedToolchainContext).hasExecutionPlatform("//allowed:allows_all")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun resolve_checkPlatformAllowedToolchains_noneRequested_failsMatch() {
        // Define two new execution platforms, only one of which is compatible with the test toolchain.
        scratch.file(
            "allowed/BUILD",
            """
        platform(
            name = "allows_single_toolchain",
            check_toolchain_types = True,
            allowed_toolchain_types = [
                "//toolchain:test_toolchain",
            ],
        )

        platform(
            name = "allows_all",
        )
        
        """.trimIndent()
        )
        addToolchain(
            "toolchain",
            "toolchain_impl",
            com.google.common.collect.ImmutableList.of<E?>(),
            com.google.common.collect.ImmutableList.of<E?>(),
            "baz"
        )
        rewriteModuleDotBazel(
            """
        register_toolchains("//toolchain:toolchain_impl")
        register_execution_platforms("//allowed:allows_single_toolchain", "//allowed:allows_all")
        
        """.trimIndent()
        )

        val key: ToolchainContextKey? = ToolchainContextKey.key().configurationKey(targetConfigKey).build()

        val result: EvaluationResult<UnloadedToolchainContext?> = invokeToolchainResolution(key)

        EvaluationResultSubjectFactory.assertThatEvaluationResult(result).hasNoError()
        val unloadedToolchainContext: UnloadedToolchainContext? = result.get(key)
        assertThat(unloadedToolchainContext).isNotNull()

        // The platform requires a toolchain type, so it is not selected.
        assertThat(unloadedToolchainContext).hasExecutionPlatform("//allowed:allows_all")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun resolve_checkPlatformAllowedToolchains_noToolchainType_noneRequested() {
        // Define two new execution platforms, only one of which is compatible with the test toolchain.
        scratch.file(
            "allowed/BUILD",
            """
        platform(
            name = "allows_none",
            check_toolchain_types = True,
            allowed_toolchain_types = [
                # Empty, so doesn't match anything.
            ],
        )

        platform(
            name = "allows_all",
        )
        
        """.trimIndent()
        )
        rewriteModuleDotBazel(
            """
        register_execution_platforms("//allowed:allows_none", "//allowed:allows_all")
        
        """.trimIndent()
        )

        val key: ToolchainContextKey? = ToolchainContextKey.key().configurationKey(targetConfigKey).build()

        val result: EvaluationResult<UnloadedToolchainContext?> = invokeToolchainResolution(key)

        EvaluationResultSubjectFactory.assertThatEvaluationResult(result).hasNoError()
        val unloadedToolchainContext: UnloadedToolchainContext? = result.get(key)
        assertThat(unloadedToolchainContext).isNotNull()

        // The platform doesn't have any toolchains specified.
        assertThat(unloadedToolchainContext).hasExecutionPlatform("//allowed:allows_all")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun resolve_checkPlatformAllowedToolchains_noToolchainType() {
        // Define two new execution platforms, only one of which is compatible with the test toolchain.
        scratch.file(
            "allowed/BUILD",
            """
        platform(
            name = "allows_none",
            check_toolchain_types = True,
            allowed_toolchain_types = [
                # Empty, so doesn't match anything.
            ],
        )

        platform(
            name = "allows_all",
        )
        
        """.trimIndent()
        )
        addToolchain(
            "toolchain",
            "toolchain_impl",
            com.google.common.collect.ImmutableList.of<E?>(),
            com.google.common.collect.ImmutableList.of<E?>(),
            "baz"
        )
        rewriteModuleDotBazel(
            """
        register_toolchains("//toolchain:toolchain_impl")
        register_execution_platforms("//allowed:allows_none", "//allowed:allows_all")
        
        """.trimIndent()
        )

        val key: ToolchainContextKey? =
            ToolchainContextKey.key()
                .configurationKey(targetConfigKey)
                .toolchainTypes(testToolchainType)
                .build()

        val result: EvaluationResult<UnloadedToolchainContext?> = invokeToolchainResolution(key)

        EvaluationResultSubjectFactory.assertThatEvaluationResult(result).hasNoError()
        val unloadedToolchainContext: UnloadedToolchainContext? = result.get(key)
        assertThat(unloadedToolchainContext).isNotNull()

        // The platform doesn't have any toolchains specified, but the request does.
        assertThat(unloadedToolchainContext).hasExecutionPlatform("//allowed:allows_all")
    }
}
