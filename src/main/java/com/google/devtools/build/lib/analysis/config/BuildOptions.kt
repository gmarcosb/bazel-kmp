// Copyright 2014 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.skyframe.serialization.ImmutableMapCodecs.IMMUTABLE_MAP_CODEC

/** Stores the command-line options from a set of configuration fragments.  */ // TODO(janakr): If overhead of FragmentOptions class names is too high, add constructor that just
// takes fragments and gets names from them.
class BuildOptions private constructor(
    fragmentOptionsMap: com.google.common.collect.ImmutableMap<java.lang.Class<out FragmentOptions?>?, FragmentOptions?>,
    starlarkOptionsMap: com.google.common.collect.ImmutableMap<Label?, Any?>,
    scopes: com.google.common.collect.ImmutableMap<Label?, Scope.ScopeType?>,
    onLeaveScopeValuesMap: com.google.common.collect.ImmutableMap<Label?, Any?>
) : Cloneable {
    /**
     * Returns the actual instance of a [FragmentOptions] class, or `null` if the options
     * class is not present.
     */
    fun <T : FragmentOptions?> get(optionsClass: java.lang.Class<T?>): T? {
        val options: FragmentOptions? = fragmentOptionsMap.get(optionsClass)
        return optionsClass.cast(options)
    }

    /** Returns true if these options contain the given [FragmentOptions].  */
    fun contains(optionsClass: java.lang.Class<out FragmentOptions?>?): Boolean {
        return fragmentOptionsMap.containsKey(optionsClass)
    }

    /**
     * Are these options "empty", meaning they contain no meaningful configuration information?
     * 
     * 
     * See [com.google.devtools.build.lib.analysis.config.transitions.NoConfigTransition].
     */
    fun hasNoConfig(): Boolean {
        // Ideally the implementation is fragmentOptionsMap.isEmpty() && starlarkOptionsMap.isEmpty().
        // See NoConfigTransition for why CoreOptions stays included.
        return fragmentOptionsMap.size() == 1 && com.google.common.collect.Iterables.getOnlyElement<FragmentOptions?>(
            fragmentOptionsMap.values()
        )
            .getOptionsClass()
            .getSimpleName()
            .equals("CoreOptions")
                && starlarkOptionsMap.isEmpty()
    }

    /** Returns a hex digest string uniquely identifying the build options.  */
    fun checksum(): String? {
        if (checksum == null) {
            synchronized(this) {
                if (checksum == null) {
                    if (fragmentOptionsMap.isEmpty() && starlarkOptionsMap.isEmpty()) {
                        checksum = "0".repeat(64) // Make empty build options easy to distinguish.
                    } else {
                        val fingerprint: Fingerprint = Fingerprint()
                        for (options in fragmentOptionsMap.values()) {
                            fingerprint.addString(optionsToCacheKey(options))
                        }
                        fingerprint.addString(starlarkMapToCacheKey(starlarkOptionsMap))
                        fingerprint.addString(mapToCacheKey(scopes))
                        fingerprint.addString(starlarkMapToCacheKey(onLeaveScopeValuesMap))
                        checksum = fingerprint.hexDigestAndReset()
                    }
                }
            }
        }
        return checksum
    }

    /**
     * Returns a user-friendly configuration identifier as a prefix of `fullId`.
     * 
     * 
     * This eliminates having to manipulate long full hashes, just like Git short commit hashes.
     */
    fun shortId(): String {
        // Inherit Git's default commit hash prefix length. It's a principled choice with similar usage
        // patterns. cquery, which uses this, has access to every configuration in the build. If it
        // turns out this setting produces ambiguous prefixes, we could always compare configurations
        // to find the actual minimal unambiguous length.
        return if (checksum() == null) "null" else checksum().substring(0, 7)
    }

    override fun toString(): String {
        return com.google.common.base.MoreObjects.toStringHelper(this)
            .add("checksum", checksum())
            .add("fragmentOptions", fragmentOptionsMap.values())
            .add("starlarkOptions", starlarkOptionsMap)
            .add("scopes", scopes)
            .add("onLeaveScopeValues", onLeaveScopeValuesMap)
            .toString()
    }

    /** Returns the options contained in this collection, sorted by [FragmentOptions] name.  */
    fun getNativeOptions(): com.google.common.collect.ImmutableCollection<FragmentOptions> {
        return fragmentOptionsMap.values()
    }

    /**
     * Returns the set of fragment classes contained in these options, sorted by [ ] name.
     */
    fun getFragmentClasses(): com.google.common.collect.ImmutableSet<java.lang.Class<out FragmentOptions?>?> {
        return fragmentOptionsMap.keySet()
    }

    /** Starlark options, sorted lexicographically by name.  */
    fun getStarlarkOptions(): com.google.common.collect.ImmutableMap<Label?, Any?> {
        return starlarkOptionsMap
    }

    /**
     * Map of [ScopeType] for starlark options. Before the final [BuildOptions] is
     * produced to create the final [BuildConfigurationKey], the [ScopeType] for each
     * starlark flag is expected to be resolved. If there is a transition involved introducing a
     * starlark flag that is not already part of the baseline configuration, the [ScopeType] for
     * that flag will be null until the final [BuildOptions] is produced.
     */
    fun getScopeTypeMap(): com.google.common.collect.ImmutableMap<Label?, Scope.ScopeType?> {
        return scopes
    }

    /** Starlark on-leave scope values, sorted lexicographically by name.  */
    fun getOnLeaveScopeValues(): com.google.common.collect.ImmutableMap<Label?, Any?> {
        return onLeaveScopeValuesMap
    }

    /**
     * Creates a copy of the BuildOptions object that contains copies of the FragmentOptions and
     * Starlark options.
     */
    public override fun clone(): BuildOptions {
        val nativeOptions: com.google.common.collect.ImmutableMap<java.lang.Class<out FragmentOptions?>?, FragmentOptions?> =
            fragmentOptionsMap.entrySet().stream()
                .collect(
                    com.google.common.collect.ImmutableMap.toImmutableMap<Any?, Any?, Any?>(
                        java.util.function.Function { java.util.Map.Entry.getKey() },  // Explicitly clone native options because FragmentOptions is mutable.
                        java.util.function.Function { e: Any? -> e.getValue().clone() })
                )
        // Note that this assumes that starlark option values are immutable.
        val starlarkOptions: com.google.common.collect.ImmutableMap<Label?, Any?> =
            com.google.common.collect.ImmutableMap.copyOf<Label?, Any?>(starlarkOptionsMap)
        val scopes: com.google.common.collect.ImmutableMap<Label?, Scope.ScopeType?> = this.scopes
        val onLeaveScopeValues: com.google.common.collect.ImmutableMap<Label?, Any?> =
            com.google.common.collect.ImmutableMap.copyOf<Label?, Any?>(onLeaveScopeValuesMap)
        return BuildOptions(nativeOptions, starlarkOptions, scopes, onLeaveScopeValues)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) {
            return true
        }
        if (other !is BuildOptions) {
            return false
        }
        return checksum() == other.checksum()
    }

    override fun hashCode(): Int {
        return 31 + checksum()!!.hashCode()
    }

    /** Maps options class definitions to FragmentOptions objects.  */
    private val fragmentOptionsMap: com.google.common.collect.ImmutableMap<java.lang.Class<out FragmentOptions?>?, FragmentOptions?>

    /**
     * Maps Starlark options names to Starlark options values. This should never contain an entry for
     * a Starlark option and the default value: if a Starlark option is explicitly or implicitly set
     * to the default it should be removed from this map so that configurations are not duplicated
     * needlessly.
     */
    private val starlarkOptionsMap: com.google.common.collect.ImmutableMap<Label?, Any?>

    // TODO: b/377559852 - Merge scopes into starlarkOptionsMap
    /** Maps Starlark options names to [Scope] information  */
    private val scopes: com.google.common.collect.ImmutableMap<Label?, Scope.ScopeType?>

    /** Maps Starlark options names to their on-leave scope values.  */
    private val onLeaveScopeValuesMap: com.google.common.collect.ImmutableMap<Label?, Any?>

    // Lazily initialized both for performance and correctness - BuildOptions instances may be mutated
    // after construction but before consumption. Access via checksum() to ensure initialization. This
    // field is volatile as per https://errorprone.info/bugpattern/DoubleCheckedLocking, which
    // encourages using volatile even for immutable objects.
    @kotlin.concurrent.Volatile
    @Transient
    private var checksum: String? = null

    init {
        this.fragmentOptionsMap = fragmentOptionsMap
        this.starlarkOptionsMap = starlarkOptionsMap
        this.scopes = scopes
        this.onLeaveScopeValuesMap = onLeaveScopeValuesMap
    }

    /**
     * Returns true if the passed parsing result's options have the same value as these options.
     * 
     * 
     * If a native parsed option is passed whose fragment has been trimmed in these options it is
     * considered to match.
     * 
     * 
     * If no options are present in the parsing result or all options in the parsing result have
     * been trimmed the result is considered not to match. This is because otherwise the parsing
     * result would match any options in a similar trimmed state, regardless of contents.
     * 
     * @param parsingResult parsing result to be compared to these options
     * @return true if all non-trimmed values match
     * @throws OptionsParsingException if options cannot be parsed
     */
    @Throws(OptionsParsingException::class)
    fun matches(parsingResult: OptionsParsingResult): Boolean {
        val ignoredDefinitions: MutableSet<OptionDefinition?> = HashSet<OptionDefinition?>()
        for (parsedOption in parsingResult.asListOfExplicitOptions()) {
            val optionDefinition: OptionDefinition = parsedOption.getOptionDefinition()

            // All options obtained from an options parser are guaranteed to have been defined in an
            // FragmentOptions class.
            val fragmentClass: java.lang.Class<out FragmentOptions?>? =
                optionDefinition.getDeclaringClass(FragmentOptions::class.java)

            val originalFragment: FragmentOptions? = fragmentOptionsMap.get(fragmentClass)
            if (originalFragment == null) {
                // Ignore flags set in trimmed fragments.
                ignoredDefinitions.add(optionDefinition)
                continue
            }
            val originalValue: Any? = originalFragment.asMap().get(optionDefinition.getOptionName())
            if (originalValue != parsedOption.getConvertedValue()) {
                return false
            }
        }

        val starlarkOptions: MutableMap<Label?, Any?> =
            labelizeStarlarkOptions(parsingResult.getStarlarkOptions())
        val starlarkDifference: com.google.common.collect.MapDifference<Label?, Any?> =
            com.google.common.collect.Maps.difference<Label?, Any?>(starlarkOptionsMap, starlarkOptions)
        if (starlarkDifference.entriesInCommon().size() < starlarkOptions.size()) {
            return false
        }

        if (ignoredDefinitions.size() == parsingResult.asListOfExplicitOptions().size()
            && starlarkOptions.isEmpty()
        ) {
            // Zero options were compared, either because none were passed or because all of them were
            // trimmed.
            return false
        }

        return true
    }

    /** Creates a builder operating on a clone of this BuildOptions.  */
    fun toBuilder(): Builder {
        return builder().merge(clone())
    }

    /** Builder class for BuildOptions.  */
    class Builder private constructor() {
        /**
         * Merges the given BuildOptions into this builder, overriding any previous instances of
         * Starlark options or FragmentOptions subclasses found in the new BuildOptions.
         */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun merge(options: BuildOptions): Builder {
            for (fragment in options.getNativeOptions()) {
                this.addFragmentOptions<FragmentOptions?>(fragment)
            }
            this.addStarlarkOptions(options.getStarlarkOptions())
            this.addScopeTypeMap(options.getScopeTypeMap())
            this.addOnLeaveScopeValues(options.getOnLeaveScopeValues())
            return this
        }

        /**
         * Adds a new [FragmentOptions] instance to the builder.
         * 
         * 
         * Overrides previous instances of the exact same subclass of `FragmentOptions`.
         * 
         * 
         * The options get preprocessed with [FragmentOptions.getNormalized].
         */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun <T : FragmentOptions?> addFragmentOptions(options: T?): Builder {
            fragmentOptions.put(options.getOptionsClass(), options.getNormalized())
            return this
        }

        /**
         * Returns the [FragmentOptions] for the given class, or `null` if that fragment is
         * not present.
         */
        fun <T : FragmentOptions?> getFragmentOptions(key: java.lang.Class<T?>?): T? {
            return fragmentOptions.get(key) as T?
        }

        /** Removes the value for the [FragmentOptions] with the given FragmentOptions class.  */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun removeFragmentOptions(key: java.lang.Class<out FragmentOptions?>?): Builder {
            fragmentOptions.remove(key)
            return this
        }

        /**
         * Adds multiple Starlark options to the builder. Overrides previous instances of the same key.
         */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun addStarlarkOptions(options: MutableMap<Label?, Any?>?): Builder {
            starlarkOptions.putAll(options)
            return this
        }

        /** Adds a Starlark option to the builder. Overrides previous instances of the same key.  */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun addStarlarkOption(key: Label?, value: Any?): Builder {
            starlarkOptions.put(key, value)
            return this
        }

        /**
         * Adds ScopeType for a Starlark option to the builder. Overrides previous instances of the same
         * key.
         */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun addScopeType(key: Label?, value: Scope.ScopeType?): Builder {
            scopes.put(key, value)
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun addScopeTypeMap(scopes: MutableMap<Label?, Scope.ScopeType?>): Builder {
            for (entry in scopes.entrySet()) {
                this.scopes.put(entry.getKey(), entry.getValue())
            }
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun removeScope(key: Label?): Builder {
            scopes.remove(key)
            return this
        }

        /** Removes the value and associated ScopeType for the Starlark option with the given key.  */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun removeStarlarkOption(key: Label?): Builder {
            starlarkOptions.remove(key)
            removeScope(key)
            onLeaveScopeValues.remove(key)
            return this
        }

        /**
         * Adds multiple Starlark on-leave scope values to the builder. Overrides previous instances of
         * the same key.
         */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun addOnLeaveScopeValues(options: MutableMap<Label?, Any?>?): Builder {
            onLeaveScopeValues.putAll(options)
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun addOnLeaveScopeValue(key: Label?, value: Any?): Builder {
            onLeaveScopeValues.put(key, value)
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun removeOnLeaveScopeValue(key: Label?): Builder {
            onLeaveScopeValues.remove(key)
            return this
        }

        fun build(): BuildOptions {
            return if (BuildOptions(
                    TODO("Cannot convert element")
                ) < Class
            )
                TODO(
                    """
                |Cannot convert element
                |With text:
                |FragmentOptions>, FragmentOptions>sortedImmutableHashMap(fragmentOptions, LEXICAL_FRAGMENT_OPTIONS_COMPARATOR)
                """.trimMargin()
                )
            TODO(
                """
                |Cannot convert element
                |With text:
                |K, V>sortedImmutableHashMap(starlarkOptions, <T>naturalOrder()
                """.trimMargin()
            )
            TODO(
                """
                |Cannot convert element
                |With text:
                |K, V>sortedImmutableHashMap(scopes, <T>naturalOrder()
                """.trimMargin()
            )
            TODO(
                """
                |Cannot convert element
                |With text:
                |K, V>sortedImmutableHashMap(onLeaveScopeValues, <T>naturalOrder()
                """.trimMargin()
            )
        }

        private val fragmentOptions: MutableMap<java.lang.Class<out FragmentOptions?>?, FragmentOptions?> =
            HashMap<java.lang.Class<out FragmentOptions?>?, FragmentOptions?>()
        private val starlarkOptions: LinkedHashMap<Label?, Any?> = LinkedHashMap<Label?, Any?>()

        // TODO: b/377559852 - Merge scopes into starlarkOptionsMap
        private val scopes: LinkedHashMap<Label?, Scope.ScopeType?> = LinkedHashMap<Label?, Scope.ScopeType?>()
        private val onLeaveScopeValues: LinkedHashMap<Label?, Any?> = LinkedHashMap<Label?, Any?>()

        companion object {
            /**
             * Constructs a hash-based [ImmutableMap] copy of the given map, with an iteration order
             * defined by the given key comparator.
             * 
             * 
             * The returned map has a deterministic iteration order but is *not* an [ ], which uses binary search lookups. Hash-based lookups are expected to be
             * much faster for build options.
             */
            private fun <K, V> sortedImmutableHashMap(
                map: MutableMap<K?, V?>, keyComparator: java.util.Comparator<K?>
            ): com.google.common.collect.ImmutableMap<K?, V?> {
                val entries: MutableList<MutableMap.MutableEntry<K?, V?>?> =
                    java.util.ArrayList<MutableMap.MutableEntry<K?, V?>?>(map.entrySet())
                entries.sort(java.util.Map.Entry.comparingByKey<K?, V?>(keyComparator))
                return com.google.common.collect.ImmutableMap.copyOf<K?, V?>(entries)
            }
        }
    }

    /**
     * A value sharing codec for BuildOptions that does not rely on an OptionsChecksumCache.
     * 
     * 
     * This allows the BuildOptions object to be serialized remotely, and fetched with a new
     * instance without relying on an existing local primed cache.
     */
    private class ValueSharingCodec : DeferredObjectCodec<BuildOptions?>() {
        public override fun autoRegister(): Boolean {
            return false
        }

        public override fun getEncodedClass(): java.lang.Class<BuildOptions?> {
            return BuildOptions::class.java
        }

        @Throws(SerializationException::class, IOException::class)
        public override fun serialize(
            context: SerializationContext, options: BuildOptions, codedOut: CodedOutputStream?
        ) {
            context.putSharedValue(options.fragmentOptionsMap, null, IMMUTABLE_MAP_CODEC, codedOut)
            context.putSharedValue(options.starlarkOptionsMap, null, IMMUTABLE_MAP_CODEC, codedOut)
            context.putSharedValue(options.scopes, null, IMMUTABLE_MAP_CODEC, codedOut)
            context.putSharedValue(options.onLeaveScopeValuesMap, null, IMMUTABLE_MAP_CODEC, codedOut)
        }

        @Throws(SerializationException::class, IOException::class)
        public override fun deserializeDeferred(
            context: AsyncDeserializationContext, codedIn: CodedInputStream?
        ): DeferredValue<out BuildOptions?> {
            val builder = DeserializationBuilder()
            context.getSharedValue(
                codedIn,
                null,
                IMMUTABLE_MAP_CODEC,
                builder,
                { builder: DeserializationBuilder, value: Any? ->
                    DeserializationBuilder.Companion.setFragmentOptionsMap(
                        builder,
                        value
                    )
                })
            context.getSharedValue(
                codedIn,
                null,
                IMMUTABLE_MAP_CODEC,
                builder,
                { builder: DeserializationBuilder, value: Any? ->
                    DeserializationBuilder.Companion.setStarlarkOptionsMap(
                        builder,
                        value
                    )
                })
            context.getSharedValue(
                codedIn,
                null,
                IMMUTABLE_MAP_CODEC,
                builder,
                { builder: DeserializationBuilder, value: Any? ->
                    DeserializationBuilder.Companion.setScopes(
                        builder,
                        value
                    )
                })
            context.getSharedValue(
                codedIn,
                null,
                IMMUTABLE_MAP_CODEC,
                builder,
                { builder: DeserializationBuilder, value: Any? ->
                    DeserializationBuilder.Companion.setOnLeaveScopeValuesMap(
                        builder,
                        value
                    )
                })
            return builder
        }

        private class DeserializationBuilder

            : DeferredObjectCodec.DeferredValue<BuildOptions?> {
            var fragmentOptionsMap: com.google.common.collect.ImmutableMap<java.lang.Class<out FragmentOptions?>?, FragmentOptions?>? =
                null
            var starlarkOptionsMap: com.google.common.collect.ImmutableMap<Label?, Any?>? = null

            // TODO: b/377559852 - Merge scopes into starlarkOptionsMap
            var scopes: com.google.common.collect.ImmutableMap<Label?, Scope.ScopeType?>? = null
            var onLeaveScopeValuesMap: com.google.common.collect.ImmutableMap<Label?, Any?>? = null

            public override fun call(): BuildOptions {
                return BuildOptions(
                    fragmentOptionsMap, starlarkOptionsMap, scopes, onLeaveScopeValuesMap
                )
            }

            companion object {
                private fun setFragmentOptionsMap(builder: DeserializationBuilder, value: Any?) {
                    builder.fragmentOptionsMap =
                        value as com.google.common.collect.ImmutableMap<java.lang.Class<out FragmentOptions?>?, FragmentOptions?>
                }

                private fun setStarlarkOptionsMap(builder: DeserializationBuilder, value: Any?) {
                    builder.starlarkOptionsMap = value as com.google.common.collect.ImmutableMap<Label?, Any?>
                }

                private fun setScopes(builder: DeserializationBuilder, value: Any?) {
                    builder.scopes = value as com.google.common.collect.ImmutableMap<Label?, Scope.ScopeType?>
                }

                private fun setOnLeaveScopeValuesMap(builder: DeserializationBuilder, value: Any?) {
                    builder.onLeaveScopeValuesMap = value as com.google.common.collect.ImmutableMap<Label?, Any?>
                }
            }
        }

        companion object {
            private val INSTANCE = ValueSharingCodec()
        }
    }

    /**
     * Codec for [BuildOptions].
     * 
     * 
     * This codec works by serializing the [BuildOptions.checksum] only. This works due to
     * the assumption that anytime a value containing a particular configuration is deserialized, it
     * was previously requested using the same configuration key, thus priming the cache.
     */
    @VisibleForSerialization
    class Codec : LeafObjectCodec<BuildOptions?>() {
        public override fun getEncodedClass(): java.lang.Class<BuildOptions?> {
            return BuildOptions::class.java
        }

        @Throws(SerializationException::class, IOException::class)
        public override fun serialize(
            context: LeafSerializationContext, options: BuildOptions, codedOut: CodedOutputStream?
        ) {
            if (!context.getDependency(OptionsChecksumCache::class.java).prime(options)) {
                throw SerializationException("Failed to prime cache for " + options.checksum())
            }
            context.serializeLeaf(options.checksum(), stringCodec(), codedOut)
        }

        @Throws(SerializationException::class, IOException::class)
        public override fun deserialize(context: LeafDeserializationContext, codedIn: CodedInputStream?): BuildOptions {
            val checksum: String? = context.deserializeLeaf(codedIn, stringCodec())
            val result: BuildOptions = context.getDependency(OptionsChecksumCache::class.java).getOptions(checksum)
            if (result == null) {
                throw SerializationException("No options instance for " + checksum)
            }
            return result
        }

        companion object {
            private val INSTANCE: Codec = com.google.devtools.build.lib.analysis.config.BuildOptions.Codec()

            fun buildOptionsCodec(): Codec {
                return com.google.devtools.build.lib.analysis.config.BuildOptions.Codec.Companion.INSTANCE
            }
        }
    }

    /**
     * Provides [BuildOptions] instances when requested via their [ ][BuildOptions.checksum].
     */
    interface OptionsChecksumCache {
        /**
         * Called during deserialization to transform a checksum into a [BuildOptions] instance.
         * 
         * 
         * Returns `null` when the given checksum is unknown, in which case the codec throws
         * [SerializationException].
         */
        fun getOptions(checksum: String?): BuildOptions?

        /**
         * Notifies the cache that it may be necessary to deserialize the given options diff's checksum.
         * 
         * 
         * Called each time an [BuildOptions] instance is serialized.
         * 
         * @return whether this cache was successfully primed, if `false` the codec will throw
         * [SerializationException]
         */
        fun prime(options: BuildOptions?): Boolean
    }

    /**
     * Simple [OptionsChecksumCache] backed by a [ConcurrentMap].
     * 
     * 
     * Checksum mappings are retained indefinitely.
     */
    class MapBackedChecksumCache : OptionsChecksumCache {
        private val map: ConcurrentMap<String?, BuildOptions?> = ConcurrentHashMap<String?, BuildOptions?>()

        override fun getOptions(checksum: String?): BuildOptions? {
            return map.get(checksum)
        }

        override fun prime(options: BuildOptions): Boolean {
            map.putIfAbsent(options.checksum(), options)
            return true
        }
    }

    companion object {
        private val ESCAPER: com.google.common.escape.Escaper =
            com.google.common.escape.CharEscaperBuilder().addEscape('\\', "\\\\").addEscape('"', "\\\"").toEscaper()

        @SerializationConstant
        val LEXICAL_FRAGMENT_OPTIONS_COMPARATOR: java.util.Comparator<java.lang.Class<out FragmentOptions?>?> =
            java.util.Comparator.comparing<java.lang.Class<out FragmentOptions?>?, String?>(java.util.function.Function { obj: java.lang.Class<out FragmentOptions?>? -> obj.getName() })

        fun labelizeStarlarkOptions(starlarkOptions: MutableMap<String?, Any?>): MutableMap<Label?, Any?> {
            return starlarkOptions.entrySet().stream()
                .collect(
                    Collectors.toMap(java.util.function.Function { e: MutableMap.MutableEntry<String?, Any?>? ->
                        Label.parseCanonicalUnchecked(
                            e.getKey()
                        )
                    }, java.util.function.Function { java.util.Map.Entry.getValue() })
                )
        }

        /**
         * Converts the map containing String representation of scopes attributes to a map of [ ] of Starlark options to their corresponding [Scope.ScopeType].
         */
        private fun convertScopesAttributes(
            scopesAttributes: MutableMap<String?, String?>, starlarkOptions: MutableMap<String?, Any?>
        ): com.google.common.collect.ImmutableMap<Label?, Scope.ScopeType?> {
            return scopesAttributes.entrySet().stream()
                .filter(java.util.function.Predicate { e: MutableMap.MutableEntry<String?, String?>? ->
                    starlarkOptions.containsKey(
                        e.getKey()
                    )
                })
                .collect(
                    com.google.common.collect.ImmutableMap.toImmutableMap<Any?, Any?, Any?>(
                        java.util.function.Function { e: Any? -> Label.parseCanonicalUnchecked(e.getKey()) },
                        java.util.function.Function { e: Any? -> ScopeType(e.getValue()) })
                )
        }

        fun getDefaultBuildOptionsForFragments(
            fragmentClasses: Iterable<java.lang.Class<out FragmentOptions?>?>
        ): BuildOptions {
            try {
                return of(fragmentClasses)
            } catch (e: OptionsParsingException) {
                throw java.lang.IllegalArgumentException("Failed to parse empty options", e)
            }
        }

        /**
         * Creates a BuildOptions class by taking the option values from an options provider (eg. an
         * OptionsParser).
         */
        fun of(
            optionsList: Iterable<java.lang.Class<out FragmentOptions?>?>, provider: OptionsProvider
        ): BuildOptions {
            val builder = builder()
            for (optionsClass in optionsList) {
                builder.addFragmentOptions<T?>(provider.getOptions(optionsClass))
            }
            return builder
                .addStarlarkOptions(labelizeStarlarkOptions(provider.getStarlarkOptions()))
                .addScopeTypeMap(
                    convertScopesAttributes(provider.getScopesAttributes(), provider.getStarlarkOptions())
                )
                .addOnLeaveScopeValues(labelizeStarlarkOptions(provider.getOnLeaveScopeValues()))
                .build()
        }

        /**
         * Creates a BuildOptions class by taking the option values from command-line arguments. Returns a
         * BuildOptions class that only has native options.
         */
        @com.google.common.annotations.VisibleForTesting
        @Throws(OptionsParsingException::class)
        fun of(
            optionsList: Iterable<java.lang.Class<out FragmentOptions?>?>, vararg args: String?
        ): BuildOptions {
            val builder = builder()
            val parser: OptionsParser = OptionsParser.builder().optionsClasses(optionsList).build()
            parser.parse(args)
            for (optionsClass in optionsList) {
                builder.addFragmentOptions<T?>(parser.getOptions(optionsClass))
            }
            return builder.build()
        }

        /*
   * Returns a BuildOptions class that only has Starlark options.
   */
        @com.google.common.annotations.VisibleForTesting
        fun of(starlarkOptions: MutableMap<Label?, Any?>?): BuildOptions {
            return builder().addStarlarkOptions(starlarkOptions).build()
        }

        /** Returns a string that uniquely identifies the options.  */
        fun optionsToCacheKey(options: OptionsBase): String {
            val result: java.lang.StringBuilder =
                java.lang.StringBuilder(options.getOptionsClass().getName()).append("{")
            result.append(mapToCacheKey(Options.toMap(options)))
            return result.append("}").toString()
        }

        /** Returns a string that uniquely identifies the options map.  */
        private fun mapToCacheKey(optionsMap: MutableMap<*, *>, distinguishStarlarkTypes: Boolean = false): String {
            val result: java.lang.StringBuilder = java.lang.StringBuilder()
            for (entry in optionsMap.entrySet()) {
                result.append(entry.getKey()).append("=")

                val value: Any? = entry.getValue()

                if (value == null) {
                    result.append("NULL")
                } else {
                    if (distinguishStarlarkTypes) {
                        result.append(Starlark.type(value))
                    }
                    // This special case is needed because Collection.toString() prints the same ("[]") for an
                    // empty collection and for a collection with a single empty string.
                    if (value is MutableCollection<*> && value.isEmpty()) {
                        result.append("EMPTY")
                    } else {
                        result.append('"').append(ESCAPER.escape(value.toString())).append('"')
                    }
                }
                result.append(", ")
            }
            return result.toString()
        }

        /**
         * Returns a string that uniquely identifies the options map. Like [.mapToCacheKey] but the
         * returned key is sensitive to the [Starlark.type] of values in the map.
         * 
         * 
         * This is important because types are observable to starlark code. See b/478938163.
         */
        private fun starlarkMapToCacheKey(starlarkOptionsMap: MutableMap<*, *>): String {
            return mapToCacheKey(starlarkOptionsMap,  /* distinguishStarlarkTypes= */true)
        }

        /** Creates a builder object for BuildOptions  */
        fun builder(): Builder {
            return com.google.devtools.build.lib.analysis.config.BuildOptions.Builder()
        }

        fun valueSharingCodec(): ValueSharingCodec {
            return ValueSharingCodec.Companion.INSTANCE
        }
    }
}
