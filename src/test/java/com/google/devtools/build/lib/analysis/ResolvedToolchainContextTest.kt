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
package com.google.devtools.build.lib.analysis

import com.google.devtools.build.lib.analysis.config.ToolchainTypeRequirement

/** Tests for [ResolvedToolchainContext].  */
@RunWith(JUnit4::class)
class ResolvedToolchainContextTest : ToolchainTestCase() {
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun load() {
        addToolchain(
            "extra",
            "extra_toolchain_linux",
            com.google.common.collect.ImmutableList.of<String?>("//constraints:linux"),
            com.google.common.collect.ImmutableList.of<String?>("//constraints:linux"),
            "baz"
        )

        val toolchainContextKey: ToolchainContextKey? =
            ToolchainContextKey.key()
                .configurationKey(targetConfigKey)
                .toolchainTypes(testToolchainType)
                .build()

        // Create a static UnloadedToolchainContext.
        val unloadedToolchainContext: UnloadedToolchainContext? =
            UnloadedToolchainContextImpl.builder(toolchainContextKey)
                .setExecutionPlatform(linuxPlatform)
                .setTargetPlatform(linuxPlatform)
                .setToolchainTypes(com.google.common.collect.ImmutableSet.of<E?>(testToolchainType))
                .setRequestedLabelToToolchainType(
                    com.google.common.collect.ImmutableMap.of<K?, V?>(testToolchainTypeLabel, testToolchainTypeInfo)
                )
                .setToolchainTypeToResolved(
                    com.google.common.collect.ImmutableSetMultimap.builder<ToolchainTypeInfo?, Label?>()
                        .put(
                            testToolchainTypeInfo,
                            Label.parseCanonicalUnchecked("//extra:extra_toolchain_linux_impl")
                        )
                        .build()
                )
                .build()

        // Create the prerequisites.
        val toolchain: ConfiguredTargetAndData =
            getConfiguredTargetAndData(
                Label.parseCanonicalUnchecked("//extra:extra_toolchain_linux_impl"), targetConfig
            )

        // Resolve toolchains.
        val toolchainContext: ResolvedToolchainContext? =
            ResolvedToolchainContext.load(
                unloadedToolchainContext,
                "test",
                com.google.common.collect.ImmutableSet.of<E?>(toolchain)
            )
        assertThat(toolchainContext).isNotNull()
        assertThat(toolchainContext).hasToolchainType(testToolchainTypeLabel)
        assertThat(toolchainContext)
            .forToolchainType(testToolchainTypeLabel)
            .getValue("data")
            .isEqualTo("baz")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun load_mandatory_missing() {
        val toolchainContextKey: ToolchainContextKey? =
            ToolchainContextKey.key()
                .configurationKey(targetConfigKey)
                .toolchainTypes(testToolchainType)
                .build()

        // Create a static UnloadedToolchainContext.
        val unloadedToolchainContext: UnloadedToolchainContext? =
            UnloadedToolchainContextImpl.builder(toolchainContextKey)
                .setExecutionPlatform(linuxPlatform)
                .setTargetPlatform(linuxPlatform)
                .setToolchainTypes(com.google.common.collect.ImmutableSet.of<E?>(testToolchainType))
                .setRequestedLabelToToolchainType(
                    com.google.common.collect.ImmutableMap.of<K?, V?>(testToolchainTypeLabel, testToolchainTypeInfo)
                )
                .build()

        // Resolve toolchains.
        org.junit.Assert.assertThrows<T?>(
            ToolchainException::class.java,
            org.junit.function.ThrowingRunnable {
                ResolvedToolchainContext.load(
                    unloadedToolchainContext,
                    "test",
                    com.google.common.collect.ImmutableSet.of<E?>()
                )
            })
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun load_optional_present() {
        addOptionalToolchain(
            "extra",
            "extra_toolchain_linux",
            com.google.common.collect.ImmutableList.of<String?>("//constraints:linux"),
            com.google.common.collect.ImmutableList.of<String?>("//constraints:linux"),
            "baz"
        )

        val toolchainContextKey: ToolchainContextKey? =
            ToolchainContextKey.key()
                .configurationKey(targetConfigKey)
                .toolchainTypes(optionalToolchainType)
                .build()

        // Create a static UnloadedToolchainContext.
        val unloadedToolchainContext: UnloadedToolchainContext? =
            UnloadedToolchainContextImpl.builder(toolchainContextKey)
                .setExecutionPlatform(linuxPlatform)
                .setTargetPlatform(linuxPlatform)
                .setToolchainTypes(com.google.common.collect.ImmutableSet.of<E?>(optionalToolchainType))
                .setRequestedLabelToToolchainType(
                    com.google.common.collect.ImmutableMap.of<K?, V?>(
                        optionalToolchainTypeLabel,
                        optionalToolchainTypeInfo
                    )
                )
                .setToolchainTypeToResolved(
                    com.google.common.collect.ImmutableSetMultimap.builder<ToolchainTypeInfo?, Label?>()
                        .put(
                            optionalToolchainTypeInfo,
                            Label.parseCanonicalUnchecked("//extra:extra_toolchain_linux_impl")
                        )
                        .build()
                )
                .build()

        // Create the prerequisites.
        val toolchain: ConfiguredTargetAndData =
            getConfiguredTargetAndData(
                Label.parseCanonicalUnchecked("//extra:extra_toolchain_linux_impl"), targetConfig
            )

        // Resolve toolchains.
        val toolchainContext: ResolvedToolchainContext? =
            ResolvedToolchainContext.load(
                unloadedToolchainContext,
                "test",
                com.google.common.collect.ImmutableSet.of<E?>(toolchain)
            )
        assertThat(toolchainContext).isNotNull()
        assertThat(toolchainContext).hasToolchainType(optionalToolchainTypeLabel)
        assertThat(toolchainContext)
            .forToolchainType(optionalToolchainTypeLabel)
            .getValue("data")
            .isEqualTo("baz")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun load_optional_missing() {
        val toolchainContextKey: ToolchainContextKey? =
            ToolchainContextKey.key()
                .configurationKey(targetConfigKey)
                .toolchainTypes(optionalToolchainType)
                .build()

        // Create a static UnloadedToolchainContext.
        val unloadedToolchainContext: UnloadedToolchainContext? =
            UnloadedToolchainContextImpl.builder(toolchainContextKey)
                .setExecutionPlatform(linuxPlatform)
                .setTargetPlatform(linuxPlatform)
                .setToolchainTypes(com.google.common.collect.ImmutableSet.of<E?>(optionalToolchainType))
                .setRequestedLabelToToolchainType(
                    com.google.common.collect.ImmutableMap.of<K?, V?>(
                        optionalToolchainTypeLabel,
                        optionalToolchainTypeInfo
                    )
                )
                .build()

        // Resolve toolchains.
        val toolchainContext: ResolvedToolchainContext? =
            ResolvedToolchainContext.load(
                unloadedToolchainContext,
                "test",
                com.google.common.collect.ImmutableSet.of<E?>()
            )
        assertThat(toolchainContext).isNotNull()

        // Missing optional toolchain type requirement is present.
        assertThat(toolchainContext).hasToolchainType(optionalToolchainTypeLabel)
        // Missing optional toolchain implementation is null.
        assertThat(toolchainContext).forToolchainType(optionalToolchainTypeLabel).isNull()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun load_mixed() {
        addToolchain(
            "extra",
            "extra_toolchain_linux",
            com.google.common.collect.ImmutableList.of<String?>("//constraints:linux"),
            com.google.common.collect.ImmutableList.of<String?>("//constraints:linux"),
            "baz"
        )

        val toolchainContextKey: ToolchainContextKey? =
            ToolchainContextKey.key()
                .configurationKey(targetConfigKey)
                .toolchainTypes(testToolchainType, optionalToolchainType)
                .build()

        // Create a static UnloadedToolchainContext.
        val unloadedToolchainContext: UnloadedToolchainContext? =
            UnloadedToolchainContextImpl.builder(toolchainContextKey)
                .setExecutionPlatform(linuxPlatform)
                .setTargetPlatform(linuxPlatform)
                .setToolchainTypes(
                    com.google.common.collect.ImmutableSet.of<E?>(
                        testToolchainType,
                        optionalToolchainType
                    )
                )
                .setRequestedLabelToToolchainType(
                    com.google.common.collect.ImmutableMap.builder<Label?, ToolchainTypeInfo?>()
                        .put(testToolchainTypeLabel, testToolchainTypeInfo)
                        .put(optionalToolchainTypeLabel, optionalToolchainTypeInfo)
                        .build()
                )
                .setToolchainTypeToResolved(
                    com.google.common.collect.ImmutableSetMultimap.builder<ToolchainTypeInfo?, Label?>()
                        .put(
                            testToolchainTypeInfo,
                            Label.parseCanonicalUnchecked("//extra:extra_toolchain_linux_impl")
                        )
                        .build()
                )
                .build()

        // Create the prerequisites.
        val testToolchain: ConfiguredTargetAndData =
            getConfiguredTargetAndData(
                Label.parseCanonicalUnchecked("//extra:extra_toolchain_linux_impl"), targetConfig
            )

        // Resolve toolchains.
        val toolchainContext: ResolvedToolchainContext? =
            ResolvedToolchainContext.load(
                unloadedToolchainContext, "test", com.google.common.collect.ImmutableSet.of<E?>(testToolchain)
            )
        assertThat(toolchainContext).isNotNull()

        // Test toolchain is present.
        assertThat(toolchainContext).hasToolchainType(testToolchainTypeLabel)
        assertThat(toolchainContext)
            .forToolchainType(testToolchainTypeLabel)
            .getValue("data")
            .isEqualTo("baz")

        // Missing optional toolchain type requirement is present.
        assertThat(toolchainContext).hasToolchainType(optionalToolchainTypeLabel)
        // Missing optional toolchain implementation is null.
        assertThat(toolchainContext).forToolchainType(optionalToolchainTypeLabel).isNull()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun load_aliasedToolchain() {
        scratch.file(
            "alias/BUILD", "alias(name = 'toolchain', actual = '//extra:extra_toolchain_linux_impl')"
        )
        addToolchain(
            "extra",
            "extra_toolchain_linux",
            com.google.common.collect.ImmutableList.of<String?>("//constraints:linux"),
            com.google.common.collect.ImmutableList.of<String?>("//constraints:linux"),
            "baz"
        )

        val toolchainContextKey: ToolchainContextKey? =
            ToolchainContextKey.key()
                .configurationKey(targetConfigKey)
                .toolchainTypes(testToolchainType)
                .build()

        // Create a static UnloadedToolchainContext.
        val unloadedToolchainContext: UnloadedToolchainContext? =
            UnloadedToolchainContextImpl.builder(toolchainContextKey)
                .setExecutionPlatform(linuxPlatform)
                .setTargetPlatform(linuxPlatform)
                .setToolchainTypes(com.google.common.collect.ImmutableSet.of<E?>(testToolchainType))
                .setRequestedLabelToToolchainType(
                    com.google.common.collect.ImmutableMap.of<K?, V?>(testToolchainTypeLabel, testToolchainTypeInfo)
                )
                .setToolchainTypeToResolved(
                    com.google.common.collect.ImmutableSetMultimap.builder<ToolchainTypeInfo?, Label?>()
                        .put(testToolchainTypeInfo, Label.parseCanonicalUnchecked("//alias:toolchain"))
                        .build()
                )
                .build()

        // Create the prerequisites.
        val toolchain: ConfiguredTargetAndData =
            getConfiguredTargetAndData(
                Label.parseCanonicalUnchecked("//alias:toolchain"), targetConfig
            )

        // Resolve toolchains.
        val toolchainContext: ResolvedToolchainContext? =
            ResolvedToolchainContext.load(
                unloadedToolchainContext,
                "test",
                com.google.common.collect.ImmutableSet.of<E?>(toolchain)
            )
        assertThat(toolchainContext).isNotNull()
        assertThat(toolchainContext).hasToolchainType(testToolchainTypeLabel)
        assertThat(toolchainContext)
            .forToolchainType(testToolchainTypeLabel)
            .getValue("data")
            .isEqualTo("baz")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun load_notToolchain() {
        scratch.file("foo/BUILD", "filegroup(name = 'not_a_toolchain')")

        val toolchainContextKey: ToolchainContextKey? =
            ToolchainContextKey.key()
                .configurationKey(targetConfigKey)
                .toolchainTypes(testToolchainType)
                .build()

        // Create a static UnloadedToolchainContext.
        val unloadedToolchainContext: UnloadedToolchainContext? =
            UnloadedToolchainContextImpl.builder(toolchainContextKey)
                .setExecutionPlatform(linuxPlatform)
                .setTargetPlatform(linuxPlatform)
                .setToolchainTypes(com.google.common.collect.ImmutableSet.of<E?>(testToolchainType))
                .setRequestedLabelToToolchainType(
                    com.google.common.collect.ImmutableMap.of<K?, V?>(testToolchainTypeLabel, testToolchainTypeInfo)
                )
                .setToolchainTypeToResolved(
                    com.google.common.collect.ImmutableSetMultimap.builder<ToolchainTypeInfo?, Label?>()
                        .put(
                            testToolchainTypeInfo,
                            Label.parseCanonicalUnchecked("//foo:not_a_toolchain")
                        )
                        .build()
                )
                .build()

        // Create the prerequisites, which is not actually a valid toolchain.
        val toolchain: ConfiguredTargetAndData =
            getConfiguredTargetAndData(
                Label.parseCanonicalUnchecked("//foo:not_a_toolchain"), targetConfig
            )
        org.junit.Assert.assertThrows<T?>(
            ToolchainException::class.java,
            org.junit.function.ThrowingRunnable {
                ResolvedToolchainContext.load(
                    unloadedToolchainContext, "test", com.google.common.collect.ImmutableSet.of<E?>(toolchain)
                )
            })
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun load_withTemplateVariables() {
        // Add new toolchain rule that provides template variables.
        val variableToolchainTypeLabel: Label =
            Label.parseCanonicalUnchecked("//variable:variable_toolchain_type")
        val variableToolchainType: ToolchainTypeRequirement =
            ToolchainTypeRequirement.create(variableToolchainTypeLabel)
        val variableToolchainTypeInfo: ToolchainTypeInfo =
            ToolchainTypeInfo.create(variableToolchainTypeLabel)
        scratch.file(
            "variable/variable_toolchain_def.bzl",
            """
        def _impl(ctx):
            value = ctx.attr.value
            toolchain = platform_common.ToolchainInfo()
            template_variables = platform_common.TemplateVariableInfo({"VALUE": value})
            return [toolchain, template_variables]

        variable_toolchain = rule(
            implementation = _impl,
            attrs = {"value": attr.string()},
        )
        
        """.trimIndent()
        )

        // Create instance of new toolchain and register it.
        scratch.file(
            "variable/BUILD",
            """
        load("//variable:variable_toolchain_def.bzl", "variable_toolchain")

        toolchain_type(name = "variable_toolchain_type")

        variable_toolchain(
            name = "variable_toolchain_impl",
            value = "foo",
        )
        
        """.trimIndent()
        )

        val toolchainContextKey: ToolchainContextKey? =
            ToolchainContextKey.key()
                .configurationKey(targetConfigKey)
                .toolchainTypes(testToolchainType)
                .build()

        // Create a static UnloadedToolchainContext.
        val unloadedToolchainContext: UnloadedToolchainContext? =
            UnloadedToolchainContextImpl.builder(toolchainContextKey)
                .setExecutionPlatform(linuxPlatform)
                .setTargetPlatform(linuxPlatform)
                .setToolchainTypes(com.google.common.collect.ImmutableSet.of<E?>(variableToolchainType))
                .setRequestedLabelToToolchainType(
                    com.google.common.collect.ImmutableMap.of<K?, V?>(
                        variableToolchainTypeLabel,
                        variableToolchainTypeInfo
                    )
                )
                .setToolchainTypeToResolved(
                    com.google.common.collect.ImmutableSetMultimap.builder<ToolchainTypeInfo?, Label?>()
                        .put(
                            variableToolchainTypeInfo,
                            Label.parseCanonicalUnchecked("//variable:variable_toolchain_impl")
                        )
                        .build()
                )
                .build()

        // Create the prerequisites.
        val toolchain: ConfiguredTargetAndData =
            getConfiguredTargetAndData(
                Label.parseCanonicalUnchecked("//variable:variable_toolchain_impl"), targetConfig
            )
        val toolchainContext: ResolvedToolchainContext =
            ResolvedToolchainContext.load(
                unloadedToolchainContext,
                "test",
                com.google.common.collect.ImmutableSet.of<E?>(toolchain)
            )
        assertThat(toolchainContext).isNotNull()
        assertThat(toolchainContext).hasToolchainType(variableToolchainTypeLabel)
        assertThat(toolchainContext.templateVariableProviders()).hasSize(1)
        assertThat(toolchainContext.templateVariableProviders().get(0).getVariables())
            .containsExactly("VALUE", "foo")
    }
}
