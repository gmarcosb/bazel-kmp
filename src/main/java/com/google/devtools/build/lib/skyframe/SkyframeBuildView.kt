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
package com.google.devtools.build.lib.skyframe

import com.google.devtools.build.lib.buildeventstream.BuildEventIdUtil.configurationIdMessage

/**
 * Skyframe-based driver of analysis.
 * 
 * 
 * Covers enough functionality to work as a substitute for `BuildView#configureTargets`.
 */
class SkyframeBuildView(
    artifactFactory: ArtifactFactory,
    skyframeExecutor: SkyframeExecutor,
    ruleClassProvider: ConfiguredRuleClassProvider,
    actionKeyContext: ActionKeyContext?
) {
    private val factory: ConfiguredTargetFactory
    private val artifactFactory: ArtifactFactory
    private val skyframeExecutor: SkyframeExecutor
    private val actionKeyContext: ActionKeyContext?
    private var enableAnalysis = false

    // This hack allows us to see when an action lookup node has been invalidated, and thus when the
    // set of artifact conflicts needs to be recomputed (whenever an action lookup node has been
    // invalidated or newly evaluated).
    private val progressReceiver = ActionLookupValueProgressReceiver()

    // Used to see if checks of graph consistency need to be done after analysis.
    @kotlin.concurrent.Volatile
    private var someActionLookupValueEvaluated = false

    // We keep the set of invalidated action lookup nodes so that we can know if something has been
    // invalidated after graph pruning has been executed.
    private var dirtiedActionLookupKeys: MutableSet<ActionLookupKey?> =
        com.google.common.collect.Sets.newConcurrentHashSet<ActionLookupKey?>()

    private val ruleClassProvider: ConfiguredRuleClassProvider

    // Null until the build configuration is set.
    private var configuration: BuildConfigurationValue? = null
    private var originalConfigurationOptions: BuildOptions? = null

    /**
     * If the last build was executed with `Options#discard_analysis_cache` and we are not
     * running Skyframe full, we should clear the legacy data since it is out-of-sync.
     */
    private var skyframeAnalysisWasDiscarded = false

    private var largestTopLevelKeySetCheckedForConflicts: com.google.common.collect.ImmutableSet<ActionLookupKey?> =
        com.google.common.collect.ImmutableSet.of<ActionLookupKey?>()
    private var foundActionConflictInLatestCheck = false

    private val starlarkTransitionCache: StarlarkTransitionCache = StarlarkTransitionCache()

    init {
        this.actionKeyContext = actionKeyContext
        this.factory =
            ConfiguredTargetFactory(
                ruleClassProvider,
                {
                    skyframeExecutor.getCheckerForConflictCheckingMode(
                        UPON_CONFIGURED_OBJECT_CREATION
                    )
                })
        this.artifactFactory = artifactFactory
        this.skyframeExecutor = skyframeExecutor
        this.ruleClassProvider = ruleClassProvider
    }

    fun resetProgressReceiver() {
        progressReceiver.reset()
    }

    val evaluatedCounts: TotalAndConfiguredTargetOnlyMetric
        get() = TotalAndConfiguredTargetOnlyMetric.create(
            progressReceiver.configuredObjectCount.get(), progressReceiver.configuredTargetCount.get()
        )

    val configuredTargetFactory: ConfiguredTargetFactory
        get() = factory

    val evaluatedActionCounts: TotalAndConfiguredTargetOnlyMetric
        get() = TotalAndConfiguredTargetOnlyMetric.create(
            progressReceiver.actionCount.get(), progressReceiver.configuredTargetActionCount.get()
        )

    val evaluatedActionCountsByMnemonic: com.google.common.collect.ImmutableMap<String?, Int?>
        get() {
            val builder: com.google.common.collect.ImmutableMap.Builder<String?, Int?> =
                com.google.common.collect.ImmutableMap.builder<String?, Int?>()
            for (entry in progressReceiver.actionCountByMnemonic.entrySet()) {
                builder.put(entry.getKey(), entry.getValue().get())
            }
            return builder.buildOrThrow()
        }

    /**
     * Returns a description of the analysis-cache affecting changes between the current configuration
     * and the incoming one.
     * 
     * @param maxDifferencesToShow the maximum number of change-affecting options to include in the
     * returned description
     * @return a description or `null` if the configuration has not changed in a way that
     * requires the analysis cache to be invalidated
     */
    private fun describeConfigurationDifference(
        oldOptions: BuildOptions?, newOptions: BuildOptions?, maxDifferencesToShow: Int
    ): String? {
        val diff: OptionsDiff = OptionsDiff.diff(oldOptions, newOptions)

        val nativeCacheInvalidatingDifferences: com.google.common.collect.ImmutableSet<com.google.devtools.common.options.OptionDefinition?> =
            getNativeCacheInvalidatingDifferences(configuration, diff)
        if (nativeCacheInvalidatingDifferences.isEmpty()
            && diff.getChangedStarlarkOptions().isEmpty()
        ) {
            // The configuration may have changed, but none of the changes required a cache reset. For
            // example, test trimming was turned on and a test option changed. In this case, nothing needs
            // to be done.
            return null
        }

        if (maxDifferencesToShow == 0) {
            return "Build options have changed"
        }

        val relevantDifferences: com.google.common.collect.ImmutableList<String?> =
            com.google.common.collect.Streams.concat(
                diff.getChangedStarlarkOptions().stream().map(Label::getCanonicalForm),
                nativeCacheInvalidatingDifferences.stream()
                    .map<R?>(java.util.function.Function { obj: com.google.devtools.common.options.OptionDefinition? -> obj.getOptionName() })
            )
                .map({ s -> "--" + s }) // Sorting the list to ensure that (if truncated through maxDifferencesToShow) the
                // options in the message remain stable.
                .sorted()
                .collect(com.google.common.collect.ImmutableList.toImmutableList<E?>())

        if (maxDifferencesToShow > 0 && relevantDifferences.size() > maxDifferencesToShow) {
            return java.lang.String.format(
                "Build options %s%s and %d more have changed",
                com.google.common.base.Joiner.on(", ").join(relevantDifferences.subList(0, maxDifferencesToShow)),
                if (maxDifferencesToShow == 1) "" else ",",
                relevantDifferences.size() - maxDifferencesToShow
            )
        } else if (relevantDifferences.size() == 1) {
            return java.lang.String.format(
                "Build option %s has changed",
                com.google.common.collect.Iterables.getOnlyElement<String?>(relevantDifferences)
            )
        } else if (relevantDifferences.size() == 2) {
            return java.lang.String.format(
                "Build options %s have changed", com.google.common.base.Joiner.on(" and ").join(relevantDifferences)
            )
        } else {
            return java.lang.String.format(
                "Build options %s, and %s have changed",
                com.google.common.base.Joiner.on(", ")
                    .join(relevantDifferences.subList(0, relevantDifferences.size() - 1)),
                com.google.common.collect.Iterables.getLast<String?>(relevantDifferences)
            )
        }
    }

    // TODO(schmitt): This method assumes that the only option that can cause multiple target
    //  configurations is --cpu which (with the presence of split transitions) is no longer true.
    private fun getNativeCacheInvalidatingDifferences(
        newConfig: BuildConfigurationValue, diff: OptionsDiff
    ): com.google.common.collect.ImmutableSet<com.google.devtools.common.options.OptionDefinition?> {
        return diff.getFirst().keySet().stream()
            .filter(
                { definition ->
                    ruleClassProvider.shouldInvalidateCacheForOptionDiff(
                        newConfig.getOptions(),
                        definition,
                        diff.getFirst().get(definition),
                        com.google.common.collect.Iterables.getOnlyElement<T?>(diff.getSecond().get(definition))
                    )
                })
            .collect(com.google.common.collect.ImmutableSet.toImmutableSet<E?>())
    }

    /**
     * Returns whether the analysis results from previous invocations should be discarded or report an
     * error if it should be, but it's disallowed.
     * 
     * 
     * This should happen when the top-level configuration has changed or if the previous
     * invocation decided that this should happen. Either way, this method also emits a message
     * informing the user about this decision.
     */
    @Throws(InvalidConfigurationException::class)
    fun shouldDiscardAnalysisCache(
        eventHandler: com.google.devtools.build.lib.events.EventHandler,
        newOptions: BuildOptions?,
        maxDifferencesToShow: Int,
        allowAnalysisCacheDiscards: Boolean,
        additionalConfigurationChangeEvent: java.util.Optional<AdditionalConfigurationChangeEvent?>
    ): Boolean {
        if (this.configuration == null) {
            return false
        }

        if (skyframeAnalysisWasDiscarded) {
            logger.atInfo().log("Discarding analysis cache because the previous invocation told us to")
            eventHandler.handle(
                com.google.devtools.build.lib.events.Event.warn(
                    "--discard_analysis_cache was used in the previous build, "
                            + "discarding analysis cache."
                )
            )
            return true
        }

        var diff =
            describeConfigurationDifference(
                originalConfigurationOptions, newOptions, maxDifferencesToShow
            )

        if (diff == null && additionalConfigurationChangeEvent.isPresent()) {
            diff = additionalConfigurationChangeEvent.get().getChangeDescription()
        }

        if (diff != null) {
            if (!allowAnalysisCacheDiscards) {
                val message: String? = java.lang.String.format("%s, analysis cache would have been discarded.", diff)
                throw InvalidConfigurationException(
                    message, FailureDetails.BuildConfiguration.Code.CONFIGURATION_DISCARDED_ANALYSIS_CACHE
                )
            }
            eventHandler.handle(
                com.google.devtools.build.lib.events.Event.warn(
                    (diff
                            + ", discarding analysis cache (this can be expensive, see"
                            + " https://bazel.build/advanced/performance/iteration-speed).")
                )
            )
            logger.atInfo().log(
                "Discarding analysis cache because the build configuration changed: %s", diff
            )
            return true
        }

        return false
    }

    /** Sets the configuration. Not thread-safe.  */
    @com.google.common.annotations.VisibleForTesting
    fun setConfiguration(
        configuration: BuildConfigurationValue?,
        originalOptions: BuildOptions?,
        discardAnalysisCache: Boolean
    ) {
        if (discardAnalysisCache) {
            // Note that clearing the analysis cache is currently required for correctness. It is also
            // helpful to save memory.
            //
            // If we had more memory, fixing the correctness issue (see also b/144932999) would allow us
            // to not invalidate the cache, leading to potentially better performance on incremental
            // builds.
            this.configuration = configuration
            this.originalConfigurationOptions = originalOptions
            skyframeExecutor.handleAnalysisInvalidatingChange()
        } else if (this.configuration == null) {
            this.configuration = configuration
            this.originalConfigurationOptions = originalOptions
        }

        skyframeAnalysisWasDiscarded = false
        skyframeExecutor.setTopLevelConfiguration(configuration)
    }

    @get:com.google.common.annotations.VisibleForTesting
    val buildConfiguration: BuildConfigurationValue?
        get() = configuration

    /**
     * Drops the analysis cache. If building with Skyframe, targets in `topLevelTargets` may
     * remain in the cache for use during the execution phase.
     * 
     * @see com.google.devtools.build.lib.analysis.AnalysisOptions.discardAnalysisCache
     */
    fun clearAnalysisCache(
        topLevelTargets: com.google.common.collect.ImmutableSet<ConfiguredTarget?>?,
        topLevelAspects: com.google.common.collect.ImmutableSet<AspectKey?>?
    ) {
        // TODO(bazel-team): Consider clearing packages too to save more memory.
        skyframeAnalysisWasDiscarded = true
        Profiler.instance().profile("skyframeExecutor.clearAnalysisCache").use { c ->
            skyframeExecutor.clearAnalysisCache(topLevelTargets, topLevelAspects)
        }
        starlarkTransitionCache.clear()
    }

    /**
     * Analyzes the specified targets using Skyframe as the driving framework.
     * 
     * @return the configured targets that should be built along with a WalkableGraph of the analysis.
     */
    @Throws(java.lang.InterruptedException::class, ViewCreationFailedException::class)
    fun configureTargets(
        eventHandler: ExtendedEventHandler,
        labelToTargetMap: com.google.common.collect.ImmutableMap<Label?, Target?>?,
        ctKeys: com.google.common.collect.ImmutableList<ConfiguredTargetKey?>,
        topLevelAspectsKeys: com.google.common.collect.ImmutableList<TopLevelAspectsKey?>?,
        topLevelArtifactContextForConflictPruning: TopLevelArtifactContext?,
        eventBus: com.google.common.eventbus.EventBus,
        bugReporter: BugReporter?,
        keepGoing: Boolean,
        executors: QuiescingExecutors,
        checkForActionConflicts: Boolean
    ): SkyframeAnalysisResult {
        enableAnalysis(true)
        val result: ConfigureTargetsResult
        try {
            Profiler.instance().profile("skyframeExecutor.configureTargets").use { c ->
                result =
                    skyframeExecutor.configureTargets(
                        eventHandler, labelToTargetMap, ctKeys, topLevelAspectsKeys, keepGoing, executors
                    )
            }
        } finally {
            enableAnalysis(false)
        }

        val cts: com.google.common.collect.ImmutableSet<ConfiguredTarget?>? = result.configuredTargets
        val aspects: com.google.common.collect.ImmutableMap<AspectKey?, ConfiguredAspect?> = result.aspects
        val aspectKeys: com.google.common.collect.ImmutableSet<AspectKey?> = aspects.keySet()
        val packageRoots: PackageRoots? = result.packageRoots
        val evaluationResult: EvaluationResult<ActionLookupValue?> = result.evaluationResult

        val interTargetConflicts: com.google.common.collect.ImmutableMap<ActionAnalysisMetadata?, ActionConflictException?> =
            com.google.common.collect.ImmutableMap.of<ActionAnalysisMetadata?, ActionConflictException?>()
        Profiler.instance().profile("skyframeExecutor.findArtifactConflicts").use { c ->
            val newKeys: com.google.common.collect.ImmutableSet<ActionLookupKey?> =
                com.google.common.collect.ImmutableSet.builderWithExpectedSize<ActionLookupKey?>(ctKeys.size() + aspectKeys.size())
                    .addAll(ctKeys)
                    .addAll(aspectKeys)
                    .build()
            if (shouldCheckForConflicts(checkForActionConflicts, newKeys)) {
                largestTopLevelKeySetCheckedForConflicts = newKeys
                // This operation is somewhat expensive, so we only do it if the graph might have changed in
                // some way -- either we analyzed a new target or we invalidated an old one or are building
                // targets together that haven't been built before.
                val analysisTraversalResult: ActionLookupValuesTraversal =
                    skyframeExecutor.collectActionLookupValuesInBuild(ctKeys, aspectKeys)
                val conflictsAndStats: ArtifactConflictFinder.ActionConflictsAndStats =
                    ArtifactConflictFinder.findAndStoreArtifactConflicts(
                        analysisTraversalResult.getActionLookupValueShards(),
                        analysisTraversalResult.getActionCount(),
                        actionKeyContext
                    )
                val buildGraphMetrics: BuildGraphMetrics? =
                    analysisTraversalResult
                        .getMetrics()
                        .setOutputArtifactCount(conflictsAndStats.outputArtifactCount)
                        .build()
                eventBus.post(AnalysisGraphStatsEvent(buildGraphMetrics))
                interTargetConflicts = conflictsAndStats.conflicts()
                someActionLookupValueEvaluated = false
            }
        }
        // Intra-target conflict would mean an error in evaluationResult.
        if (!evaluationResult.hasError() && interTargetConflicts.isEmpty()) {
            return SkyframeAnalysisResult( /* hasLoadingError= */
                false,  /* hasAnalysisError= */
                false,  /* hasActionConflicts= */
                false,
                cts,
                evaluationResult.getWalkableGraph(),
                aspects,
                result.targetsWithConfiguration,
                packageRoots
            )
        }

        val errorProcessingResult: ErrorProcessingResult =
            SkyframeErrorProcessor.processAnalysisErrors(
                evaluationResult,
                skyframeExecutor.getCyclesReporter(),
                eventHandler,
                keepGoing,
                skyframeExecutor.tracksStateForIncrementality(),
                eventBus,
                bugReporter
            )

        val actionConflicts: com.google.common.collect.ImmutableMap<ActionAnalysisMetadata?, ActionConflictException?> =
            com.google.common.collect.ImmutableMap.builder<ActionAnalysisMetadata?, ActionConflictException?>()
                .putAll(interTargetConflicts)
                .putAll(errorProcessingResult.actionConflicts) // Intra-target conflicts.
                .buildOrThrow()
        foundActionConflictInLatestCheck = !actionConflicts.isEmpty()
        val noKeepGoingExceptionDueToConflict: ViewCreationFailedException? = null
        // Sometimes there are action conflicts, but the actions aren't actually required to run by the
        // build. In such cases, the conflict should still be reported to the user.
        // See OutputArtifactConflictTest#unusedActionsStillConflict.
        val reportedActionConflictExceptions: MutableSet<String?> = com.google.common.collect.Sets.newHashSet<String?>()
        for (bad in actionConflicts.entrySet()) {
            val ace: ActionConflictException = bad.getValue()
            val detailedExitCode: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
                ace.getDetailedExitCode()
            if (reportedActionConflictExceptions.add(ace.getMessage())) {
                ace.reportTo(eventHandler)
                if (keepGoing) {
                    eventHandler.handle(
                        com.google.devtools.build.lib.events.Event.warn(
                            ("errors encountered while analyzing target '"
                                    + bad.getKey().getOwner().getLabel()
                                    + "': it will not be built")
                        )
                    )
                }
            }
            if (!keepGoing) {
                noKeepGoingExceptionDueToConflict =
                    ViewCreationFailedException(detailedExitCode.getFailureDetail(), ace)
            }
        }

        if (foundActionConflictInLatestCheck) {
            // In order to determine the set of configured targets transitively error free from action
            // conflict issues, we run a post-processing update() that uses the bad action map.
            val topLevelActionConflictReport: TopLevelActionConflictReport
            enableAnalysis(true)
            try {
                topLevelActionConflictReport =
                    skyframeExecutor.filterActionConflictsForConfiguredTargetsAndAspects(
                        eventHandler,
                        com.google.common.collect.Iterables.< T > concat < T ? > (ctKeys, aspectKeys
                    ),
                actionConflicts,
                topLevelArtifactContextForConflictPruning)
            } finally {
                enableAnalysis(false)
            }
            // Report an AnalysisFailureEvent to BEP for the top-level targets with discoverable action
            // conflicts, then finally throw if evaluation is --nokeep_going.
            for (actionLookupKey in com.google.common.collect.Iterables.concat<Any?>(ctKeys, aspectKeys)) {
                var actionLookupKey: ActionLookupKey? = actionLookupKey
                if (!topLevelActionConflictReport.isErrorFree(actionLookupKey)) {
                    val e: java.util.Optional<ActionConflictException> =
                        topLevelActionConflictReport.getConflictException(actionLookupKey)
                    if (e.isEmpty()) {
                        continue
                    }
                    val conflictException: ActionConflictException = e.get()
                    val failedCause: AnalysisFailedCause =
                        makeArtifactConflictAnalysisFailedCause(conflictException)
                    var targetConfigured = true
                    // Attempt to promote any ConfiguredTargetKey to the one embedded in the ConfiguredTarget
                    // to reflect any transitions or trimming.
                    if (actionLookupKey is ConfiguredTargetKey) {
                        val value: ConfiguredTargetValue? =
                            (evaluationResult.get(actionLookupKey) as ConfiguredTargetValue?)
                        if (value == null) {
                            targetConfigured = false
                        } else if (value.getConfiguredTarget() != null) {
                            // It's possible that the ConfiguredTarget has been cleared.
                            actionLookupKey = value.getConfiguredTarget().getLookupKey()
                        }
                    }
                    if (!targetConfigured) {
                        eventBus.post(
                            AnalysisFailureEvent.whileAnalyzingTarget(
                                actionLookupKey as ConfiguredTargetKey?,
                                NestedSetBuilder.create(Order.STABLE_ORDER, failedCause)
                            )
                        )
                    } else {
                        eventBus.post(
                            AnalysisFailureEvent.actionConflict(
                                actionLookupKey, NestedSetBuilder.create(Order.STABLE_ORDER, failedCause)
                            )
                        )
                    }

                    if (!keepGoing) {
                        noKeepGoingExceptionDueToConflict =
                            ViewCreationFailedException(
                                failedCause.getDetailedExitCode().getFailureDetail(), conflictException
                            )
                    }
                }
            }

            // If we're here and we're --nokeep_going, then there was a conflict due to actions not
            // discoverable by TopLevelActionLookupConflictFindingFunction. This includes extra actions,
            // coverage artifacts, and artifacts produced by aspects in output groups not present in
            // --output_groups. Throw the exception produced by the ArtifactConflictFinder which cannot
            // identify root-cause top-level keys but does catch all possible conflicts.
            if (!keepGoing) {
                skyframeExecutor.resetActionConflictsStoredInSkyframe()
                throw com.google.common.base.Preconditions.checkNotNull<Any?>(noKeepGoingExceptionDueToConflict)
            }

            // Filter cts and aspects to only error-free keys. Note that any analysis failure - not just
            // action conflicts - will be observed here and lead to a key's exclusion.
            cts =
                ctKeys.stream()
                    .filter(java.util.function.Predicate { k: ConfiguredTargetKey? ->
                        topLevelActionConflictReport.isErrorFree(
                            k
                        )
                    })
                    .map<Any?>(
                        java.util.function.Function { k: ConfiguredTargetKey? ->
                            com.google.common.base.Preconditions.checkNotNull<Any?>(
                                evaluationResult.get(k) as ConfiguredTargetValue?,
                                k
                            )
                                .getConfiguredTarget()
                        })
                    .collect(TODO("Cannot convert element"))<Object> com . google . common . collect . ImmutableSet . toImmutableSet < kotlin . Any ? > ()


            TODO(
                """
                |Cannot convert element
                |With text:
                |aspects =
                |          aspects.entrySet().stream()
                |              .filter(e -> topLevelActionConflictReport.isErrorFree(e.getKey()))
                |              .collect(<Entry<AspectKey,ConfiguredAspect>, AspectKey, ConfiguredAspect>toImmutableMap(Map.Entry::getKey, Map.Entry::getValue)
                """.trimMargin()
            )
        }

        return SkyframeAnalysisResult(
            errorProcessingResult.hasLoadingError,
            evaluationResult.hasError() || foundActionConflictInLatestCheck,
            foundActionConflictInLatestCheck,
            cts,
            evaluationResult.getWalkableGraph(),
            aspects,
            result.targetsWithConfiguration,
            packageRoots
        )
    }

    /**
     * Performs analysis & execution of the CTs and aspects with Skyframe.
     * 
     * 
     * In case of error: --nokeep_going will eventually throw a ViewCreationFailedException,
     * whereas --keep_going will return a SkyframeAnalysisAndExecutionResult which contains the
     * failure details.
     * 
     * 
     * TODO(b/199053098) Have a more appropriate return type.
     */
    @Throws(
        java.lang.InterruptedException::class,
        ViewCreationFailedException::class,
        BuildFailedException::class,
        TestExecException::class
    )
    fun analyzeAndExecuteTargets(
        eventHandler: ExtendedEventHandler,
        ctKeys: MutableList<ConfiguredTargetKey?>,
        topLevelAspectsKeys: com.google.common.collect.ImmutableList<TopLevelAspectsKey?>,
        testsToRun: com.google.common.collect.ImmutableSet<Label?>?,
        labelTargetMap: com.google.common.collect.ImmutableMap<Label?, Target?>,
        topLevelArtifactContext: TopLevelArtifactContext?,
        explicitTargetPatterns: com.google.common.collect.ImmutableSet<Label?>,
        eventBus: com.google.common.eventbus.EventBus,
        bugReporter: BugReporter?,
        resourceManager: ResourceManager,
        buildResultListener: BuildResultListener,
        coverageReportActionsWrapperSupplier: CoverageReportActionsWrapperSupplier,
        keepGoing: Boolean,
        skipIncompatibleExplicitTargets: Boolean,
        checkForActionConflicts: Boolean,
        extraActionTopLevelOnly: Boolean,
        executors: QuiescingExecutors,
        shouldDiscardAnalysisCache: Boolean,
        shouldClearSyscallCache: Boolean,
        buildDriverKeyTestContext: BuildDriverKeyTestContext,
        skymeldAnalysisOverlapPercentage: Int
    ): SkyframeAnalysisResult {
        val analysisWorkTimer: com.google.common.base.Stopwatch = com.google.common.base.Stopwatch.createStarted()
        var mainEvaluationResult: EvaluationResult<SkyValue>

        val newKeys: com.google.common.collect.ImmutableSet<ActionLookupKey?> =
            com.google.common.collect.ImmutableSet.builderWithExpectedSize<ActionLookupKey?>(
                ctKeys.size() + topLevelAspectsKeys.size()
            )
                .addAll(ctKeys)
                .addAll(topLevelAspectsKeys)
                .build()

        val workspaceStatusArtifacts: com.google.common.collect.ImmutableList<Artifact?>? =
            skyframeExecutor.getWorkspaceStatusArtifacts(eventHandler)

        skyframeExecutor.setTestTypeResolver(
            TestTypeResolver { target ->
                determineTestTypeImpl(
                    testsToRun,
                    labelTargetMap,
                    target.getLabel(),
                    buildDriverKeyTestContext,
                    eventHandler
                )
            })

        val buildDriverCTKeys: com.google.common.collect.ImmutableSet<BuildDriverKey> =
            ctKeys.stream()
                .map<Any?>(
                    java.util.function.Function { ctKey: ConfiguredTargetKey? ->
                        BuildDriverKey.ofConfiguredTarget(
                            ctKey,
                            topLevelArtifactContext,  /* explicitlyRequested= */
                            explicitTargetPatterns.contains(
                                ctKey.getLabel()
                            ),
                            skipIncompatibleExplicitTargets,
                            extraActionTopLevelOnly,
                            keepGoing
                        )
                    })
                .collect(com.google.common.collect.ImmutableSet.toImmutableSet<Any?>())

        val buildDriverAspectKeys: com.google.common.collect.ImmutableSet<BuildDriverKey> =
            topLevelAspectsKeys.stream()
                .map<Any?>(
                    java.util.function.Function { k: TopLevelAspectsKey? ->
                        BuildDriverKey.ofTopLevelAspect(
                            k,
                            topLevelArtifactContext,  /* explicitlyRequested= */
                            explicitTargetPatterns.contains(k.getLabel()),
                            skipIncompatibleExplicitTargets,
                            extraActionTopLevelOnly,
                            keepGoing
                        )
                    })
                .collect(com.google.common.collect.ImmutableSet.toImmutableSet<Any?>())
        val detailedExitCodes: MutableList<DetailedExitCode?> = java.util.ArrayList<DetailedExitCode?>()
        val executor: MultiThreadPoolsQuiescingExecutor =
            executors.mergedAnalysisAndExecutionExecutor as MultiThreadPoolsQuiescingExecutor
        val topLevelKeys: MutableSet<SkyKey?> =
            com.google.common.collect.Sets.newConcurrentHashSet<E?>(
                com.google.common.collect.Sets.union<BuildDriverKey?>(
                    buildDriverCTKeys,
                    buildDriverAspectKeys
                )
            )

        val conflictCheckingMode: ConflictCheckingMode? =
            if (shouldCheckForConflicts(checkForActionConflicts, newKeys))
                if (skyframeExecutor.tracksStateForIncrementality())
                    WITH_TRAVERSAL
                else
                    UPON_CONFIGURED_OBJECT_CREATION
            else
                NONE
        skyframeExecutor.setConflictCheckingModeInThisBuild(conflictCheckingMode)

        AnalysisOperationWatcher.createAndRegisterWithEventBus(
            topLevelKeys,
            eventBus,  /* lowerThresholdToSignalForExecution= */
            (topLevelKeys.size() * skymeldAnalysisOverlapPercentage / 100.0).toFloat(),  /* finisher= */
            {
                analysisFinishedCallback(
                    eventBus,
                    buildResultListener,
                    skyframeExecutor,
                    ctKeys,  /* shouldDiscardAnalysisCache= */
                    shouldDiscardAnalysisCache,  /* shouldClearSyscallCache= */
                    shouldClearSyscallCache,  /* measuredAnalysisTime= */
                    analysisWorkTimer.stop().elapsed().toMillis(),  /* conflictCheckingMode= */
                    conflictCheckingMode
                )
            },  /* executionGoAheadCallback= */
            { executor.launchQueuedUpExecutionPhaseTasks() }).use { autoCloseableWatcher ->
            try {
                skyframeExecutor.getIsBuildingExclusiveArtifacts().set(false)
                resourceManager.resetResourceUsage()
                val additionalArtifactsResult: EvaluationResult<SkyValue>
                try {
                    Profiler.instance().profile("skyframeExecutor.evaluateBuildDriverKeys").use { c ->
                        // Will be disabled later by the AnalysisOperationWatcher upon conclusion of analysis.
                        enableAnalysis(true)
                        mainEvaluationResult =
                            skyframeExecutor.evaluateBuildDriverKeys(
                                eventHandler,
                                buildDriverCTKeys,
                                buildDriverAspectKeys,
                                workspaceStatusArtifacts,
                                keepGoing,
                                executors.executionParallelism(),
                                executor
                            )
                    }
                } finally {
                    if (shouldClearSyscallCache) {
                        skyframeExecutor.clearSyscallCache()
                    }
                    // Required for incremental correctness.
                    // We unconditionally reset the states here instead of in #analysisFinishedCallback since
                    // in case of --nokeep_going & analysis error, the analysis phase is never finished.
                    skyframeExecutor.clearIncrementalArtifactConflictFindingStates()
                    skyframeExecutor.resetBuildDriverFunction()
                    skyframeExecutor.setTestTypeResolver(null)

                    // These attributes affect whether conflict checking will be done during the next build.
                    if (shouldCheckForConflicts(checkForActionConflicts, newKeys)) {
                        largestTopLevelKeySetCheckedForConflicts = newKeys
                    }
                    someActionLookupValueEvaluated = false
                }

                // The exclusive tests whose analysis succeeded i.e. those that can be run.
                val exclusiveTestsToRun: com.google.common.collect.ImmutableSet<ConfiguredTarget?> =
                    getExclusiveTests(mainEvaluationResult)
                val continueWithExclusiveTests = !mainEvaluationResult.hasError() || keepGoing
                var hasExclusiveTestsError = false

                if (continueWithExclusiveTests && !exclusiveTestsToRun.isEmpty()) {
                    skyframeExecutor.getIsBuildingExclusiveArtifacts().set(true)
                    // Run exclusive tests sequentially.
                    val testCompletionKeys: Iterable<SkyKey?> =
                        TestCompletionValue.keys(
                            exclusiveTestsToRun, topLevelArtifactContext,  /* exclusiveTesting= */true
                        )
                    for (testCompletionKey in testCompletionKeys) {
                        val testRunResult: EvaluationResult<SkyValue> =
                            skyframeExecutor.runExclusiveTestSkymeld(
                                eventHandler,
                                resourceManager,
                                testCompletionKey,
                                keepGoing,
                                executors.executionParallelism()
                            )
                        if (testRunResult.hasError()) {
                            hasExclusiveTestsError = true
                            detailedExitCodes.add(
                                SkyframeErrorProcessor.processErrors(
                                    testRunResult,
                                    skyframeExecutor.getCyclesReporter(),
                                    eventHandler,
                                    keepGoing,
                                    skyframeExecutor.tracksStateForIncrementality(),
                                    eventBus,
                                    bugReporter,  /* includeExecutionPhase= */
                                    true
                                )
                                    .executionDetailedExitCode
                            )
                        }
                    }
                }

                // Coverage report generation should only be requested after all tests have executed.
                // When --nokeep_going and there's an earlier error, we should skip this and fail fast.
                if ((!mainEvaluationResult.hasError() && !hasExclusiveTestsError) || keepGoing) {
                    val coverageReportArtifacts: com.google.common.collect.ImmutableSet<Artifact?>? =
                        coverageReportActionsWrapperSupplier.getCoverageReportArtifacts(
                            buildResultListener.getAnalyzedTargets(), buildResultListener.getAnalyzedTests()
                        )
                    eventBus.post(CoverageArtifactsKnownEvent.create(coverageReportArtifacts))
                    additionalArtifactsResult =
                        skyframeExecutor.evaluateSkyKeys(
                            eventHandler, Artifact.keys(coverageReportArtifacts), keepGoing
                        )
                    if (additionalArtifactsResult.hasError()) {
                        detailedExitCodes.add(
                            SkyframeErrorProcessor.processErrors(
                                additionalArtifactsResult,
                                skyframeExecutor.getCyclesReporter(),
                                eventHandler,
                                keepGoing,
                                skyframeExecutor.tracksStateForIncrementality(),
                                eventBus,
                                bugReporter,  /* includeExecutionPhase= */
                                true
                            )
                                .executionDetailedExitCode
                        )
                    }
                }
            } finally {
                // No more action execution beyond this point.
                skyframeExecutor.clearExecutionStatesSkymeld(eventHandler)
                // Also releases thread locks.
                resourceManager.resetResourceUsage()
            }
            if (!mainEvaluationResult.hasError() && detailedExitCodes.isEmpty()) {
                val successfulAspects: com.google.common.collect.ImmutableMap<AspectKey?, ConfiguredAspect?> =
                    getSuccessfulAspectMap(
                        topLevelAspectsKeys.size(),
                        mainEvaluationResult,
                        buildDriverAspectKeys,  /* topLevelActionConflictReport= */
                        null
                    )
                val targetsWithConfiguration: com.google.common.collect.ImmutableList.Builder<TargetAndConfiguration?> =
                    com.google.common.collect.ImmutableList.builderWithExpectedSize<TargetAndConfiguration?>(ctKeys.size())
                val successfulConfiguredTargets: com.google.common.collect.ImmutableSet<ConfiguredTarget?> =
                    getSuccessfulConfiguredTargets(
                        ctKeys.size(),
                        mainEvaluationResult,
                        buildDriverCTKeys,
                        labelTargetMap,
                        targetsWithConfiguration,  /* topLevelActionConflictReport= */
                        null
                    )

                return SkyframeAnalysisAndExecutionResult.Companion.success(
                    successfulConfiguredTargets,
                    mainEvaluationResult.getWalkableGraph(),
                    successfulAspects,
                    targetsWithConfiguration.build(),  /* packageRoots= */
                    null
                )
            }

            val errorProcessingResult: ErrorProcessingResult =
                SkyframeErrorProcessor.processErrors(
                    mainEvaluationResult,
                    skyframeExecutor.getCyclesReporter(),
                    eventHandler,
                    keepGoing,
                    skyframeExecutor.tracksStateForIncrementality(),
                    eventBus,
                    bugReporter,  /* includeExecutionPhase= */
                    true
                )
            detailedExitCodes.add(errorProcessingResult.executionDetailedExitCode)

            foundActionConflictInLatestCheck = !errorProcessingResult.actionConflicts.isEmpty()
            val topLevelActionConflictReport: TopLevelActionConflictReport? =
                if (foundActionConflictInLatestCheck)
                    handleActionConflicts(
                        eventHandler,
                        mainEvaluationResult.getWalkableGraph(),
                        ctKeys,
                        topLevelAspectsKeys,
                        topLevelArtifactContext,
                        eventBus,
                        keepGoing,
                        errorProcessingResult
                    )
                else
                    null
            val successfulAspects: com.google.common.collect.ImmutableMap<AspectKey?, ConfiguredAspect?> =
                getSuccessfulAspectMap(
                    topLevelAspectsKeys.size(),
                    mainEvaluationResult,
                    buildDriverAspectKeys,
                    topLevelActionConflictReport
                )
            val targetsWithConfiguration: com.google.common.collect.ImmutableList.Builder<TargetAndConfiguration?> =
                com.google.common.collect.ImmutableList.builderWithExpectedSize<TargetAndConfiguration?>(ctKeys.size())
            val successfulConfiguredTargets: com.google.common.collect.ImmutableSet<ConfiguredTarget?> =
                getSuccessfulConfiguredTargets(
                    ctKeys.size(),
                    mainEvaluationResult,
                    buildDriverCTKeys,
                    labelTargetMap,
                    targetsWithConfiguration,
                    topLevelActionConflictReport
                )
            return SkyframeAnalysisAndExecutionResult.Companion.withErrors( /* hasLoadingError= */
                errorProcessingResult.hasLoadingError,  // legacy behavior: action conflicts are considered analysis errors.
                /* hasAnalysisError= */
                errorProcessingResult.hasAnalysisError
                        || foundActionConflictInLatestCheck,  /* hasActionConflicts= */
                foundActionConflictInLatestCheck,
                successfulConfiguredTargets,
                mainEvaluationResult.getWalkableGraph(),
                successfulAspects,
                targetsWithConfiguration.build(),  /* packageRoots= */
                null,
                Collections.max<DetailedExitCode?>(detailedExitCodes, DetailedExitCodeComparator.INSTANCE)
            )
        }
    }

    /** Handles the required steps after all analysis work in this build is done.  */
    @Throws(java.lang.InterruptedException::class)
    private fun analysisFinishedCallback(
        eventBus: com.google.common.eventbus.EventBus,
        buildResultListener: BuildResultListener,
        skyframeExecutor: SkyframeExecutor,
        configuredTargetKeys: MutableList<ConfiguredTargetKey?>?,
        shouldDiscardAnalysisCache: Boolean,
        shouldClearSyscallCache: Boolean,
        measuredAnalysisTime: Long,
        conflictCheckingMode: ConflictCheckingMode?
    ) {
        if (conflictCheckingMode !== NONE) {
            // Now that we have the full picture, it's time to collect the metrics of the whole graph.
            val buildGraphMetricsBuilder: BuildGraphMetrics.Builder =
                skyframeExecutor
                    .collectActionLookupValuesInBuild(
                        configuredTargetKeys, buildResultListener.getAnalyzedAspects().keySet()
                    )
                    .getMetrics()
            val incrementalArtifactConflictFinder: IncrementalArtifactConflictFinder? =
                skyframeExecutor.getCheckerForConflictCheckingMode(conflictCheckingMode)
            if (incrementalArtifactConflictFinder != null) {
                buildGraphMetricsBuilder.setOutputArtifactCount(
                    incrementalArtifactConflictFinder.getOutputArtifactCount()
                )
            }
            eventBus.post(AnalysisGraphStatsEvent(buildGraphMetricsBuilder.build()))
        }

        if (shouldDiscardAnalysisCache) {
            clearAnalysisCache(
                buildResultListener.getAnalyzedTargets(),
                buildResultListener.getAnalyzedAspects().keySet()
            )
        }
        if (skyframeExecutor.getRemoteAnalysisCachingDependenciesProvider().mode()
            === RemoteAnalysisCacheMode.UPLOAD
        ) {
            skyframeExecutor.clearPackageValues()
        }

        // At this point, it's safe to clear objects related to action conflict checking.
        // Clearing the states here is a performance optimization (reduce peak heap size) and isn't
        // required for correctness.
        skyframeExecutor.clearIncrementalArtifactConflictFindingStates()

        // Clearing the syscall cache here to free up some heap space.
        // TODO(b/273225564) Would this incur more CPU cost for the execution phase cache misses?
        if (shouldClearSyscallCache) {
            skyframeExecutor.clearSyscallCache()
        }

        enableAnalysis(false)

        eventBus.post(
            AnalysisPhaseCompleteEvent(
                buildResultListener.getAnalyzedTargets(),
                this.evaluatedCounts,
                this.evaluatedActionCounts,
                this.evaluatedActionCountsByMnemonic,
                measuredAnalysisTime,
                skyframeExecutor.getPackageManager().getAndClearStatistics(),
                skyframeExecutor.wasAnalysisCacheInvalidatedAndResetBit()
            )
        )
    }

    /**
     * Report the appropriate conflicts and return a TopLevelActionConflictReport.
     * 
     * 
     * The TopLevelActionConflictReport is used to determine the set of top level targets that
     * depend on conflicted actions.
     */
    @Throws(java.lang.InterruptedException::class, ViewCreationFailedException::class)
    private fun handleActionConflicts(
        eventHandler: ExtendedEventHandler,
        graph: WalkableGraph,
        ctKeys: MutableList<ConfiguredTargetKey?>?,
        topLevelAspectsKeys: com.google.common.collect.ImmutableList<TopLevelAspectsKey?>,
        topLevelArtifactContextForConflictPruning: TopLevelArtifactContext?,
        eventBus: com.google.common.eventbus.EventBus,
        keepGoing: Boolean,
        errorProcessingResult: ErrorProcessingResult
    ): TopLevelActionConflictReport {
        // TODO(b/332898055) Unify with the noskymeld code path.
        try {
            // Here we already have the <TopLevelAspectKey, error> mapping, but what we need to fit into
            // the existing AnalysisFailureEvent is <AspectKey, error>. An extra Skyframe evaluation is
            // required.
            // If the conflict is intra-Aspect, the TopLevelAspectValue would be null and the AspectKey
            // isn't retrievable. It must be supplied via the ErrorProcessingResult.
            val effectiveTopLevelKeysForConflictReporting: Iterable<ActionLookupKey?> =
                com.google.common.collect.ImmutableSet.builder<ActionLookupKey?>()
                    .addAll(ctKeys)
                    .addAll(getDerivedAspectKeysForConflictReporting(topLevelAspectsKeys))
                    .addAll(errorProcessingResult.aspectKeysForConflictReporting)
                    .build()
            var topLevelActionConflictReport: TopLevelActionConflictReport
            enableAnalysis(true)
            // In order to determine the set of configured targets transitively error free from action
            // conflict issues, we run a post-processing update() that uses the bad action map.
            try {
                topLevelActionConflictReport =
                    skyframeExecutor.filterActionConflictsForConfiguredTargetsAndAspects(
                        eventHandler,
                        effectiveTopLevelKeysForConflictReporting,
                        errorProcessingResult.actionConflicts,
                        topLevelArtifactContextForConflictPruning
                    )
            } finally {
                enableAnalysis(false)
            }
            reportActionConflictErrors(
                topLevelActionConflictReport,
                graph,
                effectiveTopLevelKeysForConflictReporting,
                errorProcessingResult.actionConflicts,
                eventHandler,
                eventBus,
                keepGoing
            )
            return topLevelActionConflictReport
        } finally {
            skyframeExecutor.resetActionConflictsStoredInSkyframe()
        }
    }

    // When we check for action conflicts that occur with a TopLevelAspectKey, a reference to the
    // lower-level AspectKeys is required: it could happen that only some AspectKeys, but not
    // all, that derived from a TopLevelAspectKey has a conflicting action.
    private fun getDerivedAspectKeysForConflictReporting(
        topLevelAspectsKeys: com.google.common.collect.ImmutableList<TopLevelAspectsKey?>
    ): com.google.common.collect.ImmutableSet<AspectKey?> {
        val aspectKeysBuilder: com.google.common.collect.ImmutableSet.Builder<AspectKey?> =
            com.google.common.collect.ImmutableSet.builder<AspectKey?>()
        for (topLevelAspectsKey in topLevelAspectsKeys) {
            try {
                val topLevelAspectsValue: TopLevelAspectsValue =
                    skyframeExecutor.getDoneSkyValueForIntrospection(topLevelAspectsKey) as TopLevelAspectsValue
                aspectKeysBuilder.addAll(topLevelAspectsValue.getTopLevelAspectsMap().keySet())
            } catch (e: FailureToRetrieveIntrospectedValueException) {
                // It could happen that the analysis of TopLevelAspectKey wasn't complete: either its own
                // analysis failed, or another error was raise in --nokeep_going mode. In that case, it
                // couldn't be involved in the conflict exception anyway, and we just move on.
                // Unless it's an unexpected interrupt that caused the exception.
                if (e.getCause() is java.lang.InterruptedException) {
                    BugReport.sendNonFatalBugReport(e)
                }
            }
        }
        return aspectKeysBuilder.build()
    }

    private fun shouldCheckForConflicts(
        specifiedValueInRequest: Boolean, newKeys: com.google.common.collect.ImmutableSet<ActionLookupKey?>?
    ): Boolean {
        if (!specifiedValueInRequest) {
            // A build request by default enables action conflict checking, except for some cases e.g.
            // cquery.
            return false
        }

        if (someActionLookupValueEvaluated) {
            // A top-level target was added and may introduce a conflict, or a top-level target was
            // recomputed and may introduce or resolve a conflict.
            return true
        }

        if (!dirtiedActionLookupKeys.isEmpty()) {
            // No target was (re)computed but at least one was dirtied.
            // Example: (//:x //foo:y) are built, and in conflict (//:x creates foo/C and //foo:y
            // creates C). Then y is removed from foo/BUILD and only //:x is built, so //foo:y is
            // dirtied but not recomputed, and no other nodes are recomputed (and none are deleted).
            // Still we must do the conflict checking because previously there was a conflict but now
            // there isn't.
            return true
        }

        if (foundActionConflictInLatestCheck) {
            // Example sequence:
            // 1.  Build (x y z), and there is a conflict. We store (x y z) as the largest checked key
            //     set, and record the fact that there were bad actions.
            // 2.  Null-build (x z), so we don't evaluate or dirty anything, but because we know there was
            //     some conflict last time but don't know exactly which targets conflicted, it could have
            //     been (x z), so we now check again. The value of foundActionConflictInLatestCheck would
            //     then be updated for the next build, based on the result of this check.
            return true
        }

        if (!largestTopLevelKeySetCheckedForConflicts.containsAll(newKeys)) {
            // Example sequence:
            // 1.  Build (x y z), and there is a conflict. We store (x y z) as the largest checked key
            //     set, and record the fact that there were bad actions.
            // 2.  Null-build (x z), so we don't evaluate or dirty anything, but we check again for
            //     conflict because foundActionConflictInLatestCheck is true, and store (x z) as the
            //     largest checked key set.
            // 3.  Null-build (y z), so again we don't evaluate or dirty anything, and the previous build
            //     had no conflicts, so no other condition is true. But because (y z) is not a subset of
            //     (x z) and we only keep the most recent largest checked key set, we don't know if (y z)
            //     are conflict free, so we check.
            return true
        }

        // We believe the conditions above are correct in the sense that we always check for conflicts
        // when we have to. But they are incomplete, so we sometimes check for conflicts even if we
        // wouldn't have to. For example:
        // - if no target was evaluated nor dirtied and build sequence is (x y) [no conflict], (z),
        //   where z is in the transitive closure of (x y), then we shouldn't check.
        // - if no target was evaluated nor dirtied and build sequence is (x y) [no conflict], (w), (x),
        //   then the last build shouldn't conflict-check because (x y) was checked earlier. But it
        //   does, because after the second build we store (w) as the largest checked set, and (x) is
        //   not a subset of that.

        // Case when we DON'T need to re-check:
        // - a configured target is deleted. Deletion can only resolve conflicts, not introduce any, and
        //   if the previous build had a conflict then foundActionConflictInLatestCheck would be true,
        //   and if the previous build had no conflict then deleting a CT won't change that.
        //   Example that triggers this scenario:
        //   1.  genrule(name='x', srcs=['A'], ...)
        //       genrule(name='y', outs=['A'], ...)
        //   2.  Build (x y)
        //   3.  Rename 'x' to 'y', and 'y' to 'z'
        //   4.  Build (y z)
        //   5.  Null-build (y z) again
        // We only delete the old 'x' value in (5), and we don't evaluate nor dirty anything, nor was
        // (4) bad. So there's no reason to re-check just because we deleted something.
        return false
    }

    fun getArtifactFactory(): ArtifactFactory {
        return artifactFactory
    }

    fun createAnalysisEnvironment(
        owner: ActionLookupKey?,
        eventHandler: ExtendedEventHandler?,
        env: SkyFunction.Environment?,
        config: BuildConfigurationValue?,
        starlarkBuiltinsValue: StarlarkBuiltinsValue?
    ): CachingAnalysisEnvironment {
        val extendedSanityChecks = config != null && config.extendedSanityChecks()
        val allowAnalysisFailures = config != null && config.allowAnalysisFailures()
        return CachingAnalysisEnvironment(
            artifactFactory,
            skyframeExecutor.getActionKeyContext(),
            owner,
            extendedSanityChecks,
            allowAnalysisFailures,
            eventHandler,
            env,
            starlarkBuiltinsValue
        )
    }

    /**
     * Invokes the appropriate constructor to create a [ConfiguredTarget] instance.
     * 
     * 
     * For use in `ConfiguredTargetFunction`.
     * 
     * 
     * Returns null if Skyframe deps are missing or upon certain errors.
     */
    @Throws(
        java.lang.InterruptedException::class,
        ActionConflictException::class,
        InvalidExecGroupException::class,
        AnalysisFailurePropagationException::class,
        StarlarkExecTransitionLoadingException::class
    )
    fun createConfiguredTarget(
        target: Target?,
        configuration: BuildConfigurationValue?,
        analysisEnvironment: CachingAnalysisEnvironment?,
        configuredTargetKey: ConfiguredTargetKey?,
        prerequisiteMap: OrderedSetMultimap<DependencyKind?, ConfiguredTargetAndData?>?,
        materializerTargets: OrderedSetMultimap<DependencyKind?, ConfiguredTargetAndData?>?,
        configConditions: ConfigConditions?,
        toolchainContexts: ToolchainCollection<ResolvedToolchainContext?>?,
        transitivePackages: NestedSet<Package.Metadata?>?,
        execGroupCollectionBuilder: ExecGroupCollection.Builder?,
        crashIfExecutionPhase: Boolean
    ): ConfiguredTarget? {
        com.google.common.base.Preconditions.checkState(
            enableAnalysis || !crashIfExecutionPhase,
            "Already in execution phase %s %s",
            target,
            configuration
        )
        com.google.common.base.Preconditions.checkNotNull<Any?>(analysisEnvironment)
        com.google.common.base.Preconditions.checkNotNull<Any?>(target)
        com.google.common.base.Preconditions.checkNotNull<OrderedSetMultimap<DependencyKind?, ConfiguredTargetAndData?>?>(
            prerequisiteMap
        )

        val starlarkExecTransition: java.util.Optional<StarlarkAttributeTransitionProvider?>? =
            StarlarkExecTransitionLoader.loadStarlarkExecTransition(
                if (configuration == null) null else configuration.getOptions(),
                { bzlKey ->
                    analysisEnvironment
                        .getSkyframeEnv()
                        .getValueOrThrow(bzlKey, BzlLoadFailedException::class.java) as BzlLoadValue?
                })
        if (starlarkExecTransition == null) {
            return null
        }

        return factory.createConfiguredTarget(
            analysisEnvironment,
            artifactFactory,
            target,
            configuration,
            configuredTargetKey,
            prerequisiteMap,
            materializerTargets,
            configConditions,
            toolchainContexts,
            transitivePackages,
            execGroupCollectionBuilder,
            starlarkExecTransition.orElse(null)
        )
    }

    /**
     * Workaround to clear all legacy data, like the artifact factory. We need to clear them to avoid
     * conflicts. TODO(bazel-team): Remove this workaround. [skyframe-execution]
     */
    fun clearLegacyData() {
        artifactFactory.clear()
        starlarkTransitionCache.clear()
    }

    /**
     * Clears any data cached in this BuildView. To be called when the attached SkyframeExecutor is
     * reset.
     */
    fun reset() {
        configuration = null
        originalConfigurationOptions = null
        skyframeAnalysisWasDiscarded = false
        clearLegacyData()
    }

    /**
     * Hack to invalidate actions in legacy action graph when their values are invalidated in
     * skyframe.
     */
    fun getProgressReceiver(): EvaluationProgressReceiver {
        return progressReceiver
    }

    /** Clear the invalidated action lookup nodes detected during loading and analysis phases.  */
    fun clearInvalidatedActionLookupKeys() {
        dirtiedActionLookupKeys = com.google.common.collect.Sets.newConcurrentHashSet<ActionLookupKey?>()
        starlarkTransitionCache.clear()
    }

    /**
     * [.createConfiguredTarget] will only create configured targets if this is set to true. It
     * should be set to true before any Skyframe update call that might call into [ ][.createConfiguredTarget], and false immediately after the call. Use it to fail-fast in the case
     * that a target is requested for analysis not during the analysis phase.
     */
    fun enableAnalysis(enable: Boolean) {
        this.enableAnalysis = enable
    }

    fun getStarlarkTransitionCache(): StarlarkTransitionCache {
        return starlarkTransitionCache
    }

    private inner class ActionLookupValueProgressReceiver : EvaluationProgressReceiver {
        private val configuredObjectCount: AtomicInteger = AtomicInteger()
        private val actionCount: AtomicInteger = AtomicInteger()
        private val configuredTargetCount: AtomicInteger = AtomicInteger()
        private val configuredTargetActionCount: AtomicInteger = AtomicInteger()
        private val actionCountByMnemonic: ConcurrentHashMap<String?, AtomicInteger?> =
            ConcurrentHashMap<String?, AtomicInteger?>()

        override fun dirtied(skyKey: SkyKey?, dirtyType: DirtyType?) {
            if (skyKey is ActionLookupKey) {
                // If the value was just dirtied and not deleted, then it may not be truly invalid, since
                // it may later get re-validated. Therefore adding the key to dirtiedConfiguredTargetKeys
                // is provisional--if the key is later evaluated and the value found to be clean, then we
                // remove it from the set.
                dirtiedActionLookupKeys.add(skyKey as ActionLookupKey?)
            }
        }

        override fun evaluated(
            skyKey: SkyKey,
            state: EvaluationState,
            newValue: SkyValue?,
            newError: com.google.devtools.build.skyframe.ErrorInfo?,
            directDeps: GroupedDeps?
        ) {
            // We tolerate any action lookup keys here, although we only expect configured targets,
            // aspects, and the workspace status value.
            if (skyKey !is ActionLookupKey) {
                return
            }
            if (!state.versionChanged()) {
                // ActionLookupValue subclasses don't implement equality, so must have been marked clean.
                dirtiedActionLookupKeys.remove(skyKey)
            } else if (state.succeeded()) {
                val isConfiguredTarget = skyKey.functionName() == SkyFunctions.CONFIGURED_TARGET
                if (isConfiguredTarget) {
                    val configuredTargetKey: ConfiguredTargetKey = skyKey as ConfiguredTargetKey
                    val configuredTargetValue: ConfiguredTargetValue? = newValue as ConfiguredTargetValue?
                    if (configuredTargetKey.getConfigurationKey() != configuredTargetValue.getConfiguredTarget()
                            .getConfigurationKey()
                    ) {
                        // The node entry performs delegation and doesn't own the value. Skips it to avoid
                        // overcounting.
                        return
                    }
                    configuredTargetCount.incrementAndGet()
                }
                configuredObjectCount.incrementAndGet()
                if (newValue is ActionLookupValue) {
                    if (newValue is AspectValue) {
                        if (AspectValue.isForAliasTarget(newValue as AspectValue?)) {
                            // Created actions will be counted from {@link AspectValue} on the original target.
                            return
                        }
                    }

                    // During multithreaded operation, this is only set to true, so no concurrency issues.
                    someActionLookupValueEvaluated = true
                    val actions: com.google.common.collect.ImmutableList<ActionAnalysisMetadata> = newValue.getActions()
                    for (action in actions) {
                        actionCountByMnemonic
                            .computeIfAbsent(
                                action.getMnemonic(),
                                java.util.function.Function { m: String? -> AtomicInteger(0) })
                            .incrementAndGet()
                    }

                    val numActions: Int = actions.size()
                    actionCount.addAndGet(numActions)
                    if (isConfiguredTarget) {
                        configuredTargetActionCount.addAndGet(numActions)
                    }
                }
            }
        }

        fun reset() {
            configuredObjectCount.set(0)
            actionCount.set(0)
            configuredTargetCount.set(0)
            configuredTargetActionCount.set(0)
        }
    }

    /** Provides the list of coverage artifacts to be built.  */
    fun interface CoverageReportActionsWrapperSupplier {
        @Throws(java.lang.InterruptedException::class)
        fun getCoverageReportArtifacts(
            configuredTargets: MutableSet<ConfiguredTarget?>?, allTargetsToTest: MutableSet<ConfiguredTarget?>?
        ): com.google.common.collect.ImmutableSet<Artifact?>?
    }

    /** Encapsulates the context required to construct a test BuildDriverKey.  */
    interface BuildDriverKeyTestContext {
        val testStrategy: String?

        fun forceExclusiveTestsInParallel(): Boolean

        fun forceExclusiveIfLocalTestsInParallel(): Boolean
    }

    companion object {
        private val logger: GoogleLogger = GoogleLogger.forEnclosingClass()

        /**
         * From the `topLevelActionConflictReport`, report the action conflict errors.
         * 
         * 
         * Throw a ViewCreationFailedException in case of --nokeep_going.
         */
        @Throws(ViewCreationFailedException::class, java.lang.InterruptedException::class)
        private fun reportActionConflictErrors(
            topLevelActionConflictReport: TopLevelActionConflictReport,
            graph: WalkableGraph,
            effectiveTopLevelKeysForConflictReporting: Iterable<ActionLookupKey?>,
            actionConflicts: com.google.common.collect.ImmutableMap<ActionAnalysisMetadata?, ActionConflictException?>,
            eventHandler: ExtendedEventHandler,
            eventBus: com.google.common.eventbus.EventBus,
            keepGoing: Boolean
        ) {
            // ArtifactPrefixConflictExceptions come in pairs, and only one should be reported.
            val reportedActionConflictExceptions: MutableSet<String?> =
                com.google.common.collect.Sets.newHashSet<String?>()

            // Sometimes a conflicting action can't be traced to a top level target via
            // TopLevelActionConflictReport. We therefore need to print the errors from the conflicts
            // themselves. See SkyframeIntegrationTest#topLevelAspectsAndExtraActionsWithConflict.
            for (e in actionConflicts.values()) {
                if (reportedActionConflictExceptions.add(e.getMessage())) {
                    e.reportTo(eventHandler)
                    if (keepGoing) {
                        eventHandler.handle(
                            com.google.devtools.build.lib.events.Event.warn(
                                java.lang.String.format(
                                    "errors encountered while analyzing target '%s': it will not be built",
                                    e.getArtifact().getOwnerLabel()
                                )
                            )
                        )
                    }
                }
            }
            // Report an AnalysisFailureEvent to BEP for the top-level targets with discoverable action
            // conflicts, then finally throw.
            for (actionLookupKey in effectiveTopLevelKeysForConflictReporting) {
                var actionLookupKey: ActionLookupKey? = actionLookupKey
                if (topLevelActionConflictReport.isErrorFree(actionLookupKey)) {
                    continue
                }
                val e: java.util.Optional<ActionConflictException> =
                    topLevelActionConflictReport.getConflictException(actionLookupKey)
                if (e.isEmpty()) {
                    continue
                }

                val conflictException: ActionConflictException = e.get()
                val failedCause: AnalysisFailedCause = makeArtifactConflictAnalysisFailedCause(conflictException)
                var targetConfigured = true
                // Attempt to promote any ConfiguredTargetKey to the one embedded in the ConfiguredTarget to
                // reflect any transitions or trimming.
                if (actionLookupKey is ConfiguredTargetKey) {
                    // This is a graph lookup instead of an EvaluationResult lookup because Skymeld's
                    // EvaluationResult does not contain ConfiguredTargetKey.
                    val value: ConfiguredTargetValue? = (graph.getValue(actionLookupKey) as ConfiguredTargetValue?)
                    if (value == null) {
                        targetConfigured = false
                    } else if (value.getConfiguredTarget() != null) {
                        // It's possible that the ConfiguredTarget has been cleared.
                        actionLookupKey = value.getConfiguredTarget().getLookupKey()
                    }
                }
                if (!targetConfigured) {
                    eventBus.post(
                        AnalysisFailureEvent.whileAnalyzingTarget(
                            actionLookupKey as ConfiguredTargetKey?,
                            NestedSetBuilder.create(Order.STABLE_ORDER, failedCause)
                        )
                    )
                } else {
                    eventBus.post(
                        AnalysisFailureEvent.actionConflict(
                            actionLookupKey, NestedSetBuilder.create(Order.STABLE_ORDER, failedCause)
                        )
                    )
                }

                if (!keepGoing) {
                    throw ViewCreationFailedException(
                        failedCause.getDetailedExitCode().getFailureDetail(), conflictException
                    )
                }
            }
        }

        private fun getExclusiveTests(
            evaluationResult: EvaluationResult<SkyValue>
        ): com.google.common.collect.ImmutableSet<ConfiguredTarget?> {
            val exclusiveTests: com.google.common.collect.ImmutableSet.Builder<ConfiguredTarget?> =
                com.google.common.collect.ImmutableSet.builder<ConfiguredTarget?>()
            for (value in evaluationResult.values()) {
                if (value is ExclusiveTestBuildDriverValue) {
                    exclusiveTests.add(
                        (value as ExclusiveTestBuildDriverValue).getExclusiveTestConfiguredTarget()
                    )
                }
            }
            return exclusiveTests.build()
        }

        private fun determineTestTypeImpl(
            testsToRun: com.google.common.collect.ImmutableSet<Label?>?,
            labelTargetMap: com.google.common.collect.ImmutableMap<Label?, Target?>,
            label: Label?,
            buildDriverKeyTestContext: BuildDriverKeyTestContext,
            eventHandler: ExtendedEventHandler
        ): TestType {
            if (testsToRun == null || !testsToRun.contains(label)) {
                return TestType.NOT_TEST
            }
            val target: Target? = labelTargetMap.get(label)

            if (target !is Rule) {
                return TestType.NOT_TEST
            }

            val fromExplicitFlagOrTag: TestType
            if (buildDriverKeyTestContext.testStrategy == "exclusive"
                || TargetUtils.isExclusiveTestRule(target)
                || (TargetUtils.isExclusiveIfLocalTestRule(target) && TargetUtils.isLocalTestRule(target))
            ) {
                fromExplicitFlagOrTag = TestType.EXCLUSIVE
            } else if (TargetUtils.isExclusiveIfLocalTestRule(target)) {
                fromExplicitFlagOrTag = TestType.EXCLUSIVE_IF_LOCAL
            } else {
                fromExplicitFlagOrTag = TestType.PARALLEL
            }

            if ((fromExplicitFlagOrTag === TestType.EXCLUSIVE
                        && buildDriverKeyTestContext.forceExclusiveTestsInParallel())
                || (fromExplicitFlagOrTag === TestType.EXCLUSIVE_IF_LOCAL
                        && buildDriverKeyTestContext.forceExclusiveIfLocalTestsInParallel())
            ) {
                eventHandler.handle(
                    com.google.devtools.build.lib.events.Event.warn(
                        (label
                            .toString() + " is tagged "
                                + fromExplicitFlagOrTag.msg
                                + ", but --test_strategy="
                                + buildDriverKeyTestContext.testStrategy
                                + " forces parallel test execution.")
                    )
                )
                return TestType.PARALLEL
            }
            return fromExplicitFlagOrTag
        }

        @Throws(java.lang.InterruptedException::class)
        private fun getSuccessfulConfiguredTargets(
            expectedSize: Int,
            evaluationResult: EvaluationResult<SkyValue>,
            buildDriverCTKeys: MutableSet<BuildDriverKey>,
            labelToTargetMap: com.google.common.collect.ImmutableMap<Label?, Target?>,
            targetsWithConfiguration: com.google.common.collect.ImmutableList.Builder<TargetAndConfiguration?>,
            topLevelActionConflictReport: TopLevelActionConflictReport?
        ): com.google.common.collect.ImmutableSet<ConfiguredTarget?> {
            val cts: com.google.common.collect.ImmutableSet.Builder<ConfiguredTarget?> =
                com.google.common.collect.ImmutableSet.builderWithExpectedSize<ConfiguredTarget?>(expectedSize)
            for (bdCTKey in buildDriverCTKeys) {
                if (topLevelActionConflictReport != null
                    && !topLevelActionConflictReport.isErrorFree(bdCTKey.getActionLookupKey())
                ) {
                    continue
                }
                val value: BuildDriverValue? = evaluationResult.get(bdCTKey) as BuildDriverValue?
                if (value == null) {
                    continue
                }
                val ctValue: ConfiguredTargetValue = value.getWrappedSkyValue() as ConfiguredTargetValue
                cts.add(ctValue.getConfiguredTarget())

                val configurationKey: BuildConfigurationKey? = ctValue.getConfiguredTarget().getConfigurationKey()
                val configuration: BuildConfigurationValue? =
                    if (configurationKey == null)
                        null
                    else
                        evaluationResult.getWalkableGraph().getValue(configurationKey) as BuildConfigurationValue?
                targetsWithConfiguration.add(
                    TargetAndConfiguration(
                        labelToTargetMap.get(bdCTKey.getActionLookupKey().getLabel()), configuration
                    )
                )
            }
            return cts.build()
        }

        private fun getSuccessfulAspectMap(
            expectedSize: Int,
            evaluationResult: EvaluationResult<SkyValue>,
            buildDriverAspectKeys: MutableSet<BuildDriverKey>,
            topLevelActionConflictReport: TopLevelActionConflictReport?
        ): com.google.common.collect.ImmutableMap<AspectKey?, ConfiguredAspect?> {
            // There can't be duplicate Aspects after resolving --aspects, so this is safe.
            val aspects: com.google.common.collect.ImmutableMap.Builder<AspectKey?, ConfiguredAspect?> =
                com.google.common.collect.ImmutableMap.builderWithExpectedSize<AspectKey?, ConfiguredAspect?>(
                    expectedSize
                )
            for (bdAspectKey in buildDriverAspectKeys) {
                if (topLevelActionConflictReport != null
                    && !topLevelActionConflictReport.isErrorFree(bdAspectKey.getActionLookupKey())
                ) {
                    continue
                }
                val value: BuildDriverValue? = evaluationResult.get(bdAspectKey) as BuildDriverValue?
                if (value == null) {
                    // Skip aspects that couldn't be applied to targets.
                    continue
                }
                val topLevelAspectsValue: TopLevelAspectsValue = value.getWrappedSkyValue() as TopLevelAspectsValue
                aspects.putAll(topLevelAspectsValue.getTopLevelAspectsMap())
            }
            return aspects.buildOrThrow()
        }

        private fun makeArtifactConflictAnalysisFailedCause(
            ace: ActionConflictException
        ): AnalysisFailedCause {
            val detailedExitCode: DetailedExitCode = ace.getDetailedExitCode()
            val causeLabel: Label = ace.getArtifact().getArtifactOwner().getLabel()
            var causeConfigKey: BuildConfigurationKey? = null
            if (ace.getArtifact().getArtifactOwner() is ConfiguredTargetKey) {
                causeConfigKey =
                    (ace.getArtifact().getArtifactOwner() as ConfiguredTargetKey).getConfigurationKey()
            }
            return AnalysisFailedCause(
                causeLabel, configurationIdMessage(causeConfigKey), detailedExitCode
            )
        }
    }
}
