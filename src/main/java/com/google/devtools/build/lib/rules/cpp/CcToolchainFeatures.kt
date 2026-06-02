// Copyright 2015 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.rules.cpp

import com.google.devtools.build.lib.actions.InputMetadataProvider

/**
 * Provides access to features supported by a specific toolchain.
 * 
 * 
 * This class can be generated from the CToolchain protocol buffer.
 * 
 * 
 * TODO(bazel-team): Implement support for specifying the toolchain configuration directly from
 * the BUILD file.
 * 
 * 
 * TODO(bazel-team): Find a place to put the public-facing documentation and link to it from
 * here.
 * 
 * 
 * TODO(bazel-team): Split out Feature as CcToolchainFeature, which will modularize the crosstool
 * configuration into one part that is about handling a set of features (including feature
 * selection) and one part that is about how to apply a single feature (parsing flags and expanding
 * them from build variables).
 */
@com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable
class CcToolchainFeatures internal constructor(
    ccToolchainConfigInfo: CcToolchainConfigInfo,
    ccToolchainPath: PathFragment?
) : net.starlark.java.eval.StarlarkValue {
    /**
     * Thrown when a flag value cannot be expanded under a set of build variables.
     * 
     * 
     * This happens for example when a flag references a variable that is not provided by the
     * action, or when a flag group implicitly references multiple variables of sequence type.
     */
    class ExpansionException : net.starlark.java.eval.EvalException {
        internal constructor(message: String?) : super(message)

        internal constructor(message: String?, cause: Throwable?) : super(message, cause)
    }

    /** Thrown when multiple features provide the same string symbol.  */
    class CollidingProvidesException internal constructor(message: String?) : java.lang.Exception(message)

    /** A single flag to be expanded under a set of variables.  */
    @com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable
    @AutoCodec
    internal class Flag(chunks: com.google.common.collect.ImmutableList<StringChunk>?) : Expandable {
        /** Expand this flag into a single new entry in `commandLine`.  */
        @Throws(com.google.devtools.build.lib.rules.cpp.CcToolchainFeatures.ExpansionException::class)
        override fun expand(
            variables: CcToolchainVariables?,
            inputMetadataProvider: InputMetadataProvider?,
            pathMapper: PathMapper?,
            commandLine: MutableList<String?>
        ) {
            val flag: java.lang.StringBuilder = java.lang.StringBuilder()
            for (chunk in chunks) {
                flag.append(chunk.expand(variables, pathMapper))
            }
            commandLine.add(flag.toString().intern())
        }

        /** Optimization for single-chunk case  */
        @com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable
        @AutoCodec
        internal class SingleChunkFlag(chunk: StringChunk?) : Expandable {
            @Throws(com.google.devtools.build.lib.rules.cpp.CcToolchainFeatures.ExpansionException::class)
            override fun expand(
                variables: CcToolchainVariables?,
                inputMetadataProvider: InputMetadataProvider?,
                pathMapper: PathMapper?,
                commandLine: MutableList<String?>
            ) {
                commandLine.add(chunk.expand(variables, pathMapper))
            }

            val chunk: StringChunk?

            init {
                this.chunk = chunk
            }
        }

        val chunks: com.google.common.collect.ImmutableList<StringChunk>?

        init {
            this.chunks = chunks
        }

        companion object {
            /** A single environment key/value pair to be expanded under a set of variables.  */
            fun create(chunks: com.google.common.collect.ImmutableList<StringChunk>): Expandable {
                if (chunks.size() == 1) {
                    return SingleChunkFlag(chunks.get(0))
                }
                return com.google.devtools.build.lib.rules.cpp.CcToolchainFeatures.Flag(chunks)
            }
        }
    }

    /** A single environment key/value pair to be expanded under a set of variables.  */
    @com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable
    @AutoCodec
    class EnvEntry(
        val key: String?,
        valueChunks: com.google.common.collect.ImmutableList<StringChunk>?,
        expandIfAllAvailable: com.google.common.collect.ImmutableSet<String?>?
    ) {
        private fun canBeExpanded(variables: CcToolchainVariables): Boolean {
            for (variable in expandIfAllAvailable) {
                if (!variables.isAvailable(variable)) {
                    return false
                }
            }
            return true
        }

        /**
         * Adds the key/value pair this object represents to the given map of environment variables. The
         * value of the entry is expanded with the given `variables`.
         */
        @Throws(com.google.devtools.build.lib.rules.cpp.CcToolchainFeatures.ExpansionException::class)
        fun addEnvEntry(
            variables: CcToolchainVariables,
            envBuilder: com.google.common.collect.ImmutableMap.Builder<String?, String?>,
            pathMapper: PathMapper?
        ) {
            if (!canBeExpanded(variables)) {
                return
            }
            val value: java.lang.StringBuilder = java.lang.StringBuilder()
            for (chunk in valueChunks) {
                value.append(chunk.expand(variables, pathMapper))
            }
            envBuilder.put(key, value.toString())
        }

        val valueChunks: com.google.common.collect.ImmutableList<StringChunk>?
        val expandIfAllAvailable: com.google.common.collect.ImmutableSet<String?>?

        init {
            this.valueChunks = valueChunks
            this.expandIfAllAvailable = expandIfAllAvailable
        }
    }

    /** Used for equality check between a variable and a specific value.  */
    @com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable
    @AutoCodec
    @kotlin.jvm.JvmRecord
    internal data class VariableWithValue(@kotlin.jvm.JvmField val variable: String?, @kotlin.jvm.JvmField val value: String?)

    /**
     * A group of flags. When iterateOverVariable is specified, we assume the variable is a sequence
     * and the flag_group will be expanded repeatedly for every value in the sequence.
     */
    @com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable
    @AutoCodec
    internal class FlagGroup(
        expandables: com.google.common.collect.ImmutableList<Expandable>?,
        iterateOverVariable: String?,
        expandIfAllAvailable: com.google.common.collect.ImmutableSet<String?>?,
        expandIfNoneAvailable: com.google.common.collect.ImmutableSet<String?>?,
        expandIfTrue: String?,
        expandIfFalse: String?,
        expandIfEqual: VariableWithValue?
    ) : Expandable {
        @Throws(com.google.devtools.build.lib.rules.cpp.CcToolchainFeatures.ExpansionException::class)
        override fun expand(
            variables: CcToolchainVariables,
            inputMetadataProvider: InputMetadataProvider?,
            pathMapper: PathMapper?,
            commandLine: MutableList<String?>?
        ) {
            if (!canBeExpanded(variables, inputMetadataProvider, pathMapper)) {
                return
            }
            if (iterateOverVariable != null) {
                for (variableValue in CcToolchainVariables.Companion.getSequenceValue(
                    iterateOverVariable,
                    variables.getVariable(iterateOverVariable, inputMetadataProvider, pathMapper)
                )) {
                    val nestedVariables: CcToolchainVariables =
                        SingleVariables(variables, iterateOverVariable, variableValue)
                    for (expandable in expandables) {
                        expandable.expand(nestedVariables, inputMetadataProvider, pathMapper, commandLine)
                    }
                }
            } else {
                for (expandable in expandables) {
                    expandable.expand(variables, inputMetadataProvider, pathMapper, commandLine)
                }
            }
        }

        @Throws(com.google.devtools.build.lib.rules.cpp.CcToolchainFeatures.ExpansionException::class)
        private fun canBeExpanded(
            variables: CcToolchainVariables,
            inputMetadataProvider: InputMetadataProvider?,
            pathMapper: PathMapper?
        ): Boolean {
            for (variable in expandIfAllAvailable) {
                if (!variables.isAvailable(variable, inputMetadataProvider)) {
                    return false
                }
            }
            for (variable in expandIfNoneAvailable) {
                if (variables.isAvailable(variable, inputMetadataProvider)) {
                    return false
                }
            }
            if (expandIfTrue != null
                && (!variables.isAvailable(expandIfTrue, inputMetadataProvider)
                        || !variables.getVariable(expandIfTrue, pathMapper).isTruthy())
            ) {
                return false
            }
            if (expandIfFalse != null
                && (!variables.isAvailable(expandIfFalse, inputMetadataProvider)
                        || variables.getVariable(expandIfFalse, pathMapper).isTruthy())
            ) {
                return false
            }
            if (expandIfEqual != null
                && (!variables.isAvailable(expandIfEqual.variable, inputMetadataProvider)
                        || (variables
                    .getVariable(expandIfEqual.variable, pathMapper)
                    .getStringValue(expandIfEqual.variable, pathMapper)
                        != expandIfEqual.value))
            ) {
                return false
            }
            return true
        }

        /**
         * Expands all flags in this group and adds them to `commandLine`.
         * 
         * 
         * The flags of the group will be expanded either:
         * 
         * 
         *  * once, if there is no variable of sequence type in any of the group's flags, or
         *  * for each element in the sequence, if there is 'iterate_over' variable specified
         * (preferred, explicit way), or
         *  * for each element in the sequence, if there is only one sequence variable used in the
         * body of the flag_group (deprecated, implicit way). Having more than a single variable
         * of sequence type in a single flag group with implicit iteration is not supported. Use
         * explicit 'iterate_over' instead.
         * 
         */
        @Throws(com.google.devtools.build.lib.rules.cpp.CcToolchainFeatures.ExpansionException::class)
        private fun expandCommandLine(
            variables: CcToolchainVariables,
            inputMetadataProvider: InputMetadataProvider?,
            pathMapper: PathMapper?,
            commandLine: MutableList<String?>?
        ) {
            expand(variables, inputMetadataProvider, pathMapper, commandLine)
        }

        val expandables: com.google.common.collect.ImmutableList<Expandable>?
        val iterateOverVariable: String?
        val expandIfAllAvailable: com.google.common.collect.ImmutableSet<String?>?
        val expandIfNoneAvailable: com.google.common.collect.ImmutableSet<String?>?
        val expandIfTrue: String?
        val expandIfFalse: String?
        val expandIfEqual: VariableWithValue?

        init {
            this.expandables = expandables
            this.iterateOverVariable = iterateOverVariable
            this.expandIfAllAvailable = expandIfAllAvailable
            this.expandIfNoneAvailable = expandIfNoneAvailable
            this.expandIfTrue = expandIfTrue
            this.expandIfFalse = expandIfFalse
            this.expandIfEqual = expandIfEqual
        }
    }

    /** Groups a set of flags to apply for certain actions.  */
    @com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable
    @AutoCodec
    class FlagSet(
        actions: com.google.common.collect.ImmutableSet<String?>?,
        expandIfAllAvailable: com.google.common.collect.ImmutableSet<String?>?,
        withFeatureSets: com.google.common.collect.ImmutableSet<WithFeatureSet>?,
        flagGroups: com.google.common.collect.ImmutableList<FlagGroup>?
    ) {
        /** Adds the flags that apply to the given `action` to `commandLine`.  */
        @Throws(com.google.devtools.build.lib.rules.cpp.CcToolchainFeatures.ExpansionException::class)
        private fun expandCommandLine(
            action: String?,
            variables: CcToolchainVariables,
            enabledFeatureNames: MutableSet<String?>,
            inputMetadataProvider: InputMetadataProvider?,
            pathMapper: PathMapper?,
            commandLine: MutableList<String?>?
        ) {
            for (variable in expandIfAllAvailable) {
                if (!variables.isAvailable(variable, inputMetadataProvider)) {
                    return
                }
            }
            if (!isWithFeaturesSatisfied(withFeatureSets, enabledFeatureNames)) {
                return
            }
            if (!actions.contains(action)) {
                return
            }
            for (flagGroup in flagGroups) {
                flagGroup.expandCommandLine(variables, inputMetadataProvider, pathMapper, commandLine)
            }
        }

        val actions: com.google.common.collect.ImmutableSet<String?>?
        val expandIfAllAvailable: com.google.common.collect.ImmutableSet<String?>?
        val withFeatureSets: com.google.common.collect.ImmutableSet<WithFeatureSet>?
        val flagGroups: com.google.common.collect.ImmutableList<FlagGroup>?

        init {
            this.actions = actions
            this.expandIfAllAvailable = expandIfAllAvailable
            this.withFeatureSets = withFeatureSets
            this.flagGroups = flagGroups
        }
    }

    /**
     * A set of positive and negative features. This stanza will evaluate to true when every 'feature'
     * is enabled, and every 'not_feature' is not enabled.
     */
    @com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable
    @AutoCodec
    class WithFeatureSet(
        features: com.google.common.collect.ImmutableSet<String?>?,
        notFeatures: com.google.common.collect.ImmutableSet<String?>?
    ) {
        val features: com.google.common.collect.ImmutableSet<String?>?
        val notFeatures: com.google.common.collect.ImmutableSet<String?>?

        init {
            this.features = features
            this.notFeatures = notFeatures
        }
    }

    /** Groups a set of environment variables to apply for certain actions.  */
    @com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable
    @AutoCodec
    class EnvSet(
        actions: com.google.common.collect.ImmutableSet<String?>?,
        envEntries: com.google.common.collect.ImmutableList<EnvEntry>?,
        withFeatureSets: com.google.common.collect.ImmutableSet<WithFeatureSet>?
    ) {
        /**
         * Adds the environment key/value pairs that apply to the given `action` to `envBuilder`.
         */
        @Throws(com.google.devtools.build.lib.rules.cpp.CcToolchainFeatures.ExpansionException::class)
        private fun expandEnvironment(
            action: String?,
            variables: CcToolchainVariables,
            pathMapper: PathMapper?,
            enabledFeatureNames: MutableSet<String?>,
            envBuilder: com.google.common.collect.ImmutableMap.Builder<String?, String?>
        ) {
            if (!actions.contains(action)) {
                return
            }
            if (!isWithFeaturesSatisfied(withFeatureSets, enabledFeatureNames)) {
                return
            }
            for (envEntry in envEntries) {
                envEntry.addEnvEntry(variables, envBuilder, pathMapper)
            }
        }

        val actions: com.google.common.collect.ImmutableSet<String?>?
        val envEntries: com.google.common.collect.ImmutableList<EnvEntry>?
        val withFeatureSets: com.google.common.collect.ImmutableSet<WithFeatureSet>?

        init {
            this.actions = actions
            this.envEntries = envEntries
            this.withFeatureSets = withFeatureSets
        }
    }

    /**
     * An interface for classes representing crosstool messages that can activate each other using
     * 'requires' and 'implies' semantics.
     * 
     * 
     * Currently there are two types of CrosstoolActivatable: Feature and ActionConfig.
     */
    internal interface CrosstoolSelectable {
        /** Returns the name of this selectable.  */
        val name: String?
    }

    /** Contains flags for a specific feature.  */
    @com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable
    @AutoCodec
    @com.google.devtools.build.lib.skyframe.serialization.VisibleForSerialization
    class Feature(
        private val name: String,
        flagSets: com.google.common.collect.ImmutableList<FlagSet>,
        envSets: com.google.common.collect.ImmutableList<EnvSet>,
        enabled: Boolean,
        requires: com.google.common.collect.ImmutableList<com.google.common.collect.ImmutableSet<String?>>,
        implies: com.google.common.collect.ImmutableList<String>,
        provides: com.google.common.collect.ImmutableList<String>
    ) : CrosstoolSelectable {
        private val flagSets: com.google.common.collect.ImmutableList<FlagSet>
        private val envSets: com.google.common.collect.ImmutableList<EnvSet>
        val isEnabled: Boolean
        private val requires: com.google.common.collect.ImmutableList<com.google.common.collect.ImmutableSet<String?>>
        private val implies: com.google.common.collect.ImmutableList<String>
        private val provides: com.google.common.collect.ImmutableList<String>

        init {
            this.flagSets = flagSets
            this.envSets = envSets
            this.isEnabled = enabled
            this.requires = requires
            this.implies = implies
            this.provides = provides
        }

        override fun getName(): String {
            return name
        }

        override fun toString(): String {
            return com.google.common.base.MoreObjects.toStringHelper(this).add("name", name).add(
                "enabled",
                this.isEnabled
            ).toString()
        }

        /** Adds environment variables for the given action to the provided builder.  */
        @Throws(com.google.devtools.build.lib.rules.cpp.CcToolchainFeatures.ExpansionException::class)
        private fun expandEnvironment(
            action: String?,
            variables: CcToolchainVariables,
            pathMapper: PathMapper?,
            enabledFeatureNames: MutableSet<String?>,
            envBuilder: com.google.common.collect.ImmutableMap.Builder<String?, String?>
        ) {
            for (envSet in envSets) {
                envSet.expandEnvironment(action, variables, pathMapper, enabledFeatureNames, envBuilder)
            }
        }

        /** Adds the flags that apply to the given `action` to `commandLine`.  */
        @Throws(com.google.devtools.build.lib.rules.cpp.CcToolchainFeatures.ExpansionException::class)
        private fun expandCommandLine(
            action: String?,
            variables: CcToolchainVariables,
            enabledFeatureNames: MutableSet<String?>,
            inputMetadataProvider: InputMetadataProvider?,
            pathMapper: PathMapper?,
            commandLine: MutableList<String?>?
        ) {
            for (flagSet in flagSets) {
                flagSet.expandCommandLine(
                    action, variables, enabledFeatureNames, inputMetadataProvider, pathMapper, commandLine
                )
            }
        }

        fun getFlagSets(): com.google.common.collect.ImmutableList<FlagSet> {
            return flagSets
        }

        fun getEnvSets(): com.google.common.collect.ImmutableList<EnvSet> {
            return envSets
        }

        override fun equals(`object`: Any?): Boolean {
            if (this === `object`) {
                return true
            }
            if (`object` is Feature) {
                return name == `object`.name
                        && com.google.common.collect.Iterables.elementsEqual(flagSets, `object`.flagSets)
                        && com.google.common.collect.Iterables.elementsEqual(envSets, `object`.envSets)
                        && com.google.common.collect.Iterables.elementsEqual(requires, `object`.requires)
                        && com.google.common.collect.Iterables.elementsEqual(implies, `object`.implies)
                        && com.google.common.collect.Iterables.elementsEqual(provides, `object`.provides)
                        && this.isEnabled == `object`.isEnabled
            }
            return false
        }

        override fun hashCode(): Int {
            return java.util.Objects.hash(name, flagSets, envSets, requires, implies, provides, this.isEnabled)
        }

        fun getRequires(): com.google.common.collect.ImmutableList<com.google.common.collect.ImmutableSet<String?>> {
            return requires
        }

        fun getImplies(): com.google.common.collect.ImmutableList<String> {
            return implies
        }

        fun getProvides(): com.google.common.collect.ImmutableList<String> {
            return provides
        }

        companion object {
            private val FEATURE_INTERNER: com.google.common.collect.Interner<Feature> =
                BlazeInterners.newWeakInterner<Feature?>()

            @com.google.devtools.build.lib.skyframe.serialization.VisibleForSerialization
            @AutoCodec.Interner
            fun intern(feature: Feature?): Feature {
                return com.google.devtools.build.lib.rules.cpp.CcToolchainFeatures.Feature.Companion.FEATURE_INTERNER.intern(
                    feature
                )
            }
        }
    }

    /**
     * An executable to be invoked by a blaze action. Can carry information on its platform
     * restrictions.
     */
    @com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable
    class Tool @com.google.common.annotations.VisibleForTesting constructor(
        toolPathFragment: PathFragment,
        toolPathOrigin: PathOrigin,
        executionRequirements: com.google.common.collect.ImmutableSet<String?>?,
        withFeatureSetSets: com.google.common.collect.ImmutableSet<WithFeatureSet>?
    ) {
        internal enum class PathOrigin {
            CROSSTOOL_PACKAGE,
            FILESYSTEM_ROOT,
            WORKSPACE_ROOT
        }

        private val toolPathFragment: PathFragment
        private val toolPathOrigin: PathOrigin
        private val executionRequirements: com.google.common.collect.ImmutableSet<String?>?
        private val withFeatureSetSets: com.google.common.collect.ImmutableSet<WithFeatureSet>?

        // Caching tool path string.
        private var toolPathString: String? = null

        init {
            com.google.devtools.build.lib.rules.cpp.CcToolchainFeatures.Tool.Companion.checkToolPath(
                toolPathFragment,
                toolPathOrigin
            )
            this.toolPathFragment = toolPathFragment
            this.toolPathOrigin = toolPathOrigin
            this.executionRequirements = executionRequirements
            this.withFeatureSetSets = withFeatureSetSets
        }

        @Deprecated("")
        @com.google.common.annotations.VisibleForTesting
        constructor(
            toolPathFragment: PathFragment,
            executionRequirements: com.google.common.collect.ImmutableSet<String?>?,
            withFeatureSetSets: com.google.common.collect.ImmutableSet<WithFeatureSet>?
        ) : this(
            toolPathFragment,
            com.google.devtools.build.lib.rules.cpp.CcToolchainFeatures.Tool.PathOrigin.CROSSTOOL_PACKAGE,
            executionRequirements,
            withFeatureSetSets
        )

        /** Returns the path to this action's tool relative to the provided crosstool path.  */
        fun getToolPathString(ccToolchainPath: PathFragment): String? {
            return when (toolPathOrigin) {
                com.google.devtools.build.lib.rules.cpp.CcToolchainFeatures.Tool.PathOrigin.CROSSTOOL_PACKAGE -> {
                    // Legacy behavior.
                    if (toolPathString == null) {
                        toolPathString = ccToolchainPath.getRelative(toolPathFragment).getSafePathString()
                    }
                    toolPathString
                }

                com.google.devtools.build.lib.rules.cpp.CcToolchainFeatures.Tool.PathOrigin.FILESYSTEM_ROOT, com.google.devtools.build.lib.rules.cpp.CcToolchainFeatures.Tool.PathOrigin.WORKSPACE_ROOT -> toolPathFragment.getSafePathString()
            }
        }

        /** Returns a list of requirement hints that apply to the execution of this tool.  */
        fun getExecutionRequirements(): com.google.common.collect.ImmutableSet<String?>? {
            return executionRequirements
        }

        /**
         * Returns a set of [WithFeatureSet] instances used to decide whether to use this tool
         * given a set of enabled features.
         */
        fun getWithFeatureSetSets(): com.google.common.collect.ImmutableSet<WithFeatureSet>? {
            return withFeatureSetSets
        }

        companion object {
            @Throws(net.starlark.java.eval.EvalException::class)
            private fun checkToolPath(toolPath: PathFragment, origin: PathOrigin) {
                when (origin) {
                    com.google.devtools.build.lib.rules.cpp.CcToolchainFeatures.Tool.PathOrigin.CROSSTOOL_PACKAGE ->           // For legacy reasons, we allow absolute and relative paths here.
                        return

                    com.google.devtools.build.lib.rules.cpp.CcToolchainFeatures.Tool.PathOrigin.FILESYSTEM_ROOT -> {
                        if (!toolPath.isAbsolute()) {
                            throw net.starlark.java.eval.Starlark.errorf(
                                "Tool-path with origin FILESYSTEM_ROOT must be absolute, got '%s'.",
                                toolPath.getPathString()
                            )
                        }
                        return
                    }

                    com.google.devtools.build.lib.rules.cpp.CcToolchainFeatures.Tool.PathOrigin.WORKSPACE_ROOT -> {
                        if (toolPath.isAbsolute()) {
                            throw net.starlark.java.eval.Starlark.errorf(
                                "Tool-path with origin WORKSPACE_ROOT must be relative, got '%s'.",
                                toolPath.getPathString()
                            )
                        }
                        return
                    }
                }

                // Unreached.
                throw java.lang.IllegalStateException()
            }
        }
    }

    /**
     * A container for information on a particular blaze action.
     * 
     * 
     * An ActionConfig can select a tool for its blaze action based on the set of active features.
     * Internally, an ActionConfig maintains an ordered list (the order being that of the list of
     * tools in the crosstool action_config message) of such tools and the feature sets for which they
     * are valid. For a given feature configuration, the ActionConfig will consider the first tool in
     * that list with a feature set that matches the configuration to be the tool for its blaze
     * action.
     * 
     * 
     * ActionConfigs can be activated by features. That is, a particular feature can cause an
     * ActionConfig to be applied in its "implies" field. Blaze may include certain actions in the
     * action graph only if a corresponding ActionConfig is activated in the toolchain - this provides
     * the crosstool with a mechanism for adding certain actions to the action graph based on feature
     * configuration.
     * 
     * 
     * It is invalid for a toolchain to contain two action configs for the same blaze action. In
     * that case, blaze will throw an error when it consumes the crosstool.
     */
    @com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable
    @AutoCodec
    class ActionConfig(
      private val configName: String?,
      /** Returns the name of the blaze action this action config applies to.  */
      @kotlin.jvm.JvmField val actionName: String?,
      tools: com.google.common.collect.ImmutableList<Tool>,
      flagSets: com.google.common.collect.ImmutableList<FlagSet>,
      enabled: Boolean,
      implies: com.google.common.collect.ImmutableList<String>
    ) : CrosstoolSelectable {
        private val tools: com.google.common.collect.ImmutableList<Tool>
        private val flagSets: com.google.common.collect.ImmutableList<FlagSet>
        val isEnabled: Boolean
        private val implies: com.google.common.collect.ImmutableList<String>

        init {
            this.tools = tools
            this.flagSets = flagSets
            this.isEnabled = enabled
            this.implies = implies
        }

        override fun getName(): String? {
            return configName
        }

        /**
         * Returns the path to this action's tool relative to the provided crosstool path given a set of
         * enabled features.
         */
        private fun getTool(enabledFeatureNames: MutableSet<String?>): Tool {
            return tools.stream()
                .filter(java.util.function.Predicate { t: Tool ->
                    isWithFeaturesSatisfied(
                        t.getWithFeatureSetSets(),
                        enabledFeatureNames
                    )
                })
                .findFirst()
                .orElseThrow<java.lang.IllegalArgumentException?>(
                    java.util.function.Supplier {
                        java.lang.IllegalArgumentException(
                            "Matching tool for action %s not found for given feature configuration"
                                .formatted(this.actionName)
                        )
                    })
        }

        /** Adds the flags that apply to this action to `commandLine`.  */
        @Throws(com.google.devtools.build.lib.rules.cpp.CcToolchainFeatures.ExpansionException::class)
        private fun expandCommandLine(
            variables: CcToolchainVariables,
            enabledFeatureNames: MutableSet<String?>,
            inputMetadataProvider: InputMetadataProvider?,
            pathMapper: PathMapper?,
            commandLine: MutableList<String?>?
        ) {
            for (flagSet in flagSets) {
                flagSet.expandCommandLine(
                    actionName,
                    variables,
                    enabledFeatureNames,
                    inputMetadataProvider,
                    pathMapper,
                    commandLine
                )
            }
        }

        fun getImplies(): com.google.common.collect.ImmutableList<String> {
            return implies
        }

        override fun equals(other: Any?): Boolean {
            if (other === this) {
                return true
            }
            if (other !is ActionConfig) {
                return false
            }

            return configName == other.configName
                    && actionName == other.actionName
                    && this.isEnabled == other.isEnabled && com.google.common.collect.Iterables.elementsEqual(
                tools,
                other.tools
            )
                    && com.google.common.collect.Iterables.elementsEqual(flagSets, other.flagSets)
                    && com.google.common.collect.Iterables.elementsEqual(implies, other.implies)
        }

        override fun hashCode(): Int {
            return java.util.Objects.hash(configName, actionName, this.isEnabled, tools, flagSets, implies)
        }

        fun getTools(): com.google.common.collect.ImmutableList<Tool> {
            return tools
        }

        fun getFlagSets(): com.google.common.collect.ImmutableList<FlagSet> {
            return flagSets
        }

        companion object {
            @kotlin.jvm.JvmField
            val FLAG_SET_WITH_ACTION_ERROR: String =
                ("action_config %s specifies actions.  An action_config's flag sets automatically apply "
                        + "to the configured action.  Thus, you must not specify action lists in an "
                        + "action_config's flag set.")

            private val ACTION_CONFIG_INTERNER: com.google.common.collect.Interner<ActionConfig> =
                BlazeInterners.newWeakInterner<ActionConfig?>()

            @com.google.devtools.build.lib.skyframe.serialization.VisibleForSerialization
            @AutoCodec.Interner
            fun intern(actionConfig: ActionConfig?): ActionConfig {
                return ACTION_CONFIG_INTERNER.intern(actionConfig)
            }
        }
    }

    /** A description of how artifacts of a certain type are named.  */
    @com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable
    @AutoCodec
    @kotlin.jvm.JvmRecord
    internal data class ArtifactNamePattern(val prefix: String?, val extension: String?) {
        /** Returns the artifact name that this pattern selects.  */
        private fun getArtifactName(baseName: String): String {
            return prefix + baseName + extension
        }
    }

    internal class ArtifactNamePatternMapper private constructor(prefixExtensionOverrides: com.google.common.collect.ImmutableMap<ArtifactCategory?, ArtifactNamePattern?>) {
        private val prefixExtensionOverrides: com.google.common.collect.ImmutableMap<ArtifactCategory?, ArtifactNamePattern?>

        init {
            this.prefixExtensionOverrides = prefixExtensionOverrides
        }

        fun get(category: ArtifactCategory?): ArtifactNamePattern? {
            val result: ArtifactNamePattern? = prefixExtensionOverrides.get(category)
            return if (result != null) result else DEFAULT_PATTERNS.get(category)
        }

        internal class Builder {
            private val overrides: com.google.common.collect.ImmutableMap.Builder<ArtifactCategory?, ArtifactNamePattern?> =
                com.google.common.collect.ImmutableMap.builder<ArtifactCategory?, ArtifactNamePattern?>()

            @com.google.errorprone.annotations.CanIgnoreReturnValue
            fun addOverride(category: ArtifactCategory, prefix: String?, extension: String?): Builder {
                if (category.getDefaultPrefix() != prefix || category.getDefaultExtension() != extension) {
                    overrides.put(category, ArtifactNamePattern(prefix, extension))
                }
                return this
            }

            fun build(): ArtifactNamePatternMapper {
                return ArtifactNamePatternMapper(overrides.buildOrThrow())
            }
        }

        companion object {
            private val DEFAULT_PATTERNS: com.google.common.collect.ImmutableMap<ArtifactCategory?, ArtifactNamePattern?> =
                java.util.Arrays.stream<ArtifactCategory?>(ArtifactCategory.entries.toTypedArray())
                    .collect(
                        com.google.common.collect.ImmutableMap.toImmutableMap<ArtifactCategory?, ArtifactCategory?, ArtifactNamePattern?>(
                            java.util.function.Function.identity<ArtifactCategory?>(),
                            java.util.function.Function { c: ArtifactCategory? ->
                                ArtifactNamePattern(
                                    c.getDefaultPrefix(),
                                    c.getDefaultExtension()
                                )
                            })
                    )
        }
    }

    /** Captures the set of enabled features and action configs for a rule.  */
    @com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable
    @AutoCodec
    // enabledFeatureNames, see definition of equals().
    class FeatureConfiguration internal constructor(
        requestedFeatures: com.google.common.collect.ImmutableSet<String?>,
        enabledFeatures: com.google.common.collect.ImmutableList<Feature>,
        enabledActionConfigActionNames: com.google.common.collect.ImmutableSet<String>,
        actionConfigByActionName: com.google.common.collect.ImmutableMap<String?, ActionConfig?>,
        ccToolchainPath: PathFragment
    ) {
        private val requestedFeatures: com.google.common.collect.ImmutableSet<String?>
        private val enabledFeatureNames: com.google.common.collect.ImmutableSet<String?>
        private val enabledFeatures: com.google.common.collect.ImmutableList<Feature>
        private val enabledActionConfigActionNames: com.google.common.collect.ImmutableSet<String>

        private val actionConfigByActionName: com.google.common.collect.ImmutableMap<String?, ActionConfig?>

        private val ccToolchainPath: PathFragment

        protected constructor() : this( /* requestedFeatures= */
            com.google.common.collect.ImmutableSet.of<String?>(),  /* enabledFeatures= */
            com.google.common.collect.ImmutableList.of<Feature?>(),  /* enabledActionConfigActionNames= */
            com.google.common.collect.ImmutableSet.of<String?>(),  /* actionConfigByActionName= */
            com.google.common.collect.ImmutableMap.of<String?, ActionConfig?>(),  /* ccToolchainPath= */
            PathFragment.EMPTY_FRAGMENT
        )

        init {
            // The order of elements in requestFeatures does not matter for equality of any behavior, but
            // coupled with interning, it makes serialization non-deterministic because it'd depend on
            // the order in which two objects that are equal but have the different order are first
            // encountered. Sorting prevents this issue.
            this.requestedFeatures = com.google.common.collect.ImmutableSet.copyOf<String?>(
                com.google.common.collect.ImmutableList.sortedCopyOf<String?>(requestedFeatures)
            )
            this.enabledFeatures = enabledFeatures

            this.actionConfigByActionName =
                com.google.common.collect.ImmutableSortedMap.copyOf<String?, ActionConfig?>(actionConfigByActionName)
            val featureBuilder: com.google.common.collect.ImmutableSet.Builder<String?> =
                com.google.common.collect.ImmutableSet.builder<String?>()
            for (feature in enabledFeatures) {
                featureBuilder.add(feature.getName())
            }
            this.enabledFeatureNames = featureBuilder.build()
            this.enabledActionConfigActionNames = enabledActionConfigActionNames
            this.ccToolchainPath = ccToolchainPath
        }

        /**
         * @return whether the given `feature` is enabled.
         */
        fun isEnabled(feature: String?): Boolean {
            return enabledFeatureNames.contains(feature)
        }

        /** The list of requested features, even if they do not exist in CROSSTOOLs.  */
        fun getRequestedFeatures(): com.google.common.collect.ImmutableSet<String?> {
            return requestedFeatures
        }

        /**
         * @return whether an action config for the blaze action with the given name is enabled.
         */
        fun actionIsConfigured(actionName: String?): Boolean {
            return enabledActionConfigActionNames.contains(actionName)
        }

        /**
         * @return the command line for the given `action`.
         */
        @Throws(com.google.devtools.build.lib.rules.cpp.CcToolchainFeatures.ExpansionException::class)
        fun getCommandLine(action: String?, variables: CcToolchainVariables): MutableList<String?> {
            return getCommandLine(action, variables,  /* inputMetadataProvider= */null, PathMapper.NOOP)
        }

        @Throws(com.google.devtools.build.lib.rules.cpp.CcToolchainFeatures.ExpansionException::class)
        fun getCommandLine(
            action: String?,
            variables: CcToolchainVariables,
            inputMetadataProvider: InputMetadataProvider?,
            pathMapper: PathMapper?
        ): MutableList<String?> {
            val commandLine: MutableList<String?> = java.util.ArrayList<String?>()
            if (actionIsConfigured(action)) {
                actionConfigByActionName
                    .get(action)
                    .expandCommandLine(
                        variables, enabledFeatureNames, inputMetadataProvider, pathMapper, commandLine
                    )
            }

            for (feature in enabledFeatures) {
                feature.expandCommandLine(
                    action, variables, enabledFeatureNames, inputMetadataProvider, pathMapper, commandLine
                )
            }

            return commandLine
        }

        /**
         * @return the flags expanded for the given `action` in per-feature buckets.
         */
        @Throws(com.google.devtools.build.lib.rules.cpp.CcToolchainFeatures.ExpansionException::class)
        fun getPerFeatureExpansions(
            action: String?, variables: CcToolchainVariables, pathMapper: PathMapper?
        ): com.google.common.collect.ImmutableList<com.google.devtools.build.lib.util.Pair<String?, MutableList<String?>?>?> {
            return getPerFeatureExpansions(action, variables, null, pathMapper)
        }

        @Throws(com.google.devtools.build.lib.rules.cpp.CcToolchainFeatures.ExpansionException::class)
        fun getPerFeatureExpansions(
            action: String?,
            variables: CcToolchainVariables,
            inputMetadataProvider: InputMetadataProvider?,
            pathMapper: PathMapper?
        ): com.google.common.collect.ImmutableList<com.google.devtools.build.lib.util.Pair<String?, MutableList<String?>?>?> {
            val perFeatureExpansions: com.google.common.collect.ImmutableList.Builder<com.google.devtools.build.lib.util.Pair<String?, MutableList<String?>?>?> =
                com.google.common.collect.ImmutableList.builder<com.google.devtools.build.lib.util.Pair<String?, MutableList<String?>?>?>()
            if (actionIsConfigured(action)) {
                val commandLine: MutableList<String?> = java.util.ArrayList<String?>()
                val actionConfig: ActionConfig? = actionConfigByActionName.get(action)
                actionConfig.expandCommandLine(
                    variables, enabledFeatureNames, inputMetadataProvider, pathMapper, commandLine
                )
                perFeatureExpansions.add(
                    com.google.devtools.build.lib.util.Pair.of<String?, MutableList<String?>?>(
                        actionConfig!!.getName(),
                        commandLine
                    )
                )
            }

            for (feature in enabledFeatures) {
                val commandLine: MutableList<String?> = java.util.ArrayList<String?>()
                feature.expandCommandLine(
                    action, variables, enabledFeatureNames, inputMetadataProvider, pathMapper, commandLine
                )
                perFeatureExpansions.add(
                    com.google.devtools.build.lib.util.Pair.of<String?, MutableList<String?>?>(
                        feature.getName(),
                        commandLine
                    )
                )
            }

            return perFeatureExpansions.build()
        }

        /**
         * @return the environment variables (key/value pairs) for the given `action`.
         */
        @Throws(com.google.devtools.build.lib.rules.cpp.CcToolchainFeatures.ExpansionException::class)
        fun getEnvironmentVariables(
            action: String?, variables: CcToolchainVariables, pathMapper: PathMapper?
        ): com.google.common.collect.ImmutableMap<String?, String?> {
            val envBuilder: com.google.common.collect.ImmutableMap.Builder<String?, String?> =
                com.google.common.collect.ImmutableMap.builder<String?, String?>()
            for (feature in enabledFeatures) {
                feature.expandEnvironment(action, variables, pathMapper, enabledFeatureNames, envBuilder)
            }
            return envBuilder.buildOrThrow()
        }

        fun getToolPathForAction(actionName: String?): String? {
            com.google.common.base.Preconditions.checkArgument(
                actionConfigByActionName.containsKey(actionName),
                "Action %s does not have an enabled configuration in the toolchain.",
                actionName
            )
            val actionConfig: ActionConfig? = actionConfigByActionName.get(actionName)
            return actionConfig.getTool(enabledFeatureNames).getToolPathString(ccToolchainPath)
        }

        fun getToolRequirementsForAction(actionName: String?): com.google.common.collect.ImmutableSet<String?>? {
            com.google.common.base.Preconditions.checkArgument(
                actionConfigByActionName.containsKey(actionName),
                "Action %s does not have an enabled configuration in the toolchain.",
                actionName
            )
            val actionConfig: ActionConfig? = actionConfigByActionName.get(actionName)
            return actionConfig.getTool(enabledFeatureNames).getExecutionRequirements()
        }

        override fun equals(`object`: Any?): Boolean {
            if (`object` === this) {
                return true
            }
            if (`object` is FeatureConfiguration) {
                // Only compare actionConfigByActionName, enabledActionConfigActionnames and enabledFeatures
                // because enabledFeatureNames is based on the list of Features.
                return actionConfigByActionName == `object`.actionConfigByActionName
                        && com.google.common.collect.Iterables.elementsEqual(
                    enabledActionConfigActionNames, `object`.enabledActionConfigActionNames
                )
                        && com.google.common.collect.Iterables.elementsEqual(enabledFeatures, `object`.enabledFeatures)
            }
            return false
        }

        override fun hashCode(): Int {
            return java.util.Objects.hash(
                actionConfigByActionName,
                enabledActionConfigActionNames,
                enabledFeatureNames,
                enabledFeatures
            )
        }

        fun getEnabledFeatureNames(): com.google.common.collect.ImmutableSet<String?> {
            return enabledFeatureNames
        }

        companion object {
            private val FEATURE_CONFIGURATION_INTERNER: com.google.common.collect.Interner<FeatureConfiguration> =
                BlazeInterners.newWeakInterner<FeatureConfiguration?>()

            /**
             * [FeatureConfiguration] instance that doesn't produce any command lines. This is to be
             * used when creation of the real [FeatureConfiguration] failed, the rule error was
             * reported, but the analysis continues to collect more rule errors.
             */
            @kotlin.jvm.JvmField
            @SerializationConstant
            val EMPTY: FeatureConfiguration = FEATURE_CONFIGURATION_INTERNER.intern(FeatureConfiguration())

            @com.google.devtools.build.lib.skyframe.serialization.VisibleForSerialization
            @AutoCodec.Instantiator
            fun createForSerialization(
                requestedFeatures: com.google.common.collect.ImmutableSet<String?>,
                enabledFeatures: com.google.common.collect.ImmutableList<Feature>,
                enabledActionConfigActionNames: com.google.common.collect.ImmutableSet<String>,
                actionConfigByActionName: com.google.common.collect.ImmutableMap<String?, ActionConfig?>,
                ccToolchainPath: PathFragment
            ): FeatureConfiguration {
                return intern(
                    FeatureConfiguration(
                        requestedFeatures,
                        enabledFeatures,
                        enabledActionConfigActionNames,
                        actionConfigByActionName,
                        ccToolchainPath
                    )
                )
            }

            @kotlin.jvm.JvmStatic
            @com.google.common.annotations.VisibleForTesting
            fun intern(featureConfiguration: FeatureConfiguration?): FeatureConfiguration {
                return FEATURE_CONFIGURATION_INTERNER.intern(featureConfiguration)
            }
        }
    }

    private val artifactNamePatterns: ArtifactNamePatternMapper

    /**
     * All features and action configs in the order in which they were specified in the configuration.
     * 
     * 
     * We guarantee the command line to be in the order in which the flags were specified in the
     * configuration.
     */
    private val selectables: com.google.common.collect.ImmutableList<CrosstoolSelectable>

    /** Maps the selectables's name to the selectable.  */
    private val selectablesByName: com.google.common.collect.ImmutableMap<String?, CrosstoolSelectable?>

    /** Maps an action's name to the ActionConfig.  */
    private val actionConfigsByActionName: com.google.common.collect.ImmutableMap<String?, ActionConfig?>

    /** Maps from a selectable to a set of all the selectables it has a direct 'implies' edge to.  */
    private val implies: com.google.common.collect.ImmutableMultimap<CrosstoolSelectable?, CrosstoolSelectable?>

    /**
     * Maps from a selectable to all features that have an direct 'implies' edge to this selectable.
     */
    private val impliedBy: com.google.common.collect.ImmutableMultimap<CrosstoolSelectable?, CrosstoolSelectable?>

    /**
     * Maps from a selectable to a set of selecatable sets, where:
     * 
     * 
     *  * a selectable set satisfies the 'requires' condition, if all selectables in the selectable
     * set are enabled
     *  * the 'requires' condition is satisfied, if at least one of the selectable sets satisfies
     * the 'requires' condition.
     * 
     */
    private val requires: com.google.common.collect.ImmutableMultimap<CrosstoolSelectable?, com.google.common.collect.ImmutableSet<CrosstoolSelectable?>?>

    /** Maps from a string to the set of selectables that 'provide' it.  */
    private val provides: com.google.common.collect.ImmutableMultimap<String?, CrosstoolSelectable?>

    /**
     * Maps from a selectable to all selectables that have a requirement referencing it.
     * 
     * 
     * This will be used to determine which selectables need to be re-checked after a selectable
     * was disabled.
     */
    private val requiredBy: com.google.common.collect.ImmutableMultimap<CrosstoolSelectable?, CrosstoolSelectable?>

    private val defaultSelectables: com.google.common.collect.ImmutableList<String?>

    /**
     * A cache of feature selection results, so we do not recalculate the feature selection for all
     * actions. This may not be initialized on deserialization.
     */
    @Transient
    private var configurationCache: com.github.benmanes.caffeine.cache.LoadingCache<com.google.common.collect.ImmutableSet<String?>?, FeatureConfiguration?>? =
        buildConfigurationCache()

    private val ccToolchainPath: PathFragment?

    /**
     * Constructs the feature configuration from a [CcToolchainConfigInfo].
     * 
     * @param ccToolchainConfigInfo the toolchain information as specified by the user.
     * @param ccToolchainPath location of the cc_toolchain.
     * @throws EvalException if the configuration has logical errors.
     */
    init {
        // Build up the feature/action config graph.  We refer to features/action configs as
        // 'selectables'.
        // First, we build up the map of name -> selectables in one pass, so that earlier selectables
        // can reference later features in their configuration.
        val selectablesBuilder: com.google.common.collect.ImmutableList.Builder<CrosstoolSelectable?> =
            com.google.common.collect.ImmutableList.builder<CrosstoolSelectable?>()
        val selectablesByName: HashMap<String?, CrosstoolSelectable?> = HashMap<String?, CrosstoolSelectable?>()

        // Also build a map from action -> action_config, for use in tool lookups
        val actionConfigsByActionName: com.google.common.collect.ImmutableMap.Builder<String?, ActionConfig?> =
            com.google.common.collect.ImmutableMap.builder<String?, ActionConfig?>()

        val defaultSelectablesBuilder: com.google.common.collect.ImmutableList.Builder<String?> =
            com.google.common.collect.ImmutableList.builder<String?>()
        val features: com.google.common.collect.ImmutableList<Feature> = ccToolchainConfigInfo.getFeatures()
        for (feature in features) {
            selectablesBuilder.add(feature)
            selectablesByName.put(feature.getName(), feature)
            if (feature.isEnabled()) {
                defaultSelectablesBuilder.add(feature.getName())
            }
        }

        val actionConfigs: com.google.common.collect.ImmutableList<ActionConfig> =
            ccToolchainConfigInfo.getActionConfigs()
        for (actionConfig in actionConfigs) {
            selectablesBuilder.add(actionConfig)
            selectablesByName.put(actionConfig.getName(), actionConfig)
            actionConfigsByActionName.put(actionConfig.getActionName(), actionConfig)
            if (actionConfig.isEnabled()) {
                defaultSelectablesBuilder.add(actionConfig.getName())
            }
        }
        this.defaultSelectables = defaultSelectablesBuilder.build()

        this.selectables = selectablesBuilder.build()
        this.selectablesByName =
            com.google.common.collect.ImmutableSortedMap.copyOf<String?, CrosstoolSelectable?>(selectablesByName)

        checkForActionNameDups(actionConfigs)
        checkForActivatableDups(this.selectables)

        this.actionConfigsByActionName = actionConfigsByActionName.buildOrThrow()

        this.artifactNamePatterns = ccToolchainConfigInfo.getArtifactNamePatterns()

        // Next, we build up all forward references for 'implies', 'requires', and 'provides' edges.
        val implies: com.google.common.collect.ImmutableMultimap.Builder<CrosstoolSelectable?, CrosstoolSelectable?> =
            com.google.common.collect.ImmutableMultimap.builder<CrosstoolSelectable?, CrosstoolSelectable?>()
        val requires: com.google.common.collect.ImmutableMultimap.Builder<CrosstoolSelectable?, com.google.common.collect.ImmutableSet<CrosstoolSelectable?>?> =
            com.google.common.collect.ImmutableMultimap.builder<CrosstoolSelectable?, com.google.common.collect.ImmutableSet<CrosstoolSelectable?>?>()
        val provides: com.google.common.collect.ImmutableMultimap.Builder<CrosstoolSelectable?, String?> =
            com.google.common.collect.ImmutableMultimap.builder<CrosstoolSelectable?, String?>()
        // We also store the reverse 'implied by' and 'required by' edges during this pass.
        val impliedBy: com.google.common.collect.ImmutableMultimap.Builder<CrosstoolSelectable?, CrosstoolSelectable?> =
            com.google.common.collect.ImmutableMultimap.builder<CrosstoolSelectable?, CrosstoolSelectable?>()
        val requiredBy: com.google.common.collect.ImmutableMultimap.Builder<CrosstoolSelectable?, CrosstoolSelectable?> =
            com.google.common.collect.ImmutableMultimap.builder<CrosstoolSelectable?, CrosstoolSelectable?>()

        for (feature in features) {
            val name: String = feature.getName()
            val selectable: CrosstoolSelectable? = selectablesByName.get(name)
            for (requiredFeatures in feature.getRequires()) {
                val allOf: com.google.common.collect.ImmutableSet.Builder<CrosstoolSelectable?> =
                    com.google.common.collect.ImmutableSet.builder<CrosstoolSelectable?>()
                for (requiredName in requiredFeatures) {
                    val required = getActivatableOrFail(requiredName, name)
                    allOf.add(required)
                    requiredBy.put(required, selectable)
                }
                requires.put(selectable, allOf.build())
            }
            for (impliedName in feature.getImplies()) {
                val implied = getActivatableOrFail(impliedName, name)
                impliedBy.put(implied, selectable)
                implies.put(selectable, implied)
            }
            for (providesName in feature.getProvides()) {
                provides.put(selectable, providesName)
            }
        }

        for (actionConfig in actionConfigs) {
            val name: String? = actionConfig.getName()
            val selectable: CrosstoolSelectable? = selectablesByName.get(name)
            for (impliedName in actionConfig.getImplies()) {
                val implied = getActivatableOrFail(impliedName, name)
                impliedBy.put(implied, selectable)
                implies.put(selectable, implied)
            }
        }

        this.implies = implies.build()
        this.requires = requires.build()
        this.provides = provides.build().inverse()
        this.impliedBy = impliedBy.build()
        this.requiredBy = requiredBy.build()
        this.ccToolchainPath = ccToolchainPath
    }

    /**
     * @return an empty `FeatureConfiguration` cache.
     */
    private fun buildConfigurationCache(): com.github.benmanes.caffeine.cache.LoadingCache<com.google.common.collect.ImmutableSet<String?>?, FeatureConfiguration?> {
        return Caffeine.newBuilder() // TODO(klimek): Benchmark and tweak once we support a larger configuration.
            .maximumSize(10000)
            .build<com.google.common.collect.ImmutableSet<String?>?, FeatureConfiguration?>(com.github.benmanes.caffeine.cache.CacheLoader { requestedFeatures: com.google.common.collect.ImmutableSet<kotlin.String?>? ->
                computeFeatureConfiguration(
                    requestedFeatures
                )
            })
    }

    @net.starlark.java.annot.StarlarkMethod(
        name = "configure_features",
        documented = false,
        parameters = [net.starlark.java.annot.Param(name = "requested_features", named = true)],
        useStarlarkThread = true
    )
    @Throws(net.starlark.java.eval.EvalException::class)
    fun configureFeatures(
        requestedFeatures: net.starlark.java.eval.StarlarkList<*>?, thread: net.starlark.java.eval.StarlarkThread?
    ): FeatureConfigurationForStarlark {
        try {
            CcModule.Companion.checkPrivateStarlarkificationAllowlist(thread)
            return FeatureConfigurationForStarlark.Companion.from(
                getFeatureConfiguration(
                    com.google.common.collect.ImmutableSet.copyOf<String?>(
                        net.starlark.java.eval.Sequence.cast<String?>(
                            requestedFeatures,
                            String::class.java,
                            "requested_features"
                        )
                    )
                )
            )
        } catch (ex: CollidingProvidesException) {
            throw net.starlark.java.eval.EvalException(ex)
        }
    }

    /**
     * Given a list of `requestedSelectables`, returns all features that are enabled by the
     * toolchain configuration.
     * 
     * 
     * A requested feature will not be enabled if the toolchain does not support it (which may
     * depend on other requested features).
     * 
     * 
     * Additional features will be enabled if the toolchain supports them and they are implied by
     * requested features.
     * 
     * 
     * If multiple threads call this method we may do additional work in initializing the cache.
     * This reinitialization is benign.
     */
    @Throws(CollidingProvidesException::class)
    fun getFeatureConfiguration(requestedSelectables: com.google.common.collect.ImmutableSet<String?>?): FeatureConfiguration? {
        try {
            if (configurationCache == null) {
                configurationCache = buildConfigurationCache()
            }
            return configurationCache.get(requestedSelectables)
        } catch (e: CompletionException) {
            com.google.common.base.Throwables.throwIfInstanceOf<CollidingProvidesException?>(
                e.getCause(),
                CollidingProvidesException::class.java
            )
            throw e
        }
    }

    /**
     * Given `featureSpecification`, returns a FeatureConfiguration with all requested features
     * enabled.
     * 
     * 
     * A requested feature will not be enabled if the toolchain does not support it (which may
     * depend on other requested features).
     * 
     * 
     * Additional features will be enabled if the toolchain supports them and they are implied by
     * requested features.
     */
    @Throws(CollidingProvidesException::class)
    fun computeFeatureConfiguration(requestedSelectables: com.google.common.collect.ImmutableSet<String?>): FeatureConfiguration? {
        // Command line flags will be output in the order in which they are specified in the toolchain
        // configuration.
        return FeatureSelection(
            requestedSelectables,
            selectablesByName,
            selectables,
            provides,
            implies,
            impliedBy,
            requires,
            requiredBy,
            actionConfigsByActionName,
            ccToolchainPath
        )
            .run()
    }

    @net.starlark.java.annot.StarlarkMethod(
        name = "default_features_and_action_configs",
        documented = false,
        useStarlarkThread = true
    )
    @Throws(net.starlark.java.eval.EvalException::class)
    fun getDefaultFeaturesAndActionConfigsForStarlark(thread: net.starlark.java.eval.StarlarkThread?): net.starlark.java.eval.StarlarkList<String?>? {
        CcModule.Companion.checkPrivateStarlarkificationAllowlist(thread)
        return net.starlark.java.eval.StarlarkList.immutableCopyOf<String?>(defaultSelectables)
    }

    val defaultFeaturesAndActionConfigs: com.google.common.collect.ImmutableList<String?>
        get() = defaultSelectables

    /**
     * @return the selectable with the given `name`.s
     * @throws EvalException if no selectable with the given name was configured.
     */
    @Throws(net.starlark.java.eval.EvalException::class)
    private fun getActivatableOrFail(name: String?, reference: String?): CrosstoolSelectable? {
        if (!selectablesByName.containsKey(name)) {
            throw net.starlark.java.eval.Starlark.errorf(
                "Invalid toolchain configuration: feature '%s', which is referenced from feature '%s',"
                        + " is not defined.",
                name, reference
            )
        }
        return selectablesByName.get(name)
    }

    @get:com.google.common.annotations.VisibleForTesting
    val activatableNames: MutableCollection<String?>
        get() = selectablesByName.keySet()

    /**
     * Returns the artifact selected by the toolchain for the given action type and action category.
     */
    fun getArtifactNameForCategory(artifactCategory: ArtifactCategory?, outputName: String?): String? {
        val output: PathFragment = PathFragment.create(outputName)
        return output
            .getParentDirectory()
            .getChild(artifactNamePatterns.get(artifactCategory).getArtifactName(output.getBaseName()))
            .getPathString()
    }

    /**
     * Returns the artifact name extension selected by the toolchain for the given artifact category.
     */
    fun getArtifactNameExtensionForCategory(artifactCategory: ArtifactCategory?): String? {
        return artifactNamePatterns.get(artifactCategory)!!.extension
    }

    companion object {
        /** Error message thrown when a toolchain enables two features that provide the same string.  */
        const val COLLIDING_PROVIDES_ERROR: String = "Symbol %s is provided by all of the following features: %s"

        private fun isWithFeaturesSatisfied(
            withFeatureSets: MutableCollection<WithFeatureSet>, enabledFeatureNames: MutableSet<String?>
        ): Boolean {
            if (withFeatureSets.isEmpty()) {
                return true
            }
            for (featureSet in withFeatureSets) {
                if (enabledFeatureNames.containsAll(featureSet.features)
                    && featureSet.notFeatures.stream()
                        .noneMatch(java.util.function.Predicate { o: String? -> enabledFeatureNames.contains(o) })
                ) {
                    return true
                }
            }
            return false
        }

        @Throws(net.starlark.java.eval.EvalException::class)
        private fun checkForActivatableDups(selectables: Iterable<CrosstoolSelectable>) {
            val names: MutableCollection<String?> = HashSet<String?>()
            for (selectable in selectables) {
                if (!names.add(selectable.name)) {
                    throw net.starlark.java.eval.Starlark.errorf(
                        "Invalid toolchain configuration: feature or action config '%s' was specified multiple"
                                + " times.",
                        selectable.name
                    )
                }
            }
        }

        @Throws(net.starlark.java.eval.EvalException::class)
        private fun checkForActionNameDups(actionConfigs: Iterable<ActionConfig>) {
            val actionNames: MutableCollection<String?> = HashSet<String?>()
            for (actionConfig in actionConfigs) {
                if (!actionNames.add(actionConfig.actionName)) {
                    throw net.starlark.java.eval.Starlark.errorf(
                        "Invalid toolchain configuration: multiple action configs for action '%s'",
                        actionConfig.actionName
                    )
                }
            }
        }
    }
}
