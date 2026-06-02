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

import com.google.devtools.build.lib.analysis.DependencyKind.OUTPUT_FILE_RULE_DEPENDENCY

/**
 * Evaluates a dependency.
 * 
 * 
 * A dependency is described by a [DependencyKind] (e.g. an attribute), a [Label] to
 * the dependency, and possibly a list of [Aspect]s. This class determines the [ ] based on the parent's configuration. This may include using the [ ] to perform an attribute configuration transition.
 * 
 * 
 * It then delegates computation of the [ConfiguredTargetAndData] prerequisite values to
 * [PrerequisitesProducer] with the determined configuration(s).
 * 
 * 
 * In the case that the dependency is a materializer target, the dependency may result in zero or
 * more [ConfiguredTargetAndData]s per configuration.
 */
internal class DependencyProducer
    (
    parameters: PrerequisiteParameters,
    kind: DependencyKind?,
    toLabel: com.google.devtools.build.lib.cmdline.Label,
    propagatingAspects: com.google.common.collect.ImmutableList<Aspect?>?,
    sink: ResultSink,
    originatingMaterializerTarget: com.google.devtools.build.lib.cmdline.Label?,
    index: Int
) : StateMachine, com.google.devtools.build.lib.analysis.producers.TransitionApplier.ResultSink,
    com.google.devtools.build.lib.analysis.producers.PrerequisitesProducer.ResultSink {
    internal interface ResultSink : TransitionCollector {
        /**
         * Accepts dependency values for a given kind and label.
         * 
         * 
         * Multiple values may occur if there is a split transition.
         * 
         * 
         * For a skipped dependency, outputs an empty array. See comments in [ ][DependencyResolutionHelpers.getExecutionPlatformLabel] for when this happens.
         */
        fun acceptDependencyValues(index: Int, values: Array<ConfiguredTargetAndData>?)

        fun acceptMaterializerTarget(dependencyKind: DependencyKind?, target: ConfiguredTargetAndData?)

        fun acceptDependencyError(error: DependencyError?)

        fun acceptDependencyError(error: MissingEdgeError?)
    }

    // -------------------- Input --------------------
    private val parameters: PrerequisiteParameters
    private val kind: DependencyKind
    private val toLabel: com.google.devtools.build.lib.cmdline.Label
    private val propagatingAspects: com.google.common.collect.ImmutableList<Aspect?>?

    // -------------------- Output --------------------
    private val sink: ResultSink
    private val index: Int

    // -------------------- Internal State --------------------
    private var transitionedConfigurations: com.google.common.collect.ImmutableMap<String?, BuildConfigurationKey?>? =
        null
    private var prerequisiteValues: Array<ConfiguredTargetAndData>

    // The label of the materializer target this DependencyProducer is producing for, null otherwise.
    private val originatingMaterializerTarget: com.google.devtools.build.lib.cmdline.Label?

    init {
        this.parameters = parameters
        this.kind = com.google.common.base.Preconditions.checkNotNull<DependencyKind>(kind)
        this.toLabel = toLabel
        this.propagatingAspects = propagatingAspects
        this.sink = sink
        this.originatingMaterializerTarget = originatingMaterializerTarget
        this.index = index
    }

    override fun step(tasks: StateMachine.Tasks?): StateMachine {
        val attribute: com.google.devtools.build.lib.packages.Attribute? = kind.getAttribute()

        if (kind === VISIBILITY_DEPENDENCY
            || (attribute != null && attribute.getName() == "visibility")
        ) {
            // This is always a null transition because visibility targets are not configurable.
            return computePrerequisites(
                AttributeConfiguration.Companion.ofVisibility(),  /* executionPlatformLabel= */null
            )
        }
        if (kind === TRANSITIVE_VISIBILITY_DEPENDENCY) {
            return computePrerequisites(
                AttributeConfiguration.Companion.ofVisibility(),  /* executionPlatformLabel= */null
            )
        }

        // The logic of `DependencyResolutionHelpers.computeDependencyLabels` implies that
        // `parameters.configurationKey()` is non-null for everything that follows.
        val configurationKey: BuildConfigurationKey =
            com.google.common.base.Preconditions.checkNotNull<BuildConfigurationKey>(parameters.configurationKey())

        if (DependencyKind.isToolchain(kind)) {
            // There's no attribute so no attribute transition.

            // This dependency is a toolchain. Its package has not been loaded and therefore we can't
            // determine which aspects and which rule configuration transition we should use, so just
            // use sensible defaults. Not depending on their package makes the error message reporting
            // a missing toolchain a bit better.
            // TODO(lberki): This special-casing is weird. Find a better way to depend on toolchains.
            // This logic needs to stay in sync with the dep finding logic in
            // //third_party/bazel/src/main/java/com/google/devtools/build/lib/analysis/Util.java#findImplicitDeps.

            return computePrerequisites(
                AttributeConfiguration.Companion.ofUnary(configurationKey),
                parameters.getExecutionPlatformLabel(
                    (kind as ToolchainDependencyKind).getExecGroupName(),
                    DependencyKind.isBaseTargetToolchain(kind)
                )
            )
        }

        if (kind === OUTPUT_FILE_RULE_DEPENDENCY) {
            // There's no attribute so no attribute transition.
            return computePrerequisites(
                AttributeConfiguration.Companion.ofUnary(configurationKey),  /* executionPlatformLabel= */null
            )
        }

        val transitionData: AttributeTransitionData.Builder =
            AttributeTransitionData.builder()
                .attributes(parameters.attributeMap())
                .analysisData(parameters.starlarkTransitionProvider())
        val executionPlatformResult: ExecutionPlatformResult =
            getExecutionPlatformLabel(
                kind as AttributeDependencyKind,
                parameters.toolchainContexts(),
                parameters.baseTargetToolchainContexts(),
                parameters.aspects()
            )
        when (executionPlatformResult.kind()) {
            LABEL -> transitionData.executionPlatform(executionPlatformResult.label())
            NULL_LABEL -> transitionData.executionPlatform(null)
            SKIP -> {
                sink.acceptDependencyValues(index, EMPTY_OUTPUT)
                return StateMachine.DONE
            }

            ERROR -> {
                return ExecGroupErrorEmitter(executionPlatformResult.error())
            }
        }
        val attributeTransition: ConfigurationTransition?
        try {
            attributeTransition = attribute.getTransitionFactory().create(transitionData.build())
        } catch (e: TransitionCreationException) {
            sink.acceptDependencyError(DependencyError.Companion.of(e))
            return StateMachine.DONE
        }
        sink.acceptTransition(kind, toLabel, attributeTransition)
        return TransitionApplier(
            toLabel,
            configurationKey,
            attributeTransition,
            parameters.transitionCache(),
            this as com.google.devtools.build.lib.analysis.producers.TransitionApplier.ResultSink,
            parameters.eventHandler(),  /* runAfter= */
            StateMachine { tasks: StateMachine.Tasks? -> this.processTransitionResult(tasks) })
    }

    override fun acceptTransitionedConfigurations(
        transitionedConfigurations: com.google.common.collect.ImmutableMap<String?, BuildConfigurationKey?>?
    ) {
        this.transitionedConfigurations = transitionedConfigurations
    }

    override fun acceptTransitionError(e: TransitionException) {
        sink.acceptDependencyError(
            DependencyError.Companion.of(TransitionException(getMessageWithEdgeTransitionInfo(e), e))
        )
    }

    override fun acceptOptionsParsingError(e: com.google.devtools.common.options.OptionsParsingException) {
        sink.acceptDependencyError(
            DependencyError.Companion.of(
                com.google.devtools.common.options.OptionsParsingException(
                    getMessageWithEdgeTransitionInfo(e), e.getInvalidArgument(), e
                )
            )
        )
    }

    override fun acceptBuildOptionsScopeFunctionError(e: BuildOptionsScopeFunctionException?) {
        sink.acceptDependencyError(DependencyError.Companion.of(e))
    }

    override fun acceptPlatformMappingError(e: PlatformMappingException?) {
        sink.acceptDependencyError(DependencyError.Companion.of(e))
    }

    override fun acceptPlatformFlagsError(e: InvalidPlatformException?) {
        sink.acceptDependencyError(DependencyError.Companion.of(e))
    }

    private fun getMessageWithEdgeTransitionInfo(e: Throwable): String? {
        return java.lang.String.format(
            "on dependency edge %s (%s) -|%s|-> %s: %s",
            parameters.target().getLabel(),
            parameters.configurationKey().getOptions().shortId(),
            kind.getAttribute().getName(),
            toLabel,
            e.message
        )
    }

    private fun processTransitionResult(tasks: StateMachine.Tasks?): StateMachine {
        if (transitionedConfigurations == null) {
            return StateMachine.DONE // There was a previously reported error.
        }

        var isNonconfigurableTargetInSamePackage = false
        try {
            val toTarget: com.google.devtools.build.lib.packages.Target? =
                getTargetInSamePackageWithoutSkyframe(toLabel)
            if (toTarget != null) {
                isNonconfigurableTargetInSamePackage = !toTarget.isConfigurable()
            }
        } catch (e: NoSuchTargetException) {
            val parentTarget: com.google.devtools.build.lib.packages.Target = parameters.target()
            parameters
                .transitiveState()
                .addTransitiveCause(LoadingFailedCause(toLabel, e.getDetailedExitCode()))
            parameters
                .eventHandler()
                .handle(
                    com.google.devtools.build.lib.events.Event.error(
                        TargetUtils.getLocationMaybe(parentTarget),
                        TargetUtils.formatMissingEdge(parentTarget, toLabel, e, kind.getAttribute())
                    )
                )
        }

        if (isNonconfigurableTargetInSamePackage) {
            // The target is in the same package as the parent and non-configurable. In the general case
            // loading a child target would defeat Package-based sharding. However, when the target is in
            // the same Package, that concern no longer applies. This optimization means that delegation,
            // and the corresponding creation of additional Skyframe nodes, can be avoided in the very
            // common case of source file dependencies in the same Package.

            // Discards transition keys for patch transitions but keeps them otherwise.

            val transitionKeys: com.google.common.collect.ImmutableList<String?> =
                if (transitionedConfigurations.size == 1
                    && transitionedConfigurations.containsKey(PATCH_TRANSITION_KEY)
                )
                    com.google.common.collect.ImmutableList.of<String?>()
                else
                    transitionedConfigurations.keys.asList()
            return computePrerequisites(
                AttributeConfiguration.Companion.ofNullTransitionKeys(transitionKeys),  /* executionPlatformLabel= */
                null
            )
        }

        val parentChecksum: String = parameters.configurationKey().getOptionsChecksum()
        for (configuration in transitionedConfigurations.values) {
            val childChecksum: String = configuration.getOptionsChecksum()
            if (parentChecksum != childChecksum) {
                parameters
                    .eventHandler()
                    .post(ConfigurationTransitionEvent.create(parentChecksum, childChecksum))
            }
        }

        if (transitionedConfigurations.size == 1) {
            val patchedConfiguration: BuildConfigurationKey? =
                transitionedConfigurations.get(PATCH_TRANSITION_KEY)
            if (patchedConfiguration != null) {
                // It was a patch transition or no-op split transition.
                return computePrerequisites(
                    AttributeConfiguration.Companion.ofUnary(patchedConfiguration),  /* executionPlatformLabel= */
                    null
                )
            }
        }

        return computePrerequisites(
            AttributeConfiguration.Companion.ofSplit(transitionedConfigurations),  /* executionPlatformLabel= */
            null
        )
    }

    private fun computePrerequisites(
        configuration: AttributeConfiguration, executionPlatformLabel: com.google.devtools.build.lib.cmdline.Label?
    ): StateMachine {
        return PrerequisitesProducer(
            parameters,
            toLabel,
            executionPlatformLabel,
            configuration,
            propagatingAspects,
            this as com.google.devtools.build.lib.analysis.producers.PrerequisitesProducer.ResultSink,
            useBaseTargetPrerequisitesSupplier(),
            StateMachine { tasks: StateMachine.Tasks? -> this.evaluateMaterializerTargets(tasks) })
    }

    /**
     * Returns true only during aspects evaluation for attribute dependencies not owned by an aspect
     * to enable using the [BaseTargetPrerequisitesSupplier] to look up them.
     * 
     * 
     * Check [AspectFunction.baseTargetPrerequisitesSupplier] for more details.
     */
    private fun useBaseTargetPrerequisitesSupplier(): Boolean {
        if (parameters.aspects().isEmpty()) {
            return false
        }

        if (DependencyKind.isBaseTargetToolchain(kind)) {
            return true
        }

        if (DependencyKind.isAttribute(kind)) {
            if (kind.getOwningAspect() == null) {
                return true
            }
        }

        return false
    }

    override fun acceptPrerequisitesValue(value: Array<ConfiguredTargetAndData>) {
        this.prerequisiteValues = value
    }

    override fun acceptPrerequisitesError(error: NoSuchThingException?) {
        sink.acceptDependencyError(MissingEdgeError(kind, toLabel, error))
    }

    override fun acceptPrerequisitesError(error: InvalidVisibilityDependencyException?) {
        sink.acceptDependencyError(DependencyError.Companion.of(error))
    }

    override fun acceptPrerequisitesCreationError(error: ConfiguredValueCreationException) {
        // Cases where the child target cannot be loaded at all are propagated as
        // `NoSuchThingException`. In some cases, child target loading completes with errors. In that
        // case, the error is propagated as a `ConfiguredValueCreationException` with a
        // `LoadingFailedCause`. Requests parent-side context to be added to such errors by propagating
        // a `MissingEdgeError`.
        for (cause in error.getRootCauses().toList()) {
            if (cause is LoadingFailedCause) {
                if (cause.getLabel() == toLabel) {
                    sink.acceptDependencyError(
                        MissingEdgeError(
                            kind, toLabel, NoSuchTargetException.createForParentPropagation(toLabel)
                        )
                    )
                }
            }
        }
    }

    override fun acceptPrerequisitesAspectError(error: DependencyEvaluationException?) {
        sink.acceptDependencyError(DependencyError.Companion.of(error))
    }

    override fun acceptPrerequisitesAspectError(error: AspectCreationException?) {
        sink.acceptDependencyError(DependencyError.Companion.of(error))
    }

    private fun evaluateMaterializerTargets(tasks: StateMachine.Tasks): StateMachine {
        // If the target this DependencyProducer is producing dependencies for is an alias, then
        // do not expand materializer targets. Instead, keep the materializer target as-is and the
        // alias will pass the materializer target along to the target that depends on the alias, where
        // all this code will be run again and the materializer target will be expanded there. This is
        // extra important for materializer rules which return more than one dependency: alias()'s
        // 'actual' attribute is a single-label attribute, which means that normally it cannot contain
        // a materializer target that returns multiple dependencies. By deferring evaluation of
        // materializer targets, alias()s can point to any materializer target.

        if (parameters.target().getAssociatedRule() != null) {
            // Identifying a rule by its ConfiguredTargetFactory is a bit hacky, but the alternative
            // is adding an "isAlias" boolean to RuleClass which would require serializing more data for
            // one corner case.
            if (parameters.target().getAssociatedRule().getRuleClassObject().getConfiguredTargetFactory()
                        is Alias
            ) {
                return StateMachine { tasks: StateMachine.Tasks? -> this.emitResults(tasks) }
            }
        }

        val attribute: com.google.devtools.build.lib.packages.Attribute? = kind.getAttribute()

        val materializerRuleDependencySink =
            MaterializerRuleDependencySink()

        var materializedTargetsCount = 0
        val erroringMaterializerTargetsUnderAnalysisTest: MutableList<com.google.devtools.build.lib.util.Pair<ConfiguredTargetAndData?, Int?>> =
            java.util.ArrayList<com.google.devtools.build.lib.util.Pair<ConfiguredTargetAndData?, Int?>>()

        // There will be one ConfiguredTargetAndData in prerequisiteValues for each configuration this
        // label is being evaluated under.
        for (dep in prerequisiteValues) {
            // Skip non-materializer rules.
            // Footnote: Iff the first dependency is a materializer rule, then they all should be, since
            // this loop is iterating over the same target under different configurations.

            if (!dep.isMaterializerRule()) {
                materializedTargetsCount++
                continue
            }

            if (originatingMaterializerTarget != null) {
                sink.acceptDependencyError(
                    DependencyError.Companion.of(
                        MaterializerException.Companion.materializerRuleException(
                            attribute,
                            parameters.label(),
                            String.format(
                                "Materializer target %s depends on another materializer target"
                                        + " %s, which is not supported.",
                                originatingMaterializerTarget, dep.getTargetLabel()
                            ),
                            null
                        )
                    )
                )
                return StateMachine.DONE
            }

            // Check that this materializer is in a label_list attribute. Since materializers can return
            // a variable number of targets, they cannot go into single-label-typed attributes.
            if (attribute != null && dep.isMaterializerRule()
                && attribute.getType() !== BuildType.LABEL_LIST
            ) {
                sink.acceptDependencyError(
                    DependencyError.Companion.of(
                        MaterializerException.Companion.materializerRuleException(
                            attribute,
                            parameters.label(),
                            String.format(
                                "Target %s is a materializer target but attribute '%s' is a %s, not a"
                                        + " label list",
                                dep.getTargetLabel(), attribute.getName(), attribute.getType()
                            ),
                            null
                        )
                    )
                )
                return StateMachine.DONE
            }

            sink.acceptMaterializerTarget(kind, dep)

            val materializedDepsInfo: MaterializedDepsInfo? =
                dep.getConfiguredTarget().get(MaterializedDepsInfo.PROVIDER)
            if (materializedDepsInfo != null) {
                for (dependency in materializedDepsInfo.getDeps()) {
                    // In the case that the dep is a ConfiguredTarget, things are somewhat circuitous because
                    // this very ConfiguredTarget object is already the ConfiguredTarget that is needed. What
                    // is actually needed though is the corresponding ConfiguredTargetAndData object. The
                    // ConfiguredTargetAndData object is also already available in the RuleContext when the
                    // MaterializedDepsInfo was created, but there is no easy way to get it from the
                    // RuleContext, through the provider, then to here. So just use the label to ask
                    // DependencyProducer to get the ConfiguredTargetAndData like everything else.

                    val label: com.google.devtools.build.lib.cmdline.Label =
                        dependency.map<com.google.devtools.build.lib.cmdline.Label>(
                            ConfiguredTarget::getLabel,
                            DormantDependency::getLabel
                        )

                    // The task will not start until this step is complete, so it is safe to calculate
                    // indices here and reallocate the results array after (instead of having to
                    // precalculate everything, then reallocate, then calculate and enqueue the dependency
                    // producer tasks)
                    tasks.enqueue(
                        DependencyProducer(
                            parameters,
                            kind,
                            label,
                            propagatingAspects,
                            materializerRuleDependencySink,
                            dep.getTargetLabel(),
                            materializedTargetsCount
                        )
                    )

                    // Using result.length is a bit of a hack. It's not easy to get the number of
                    // configurations, and hence how many results DependencyProducer will return, in this
                    // part of the code. However the number of results returned from DependencyProducer
                    // in the first round in attributeResolutionStep matches the number of configurations.
                    materializedTargetsCount += prerequisiteValues.size
                }
            } else {
                // StarlarkRuleConfiguredTargetUtil checks that MaterializedDepsInfo provider exists, but if
                // the materializer target is being tested under an analysis test that expects failure, then
                // there might be a call to fail() before the MaterializedDepsInfo provider is returned, but
                // analysis will continue. So allow there to be no MaterializedDepsInfo from a materializer
                // target only if analysis failure is allowed. Futhermore, the failing materializer target
                // must not disappear as it usually would so that the test can observe the
                // AnalysisFailureInfo provider.
                com.google.common.base.Preconditions.checkState(dep.getConfiguration().allowAnalysisFailures())
                com.google.common.base.Preconditions.checkNotNull<T?>(
                    dep.getConfiguredTarget().get(AnalysisFailureInfo.STARLARK_CONSTRUCTOR)
                )
                erroringMaterializerTargetsUnderAnalysisTest.add(
                    com.google.devtools.build.lib.util.Pair<ConfiguredTargetAndData?, Int?>(
                        dep,
                        materializedTargetsCount
                    )
                )
                // The materializer target is in error, so it will not be expanded, so only add 1 for itself
                materializedTargetsCount++
            }
        }

        // Note that if a materializer returns no deps, then this will clear the array, which
        // is important because the materializer target itself needs to be cleared out.
        if (materializedTargetsCount != prerequisiteValues.size) {
            // Throw away the materializer target(s) and make room for the materialized target(s).
            prerequisiteValues = arrayOfNulls<ConfiguredTargetAndData>(materializedTargetsCount)

            // But preserve any materializer target that was in error under analysis testing.
            for (pair in erroringMaterializerTargetsUnderAnalysisTest) {
                prerequisiteValues[pair.getSecond()] = pair.getFirst()
            }
        }

        return StateMachine { tasks: StateMachine.Tasks? -> this.emitResults(tasks) }
    }

    private inner class MaterializerRuleDependencySink : ResultSink {
        public override fun acceptTransition(
            kind: DependencyKind?,
            label: com.google.devtools.build.lib.cmdline.Label?,
            transition: ConfigurationTransition?
        ) {
            sink.acceptTransition(kind, label, transition)
        }

        override fun acceptDependencyValues(index: Int, values: Array<ConfiguredTargetAndData?>) {
            java.lang.System.arraycopy(values, 0, prerequisiteValues, index, values.size)
        }

        override fun acceptMaterializerTarget(
            dependencyKind: DependencyKind?, target: ConfiguredTargetAndData?
        ) {
            sink.acceptMaterializerTarget(dependencyKind, target)
        }

        override fun acceptDependencyError(error: MissingEdgeError?) {
            sink.acceptDependencyError(error)
        }

        override fun acceptDependencyError(error: DependencyError?) {
            sink.acceptDependencyError(error)
        }
    }

    private fun emitResults(tasks: StateMachine.Tasks?): StateMachine {
        sink.acceptDependencyValues(index, prerequisiteValues)
        return StateMachine.DONE
    }

    /**
     * Attempts to resolve a label to a target in the same package as the parent target without doing
     * a skyframe call. Returns the target if it can be resolved, and null otherwise.
     * 
     * 
     * In particular, this method always returns null if `label` points to a different
     * package.
     * 
     * 
     * If the parent target is owned by a [PackagePiece], this method will look for `label` in that package piece only, and cannot examine other package pieces.
     * 
     * @throws NoSuchTargetException if it can be determined without a skyframe call that `label` is not a valid target.
     */
    @Throws(NoSuchTargetException::class)
    private fun getTargetInSamePackageWithoutSkyframe(label: com.google.devtools.build.lib.cmdline.Label): com.google.devtools.build.lib.packages.Target? {
        val parentTarget: com.google.devtools.build.lib.packages.Target = parameters.target()
        if (parentTarget.getLabel().getPackageIdentifier() == label.getPackageIdentifier()) {
            val parentPackageoid: Packageoid? = parentTarget.getPackageoid()
            if (parentPackageoid is com.google.devtools.build.lib.packages.Package) {
                // Throws NoSuchTargetException if label is not found; since parentPkg is a full Package,
                // this guarantees that label is not a valid target.
                return parentPackageoid.getTarget(label.getName())
            } else if (parentPackageoid is PackagePiece) {
                // NoSuchTargetException could indicate that label is owned by a different package piece,
                // and we would need a skyframe call to resolve.
                try {
                    return parentPackageoid.getTarget(label.getName())
                } catch (e: NoSuchTargetException) {
                    return null
                }
            }
        }
        return null
    }

    /**
     * Emits errors from [ExecutionPlatformResult.error].
     * 
     * 
     * Exists to fetch the [BuildConfigurationValue], needed to construct [ ].
     */
    private inner class ExecGroupErrorEmitter(// -------------------- Input --------------------
        private val message: String?
    ) : StateMachine, java.util.function.Consumer<SkyValue?> {
        // -------------------- Internal State --------------------
        private var configuration: BuildConfigurationValue? = null

        override fun step(tasks: StateMachine.Tasks): StateMachine {
            // The configuration value should already exist as a dependency so this lookup is safe enough
            // for error handling.
            tasks.lookUp(parameters.configurationKey(), this as java.util.function.Consumer<SkyValue?>)
            return StateMachine { tasks: StateMachine.Tasks? -> this.postEvent(tasks) }
        }

        override fun accept(value: SkyValue?) {
            this.configuration = value as BuildConfigurationValue?
        }

        fun postEvent(tasks: StateMachine.Tasks?): StateMachine {
            parameters
                .eventHandler()
                .post(AnalysisRootCauseEvent.withConfigurationValue(configuration, toLabel, message))
            sink.acceptDependencyError(
                DependencyError.Companion.of(
                    DependencyEvaluationException(
                        ConfiguredValueCreationException(
                            parameters.location(),
                            message,
                            parameters.label(),
                            parameters.eventId(),  /* rootCauses= */
                            null,  /* detailedExitCode= */
                            null
                        ),  // This error originates in dependency resolution, attached to the current target,
                        // so no dependency has reported the error.
                        /* depReportedOwnError= */
                        false
                    )
                )
            )
            return StateMachine.DONE
        }
    }

    companion object {
        private val EMPTY_OUTPUT: Array<ConfiguredTargetAndData> = arrayOfNulls<ConfiguredTargetAndData>(0)
    }
}
