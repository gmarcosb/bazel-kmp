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
package com.google.devtools.build.lib.analysis.starlark

import com.google.devtools.build.lib.actions.ActionKeyContext

/**
 * Supports `ctx.actions.args()` from Starlark.
 * 
 * 
 * To be as memory-friendly as possible, expansion happens in three stages. First, when a
 * Starlark rule is analyzed, its `Args` are built into a `StarlarkCustomCommandLine`.
 * This is retained in Skyframe, so care is taken to be as compact as possible. At this point, the
 * [representation][.arguments] is just a "recipe" to compute the full command line later
 * on. Additionally, [CommandLine.addToFingerprint] supports computing a fingerprint without
 * actually constructing the expanded command line.
 * 
 * 
 * Second, right before an action executes, [.expand] is
 * called to "preprocess" the recipe into a [PreprocessedCommandLine]. This step includes
 * flattening nested sets and applying any operations that can throw an exception, such as expanding
 * directories and invoking `map_each` functions. At this point, the representation stores a
 * string for each individual argument, but string formatting (including `format`, `format_each`, `before_each`, `join_with`, `format_joined`, and `flag_per_line`), is not yet applied. If `map_each` is not used, path mapping is also not
 * applied yet. This means that in the common case of an [Artifact] with no `map_each`
 * function, the string representation is still its [Artifact.getExecPathString], which is not
 * a novel string instance - it is already stored in the [Artifact]. This is crucial because
 * for param files (the longest command lines), the preprocessed representation is retained
 * throughout the action's execution. If `map_each` is used, path mapping affects the result
 * of the callback and thus needs to be applied eagerly.
 * 
 * 
 * Finally, string formatting and path mapping are applied lazily during iteration over a [ ]. When there is no param file, this happens up front during [ ][CommandLines.expand]. When a
 * param file is used, the lazy [PreprocessedCommandLine.arguments] is stored in a [ ], which is processed by the action execution strategy. Strategies should
 * respect the laziness of [ParamFileActionInput.getArguments] by iterating as few times as
 * possible and not retaining elements longer than necessary.
 * 
 * 
 * As an example, consider this common usage pattern, where `inputs` is a `depset` of
 * artifacts:
 * 
 * <pre>`args = ctx.actions.args() args.use_param_file("--flagfile=%s") args.add_all(inputs, format_each = "--input=%s") `</pre>
 * 
 * During analysis, the nested set is stored without flattening. During preprocessing, the nested
 * set is flattened and [Artifact.expandToCommandLine] is called for each element, but this
 * returns an exec path string instance already stored inside the artifact. `format_each` is
 * not yet applied, so no new strings are created. [SingleStringArgFormatter.format] is only
 * called during iteration over the [PreprocessedCommandLine.arguments]. If path mapping is
 * used, the artifact instances are kept around instead and the mapped exec paths strings are only
 * created during iteration, together with the formatted strings.
 */
open class StarlarkCustomCommandLine private constructor(
    /**
     * Stored as an `Object[]` instead of an [ImmutableList] to save memory, but is never
     * modified. Access via [.rawArgsAsList] for an unmodifiable [List] view.
     */
    private val arguments: Array<Any?>
) : CommandLine() {
    // Used to distinguish command line arguments that are potentially subject to special default
    // stringification (such as Artifacts when path mapped or Labels when not main repo labels) from
    // strings that happen to be identical to their string representations.
    private enum class StringificationType {
        DEFAULT,
        FILE,
        LABEL
    }

    /**
     * Representation of a sequence of arguments originating from `Args.add_all` or `Args.add_joined`.
     */
    @AutoCodec
    internal class VectorArg private constructor(
        private val features: Int,
        private val stringificationType: StringificationType,
        starlarkSemantics: net.starlark.java.eval.StarlarkSemantics?,
        mapEachGlobalFunction: net.starlark.java.eval.StarlarkFunction?
    ) {
        // Null unless map_each is present.
        private val starlarkSemantics: net.starlark.java.eval.StarlarkSemantics?

        // Null unless map_each is a global function.
        private val mapEachGlobalFunction: net.starlark.java.eval.StarlarkFunction?

        init {
            this.starlarkSemantics = starlarkSemantics
            this.mapEachGlobalFunction = mapEachGlobalFunction
        }

        /**
         * Adds this [VectorArg] to the given [PreprocessedCommandLine.Builder].
         * 
         * @param arguments result of [.rawArgsAsList]
         * @param argi index in `arguments` at which this [VectorArg] begins; should be
         * directly preceded by `this`
         * @param builder the [PreprocessedCommandLine.Builder] in which to add a preprocessed
         * representation of this arg
         * @param pathMapper mapper for exec paths
         * @return index in `arguments` where the next arg begins, or `arguments.size()` if
         * this is the last argument
         */
        @Throws(CommandLineExpansionException::class, java.lang.InterruptedException::class)
        private fun preprocess(
            arguments: MutableList<Any?>,
            argi: Int,
            builder: PreprocessedCommandLine.Builder,
            inputMetadataProvider: InputMetadataProvider?,
            pathMapper: PathMapper,
            mainRepoMapping: com.google.devtools.build.lib.cmdline.RepositoryMapping?
        ): Int {
            var argi = argi
            var mapEach: net.starlark.java.eval.StarlarkCallable? = null
            var location: net.starlark.java.syntax.Location? = null
            if ((features and HAS_MAP_EACH) != 0) {
                if (mapEachGlobalFunction != null) {
                    mapEach = mapEachGlobalFunction
                } else {
                    mapEach = arguments.get(argi++) as net.starlark.java.eval.StarlarkCallable?
                }
                location = arguments.get(argi++) as net.starlark.java.syntax.Location?
            }

            val originalValues: MutableList<Any?>
            if ((features and IS_NESTED_SET) != 0) {
                val nestedSet: NestedSet<Any?> = arguments.get(argi++) as NestedSet<Any?>
                originalValues = nestedSet.toList()
            } else {
                val count: Int =
                    (if ((features and VectorArg.Companion.HAS_SINGLE_ARG) != 0) 1 else arguments.get(argi++) as Int?)!!
                originalValues = arguments.subList(argi, argi + count)
                argi += count
            }
            val expandedValues =
                maybeExpandDirectories(inputMetadataProvider, originalValues, pathMapper)
            var values: MutableList<Any>
            if (mapEach != null) {
                values = java.util.ArrayList<Any>(expandedValues.size)
                applyMapEach(
                    mapEach,
                    expandedValues,
                    java.util.function.Consumer { e: String? -> values.add(e!!) },
                    location,
                    inputMetadataProvider,
                    pathMapper,
                    starlarkSemantics
                )
            } else {
                val count = expandedValues.size
                values = java.util.ArrayList<Any>(expandedValues.size)
                for (i in 0..<count) {
                    values.add(expandToCommandLine(expandedValues.get(i), mainRepoMapping)!!)
                }
            }
            // It's safe to uniquify at this stage, any transformations after this
            // will ensure continued uniqueness of the values
            if ((features and UNIQUIFY) != 0) {
                val count = values.size
                val seen: HashSet<String?> = com.google.common.collect.Sets.newHashSetWithExpectedSize<String?>(count)
                var addIndex = 0
                for (i in 0..<count) {
                    val  /* String | DerivedArtifact */`val` = values.get(i)
                    // If the path mapper is a no-op, an artifact behaves just like its (trivially mapped)
                    // exec path string. If the path mapper is not a no-op, mapped paths are always distinct
                    // from unmapped paths. We can thus uniquify based on the mapped exec path string in each
                    // case.
                    if (seen.add(maybePathMap(`val`, pathMapper))) {
                        values.set(addIndex++, `val`)
                    }
                }
                values = values.subList(0, addIndex)
            }
            val omitIfEmpty = (features and OMIT_IF_EMPTY) != 0
            val isEmptyAndShouldOmit = omitIfEmpty && values.isEmpty()
            if ((features and HAS_ARG_NAME) != 0) {
                val argName = arguments.get(argi++) as String
                if (!isEmptyAndShouldOmit) {
                    builder.addString(argName)
                }
            }

            var formatEach: String? = null
            var beforeEach: String? = null
            var joinWith: String? = null
            var formatJoined: String? = null
            if ((features and HAS_FORMAT_EACH) != 0) {
                formatEach = arguments.get(argi++) as String?
            }
            if ((features and HAS_BEFORE_EACH) != 0) {
                beforeEach = arguments.get(argi++) as String?
            } else if ((features and HAS_JOIN_WITH) != 0) {
                joinWith = arguments.get(argi++) as String?
                if ((features and HAS_FORMAT_JOINED) != 0) {
                    formatJoined = arguments.get(argi++) as String?
                }
            }

            // If !omitIfEmpty, joining yields a single argument even if values is empty. Note that
            // the argument may still be non-empty if format_joined is used.
            if (!values.isEmpty() || (!omitIfEmpty && joinWith != null)) {
                val arg =
                    if (joinWith != null)
                        JoinedPreprocessedVectorArg(values, formatEach, joinWith, formatJoined)
                    else
                        UnjoinedPreprocessedVectorArg(values, formatEach, beforeEach)
                builder.addPreprocessedArg(arg)
            }

            if ((features and HAS_TERMINATE_WITH) != 0) {
                val terminateWith = arguments.get(argi++) as String
                if (!isEmptyAndShouldOmit) {
                    builder.addString(terminateWith)
                }
            }
            return argi
        }

        /**
         * Expands the directories if `expand_directories` feature is enabled and an
         * InputMetadataProvider is available.
         * 
         * 
         * Technically, we should always expand the directories if the feature is requested, however
         * we cannot do that in the absence of the [InputMetadataProvider].
         */
        @Throws(CommandLineExpansionException::class)
        private fun maybeExpandDirectories(
            inputMetadataProvider: InputMetadataProvider?,
            originalValues: MutableList<Any?>,
            pathMapper: PathMapper
        ): MutableList<Any?> {
            if ((features and EXPAND_DIRECTORIES) == 0 || inputMetadataProvider == null || !hasDirectory(originalValues)) {
                return originalValues
            }

            return expandDirectories(inputMetadataProvider, originalValues, pathMapper)
        }

        @Throws(CommandLineExpansionException::class, java.lang.InterruptedException::class)
        private fun addToFingerprint(
            arguments: MutableList<Any?>,
            argi: Int,
            actionKeyContext: ActionKeyContext,
            fingerprint: Fingerprint,
            inputMetadataProvider: InputMetadataProvider?,
            outputPathsMode: CoreOptions.OutputPathsMode,
            mainRepoMapping: com.google.devtools.build.lib.cmdline.RepositoryMapping
        ): Int {
            var argi = argi
            var mapEach: net.starlark.java.eval.StarlarkCallable? = null
            var location: net.starlark.java.syntax.Location? = null
            if ((features and HAS_MAP_EACH) != 0) {
                if (mapEachGlobalFunction != null) {
                    mapEach = mapEachGlobalFunction
                } else {
                    mapEach = arguments.get(argi++) as net.starlark.java.eval.StarlarkCallable?
                }
                location = arguments.get(argi++) as net.starlark.java.syntax.Location?
            }

            // NestedSets and lists never result in the same fingerprint as the
            // ActionKeyContext#addNestedSetToFingerprint call below always adds the order of the
            // NestedSet to the fingerprint.
            //
            // Path mapping may affect the default stringification of Artifact instances at execution
            // time, but the effect of path mapping on an individual command line element is a pure
            // function of:
            // * whether the element is of type Artifact (FileApi in Starlark), which is fingerprinted
            //   via the elementType UUID below;
            // * the path of the artifact, which is fingerprinted via its default string representation
            //   below;
            // * the paths and possibly the digests of all input artifacts as well as the path mapping
            //   mode, which are fingerprinted by SpawnAction.
            // It is thus safe to ignore pathMapper below for anything that relies on the default
            // stringification behavior (which excludes custom mapEach functions).
            if ((features and IS_NESTED_SET) != 0) {
                val values: NestedSet<*>? = arguments.get(argi++) as NestedSet<*>?
                if (mapEach != null) {
                    // mapEach functions do not rely on default stringification behavior, so we can omit
                    // fingerprinting stringificationType here.
                    val commandLineItemMapFn =
                        CommandLineItemMapEachAdaptor(
                            mapEach,
                            location,
                            starlarkSemantics,
                            if ((features and EXPAND_DIRECTORIES) != 0 || wantsDirectoryExpander(mapEach))
                                inputMetadataProvider
                            else
                                null,
                            outputPathsMode
                        )
                    try {
                        actionKeyContext.addNestedSetToFingerprint(commandLineItemMapFn, fingerprint, values)
                    } finally {
                        // The cache holds an entry for a NestedSet for every (map_fn, hasInputMetadataProvider
                        // bit, pathMapperCacheKey).
                        // Clearing the input metadata provider itself saves us from storing the contents of it
                        // in the cache keys (it is no longer needed after we evaluate the value).
                        // NestedSet cache is cleared after every build, which means that the input metadata
                        // provider for a given action, if present, cannot change within the lifetime of the
                        // fingerprintcache (we call getKey with inputMetadataProvider to check action key, when
                        // we are ready to execute the action in case of a cache miss).
                        commandLineItemMapFn.clearInputMetadataProvider()
                    }
                } else {
                    fingerprint.addInt(stringificationType.ordinal)
                    if (stringificationType == StringificationType.LABEL) {
                        fingerprint.addStringMap(
                            com.google.common.collect.Maps.transformValues<String?, RepositoryName?, String?>(
                                mainRepoMapping.entries(),
                                com.google.common.base.Function { obj: RepositoryName? -> obj.getName() })
                        )
                    }
                    actionKeyContext.addNestedSetToFingerprint(fingerprint, values)
                }
            } else {
                val count: Int =
                    (if ((features and VectorArg.Companion.HAS_SINGLE_ARG) != 0) 1 else arguments.get(argi++) as Int?)!!
                val maybeExpandedValues =
                    maybeExpandDirectories(
                        inputMetadataProvider,
                        arguments.subList(argi, argi + count),
                        PathMapper.forActionKey(outputPathsMode)
                    )
                argi += count
                if (mapEach != null) {
                    // TODO(b/160181927): If inputMetadataProvider == null (happens in the analysis phase)
                    // but expandDirectories is true, we run the map_each function on directory values without
                    // actually expanding them. This differs from the real evaluation behavior. This means
                    // that we can erroneously produce the same digest for two command lines that differ only
                    // in their directory expansion. Fortunately, this is only a problem for shared action
                    // conflict checking/aquery result, since at execution time we have an input metadata
                    // provider.
                    applyMapEach(
                        mapEach,
                        maybeExpandedValues,
                        java.util.function.Consumer { input: String? -> fingerprint.addString(input) },
                        location,
                        inputMetadataProvider,
                        PathMapper.forActionKey(outputPathsMode),
                        starlarkSemantics
                    )
                } else {
                    for (value in maybeExpandedValues) {
                        addSingleObjectToFingerprint(fingerprint, value, mainRepoMapping)
                    }
                }
            }
            if ((features and EXPAND_DIRECTORIES) != 0) {
                fingerprint.addUUID(EXPAND_DIRECTORIES_UUID)
            }
            if ((features and UNIQUIFY) != 0) {
                fingerprint.addUUID(UNIQUIFY_UUID)
            }
            if ((features and OMIT_IF_EMPTY) != 0) {
                fingerprint.addUUID(OMIT_IF_EMPTY_UUID)
            }
            if ((features and HAS_ARG_NAME) != 0) {
                val argName = arguments.get(argi++) as String?
                fingerprint.addUUID(ARG_NAME_UUID)
                fingerprint.addString(argName)
            }
            if ((features and HAS_FORMAT_EACH) != 0) {
                val formatStr = arguments.get(argi++) as String?
                fingerprint.addUUID(FORMAT_EACH_UUID)
                fingerprint.addString(formatStr)
            }
            if ((features and HAS_BEFORE_EACH) != 0) {
                val beforeEach = arguments.get(argi++) as String?
                fingerprint.addUUID(BEFORE_EACH_UUID)
                fingerprint.addString(beforeEach)
            } else if ((features and HAS_JOIN_WITH) != 0) {
                val joinWith = arguments.get(argi++) as String?
                fingerprint.addUUID(JOIN_WITH_UUID)
                fingerprint.addString(joinWith)
                if ((features and HAS_FORMAT_JOINED) != 0) {
                    val formatJoined = arguments.get(argi++) as String?
                    fingerprint.addUUID(FORMAT_JOINED_UUID)
                    fingerprint.addString(formatJoined)
                }
            }
            if ((features and HAS_TERMINATE_WITH) != 0) {
                val terminateWith = arguments.get(argi++) as String?
                fingerprint.addUUID(TERMINATE_WITH_UUID)
                fingerprint.addString(terminateWith)
            }
            return argi
        }

        internal class Builder {
            private val list: net.starlark.java.eval.Sequence<*>?
            private val nestedSet: NestedSet<*>?
            private val nestedSetStringificationType: StringificationType
            private var location: net.starlark.java.syntax.Location? = null
            private var argName: String? = null
            private var expandDirectories = false
            private var mapEach: net.starlark.java.eval.StarlarkCallable? = null
            private var formatEach: String? = null
            private var beforeEach: String? = null
            private var joinWith: String? = null
            private var formatJoined: String? = null
            private var omitIfEmpty = false
            private var uniquify = false
            private var terminateWith: String? = null

            constructor(list: net.starlark.java.eval.Sequence<*>?) {
                this.list = list
                this.nestedSet = null
                this.nestedSetStringificationType = StringificationType.DEFAULT
            }

            constructor(nestedSet: NestedSet<*>?, nestedSetElementType: java.lang.Class<*>?) {
                this.list = null
                this.nestedSet = nestedSet
                if (nestedSetElementType == FileApi::class.java) {
                    this.nestedSetStringificationType = StringificationType.FILE
                } else if (nestedSetElementType == com.google.devtools.build.lib.cmdline.Label::class.java) {
                    this.nestedSetStringificationType = StringificationType.LABEL
                } else {
                    this.nestedSetStringificationType = StringificationType.DEFAULT
                }
            }

            @com.google.errorprone.annotations.CanIgnoreReturnValue
            fun setLocation(location: net.starlark.java.syntax.Location?): Builder {
                this.location = location
                return this
            }

            @com.google.errorprone.annotations.CanIgnoreReturnValue
            fun setArgName(argName: String?): Builder {
                this.argName = argName
                return this
            }

            @com.google.errorprone.annotations.CanIgnoreReturnValue
            fun setExpandDirectories(expandDirectories: Boolean): Builder {
                this.expandDirectories = expandDirectories
                return this
            }

            @com.google.errorprone.annotations.CanIgnoreReturnValue
            fun setMapEach(mapEach: net.starlark.java.eval.StarlarkCallable?): Builder {
                this.mapEach = mapEach
                return this
            }

            @com.google.errorprone.annotations.CanIgnoreReturnValue
            fun setFormatEach(format: String?): Builder {
                this.formatEach = format
                return this
            }

            @com.google.errorprone.annotations.CanIgnoreReturnValue
            fun setBeforeEach(beforeEach: String?): Builder {
                this.beforeEach = beforeEach
                return this
            }

            @com.google.errorprone.annotations.CanIgnoreReturnValue
            fun setJoinWith(joinWith: String?): Builder {
                this.joinWith = joinWith
                return this
            }

            @com.google.errorprone.annotations.CanIgnoreReturnValue
            fun setFormatJoined(formatJoined: String?): Builder {
                this.formatJoined = formatJoined
                return this
            }

            @com.google.errorprone.annotations.CanIgnoreReturnValue
            fun omitIfEmpty(omitIfEmpty: Boolean): Builder {
                this.omitIfEmpty = omitIfEmpty
                return this
            }

            @com.google.errorprone.annotations.CanIgnoreReturnValue
            fun uniquify(uniquify: Boolean): Builder {
                this.uniquify = uniquify
                return this
            }

            @com.google.errorprone.annotations.CanIgnoreReturnValue
            fun setTerminateWith(terminateWith: String?): Builder {
                this.terminateWith = terminateWith
                return this
            }
        }

        override fun equals(o: Any?): Boolean {
            if (this === o) {
                return true
            }
            if (o !is VectorArg) {
                return false
            }
            return features == o.features && stringificationType == o.stringificationType
                    && starlarkSemantics == o.starlarkSemantics // Use reference equality to avoid resurrecting a weakly-reachable but value-equal
                    // StarlarkFunction instance. Value-equal instances may be created when a .bzl file is
                    // re-evaluated.
                    && mapEachGlobalFunction === o.mapEachGlobalFunction
        }

        override fun hashCode(): Int {
            var result: Int = HashCodes.hashObjects(stringificationType, starlarkSemantics)
            result = 31 * result + java.lang.Integer.hashCode(features)
            result = 31 * result + java.lang.System.identityHashCode(mapEachGlobalFunction)
            return result
        }

        companion object {
            // The strong interner is used when StarlarkSemantics is null. The weak interner is used when
            // StarlarkSemantics is present (implying a map_each), since StarlarkSemantics can change
            // between builds.
            private val strongInterner: com.google.common.collect.Interner<VectorArg> =
                com.google.devtools.build.lib.concurrent.BlazeInterners.newStrongInterner<VectorArg?>()
            private val weakInterner: com.google.common.collect.Interner<VectorArg?> =
                com.google.devtools.build.lib.concurrent.BlazeInterners.newWeakInterner<VectorArg?>()

            private const val HAS_MAP_EACH = 1
            private val IS_NESTED_SET = 1 shl 1
            private val EXPAND_DIRECTORIES = 1 shl 2
            private val UNIQUIFY = 1 shl 3
            private val OMIT_IF_EMPTY = 1 shl 4
            private val HAS_ARG_NAME = 1 shl 5
            private val HAS_FORMAT_EACH = 1 shl 6
            private val HAS_BEFORE_EACH = 1 shl 7
            private val HAS_JOIN_WITH = 1 shl 8
            private val HAS_FORMAT_JOINED = 1 shl 9
            private val HAS_TERMINATE_WITH = 1 shl 10
            private val HAS_SINGLE_ARG = 1 shl 11

            private val EXPAND_DIRECTORIES_UUID: UUID = UUID.fromString("9d7520d2-a187-11e8-98d0-529269fb1459")
            private val UNIQUIFY_UUID: UUID = UUID.fromString("7f494c3e-faea-4498-a521-5d3bc6ee19eb")
            private val OMIT_IF_EMPTY_UUID: UUID = UUID.fromString("923206f1-6474-4a8f-b30f-4dd3143622e6")
            private val ARG_NAME_UUID: UUID = UUID.fromString("2bc00382-7199-46ec-ad52-1556577cde1a")
            private val FORMAT_EACH_UUID: UUID = UUID.fromString("8e974aec-df07-4a51-9418-f4c1172b4045")
            private val BEFORE_EACH_UUID: UUID = UUID.fromString("f7e101bc-644d-4277-8562-6515ad55a988")
            private val JOIN_WITH_UUID: UUID = UUID.fromString("c227dbd3-edad-454e-bc8a-c9b5ba1c38a3")
            private val FORMAT_JOINED_UUID: UUID = UUID.fromString("528af376-4233-4c27-be4d-b0ff24ed68db")
            private val TERMINATE_WITH_UUID: UUID = UUID.fromString("a4e5e090-0dbd-4d41-899a-77cfbba58655")

            private fun create(
                features: Int,
                stringificationType: StringificationType,
                starlarkSemantics: net.starlark.java.eval.StarlarkSemantics?,
                mapEachGlobalFunction: net.starlark.java.eval.StarlarkFunction?
            ): VectorArg {
                return intern(
                    VectorArg(features, stringificationType, starlarkSemantics, mapEachGlobalFunction)
                )
            }

            @com.google.devtools.build.lib.skyframe.serialization.VisibleForSerialization
            @AutoCodec.Interner
            fun intern(vectorArg: VectorArg): VectorArg {
                val interner: com.google.common.collect.Interner<VectorArg?> =
                    if (vectorArg.starlarkSemantics == null) strongInterner else weakInterner
                return interner.intern(vectorArg)
            }

            private fun push(
                arguments: MutableList<Any?>, arg: Builder, starlarkSemantics: net.starlark.java.eval.StarlarkSemantics?
            ) {
                // The location is really only needed if map_each is present, but it's easy enough to require
                // it unconditionally.
                com.google.common.base.Preconditions.checkNotNull<net.starlark.java.syntax.Location?>(arg.location)

                var features = 0
                features = features or if (arg.mapEach != null) HAS_MAP_EACH else 0
                features = features or if (arg.nestedSet != null) IS_NESTED_SET else 0
                features = features or if (arg.expandDirectories) EXPAND_DIRECTORIES else 0
                features = features or if (arg.uniquify) UNIQUIFY else 0
                features = features or if (arg.omitIfEmpty) OMIT_IF_EMPTY else 0
                features = features or if (arg.argName != null) HAS_ARG_NAME else 0
                features = features or if (arg.formatEach != null) HAS_FORMAT_EACH else 0
                features = features or if (arg.beforeEach != null) HAS_BEFORE_EACH else 0
                features = features or if (arg.joinWith != null) HAS_JOIN_WITH else 0
                features = features or if (arg.formatJoined != null) HAS_FORMAT_JOINED else 0
                features = features or if (arg.terminateWith != null) HAS_TERMINATE_WITH else 0
                features = features or if (arg.nestedSet == null && arg.list.size == 1) HAS_SINGLE_ARG else 0
                // Intern global Starlark functions in the VectorArg as they can be reused for all rule
                // instances (and possibly even across multiple Args.add_all calls), saving a slot in
                // arguments.
                val mapEachGlobalFunction: net.starlark.java.eval.StarlarkFunction? =
                    if (arg.mapEach is net.starlark.java.eval.StarlarkFunction && mapEach.isGlobal()) mapEach else null
                arguments.add(
                    create(
                        features,
                        arg.nestedSetStringificationType,
                        if (arg.mapEach != null) starlarkSemantics else null,
                        mapEachGlobalFunction
                    )
                )
                if (arg.mapEach != null) {
                    if (mapEachGlobalFunction == null) {
                        arguments.add(arg.mapEach)
                    }
                    arguments.add(arg.location)
                }
                if (arg.nestedSet != null) {
                    arguments.add(arg.nestedSet)
                } else {
                    val list: MutableList<*>? = arg.list
                    val count = list!!.size
                    if (count != 1) {
                        // A count of 1 is encoded via the HAS_SINGLE_ARG feature.
                        arguments.add(count)
                    }
                    for (i in 0..<count) {
                        arguments.add(list.get(i))
                    }
                }
                if (arg.argName != null) {
                    arguments.add(arg.argName)
                }
                if (arg.formatEach != null) {
                    arguments.add(arg.formatEach)
                }
                if (arg.beforeEach != null) {
                    com.google.common.base.Preconditions.checkState(
                        arg.joinWith == null,
                        "before_each and join_with are mutually exclusive"
                    )
                    com.google.common.base.Preconditions.checkState(
                        arg.formatJoined == null, "before_each and format_joined are mutually exclusive"
                    )
                    arguments.add(arg.beforeEach)
                }
                if (arg.joinWith != null) {
                    arguments.add(arg.joinWith)
                }
                if (arg.formatJoined != null) {
                    com.google.common.base.Preconditions.checkNotNull<String?>(
                        arg.joinWith,
                        "format_joined requires join_with"
                    )
                    arguments.add(arg.formatJoined)
                }
                if (arg.terminateWith != null) {
                    arguments.add(arg.terminateWith)
                }
            }

            private fun hasDirectory(originalValues: MutableList<Any?>): Boolean {
                val n = originalValues.size
                for (i in 0..<n) {
                    val `object` = originalValues.get(i)
                    if (isDirectory(`object`)) {
                        return true
                    }
                }
                return false
            }

            private fun isDirectory(`object`: Any?): Boolean {
                return `object` is Artifact && `object`.isDirectory()
            }

            @Throws(CommandLineExpansionException::class)
            private fun expandDirectories(
                inputMetadataProvider: InputMetadataProvider,
                originalValues: MutableList<Any?>,
                pathMapper: PathMapper
            ): MutableList<Any?> {
                val expandedValues: MutableList<Any?> = java.util.ArrayList<Any?>(originalValues.size)
                for (`object` in originalValues) {
                    if (isDirectory(`object`)) {
                        val artifact: Artifact = `object` as Artifact
                        if (artifact.isTreeArtifact()) {
                            val treeArtifactValue: TreeArtifactValue = inputMetadataProvider.getTreeMetadata(artifact)
                            if (treeArtifactValue == null) {
                                throw CommandLineExpansionException(
                                    String.format(
                                        ("Failed to expand directory %s. Either add the directory as an input of the"
                                                + " action or set 'expand_directories = False' in the 'add_all' or"
                                                + " 'add_joined' call to have the path of the directory added to the"
                                                + " command line instead of its contents."),
                                        net.starlark.java.eval.Starlark.repr(
                                            artifact,
                                            net.starlark.java.eval.StarlarkSemantics.DEFAULT
                                        )
                                    )
                                )
                            }
                            expandedValues.addAll(treeArtifactValue.getChildren())
                        } else if (artifact.isFileset()) {
                            expandFileset(inputMetadataProvider, artifact, expandedValues, pathMapper)
                        } else {
                            throw java.lang.AssertionError("Unknown artifact type.")
                        }
                    } else {
                        expandedValues.add(`object`)
                    }
                }
                return expandedValues
            }

            @Throws(CommandLineExpansionException::class)
            private fun expandFileset(
                inputMetadataProvider: InputMetadataProvider,
                fileset: Artifact,
                expandedValues: MutableList<Any?>,
                pathMapper: PathMapper
            ) {
                val filesetOutput: FilesetOutputTree = inputMetadataProvider.getFileset(fileset)
                if (filesetOutput == null) {
                    throw CommandLineExpansionException(
                        String.format(
                            "Could not expand fileset: %s. Did you forget to add it as an input of the action?",
                            fileset
                        )
                    )
                }
                val mappedExecPath: PathFragment = pathMapper.map(fileset.getExecPath())
                for (link in filesetOutput.symlinks()) {
                    expandedValues.add(
                        FilesetSymlinkFile(fileset, mappedExecPath.getRelative(link.name()))
                    )
                }
            }
        }
    }

    /** Representation of a single formatted argument originating from `Args.add`  */
    private object SingleFormattedArg {
        private val SINGLE_FORMATTED_ARG_UUID: UUID = UUID.fromString("8cb96642-a235-4fe0-b3ed-ebfdae8a0bd9")

        fun push(arguments: MutableList<Any?>, `object`: Any?, format: String?) {
            arguments.add(SINGLE_FORMATTED_ARG_MARKER)
            arguments.add(`object`)
            arguments.add(format)
        }

        /**
         * Adds a [SingleFormattedArg] to the given [PreprocessedCommandLine.Builder].
         * 
         * @param arguments result of [.rawArgsAsList]
         * @param argi index in `arguments` at which the [SingleFormattedArg] begins; should
         * be directly preceded by [.SINGLE_FORMATTED_ARG_MARKER]
         * @param builder the [PreprocessedCommandLine.Builder] in which to add a preprocessed
         * representation of this arg
         * @param mainRepoMapping the repository mapping to use for formatting labels if needed
         * @return index in `arguments` where the next arg begins, or `arguments.size()` if
         * there are no more arguments
         */
        fun preprocess(
            arguments: MutableList<Any?>,
            argi: Int,
            builder: PreprocessedCommandLine.Builder,
            mainRepoMapping: com.google.devtools.build.lib.cmdline.RepositoryMapping?
        ): Int {
            var argi = argi
            val `object` = arguments.get(argi++)
            val formatStr = arguments.get(argi++) as String?
            when (expandToCommandLine(`object`, mainRepoMapping)) {
                -> builder.addPreprocessedArg(
                    PreprocessedSingleFormattedArtifactArg(formatStr, derivedArtifact)
                )

                -> builder.addPreprocessedArg(PreprocessedSingleFormattedArg(formatStr, stringValue))
                else -> throw java.lang.AssertionError("Unexpected object type: " + `object`)
            }
            return argi
        }

        fun addToFingerprint(
            arguments: MutableList<Any?>,
            argi: Int,
            fingerprint: Fingerprint,
            mainRepoMapping: com.google.devtools.build.lib.cmdline.RepositoryMapping?
        ): Int {
            var argi = argi
            val `object` = arguments.get(argi++)
            addSingleObjectToFingerprint(fingerprint, `object`, mainRepoMapping)
            val formatStr = arguments.get(argi++) as String?
            fingerprint.addString(formatStr)
            fingerprint.addUUID(SINGLE_FORMATTED_ARG_UUID)
            return argi
        }
    }

    internal class Builder(starlarkSemantics: net.starlark.java.eval.StarlarkSemantics?) {
        private val starlarkSemantics: net.starlark.java.eval.StarlarkSemantics
        private val arguments: MutableList<Any?> = java.util.ArrayList<Any?>()

        // Indexes in arguments list where individual args begin
        private val argStartIndexes: com.google.common.collect.ImmutableList.Builder<Int?> =
            com.google.common.collect.ImmutableList.builder<Int?>()

        init {
            this.starlarkSemantics =
                com.google.common.base.Preconditions.checkNotNull<net.starlark.java.eval.StarlarkSemantics>(
                    starlarkSemantics
                )
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun recordArgStart(): Builder {
            if (!arguments.isEmpty()) {
                argStartIndexes.add(arguments.size())
            }
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun add(`object`: Any?): Builder {
            arguments.add(`object`)
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun add(vectorArg: VectorArg.Builder): Builder {
            VectorArg.Companion.push(arguments, vectorArg, starlarkSemantics)
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun addFormatted(`object`: Any?, format: String?): Builder {
            com.google.common.base.Preconditions.checkNotNull<Any?>(`object`)
            com.google.common.base.Preconditions.checkNotNull<String?>(format)
            SingleFormattedArg.push(arguments, `object`, format)
            return this
        }

        fun build(
            flagPerLine: Boolean,
            mainRepoMapping: com.google.devtools.build.lib.cmdline.RepositoryMapping?
        ): CommandLine? {
            if (arguments.isEmpty()) {
                return CommandLine.empty()
            }
            val args: Array<Any?>?
            if (mainRepoMapping != null) {
                args = arguments.toArray<Any?>(arrayOfNulls<Any>(arguments.size() + 1))
                args!![arguments.size()] = mainRepoMapping
            } else {
                args = arguments.toArray()
            }
            return if (flagPerLine)
                StarlarkCustomCommandLineWithIndexes(args!!, argStartIndexes.build())
            else
                StarlarkCustomCommandLine(args!!)
        }
    }

    /** Wraps [.arguments] in an unmodifiable [List] view.  */
    private fun rawArgsAsList(): MutableList<Any?> {
        return Collections.unmodifiableList<Any?>(java.util.Arrays.asList<Any?>(*arguments))
    }

    @Throws(CommandLineExpansionException::class, java.lang.InterruptedException::class)
    public override fun expand(): ArgChunk {
        return expand(null, PathMapper.NOOP)
    }

    @Throws(CommandLineExpansionException::class, java.lang.InterruptedException::class)
    public override fun expand(
        inputMetadataProvider: InputMetadataProvider?, pathMapper: PathMapper
    ): ArgChunk {
        val builder: PreprocessedCommandLine.Builder =
            com.google.devtools.build.lib.analysis.starlark.StarlarkCustomCommandLine.PreprocessedCommandLine.Builder()
        val arguments = rawArgsAsList()

        val mainRepoMapping: com.google.devtools.build.lib.cmdline.RepositoryMapping?
        val size: Int
        // Added in #build() if any labels in the command line require this to be formatted with an
        // apparent repository name.
        if (arguments.getLast() is com.google.devtools.build.lib.cmdline.RepositoryMapping) {
            mainRepoMapping = arguments.getLast() as com.google.devtools.build.lib.cmdline.RepositoryMapping?
            size = arguments.size() - 1
        } else {
            mainRepoMapping = null
            size = arguments.size()
        }

        var argi = 0
        while (argi < size) {
            val arg = arguments.get(argi++)
            if (arg is VectorArg) {
                argi =
                    arg
                        .preprocess(
                            arguments, argi, builder, inputMetadataProvider, pathMapper, mainRepoMapping
                        )
            } else if (arg === SINGLE_FORMATTED_ARG_MARKER) {
                argi = SingleFormattedArg.preprocess(arguments, argi, builder, mainRepoMapping)
            } else {
                builder.addArg(expandToCommandLine(arg, mainRepoMapping)!!)
            }
        }
        return pathMapper.mapCustomStarlarkArgs(builder.build())
    }

    @Throws(CommandLineExpansionException::class, java.lang.InterruptedException::class)
    public override fun arguments(): Iterable<String?> {
        return expand().arguments(PathMapper.NOOP)
    }

    @Throws(CommandLineExpansionException::class, java.lang.InterruptedException::class)
    public override fun arguments(
        inputMetadataProvider: InputMetadataProvider?, pathMapper: PathMapper
    ): Iterable<String?> {
        return expand(inputMetadataProvider, pathMapper).arguments(pathMapper)
    }

    private class StarlarkCustomCommandLineWithIndexes(
        arguments: Array<Any?>,
        argStartIndexes: com.google.common.collect.ImmutableList<Int?>
    ) : StarlarkCustomCommandLine(arguments) {
        /**
         * An extra level of grouping on top of the 'arguments' list. Each element is the start of a
         * group of args, with index 0 omitted. For example, if this contains 3, then arguments 0, 1 and
         * 2 constitute the first group, and arguments 3 to the end constitute the next. The expanded
         * version of these arguments will be concatenated together to support `flag_per_line`
         * format.
         */
        private val argStartIndexes: com.google.common.collect.ImmutableList<Int?>

        init {
            this.argStartIndexes = argStartIndexes
        }

        @Throws(CommandLineExpansionException::class, java.lang.InterruptedException::class)
        override fun expand(
            inputMetadataProvider: InputMetadataProvider?, pathMapper: PathMapper
        ): ArgChunk {
            val builder: PreprocessedCommandLine.Builder =
                com.google.devtools.build.lib.analysis.starlark.StarlarkCustomCommandLine.PreprocessedCommandLine.Builder()
            val arguments = (this as StarlarkCustomCommandLine).rawArgsAsList()
            val startIndexIterator: MutableIterator<Int?> = argStartIndexes.iterator()

            val mainRepoMapping: com.google.devtools.build.lib.cmdline.RepositoryMapping?
            val size: Int
            if (arguments.getLast() is com.google.devtools.build.lib.cmdline.RepositoryMapping) {
                mainRepoMapping = arguments.getLast() as com.google.devtools.build.lib.cmdline.RepositoryMapping?
                size = arguments.size() - 1
            } else {
                mainRepoMapping = null
                size = arguments.size()
            }

            var argi = 0
            while (argi < size) {
                val nextStartIndex: Int = (if (startIndexIterator.hasNext()) startIndexIterator.next() else size)!!
                val line: PreprocessedCommandLine.Builder =
                    com.google.devtools.build.lib.analysis.starlark.StarlarkCustomCommandLine.PreprocessedCommandLine.Builder()

                while (argi < nextStartIndex) {
                    val arg = arguments.get(argi++)
                    if (arg is VectorArg) {
                        argi =
                            arg
                                .preprocess(
                                    arguments, argi, line, inputMetadataProvider, pathMapper, mainRepoMapping
                                )
                    } else if (arg === SINGLE_FORMATTED_ARG_MARKER) {
                        argi = SingleFormattedArg.preprocess(arguments, argi, line, mainRepoMapping)
                    } else {
                        line.addArg(expandToCommandLine(arg, mainRepoMapping)!!)
                    }
                }

                builder.addLineForFlagPerLine(line)
            }

            return pathMapper.mapCustomStarlarkArgs(builder.build())
        }
    }

    @Throws(CommandLineExpansionException::class, java.lang.InterruptedException::class)
    public override fun addToFingerprint(
        actionKeyContext: ActionKeyContext,
        inputMetadataProvider: InputMetadataProvider?,
        effectiveOutputPathsMode: CoreOptions.OutputPathsMode,
        fingerprint: Fingerprint
    ) {
        val arguments = rawArgsAsList()
        val size: Int
        val mainRepoMapping: com.google.devtools.build.lib.cmdline.RepositoryMapping?
        if (arguments.getLast() is com.google.devtools.build.lib.cmdline.RepositoryMapping) {
            mainRepoMapping = mapping
            size = arguments.size() - 1
        } else {
            mainRepoMapping = null
            size = arguments.size()
        }
        var argi = 0
        while (argi < size) {
            val arg = arguments.get(argi++)
            if (arg is VectorArg) {
                argi =
                    arg
                        .addToFingerprint(
                            arguments,
                            argi,
                            actionKeyContext,
                            fingerprint,
                            inputMetadataProvider,
                            effectiveOutputPathsMode,
                            mainRepoMapping
                        )
            } else if (arg === SINGLE_FORMATTED_ARG_MARKER) {
                argi = SingleFormattedArg.addToFingerprint(arguments, argi, fingerprint, mainRepoMapping)
            } else {
                addSingleObjectToFingerprint(fingerprint, arg, mainRepoMapping)
            }
        }
    }

    /** Used during action key evaluation when we don't have an input metadata provider.  */
    private class NoopExpander : DirectoryExpander {
        override fun list(file: FileApi): com.google.common.collect.ImmutableList<FileApi?> {
            return com.google.common.collect.ImmutableList.of<FileApi?>(file)
        }

        companion object {
            val INSTANCE: DirectoryExpander = NoopExpander()
        }
    }

    private class FullExpander(inputMetadataProvider: InputMetadataProvider) : DirectoryExpander {
        private val inputMetadataProvider: InputMetadataProvider

        init {
            this.inputMetadataProvider = inputMetadataProvider
        }

        @Throws(net.starlark.java.eval.EvalException::class)
        override fun list(file: FileApi?): com.google.common.collect.ImmutableList<FileApi?> {
            val artifact: Artifact = file as Artifact
            if (artifact.isTreeArtifact()) {
                val treeArtifactValue: TreeArtifactValue = inputMetadataProvider.getTreeMetadata(artifact)
                if (treeArtifactValue == null) {
                    throw net.starlark.java.eval.Starlark.errorf(
                        "Failed to expand directory %s. Only directories that are action inputs can be"
                                + " expanded.",
                        net.starlark.java.eval.Starlark.repr(artifact, net.starlark.java.eval.StarlarkSemantics.DEFAULT)
                    )
                }

                return com.google.common.collect.ImmutableList.< E > copyOf < E ? > (treeArtifactValue.getChildren())
            } else {
                return com.google.common.collect.ImmutableList.of<FileApi?>(file)
            }
        }
    }

    private class CommandLineItemMapEachAdaptor
        (
        mapFn: net.starlark.java.eval.StarlarkCallable?,
        location: net.starlark.java.syntax.Location?,
        starlarkSemantics: net.starlark.java.eval.StarlarkSemantics?,
        inputMetadataProvider: InputMetadataProvider?,
        outputPathsMode: CoreOptions.OutputPathsMode
    ) : CommandLineItem.ParametrizedMapFn<Any?>() {
        private val mapFn: net.starlark.java.eval.StarlarkCallable?
        private val location: net.starlark.java.syntax.Location?
        private val starlarkSemantics: net.starlark.java.eval.StarlarkSemantics?

        /**
         * Indicates whether an input metadata provider was provided on construction. This is used to
         * distinguish the case where it's not provided from the case where it was provided but
         * subsequently cleared.
         */
        private val hasInputMetadataProvider: Boolean

        private val outputPathsMode: CoreOptions.OutputPathsMode

        private var inputMetadataProvider: InputMetadataProvider?

        init {
            this.mapFn = mapFn
            this.location = location
            this.starlarkSemantics = starlarkSemantics
            this.hasInputMetadataProvider = inputMetadataProvider != null
            this.inputMetadataProvider = inputMetadataProvider
            this.outputPathsMode = outputPathsMode
        }

        @Throws(CommandLineExpansionException::class, java.lang.InterruptedException::class)
        public override fun expandToCommandLine(`object`: Any, args: java.util.function.Consumer<String?>) {
            com.google.common.base.Preconditions.checkState(inputMetadataProvider != null || !hasInputMetadataProvider)
            applyMapEach(
                mapFn,
                maybeExpandDirectory(`object`),
                args,
                location,
                inputMetadataProvider,
                PathMapper.forActionKey(outputPathsMode),
                starlarkSemantics
            )
        }

        @Throws(CommandLineExpansionException::class)
        fun maybeExpandDirectory(`object`: Any): MutableList<Any?> {
            if (inputMetadataProvider == null || !VectorArg.Companion.isDirectory(`object`)) {
                return com.google.common.collect.ImmutableList.of<Any?>(`object`)
            }

            return VectorArg.Companion.expandDirectories(
                inputMetadataProvider,
                com.google.common.collect.ImmutableList.of<Any?>(`object`),
                PathMapper.forActionKey(outputPathsMode)
            )
        }

        override fun equals(obj: Any?): Boolean {
            if (obj !is CommandLineItemMapEachAdaptor) {
                return false
            }
            // Instance compare intentional
            // The normal implementation uses location + name of function,
            // which can conceivably conflict in tests
            // We only compare presence of inputMetadataProvider vs absence of it since the nested set
            // fingerprint cache is emptied after every build, therefore if the artifact expander is
            // provided, it will be the same.
            return mapFn === obj.mapFn && hasInputMetadataProvider == obj.hasInputMetadataProvider && outputPathsMode === obj.outputPathsMode
        }

        override fun hashCode(): Int {
            // Force use of identityHashCode, in case the callable uses a custom hash function. (As of
            // this writing, only providers seem to have a custom hashCode, and those shouldn't be used
            // as map_each functions, but doesn't hurt to be safe...).
            return (outputPathsMode.hashCode()
                    + 31
                    * (java.lang.Boolean.hashCode(hasInputMetadataProvider)
                    + 31 * (java.lang.System.identityHashCode(mapFn) + 1)))
        }

        public override fun maxInstancesAllowed(): Int {
            // No limit to these, as this is just a wrapper for Starlark functions, which are
            // always static
            return java.lang.Integer.MAX_VALUE
        }

        /**
         * Clears the input metadata provider in order not to prolong the lifetime of it unnecessarily.
         * 
         * 
         * Although this operation technically changes this object, it can be called after we add the
         * object to a [HashSet]. Clearing inputMetadataProvider does not affect the result of
         * [.equals] or [.hashCode]. Please note that once we call this function, we can no
         * longer call [.expandToCommandLine].
         */
        fun clearInputMetadataProvider() {
            inputMetadataProvider = null
        }
    }

    /**
     * When we expand filesets the user might still expect a File object (since the results may be fed
     * into map_each. Therefore we synthesize a File object from the fileset symlink.
     */
    internal class FilesetSymlinkFile(fileset: Artifact, execPath: PathFragment) : FileApi, CommandLineItem {
        private val fileset: Artifact
        private val execPath: PathFragment

        init {
            this.fileset = fileset
            this.execPath = execPath
        }

        override fun getDirnameForStarlark(semantics: net.starlark.java.eval.StarlarkSemantics?): String? {
            val parent: PathFragment? = execPath.getParentDirectory()
            return if (parent == null) "/" else parent.getSafePathString()
        }

        val filename: String?
            get() = execPath.getBaseName()

        val extension: String?
            get() = execPath.getFileExtension()

        val ownerLabel: com.google.devtools.build.lib.cmdline.Label
            get() = fileset.getOwnerLabel()

        override fun getRootForStarlark(semantics: net.starlark.java.eval.StarlarkSemantics?): FileRootApi {
            return fileset.getRoot()
        }

        val isSourceArtifact: Boolean
            get() =// This information is lost to us.
                // Since the symlinks are always in the output tree, settle for saying "no"
                false

        val isDirectory: Boolean
            get() = false

        val isSymlink: Boolean
            get() = false

        val runfilesPathString: String
            get() {
                val relativePath: PathFragment? = execPath.relativeTo(fileset.getExecPath())
                return fileset.getRunfilesPath().getRelative(relativePath).getPathString()
            }

        override fun getExecPathStringForStarlark(semantics: net.starlark.java.eval.StarlarkSemantics?): String? {
            return execPath.getPathString()
        }

        @get:Throws(net.starlark.java.eval.EvalException::class)
        val treeRelativePathString: String?
            get() {
                throw net.starlark.java.eval.Starlark.errorf(
                    "tree_relative_path not allowed for files that are not tree artifact files."
                )
            }

        public override fun expandToCommandLine(): String? {
            return execPath.getPathString()
        }

        override fun repr(
            printer: net.starlark.java.eval.Printer,
            semantics: net.starlark.java.eval.StarlarkSemantics?
        ) {
            if (this.isSourceArtifact) {
                printer.append("<source file " + this.runfilesPathString + ">")
            } else {
                printer.append("<generated file " + this.runfilesPathString + ">")
            }
        }
    }

    /** An element in a [PreprocessedCommandLine].  */
    private interface PreprocessedArg {
        fun toIterable(pathMapper: PathMapper?): Iterable<String?>?

        fun numArgs(): Int

        fun totalArgLength(pathMapper: PathMapper?): Int
    }

    /**
     * Intermediate command line representation with directory expansion and `map_each` already
     * applied, but with string formatting and path mapping not yet applied. See [ ] class-level documentation for details.
     * 
     * 
     * Implements [.totalArgLength] without applying string formatting and path mapping so
     * that the total command line length can be efficiently tested against [CommandLineLimits]
     * and param file thresholds.
     */
    private class PreprocessedCommandLine(preprocessedArgs: com.google.common.collect.ImmutableList<PreprocessedArg>) :
        ArgChunk {
        private val preprocessedArgs: com.google.common.collect.ImmutableList<PreprocessedArg>

        init {
            this.preprocessedArgs = preprocessedArgs
        }

        public override fun arguments(pathMapper: PathMapper?): Iterable<String?> {
            return com.google.common.collect.Iterables.concat<String?>(
                com.google.common.collect.Lists.transform<PreprocessedArg?, Iterable<String?>?>(
                    preprocessedArgs,
                    com.google.common.base.Function { arg: PreprocessedArg? -> arg!!.toIterable(pathMapper) })
            )
        }

        public override fun totalArgLength(pathMapper: PathMapper?): Int {
            var total = 0
            for (arg in preprocessedArgs) {
                total += arg.totalArgLength(pathMapper)
            }
            return total
        }

        internal class Builder {
            private val preprocessedArgs: com.google.common.collect.ImmutableList.Builder<PreprocessedArg?> =
                com.google.common.collect.ImmutableList.builder<PreprocessedArg?>()
            private var numArgs = 0

            fun addPreprocessedArg(arg: PreprocessedArg) {
                preprocessedArgs.add(arg)
                numArgs += arg.numArgs()
            }

            fun addString(string: String) {
                addPreprocessedArg(PreprocessedStringArg(string))
            }

            fun addArg( /* String | DerivedArtifact */arg: Any) {
                when (arg) {
                    -> addPreprocessedArg(PreprocessedStringArg(string))
                    -> addPreprocessedArg(PreprocessedArtifactArg(artifact))
                    else -> throw java.lang.IllegalStateException("Unexpected arg type: " + arg)
                }
            }

            fun addLineForFlagPerLine(line: Builder) {
                val group: com.google.common.collect.ImmutableList<PreprocessedArg> = line.preprocessedArgs.build()
                if (line.numArgs < 2) {
                    for (arg in group) {
                        addPreprocessedArg(arg)
                    }
                } else {
                    addPreprocessedArg(GroupedPreprocessedArgs(group))
                }
            }

            fun build(): PreprocessedCommandLine {
                return PreprocessedCommandLine(preprocessedArgs.build())
            }
        }
    }

    /** Preprocessed version a single string argument.  */
    private class PreprocessedStringArg(private val arg: String) : PreprocessedArg {
        override fun toIterable(pathMapper: PathMapper?): com.google.common.collect.ImmutableList<String?> {
            return com.google.common.collect.ImmutableList.of<String?>(arg)
        }

        override fun numArgs(): Int {
            return 1
        }

        override fun totalArgLength(pathMapper: PathMapper?): Int {
            return arg.length() + 1
        }
    }

    private class PreprocessedArtifactArg(artifact: DerivedArtifact) : PreprocessedArg {
        private val artifact: DerivedArtifact

        init {
            this.artifact = artifact
        }

        override fun toIterable(pathMapper: PathMapper): com.google.common.collect.ImmutableList<String?> {
            return com.google.common.collect.ImmutableList.of<E?>(pathMapper.getMappedExecPathString(artifact))
        }

        override fun numArgs(): Int {
            return 1
        }

        override fun totalArgLength(pathMapper: PathMapper): Int {
            return (artifact.getExecPathString().length()
                    - pathMapper.computeExecPathLengthDiff(artifact)
                    + 1)
        }
    }

    /** Preprocessed version of a [SingleFormattedArg].  */
    private class PreprocessedSingleFormattedArg(private val format: String?, private val stringValue: String) :
        PreprocessedArg {
        override fun toIterable(pathMapper: PathMapper?): com.google.common.collect.ImmutableList<String?> {
            return com.google.common.collect.ImmutableList.of<E?>(SingleStringArgFormatter.format(format, stringValue))
        }

        override fun numArgs(): Int {
            return 1
        }

        override fun totalArgLength(pathMapper: PathMapper?): Int {
            return SingleStringArgFormatter.formattedLength(format) + stringValue.length() + 1
        }
    }

    /** Preprocessed version of a [SingleFormattedArg] for a [DerivedArtifact].  */
    private class PreprocessedSingleFormattedArtifactArg(private val format: String?, artifact: DerivedArtifact) :
        PreprocessedArg {
        private val artifact: DerivedArtifact

        init {
            this.artifact = artifact
        }

        override fun toIterable(pathMapper: PathMapper): com.google.common.collect.ImmutableList<String?> {
            return com.google.common.collect.ImmutableList.of<E?>(
                SingleStringArgFormatter.format(format, pathMapper.getMappedExecPathString(artifact))
            )
        }

        override fun numArgs(): Int {
            return 1
        }

        override fun totalArgLength(pathMapper: PathMapper): Int {
            return (SingleStringArgFormatter.formattedLength(format)
                    + artifact.getExecPathString().length()
                    - pathMapper.computeExecPathLengthDiff(artifact)
                    + 1)
        }
    }

    /** Preprocessed version of a [VectorArg] originating from `Args.add_all`.  */
    private class UnjoinedPreprocessedVectorArg(
        private val values: MutableList<Any>,
        private val formatEach: String?,
        private val beforeEach: String?
    ) : PreprocessedArg {
        override fun toIterable(pathMapper: PathMapper): Iterable<String?> {
            var list: MutableList<String?> = com.google.common.collect.Lists.transform<Any?, String?>(
                values,
                com.google.common.base.Function { value: Any? -> Companion.maybePathMap(value!!, pathMapper) })
            if (formatEach != null) {
                list = com.google.common.collect.Lists.transform<String?, String?>(
                    list,
                    com.google.common.base.Function { s: String? -> SingleStringArgFormatter.format(formatEach, s) })
            }
            if (beforeEach == null) {
                return list
            } else {
                val finalList = list
                return Iterable { BeforeEachIterator(finalList.iterator(), beforeEach) }
            }
        }

        override fun numArgs(): Int {
            return (if (beforeEach != null) 2 else 1) * values.size()
        }

        override fun totalArgLength(pathMapper: PathMapper): Int {
            var total = 0
            for (arg in values) {
                total += argLength(arg, pathMapper)
            }
            if (formatEach != null) {
                total += SingleStringArgFormatter.formattedLength(formatEach) * values.size()
            }
            if (beforeEach != null) {
                total += beforeEach.length() * values.size()
            }
            return total + numArgs()
        }
    }

    /** Preprocessed version of a [VectorArg] originating from `Args.add_joined`.  */
    private class JoinedPreprocessedVectorArg(
        private val values: MutableList<Any>,
        private val formatEach: String?,
        private val joinWith: String,
        private val formatJoined: String?
    ) : PreprocessedArg {
        override fun toIterable(pathMapper: PathMapper): com.google.common.collect.ImmutableList<String?> {
            var it: MutableList<String?> = com.google.common.collect.Lists.transform<Any?, String?>(
                values,
                com.google.common.base.Function { value: Any? -> Companion.maybePathMap(value!!, pathMapper) })
            if (formatEach != null) {
                it = com.google.common.collect.Lists.transform<String?, String?>(
                    it,
                    com.google.common.base.Function { s: String? -> SingleStringArgFormatter.format(formatEach, s) })
            }
            var result: String = com.google.common.base.Joiner.on(joinWith).join(it)
            if (formatJoined != null) {
                result = SingleStringArgFormatter.format(formatJoined, result)
            }
            return com.google.common.collect.ImmutableList.of<String?>(result)
        }

        override fun numArgs(): Int {
            return 1
        }

        override fun totalArgLength(pathMapper: PathMapper): Int {
            var total = 0
            for (arg in values) {
                total += argLength(arg, pathMapper)
            }
            if (formatEach != null) {
                total += SingleStringArgFormatter.formattedLength(formatEach) * values.size()
            }
            if (values.size() > 1) {
                total += joinWith.length() * (values.size() - 1)
            }
            if (formatJoined != null) {
                total += SingleStringArgFormatter.formattedLength(formatJoined)
            }
            return total + 1
        }
    }

    /** Preprocessed representation of a single line in `flag_per_line` format.  */
    private class GroupedPreprocessedArgs(args: com.google.common.collect.ImmutableList<PreprocessedArg>) :
        PreprocessedArg {
        private val args: com.google.common.collect.ImmutableList<PreprocessedArg>

        init {
            this.args = args
        }

        override fun toIterable(pathMapper: PathMapper?): com.google.common.collect.ImmutableList<String?> {
            val it: MutableIterator<String> =
                com.google.common.collect.Iterables.concat<String?>(
                    com.google.common.collect.Lists.transform<PreprocessedArg?, Iterable<String?>?>(
                        args,
                        com.google.common.base.Function { arg: PreprocessedArg? -> arg!!.toIterable(pathMapper) })
                ).iterator()
            val first = it.next()
            val rest: String = SPACE_JOINER.join(it)
            val line = if (first.isEmpty()) rest else first + '=' + rest
            return com.google.common.collect.ImmutableList.of<String?>(line)
        }

        override fun numArgs(): Int {
            return 1
        }

        override fun totalArgLength(pathMapper: PathMapper?): Int {
            var total = 0
            for (arg in args) {
                total += arg.totalArgLength(pathMapper)
            }
            val first: String =
                com.google.common.collect.Iterables.concat<String>(
                    com.google.common.collect.Lists.transform<PreprocessedArg?, Iterable<String?>?>(
                        args,
                        com.google.common.base.Function { arg: PreprocessedArg? -> arg!!.toIterable(pathMapper) })
                )
                    .iterator()
                    .next()
            if (first.isEmpty()) {
                total--
            }
            return total
        }

        companion object {
            private val SPACE_JOINER: com.google.common.base.Joiner = com.google.common.base.Joiner.on(' ')
        }
    }

    /** Implements the `before_each` behavior of `Args.add_all`.  */
    private class BeforeEachIterator(private val strings: MutableIterator<String?>, private val beforeEach: String?) :
        com.google.common.collect.UnmodifiableIterator<String?>() {
        private var before = true

        override fun hasNext(): Boolean {
            return strings.hasNext()
        }

        override fun next(): String? {
            if (!hasNext()) {
                throw java.util.NoSuchElementException()
            }
            val next = if (before) beforeEach else strings.next()
            before = !before
            return next
        }
    }

    companion object {
        private val LINE_JOINER: com.google.common.base.Joiner = com.google.common.base.Joiner.on("\n").skipNulls()
        private val FIELD_JOINER: com.google.common.base.Joiner = com.google.common.base.Joiner.on(": ").skipNulls()

        /** Denotes that the following two elements are an object and format string.  */
        @SerializationConstant
        @com.google.devtools.build.lib.skyframe.serialization.VisibleForSerialization
        val SINGLE_FORMATTED_ARG_MARKER: Any = object : Any() {
            override fun toString(): String {
                return "SINGLE_FORMATTED_ARG_MARKER"
            }
        }

        private fun  /* String | DerivedArtifact */expandToCommandLine(
            `object`: Any?, mainRepoMapping: com.google.devtools.build.lib.cmdline.RepositoryMapping?
        ): Any? {
            // Label arguments are rare, so we don't bother rendering them lazily.
            if (`object` is com.google.devtools.build.lib.cmdline.Label) {
                return `object`.getDisplayForm(mainRepoMapping)
            }

            // DerivedArtifacts are path mapped lazily.
            return if (`object` is DerivedArtifact)
                `object`
            else
                CommandLineItem.expandToCommandLine(`object`)
        }

        private fun addSingleObjectToFingerprint(
            fingerprint: Fingerprint,
            `object`: Any?,
            mainRepoMapping: com.google.devtools.build.lib.cmdline.RepositoryMapping?
        ) {
            if (`object` is com.google.devtools.build.lib.cmdline.Label) {
                fingerprint.addString(`object`.getDisplayForm(mainRepoMapping))
                return
            }
            val stringificationType =
                if (`object` is FileApi) StringificationType.FILE else StringificationType.DEFAULT
            fingerprint.addInt(stringificationType.ordinal())
            fingerprint.addString(CommandLineItem.expandToCommandLine(`object`))
        }

        @Throws(CommandLineExpansionException::class, java.lang.InterruptedException::class)
        private fun applyMapEach(
            mapFn: net.starlark.java.eval.StarlarkCallable?,
            originalValues: MutableList<Any?>,
            consumer: java.util.function.Consumer<String?>,
            loc: net.starlark.java.syntax.Location?,
            inputMetadataProvider: InputMetadataProvider?,
            pathMapper: PathMapper,
            starlarkSemantics: net.starlark.java.eval.StarlarkSemantics?
        ) {
            try {
                net.starlark.java.eval.Mutability.create("map_each").use { mu ->
                    // This computation produces only a String list, which doesn't require reference semantics,
                    // so createTransient() is safe.
                    val thread: net.starlark.java.eval.StarlarkThread =
                        net.starlark.java.eval.StarlarkThread.createTransient(mu, pathMapper.storeIn(starlarkSemantics))
                    // TODO(b/77140311): Error if we issue print statements.
                    thread.setPrintHandler(net.starlark.java.eval.StarlarkThread.PrintHandler { th: net.starlark.java.eval.StarlarkThread?, msg: String? -> })
                    val count: Int = originalValues.size()
                    // We create a list that we reuse for the args to map_each
                    val args: MutableList<Any?> = java.util.ArrayList<Any?>(2)
                    args.add(null) // This will be overwritten each iteration.
                    // map_each can accept either each object, or each object + a directory expander.
                    if (wantsDirectoryExpander(mapFn)) {
                        val expander: DirectoryExpander?
                        if (inputMetadataProvider != null) {
                            expander = FullExpander(inputMetadataProvider)
                        } else {
                            expander = NoopExpander.Companion.INSTANCE
                        }
                        args.add(expander) // This will remain constant each iteration
                    }
                    for (i in 0..<count) {
                        args.set(0, originalValues.get(i))
                        val ret: Any = net.starlark.java.eval.Starlark.call(
                            thread,
                            mapFn,
                            args,  /* kwargs= */
                            com.google.common.collect.ImmutableMap.of<String?, Any?>()
                        )
                        if (ret is String) {
                            consumer.accept(ret)
                        } else if (ret is net.starlark.java.eval.Sequence<*>) {
                            for (`val` in ret) {
                                if (`val` !is String) {
                                    throw CommandLineExpansionException(
                                        ("Expected map_each to return string, None, or list of strings, "
                                                + "found list containing "
                                                + net.starlark.java.eval.Starlark.type(`val`))
                                    )
                                }
                                consumer.accept(`val`)
                            }
                        } else if (ret !== net.starlark.java.eval.Starlark.NONE) {
                            throw CommandLineExpansionException(
                                "Expected map_each to return string, None, or list of strings, found "
                                        + net.starlark.java.eval.Starlark.type(ret)
                            )
                        }
                    }
                }
            } catch (e: net.starlark.java.eval.EvalException) {
                // TODO(adonovan): consider calling a wrapper function to interpose a fake stack
                // frame that establishes the args.add_all call at loc. Or manipulating the stack
                // before printing it.
                throw CommandLineExpansionException(
                    errorMessage(e.getMessageWithStack(), loc, e.getCause())
                )
            }
        }

        private fun wantsDirectoryExpander(mapFn: net.starlark.java.eval.StarlarkCallable?): Boolean {
            return mapFn is net.starlark.java.eval.StarlarkFunction
                    && mapFn.getParameterNames().size() >= 2
        }

        private fun errorMessage(
            message: String?, location: net.starlark.java.syntax.Location?, cause: Throwable?
        ): String {
            return LINE_JOINER.join(
                "\n", FIELD_JOINER.join(location, message), getCauseMessage(cause, message)
            )
        }

        private fun getCauseMessage(cause: Throwable?, message: String?): String? {
            if (cause == null) {
                return null
            }
            val causeMessage: String? = cause.getMessage()
            if (causeMessage == null) {
                return null
            }
            if (message == null) {
                return causeMessage
            }
            // Skip the cause if it is redundant with the message so far.
            if (message.contains(causeMessage)) {
                return null
            }
            return causeMessage
        }

        private fun maybePathMap(
            /* String | DerivedArtifact */arg: Any, pathMapper: PathMapper
        ): String? {
            return when (arg) {
                -> string
                -> pathMapper.getMappedExecPathString(artifact)
                else -> throw java.lang.AssertionError("Unexpected arg type: " + arg)
            }
        }

        private fun argLength( /* String | DerivedArtifact */arg: Any, pathMapper: PathMapper): Int {
            return when (arg) {
                -> string.length()
                -> artifact.getExecPathString().length() - pathMapper.computeExecPathLengthDiff(artifact)
                else -> throw java.lang.AssertionError("Unexpected arg type: " + arg)
            }
        }
    }
}
