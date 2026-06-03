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
package com.google.devtools.build.lib.analysis

import com.google.devtools.build.lib.actions.Artifact

/**
 * `ConfiguredTarget`s implementing this interface can provide artifacts that **can** be
 * built when the target is mentioned on the command line (as opposed to being always built, like
 * [com.google.devtools.build.lib.analysis.FileProvider])
 * 
 * 
 * The artifacts are grouped into "output groups". Which output groups are built is controlled by
 * the `--output_groups` undocumented command line option, which in turn is added to the
 * command line at the discretion of the build command being run.
 * 
 * 
 * Output groups starting with an underscore are "not important". This means that artifacts built
 * because such an output group is mentioned in a `--output_groups` command line option are
 * not mentioned on the output.
 * 
 * 
 * Implementations are optimized for memory footprint based on common usage, including compact
 * representations for groups with an empty set of files, a small number of groups (1 or 2), and the
 * frequently used [.DEFAULT_GROUPS]. See detection of special cases in the [ ][.singleGroup] and [.createInternal] factory methods.
 */
@com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable
abstract class OutputGroupInfo private constructor() : StructImpl(), StarlarkIndexable, StarlarkIterable<String?>,
    OutputGroupInfoApi {
    /** Request parameter for [.determineOutputGroups].  */
    enum class ValidationMode {
        /** Validation outputs not built.  */
        OFF,

        /**
         * Validation outputs built by requesting [.VALIDATION] output group Blaze core collects.
         */
        OUTPUT_GROUP,

        /**
         * Validation outputs built by `ValidateTarget` aspect "promoting" [.VALIDATION]
         * output group Blaze core collects to [.VALIDATION_TOP_LEVEL] and requesting the latter.
         */
        ASPECT
    }

    public override fun getProvider(): OutputGroupInfoProvider {
        return STARLARK_CONSTRUCTOR
    }

    override fun isImmutable(): Boolean {
        return true // immutable and Starlark-hashable
    }

    /**
     * Returns the artifacts in a particular output group.
     * 
     * @return the artifacts in the output group with the given name. The return value is never null.
     * If the specified output group is not present, the empty set is returned.
     */
    abstract fun getOutputGroup(name: String?): NestedSet<Artifact?>

    @Throws(net.starlark.java.eval.EvalException::class)
    override fun getIndex(semantics: StarlarkSemantics?, key: Any?): Any {
        if (key !is String) {
            throw Starlark.errorf(
                "Output group names must be strings, got %s instead", Starlark.type(key)
            )
        }
        val result: Depset = getValue(key)
        if (result == null) {
            throw Starlark.errorf("Output group %s not present", key)
        }
        return result
    }

    override fun containsKey(semantics: StarlarkSemantics?, key: Any?): Boolean {
        return key is String && containsKey(key)
    }

    @com.google.errorprone.annotations.ForOverride
    abstract fun containsKey(name: String?): Boolean

    public override fun getValue(name: String?): Depset? {
        val result: NestedSet<Artifact?> = getOutputGroup(name)
        if (result.isEmpty() && !containsKey(name)) {
            return null
        }
        return Depset.of(Artifact::class.java, result)
    }

    /** All output groups are empty.  */
    private class EmptyFiles(groups: com.google.common.collect.ImmutableSet<String?>) : OutputGroupInfo() {
        private val groups: com.google.common.collect.ImmutableSet<String?>

        init {
            this.groups = groups
        }

        override fun getOutputGroup(name: String?): NestedSet<Artifact?>? {
            return EMPTY_FILES
        }

        override fun containsKey(name: String?): Boolean {
            return groups.contains(name)
        }

        override fun iterator(): MutableIterator<String?> {
            return groups.iterator()
        }

        public override fun getFieldNames(): com.google.common.collect.ImmutableSet<String?> {
            return groups
        }

        override fun equals(o: Any?): Boolean {
            if (this === o) {
                return true
            }
            if (o !is EmptyFiles) {
                return false
            }
            return groups == o.groups
        }

        override fun hashCode(): Int {
            return groups.hashCode()
        }

        companion object {
            private val interner: com.google.common.collect.Interner<EmptyFiles> =
                BlazeInterners.newWeakInterner<EmptyFiles?>()

            fun of(groups: com.google.common.collect.ImmutableSet<String?>): EmptyFiles {
                return interner.intern(EmptyFiles(groups))
            }
        }
    }

    private abstract class SingleGroup(files: NestedSet<Artifact?>?) : OutputGroupInfo() {
        private val files: NestedSet<Artifact?>?

        init {
            this.files = files
        }

        override fun getOutputGroup(name: String): NestedSet<Artifact?>? {
            return if (containsKey(name)) files else EMPTY_FILES
        }

        override fun containsKey(name: String): Boolean {
            return name == groupName()
        }

        override fun iterator(): MutableIterator<String?> {
            return com.google.common.collect.Iterators.singletonIterator<String?>(groupName())
        }

        public override fun getFieldNames(): com.google.common.collect.ImmutableSet<String?> {
            return com.google.common.collect.ImmutableSet.of<String?>(groupName())
        }

        @com.google.errorprone.annotations.ForOverride
        abstract fun groupName(): String?
    }

    /** A single non-empty output group: [.HIDDEN_TOP_LEVEL].  */
    private class HiddenTopLevelOnly(files: NestedSet<Artifact?>?) : SingleGroup(files) {
        override fun groupName(): String {
            return HIDDEN_TOP_LEVEL
        }
    }

    /** A single non-empty output group: [.VALIDATION].  */
    private class ValidationOnly(files: NestedSet<Artifact?>?) : SingleGroup(files) {
        override fun groupName(): String {
            return VALIDATION
        }
    }

    /** A single non-empty output group: [.DEFAULT].  */
    private class DefaultOnly(files: NestedSet<Artifact?>?) : SingleGroup(files) {
        override fun groupName(): String {
            return DEFAULT
        }
    }

    /** A single non-empty output group besides the common groups special-cased above.  */
    private class OtherGroupOnly(private val groupName: String?, files: NestedSet<Artifact?>?) : SingleGroup(files) {
        override fun groupName(): String? {
            return groupName
        }
    }

    /**
     * Two output groups: [.HIDDEN_TOP_LEVEL] and one other, at least one of which is non-empty.
     */
    private class HiddenTopLevelAndOneOther(
        hiddenTopLevelFiles: NestedSet<Artifact?>?,
        otherGroup: String?,
        otherFiles: NestedSet<Artifact?>?
    ) : OutputGroupInfo() {
        private val hiddenTopLevelFiles: NestedSet<Artifact?>?
        private val otherGroup: String?
        private val otherFiles: NestedSet<Artifact?>?

        init {
            this.hiddenTopLevelFiles = hiddenTopLevelFiles
            this.otherGroup = otherGroup
            this.otherFiles = otherFiles
        }

        override fun getOutputGroup(name: String): NestedSet<Artifact?>? {
            if (name == HIDDEN_TOP_LEVEL) {
                return hiddenTopLevelFiles
            }
            if (name == otherGroup) {
                return otherFiles
            }
            return EMPTY_FILES
        }

        override fun containsKey(name: String): Boolean {
            return name == HIDDEN_TOP_LEVEL || name == otherGroup
        }

        override fun iterator(): MutableIterator<String?> {
            return com.google.common.collect.Iterators.forArray<String?>(HIDDEN_TOP_LEVEL, otherGroup)
        }

        public override fun getFieldNames(): com.google.common.collect.ImmutableSet<String?> {
            return com.google.common.collect.ImmutableSet.of<String?>(HIDDEN_TOP_LEVEL, otherGroup)
        }
    }

    /** Handles the arbitrary case for when none of the special cases above match.  */
    private class ArbitraryGroups(map: ImmutableSharedKeyMap<String?, NestedSet<Artifact?>?>) : OutputGroupInfo() {
        private val map: ImmutableSharedKeyMap<String?, NestedSet<Artifact?>?>

        init {
            this.map = map
        }

        override fun getOutputGroup(name: String?): NestedSet<Artifact?>? {
            return com.google.common.base.MoreObjects.firstNonNull<T?>(map.get(name), EMPTY_FILES)
        }

        override fun containsKey(name: String?): Boolean {
            return map.containsKey(name)
        }

        override fun iterator(): MutableIterator<String?> {
            return map.iterator()
        }

        public override fun getFieldNames(): com.google.common.collect.ImmutableSet<String?> {
            return com.google.common.collect.ImmutableSet.copyOf(map)
        }
    }

    /** Provider implementation for [OutputGroupInfoApi.OutputGroupInfoApiProvider].  */
    class OutputGroupInfoProvider internal constructor() :
        BuiltinProvider<OutputGroupInfo?>("OutputGroupInfo", OutputGroupInfo::class.java),
        OutputGroupInfoApi.OutputGroupInfoApiProvider {
        @Throws(net.starlark.java.eval.EvalException::class)
        public override fun constructor(kwargs: Dict<String?, Any?>): OutputGroupInfoApi? {
            val outputGroups: com.google.common.collect.ImmutableMap.Builder<String?, NestedSet<Artifact?>?> =
                com.google.common.collect.ImmutableMap.builderWithExpectedSize<String?, NestedSet<Artifact?>?>(kwargs.size())
            for (entry in com.google.common.collect.ImmutableList.sortedCopyOf<MutableMap.MutableEntry<String?, Any?>?>(
                java.util.Map.Entry.comparingByKey<String?, Any?>(),
                kwargs.entrySet()
            )) {
                outputGroups.put(
                    entry.getKey(),
                    StarlarkRuleConfiguredTargetUtil.convertToOutputGroupValue(
                        entry.getKey(), entry.getValue()
                    )
                )
            }
            return createInternal(outputGroups.buildOrThrow())
        }
    }

    companion object {
        const val STARLARK_NAME: String = "output_groups"

        val STARLARK_CONSTRUCTOR: OutputGroupInfoProvider = OutputGroupInfoProvider()

        /**
         * Prefix for output groups that are not reported to the user on the terminal output of Blaze when
         * they are built.
         */
        const val HIDDEN_OUTPUT_GROUP_PREFIX: String = "_"

        /**
         * Suffix for output groups that are internal to bazel and may not be referenced from a filegroup.
         */
        const val INTERNAL_SUFFIX: String = "_INTERNAL_"

        /**
         * Building these artifacts only results in the compilation (and not e.g. linking) of the
         * associated target. Mostly useful for C++, less so for e.g. Java.
         */
        const val FILES_TO_COMPILE: String = "compilation_outputs"

        /**
         * These artifacts are the direct requirements for compilation, but building these does not
         * actually compile the target. Mostly useful when IDEs want Blaze to emit generated code so that
         * they can do the compilation in their own way.
         */
        val COMPILATION_PREREQUISITES: String = "compilation_prerequisites" + INTERNAL_SUFFIX

        /**
         * These files are built when a target is mentioned on the command line, but are not reported to
         * the user. This is mostly runfiles, which is necessary because we don't want a target to
         * successfully build if a file in its runfiles is broken.
         */
        val HIDDEN_TOP_LEVEL: String = HIDDEN_OUTPUT_GROUP_PREFIX + "hidden_top_level" + INTERNAL_SUFFIX

        /**
         * This output group contains artifacts that are the outputs of validation actions. These actions
         * should be run even if no other action depends on their outputs, therefore this output group is:
         * 
         * 
         *  * built even if `--output_groups` overrides the default output groups
         *  * not affected by the subtraction operation of `--output_groups` (i.e. `
         * "--output_groups=-_validation"`)
         * 
         * 
         * The only way to disable this output group is with `--run_validations=false`.
         */
        val VALIDATION: String = HIDDEN_OUTPUT_GROUP_PREFIX + "validation"

        /** Helper output group used to request [.VALIDATION] outputs from top-level aspect.  */
        val VALIDATION_TOP_LEVEL: String = HIDDEN_OUTPUT_GROUP_PREFIX + "validation_top_level" + INTERNAL_SUFFIX

        /** Helper output group to override [.VALIDATION] outputs from dependencies  */
        val VALIDATION_TRANSITIVE: String = HIDDEN_OUTPUT_GROUP_PREFIX + "validation_transitive"

        /**
         * Temporary files created during building a rule, for example, .i, .d and .s files for C++
         * compilation.
         * 
         * 
         * This output group is somewhat special: it is always built, but it only contains files when
         * the `--save_temps` command line option present. I'm not sure if this is to save RAM by
         * not creating the associated actions and artifacts if we don't need them or just historical
         * baggage.
         */
        val TEMP_FILES: String = "temp_files" + INTERNAL_SUFFIX

        /** The default group of files built by a target when it is mentioned on the command line.  */
        const val DEFAULT: String = "default"

        /** The default set of OutputGroups we typically want to build.  */
        val DEFAULT_GROUPS: com.google.common.collect.ImmutableSortedSet<String?> =
            com.google.common.collect.ImmutableSortedSet.of<String?>(
                DEFAULT, TEMP_FILES, HIDDEN_TOP_LEVEL
            )

        private val EMPTY_FILES: NestedSet<Artifact?>? = NestedSetBuilder.emptySet(Order.STABLE_ORDER)

        fun get(collection: ProviderCollection): OutputGroupInfo? {
            return collection.get(STARLARK_CONSTRUCTOR)
        }

        /**
         * Merges output groups from a list of output providers. The set of output groups must be
         * disjoint, except for the special validation output group, which is always merged.
         */
        @Throws(MergingException::class)
        fun merge(providers: MutableList<OutputGroupInfo>): OutputGroupInfo? {
            if (providers.isEmpty()) {
                return null
            }
            if (providers.size() == 1) {
                return providers.get(0)
            }

            val outputGroupBuilders: MutableMap<String?, NestedSetBuilder<Artifact?>> =
                TreeMap<String?, NestedSetBuilder<Artifact?>>()
            for (provider in providers) {
                for (group in provider) {
                    val builder: NestedSetBuilder<Artifact?> =
                        outputGroupBuilders.computeIfAbsent(
                            group,
                            java.util.function.Function { g: String? -> NestedSetBuilder.stableOrder() })
                    builder.addTransitive(provider.getOutputGroup(group))
                }
            }

            val outputGroups: com.google.common.collect.ImmutableMap.Builder<String?, NestedSet<Artifact?>?> =
                com.google.common.collect.ImmutableMap.builder<String?, NestedSet<Artifact?>?>()
            for (entry in outputGroupBuilders.entrySet()) {
                outputGroups.put(entry.getKey(), entry.getValue().build())
            }

            return createInternal(outputGroups.buildOrThrow())
        }

        fun determineOutputGroups(
            outputGroups: MutableList<String>, validationMode: ValidationMode, shouldRunTests: Boolean
        ): com.google.common.collect.ImmutableSortedSet<String?> {
            return determineOutputGroups(DEFAULT_GROUPS, outputGroups, validationMode, shouldRunTests)
        }

        @com.google.common.annotations.VisibleForTesting
        fun determineOutputGroups(
            defaultOutputGroups: MutableSet<String?>?,
            outputGroups: MutableList<String>,
            validationMode: ValidationMode,
            shouldRunTests: Boolean
        ): com.google.common.collect.ImmutableSortedSet<String?> {
            val current: MutableSet<String?> = com.google.common.collect.Sets.newHashSet<String?>()

            // If all of the requested output groups start with "+" or "-", then these are added or
            // subtracted to the set of default output groups.
            // If any of them don't start with "+" or "-", then the list of requested output groups
            // overrides the default set of output groups, except for the validation output group.
            var addDefaultOutputGroups = true
            for (outputGroup in outputGroups) {
                if (!(outputGroup.startsWith("+") || outputGroup.startsWith("-"))) {
                    addDefaultOutputGroups = false
                    break
                }
            }
            if (addDefaultOutputGroups) {
                current.addAll(defaultOutputGroups!!)
            }

            for (outputGroup in outputGroups) {
                if (outputGroup.startsWith("+")) {
                    current.add(outputGroup.substring(1))
                } else if (outputGroup.startsWith("-")) {
                    current.remove(outputGroup.substring(1))
                } else {
                    current.add(outputGroup)
                }
            }

            // Add the validation output group regardless of the additions and subtractions above.
            when (validationMode) {
                ValidationMode.OUTPUT_GROUP -> current.add(VALIDATION)
                ValidationMode.ASPECT -> current.add(VALIDATION_TOP_LEVEL)
                ValidationMode.OFF -> {
                    // fall out
                }
            }

            // The `test` command ultimately requests artifacts from the `default` output group in order to
            // execute the tests, so we should ensure these artifacts are requested by the targets for
            // proper failure reporting.
            if (shouldRunTests) {
                current.add(DEFAULT)
            }

            return com.google.common.collect.ImmutableSortedSet.copyOf<String?>(current)
        }

        fun singleGroup(group: String, files: NestedSet<Artifact?>): OutputGroupInfo? {
            if (files.isEmpty()) {
                return EmptyFiles.Companion.of(com.google.common.collect.ImmutableSet.of<String?>(group))
            }
            return when (group) {
                HIDDEN_TOP_LEVEL -> HiddenTopLevelOnly(files)
                VALIDATION -> ValidationOnly(files)
                DEFAULT -> DefaultOnly(files)
                else -> OtherGroupOnly(group, files)
            }
        }

        fun fromBuilders(builders: SortedMap<String?, NestedSetBuilder<Artifact?>?>): OutputGroupInfo? {
            val outputGroups: com.google.common.collect.ImmutableMap.Builder<String?, NestedSet<Artifact?>?> =
                com.google.common.collect.ImmutableMap.builderWithExpectedSize<String?, NestedSet<Artifact?>?>(builders.size())
            builders.forEach(java.util.function.BiConsumer { group: String?, files: NestedSetBuilder<Artifact?>? ->
                outputGroups.put(
                    group,
                    files.build()
                )
            })
            return createInternal(outputGroups.buildOrThrow())
        }

        private fun createInternal(
            outputGroups: com.google.common.collect.ImmutableMap<String?, NestedSet<Artifact?>?>
        ): OutputGroupInfo? {
            if (outputGroups.values().stream().allMatch(NestedSet::isEmpty)) {
                val groups:  // keySet retains a reference to the map.
                        com.google.common.collect.ImmutableSet<String?> =
                    com.google.common.collect.ImmutableSet.copyOf<String?>(outputGroups.keySet())
                return EmptyFiles.Companion.of(groups)
            }

            if (outputGroups.size() == 1) {
                val onlyGroup: String? =
                    com.google.common.collect.Iterables.getOnlyElement<String?>(outputGroups.keySet())
                return Companion.singleGroup(onlyGroup!!, outputGroups.get(onlyGroup))
            }

            if (outputGroups.size() == 2) {
                val groups: com.google.common.collect.ImmutableList<String> = outputGroups.keySet().asList()
                if (groups.get(0) == HIDDEN_TOP_LEVEL) {
                    val otherGroup: String = groups.get(1)
                    return HiddenTopLevelAndOneOther(
                        outputGroups.get(HIDDEN_TOP_LEVEL), otherGroup, outputGroups.get(otherGroup)
                    )
                }
            }

            return ArbitraryGroups(ImmutableSharedKeyMap.copyOf(outputGroups))
        }
    }
}
