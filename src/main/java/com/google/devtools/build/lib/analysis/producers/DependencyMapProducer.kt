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

import com.google.devtools.build.lib.analysis.AspectResolutionHelpers.computeAttributeAspects

/**
 * Computes the full multimap of prerequisite values from a multimap of labels.
 * 
 * 
 * This class creates a child [DependencyProducer] for each ([DependencyKind], [ ]) multimap entry and collects the results. It outputs a multimap with the same entries,
 * replacing [Label] values with the corresponding computed [ConfiguredTargetAndData]
 * dependency values.
 */
class DependencyMapProducer(
    parameters: PrerequisiteParameters,
    dependencyLabels: OrderedSetMultimap<DependencyKind, com.google.devtools.build.lib.cmdline.Label?>,
    sink: ResultSink
) : StateMachine, com.google.devtools.build.lib.analysis.producers.DependencyProducer.ResultSink {
    /** Receiver for output of [DependencyMapProducer].  */
    interface ResultSink : TransitionCollector {
        fun acceptDependencyMap(value: OrderedSetMultimap<DependencyKind?, ConfiguredTargetAndData?>?)

        fun acceptMaterializerTargets(
            value: OrderedSetMultimap<DependencyKind?, ConfiguredTargetAndData?>?
        )

        fun acceptDependencyMapError(error: DependencyError?)

        fun acceptDependencyMapError(error: MissingEdgeError?)
    }

    // -------------------- Input --------------------
    private val parameters: PrerequisiteParameters
    private val dependencyLabels: OrderedSetMultimap<DependencyKind, com.google.devtools.build.lib.cmdline.Label?>

    // -------------------- Output --------------------
    private val sink: ResultSink

    // -------------------- Internal State --------------------
    /**
     * This buffer receives results from child [DependencyProducer]s.
     * 
     * 
     * The indices break down the result by the following.
     * 
     * 
     *  1. The entries of [.dependencyLabels].
     *  1. The configurations for that entry (more than one if there is a split transition).
     * 
     * 
     * 
     * It would not be straightforward to replace this with a [OrderedSetMultimap] because
     * the child [DependencyProducer]s complete in an arbitrary order and the ordering of [ ][.dependencyLabels] must be preserved. Additionally, this is a fairly hot codepath and the
     * additional overhead of maps would consume significant resources.
     */
    private val results: Array<Array<ConfiguredTargetAndData?>?>

    private var materializerTargets: OrderedSetMultimap<DependencyKind?, ConfiguredTargetAndData?>? = null

    private var computedAttributeAspects: com.google.common.collect.ImmutableMultimap<Aspect?, String?>?
    private var computedToolchainsAspects: com.google.common.collect.ImmutableMultimap<Aspect?, com.google.devtools.build.lib.cmdline.Label?>?

    private var lastError: DependencyError? = null

    init {
        this.parameters = parameters
        this.dependencyLabels = dependencyLabels
        this.sink = sink
        this.results = arrayOfNulls<Array<ConfiguredTargetAndData?>>(dependencyLabels.size())
        this.computedAttributeAspects = null
        this.computedToolchainsAspects = null
    }

    private fun computePrerequisitesForMaterializer(
        rule: com.google.devtools.build.lib.packages.Rule,
        dependencyMap: ImmutableSortedKeyListMultimap<String?, ConfiguredTargetAndData?>
    ): com.google.common.collect.ImmutableMap<String?, Any?> {
        val result: MutableMap<String?, Any?> = TreeMap<String?, Any?>()

        for (attribute in rule.getAttributes()) {
            if (attribute.getType().getLabelClass() != LabelClass.DEPENDENCY
                || !attribute.isForDependencyResolution()
            ) {
                continue
            }

            result.put(
                attribute.getName(),
                com.google.common.collect.Lists.transform<ConfiguredTargetAndData?, ConfiguredTarget?>(
                    dependencyMap.get(attribute.getName()),
                    com.google.common.base.Function { obj: ConfiguredTargetAndData? -> obj.getConfiguredTarget() })
            )
        }

        return com.google.common.collect.ImmutableMap.copyOf<String?, Any?>(result)
    }

    /** An exception thrown if a materializer cannot be evaluated.  */
    class MaterializerException private constructor(message: String?, cause: java.lang.Exception?) :
        java.lang.Exception(message, cause) {
        companion object {
            /** This one says "on attribute" because attribute materializers are "on attributes".  */
            fun materializerAttributeException(
                attribute: com.google.devtools.build.lib.packages.Attribute,
                label: com.google.devtools.build.lib.cmdline.Label?,
                message: String?,
                cause: java.lang.Exception?
            ): MaterializerException {
                return MaterializerException(
                    String.format(
                        "Error while evaluating materializer on attribute '%s' of target '%s': %s",
                        attribute.getPublicName(), label, message
                    ),
                    cause
                )
            }

            /** This one says "in attribute" because materializer targets are "in attributes".  */
            fun materializerRuleException(
                attribute: com.google.devtools.build.lib.packages.Attribute,
                label: com.google.devtools.build.lib.cmdline.Label?,
                message: String?,
                cause: java.lang.Exception?
            ): MaterializerException {
                return MaterializerException(
                    String.format(
                        "Error while evaluating materializer target in attribute '%s' of target '%s': %s",
                        attribute.getPublicName(), label, message
                    ),
                    cause
                )
            }
        }
    }

    @Throws(java.lang.InterruptedException::class)
    private fun getMaterializationResultMaybe(kind: DependencyKind): com.google.common.collect.ImmutableList<com.google.devtools.build.lib.cmdline.Label?>? {
        if (kind.getAttribute() == null) {
            return null
        }

        if (!kind.getAttribute().isMaterializing()) {
            return null
        }

        // By this point, we know the attribute is a materializingDefault. Compute the attributes
        // available to it...
        val attrs: ImmutableSortedKeyListMultimap<String?, ConfiguredTargetAndData?> = createMaterializerMap()
        val prerequisitesForMaterializer: com.google.common.collect.ImmutableMap<String?, Any?> =
            computePrerequisitesForMaterializer(parameters.associatedRule(), attrs)

        // ...then invoke the function,
        val materializingDefault: MaterializingDefault<*, *> = kind.getAttribute().getMaterializer()
        val materializerResult: Any?
        try {
            materializerResult =
                materializingDefault.resolve(
                    parameters.associatedRule(),
                    parameters.attributeMap(),
                    prerequisitesForMaterializer,
                    parameters.eventHandler()
                )
        } catch (e: net.starlark.java.eval.EvalException) {
            parameters.eventHandler().handle(
                com.google.devtools.build.lib.events.Event.error(
                    parameters.location(),
                    e.getMessageWithStack()
                )
            )
            acceptDependencyError(
                DependencyError.Companion.of(
                    MaterializerException.Companion.materializerAttributeException(
                        kind.getAttribute(), parameters.label(), e.message, e
                    )
                )
            )
            return null
        }

        // ...then return its return value as the value of the attribute.
        if (kind.getAttribute().getType() === BuildType.LABEL) {
            return if (materializerResult == null)
                com.google.common.collect.ImmutableList.of<com.google.devtools.build.lib.cmdline.Label?>()
            else
                com.google.common.collect.ImmutableList.of<com.google.devtools.build.lib.cmdline.Label?>(
                    BuildType.LABEL.cast(
                        materializerResult
                    )
                )
        } else if (kind.getAttribute().getType() === BuildType.LABEL_LIST) {
            return com.google.common.collect.ImmutableList.copyOf<com.google.devtools.build.lib.cmdline.Label?>(
                BuildType.LABEL_LIST.cast(materializerResult)
            )
        } else {
            throw java.lang.IllegalStateException("bad value returned from materializingDefault")
        }
    }

    private inner class MaterializedDependencySink(private val resultsIndex: Int, private var remaining: Int) :
        com.google.devtools.build.lib.analysis.producers.DependencyProducer.ResultSink {
        // The outer array is for the individual labels the materializer returns, the inner array is for
        // the different configurations in case the attribute has a split transition
        private val materializationResults: Array<Array<ConfiguredTargetAndData?>?>

        init {
            this.materializationResults = arrayOfNulls<Array<ConfiguredTargetAndData?>>(remaining)
        }

        public override fun acceptTransition(
            kind: DependencyKind?,
            label: com.google.devtools.build.lib.cmdline.Label?,
            transition: ConfigurationTransition?
        ) {
            this@DependencyMapProducer.acceptTransition(kind, label, transition)
        }

        override fun acceptDependencyValues(index: Int, values: Array<ConfiguredTargetAndData?>?) {
            materializationResults[index] = values
            if (--remaining > 0) {
                // More dependencies to come
                return
            }

            // "results" is an array of arrays: for each (dependency kind, label) pair, it contains an
            // array with a dependency for each configuration in a split transition. Materializers abuse
            // this mechanism by putting all configured targets returned by a materializer into the second
            // array because it cannot be known how many of them there are before "results" is created.
            // This means that if a materializer has a split configuration, we need to do a level of
            // flattening here.
            results[resultsIndex] =
                java.util.Arrays.stream<Array<ConfiguredTargetAndData?>?>(materializationResults)
                    .flatMap<ConfiguredTargetAndData?> { array: Array<ConfiguredTargetAndData?>? ->
                        java.util.Arrays.stream(
                            array
                        )
                    }
                    .toArray<ConfiguredTargetAndData?> { _Dummy_.__Array__() }
        }

        override fun acceptMaterializerTarget(
            dependencyKind: DependencyKind?, target: ConfiguredTargetAndData?
        ) {
            this@DependencyMapProducer.acceptMaterializerTarget(dependencyKind, target)
        }

        override fun acceptDependencyError(error: DependencyError) {
            this@DependencyMapProducer.acceptDependencyError(error)
        }

        override fun acceptDependencyError(error: MissingEdgeError?) {
            this@DependencyMapProducer.acceptDependencyError(error)
        }
    }

    @Throws(java.lang.InterruptedException::class)
    private fun attributeResolutionStep(
        tasks: StateMachine.Tasks, forMaterializers: Boolean, next: StateMachine
    ): StateMachine {
        var index = 0
        for (entry in dependencyLabels.asMap().entries) {
            val kind: DependencyKind = entry.key
            val forDependencyResolution = isForDependencyResolution(kind)
            val skip = forMaterializers != forDependencyResolution

            // Only call materializer when materialization results are ready
            val materializationResults: com.google.common.collect.ImmutableList<com.google.devtools.build.lib.cmdline.Label?>? =
                if (forMaterializers) null else getMaterializationResultMaybe(kind)

            // The list of aspects is evaluated here to be done once per attribute, rather than once per
            // dependency.
            val aspects: com.google.common.collect.ImmutableList<Aspect?>? =
                if (skip)
                    null
                else
                    computePropagatingAspects(
                        kind,
                        parameters.aspects(),
                        this.computedAttributeAspects,
                        this.computedToolchainsAspects,
                        parameters.associatedRule(),
                        parameters.baseTargetToolchainContexts()
                    )
            for (label in entry.value) {
                val currentIndex = index++
                if (skip) {
                    continue
                }

                if (materializationResults != null) {
                    // DependencyResolver should have left this as null
                    com.google.common.base.Preconditions.checkState(label == null)

                    if (materializationResults.isEmpty()) {
                        results[currentIndex] = arrayOf<ConfiguredTargetAndData?>()
                    } else {
                        val sink =
                            MaterializedDependencySink(currentIndex, materializationResults.size)
                        for (i in materializationResults.indices) {
                            tasks.enqueue(
                                DependencyProducer(
                                    parameters,
                                    kind,
                                    materializationResults.get(i),
                                    aspects,
                                    sink,  /* originatingMaterializerTarget= */
                                    null,
                                    i
                                )
                            )
                        }
                    }
                } else if (label != null) {
                    tasks.enqueue(
                        DependencyProducer(
                            parameters,
                            kind,
                            label,
                            aspects,
                            this as com.google.devtools.build.lib.analysis.producers.DependencyProducer.ResultSink,  /* originatingMaterializerTarget= */
                            null,
                            currentIndex
                        )
                    )
                }
            }
        }

        return next
    }

    @Throws(java.lang.InterruptedException::class)
    override fun step(tasks: StateMachine.Tasks): StateMachine {
        try {
            computeAspectPropagationEdges()
        } catch (e: net.starlark.java.eval.EvalException) {
            parameters.eventHandler().handle(
                com.google.devtools.build.lib.events.Event.error(
                    parameters.location(),
                    e.getMessageWithStack()
                )
            )
            acceptDependencyError(
                DependencyError.Companion.of(DependencyEvaluationException(e, parameters.location()))
            )
            return StateMachine.DONE
        }
        return attributeResolutionStep(
            tasks,
            true,
            StateMachine { tasks: StateMachine.Tasks? -> this.evaluateMaterializersIfNeeded(tasks) })
    }

    @Throws(java.lang.InterruptedException::class)
    private fun evaluateMaterializersIfNeeded(tasks: StateMachine.Tasks): StateMachine {
        return attributeResolutionStep(
            tasks,
            false,
            StateMachine { tasks: StateMachine.Tasks? -> this.buildAndEmitResult(tasks) })
    }

    /** Computes the aspects' propagation attribute names and toolchain types.  */
    @Throws(java.lang.InterruptedException::class, net.starlark.java.eval.EvalException::class)
    private fun computeAspectPropagationEdges() {
        if (parameters.aspects().isEmpty()) {
            return
        }

        this.computedAttributeAspects =
            computeAttributeAspects(
                parameters.aspects(),
                parameters.target(),
                parameters.attributeMap(),
                this.dependencyLabels,
                parameters.eventHandler()
            )
        this.computedToolchainsAspects =
            computeToolchainsAspects(
                parameters.aspects(),
                parameters.target(),
                parameters.attributeMap(),
                this.dependencyLabels,
                parameters.eventHandler()
            )
    }

    override fun acceptDependencyValues(index: Int, values: Array<ConfiguredTargetAndData?>?) {
        results[index] = values
    }

    override fun acceptMaterializerTarget(
        dependencyKind: DependencyKind?, target: ConfiguredTargetAndData?
    ) {
        // Lazily allocate since materializers should be relatively rare.

        if (materializerTargets == null) {
            materializerTargets = OrderedSetMultimap<DependencyKind?, ConfiguredTargetAndData?>()
        }
        materializerTargets.put(dependencyKind, target)
    }

    override fun acceptDependencyError(error: DependencyError) {
        emitErrorIfMostImportant(error)
    }

    override fun acceptDependencyError(error: MissingEdgeError?) {
        sink.acceptDependencyMapError(error)
    }

    public override fun acceptTransition(
        kind: DependencyKind?, label: com.google.devtools.build.lib.cmdline.Label?, transition: ConfigurationTransition?
    ) {
        sink.acceptTransition(kind, label, transition)
    }

    private fun createMaterializerMap(): ImmutableSortedKeyListMultimap<String?, ConfiguredTargetAndData?> {
        val result: com.google.devtools.build.lib.collect.ImmutableSortedKeyListMultimap.Builder<String?, ConfiguredTargetAndData?> =
            ImmutableSortedKeyListMultimap.builder<String?, ConfiguredTargetAndData?>()
        var i = 0
        // It's correct to call .keys() here: it's called once for every entry in the map (not just for
        // every key), which is what's needed to keep in sync with the array in 'results'.
        for (kind in dependencyLabels.keys()) {
            val deps: Array<ConfiguredTargetAndData?>? = results[i++]
            if (deps == null) {
                continue
            }

            val attribute: com.google.devtools.build.lib.packages.Attribute? = kind.getAttribute()
            if (attribute == null) {
                continue
            }

            // An empty `result` means the entry is skipped due to a missing exec group.
            if (deps.size > 0) {
                result.putAll(attribute.getName(), java.util.Arrays.asList<ConfiguredTargetAndData?>(*deps))
            }
        }

        return result.build()
    }

    private fun buildAndEmitResult(tasks: StateMachine.Tasks?): StateMachine {
        if (lastError != null || parameters.transitiveState().hasRootCause()) {
            return StateMachine.DONE // There was an error.
        }

        val output: OrderedSetMultimap<DependencyKind?, ConfiguredTargetAndData?> =
            OrderedSetMultimap<DependencyKind?, ConfiguredTargetAndData?>()
        var i = 0
        // It's correct to call .keys() here: it's called once for every entry in the map (not just for
        // every key), which is what's needed to keep in sync with the array in 'results'.
        for (kind in dependencyLabels.keys()) {
            val result: Array<ConfiguredTargetAndData?>? = results[i++]
            if (result == null) {
                return StateMachine.DONE // There was an error.
            }
            // An empty `result` means the entry is skipped due to a missing exec group.
            if (result.size > 0) {
                output.putAll(kind, java.util.Arrays.asList<ConfiguredTargetAndData?>(*result))
            }
        }

        sink.acceptDependencyMap(output)
        sink.acceptMaterializerTargets(materializerTargets)
        return StateMachine.DONE
    }

    private fun emitErrorIfMostImportant(error: DependencyError) {
        if (lastError == null || DependencyError.Companion.isSecondErrorMoreImportant(lastError, error)) {
            lastError = error
            sink.acceptDependencyMapError(error)
        }
    }

    companion object {
        private fun isForDependencyResolution(dependencyKind: DependencyKind): Boolean {
            if (dependencyKind.getAttribute() == null) {
                return false
            }

            return dependencyKind.getAttribute().isForDependencyResolution()
        }
    }
}
