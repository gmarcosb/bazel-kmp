// Copyright 2023 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.analysis.producers

import com.google.devtools.build.lib.analysis.AspectResolutionHelpers.computeAspectCollection

/**
 * Computes requested prerequisite(s), applying any requested aspects.
 * 
 * 
 * A dependency is specified by a [Label] and an execution platform [Label] if it is
 * a toolchain.
 * 
 * 
 * Its configuration is determined by an [AttributeConfiguration], which may be split and
 * result in multiple outputs.
 * 
 * 
 * Computes any specified aspects, applying the appropriate filtering, and merges them into the
 * resulting values.
 */
internal class PrerequisitesProducer
    (
    parameters: PrerequisiteParameters,
    label: com.google.devtools.build.lib.cmdline.Label?,
    executionPlatformLabel: com.google.devtools.build.lib.cmdline.Label?,
    configuration: AttributeConfiguration,
    propagatingAspects: com.google.common.collect.ImmutableList<Aspect?>,
    sink: ResultSink,
    useBaseTargetPrerequisitesSupplier: Boolean,
    next: StateMachine?
) : StateMachine, com.google.devtools.build.lib.analysis.producers.ConfiguredTargetAndDataProducer.ResultSink,
    com.google.devtools.build.lib.analysis.producers.ConfiguredAspectProducer.ResultSink {
    internal interface ResultSink {
        fun acceptPrerequisitesValue(prerequisites: Array<ConfiguredTargetAndData?>?)

        fun acceptPrerequisitesError(error: NoSuchThingException?)

        fun acceptPrerequisitesError(error: InvalidVisibilityDependencyException?)

        fun acceptPrerequisitesCreationError(error: ConfiguredValueCreationException?)

        fun acceptPrerequisitesAspectError(error: DependencyEvaluationException?)

        fun acceptPrerequisitesAspectError(error: AspectCreationException?)
    }

    // -------------------- Input --------------------
    private val parameters: PrerequisiteParameters
    private val label: com.google.devtools.build.lib.cmdline.Label?

    // Non-null for toolchain prerequisites.
    private val executionPlatformLabel: com.google.devtools.build.lib.cmdline.Label?
    private val configuration: AttributeConfiguration
    private val propagatingAspects: com.google.common.collect.ImmutableList<Aspect?>
    private val useBaseTargetPrerequisitesSupplier: Boolean

    // -------------------- Output --------------------
    private val sink: ResultSink

    // -------------------- Sequencing --------------------
    private val next: StateMachine?

    // -------------------- Internal State --------------------
    private var configuredTargets: Array<ConfiguredTargetAndData?>
    private var execAspects: com.google.common.collect.ImmutableList<Aspect?> =
        com.google.common.collect.ImmutableList.of<Aspect?>()
    private var hasError = false

    init {
        this.parameters = parameters
        this.label = label
        this.executionPlatformLabel = executionPlatformLabel
        this.configuration = configuration
        this.propagatingAspects = propagatingAspects
        this.sink = sink
        this.useBaseTargetPrerequisitesSupplier = useBaseTargetPrerequisitesSupplier
        this.next = next

        // size > 0 guaranteed by contract of SplitTransition.
        val size: Int = configuration.count()
        this.configuredTargets = arrayOfNulls<ConfiguredTargetAndData>(size)
    }

    override fun step(tasks: StateMachine.Tasks): StateMachine {
        val baseTargetPrerequisitesSupplier: BaseTargetPrerequisitesSupplier? =
            if (useBaseTargetPrerequisitesSupplier) parameters.baseTargetPrerequisitesSupplier() else null
        when (configuration.kind()) {
            com.google.devtools.build.lib.analysis.producers.AttributeConfiguration.Kind.VISIBILITY -> tasks.enqueue(
                ConfiguredTargetAndDataProducer(
                    getPrerequisiteKey( /* configurationKey= */null),  /* transitionKeys= */
                    com.google.common.collect.ImmutableList.of<String?>(),
                    parameters.transitiveState(),
                    this as com.google.devtools.build.lib.analysis.producers.ConfiguredTargetAndDataProducer.ResultSink,  /* outputIndex= */
                    0,
                    baseTargetPrerequisitesSupplier
                )
            )

            com.google.devtools.build.lib.analysis.producers.AttributeConfiguration.Kind.NULL_TRANSITION_KEYS -> tasks.enqueue(
                ConfiguredTargetAndDataProducer(
                    getPrerequisiteKey( /* configurationKey= */null),
                    configuration.nullTransitionKeys(),
                    parameters.transitiveState(),
                    this as com.google.devtools.build.lib.analysis.producers.ConfiguredTargetAndDataProducer.ResultSink,  /* outputIndex= */
                    0,
                    baseTargetPrerequisitesSupplier
                )
            )

            com.google.devtools.build.lib.analysis.producers.AttributeConfiguration.Kind.UNARY -> tasks.enqueue(
                ConfiguredTargetAndDataProducer(
                    getPrerequisiteKey(configuration.unary()),  /* transitionKeys= */
                    com.google.common.collect.ImmutableList.of<String?>(),
                    parameters.transitiveState(),
                    this as com.google.devtools.build.lib.analysis.producers.ConfiguredTargetAndDataProducer.ResultSink,  /* outputIndex= */
                    0,
                    baseTargetPrerequisitesSupplier
                )
            )

            com.google.devtools.build.lib.analysis.producers.AttributeConfiguration.Kind.SPLIT -> {
                var index = 0
                for (entry in configuration.split().entries) {
                    tasks.enqueue(
                        ConfiguredTargetAndDataProducer(
                            getPrerequisiteKey(entry.value),
                            com.google.common.collect.ImmutableList.of<String?>(entry.key),
                            parameters.transitiveState(),
                            this as com.google.devtools.build.lib.analysis.producers.ConfiguredTargetAndDataProducer.ResultSink,
                            index,
                            baseTargetPrerequisitesSupplier
                        )
                    )
                    ++index
                }
            }
        }
        return StateMachine { tasks: StateMachine.Tasks? -> this.computeConfiguredAspects(tasks) }
    }

    override fun acceptConfiguredTargetAndData(value: ConfiguredTargetAndData?, index: Int) {
        configuredTargets[index] = value
    }

    override fun acceptConfiguredTargetAndDataError(error: NoSuchThingException?) {
        hasError = true
        sink.acceptPrerequisitesError(error)
    }

    override fun acceptConfiguredTargetAndDataError(error: InconsistentNullConfigException?) {
        hasError = true
        if (configuration.kind() == com.google.devtools.build.lib.analysis.producers.AttributeConfiguration.Kind.VISIBILITY) {
            // The target was configurable, but used as a visibility dependency. This is invalid because
            // only `PackageGroup`s are accepted as visibility dependencies and those are not
            // configurable. Propagates the exception with more precise information.
            sink.acceptPrerequisitesError(InvalidVisibilityDependencyException(label))
            return
        }
        // `configuration.kind()` was `NULL_TRANSITION_KEYS`. This is only used when the target is in
        // the same package as the parent and not configurable so this should never happen.
        throw java.lang.IllegalStateException(error)
    }

    override fun acceptConfiguredTargetAndDataError(error: ConfiguredValueCreationException?) {
        hasError = true
        sink.acceptPrerequisitesCreationError(error)
    }

    @Throws(java.lang.InterruptedException::class)
    private fun computeConfiguredAspects(tasks: StateMachine.Tasks): StateMachine {
        if (hasError) {
            return StateMachine.DONE
        }

        if (configuration.kind() == com.google.devtools.build.lib.analysis.producers.AttributeConfiguration.Kind.VISIBILITY) {
            // Verifies that the dependency is a `package_group`. The value is always at index 0 because
            // the `VISIBILITY` configuration is always unary.
            if (configuredTargets[0].getConfiguredTarget() !is PackageGroupConfiguredTarget) {
                sink.acceptPrerequisitesError(InvalidVisibilityDependencyException(label))
                return StateMachine.DONE
            }
        }

        cleanupValues()

        if (parameters.loadExecAspectsKey() != null) {
            tasks.lookUp(
                parameters.loadExecAspectsKey(),
                java.util.function.Consumer { execAspects: SkyValue? -> this.acceptExecAspects(execAspects) } as java.util.function.Consumer<SkyValue?>)
        }
        return StateMachine { tasks: StateMachine.Tasks? -> this.maybeFilterAspects(tasks) }
    }

    @Throws(java.lang.InterruptedException::class)
    private fun maybeFilterAspects(tasks: StateMachine.Tasks): StateMachine? {
        val aspects: AspectCollection
        try {
            // All configured targets in the set have the same underlying target so using an arbitrary one
            // for aspect filtering is safe.
            val filteredAspects: com.google.common.collect.ImmutableList<Aspect?> =
                filterAspectsBasedOnTarget(propagatingAspects, execAspects, configuredTargets[0])
            if (filteredAspects.isEmpty()) {
                aspects = AspectCollection.EMPTY
            } else {
                if (configuredTargets[0].isTargetRule()) {
                    aspects =
                        computeAspectCollection(
                            filteredAspects,
                            configuredTargets[0].getTargetAdvertisedProviders(),
                            configuredTargets[0].getTargetLabel(),
                            configuredTargets[0].getRuleDefinitionEnvironmentLabel(),
                            configuredTargets[0].getRuleClass(),
                            configuredTargets[0].getOnlyTagsAttribute(),
                            configuredTargets[0].getLocation(),
                            parameters.eventHandler()
                        )
                } else {
                    aspects =
                        computeAspectCollectionNoAspectsFiltering(
                            filteredAspects,
                            configuredTargets[0].getTargetLabel(),
                            configuredTargets[0].getLocation()
                        )
                }
            }
        } catch (e: InconsistentAspectOrderException) {
            sink.acceptPrerequisitesAspectError(DependencyEvaluationException(e))
            return StateMachine.DONE
        } catch (e: net.starlark.java.eval.EvalException) {
            parameters.eventHandler().handle(
                com.google.devtools.build.lib.events.Event.error(
                    parameters.location(),
                    e.getMessageWithStack()
                )
            )
            sink.acceptPrerequisitesAspectError(
                DependencyEvaluationException(e, parameters.location())
            )
            return StateMachine.DONE
        }

        if (aspects.isEmpty()) { // Short circuits if there are no aspects.
            sink.acceptPrerequisitesValue(configuredTargets)
            return next
        }

        for (i in configuredTargets.indices) {
            val target: ConfiguredTargetAndData? = configuredTargets[i]
            configuredTargets[i] = null
            tasks.enqueue(
                ConfiguredAspectProducer(
                    aspects,
                    target,
                    this as com.google.devtools.build.lib.analysis.producers.ConfiguredAspectProducer.ResultSink,
                    i,
                    parameters.transitiveState()
                )
            )
        }
        return StateMachine { tasks: StateMachine.Tasks? -> this.emitMergedTargets(tasks) }
    }

    override fun acceptConfiguredAspectMergedTarget(
        outputIndex: Int, mergedTarget: ConfiguredTargetAndData?
    ) {
        configuredTargets[outputIndex] = mergedTarget
    }

    override fun acceptConfiguredAspectError(error: MergingException) {
        hasError = true
        sink.acceptPrerequisitesAspectError(
            DependencyEvaluationException(
                ConfiguredValueCreationException(
                    parameters.location(),
                    error.getMessage(),
                    parameters.label(),
                    parameters.eventId(),  /* rootCauses= */
                    null,  /* detailedExitCode= */
                    null
                ),  /* depReportedOwnError= */
                false
            )
        )
    }

    override fun acceptConfiguredAspectError(error: AspectCreationException?) {
        hasError = true
        sink.acceptPrerequisitesAspectError(error)
    }

    fun acceptExecAspects(execAspects: SkyValue?) {
        if (execAspects is LoadAspectsValue) {
            this.execAspects = execAspects.getAspects()
        }
    }

    private fun emitMergedTargets(tasks: StateMachine.Tasks?): StateMachine? {
        if (!hasError) {
            sink.acceptPrerequisitesValue(configuredTargets)
            return next
        } else {
            return StateMachine.DONE
        }
    }

    private fun getPrerequisiteKey(configurationKey: BuildConfigurationKey?): ConfiguredTargetKey? {
        val key: com.google.devtools.build.lib.skyframe.ConfiguredTargetKey.Builder =
            ConfiguredTargetKey.builder().setLabel(label).setConfigurationKey(configurationKey)
        if (executionPlatformLabel != null) {
            key.setExecutionPlatformLabel(executionPlatformLabel)
        }
        return key.build()
    }

    private fun cleanupValues() {
        if (configuredTargets.size == 1) {
            return
        }

        // Otherwise, there was a split transition.
        if (configuredTargets[0].getConfiguration() == null) {
            // The resulting configurations are null. Aggregates the transition keys.
            val keys: com.google.common.collect.ImmutableList.Builder<String?> =
                com.google.common.collect.ImmutableList.Builder<String?>()
            keys.addAll(configuredTargets[0].getTransitionKeys())
            for (i in 1..<configuredTargets.size) {
                com.google.common.base.Preconditions.checkState(
                    configuredTargets[i].getConfiguration() == null,
                    "inconsistent split transition result from %s to %s",
                    parameters.label(),
                    label
                )
                keys.addAll(configuredTargets[i].getTransitionKeys())
            }
            configuredTargets =
                arrayOf<ConfiguredTargetAndData?>(configuredTargets[0].copyWithTransitionKeys(keys.build()))
            return
        }

        // Deduplicates entries that have identical configurations and thus identical values, keeping
        // only the first entry with the configuration.
        val seenConfigurations: HashSet<BuildConfigurationKey?> = HashSet<BuildConfigurationKey?>()
        var firstIndex = 0
        for (i in configuredTargets.indices) {
            if (!seenConfigurations.add(configuredTargets[i].getConfigurationKey())) {
                // The target at `i` was a duplicate of a previous target. Deletes it by:
                // 1. overwriting it with the first target; and
                // 2. removing the slot previously associated with the first target.
                configuredTargets[i] = configuredTargets[firstIndex++]
            }
        }
        if (firstIndex > 0) {
            configuredTargets = java.util.Arrays.copyOfRange<ConfiguredTargetAndData?>(
                configuredTargets,
                firstIndex,
                configuredTargets.size
            )
        }
        java.util.Arrays.sort<ConfiguredTargetAndData?>(configuredTargets, ConfiguredTargetAndData.SPLIT_DEP_ORDERING)
    }

    companion object {
        private fun filterAspectsBasedOnTarget(
            propagatingAspects: com.google.common.collect.ImmutableList<Aspect?>,
            execAspects: com.google.common.collect.ImmutableList<Aspect?>,
            prerequisite: ConfiguredTargetAndData
        ): com.google.common.collect.ImmutableList<Aspect?> {
            if (prerequisite.isTargetOutputFile()) {
                return propagatingAspects.stream()
                    .filter { aspect: Aspect? -> aspect.getDefinition().applyToGeneratingRules() }
                    .collect(com.google.common.collect.ImmutableList.toImmutableList<Aspect?>())
            }

            if (!prerequisite.isTargetRule() || prerequisite.isMaterializerRule()) {
                return com.google.common.collect.ImmutableList.of<Aspect?>()
            }

            if (prerequisite.getConfiguration().isExecConfiguration()) {
                return com.google.common.collect.ImmutableList.builder<Aspect?>().addAll(propagatingAspects)
                    .addAll(execAspects).build()
            }

            return propagatingAspects
        }
    }
}
