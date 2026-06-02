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

import com.google.devtools.build.lib.analysis.config.CommonOptions.EMPTY_OPTIONS

/**
 * A helper object to support Skyframe-driven execution.
 * 
 * 
 * This object is mostly used to inject external state, such as the executor engine or some
 * additional artifacts (workspace status and build info artifacts) into SkyFunctions for use during
 * the build.
 */
abstract class SkyframeExecutor protected constructor(
    skyframeExecutorConsumerOnInit: java.util.function.Consumer<SkyframeExecutor?>,
    pkgFactory: PackageFactory,
    fileSystem: com.google.devtools.build.lib.vfs.FileSystem,
    directories: BlazeDirectories?,
    actionKeyContext: ActionKeyContext?,
    workspaceStatusActionFactory: Factory,
    extraSkyFunctions: com.google.common.collect.ImmutableMap<SkyFunctionName?, SkyFunction?>?,
    syscallCache: SyscallCache,
    externalFileAction: ExternalFileAction?,
    ignoredSubdirectoriesFunction: SkyFunction?,
    crossRepositoryLabelViolationStrategy: CrossRepositoryLabelViolationStrategy?,
    buildFilesByPriority: com.google.common.collect.ImmutableList<BuildFileName?>?,
    actionOnIOExceptionReadingBuildFile: ActionOnIOExceptionReadingBuildFile?,
    actionOnFilesystemErrorCodeLoadingBzlFile: ActionOnFilesystemErrorCodeLoadingBzlFile?,
    shouldUseRepoDotBazel: Boolean,
    shouldUnblockCpuWorkWhenFetchingDeps: Boolean,
    packageProgress: PackageProgressReceiver?,
    analysisProgress: AnalysisProgressReceiver?,
    skyKeyStateReceiver: SkyKeyStateReceiver,
    bugReporter: BugReporter?,
    diffAwarenessFactories: Iterable<out DiffAwareness.Factory?>?,
    workspaceInfoFromDiffReceiver: WorkspaceInfoFromDiffReceiver?,
    recordingDiffer: RecordingDifferencer?,
    allowExternalRepositories: Boolean,
    repoContentsCachePathSupplier: java.util.function.Supplier<com.google.devtools.build.lib.vfs.Path?>?,
    globUnderSingleDep: Boolean,
    diffCheckNotificationOptions: java.util.Optional<DiffCheckNotificationOptions>
) : WalkableGraphFactory {
    @kotlin.jvm.JvmField
    var memoizingEvaluator: MemoizingEvaluator? = null
    private val emittedEventState: EmittedEventState = EmittedEventState()
    protected val pkgFactory: PackageFactory
    private val workspaceStatusActionFactory: WorkspaceStatusAction.Factory
    protected val fileSystem: com.google.devtools.build.lib.vfs.FileSystem
    protected val directories: BlazeDirectories
    @kotlin.jvm.JvmField
    val externalFilesHelper: ExternalFilesHelper
    protected val bugReporter: BugReporter?

    /**
     * Measures source artifacts read this build. Does not include cached artifacts, so is less useful
     * on incremental builds.
     */
    private val sourceArtifactsSeen: FilesMetricConsumer = FilesMetricConsumer()

    private val outputArtifactsSeen: FilesMetricConsumer = FilesMetricConsumer()
    private val outputArtifactsFromActionCache: FilesMetricConsumer = FilesMetricConsumer()
    private val topLevelArtifactsMetric: FilesMetricConsumer = FilesMetricConsumer()

    var outputService: OutputService? = null // Null only for non-build commands.

    // Cache of parsed bzl files, for use when we're inlining BzlCompileFunction in
    // BzlLoadFunction. See the comments in BzlLoadFunction for motivations and details.
    private val bzlCompileCache: com.github.benmanes.caffeine.cache.Cache<BzlCompileValue.Key?, BzlCompileValue?> =
        Caffeine.newBuilder().build<BzlCompileValue.Key?, BzlCompileValue?>()

    private val numPackagesSuccessfullyLoaded: AtomicInteger = AtomicInteger(0)
    private val packageProgress: PackageProgressReceiver?
    private val analysisProgress: AnalysisProgressReceiver?
    protected val syscallCache: SyscallCache

    private val skyframeBuildView: SkyframeBuildView
    private var actionLogBufferPathGenerator: ActionLogBufferPathGenerator? = null

    private val skyframeExecutorConsumerOnInit: java.util.function.Consumer<SkyframeExecutor?>

    // AtomicReferences are used here as mutable boxes shared with value builders.
    private val showLoadingProgress: AtomicBoolean = AtomicBoolean()
    private val pkgLocator: AtomicReference<PathPackageLocator?> = AtomicReference<PathPackageLocator?>()
    val deletedPackages: AtomicReference<com.google.common.collect.ImmutableSet<PackageIdentifier?>?> =
        AtomicReference<com.google.common.collect.ImmutableSet<PackageIdentifier?>?>(com.google.common.collect.ImmutableSet.of<PackageIdentifier?>())
    private val eventBus: AtomicReference<com.google.common.eventbus.EventBus?> =
        AtomicReference<com.google.common.eventbus.EventBus?>()
    val tsgm: AtomicReference<TimestampGranularityMonitor?> = AtomicReference<TimestampGranularityMonitor?>()
    private val clientEnv: AtomicReference<MutableMap<String?, String?>?> =
        AtomicReference<MutableMap<String?, String?>?>()

    private val artifactFactory: ArtifactFactory
    private val actionKeyContext: ActionKeyContext

    var active: Boolean = true
    private val packageManager: SkyframePackageManager
    private val queryTransitivePackagePreloader: QueryTransitivePackagePreloader

    /** Used to lock evaluator on legacy calls to get existing values.  */
    private val valueLookupLock = Any()

    private val statusReporterRef: AtomicReference<ActionExecutionStatusReporter?> =
        AtomicReference<ActionExecutionStatusReporter?>()
    protected val skyframeActionExecutor: SkyframeActionExecutor
    private var actionRewindStrategy: ActionRewindStrategy? = null
    private var buildDriverFunction: BuildDriverFunction? = null
    private var globFunction: GlobFunction? = null
    var progressReceiver: SkyframeProgressReceiver? = null
    private var cyclesReporter: CyclesReporter? = null

    private var lastAnalysisDiscarded = false

    /**
     * True if analysis was not incremental because [.handleAnalysisInvalidatingChange] was
     * called, typically because a configuration-related option changed.
     */
    private var analysisCacheInvalidated = false

    /** True if loading and analysis nodes were cleared (discarded) after analysis to save memory.  */
    private var analysisCacheCleared = false

    private val extraSkyFunctions: com.google.common.collect.ImmutableMap<SkyFunctionName?, SkyFunction?>?

    var incrementalBuildMonitor: SkyframeIncrementalBuildMonitor? = SkyframeIncrementalBuildMonitor()

    private val ignoredSubdirectoriesFunction: SkyFunction?

    private val ruleClassProvider: ConfiguredRuleClassProvider

    private val crossRepositoryLabelViolationStrategy: CrossRepositoryLabelViolationStrategy?

    private val buildFilesByPriority: com.google.common.collect.ImmutableList<BuildFileName?>?

    private val actionOnIOExceptionReadingBuildFile: ActionOnIOExceptionReadingBuildFile?

    private val actionOnFilesystemErrorCodeLoadingBzlFile: ActionOnFilesystemErrorCodeLoadingBzlFile?

    private val shouldUseRepoDotBazel: Boolean

    private val shouldUnblockCpuWorkWhenFetchingDeps: Boolean

    private val skyKeyStateReceiver: SkyKeyStateReceiver

    private val pathResolverFactory: PathResolverFactory = PathResolverFactoryImpl()

    private val cpuBoundSemaphore: AtomicReference<Semaphore?> = AtomicReference<Semaphore?>(
        Semaphore(
            DEFAULT_SEMAPHORE_SIZE
        )
    )

    private var lastExecutionSalt: String? = null

    // start: Skymeld-only
    // This is set once every build and set to null at the end of each.
    private var mergedSkyframeAnalysisExecutionSupplier: java.util.function.Supplier<Boolean?>? = null

    // Reset after each build.
    private var incrementalArtifactConflictFinder: IncrementalArtifactConflictFinder? = null

    // Reset after each build.
    private var conflictCheckingModeInThisBuild: ConflictCheckingMode? = NONE
    private var consumedArtifactsTracker: ConsumedArtifactsTracker? = null

    // end: Skymeld-only
    private var ruleContextConstraintSemantics: RuleContextConstraintSemantics? = null
    private var extraActionFilter: com.google.devtools.build.lib.util.RegexFilter? = null
    private var watchdog: ActionExecutionInactivityWatchdog? = null

    val isBuildingExclusiveArtifacts: AtomicBoolean = AtomicBoolean(false)

    // Reset to null after each build to save memory. Guaranteed to be non-null when retrieved via
    // BuildDriverFunction.
    private var testTypeResolver: TestTypeResolver? = null

    // This boolean controls whether FILE_STATE or DIRECTORY_LISTING_STATE nodes are dropped after the
    // corresponding FILE or DIRECTORY_LISTING nodes are evaluated.
    // See b/261019506.
    protected var heuristicallyDropNodes: Boolean = false

    val modifiedFiles: AtomicInteger = AtomicInteger()
    var numSourceFilesCheckedBecauseOfMissingDiffs: Int = 0

    // This is intentionally not kept in sync with the evaluator: we may reset the evaluator without
    // ever losing injected/invalidated data here. This is safe because the worst that will happen is
    // that on the next build we try to inject/invalidate some nodes that aren't needed for the build.
    protected val recordingDiffer: RecordingDifferencer?
    val diffAwarenessManager: DiffAwarenessManager?
    private val allowExternalRepositories: Boolean
    private val workspaceInfoFromDiffReceiver: WorkspaceInfoFromDiffReceiver?
    private var previousClientEnvironment: MutableSet<String?> = com.google.common.collect.ImmutableSet.of<String?>()

    // Contain the paths in the .bazelignore file.
    private var ignoredPaths: IgnoredSubdirectories? = IgnoredSubdirectories.EMPTY

    var sourceDiffCheckingDuration: java.time.Duration? = java.time.Duration.ofSeconds(-1L)

    private var skyfocusState: SkyfocusState = SkyfocusState.Companion.DISABLED

    private var platformMappingKey: PlatformMappingKey? = null

    /**
     * Determines the type of hybrid globbing strategy to use when [ ][.tracksStateForIncrementality] is `true`. See [.getGlobbingStrategy] for more
     * details.
     */
    private val globUnderSingleDep: Boolean

    private var remoteAnalysisCachingHasEverBeenEnabled = false

    private var remoteAnalysisCachingDependenciesProvider: RemoteAnalysisCachingDependenciesProvider =
        RemoteAnalysisCacheManager.createDisabled()

    private var remoteAnalysisCacheReaderDepsProvider: RemoteAnalysisCacheReaderDepsProvider? =
        RemoteAnalysisCacheDeps.createDisabled()

    /**
     * The state of the remote analysis caching.
     * 
     * 
     * This is used to track the state of the remote analysis caching so that we can invalidate
     * keys if needed. This object's lifetime is the same as the lifetime as the owning
     * SkyframeExecutor object, or until resetEvaluator is called, which then resets this to the empty
     * state.
     */
    private var remoteAnalysisCachingState: RemoteAnalysisCachingServerState =
        RemoteAnalysisCachingServerState.initializeEmpty()

    private val analysisCount: AtomicInteger = AtomicInteger()

    protected val diffCheckNotificationOptions: java.util.Optional<DiffCheckNotificationOptions>

    private var isCleanBuild = true

    val andIncrementAnalysisCount: Int
        /** Returns how many times analysis has been run during the life of this bazel server instance.  */
        get() = analysisCount.getAndIncrement()

    /**
     * Returns the dependencies for remote analysis caching.
     * 
     * 
     * Should not be called before analysis begins.
     */
    @com.google.common.annotations.VisibleForTesting // productionVisibility = Visibility.PRIVATE
    fun getRemoteAnalysisCachingDependenciesProvider(): RemoteAnalysisCachingDependenciesProvider {
        return remoteAnalysisCachingDependenciesProvider
    }

    @com.google.common.annotations.VisibleForTesting // productionVisibility = Visibility.PRIVATE
    fun getRemoteAnalysisCacheReaderDepsProvider(): RemoteAnalysisCacheReaderDepsProvider? {
        return remoteAnalysisCacheReaderDepsProvider
    }

    fun setRemoteAnalysisCachingDependenciesProvider(
        remoteAnalysisCachingDependenciesProvider: RemoteAnalysisCachingDependenciesProvider,
        remoteAnalysisCacheReaderDepsProvider: RemoteAnalysisCacheReaderDepsProvider?
    ) {
        this.remoteAnalysisCachingDependenciesProvider = remoteAnalysisCachingDependenciesProvider
        this.remoteAnalysisCacheReaderDepsProvider = remoteAnalysisCacheReaderDepsProvider
    }

    fun getRemoteAnalysisCachingState(): RemoteAnalysisCachingServerState {
        return remoteAnalysisCachingState
    }

    /**
     * Syncs the [RemoteAnalysisCachingServerState] with the latest state from the current
     * invocation.
     */
    fun syncRemoteAnalysisCachingState(
        currentInvocationVersion: FrontierNodeVersion?, currentInvocationClientId: ClientId?
    ) {
        remoteAnalysisCachingState.setVersion(currentInvocationVersion)
        remoteAnalysisCachingState.setClientId(currentInvocationClientId)
    }

    /**
     * Invalidates the given keys with an external remote analysis service.
     * 
     * 
     * If remote analysis caching is currently disabled but has been enabled before, all
     * deserialized nodes are deleted.
     */
    @Throws(java.lang.InterruptedException::class)
    fun invalidateWithExternalService(eventHandler: ExtendedEventHandler?) {
        val remoteAnalysisCachingCurrentlyEnabled = this.isRemoteAnalysisCachingEnabled
        remoteAnalysisCachingHasEverBeenEnabled =
            remoteAnalysisCachingHasEverBeenEnabled or remoteAnalysisCachingCurrentlyEnabled
        if (!remoteAnalysisCachingHasEverBeenEnabled) {
            return
        }

        val keysToLookupSupplier: java.util.function.Supplier<com.google.common.collect.ImmutableSet<SkyKey?>> =
            java.util.function.Supplier {
                Profiler.instance().profile("getDeserializedKeys").use { c ->
                    return@Supplier this.evaluator.getInMemoryGraph().getAllNodeEntries().parallelStream()
                        .filter(java.util.function.Predicate { e: InMemoryNodeEntry? -> e.isDone() && e.getValue() is DeserializedSkyValue })
                        .map<SkyKey?>(java.util.function.Function { obj: InMemoryNodeEntry? -> obj.getKey() })
                        .collect(com.google.common.collect.ImmutableSet.toImmutableSet<SkyKey?>())
                }
            }

        if (!remoteAnalysisCachingCurrentlyEnabled) {
            // If skycache is currently disabled, we need to delete all the deserialized nodes
            // because they do not have transitive edges to File/Directory nodes.
            val keysToLookup: com.google.common.collect.ImmutableSet<SkyKey?> = keysToLookupSupplier.get()
            if (!keysToLookup.isEmpty()) {
                // Only scan the graph for deletion if there are keys to delete,
                // otherwise it'll be a wasteful iteration.
                this.evaluator.delete(java.util.function.Predicate { `object`: SkyKey? -> keysToLookup.contains(`object`) })
            }
            return
        }

        val keysToInvalidate: MutableSet<SkyKey> =
            remoteAnalysisCachingDependenciesProvider.lookupKeysToInvalidate(
                keysToLookupSupplier, remoteAnalysisCachingState
            )

        if (keysToInvalidate.isEmpty()) {
            return
        }

        // Log a sample of the invalidated SkyKeys to the INFO log.
        val maxKeysToLog = 20
        if (keysToInvalidate.size() > maxKeysToLog) {
            logger.atInfo().log(
                "Invalidating %d keys, but only logging first %s.",
                keysToInvalidate.size(), maxKeysToLog
            )
        }
        var i = 0
        for (key in keysToInvalidate) {
            if (i++ > maxKeysToLog) {
                break
            }
            logger.atInfo().log("Invalidating key: %s", key.getCanonicalName())
        }

        // Log the number of invalidated SkyKeys by SkyFunction.
        logger.atInfo().log(
            "Skycache invalidation counts by SkyFunction: %s",
            keysToInvalidate.stream().collect(
                Collectors.groupingBy(
                    java.util.function.Function { obj: SkyKey? -> obj.functionName() },
                    Collectors.counting()
                )
            )
        )

        // `delete` is used instead of `invalidate` because the latter marks the
        // nodes as `changed`, which is not allowed for hermetic SkyFunctions. This
        // deletion is not materialized until the start of the next Skyframe
        // evaluation, when EagerInvalidator#delete will kick in.
        this.evaluator.delete(java.util.function.Predicate { o: SkyKey? -> keysToInvalidate.contains(o) })

        // Given that the deletion is not materialized until the start of the next
        // Skyframe evaluation, it is not safe to remove the keys from the set of
        // deserialized keys here. If we delete the keys before the change is
        // reflected in Skyframe, and an interrupt happens in between, the Skyframe
        // node will not receive correct invalidation updates.
        //
        // Instead, we use the SkyframeProgressReceiver to delete each key from the
        // RemoteAnalysisCachingState *after* the actual Skyframe deletion.
    }

    @get:com.google.common.annotations.VisibleForTesting
    val isRemoteAnalysisCachingEnabled: Boolean
        get() = remoteAnalysisCachingDependenciesProvider.mode() === RemoteAnalysisCacheMode.DOWNLOAD

    internal inner class PathResolverFactoryImpl : PathResolverFactory {
        public override fun createPathResolverForArtifactValues(actionInputMap: ActionInputMap?): ArtifactPathResolver? {
            return if (outputService.supportsPathResolverForArtifactValues())
                outputService.createPathResolverForArtifactValues(
                    directories.getExecRoot(ruleClassProvider.getRunfilesPrefix()).asFragment(),
                    directories.getRelativeOutputPath(),
                    fileSystem,
                    this.packagePathEntries,
                    actionInputMap
                )
            else
                ArtifactPathResolver.IDENTITY
        }
    }

    private fun skyFunctions(): com.google.common.collect.ImmutableMap<SkyFunctionName?, SkyFunction?> {
        this.actionRewindStrategy =
            ActionRewindStrategy(
                skyframeActionExecutor, bugReporter, { this.getRemoteAnalysisCacheReaderDepsProvider() })
        val bzlLoadFunctionForInliningPackageAndWorkspaceNodes: BzlLoadFunction? =
            this.bzlLoadFunctionForInliningPackageAndWorkspaceNodes

        // We don't check for duplicates in order to allow extraSkyfunctions to override existing
        // entries.
        val map: MutableMap<SkyFunctionName?, SkyFunction?> = HashMap<SkyFunctionName?, SkyFunction?>()
        // IF YOU ADD A NEW SKYFUNCTION: If your Skyfunction can be used transitively by package
        // loading, make sure to register it in AbstractPackageLoader as well.
        map.put(SkyFunctions.PRECOMPUTED, PrecomputedFunction())
        map.put(SkyFunctions.CLIENT_ENVIRONMENT_VARIABLE, ClientEnvironmentFunction(clientEnv))
        map.put(SkyFunctions.ACTION_ENVIRONMENT_VARIABLE, ActionEnvironmentFunction())
        map.put(SkyFunctions.REPOSITORY_ENVIRONMENT_VARIABLE, RepoEnvironmentFunction())
        map.put(FileStateKey.FILE_STATE, newFileStateFunction())
        map.put(SkyFunctions.DIRECTORY_LISTING_STATE, newDirectoryListingStateFunction())
        map.put(FileSymlinkCycleUniquenessFunction.NAME, FileSymlinkCycleUniquenessFunction())
        map.put(
            FileSymlinkInfiniteExpansionUniquenessFunction.NAME,
            FileSymlinkInfiniteExpansionUniquenessFunction()
        )
        map.put(SkyFunctions.FILE, FileFunction(pkgLocator, directories))
        map.put(SkyFunctions.DIRECTORY_LISTING, DirectoryListingFunction())
        map.put(SkyFunctions.DIRECTORY_TREE_DIGEST, DirectoryTreeDigestFunction())
        map.put(
            SkyFunctions.PACKAGE_LOOKUP,
            PackageLookupFunction(
                deletedPackages, crossRepositoryLabelViolationStrategy, buildFilesByPriority
            )
        )
        map.put(SkyFunctions.CONTAINING_PACKAGE_LOOKUP, ContainingPackageLookupFunction())
        map.put(SkyFunctions.PROJECT, ProjectFunction())
        map.put(SkyFunctions.PROJECT_FILES_LOOKUP, ProjectFilesLookupFunction())
        map.put(
            SkyFunctions.BZL_COMPILE,  // TODO rename
            BzlCompileFunction(
                ruleClassProvider.getBazelStarlarkEnvironment(),
                this.digestFunction.getHashFunction(),
                pkgFactory.getPackageLoadingListener()
            )
        )
        map.put(
            SkyFunctions.STARLARK_BUILTINS,
            StarlarkBuiltinsFunction(ruleClassProvider.getBazelStarlarkEnvironment())
        )
        map.put(SkyFunctions.BZL_LOAD, newBzlLoadFunction(ruleClassProvider))
        this.globFunction = newGlobFunction()
        map.put(SkyFunctions.GLOB, this.globFunction)
        map.put(SkyFunctions.GLOBS, GlobsFunction())
        map.put(SkyFunctions.TARGET_PATTERN, TargetPatternFunction())
        map.put(SkyFunctions.PREPARE_DEPS_OF_PATTERNS, PrepareDepsOfPatternsFunction())
        map.put(SkyFunctions.PREPARE_DEPS_OF_PATTERN, PrepareDepsOfPatternFunction(pkgLocator))
        map.put(
            SkyFunctions.PREPARE_DEPS_OF_TARGETS_UNDER_DIRECTORY,
            PrepareDepsOfTargetsUnderDirectoryFunction(directories)
        )
        map.put(SkyFunctions.COLLECT_TARGETS_IN_PACKAGE, CollectTargetsInPackageFunction())
        map.put(
            SkyFunctions.COLLECT_PACKAGES_UNDER_DIRECTORY,
            newCollectPackagesUnderDirectoryFunction(directories)
        )
        map.put(SkyFunctions.IGNORED_SUBDIRECTORIES, ignoredSubdirectoriesFunction)
        map.put(SkyFunctions.TESTS_IN_SUITE, TestExpansionFunction())
        map.put(SkyFunctions.TEST_SUITE_EXPANSION, TestsForTargetPatternFunction())
        map.put(SkyFunctions.TARGET_PATTERN_PHASE, TargetPatternPhaseFunction())
        map.put(SkyFunctions.RECURSIVE_PKG, RecursivePkgFunction(directories))
        map.put(
            SkyFunctions.PACKAGE,
            PackageFunction.Companion.newBuilder()
                .setPackageFactory(pkgFactory)
                .setPackageLocator(packageManager)
                .setShowLoadingProgress(showLoadingProgress)
                .setNumPackagesSuccessfullyLoaded(numPackagesSuccessfullyLoaded)
                .setBzlLoadFunctionForInlining(bzlLoadFunctionForInliningPackageAndWorkspaceNodes)
                .setPackageProgress(packageProgress)
                .setActionOnIOExceptionReadingBuildFile(actionOnIOExceptionReadingBuildFile)
                .setActionOnFilesystemErrorCodeLoadingBzlFile(actionOnFilesystemErrorCodeLoadingBzlFile)
                .setShouldUseRepoDotBazel(shouldUseRepoDotBazel)
                .setGlobbingStrategy(this.globbingStrategy)
                .setThreadStateReceiverFactoryForMetrics(java.util.function.Function { key: SkyKey? ->
                    skyKeyStateReceiver.makeThreadStateReceiver(
                        key
                    )
                })
                .setCpuBoundSemaphore(cpuBoundSemaphore)
                .build()
        )
        map.put(SkyFunctions.PACKAGE_DECLARATIONS, PackageDeclarationsFunction())
        map.put(SkyFunctions.PACKAGE_ERROR, PackageErrorFunction())
        map.put(SkyFunctions.PACKAGE_ERROR_MESSAGE, PackageErrorMessageFunction())
        map.put(SkyFunctions.MACRO_INSTANCE, MacroInstanceFunction())
        map.put(SkyFunctions.EVAL_MACRO, EvalMacroFunction(pkgFactory, cpuBoundSemaphore))
        map.put(SkyFunctions.NON_FINALIZER_PACKAGE_PIECES, NonFinalizerPackagePiecesFunction())
        map.put(SkyFunctions.TARGET_PATTERN_ERROR, TargetPatternErrorFunction())
        map.put(TransitiveTargetKey.Companion.NAME, TransitiveTargetFunction())
        map.put(Label.TRANSITIVE_TRAVERSAL, TransitiveTraversalFunction())
        map.put(
            SkyFunctions.CONFIGURED_TARGET,
            ConfiguredTargetFunction(
                BuildViewProvider(),
                ruleClassProvider,
                cpuBoundSemaphore,
                shouldStoreTransitivePackagesInLoadingAndAnalysis(),
                shouldUnblockCpuWorkWhenFetchingDeps,
                analysisProgress,
                PrerequisitePackageFunction { id: PackageIdentifier? -> this.getExistingPackage(id) },
                java.util.function.Supplier { this.getRemoteAnalysisCacheReaderDepsProvider() })
        )
        map.put(
            SkyFunctions.ASPECT,
            AspectFunction(
                BuildViewProvider(),
                ruleClassProvider,
                shouldStoreTransitivePackagesInLoadingAndAnalysis(),
                PrerequisitePackageFunction { id: PackageIdentifier? -> this.getExistingPackage(id) },
                BaseTargetPrerequisitesSupplierImpl(),
                com.google.common.base.Supplier { this.getRemoteAnalysisCacheReaderDepsProvider() },
                analysisProgress
            )
        )
        map.put(
            SkyFunctions.TOP_LEVEL_ASPECTS,
            ToplevelStarlarkAspectFunction(
                BuildViewProvider(),
                ruleClassProvider,
                shouldStoreTransitivePackagesInLoadingAndAnalysis(),
                PrerequisitePackageFunction { id: PackageIdentifier? -> this.getExistingPackage(id) })
        )
        map.put(SkyFunctions.LOAD_ASPECTS, LoadAspectsFunction())
        map.put(GenQueryPackageProviderFactory.GENQUERY_SCOPE, GenQueryPackageProviderFactory.FUNCTION)
        map.put(
            SkyFunctions.ACTION_LOOKUP_CONFLICT_FINDING,
            ActionLookupConflictFindingFunction(java.util.function.Supplier { this.getRemoteAnalysisCacheReaderDepsProvider() })
        )
        map.put(
            SkyFunctions.TOP_LEVEL_ACTION_LOOKUP_CONFLICT_FINDING,
            TopLevelActionLookupConflictFindingFunction()
        )
        map.put(
            SkyFunctions.BUILD_CONFIGURATION,
            BuildConfigurationFunction(directories, ruleClassProvider)
        )
        map.put(SkyFunctions.BUILD_CONFIGURATION_KEY, BuildConfigurationKeyFunction())
        map.put(
            SkyFunctions.PARSED_FLAGS,
            ParsedFlagsFunction(ruleClassProvider.getFragmentRegistry().getOptionsClasses())
        )
        map.put(
            SkyFunctions.BASELINE_OPTIONS,
            BaselineOptionsFunction(this.minimalVersionForBaselineOptionsFunction)
        )
        map.put(
            SkyFunctions.STARLARK_BUILD_SETTINGS_DETAILS, StarlarkBuildSettingsDetailsFunction()
        )
        map.put(
            SkyFunctions.REPO_FILE,
            if (shouldUseRepoDotBazel)
                RepoFileFunction(
                    ruleClassProvider.getBazelStarlarkEnvironment(),
                    Root.fromPath(directories.getWorkspace())
                )
            else
                SkyFunction { k: SkyKey?, env: SkyFunction.Environment? ->
                    throw java.lang.IllegalStateException("supposed to be unused")
                })
        map.put(SkyFunctions.REPO_PACKAGE_ARGS, RepoPackageArgsFunction.INSTANCE)
        // Inject an empty default BAZEL_DEP_GRAPH SkyFunction for unit tests.
        map.put(
            SkyFunctions.BAZEL_DEP_GRAPH,
            SkyFunction { skyKey: SkyKey?, env: SkyFunction.Environment? -> BazelDepGraphValue.createEmptyDepGraph() })
        map.put(RepoDefinitionValue.REPO_DEFINITION, RepoDefinitionFunction(directories))
        map.put(
            SkyFunctions.TARGET_COMPLETION,
            TargetCompletor.Companion.targetCompletionFunction(
                pathResolverFactory,
                skyframeActionExecutor,
                topLevelArtifactsMetric,
                actionRewindStrategy,
                bugReporter
            )
        )
        map.put(
            SkyFunctions.ASPECT_COMPLETION,
            AspectCompletor.Companion.aspectCompletionFunction(
                pathResolverFactory,
                skyframeActionExecutor,
                topLevelArtifactsMetric,
                actionRewindStrategy,
                bugReporter
            )
        )
        map.put(SkyFunctions.TEST_COMPLETION, TestCompletionFunction())
        map.put(
            Artifact.ARTIFACT,
            ArtifactFunction(
                { !skyframeActionExecutor.actionFileSystemType().inMemoryFileSystem() },
                sourceArtifactsSeen,
                syscallCache,
                skyframeActionExecutor,
                { this.getRemoteAnalysisCacheReaderDepsProvider() })
        )
        map.put(
            SkyFunctions.BUILD_INFO,
            WorkspaceStatusFunction(java.util.function.Supplier { this.makeWorkspaceStatusAction() })
        )
        map.put(SkyFunctions.COVERAGE_REPORT, CoverageReportFunction(actionKeyContext))
        map.put(SkyFunctions.ACTION_EXECUTION, newActionExecutionFunction())
        map.put(
            SkyFunctions.RECURSIVE_FILESYSTEM_TRAVERSAL,
            RecursiveFilesystemTraversalFunction(syscallCache)
        )
        map.put(
            SkyFunctions.ACTION_TEMPLATE_EXPANSION,
            ActionTemplateExpansionFunction(actionKeyContext)
        )
        map.put(SkyFunctions.LOCAL_REPOSITORY_LOOKUP, LocalRepositoryLookupFunction())
        map.put(
            SkyFunctions.REGISTERED_EXECUTION_PLATFORMS, RegisteredExecutionPlatformsFunction()
        )
        map.put(SkyFunctions.REGISTERED_TOOLCHAINS, RegisteredToolchainsFunction())
        map.put(SkyFunctions.SINGLE_TOOLCHAIN_RESOLUTION, SingleToolchainResolutionFunction())
        map.put(SkyFunctions.TOOLCHAIN_RESOLUTION, ToolchainResolutionFunction())
        map.put(SkyFunctions.REPOSITORY_MAPPING, RepositoryMappingFunction(ruleClassProvider))
        map.put(SkyFunctions.PLATFORM, PlatformFunction())
        map.put(
            SkyFunctions.PLATFORM_MAPPING,
            PlatformMappingFunction(ruleClassProvider.getFragmentRegistry().getOptionsClasses())
        )
        map.put(
            SkyFunctions.ARTIFACT_NESTED_SET,
            ArtifactNestedSetFunction({ this.getConsumedArtifactsTracker() })
        )
        val buildDriverFunction: BuildDriverFunction = newBuildDriverFunction()
        map.put(SkyFunctions.BUILD_DRIVER, buildDriverFunction)
        val flagSetFunction: FlagSetFunction = FlagSetFunction()
        map.put(SkyFunctions.FLAG_SET, flagSetFunction)
        this.buildDriverFunction = buildDriverFunction
        map.put(SkyFunctions.BUILD_OPTIONS_SCOPE, BuildOptionsScopeFunction())

        map.putAll(extraSkyFunctions)
        return com.google.common.collect.ImmutableMap.copyOf<SkyFunctionName?, SkyFunction?>(map)
    }

    protected fun newBuildDriverFunction(): BuildDriverFunction {
        return BuildDriverFunction(
            { getCheckerForConflictCheckingMode(WITH_TRAVERSAL) },
            { this.getRuleContextConstraintSemantics() },
            { this.getExtraActionFilter() },
            { this.getTestTypeResolver() },
            AdditionalPostAnalysisDepsRequestedAndAvailable.NO_OP
        )
    }

    protected fun newFileStateFunction(): SkyFunction {
        return FileStateFunction(java.util.function.Supplier { tsgm.get() }, syscallCache, externalFilesHelper)
    }

    protected fun newDirectoryListingStateFunction(): SkyFunction {
        return DirectoryListingStateFunction(externalFilesHelper, syscallCache)
    }

    protected val minimalVersionForBaselineOptionsFunction: com.google.devtools.build.skyframe.Version?
        get() = com.google.devtools.build.skyframe.Version.minimal()

    protected fun newActionExecutionFunction(): SkyFunction {
        return ActionExecutionFunction(
            actionRewindStrategy,
            skyframeActionExecutor,
            java.util.function.Supplier { memoizingEvaluator },
            directories,
            java.util.function.Supplier { tsgm.get() },
            bugReporter,
            java.util.function.Supplier { this.getRemoteAnalysisCacheReaderDepsProvider() },
            java.util.function.Supplier { this.getConsumedArtifactsTracker() })
    }

    protected fun newCollectPackagesUnderDirectoryFunction(directories: BlazeDirectories?): SkyFunction {
        return CollectPackagesUnderDirectoryFunction(directories)
    }

    protected fun newGlobFunction(): GlobFunction {
        return GlobFunction.create( /* recursionInSingleFunction= */true)
    }

    protected val bzlLoadFunctionForInliningPackageAndWorkspaceNodes: BzlLoadFunction?
        get() = null

    protected fun newBzlLoadFunction(ruleClassProvider: RuleClassProvider?): SkyFunction {
        return BzlLoadFunction.Companion.create(
            ruleClassProvider,
            directories,
            this.digestFunction.getHashFunction(),
            pkgFactory.getPackageLoadingListener(),
            bzlCompileCache
        )
    }

    @ThreadCompatible
    fun setActive(active: Boolean) {
        this.active = active
    }

    protected fun checkActive() {
        com.google.common.base.Preconditions.checkState(active)
    }

    fun configureActionExecutor(
        fileCache: InputMetadataProvider?,
        actionInputPrefetcher: ActionInputPrefetcher?,
        actionExecutionSalt: String?,
        maxStdoutErrBytes: Int
    ) {
        skyframeActionExecutor.configure(
            fileCache,
            actionInputPrefetcher,
            DiscoveredModulesPruner.DEFAULT,
            actionExecutionSalt,
            maxStdoutErrBytes
        )
    }

    @com.google.errorprone.annotations.ForOverride
    protected abstract fun dumpPackages(out: PrintStream?)

    fun setOutputService(outputService: OutputService?) {
        this.outputService = outputService
    }

    /** Inform this SkyframeExecutor that a new command is starting.  */
    fun noteCommandStart() {
        // Prevent stale Skycache configuration from persisting between builds.
        remoteAnalysisCachingDependenciesProvider = RemoteAnalysisCacheManager.createDisabled()
    }

    /**
     * Notify listeners about changed files, and release any associated memory afterwards.
     * 
     * 
     * It's called at the end of the execution of a Blaze command and if the command builds, before
     * the execution phase starts. In the latter case, the invocation at the end of the command will
     * be a no-op so that the event about changed files is posted only once.
     * 
     * 
     * The reason why the event about changed files is posted early if the command builds is that
     * it's used in the execution phase.
     */
    fun drainChangedFiles() {
        if (incrementalBuildMonitor != null) {
            incrementalBuildMonitor.alertListeners(getEventBus())
            incrementalBuildMonitor = null
        }
    }

    /**
     * Was there an analysis-invalidating change, like a configuration option changing, causing a
     * non-incremental analysis phase to be performed. Calling this resets the state to false.
     */
    fun wasAnalysisCacheInvalidatedAndResetBit(): Boolean {
        val tmp = analysisCacheInvalidated
        analysisCacheInvalidated = false
        return tmp
    }

    /** Was the analysis (and loading) cache cleared to save memory before execution.  */
    fun wasAnalysisCacheCleared(): Boolean {
        return analysisCacheCleared
    }

    /**
     * This method exists only to allow a module to make a top-level Skyframe call during the
     * transition to making it fully Skyframe-compatible. Do not add additional callers!
     */
    @Throws(EnvironmentalExecException::class, java.lang.InterruptedException::class)
    fun evaluateSkyKeyForExecutionSetup(
        eventHandler: ExtendedEventHandler?, key: SkyKey
    ): SkyValue {
        synchronized(valueLookupLock) {
            // We evaluate in keepGoing mode because in the case that the graph does not store its
            // edges, nokeepGoing builds are not allowed, whereas keepGoing builds are always
            // permitted.
            val result: EvaluationResult<*> =
                evaluate<SkyValue?>(
                    com.google.common.collect.ImmutableList.of<SkyKey?>(key),
                    true,
                    java.lang.Runtime.getRuntime().availableProcessors(),
                    eventHandler
                )
            if (!result.hasError()) {
                return com.google.common.base.Preconditions.checkNotNull(result.get(key), "%s %s", result, key)
            }
            val errorInfo: com.google.devtools.build.skyframe.ErrorInfo =
                com.google.common.base.Preconditions.checkNotNull<com.google.devtools.build.skyframe.ErrorInfo>(
                    result.getError(key), "%s %s", key, result
                )
            if (errorInfo.getException() != null) {
                com.google.common.base.Throwables.throwIfInstanceOf<X?>(
                    errorInfo.getException(),
                    EnvironmentalExecException::class.java
                )
                com.google.common.base.Throwables.throwIfUnchecked(errorInfo.getException())
                throw java.lang.IllegalStateException(errorInfo.getException())
            }
            throw java.lang.IllegalStateException(errorInfo.toString())
        }
    }

    internal inner class BuildViewProvider {
        /** Returns the current [SkyframeBuildView] instance.  */
        fun getSkyframeBuildView(): SkyframeBuildView {
            return skyframeBuildView
        }
    }

    /**
     * Must be called before the [SkyframeExecutor] can be used (should only be called in
     * factory methods and as an implementation detail of [.resetEvaluator]).
     */
    protected fun init() {
        progressReceiver = newSkyframeProgressReceiver()
        memoizingEvaluator = createEvaluator(skyFunctions(), progressReceiver, emittedEventState)
        skyframeExecutorConsumerOnInit.accept(this)
        isCleanBuild = true
    }

    @com.google.errorprone.annotations.ForOverride
    protected abstract fun createEvaluator(
        skyFunctions: com.google.common.collect.ImmutableMap<SkyFunctionName?, SkyFunction?>?,
        progressReceiver: SkyframeProgressReceiver?,
        emittedEventState: EmittedEventState?
    ): MemoizingEvaluator

    protected open fun newSkyframeProgressReceiver(): SkyframeProgressReceiver {
        return SkyframeProgressReceiver()
    }

    /** Reinitializes the Skyframe evaluator, dropping all previously computed values.  */
    open fun resetEvaluator() {
        analysisCount.set(0)
        emittedEventState.clear()
        skyframeBuildView.reset()
        // Prevent stale Skycache configuration from persisting between cleans.
        remoteAnalysisCachingState = RemoteAnalysisCachingServerState.initializeEmpty()
        remoteAnalysisCachingDependenciesProvider = RemoteAnalysisCacheManager.createDisabled()
        skyfocusState = SkyfocusState.Companion.DISABLED
        // cleanupInterningPools must be called before init(), since init() initializes a new graph,
        // losing all references to the SkyKeyInterners that must be cleaned up.
        memoizingEvaluator.cleanupInterningPools()
        init()
    }

    /**
     * Notifies the executor that the command is complete.
     * 
     * 
     * Should be called only once per build.
     */
    @Throws(java.lang.InterruptedException::class)
    fun notifyCommandComplete(eventHandler: ExtendedEventHandler?) {
        try {
            drainChangedFiles()
            memoizingEvaluator.noteEvaluationsAtSameVersionMayBeFinished(eventHandler)
        } finally {
            globFunction.complete()
            clearSyscallCache()
            // So that the supplier object can be GC-ed.
            mergedSkyframeAnalysisExecutionSupplier = null
            clearPlatformMappingCache()
        }
    }

    /**
     * Notifies the executor to post logging stats when the server is crashing, so that logging is
     * still available even when the server crashes.
     */
    fun postLoggingStatsWhenCrashing(eventHandler: ExtendedEventHandler?) {
        memoizingEvaluator.postLoggingStats(eventHandler)
    }

    /** Clear any configured target data stored outside Skyframe.  */
    open fun handleAnalysisInvalidatingChange() {
        logger.atInfo().log("Dropping configured target data")
        analysisCacheInvalidated = true
        skyframeBuildView.clearInvalidatedActionLookupKeys()
        skyframeBuildView.clearLegacyData()
    }

    /**
     * Computes statistics on heap-resident rules and aspects and SkyKey/Values. Returns null if
     * unsupported.
     */
    abstract val skyframeStats: SkyframeStats?

    /**
     * Decides if graph edges should be stored during this evaluation and checks if the state from the
     * last evaluation, if any, can be kept.
     * 
     * 
     * If not, it will mark this state for deletion. The actual cleaning is put off until [ ][.sync], in case no evaluation was actually called for and the existing state can be kept for
     * longer.
     */
    open fun decideKeepIncrementalState(
        batch: Boolean,
        keepStateAfterBuild: Boolean,
        trackIncrementalState: Boolean,
        heuristicallyDropNodes: Boolean,
        discardAnalysisCache: Boolean,
        eventHandler: com.google.devtools.build.lib.events.EventHandler?
    ) {
        // Assume incrementality.
    }

    /**
     * Whether this executor tracks state for the purpose of improving incremental performance.
     * 
     * 
     * A return of `false` indicates that nodes have a lifetime of a single command and that
     * graph edges are not kept.
     */
    open fun tracksStateForIncrementality(): Boolean {
        return true
    }

    @get:com.google.errorprone.annotations.ForOverride
    protected val globbingStrategy: GlobbingStrategy
        get() {
            if (tracksStateForIncrementality()) {
                return if (globUnderSingleDep)
                    GlobbingStrategy.SINGLE_GLOBS_HYBRID
                else
                    GlobbingStrategy.MULTIPLE_GLOB_HYBRID
            }
            return GlobbingStrategy.NON_SKYFRAME
        }

    val forcedSingleSourceRootIfNoExecrootSymlinkCreation: Root?
        /**
         * If not null, this is the only source root in the build, corresponding to the single element in
         * a single-element package path. Such a single-source-root build need not plant the execroot
         * symlink forest, and can trivially resolve source artifacts from exec paths. As a consequence,
         * builds where this is not null do not need to track a package -> source root map. In addition,
         * such builds can only occur in a monorepo, and thus do not need to produce repo mapping
         * manifests for runfiles.
         */
        get() = null

    private fun shouldStoreTransitivePackagesInLoadingAndAnalysis(): Boolean {
        // Transitive packages may be needed for either RepoMappingManifestAction or Skymeld with
        // external repository support. They are never needed if external repositories are disabled. To
        // avoid complexity from toggling this, just choose a setting for the lifetime of the server.
        // TODO(b/283125139): Can we support external repositories without tracking transitive packages?
        return allowExternalRepositories
    }

    @com.google.common.annotations.VisibleForTesting
    protected abstract fun injectable(): Injectable?

    /** Data that should be discarded in [.discardPreExecutionCache].  */
    protected enum class DiscardType {
        ALL,
        ANALYSIS_REFS_ONLY,
        LOADING_NODES_ONLY;

        fun discardsAnalysis(): Boolean {
            return this != DiscardType.LOADING_NODES_ONLY
        }

        fun discardsLoading(): Boolean {
            return this != DiscardType.ANALYSIS_REFS_ONLY
        }
    }

    /**
     * Save memory by removing references to configured targets and aspects in Skyframe.
     * 
     * 
     * These nodes must be recreated on subsequent builds. We do not clear the top-level target
     * nodes, since their configured targets are needed for the target completion middleman values.
     * 
     * 
     * The nodes are not deleted during this method call, because they are needed for the execution
     * phase. Instead, their analysis-time data is cleared while preserving the generating action info
     * needed for execution. The next build will delete the nodes (and recreate them if necessary).
     * 
     * 
     * `discardType` can be used to specify which data to discard.
     */
    protected fun discardPreExecutionCache(
        topLevelTargets: com.google.common.collect.ImmutableSet<ConfiguredTarget?>,
        topLevelAspects: com.google.common.collect.ImmutableSet<AspectKey?>,
        discardType: DiscardType
    ) {
        // This is to prevent throwing away Packages we may need during execution.
        val packageSetBuilder: com.google.common.collect.ImmutableSet.Builder<PackageIdentifier?> =
            com.google.common.collect.ImmutableSet.builder<PackageIdentifier?>()
        if (discardType.discardsLoading()) {
            packageSetBuilder.addAll(
                com.google.common.collect.Collections2.transform<ConfiguredTarget?, PackageIdentifier?>(
                    topLevelTargets,
                    com.google.common.base.Function { target: ConfiguredTarget? ->
                        target.getLabel().getPackageIdentifier()
                    })
            )
            packageSetBuilder.addAll(
                com.google.common.collect.Collections2.transform<AspectKey?, PackageIdentifier?>(
                    topLevelAspects,
                    com.google.common.base.Function { aspect: AspectKey? -> aspect.getLabel().getPackageIdentifier() })
            )
        }
        val topLevelPackages: com.google.common.collect.ImmutableSet<PackageIdentifier?> = packageSetBuilder.build()
        lastAnalysisDiscarded = true
        val graph: InMemoryGraph = memoizingEvaluator.getInMemoryGraph()
        val trackIncrementalState = tracksStateForIncrementality()

        trackDiscardAnalysisCache(discardType).use { p ->
            graph.parallelForEach(
                java.util.function.Consumer { e: InMemoryNodeEntry? ->
                    if (!e.isDone()) {
                        return@parallelForEach
                    }
                    val removeNode =
                        processDiscardAndDetermineRemoval(
                            e,
                            discardType,
                            topLevelPackages,
                            topLevelTargets,
                            topLevelAspects,
                            trackIncrementalState
                        )
                    if (removeNode) {
                        graph.remove(e.getKey())
                    }
                })
        }
    }

    /**
     * This is a memory optimization only used in Skycache's upload mode. It makes builds
     * non-incremental and the only way it can be undone is by running the clean command or restarting
     * the Bazel server.
     */
    fun clearPackageValues() {
        if (remoteAnalysisCachingDependenciesProvider.shouldMinimizeMemory()) {
            remoteAnalysisCachingDependenciesProvider.computeSelectionAndMinimizeMemory(
                memoizingEvaluator.getInMemoryGraph()
            )
        }
    }

    /** Tracks how long it takes to clear the analysis cache.  */
    private fun trackDiscardAnalysisCache(discardType: DiscardType?): SilentCloseable {
        val profiler: AutoProfiler =
            GoogleAutoProfilerUtils.logged("discarding analysis cache " + discardType)
        return SilentCloseable {
            val d: java.time.Duration? = java.time.Duration.ofNanos(profiler.completeAndGetElapsedTimeNanos())
            getEventBus().post(AnalysisCacheClearEvent(d))
        }
    }

    /**
     * Saves memory by clearing analysis objects from Skyframe. Clears their data without deleting
     * them (they will be deleted on the next build). May also delete loading-phase objects from the
     * graph.
     */
    // VisibleForTesting but open-source annotation doesn't have productionVisibility option.
    fun clearAnalysisCache(
        topLevelTargets: com.google.common.collect.ImmutableSet<ConfiguredTarget?>?,
        topLevelAspects: com.google.common.collect.ImmutableSet<AspectKey?>?
    ) {
        this.analysisCacheCleared = true
        clearAnalysisCacheImpl(topLevelTargets, topLevelAspects)
    }

    protected abstract fun clearAnalysisCacheImpl(
        topLevelTargets: com.google.common.collect.ImmutableSet<ConfiguredTarget?>?,
        topLevelAspects: com.google.common.collect.ImmutableSet<AspectKey?>?
    )

    protected abstract fun dropConfiguredTargetsNow(eventHandler: ExtendedEventHandler?)

    private fun makeWorkspaceStatusAction(): WorkspaceStatusAction {
        val env: WorkspaceStatusAction.Environment =
            object : Environment() {
                public override fun createStableArtifact(name: String?): Artifact {
                    val root: ArtifactRoot? =
                        directories.getBuildDataDirectory(ruleClassProvider.getRunfilesPrefix())
                    return skyframeBuildView
                        .getArtifactFactory()
                        .getDerivedArtifact(
                            PathFragment.create(name), root, WorkspaceStatusValue.BUILD_INFO_KEY
                        )
                }

                public override fun createVolatileArtifact(name: String?): Artifact {
                    val root: ArtifactRoot? =
                        directories.getBuildDataDirectory(ruleClassProvider.getRunfilesPrefix())
                    return skyframeBuildView
                        .getArtifactFactory()
                        .getConstantMetadataArtifact(
                            PathFragment.create(name), root, WorkspaceStatusValue.BUILD_INFO_KEY
                        )
                }
            }
        return workspaceStatusActionFactory.createWorkspaceStatusAction(env)
    }

    fun injectCoverageReportData(actions: com.google.common.collect.ImmutableList<ActionAnalysisMetadata?>?) {
        CoverageReportFunction.Companion.COVERAGE_REPORT_KEY.set(injectable(), actions)
    }

    private fun setDefaultVisibility(defaultVisibility: RuleVisibility?) {
        PrecomputedValue.DEFAULT_VISIBILITY.set(injectable(), defaultVisibility)
    }

    private fun setConfigSettingVisibilityPolicty(policy: ConfigSettingVisibilityPolicy?) {
        PrecomputedValue.CONFIG_SETTING_VISIBILITY_POLICY.set(injectable(), policy)
    }

    private fun setStarlarkSemantics(starlarkSemantics: net.starlark.java.eval.StarlarkSemantics?) {
        PrecomputedValue.STARLARK_SEMANTICS.set(injectable(), starlarkSemantics)
    }

    private fun setLazyMacroExpansionPackages(packages: LazyMacroExpansionPackages?) {
        PrecomputedValue.LAZY_MACRO_EXPANSION_PACKAGES.set(injectable(), packages)
    }

    private fun setStampSettingMarker() {
        PrecomputedValue.STAMP_SETTING_MARKER.inject(injectable())
    }

    @Throws(InvalidConfigurationException::class, java.lang.InterruptedException::class)
    fun setBaselineConfiguration(buildOptions: BuildOptions, eventHandler: ExtendedEventHandler?) {
        BaselineOptionsFunction.BASELINE_CONFIGURATION.set(injectable(), buildOptions)
        BaselineOptionsFunction.BASELINE_EXEC_CONFIGURATION.set(
            injectable(), adjustForExec(buildOptions, eventHandler)
        )
    }

    @Throws(InvalidConfigurationException::class, java.lang.InterruptedException::class)
    private fun adjustForExec(buildOptions: BuildOptions, eventHandler: ExtendedEventHandler?): BuildOptions {
        val execTransition: StarlarkAttributeTransitionProvider?
        try {
            execTransition = getStarlarkExecTransition(buildOptions, eventHandler)
        } catch (e: StarlarkExecTransitionLoadingException) {
            throw InvalidConfigurationException(e)
        }
        // Get the current target platform and use it as the exec platform.
        // This value isn't actually important as long as it exists and is stable.
        // TODO(345289271): Make this a value that's stable even when the target platform changes.
        val hostPlatform: Label? = buildOptions.get(PlatformOptions::class.java).getHostPlatform()
        return adjustForExec(buildOptions, execTransition, hostPlatform, eventHandler)
    }

    /** Adjusts the baseline options for the exec transition.  */
    @Throws(java.lang.InterruptedException::class)
    private fun adjustForExec(
        baselineOptions: BuildOptions,
        starlarkExecTransition: StarlarkAttributeTransitionProvider?,
        newPlatform: Label?,
        eventHandler: ExtendedEventHandler?
    ): BuildOptions {
        // A null executionPlatform actually skips transition application so need some value here when
        // not overriding the platform. It is safe to supply some fake value here (as long as it is
        // constant) since the baseline should never be used to actually construct an action or do
        // toolchain resolution.

        var baselineOptions: BuildOptions = baselineOptions
        val execTransition: PatchTransition =
            ExecutionTransitionFactory.createFactory()
                .create(
                    AttributeTransitionData.builder()
                        .executionPlatform(
                            if (newPlatform != null)
                                newPlatform
                            else
                                Label.parseCanonicalUnchecked(
                                    "//this_is_a_faked_exec_platform_for_blaze_internals"
                                )
                        )
                        .analysisData(starlarkExecTransition)
                        .build()
                )

        val flagsAliases: com.google.common.collect.ImmutableMap<String?, Label?> =
            baselineOptions.get(CoreOptions::class.java).getCommandLineFlagAliasesMap()

        val hostFlags: com.google.common.collect.ImmutableSet.Builder<Label?> =
            com.google.common.collect.ImmutableSet.builder<Label?>()
        for (alias in flagsAliases.entrySet()) {
            if (alias.getKey().startsWith("host_")) {
                hostFlags.add(alias.getValue())
            }
        }

        // ImmutableSet.copyOf() is needed here because AutoCodec doesn't have a mapping for
        // ImmutableMap.KeySet and this could cause a serialization failure even though the
        // key content remains the same before and after serialization and deserialization.
        val starlarkBuildSettingsDetailsKey: StarlarkBuildSettingsDetailsValue.Key =
            StarlarkBuildSettingsDetailsValue.Key.create(
                com.google.common.collect.ImmutableSet.copyOf(baselineOptions.getStarlarkOptions().keySet()),
                com.google.common.collect.ImmutableSet.< E > copyOf < E ? > (hostFlags.build())
            )
        val result: EvaluationResult<StarlarkBuildSettingsDetailsValue?> =
            evaluate<StarlarkBuildSettingsDetailsValue?>(
                com.google.common.collect.ImmutableList.of<Any?>(starlarkBuildSettingsDetailsKey),  /* keepGoing= */
                true,  /* numThreads= */
                1,
                eventHandler
            )

        val optionsWithDefaults: BuildOptions? =
            StarlarkTransitionCache.getDefaultStarlarkOptionsForCustomExec(
                baselineOptions.toBuilder(),
                result.get(starlarkBuildSettingsDetailsKey),
                baselineOptions
            )
        baselineOptions =
            execTransition.patch(
                TransitionUtil.restrict(execTransition, optionsWithDefaults), eventHandler
            )

        return baselineOptions
    }

    fun injectExtraPrecomputedValues(extraPrecomputedValues: MutableList<PrecomputedValue.Injected>) {
        for (injected in extraPrecomputedValues) {
            injected.inject(injectable())
        }
    }

    private fun setShowLoadingProgress(showLoadingProgressValue: Boolean) {
        showLoadingProgress.set(showLoadingProgressValue)
    }

    protected fun setCommandId(commandId: UUID?) {
        PrecomputedValue.BUILD_ID.set(injectable(), commandId)
    }

    /** Returns the build-info.txt and build-changelist.txt artifacts.  */
    @Throws(java.lang.InterruptedException::class)
    fun getWorkspaceStatusArtifacts(eventHandler: ExtendedEventHandler?): com.google.common.collect.ImmutableList<Artifact?> {
        Profiler.instance().profile("SkyframeExecutor.getWorkspaceStatusArtifact").use { c ->
            // Should already be present, unless the user didn't request any targets for analysis.
            val result: EvaluationResult<WorkspaceStatusValue?> =
                evaluate<T?>(
                    com.google.common.collect.ImmutableList.of<BuildInfoKey?>(WorkspaceStatusValue.BUILD_INFO_KEY),  /* keepGoing= */
                    true,  /* numThreads= */
                    1,
                    eventHandler
                )
            val value: WorkspaceStatusValue =
                com.google.common.base.Preconditions.checkNotNull<WorkspaceStatusValue>(result.get(WorkspaceStatusValue.BUILD_INFO_KEY))
            return com.google.common.collect.ImmutableList.of<Artifact?>(
                value.getStableArtifact(),
                value.getVolatileArtifact()
            )
        }
    }

    fun getEventBus(): com.google.common.eventbus.EventBus? {
        return eventBus.get()
    }

    val packagePathEntries: com.google.common.collect.ImmutableList<Root>
        get() = pkgLocator.get().getPathEntries()

    fun getIgnoredPaths(): IgnoredSubdirectories? {
        return ignoredPaths
    }

    fun getSkyfocusState(): SkyfocusState {
        return skyfocusState
    }

    fun setSkyfocusState(skyfocusState: SkyfocusState) {
        this.skyfocusState = skyfocusState
    }

    @Throws(java.lang.InterruptedException::class, AbruptExitException::class)
    protected fun getDiff(
        tsgm: TimestampGranularityMonitor?,
        modifiedFileSet: ModifiedFileSet,
        pathEntry: Root?,
        fsvcThreads: Int
    ): com.google.devtools.build.skyframe.Differencer.Diff {
        if (modifiedFileSet.modifiedSourceFiles().isEmpty()) {
            return ImmutableDiff(
                com.google.common.collect.ImmutableList.of<SkyKey?>(),
                com.google.common.collect.ImmutableMap.of<SkyKey?, Delta?>()
            )
        }

        // TODO(bazel-team): change ModifiedFileSet to work with RootedPaths instead of PathFragments.
        val dirtyFileStateSkyKeys: MutableCollection<FileStateKey?> =
            com.google.common.collect.Collections2.transform<PathFragment?, FileStateKey?>(
                modifiedFileSet.modifiedSourceFiles(),
                com.google.common.base.Function { pathFragment: PathFragment? ->
                    com.google.common.base.Preconditions.checkState(
                        !pathFragment.isAbsolute(), "found absolute PathFragment: %s", pathFragment
                    )
                    FileStateValue.key(RootedPath.toRootedPath(pathEntry, pathFragment))
                })

        return FileSystemValueCheckerInferringAncestors.getDiffWithInferredAncestors(
            tsgm,
            memoizingEvaluator.getInMemoryGraph(),
            dirtyFileStateSkyKeys,
            fsvcThreads,
            syscallCache,
            this.skyValueDirtinessCheckerForFiles
        )
    }

    @get:com.google.errorprone.annotations.ForOverride
    protected val skyValueDirtinessCheckerForFiles: SkyValueDirtinessChecker?
        /** Returns the [SkyValueDirtinessChecker] relevant for files.  */
        get() = FileDirtinessChecker()

    /**
     * Deletes all loaded packages and their upwards transitive closure, forcing reevaluation of all
     * affected nodes.
     */
    fun clearLoadedPackages() {
        memoizingEvaluator.delete(java.util.function.Predicate { k: SkyKey? -> SkyFunctions.PACKAGE.equals(k.functionName()) })
    }

    /** Sets the packages that should be treated as deleted and ignored.  */
    @com.google.common.annotations.VisibleForTesting // productionVisibility = Visibility.PRIVATE
    abstract fun setDeletedPackages(pkgs: Iterable<PackageIdentifier?>?)

    /**
     * Prepares the evaluator for loading.
     * 
     * 
     * MUST be run before every incremental build.
     */
    @com.google.common.annotations.VisibleForTesting // productionVisibility = Visibility.PRIVATE
    fun preparePackageLoading(
        pkgLocator: PathPackageLocator?,
        packageOptions: PackageOptions,
        buildLanguageOptions: BuildLanguageOptions,
        commandId: UUID?,
        clientEnv: MutableMap<String?, String?>?,
        executors: QuiescingExecutors,
        tsgm: TimestampGranularityMonitor?
    ) {
        com.google.common.base.Preconditions.checkNotNull<Any?>(pkgLocator)
        com.google.common.base.Preconditions.checkNotNull<TimestampGranularityMonitor?>(tsgm)
        setActive(true)

        this.tsgm.set(tsgm)
        setCommandId(commandId)
        this.clientEnv.set(clientEnv)

        setShowLoadingProgress(packageOptions.getShowLoadingProgress())
        setDefaultVisibility(packageOptions.getDefaultVisibility())
        if (!packageOptions.getEnforceConfigSettingVisibility()) {
            setConfigSettingVisibilityPolicty(ConfigSettingVisibilityPolicy.LEGACY_OFF)
        } else {
            setConfigSettingVisibilityPolicty(
                if (packageOptions.getConfigSettingPrivateDefaultVisibility())
                    ConfigSettingVisibilityPolicy.DEFAULT_STANDARD
                else
                    ConfigSettingVisibilityPolicy.DEFAULT_PUBLIC
            )
        }

        val starlarkSemantics: net.starlark.java.eval.StarlarkSemantics =
            getEffectiveStarlarkSemantics(buildLanguageOptions)
        setStarlarkSemantics(starlarkSemantics)
        setSiblingDirectoryLayout(
            starlarkSemantics.getBool(BuildLanguageOptions.EXPERIMENTAL_SIBLING_REPOSITORY_LAYOUT)
        )
        setPackageLocator(pkgLocator)
        setLazyMacroExpansionPackages(packageOptions.getLazyMacroExpansionPackages())
        setStampSettingMarker()

        this.pkgFactory.setGlobbingThreads(executors.globbingParallelism())
        this.pkgFactory.setMaxDirectoriesToEagerlyVisitInGlobbing(
            packageOptions.getMaxDirectoriesToEagerlyVisitInGlobbing()
        )
        emittedEventState.clear()

        // Clear internal caches used by SkyFunctions used for package loading. If the SkyFunctions
        // never had a chance to restart (e.g. due to user interrupt, or an error in a --nokeep_going
        // build), these may have stale entries.
        bzlCompileCache.invalidateAll()

        numPackagesSuccessfullyLoaded.set(0)
        if (packageProgress != null) {
            packageProgress.reset()
        }

        // Reset the stateful SkyframeCycleReporter, which contains cycles from last run.
        cyclesReporter = createCyclesReporter()
        analysisCacheCleared = false
    }

    private fun setSiblingDirectoryLayout(experimentalSiblingRepositoryLayout: Boolean) {
        this.artifactFactory.setSiblingRepositoryLayout(experimentalSiblingRepositoryLayout)
    }

    fun getEffectiveStarlarkSemantics(
        buildLanguageOptions: BuildLanguageOptions
    ): net.starlark.java.eval.StarlarkSemantics {
        return buildLanguageOptions.toStarlarkSemantics()
    }

    private fun setPackageLocator(pkgLocator: PathPackageLocator) {
        val eventBus: com.google.common.eventbus.EventBus? = this.eventBus.get()
        if (eventBus != null) {
            eventBus.post(pkgLocator)
        }

        val oldLocator: PathPackageLocator? = this.pkgLocator.getAndSet(pkgLocator)
        PrecomputedValue.PATH_PACKAGE_LOCATOR.set(injectable(), pkgLocator)

        if (oldLocator != null && !pkgLocator.equals(oldLocator)) {
            com.google.common.base.Preconditions.checkState(
                directories.getVirtualSourceRoot() == null,
                "Package locator should not change when using a virtual source root (%s -> %s)",
                oldLocator,
                pkgLocator
            )
            // The package path is read not only by SkyFunctions but also by some other code paths.
            // We need to take additional steps to keep the corresponding data structures in sync.
            // (Some of the additional steps are carried out by ConfiguredTargetValueInvalidationListener,
            // and some by BuildView#buildHasIncompatiblePackageRoots and #updateSkyframe.)
            onPkgLocatorChange()
        }
    }

    @com.google.errorprone.annotations.ForOverride
    open fun onPkgLocatorChange() {
    }

    fun getSkyframeBuildView(): SkyframeBuildView {
        return skyframeBuildView
    }

    /** Sets whether this build is done with --experimental_merged_skyframe_analysis_execution.  */
    fun setMergedSkyframeAnalysisExecutionSupplier(
        mergedSkyframeAnalysisExecutionSupplier: java.util.function.Supplier<Boolean?>?
    ) {
        this.mergedSkyframeAnalysisExecutionSupplier = mergedSkyframeAnalysisExecutionSupplier
    }

    val isMergedSkyframeAnalysisExecution: Boolean
        get() = mergedSkyframeAnalysisExecutionSupplier != null
                && mergedSkyframeAnalysisExecutionSupplier.get()

    fun getConsumedArtifactsTracker(): ConsumedArtifactsTracker? {
        return consumedArtifactsTracker
    }

    fun initializeConsumedArtifactsTracker() {
        consumedArtifactsTracker = ConsumedArtifactsTracker()
    }

    /** Sets the eventBus to use for posting events.  */
    fun setEventBus(eventBus: com.google.common.eventbus.EventBus?) {
        this.eventBus.set(eventBus)
    }

    fun setClientEnv(clientEnv: MutableMap<String?, String?>?) {
        this.skyframeActionExecutor.setClientEnv(clientEnv)
    }

    /** Sets the path for action log buffers.  */
    fun setActionOutputRoot(actionOutputRoot: com.google.devtools.build.lib.vfs.Path?) {
        com.google.common.base.Preconditions.checkNotNull<com.google.devtools.build.lib.vfs.Path?>(actionOutputRoot)
        this.actionLogBufferPathGenerator = ActionLogBufferPathGenerator(actionOutputRoot)
        this.skyframeActionExecutor.setActionLogBufferPathGenerator(actionLogBufferPathGenerator)
    }

    private fun setRemoteExecutionEnabled(enabled: Boolean) {
        PrecomputedValue.REMOTE_EXECUTION_ENABLED.set(injectable(), enabled)
    }

    /** Called when a top-level configuration is determined.  */
    fun setTopLevelConfiguration(topLevelConfiguration: BuildConfigurationValue?) {}

    /**
     * Parse raw options and create a [BuildOptions] instance. Options may be a mix of native
     * and Starlark options.
     */
    @com.google.common.annotations.VisibleForTesting
    @Throws(InvalidConfigurationException::class)
    fun createBuildOptionsForTesting(
        eventHandler: ExtendedEventHandler?, args: com.google.common.collect.ImmutableList<String?>
    ): BuildOptions {
        val mainRepositoryMappingKey: RepositoryMappingValue.Key =
            RepositoryMappingValue.key(RepositoryName.MAIN)
        val mainRepoMappingResult: EvaluationResult<SkyValue?> =
            evaluateSkyKeys(eventHandler, com.google.common.collect.ImmutableList.of<SkyKey?>(mainRepositoryMappingKey))
        if (mainRepoMappingResult.hasError()) {
            throw InvalidConfigurationException(
                "Cannot find main repository mapping",
                Code.INVALID_BUILD_OPTIONS,
                mainRepoMappingResult.getError().getException()
            )
        }
        val mainRepositoryMappingValue: RepositoryMappingValue =
            mainRepoMappingResult.get(mainRepositoryMappingKey) as RepositoryMappingValue
        val mainRepoContext: RepoContext =
            RepoContext.of(RepositoryName.MAIN, mainRepositoryMappingValue.repositoryMapping())

        val flagAliasMappings: com.google.common.collect.ImmutableMap<String?, Label?> =
            args.stream()
                .filter(java.util.function.Predicate { arg: String? -> arg.startsWith("--flag_alias=") })
                .map<Array<String?>?>(java.util.function.Function { arg: String? ->
                    arg.substring("--flag_alias=".length()).split("=")
                })
                .collect(
                    com.google.common.collect.ImmutableMap.toImmutableMap<Any?, String?, Any?>(
                        java.util.function.Function { pair: Any? -> pair[0] },
                        java.util.function.Function { pair: Any? -> Label.parseCanonicalUnchecked(pair[1]) })
                )
        // Parse the options.
        val rootPackage: PackageContext? = mainRepoContext.rootPackage()
        val parsedFlagsKey: ParsedFlagsValue.Key =
            ParsedFlagsValue.Key.create(args, rootPackage, flagAliasMappings)
        val result: EvaluationResult<SkyValue?> =
            evaluateSkyKeys(eventHandler, com.google.common.collect.ImmutableList.of<SkyKey?>(parsedFlagsKey))
        if (result.hasError()) {
            val firstError: MutableMap.MutableEntry<SkyKey?, com.google.devtools.build.skyframe.ErrorInfo>? =
                com.google.common.collect.Iterables.get<MutableMap.MutableEntry<SkyKey?, com.google.devtools.build.skyframe.ErrorInfo>?>(
                    result.errorMap().entrySet(),
                    0
                )
            val errorKey: SkyKey? = firstError.getKey()
            val error: com.google.devtools.build.skyframe.ErrorInfo = firstError.getValue()
            val e: Throwable? = error.getException()

            if (e != null) {
                throw InvalidConfigurationException(Code.INVALID_BUILD_OPTIONS, e)
            } else if (!error.getCycleInfo().isEmpty()) {
                // This should not ever happen: there should not be a way for BuildConfigurationKeyValue.Key
                // to produce a skyframe cycle. Produce a basic error message for developers
                // to use to track down and fix the problem.
                // Unfortunately, there's no way to express this as an invariant, so manual inspection of
                // skyfunctions is the only way to prevent this.
                cyclesReporter.reportCycles(error.getCycleInfo(), errorKey, eventHandler)
                throw InvalidConfigurationException(
                    "cannot load build configuration key because of this cycle", Code.CYCLE
                )
            }
        }
        val parsedFlagsValue: ParsedFlagsValue = result.get(parsedFlagsKey) as ParsedFlagsValue
        return BuildOptions.of(
            ruleClassProvider.getFragmentRegistry().getOptionsClasses(),
            parsedFlagsValue.parsingResult()
        )
    }

    /** Asks the Skyframe evaluator to build a [BuildConfigurationValue].  */
    @Throws(InvalidConfigurationException::class)
    fun createConfiguration(
        eventHandler: ExtendedEventHandler?, buildOptions: BuildOptions?, keepGoing: Boolean
    ): BuildConfigurationValue {
        if (analysisProgress != null) {
            analysisProgress.reset()
        }

        val topLevelTargetConfig: BuildConfigurationValue =
            getConfiguration(eventHandler, buildOptions, keepGoing)

        // TODO(gregce): cache invalid option errors in BuildConfigurationFunction, then use a dedicated
        // accessor (i.e. not the event handler) to trigger the exception below.
        val nosyEventHandler: ErrorSensingEventHandler<java.lang.Void?> =
            ErrorSensingEventHandler.withoutPropertyValueTracking(eventHandler)
        topLevelTargetConfig.reportInvalidOptions(nosyEventHandler)
        if (nosyEventHandler.hasErrors()) {
            throw InvalidConfigurationException(
                "Build options are invalid", Code.INVALID_BUILD_OPTIONS
            )
        }
        return topLevelTargetConfig
    }

    /**
     * Asks the Skyframe evaluator to build the given artifacts and targets, and to test the given
     * parallel test targets. Additionally, exclusive tests are built together with all the other
     * tests but they are intentionally *not* run since they must be executed separately one-by-one.
     */
    @Throws(java.lang.InterruptedException::class, AbruptExitException::class)
    fun buildArtifacts(
        reporter: com.google.devtools.build.lib.events.Reporter?,
        resourceManager: ResourceManager,
        executor: Executor?,
        artifactsToBuild: MutableSet<Artifact?>?,
        targetsToBuild: MutableCollection<ConfiguredTarget?>?,
        aspects: com.google.common.collect.ImmutableSet<AspectKey?>?,
        parallelTests: MutableSet<ConfiguredTarget?>,
        exclusiveTests: MutableSet<ConfiguredTarget?>,
        options: com.google.devtools.common.options.OptionsProvider,
        actionCacheChecker: ActionCacheChecker?,
        outputDirectoryHelper: ActionOutputDirectoryHelper?,
        executionProgressReceiver: EvaluationProgressReceiver?,
        topLevelArtifactContext: TopLevelArtifactContext?
    ): EvaluationResult<*> {
        checkActive()
        com.google.common.base.Preconditions.checkState(actionLogBufferPathGenerator != null)

        Profiler.instance().profile("skyframeActionExecutor.prepareForExecution").use { c ->
            prepareSkyframeActionExecutorForExecution(
                reporter, executor, options, actionCacheChecker, outputDirectoryHelper
            )
        }
        resourceManager.resetResourceUsage()
        try {
            setExecutionProgressReceiver(executionProgressReceiver)
            val targetKeys: Iterable<TargetCompletionKey?> =
                TargetCompletionValue.keys(
                    targetsToBuild,
                    topLevelArtifactContext,
                    com.google.common.collect.Sets.union<ConfiguredTarget?>(parallelTests, exclusiveTests)
                )
            val aspectKeys: Iterable<SkyKey?>? = AspectCompletionValue.keys(aspects, topLevelArtifactContext)
            val testKeys: Iterable<SkyKey?> =
                TestCompletionValue.keys(
                    parallelTests, topLevelArtifactContext,  /* exclusiveTesting= */false
                )
            val evaluationContext: com.google.devtools.build.skyframe.EvaluationContext? =
                newEvaluationContextBuilder()
                    .setKeepGoing(options.getOptions<O?>(KeepGoingOption::class.java).getKeepGoing())
                    .setParallelism(options.getOptions<O?>(BuildRequestOptions::class.java).jobs)
                    .setEventHandler(reporter)
                    .setExecutionPhase()
                    .build()
            return memoizingEvaluator.evaluate<T?>(
                com.google.common.collect.Iterables.concat(
                    Artifact.keys(artifactsToBuild),
                    targetKeys,
                    aspectKeys,
                    testKeys
                ),
                evaluationContext
            )
        } finally {
            // Also releases thread locks.
            resourceManager.resetResourceUsage()
            cleanUpAfterSingleEvaluationWithActionExecution(reporter)
        }
    }

    fun setExecutionProgressReceiver(
        executionProgressReceiver: EvaluationProgressReceiver?
    ) {
        progressReceiver.executionProgressReceiver = executionProgressReceiver
    }

    fun prepareSkyframeActionExecutorForExecution(
        reporter: com.google.devtools.build.lib.events.Reporter?,
        executor: Executor?,
        options: com.google.devtools.common.options.OptionsProvider,
        actionCacheChecker: ActionCacheChecker?,
        outputDirectoryHelper: ActionOutputDirectoryHelper?
    ) {
        val keepStateAfterBuild =
            tracksStateForIncrementality()
                    && options.getOptions<O?>(KeepStateAfterBuildOption::class.java).getKeepStateAfterBuild()
        skyframeActionExecutor.prepareForExecution(
            reporter,
            executor,
            options,
            actionCacheChecker,
            outputDirectoryHelper,
            outputService,
            keepStateAfterBuild
        )
    }

    /** Asks the Skyframe evaluator to run a single exclusive test.  */
    @Throws(java.lang.InterruptedException::class)
    fun runExclusiveTest(
        reporter: com.google.devtools.build.lib.events.Reporter?,
        resourceManager: ResourceManager,
        executor: Executor?,
        exclusiveTest: ConfiguredTarget,
        options: com.google.devtools.common.options.OptionsProvider,
        actionCacheChecker: ActionCacheChecker?,
        outputDirectoryHelper: ActionOutputDirectoryHelper?,
        topLevelArtifactContext: TopLevelArtifactContext?
    ): EvaluationResult<*> {
        checkActive()
        com.google.common.base.Preconditions.checkState(actionLogBufferPathGenerator != null)

        Profiler.instance().profile("skyframeActionExecutor.prepareForExecution").use { c ->
            prepareSkyframeActionExecutorForExecution(
                reporter, executor, options, actionCacheChecker, outputDirectoryHelper
            )
        }
        resourceManager.resetResourceUsage()
        try {
            val testKeys: Iterable<SkyKey?> =
                TestCompletionValue.keys(
                    com.google.common.collect.ImmutableSet.of<ConfiguredTarget?>(exclusiveTest),
                    topLevelArtifactContext,  /* exclusiveTesting= */
                    true
                )
            return evaluate<T?>(
                testKeys,  /* keepGoing= */
                options.getOptions<O?>(KeepGoingOption::class.java).getKeepGoing(),  /* numThreads= */
                options.getOptions<O?>(BuildRequestOptions::class.java).jobs,
                reporter
            )
        } finally {
            // Also releases thread locks.
            resourceManager.resetResourceUsage()
            cleanUpAfterSingleEvaluationWithActionExecution(reporter)
        }
    }

    @Throws(java.lang.InterruptedException::class)
    fun runExclusiveTestSkymeld(
        eventHandler: ExtendedEventHandler?,
        resourceManager: ResourceManager,
        testCompletionKey: SkyKey,
        keepGoing: Boolean,
        numThreads: Int
    ): EvaluationResult<SkyValue?> {
        checkActive()
        com.google.common.base.Preconditions.checkState(actionLogBufferPathGenerator != null)

        resourceManager.resetResourceUsage()
        try {
            return evaluate<SkyValue?>(
                com.google.common.collect.ImmutableSet.of<SkyKey?>(testCompletionKey),
                keepGoing,
                numThreads,
                eventHandler
            )
        } finally {
            resourceManager.resetResourceUsage()
        }
    }

    @com.google.common.annotations.VisibleForTesting
    fun prepareBuildingForTestingOnly(
        reporter: com.google.devtools.build.lib.events.Reporter?,
        executor: Executor?,
        options: com.google.devtools.common.options.OptionsProvider,
        checker: ActionCacheChecker?,
        outputDirectoryHelper: ActionOutputDirectoryHelper?
    ) {
        prepareSkyframeActionExecutorForExecution(
            reporter, executor, options, checker, outputDirectoryHelper
        )
    }

    /**
     * Sets the execution salt and deletes all action execution nodes if it has changed since the last
     * time it was set.
     */
    @Throws(AbruptExitException::class)
    fun setSaltAndDeleteActionsIfChanged(executionSalt: String?) {
        Profiler.instance().profile("setSaltAndDeleteActionsIfChanged").use { c ->
            if (lastExecutionSalt != null && lastExecutionSalt != executionSalt) {
                memoizingEvaluator.delete(java.util.function.Predicate { k: SkyKey? -> k.functionName() == SkyFunctions.ACTION_EXECUTION })
            }
            lastExecutionSalt = executionSalt
        }
    }

    @Throws(java.lang.InterruptedException::class)
    fun targetPatterns(
        patternSkyKeys: Iterable<out SkyKey?>?,
        numThreads: Int,
        keepGoing: Boolean,
        eventHandler: ExtendedEventHandler?
    ): EvaluationResult<SkyValue?> {
        checkActive()
        val evaluationContext: com.google.devtools.build.skyframe.EvaluationContext? =
            newEvaluationContextBuilder()
                .setKeepGoing(keepGoing)
                .setParallelism(numThreads)
                .setEventHandler(eventHandler)
                .build()
        return memoizingEvaluator.evaluate<SkyValue?>(patternSkyKeys, evaluationContext)
    }

    fun getConfiguration(
        eventHandler: ExtendedEventHandler?, configurationKey: BuildConfigurationKey?
    ): BuildConfigurationValue? {
        if (configurationKey == null) {
            return null
        }
        return evaluateSkyKeys(eventHandler, com.google.common.collect.ImmutableList.of<SkyKey?>(configurationKey)).get(
            configurationKey
        ) as BuildConfigurationValue?
    }

    /**
     * Returns the configurations corresponding to the given sets of build options. Output order is
     * the same as input order.
     * 
     * @throws InvalidConfigurationException if any build options produces an invalid configuration
     */
    // TODO(ulfjack): Remove this legacy method after switching to the Skyframe-based implementation.
    @Throws(InvalidConfigurationException::class)
    fun getConfiguration(
        eventHandler: ExtendedEventHandler?, buildOptions: BuildOptions?, keepGoing: Boolean
    ): BuildConfigurationValue {
        // Prepare the Skyframe inputs.
        val buildConfigurationKey: BuildConfigurationKey =
            createBuildConfigurationKey(eventHandler, buildOptions)

        // Skyframe-evaluate the configurations and throw errors if any.
        val evalResult: EvaluationResult<SkyValue?> =
            evaluateSkyKeys(
                eventHandler,
                com.google.common.collect.ImmutableList.of<SkyKey?>(buildConfigurationKey),
                keepGoing
            )
        if (evalResult.hasError()) {
            val firstError: MutableMap.MutableEntry<SkyKey?, com.google.devtools.build.skyframe.ErrorInfo>? =
                com.google.common.collect.Iterables.get<MutableMap.MutableEntry<SkyKey?, com.google.devtools.build.skyframe.ErrorInfo>?>(
                    evalResult.errorMap().entrySet(),
                    0
                )
            val error: com.google.devtools.build.skyframe.ErrorInfo = firstError.getValue()
            val e: Throwable? = error.getException()
            when (e) {
                -> throw invalidConfigurationException
                -> throw InvalidConfigurationException(detailedException.detailedExitCode, e)
                null, -> {
                    if (e == null && !error.getCycleInfo().isEmpty()) {
                        cyclesReporter.reportCycles(error.getCycleInfo(), firstError.getKey(), eventHandler)
                        throw InvalidConfigurationException(
                            "cannot load build configuration because of this cycle", Code.CYCLE
                        )
                    }
                    throw java.lang.IllegalStateException(
                        "Unknown error during configuration creation evaluation", e
                    )
                }
            }
        }

        // Prepare and return the results.
        return evalResult.get(buildConfigurationKey) as BuildConfigurationValue
    }

    fun getConfigurations(
        eventHandler: ExtendedEventHandler?, keys: MutableCollection<BuildConfigurationKey?>
    ): MutableMap<BuildConfigurationKey?, BuildConfigurationValue?> {
        val evaluationResult: EvaluationResult<SkyValue?> = evaluateSkyKeys(eventHandler, keys)
        return keys.stream()
            .collect(
                com.google.common.collect.ImmutableMap.toImmutableMap<BuildConfigurationKey?, BuildConfigurationKey?, BuildConfigurationValue?>(
                    com.google.common.base.Functions.identity<BuildConfigurationKey?>(),
                    java.util.function.Function { key: BuildConfigurationKey? -> evaluationResult.get(key) as BuildConfigurationValue? })
            )
    }

    val transitiveConfigurationKeys: MutableCollection<SkyKey>
        /** Returns every [BuildConfigurationKey] in the graph.  */
        get() = memoizingEvaluator.getDoneValues().keySet().stream()
            .filter(java.util.function.Predicate { key: SkyKey? -> SkyFunctions.BUILD_CONFIGURATION.equals(key.functionName()) })
            .collect(com.google.common.collect.ImmutableList.toImmutableList<SkyKey?>())

    /**
     * Only for testing:
     * 
     * 
     * Returns the Starlark transition that implements the exec transition, if one is defined for
     * this build. Else returns null (this build uses the Java-native exec transition).
     * 
     * 
     * Production code handles this in Bazel's analysis phase skyfunctions.
     */
    @Throws(StarlarkExecTransitionLoadingException::class, java.lang.InterruptedException::class)
    fun getStarlarkExecTransition(
        options: BuildOptions?, eventHandler: ExtendedEventHandler?
    ): StarlarkAttributeTransitionProvider? {
        return StarlarkExecTransitionLoader.loadStarlarkExecTransition(
            options,
            { bzlKey ->
                val result: EvaluationResult<SkyValue?>
                T > evaluate<T?>(
                    com.google.common.collect.ImmutableList.of<E?>(bzlKey),  /* keepGoing= */
                    false,  /* numThreads= */
                    DEFAULT_THREAD_COUNT,
                    eventHandler
                )
                if (result.hasError()) {
                    val firstError: MutableMap.MutableEntry<SkyKey?, com.google.devtools.build.skyframe.ErrorInfo>? =
                        com.google.common.collect.Iterables.get<MutableMap.MutableEntry<SkyKey?, com.google.devtools.build.skyframe.ErrorInfo>?>(
                            result.errorMap().entrySet(),
                            0
                        )
                    val error: com.google.devtools.build.skyframe.ErrorInfo = firstError.getValue()
                    val e: Throwable? = error.getException()
                    // Wrap loading failed exceptions
                    if (e != null) {
                        // If it's a BzlLoadFailedException, rethrow it directly.
                        com.google.common.base.Throwables.throwIfInstanceOf<X?>(e, BzlLoadFailedException::class.java)
                        // Otherwise, wrap it.
                        throw StarlarkExecTransitionLoadingException(e)
                    } else if (e == null && !error.getCycleInfo().isEmpty()) {
                        cyclesReporter.reportCycles(
                            error.getCycleInfo(), firstError.getKey(), eventHandler
                        )
                        throw StarlarkExecTransitionLoadingException(
                            "Unexpected cycle in exec transition dependencies"
                        )
                    }
                    throw java.lang.IllegalStateException("Unknown error while creating exec transition", e)
                }
                result.get(bzlKey) as BzlLoadValue?
            })
            .orElse(null)
    }

    @Throws(InvalidConfigurationException::class)
    private fun createBuildConfigurationKey(
        eventHandler: ExtendedEventHandler?, buildOptions: BuildOptions?
    ): BuildConfigurationKey {
        val key: BuildConfigurationKeyValue.Key = BuildConfigurationKeyValue.Key.create(buildOptions)
        val evaluationResult: EvaluationResult<SkyValue?> =
            evaluateSkyKeys(eventHandler, com.google.common.collect.ImmutableSet.of<SkyKey?>(key))
        // Handle all possible errors by reporting them to the user.
        if (evaluationResult.hasError()) {
            val firstError: MutableMap.MutableEntry<SkyKey?, com.google.devtools.build.skyframe.ErrorInfo>? =
                com.google.common.collect.Iterables.get<MutableMap.MutableEntry<SkyKey?, com.google.devtools.build.skyframe.ErrorInfo>?>(
                    evaluationResult.errorMap().entrySet(),
                    0
                )
            val errorKey: SkyKey? = firstError.getKey()
            val error: com.google.devtools.build.skyframe.ErrorInfo = firstError.getValue()
            val e: Throwable? = error.getException()

            if (e != null) {
                // Wrap exceptions related to loading
                if (e is NoSuchThingException) {
                    throw InvalidConfigurationException(e.getDetailedExitCode(), e)
                }
                com.google.common.base.Throwables.throwIfInstanceOf<X?>(e, InvalidConfigurationException::class.java)
                // If we get here, e is non-null but not an InvalidConfigurationException, so wrap it and
                // throw.
                throw InvalidConfigurationException(Code.PLATFORM_MAPPING_EVALUATION_FAILURE, e)
            } else if (!error.getCycleInfo().isEmpty()) {
                // This should not ever happen: there should not be a way for BuildConfigurationKeyValue.Key
                // to produce a skyframe cycle. Produce a basic error message for developers
                // to use to track down and fix the problem.
                // Unfortunately, there's no way to express this as an invariant, so manual inspection of
                // skyfunctions is the only way to prevent this.
                cyclesReporter.reportCycles(error.getCycleInfo(), errorKey, eventHandler)
                throw InvalidConfigurationException(
                    "cannot load build configuration key because of this cycle", Code.CYCLE
                )
            }

            // Unclear what could have happened if the exception is null and there isn't a cycle.
            throw java.lang.IllegalStateException("Unknown error during configuration creation evaluation", e)
        }
        val buildConfigurationKeyValue: BuildConfigurationKeyValue =
            evaluationResult.get(key) as BuildConfigurationKeyValue
        return buildConfigurationKeyValue.buildConfigurationKey()
    }

    /**
     * Evaluates the given sky keys, blocks, and returns their evaluation results. Fails fast on the
     * first evaluation error.
     */
    private fun evaluateSkyKeys(
        eventHandler: ExtendedEventHandler?, skyKeys: Iterable<out SkyKey?>?
    ): EvaluationResult<SkyValue?> {
        return evaluateSkyKeys(eventHandler, skyKeys, false)
    }

    /**
     * Evaluates the given sky keys, blocks, and returns their evaluation results. Enables/disables
     * "keep going" on evaluation errors as specified.
     */
    fun evaluateSkyKeys(
        eventHandler: ExtendedEventHandler?,
        skyKeys: Iterable<out SkyKey?>?,
        keepGoing: Boolean
    ): EvaluationResult<SkyValue?> {
        val result: EvaluationResult<SkyValue?>
        try {
            result =
                com.google.devtools.build.lib.concurrent.Uninterruptibles.callUninterruptibly<EvaluationResult<SkyValue?>>(
                    java.util.concurrent.Callable {
                        EnableAnalysisScope().use { closer ->
                            synchronized(valueLookupLock) {
                                return@callUninterruptibly evaluate<SkyValue?>(
                                    skyKeys, keepGoing,  /* numThreads= */DEFAULT_THREAD_COUNT, eventHandler
                                )
                            }
                        }
                    })
        } catch (e: java.lang.Exception) {
            throw java.lang.IllegalStateException(e) // Should never happen.
        }
        return result
    }

    /** Evaluates sky keys that require action execution and returns their evaluation results.  */
    fun evaluateSkyKeysWithExecution(
        reporter: com.google.devtools.build.lib.events.Reporter?,
        executor: Executor?,
        skyKeys: Iterable<out SkyKey?>?,
        options: com.google.devtools.common.options.OptionsProvider,
        actionCacheChecker: ActionCacheChecker?,
        outputDirectoryHelper: ActionOutputDirectoryHelper?
    ): EvaluationResult<SkyValue?> {
        prepareSkyframeActionExecutorForExecution(
            reporter, executor, options, actionCacheChecker, outputDirectoryHelper
        )
        try {
            return evaluateSkyKeys(
                reporter, skyKeys, options.getOptions<O?>(KeepGoingOption::class.java).getKeepGoing()
            )
        } finally {
            cleanUpAfterSingleEvaluationWithActionExecution(reporter)
        }
    }

    private inner class EnableAnalysisScope : java.lang.AutoCloseable {
        init {
            skyframeBuildView.enableAnalysis(true)
        }

        override fun close() {
            skyframeBuildView.enableAnalysis(false)
        }
    }

    /** Invalidates SkyFrame values that may have failed for transient reasons.  */
    abstract fun invalidateTransientErrors()

    /** Configures a given set of configured targets.  */
    @com.google.errorprone.annotations.CanIgnoreReturnValue
    @Throws(java.lang.InterruptedException::class)
    fun configureTargets(
        eventHandler: ExtendedEventHandler,
        labelToTargetMap: com.google.common.collect.ImmutableMap<Label?, Target?>,
        configuredTargetKeys: com.google.common.collect.ImmutableList<ConfiguredTargetKey>,
        topLevelAspectKeys: com.google.common.collect.ImmutableList<TopLevelAspectsKey?>,
        keepGoing: Boolean,
        executors: QuiescingExecutors
    ): ConfigureTargetsResult {
        checkActive()

        eventHandler.post(ConfigurationPhaseStartedEvent(analysisProgress))
        val evaluationContext: com.google.devtools.build.skyframe.EvaluationContext? =
            newEvaluationContextBuilder()
                .setParallelism(executors.analysisParallelism())
                .setKeepGoing(keepGoing)
                .setExecutor(executors.analysisExecutor)
                .setEventHandler(eventHandler)
                .build()
        val result: EvaluationResult<ActionLookupValue?> =
            memoizingEvaluator.evaluate<T?>(
                com.google.common.collect.Iterables.concat<Any?>(configuredTargetKeys, topLevelAspectKeys),
                evaluationContext
            )
        syscallCache.noteAnalysisPhaseEnded()

        val targetsWithConfiguration: com.google.common.collect.ImmutableList.Builder<TargetAndConfiguration?> =
            com.google.common.collect.ImmutableList.builderWithExpectedSize<TargetAndConfiguration?>(
                configuredTargetKeys.size()
            )
        val configuredTargets: com.google.common.collect.ImmutableSet.Builder<ConfiguredTarget?> =
            com.google.common.collect.ImmutableSet.builder<ConfiguredTarget?>()
        val aspects: com.google.common.collect.ImmutableMap.Builder<AspectKey?, ConfiguredAspect?> =
            com.google.common.collect.ImmutableMap.builder<AspectKey?, ConfiguredAspect?>()

        val graph: WalkableGraph? = result.getWalkableGraph()
        for (key in configuredTargetKeys) {
            val value: ConfiguredTargetValue? = result.get(key) as ConfiguredTargetValue?
            if (value == null) {
                continue
            }
            val configuredTarget: ConfiguredTarget? = value.getConfiguredTarget()
            configuredTargets.add(configuredTarget)

            val target: Target? = labelToTargetMap.get(key.getLabel())
            val configuration: BuildConfigurationValue? =
                getConfigurationFromGraph(graph, configuredTarget.getConfigurationKey())
            targetsWithConfiguration.add(TargetAndConfiguration(target, configuration))
            val actual: Label? =
                if (configuredTarget is AliasConfiguredTarget)
                    configuredTarget.getActual().getLabel()
                else
                    null
            eventHandler.post(TargetConfiguredEvent(target, configuration, actual))
        }

        for (key in topLevelAspectKeys) {
            val value: TopLevelAspectsValue? = result.get(key) as TopLevelAspectsValue?
            if (value == null) {
                continue  // Skip aspects that couldn't be applied to targets.
            }
            // The ConfiguredTargetKey in the AspectKey will vary from the TopLevelAspectKey's
            // ConfiguredTargetKey due to rule transitions. See the implementation in
            // ToplevelStarlarkAspectFunction#getConfiguredTargetKey().
            // Keep this logic in-sync with BuildDriverFunction#announceTopLevelAspectAnalyzed(), which
            // is the corresponding skymeld (merged analysis+execution) codepath.
            val firstAspectKey: AspectKey? =
                com.google.common.collect.Iterables.getFirst<AspectKey?>(value.getTopLevelAspectsMap().keySet(), null)
            if (firstAspectKey == null) {
                continue
            }
            val transitionedKey: ConfiguredTargetKey? = firstAspectKey.getBaseConfiguredTargetKey()
            val aspectCount: Int = value.getTopLevelAspectsMap().size()
            eventHandler.post(ToplevelAspectsIdentifiedEvent(transitionedKey, aspectCount))
            for (entry in value.getTopLevelAspectsMap().entrySet()) {
                val aspectKey: AspectKey? = entry.getKey()
                val aspectValue: AspectValue? = entry.getValue()
                aspects.put(aspectKey, aspectValue)
                val configuration: BuildConfigurationValue? =
                    getConfigurationFromGraph(graph, aspectKey.getConfigurationKey())
                eventHandler.post(
                    AspectConfiguredEvent(
                        aspectKey.getLabel(),  /* aspectClassName= */
                        aspectKey.getAspectClass().getName(),  /* aspectDescription= */
                        aspectKey.getAspectDescriptor().getDescription(),
                        configuration
                    )
                )
            }
        }

        return ConfigureTargetsResult(
            result,
            configuredTargets.build(),
            aspects.buildOrThrow(),
            targetsWithConfiguration.build(),
            this.packageRoots
        )
    }

    @get:com.google.errorprone.annotations.ForOverride
    protected val packageRoots: PackageRoots?
        get() = MapAsPackageRoots(collectPackageRoots())

    /** Result of a call to [.configureTargets].  */
    class ConfigureTargetsResult(
        evaluationResult: EvaluationResult<ActionLookupValue?>?,
        configuredTargets: com.google.common.collect.ImmutableSet<ConfiguredTarget?>?,
        aspects: com.google.common.collect.ImmutableMap<AspectKey?, ConfiguredAspect?>?,
        targetsWithConfiguration: com.google.common.collect.ImmutableList<TargetAndConfiguration?>?,
        packageRoots: PackageRoots?
    ) {
        val evaluationResult: EvaluationResult<ActionLookupValue?>?
        val configuredTargets: com.google.common.collect.ImmutableSet<ConfiguredTarget?>?
        val aspects: com.google.common.collect.ImmutableMap<AspectKey?, ConfiguredAspect?>?
        val targetsWithConfiguration: com.google.common.collect.ImmutableList<TargetAndConfiguration?>?
        val packageRoots: PackageRoots?

        init {
            this.evaluationResult = evaluationResult
            this.configuredTargets = configuredTargets
            this.aspects = aspects
            this.targetsWithConfiguration = targetsWithConfiguration
            this.packageRoots = packageRoots
        }
    }

    /** Returns a map of collected package names to root paths.  */
    private fun collectPackageRoots(): com.google.common.collect.ImmutableMap<PackageIdentifier?, Root?> {
        val roots: MutableMap<PackageIdentifier?, Root?> = ConcurrentHashMap<PackageIdentifier?, Root?>()
        memoizingEvaluator
            .getInMemoryGraph()
            .parallelForEach(
                java.util.function.Consumer { nodeEntry: InMemoryNodeEntry? ->
                    val key: SkyKey = nodeEntry.getKey()
                    if (key is PackageIdentifier && nodeEntry.isDone()) {
                        val packageValue: PackageValue? = nodeEntry.getValue() as PackageValue?
                        if (packageValue != null) { // Null for errors e.g. "no such package"
                            roots.put(key as PackageIdentifier?, packageValue.getPackage().getSourceRoot())
                        }
                    }
                })
        return com.google.common.collect.ImmutableMap.copyOf<PackageIdentifier?, Root?>(roots)
    }

    fun clearSyscallCache() {
        syscallCache.clear()
    }

    @Throws(java.lang.InterruptedException::class)
    private fun clearPlatformMappingCache() {
        if (platformMappingKey == null) {
            return
        }
        val platformMappingValue: SkyValue? = memoizingEvaluator.getExistingValue(platformMappingKey)
        if (platformMappingValue != null) {
            (platformMappingValue as PlatformMappingValue).clearMappingCache()
        }
    }

    fun setConflictCheckingModeInThisBuild(
        conflictCheckingModeInThisBuild: ConflictCheckingMode?
    ) {
        this.conflictCheckingModeInThisBuild = conflictCheckingModeInThisBuild
    }

    /**
     * Evaluates the given collections of CT/Aspect BuildDriverKeys. This is part of
     * https://github.com/bazelbuild/bazel/issues/14057, internal: b/147350683.
     */
    @Throws(java.lang.InterruptedException::class)
    fun evaluateBuildDriverKeys(
        eventHandler: ExtendedEventHandler,
        buildDriverCTKeys: MutableSet<BuildDriverKey?>?,
        buildDriverAspectKeys: MutableSet<BuildDriverKey?>?,
        workspaceStatusArtifacts: com.google.common.collect.ImmutableList<Artifact?>?,
        keepGoing: Boolean,
        executionParallelism: Int,
        executor: QuiescingExecutor?
    ): EvaluationResult<SkyValue?> {
        checkActive()
        buildDriverFunction.setShouldCheckForConflictWithTraversal(
            { conflictCheckingModeInThisBuild === WITH_TRAVERSAL })
        if (conflictCheckingModeInThisBuild !== NONE) {
            initializeSkymeldConflictFindingStates()
        }
        eventHandler.post(ConfigurationPhaseStartedEvent(analysisProgress))
        // For the workspace status actions.
        eventHandler.post(SomeExecutionStartedEvent.Companion.notCountedInExecutionTime())
        val evaluationContext: com.google.devtools.build.skyframe.EvaluationContext? =
            newEvaluationContextBuilder()
                .setKeepGoing(keepGoing)
                .setParallelism(executionParallelism)
                .setExecutor(executor)
                .setEventHandler(eventHandler)
                .setMergingSkyframeAnalysisExecutionPhases(true)
                .build()
        return memoizingEvaluator.evaluate<T?>(
            com.google.common.collect.Iterables.concat(
                buildDriverCTKeys, buildDriverAspectKeys, Artifact.keys(workspaceStatusArtifacts)
            ),
            evaluationContext
        )
    }

    /** Called after a single Skyframe evaluation that involves action execution.  */
    private fun cleanUpAfterSingleEvaluationWithActionExecution(eventHandler: ExtendedEventHandler?) {
        setExecutionProgressReceiver(null)
        actionRewindStrategy.reset(eventHandler)
        skyframeActionExecutor.executionOver()
    }

    /**
     * Clears the various states required for execution after ALL action execution in the build is
     * done.
     */
    fun clearExecutionStatesSkymeld(eventHandler: ExtendedEventHandler?) {
        // In case of a very early error in the analysis/execution phase, there could be a race between
        // the watchdog being set and this cleanup code.
        // No risk of NPE due to check-then-act: if the watchdog is non-null, it'll only be set to null
        // here.
        if (watchdog != null) {
            watchdog.stop()
            watchdog = null
        }
        cleanUpAfterSingleEvaluationWithActionExecution(eventHandler)
        statusReporterRef.get().unregisterFromEventBus()
        setActionExecutionProgressReportingObjects(null, null, null)
        consumedArtifactsTracker = null
    }

    /**
     * Checks the given action lookup values for action conflicts. Values satisfying the returned
     * predicate are known to be transitively error-free from action conflicts or other analysis
     * failures. [.resetActionConflictsStoredInSkyframe] must be called after this to free
     * memory coming from this call.
     */
    @Throws(java.lang.InterruptedException::class)
    fun filterActionConflictsForConfiguredTargetsAndAspects(
        eventHandler: ExtendedEventHandler?,
        keys: Iterable<ActionLookupKey?>?,
        actionConflicts: com.google.common.collect.ImmutableMap<ActionAnalysisMetadata?, ActionConflictException?>?,
        topLevelArtifactContext: TopLevelArtifactContext?
    ): TopLevelActionConflictReport {
        checkActive()
        ACTION_CONFLICTS.set(injectable(), actionConflicts)
        // This work is CPU-bound, so use the number of available processors.
        val result: EvaluationResult<ActionLookupConflictFindingValue?> =
            evaluate<ActionLookupConflictFindingValue?>(
                TopLevelActionLookupConflictFindingFunction.Companion.keys(
                    keys,
                    topLevelArtifactContext
                ),  /* keepGoing= */
                true,  /* numThreads= */
                java.lang.Runtime.getRuntime().availableProcessors(),
                eventHandler
            )

        // Remove top-level action-conflict detection values for memory efficiency. Non-top-level ones
        // are removed below. We are OK with this mini-phase being non-incremental as the failure mode
        // of action conflict is rare.
        memoizingEvaluator.delete(
            SkyFunctionName.functionIs(SkyFunctions.TOP_LEVEL_ACTION_LOOKUP_CONFLICT_FINDING)
        )
        return TopLevelActionConflictReport(result, topLevelArtifactContext)
    }

    /**
     * Encapsulation of the result of #filterActionConflictsForConfiguredTargetsAndAspects() allowing
     * callers to determine which top-level keys did not have analysis errors and retrieve the
     * ActionConflictException for those that keys that specifically have conflicts.
     */
    internal class TopLevelActionConflictReport(
        result: EvaluationResult<ActionLookupConflictFindingValue?>,
        topLevelArtifactContext: TopLevelArtifactContext?
    ) {
        val result: EvaluationResult<ActionLookupConflictFindingValue?>
        private val topLevelArtifactContext: TopLevelArtifactContext?

        init {
            this.result = result
            this.topLevelArtifactContext = topLevelArtifactContext
        }

        fun isErrorFree(k: ActionLookupKey?): Boolean {
            return (result.get(
                com.google.devtools.build.lib.skyframe.TopLevelActionLookupConflictFindingFunction.Key.Companion.create(
                    k,
                    topLevelArtifactContext
                )
            )
                    != null)
        }

        /**
         * Get the ActionConflictException produced for the given ActionLookupKey. Will throw if the
         * given key [is error-free][.isErrorFree].
         */
        fun getConflictException(k: ActionLookupKey?): java.util.Optional<ActionConflictException?> {
            val errorInfo: com.google.devtools.build.skyframe.ErrorInfo =
                result.getError(
                    com.google.devtools.build.lib.skyframe.TopLevelActionLookupConflictFindingFunction.Key.Companion.create(
                        k,
                        topLevelArtifactContext
                    )
                )
            val e: java.lang.Exception? = errorInfo.getException()
            return java.util.Optional.ofNullable<ActionConflictException?>(
                if (e is ActionConflictException) e as ActionConflictException? else null
            )
        }
    }

    /**
     * Clears all action conflicts stored in skyframe that were discovered by a call to [ ][.filterActionConflictsForConfiguredTargetsAndAspects].
     * 
     * 
     * This function must be called after a call to [ ][.filterActionConflictsForConfiguredTargetsAndAspects], either directly (in the case of
     * no-keep_going evaluations) or indirectly by [.filterActionConflictsForTopLevelArtifacts]
     * in keep_going evaluations.
     */
    fun resetActionConflictsStoredInSkyframe() {
        memoizingEvaluator.delete(
            SkyFunctionName.functionIs(SkyFunctions.ACTION_LOOKUP_CONFLICT_FINDING)
        )
    }

    fun resetBuildDriverFunction() {
        buildDriverFunction.resetStates()
    }

    // Initialize the various conflict-finding states. These are good for 1 invocation.
    private fun initializeSkymeldConflictFindingStates() {
        incrementalArtifactConflictFinder =
            IncrementalArtifactConflictFinder(
                MapBasedActionGraph(actionKeyContext),
                SkyframeExecutorWrappingWalkableGraph.Companion.of(this)
            )
    }

    /** Clear the incremental conflict finding states to save memory.  */
    fun clearIncrementalArtifactConflictFindingStates() {
        // Create a local ref for shutting down, in case there's a race.
        val localRef: IncrementalArtifactConflictFinder? = incrementalArtifactConflictFinder
        if (localRef != null) {
            localRef.shutdown()
        }
        incrementalArtifactConflictFinder = null
        conflictCheckingModeInThisBuild = NONE
    }

    fun getCheckerForConflictCheckingMode(
        expectedModeFromCaller: ConflictCheckingMode?
    ): IncrementalArtifactConflictFinder? {
        return if (conflictCheckingModeInThisBuild === expectedModeFromCaller)
            incrementalArtifactConflictFinder
        else
            null
    }

    val ephemeralCheckIfOutputConsumed: EphemeralCheckIfOutputConsumed?
        /** Whether an artifact is consumed in this build.  */
        get() = consumedArtifactsTracker

    /**
     * Checks the action lookup values owning the given artifacts for action conflicts. Artifacts
     * satisfying the returned predicate are known to be transitively free from action conflicts.
     * [.filterActionConflictsForConfiguredTargetsAndAspects] must be called before this is
     * called in order to populate the known action conflicts.
     * 
     * 
     * This method is only called in keep-going mode, since otherwise any known action conflicts
     * will immediately fail the build.
     */
    @Throws(java.lang.InterruptedException::class)
    fun filterActionConflictsForTopLevelArtifacts(
        eventHandler: ExtendedEventHandler?, artifacts: MutableCollection<Artifact?>
    ): com.google.common.base.Predicate<Artifact?> {
        checkActive()
        // This work is CPU-bound, so use the number of available processors.
        val result: EvaluationResult<ActionLookupConflictFindingValue?> =
            evaluate<ActionLookupConflictFindingValue?>(
                com.google.common.collect.Iterables.transform<Artifact?, Any?>(
                    artifacts,
                    ActionLookupConflictFindingValue::key
                ),  /* keepGoing= */
                true,  /* numThreads= */
                java.lang.Runtime.getRuntime().availableProcessors(),
                eventHandler
            )

        // Remove remaining action-conflict detection values immediately for memory efficiency.
        resetActionConflictsStoredInSkyframe()

        return com.google.common.base.Predicate { a: Artifact? -> result.get(ActionLookupConflictFindingValue.key(a)) != null }
    }

    /**
     * For internal use in queries: performs a graph update to make sure the transitive closure of the
     * specified `universeKey` is present in the graph, and returns the [ ].
     * 
     * 
     * The graph update is unconditionally done in keep-going mode, so that the query is guaranteed
     * a complete graph to work on.
     */
    @Throws(java.lang.InterruptedException::class)
    override fun prepareAndGet(
        roots: MutableSet<SkyKey?>?, evaluationContext: com.google.devtools.build.skyframe.EvaluationContext
    ): EvaluationResult<SkyValue?> {
        val evaluationContextToUse: com.google.devtools.build.skyframe.EvaluationContext? =
            evaluationContext.builder().setKeepGoing(true).setStoreExactCycles(false).build()
        return memoizingEvaluator.evaluate<SkyValue?>(roots, evaluationContextToUse)
    }

    fun maybeGetHardcodedUniverseScope(): java.util.Optional<UniverseScope?> {
        return java.util.Optional.empty<UniverseScope?>()
    }

    /** Returns the generating action of a given artifact (`null` if it's a source artifact).  */
    @Throws(java.lang.InterruptedException::class)
    private fun getGeneratingAction(
        eventHandler: ExtendedEventHandler?, artifact: Artifact
    ): ActionAnalysisMetadata? {
        if (artifact.isSourceArtifact()) {
            return null
        }

        val generatingActionKey: ActionLookupData =
            (artifact as Artifact.DerivedArtifact).getGeneratingActionKey()

        val lookupKey: ActionLookupKey = generatingActionKey.getActionLookupKey()

        synchronized(valueLookupLock) {
            // Note that this will crash (attempting to run a configured target value builder after
            // analysis) after a failed --nokeep_going analysis in which the configured target that
            // failed was a (transitive) dependency of the configured target that should generate
            // this action. We don't expect callers to query generating actions in such cases.
            val result: EvaluationResult<ActionLookupValue> =
                evaluate<ActionLookupValue>(
                    com.google.common.collect.ImmutableList.of<Any?>(lookupKey),  /* keepGoing= */
                    false,  /* numThreads= */
                    java.lang.Runtime.getRuntime().availableProcessors(),
                    eventHandler
                )
            if (result.hasError()) {
                return null
            }
            val actionLookupValue: ActionLookupValue = result.get(lookupKey)
            return actionLookupValue.getActions().get(generatingActionKey.getActionIndex())
        }
    }

    /**
     * Returns an action graph.
     * 
     * 
     * For legacy compatibility only.
     */
    fun getActionGraph(eventHandler: ExtendedEventHandler?): ActionGraph? {
        return ActionGraph { artifact ->
            try {
                return@ActionGraph com.google.devtools.build.lib.concurrent.Uninterruptibles.callUninterruptibly<Any?>(
                    java.util.concurrent.Callable { this@SkyframeExecutor.getGeneratingAction(eventHandler, artifact) })
            } catch (e: java.lang.Exception) {
                throw java.lang.IllegalStateException(
                    "Error getting generating action: " + artifact.prettyPrint(), e
                )
            }
        }
    }

    fun getPackageManager(): PackageManager {
        return packageManager
    }

    fun getQueryTransitivePackagePreloader(): QueryTransitivePackagePreloader {
        return queryTransitivePackagePreloader
    }

    @com.google.common.annotations.VisibleForTesting
    fun newTargetPatternPreloader(): TargetPatternPreloader? {
        return SkyframeTargetPatternEvaluator(this)
    }

    fun getActionKeyContext(): ActionKeyContext {
        return actionKeyContext
    }

    val digestFunction: DigestHashFunction?
        // TODO(janakr): Is there a better place for this?
        get() = fileSystem.getDigestFunction()

    /** Exception thrown when [.getDoneSkyValueForIntrospection] fails.  */
    class FailureToRetrieveIntrospectedValueException : java.lang.Exception {
        private constructor(message: String?) : super(message)

        private constructor(message: String?, cause: java.lang.InterruptedException?) : super(message, cause)
    }

    /**
     * Returns the value of a node that the caller knows to be done. May be called intra-evaluation.
     * Null values and interrupts are unexpected, and will cause a [ ]. Callers should handle gracefully, probably via
     * [BugReporter].
     */
    @com.google.devtools.build.lib.concurrent.ThreadSafety.ThreadSafe
    @Throws(FailureToRetrieveIntrospectedValueException::class)
    fun getDoneSkyValueForIntrospection(key: SkyKey?): SkyValue {
        val entry: NodeEntry?
        try {
            entry = memoizingEvaluator.getExistingEntryAtCurrentlyEvaluatingVersion(key)
        } catch (e: java.lang.InterruptedException) {
            throw FailureToRetrieveIntrospectedValueException(
                "Unexpected interrupt when fetching " + key, e
            )
        }
        if (entry == null || !entry.isDone()) {
            throw FailureToRetrieveIntrospectedValueException(
                "Entry for " + key + " not found or null: " + entry
            )
        }
        val value: SkyValue?
        try {
            value = entry.getValue()
        } catch (e: java.lang.InterruptedException) {
            throw FailureToRetrieveIntrospectedValueException(
                "Entry for " + key + " did not have locally present value: " + entry, e
            )
        }
        if (value == null) {
            throw FailureToRetrieveIntrospectedValueException(
                "Entry for " + key + " had null value: " + entry
            )
        }
        return value
    }

    internal inner class SkyframePackageLoader {
        /**
         * Looks up a particular package (mostly used after the loading phase, so packages should
         * already be present, but occasionally used pre-loading phase). Use should be discouraged,
         * since this cannot be used inside a Skyframe evaluation, and concurrent calls are
         * synchronized.
         * 
         * 
         * Note that this method needs to be synchronized since InMemoryMemoizingEvaluator.evaluate()
         * method does not support concurrent calls.
         */
        @Throws(java.lang.InterruptedException::class, NoSuchPackageException::class)
        fun getPackage(eventHandler: ExtendedEventHandler?, pkgName: PackageIdentifier): Package {
            val keys: com.google.common.collect.ImmutableList<SkyKey?> =
                com.google.common.collect.ImmutableList.of<SkyKey?>(pkgName)
            val result: EvaluationResult<PackageValue?>
            synchronized(valueLookupLock) {
                // Loading a single package shouldn't be too bad to do in keep_going mode even if the build
                // overall is in nokeep_going mode: the worst that happens is we parse some unnecessary
                // .bzl files.
                result =
                    evaluate<PackageValue?>(
                        keys,  /* keepGoing= */true,  /* numThreads= */DEFAULT_THREAD_COUNT, eventHandler
                    )
            }
            val error: com.google.devtools.build.skyframe.ErrorInfo? = result.getError(pkgName)
            if (error != null) {
                checkCycles(eventHandler, pkgName, pkgName, error)
                val e: Throwable = com.google.common.base.Preconditions.checkNotNull<java.lang.Exception>(
                    error.getException(),
                    "%s %s",
                    pkgName,
                    error
                )
                // PackageFunction should be catching, swallowing, and rethrowing all transitive errors as
                // NoSuchPackageExceptions or constructing packages with errors, since we're in keep_going
                // mode.
                com.google.common.base.Throwables.throwIfInstanceOf<X?>(e, NoSuchPackageException::class.java)
                throw java.lang.IllegalStateException(
                    ("Unexpected Exception type from PackageValue for '"
                            + pkgName
                            + "'' with error: "
                            + error),
                    e
                )
            }
            return result.get(pkgName).getPackage()
        }

        /**
         * Returns the BUILD file target of the given package. Mostly used after the loading phase, so
         * packages should already be present, but occasionally used pre-loading phase. If the package
         * is not present, will load either the full package (if lazy macro expansion is disabled) or
         * just the package piece owning the BUILD file target (if lazy macro expansion is enabled).
         * 
         * 
         * Use should be discouraged, since this cannot be used inside a Skyframe evaluation, and
         * concurrent calls are synchronized.
         * 
         * 
         * This method contains a synchronized block since InMemoryMemoizingEvaluator.evaluate()
         * method does not support concurrent calls.
         */
        @Throws(
            java.lang.InterruptedException::class,
            NoSuchPackageException::class,
            NoSuchPackagePieceException::class
        )
        fun getBuildFile(eventHandler: ExtendedEventHandler?, pkgName: PackageIdentifier): InputFile {
            val packagePieceIdentifier: PackagePieceIdentifier.ForBuildFile =
                ForBuildFile(pkgName)
            var resultForBuildFile: EvaluationResult<PackagePieceValue.ForBuildFile?>? = null
            synchronized(valueLookupLock) {
                if (getLazyMacroExpansionPackages(eventHandler).contains(pkgName)) {
                    resultForBuildFile =
                        evaluate<PackagePieceValue.ForBuildFile?>(
                            com.google.common.collect.ImmutableList.of<Any?>(packagePieceIdentifier),  /* keepGoing= */
                            true,  /* numThreads= */
                            DEFAULT_THREAD_COUNT,
                            eventHandler
                        )
                }
            }
            if (resultForBuildFile == null) {
                // Need monolithic package.
                return getPackage(eventHandler, pkgName).getBuildFile()
            }
            val error: com.google.devtools.build.skyframe.ErrorInfo? =
                resultForBuildFile.getError(packagePieceIdentifier)
            if (error != null) {
                checkCycles(eventHandler, packagePieceIdentifier, pkgName, error)
                val e: Throwable = com.google.common.base.Preconditions.checkNotNull<java.lang.Exception>(
                    error.getException(),
                    "%s %s",
                    pkgName,
                    error
                )
                // Given a PackagePieceIdentifier.ForBuildFile, PackageFunction should be catching,
                // swallowing, and rethrowing all transitive errors as either NoSuchPackageExceptions or
                // NoSuchPackagePieceExceptions, or constructing packages with errors, since we're in
                // keep_going mode.
                com.google.common.base.Throwables.throwIfInstanceOf<X?>(e, NoSuchPackageException::class.java)
                com.google.common.base.Throwables.throwIfInstanceOf<X?>(e, NoSuchPackagePieceException::class.java)
                throw java.lang.IllegalStateException(
                    ("Unexpected Exception type from PackagePieceValue.ForBuildFile for '"
                            + packagePieceIdentifier
                            + "'' with error: "
                            + error),
                    e
                )
            }
            return resultForBuildFile.get(packagePieceIdentifier).getPackagePiece().getBuildFile()
        }

        @Throws(java.lang.InterruptedException::class)
        private fun getLazyMacroExpansionPackages(
            eventHandler: ExtendedEventHandler?
        ): LazyMacroExpansionPackages? {
            val key: SkyKey = PrecomputedValue.LAZY_MACRO_EXPANSION_PACKAGES.getKey()
            val lazyMacroExpansionPackagesResult: EvaluationResult<PrecomputedValue?> =
                evaluate<PrecomputedValue?>(
                    com.google.common.collect.ImmutableList.of<SkyKey?>(key),  /* keepGoing= */
                    true,  /* numThreads= */
                    DEFAULT_THREAD_COUNT,
                    eventHandler
                )
            return lazyMacroExpansionPackagesResult.get(key).get() as LazyMacroExpansionPackages?
        }

        @Throws(NoSuchPackageException::class)
        private fun checkCycles(
            eventHandler: ExtendedEventHandler?,
            topLevelKey: SkyKey?,
            pkgName: PackageIdentifier?,
            error: com.google.devtools.build.skyframe.ErrorInfo
        ) {
            if (!error.getCycleInfo().isEmpty()) {
                cyclesReporter.reportCycles(error.getCycleInfo(), topLevelKey, eventHandler)
                // This can only happen if a package is freshly loaded outside of the target parsing or
                // loading phase
                throw BuildFileContainsErrorsException(
                    pkgName, "Cycle encountered while loading package " + pkgName
                )
            }
        }

        /** Returns whether the given package should be consider deleted and thus should be ignored.  */
        fun isPackageDeleted(packageName: PackageIdentifier?): Boolean {
            return deletedPackages.get().contains(packageName)
        }

        fun getPackageLookupValue(pkgName: PackageIdentifier?): PackageLookupValue? {
            try {
                return memoizingEvaluator.getExistingValue(PackageLookupValue.key(pkgName)) as PackageLookupValue?
            } catch (e: java.lang.InterruptedException) {
                throw java.lang.IllegalStateException(
                    java.lang.String.format(
                        "Evaluator %s should not be interruptible (%s)", memoizingEvaluator, pkgName
                    ),
                    e
                )
            }
        }

        fun dumpPackages(out: PrintStream?) {
            this@SkyframeExecutor.dumpPackages(out)
        }
    }

    val evaluator: MemoizingEvaluator
        get() = memoizingEvaluator

    /**
     * Initializes and syncs the graph with the given options, readying it for the next evaluation.
     * 
     * 
     * At a minimum, [PackageOptions] and [BuildLanguageOptions] are expected to be
     * present in the given [OptionsProvider].
     * 
     * 
     * Returns precomputed information about the workspace if it is available at this stage. This
     * is an optimization allowing implementations which have such information to make it available
     * early in the build.
     */
    @com.google.errorprone.annotations.CanIgnoreReturnValue
    @Throws(java.lang.InterruptedException::class, AbruptExitException::class)
    open fun sync(
        eventHandler: ExtendedEventHandler?,
        pathPackageLocator: PathPackageLocator?,
        commandId: UUID?,
        clientEnv: MutableMap<String?, String?>?,
        tsgm: TimestampGranularityMonitor?,
        executors: QuiescingExecutors,
        options: com.google.devtools.common.options.OptionsProvider,
        commandName: String?,
        commandExecutes: Boolean
    ): WorkspaceInfoFromDiff? {
        getActionEnvFromOptions(options.getOptions<O?>(CoreOptions::class.java))
        val platformOptions: O? = options.getOptions<O?>(PlatformOptions::class.java)
        platformMappingKey = if (platformOptions != null) platformOptions.getPlatformMappingKey() else null
        val remoteOptions: RemoteOptions? = options.getOptions<RemoteOptions?>(RemoteOptions::class.java)
        setRemoteExecutionEnabled(remoteOptions != null && remoteOptions.isRemoteExecutionEnabled)
        cpuBoundSemaphore.set(getUpdatedSkyFunctionsSemaphore(options))
        syncPackageLoading(
            pathPackageLocator,
            commandId,
            clientEnv,
            tsgm,
            executors,
            options,
            commandName,
            commandExecutes,
            eventHandler
        )

        if (lastAnalysisDiscarded) {
            logger.atInfo().log("Discarding analysis cache because the previous invocation told us to")
            dropConfiguredTargetsNow(eventHandler)
            lastAnalysisDiscarded = false
        }
        return null
    }

    /** Determines the updated [.cpuBoundSemaphore] from the provided options.  */
    protected fun getUpdatedSkyFunctionsSemaphore(options: com.google.devtools.common.options.OptionsProvider): Semaphore? {
        val analysisOptions: AnalysisOptions? = options.getOptions<O?>(AnalysisOptions::class.java)
        if (analysisOptions == null) {
            return cpuBoundSemaphore.get() // Leaves as-is.
        }

        val newSize: Int = analysisOptions.getOomSensitiveSkyFunctionsSemaphoreSize()
        if (newSize == 0) {
            return null
        }
        return Semaphore(newSize)
    }

    @Throws(AbruptExitException::class)
    protected fun syncPackageLoading(
        pathPackageLocator: PathPackageLocator?,
        commandId: UUID?,
        clientEnv: MutableMap<String?, String?>?,
        tsgm: TimestampGranularityMonitor?,
        executors: QuiescingExecutors,
        options: com.google.devtools.common.options.OptionsProvider,
        commandName: String?,
        commandExecutes: Boolean,
        eventHandler: ExtendedEventHandler?
    ) {
        val packageOptions: PackageOptions? = options.getOptions<O?>(PackageOptions::class.java)
        Profiler.instance().profile("preparePackageLoading").use { c ->
            preparePackageLoading(
                pathPackageLocator,
                packageOptions,
                options.getOptions<BuildLanguageOptions?>(BuildLanguageOptions::class.java),
                commandId,
                clientEnv,
                executors,
                tsgm
            )
        }
        Profiler.instance().profile("setDeletedPackages").use { c ->
            setDeletedPackages(packageOptions.getDeletedPackagesOrEmptySet())
        }
        incrementalBuildMonitor = SkyframeIncrementalBuildMonitor()
        invalidateTransientErrors()
        sourceArtifactsSeen.reset()
        outputArtifactsSeen.reset()
        outputArtifactsFromActionCache.reset()
        topLevelArtifactsMetric.reset()
    }

    private fun getActionEnvFromOptions(opt: CoreOptions?) {
        // ImmutableMap does not support null values, so use a LinkedHashMap instead.
        val actionEnvironment: LinkedHashMap<String?, String?> = LinkedHashMap<String?, String?>()
        if (opt != null) {
            for (envVar in opt.getActionEnvironment()) {
                when (envVar) {
                    -> actionEnvironment.put(name, value)
                    -> actionEnvironment.put(name, null)
                    -> actionEnvironment.remove(name)
                }
            }
        }
        setActionEnv(actionEnvironment)
    }

    @com.google.common.annotations.VisibleForTesting
    fun setActionEnv(actionEnv: MutableMap<String?, String?>?) {
        PrecomputedValue.ACTION_ENV.set(injectable(), actionEnv)
    }

    private fun createCyclesReporter(): CyclesReporter {
        return CyclesReporter(
            TargetCycleReporter(packageManager),
            ActionArtifactCycleReporter(packageManager),
            TestExpansionCycleReporter(packageManager),
            RegisteredToolchainsCycleReporter(),
            RegisteredExecutionPlatformsCycleReporter(),  // TODO(ulfjack): The BzlLoadCycleReporter swallows previously reported cycles
            //  unconditionally! Is that intentional?
            BzlLoadCycleReporter(),
            BzlmodRepoCycleReporter()
        )
    }

    fun getCyclesReporter(): CyclesReporter? {
        return cyclesReporter
    }

    fun setActionExecutionProgressReportingObjects(
        supplier: ProgressSupplier?,
        completionReceiver: ActionCompletedReceiver?,
        statusReporter: ActionExecutionStatusReporter?
    ) {
        skyframeActionExecutor.setActionExecutionProgressReportingObjects(supplier, completionReceiver)
        this.statusReporterRef.set(statusReporter)
    }

    @Throws(AbruptExitException::class, java.lang.InterruptedException::class)
    abstract fun detectModifiedOutputFiles(
        modifiedOutputFiles: ModifiedFileSet?,
        lastExecutionTimeRange: com.google.common.collect.Range<Long?>?,
        outputChecker: OutputChecker?,
        fsvcThreads: Int
    )

    /**
     * Mark dirty values for deletion if they've been dirty for longer than N versions.
     * 
     * 
     * Specifying a value N means, if the current version is V and a value was dirtied (and has
     * remained so) in version U, and U + N &lt;= V, then the value will be marked for deletion and
     * purged in version V+1.
     */
    abstract fun deleteOldNodes(versionWindowForDirtyGc: Long)

    val packageProgressReceiver: PackageProgressReceiver?
        get() = packageProgress

    fun getBuildFilesByPriority(): com.google.common.collect.ImmutableList<BuildFileName?>? {
        return buildFilesByPriority
    }

    /**
     * Loads the given target patterns without applying any filters (such as removing non-test targets
     * if `--build_tests_only` is set).
     * 
     * @param eventHandler handler which accepts update events
     * @param targetPatterns patterns to be loaded
     * @param threadCount number of threads to use for this skyframe evaluation
     * @param keepGoing whether to attempt to ignore errors. See also [KeepGoingOption]
     */
    @Throws(TargetParsingException::class, java.lang.InterruptedException::class)
    fun loadTargetPatternsWithoutFilters(
        eventHandler: ExtendedEventHandler,
        targetPatterns: MutableList<String?>,
        relativeWorkingDirectory: PathFragment?,
        threadCount: Int,
        keepGoing: Boolean
    ): TargetPatternPhaseValue? {
        val key: SkyKey =
            TargetPatternPhaseValue.Companion.keyWithoutFilters(
                com.google.common.collect.ImmutableList.copyOf<String?>(targetPatterns), relativeWorkingDirectory
            )
        return getTargetPatternPhaseValue(eventHandler, targetPatterns, threadCount, keepGoing, key)
    }

    /**
     * Loads the given target patterns after applying filters configured through parameters and
     * options (such as removing non-test targets if `--build_tests_only` is set).
     * 
     * @param eventHandler handler which accepts update events
     * @param targetPatterns patterns to be loaded
     * @param threadCount number of threads to use for this skyframe evaluation
     * @param keepGoing whether to attempt to ignore errors. See also [KeepGoingOption]
     * @param determineTests whether to ignore any targets that aren't tests or test suites
     */
    @Throws(TargetParsingException::class, java.lang.InterruptedException::class)
    fun loadTargetPatternsWithFilters(
        eventHandler: ExtendedEventHandler,
        targetPatterns: MutableList<String?>,
        relativeWorkingDirectory: PathFragment?,
        options: LoadingOptions,
        threadCount: Int,
        keepGoing: Boolean,
        determineTests: Boolean
    ): TargetPatternPhaseValue? {
        val key: SkyKey =
            TargetPatternPhaseValue.Companion.key(
                com.google.common.collect.ImmutableList.copyOf<String?>(targetPatterns),
                relativeWorkingDirectory,
                options.getCompileOneDependency(),
                options.getBuildTestsOnly(),
                determineTests,
                com.google.common.collect.ImmutableList.copyOf(options.getBuildTagFilterList()),
                options.getBuildManualTests(),
                options.getExpandTestSuites(),
                TestFilter.forOptions(options)
            )
        return getTargetPatternPhaseValue(eventHandler, targetPatterns, threadCount, keepGoing, key)
    }

    @Throws(java.lang.InterruptedException::class, TargetParsingException::class)
    private fun getTargetPatternPhaseValue(
        eventHandler: ExtendedEventHandler,
        targetPatterns: MutableList<String?>?,
        threadCount: Int,
        keepGoing: Boolean,
        key: SkyKey
    ): TargetPatternPhaseValue? {
        val timer: com.google.common.base.Stopwatch = com.google.common.base.Stopwatch.createStarted()
        eventHandler.post(LoadingPhaseStartedEvent(packageProgress))
        val evalResult: EvaluationResult<TargetPatternPhaseValue?> =
            evaluate<TargetPatternPhaseValue?>(
                com.google.common.collect.ImmutableList.of<SkyKey?>(key),
                keepGoing,
                threadCount,
                eventHandler
            )
        tryThrowTargetParsingException(eventHandler, targetPatterns, key, evalResult)
        eventHandler.post(TargetParsingPhaseTimeEvent(timer.stop().elapsed().toMillis()))
        return evalResult.get(key)
    }

    @Throws(TargetParsingException::class)
    private fun tryThrowTargetParsingException(
        eventHandler: ExtendedEventHandler,
        targetPatterns: MutableList<String?>?,
        key: SkyKey?,
        evalResult: EvaluationResult<TargetPatternPhaseValue?>
    ) {
        if (evalResult.hasError()) {
            val errorInfo: com.google.devtools.build.skyframe.ErrorInfo = evalResult.getError(key)
            val exc: TargetParsingException?
            if (!errorInfo.getCycleInfo().isEmpty()) {
                exc =
                    TargetParsingException(
                        "cycles detected during target parsing", TargetPatterns.Code.CYCLE
                    )
                cyclesReporter.reportCycles(errorInfo.getCycleInfo(), key, eventHandler)
                // Fallback: we don't know which patterns failed, specifically, so we report the entire
                // set as being in error.
                eventHandler.post(PatternExpandingError.failed(targetPatterns, exc.getMessage()))
            } else {
                exc = constructNoCycleTargetParsingException(eventHandler, targetPatterns, errorInfo)
            }
            throw exc
        }
    }

    /**
     * Returns flag aliases from `MODULE.bazel` `flag_alias()` definitions.
     * 
     * 
     * These, along with whatever is set in `--flag_alias`, rewrite `--foo`-style
     * command line flags to canonical Starlark flags.
     * 
     * @param eventHandler handler for Skyframe events
     */
    @Throws(java.lang.InterruptedException::class)
    fun getFlagAliases(eventHandler: ExtendedEventHandler?): MutableMap<String?, String?> {
        val evalResult: EvaluationResult<BazelDepGraphValue?>
        BazelDepGraphValue > evaluate<T?>(
            com.google.common.collect.ImmutableList.of<Any?>(BazelDepGraphValue.KEY),
            false,
            DEFAULT_THREAD_COUNT,
            eventHandler
        )
        val bzlmodDepGraph: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            evalResult.get(BazelDepGraphValue.KEY).depGraph
        val aliasesMap: LinkedHashMap<String?, String?> = LinkedHashMap<String?, String?>()
        val rootModule: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            bzlmodDepGraph.entrySet().iterator().next().getValue()
        for (module in bzlmodDepGraph.entrySet()) {
            val flagAliases: com.google.common.collect.ImmutableMap<String?, String?> =
                module.getValue().getFlagAliases()
            for (flagAlias in flagAliases.entrySet()) {
                aliasesMap.put(
                    flagAlias.getKey(),
                    if (flagAlias.getValue().startsWith("//"))
                        module.getKey().getCanonicalRepoNameWithoutVersion() + flagAlias.getValue()
                    else
                        flagAlias.getValue()
                )
            }
            if (!module.getValue().getName().equals("rules_python")) {
                continue
            }
            // Don't apply hard-coded aliases if rules_python uses MODULE.bazel aliases.
            if (!module.getValue().getFlagAliases().isEmpty()) {
                continue
            }
            // Add Python flags that haven't already been added by rules_python's MODULE.bazel.
            PY_FLAG_ALIASES.entrySet().stream()
                .filter(java.util.function.Predicate { e: MutableMap.MutableEntry<String?, String?>? ->
                    !flagAliases.containsKey(
                        e.getKey()
                    )
                })
                .map<MutableMap.MutableEntry<String?, String?>?>(
                    java.util.function.Function { e: MutableMap.MutableEntry<String?, String?>? ->
                        if (rootModule.getName().equals("rules_python"))
                            java.util.Map.entry<String?, String?>(
                                e.getKey(),
                                e.getValue().substring(e.getValue().indexOf("/"))
                            )
                        else
                            e
                    })
                .forEach(java.util.function.Consumer { e: MutableMap.MutableEntry<String?, String?>? ->
                    aliasesMap.put(
                        e.getKey(),
                        e.getValue()
                    )
                })
            // Add Bazel Python flags that haven't already been added by rules_python's MODULE.bazel.
            BAZEL_PY_FLAG_ALIASES.entrySet().stream()
                .filter(java.util.function.Predicate { e: MutableMap.MutableEntry<String?, String?>? ->
                    !flagAliases.containsKey(
                        e.getKey()
                    )
                })
                .map<MutableMap.MutableEntry<String?, String?>?>(
                    java.util.function.Function { e: MutableMap.MutableEntry<String?, String?>? ->
                        if (rootModule.getName().equals("rules_python"))
                            java.util.Map.entry<String?, String?>(
                                e.getKey(),
                                e.getValue().substring(e.getValue().indexOf("/"))
                            )
                        else
                            e
                    })
                .forEach(java.util.function.Consumer { e: MutableMap.MutableEntry<String?, String?>? ->
                    aliasesMap.put(
                        e.getKey(),
                        e.getValue()
                    )
                })
        }

        return com.google.common.collect.ImmutableMap.copyOf<String?, String?>(aliasesMap)
    }

    @Throws(java.lang.InterruptedException::class, RepositoryMappingResolutionException::class)
    fun getMainRepoMapping(eventHandler: ExtendedEventHandler?): RepositoryMapping {
        return getMainRepoMapping(false, DEFAULT_THREAD_COUNT, eventHandler)
    }

    @Throws(java.lang.InterruptedException::class, RepositoryMappingResolutionException::class)
    fun getMainRepoMapping(
        keepGoing: Boolean, loadingPhaseThreads: Int, eventHandler: ExtendedEventHandler?
    ): RepositoryMapping {
        val mainRepoMappingKey: SkyKey = RepositoryMappingValue.key(RepositoryName.MAIN)
        val evalResult: EvaluationResult<RepositoryMappingValue?> =
            evaluate<RepositoryMappingValue?>(
                com.google.common.collect.ImmutableList.of<SkyKey?>(mainRepoMappingKey),
                keepGoing,
                loadingPhaseThreads,
                eventHandler
            )
        if (evalResult.hasError()) {
            val errorInfo: com.google.devtools.build.skyframe.ErrorInfo = evalResult.getError(mainRepoMappingKey)
            val e: java.lang.Exception? = errorInfo.getException()
            if (e == null && !errorInfo.getCycleInfo().isEmpty()) {
                cyclesReporter.reportCycles(errorInfo.getCycleInfo(), mainRepoMappingKey, eventHandler)
                throw RepositoryMappingResolutionException(
                    DetailedExitCode.of(
                        FailureDetail.newBuilder()
                            .setExternalRepository(
                                FailureDetails.ExternalRepository.newBuilder()
                                    .setCode(ExternalRepository.Code.REPOSITORY_MAPPING_RESOLUTION_FAILED)
                                    .build()
                            )
                            .setMessage("cycles detected during computation of main repo mapping")
                            .build()
                    )
                )
            }
            if (e is DetailedException) {
                throw RepositoryMappingResolutionException(
                    (e as DetailedException).detailedExitCode, e
                )
            }
            // An IOException at this early stage is often due to transient infrastructure issues. We
            // give such failures a specific error code so that they can be retried.
            val externalRepoDetail: FailureDetails.ExternalRepository? =
                if (e is IOException)
                    FailureDetails.ExternalRepository.newBuilder()
                        .setCode(ExternalRepository.Code.REPOSITORY_MAPPING_IO_EXCEPTION)
                        .build()
                else
                    FailureDetails.ExternalRepository.getDefaultInstance()
            throw RepositoryMappingResolutionException(
                DetailedExitCode.of(
                    FailureDetail.newBuilder()
                        .setExternalRepository(externalRepoDetail)
                        .setMessage("error during computation of main repo mapping: " + e.getMessage())
                        .build()
                ),
                e
            )
        }
        return evalResult.get(mainRepoMappingKey).repositoryMapping()
    }

    protected fun getRuleContextConstraintSemantics(): RuleContextConstraintSemantics? {
        return ruleContextConstraintSemantics
    }

    fun setRuleContextConstraintSemantics(
        ruleContextConstraintSemantics: RuleContextConstraintSemantics?
    ) {
        this.ruleContextConstraintSemantics = ruleContextConstraintSemantics
    }

    protected fun getExtraActionFilter(): com.google.devtools.build.lib.util.RegexFilter {
        return com.google.common.base.Preconditions.checkNotNull<com.google.devtools.build.lib.util.RegexFilter>(
            extraActionFilter
        )
    }

    protected fun getTestTypeResolver(): TestTypeResolver? {
        return com.google.common.base.Preconditions.checkNotNull<TestTypeResolver?>(testTypeResolver)
    }

    fun setTestTypeResolver(testTypeResolver: TestTypeResolver?) {
        this.testTypeResolver = testTypeResolver
    }

    fun setExtraActionFilter(extraActionFilter: com.google.devtools.build.lib.util.RegexFilter?) {
        this.extraActionFilter = extraActionFilter
    }

    fun setAndStartWatchdog(watchdog: ActionExecutionInactivityWatchdog) {
        this.watchdog = watchdog
        watchdog.start()
    }

    /** A progress receiver to track analysis invalidation and update progress messages.  */
    protected open inner class SkyframeProgressReceiver : EvaluationProgressReceiver {
        /**
         * This flag is needed in order to avoid invalidating legacy data when we clear the analysis
         * cache because of --discard_analysis_cache flag. For that case we want to keep the legacy data
         * but get rid of the Skyframe data.
         */
        var ignoreInvalidations: Boolean = false

        /** This receiver is only needed for execution, so it is null otherwise.  */
        private var executionProgressReceiver: EvaluationProgressReceiver? = null

        override fun dirtied(skyKey: SkyKey?, dirtyType: DirtyType?) {
            if (ignoreInvalidations) {
                return
            }
            skyframeBuildView.getProgressReceiver().dirtied(skyKey, dirtyType)
        }

        override fun deleted(skyKey: SkyKey?) {
            if (ignoreInvalidations) {
                return
            }
            skyframeBuildView.getProgressReceiver().deleted(skyKey)
        }

        override fun enqueueing(skyKey: SkyKey?) {
            if (ignoreInvalidations) {
                return
            }
            skyframeBuildView.getProgressReceiver().enqueueing(skyKey)
            if (executionProgressReceiver != null) {
                executionProgressReceiver.enqueueing(skyKey)
            }
        }

        override fun stateStarting(skyKey: SkyKey?, nodeState: NodeState?) {
            if (NodeState.COMPUTE == nodeState) {
                skyKeyStateReceiver.computationStarted(skyKey)
            }
        }

        override fun stateEnding(skyKey: SkyKey?, nodeState: NodeState?) {
            if (NodeState.COMPUTE == nodeState) {
                skyKeyStateReceiver.computationEnded(skyKey)
            }
        }

        override fun evaluated(
            skyKey: SkyKey,
            state: EvaluationState,
            newValue: SkyValue?,
            newError: com.google.devtools.build.skyframe.ErrorInfo?,
            directDeps: GroupedDeps?
        ) {
            if (heuristicallyDropNodes) {
                val argument: Any? = skyKey.argument()
                if (skyKey.functionName() == SkyFunctions.FILE) {
                    com.google.common.base.Preconditions.checkArgument(
                        argument is RootedPath,
                        "FILE SkyKey (%s) does not have a RootedPath typed argument (%s)",
                        skyKey,
                        argument
                    )
                    memoizingEvaluator.getInMemoryGraph().remove(argument as RootedPath)
                } else if (skyKey.functionName() == SkyFunctions.DIRECTORY_LISTING) {
                    com.google.common.base.Preconditions.checkArgument(
                        argument is RootedPath,
                        "DIRECTORY_LISTING SkyKey (%s) does not have a RootedPath typed argument (%s)",
                        skyKey,
                        argument
                    )
                    val directoryListingStateKey: SkyKey? = DirectoryListingStateValue.key(argument as RootedPath)
                    memoizingEvaluator.getInMemoryGraph().remove(directoryListingStateKey)
                } else if (directDeps != null
                    && skyKey.functionName() == SkyFunctions.CONFIGURED_TARGET
                ) {
                    maybeDropGenQueryDep(newValue, directDeps)
                }
            }

            if (state.versionChanged()) {
                skyKeyStateReceiver.evaluated(skyKey)
            }
            if (ignoreInvalidations) {
                return
            }
            skyframeBuildView
                .getProgressReceiver()
                .evaluated(skyKey, state, newValue, newError, directDeps)
            if (executionProgressReceiver != null) {
                executionProgressReceiver.evaluated(skyKey, state, newValue, newError, directDeps)
            }

            // After a PACKAGE node is freshly computed, all targets and the labels associated with this
            // package should have been added to the InMemoryGraph. So it is safe to remove relevant
            // labels from weak interner.
            val labelInterner: LabelInterner = Label.getLabelInterner()
            // TODO(https://github.com/bazelbuild/bazel/issues/23852): also intern labels in package
            // pieces for macros.
            if (labelInterner.enabled()
                && skyKey.functionName() == SkyFunctions.PACKAGE
                && newValue != null && directDeps != null
            ) {
                com.google.common.base.Preconditions.checkState(newValue is PackageoidValue, newValue)

                val pkg: Packageoid = (newValue as PackageoidValue).packageoid
                // Lock is keyed by package id, not by package piece id, because we cannot easily look up a
                // package piece from a target label (and even if we could, it is possible - although it is
                // an error state - for package pieces in the same package to collide and declare targets
                // with the same label).
                val writeLock: java.util.concurrent.locks.Lock =
                    labelInterner.getLockForLabelTransferToPool(pkg.getPackageIdentifier())
                writeLock.lock()
                try {
                    pkg.getTargets()
                        .forEach(
                            { name, target ->
                                val label: Label? = target.getLabel()
                                labelInterner.removeWeak(label)
                            })
                } finally {
                    writeLock.unlock()
                }
            }

            if (!heuristicallyDropNodes || directDeps == null || (this.globbingStrategy != GlobbingStrategy.SINGLE_GLOBS_HYBRID)) {
                // `--heuristically_drop_nodes` is only meaningful when this is a non-incremental build with
                // SINGLE_GLOBS_HYBRID strategy.
                return
            }

            // With non-incremental build, edges are not stored. So GLOBS node will not be useful anymore
            // after PACKAGE evaluation completes, making it safe to be removed.
            // See `SequencedSkyframeExecutor#decideKeepIncrementalState()` and b/261019506#comment1.
            if (skyKey.functionName() == SkyFunctions.PACKAGE) {
                for (dep in directDeps.getAllElementsAsIterable()) {
                    if (dep.functionName() == SkyFunctions.GLOBS) {
                        memoizingEvaluator.getInMemoryGraph().remove(dep)
                    }
                }
            }
        }

        override fun changePruned(skyKey: SkyKey?) {
            if (executionProgressReceiver != null) {
                executionProgressReceiver.changePruned(skyKey)
            }
        }

        private fun maybeDropGenQueryDep(newValue: SkyValue, directDeps: GroupedDeps) {
            if (newValue !is RuleConfiguredTargetValue) {
                return
            }
            val t: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
                (newValue as RuleConfiguredTargetValue).getConfiguredTarget()
            if (!t.getRuleClassString().equals("genquery")) {
                return
            }
            for (key in directDeps.getAllElementsAsIterable()) {
                if (key is GenQueryPackageProviderFactory.Key) {
                    // The following call can occur several times for the same GENQUERY_SCOPE key in a single
                    // Skyframe evaluation, because multiple genquery configured targets may have deps on the
                    // same GENQUERY_SCOPE node. It is #removeIfDone and not merely #remove because not-done
                    // nodes cannot be removed from the graph, because they may own state which the Skyframe
                    // evaluation depends on for its completion, namely, the list of rdeps which must be
                    // signaled when the node finishes evaluation.
                    memoizingEvaluator.getInMemoryGraph().removeIfDone(key)
                    return
                }
            }
        }
    }

    fun createExecutionFinishedEvent(): ExecutionFinishedEvent {
        return createExecutionFinishedEventInternal()
            .setSourceArtifactsRead(sourceArtifactsSeen.toFilesMetricAndReset())
            .setOutputArtifactsSeen(outputArtifactsSeen.toFilesMetricAndReset())
            .setOutputArtifactsFromActionCache(outputArtifactsFromActionCache.toFilesMetricAndReset())
            .setTopLevelArtifacts(topLevelArtifactsMetric.toFilesMetricAndReset())
            .build()
    }

    @com.google.errorprone.annotations.ForOverride
    protected open fun createExecutionFinishedEventInternal(): ExecutionFinishedEvent.Builder {
        return ExecutionFinishedEvent.builderWithDefaults()
    }

    @Throws(java.lang.InterruptedException::class)
    fun collectActionLookupValuesInBuild(
        topLevelCtKeys: MutableList<ConfiguredTargetKey?>?,
        aspectKeys: com.google.common.collect.ImmutableSet<AspectKey?>?
    ): ActionLookupValuesTraversal {
        Profiler.instance().profile("skyframeExecutor.collectActionLookupValuesInBuild").use { c ->
            val alvTraversal: ActionLookupValuesTraversal = ActionLookupValuesTraversal()
            if (!tracksStateForIncrementality()) {
                // For non-incremental builds, do a parallel sweep over the whole graph.
                memoizingEvaluator
                    .getInMemoryGraph()
                    .parallelForEach(
                        java.util.function.Consumer { e: InMemoryNodeEntry? ->
                            if (e.getKey() !is ActionLookupKey || !e.isDone()) {
                                return@parallelForEach
                            }
                            val value: SkyValue? = e.getValue()
                            if (value == null) {
                                return@parallelForEach  // Error.
                            }
                            alvTraversal.accumulate(key, value)
                        })
            } else {
                // When incrementality is enabled, traverse the analysis graph top-down. This is slower, but
                // is necessary to avoid collecting nodes that are in the graph from a previous build, but
                // unnecessary for this build.
                // TODO: jhorvitz - We could use the faster parallel sweep on clean builds.
                TransitiveActionLookupKeysCollector(SkyframeExecutorWrappingWalkableGraph.Companion.of(this))
                    .collect(
                        com.google.common.collect.Iterables.< T > concat < T ? > (topLevelCtKeys,
                        aspectKeys
                    ), alvTraversal)
            }
            return alvTraversal
        }
    }

    fun hasDiffAwareness(): Boolean {
        return diffAwarenessManager != null
    }

    @com.google.common.annotations.VisibleForTesting
    @Throws(java.lang.InterruptedException::class, AbruptExitException::class)
    fun handleDiffsForTesting(eventHandler: ExtendedEventHandler) {
        handleDiffsForTesting(
            eventHandler,
            com.google.devtools.common.options.Options.getDefaults<O?>(PackageOptions::class.java)
        )
    }

    /** Uses diff awareness on all the package paths to invalidate changed files.  */
    @com.google.common.annotations.VisibleForTesting
    @Throws(java.lang.InterruptedException::class, AbruptExitException::class)
    fun handleDiffsForTesting(
        eventHandler: ExtendedEventHandler, packageOptions: PackageOptions
    ) {
        if (lastAnalysisDiscarded) {
            // Values were cleared last build, but they couldn't be deleted because they were needed for
            // the execution phase. We can delete them now.
            dropConfiguredTargetsNow(eventHandler)
            lastAnalysisDiscarded = false
        }
        packageOptions.setCheckOutputFiles(false)
        val options: com.google.common.collect.ClassToInstanceMap<com.google.devtools.common.options.OptionsBase?> =
            com.google.common.collect.ImmutableClassToInstanceMap.of<B?, PackageOptions?>(
                PackageOptions::class.java,
                packageOptions
            )
        handleDiffs(
            eventHandler,
            object : com.google.devtools.common.options.OptionsProvider {
                override fun <O : com.google.devtools.common.options.OptionsBase?> getOptions(optionsClass: java.lang.Class<O?>): O? {
                    return options.getInstance<O?>(optionsClass)
                }

                val starlarkOptions: com.google.common.collect.ImmutableMap<String?, Any?>
                    get() = com.google.common.collect.ImmutableMap.of<String?, Any?>()

                val scopesAttributes: com.google.common.collect.ImmutableMap<String?, String?>
                    get() = com.google.common.collect.ImmutableMap.of<String?, String?>()

                val onLeaveScopeValues: com.google.common.collect.ImmutableMap<String?, Any?>
                    get() = com.google.common.collect.ImmutableMap.of<String?, Any?>()

                val explicitCommandLineStarlarkOptions: com.google.common.collect.ImmutableMap<String?, Any?>
                    get() = com.google.common.collect.ImmutableMap.of<String?, Any?>()

                val starlarkOptionsAllowingMultiple: com.google.common.collect.ImmutableSet<String?>
                    get() = com.google.common.collect.ImmutableSet.of<String?>()

                val userOptions: com.google.common.collect.ImmutableMap<String?, String?>
                    get() = com.google.common.collect.ImmutableMap.of<String?, String?>()
            })
    }

    @com.google.errorprone.annotations.CanIgnoreReturnValue
    @Throws(java.lang.InterruptedException::class, AbruptExitException::class)
    protected fun handleDiffs(
        eventHandler: ExtendedEventHandler, options: com.google.devtools.common.options.OptionsProvider
    ): WorkspaceInfoFromDiff? {
        val tsgm: TimestampGranularityMonitor? = this.tsgm.get()
        modifiedFiles.set(0)
        numSourceFilesCheckedBecauseOfMissingDiffs = 0

        var workspaceInfo: WorkspaceInfoFromDiff? = null
        val modifiedFilesByPathEntry: MutableMap<Root?, DiffAwarenessManager.ProcessableModifiedFileSet> =
            com.google.common.collect.Maps.newHashMap<Root?, DiffAwarenessManager.ProcessableModifiedFileSet>()
        val pathEntriesWithoutDiffInformation: MutableSet<com.google.devtools.build.lib.util.Pair<Root?, ProcessableModifiedFileSet?>> =
            com.google.common.collect.Sets.newHashSet<com.google.devtools.build.lib.util.Pair<Root?, ProcessableModifiedFileSet?>?>()
        val pkgRoots: com.google.common.collect.ImmutableList<Root> = this.packagePathEntries

        val workspacePath: com.google.devtools.build.lib.vfs.Path? = directories.getWorkspace()
        val evaluationResult: EvaluationResult<SkyValue?> =
            evaluateSkyKeys(
                eventHandler,
                com.google.common.collect.ImmutableList.of<E?>(IgnoredSubdirectoriesValue.key())
            )
        val ignoredSubdirectoriesValue: IgnoredSubdirectoriesValue? =
            evaluationResult.get(IgnoredSubdirectoriesValue.key()) as IgnoredSubdirectoriesValue?

        if (diffAwarenessManager != null) {
            for (pathEntry in pkgRoots) {
                // Ignored subdirectories are specified relative to the workspace root by definition of
                // .bazelignore. So, we only use ignored paths when the package root is equal to the
                // workspace path.
                if (workspacePath != null && workspacePath == pathEntry.asPath()
                    && ignoredSubdirectoriesValue != null
                ) {
                    ignoredPaths =
                        ignoredSubdirectoriesValue
                            .asIgnoredSubdirectories()
                            .withPrefix(pathEntry.asPath().asFragment().toRelative())
                }

                val modifiedFileSet: DiffAwarenessManager.ProcessableModifiedFileSet =
                    diffAwarenessManager.getDiff(eventHandler, pathEntry, ignoredPaths, options)
                if (pkgRoots.size() == 1) {
                    workspaceInfo = modifiedFileSet.workspaceInfo
                    workspaceInfoFromDiffReceiver.syncWorkspaceInfoFromDiff(
                        pathEntry.asPath().asFragment(), workspaceInfo
                    )
                }
                if (modifiedFileSet.modifiedFileSet.treatEverythingAsModified()) {
                    pathEntriesWithoutDiffInformation.add(
                        com.google.devtools.build.lib.util.Pair.of<Root?, ProcessableModifiedFileSet?>(
                            pathEntry,
                            modifiedFileSet
                        )
                    )
                } else {
                    modifiedFilesByPathEntry.put(pathEntry, modifiedFileSet)
                }
            }
        }
        val buildRequestOptions: BuildRequestOptions? = options.getOptions<O?>(BuildRequestOptions::class.java)
        val fsvcThreads = if (buildRequestOptions == null) 200 else buildRequestOptions.fsvcThreads
        Profiler.instance().profile("handleDiffsWithCompleteDiffInformation").use { c ->
            handleDiffsWithCompleteDiffInformation(tsgm, modifiedFilesByPathEntry, fsvcThreads)
        }
        var scheduledExecutorService: ScheduledExecutorService? = null
        var diffCheckNotificationFuture: java.util.concurrent.ScheduledFuture<*>? = null
        if (!isCleanBuild && diffCheckNotificationOptions.isPresent()) {
            val diffCheckNotificationOptions: DiffCheckNotificationOptions =
                this.diffCheckNotificationOptions.get()
            scheduledExecutorService = Executors.newSingleThreadScheduledExecutor()
            diffCheckNotificationFuture =
                scheduledExecutorService.schedule(
                    java.lang.Runnable {
                        eventHandler.handle(
                            com.google.devtools.build.lib.events.Event.info(
                                diffCheckNotificationOptions.statusMessage
                            )
                        )
                    },
                    diffCheckNotificationOptions.statusUpdateDelay.toMillis(),
                    TimeUnit.MILLISECONDS
                )
        }

        val repoOptions: RepositoryOptions? = options.getOptions<O?>(RepositoryOptions::class.java)
        try {
            Profiler.instance().profile("handleDiffsWithMissingDiffInformation").use { c ->
                val packageOptions: PackageOptions? = options.getOptions<O?>(PackageOptions::class.java)
                handleDiffsWithMissingDiffInformation(
                    eventHandler,
                    tsgm,
                    pathEntriesWithoutDiffInformation,
                    packageOptions.getCheckOutputFiles(),
                    repoOptions != null && repoOptions.checkExternalRepositoryFiles,
                    packageOptions.getCheckExternalOtherFiles(),
                    fsvcThreads
                )
            }
        } finally {
            if (scheduledExecutorService != null && diffCheckNotificationFuture != null) {
                diffCheckNotificationFuture.cancel(false)
                scheduledExecutorService.shutdown()
            }
        }
        handleClientEnvironmentChanges()
        isCleanBuild = false
        return workspaceInfo
    }

    /** Invalidates entries in the client environment.  */
    private fun handleClientEnvironmentChanges() {
        // Remove deleted client environmental variables.
        val deletedKeys: com.google.common.collect.ImmutableList<SkyKey> =
            com.google.common.collect.Sets.difference<String?>(previousClientEnvironment, clientEnv.get().keySet())
                .stream()
                .map<Any?>(ClientEnvironmentFunction::key)
                .collect(com.google.common.collect.ImmutableList.toImmutableList<Any?>())
        recordingDiffer.invalidate(deletedKeys)
        previousClientEnvironment = clientEnv.get().keySet()
        // Inject current client environmental values. We can inject unconditionally without fearing
        // over-invalidation; skyframe will not invalidate an injected key if the key's new value is the
        // same as the old value.
        val newValuesBuilder: com.google.common.collect.ImmutableMap.Builder<SkyKey?, Delta?> =
            com.google.common.collect.ImmutableMap.builder<SkyKey?, Delta?>()
        for (entry in clientEnv.get().entrySet()) {
            newValuesBuilder.put(
                ClientEnvironmentFunction.key(entry.getKey()),
                Delta.justNew(EnvironmentVariableValue(entry.getValue()))
            )
        }
        recordingDiffer.inject(newValuesBuilder.buildOrThrow())
    }

    /**
     * Invalidates files under path entries whose corresponding [DiffAwareness] gave an exact
     * diff. Removes entries from the given map as they are processed. All of the files need to be
     * invalidated, so the map should be empty upon completion of this function.
     */
    @Throws(java.lang.InterruptedException::class, AbruptExitException::class)
    private fun handleDiffsWithCompleteDiffInformation(
        tsgm: TimestampGranularityMonitor?,
        modifiedFilesByPathEntry: MutableMap<Root?, ProcessableModifiedFileSet>,
        fsvcThreads: Int
    ) {
        for (pathEntry in com.google.common.collect.ImmutableSet.copyOf<Root?>(modifiedFilesByPathEntry.keySet())) {
            val processableModifiedFileSet: DiffAwarenessManager.ProcessableModifiedFileSet =
                modifiedFilesByPathEntry.get(pathEntry)
            val modifiedFileSet: ModifiedFileSet = processableModifiedFileSet.modifiedFileSet
            com.google.common.base.Preconditions.checkState(!modifiedFileSet.treatEverythingAsModified(), pathEntry)
            handleChangedFiles(
                com.google.common.collect.ImmutableList.of<Root?>(pathEntry),
                getDiff(tsgm, modifiedFileSet, pathEntry, fsvcThreads),  /* numSourceFilesCheckedIfDiffWasMissing= */
                0
            )
            processableModifiedFileSet.markProcessed()
        }
    }

    /**
     * Finds and invalidates changed files under path entries whose corresponding [ ] said all files may have been modified.
     * 
     * 
     * We need to manually check for changes to known files. This entails finding all dirty file
     * system values under package roots for which we don't have diff information. If at least one
     * path entry doesn't have diff information, then we're going to have to iterate over the skyframe
     * values at least once no matter what.
     */
    @Throws(java.lang.InterruptedException::class, AbruptExitException::class)
    protected fun handleDiffsWithMissingDiffInformation(
        eventHandler: ExtendedEventHandler?,
        tsgm: TimestampGranularityMonitor?,
        pathEntriesWithoutDiffInformation: MutableSet<com.google.devtools.build.lib.util.Pair<Root?, ProcessableModifiedFileSet?>>,
        checkOutputFiles: Boolean,
        checkExternalRepositoryFiles: Boolean,
        checkExternalOtherFiles: Boolean,
        fsvcThreads: Int
    ) {
        val externalFilesKnowledge: ExternalFilesKnowledge = externalFilesHelper.getExternalFilesKnowledge()
        if (!pathEntriesWithoutDiffInformation.isEmpty() || (checkOutputFiles && externalFilesKnowledge.anyOutputFilesSeen)
            || (checkExternalRepositoryFiles && allowExternalRepositories)
            || (checkExternalRepositoryFiles && externalFilesKnowledge.anyFilesInExternalReposSeen)
            || (checkExternalOtherFiles && externalFilesKnowledge.tooManyExternalOtherFilesSeen)
        ) {
            // We freshly compute knowledge of the presence of external files in the skyframe graph. We
            // use a fresh ExternalFilesHelper instance and only set the real instance's knowledge *after*
            // we are done with the graph scan, lest an interrupt during the graph scan causes us to
            // incorrectly think there are no longer any external files.
            val tmpExternalFilesHelper: ExternalFilesHelper =
                externalFilesHelper.cloneWithFreshExternalFilesKnowledge()

            // Before running the {@link FilesystemValueChecker} ensure that all values marked for
            // invalidation have actually been invalidated, because checking those is a waste of time.
            applyInvalidation(eventHandler)

            val fsvc: FilesystemValueChecker =
                FilesystemValueChecker(
                    tsgm,
                    syscallCache,
                    if (outputService == null)
                        XattrProviderOverrider.NO_OVERRIDE
                    else { delegate: XattrProvider? -> outputService.getXattrProvider(delegate) },
                    fsvcThreads
                )

            val diffPackageRootsUnderWhichToCheck: MutableSet<Root?> =
                getDiffPackageRootsUnderWhichToCheck(pathEntriesWithoutDiffInformation)

            var fileTypesToCheck: EnumSet<com.google.devtools.build.lib.skyframe.ExternalFilesHelper.FileType?> =
                EnumSet.noneOf<com.google.devtools.build.lib.skyframe.ExternalFilesHelper.FileType?>(com.google.devtools.build.lib.skyframe.ExternalFilesHelper.FileType::class.java)
            var dirtinessCheckers: Iterable<SkyValueDirtinessChecker?> =
                com.google.common.collect.ImmutableList.of<SkyValueDirtinessChecker?>()

            if (!diffPackageRootsUnderWhichToCheck.isEmpty()) {
                dirtinessCheckers =
                    com.google.common.collect.Iterables.< T > concat < T ? > (
                            dirtinessCheckers,
                com.google.common.collect.ImmutableList.of<MissingDiffDirtinessChecker?>(
                    MissingDiffDirtinessChecker(diffPackageRootsUnderWhichToCheck)
                ))
            }
            if (checkExternalRepositoryFiles) {
                fileTypesToCheck =
                    EnumSet.of<com.google.devtools.build.lib.skyframe.ExternalFilesHelper.FileType?>(com.google.devtools.build.lib.skyframe.ExternalFilesHelper.FileType.EXTERNAL_REPO)
            }
            if (checkExternalOtherFiles
                && (externalFilesKnowledge.tooManyExternalOtherFilesSeen
                        || !externalFilesKnowledge.externalOtherFilesSeen.isEmpty())
            ) {
                fileTypesToCheck.add(com.google.devtools.build.lib.skyframe.ExternalFilesHelper.FileType.EXTERNAL_OTHER)
            }
            // See the comment for FileType.OUTPUT for why we need to consider output files here.
            if (checkOutputFiles) {
                fileTypesToCheck.add(com.google.devtools.build.lib.skyframe.ExternalFilesHelper.FileType.OUTPUT)
            }
            var externalDirtinessChecker: ExternalDirtinessChecker? = null
            if (!fileTypesToCheck.isEmpty()) {
                // FileType.REPO_CONTENTS_CACHE_DIRS is intentionally never checked here. See the comment on
                // that enum constant for details.
                externalDirtinessChecker =
                    ExternalDirtinessChecker(tmpExternalFilesHelper, fileTypesToCheck)
                dirtinessCheckers =
                    com.google.common.collect.Iterables.< T > concat < T ? > (dirtinessCheckers, com.google.common.collect.ImmutableList.of<ExternalDirtinessChecker?>(externalDirtinessChecker))
            }
            com.google.common.base.Preconditions.checkArgument(
                !com.google.common.collect.Iterables.isEmpty(
                    dirtinessCheckers
                )
            )

            logger.atInfo().log(
                "About to scan skyframe graph checking for filesystem nodes of types %s",
                com.google.common.collect.Iterables.toString(fileTypesToCheck)
            )
            val batchDirtyResult: ImmutableBatchDirtyResult
            Profiler.instance().profile("fsvc.getDirtyKeys").use { c ->
                batchDirtyResult =
                    fsvc.getDirtyKeys(
                        memoizingEvaluator.getValues(),
                        UnionDirtinessChecker(
                            com.google.common.collect.ImmutableList.copyOf<SkyValueDirtinessChecker?>(
                                dirtinessCheckers
                            )
                        )
                    )
            }
            if (externalDirtinessChecker != null) {
                recordingDiffer.invalidate(
                    externalFilesHelper.getExtraKeysToInvalidate(
                        externalDirtinessChecker.getDirtyExternalRepos(), eventHandler
                    )
                )
            }
            handleChangedFiles(
                diffPackageRootsUnderWhichToCheck,
                batchDirtyResult,  /* numSourceFilesCheckedIfDiffWasMissing= */
                batchDirtyResult.numKeysChecked
            )
            // We use the knowledge gained during the graph scan that just completed. Otherwise, naively,
            // once an external file gets into the Skyframe graph, we'll overly-conservatively always
            // think the graph needs to be scanned.
            externalFilesHelper.setExternalFilesKnowledge(
                tmpExternalFilesHelper.getExternalFilesKnowledge()
            )
        } else if (checkExternalOtherFiles
            && !externalFilesKnowledge.externalOtherFilesSeen.isEmpty()
        ) {
            logger.atInfo().log(
                "About to scan %d external files", externalFilesKnowledge.externalOtherFilesSeen.size()
            )
            val fsvc: FilesystemValueChecker =
                FilesystemValueChecker(
                    tsgm,
                    syscallCache,
                    if (outputService == null)
                        XattrProviderOverrider.NO_OVERRIDE
                    else { delegate: XattrProvider? -> outputService.getXattrProvider(delegate) },
                    fsvcThreads
                )
            val batchDirtyResult: ImmutableBatchDirtyResult
            Profiler.instance().profile("fsvc.getDirtyExternalKeys").use { c ->
                val externalDirtyNodes: MutableMap<SkyKey?, SkyValue?> = ConcurrentHashMap<SkyKey?, SkyValue?>()
                for (path in externalFilesKnowledge.externalOtherFilesSeen) {
                    var key: SkyKey? = FileStateValue.key(path)
                    var value: SkyValue? = memoizingEvaluator.getExistingValue(key)
                    if (value != null) {
                        externalDirtyNodes.put(key, value)
                    }
                    key = DirectoryListingStateValue.key(path)
                    value = memoizingEvaluator.getExistingValue(key)
                    if (value != null) {
                        externalDirtyNodes.put(key, value)
                    }
                }
                batchDirtyResult =
                    fsvc.getDirtyKeys(
                        externalDirtyNodes,
                        ExternalDirtinessChecker(
                            externalFilesHelper,
                            EnumSet.of<com.google.devtools.build.lib.skyframe.ExternalFilesHelper.FileType?>(com.google.devtools.build.lib.skyframe.ExternalFilesHelper.FileType.EXTERNAL_OTHER)
                        )
                    )
            }
            handleChangedFiles(
                com.google.common.collect.ImmutableList.of<Root?>(), batchDirtyResult, batchDirtyResult.numKeysChecked
            )
        }
        for (pair in pathEntriesWithoutDiffInformation) {
            pair.getSecond().markProcessed()
        }
    }

    /**
     * Actually invalidates values marked for invalidation.
     * 
     * 
     * Invalidation is delayed because:
     * 
     * 
     *  * there may never be a next evaluation, so the work to clean up values may be wasted;
     *  * invalidated values may be resurrected due to change pruning.
     * 
     */
    @Throws(java.lang.InterruptedException::class)
    fun applyInvalidation(eventHandler: ExtendedEventHandler?) {
        Profiler.instance().profile("applyInvalidation").use { c ->
            val evaluationContext: com.google.devtools.build.skyframe.EvaluationContext? =
                newEvaluationContextBuilder()
                    .setKeepGoing(false)
                    .setParallelism(DEFAULT_THREAD_COUNT)
                    .setEventHandler(eventHandler)
                    .build()
            memoizingEvaluator.evaluate<SkyValue?>(
                com.google.common.collect.ImmutableList.of<SkyKey?>(),
                evaluationContext
            )
        }
    }

    protected fun getDiffPackageRootsUnderWhichToCheck(
        pathEntriesWithoutDiffInformation: MutableSet<com.google.devtools.build.lib.util.Pair<Root?, ProcessableModifiedFileSet?>>
    ): MutableSet<Root?> {
        val diffPackageRootsUnderWhichToCheck: MutableSet<Root?> = HashSet<Root?>()
        for (pair in pathEntriesWithoutDiffInformation) {
            diffPackageRootsUnderWhichToCheck.add(pair.getFirst())
        }
        return diffPackageRootsUnderWhichToCheck
    }

    @Throws(AbruptExitException::class)
    protected fun handleChangedFiles(
        diffPackageRootsUnderWhichToCheck: MutableCollection<Root?>,
        diff: com.google.devtools.build.skyframe.Differencer.Diff,
        numSourceFilesCheckedIfDiffWasMissing: Int
    ) {
        val numWithoutNewValues: Int = diff.changedKeysWithoutNewValues().size()
        val keysToBeChangedLaterInThisBuild: Iterable<SkyKey> = diff.changedKeysWithoutNewValues()
        val changedKeysWithNewValues: MutableMap<SkyKey?, Delta?> = diff.changedKeysWithNewValues()

        logDiffInfo(
            diffPackageRootsUnderWhichToCheck,
            keysToBeChangedLaterInThisBuild,
            numWithoutNewValues,
            changedKeysWithNewValues.keySet()
        )

        handleSkyfocusVerificationSet(diff)

        recordingDiffer.invalidate(keysToBeChangedLaterInThisBuild)
        recordingDiffer.inject(changedKeysWithNewValues)
        modifiedFiles.addAndGet(
            getNumberOfModifiedFiles(keysToBeChangedLaterInThisBuild)
                    + getNumberOfModifiedFiles(changedKeysWithNewValues.keySet())
        )
        numSourceFilesCheckedBecauseOfMissingDiffs += numSourceFilesCheckedIfDiffWasMissing
        incrementalBuildMonitor.accrue(keysToBeChangedLaterInThisBuild)
        incrementalBuildMonitor.accrue(changedKeysWithNewValues.keySet())
    }

    /**
     * Given a set of [SkyKey]s that were deemed to have changed, check their intersection with
     * the [SkyframeFocuser] is non-empty.
     * 
     * 
     * If it's non-empty, it means that there were changed files outside the active directories,
     * but within the transitive closure of the focused targets. The build cannot proceed normally
     * because Skyfocus has removed the nodes and edges from the backing graph to build those files
     * incrementally
     * 
     * 
     * The only ways forward are to:
     * 
     * 
     *  1. 1) Present an error to the user on the files that have changed, and ask the user to
     * expand their active directories to include these files.
     *  1. 2) Automatically expand the active directories and reset the analysis cache to rebuild
     * the Skyframe graph. (i.e. new build).
     * 
     * 
     * This function currently implements only option 1).
     * 
     * 
     * Only runs when Skyfocus is enabled (--experimental_enable_skyfocus).
     */
    @Throws(AbruptExitException::class)
    private fun handleSkyfocusVerificationSet(diff: com.google.devtools.build.skyframe.Differencer.Diff) {
        if (!skyfocusState.enabled) {
            return
        }

        val verificationSet: com.google.common.collect.ImmutableSet<SkyKey?> = skyfocusState.verificationSet
        if (diff.isEmpty() || verificationSet.isEmpty()) {
            return
        }

        val intersection: MutableSet<String?> = TreeSet<String?>()
        val maybeAddToIntersection: java.util.function.Consumer<SkyKey?> =
            java.util.function.Consumer { k: SkyKey? ->
                if (!verificationSet.contains(k)) {
                    return@Consumer
                }
                val rp: RootedPath =
                    when (k) {
                        -> r
                        -> d.argument()
                        else -> throw java.lang.IllegalStateException(
                            "Unhandled key type in verification set: " + k.getCanonicalName()
                        )
                    }
                // RootedPath#toString() prints square brackets around the components, but we don't
                // want that.
                intersection.add(
                    rp.getRoot().toString() + java.nio.file.FileSystems.getDefault()
                        .getSeparator() + rp.getRootRelativePath()
                )
            }

        diff.changedKeysWithoutNewValues().forEach(maybeAddToIntersection)
        diff.changedKeysWithNewValues().keySet().forEach(maybeAddToIntersection)

        if (intersection.isEmpty()) {
            return
        }

        val message: java.lang.StringBuilder = java.lang.StringBuilder()
        message.append(
            "Skyfocus detected changes outside of the active directories. These files/directories must"
                    + " be added to the active directories."
        )
        message.append("\n")
        for (path in intersection) {
            message.append(path)
            message.append("\n")
        }

        throw AbruptExitException(
            DetailedExitCode.of(
                FailureDetail.newBuilder()
                    .setMessage(message.toString())
                    .setSkyfocus(
                        Skyfocus.newBuilder()
                            .setCode(Skyfocus.Code.NON_ACTIVE_DIRECTORIES_CHANGE)
                            .build()
                    )
                    .build()
            )
        )
    }

    /**
     * Collects the [ActionLookupKey] transitive closure of given [ActionLookupKey]s.
     * 
     * 
     * In the non-Skymeld case, this class is constructed and performs one traversal before
     * shutdown at the end of analysis.
     */
    private class TransitiveActionLookupKeysCollector(walkableGraph: WalkableGraph) {
        private val walkableGraph: WalkableGraph

        init {
            this.walkableGraph = walkableGraph
        }

        /**
         * Traverses the transitive closure of `visitationRoots` and returns an [ ] keyed map to corresponding values for all visited keys.
         */
        @Throws(java.lang.InterruptedException::class)
        fun collect(
            visitationRoots: Iterable<ActionLookupKey?>, alvTraversal: ActionLookupValuesTraversal
        ) {
            val executorService: ForkJoinPool =
                NamedForkJoinPool.newNamedPool(
                    "find-action-lookup-values-in-build", java.lang.Runtime.getRuntime().availableProcessors()
                )
            val seen: MutableSet<ActionLookupKey?> =
                com.google.common.collect.Sets.newConcurrentHashSet<ActionLookupKey?>()
            val futures: MutableList<java.util.concurrent.Future<*>> =
                com.google.common.collect.Lists.newArrayListWithCapacity<java.util.concurrent.Future<*>?>(
                    com.google.common.collect.Iterables.size(visitationRoots)
                )
            for (key in visitationRoots) {
                if (seen.add(key)) {
                    futures.add(executorService.submit<java.lang.Void?>(VisitActionLookupKey(key, seen, alvTraversal)))
                }
            }
            try {
                for (future in futures) {
                    future.get()
                }
            } catch (e: ExecutionException) {
                throw java.lang.IllegalStateException("Error collecting transitive ActionLookupValues", e)
            } finally {
                if (!executorService.isShutdown() && ExecutorUtil.interruptibleShutdown(executorService)) {
                    // Preserve the interrupt status.
                    java.lang.Thread.currentThread().interrupt()
                }
            }
        }

        private inner class VisitActionLookupKey(
            key: ActionLookupKey?,
            seen: MutableSet<ActionLookupKey?>,
            alvTraversal: ActionLookupValuesTraversal
        ) : RecursiveAction() {
            private val key: ActionLookupKey?
            private val seen: MutableSet<ActionLookupKey?>
            private val alvTraversal: ActionLookupValuesTraversal

            init {
                this.key = key
                this.seen = seen
                this.alvTraversal = alvTraversal
            }

            public override fun compute() {
                var value: SkyValue? = null
                try {
                    value = walkableGraph.getValue(key)
                } catch (e: java.lang.InterruptedException) {
                    java.lang.Thread.currentThread().interrupt()
                }
                if (value == null) { // The value failed to evaluate.
                    return
                }

                alvTraversal.accumulate(key, value)

                val directDeps: Iterable<SkyKey>
                try {
                    directDeps = walkableGraph.getDirectDeps(key)
                } catch (e: java.lang.InterruptedException) {
                    java.lang.Thread.currentThread().interrupt()
                    return
                }
                val subtasks: java.util.ArrayList<VisitActionLookupKey?> = java.util.ArrayList<VisitActionLookupKey?>()
                for (dep in directDeps) {
                    // Besides PlatformFunction, the subgraph of dependencies of ActionLookupKeys never has
                    // a non-ActionLookupKey depending on an ActionLookupKey. So we can skip any other
                    // non-ActionLookupKeys in the traversal as an optimization.
                    var dep: SkyKey = dep
                    if (dep.functionName() == SkyFunctions.PLATFORM) {
                        val platformLabel: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
                            (dep.argument() as PlatformValue.Key).label()
                        dep = PlatformFunction.configuredTargetDep(platformLabel)
                    }
                    if (dep !is ActionLookupKey) {
                        continue
                    }
                    if (seen.add(dep)) {
                        subtasks.add(VisitActionLookupKey(dep, seen, alvTraversal))
                    }
                }
                ForkJoinTask.invokeAll<VisitActionLookupKey?>(subtasks)
            }
        }
    }

    @Throws(java.lang.InterruptedException::class)
    fun <T : SkyValue?> evaluate(
        roots: Iterable<out SkyKey?>?,
        keepGoing: Boolean,
        numThreads: Int,
        eventHandler: ExtendedEventHandler?
    ): EvaluationResult<T?> {
        val evaluationContext: com.google.devtools.build.skyframe.EvaluationContext? =
            newEvaluationContextBuilder()
                .setKeepGoing(keepGoing)
                .setParallelism(numThreads)
                .setEventHandler(eventHandler)
                .build()
        return memoizingEvaluator.evaluate<T?>(roots, evaluationContext)
    }

    @kotlin.concurrent.Volatile
    private var dropper: UnnecessaryTemporaryStateDropper = NULL_UNNECESSARY_TEMPORARY_STATE_DROPPER

    private val unnecessaryTemporaryStateDropperReceiver: UnnecessaryTemporaryStateDropperReceiver =
        object : UnnecessaryTemporaryStateDropperReceiver() {
            override fun onEvaluationStarted(dropper: UnnecessaryTemporaryStateDropper) {
                this@SkyframeExecutor.dropper = dropper
            }

            override fun onEvaluationFinished() {
                this@SkyframeExecutor.dropper.drop()
                this@SkyframeExecutor.dropper = NULL_UNNECESSARY_TEMPORARY_STATE_DROPPER
            }
        }

    init {
        // Strictly speaking, these arguments are not required for initialization, but all current
        // callsites have them at hand, so we might as well set them during construction.
        this.skyframeExecutorConsumerOnInit = skyframeExecutorConsumerOnInit
        this.pkgFactory = pkgFactory
        this.shouldUnblockCpuWorkWhenFetchingDeps = shouldUnblockCpuWorkWhenFetchingDeps
        this.skyKeyStateReceiver = skyKeyStateReceiver
        this.bugReporter = bugReporter
        this.syscallCache = syscallCache
        this.pkgFactory.setSyscallCache(this.syscallCache)
        this.workspaceStatusActionFactory = workspaceStatusActionFactory
        this.queryTransitivePackagePreloader =
            QueryTransitivePackagePreloader(
                { memoizingEvaluator }, { this.newEvaluationContextBuilder() }, bugReporter
            )
        this.packageManager =
            SkyframePackageManager(
                SkyframePackageLoader(),
                this.syscallCache,
                java.util.function.Supplier { pkgLocator.get() },
                numPackagesSuccessfullyLoaded
            )
        this.fileSystem = fileSystem
        this.directories = com.google.common.base.Preconditions.checkNotNull<BlazeDirectories>(directories)
        this.actionKeyContext = com.google.common.base.Preconditions.checkNotNull<ActionKeyContext>(actionKeyContext)
        this.ignoredSubdirectoriesFunction = ignoredSubdirectoriesFunction
        this.extraSkyFunctions = extraSkyFunctions

        this.ruleClassProvider = pkgFactory.getRuleClassProvider() as ConfiguredRuleClassProvider
        this.skyframeActionExecutor =
            SkyframeActionExecutor(
                actionKeyContext,
                outputArtifactsSeen,
                outputArtifactsFromActionCache,
                statusReporterRef,
                java.util.function.Supplier { this.packagePathEntries },
                this.syscallCache,
                java.util.function.Function { key: SkyKey? -> skyKeyStateReceiver.makeThreadStateReceiver(key) },
                ExistingActionLookupValuePeeker { key: ActionLookupKey? -> this.getExistingActionLookupValue(key) })
        this.artifactFactory =
            ArtifactFactory( /* execRootParent= */
                directories.getExecRootBase(),
                directories.getRelativeOutputPath()
            )
        this.skyframeBuildView =
            SkyframeBuildView(artifactFactory, this, ruleClassProvider, actionKeyContext)
        this.externalFilesHelper =
            ExternalFilesHelper.Companion.create(
                pkgLocator, externalFileAction, directories, repoContentsCachePathSupplier
            )
        this.crossRepositoryLabelViolationStrategy = crossRepositoryLabelViolationStrategy
        this.buildFilesByPriority = buildFilesByPriority
        this.actionOnIOExceptionReadingBuildFile = actionOnIOExceptionReadingBuildFile
        this.actionOnFilesystemErrorCodeLoadingBzlFile = actionOnFilesystemErrorCodeLoadingBzlFile
        this.shouldUseRepoDotBazel = shouldUseRepoDotBazel
        this.packageProgress = packageProgress
        this.analysisProgress = analysisProgress
        this.diffAwarenessManager =
            if (diffAwarenessFactories != null) DiffAwarenessManager(diffAwarenessFactories) else null
        this.workspaceInfoFromDiffReceiver = workspaceInfoFromDiffReceiver
        this.recordingDiffer = recordingDiffer
        this.allowExternalRepositories = allowExternalRepositories
        this.globUnderSingleDep = globUnderSingleDep
        this.diffCheckNotificationOptions = diffCheckNotificationOptions
    }

    protected fun newEvaluationContextBuilder(): com.google.devtools.build.skyframe.EvaluationContext.Builder? {
        return com.google.devtools.build.skyframe.EvaluationContext.newBuilder()
            .setUnnecessaryTemporaryStateDropperReceiver(unnecessaryTemporaryStateDropperReceiver)
    }

    fun dropUnnecessaryTemporarySkyframeState() {
        dropper.drop()
    }

    /** Receiver for successfully evaluated/doing computation [SkyKey]s.  */
    interface SkyKeyStateReceiver {
        /** Called when `key`'s associated [SkyFunction.compute] is called.  */
        fun computationStarted(key: SkyKey?) {}

        /** Called when `key`'s associated [SkyFunction.compute] has finished.  */
        fun computationEnded(key: SkyKey?) {}

        /** Called when `key` has been evaluated and has a new value.  */
        fun evaluated(key: SkyKey?) {}

        fun makeThreadStateReceiver(key: SkyKey?): ThreadStateReceiver {
            return ThreadStateReceiver.NULL_INSTANCE
        }

        companion object {
            @kotlin.jvm.JvmField
            val NULL_INSTANCE: SkyKeyStateReceiver = object : SkyKeyStateReceiver {}
        }
    }

    @Throws(java.lang.InterruptedException::class)
    fun getExistingPackage(id: PackageIdentifier?): Package? {
        val value: PackageValue? = memoizingEvaluator.getExistingValue(id) as PackageValue?
        if (value == null) {
            return null
        }
        return value.getPackage()
    }

    @Throws(java.lang.InterruptedException::class)
    private fun getExistingActionLookupValue(key: ActionLookupKey?): ActionLookupValue? {
        return memoizingEvaluator.getExistingValue(key) as ActionLookupValue?
    }

    @get:com.google.common.annotations.VisibleForTesting
    val ruleClassProviderForTesting: ConfiguredRuleClassProvider
        get() = ruleClassProvider

    @get:com.google.common.annotations.VisibleForTesting
    val packageSettingsForTesting: PackageSettings
        get() = pkgFactory.getPackageSettingsForTesting()

    @get:com.google.common.annotations.VisibleForTesting
    val blazeDirectoriesForTesting: BlazeDirectories
        get() = directories

    @get:com.google.common.annotations.VisibleForTesting
    val actionExecutionStatusReporterForTesting: ActionExecutionStatusReporter?
        get() = statusReporterRef.get()

    @com.google.common.annotations.VisibleForTesting
    fun clearEmittedEventStateForTesting() {
        emittedEventState.clear()
    }

    /**
     * Invalidates Skyframe values corresponding to the given set of modified files under the given
     * path entry.
     * 
     * 
     * May throw an [InterruptedException], which means that no values have been invalidated.
     */
    @com.google.common.annotations.VisibleForTesting
    @Throws(java.lang.InterruptedException::class, AbruptExitException::class)
    fun invalidateFilesUnderPathForTesting(
        eventHandler: ExtendedEventHandler?, modifiedFileSet: ModifiedFileSet, pathEntry: Root?
    ) {
        if (lastAnalysisDiscarded) {
            // Values were cleared last build, but they couldn't be deleted because they were needed for
            // the execution phase. We can delete them now.
            dropConfiguredTargetsNow(eventHandler)
            lastAnalysisDiscarded = false
        }
        clearSyscallCache()
        invalidateFilesUnderPathForTestingImpl(eventHandler, modifiedFileSet, pathEntry)
    }

    @com.google.errorprone.annotations.ForOverride
    @Throws(AbruptExitException::class, java.lang.InterruptedException::class)
    protected fun invalidateFilesUnderPathForTestingImpl(
        eventHandler: ExtendedEventHandler?, modifiedFileSet: ModifiedFileSet, pathEntry: Root?
    ) {
        val tsgm: TimestampGranularityMonitor? = this.tsgm.get()
        val diff: com.google.devtools.build.skyframe.Differencer.Diff
        if (modifiedFileSet.treatEverythingAsModified()) {
            diff =
                FilesystemValueChecker(
                    tsgm,
                    syscallCache,
                    if (outputService == null)
                        XattrProviderOverrider.NO_OVERRIDE
                    else { delegate: XattrProvider? -> outputService.getXattrProvider(delegate) },  /* numThreads= */
                    200
                )
                    .getDirtyKeys(
                        memoizingEvaluator.getValues(),
                        DirtinessCheckerUtils.createBasicFilesystemDirtinessChecker()
                    )
        } else {
            diff = getDiff(tsgm, modifiedFileSet, pathEntry,  /* fsvcThreads= */200)
        }
        recordingDiffer.invalidate(diff.changedKeysWithoutNewValues())
        recordingDiffer.inject(diff.changedKeysWithNewValues())
        // Blaze invalidates transient errors on every build.
        invalidateTransientErrors()
    }

    /** Returns a particular configured target.  */
    @com.google.common.annotations.VisibleForTesting
    @Throws(java.lang.InterruptedException::class)
    fun getConfiguredTargetForTesting(
        eventHandler: ExtendedEventHandler?,
        label: Label?,
        configuration: BuildConfigurationValue?
    ): ConfiguredTarget? {
        val prerequisite: ConfiguredTargetAndData? =
            getConfiguredTargetAndDataForTesting(eventHandler, label, configuration)
        return if (prerequisite == null) null else prerequisite.getConfiguredTarget()
    }

    @com.google.common.annotations.VisibleForTesting
    @Throws(java.lang.InterruptedException::class)
    fun getConfiguredTargetAndDataForTesting(
        eventHandler: ExtendedEventHandler?,
        label: Label?,
        configuration: BuildConfigurationValue?
    ): ConfiguredTargetAndData? {
        val sink: ConfiguredTargetAndDataProducer.ResultSink? =
            object : ResultSink() {
                private var result: ConfiguredTargetAndData? = null

                public override fun acceptConfiguredTargetAndData(value: ConfiguredTargetAndData?, index: Int) {
                    this.result = value
                }

                public override fun acceptConfiguredTargetAndDataError(error: ConfiguredValueCreationException?) {}

                public override fun acceptConfiguredTargetAndDataError(error: InconsistentNullConfigException?) {}

                public override fun acceptConfiguredTargetAndDataError(error: NoSuchThingException?) {}
            }

        val result: EvaluationResult<SkyValue?>?
        EnableAnalysisScope().use { closer ->
            result =
                StateMachineEvaluatorForTesting.run(
                    ConfiguredTargetAndDataProducer(
                        ConfiguredTargetKey.builder()
                            .setLabel(label)
                            .setConfiguration(configuration)
                            .build(),  /* transitionKeys= */
                        com.google.common.collect.ImmutableList.of<E?>(),
                        TransitiveDependencyState.createForTesting(),
                        sink,  /* outputIndex= */
                        0,  /* baseTargetPrerequisitesSupplier= */
                        null
                    ),
                    memoizingEvaluator,
                    getEvaluationContextForTesting(eventHandler)
                )
        }
        if (result != null) {
            try {
                val unused: ErrorProcessingResult? =
                    SkyframeErrorProcessor.processAnalysisErrors(
                        result,
                        cyclesReporter,
                        eventHandler,  /* keepGoing= */
                        true,
                        tracksStateForIncrementality(),  /* eventBus= */
                        null,
                        bugReporter
                    )
            } catch (ignored: ViewCreationFailedException) {
                // Ignored.
            }
        }
        return sink.result
    }

    private fun getEvaluationContextForTesting(eventHandler: ExtendedEventHandler?): com.google.devtools.build.skyframe.EvaluationContext? {
        return newEvaluationContextBuilder()
            .setParallelism(DEFAULT_THREAD_COUNT)
            .setEventHandler(eventHandler)
            .build()
    }

    private inner class BaseTargetPrerequisitesSupplierImpl

        : BaseTargetPrerequisitesSupplier {
        @Throws(java.lang.InterruptedException::class)
        public override fun getPrerequisite(key: ConfiguredTargetKey?): ConfiguredTargetValue? {
            return memoizingEvaluator.getExistingValue(key) as ConfiguredTargetValue?
        }

        @Throws(java.lang.InterruptedException::class)
        public override fun getPrerequisiteConfiguration(key: BuildConfigurationKey?): BuildConfigurationValue? {
            return memoizingEvaluator.getExistingValue(key) as BuildConfigurationValue?
        }

        @Throws(java.lang.InterruptedException::class)
        public override fun getUnloadedToolchainContext(key: ToolchainContextKey?): UnloadedToolchainContext? {
            return memoizingEvaluator.getExistingValue(key) as UnloadedToolchainContext?
        }
    }

    /**
     * Prepares the Skyframe graph for Skyfocus.
     * 
     * 
     * This function is called at the beginning of a command, and it decides whether to run
     * Skyfocus or not.
     */
    fun prepareForSkyfocus(
        skyfocusOptions: SkyfocusOptions, reporter: com.google.devtools.build.lib.events.Reporter, productName: String
    ) {
        if (!memoizingEvaluator.skyfocusSupported()) {
            skyfocusState = SkyfocusState.Companion.DISABLED
            return
        }

        // Always reset top level evaluations for each invocation for an evaluator that supports
        // Skyfocus.
        memoizingEvaluator.cleanupLatestTopLevelEvaluations()

        if (!skyfocusOptions.getSkyfocusEnabled()) {
            skyfocusState = SkyfocusState.Companion.DISABLED
            return
        }

        reporter.handle(
            com.google.devtools.build.lib.events.Event.info(
                ("--experimental_enable_skyfocus is enabled. "
                        + com.google.devtools.build.lib.util.StringUtilities.capitalize(productName)
                        + " will reclaim memory not needed to build the active directories. Run '"
                        + productName
                        + " dump --skyframe=active_directories' to show the active directories, after this"
                        + " command.")
            )
        )

        if (skyfocusOptions.getFrontierViolationCheck() == FrontierViolationCheck.STRICT) {
            reporter.handle(
                com.google.devtools.build.lib.events.Event.warn("Changes outside of the active directories will cause a build error.")
            )
        }

        val newUserDefinedactiveDirectories: com.google.common.collect.ImmutableSet<String?> =
            com.google.common.collect.ImmutableSet.copyOf<String?>(skyfocusOptions.getActiveDirectories())
        val activeactiveDirectories: com.google.common.collect.ImmutableSet<FileStateKey?> =
            skyfocusState.activeDirectories

        if (!activeactiveDirectories.isEmpty()) {
            for (s in newUserDefinedactiveDirectories) {
                val key: FileStateKey? = SkyfocusExecutor.toFileStateKey(pkgLocator.get(), s)
                if (!activeactiveDirectories.contains(key)) {
                    // New active directories contains new files. Unfortunately, this is a suboptimal path,
                    // and we
                    // have to re-run full analysis.
                    reporter.handle(
                        com.google.devtools.build.lib.events.Event.warn(
                            "active directories changed to include new files, discarding analysis cache. This"
                                    + " can be expensive, so choose your active directories carefully."
                        )
                    )
                    resetEvaluator()
                    break
                }
            }
        }

        memoizingEvaluator.rememberTopLevelEvaluations(true)
        skyfocusState = skyfocusState.toBuilder().enabled(true).options(skyfocusOptions).build()
    }

    /**
     * Run Skyfocus. This only works if Skyfocus is enabled explicitly via the command-line flag, and
     * focusing is necessary (e.g. new active directories, or analysis cache was dropped).
     */
    @Throws(java.lang.InterruptedException::class)
    fun runSkyfocus(
        topLevelTargets: com.google.common.collect.ImmutableSet<Label?>,
        activeDirectoriesMatcher: java.util.Optional<PathFragmentPrefixTrie?>?,
        reporter: com.google.devtools.build.lib.events.Reporter,
        actionCache: ActionCache?,
        options: com.google.devtools.common.options.OptionsParsingResult
    ) {
        if (!skyfocusState.enabled || topLevelTargets.isEmpty()) {
            return
        }

        val beforeNodeCount: Int = this.evaluator.getValues().size()
        var beforeHeap: Long = 0
        if (skyfocusState.options.getDumpPostGcStats()) {
            // we have to gc once here to get an accurate reading on the exact work Skyfocus is
            // doing.
            java.lang.System.gc()
            beforeHeap =
                getHeapSize(
                    options
                        .getOptions<O?>(MemoryPressureOptions::class.java)
                        .getJvmHeapHistogramInternalObjectPattern()
                        .regexPattern()
                )
        }
        val beforeActionCacheEntries: Long = if (actionCache == null) 0 else actionCache.size()

        var skyFunctionCountBefore: com.google.common.collect.ImmutableMultiset<SkyFunctionName?> =
            com.google.common.collect.ImmutableMultiset.of<SkyFunctionName?>()
        val graph: InMemoryGraph = memoizingEvaluator.getInMemoryGraph()
        val dumpKeysOption: SkyfocusDumpOption? = skyfocusState.options.getDumpKeys()
        if (skyfocusState.options.getDumpKeys() != SkyfocusDumpOption.NONE) {
            skyFunctionCountBefore = getSkyFunctionNameCount(graph)
        }

        val maybeNewSkyfocusState: java.util.Optional<SkyfocusState> =
            SkyfocusExecutor.prepareActiveDirectories(
                topLevelTargets,
                activeDirectoriesMatcher,
                this.evaluator as InMemoryMemoizingEvaluator?,
                skyfocusState,
                packageManager,
                pkgLocator.get(),
                reporter
            )

        if (maybeNewSkyfocusState.isEmpty()) {
            return
        }

        val newSkyfocusState: SkyfocusState = maybeNewSkyfocusState.get()

        // Run Skyfocus!
        val focusResult: FocusResult =
            SkyfocusExecutor.execute(
                newSkyfocusState.activeDirectories,
                this.evaluator as InMemoryMemoizingEvaluator?,
                reporter,
                actionCache
            )

        skyfocusState =
            newSkyfocusState.toBuilder()
                .frontierSet(focusResult.deps)
                .verificationSet(focusResult.verificationSet)
                .build()

        // Shouldn't result in an empty graph.
        com.google.common.base.Preconditions.checkState(
            !focusResult.deps.isEmpty(),
            "FocusResult deps should not be empty"
        )
        com.google.common.base.Preconditions.checkState(
            !focusResult.rdeps.isEmpty(),
            "FocusResults rdeps should not be empty"
        )

        // Now that the graph has dropped nodes, run a GC to reclaim some memory.
        java.lang.System.gc()
        // Next, shrink the interners' backing maps - which now have larger
        // capacities than necessary - and reclaim some more memory.
        PooledInterner.shrinkAll()

        dumpSkyfocusKeys(dumpKeysOption, reporter, focusResult, graph, skyFunctionCountBefore)

        if (skyfocusState.options.getDumpKeys() != SkyfocusDumpOption.NONE) {
            reportMetricChange(
                reporter,
                "Rdep edges",
                focusResult.rdepEdgesBefore,
                focusResult.rdepEdgesAfter,
                LongFunction { i: Long -> java.lang.Long.toString(i) })

            reportMetricChange(
                reporter,
                "Node count",
                beforeNodeCount.toLong(),
                memoizingEvaluator.getValues().size().toLong(),
                LongFunction { i: Long -> java.lang.Long.toString(i) })

            if (actionCache != null) {
                reportMetricChange(
                    reporter,
                    "Action cache count",
                    beforeActionCacheEntries,
                    actionCache.size(),
                    LongFunction { i: Long -> java.lang.Long.toString(i) })
            }
        }

        if (skyfocusState.options.getDumpPostGcStats()) {
            reportMetricChange(
                reporter,
                "Heap",
                beforeHeap,
                getHeapSize(
                    options
                        .getOptions<O?>(MemoryPressureOptions::class.java)
                        .getJvmHeapHistogramInternalObjectPattern()
                        .regexPattern()
                ),
                LongFunction { bytes: Long -> com.google.devtools.build.lib.util.StringUtilities.prettyPrintBytes(bytes) })
        }
    }

    /**
     * Defines configuration for the diff checking and the progress message shown during a slow diff
     * check.
     */
    interface DiffCheckNotificationOptions {
        /**
         * Whether to allow a diff check for the given [EvaluatingVersionDiff]. A return of false
         * results in starting with a fresh Skyframe graph instead of an incremental build.
         */
        fun allowDiffCheck(
            versionDiff: EvaluatingVersionDiff?,
            eventHandler: com.google.devtools.build.lib.events.EventHandler?,
            options: com.google.devtools.common.options.OptionsProvider?
        ): Boolean

        val statusMessage: String?

        val statusUpdateDelay: java.time.Duration?
    }

    companion object {
        private val logger: GoogleLogger = GoogleLogger.forEnclosingClass()

        // TODO(bazel-team): Figure out how to handle value builders that block internally. Blocking
        // operations may need to be handled in another (bigger?) thread pool. Also, we should detect
        // the number of cores and use that as the thread-pool size for CPU-bound operations.
        // I just bumped this to 200 to get reasonable execution phase performance; that may cause
        // significant overhead for CPU-bound processes (i.e. analysis). [skyframe-analysis]
        @kotlin.jvm.JvmField
        val DEFAULT_THREAD_COUNT: Int =
        // Reduce thread count while running tests of Bazel. Test cases are typically small, and large
        // thread pools vying for a relatively small number of CPU cores may induce non-optimal
            // performance.
            if (TestType.isInTest()) 5 else 200

        // The limit of how many times we will traverse through an exception chain when catching a
        // target parsing exception.
        private const val EXCEPTION_TRAVERSAL_LIMIT = 10

        // A Semaphore to limit the number of in-flight execution of certain SkyFunctions to prevent OOM.
        // TODO(b/185987566): Remove this semaphore.
        private val DEFAULT_SEMAPHORE_SIZE: Int = java.lang.Runtime.getRuntime().availableProcessors()

        /**
         * Use the fact that analysis of a target must occur before execution of that target, and in a
         * separate Skyframe evaluation, to avoid propagating events from configured target nodes (and
         * more generally action lookup nodes) to action execution nodes. We take advantage of the fact
         * that if a node depends on an action lookup node and is not itself an action lookup node, then
         * it is an execution-phase node: the action lookup nodes are terminal in the analysis phase.
         * 
         * 
         * Skymeld: propagate events to BuildDriverKey nodes, since they cover both analysis &
         * execution.
         */
        val DEFAULT_EVENT_FILTER_WITH_ACTIONS: com.google.devtools.build.skyframe.EventFilter =
            object : com.google.devtools.build.skyframe.EventFilter() {
                override fun storeEvents(): Boolean {
                    return true
                }

                override fun shouldPropagate(depKey: SkyKey?, primaryKey: SkyKey?): Boolean {
                    // Do not propagate events from analysis phase nodes to execution phase nodes.
                    return isAnalysisPhaseActionLookupKey(primaryKey)
                            || !isAnalysisPhaseActionLookupKey(depKey) // Skymeld only.
                            || primaryKey is BuildDriverKey
                }
            }

        private fun isAnalysisPhaseActionLookupKey(key: SkyKey?): Boolean {
            return key is ActionLookupKey && key !is ActionTemplateExpansionKey
        }

        /**
         * Types that are created during loading, use significant space, and are definitely not needed
         * during execution unless explicitly named.
         * 
         * 
         * Some keys, like globs, may be re-evaluated during execution, so these types should only be
         * discarded if reverse deps are not being tracked!
         */
        private val LOADING_TYPES: com.google.common.collect.ImmutableSet<SkyFunctionName?> =
            com.google.common.collect.ImmutableSet.of<SkyFunctionName?>(
                SkyFunctions.PACKAGE, SkyFunctions.BZL_LOAD, SkyFunctions.BZL_COMPILE, SkyFunctions.GLOB
            )

        protected fun isEmptyOptionsKey(key: BuildConfigurationKey?): Boolean {
            if (key == null) {
                return false
            }
            return key.getOptionsChecksum().equals(EMPTY_OPTIONS.checksum())
        }

        /** Signals whether nodes (or some internal node data) can be removed from the analysis cache.  */
        private fun processDiscardAndDetermineRemoval(
            entry: InMemoryNodeEntry,
            discardType: DiscardType,
            topLevelPackages: com.google.common.collect.ImmutableSet<PackageIdentifier?>,
            topLevelTargets: MutableCollection<ConfiguredTarget?>,
            topLevelAspects: com.google.common.collect.ImmutableSet<AspectKey?>,
            trackIncrementalState: Boolean
        ): Boolean {
            val key: SkyKey = entry.getKey()
            val functionName: SkyFunctionName = key.functionName()
            if (discardType.discardsLoading()) {
                // Keep packages for top-level targets and aspects in memory to get the target from later.
                if (functionName == SkyFunctions.PACKAGE && topLevelPackages.contains(key.argument())) {
                    return false
                }
                if (LOADING_TYPES.contains(functionName)) {
                    return true
                }
            }
            if (discardType.discardsAnalysis()) {
                if (functionName == SkyFunctions.CONFIGURED_TARGET) {
                    val ctValue: ConfiguredTargetValue? = entry.getValue() as ConfiguredTargetValue?
                    if (ctValue == null) {
                        return false // Not successfully analyzed.
                    }
                    val configuredTarget: ConfiguredTarget? = ctValue.getConfiguredTarget()
                    if (configuredTarget == null) {
                        return false // It was already cleared.
                    }
                    val topLevel = topLevelTargets.contains(configuredTarget)
                    if (!topLevel && !trackIncrementalState && !hasActions(ctValue)) {
                        // If not tracking incremental state, removing these nodes doesn't hurt. Morally we should
                        // always be able to remove these, since they're not used for execution, but it leaves the
                        // graph inconsistent, and the --discard_analysis_cache with --track_incremental_state
                        // case isn't worth optimizing for.
                        return true
                    }
                    if (isEmptyOptionsKey(configuredTarget.getConfigurationKey())) {
                        // Keep these to avoid the need to re-create them later, they are dependencies of the
                        // empty configuration key and will never change.
                        return false
                    }
                    ctValue.clear(!topLevelTargets.contains(configuredTarget))
                } else if (functionName == SkyFunctions.ASPECT) {
                    val aspectKey: AspectKey = key as AspectKey
                    val aspectValue: AspectValue? = entry.getValue() as AspectValue?
                    if (aspectValue == null) {
                        return false // Not successfully analyzed.
                    }
                    val topLevel: Boolean = topLevelAspects.contains(key)
                    if (!topLevel && !trackIncrementalState && !hasActions(aspectValue)) {
                        return true
                    }
                    if (isEmptyOptionsKey(aspectKey.getConfigurationKey())) {
                        // Keep these to avoid the need to re-create them later, they are dependencies of the
                        // empty configuration key and will never change.
                        return false
                    }
                    aspectValue.clear(!topLevel)
                }
            }
            return false
        }

        private fun hasActions(value: ConfiguredObjectValue?): Boolean {
            return value is ActionLookupValue && !value.getActions().isEmpty()
        }

        @Throws(java.lang.InterruptedException::class)
        private fun getConfigurationFromGraph(
            graph: WalkableGraph, key: BuildConfigurationKey?
        ): BuildConfigurationValue? {
            return if (key == null) null else graph.getValue(key) as BuildConfigurationValue?
        }

        private fun constructNoCycleTargetParsingException(
            eventHandler: ExtendedEventHandler,
            targetPatterns: MutableList<String?>?,
            errorInfo: com.google.devtools.build.skyframe.ErrorInfo
        ): TargetParsingException {
            val e: java.lang.Exception =
                com.google.common.base.Preconditions.checkNotNull<java.lang.Exception>(errorInfo.getException())
            val detailedExitCode: DetailedExitCode? = traverseExceptionChain(e)
            if (e !is TargetParsingException) {
                // If it's a TargetParsingException, then the TargetPatternPhaseFunction has already
                // reported the error, so we don't need to report it again.
                eventHandler.post(PatternExpandingError.failed(targetPatterns, e.getMessage()))
            }

            // Following SkyframeTargetPatternEvaluator, we create with a new TargetParsingException either
            // with an existing DetailedExitCode, or with a FailureDetail Code.
            val cause: Throwable? = if (e is TargetParsingException) e.getCause() else e
            return if (detailedExitCode != null)
                TargetParsingException(e.getMessage(), cause, detailedExitCode)
            else
                TargetParsingException(
                    e.getMessage(), cause, TargetPatterns.Code.TARGET_PATTERN_PARSE_FAILURE
                )
        }

        private fun traverseExceptionChain(topLevelException: java.lang.Exception): DetailedExitCode? {
            var traverseException: java.lang.Exception = topLevelException
            var detailedExitCode: DetailedExitCode? = null
            var traverseLevel = 0
            while (traverseLevel < EXCEPTION_TRAVERSAL_LIMIT) {
                traverseLevel++
                detailedExitCode = DetailedException.getDetailedExitCode(traverseException)
                if (detailedExitCode != null || traverseException.getCause() == null) {
                    break
                }
                traverseException = traverseException.getCause() as java.lang.Exception
            }
            return detailedExitCode
        }

        /** Canonical Starlark flag aliases for [PythonOptions] flags.  */ // TODO: b/453809359 - Remove when Bazel 9+ can read Python flag alias definitions straight from
        // rules_python's MODULE.bazel.
        private val PY_FLAG_ALIASES: com.google.common.collect.ImmutableMap<String?, String?> =
            com.google.common.collect.ImmutableMap.of<String?, String?>(
                "build_python_zip",
                "@@rules_python+//python/config_settings:build_python_zip",
                "incompatible_default_to_explicit_init_py",
                "@@rules_python+//python/config_settings:incompatible_default_to_explicit_init_py"
            )

        /** Canonical Starlark flag aliases for [BazelPythonConfiguration] flags.  */ // TODO: b/453809359 - Remove when Bazel 9+ can read Python flag alias definitions straight from
        // rules_python's MODULE.bazel.
        private val BAZEL_PY_FLAG_ALIASES: com.google.common.collect.ImmutableMap<String?, String?> =
            com.google.common.collect.ImmutableMap.of<String?, String?>(
                "python_path",
                "@@rules_python+//python/config_settings:python_path",
                "experimental_python_import_all_repositories",
                "@@rules_python+//python/config_settings:experimental_python_import_all_repositories"
            )

        private const val MAX_NUMBER_OF_CHANGED_KEYS_TO_LOG = 10

        private fun logDiffInfo(
            pathEntries: Iterable<Root?>,
            changedWithoutNewValue: Iterable<SkyKey>,
            numWithoutNewValues: Int,
            changedWithNewValue: MutableSet<SkyKey?>
        ) {
            val numModified: Int = changedWithNewValue.size() + numWithoutNewValues
            val result: java.lang.StringBuilder =
                java.lang.StringBuilder("DiffAwareness found ")
                    .append(numModified)
                    .append(" modified source files and directory listings")
            if (!com.google.common.collect.Iterables.isEmpty(pathEntries)) {
                result.append(" for ")
                result.append(com.google.common.base.Joiner.on(", ").join(pathEntries))
            }

            if (numModified > 0) {
                val allModifiedKeys: Iterable<SkyKey?> =
                    com.google.common.collect.Iterables.concat<SkyKey?>(changedWithoutNewValue, changedWithNewValue)
                val trimmed: Iterable<SkyKey?> =
                    com.google.common.collect.Iterables.limit<SkyKey?>(
                        allModifiedKeys,
                        MAX_NUMBER_OF_CHANGED_KEYS_TO_LOG
                    )

                result.append(": ").append(com.google.common.base.Joiner.on(", ").join(trimmed))

                if (numModified > MAX_NUMBER_OF_CHANGED_KEYS_TO_LOG) {
                    result.append(", ...")
                }
            }

            logger.atInfo().log("%s", result)
        }

        private fun getNumberOfModifiedFiles(modifiedValues: Iterable<SkyKey>): Int {
            // We are searching only for changed files, DirectoryListingValues don't depend on
            // child values, that's why they are invalidated separately
            return com.google.common.collect.Iterables.size(
                com.google.common.collect.Iterables.filter<SkyKey?>(
                    modifiedValues,
                    SkyFunctionName.functionIs(FileStateKey.FILE_STATE)
                )
            )
        }

        private val NULL_UNNECESSARY_TEMPORARY_STATE_DROPPER: UnnecessaryTemporaryStateDropper =
            UnnecessaryTemporaryStateDropper {}

        /**
         * Returns the current heap size in bytes.
         * 
         * 
         * Identical implementation to `blaze info used-heap-size-after-gc`, except that depending on
         * that function would cause a cyclic dep.
         * 
         * 
         * TODO: b/311665999 - Remove the subtraction of FillerArray once we figure out an alternative.
         */
        private fun getHeapSize(internalJvmObjectPattern: java.util.regex.Pattern?): Long {
            val memBean: java.lang.management.MemoryMXBean = java.lang.management.ManagementFactory.getMemoryMXBean()
            return (memBean.getHeapMemoryUsage().getUsed()
                    - HeapOffsetHelper.getSizeOfFillerArrayOnHeap(
                internalJvmObjectPattern, BugReporter.defaultInstance()
            ))
        }

        /**
         * Reports the reduction in the given value from before to after.
         * 
         * @param eventHandler the event handler
         * @param prefix the prefix to use for the message
         * @param before the value before
         * @param after the value after
         * @param valueFormatter the function to format the value
         */
        private fun reportMetricChange(
            eventHandler: ExtendedEventHandler,
            prefix: String,
            before: Long,
            after: Long,
            valueFormatter: LongFunction<String?>
        ) {
            com.google.common.base.Preconditions.checkState(!prefix.isEmpty(), "A prefix must be specified.")

            var message: String? =
                java.lang.String.format(
                    "%s: %s -> %s", prefix, valueFormatter.apply(before), valueFormatter.apply(after)
                )
            if (before > 0) {
                val change = (before - after).toDouble() / before * 100
                message += java.lang.String.format(" (%+.2f%%)", -change)
            }

            eventHandler.handle(com.google.devtools.build.lib.events.Event.info(message))
        }

        /**
         * Reports the computed set of SkyKeys that need to be kept in the Skyframe graph for incremental
         * correctness.
         * 
         * @param reporter the event reporter
         * @param focusResult the result from SkyframeFocuser
         */
        private fun dumpSkyfocusKeys(
            dumpKeysOption: SkyfocusDumpOption?,
            reporter: com.google.devtools.build.lib.events.Reporter,
            focusResult: FocusResult,
            graph: InMemoryGraph,
            skyFunctionNameCountsBefore: com.google.common.collect.ImmutableMultiset<SkyFunctionName?>
        ) {
            if (dumpKeysOption == SkyfocusDumpOption.VERBOSE) {
                PrintStream(reporter.getOutErr().getOutputStream()).use { pos ->
                    pos.println("Roots kept: " + focusResult.roots.size())
                    focusResult.roots.forEach(java.util.function.Consumer { k: SkyKey? -> pos.println(k.getCanonicalName()) })

                    pos.println("Leafs (including active directories) kept: " + focusResult.leafs.size())
                    focusResult.leafs.forEach(java.util.function.Consumer { k: SkyKey? -> pos.println("leaf: " + k.getCanonicalName()) })

                    pos.println("Rdeps kept: " + focusResult.rdeps.size())
                    focusResult.rdeps.forEach(java.util.function.Consumer { k: SkyKey? -> pos.println(k.getCanonicalName()) })

                    pos.println("Deps kept: " + focusResult.deps.size())
                    focusResult.deps.forEach(java.util.function.Consumer { k: SkyKey? -> pos.println(k.getCanonicalName()) })

                    pos.println("Verification set: " + focusResult.verificationSet.size())
                    focusResult.verificationSet.forEach(java.util.function.Consumer { k: SkyKey? -> pos.println(k.getCanonicalName()) })
                }
            } else if (dumpKeysOption == SkyfocusDumpOption.COUNT) {
                reporter.handle(
                    com.google.devtools.build.lib.events.Event.info(
                        java.lang.String.format(
                            "Roots kept: %d",
                            focusResult.roots.size()
                        )
                    )
                )
                reporter.handle(
                    com.google.devtools.build.lib.events.Event.info(
                        java.lang.String.format(
                            "Leafs kept: %d",
                            focusResult.leafs.size()
                        )
                    )
                )
                reporter.handle(
                    com.google.devtools.build.lib.events.Event.info(
                        java.lang.String.format(
                            "Rdeps kept: %d",
                            focusResult.rdeps.size()
                        )
                    )
                )
                reporter.handle(
                    com.google.devtools.build.lib.events.Event.info(
                        java.lang.String.format(
                            "Deps kept: %d",
                            focusResult.deps.size()
                        )
                    )
                )
                reporter.handle(
                    com.google.devtools.build.lib.events.Event.info(
                        java.lang.String.format(
                            "Verification set: %d",
                            focusResult.verificationSet.size()
                        )
                    )
                )
                val skyFunctionNameCountsAfter: com.google.common.collect.ImmutableMultiset<SkyFunctionName?> =
                    getSkyFunctionNameCount(graph)
                skyFunctionNameCountsBefore.forEachEntry(
                    ObjIntConsumer { entry: SkyFunctionName?, beforeCount: Int ->
                        reportMetricChange(
                            reporter,
                            entry.toString(),
                            beforeCount.toLong(),
                            skyFunctionNameCountsAfter.count(entry).toLong(),
                            LongFunction { i: Long -> java.lang.Long.toString(i) })
                    })
            }
        }

        /**
         * Returns a multiset of the SkyFunctionNames in the given graph, sorted by the highest count
         * first.
         */
        private fun getSkyFunctionNameCount(graph: InMemoryGraph): com.google.common.collect.ImmutableMultiset<SkyFunctionName?> {
            val counts: com.google.common.collect.Multiset<SkyFunctionName?> =
                com.google.common.collect.ConcurrentHashMultiset.create<SkyFunctionName?>()
            graph.parallelForEach(java.util.function.Consumer { entry: InMemoryNodeEntry? ->
                counts.add(
                    entry.getKey().functionName()
                )
            })
            return com.google.common.collect.Multisets.copyHighestCountFirst<SkyFunctionName?>(counts)
        }
    }
}
