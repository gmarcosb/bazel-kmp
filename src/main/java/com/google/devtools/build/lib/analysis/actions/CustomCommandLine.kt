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
package com.google.devtools.build.lib.analysis.actions

import com.google.devtools.build.lib.actions.AbstractCommandLine

/** A customizable, serializable class for building memory efficient command lines.  */
@Immutable
open class CustomCommandLine private constructor(
    /**
     * Stored as an `Object[]` instead of an [ImmutableList] to save memory, but is never
     * modified. Access via [.rawArgsAsList] for an unmodifiable [List] view.
     */
    private val arguments: Array<Any?>
) : AbstractCommandLine() {
    private interface ArgvFragment {
        /**
         * Expands this fragment into the passed command line vector.
         * 
         * @param arguments The command line's argument vector.
         * @param argi The index of the next available argument.
         * @param builder The command line builder to which we should add arguments.
         * @param pathMapper Logic for stripping output path config prefixes
         * @return The index of the next argument, after the ArgvFragment has consumed its args. If the
         * ArgvFragment doesn't have any args, it should return `argi` unmodified.
         */
        @Throws(CommandLineExpansionException::class, java.lang.InterruptedException::class)
        fun eval(
            arguments: MutableList<Any?>?,
            argi: Int,
            builder: com.google.common.collect.ImmutableList.Builder<String?>?,
            pathMapper: PathMapper?
        ): Int

        @Throws(CommandLineExpansionException::class, java.lang.InterruptedException::class)
        fun addToFingerprint(
            arguments: MutableList<Any?>?,
            argi: Int,
            actionKeyContext: ActionKeyContext?,
            fingerprint: Fingerprint?
        ): Int
    }

    /**
     * Helper base class for an ArgvFragment that doesn't use the input argument vector.
     * 
     * 
     * This can be used for any ArgvFragments that self-contain all the necessary state.
     */
    private abstract class StandardArgvFragment : ArgvFragment {
        override fun eval(
            arguments: MutableList<Any?>?,
            argi: Int,
            builder: com.google.common.collect.ImmutableList.Builder<String?>?,
            pathMapper: PathMapper?
        ): Int {
            eval(builder)
            return argi // Doesn't consume any arguments, so return argi unmodified
        }

        abstract fun eval(builder: com.google.common.collect.ImmutableList.Builder<String?>?)

        override fun addToFingerprint(
            arguments: MutableList<Any?>?,
            argi: Int,
            actionKeyContext: ActionKeyContext?,
            fingerprint: Fingerprint?
        ): Int {
            addToFingerprint(actionKeyContext, fingerprint)
            return argi // Doesn't consume any arguments, so return argi unmodified
        }

        abstract fun addToFingerprint(actionKeyContext: ActionKeyContext?, fingerprint: Fingerprint?)
    }

    /**
     * An ArgvFragment that expands a collection of objects in a user-specified way.
     * 
     * 
     * Vector args support formatting, interspersing args (adding strings before each value),
     * joining, and mapping custom types. Please use this whenever you need to transform lists or
     * nested sets instead of doing it manually, as use of this class is more memory efficient.
     * 
     * 
     * The order of evaluation is:
     * 
     * 
     *  * Map the type T to a string using a custom map function, if any, or
     *  * Map any non-string type {PathFragment, Artifact} to their path/exec path
     *  * Format the string using the supplied format string, if any
     *  * Add the arguments each prepended by the before string, if any, or
     *  * Join the arguments with the join string, if any, or
     *  * Simply add all arguments
     * 
     * 
     * <pre>`Examples: List<String> values = ImmutableList.of("1", "2", "3"); commandBuilder.addAll(VectorArg.format("-l%s").each(values)) -> ["-l1", "-l2", "-l3"] commandBuilder.addAll(VectorArg.addBefore("-l").each(values)) -> ["-l", "1", "-l", "2", "-l", "3"] commandBuilder.addAll(VectorArg.join(":").each(values)) -> ["1:2:3"] `</pre>
     */
    open class VectorArg<T> private constructor(
        val isNestedSet: Boolean,
        val isEmpty: Boolean,
        val count: Int,
        val formatEach: String?,
        val beforeEach: String?,
        val joinWith: String?
    ) {
        /**
         * A vector arg that doesn't map its parameters.
         * 
         * 
         * Call [SimpleVectorArg.mapped] to produce a vector arg that maps from a given type to
         * a string.
         */
        class SimpleVectorArg<T> private constructor(
            isNestedSet: Boolean,
            isEmpty: Boolean,
            count: Int,
            formatEach: String?,
            beforeEach: String?,
            joinWith: String?,
            private val values: Any?
        ) : VectorArg<T?>(isNestedSet, isEmpty, count, formatEach, beforeEach, joinWith) {
            private constructor(builder: Builder, values: MutableCollection<T?>?) : this( /* isNestedSet= */
                false,
                values == null || values.isEmpty(),
                if (values != null) values.size else 0,
                builder.formatEach,
                builder.beforeEach,
                builder.joinWith,
                values
            )

            private constructor(builder: Builder, values: NestedSet<T?>?) : this( /* isNestedSet= */
                true,
                values == null || values.isEmpty(),  /* count= */
                -1,
                builder.formatEach,
                builder.beforeEach,
                builder.joinWith,
                values
            )

            /** Each argument is mapped using the supplied map function  */
            fun mapped(mapFn: CommandLineItem.MapFn<in T?>?): MappedVectorArg<T?> {
                return MappedVectorArg<T?>(this, mapFn)
            }
        }

        /** A vector arg that maps some type T to strings.  */
        internal class MappedVectorArg<T> private constructor(
            other: SimpleVectorArg<T?>,
            mapFn: CommandLineItem.MapFn<in T?>?
        ) : VectorArg<String?>(
            other.isNestedSet,
            other.isEmpty,
            other.count,
            other.formatEach,
            other.beforeEach,
            other.joinWith
        ) {
            private val values: Any?
            private val mapFn: CommandLineItem.MapFn<in T?>?

            init {
                this.values = other.values
                this.mapFn = mapFn
            }
        }

        /** Builder for [VectorArg].  */
        class Builder {
            private var formatEach: String? = null
            private var beforeEach: String? = null
            private var joinWith: String? = null

            /** Each argument is formatted via [SingleStringArgFormatter.format].  */
            @com.google.errorprone.annotations.CanIgnoreReturnValue
            fun format(@com.google.errorprone.annotations.CompileTimeConstant formatEach: String?): Builder {
                com.google.common.base.Preconditions.checkNotNull<String?>(formatEach)
                this.formatEach = formatEach
                return this
            }

            /** Each argument is prepended by the beforeEach param.  */
            @com.google.errorprone.annotations.CanIgnoreReturnValue
            fun addBefore(@com.google.errorprone.annotations.CompileTimeConstant beforeEach: String?): Builder {
                com.google.common.base.Preconditions.checkNotNull<String?>(beforeEach)
                this.beforeEach = beforeEach
                return this
            }

            /** Once all arguments have been evaluated, they are joined with this delimiter  */
            @com.google.errorprone.annotations.CanIgnoreReturnValue
            fun join(delimiter: String?): Builder {
                com.google.common.base.Preconditions.checkNotNull<String?>(delimiter)
                this.joinWith = delimiter
                return this
            }

            fun <T> each(values: MutableCollection<T?>?): SimpleVectorArg<T?> {
                return SimpleVectorArg<T?>(this, values)
            }

            fun <T> each(values: NestedSet<T?>?): SimpleVectorArg<T?> {
                return SimpleVectorArg<Any?>(this, values)
            }
        }

        private class VectorArgFragment(
            private val isNestedSet: Boolean,
            private val hasMapEach: Boolean,
            private val hasFormatEach: Boolean,
            private val hasBeforeEach: Boolean,
            private val hasJoinWith: Boolean
        ) : ArgvFragment {
            @Throws(CommandLineExpansionException::class, java.lang.InterruptedException::class)
            override fun eval(
                arguments: MutableList<Any?>,
                argi: Int,
                builder: com.google.common.collect.ImmutableList.Builder<String?>,
                pathMapper: PathMapper
            ): Int {
                var argi = argi
                val mutatedValues: MutableList<String?>
                val mapFn: CommandLineItem.MapFn<Any?>?
                if (hasMapEach) {
                    mapFn = arguments.get(argi++) as CommandLineItem.MapFn<Any?>?
                } else if (!pathMapper.isNoop() && !isNestedSet) {
                    // Allow the PathMapper to apply a map function to string arguments depending on the
                    // previous argument (e.g. to modify exec paths obtained in string form from location
                    // expansion).
                    val previousArg: String?
                    if (argi > 0 && arguments.get(argi - 1) is String) {
                        previousArg = arguments.get(argi - 1) as String?
                    } else {
                        previousArg = null
                    }
                    mapFn = pathMapper.getMapFn(previousArg)
                } else {
                    mapFn = null
                }
                if (isNestedSet) {
                    val values: NestedSet<Any?> = arguments.get(argi++) as NestedSet<Any?>
                    val list: com.google.common.collect.ImmutableList<Any?> = values.toList()
                    mutatedValues = java.util.ArrayList<String?>(list.size)
                    if (mapFn != null) {
                        val args: java.util.function.Consumer<String?> =
                            java.util.function.Consumer { e: String? -> mutatedValues.add(e) } // Hoist out of loop to reduce GC
                        for (`object` in list) {
                            mapFn.expandToCommandLine(`object`, args)
                        }
                    } else {
                        for (`object` in list) {
                            mutatedValues.add(expandToCommandLine(`object`, pathMapper))
                        }
                    }
                } else {
                    val count = arguments.get(argi++) as Int
                    mutatedValues = java.util.ArrayList<String?>(count)
                    if (mapFn != null) {
                        val args: java.util.function.Consumer<String?> =
                            java.util.function.Consumer { e: String? -> mutatedValues.add(e) } // Hoist out of loop to reduce GC
                        for (i in 0..<count) {
                            mapFn.expandToCommandLine(arguments.get(argi++), args)
                        }
                    } else {
                        for (i in 0..<count) {
                            mutatedValues.add(expandToCommandLine(arguments.get(argi++), pathMapper))
                        }
                    }
                }
                val count = mutatedValues.size
                if (hasFormatEach) {
                    val formatStr = arguments.get(argi++) as String?
                    for (i in 0..<count) {
                        mutatedValues.set(i, SingleStringArgFormatter.format(formatStr, mutatedValues.get(i)))
                    }
                }
                if (hasBeforeEach) {
                    val beforeEach = arguments.get(argi++) as String
                    for (i in 0..<count) {
                        builder.add(beforeEach)
                        builder.add(mutatedValues.get(i))
                    }
                } else if (hasJoinWith) {
                    val joinWith = arguments.get(argi++) as String
                    builder.add(com.google.common.base.Joiner.on(joinWith).join(mutatedValues))
                } else {
                    builder.addAll(mutatedValues)
                }
                return argi
            }

            @Throws(CommandLineExpansionException::class, java.lang.InterruptedException::class)
            override fun addToFingerprint(
                arguments: MutableList<Any?>,
                argi: Int,
                actionKeyContext: ActionKeyContext,
                fingerprint: Fingerprint
            ): Int {
                var argi = argi
                val mapFn: CommandLineItem.MapFn<Any?>? =
                    if (hasMapEach) arguments.get(argi++) as CommandLineItem.MapFn<Any?>? else null
                if (isNestedSet) {
                    val values: NestedSet<Any?>? = arguments.get(argi++) as NestedSet<Any?>?
                    if (mapFn != null) {
                        actionKeyContext.addNestedSetToFingerprint(mapFn, fingerprint, values)
                    } else {
                        actionKeyContext.addNestedSetToFingerprint(fingerprint, values)
                    }
                } else {
                    val count = arguments.get(argi++) as Int
                    if (mapFn != null) {
                        for (i in 0..<count) {
                            mapFn.expandToCommandLine(arguments.get(argi++), fingerprint::addString)
                        }
                    } else {
                        for (i in 0..<count) {
                            fingerprint.addString(CommandLineItem.expandToCommandLine(arguments.get(argi++)))
                        }
                    }
                }
                if (hasFormatEach) {
                    fingerprint.addUUID(FORMAT_EACH_UUID)
                    fingerprint.addString(arguments.get(argi++) as String?)
                }
                if (hasBeforeEach) {
                    fingerprint.addUUID(BEFORE_EACH_UUID)
                    fingerprint.addString(arguments.get(argi++) as String?)
                } else if (hasJoinWith) {
                    fingerprint.addUUID(JOIN_WITH_UUID)
                    fingerprint.addString(arguments.get(argi++) as String?)
                }
                return argi
            }

            override fun equals(o: Any?): Boolean {
                if (this === o) {
                    return true
                }
                if (o == null || javaClass != o.javaClass) {
                    return false
                }
                val vectorArgFragment = o as VectorArgFragment
                return isNestedSet == vectorArgFragment.isNestedSet && hasMapEach == vectorArgFragment.hasMapEach && hasFormatEach == vectorArgFragment.hasFormatEach && hasBeforeEach == vectorArgFragment.hasBeforeEach && hasJoinWith == vectorArgFragment.hasJoinWith
            }

            override fun hashCode(): Int {
                return com.google.common.base.Objects.hashCode(
                    isNestedSet,
                    hasMapEach,
                    hasFormatEach,
                    hasBeforeEach,
                    hasJoinWith
                )
            }

            companion object {
                private val interner: com.google.common.collect.Interner<VectorArgFragment> =
                    BlazeInterners.newStrongInterner()
                private val FORMAT_EACH_UUID: UUID = UUID.fromString("f830781f-2e0d-4e3b-9b99-ece7f249e0f3")
                private val BEFORE_EACH_UUID: UUID = UUID.fromString("07d22a0d-2691-4f1c-9f47-5294de1f94e4")
                private val JOIN_WITH_UUID: UUID = UUID.fromString("c96ed6f0-9220-40f6-9e0c-1c0c5e0b47e4")

                private fun expandToCommandLine(`object`: Any?, pathMapper: PathMapper?): String {
                    // It'd be nice to build this into ActionInput's CommandLine interface so we don't have
                    // to explicitly check if an object is a ActionInput. Unfortunately that would require
                    // a lot more dependencies on the Java library ActionInput is built into.
                    return if (pathMapper != null && `object` is ActionInput)
                        pathMapper.getMappedExecPathString(`object`)
                    else
                        CommandLineItem.expandToCommandLine(`object`)
                }
            }
        }

        companion object {
            fun <T> of(values: MutableCollection<T?>?): SimpleVectorArg<T?> {
                return com.google.devtools.build.lib.analysis.actions.CustomCommandLine.VectorArg.Builder()
                    .each<T?>(values)
            }

            fun <T> of(values: NestedSet<T?>?): SimpleVectorArg<T?>? {
                return com.google.devtools.build.lib.analysis.actions.CustomCommandLine.VectorArg.Builder().each(values)
            }

            /** Each argument is formatted via [SingleStringArgFormatter.format].  */
            fun format(@com.google.errorprone.annotations.CompileTimeConstant formatEach: String?): Builder {
                return com.google.devtools.build.lib.analysis.actions.CustomCommandLine.VectorArg.Builder()
                    .format(formatEach)
            }

            /** Each argument is prepended by the beforeEach param.  */
            fun addBefore(@com.google.errorprone.annotations.CompileTimeConstant beforeEach: String?): Builder {
                return com.google.devtools.build.lib.analysis.actions.CustomCommandLine.VectorArg.Builder()
                    .addBefore(beforeEach)
            }

            /** Once all arguments have been evaluated, they are joined with this delimiter  */
            fun join(delimiter: String?): Builder {
                return com.google.devtools.build.lib.analysis.actions.CustomCommandLine.VectorArg.Builder()
                    .join(delimiter)
            }

            private fun push(arguments: MutableList<Any?>, vectorArg: VectorArg<*>) {
                // This is either a Collection or a NestedSet.
                val values: Any?
                val mapFn: CommandLineItem.MapFn<*>?
                if (vectorArg is SimpleVectorArg<*>) {
                    values = vectorArg.values
                    mapFn = null
                } else {
                    values = (vectorArg as MappedVectorArg<*>).values
                    mapFn = vectorArg.mapFn
                }
                var vectorArgFragment =
                    VectorArgFragment(
                        vectorArg.isNestedSet,
                        mapFn != null,
                        vectorArg.formatEach != null,
                        vectorArg.beforeEach != null,
                        vectorArg.joinWith != null
                    )
                require(!(vectorArgFragment.hasBeforeEach && vectorArgFragment.hasJoinWith)) { "Cannot use both 'before' and 'join' in vector arg." }
                vectorArgFragment = VectorArgFragment.Companion.interner.intern(vectorArgFragment)
                arguments.add(vectorArgFragment)
                if (vectorArgFragment.hasMapEach) {
                    arguments.add(mapFn)
                }
                if (vectorArgFragment.isNestedSet) {
                    arguments.add(values)
                } else {
                    // Simply expand any ordinary collection into the argv
                    arguments.add(vectorArg.count)
                    arguments.addAll((values as kotlin.collections.MutableCollection<*>?)!!)
                }
                if (vectorArgFragment.hasFormatEach) {
                    arguments.add(vectorArg.formatEach)
                }
                if (vectorArgFragment.hasBeforeEach) {
                    arguments.add(vectorArg.beforeEach)
                }
                if (vectorArgFragment.hasJoinWith) {
                    arguments.add(vectorArg.joinWith)
                }
            }
        }
    }

    @VisibleForSerialization
    internal class FormatArg : ArgvFragment {
        override fun eval(
            arguments: MutableList<Any?>,
            argi: Int,
            builder: com.google.common.collect.ImmutableList.Builder<String?>,
            pathMapper: PathMapper?
        ): Int {
            var argi = argi
            val argCount = arguments.get(argi++) as Int
            val formatStr = arguments.get(argi++) as String
            val args = arrayOfNulls<Any>(argCount)
            for (i in 0..<argCount) {
                args[i] = CommandLineItem.expandToCommandLine(arguments.get(argi++))
            }
            builder.add(String.format(formatStr, *args))
            return argi
        }

        override fun addToFingerprint(
            arguments: MutableList<Any?>,
            argi: Int,
            actionKeyContext: ActionKeyContext?,
            fingerprint: Fingerprint
        ): Int {
            var argi = argi
            val argCount = arguments.get(argi++) as Int
            fingerprint.addUUID(FORMAT_UUID)
            fingerprint.addString(arguments.get(argi++) as String?)
            for (i in 0..<argCount) {
                fingerprint.addString(CommandLineItem.expandToCommandLine(arguments.get(argi++)))
            }
            return argi
        }

        companion object {
            @SerializationConstant
            @VisibleForSerialization
            val INSTANCE: FormatArg = FormatArg()

            private val FORMAT_UUID: UUID = UUID.fromString("377cee34-e947-49e0-94a2-6ab95b396ec4")

            private fun push(arguments: MutableList<Any?>, formatStr: String?, args: Array<Any?>) {
                arguments.add(INSTANCE)
                arguments.add(args.size)
                arguments.add(formatStr)
                Collections.addAll<Any?>(arguments, *args)
            }
        }
    }

    @VisibleForSerialization
    internal class PrefixArg : ArgvFragment {
        override fun eval(
            arguments: MutableList<Any?>,
            argi: Int,
            builder: com.google.common.collect.ImmutableList.Builder<String?>,
            pathMapper: PathMapper?
        ): Int {
            var argi = argi
            val before = arguments.get(argi++) as String?
            var arg = arguments.get(argi++)
            if (arg is RepositoryMapping) {
                arg = (arguments.get(argi++) as Label).getDisplayForm(arg)
            }
            builder.add(before + CommandLineItem.expandToCommandLine(arg))
            return argi
        }

        override fun addToFingerprint(
            arguments: MutableList<Any?>,
            argi: Int,
            actionKeyContext: ActionKeyContext?,
            fingerprint: Fingerprint
        ): Int {
            var argi = argi
            fingerprint.addUUID(PREFIX_UUID)
            fingerprint.addString(arguments.get(argi++) as String?)
            var arg = arguments.get(argi++)
            if (arg is RepositoryMapping) {
                arg = (arguments.get(argi++) as Label).getDisplayForm(arg)
            }
            fingerprint.addString(CommandLineItem.expandToCommandLine(arg))
            return argi
        }

        companion object {
            @SerializationConstant
            @VisibleForSerialization
            val INSTANCE: PrefixArg = PrefixArg()

            private val PREFIX_UUID: UUID = UUID.fromString("a95eccdf-4f54-46fc-b925-c8c7e1f50c95")

            private fun push(
                arguments: MutableList<Any?>,
                before: String?,
                arg: Any?,
                mainRepoMapping: RepositoryMapping?
            ) {
                arguments.add(INSTANCE)
                arguments.add(before)
                if (mainRepoMapping != null) {
                    arguments.add(mainRepoMapping)
                }
                arguments.add(arg)
            }
        }
    }

    /**
     * A command line argument for [TreeFileArtifact].
     * 
     * 
     * Since [TreeFileArtifact] is not known or available at analysis time, subclasses should
     * enclose its parent TreeFileArtifact instead at analysis time. This interface provides method
     * [.substituteTreeArtifact] to generate another argument object that replaces the enclosed
     * TreeArtifact with one of its [TreeFileArtifact] at execution time.
     */
    private abstract class TreeFileArtifactArgvFragment {
        /**
         * Substitutes this ArgvFragment with another arg object, with the original TreeArtifacts
         * contained in this ArgvFragment replaced by their associated TreeFileArtifacts.
         * 
         * @param substitutionMap A map between TreeArtifacts and their associated TreeFileArtifacts
         * used to replace them.
         */
        abstract fun substituteTreeArtifact(substitutionMap: MutableMap<Artifact?, TreeFileArtifact?>?): Any?
    }

    /**
     * A command line argument that can expand enclosed TreeArtifacts into a list of child [ ]s at execution time before argument evaluation.
     * 
     * 
     * The main difference between this class and [TreeFileArtifactArgvFragment] is that
     * [TreeFileArtifactArgvFragment] is used in [SpawnActionTemplate] to substitutes a
     * TreeArtifact with *one* of its child TreeFileArtifacts, while this class expands a TreeArtifact
     * into *all* of its child TreeFileArtifacts.
     */
    private abstract class TreeArtifactExpansionArgvFragment : StandardArgvFragment() {
        /**
         * Evaluates this argument fragment into an argument string and adds it into `builder`.
         * The enclosed TreeArtifact will be expanded using `inputMetadataProvider`.
         */
        abstract fun eval(
            builder: com.google.common.collect.ImmutableList.Builder<String?>?,
            inputMetadataProvider: InputMetadataProvider?
        )

        /**
         * Evaluates this argument fragment by serializing it into a string. Note that the returned
         * argument is not suitable to be used as part of an actual command line. The purpose of this
         * method is to provide a unique command line argument string to be used as part of an action
         * key at analysis time.
         * 
         * 
         * Internally this method just calls [.describe].
         */
        override fun eval(builder: com.google.common.collect.ImmutableList.Builder<String?>) {
            builder.add(describe())
        }

        /**
         * Returns a string that describes this argument fragment. The string can be used as part of an
         * action key for the command line at analysis time.
         */
        abstract fun describe(): String?
    }

    private class ExpandedTreeArtifactArg(treeArtifact: Artifact) : TreeArtifactExpansionArgvFragment() {
        private val treeArtifact: Artifact

        init {
            com.google.common.base.Preconditions.checkArgument(
                treeArtifact.isTreeArtifact(), "%s is not a TreeArtifact", treeArtifact
            )
            this.treeArtifact = treeArtifact
        }

        override fun eval(
            builder: com.google.common.collect.ImmutableList.Builder<String?>,
            inputMetadataProvider: InputMetadataProvider
        ) {
            val treeArtifactValue: TreeArtifactValue? = inputMetadataProvider.getTreeMetadata(treeArtifact)
            if (treeArtifactValue == null) {
                return
            }
            for (child in treeArtifactValue.getChildren()) {
                builder.add(child.getExecPathString())
            }
        }

        public override fun describe(): String? {
            return java.lang.String.format(
                "ExpandedTreeArtifactArg{ treeArtifact: %s}", treeArtifact.getExecPathString()
            )
        }

        override fun addToFingerprint(actionKeyContext: ActionKeyContext?, fingerprint: Fingerprint) {
            fingerprint.addUUID(TREE_UUID)
            fingerprint.addPath(treeArtifact.getExecPath())
        }

        companion object {
            private val TREE_UUID: UUID = UUID.fromString("13b7626b-c77d-4a30-ad56-ff08c06b1cee")
        }
    }

    /**
     * An argument object that evaluates to the exec path of a [TreeFileArtifact], enclosing the
     * associated [TreeFileArtifact].
     */
    private class TreeFileArtifactExecPathArg(artifact: Artifact) : TreeFileArtifactArgvFragment() {
        private val placeHolderTreeArtifact: Artifact

        init {
            com.google.common.base.Preconditions.checkArgument(
                artifact.isTreeArtifact(),
                "%s must be a TreeArtifact",
                artifact
            )
            placeHolderTreeArtifact = artifact
        }

        override fun substituteTreeArtifact(substitutionMap: MutableMap<Artifact?, TreeFileArtifact?>): Any {
            val artifact: Artifact? = substitutionMap.get(placeHolderTreeArtifact)
            com.google.common.base.Preconditions.checkNotNull<Any?>(
                artifact,
                "Artifact to substitute: %s",
                placeHolderTreeArtifact
            )
            return artifact.getExecPath()
        }
    }

    /**
     * A Builder class for CustomCommandLine with the appropriate methods.
     * 
     * 
     * [Collection] instances passed to `add*` methods will copied internally. If you
     * have a [NestedSet], these should never be flattened to a collection before being passed
     * to the command line.
     * 
     * 
     * Try to avoid coercing items to strings unnecessarily. Instead, use a more memory-efficient
     * form that defers the string coercion until the last moment. In particular, avoid flattening
     * lists and nested sets (see [VectorArg]).
     * 
     * 
     * Three types are given special consideration:
     * 
     * 
     *  * Any labels added will be added using [Label.getCanonicalForm]
     *  * Path fragments will be added using [PathFragment.toString]
     *  * Artifacts will be added using [Artifact.getExecPathString].
     * 
     * 
     * 
     * Any other type must be mapped to a string. For collections, please use [ ][VectorArg.SimpleVectorArg.mapped].
     */
    class Builder {
        // In order to avoid unnecessary wrapping, we keep raw objects here, but these objects are
        // always either ArgvFragments or objects whose desired string representations are just their
        // toString() results.
        private val arguments: MutableList<Any?> = java.util.ArrayList<Any?>()

        /**
         * Adds a constant-value string.
         * 
         * 
         * Prefer this over its dynamic cousin, as using static strings saves memory.
         */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun add(@com.google.errorprone.annotations.CompileTimeConstant value: String?): Builder {
            return addObjectInternal(value)
        }

        /**
         * Adds a string argument to the command line.
         * 
         * 
         * If the value is null, neither the arg nor the value is added.
         */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun add(@com.google.errorprone.annotations.CompileTimeConstant arg: String?, value: String?): Builder {
            return addObjectInternal(arg, value)
        }

        /**
         * Adds a single argument to the command line, which is lazily converted to string.
         * 
         * 
         * If the value is null, this method is a no-op.
         * 
         * 
         * Passing a [Collection] containing multiple elements to this method instead of [ ][.addAll] and similar is preferable if the caller knows that the given instance
         * will be retained elsewhere. This method spends a single array slot on the [Collection]
         * instead of copying over all of its elements, potentially saving memory if it is retained
         * elsewhere.
         */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun addObject(value: Any?): Builder {
            return addObjectInternal(value)
        }

        /**
         * Adds a dynamically calculated string.
         * 
         * 
         * Consider whether using another method could be more efficient. For instance, rather than
         * calling this method with an Artifact's exec path, just add the artifact itself. It will
         * lazily get converted to its exec path. Same with labels, path fragments, and many other
         * objects.
         * 
         * 
         * If you are joining some list into a single argument, consider using [VectorArg].
         * 
         * 
         * If you are formatting a string, consider using [Builder.addFormatted].
         * 
         * 
         * There are many other ways you can try to avoid calling this. In general, try to use
         * constants or objects that are already on the heap elsewhere.
         */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun addDynamicString(value: String?): Builder {
            return addObjectInternal(value)
        }

        /**
         * Adds a label value by calling [Label.getCanonicalForm].
         * 
         * 
         * Prefer this over manually calling [Label.getCanonicalForm], as it avoids a copy of
         * the label value.
         */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun addLabel(value: Label?): Builder {
            return addObjectInternal(value)
        }

        /**
         * Adds a label value by calling [Label.getCanonicalForm].
         * 
         * 
         * Prefer this over manually calling [Label.getCanonicalForm], as it avoids storing a
         * copy of the label value.
         * 
         * 
         * If the value is null, neither the arg nor the value is added.
         */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun addLabel(@com.google.errorprone.annotations.CompileTimeConstant arg: String?, value: Label?): Builder {
            return addObjectInternal(arg, value)
        }

        /**
         * Adds an artifact by calling [PathFragment.getPathString].
         * 
         * 
         * Prefer this over manually calling [PathFragment.getPathString], as it avoids storing
         * a copy of the path string.
         */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun addPath(value: PathFragment?): Builder {
            return addObjectInternal(value)
        }

        /**
         * Adds an artifact by calling [PathFragment.getPathString].
         * 
         * 
         * Prefer this over manually calling [PathFragment.getPathString], as it avoids storing
         * a copy of the path string.
         * 
         * 
         * If the value is null, neither the arg nor the value is added.
         */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun addPath(
            @com.google.errorprone.annotations.CompileTimeConstant arg: String?,
            value: PathFragment?
        ): Builder {
            return addObjectInternal(arg, value)
        }

        /**
         * Adds an artifact by calling [Artifact.getExecPath].
         * 
         * 
         * Prefer this over manually calling [Artifact.getExecPath], as it avoids storing a
         * copy of the artifact path string.
         */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun addExecPath(value: Artifact?): Builder {
            return addObjectInternal(value)
        }

        /**
         * Adds an artifact by calling [Artifact.getExecPath].
         * 
         * 
         * Prefer this over manually calling [Artifact.getExecPath], as it avoids storing a
         * copy of the artifact path string.
         * 
         * 
         * If the value is null, neither the arg nor the value is added.
         */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun addExecPath(
            @com.google.errorprone.annotations.CompileTimeConstant arg: String?,
            value: Artifact?
        ): Builder {
            return addObjectInternal(arg, value)
        }

        /** Adds a lazily expanded string.  */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun addLazyString(value: OnDemandString?): Builder {
            return addObjectInternal(value)
        }

        /** Adds a lazily expanded string.  */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun addLazyString(
            @com.google.errorprone.annotations.CompileTimeConstant arg: String?,
            value: OnDemandString?
        ): Builder {
            return addObjectInternal(arg, value)
        }

        /** Calls [String.format] at command line expansion time.  */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        @com.google.errorprone.annotations.FormatMethod
        fun addFormatted(
            @com.google.errorprone.annotations.FormatString formatStr: String?,
            vararg args: Any?
        ): Builder {
            com.google.common.base.Preconditions.checkNotNull<String?>(formatStr)
            FormatArg.Companion.push(arguments, formatStr, args)
            return this
        }

        /** Concatenates the passed prefix string and the string.  */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun addPrefixed(@com.google.errorprone.annotations.CompileTimeConstant prefix: String?, arg: String?): Builder {
            return addPrefixedInternal(prefix, arg,  /* mainRepoMapping= */null)
        }

        /**
         * Concatenates the passed prefix string and the label using [Label.getDisplayForm], which
         * is identical to [Label.getCanonicalForm] for main repo labels.
         */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun addPrefixedLabel(
            @com.google.errorprone.annotations.CompileTimeConstant prefix: String?,
            arg: Label?,
            mainRepoMapping: RepositoryMapping?
        ): Builder {
            return addPrefixedInternal(prefix, arg, mainRepoMapping)
        }

        /** Concatenates the passed prefix string and the path.  */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun addPrefixedPath(
            @com.google.errorprone.annotations.CompileTimeConstant prefix: String?,
            arg: PathFragment?
        ): Builder {
            return addPrefixedInternal(prefix, arg,  /* mainRepoMapping= */null)
        }

        /** Concatenates the passed prefix string and the artifact's exec path.  */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun addPrefixedExecPath(
            @com.google.errorprone.annotations.CompileTimeConstant prefix: String?,
            arg: Artifact?
        ): Builder {
            return addPrefixedInternal(prefix, arg,  /* mainRepoMapping= */null)
        }

        /**
         * Adds the passed strings to the command line.
         * 
         * 
         * If you are converting long lists or nested sets of a different type to string lists,
         * please try to use a different method that supports what you are trying to do directly.
         */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun addAll(values: MutableCollection<String?>?): Builder {
            return addCollectionInternal(values)
        }

        /**
         * Adds the passed strings to the command line.
         * 
         * 
         * If you are converting long lists or nested sets of a different type to string lists,
         * please try to use a different method that supports what you are trying to do directly.
         */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun addAll(values: NestedSet<String?>?): Builder {
            return addNestedSetInternal(values)
        }

        /**
         * Adds the arg followed by the passed strings.
         * 
         * 
         * If you are converting long lists or nested sets of a different type to string lists,
         * please try to use a different method that supports what you are trying to do directly.
         * 
         * 
         * If values is empty, the arg isn't added.
         */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun addAll(
            @com.google.errorprone.annotations.CompileTimeConstant arg: String?,
            values: MutableCollection<String?>?
        ): Builder {
            return addCollectionInternal(arg, values)
        }

        /**
         * Adds the arg followed by the passed strings.
         * 
         * 
         * If values is empty, the arg isn't added.
         */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun addAll(
            @com.google.errorprone.annotations.CompileTimeConstant arg: String?,
            values: NestedSet<String?>?
        ): Builder {
            return addNestedSetInternal(arg, values)
        }

        /** Adds the passed vector arg. See [VectorArg].  */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun addAll(vectorArg: VectorArg<String?>): Builder {
            return addVectorArgInternal(vectorArg)
        }

        /**
         * Adds the arg followed by the passed vector arg. See [VectorArg].
         * 
         * 
         * If values is empty, the arg isn't added.
         */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun addAll(
            @com.google.errorprone.annotations.CompileTimeConstant arg: String?,
            vectorArg: VectorArg<String?>
        ): Builder {
            return addVectorArgInternal(arg, vectorArg)
        }

        /** Adds the passed paths to the command line.  */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun addPaths(values: MutableCollection<PathFragment?>?): Builder {
            return addCollectionInternal(values)
        }

        /** Adds the passed paths to the command line.  */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun addPaths(values: NestedSet<PathFragment?>?): Builder {
            return addNestedSetInternal(values)
        }

        /**
         * Adds the arg followed by the path strings.
         * 
         * 
         * If values is empty, the arg isn't added.
         */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun addPaths(
            @com.google.errorprone.annotations.CompileTimeConstant arg: String?,
            values: MutableCollection<PathFragment?>?
        ): Builder {
            return addCollectionInternal(arg, values)
        }

        /**
         * Adds the arg followed by the path fragments.
         * 
         * 
         * If values is empty, the arg isn't added.
         */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun addPaths(
            @com.google.errorprone.annotations.CompileTimeConstant arg: String?, values: NestedSet<PathFragment?>?
        ): Builder {
            return addNestedSetInternal(arg, values)
        }

        /** Adds the passed vector arg. See [VectorArg].  */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun addPaths(vectorArg: VectorArg<PathFragment?>): Builder {
            return addVectorArgInternal(vectorArg)
        }

        /**
         * Adds the arg followed by the passed vector arg. See [VectorArg].
         * 
         * 
         * If values is empty, the arg isn't added.
         */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun addPaths(
            @com.google.errorprone.annotations.CompileTimeConstant arg: String?,
            vectorArg: VectorArg<PathFragment?>
        ): Builder {
            return addVectorArgInternal(arg, vectorArg)
        }

        /**
         * Adds the artifacts' exec paths to the command line.
         * 
         * 
         * Do not use this method if the list is derived from a flattened nested set. Instead, figure
         * out how to avoid flattening the set and use [.addExecPaths].
         */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun addExecPaths(values: MutableCollection<Artifact?>?): Builder {
            return addCollectionInternal(values)
        }

        /** Adds the artifacts' exec paths to the command line.  */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun addExecPaths(values: NestedSet<Artifact?>?): Builder {
            return addNestedSetInternal(values)
        }

        /**
         * Adds the arg followed by the artifacts' exec paths.
         * 
         * 
         * Do not use this method if the list is derived from a flattened nested set. Instead, figure
         * out how to avoid flattening the set and use [.addExecPaths].
         * 
         * 
         * If values is empty, the arg isn't added.
         */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun addExecPaths(
            @com.google.errorprone.annotations.CompileTimeConstant arg: String?, values: MutableCollection<Artifact?>?
        ): Builder {
            return addCollectionInternal(arg, values)
        }

        /**
         * Adds the arg followed by the artifacts' exec paths.
         * 
         * 
         * If values is empty, the arg isn't added.
         */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun addExecPaths(
            @com.google.errorprone.annotations.CompileTimeConstant arg: String?, values: NestedSet<Artifact?>?
        ): Builder {
            return addNestedSetInternal(arg, values)
        }

        /** Adds the passed vector arg. See [VectorArg].  */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun addExecPaths(vectorArg: VectorArg<Artifact?>): Builder {
            return addVectorArgInternal(vectorArg)
        }

        /**
         * Adds the arg followed by the passed vector arg. See [VectorArg].
         * 
         * 
         * If values is empty, the arg isn't added.
         */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun addExecPaths(
            @com.google.errorprone.annotations.CompileTimeConstant arg: String?,
            vectorArg: VectorArg<Artifact?>
        ): Builder {
            return addVectorArgInternal(arg, vectorArg)
        }

        /**
         * Adds a placeholder TreeArtifact exec path. When the command line is used in an action
         * template, the placeholder will be replaced by the exec path of a [TreeFileArtifact]
         * inside the TreeArtifact at execution time for each expanded action.
         * 
         * @param treeArtifact the TreeArtifact that will be evaluated to one of its child [     ] at execution time
         */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun addPlaceholderTreeArtifactExecPath(treeArtifact: Artifact?): Builder {
            if (treeArtifact != null) {
                arguments.add(TreeFileArtifactExecPathArg(treeArtifact))
            }
            return this
        }

        /**
         * Adds a flag with the exec path of a placeholder TreeArtifact. When the command line is used
         * in an action template, the placeholder will be replaced by the exec path of a [ ] inside the TreeArtifact at execution time for each expanded action.
         * 
         * @param arg the name of the argument
         * @param treeArtifact the TreeArtifact that will be evaluated to one of its child [     ] at execution time
         */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun addPlaceholderTreeArtifactExecPath(arg: String?, treeArtifact: Artifact?): Builder {
            com.google.common.base.Preconditions.checkNotNull<String?>(arg)
            if (treeArtifact != null) {
                arguments.add(arg)
                arguments.add(TreeFileArtifactExecPathArg(treeArtifact))
            }
            return this
        }

        /**
         * Adds the exec paths (one argument per exec path) of all [TreeFileArtifact]s under
         * `treeArtifact`.
         * 
         * @param treeArtifact the TreeArtifact containing the [TreeFileArtifact]s to add.
         */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun addExpandedTreeArtifactExecPaths(treeArtifact: Artifact?): Builder {
            com.google.common.base.Preconditions.checkNotNull<Any?>(treeArtifact)
            arguments.add(ExpandedTreeArtifactArg(treeArtifact))
            return this
        }

        fun build(): CustomCommandLine {
            return CustomCommandLine(arguments.toTypedArray())
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        private fun addObjectInternal(value: Any?): Builder {
            if (value != null) {
                arguments.add(value)
            }
            return this
        }

        /** Adds the arg and the passed value if the value is non-null.  */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        private fun addObjectInternal(
            @com.google.errorprone.annotations.CompileTimeConstant arg: String?,
            value: Any?
        ): Builder {
            com.google.common.base.Preconditions.checkNotNull<String?>(arg)
            if (value != null) {
                arguments.add(arg)
                addObjectInternal(value)
            }
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        private fun addPrefixedInternal(
            prefix: String?, arg: Any?, mainRepoMapping: RepositoryMapping?
        ): Builder {
            com.google.common.base.Preconditions.checkNotNull<String?>(prefix)
            if (arg != null) {
                PrefixArg.Companion.push(arguments, prefix, arg, mainRepoMapping)
            }
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        private fun addCollectionInternal(values: MutableCollection<*>?): Builder {
            if (values != null) {
                arguments.addAll(values)
            }
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        private fun addCollectionInternal(
            @com.google.errorprone.annotations.CompileTimeConstant arg: String?, values: MutableCollection<*>?
        ): Builder {
            com.google.common.base.Preconditions.checkNotNull<String?>(arg)
            if (values != null && !values.isEmpty()) {
                arguments.add(arg)
                addCollectionInternal(values)
            }
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        private fun addNestedSetInternal(values: NestedSet<*>?): Builder {
            if (values != null) {
                arguments.add(values)
            }
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        private fun addNestedSetInternal(
            @com.google.errorprone.annotations.CompileTimeConstant arg: String?, values: NestedSet<*>?
        ): Builder {
            com.google.common.base.Preconditions.checkNotNull<String?>(arg)
            if (values != null && !values.isEmpty()) {
                arguments.add(arg)
                addNestedSetInternal(values)
            }
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        private fun addVectorArgInternal(vectorArg: VectorArg<*>): Builder {
            if (!vectorArg.isEmpty) {
                VectorArg.Companion.push(arguments, vectorArg)
            }
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        private fun addVectorArgInternal(
            @com.google.errorprone.annotations.CompileTimeConstant arg: String?,
            vectorArg: VectorArg<*>
        ): Builder {
            com.google.common.base.Preconditions.checkNotNull<String?>(arg)
            if (!vectorArg.isEmpty) {
                arguments.add(arg)
                addVectorArgInternal(vectorArg)
            }
            return this
        }
    }

    /** Wraps [.arguments] in an unmodifiable [List] view.  */
    private fun rawArgsAsList(): MutableList<Any?> {
        return Collections.unmodifiableList<Any?>(java.util.Arrays.asList<Any?>(*arguments))
    }

    /**
     * Given the list of [TreeFileArtifact]s, returns another CustomCommandLine that replaces
     * their parent TreeArtifacts with the TreeFileArtifacts in all [ ] argument objects.
     */
    @com.google.common.annotations.VisibleForTesting
    fun evaluateTreeFileArtifacts(treeFileArtifacts: Iterable<TreeFileArtifact?>?): CustomCommandLine {
        return TreeArtifactSubstitutionCustomCommandLine(
            arguments, com.google.common.collect.Maps.uniqueIndex(treeFileArtifacts, TreeFileArtifact::getParent)
        )
    }

    @Throws(CommandLineExpansionException::class, java.lang.InterruptedException::class)
    public override fun arguments(): com.google.common.collect.ImmutableList<String?> {
        return arguments(null, PathMapper.NOOP)
    }

    /**
     * @param pathMapper a [PathMapper] that rewrites the config parts of artifact paths to
     * improve caching. This only affects [Builder.addExecPath] and [     ][Builder.addPath] entries. Output paths embedded in larger strings and added
     * via [Builder.add] or other variants must be handled separately.
     */
    @Throws(CommandLineExpansionException::class, java.lang.InterruptedException::class)
    public override fun arguments(
        inputMetadataProvider: InputMetadataProvider?, pathMapper: PathMapper
    ): com.google.common.collect.ImmutableList<String?> {
        val builder: com.google.common.collect.ImmutableList.Builder<String?> =
            com.google.common.collect.ImmutableList.builder<String?>()
        val arguments = rawArgsAsList()
        val count = arguments.size
        // Track the last scalar, non-path argument (e.g. "--javacopts") so that the PathMapper can
        // heuristically map subsequent argument collections that contain paths.
        var previousFlag: String? = null
        var i = 0
        while (i < count) {
            var arg = arguments.get(i++)
            if (arg is TreeFileArtifactArgvFragment) {
                arg = substituteTreeFileArtifactArgvFragment(arg)
            }
            if (arg is NestedSet<*>) {
                evalSimpleVectorArg(arg.toList(), builder, pathMapper, previousFlag)
            } else if (arg is Iterable<*>) {
                evalSimpleVectorArg(arg, builder, pathMapper, previousFlag)
            } else if (arg is ArgvFragment) {
                if (inputMetadataProvider != null
                    && arg is TreeArtifactExpansionArgvFragment
                ) {
                    arg.eval(builder, inputMetadataProvider)
                } else {
                    i = arg.eval(arguments, i, builder, pathMapper)
                }
            } else if (arg is ActionInput) {
                builder.add(pathMapper.getMappedExecPathString(arg))
            } else if (arg is PathFragment) {
                builder.add(pathMapper.map(arg).getPathString())
            } else {
                builder.add(CommandLineItem.expandToCommandLine(arg))
            }
            // Track the last scalar string argument (e.g. "--javacopts") so that the PathMapper can
            // heuristically map subsequent argument collections that contain paths.
            if (arg is String) {
                previousFlag = arg
            } else {
                previousFlag = null
            }
        }
        return builder.build()
    }

    private fun evalSimpleVectorArg(
        arg: Iterable<*>,
        builder: com.google.common.collect.ImmutableList.Builder<String?>,
        pathMapper: PathMapper,
        previousFlag: String?
    ) {
        val mapFn: ExceptionlessMapFn<Any?> = pathMapper.getMapFn(previousFlag)
        for (value in arg) {
            if (value is ActionInput) {
                builder.add(pathMapper.getMappedExecPathString(value))
            } else {
                mapFn.expandToCommandLine(value, builder::add)
            }
        }
    }

    /**
     * Returns another argument object that has its enclosing tree artifact substituted by a [ ].
     */
    @com.google.errorprone.annotations.ForOverride
    open fun substituteTreeFileArtifactArgvFragment(argvFragment: TreeFileArtifactArgvFragment?): Any? {
        throw java.lang.IllegalStateException("Unexpected " + argvFragment)
    }

    @Throws(CommandLineExpansionException::class, java.lang.InterruptedException::class)
    public override fun addToFingerprint(
        actionKeyContext: ActionKeyContext,
        inputMetadataProvider: InputMetadataProvider?,
        effectiveOutputPathsMode: OutputPathsMode?,
        fingerprint: Fingerprint
    ) {
        val arguments = rawArgsAsList()
        val count = arguments.size
        var i = 0
        while (i < count) {
            var arg = arguments.get(i++)
            if (arg is TreeFileArtifactArgvFragment) {
                arg = substituteTreeFileArtifactArgvFragment(arg)
            }
            if (arg is NestedSet) {
                actionKeyContext.addNestedSetToFingerprint(fingerprint, arg as NestedSet<Any?>?)
            } else if (arg is Iterable<*>) {
                for (value in arg) {
                    fingerprint.addString(CommandLineItem.expandToCommandLine(value))
                }
            } else if (arg is ArgvFragment) {
                i = arg.addToFingerprint(arguments, i, actionKeyContext, fingerprint)
            } else {
                fingerprint.addString(CommandLineItem.expandToCommandLine(arg))
            }
        }
    }

    /**
     * Supports [.substituteTreeFileArtifactArgvFragment] by maintaining a map from tree
     * artifact to [TreeFileArtifact].
     */
    private class TreeArtifactSubstitutionCustomCommandLine(
        arguments: Array<Any?>,
        substitutionMap: com.google.common.collect.ImmutableMap<Artifact?, TreeFileArtifact?>?
    ) : CustomCommandLine(arguments) {
        private val substitutionMap: com.google.common.collect.ImmutableMap<Artifact?, TreeFileArtifact?>?

        init {
            this.substitutionMap = substitutionMap
        }

        override fun substituteTreeFileArtifactArgvFragment(argvFragment: TreeFileArtifactArgvFragment): Any? {
            return argvFragment.substituteTreeArtifact(substitutionMap)
        }
    }

    companion object {
        fun builder(): Builder {
            return com.google.devtools.build.lib.analysis.actions.CustomCommandLine.Builder()
        }
    }
}
