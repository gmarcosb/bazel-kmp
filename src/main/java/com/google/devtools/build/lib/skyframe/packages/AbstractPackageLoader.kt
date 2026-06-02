// Copyright 2017 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.skyframe.packages

import com.google.devtools.build.lib.analysis.BlazeDirectories

/**
 * Abstract base class of a [PackageLoader] implementation that has no incrementality or
 * caching.
 */
abstract class AbstractPackageLoader internal constructor(builder: Builder) : PackageLoader {
    private val preinjectedDiff: ImmutableDiff
    private val preinjectedDifferencer: Differencer = object : Differencer() {
        override fun getDiff(
            fromGraph: WalkableGraph?,
            fromVersion: com.google.devtools.build.skyframe.Version?,
            toVersion: com.google.devtools.build.skyframe.Version?
        ): com.google.devtools.build.skyframe.Differencer.Diff {
            return preinjectedDiff
        }
    }
    private val commonReporter: com.google.devtools.build.lib.events.Reporter?
    protected val ruleClassProvider: ConfiguredRuleClassProvider
    private val pkgFactory: PackageFactory
    protected val starlarkSemantics: net.starlark.java.eval.StarlarkSemantics?
    protected val extraSkyFunctions: com.google.common.collect.ImmutableMap<SkyFunctionName?, SkyFunction?>
    private val pkgLocatorRef: AtomicReference<PathPackageLocator?>
    protected val externalFilesHelper: ExternalFilesHelper?
    protected val directories: BlazeDirectories
    private val hashFunction: com.google.common.hash.HashFunction?
    private val nonSkyframeGlobbingThreads: Int

    @kotlin.jvm.JvmField
    @com.google.common.annotations.VisibleForTesting
    val forkJoinPoolForNonSkyframeGlobbing: ForkJoinPool
    private val skyframeThreads: Int

    /**
     * Determines the size of a semaphore to use when loading packages.
     * 
     * 
     * Package loading does a mix of CPU work and blocking I/O work so it can be better for
     * performance to oversubscribe package loading threads relative to CPUs. However, that may lead
     * to a condition where CPU work thrashes due to context switching. Setting this semaphore to the
     * CPU count mitigates the thrashing, but won't do much without [skyframeThreads] greater
     * than CPU count.
     * 
     * 
     * A value of 0 disables the semaphore.
     */
    private val cpuBoundSemaphoreTokenCount: Int

    /** Abstract base class of a builder for [PackageLoader] instances.  */
    abstract class Builder protected constructor(
        workspaceDir: Root,
        installBase: com.google.devtools.build.lib.vfs.Path?,
        outputBase: com.google.devtools.build.lib.vfs.Path?,
        buildFilesByPriority: com.google.common.collect.ImmutableList<BuildFileName?>?,
        externalFileAction: ExternalFileAction?
    ) {
        val workspaceDir: com.google.devtools.build.lib.vfs.Path?
        val directories: BlazeDirectories
        val pkgLocator: PathPackageLocator
        val pkgLocatorRef: AtomicReference<PathPackageLocator?>
        private var externalFileAction: ExternalFileAction?
        var externalFilesHelper: ExternalFilesHelper? = null
        var ruleClassProvider: ConfiguredRuleClassProvider = this.defaultRuleClassProvider
        var starlarkSemantics: net.starlark.java.eval.StarlarkSemantics? = null
        var lazyMacroExpansionPackages: LazyMacroExpansionPackages? = LazyMacroExpansionPackages.NONE
        var commonReporter: com.google.devtools.build.lib.events.Reporter =
            com.google.devtools.build.lib.events.Reporter()
        var extraSkyFunctions: MutableMap<SkyFunctionName?, SkyFunction?> = HashMap<SkyFunctionName?, SkyFunction?>()
        var extraPrecomputedValues: MutableList<Injected?> = java.util.ArrayList<Injected?>()
        var nonSkyframeGlobbingThreads: Int = 1
        var skyframeThreads: Int = 1
        var cpuBoundSemaphoreTokenCount: Int = 0

        init {
            this.workspaceDir = workspaceDir.asPath()
            val devNull: com.google.devtools.build.lib.vfs.Path? = workspaceDir.getRelative("/dev/null")
            directories =
                BlazeDirectories(
                    ServerDirectories(installBase, outputBase, devNull), this.workspaceDir, "blaze"
                )

            this.pkgLocator =
                PathPackageLocator(
                    directories.getOutputBase(),
                    com.google.common.collect.ImmutableList.of<E?>(workspaceDir),
                    buildFilesByPriority
                )
            this.pkgLocatorRef = AtomicReference<PathPackageLocator?>(pkgLocator)
            this.externalFileAction = externalFileAction

            this.commonReporter.addHandler(EventBusEventHandler.createWithNewEventBus())
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setRuleClassProvider(ruleClassProvider: ConfiguredRuleClassProvider): Builder {
            this.ruleClassProvider = ruleClassProvider
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setStarlarkSemantics(semantics: net.starlark.java.eval.StarlarkSemantics): Builder {
            this.starlarkSemantics = semantics
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun useDefaultStarlarkSemantics(): Builder {
            this.starlarkSemantics = net.starlark.java.eval.StarlarkSemantics.DEFAULT
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setLazyMacroExpansionPackages(packages: LazyMacroExpansionPackages?): Builder {
            this.lazyMacroExpansionPackages = packages
            return this
        }

        /** Sets the reporter used by all skyframe evaluations.  */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setCommonReporter(commonReporter: com.google.devtools.build.lib.events.Reporter): Builder {
            this.commonReporter = commonReporter
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun addExtraSkyFunctions(
            extraSkyFunctions: com.google.common.collect.ImmutableMap<SkyFunctionName?, SkyFunction?>?
        ): Builder {
            this.extraSkyFunctions.putAll(extraSkyFunctions)
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun addExtraPrecomputedValues(vararg extraPrecomputedValues: Injected?): Builder {
            return this.addExtraPrecomputedValues(java.util.Arrays.asList<Injected?>(*extraPrecomputedValues))
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun addExtraPrecomputedValues(
            extraPrecomputedValues: MutableList<Injected?>?
        ): Builder {
            this.extraPrecomputedValues.addAll(extraPrecomputedValues)
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setNonSkyframeGlobbingThreads(numThreads: Int): Builder {
            this.nonSkyframeGlobbingThreads = numThreads
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setSkyframeThreads(skyframeThreads: Int): Builder {
            this.skyframeThreads = skyframeThreads
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setCpuBoundSemaphoreTokenCount(tokenCount: Int): Builder {
            this.cpuBoundSemaphoreTokenCount = tokenCount
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setExternalFileAction(externalFileAction: ExternalFileAction?): Builder {
            this.externalFileAction = externalFileAction
            return this
        }

        /** Throws [IllegalArgumentException] if builder args are incomplete/inconsistent.  */
        protected fun validate() {
            requireNotNull(starlarkSemantics) { "must call either setStarlarkSemantics or useDefaultStarlarkSemantics" }
        }

        fun build(): PackageLoader? {
            validate()
            externalFilesHelper =
                ExternalFilesHelper.create(
                    pkgLocatorRef,
                    externalFileAction,
                    directories,  /* repoContentsCachePathSupplier= */
                    java.util.function.Supplier { null })
            return buildImpl()
        }

        protected abstract fun buildImpl(): PackageLoader?

        protected abstract val defaultRuleClassProvider: ConfiguredRuleClassProvider
    }

    init {
        this.ruleClassProvider = builder.ruleClassProvider
        this.starlarkSemantics = builder.starlarkSemantics
        this.commonReporter = builder.commonReporter
        this.extraSkyFunctions =
            com.google.common.collect.ImmutableMap.copyOf<SkyFunctionName?, SkyFunction?>(builder.extraSkyFunctions)
        this.pkgLocatorRef = builder.pkgLocatorRef
        this.nonSkyframeGlobbingThreads = builder.nonSkyframeGlobbingThreads
        this.forkJoinPoolForNonSkyframeGlobbing =
            NamedForkJoinPool.newNamedPool(
                "package-loader-globbing-pool", builder.nonSkyframeGlobbingThreads
            )
        this.skyframeThreads = builder.skyframeThreads
        this.cpuBoundSemaphoreTokenCount = builder.cpuBoundSemaphoreTokenCount
        this.directories = builder.directories
        this.hashFunction = builder.workspaceDir.getFileSystem().getDigestFunction().getHashFunction()

        this.externalFilesHelper = builder.externalFilesHelper

        this.preinjectedDiff =
            makePreinjectedDiff(
                starlarkSemantics,
                builder.pkgLocator,
                com.google.common.collect.ImmutableList.copyOf<Injected?>(builder.extraPrecomputedValues),
                builder.lazyMacroExpansionPackages
            )
        pkgFactory =
            PackageFactory(
                ruleClassProvider,
                forkJoinPoolForNonSkyframeGlobbing,
                PackageSettings.DEFAULTS,
                PackageValidator.NOOP_VALIDATOR,
                PackageOverheadEstimator.NOOP_ESTIMATOR,
                PackageLoadingListener.NOOP_LISTENER
            )
    }

    override fun close() {
        // We don't use ForkJoinPool#shutdownNow since it has a performance bug. See
        // http://b/33482341#comment13.
        forkJoinPoolForNonSkyframeGlobbing.shutdown()
    }

    @Throws(NoSuchPackageException::class, java.lang.InterruptedException::class)
    override fun loadPackage(pkgId: PackageIdentifier): Package? {
        return makeLoadingContext()
            .loadPackages(com.google.common.collect.ImmutableList.of<PackageIdentifier?>(pkgId))
            .getLoadedValues()
            .get(pkgId)
            .get()
    }

    private class LoadingContext(
        evaluator: MemoizingEvaluator,
        evaluationContext: com.google.devtools.build.skyframe.EvaluationContext?,
        storedEventHandler: StoredEventHandler
    ) : com.google.devtools.build.lib.skyframe.packages.PackageLoader.LoadingContext {
        private val evaluator: MemoizingEvaluator
        private val evaluationContext: com.google.devtools.build.skyframe.EvaluationContext?
        private val storedEventHandler: StoredEventHandler

        init {
            this.evaluator = evaluator
            this.evaluationContext = evaluationContext
            this.storedEventHandler = storedEventHandler
        }

        @Throws(java.lang.InterruptedException::class)
        override fun loadPackages(
            pkgIds: Iterable<PackageIdentifier?>?
        ): com.google.devtools.build.lib.skyframe.packages.PackageLoader.Result<PackageIdentifier?, Package?, NoSuchPackageException?> {
            storedEventHandler.clear()
            val pkgKeys: com.google.common.collect.ImmutableSet<SkyKey> =
                com.google.common.collect.ImmutableSet.< E > copyOf < E >(pkgIds)
            val evalResult: EvaluationResult<PackageValue?> =
                evaluator.evaluate<PackageValue?>(pkgKeys, evaluationContext)

            val resultBuilder: com.google.common.collect.ImmutableMap.Builder<PackageIdentifier?, ValueOrException<Package?, NoSuchPackageException?>?> =
                com.google.common.collect.ImmutableMap.builder<PackageIdentifier?, ValueOrException<Package?, NoSuchPackageException?>?>()
            for (key in pkgKeys) {
                val error: com.google.devtools.build.skyframe.ErrorInfo? = evalResult.getError(key)
                val packageValue: PackageValue? = evalResult.get(key)
                com.google.common.base.Preconditions.checkState((error == null) != (packageValue == null))
                val pkgId: PackageIdentifier? = key.argument() as PackageIdentifier?
                resultBuilder.put(
                    pkgId,
                    if (error != null)
                        ValueOrException.ofException<Package?, NoSuchPackageException?>(
                            exceptionFromErrorInfo(
                                error,
                                pkgId
                            )
                        )
                    else
                        ValueOrException.ofValue<Package?, NoSuchPackageException?>(packageValue.getPackage())
                )
            }
            return com.google.devtools.build.lib.skyframe.packages.PackageLoader.Result<K?, V?, E?>(
                resultBuilder.buildOrThrow(),
                storedEventHandler.getEvents()
            )
        }

        @Throws(java.lang.InterruptedException::class)
        override fun loadModules(labels: Iterable<Label?>): com.google.devtools.build.lib.skyframe.packages.PackageLoader.Result<Label?, net.starlark.java.eval.Module?, StarlarkModuleLoadingException?> {
            storedEventHandler.clear()
            val keys: com.google.common.collect.ImmutableList<BzlLoadValue.Key>
            Label > com.google.common.collect.Streams.stream<Label?>(labels).map<Any?>(BzlLoadValue::keyForBuild)
                .collect(TODO("Cannot convert element"))<Object> com . google . common . collect . ImmutableList . toImmutableList < kotlin . Any ? > ()


            val evalResult: EvaluationResult<BzlLoadValue?> = evaluator.evaluate<T?>(keys, evaluationContext)
            val resultBuilder: com.google.common.collect.ImmutableMap.Builder<Label?, ValueOrException<net.starlark.java.eval.Module?, StarlarkModuleLoadingException?>?> =
                com.google.common.collect.ImmutableMap.builderWithExpectedSize<Label?, ValueOrException<net.starlark.java.eval.Module?, StarlarkModuleLoadingException?>?>(
                    keys.size()
                )
            for (key in keys) {
                val error: com.google.devtools.build.skyframe.ErrorInfo? = evalResult.getError(key)
                val moduleValue: BzlLoadValue? = evalResult.get(key)
                com.google.common.base.Preconditions.checkState((error == null) != (moduleValue == null))
                val label: Label? = key.label
                if (error == null) {
                    resultBuilder.put(label, ValueOrException.ofValue<V?, E?>(moduleValue.getModule()))
                } else {
                    resultBuilder.put(
                        label,
                        ValueOrException.ofException<net.starlark.java.eval.Module?, StarlarkModuleLoadingException?>(
                            com.google.devtools.build.lib.skyframe.packages.AbstractPackageLoader.LoadingContext.Companion.starlarkModuleLoadingExceptionFromErrorInfo(
                                error,
                                label
                            )
                        )
                    )
                }
            }
            return com.google.devtools.build.lib.skyframe.packages.PackageLoader.Result<K?, V?, E?>(
                resultBuilder.buildOrThrow(),
                storedEventHandler.getEvents()
            )
        }

        @get:Throws(java.lang.InterruptedException::class)
        val repositoryMapping: RepositoryMapping?
            get() {
                val key: SkyKey = RepositoryMappingValue.Companion.key(RepositoryName.MAIN)
                val evalResult: EvaluationResult<RepositoryMappingValue> =
                    evaluator.evaluate<RepositoryMappingValue?>(
                        com.google.common.collect.ImmutableList.of<SkyKey?>(key),
                        evaluationContext
                    )
                val mainRepositoryMappingValue: RepositoryMappingValue = evalResult.get(key)
                // We always set up a repository mapping function
                com.google.common.base.Preconditions.checkState(evalResult.getError(key) == null && mainRepositoryMappingValue != null)
                return mainRepositoryMappingValue.repositoryMapping
            }

        companion object {
            private fun starlarkModuleLoadingExceptionFromErrorInfo(
                error: com.google.devtools.build.skyframe.ErrorInfo, label: Label?
            ): StarlarkModuleLoadingException {
                if (!error.getCycleInfo().isEmpty()) {
                    return StarlarkModuleLoadingException("Cycle encountered while loading " + label)
                }
                val e: Throwable =
                    com.google.common.base.Preconditions.checkNotNull<java.lang.Exception>(error.getException())
                if (e is BzlLoadFailedException) {
                    return StarlarkModuleLoadingException(e)
                }
                throw java.lang.IllegalStateException(
                    "Unexpected Exception type from BzlLoadValue for " + label + " with error: " + error, e
                )
            }
        }
    }

    override fun makeLoadingContext(): LoadingContext {
        val reporter: com.google.devtools.build.lib.events.Reporter =
            com.google.devtools.build.lib.events.Reporter(commonReporter)
        val storedEventHandler: StoredEventHandler = StoredEventHandler()
        reporter.addHandler(storedEventHandler)
        val evaluationContext: com.google.devtools.build.skyframe.EvaluationContext? =
            com.google.devtools.build.skyframe.EvaluationContext.newBuilder()
                .setKeepGoing(true)
                .setParallelism(skyframeThreads)
                .setEventHandler(reporter)
                .build()

        return com.google.devtools.build.lib.skyframe.packages.AbstractPackageLoader.LoadingContext(
            makeFreshEvaluator(),
            evaluationContext,
            storedEventHandler
        )
    }

    fun getRuleClassProvider(): ConfiguredRuleClassProvider {
        return ruleClassProvider
    }

    private fun makeFreshEvaluator(): MemoizingEvaluator {
        return InMemoryMemoizingEvaluator(
            makeFreshSkyFunctions(),
            preinjectedDifferencer,
            EvaluationProgressReceiver.NULL,
            GraphInconsistencyReceiver.THROWING,
            com.google.devtools.build.skyframe.EventFilter.FULL_STORAGE,
            EmittedEventState(),  /* keepEdges= */
            false,  // Using pooled interner is unsound if there are multiple MemoizingEvaluators evaluating
            // concurrently.
            /* usePooledInterning= */
            false
        )
    }

    protected abstract val crossRepositoryLabelViolationStrategy: CrossRepositoryLabelViolationStrategy?

    protected abstract val buildFilesByPriority: com.google.common.collect.ImmutableList<BuildFileName?>?

    protected abstract val actionOnIOExceptionReadingBuildFile: ActionOnIOExceptionReadingBuildFile?

    protected abstract fun shouldUseRepoDotBazel(): Boolean

    private fun makeFreshSkyFunctions(): com.google.common.collect.ImmutableMap<SkyFunctionName?, SkyFunction?> {
        val tsgm: TimestampGranularityMonitor =
            TimestampGranularityMonitor(com.google.devtools.build.lib.clock.BlazeClock.instance())
        val syscallCache: DefaultSyscallCache? =
            DefaultSyscallCache.newBuilder().setInitialCapacity(nonSkyframeGlobbingThreads).build()
        pkgFactory.setSyscallCache(syscallCache)
        pkgFactory.setMaxDirectoriesToEagerlyVisitInGlobbing(
            MAX_DIRECTORIES_TO_EAGERLY_VISIT_IN_GLOBBING
        )
        val cachingPackageLocator: CachingPackageLocator =
            object : CachingPackageLocator() {
                public override fun getBuildFileForPackage(packageName: PackageIdentifier?): com.google.devtools.build.lib.vfs.Path? {
                    return pkgLocatorRef.get().getPackageBuildFileNullable(packageName, syscallCache)
                }

                public override fun getBaseNameForLoadedPackage(packageName: PackageIdentifier?): String? {
                    val buildFileForPackage: com.google.devtools.build.lib.vfs.Path? =
                        getBuildFileForPackage(packageName)
                    return if (buildFileForPackage == null) null else buildFileForPackage.getBaseName()
                }
            }
        val cpuBoundSemaphore: AtomicReference<Semaphore?> =
            AtomicReference<Semaphore?>(
                if (cpuBoundSemaphoreTokenCount > 0) Semaphore(cpuBoundSemaphoreTokenCount) else null
            )
        val builder: com.google.common.collect.ImmutableMap.Builder<SkyFunctionName?, SkyFunction?> =
            com.google.common.collect.ImmutableMap.builder<SkyFunctionName?, SkyFunction?>()
        builder
            .put(SkyFunctions.PRECOMPUTED, PrecomputedFunction())
            .put(
                FileStateKey.FILE_STATE,
                FileStateFunction(java.util.function.Supplier { tsgm }, syscallCache, externalFilesHelper)
            )
            .put(FileSymlinkCycleUniquenessFunction.NAME, FileSymlinkCycleUniquenessFunction())
            .put(
                FileSymlinkInfiniteExpansionUniquenessFunction.NAME,
                FileSymlinkInfiniteExpansionUniquenessFunction()
            )
            .put(SkyFunctions.FILE, FileFunction(pkgLocatorRef, directories))
            .put(
                SkyFunctions.PACKAGE_LOOKUP,
                PackageLookupFunction( /* deletedPackages= */
                    AtomicReference<com.google.common.collect.ImmutableSet<PackageIdentifier?>?>(com.google.common.collect.ImmutableSet.of<PackageIdentifier?>()),
                    this.crossRepositoryLabelViolationStrategy,
                    this.buildFilesByPriority
                )
            )
            .put(SkyFunctions.IGNORED_SUBDIRECTORIES, IgnoredSubdirectoriesFunction.Companion.NOOP)
            .put(SkyFunctions.CONTAINING_PACKAGE_LOOKUP, ContainingPackageLookupFunction())
            .put(
                SkyFunctions.BZL_COMPILE,
                BzlCompileFunction(
                    ruleClassProvider.getBazelStarlarkEnvironment(),
                    hashFunction,
                    PackageLoadingListener.NOOP_LISTENER
                )
            )
            .put(
                SkyFunctions.STARLARK_BUILTINS,
                StarlarkBuiltinsFunction(ruleClassProvider.getBazelStarlarkEnvironment())
            )
            .put(
                SkyFunctions.BZL_LOAD,
                BzlLoadFunction.create(
                    ruleClassProvider,
                    directories,
                    hashFunction,
                    PackageLoadingListener.NOOP_LISTENER,
                    Caffeine.newBuilder().build<BzlCompileValue.Key?, BzlCompileValue?>()
                )
            )
            .put(
                SkyFunctions.REPO_FILE,
                RepoFileFunction(
                    ruleClassProvider.getBazelStarlarkEnvironment(),
                    Root.fromPath(directories.getWorkspace())
                )
            )
            .put(SkyFunctions.REPO_PACKAGE_ARGS, RepoPackageArgsFunction.Companion.INSTANCE)
            .put(SkyFunctions.REPOSITORY_MAPPING, RepositoryMappingFunction(ruleClassProvider))
            .put(
                SkyFunctions.PACKAGE,
                PackageFunction.newBuilder()
                    .setPackageFactory(pkgFactory)
                    .setPackageLocator(cachingPackageLocator)
                    .setActionOnIOExceptionReadingBuildFile(this.actionOnIOExceptionReadingBuildFile)
                    .setShouldUseRepoDotBazel(shouldUseRepoDotBazel())
                    .setGlobbingStrategy(GlobbingStrategy.NON_SKYFRAME)
                    .setCpuBoundSemaphore(cpuBoundSemaphore)
                    .build()
            )
            .put(SkyFunctions.PACKAGE_DECLARATIONS, PackageDeclarationsFunction())
            .put(SkyFunctions.MACRO_INSTANCE, MacroInstanceFunction())
            .put(SkyFunctions.EVAL_MACRO, EvalMacroFunction(pkgFactory, cpuBoundSemaphore))
            .put(SkyFunctions.NON_FINALIZER_PACKAGE_PIECES, NonFinalizerPackagePiecesFunction())
            .putAll(extraSkyFunctions)
        return builder.buildOrThrow()
    }

    companion object {
        // See {@link PackageFactory.setMaxDirectoriesToEagerlyVisitInGlobbing}.
        private const val MAX_DIRECTORIES_TO_EAGERLY_VISIT_IN_GLOBBING = 3000

        private fun makePreinjectedDiff(
            starlarkSemantics: net.starlark.java.eval.StarlarkSemantics?,
            pkgLocator: PathPackageLocator?,
            extraPrecomputedValues: com.google.common.collect.ImmutableList<Injected>,
            lazyMacroExpansionPackages: LazyMacroExpansionPackages?
        ): ImmutableDiff {
            val valuesToInject: MutableMap<SkyKey?, Delta?> = HashMap<SkyKey?, Delta?>()
            val injectable: Injectable =
                object : Injectable() {
                    override fun inject(deltas: MutableMap<SkyKey?, Delta?>?) {
                        valuesToInject.putAll(deltas)
                    }

                    override fun inject(key: SkyKey?, delta: Delta?) {
                        valuesToInject.put(key, delta)
                    }
                }
            for (injected in extraPrecomputedValues) {
                injected.inject(injectable)
            }
            PrecomputedValue.Companion.PATH_PACKAGE_LOCATOR.set(injectable, pkgLocator)
            PrecomputedValue.Companion.DEFAULT_VISIBILITY.set(injectable, RuleVisibility.PRIVATE)
            PrecomputedValue.Companion.CONFIG_SETTING_VISIBILITY_POLICY.set(
                injectable, ConfigSettingVisibilityPolicy.LEGACY_OFF
            )
            PrecomputedValue.Companion.STARLARK_SEMANTICS.set(injectable, starlarkSemantics)
            PrecomputedValue.Companion.LAZY_MACRO_EXPANSION_PACKAGES.set(injectable, lazyMacroExpansionPackages)
            return ImmutableDiff(com.google.common.collect.ImmutableList.of<SkyKey?>(), valuesToInject)
        }

        private fun exceptionFromErrorInfo(
            error: com.google.devtools.build.skyframe.ErrorInfo, pkgId: PackageIdentifier?
        ): NoSuchPackageException? {
            if (!error.getCycleInfo().isEmpty()) {
                return BuildFileContainsErrorsException(
                    pkgId, "Cycle encountered while loading package " + pkgId
                )
            }
            val e: Throwable =
                com.google.common.base.Preconditions.checkNotNull<java.lang.Exception>(error.getException())
            if (e is NoSuchPackageException) {
                return e
            }
            throw java.lang.IllegalStateException(
                "Unexpected Exception type from PackageValue for '" + pkgId + "'' with error: " + error, e
            )
        }
    }
}
