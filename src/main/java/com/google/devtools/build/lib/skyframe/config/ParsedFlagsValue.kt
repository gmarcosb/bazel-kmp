// Copyright 2024 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.analysis.config.BuildOptions

/** Stores the [OptionsParsingResult] from [ParsedFlagsFunction].  */
@AutoCodec
class ParsedFlagsValue private constructor(
    flags: NativeAndStarlarkFlags?,
    parsingResult: com.google.devtools.common.options.OptionsParsingResult?
) : SkyValue {
    /** Key for [ParsedFlagsValue] based on the raw flags.  */
    @ThreadSafety.Immutable
    @AutoCodec
    class Key private constructor(
        rawFlags: com.google.common.collect.ImmutableList<String?>?,
        packageContext: PackageContext?,
        private val includeDefaultValues: Boolean,
        flagAliasMappings: com.google.common.collect.ImmutableMap<String?, Label?>?
    ) : SkyKey {
        private val rawFlags: com.google.common.collect.ImmutableList<String?>
        private val packageContext: PackageContext

        private val flagAliasMappings: com.google.common.collect.ImmutableMap<String?, Label?>?

        init {
            this.rawFlags =
                com.google.common.base.Preconditions.checkNotNull<com.google.common.collect.ImmutableList<String?>>(
                    rawFlags
                )
            this.packageContext = com.google.common.base.Preconditions.checkNotNull<PackageContext>(packageContext)
            this.flagAliasMappings = flagAliasMappings
        }

        fun rawFlags(): com.google.common.collect.ImmutableList<String?> {
            return rawFlags
        }

        fun packageContext(): PackageContext {
            return packageContext
        }

        fun includeDefaultValues(): Boolean {
            return includeDefaultValues
        }

        fun flagAliasMappings(): com.google.common.collect.ImmutableMap<String?, Label?>? {
            return flagAliasMappings
        }

        override fun functionName(): SkyFunctionName {
            return SkyFunctions.PARSED_FLAGS
        }

        override fun equals(o: Any?): Boolean {
            if (this === o) {
                return true
            }
            if (o !is Key) {
                return false
            }
            return rawFlags == o.rawFlags
                    && packageContext.equals(o.packageContext)
                    && includeDefaultValues == o.includeDefaultValues
        }

        override fun hashCode(): Int {
            return (HashCodes.hashObjects(rawFlags, packageContext) * 31
                    + java.lang.Boolean.hashCode(includeDefaultValues))
        }

        override fun toString(): String {
            return com.google.common.base.MoreObjects.toStringHelper("ParsedFlagsValue.Key")
                .add("rawFlags", rawFlags)
                .add("packageContext", packageContext)
                .add("includeDefaultValues", includeDefaultValues)
                .toString()
        }

        val skyKeyInterner: SkyKeyInterner<Key?>
            get() = com.google.devtools.build.lib.skyframe.config.ParsedFlagsValue.Key.Companion.interner

        companion object {
            private val interner: SkyKeyInterner<Key?> = SkyKey.newInterner<Key?>()

            /**
             * Returns a new [Key] for the given command-line flags, such as `--compilation_mode=bdg` or `--//custom/starlark:flag=23`.
             */
            fun create(
                rawFlags: com.google.common.collect.ImmutableList<String?>?,
                packageContext: PackageContext?,
                flagAliasMappings: com.google.common.collect.ImmutableMap<String?, Label?>?
            ): Key {
                return com.google.devtools.build.lib.skyframe.config.ParsedFlagsValue.Key.Companion.create(
                    rawFlags,
                    packageContext,  /* includeDefaultValues= */
                    false,
                    flagAliasMappings
                )
            }

            /**
             * Returns a new [Key] for the given command-line flags, such as `--compilation_mode=bdg` or `--//custom/starlark:flag=23`.
             */
            @AutoCodec.Instantiator
            fun create(
                rawFlags: com.google.common.collect.ImmutableList<String?>?,
                packageContext: PackageContext?,
                includeDefaultValues: Boolean,
                flagAliasMappings: com.google.common.collect.ImmutableMap<String?, Label?>?
            ): Key {
                return com.google.devtools.build.lib.skyframe.config.ParsedFlagsValue.Key.Companion.interner.intern(
                    com.google.devtools.build.lib.skyframe.config.ParsedFlagsValue.Key(
                        rawFlags,
                        packageContext,
                        includeDefaultValues,
                        flagAliasMappings
                    )
                )
            }
        }
    }

    private val flags: NativeAndStarlarkFlags
    private val parsingResult: com.google.devtools.common.options.OptionsParsingResult
    private val mergeCache: com.github.benmanes.caffeine.cache.LoadingCache<BuildOptions?, BuildConfigurationKey?> =
        Caffeine.newBuilder().weakKeys()
            .build<BuildOptions?, BuildConfigurationKey?>(com.github.benmanes.caffeine.cache.CacheLoader { source: BuildOptions? ->
                this.mergeWithImpl(source)
            })

    init {
        this.parsingResult =
            com.google.common.base.Preconditions.checkNotNull<com.google.devtools.common.options.OptionsParsingResult>(
                parsingResult
            )
        this.flags = com.google.common.base.Preconditions.checkNotNull<NativeAndStarlarkFlags>(flags)
    }

    fun parsingResult(): com.google.devtools.common.options.OptionsParsingResult {
        return parsingResult
    }

    /**
     * Returns a new [BuildConfigurationKey] with options containing all flags from the given
     * [BuildOptions] with [.parsingResult] merged in.
     * 
     * 
     * Returns a [BuildConfigurationKey] instead of just [BuildOptions] so that caching
     * of mappings also saves the CPU cost of interning [BuildConfigurationKey] (which is what
     * callers typically need).
     * 
     * 
     * The merging logic is as follows:
     * 
     * 
     *  * For native flags, only the fragments in the original [BuildOptions] are kept.
     *  * Any native flags in this instance, for fragments that are kept, are set to the value from
     * this instance.
     *  * All Starlark flags from the original [BuildOptions] are kept, then all Starlark
     * options from this instance are added.
     *  * Any Starlark flags which are present in both, the value from this instance is kept.
     * 
     * 
     * 
     * To preserve fragment trimming, this method will not expand the set of included native
     * fragments from the original [BuildOptions]. If the parsing result contains native options
     * whose owning fragment is not part of the original [BuildOptions] they will be ignored
     * (i.e. not set on the resulting options). Starlark options are not affected by this restriction.
     * 
     * @param source the base options to modify
     * @return a [BuildConfigurationKey] to request the new configuration after applying this
     * parsed flags value to the original options
     */
    fun mergeWith(source: BuildOptions?): BuildConfigurationKey? {
        return mergeCache.get(source)
    }

    private fun mergeWithImpl(source: BuildOptions): BuildConfigurationKey? {
        val builder: BuildOptions.Builder = source.toBuilder()

        // Handle native options.
        for (optionValue in parsingResult.allOptionValues()) {
            val optionDefinition: com.google.devtools.common.options.OptionDefinition =
                optionValue.getOptionDefinition()
            // All options obtained from an options parser are guaranteed to have been defined in an
            // FragmentOptions class.
            val fragmentOptionClass: java.lang.Class<out FragmentOptions?>? =
                optionDefinition.getDeclaringClass<C?>(FragmentOptions::class.java)

            val fragment: FragmentOptions? = builder.getFragmentOptions(fragmentOptionClass)
            if (fragment == null) {
                // Preserve trimming by ignoring fragments not present in the original options.
                continue
            }
            updateOptionValue(fragment, optionDefinition, optionValue)
        }

        // Also copy Starlark options.
        for (starlarkOption in parsingResult.getStarlarkOptions().entrySet()) {
            updateStarlarkFlag(builder, starlarkOption.getKey(), starlarkOption.getValue())
        }

        return BuildConfigurationKey.Companion.create(builder.addScopeTypeMap(source.getScopeTypeMap()).build())
    }

    private fun updateStarlarkFlag(
        builder: BuildOptions.Builder, rawFlagName: String?, rawFlagValue: Any?
    ) {
        val flagName: Label? = Label.parseCanonicalUnchecked(rawFlagName)
        // If the known default value is the same as the new value, unset it.
        if (isStarlarkFlagSetToDefault(rawFlagName, rawFlagValue)) {
            builder.removeStarlarkOption(flagName)
        } else {
            builder.addStarlarkOption(flagName, rawFlagValue)
        }
    }

    private fun isStarlarkFlagSetToDefault(rawFlagName: String?, rawFlagValue: Any?): Boolean {
        val defaultVal: Any? = flags.starlarkFlagDefaults().get(rawFlagName)
        return defaultVal != null && defaultVal == rawFlagValue
    }

    override fun equals(obj: Any?): Boolean {
        if (this === obj) {
            return true
        }
        if (obj !is ParsedFlagsValue) {
            return false
        }
        return flags == obj.flags
    }

    override fun hashCode(): Int {
        return flags.hashCode()
    }

    override fun toString(): String {
        return com.google.common.base.MoreObjects.toStringHelper(this)
            .add("flags", flags)
            .add("parsingResult", parsingResult)
            .toString()
    }

    companion object {
        @Throws(com.google.devtools.common.options.OptionsParsingException::class)
        fun parseAndCreate(flags: NativeAndStarlarkFlags): ParsedFlagsValue {
            return ParsedFlagsValue(flags, flags.parse())
        }

        @AutoCodec.Instantiator
        @com.google.devtools.build.lib.skyframe.serialization.VisibleForSerialization
        fun createForDeserialization(flags: NativeAndStarlarkFlags): ParsedFlagsValue {
            try {
                return parseAndCreate(flags)
            } catch (e: com.google.devtools.common.options.OptionsParsingException) {
                // Should be impossible since it parsed successfully before it was serialized.
                throw java.lang.IllegalStateException(e)
            }
        }

        private fun updateOptionValue(
            fragment: FragmentOptions?,
            optionDefinition: com.google.devtools.common.options.OptionDefinition,
            optionValue: com.google.devtools.common.options.OptionValueDescription
        ) {
            // TODO: https://github.com/bazelbuild/bazel/issues/22453 - This will completely overwrite
            //  accumulating flags, which is almost certainly not what users want. Instead this should
            //  intelligently merge options.
            val value: Any? = optionValue.getValue()
            optionDefinition.setValue(fragment, value)
        }
    }
}
