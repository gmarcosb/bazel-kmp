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
package com.google.devtools.build.lib.skyframe.config

import com.google.devtools.build.lib.analysis.PlatformOptions

/** Unit tests for [PlatformMappingValue].  */
@RunWith(JUnit4::class)
class PlatformMappingValueTest {
    /** Extra options for this test.  */
    @OptionsClass
    abstract class DummyTestOptions : FragmentOptions() {
        @get:Option(
            name = "str_option",
            documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
            effectTags = [OptionEffectTag.NO_OP],
            defaultValue = "defVal"
        )
        abstract val strOption: String?

        @get:Option(
            name = "other_str_option",
            documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
            effectTags = [OptionEffectTag.NO_OP],
            defaultValue = "defVal"
        )
        abstract val otherStrOption: String?
    }

    private class PlatformMappingBuilder {
        private val platformsToFlags: MutableMap<Label?, ParsedFlagsValue?> = HashMap<Label?, ParsedFlagsValue?>()
        private val flagsToPlatforms: MutableMap<ParsedFlagsValue?, Label?> = HashMap<ParsedFlagsValue?, Label?>()

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun addPlatform(platform: Label?, flags: ParsedFlagsValue?): PlatformMappingBuilder {
            this.platformsToFlags.put(platform, flags)
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        @Throws(OptionsParsingException::class)
        fun addPlatform(platform: Label?, vararg nativeFlags: String?): PlatformMappingBuilder {
            return this.addPlatform(platform, createFlags(*nativeFlags))
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun addFlags(flags: ParsedFlagsValue?, platform: Label?): PlatformMappingBuilder {
            this.flagsToPlatforms.put(flags, platform)
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        @Throws(OptionsParsingException::class)
        fun addFlags(platform: Label?, vararg nativeFlags: String?): PlatformMappingBuilder {
            return this.addFlags(createFlags(*nativeFlags), platform)
        }

        fun build(): PlatformMappingValue {
            return PlatformMappingValue(
                com.google.common.collect.ImmutableMap.< K, V > copyOf<K?, V?>(platformsToFlags),
                com.google.common.collect.ImmutableMap.< K, V > copyOf<K?, V?>(flagsToPlatforms),
                BUILD_CONFIG_OPTIONS
            )
        }

        companion object {
            @Throws(OptionsParsingException::class)
            private fun createFlags(vararg nativeFlags: String?): ParsedFlagsValue {
                val flags: NativeAndStarlarkFlags? =
                    NativeAndStarlarkFlags.builder()
                        .nativeFlags(com.google.common.collect.ImmutableList.< E > copyOf < E ? > (nativeFlags))
                        .optionsClasses(BUILD_CONFIG_OPTIONS)
                        .repoMapping(REPO_MAPPING)
                        .build()
                return ParsedFlagsValue.parseAndCreate(flags)
            }
        }
    }

    @org.junit.Test
    @Throws(OptionsParsingException::class)
    fun map_noMappings() {
        val mappingValue: PlatformMappingValue = builder().build()

        val mapped: BuildOptions = mappingValue.map(createBuildOptions()).getOptions()

        assertThat(mapped.get(PlatformOptions::class.java).getPlatforms())
            .containsExactly(DEFAULT_TARGET_PLATFORM)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun map_platformToFlags() {
        val mappingValue: PlatformMappingValue =
            builder().addPlatform(PLATFORM_ONE, "--str_option=one", "--other_str_option=dbg").build()

        val modifiedOptions: BuildOptions = createBuildOptions("--platforms=//platforms:one")

        val mapped: BuildOptions = mappingValue.map(modifiedOptions).getOptions()

        assertThat(
            mapped.get(com.google.devtools.build.lib.skyframe.config.PlatformMappingValueTest.DummyTestOptions::class.java)
                .getStrOption()
        ).isEqualTo("one")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun map_flagsToPlatform() {
        val mappingValue: PlatformMappingValue =
            builder().addFlags(PLATFORM_ONE, "--str_option=one", "--other_str_option=dbg").build()

        val modifiedOptions: BuildOptions = createBuildOptions("--str_option=one", "--other_str_option=dbg")
        val mapped: BuildOptions = mappingValue.map(modifiedOptions).getOptions()

        assertThat(mapped.get(PlatformOptions::class.java).getPlatforms()).containsExactly(PLATFORM_ONE)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun map_flagsToPlatform_checkPriority() {
        val mappingValue: PlatformMappingValue =
            builder()
                .addFlags(PLATFORM_ONE, "--str_option=one", "--other_str_option=dbg")
                .addFlags(PLATFORM_TWO, "--str_option=two")
                .build()

        val modifiedOptions: BuildOptions = createBuildOptions("--str_option=two")

        val mapped: BuildOptions = mappingValue.map(modifiedOptions).getOptions()

        assertThat(mapped.get(PlatformOptions::class.java).getPlatforms()).containsExactly(PLATFORM_TWO)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun map_flagsToPlatform_noneMatching() {
        val mappingValue: PlatformMappingValue =
            builder().addFlags(PLATFORM_ONE, "--str_option=foo", "--other_str_option=dbg").build()

        val modifiedOptions: BuildOptions = createBuildOptions("--str_option=bar")

        val mapped: BuildOptions = mappingValue.map(modifiedOptions).getOptions()

        assertThat(mapped.get(PlatformOptions::class.java).getPlatforms())
            .containsExactly(DEFAULT_TARGET_PLATFORM)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun map_noPlatformOptions() {
        val mappingValue: PlatformMappingValue = builder().build()

        // Does not contain PlatformOptions.
        val options: BuildOptions? = BuildOptions.of(com.google.common.collect.ImmutableList.of<E?>())
        org.junit.Assert.assertThrows<java.lang.IllegalArgumentException?>(
            java.lang.IllegalArgumentException::class.java,
            org.junit.function.ThrowingRunnable { mappingValue.map(options) })
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun map_noMappingIfPlatformIsSetButNotMatching() {
        val mappingValue: PlatformMappingValue =
            builder() // Add a mapping for a different platform.
                .addPlatform(PLATFORM_ONE, "--str_option=one", "--other_str_option=dbg")
                .build()

        val modifiedOptions: BuildOptions =
            createBuildOptions("--str_option=one", "--platforms=//platforms:two")
        val mapped: BuildOptions? = mappingValue.map(modifiedOptions).getOptions()

        // No change because the platform is not in the mapping.
        assertThat(modifiedOptions).isEqualTo(mapped)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun map_noMappingIfPlatformIsSetAndNoPlatformMapping() {
        val mappingValue: PlatformMappingValue =
            builder() // Add a flag mapping that would match.
                .addFlags(PLATFORM_ONE, "--str_option=one")
                .build()

        val modifiedOptions: BuildOptions =
            createBuildOptions("--str_option=one", "--platforms=//platforms:two")

        val mapped: BuildOptions? = mappingValue.map(modifiedOptions).getOptions()

        // No change because the platform is not in the mapping.
        assertThat(modifiedOptions).isEqualTo(mapped)
    }

    companion object {
        private val PLATFORM_ONE: Label? = Label.parseCanonicalUnchecked("//platforms:one")
        private val PLATFORM_TWO: Label? = Label.parseCanonicalUnchecked("@dep+1.0//platforms:two")
        private val REPO_MAPPING: RepositoryMapping? = RepositoryMapping.create(
            com.google.common.collect.ImmutableMap.of<K?, V?>(
                "", RepositoryName.MAIN, "dep", RepositoryName.createUnvalidated("dep+1.0")
            ),
            RepositoryName.MAIN
        )
        private val DEFAULT_TARGET_PLATFORM: Label? = Label.parseCanonicalUnchecked("@bazel_tools//tools:host_platform")

        private val BUILD_CONFIG_OPTIONS: com.google.common.collect.ImmutableSet<java.lang.Class<out FragmentOptions?>?> =
            // PlatformOptions is required for mapping.
            com.google.common.collect.ImmutableSet.of<E?>(
                PlatformOptions::class.java,
                com.google.devtools.build.lib.skyframe.config.PlatformMappingValueTest.DummyTestOptions::class.java
            )

        @Throws(OptionsParsingException::class)
        private fun createBuildOptions(vararg args: String?): BuildOptions {
            return BuildOptions.of(BUILD_CONFIG_OPTIONS, args)
        }

        private fun builder(): PlatformMappingBuilder {
            return PlatformMappingBuilder()
        }

        /**
         * Caching of option default values does not consider conversion context (b/365420093). Parse the
         * default [PlatformOptions] up front with no conversion context so that the default value
         * of [PlatformOptions.hostPlatform] is deterministic.
         */
        // TODO: b/365420093 - Remove this workaround when the bug is fixed.
        @BeforeClass
        fun computeDefaultPlatformOptions() {
            val unused: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
                Options.getDefaults(PlatformOptions::class.java)
        }
    }
}
