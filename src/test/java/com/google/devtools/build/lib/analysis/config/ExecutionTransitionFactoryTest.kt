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
package com.google.devtools.build.lib.analysis.config

import com.google.devtools.build.lib.analysis.PlatformOptions

/** Tests for [ExecutionTransitionFactory].  */
@RunWith(TestParameterInjector::class)
class ExecutionTransitionFactoryTest : BuildViewTestCase() {
    @Throws(java.lang.Exception::class)
    private fun getExecTransition(execPlatform: Label?): PatchTransition {
        return ExecutionTransitionFactory.createFactory()
            .create(
                AttributeTransitionData.builder()
                    .attributes(FakeAttributeMapper.empty())
                    .analysisData(
                        getSkyframeExecutor()
                            .getStarlarkExecTransition(targetConfig.getOptions(), reporter)
                    )
                    .executionPlatform(execPlatform)
                    .build()
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun executionTransition() {
        val transition: PatchTransition = getExecTransition(EXECUTION_PLATFORM)
        assertThat(transition).isNotNull()

        // Apply the transition.
        val options: BuildOptions? =
            BuildOptions.of(
                targetConfig.getOptions().getFragmentClasses(), "--platforms=//platform:target"
            )

        val result: BuildOptions =
            transition.patch(
                BuildOptionsView(options, transition.requiresOptionFragments()),
                StoredEventHandler()
            )
        assertThat(result).isNotNull()
        assertThat(result).isNotSameInstanceAs(options)

        assertThat(result.get(CoreOptions::class.java).getIsExec()).isTrue()
        assertThat(result.get(PlatformOptions::class.java).getPlatforms())
            .containsExactly(EXECUTION_PLATFORM)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun executionTransition_noExecPlatform() {
        // No execution platform available.
        val transition: PatchTransition = getExecTransition(null)
        assertThat(transition).isNotNull()

        // Apply the transition.
        val options: BuildOptions? =
            BuildOptions.of(
                targetConfig.getOptions().getFragmentClasses(), "--platforms=//platform:target"
            )

        val result: BuildOptions? =
            transition.patch(
                BuildOptionsView(options, transition.requiresOptionFragments()),
                StoredEventHandler()
            )
        assertThat(result).isNotNull()
        assertThat(result).isEqualTo(options)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun executionTransitionOutputPathDistinguisher() {
        val transition: PatchTransition = getExecTransition(EXECUTION_PLATFORM)
        assertThat(transition).isNotNull()

        // Apply the transition.
        val options: BuildOptions? =
            BuildOptions.of(
                targetConfig.getOptions().getFragmentClasses(), "--platforms=//platform:target"
            )

        val result: BuildOptions =
            transition.patch(
                BuildOptionsView(options, transition.requiresOptionFragments()),
                StoredEventHandler()
            )

        assertThat(result.get(CoreOptions::class.java).getPlatformSuffix()).isEqualTo("exec")
    }

    @org.junit.Test
    @TestParameters(
        ("{cmdLineRef: 'gibberish', expectedError: 'Doesn''t match expected form"
                + " //pkg:file.bzl%%symbol'}"),
        ("{cmdLineRef: '//test:defs.bzl', expectedError: 'Doesn''t match expected form"
                + " //pkg:file.bzl%%symbol'}"),
        ("{cmdLineRef: '//test:defs.bzl%', expectedError: 'Doesn''t match expected form"
                + " //pkg:file.bzl%%symbol'}"),
        ("{cmdLineRef: '//test:defs.bzl%symbol_doesnt_exist', expectedError: 'symbol_doesnt_exist not"
                + " found in //test:defs.bzl'}"),
        ("{cmdLineRef: '//test:file_doesnt_exist.bzl%symbol', expectedError:"
                + " '''//test:file_doesnt_exist.bzl'': no such file'}"),
        ("{cmdLineRef: '//test:defs.bzl%not_a_transition', expectedError: 'not_a_transition is not a"
                + " Starlark transition.'}")
    )
    @Throws(java.lang.Exception::class)
    fun starlarkExecFlagBadReferences(cmdLineRef: String?, expectedError: String?) {
        scratch.file("test/defs.bzl", "not_a_transition = 4")
        scratch.file("test/BUILD")

        val e: InvalidConfigurationException? =
            org.junit.Assert.assertThrows<T?>(
                InvalidConfigurationException::class.java,
                org.junit.function.ThrowingRunnable { useConfiguration("--experimental_exec_config=" + cmdLineRef) })
        assertThat(e).hasMessageThat().contains(expectedError)
    }

    /** Checks all incompatible options propagate to the exec configuration.  */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun incompatibleOptionsPreservedInExec() {
        val defaultOptions: BuildOptions =
            BuildOptions.getDefaultBuildOptionsForFragments(
                targetConfig.getOptions().getFragmentClasses()
            )
        val optionInfoMap: com.google.common.collect.ImmutableMap<String?, OptionInfo?> =
            OptionInfo.buildMapFrom(defaultOptions)

        // Find all options with the INCOMPATIBLE_CHANGE metadata tag or start with "--incompatible_".
        val incompatibleOptions: com.google.common.collect.ImmutableMap<String?, OptionInfo?>? =
            optionInfoMap.entrySet().stream()
                .filter(
                    java.util.function.Predicate { o: MutableMap.MutableEntry<String?, OptionInfo?>? ->
                        o.getKey().startsWith("incompatible_")
                                || o.getValue().hasOptionMetadataTag(OptionMetadataTag.INCOMPATIBLE_CHANGE)
                    })
                .filter(java.util.function.Predicate { o: MutableMap.MutableEntry<String?, OptionInfo?>? ->
                    o.getValue().getDefinition().getType().isAssignableFrom(Boolean::class.javaPrimitiveType)
                })
                .filter(java.util.function.Predicate { o: MutableMap.MutableEntry<String?, OptionInfo?>? ->
                    !o.getValue().getDefinition().isDeprecated()
                }) // TODO: b/328442047 - Remove this when the flag is removed.
                .filter( // Skipping this explicitly because it is a no-op but can't be removed yet.
                    java.util.function.Predicate { o: MutableMap.MutableEntry<String?, OptionInfo?>? -> o.getKey() != "incompatible_enable_android_toolchain_resolution" })
        TODO(
            """
            |Cannot convert element
            |With text:
            |collect(<Map.Entry<String,OptionInfo>, String, OptionInfo>toImmutableMap(Map.Entry::getKey, Map.Entry::getValue)
            """.trimMargin()
        )


        // Verify all "--incompatible_*" options also have the INCOMPATIBLE_CHANGE metadata tag.
        val missingMetadataTagOptions: com.google.common.collect.ImmutableList<String?> =
            incompatibleOptions.values().stream()
                .filter(java.util.function.Predicate { o: OptionInfo? -> !o.hasOptionMetadataTag(OptionMetadataTag.INCOMPATIBLE_CHANGE) })
                .map<String?>(java.util.function.Function { o: OptionInfo? ->
                    "--" + o.getDefinition().getOptionName()
                })
                .collect(TODO("Cannot convert element"))<String> com . google . common . collect . ImmutableList . toImmutableList < kotlin . Any ? > ()

        Truth.assertThat(missingMetadataTagOptions).isEmpty()

        // Flip all incompatible (boolean) options to their non-default value.
        val flipped: BuildOptions = defaultOptions.clone() // To be flipped by below logic.
        for (option in incompatibleOptions.values()) {
            val fragment: FragmentOptions? = flipped.get(option.getOptionClass())
            val value: Boolean = option.getDefinition().getBooleanValue(fragment)
            option.getDefinition().setValue(fragment, !value)
        }

        // Fix the details of the exec transition so that the check passes.
        flipped
            .get(CoreOptions::class.java)
            .setStarlarkExecConfig(
                targetConfig.getOptions().get(CoreOptions::class.java).getStarlarkExecConfig()
            )

        val execTransition: PatchTransition = getExecTransition(EXECUTION_PLATFORM)
        val execOptions: BuildOptions =
            execTransition.patch(
                BuildOptionsView(flipped, execTransition.requiresOptionFragments()),
                StoredEventHandler()
            )

        // Find which incompatible options are different in the exec config (shouldn't be any).
        val unpreservedOptions: com.google.common.collect.ImmutableList.Builder<ChangedFlag?> =
            com.google.common.collect.ImmutableList.Builder<ChangedFlag?>()
        for (incompatibleOption in incompatibleOptions.values()) {
            val optionClass: java.lang.Class<out FragmentOptions?>? = incompatibleOption.getOptionClass()
            val execValue: Boolean =
                incompatibleOption.getDefinition().getBooleanValue(execOptions.get(optionClass))
            val flippedValue: Boolean =
                incompatibleOption.getDefinition().getBooleanValue(flipped.get(optionClass))
            if (execValue != flippedValue) {
                unpreservedOptions.add(
                    ChangedFlag(
                        incompatibleOption.getOptionClass().getName(),
                        incompatibleOption.getDefinition().getOptionName(),
                        flippedValue,
                        execValue
                    )
                )
            }
        }

        Truth.assertThat(unpreservedOptions.build()).isEmpty()
    }

    /** Store details of flags that have changed values unexpectedly.  */
    @kotlin.jvm.JvmRecord
    private data class ChangedFlag(fragment: String?, flag: String?, expectedValue: Any?, foundValue: Any?) {
        val fragment: String?
        val flag: String?
        val expectedValue: Any?
        val foundValue: Any?

        init {
            this.fragment = fragment
            this.flag = flag
            this.expectedValue = expectedValue
            this.foundValue = foundValue
        }
    }

    /** Checks all experimental options propagate to the exec configuration.  */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun experimentalOptionsPreservedInExec() {
        val defaultOptions: BuildOptions =
            BuildOptions.getDefaultBuildOptionsForFragments(
                targetConfig.getOptions().getFragmentClasses()
            )
        val optionInfoMap: com.google.common.collect.ImmutableMap<String?, OptionInfo?> =
            OptionInfo.buildMapFrom(defaultOptions)

        // Find all options with the EXPERIMENTAL metadata tag or that start with "--experimental_".
        val experimentalOptions: com.google.common.collect.ImmutableMap<String?, OptionInfo?>? =
            optionInfoMap.entrySet().stream()
                .filter(
                    java.util.function.Predicate { o: MutableMap.MutableEntry<String?, OptionInfo?>? ->
                        o.getKey().startsWith("experimental_")
                                || o.getValue().hasOptionMetadataTag(OptionMetadataTag.EXPERIMENTAL)
                    })
                .filter(java.util.function.Predicate { o: MutableMap.MutableEntry<String?, OptionInfo?>? ->
                    o.getValue().getDefinition().getType().isAssignableFrom(Boolean::class.javaPrimitiveType)
                })
                .filter(java.util.function.Predicate { o: MutableMap.MutableEntry<String?, OptionInfo?>? ->
                    !o.getValue().getDefinition().isDeprecated()
                }) // Skipping this explicitly as propagating it causes a cycle when compiling
                // the optimizer itself.
                .filter(java.util.function.Predicate { o: MutableMap.MutableEntry<String?, OptionInfo?>? -> o.getKey() != "experimental_local_java_optimizations" }) // A rare (only?) case of a flag named "--experimental_..." that isn't
                // actually experimental.
                .filter(java.util.function.Predicate { o: MutableMap.MutableEntry<String?, OptionInfo?>? -> o.getKey() != "experimental_deps_ok" })
        TODO(
            """
            |Cannot convert element
            |With text:
            |collect(<Map.Entry<String,OptionInfo>, String, OptionInfo>toImmutableMap(Map.Entry::getKey, Map.Entry::getValue)
            """.trimMargin()
        )


        // Verify all "--experimental_*" options also have the EXPERIMENTAL metadata tag.
        val missingMetadataTagOptions: com.google.common.collect.ImmutableList<String?> =
            experimentalOptions.values().stream()
                .filter(java.util.function.Predicate { o: OptionInfo? -> !o.hasOptionMetadataTag(OptionMetadataTag.EXPERIMENTAL) })
                .map<String?>(java.util.function.Function { o: OptionInfo? ->
                    "--" + o.getDefinition().getOptionName()
                })
                .collect(TODO("Cannot convert element"))<String> com . google . common . collect . ImmutableList . toImmutableList < kotlin . Any ? > ()

        Truth.assertThat(missingMetadataTagOptions).isEmpty()

        // Flip all experimental (boolean) options to their non-default value.
        val flipped: BuildOptions = defaultOptions.clone() // To be flipped by below logic.
        for (option in experimentalOptions.values()) {
            val fragment: FragmentOptions? = flipped.get(option.getOptionClass())
            val value: Boolean = option.getDefinition().getBooleanValue(fragment)
            option.getDefinition().setValue(fragment, !value)
        }

        // Fix the details of the exec transition so that the check passes.
        flipped
            .get(CoreOptions::class.java)
            .setStarlarkExecConfig(
                targetConfig.getOptions().get(CoreOptions::class.java).getStarlarkExecConfig()
            )

        val execTransition: PatchTransition = getExecTransition(EXECUTION_PLATFORM)
        val execOptions: BuildOptions =
            execTransition.patch(
                BuildOptionsView(flipped, execTransition.requiresOptionFragments()),
                StoredEventHandler()
            )

        // Find which experimental options are different in the exec config (shouldn't be any).
        val unpreservedOptions: com.google.common.collect.ImmutableList.Builder<ChangedFlag?> =
            com.google.common.collect.ImmutableList.Builder<ChangedFlag?>()
        for (experimentalOption in experimentalOptions.values()) {
            val optionClass: java.lang.Class<out FragmentOptions?>? = experimentalOption.getOptionClass()
            val execValue: Boolean =
                experimentalOption.getDefinition().getBooleanValue(execOptions.get(optionClass))
            val flippedValue: Boolean =
                experimentalOption.getDefinition().getBooleanValue(flipped.get(optionClass))
            if (execValue != flippedValue) {
                unpreservedOptions.add(
                    ChangedFlag(
                        experimentalOption.getOptionClass().getName(),
                        experimentalOption.getDefinition().getOptionName(),
                        flippedValue,
                        execValue
                    )
                )
            }
        }

        Truth.assertThat(unpreservedOptions.build()).isEmpty()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun platformInOutputPathWorksInExecMode() {
        scratch.file(
            "platforms/BUILD",
            """
        platform(name = "mock_platform")
        
        """.trimIndent()
        )
        scratch.file(
            "test/lib.bzl",
            """
        my_rule = rule(
            implementation = lambda ctx: [],
            attrs = {
                "exec_deps": attr.label_list(cfg = "exec"),
            },
        )
        
        """.trimIndent()
        )
        scratch.file(
            "test/BUILD",
            """
        load(":lib.bzl", "my_rule")
        my_rule(
            name = "parent",
            exec_deps = [":child"]
        )
        my_rule(name = "child")
        
        """.trimIndent()
        )

        useConfiguration(
            "--experimental_platform_in_output_dir",
            "--extra_execution_platforms=//platforms:mock_platform",
            "--experimental_override_name_platform_in_output_dir=//platforms:mock_platform=mock_platform_path_string"
        )
        val execConfig: BuildConfigurationValue =
            getConfiguration(
                getDirectPrerequisite(getConfiguredTarget("//test:parent"), "//test:child")
            )

        assertThat(execConfig.isExecConfiguration()).isTrue()
        assertThat(execConfig.getOutputDirectoryName()).isEqualTo("mock_platform_path_string-opt-exec")
    }

    companion object {
        private val EXECUTION_PLATFORM: Label? = Label.parseCanonicalUnchecked("//platform:exec")
    }
}
