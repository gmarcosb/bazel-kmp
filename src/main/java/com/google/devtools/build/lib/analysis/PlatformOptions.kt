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
package com.google.devtools.build.lib.analysis

import com.google.devtools.build.lib.analysis.config.CoreOptionConverters.LabelConverter

/** Command-line options for platform-related configuration.  */
@com.google.devtools.common.options.OptionsClass
abstract class PlatformOptions : FragmentOptions() {
    @kotlin.jvm.JvmField
    @get:com.google.devtools.common.options.Option(
        name = "host_platform",
        oldName = "experimental_host_platform",
        converter = HostPlatformConverter::class,
        defaultValue = DEFAULT_HOST_PLATFORM,
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.TOOLCHAIN,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.AFFECTS_OUTPUTS, com.google.devtools.common.options.OptionEffectTag.CHANGES_INPUTS, com.google.devtools.common.options.OptionEffectTag.LOADING_AND_ANALYSIS
        ],
        help = "The label of a platform rule that describes the host system."
    )
    abstract val hostPlatform: com.google.devtools.build.lib.cmdline.Label?

    abstract fun setHostPlatform(value: com.google.devtools.build.lib.cmdline.Label?)

    @get:com.google.devtools.common.options.Option(
        name = "extra_execution_platforms",
        converter = com.google.devtools.common.options.Converters.CommaSeparatedOptionListConverter::class,
        defaultValue = "",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.TOOLCHAIN,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.EXECUTION],
        help = """
          The platforms that are available as execution platforms to run actions.
          Platforms can be specified by exact target, or as a target pattern.
          These platforms will be considered before those declared in the `WORKSPACE` file by
          `register_execution_platforms()`. This option may only be set once; later
          instances will override earlier flag settings.
          
          """.trimIndent()
    )
    abstract val extraExecutionPlatforms: MutableList<String?>?

    @get:com.google.devtools.common.options.Option(
        name = "platforms",
        oldName = "experimental_platforms",
        converter = LabelListConverter::class,
        defaultValue = "",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.TOOLCHAIN,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.AFFECTS_OUTPUTS, com.google.devtools.common.options.OptionEffectTag.CHANGES_INPUTS, com.google.devtools.common.options.OptionEffectTag.LOADING_AND_ANALYSIS
        ],
        help = ("The labels of the platform rules describing the target platforms for the current "
                + "command.")
    )
    abstract val platforms: MutableList<com.google.devtools.build.lib.cmdline.Label>?

    abstract fun setPlatforms(value: MutableList<com.google.devtools.build.lib.cmdline.Label?>?)

    @get:com.google.devtools.common.options.Option(
        name = "extra_toolchains",
        defaultValue = "null",
        converter = com.google.devtools.common.options.Converters.CommaSeparatedOptionListConverter::class,
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.TOOLCHAIN,
        allowMultiple = true,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.AFFECTS_OUTPUTS, com.google.devtools.common.options.OptionEffectTag.CHANGES_INPUTS, com.google.devtools.common.options.OptionEffectTag.LOADING_AND_ANALYSIS
        ],
        help = """
          The toolchain rules to be considered during toolchain resolution.
          Toolchains can be specified by exact target, or as a target pattern.
          These toolchains will be considered before those declared in the `WORKSPACE` file
          by `register_toolchains()`.
          
          """.trimIndent()
    )
    abstract var extraToolchains: MutableList<String?>?

    @get:com.google.devtools.common.options.Option(
        name = "toolchain_resolution_debug",
        defaultValue = "-.*",
        converter = RegexFilterConverter::class,
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.LOGGING,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.TERMINAL_OUTPUT],
        help = ("Print debug information during toolchain resolution. The flag takes a regex, which is"
                + " checked against toolchain types and specific targets to see which to debug. "
                + "Multiple regexes may be  separated by commas, and then each regex is checked "
                + "separately. Note: The output of this flag is very complex and will likely only be "
                + "useful to experts in toolchain resolution.")
    )
    abstract val toolchainResolutionDebug: com.google.devtools.build.lib.util.RegexFilter?

    @get:com.google.devtools.common.options.Option(
        name = "incompatible_use_toolchain_resolution_for_java_rules",
        defaultValue = "true",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNDOCUMENTED,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.UNKNOWN],
        metadataTags = [com.google.devtools.common.options.OptionMetadataTag.INCOMPATIBLE_CHANGE],
        help = "No-op. Kept here for backwards compatibility."
    )
    abstract val useToolchainResolutionForJavaRules: Boolean

    @kotlin.jvm.JvmField
    @get:com.google.devtools.common.options.Option(
        name = "platform_mappings",
        converter = PlatformMappingKeyConverter::class,
        defaultValue = "",
        documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.TOOLCHAIN,
        effectTags = [com.google.devtools.common.options.OptionEffectTag.AFFECTS_OUTPUTS, com.google.devtools.common.options.OptionEffectTag.CHANGES_INPUTS, com.google.devtools.common.options.OptionEffectTag.LOADING_AND_ANALYSIS
        ],
        metadataTags = [com.google.devtools.common.options.OptionMetadataTag.NON_CONFIGURABLE
        ],
        help = """
          The location of a mapping file that describes which platform to use if none is set or
          which flags to set when a platform already exists. Must be relative to the main
          workspace root. Defaults to `platform_mappings` (a file directly under the
          workspace root).
          
          """.trimIndent()
    )
    abstract val platformMappingKey: PlatformMappingKey?

    val normalized: PlatformOptions
        get() {
            val result = clone() as PlatformOptions
            result.extraToolchains = dedupeKeepingLast(
                if (result.extraToolchains == null)
                    com.google.common.collect.ImmutableList.of<String?>()
                else
                    com.google.common.collect.ImmutableList.copyOf<String?>(result.extraToolchains)
            )
            // Only the first entry of platforms is used (it should have been Label and not List<Label>)
            // So drop all but the first entry.
            if (result.platforms!!.size > 1) {
                result.setPlatforms(
                    com.google.common.collect.ImmutableList.of<com.google.devtools.build.lib.cmdline.Label?>(
                        result.platforms!!.get(0)
                    )
                )
            }
            return result
        }

    /** Returns the intended target platform value based on options defined in this fragment.  */
    fun computeTargetPlatform(): com.google.devtools.build.lib.cmdline.Label? {
        if (!this.platforms!!.isEmpty()) {
            return com.google.common.collect.Iterables.getFirst<com.google.devtools.build.lib.cmdline.Label?>(
                this.platforms,
                null
            )
        } else {
            // Default to the host platform, whatever it is.
            return this.hostPlatform
        }
    }

    /**
     * Converter for `--host_platform` that returns the default host platform if the flag is set
     * to empty string.
     */
    private class HostPlatformConverter : LabelConverter() {
        @Throws(com.google.devtools.common.options.OptionsParsingException::class)
        public override fun convert(
            input: String,
            conversionContext: Any?
        ): com.google.devtools.build.lib.cmdline.Label? {
            if (input.isEmpty()) {
                return super.convert(DEFAULT_HOST_PLATFORM, conversionContext)
            }
            return super.convert(input, conversionContext)
        }
    }

    /**
     * Converter for `--platform_mappings` that creates a canonical [PlatformMappingKey]
     * for the build.
     */
    private class PlatformMappingKeyConverter : com.google.devtools.common.options.Converter<PlatformMappingKey?> {
        private val pathConverter: OptionsUtils.PathFragmentConverter = OptionsUtils.PathFragmentConverter()

        @Throws(com.google.devtools.common.options.OptionsParsingException::class)
        override fun convert(input: String, conversionContext: Any?): PlatformMappingKey? {
            if (input.isEmpty()) {
                return PlatformMappingKey.DEFAULT
            }
            val path: PathFragment = pathConverter.convert(input)
            if (path.isAbsolute()) {
                throw com.google.devtools.common.options.OptionsParsingException("Expected relative path but got '" + input + "'.")
            }
            return PlatformMappingKey.createExplicitlySet(path)
        }

        override fun starlarkConvertible(): Boolean {
            return true
        }

        override fun reverseForStarlark(converted: Any?): String? {
            val key: PlatformMappingKey = converted as PlatformMappingKey
            return if (key == PlatformMappingKey.DEFAULT)
                ""
            else
                key.getWorkspaceRelativeMappingPath().getPathString()
        }

        val typeDescription: String
            get() = "a main workspace-relative path"
    }

    companion object {
        private val DEFAULT_PLATFORM_NAMES: com.google.common.collect.ImmutableSet<String?> =
            com.google.common.collect.ImmutableSet.of<String?>(
                "host",
                "host_platform",
                "target_platform",
                "default_host",
                "default_target"
            )

        const val DEFAULT_HOST_PLATFORM: String = "@bazel_tools//tools:host_platform"

        fun platformIsDefault(platform: com.google.devtools.build.lib.cmdline.Label): Boolean {
            return DEFAULT_PLATFORM_NAMES.contains(platform.getName())
        }

        /**
         * Deduplicate the given list, keeping the last copy of any duplicates.
         * 
         * 
         * Example: [a, b, a, c, b] -> [a, c, b]
         */
        private fun dedupeKeepingLast(values: com.google.common.collect.ImmutableList<String?>): com.google.common.collect.ImmutableList<String?> {
            // Check common cases.
            if (values.size <= 1) {
                return values
            }

            // Reverse the list and then deduplicate.
            val reversedResult: com.google.common.collect.ImmutableList<String?> =
                values.reverse().stream().distinct()
                    .collect(com.google.common.collect.ImmutableList.toImmutableList<String?>())

            // If there were no duplicates, return the exact same instance we got.
            if (reversedResult.size == values.size) {
                return values
            }

            // Reverse the result to get back to the original order.
            return reversedResult.reverse()
        }
    }
}
