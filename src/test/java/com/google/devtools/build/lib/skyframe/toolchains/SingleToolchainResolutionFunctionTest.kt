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

import com.google.devtools.build.lib.cmdline.Label

/**
 * Tests for [SingleToolchainResolutionValue] and [SingleToolchainResolutionFunction].
 */
@RunWith(JUnit4::class)
class SingleToolchainResolutionFunctionTest : ToolchainTestCase() {
    var linuxCtkey: ConfiguredTargetKey? = null
    var macCtkey: ConfiguredTargetKey? = null

    @Before
    fun setUpKeys() {
        // This has to happen here so that targetConfiguration is populated.
        linuxCtkey =
            ConfiguredTargetKey.builder()
                .setLabel(Label.parseCanonicalUnchecked("//platforms:linux"))
                .setConfiguration(getTargetConfiguration())
                .build()
        macCtkey =
            ConfiguredTargetKey.builder()
                .setLabel(Label.parseCanonicalUnchecked("//platforms:mac"))
                .setConfiguration(getTargetConfiguration())
                .build()
    }

    @Throws(java.lang.InterruptedException::class)
    private fun invokeToolchainResolution(key: SkyKey?): EvaluationResult<SingleToolchainResolutionValue?> {
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
    fun testResolution_singleExecutionPlatform() {
        val key: SkyKey? =
            SingleToolchainResolutionValue.key(
                targetConfigKey,
                testToolchainType,
                testToolchainTypeInfo,
                linuxCtkey,
                com.google.common.collect.ImmutableList.of<E?>(macCtkey)
            )
        val result: EvaluationResult<SingleToolchainResolutionValue?> = invokeToolchainResolution(key)

        EvaluationResultSubjectFactory.assertThatEvaluationResult(result).hasNoError()

        val singleToolchainResolutionValue: SingleToolchainResolutionValue = result.get(key)
        assertThat(singleToolchainResolutionValue.availableToolchainLabels())
            .containsExactly(macCtkey, Label.parseCanonicalUnchecked("//toolchain:toolchain_2_impl"))
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testResolution_multipleExecutionPlatforms() {
        addToolchain(
            "extra",
            "extra_toolchain",
            com.google.common.collect.ImmutableList.of<E?>("//constraints:linux"),
            com.google.common.collect.ImmutableList.of<E?>("//constraints:linux"),
            "baz"
        )
        rewriteModuleDotBazel(
            """
        register_toolchains(
            "//toolchain:toolchain_1",
            "//toolchain:toolchain_2",
            "//extra:extra_toolchain",
        )
        
        """.trimIndent()
        )

        val key: SkyKey? =
            SingleToolchainResolutionValue.key(
                targetConfigKey,
                testToolchainType,
                testToolchainTypeInfo,
                linuxCtkey,
                com.google.common.collect.ImmutableList.of<E?>(linuxCtkey, macCtkey)
            )
        val result: EvaluationResult<SingleToolchainResolutionValue?> = invokeToolchainResolution(key)

        EvaluationResultSubjectFactory.assertThatEvaluationResult(result).hasNoError()

        val singleToolchainResolutionValue: SingleToolchainResolutionValue = result.get(key)
        assertThat(singleToolchainResolutionValue.availableToolchainLabels())
            .containsExactly(
                linuxCtkey,
                Label.parseCanonicalUnchecked("//extra:extra_toolchain_impl"),
                macCtkey,
                Label.parseCanonicalUnchecked("//toolchain:toolchain_2_impl")
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testResolution_noneFound() {
        // Clear the toolchains.
        rewriteModuleDotBazel()

        val key: SkyKey? =
            SingleToolchainResolutionValue.key(
                targetConfigKey,
                testToolchainType,
                testToolchainTypeInfo,
                linuxCtkey,
                com.google.common.collect.ImmutableList.of<E?>(macCtkey)
            )
        val result: EvaluationResult<SingleToolchainResolutionValue?> = invokeToolchainResolution(key)

        val singleToolchainResolutionValue: SingleToolchainResolutionValue = result.get(key)
        assertThat(singleToolchainResolutionValue.availableToolchainLabels()).isEmpty()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testResolution_checkPlatformAllowedToolchains() {
        // Define two new execution platforms, only one of which is compatible with the test toolchain.
        scratch.file(
            "allowed/BUILD",
            """
        platform(
            name = "fails_match",
            check_toolchain_types = True,
            allowed_toolchain_types = [
                # Empty, so doesn't match anything.
            ],
        )

        platform(
            name = "matches",
            check_toolchain_types = True,
            allowed_toolchain_types = [
                "//toolchain:test_toolchain",
            ],
        )
        
        """.trimIndent()
        )
        val failsMatchPlatformCtKey: ConfiguredTargetKey? =
            ConfiguredTargetKey.builder()
                .setLabel(Label.parseCanonicalUnchecked("//allowed:fails_match"))
                .setConfiguration(getTargetConfiguration())
                .build()
        val allowedPlatformCtKey: ConfiguredTargetKey? =
            ConfiguredTargetKey.builder()
                .setLabel(Label.parseCanonicalUnchecked("//allowed:matches"))
                .setConfiguration(getTargetConfiguration())
                .build()

        // Define the toolchains themselves.
        addToolchain(
            "extra",
            "extra_toolchain",
            com.google.common.collect.ImmutableList.of<E?>(),
            com.google.common.collect.ImmutableList.of<E?>(),
            "baz"
        )
        rewriteModuleDotBazel(
            """
        register_toolchains("//extra:extra_toolchain")
        
        """.trimIndent()
        )

        // Resolve toolchains.
        val key: SkyKey? =
            SingleToolchainResolutionValue.key(
                targetConfigKey,
                testToolchainType,
                testToolchainTypeInfo,
                linuxCtkey,
                com.google.common.collect.ImmutableList.of<E?>(failsMatchPlatformCtKey, allowedPlatformCtKey)
            )
        val result: EvaluationResult<SingleToolchainResolutionValue?> = invokeToolchainResolution(key)

        EvaluationResultSubjectFactory.assertThatEvaluationResult(result).hasNoError()

        val singleToolchainResolutionValue: SingleToolchainResolutionValue = result.get(key)
        assertThat(singleToolchainResolutionValue.availableToolchainLabels())
            .containsExactly(
                allowedPlatformCtKey, Label.parseCanonicalUnchecked("//extra:extra_toolchain_impl")
            )
    }

    @org.junit.Test
    fun testToolchainResolutionValue_equalsAndHashCode() {
        EqualsTester()
            .addEqualityGroup(
                SingleToolchainResolutionValue.create(
                    testToolchainTypeInfo,
                    com.google.common.collect.ImmutableMap.of<K?, V?>(
                        linuxCtkey, Label.parseCanonicalUnchecked("//test:toolchain_impl_1")
                    )
                ),
                SingleToolchainResolutionValue.create(
                    testToolchainTypeInfo,
                    com.google.common.collect.ImmutableMap.of<K?, V?>(
                        linuxCtkey, Label.parseCanonicalUnchecked("//test:toolchain_impl_1")
                    )
                )
            ) // Different execution platform, same label.
            .addEqualityGroup(
                SingleToolchainResolutionValue.create(
                    testToolchainTypeInfo,
                    com.google.common.collect.ImmutableMap.of<K?, V?>(
                        macCtkey, Label.parseCanonicalUnchecked("//test:toolchain_impl_1")
                    )
                )
            ) // Same execution platform, different label.
            .addEqualityGroup(
                SingleToolchainResolutionValue.create(
                    testToolchainTypeInfo,
                    com.google.common.collect.ImmutableMap.of<K?, V?>(
                        linuxCtkey, Label.parseCanonicalUnchecked("//test:toolchain_impl_2")
                    )
                )
            ) // Different execution platform, different label.
            .addEqualityGroup(
                SingleToolchainResolutionValue.create(
                    testToolchainTypeInfo,
                    com.google.common.collect.ImmutableMap.of<K?, V?>(
                        macCtkey, Label.parseCanonicalUnchecked("//test:toolchain_impl_2")
                    )
                )
            ) // Multiple execution platforms.
            .addEqualityGroup(
                SingleToolchainResolutionValue.create(
                    testToolchainTypeInfo,
                    com.google.common.collect.ImmutableMap.builder<ConfiguredTargetKey?, Label?>()
                        .put(linuxCtkey, Label.parseCanonicalUnchecked("//test:toolchain_impl_1"))
                        .put(macCtkey, Label.parseCanonicalUnchecked("//test:toolchain_impl_1"))
                        .buildOrThrow()
                )
            )
            .testEquals()
    }
}
