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

/**
 * Stores contents of a platforms/flags mapping file for transforming one [BuildOptions] into
 * another.
 * 
 * 
 * See [
 * the design](https://docs.google.com/document/d/1Vg_tPgiZbSrvXcJ403vZVAGlsWhH9BUDrAxMOYnO0Ls) for more details on how the mapping can be defined and the desired logic on how it
 * is applied to configuration keys.
 */
@AutoCodec
class PlatformMappingValue internal constructor(
    platformsToFlags: com.google.common.collect.ImmutableMap<Label?, ParsedFlagsValue?>?,
    flagsToPlatforms: com.google.common.collect.ImmutableMap<ParsedFlagsValue?, Label?>?,
    optionsClasses: com.google.common.collect.ImmutableSet<java.lang.Class<out FragmentOptions?>?>?
) : SkyValue {
    private val platformsToFlags: com.google.common.collect.ImmutableMap<Label?, ParsedFlagsValue?>? = null
    private val flagsToPlatforms: com.google.common.collect.ImmutableMap<ParsedFlagsValue?, Label?>? = null
    private val optionsClasses: com.google.common.collect.ImmutableSet<java.lang.Class<out FragmentOptions?>?>
    private val mappingCache: com.github.benmanes.caffeine.cache.LoadingCache<BuildOptions?, BuildConfigurationKey?>

    /**
     * Creates a new mapping value which will match on the given platforms (if a target platform is
     * set on the key to be mapped), otherwise on the set of flags.
     * 
     * @param platformsToFlags mapping from target platform label to the command line style flags that
     * should be parsed & modified if that platform is set
     * @param flagsToPlatforms mapping from a set of command line style flags to a target platform
     * that should be set if the flags match the mapped options
     * @param optionsClasses default options classes that should be used for options parsing
     */
    init {
        TODO(
            """
            |Cannot convert element
            |With text:
            |this.platformsToFlags = <ImmutableMap<Label, ParsedFlagsValue>>checkNotNull(platformsToFlags);
            """.trimMargin()
        )
        TODO(
            """
            |Cannot convert element
            |With text:
            |this.flagsToPlatforms = <ImmutableMap<ParsedFlagsValue,Label>>checkNotNull(flagsToPlatforms);
            """.trimMargin()
        )
        if (.also { this.optionsClasses = it } < ImmutableSet < Class)
        FragmentOptions ushr com.google.common.base.Preconditions.checkNotNull<com.google.common.collect.ImmutableSet<java.lang.Class<out FragmentOptions?>?>?>(
            optionsClasses
        )
        this.mappingCache = Caffeine.newBuilder()
            .build<BuildOptions?, BuildConfigurationKey?>(com.github.benmanes.caffeine.cache.CacheLoader { originalOptions: BuildOptions? ->
                this.computeMapping(originalOptions)
            })
    }

    /**
     * Maps one [BuildOptions] to another's [BuildConfigurationKey] by way of mappings
     * provided in a file.
     * 
     * 
     * Returns a [BuildConfigurationKey] instead of just [BuildOptions] so that caching
     * of mappings also saves the CPU cost of interning [BuildConfigurationKey] (which is what
     * callers typically need).
     * 
     * 
     * The [
     * full design](https://docs.google.com/document/d/1Vg_tPgiZbSrvXcJ403vZVAGlsWhH9BUDrAxMOYnO0Ls) contains the details for the mapping logic but in short:
     * 
     * 
     *  1. If a target platform is set on the original then mappings from platform to flags will be
     * applied.
     *  1. If no target platform is set then mappings from flags to platforms will be applied.
     *  1. If no matching flags to platforms mapping was found, the default target platform will be
     * used.
     * 
     * 
     * @param original the key representing the configuration to be mapped
     * @return a [BuildConfigurationKey] to request the mapped configuration
     * @throws OptionsParsingException if any of the user configured flags cannot be parsed
     * @throws IllegalArgumentException if the original does not contain a [PlatformOptions]
     * fragment
     */
    @Throws(com.google.devtools.common.options.OptionsParsingException::class)
    fun map(original: BuildOptions?): BuildConfigurationKey? {
        try {
            return mappingCache.get(original)
        } catch (e: CompletionException) {
            com.google.common.base.Throwables.throwIfInstanceOf<com.google.devtools.common.options.OptionsParsingException?>(
                e.getCause(),
                com.google.devtools.common.options.OptionsParsingException::class.java
            )
            com.google.common.base.Throwables.throwIfUnchecked(e.getCause())
            throw e
        }
    }

    /** Clears the mapping cache to save memory.  */
    fun clearMappingCache() {
        mappingCache.invalidateAll()
    }

    @Throws(com.google.devtools.common.options.OptionsParsingException::class)
    private fun computeMapping(originalOptions: BuildOptions): BuildConfigurationKey? {
        if (originalOptions.hasNoConfig()) {
            // The empty configuration (produced by NoConfigTransition) is terminal: it'll never change.
            return BuildConfigurationKey.Companion.create(originalOptions)
        }

        val platformOptions: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            originalOptions.get(PlatformOptions::class.java)
        com.google.common.base.Preconditions.checkArgument(
            platformOptions != null,
            "When using platform mappings, all configurations must contain platform options"
        )

        if (!platformOptions.getPlatforms().isEmpty()) {
            val platforms: MutableList<Label?> = platformOptions.getPlatforms()

            // Platform mapping only supports a single target platform, others are ignored.
            val targetPlatform: Label? = com.google.common.collect.Iterables.getFirst<Label?>(platforms, null)
            if (!platformsToFlags.containsKey(targetPlatform)) {
                // This can happen if the user has set the platform and any other flags that would normally
                // be mapped from it on the command line instead of relying on the mapping.
                return BuildConfigurationKey.Companion.create(originalOptions)
            }

            val parsedFlags: ParsedFlagsValue? = platformsToFlags.get(targetPlatform)
            return parsedFlags.mergeWith(originalOptions)
        }

        for (flagsToPlatform in flagsToPlatforms.entrySet()) {
            val parsedFlags: ParsedFlagsValue = flagsToPlatform.getKey()
            val platformLabel: Label = flagsToPlatform.getValue()
            if (originalOptions.matches(parsedFlags.parsingResult())) {
                val modifiedOptions: BuildOptions = originalOptions.clone()
                modifiedOptions.get(PlatformOptions::class.java)
                    .setPlatforms(com.google.common.collect.ImmutableList.of<E?>(platformLabel))
                return BuildConfigurationKey.Companion.create(modifiedOptions)
            }
        }

        // No mapping found.
        val targetPlatform: Label = platformOptions.computeTargetPlatform()
        val modifiedOptions: BuildOptions = originalOptions.clone()
        modifiedOptions.get(PlatformOptions::class.java)
            .setPlatforms(com.google.common.collect.ImmutableList.of<E?>(targetPlatform))
        return BuildConfigurationKey.Companion.create(modifiedOptions)
    }

    override fun equals(obj: Any?): Boolean {
        if (this === obj) {
            return true
        }
        if (obj !is PlatformMappingValue) {
            return false
        }
        return this.flagsToPlatforms == obj.flagsToPlatforms
                && this.platformsToFlags == obj.platformsToFlags
                && this.optionsClasses == obj.optionsClasses
    }

    override fun hashCode(): Int {
        return HashCodes.hashObjects(flagsToPlatforms, platformsToFlags, optionsClasses)
    }

    override fun toString(): String {
        return com.google.common.base.MoreObjects.toStringHelper(this)
            .add("flagsToPlatforms", flagsToPlatforms)
            .add("platformsToFlags", platformsToFlags)
            .add("optionsClasses", optionsClasses)
            .toString()
    }
}
