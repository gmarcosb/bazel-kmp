// Copyright 2018 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.analysis.util

import com.google.common.base.Preconditions
import com.google.common.collect.*
import com.google.common.eventbus.EventBus
import com.google.devtools.build.lib.actions.ActionLookupKey
import com.google.devtools.build.lib.events.Event
import java.util.*
import java.util.function.Function

/**
 * A util class that contains all the helper stuff previously in BuildView that only exists to give
 * tests access to Skyframe internals. The code largely predates the introduction of Skyframe, and
 * mostly exists to avoid having to rewrite our tests to work with Skyframe natively.
 */
class BuildViewForTesting(
    directories: BlazeDirectories?,
    ruleClassProvider: ConfiguredRuleClassProvider?,
    skyframeExecutor: SkyframeExecutor?,
    coverageReportActionFactory: CoverageReportActionFactory?
) {
    private val buildView: BuildView
    private val skyframeExecutor: SkyframeExecutor
    private val skyframeBuildView: SkyframeBuildView

    private val ruleClassProvider: ConfiguredRuleClassProvider?

    private var currentActionLookupKeys: ImmutableMap<ActionLookupKey?, Version?> =
        ImmutableMap.of<ActionLookupKey?, Version?>()

    /**
     * Tracks keys that mismatched at a previous diff computation.
     * 
     * 
     * [.populateActionLookupKeyMapAndGetDiff] scans the entire graph and computes a diff
     * against the previous [.currentActionLookupKeys] value. For this to be consistent with
     * [SkyframeExecutor.getEvaluatedCounts] it needs to filter out [ ][SkyFunctions.CONFIGURED_TARGET] nodes that do not own the underlying [ ]s. The owners have [ConfiguredTargetKey.getConfigurationKey] values
     * matching the [ConfiguredTarget.getConfigurationKey] values.
     * 
     * 
     * The problem is that the Skyframe graph may contain entries that are not done at the time of
     * graph inspection. This may occur when there's an incremental evaluation that doesn't require a
     * previously computed value.
     * 
     * 
     * If the [ConfiguredTargetValue] is unavailable and can't be compared, the diff still
     * needs to decide whether to skip it. If it was skipped previously, it needs to be skipped again.
     * Otherwise it'll show up as a newly evaluated node.
     */
    private var previousProxyNodeKeys: ImmutableSet<ConfiguredTargetKey?> = ImmutableSet.of<ConfiguredTargetKey?>()

    init {
        this.buildView =
            BuildView(
                directories, ruleClassProvider, skyframeExecutor, coverageReportActionFactory
            )
        this.ruleClassProvider = ruleClassProvider
        this.skyframeExecutor = Preconditions.checkNotNull<SkyframeExecutor>(skyframeExecutor)
        this.skyframeBuildView = skyframeExecutor.getSkyframeBuildView()
    }

    val skyframeEvaluatedActionLookupKeyCountForTesting: MutableSet<ActionLookupKey>
        get() {
            val actionLookupKeys: MutableSet<ActionLookupKey?> =
                populateActionLookupKeyMapAndGetDiff()
            Preconditions.checkState(
                actionLookupKeys.size == skyframeBuildView.getEvaluatedCounts().total(),
                "Number of newly evaluated action lookup values %s does not agree with number that changed"
                        + " in graph: %s. Keys: %s",
                actionLookupKeys.size,
                skyframeBuildView.getEvaluatedCounts().total(),
                actionLookupKeys
            )
            return actionLookupKeys
        }

    private fun populateActionLookupKeyMapAndGetDiff(): MutableSet<ActionLookupKey?> {
        val graph: InMemoryGraph = skyframeExecutor.getEvaluator().getInMemoryGraph()
        val proxyNodeKeys: ImmutableSet.Builder<ConfiguredTargetKey?> = ImmutableSet.builder<ConfiguredTargetKey?>()
        val newMap: ImmutableMap<ActionLookupKey?, Version?> =
            graph.allNodeEntries.stream()
                .filter(
                    { entry ->
                        val key: SkyKey = entry.key
                        if (key !is ActionLookupKey) {
                            return@filter false
                        }
                        if (!key.functionName().equals(SkyFunctions.CONFIGURED_TARGET)) {
                            return@filter true
                        }

                        val ctKey: ConfiguredTargetKey = key as ConfiguredTargetKey

                        if (!entry.isDone()) {
                            if (previousProxyNodeKeys.contains(ctKey)) {
                                // The node is dirty and was a proxy previously. Filters the entry as long as
                                // it remains not done.
                                proxyNodeKeys.add(ctKey)
                                return@filter false
                            }
                            return@filter true
                        }

                        val value: ConfiguredTargetValue? = entry.value as ConfiguredTargetValue?
                        if (value == null) {
                            // The node has an error. No filtering is applied in this case.
                            return@filter true
                        }
                        if (ctKey.getConfigurationKey() != value.getConfiguredTarget().getConfigurationKey()) {
                            // The configurations are not equal so the node is only performing delegation
                            // and doesn't own the configured target.
                            proxyNodeKeys.add(ctKey)
                            return@filter false
                        }
                        true
                    })
                .collect(
                    ImmutableMap.toImmutableMap<T?, K?, V?>(
                        Function { e: T? -> e.key as ActionLookupKey? },
                        NodeEntry::getVersion
                    )
                )
        previousProxyNodeKeys = proxyNodeKeys.build()
        val difference: MapDifference<ActionLookupKey?, Version?> =
            Maps.difference<ActionLookupKey?, Version?>(newMap, currentActionLookupKeys)
        currentActionLookupKeys = newMap
        return Sets.union<ActionLookupKey?>(
            difference.entriesDiffering().keys, difference.entriesOnlyOnLeft().keys
        )
    }

    /** Returns whether the given configured target has errors.  */
    fun hasErrors(configuredTarget: ConfiguredTarget?): Boolean {
        return configuredTarget == null
    }

    @ThreadCompatible
    @Throws(
        ViewCreationFailedException::class,
        InterruptedException::class,
        InvalidConfigurationException::class,
        BuildFailedException::class,
        TestExecException::class,
        AbruptExitException::class
    )
    fun update(
        loadingResult: TargetPatternPhaseValue?,
        targetOptions: BuildOptions?,
        explicitTargetPatterns: ImmutableSet<Label?>?,
        aspects: MutableList<String?>?,
        aspectsParameters: ImmutableMap<String?, String?>?,
        viewOptions: AnalysisOptions?,
        keepGoing: Boolean,
        loadingPhaseThreads: Int,
        topLevelOptions: TopLevelArtifactContext?,
        eventHandler: ExtendedEventHandler?,
        eventBus: EventBus?
    ): AnalysisResult {
        populateActionLookupKeyMapAndGetDiff()
        return buildView.update(
            loadingResult,
            targetOptions,
            explicitTargetPatterns,
            aspects,
            aspectsParameters,
            viewOptions,
            keepGoing,  /* skipIncompatibleExplicitTargets= */
            false,  /* checkForActionConflicts= */
            true,
            QuiescingExecutorsImpl.forTesting(),
            topLevelOptions,  /* reportIncompatibleTargets= */
            true,
            eventHandler,
            eventBus,
            BugReporter.defaultInstance(),  /* includeExecutionPhase= */
            false,  /* skymeldAnalysisOverlapPercentage= */
            0,  /* resourceManager= */
            null,  /* buildResultListener= */
            null,  /* executionSetupCallback= */
            null,  /* buildConfigurationsCreatedCallback= */
            null,  /* buildDriverKeyTestContext= */
            null,  /* additionalConfigurationChangeEvent= */
            Optional.empty<T?>(),  /* remoteAnalysisCachingDependenciesProvider= */
            RemoteAnalysisCacheManager
                .createDisabled(),
            RemoteAnalysisCacheDeps.createDisabled()
        )
    }

    /** Sets the configuration. Not thread-safe.  */
    fun setConfigurationForTesting(configuration: BuildConfigurationValue) {
        skyframeBuildView.setConfiguration(configuration, configuration.getOptions(), true)
    }

    val artifactFactory: ArtifactFactory
        get() = skyframeBuildView.getArtifactFactory()

    @Throws(
        InterruptedException::class,
        DependencyResolutionHelpers.Failure::class,
        InvalidConfigurationException::class,
        InconsistentAspectOrderException::class,
        StarlarkTransition.TransitionException::class
    )
    fun getDirectPrerequisitesForTesting(
        eventHandler: ExtendedEventHandler, ct: ConfiguredTarget
    ): MutableCollection<ConfiguredTarget?> {
        return Collections2.transform<ConfiguredTargetAndData?, ConfiguredTarget?>(
            getConfiguredTargetAndDataDirectPrerequisitesForTesting(eventHandler, ct),
            ConfiguredTargetAndData::getConfiguredTarget
        )
    }

    @Throws(
        InterruptedException::class,
        DependencyResolutionHelpers.Failure::class,
        InvalidConfigurationException::class,
        InconsistentAspectOrderException::class,
        StarlarkTransition.TransitionException::class
    )
    fun getConfiguredTargetAndDataDirectPrerequisitesForTesting(
        eventHandler: ExtendedEventHandler, configuredTarget: ConfiguredTarget
    ): MutableCollection<ConfiguredTargetAndData?> {
        val state: DependencyResolver.State =
            initializeDependencyResolverState(eventHandler, configuredTarget)
        val producer: DependencyResolver = runDependencyResolver(eventHandler, configuredTarget, state)
        return producer.getDepValueMap().values()
    }

    /**
     * Returns a configured target for the specified target and configuration. If the target in
     * question has a top-level rule class transition, that transition is applied in the returned
     * ConfiguredTarget.
     * 
     * 
     * Returns `null` if something goes wrong.
     */
    @Throws(InvalidConfigurationException::class, InterruptedException::class)
    fun getConfiguredTargetForTesting(
        eventHandler: ExtendedEventHandler?, label: Label?, config: BuildConfigurationValue?
    ): ConfiguredTarget {
        return skyframeExecutor.getConfiguredTargetForTesting(eventHandler, label, config)
    }

    @Throws(InvalidConfigurationException::class, InterruptedException::class)
    fun getConfiguredTargetAndDataForTesting(
        eventHandler: ExtendedEventHandler?, label: Label?, config: BuildConfigurationValue?
    ): ConfiguredTargetAndData {
        return skyframeExecutor.getConfiguredTargetAndDataForTesting(eventHandler, label, config)
    }

    /**
     * Returns a RuleContext which is the same as the original RuleContext of the target parameter.
     */
    @Throws(
        DependencyResolutionHelpers.Failure::class,
        InvalidConfigurationException::class,
        InterruptedException::class,
        InconsistentAspectOrderException::class,
        ToolchainException::class,
        StarlarkTransition.TransitionException::class,
        InvalidExecGroupException::class
    )
    fun getRuleContextForTesting(
        target: ConfiguredTarget, eventHandler: StoredEventHandler
    ): RuleContext {
        val targetConfig: BuildConfigurationValue =
            skyframeExecutor.getConfiguration(eventHandler, target.getConfigurationKey())
        val skyframeEnv: SkyFunction.Environment =
            SkyFunctionEnvironmentForTesting(eventHandler, skyframeExecutor)
        val starlarkBuiltinsValue: StarlarkBuiltinsValue =
            Preconditions.checkNotNull<T?>(skyframeEnv.getValue(StarlarkBuiltinsValue.key())) as StarlarkBuiltinsValue
        val analysisEnv: CachingAnalysisEnvironment =
            CachingAnalysisEnvironment(
                this.artifactFactory,
                skyframeExecutor.getActionKeyContext(),
                ConfiguredTargetKey.builder()
                    .setLabel(target.getLabel())
                    .setConfiguration(targetConfig)
                    .build(),
                targetConfig.extendedSanityChecks(),
                targetConfig.allowAnalysisFailures(),
                eventHandler,
                skyframeEnv,
                starlarkBuiltinsValue
            )
        return getRuleContextForTesting(eventHandler, target, analysisEnv)
    }

    /**
     * Creates and returns a rule context that is equivalent to the one that was used to create the
     * given configured target.
     */
    @Throws(
        DependencyResolutionHelpers.Failure::class,
        InvalidConfigurationException::class,
        InterruptedException::class,
        InconsistentAspectOrderException::class,
        ToolchainException::class,
        StarlarkTransition.TransitionException::class,
        InvalidExecGroupException::class
    )
    fun getRuleContextForTesting(
        eventHandler: ExtendedEventHandler, configuredTarget: ConfiguredTarget, env: AnalysisEnvironment?
    ): RuleContext {
        val state: DependencyResolver.State =
            initializeDependencyResolverState(eventHandler, configuredTarget)
        val producer: DependencyResolver = runDependencyResolver(eventHandler, configuredTarget, state)

        val prerequisiteMap: OrderedSetMultimap<DependencyKind?, ConfiguredTargetAndData?> =
            producer.getDepValueMap()

        val target: Target = state.targetAndConfiguration.getTarget()
        val targetDescription: String? = target.toString()

        val unloadedToolchainCollection: ToolchainCollection<UnloadedToolchainContext?> =
            producer.getUnloadedToolchainContexts()

        val resolvedToolchainContext: ToolchainCollection.Builder<ResolvedToolchainContext?> =
            ToolchainCollection.builder()
        for (unloadedToolchainContext in unloadedToolchainCollection.contextMap().entrySet()) {
            val toolchainContext: ResolvedToolchainContext? =
                ResolvedToolchainContext.load(
                    unloadedToolchainContext.value,
                    targetDescription,
                    ImmutableSet.copyOf(
                        prerequisiteMap.get(
                            DependencyKind.forExecGroup(unloadedToolchainContext.key)
                        )
                    )
                )
            resolvedToolchainContext.addContext(unloadedToolchainContext.key, toolchainContext)
        }

        return Builder(
            env,
            target,  /* aspects= */
            ImmutableList.of<E?>(),
            state.targetAndConfiguration.getConfiguration()
        )
            .setRuleClassProvider(ruleClassProvider)
            .setConfigurationFragmentPolicy(
                target.getAssociatedRule().getRuleClassObject().getConfigurationFragmentPolicy()
            )
            .setActionOwnerSymbol(ConfiguredTargetKey.fromConfiguredTarget(configuredTarget))
            .setMutability(Mutability.create("configured target"))
            .setVisibility(VisibilityProvider.PUBLIC_VISIBILITY)
            .setPrerequisites(ConfiguredTargetFactory.removeToolchainDeps(prerequisiteMap))
            .setConfigConditions(ConfigConditions.EMPTY)
            .setToolchainContexts(resolvedToolchainContext.build())
            .setExecGroupCollectionBuilder(state.execGroupCollectionBuilder)
            .unsafeBuild()
    }

    @Throws(InterruptedException::class)
    private fun runDependencyResolver(
        eventHandler: ExtendedEventHandler?,
        configuredTarget: ConfiguredTarget?,
        state: DependencyResolver.State
    ): DependencyResolver {
        val producer: DependencyResolver = DependencyResolver(state.targetAndConfiguration)
        try {
            check(
                producer.evaluate(
                    state,
                    ConfiguredTargetKey.fromConfiguredTarget(configuredTarget),
                    ruleClassProvider,
                    skyframeBuildView.getStarlarkTransitionCache(),  /* semaphoreLocker= */
                    {},
                    SkyFunctionEnvironmentForTesting(eventHandler, skyframeExecutor),
                    eventHandler
                )
            ) { configuredTarget.toString() + " should be already evaluated" }
        } catch (e: ReportedException) {
            throw IllegalStateException(e) // Should not be possible for done ConfiguredTarget.
        } catch (e: UnreportedException) {
            throw IllegalStateException(e)
        } catch (e: IncompatibleTargetException) {
            throw IllegalStateException(e)
        }
        return producer
    }

    @Throws(InterruptedException::class)
    private fun initializeDependencyResolverState(
        eventHandler: ExtendedEventHandler, configuredTarget: ConfiguredTarget
    ): DependencyResolver.State {
        // In production, the TargetAndConfiguration value is based on final configuration of the
        // ConfiguredTarget after any rule transition is applied.
        val configuration: BuildConfigurationValue? =
            skyframeExecutor.getConfiguration(eventHandler, configuredTarget.getConfigurationKey())
        val target: Target
        try {
            target =
                skyframeExecutor.getPackageManager().getTarget(eventHandler, configuredTarget.getLabel())
        } catch (e: NoSuchPackageException) {
            eventHandler.handle(
                Event.error("Failed to get target when trying to get rule context for testing")
            )
            throw IllegalStateException(e)
        } catch (e: NoSuchTargetException) {
            eventHandler.handle(
                Event.error("Failed to get target when trying to get rule context for testing")
            )
            throw IllegalStateException(e)
        }
        return DependencyResolver.State.createForTesting(
            TargetAndConfiguration(target.getAssociatedRule(), configuration)
        )
    }

    /** Clears the analysis cache as in --discard_analysis_cache.  */
    fun clearAnalysisCache(
        topLevelTargets: ImmutableSet<ConfiguredTarget?>?, topLevelAspects: ImmutableSet<AspectKey?>?
    ) {
        skyframeBuildView.clearAnalysisCache(topLevelTargets, topLevelAspects)
    }
}
