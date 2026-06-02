// Copyright 2021 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.skyframe

import com.google.devtools.build.lib.actions.ActionAnalysisMetadata

/**
 * Drives the analysis & execution of an ActionLookupKey, which is wrapped inside a BuildDriverKey.
 */
class BuildDriverFunction(
    incrementalArtifactConflictFinder: java.util.function.Supplier<IncrementalArtifactConflictFinder?>,
    ruleContextConstraintSemantics: java.util.function.Supplier<RuleContextConstraintSemantics?>,
    extraActionFilterSupplier: java.util.function.Supplier<com.google.devtools.build.lib.util.RegexFilter?>,
    testTypeResolver: java.util.function.Supplier<TestTypeResolver?>,
    additionalPostAnalysisDepsRequestedAndAvailable: AdditionalPostAnalysisDepsRequestedAndAvailable
) : SkyFunction {
    private val incrementalArtifactConflictFinder: java.util.function.Supplier<IncrementalArtifactConflictFinder?>
    private val ruleContextConstraintSemantics: java.util.function.Supplier<RuleContextConstraintSemantics?>
    private val extraActionFilterSupplier: java.util.function.Supplier<com.google.devtools.build.lib.util.RegexFilter?>
    private val testTypeResolver: java.util.function.Supplier<TestTypeResolver?>
    val additionalPostAnalysisDepsRequestedAndAvailable: AdditionalPostAnalysisDepsRequestedAndAvailable


    private var shouldCheckForConflictWithTraversal: java.util.function.Supplier<Boolean?>? = null

    // A set of BuildDriverKeys that have been checked for conflicts.
    // This gets cleared after each build.
    // We can't use SkyKeyComputeState here since it doesn't guarantee that the same state for
    // a previously requested SkyKey is retrieved. This could cause a correctness issue:
    // - we clear the conflict checking states and shut down the Executors after all the analysis
    //   work is done in the build
    // - If the SkyKeyComputeState for this BuildDriverKey was cleared, an evaluation of this key
    //   would attempt again to check for conflicts => we redo the work, or a race condition with the
    //   shutting down of the Executors could lead to a RejectedExecutionException.
    private var checkedForConflicts: MutableSet<BuildDriverKey?> =
        com.google.common.collect.Sets.newConcurrentHashSet<BuildDriverKey?>()

    // Events coming from Skyframe may contain duplicates (because of resets). It would be better to
    // de-duplicate at the source to avoid repeated work by each subscriber.
    //
    // Each top level key has at most 1 effective status event, e.g. a top level target can't be
    // analyzed twice in a build. Therefore, to keep track of the posted events, we only need to keep
    // the sent event types instead of the events themselves.
    //
    // We didn't use SkyKeyComputeState since it should only be used as a performance optimization,
    // whereas in this situation the state determines the behavior of the SkyFunction.
    private var keyToPostedEvents: MutableMap<BuildDriverKey?, MutableSet<com.google.devtools.build.lib.skyframe.TopLevelStatusEvents.Type?>> =
        com.google.common.collect.Maps.newConcurrentMap<BuildDriverKey?, MutableSet<com.google.devtools.build.lib.skyframe.TopLevelStatusEvents.Type?>?>()

    init {
        this.incrementalArtifactConflictFinder = incrementalArtifactConflictFinder
        this.ruleContextConstraintSemantics = ruleContextConstraintSemantics
        this.extraActionFilterSupplier = extraActionFilterSupplier
        this.testTypeResolver = testTypeResolver
        this.additionalPostAnalysisDepsRequestedAndAvailable =
            additionalPostAnalysisDepsRequestedAndAvailable
    }

    private class State : SkyKeyComputeState {
        // It's only necessary to do this check once.
        private var checkedForCompatibility = false
        private var checkedForPlatformCompatibility = false

        private var testType: com.google.devtools.build.lib.skyframe.BuildDriverKey.TestType? = null
    }

    fun setShouldCheckForConflictWithTraversal(
        shouldCheckForConflictWithTraversal: java.util.function.Supplier<Boolean?>?
    ) {
        this.shouldCheckForConflictWithTraversal = shouldCheckForConflictWithTraversal
    }

    /**
     * From the ConfiguredTarget/Aspect keys, get the top-level artifacts. Then evaluate them together
     * with the appropriate CompletionFunctions. This is the bridge between the conceptual analysis &
     * execution phases.
     */
    @Throws(SkyFunctionException::class, java.lang.InterruptedException::class)
    override fun compute(skyKey: SkyKey?, env: SkyFunction.Environment): SkyValue? {
        val buildDriverKey: BuildDriverKey = skyKey as BuildDriverKey
        val actionLookupKey: ActionLookupKey = buildDriverKey.getActionLookupKey()
        val topLevelArtifactContext: TopLevelArtifactContext? = buildDriverKey.getTopLevelArtifactContext()
        val state: State =
            env.getState<State>(java.util.function.Supplier { com.google.devtools.build.lib.skyframe.BuildDriverFunction.State() })

        // Register a dependency on the BUILD_ID. We do this to make sure BuildDriverFunction is
        // reevaluated every build.
        PrecomputedValue.BUILD_ID.get(env)

        val postedEventsTypes: MutableSet<com.google.devtools.build.lib.skyframe.TopLevelStatusEvents.Type?> =
            keyToPostedEvents.computeIfAbsent(
                buildDriverKey,
                java.util.function.Function { unused: BuildDriverKey? -> HashSet<com.google.devtools.build.lib.skyframe.TopLevelStatusEvents.Type?>() })
        // Why SkyValue and not ActionLookupValue? The evaluation of some ActionLookupKey can result in
        // classes that don't implement ActionLookupValue
        // (e.g. ConfiguredTargetKey -> NonRuleConfiguredTargetValue).
        val topLevelSkyValue: SkyValue?
        try {
            topLevelSkyValue = env.getValueOrThrow<E?>(actionLookupKey, AbstractSaneAnalysisException::class.java)
        } catch (e: AbstractSaneAnalysisException) {
            signalAnalysisConclusionIfKeepGoing(
                env, buildDriverKey, postedEventsTypes,  /* success= */false
            )
            throw BuildDriverFunctionException.Companion.ofConfiguredTargetOrAspectEval(e)
        }

        if (env.valuesMissing()) {
            return null
        }

        // At this point, the target is considered "analyzed". It's important that this event is sent
        // before the TopLevelEntityAnalysisConcludedEvent: when the last of the analysis work is
        // concluded, we need to have the complete list of analyzed targets ready in
        // BuildResultListener.
        if (topLevelSkyValue is ConfiguredTargetValue) {
            announceTopLevelConfiguredTargetAnalyzed(
                env, topLevelSkyValue as ConfiguredTargetValue?, postedEventsTypes
            )
        } else {
            announceTopLevelAspectAnalyzed(
                env, topLevelSkyValue as TopLevelAspectsValue?, postedEventsTypes
            )
        }

        // We only check for action conflict once per BuildDriverKey.
        if (com.google.common.base.Preconditions.checkNotNull<java.util.function.Supplier<Boolean?>?>(
                shouldCheckForConflictWithTraversal
            ).get()
            && checkedForConflicts.add(buildDriverKey)
        ) {
            Profiler.instance().profile("BuildDriverFunction.checkActionConflicts").use { c ->
                val actionConflicts: com.google.common.collect.ImmutableMap<ActionAnalysisMetadata?, ActionConflictException?> =
                    checkActionConflicts(actionLookupKey)
                if (!actionConflicts.isEmpty()) {
                    // The analysis technically succeeded, even though the target/aspect can't be executed.
                    signalAnalysisConclusionIfKeepGoing(
                        env, buildDriverKey, postedEventsTypes,  /* success= */true
                    )
                    throw BuildDriverFunctionException(
                        TopLevelConflictException(
                            "Action conflict(s) detected while analyzing top-level target "
                                    + actionLookupKey.getLabel(),
                            actionConflicts
                        )
                    )
                }
            }
        }

        com.google.common.base.Preconditions.checkState(
            topLevelSkyValue is ConfiguredTargetValue
                    || topLevelSkyValue is TopLevelAspectsValue
        )
        if (state.testType == null) {
            if (topLevelSkyValue is ConfiguredTargetValue) {
                state.testType =
                    testTypeResolver
                        .get()
                        .determineTestType(
                            (topLevelSkyValue as ConfiguredTargetValue).getConfiguredTarget()
                        )
            } else {
                state.testType = com.google.devtools.build.lib.skyframe.BuildDriverKey.TestType.NOT_TEST
            }
        }

        if (topLevelSkyValue is ConfiguredTargetValue) {
            val configuredTarget: ConfiguredTarget = topLevelSkyValue.getConfiguredTarget()
            // It's possible that this code path is triggered AFTER the analysis cache clean up and the
            // transitive packages for package root resolution is already cleared. In such a case, the
            // symlinks should have already been planted.
            val transitivePackagesForSymlinkPlanting: NestedSet<Package.Metadata?>? =
                topLevelSkyValue.getTransitivePackages()
            if (transitivePackagesForSymlinkPlanting != null) {
                postEventIfNecessary(
                    postedEventsTypes,
                    env,
                    TopLevelTargetReadyForSymlinkPlanting.create(transitivePackagesForSymlinkPlanting)
                )
            }

            val buildConfigurationValue: BuildConfigurationValue? =
                if (configuredTarget.getConfigurationKey() == null)
                    null
                else
                    env.getValue(configuredTarget.getConfigurationKey()) as BuildConfigurationValue?
            if (env.valuesMissing()) {
                return null
            }

            if (!state.checkedForCompatibility) {
                try {
                    val isConfiguredTargetCompatible =
                        isConfiguredTargetCompatible(
                            env,
                            state,
                            configuredTarget,
                            buildConfigurationValue,
                            buildDriverKey.isExplicitlyRequested(),
                            buildDriverKey.shouldSkipIncompatibleExplicitTargets()
                        )
                    if (isConfiguredTargetCompatible == null) {
                        return null
                    }

                    state.checkedForCompatibility = true
                    if (!isConfiguredTargetCompatible) {
                        postEventIfNecessary(
                            postedEventsTypes, env, TopLevelTargetSkippedEvent.create(configuredTarget)
                        )
                        // We still record analyzed but skipped tests, as this information is needed for the
                        // result summary.
                        if (isTest(state.testType)) {
                            postEventIfNecessary(
                                postedEventsTypes,
                                env,
                                TestAnalyzedEvent.create(
                                    configuredTarget,
                                    com.google.common.base.Preconditions.checkNotNull<BuildConfigurationValue?>(
                                        buildConfigurationValue
                                    ),  /* isSkipped= */
                                    true
                                )
                            )
                        }
                        // Only send the event now to include the compatibility check in the measurement for
                        // time spent on analysis work.
                        postEventIfNecessary(
                            postedEventsTypes,
                            env,
                            TopLevelEntityAnalysisConcludedEvent.create(buildDriverKey,  /* succeeded= */true)
                        )
                        // We consider the evaluation of this BuildDriverKey successful at this point, even when
                        // the target is skipped.
                        removeStatesForKey(buildDriverKey)
                        return BuildDriverValue(topLevelSkyValue,  /* skipped= */true)
                    }
                } catch (e: TargetCompatibilityCheckException) {
                    // The analysis of the target technically succeeded, just that it was incompatible and
                    // can't be executed.
                    signalAnalysisConclusionIfKeepGoing(
                        env, buildDriverKey, postedEventsTypes,  /* success= */true
                    )
                    throw BuildDriverFunctionException(e)
                }
            }

            if (!additionalPostAnalysisDepsRequestedAndAvailable.request(env, actionLookupKey)) {
                return null
            }

            postEventIfNecessary(
                postedEventsTypes,
                env,
                TopLevelEntityAnalysisConcludedEvent.create(buildDriverKey,  /* succeeded= */true)
            )
            postEventIfNecessary(
                postedEventsTypes,
                env,
                TopLevelTargetPendingExecutionEvent.create(configuredTarget, isTest(state.testType))
            )
            requestConfiguredTargetExecution(
                configuredTarget,
                buildDriverKey,
                buildConfigurationValue,
                env,
                topLevelArtifactContext,
                postedEventsTypes,
                state.testType
            )
        } else {
            val artifactsToBuild: com.google.common.collect.ImmutableSet.Builder<Artifact?> =
                com.google.common.collect.ImmutableSet.builder<Artifact?>()
            val aspectCompletionKeys: MutableList<SkyKey?> = java.util.ArrayList<SkyKey?>()

            // Do not trigger Skyframe restarts in this loop (see comments below).
            for (entry in (topLevelSkyValue as TopLevelAspectsValue).getTopLevelAspectsMap().entrySet()) {
                val aspectKey: AspectKey? = entry.getKey()
                val aspectValue: AspectValue = entry.getValue()
                addExtraActionsIfRequested(
                    aspectValue.getProvider(ExtraActionArtifactsProvider::class.java),
                    artifactsToBuild,
                    buildDriverKey.isExtraActionTopLevelOnly()
                )

                // It's possible that this code path is triggered AFTER the analysis cache clean up and the
                // transitive packages for package root resolution is already cleared. In such a case, the
                // symlinks should have already been planted.
                val transitivePackagesForSymlinkPlanting: NestedSet<Package.Metadata?>? =
                    aspectValue.getTransitivePackages()
                if (transitivePackagesForSymlinkPlanting != null) {
                    // This event should be sent out exactly once per aspect in this BuildDriverKey, even with
                    // resets. We achieve this by marking the event type as sent only after sending the event
                    // for all aspects, but must avoid triggering Skyframe restarts while doing so.
                    if (!postedEventsTypes.contains(
                            TopLevelStatusEvents.Type.TOP_LEVEL_TARGET_READY_FOR_SYMLINK_PLANTING
                        )
                    ) {
                        env.getListener()
                            .post(
                                TopLevelTargetReadyForSymlinkPlanting.create(
                                    transitivePackagesForSymlinkPlanting
                                )
                            )
                    }
                }
                aspectCompletionKeys.add(AspectCompletionKey.Companion.create(aspectKey, topLevelArtifactContext))
            }
            postedEventsTypes.add(TopLevelStatusEvents.Type.TOP_LEVEL_TARGET_READY_FOR_SYMLINK_PLANTING)

            if (!additionalPostAnalysisDepsRequestedAndAvailable.request(env, actionLookupKey)) {
                return null
            }

            // Send the AspectAnalyzedEvents first to make sure the BuildResultListener is up-to-date
            // before signaling that the analysis of this top level aspect has concluded.
            postEventIfNecessary(
                postedEventsTypes,
                env,
                TopLevelEntityAnalysisConcludedEvent.create(buildDriverKey,  /* succeeded= */true)
            )

            postEventIfNecessary(postedEventsTypes, env, SomeExecutionStartedEvent.create())
            // Request the execution of the collected aspects.
            declareDependenciesAndCheckValues(
                env,
                com.google.common.collect.Iterables.concat(
                    Artifact.keys(artifactsToBuild.build()),
                    aspectCompletionKeys
                )
            )
        }

        if (env.valuesMissing()) {
            return null
        }

        // If we get to this point, the execution of this target/aspect succeeded.
        if (state.testType == com.google.devtools.build.lib.skyframe.BuildDriverKey.TestType.EXCLUSIVE || state.testType == com.google.devtools.build.lib.skyframe.BuildDriverKey.TestType.EXCLUSIVE_IF_LOCAL) {
            com.google.common.base.Preconditions.checkState(topLevelSkyValue is ConfiguredTargetValue)
            removeStatesForKey(buildDriverKey)
            return ExclusiveTestBuildDriverValue(
                topLevelSkyValue, (topLevelSkyValue as ConfiguredTargetValue).getConfiguredTarget()
            )
        }

        removeStatesForKey(buildDriverKey)
        return BuildDriverValue(topLevelSkyValue,  /* skipped= */false)
    }

    fun resetStates() {
        checkedForConflicts = com.google.common.collect.Sets.newConcurrentHashSet<BuildDriverKey?>()
        keyToPostedEvents =
            com.google.common.collect.Maps.newConcurrentMap<BuildDriverKey?, MutableSet<com.google.devtools.build.lib.skyframe.TopLevelStatusEvents.Type?>?>()
    }

    private fun removeStatesForKey(key: BuildDriverKey?) {
        checkedForConflicts.remove(key)
        keyToPostedEvents.remove(key)
    }

    /**
     * Checks if a ConfiguredTarget is compatible with the platform/environment. See [ ].
     * 
     * @return null if a value is missing in the environment.
     */
    @Throws(java.lang.InterruptedException::class, TargetCompatibilityCheckException::class)
    private fun isConfiguredTargetCompatible(
        env: SkyFunction.Environment,
        state: State,
        configuredTarget: ConfiguredTarget?,
        buildConfigurationValue: BuildConfigurationValue?,
        isExplicitlyRequested: Boolean,
        skipIncompatibleExplicitTargets: Boolean
    ): Boolean? {
        if (!state.checkedForPlatformCompatibility) {
            val platformCompatibility: PlatformCompatibility =
                TopLevelConstraintSemantics.compatibilityWithPlatformRestrictions(
                    configuredTarget,
                    env.getListener(),  /* eagerlyThrowError= */
                    true,
                    isExplicitlyRequested,
                    skipIncompatibleExplicitTargets
                )
            state.checkedForPlatformCompatibility = true
            when (platformCompatibility) {
                INCOMPATIBLE_EXPLICIT, INCOMPATIBLE_IMPLICIT -> return false
                COMPATIBLE -> {}
            }
        }

        val environmentCompatibility: EnvironmentCompatibility? =
            TopLevelConstraintSemantics.compatibilityWithTargetEnvironment(
                configuredTarget,
                buildConfigurationValue,
                { label -> getTarget(env, label) },
                env.getListener()
            )
        if (env.valuesMissing() || environmentCompatibility == null) {
            return null
        }
        if (environmentCompatibility.isCompatible) {
            return true
        }
        if (environmentCompatibility.severeMissingEnvironments() == null) {
            return false
        }
        val badTargetsUserMessage: String? =
            TopLevelConstraintSemantics.getErrorMessageForTarget(
                ruleContextConstraintSemantics.get(),
                configuredTarget,
                environmentCompatibility.severeMissingEnvironments()
            )
        throw TargetCompatibilityCheckException(
            badTargetsUserMessage,
            FailureDetail.newBuilder()
                .setMessage(badTargetsUserMessage)
                .setAnalysis(Analysis.newBuilder().setCode(Code.TARGETS_MISSING_ENVIRONMENTS))
                .build()
        )
    }

    @Throws(java.lang.InterruptedException::class)
    private fun requestConfiguredTargetExecution(
        configuredTarget: ConfiguredTarget,
        buildDriverKey: BuildDriverKey,
        buildConfigurationValue: BuildConfigurationValue?,
        env: SkyFunction.Environment,
        topLevelArtifactContext: TopLevelArtifactContext?,
        postedEventsTypes: MutableSet<com.google.devtools.build.lib.skyframe.TopLevelStatusEvents.Type?>,
        testType: com.google.devtools.build.lib.skyframe.BuildDriverKey.TestType
    ) {
        val artifactsToBuild: com.google.common.collect.ImmutableSet.Builder<Artifact?> =
            com.google.common.collect.ImmutableSet.builder<Artifact?>()
        addExtraActionsIfRequested(
            configuredTarget.getProvider(ExtraActionArtifactsProvider::class.java),
            artifactsToBuild,
            buildDriverKey.isExtraActionTopLevelOnly()
        )
        val keysToRequest: com.google.common.collect.ImmutableSet.Builder<SkyKey?> =
            com.google.common.collect.ImmutableSet.builder<SkyKey?>().addAll(Artifact.keys(artifactsToBuild.build()))
        postEventIfNecessary(postedEventsTypes, env, SomeExecutionStartedEvent.create())
        if (testType == com.google.devtools.build.lib.skyframe.BuildDriverKey.TestType.NOT_TEST) {
            keysToRequest.add(
                TargetCompletionValue.key(
                    ConfiguredTargetKey.Companion.fromConfiguredTarget(configuredTarget),
                    topLevelArtifactContext,  /* willTest= */
                    false
                )
            )
            declareDependenciesAndCheckValues(env, keysToRequest.build())
            return
        }

        postEventIfNecessary(
            postedEventsTypes,
            env,
            TestAnalyzedEvent.create(
                configuredTarget,
                com.google.common.base.Preconditions.checkNotNull<BuildConfigurationValue?>(buildConfigurationValue),  /* isSkipped= */
                false
            )
        )

        if (testType == com.google.devtools.build.lib.skyframe.BuildDriverKey.TestType.PARALLEL) {
            // Only run non-exclusive tests here. Exclusive tests need to be run sequentially later.
            keysToRequest.add(
                TestCompletionValue.key(
                    ConfiguredTargetKey.Companion.fromConfiguredTarget(configuredTarget),
                    topLevelArtifactContext,  /* exclusiveTesting= */
                    false
                )
            )
            declareDependenciesAndCheckValues(env, keysToRequest.build())
            return
        }

        // Exclusive tests will be run with sequential Skyframe evaluations afterwards.
        keysToRequest.add(
            TargetCompletionValue.key(
                ConfiguredTargetKey.Companion.fromConfiguredTarget(configuredTarget),
                topLevelArtifactContext,  /* willTest= */
                true
            )
        )
        declareDependenciesAndCheckValues(env, keysToRequest.build())
    }

    @com.google.common.annotations.VisibleForTesting
    @Throws(java.lang.InterruptedException::class)
    fun checkActionConflicts(
        actionLookupKey: ActionLookupKey?
    ): com.google.common.collect.ImmutableMap<ActionAnalysisMetadata?, ActionConflictException?> {
        val localRef: IncrementalArtifactConflictFinder? = incrementalArtifactConflictFinder.get()
        // a null value means that the conflict checker is shut down.
        if (localRef == null) {
            return com.google.common.collect.ImmutableMap.of<ActionAnalysisMetadata?, ActionConflictException?>()
        }
        return localRef.findArtifactConflicts(actionLookupKey).conflicts
    }

    private fun addExtraActionsIfRequested(
        provider: ExtraActionArtifactsProvider?,
        artifactsToBuild: com.google.common.collect.ImmutableSet.Builder<Artifact?>,
        extraActionTopLevelOnly: Boolean
    ) {
        if (provider != null) {
            addArtifactsToBuilder(
                if (extraActionTopLevelOnly)
                    provider.getExtraActionArtifacts().toList()
                else
                    provider.getTransitiveExtraActionArtifacts().toList(),
                artifactsToBuild,
                extraActionFilterSupplier.get()
            )
        }
    }

    /** A SkyFunctionException wrapper for the actual TopLevelConflictException.  */
    private class BuildDriverFunctionException : SkyFunctionException {
        private constructor(cause: java.lang.Exception?, transience: Transience?) : super(cause, transience)

        // The exception is transient here since it could be caused by external factors (conflict with
        // another target).
        internal constructor(cause: TopLevelConflictException?) : super(cause, Transience.TRANSIENT)

        internal constructor(cause: TargetCompatibilityCheckException?) : super(cause, Transience.TRANSIENT)

        companion object {
            fun ofConfiguredTargetOrAspectEval(
                cause: AbstractSaneAnalysisException?
            ): BuildDriverFunctionException {
                return BuildDriverFunctionException(cause, Transience.PERSISTENT)
            }
        }
    }

    /** Helper to resolve the test type.  */
    interface TestTypeResolver {
        /** Determines the appropriate test type given a ConfiguredTarget.  */
        fun determineTestType(target: ConfiguredTarget?): com.google.devtools.build.lib.skyframe.BuildDriverKey.TestType?
    }

    /** Helper to request additional post analysis deps, if required.  */
    fun interface AdditionalPostAnalysisDepsRequestedAndAvailable {
        /** Returns whether the deps are requested and available.  */
        @Throws(java.lang.InterruptedException::class)
        fun request(env: SkyFunction.Environment?, key: ActionLookupKey?): Boolean

        companion object {
            @kotlin.jvm.JvmField
            val NO_OP: AdditionalPostAnalysisDepsRequestedAndAvailable =
                AdditionalPostAnalysisDepsRequestedAndAvailable { env: SkyFunction.Environment?, key: ActionLookupKey? -> true }
        }
    }

    /** Contains the results of collecting ALVs.  */
    @AutoValue
    abstract class ActionLookupValuesCollectionResult {
        abstract fun collectedValues(): com.google.common.collect.ImmutableCollection<SkyValue?>?

        companion object {
            fun create(
                collectedValues: com.google.common.collect.ImmutableCollection<SkyValue?>?
            ): ActionLookupValuesCollectionResult {
                return AutoValue_BuildDriverFunction_ActionLookupValuesCollectionResult(collectedValues)
            }
        }
    }

    companion object {
        /**
         * Sends out a signal that no more analysis work will be done on this top level target/aspect.
         * 
         * 
         * Only do so in --keep_going mode. This is consistent with the legacy behavior where the
         * analysis phase isn't considered "finished" if there's an error in --nokeep_going mode.
         */
        private fun signalAnalysisConclusionIfKeepGoing(
            env: SkyFunction.Environment,
            buildDriverKey: BuildDriverKey,
            postedEventsTypes: MutableSet<com.google.devtools.build.lib.skyframe.TopLevelStatusEvents.Type?>,
            success: Boolean
        ) {
            if (buildDriverKey.keepGoing()) {
                postEventIfNecessary(
                    postedEventsTypes,
                    env,
                    TopLevelEntityAnalysisConcludedEvent.create(buildDriverKey, success)
                )
            }
        }

        /**
         * [TopLevelTargetAnalyzedEvent]s should be sent out before conflict checking to be
         * consistent with the non-skymeld code path.
         */
        @Throws(java.lang.InterruptedException::class)
        private fun announceTopLevelConfiguredTargetAnalyzed(
            env: SkyFunction.Environment,
            configuredTargetValue: ConfiguredTargetValue,
            postedEventsTypes: MutableSet<com.google.devtools.build.lib.skyframe.TopLevelStatusEvents.Type?>
        ) {
            val configuredTarget: ConfiguredTarget = configuredTargetValue.getConfiguredTarget()
            if (postedEventsTypes.add(TopLevelStatusEvents.Type.TOP_LEVEL_TARGET_CONFIGURED)) {
                val target: Target?
                try {
                    val label: Label = configuredTarget.getOriginalLabel()
                    target =
                        (env.getValue(label.getPackageIdentifier()) as PackageValue)
                            .getPackage()
                            .getTarget(label.name)
                } catch (e: NoSuchTargetException) {
                    throw java.lang.IllegalStateException(
                        "Target should already be verified and available for top level ConfiguredTarget: "
                                + configuredTarget,
                        e
                    )
                }

                val actual: Label? =
                    if (configuredTarget is AliasConfiguredTarget)
                        configuredTarget.getActual().getLabel()
                    else
                        null

                env.getListener()
                    .post(
                        TargetConfiguredEvent(
                            target,
                            getConfigurationValue(env, configuredTarget.getConfigurationKey()),
                            actual
                        )
                    )
            }
            postEventIfNecessary(
                postedEventsTypes, env, TopLevelTargetAnalyzedEvent.create(configuredTarget)
            )
        }

        /**
         * [AspectAnalyzedEvents] should be sent out before conflict checking to be consistent with
         * the non-skymeld code path.
         */
        @Throws(java.lang.InterruptedException::class)
        private fun announceTopLevelAspectAnalyzed(
            env: SkyFunction.Environment,
            topLevelAspectsValue: TopLevelAspectsValue,
            postedEventsTypes: MutableSet<com.google.devtools.build.lib.skyframe.TopLevelStatusEvents.Type?>
        ) {
            if (!postedEventsTypes.add(TopLevelStatusEvents.Type.ASPECT_ANALYZED)) {
                return
            }
            // The ConfiguredTargetKey in the AspectKey will vary from the TopLevelAspectKey's
            // ConfiguredTargetKey due to rule transitions. See the implementation in
            // ToplevelStarlarkAspectFunction#getConfiguredTargetKey().
            // Keep this logic in-sync with SkyframeExecutor#configureTargets().
            val firstAspectKey: AspectKey? =
                com.google.common.collect.Iterables.getFirst<AspectKey?>(
                    topLevelAspectsValue.getTopLevelAspectsMap().keySet(), null
                )
            if (firstAspectKey == null) {
                return
            }
            val transitionedKey: ConfiguredTargetKey? = firstAspectKey.getBaseConfiguredTargetKey()
            val aspectCount: Int = topLevelAspectsValue.getTopLevelAspectsMap().size()
            env.getListener().post(ToplevelAspectsIdentifiedEvent(transitionedKey, aspectCount))

            for (entry in topLevelAspectsValue.getTopLevelAspectsMap().entrySet()) {
                val aspectKey: AspectKey = entry.getKey()
                env.getListener()
                    .post(
                        AspectConfiguredEvent(
                            aspectKey.getLabel(),  /* aspectClassName= */
                            aspectKey.getAspectClass().getName(),
                            aspectKey.getAspectDescriptor().getDescription(),
                            getConfigurationValue(env, aspectKey.getConfigurationKey())
                        )
                    )
                env.getListener().post(AspectAnalyzedEvent.create(aspectKey, entry.getValue()))
            }
        }

        @Throws(java.lang.InterruptedException::class)
        private fun getConfigurationValue(
            env: SkyFunction.Environment, key: BuildConfigurationKey?
        ): BuildConfigurationValue? {
            if (key == null) {
                return null
            }
            return env.getValue(key) as BuildConfigurationValue?
        }

        private fun postEventIfNecessary(
            postedEventsTypes: MutableSet<com.google.devtools.build.lib.skyframe.TopLevelStatusEvents.Type?>,
            env: SkyFunction.Environment,
            event: TopLevelStatusEventWithType
        ) {
            if (postedEventsTypes.add(event.getType())) {
                env.getListener().post(event)
            }
        }

        private fun isTest(testType: com.google.devtools.build.lib.skyframe.BuildDriverKey.TestType): Boolean {
            return testType != com.google.devtools.build.lib.skyframe.BuildDriverKey.TestType.NOT_TEST
        }

        @Throws(java.lang.InterruptedException::class, NoSuchTargetException::class)
        private fun getTarget(env: SkyFunction.Environment, label: Label): Target? {
            val packageValue: PackageValue? = env.getValue(label.getPackageIdentifier()) as PackageValue?
            if (env.valuesMissing() || packageValue == null) {
                return null
            }
            val pkg: Package = packageValue.getPackage()
            return pkg.getTarget(label.name)
        }

        /**
         * Declares dependencies and checks values for requested nodes in the graph.
         * 
         * 
         * Calls [SkyFunction.Environment.getValuesAndExceptions] and iterates over the result.
         * If any node is not done, or during iteration any value has exception, [ ][SkyFunction.Environment.valuesMissing] will return true.
         */
        @Throws(java.lang.InterruptedException::class)
        private fun declareDependenciesAndCheckValues(
            env: SkyFunction.Environment, skyKeys: Iterable<out SkyKey?>
        ) {
            val result: SkyframeLookupResult = env.getValuesAndExceptions(skyKeys)
            for (key in skyKeys) {
                if (result.get(key) == null) {
                    return
                }
            }
        }

        private fun addArtifactsToBuilder(
            artifacts: MutableList<out Artifact>,
            builder: com.google.common.collect.ImmutableSet.Builder<Artifact?>,
            filter: com.google.devtools.build.lib.util.RegexFilter
        ) {
            for (artifact in artifacts) {
                if (filter.isIncluded(artifact.getOwnerLabel().toString())) {
                    builder.add(artifact)
                }
            }
        }
    }
}
