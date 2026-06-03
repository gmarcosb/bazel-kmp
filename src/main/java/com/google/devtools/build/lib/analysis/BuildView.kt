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

import com.google.devtools.build.lib.analysis.BuildView.Companion.createAnalysisFailureDetail
import com.google.devtools.build.lib.skyframe.BzlLoadValue.keyForBuild

/**
 * The BuildView presents a semantically-consistent and transitively-closed dependency graph for
 * some set of packages.
 * 
 * <h2>Package design</h2>
 * 
 * 
 * This package contains the Blaze dependency analysis framework (aka "analysis phase"). The goal
 * of this code is to perform semantic analysis of all of the build targets required for a given
 * build, to report errors/warnings for any problems in the input, and to construct an "action
 * graph" (see `lib.actions` package) correctly representing the work to be done during the
 * execution phase of the build.
 * 
 * 
 * **Configurations** the inputs to a build come from two sources: the intrinsic inputs,
 * specified in the BUILD file, are called *targets*. The environmental inputs, coming from
 * the build tool, the command-line, or configuration files, are called the *configuration*.
 * Only when a target and a configuration are combined is there sufficient information to perform a
 * build.
 * 
 * 
 * Targets are implemented by the [Target] hierarchy in the `lib.packages` code.
 * Configurations are implemented by [BuildConfigurationValue]. The pair of these together is
 * represented by an instance of class [ConfiguredTarget]; this is the root of a hierarchy
 * with different implementations for each kind of target: source file, derived file, rules, etc.
 * 
 * 
 * The framework code in this package (as opposed to its subpackages) is responsible for
 * constructing the `ConfiguredTarget` graph for a given target and configuration, taking care
 * of such issues as:
 * 
 * 
 *  * caching common subgraphs.
 *  * detecting and reporting cycles.
 *  * correct propagation of errors through the graph.
 *  * reporting universal errors, such as dependencies from production code to tests, or to
 * experimental branches.
 *  * capturing and replaying errors.
 *  * maintaining the graph from one build to the next to avoid unnecessary recomputation.
 *  * checking software licenses.
 * 
 * 
 * 
 * See also [ConfiguredTarget] which documents some important invariants.
 * 
 * 
 * Lifespan: 1 invocation.
 */
class BuildView(
    directories: BlazeDirectories?,
    ruleClassProvider: ConfiguredRuleClassProvider,
    skyframeExecutor: SkyframeExecutor?,
    coverageReportActionFactory: CoverageReportActionFactory?
) {
    private val directories: BlazeDirectories?

    private val skyframeExecutor: SkyframeExecutor
    private val skyframeBuildView: SkyframeBuildView

    private val ruleClassProvider: ConfiguredRuleClassProvider

    private var memoizedCoverageArtifacts: com.google.common.collect.ImmutableSet<Artifact?>? = null

    /** A factory class to create the coverage report action. May be null.  */
    private val coverageReportActionFactory: CoverageReportActionFactory?

    init {
        this.directories = directories
        this.coverageReportActionFactory = coverageReportActionFactory
        this.ruleClassProvider = ruleClassProvider
        this.skyframeExecutor = com.google.common.base.Preconditions.checkNotNull<SkyframeExecutor>(skyframeExecutor)
        this.skyframeBuildView = skyframeExecutor.getSkyframeBuildView()
    }

    /** Returns the number of analyzed targets/aspects.  */
    fun getEvaluatedCounts(): TotalAndConfiguredTargetOnlyMetric {
        return skyframeBuildView.getEvaluatedCounts()
    }

    fun getEvaluatedActionsCounts(): TotalAndConfiguredTargetOnlyMetric {
        return skyframeBuildView.getEvaluatedActionCounts()
    }

    fun getEvaluatedActionsCountsByMnemonic(): com.google.common.collect.ImmutableMap<String?, Int?> {
        return skyframeBuildView.getEvaluatedActionCountsByMnemonic()
    }

    fun getAndClearPkgManagerStatistics(): PackageManagerStatistics {
        return skyframeExecutor.getPackageManager().getAndClearStatistics()
    }

    private fun getArtifactFactory(): ArtifactFactory {
        return skyframeBuildView.getArtifactFactory()
    }

    @ThreadCompatible
    @Throws(
        ViewCreationFailedException::class,
        InvalidConfigurationException::class,
        java.lang.InterruptedException::class,
        BuildFailedException::class,
        TestExecException::class,
        AbruptExitException::class
    )
    fun update(
        loadingResult: TargetPatternPhaseValue,
        targetOptions: BuildOptions?,
        explicitTargetPatterns: com.google.common.collect.ImmutableSet<Label?>?,
        aspects: MutableList<String>,
        aspectsParameters: com.google.common.collect.ImmutableMap<String?, String?>?,
        viewOptions: AnalysisOptions,
        keepGoing: Boolean,
        skipIncompatibleExplicitTargets: Boolean,
        checkForActionConflicts: Boolean,
        executors: QuiescingExecutors?,
        topLevelOptions: TopLevelArtifactContext,
        reportIncompatibleTargets: Boolean,
        eventHandler: ExtendedEventHandler,
        eventBus: com.google.common.eventbus.EventBus,
        bugReporter: BugReporter?,
        includeExecutionPhase: Boolean,
        skymeldAnalysisOverlapPercentage: Int,
        resourceManager: com.google.devtools.build.lib.actions.ResourceManager?,
        buildResultListener: BuildResultListener?,
        executionSetupCallback: ExecutionSetup?,
        buildConfigurationsCreatedCallback: BuildConfigurationsCreated?,
        buildDriverKeyTestContext: BuildDriverKeyTestContext?,
        additionalConfigurationChangeEvent: java.util.Optional<AdditionalConfigurationChangeEvent?>?,
        remoteAnalysisCachingDependenciesProvider: RemoteAnalysisCachingDependenciesProvider,
        remoteAnalysisCacheReaderDeps: RemoteAnalysisCacheReaderDepsProvider?
    ): com.google.devtools.build.lib.analysis.AnalysisResult {
        var remoteAnalysisCachingDependenciesProvider: RemoteAnalysisCachingDependenciesProvider =
            remoteAnalysisCachingDependenciesProvider
        var remoteAnalysisCacheReaderDeps: RemoteAnalysisCacheReaderDepsProvider? = remoteAnalysisCacheReaderDeps
        logger.atInfo().log("Starting analysis")
        pollInterruptedStatus()

        skyframeBuildView.resetProgressReceiver()

        val labelToTargetMap: com.google.common.collect.ImmutableMap<Label?, Target?> =
            constructLabelToTargetMap(loadingResult)
        eventBus.post(AnalysisPhaseStartedEvent(labelToTargetMap.values()))

        // Prepare the analysis phase
        val topLevelConfig: BuildConfigurationValue
        val topLevelConfigurationTrimmedOfTestOptions: BuildOptions?
        val shouldDiscardAnalysisCache: Boolean
        if (skyframeExecutor.getAndIncrementAnalysisCount() !== 0
            && remoteAnalysisCachingDependenciesProvider.mode() === RemoteAnalysisCacheMode.UPLOAD
        ) {
            throw AbruptExitException(
                DetailedExitCode.of(
                    FailureDetail.newBuilder()
                        .setMessage(UPLOAD_BUILDS_MUST_BE_COLD)
                        .setSkyfocus(
                            Skyfocus.newBuilder().setCode(Skyfocus.Code.CONFIGURATION_CHANGE).build()
                        )
                        .build()
                )
            )
        }
        Profiler.instance().profile("createConfigurations").use { c ->
            shouldDiscardAnalysisCache =
                skyframeBuildView.shouldDiscardAnalysisCache(
                    eventHandler,
                    targetOptions,
                    viewOptions.getMaxConfigChangesToShow(),
                    viewOptions.getAllowAnalysisCacheDiscards(),
                    additionalConfigurationChangeEvent
                )
            skyframeExecutor.setBaselineConfiguration(targetOptions, eventHandler)
            topLevelConfig = skyframeExecutor.createConfiguration(eventHandler, targetOptions, keepGoing)
        }
        if (remoteAnalysisCachingDependenciesProvider.mode() === RemoteAnalysisCacheMode.DOWNLOAD) {
            Profiler.instance().profile("skycache.metadataQuery").use { c ->
                remoteAnalysisCachingDependenciesProvider.queryMetadataAndMaybeBailout()
            }
            if (remoteAnalysisCachingDependenciesProvider.mode() !== RemoteAnalysisCacheMode.OFF
                && remoteAnalysisCachingDependenciesProvider.bailedOut()
            ) {
                remoteAnalysisCachingDependenciesProvider = RemoteAnalysisCacheManager.createDisabled()
                remoteAnalysisCacheReaderDeps = RemoteAnalysisCacheDeps.createDisabled()
            } else {
                eventBus.post(RemoteAnalysisCachingEnabledEvent())
            }
        }

        val skyfocusState: SkyfocusState = skyframeExecutor.getSkyfocusState()
        if (skyfocusState.enabled()) {
            val buildConfigChanged =
                skyfocusState.buildConfiguration() != null
                        && !skyfocusState.buildConfiguration().equals(topLevelConfig)
            if (buildConfigChanged) {
                when (skyfocusState.options().getFrontierViolationCheck()) {
                    WARN -> {
                        eventHandler.handle(
                            com.google.devtools.build.lib.events.Event.warn(
                                "Skyfocus: detected changes to the build configuration, will be discarding"
                                        + " the analysis cache."
                            )
                        )
                    }

                    STRICT -> throw AbruptExitException(
                        DetailedExitCode.of(
                            FailureDetail.newBuilder()
                                .setMessage(
                                    ("Skyfocus: detected changes to the build configuration. This is not"
                                            + " allowed in a focused build. Either clean to reset the"
                                            + " build, or set"
                                            + " --experimental_frontier_violation_check=warn to perform a"
                                            + " full reanalysis instead of failing the build.")
                                )
                                .setSkyfocus(
                                    Skyfocus.newBuilder()
                                        .setCode(Skyfocus.Code.CONFIGURATION_CHANGE)
                                        .build()
                                )
                                .build()
                        )
                    )

                    DISABLED_FOR_TESTING -> throw java.lang.IllegalStateException("disallowed; not in test.")
                }
            }

            skyframeExecutor.setSkyfocusState(
                skyfocusState.toBuilder()
                    .buildConfiguration(topLevelConfig)
                    .forcedRerun(buildConfigChanged)
                    .build()
            )
        }

        topLevelConfigurationTrimmedOfTestOptions =
            getTopLevelConfigurationTrimmedOfTestOptions(topLevelConfig.getOptions(), eventHandler)
        eventBus.post(
            TopLevelConfigRequestedEvent(
                topLevelConfig, topLevelConfigurationTrimmedOfTestOptions
            )
        )

        if (buildConfigurationsCreatedCallback != null) {
            buildConfigurationsCreatedCallback.run(topLevelConfig)
        }


        skyframeBuildView.setConfiguration(topLevelConfig, targetOptions, shouldDiscardAnalysisCache)

        eventBus.post(MakeEnvironmentEvent(topLevelConfig.getMakeEnvironment()))
        eventBus.post(topLevelConfig.toBuildEvent())

        // Lightly chastize the user for disabling visibility checking. (Previously, we spammed them for
        // every visibility failure; #16767.)
        if (!topLevelConfig.checkVisibility()) {
            eventHandler.handle(
                com.google.devtools.build.lib.events.Event.warn(
                    "This build has globally disabled target visibility checking"
                            + " (--nocheck_visibility)."
                )
            )
        }

        val configurationKey: BuildConfigurationKey? = topLevelConfig.getKey()
        val topLevelCtKeys: com.google.common.collect.ImmutableList<ConfiguredTargetKey?> =
            labelToTargetMap.keySet().stream()
                .map<Any?>(
                    java.util.function.Function { label: Label? ->
                        ConfiguredTargetKey.builder()
                            .setLabel(label)
                            .setConfigurationKey(configurationKey)
                            .build()
                    })
                .collect(com.google.common.collect.ImmutableList.toImmutableList<Any?>())

        val aspectKeys: com.google.common.collect.ImmutableList<TopLevelAspectsKey?> =
            createTopLevelAspectKeys(
                aspects, aspectsParameters, labelToTargetMap, topLevelConfig, eventHandler
            )

        skyframeExecutor.setRemoteAnalysisCachingDependenciesProvider(
            remoteAnalysisCachingDependenciesProvider, remoteAnalysisCacheReaderDeps
        )
        skyframeExecutor.invalidateWithExternalService(eventHandler)

        getArtifactFactory().noteAnalysisStarting()
        var skyframeAnalysisResult: SkyframeAnalysisResult
        try {
            if (includeExecutionPhase) {
                skyframeExecutor.setExtraActionFilter(viewOptions.getExtraActionFilter())
                skyframeExecutor.setRuleContextConstraintSemantics(
                    ruleClassProvider.getConstraintSemantics() as RuleContextConstraintSemantics?
                )
                Profiler.instance().profile("prepareForExecution").use { c ->
                    com.google.common.base.Preconditions.checkNotNull<ExecutionSetup?>(executionSetupCallback)
                        .prepareForExecution()
                }
                var discardAnalysisCacheAfterAnalysis =
                    viewOptions.getDiscardAnalysisCache()
                            || !skyframeExecutor.tracksStateForIncrementality()
                if (discardAnalysisCacheAfterAnalysis
                    && remoteAnalysisCachingDependenciesProvider.mode().isRetrievalEnabled()
                ) {
                    // When remote analysis value retrieval is enabled, it is possible for analysis
                    // to occur during the logical execution phase. Discarding the analysis cache
                    // can lead to crashes.
                    //
                    // TODO: b/466388360 - consider alternatives
                    eventHandler.handle(
                        com.google.devtools.build.lib.events.Event.warn("Remote analysis caching is enabled. Not discarding the analysis cache.")
                    )
                    discardAnalysisCacheAfterAnalysis = false
                }
                skyframeAnalysisResult =
                    skyframeBuildView.analyzeAndExecuteTargets(
                        eventHandler,
                        topLevelCtKeys,
                        aspectKeys,
                        loadingResult.getTestsToRunLabels(),
                        labelToTargetMap,
                        topLevelOptions,
                        explicitTargetPatterns,
                        eventBus,
                        bugReporter,
                        com.google.common.base.Preconditions.checkNotNull<T?>(resourceManager),  // non-null for skymeld.
                        com.google.common.base.Preconditions.checkNotNull<T?>(buildResultListener),  // non-null for skymeld.
                        { configuredTargets, allTargetsToTest ->
                            memoizedGetCoverageArtifactsHelper(
                                configuredTargets, allTargetsToTest, eventHandler, eventBus
                            )
                        },
                        keepGoing,
                        skipIncompatibleExplicitTargets,
                        checkForActionConflicts,
                        viewOptions.getExtraActionTopLevelOnly(),
                        executors,  /* shouldDiscardAnalysisCache= */
                        discardAnalysisCacheAfterAnalysis,  // Analysis uploads happen after the build and use the syscall cache, so it should
                        // not be cleared mid-build. The cache is still cleared upon command completion.
                        /* shouldClearSyscallCache= */
                        remoteAnalysisCachingDependenciesProvider.mode()
                                !== RemoteAnalysisCacheMode.UPLOAD,
                        buildDriverKeyTestContext,
                        skymeldAnalysisOverlapPercentage
                    )
            } else {
                skyframeAnalysisResult =
                    skyframeBuildView.configureTargets(
                        eventHandler,
                        labelToTargetMap,
                        topLevelCtKeys,
                        aspectKeys,
                        topLevelOptions,
                        eventBus,
                        bugReporter,
                        keepGoing,
                        executors,
                        checkForActionConflicts
                    )
                setArtifactRoots(skyframeAnalysisResult.getPackageRoots())
                if (skyframeExecutor.getRemoteAnalysisCachingDependenciesProvider().mode()
                    === RemoteAnalysisCacheMode.UPLOAD
                ) {
                    skyframeExecutor.clearPackageValues()
                }
            }
        } finally {
            skyframeBuildView.clearInvalidatedActionLookupKeys()
        }

        val numTargetsToAnalyze: Int = labelToTargetMap.size()
        val numSuccessful: Int = skyframeAnalysisResult.getConfiguredTargets().size()
        if (0 < numSuccessful && numSuccessful < numTargetsToAnalyze) {
            val msg: String? =
                java.lang.String.format(
                    "%s succeeded for only %d of %d top-level targets",
                    if (includeExecutionPhase) "Build" else "Analysis", numSuccessful, numTargetsToAnalyze
                )
            eventHandler.handle(com.google.devtools.build.lib.events.Event.info(msg))
            logger.atInfo().log("%s", msg)
        }

        val result: com.google.devtools.build.lib.analysis.AnalysisResult
        if (includeExecutionPhase) {
            // TODO(b/199053098): Also consider targets with errors like below.
            result =
                createResult(
                    eventHandler,
                    eventBus,
                    loadingResult,
                    topLevelConfig,
                    topLevelOptions,
                    viewOptions,
                    skyframeAnalysisResult,  /* targetsToSkip= */
                    com.google.common.collect.ImmutableSet.of<ConfiguredTarget?>(),
                    labelToTargetMap,  /* includeExecutionPhase= */
                    true
                )
        } else {
            var targetsToSkip: com.google.common.collect.ImmutableSet<ConfiguredTarget> =
                com.google.common.collect.ImmutableSet.of<ConfiguredTarget>()
            if (reportIncompatibleTargets) {
                val topLevelConstraintSemantics: TopLevelConstraintSemantics =
                    TopLevelConstraintSemantics(
                        ruleClassProvider.getConstraintSemantics() as RuleContextConstraintSemantics?,
                        skyframeExecutor.getPackageManager(),
                        skyframeExecutor.getEvaluator(),
                        eventHandler
                    )

                val platformRestrictions: PlatformRestrictionsResult =
                    topLevelConstraintSemantics.checkPlatformRestrictions(
                        skyframeAnalysisResult.getConfiguredTargets(),
                        explicitTargetPatterns,
                        keepGoing,
                        skipIncompatibleExplicitTargets
                    )

                if (!platformRestrictions.targetsWithErrors().isEmpty()) {
                    // If there are any errored targets (e.g. incompatible targets that are explicitly
                    // specified on the command line), remove them from the list of targets to be built.
                    skyframeAnalysisResult =
                        skyframeAnalysisResult.withAdditionalErroredTargets(
                            platformRestrictions.targetsWithErrors()
                        )
                }

                targetsToSkip =
                    com.google.common.collect.Sets.union<E>(
                        topLevelConstraintSemantics.checkTargetEnvironmentRestrictions(
                            skyframeAnalysisResult.getConfiguredTargets()
                        ),
                        platformRestrictions.targetsToSkip()
                    )
                        .immutableCopy()
            }

            result =
                createResult(
                    eventHandler,
                    eventBus,
                    loadingResult,
                    topLevelConfig,
                    topLevelOptions,
                    viewOptions,
                    skyframeAnalysisResult,
                    targetsToSkip,
                    labelToTargetMap,  /* includeExecutionPhase= */
                    false
                )
        }
        logger.atInfo().log("Finished analysis")
        return result
    }

    @Throws(java.lang.InterruptedException::class)
    private fun constructLabelToTargetMap(
        loadingResult: TargetPatternPhaseValue
    ): com.google.common.collect.ImmutableMap<Label?, Target?> {
        val labels: com.google.common.collect.ImmutableSet<Label> = loadingResult.getTargetLabels()
        val builder: com.google.common.collect.ImmutableMap.Builder<Label?, Target?> =
            com.google.common.collect.ImmutableMap.builderWithExpectedSize<Label?, Target?>(labels.size())
        for (label in labels) {
            val pkg: Package =
                checkNotNull(skyframeExecutor.getExistingPackage(label.getPackageIdentifier()), label)
            val target: Target = checkNotNull(pkg.getTargets().get(label.getName()), label)
            builder.put(label, target)
        }
        return builder.buildOrThrow()
    }

    @Throws(java.lang.InterruptedException::class, ViewCreationFailedException::class)
    private fun createTopLevelAspectKeys(
        aspects: MutableList<String>,
        aspectsParameters: com.google.common.collect.ImmutableMap<String?, String?>?,
        topLevelTargets: com.google.common.collect.ImmutableMap<Label?, Target?>,
        configuration: BuildConfigurationValue?,
        eventHandler: ExtendedEventHandler?
    ): com.google.common.collect.ImmutableList<TopLevelAspectsKey?> {
        val mainRepoMapping: RepositoryMapping?
        try {
            mainRepoMapping = skyframeExecutor.getMainRepoMapping(eventHandler)
        } catch (e: RepositoryMappingResolutionException) {
            val errorMessage: String? =
                java.lang.String.format(
                    "Failed to get main repo mapping for aspect label canonicalization: %s",
                    e.getMessage()
                )
            throw ViewCreationFailedException(
                errorMessage,
                createAnalysisFailureDetail(errorMessage, Analysis.Code.UNEXPECTED_ANALYSIS_EXCEPTION),
                e
            )
        }

        val aspectClassesBuilder: com.google.common.collect.ImmutableList.Builder<AspectClass?> =
            com.google.common.collect.ImmutableList.builder<AspectClass?>()
        for (aspect in aspects) {
            // Syntax: label%aspect
            val delimiterPosition: Int = aspect.indexOf('%'.code)
            if (delimiterPosition >= 0) {
                // TODO(jfield): For consistency with Starlark loads, the aspect should be specified
                // as an absolute label.
                // We convert it for compatibility reasons (this will be removed in the future).
                var bzlFileLoadLikeString: String = aspect.substring(0, delimiterPosition)
                if (!bzlFileLoadLikeString.startsWith("//") && !bzlFileLoadLikeString.startsWith("@")) {
                    // "Legacy" behavior of '--aspects' parameter.
                    if (bzlFileLoadLikeString.startsWith("/")) {
                        bzlFileLoadLikeString = bzlFileLoadLikeString.substring(1)
                    }
                    val lastSlashPosition: Int = bzlFileLoadLikeString.lastIndexOf('/'.code)
                    if (lastSlashPosition >= 0) {
                        bzlFileLoadLikeString =
                            ("//"
                                    + bzlFileLoadLikeString.substring(0, lastSlashPosition)
                                    + ":"
                                    + bzlFileLoadLikeString.substring(lastSlashPosition + 1))
                    } else {
                        bzlFileLoadLikeString = "//:" + bzlFileLoadLikeString
                    }
                    if (!bzlFileLoadLikeString.endsWith(".bzl")) {
                        bzlFileLoadLikeString = bzlFileLoadLikeString + ".bzl"
                    }
                }
                val starlarkFileLabel: Label?
                try {
                    starlarkFileLabel =
                        Label.parseWithRepoContext(
                            bzlFileLoadLikeString,
                            Label.RepoContext.of(RepositoryName.MAIN, mainRepoMapping)
                        )
                } catch (e: LabelSyntaxException) {
                    val errorMessage: String? =
                        java.lang.String.format("Invalid aspect '%s': %s", aspect, e.getMessage())
                    throw ViewCreationFailedException(
                        errorMessage,
                        createAnalysisFailureDetail(errorMessage, Analysis.Code.ASPECT_LABEL_SYNTAX_ERROR),
                        e
                    )
                }
                val starlarkFunctionName: String = aspect.substring(delimiterPosition + 1)
                aspectClassesBuilder.add(
                    StarlarkAspectClass(keyForBuild(starlarkFileLabel), starlarkFunctionName)
                )
            } else {
                val aspectFactoryClass: NativeAspectClass? =
                    ruleClassProvider.getNativeAspectClassMap().get(aspect)

                if (aspectFactoryClass != null) {
                    aspectClassesBuilder.add(aspectFactoryClass)
                } else {
                    val errorMessage = "Aspect '" + aspect + "' is unknown"
                    throw ViewCreationFailedException(
                        errorMessage,
                        createAnalysisFailureDetail(errorMessage, Analysis.Code.ASPECT_NOT_FOUND)
                    )
                }
            }
        }
        val aspectClasses: com.google.common.collect.ImmutableList<AspectClass?> = aspectClassesBuilder.build()
        if (aspectClasses.isEmpty()) {
            return com.google.common.collect.ImmutableList.of<TopLevelAspectsKey?>()
        }

        return topLevelTargets.entrySet()
            .stream() // Do not run aspects on materializer targets since registering actions is not allowed in
            // materializer rules (and thus aspects that run on them) and many aspects do register
            // actions, and there isn't much for an aspect to do on a materializer target anyway.
            .filter(java.util.function.Predicate { entry: MutableMap.MutableEntry<Label?, Target?>? ->
                !entry.getValue().isMaterializerRule()
            })
            .map<Any?>(
                java.util.function.Function { target: MutableMap.MutableEntry<Label?, Target?>? ->
                    AspectKeyCreator.createTopLevelAspectsKey(
                        aspectClasses, target.getKey(), configuration, aspectsParameters
                    )
                })
            .collect(com.google.common.collect.ImmutableList.toImmutableList<Any?>())
    }

    @Throws(java.lang.InterruptedException::class)
    private fun createResult(
        eventHandler: ExtendedEventHandler,
        eventBus: com.google.common.eventbus.EventBus?,
        loadingResult: TargetPatternPhaseValue,
        configuration: BuildConfigurationValue?,
        topLevelOptions: TopLevelArtifactContext,
        viewOptions: AnalysisOptions,
        skyframeAnalysisResult: SkyframeAnalysisResult,
        targetsToSkip: MutableSet<ConfiguredTarget>,
        labelToTargetMap: com.google.common.collect.ImmutableMap<Label?, Target?>,
        includeExecutionPhase: Boolean
    ): com.google.devtools.build.lib.analysis.AnalysisResult {
        val testsToRun: com.google.common.collect.ImmutableSet<Label?>? = loadingResult.getTestsToRunLabels()
        val configuredTargets: MutableSet<ConfiguredTarget> =
            com.google.common.collect.Sets.newLinkedHashSet<E>(skyframeAnalysisResult.getConfiguredTargets())
        val aspects: com.google.common.collect.ImmutableMap<AspectKey?, ConfiguredAspect?> =
            skyframeAnalysisResult.getAspects()

        var allTargetsToTest: MutableSet<ConfiguredTarget>? = null
        if (testsToRun != null) {
            // Determine the subset of configured targets that are meant to be run as tests.
            allTargetsToTest = Companion.filterTestsByTargets(configuredTargets, testsToRun)
        }

        var artifactsToBuild: com.google.common.collect.ImmutableSet.Builder<Artifact?> =
            com.google.common.collect.ImmutableSet.builder<Artifact?>()

        // build-info and build-changelist.
        val buildInfoArtifacts: com.google.common.collect.ImmutableList<Artifact?> =
            skyframeExecutor.getWorkspaceStatusArtifacts(eventHandler)
        com.google.common.base.Preconditions.checkState(buildInfoArtifacts.size() == 2, buildInfoArtifacts)

        // Extra actions
        addExtraActionsIfRequested(
            viewOptions, configuredTargets, aspects, artifactsToBuild, eventHandler
        )

        // Coverage
        artifactsToBuild.addAll(
            memoizedGetCoverageArtifactsHelper(
                configuredTargets, allTargetsToTest, eventHandler, eventBus
            )
        )

        // TODO(cparsons): If extra actions are ever removed, this filtering step can probably be
        //  removed as well: the only concern would be action conflicts involving coverage artifacts,
        //  which seems far-fetched.
        if (skyframeAnalysisResult.hasActionConflicts()) {
            // We don't remove the (hopefully unnecessary) guard in SkyframeBuildView that enables/
            // disables analysis, since no new targets should actually be analyzed.
            val artifacts: com.google.common.collect.ImmutableSet<Artifact?> = artifactsToBuild.build()
            val errorFreeArtifacts: java.util.function.Predicate<Artifact?>? =
                skyframeExecutor.filterActionConflictsForTopLevelArtifacts(eventHandler, artifacts)

            artifactsToBuild = com.google.common.collect.ImmutableSet.builder<Artifact?>()
            artifacts.stream().filter(errorFreeArtifacts)
                .forEach(java.util.function.Consumer { element: Artifact? -> artifactsToBuild.add(element) })
        }
        // Build-info artifacts are always conflict-free, and can't be checked easily.
        buildInfoArtifacts.forEach(java.util.function.Consumer { element: Artifact? -> artifactsToBuild.add(element) })

        // Tests.
        val parallelTestsBuilder: com.google.common.collect.ImmutableSet.Builder<ConfiguredTarget?> =
            com.google.common.collect.ImmutableSet.builder<ConfiguredTarget?>()
        val exclusiveTestsBuilder: com.google.common.collect.ImmutableSet.Builder<ConfiguredTarget?> =
            com.google.common.collect.ImmutableSet.builder<ConfiguredTarget?>()
        val exclusiveIfLocalTestsBuilder: com.google.common.collect.ImmutableSet.Builder<ConfiguredTarget?> =
            com.google.common.collect.ImmutableSet.builder<ConfiguredTarget?>()
        collectTests(
            topLevelOptions,
            allTargetsToTest,
            labelToTargetMap,
            parallelTestsBuilder,
            exclusiveTestsBuilder,
            exclusiveIfLocalTestsBuilder
        )
        val parallelTests: com.google.common.collect.ImmutableSet<ConfiguredTarget?> = parallelTestsBuilder.build()
        val exclusiveTests: com.google.common.collect.ImmutableSet<ConfiguredTarget?> = exclusiveTestsBuilder.build()
        val exclusiveIfLocalTests: com.google.common.collect.ImmutableSet<ConfiguredTarget?> =
            exclusiveIfLocalTestsBuilder.build()

        val failureDetail: FailureDetail? =
            Companion.createAnalysisFailureDetail(loadingResult, skyframeAnalysisResult)
        if (includeExecutionPhase) {
            val skyframeAnalysisAndExecutionResult: SkyframeAnalysisAndExecutionResult =
                skyframeAnalysisResult as SkyframeAnalysisAndExecutionResult
            return AnalysisAndExecutionResult(
                configuration,
                com.google.common.collect.ImmutableSet.copyOf<ConfiguredTarget?>(configuredTargets),
                aspects,
                if (allTargetsToTest == null) null else com.google.common.collect.ImmutableSet.copyOf<ConfiguredTarget?>(
                    allTargetsToTest
                ),
                com.google.common.collect.ImmutableSet.copyOf<ConfiguredTarget?>(targetsToSkip),
                failureDetail,
                skyframeAnalysisAndExecutionResult.getRepresentativeExecutionExitCode(),
                artifactsToBuild.build(),
                parallelTests,
                exclusiveTests,
                exclusiveIfLocalTests,
                topLevelOptions,
                skyframeAnalysisResult.getTargetsWithConfiguration()
            )
        }

        val graph: WalkableGraph = skyframeAnalysisResult.getWalkableGraph()
        val actionGraph: ActionGraph =
            object : ActionGraph() {
                public override fun getGeneratingAction(artifact: Artifact): ActionAnalysisMetadata? {
                    if (artifact.isSourceArtifact()) {
                        return null
                    }
                    val generatingActionKey: ActionLookupData =
                        (artifact as Artifact.DerivedArtifact).getGeneratingActionKey()
                    val `val`: ActionLookupValue?
                    try {
                        `val` = graph.getValue(generatingActionKey.getActionLookupKey()) as ActionLookupValue?
                    } catch (e: java.lang.InterruptedException) {
                        throw java.lang.IllegalStateException(
                            "Interruption not expected from this graph: " + generatingActionKey, e
                        )
                    }
                    if (`val` == null) {
                        logger.atWarning().atMostEvery(1, TimeUnit.SECONDS).log(
                            "Missing generating action for %s (%s)", artifact, generatingActionKey
                        )
                        return null
                    }
                    return `val`.getActions().get(generatingActionKey.getActionIndex())
                }
            }
        return com.google.devtools.build.lib.analysis.AnalysisResult(
            configuration,
            com.google.common.collect.ImmutableSet.copyOf<ConfiguredTarget?>(configuredTargets),
            aspects,
            if (allTargetsToTest == null) null else com.google.common.collect.ImmutableSet.copyOf<ConfiguredTarget?>(
                allTargetsToTest
            ),
            com.google.common.collect.ImmutableSet.copyOf<ConfiguredTarget?>(targetsToSkip),
            failureDetail,
            actionGraph,
            artifactsToBuild.build(),
            parallelTests,
            exclusiveTests,
            exclusiveIfLocalTests,
            topLevelOptions,
            skyframeAnalysisResult.getPackageRoots(),
            skyframeAnalysisResult.getTargetsWithConfiguration()
        )
    }

    private fun addExtraActionsIfRequested(
        viewOptions: AnalysisOptions,
        configuredTargets: MutableCollection<ConfiguredTarget>,
        aspects: com.google.common.collect.ImmutableMap<AspectKey?, ConfiguredAspect?>,
        artifactsToBuild: com.google.common.collect.ImmutableSet.Builder<Artifact?>,
        eventHandler: ExtendedEventHandler
    ) {
        val filter: RegexFilter = viewOptions.getExtraActionFilter()
        for (target in configuredTargets) {
            val provider: ExtraActionArtifactsProvider? =
                target.getProvider(ExtraActionArtifactsProvider::class.java)
            if (provider != null) {
                if (viewOptions.getExtraActionTopLevelOnly()) {
                    // Collect all aspect-classes that topLevel might inject.
                    val aspectClasses: MutableSet<AspectClass?> = HashSet<AspectClass?>()
                    var actualTarget: Target? = null
                    try {
                        actualTarget =
                            skyframeExecutor.getPackageManager().getTarget(eventHandler, target.getLabel())
                    } catch (e: NoSuchPackageException) {
                        eventHandler.handle(com.google.devtools.build.lib.events.Event.error(""))
                    } catch (e: NoSuchTargetException) {
                        eventHandler.handle(com.google.devtools.build.lib.events.Event.error(""))
                    } catch (e: java.lang.InterruptedException) {
                        eventHandler.handle(com.google.devtools.build.lib.events.Event.error(""))
                    }
                    for (attr in actualTarget.getAssociatedRule().getAttributes()) {
                        aspectClasses.addAll(attr.getAspectClasses())
                    }
                    addArtifactsToBuilder(
                        provider.getExtraActionArtifacts().toList(), artifactsToBuild, filter
                    )
                    if (!aspectClasses.isEmpty()) {
                        addArtifactsToBuilder(
                            filterTransitiveExtraActions(provider, aspectClasses), artifactsToBuild, filter
                        )
                    }
                } else {
                    addArtifactsToBuilder(
                        provider.getTransitiveExtraActionArtifacts().toList(), artifactsToBuild, filter
                    )
                }
            }
        }
        for (aspectEntry in aspects.entrySet()) {
            val provider: ExtraActionArtifactsProvider? =
                aspectEntry.getValue().getProvider<P?>(ExtraActionArtifactsProvider::class.java)
            if (provider != null) {
                if (viewOptions.getExtraActionTopLevelOnly()) {
                    addArtifactsToBuilder(
                        provider.getExtraActionArtifacts().toList(), artifactsToBuild, filter
                    )
                } else {
                    addArtifactsToBuilder(
                        provider.getTransitiveExtraActionArtifacts().toList(), artifactsToBuild, filter
                    )
                }
            }
        }
    }

    /**
     * Sets the possible artifact roots in the artifact factory. This allows the factory to resolve
     * paths with unknown roots to artifacts.
     */
    private fun setArtifactRoots(packageRoots: PackageRoots) {
        getArtifactFactory().setPackageRoots(packageRoots.getPackageRootLookup())
    }

    /** Performs the necessary setups for the execution phase.  */
    fun interface ExecutionSetup {
        @Throws(
            AbruptExitException::class,
            BuildFailedException::class,
            InvalidConfigurationException::class,
            java.lang.InterruptedException::class
        )
        fun prepareForExecution()
    }

    /** The callback for when BuildConfigurationValue is available.  */
    fun interface BuildConfigurationsCreated {
        fun run(buildConfiguration: BuildConfigurationValue?)
    }

    @Throws(java.lang.InterruptedException::class)
    private fun memoizedGetCoverageArtifactsHelper(
        configuredTargets: MutableSet<ConfiguredTarget>?,
        allTargetsToTest: MutableSet<ConfiguredTarget>?,
        eventHandler: com.google.devtools.build.lib.events.EventHandler?,
        eventBus: com.google.common.eventbus.EventBus?
    ): com.google.common.collect.ImmutableSet<Artifact?>? {
        if (memoizedCoverageArtifacts == null) {
            memoizedCoverageArtifacts =
                constructCoverageArtifacts(configuredTargets, allTargetsToTest, eventHandler, eventBus)
        }
        return memoizedCoverageArtifacts
    }

    @Throws(java.lang.InterruptedException::class)
    private fun constructCoverageArtifacts(
        configuredTargets: MutableSet<ConfiguredTarget>?,
        allTargetsToTest: MutableSet<ConfiguredTarget>?,
        eventHandler: com.google.devtools.build.lib.events.EventHandler?,
        eventBus: com.google.common.eventbus.EventBus?
    ): com.google.common.collect.ImmutableSet<Artifact?> {
        if (coverageReportActionFactory == null) {
            return com.google.common.collect.ImmutableSet.of<Artifact?>()
        }
        val actionsWrapper: CoverageReportActionsWrapper? =
            coverageReportActionFactory.createCoverageReportActionsWrapper(
                eventHandler,
                eventBus,
                directories,
                configuredTargets,
                allTargetsToTest,
                getArtifactFactory(),
                skyframeExecutor.getActionKeyContext(),
                CoverageReportValue.COVERAGE_REPORT_KEY,
                ruleClassProvider.getRunfilesPrefix()
            )
        if (actionsWrapper == null) {
            return com.google.common.collect.ImmutableSet.of<Artifact?>()
        }
        skyframeExecutor.injectCoverageReportData(actionsWrapper.getActions())
        return com.google.common.collect.ImmutableSet.copyOf(actionsWrapper.getCoverageOutputs())
    }

    companion object {
        private val logger: GoogleLogger = GoogleLogger.forEnclosingClass()
        const val UPLOAD_BUILDS_MUST_BE_COLD: String =
            "'--experimental_remote_analysis_cache_mode=upload' builds must be cold"

        /** Returns the collection of configured targets corresponding to any of the provided targets.  */
        @com.google.common.annotations.VisibleForTesting
        fun filterTestsByTargets(
            targets: MutableCollection<ConfiguredTarget>, allowedTargetLabels: MutableSet<Label?>
        ): LinkedHashSet<ConfiguredTarget> {
            return targets.stream()
                .filter(java.util.function.Predicate { ct: ConfiguredTarget -> allowedTargetLabels.contains(ct.getLabel()) })
                .collect(Collectors.toCollection(java.util.function.Supplier { LinkedHashSet() }))
        }

        /**
         * Check for errors in "chronological" order (acknowledge that loading and analysis are
         * interleaved, but sequential on the single target scale).
         */
        fun createAnalysisFailureDetail(
            loadingResult: TargetPatternPhaseValue,
            skyframeAnalysisResult: SkyframeAnalysisResult?
        ): FailureDetail? {
            if (loadingResult.hasError()) {
                return FailureDetail.newBuilder()
                    .setMessage("command succeeded, but there were errors parsing the target pattern")
                    .setTargetPatterns(TargetPatterns.newBuilder().setCode(Code.TARGET_PATTERN_PARSE_FAILURE))
                    .build()
            }
            if (loadingResult.hasPostExpansionError()
                || (skyframeAnalysisResult != null && skyframeAnalysisResult.hasLoadingError())
            ) {
                return FailureDetail.newBuilder()
                    .setMessage("command succeeded, but there were loading phase errors")
                    .setAnalysis(Analysis.newBuilder().setCode(Analysis.Code.GENERIC_LOADING_PHASE_FAILURE))
                    .build()
            }
            if (skyframeAnalysisResult != null && skyframeAnalysisResult.hasAnalysisError()) {
                return FailureDetail.newBuilder()
                    .setMessage("command succeeded, but not all targets were analyzed")
                    .setAnalysis(Analysis.newBuilder().setCode(Analysis.Code.NOT_ALL_TARGETS_ANALYZED))
                    .build()
            }
            return null
        }

        private fun createAnalysisFailureDetail(
            errorMessage: String?, code: Analysis.Code?
        ): FailureDetail {
            return FailureDetail.newBuilder()
                .setMessage(errorMessage)
                .setAnalysis(Analysis.newBuilder().setCode(code))
                .build()
        }

        private fun addArtifactsToBuilder(
            artifacts: MutableList<out Artifact>,
            builder: com.google.common.collect.ImmutableSet.Builder<Artifact?>,
            filter: RegexFilter
        ) {
            for (artifact in artifacts) {
                if (filter.isIncluded(artifact.getOwnerLabel().toString())) {
                    builder.add(artifact)
                }
            }
        }

        /**
         * Returns a list of artifacts from 'provider' that were registered by an aspect from
         * 'aspectClasses'. All artifacts in 'provider' are considered - both direct and transitive.
         */
        private fun filterTransitiveExtraActions(
            provider: ExtraActionArtifactsProvider, aspectClasses: MutableSet<AspectClass?>
        ): com.google.common.collect.ImmutableList<Artifact> {
            val artifacts: com.google.common.collect.ImmutableList.Builder<Artifact?> =
                com.google.common.collect.ImmutableList.builder<Artifact?>()
            // Add to 'artifacts' all extra-actions which were registered by aspects which 'topLevel'
            // might have injected.
            for (artifact in provider.getTransitiveExtraActionArtifacts().toList()) {
                val owner: ActionLookupKey? = artifact.getArtifactOwner()
                if (owner is AspectKey) {
                    if (aspectClasses.contains(owner.getAspectClass())) {
                        artifacts.add(artifact)
                    }
                }
            }
            return artifacts.build()
        }

        private fun collectTests(
            topLevelOptions: TopLevelArtifactContext,
            allTestTargets: Iterable<ConfiguredTarget>?,
            labelToTargetMap: com.google.common.collect.ImmutableMap<Label?, Target?>,
            parallelTests: com.google.common.collect.ImmutableSet.Builder<ConfiguredTarget?>,
            exclusiveTests: com.google.common.collect.ImmutableSet.Builder<ConfiguredTarget?>,
            exclusiveIfLocalTests: com.google.common.collect.ImmutableSet.Builder<ConfiguredTarget?>
        ) {
            val outputGroups: MutableSet<String?> = topLevelOptions.outputGroups()
            if (!outputGroups.contains(OutputGroupInfo.Companion.FILES_TO_COMPILE) && !outputGroups.contains(
                    OutputGroupInfo.Companion.COMPILATION_PREREQUISITES
                ) && allTestTargets != null
            ) {
                val isExclusive: Boolean = topLevelOptions.runTestsExclusively()
                for (configuredTarget in allTestTargets) {
                    val target: Target? = labelToTargetMap.get(configuredTarget.getLabel())
                    if (target is Rule) {
                        if (isExclusive || TargetUtils.isExclusiveTestRule(target)) {
                            exclusiveTests.add(configuredTarget)
                        } else if (TargetUtils.isExclusiveIfLocalTestRule(target as Rule?)
                            && TargetUtils.isLocalTestRule(target as Rule?)
                        ) {
                            exclusiveTests.add(configuredTarget)
                        } else if (TargetUtils.isExclusiveIfLocalTestRule(target as Rule?)) {
                            exclusiveIfLocalTests.add(configuredTarget)
                        } else {
                            parallelTests.add(configuredTarget)
                        }
                    }
                }
            }
        }

        /**
         * Tests and clears the current thread's pending "interrupted" status, and throws
         * InterruptedException iff it was set.
         */
        @Throws(java.lang.InterruptedException::class)
        private fun pollInterruptedStatus() {
            if (java.lang.Thread.interrupted()) {
                throw java.lang.InterruptedException()
            }
        }

        @Throws(java.lang.InterruptedException::class)
        fun getTopLevelConfigurationTrimmedOfTestOptions(
            buildOptions: BuildOptions?, eventHandler: ExtendedEventHandler?
        ): BuildOptions {
            return getPatchedOptions(buildOptions, eventHandler)
        }

        @Throws(java.lang.InterruptedException::class)
        private fun getPatchedOptions(
            buildOptions: BuildOptions?, eventHandler: ExtendedEventHandler?
        ): BuildOptions {
            return TestTrimmingTransition.INSTANCE.patch(
                BuildOptionsView(
                    buildOptions, TestTrimmingTransition.INSTANCE.requiresOptionFragments()
                ),
                eventHandler
            )
        }
    }
}
