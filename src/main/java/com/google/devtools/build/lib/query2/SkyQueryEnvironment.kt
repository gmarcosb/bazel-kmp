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
package com.google.devtools.build.lib.query2

import com.google.devtools.build.lib.pkgcache.FilteringPolicies.NO_FILTER

/**
 * [AbstractBlazeQueryEnvironment] that introspects the Skyframe graph to find forward and
 * reverse edges. Results obtained by calling [.evaluateQuery] are not guaranteed to be in any
 * particular order. As well, this class eagerly loads the full transitive closure of targets, even
 * if the full closure isn't needed.
 * 
 * 
 * This class has concurrent implementations of the [QueryTaskFuture]/[ ] helper methods. The combination of this and the asynchronous evaluation model
 * yields parallel query evaluation.
 */
class SkyQueryEnvironment protected constructor(
    keepGoing: Boolean,
    protected val loadingPhaseThreads: Int,
    protected val trackIncrementalState: Boolean,
    queryEvaluationParallelismLevel: Int,
    eventHandler: ExtendedEventHandler?,
    settings: MutableSet<com.google.devtools.build.lib.query2.engine.QueryEnvironment.Setting?>,
    extraFunctions: Iterable<QueryFunction?>?,
    mainRepoTargetParser: TargetPattern.Parser,
    parserPrefix: PathFragment?,
    graphFactory: WalkableGraphFactory,
    universeScope: UniverseScope,
    pkgPath: PathPackageLocator,
    labelPrinter: LabelPrinter?
) : AbstractBlazeQueryEnvironment<Target?>(
    keepGoing,  /* strictScope= */
    true,  /* labelFilter= */
    Rule.ALL_LABELS,
    eventHandler,
    settings,
    extraFunctions,
    labelPrinter
), StreamableQueryEnvironment<Target?> {
    @get:com.google.devtools.build.lib.concurrent.ThreadSafety.ThreadSafe
    val accessor: BlazeTargetAccessor = BlazeTargetAccessor(this)
    protected val graphFactory: WalkableGraphFactory
    protected val universeScope: UniverseScope
    protected val mainRepoTargetParser: TargetPattern.Parser
    protected val parserPrefix: PathFragment?
    protected val pkgPath: PathPackageLocator
    protected val queryEvaluationParallelismLevel: Int
    private val visibilityDepsAreAllowed: Boolean
    private val toolchainTypeDepsAreAllowed: Boolean

    // The following fields are set in the #beforeEvaluateQuery method.
    protected var packageSemaphore: MultisetSemaphore<PackageIdentifier?>? = null
    var graph: WalkableGraph? = null
    protected var graphBackedRecursivePackageProvider: GraphBackedRecursivePackageProvider? = null
    protected var executor: com.google.common.util.concurrent.ListeningExecutorService? = null
    private var resolver: TargetPatternResolver<Target?>? = null

    constructor(
        keepGoing: Boolean,
        loadingPhaseThreads: Int,
        trackIncrementalState: Boolean,
        eventHandler: ExtendedEventHandler?,
        settings: MutableSet<com.google.devtools.build.lib.query2.engine.QueryEnvironment.Setting?>,
        extraFunctions: Iterable<QueryFunction?>?,
        mainRepoTargetParser: TargetPattern.Parser,
        parserPrefix: PathFragment?,
        graphFactory: WalkableGraphFactory,
        universeScope: UniverseScope,
        pkgPath: PathPackageLocator,
        labelPrinter: LabelPrinter?
    ) : this(
        keepGoing,
        loadingPhaseThreads,
        trackIncrementalState,  // SkyQueryEnvironment operates on a prepopulated Skyframe graph. Therefore, query
        // evaluation is completely CPU-bound.
        /* queryEvaluationParallelismLevel= */
        DEFAULT_THREAD_COUNT,
        eventHandler,
        settings,
        extraFunctions,
        mainRepoTargetParser,
        parserPrefix,
        graphFactory,
        universeScope,
        pkgPath,
        labelPrinter
    )

    override fun close() {
        if (executor != null) {
            executor.shutdownNow()
            executor = null
        }
    }

    /** Gets roots of graph which contains all nodes needed to evaluate `expr`.  */
    @Throws(com.google.devtools.build.lib.query2.engine.QueryException::class, java.lang.InterruptedException::class)
    protected fun getGraphRootsFromUniverseKeyAndExpression(
        universeKey: SkyKey, expr: QueryExpression?
    ): MutableSet<SkyKey?> {
        return com.google.common.collect.ImmutableSet.of<SkyKey?>(universeKey)
    }

    protected fun newEvaluationContext(): com.google.devtools.build.skyframe.EvaluationContext? {
        return com.google.devtools.build.skyframe.EvaluationContext.newBuilder()
            .setParallelism(loadingPhaseThreads)
            .setEventHandler(eventHandler)
            .build()
    }

    @Throws(com.google.devtools.build.lib.query2.engine.QueryException::class, java.lang.InterruptedException::class)
    protected fun beforeEvaluateQuery(
        expr: QueryExpression, callback: ThreadSafeOutputFormatterCallback<Target?>?
    ) {
        if (!trackIncrementalState) {
            val visitor: RequiresEdgesQueryExpressionVisitor = RequiresEdgesQueryExpressionVisitor()
            if (expr.accept<Boolean?>(visitor)) {
                throw com.google.devtools.build.lib.query2.engine.QueryException(
                    expr,
                    "Queries requiring edge traversal are not supported with --notrack_incremental_state",
                    Code.ILLEGAL_FLAG_COMBINATION
                )
            }
        }
        val universeKey: UniverseSkyKey = universeScope.getUniverseKey(expr, parserPrefix)
        val universeScopeListToUse: com.google.common.collect.ImmutableList<String?>? = universeKey.getPatterns()
        logger.atInfo().log("Using a --universe_scope value of %s", universeScopeListToUse)
        val roots: MutableSet<SkyKey?> = getGraphRootsFromUniverseKeyAndExpression(universeKey, expr)

        val result: EvaluationResult<SkyValue?>
        GoogleAutoProfilerUtils.logged("evaluation and walkable graph").use { p ->
            result = graphFactory.prepareAndGet(roots, newEvaluationContext())
        }
        if (graph == null || graph !== result.getWalkableGraph()) {
            checkEvaluationResult(universeScopeListToUse, roots, universeKey, result, expr)
            packageSemaphore = makeFreshPackageMultisetSemaphore()
            graph = com.google.common.base.Preconditions.checkNotNull<WalkableGraph?>(result.getWalkableGraph(), result)
            graphBackedRecursivePackageProvider =
                GraphBackedRecursivePackageProvider(
                    graph,
                    UniverseTargetPattern.of(getTargetPatternsForUniverseKey(universeKey)),
                    pkgPath,
                    TraversalInfoRootPackageExtractor()
                )
        }

        if (executor == null) {
            executor =
                com.google.common.util.concurrent.MoreExecutors.listeningDecorator(
                    ThreadPoolExecutor( /* corePoolSize= */
                        queryEvaluationParallelismLevel,  /* maximumPoolSize= */
                        queryEvaluationParallelismLevel,  /* keepAliveTime= */
                        1,  /* unit= */
                        TimeUnit.SECONDS,  /* workQueue= */
                        BlockingStack<java.lang.Runnable?>(),
                        com.google.common.util.concurrent.ThreadFactoryBuilder().setNameFormat("QueryEnvironment %d")
                            .build()
                    )
                )
        }
        resolver = makeNewTargetPatternResolver(expr, callback)
    }

    protected fun makeNewTargetPatternResolver(
        expr: QueryExpression?, callback: ThreadSafeOutputFormatterCallback<Target?>?
    ): TargetPatternResolver<Target?>? {
        return RecursivePackageProviderBackedTargetPatternResolver(
            graphBackedRecursivePackageProvider,
            eventHandler,
            FilteringPolicies.NO_FILTER,
            packageSemaphore,  /* maxConcurrentGetTargetsTasks= */
            java.util.Optional.empty<Int?>(),
            com.google.devtools.build.lib.skyframe.PackageIdentifierBatchingCallback.Factory { batchResults: SafeBatchCallback<PackageIdentifier?>?, batchSize: Int ->
                SimplePackageIdentifierBatchingCallback(
                    batchResults,
                    batchSize
                )
            })
    }

    /** Returns the TargetPatterns corresponding to `universeKey`.  */
    protected fun getTargetPatternsForUniverseKey(universeKey: SkyKey): com.google.common.collect.ImmutableList<TargetPattern?> {
        return com.google.common.collect.ImmutableList.copyOf<TargetPattern?>(
            com.google.common.collect.Iterables.transform<TargetPatternKey?, TargetPattern?>(
                PrepareDepsOfPatternsFunction.getTargetPatternKeys(
                    PrepareDepsOfPatternsFunction.getSkyKeys(
                        universeKey, eventHandler, mainRepoTargetParser.getRepoMapping()
                    )
                ),
                com.google.common.base.Function { obj: TargetPatternKey? -> obj.getParsedPattern() })
        )
    }

    protected fun makeFreshPackageMultisetSemaphore(): MultisetSemaphore<PackageIdentifier?> {
        return MultisetSemaphore.unbounded<PackageIdentifier?>()
    }

    @get:com.google.devtools.build.lib.concurrent.ThreadSafety.ThreadSafe
    val packageMultisetSemaphore: MultisetSemaphore<PackageIdentifier?>
        get() = packageSemaphore

    /** Returns true if this environment has a dependency filter on any edges.  */
    fun hasDependencyFilter(): Boolean {
        return dependencyFilter !== DependencyFilter.ALL_DEPS
    }

    override fun transformParsedQuery(queryExpression: QueryExpression): QueryExpression {
        val mapper: QueryExpressionMapper<java.lang.Void?>? = this.queryExpressionMapper
        val transformedQueryExpression: QueryExpression
        GoogleAutoProfilerUtils.logged("transforming query", MIN_LOGGING).use { p ->
            transformedQueryExpression = queryExpression.accept<QueryExpression>(mapper)
        }
        val queryWeightEstimate: Long = queryExpression.accept<Long?>(TotalWeightQueryExpressionVisitor())
        if (queryWeightEstimate <= MAX_QUERY_WEIGHT_TO_LOG) {
            logger.atInfo().log(
                "transformed query [%s] to [%s]",
                queryExpression.toTrunctatedString(), transformedQueryExpression.toTrunctatedString()
            )
        } else {
            logger.atInfo().log(
                "not logging transformed query with estimated size: %d", queryWeightEstimate
            )
        }
        return transformedQueryExpression
    }

    protected val queryExpressionMapper: QueryExpressionMapper<java.lang.Void?>?
        get() {
            val constantUniverseScopeListMaybe: java.util.Optional<com.google.common.collect.ImmutableList<String?>> =
                universeScope.getConstantValueMaybe()
            if (constantUniverseScopeListMaybe.isEmpty()) {
                return QueryExpressionMapper.Companion.identity()
            }
            val constantUniverseScopeList: com.google.common.collect.ImmutableList<String?> =
                constantUniverseScopeListMaybe.get()
            if (constantUniverseScopeList.size != 1) {
                return QueryExpressionMapper.Companion.identity()
            }
            val universeScopePatternString: String? =
                com.google.common.collect.Iterables.getOnlyElement<String?>(constantUniverseScopeList)
            val absoluteUniverseScopePattern: TargetPattern?
            try {
                absoluteUniverseScopePattern =
                    mainRepoTargetParser.parse(mainRepoTargetParser.absolutize(universeScopePatternString))
            } catch (e: TargetParsingException) {
                return QueryExpressionMapper.Companion.identity()
            }
            return QueryExpressionMapper.Companion.compose<java.lang.Void?>(
                com.google.common.collect.ImmutableList.of<QueryExpressionMapper<java.lang.Void?>?>(
                    RdepsToAllRdepsQueryExpressionMapper(
                        mainRepoTargetParser, absoluteUniverseScopePattern
                    ),
                    FilteredDirectRdepsInUniverseExpressionMapper(
                        mainRepoTargetParser, absoluteUniverseScopePattern
                    )
                )
            )
        }

    @Throws(com.google.devtools.build.lib.query2.engine.QueryException::class, java.lang.InterruptedException::class)
    override fun evalTopLevelInternal(
        expr: QueryExpression?, callback: OutputFormatterCallback<Target?>?
    ) {
        try {
            super.evalTopLevelInternal(expr, callback)
        } catch (throwable: com.google.devtools.build.lib.query2.engine.QueryException) {
            logger.atInfo().withCause(throwable).log(
                "About to shutdown query threadpool because of throwable"
            )
            val obsoleteExecutor: com.google.common.util.concurrent.ListeningExecutorService? = executor
            // Signal that executor must be recreated on the next invocation.
            executor = null

            // If evaluation failed abruptly (e.g. was interrupted), attempt to terminate all remaining
            // tasks and then wait for them all to finish. We don't want to leave any dangling threads
            // running tasks.
            obsoleteExecutor.shutdownNow()
            var interrupted = false
            var executorTerminated = false
            try {
                while (!executorTerminated) {
                    try {
                        executorTerminated =
                            obsoleteExecutor.awaitTermination(Long.Companion.MAX_VALUE, TimeUnit.MILLISECONDS)
                    } catch (e: java.lang.InterruptedException) {
                        interrupted = true
                        handleInterruptedShutdown()
                    }
                }
            } finally {
                if (interrupted) {
                    java.lang.Thread.currentThread().interrupt()
                }
            }

            throw throwable
        } catch (throwable: java.lang.InterruptedException) {
            logger.atInfo().withCause(throwable).log(
                "About to shutdown query threadpool because of throwable"
            )
            val obsoleteExecutor: com.google.common.util.concurrent.ListeningExecutorService? = executor
            executor = null

            obsoleteExecutor.shutdownNow()
            var interrupted = false
            var executorTerminated = false
            try {
                while (!executorTerminated) {
                    try {
                        executorTerminated =
                            obsoleteExecutor.awaitTermination(Long.Companion.MAX_VALUE, TimeUnit.MILLISECONDS)
                    } catch (e: java.lang.InterruptedException) {
                        interrupted = true
                        handleInterruptedShutdown()
                    }
                }
            } finally {
                if (interrupted) {
                    java.lang.Thread.currentThread().interrupt()
                }
            }

            throw throwable
        } catch (throwable: java.lang.RuntimeException) {
            logger.atInfo().withCause(throwable).log(
                "About to shutdown query threadpool because of throwable"
            )
            val obsoleteExecutor: com.google.common.util.concurrent.ListeningExecutorService? = executor
            executor = null

            obsoleteExecutor.shutdownNow()
            var interrupted = false
            var executorTerminated = false
            try {
                while (!executorTerminated) {
                    try {
                        executorTerminated =
                            obsoleteExecutor.awaitTermination(Long.Companion.MAX_VALUE, TimeUnit.MILLISECONDS)
                    } catch (e: java.lang.InterruptedException) {
                        interrupted = true
                        handleInterruptedShutdown()
                    }
                }
            } finally {
                if (interrupted) {
                    java.lang.Thread.currentThread().interrupt()
                }
            }

            throw throwable
        } catch (throwable: java.lang.Error) {
            logger.atInfo().withCause(throwable).log(
                "About to shutdown query threadpool because of throwable"
            )
            val obsoleteExecutor: com.google.common.util.concurrent.ListeningExecutorService? = executor
            executor = null

            obsoleteExecutor.shutdownNow()
            var interrupted = false
            var executorTerminated = false
            try {
                while (!executorTerminated) {
                    try {
                        executorTerminated =
                            obsoleteExecutor.awaitTermination(Long.Companion.MAX_VALUE, TimeUnit.MILLISECONDS)
                    } catch (e: java.lang.InterruptedException) {
                        interrupted = true
                        handleInterruptedShutdown()
                    }
                }
            } finally {
                if (interrupted) {
                    java.lang.Thread.currentThread().interrupt()
                }
            }

            throw throwable
        }
    }

    /**
     * Subclasses may implement special handling when the query threadpool shutdown process is
     * interrupted. This isn't likely to happen unless there's a bug in the lifecycle management of
     * query tasks.
     */
    protected fun handleInterruptedShutdown() {}

    @Throws(
        com.google.devtools.build.lib.query2.engine.QueryException::class,
        java.lang.InterruptedException::class,
        IOException::class
    )
    override fun evaluateQuery(
        expr: QueryExpression, callback: ThreadSafeOutputFormatterCallback<Target?>
    ): QueryEvalResult? {
        beforeEvaluateQuery(expr, callback)

        // SkyQueryEnvironment batches callback invocations using a BatchStreamedCallback, created here
        // so that there's one per top-level evaluateQuery call. The batch size is large enough that
        // per-call costs of calling the original callback are amortized over a good number of targets,
        // and small enough that holding a batch of targets in memory doesn't risk an OOM error.
        //
        // This flushes the batched callback prior to constructing the QueryEvalResult in the unlikely
        // case of a race between the original callback and the eventHandler.
        val batchCallback =
            BatchStreamedCallback(
                callback, BATCH_CALLBACK_SIZE, createUniquifierForOuterBatchStreamedCallback(expr)
            )
        return evaluateQueryInternal(expr, batchCallback)
    }

    @Throws(java.lang.InterruptedException::class)
    private fun targetifyValues(
        input: MutableMap<SkyKey?, out Iterable<SkyKey?>?>,
        missingTargetCollector: com.google.common.collect.ImmutableSet.Builder<SkyKey?>
    ): MutableMap<SkyKey?, MutableCollection<Target?>> {
        return targetifyValues(
            input,
            makePackageKeyToTargetKeyMap(
                com.google.common.collect.ImmutableSet.copyOf<SkyKey?>(
                    com.google.common.collect.Iterables.concat<SkyKey?>(
                        input.values
                    )
                )
            ),
            missingTargetCollector
        )
    }

    @Throws(java.lang.InterruptedException::class)
    private fun targetifyValues(
        input: MutableMap<SkyKey?, out Iterable<SkyKey?>?>,
        packageKeyToTargetKeyMap: com.google.common.collect.Multimap<SkyKey?, SkyKey?>,
        missingTargetCollector: com.google.common.collect.ImmutableSet.Builder<SkyKey?>
    ): MutableMap<SkyKey?, MutableCollection<Target?>> {
        val result: com.google.common.collect.ImmutableMap.Builder<SkyKey?, MutableCollection<Target?>?> =
            com.google.common.collect.ImmutableMap.builder<SkyKey?, MutableCollection<Target?>?>()

        val allTargets: MutableMap<SkyKey?, Target?> =
            getTargetKeyToTargetMapForPackageKeyToTargetKeyMap(packageKeyToTargetKeyMap)

        for (entry in input.entries) {
            val skyKeys: Iterable<SkyKey?> = entry.value
            val targets: MutableSet<Target?> =
                com.google.devtools.build.lib.collect.compacthashset.CompactHashSet.createWithExpectedSize<Target?>(
                    com.google.common.collect.Iterables.size(skyKeys)
                )
            for (key in skyKeys) {
                val target = allTargets.get(key)
                if (target != null) {
                    targets.add(target)
                } else {
                    missingTargetCollector.add(key)
                }
            }
            result.put(entry.key, targets)
        }
        return result.buildOrThrow()
    }

    @Throws(java.lang.InterruptedException::class)
    private fun getRawReverseDeps(
        transitiveTraversalKeys: Iterable<SkyKey?>?,
        extraGlobalDeps: com.google.common.collect.ImmutableSetMultimap<SkyKey?, SkyKey?>
    ): MutableMap<SkyKey?, MutableCollection<Target?>> {
        return targetifyValues(
            getReverseDepLabelsOfLabels(transitiveTraversalKeys, extraGlobalDeps),
            com.google.common.collect.ImmutableSet.builder<SkyKey?>()
        )
    }

    @Throws(java.lang.InterruptedException::class)
    fun getReverseDepLabelsOfLabels(
        labels: Iterable<out SkyKey?>?,
        extraGlobalDeps: com.google.common.collect.ImmutableSetMultimap<SkyKey?, SkyKey?>
    ): MutableMap<SkyKey?, Iterable<SkyKey?>?> {
        val globalLabelToRdeps: com.google.common.collect.ImmutableSetMultimap<SkyKey?, SkyKey?> =
            extraGlobalDeps.inverse()
        val reverseDeps: MutableMap<SkyKey?, Iterable<SkyKey?>?> = graph.getReverseDeps(labels)
        val resultsBuilder: com.google.common.collect.ImmutableMap.Builder<SkyKey?, Iterable<SkyKey?>?> =
            com.google.common.collect.ImmutableMap.builder<SkyKey?, Iterable<SkyKey?>?>()

        for (entry in reverseDeps.entries) {
            val rdepsFromGlobals: com.google.common.collect.ImmutableSet<SkyKey?> = globalLabelToRdeps.get(entry.key)
            resultsBuilder.put(
                entry.key,
                com.google.common.collect.Iterables.concat<SkyKey?>(entry.value, rdepsFromGlobals)
            )
        }
        return resultsBuilder.buildOrThrow()
    }

    private fun getAllowedDeps(rule: Rule): MutableSet<Label?> {
        val allowedLabels: MutableSet<Label?> = HashSet<Any?>(rule.getTransitions(dependencyFilter).values())
        if (visibilityDepsAreAllowed) {
            // Rule#getTransitions only visits the labels of attribute values, so that means it doesn't
            // know about deps from the labels of the rule's package's default_visibility. Therefore, we
            // need to explicitly handle that here.
            com.google.common.collect.Iterables.addAll<T?>(allowedLabels, rule.getVisibilityDependencyLabels())
        }
        if (toolchainTypeDepsAreAllowed) {
            for (toolchainTypeRequirement in rule.getRuleClassObject().getToolchainTypes()) {
                allowedLabels.add(toolchainTypeRequirement.toolchainType())
            }
        }
        // We should add deps from aspects, otherwise they are going to be filtered out.
        allowedLabels.addAll(rule.getAspectLabelsSuperset(dependencyFilter))
        return allowedLabels
    }

    private fun filterFwdDeps(target: Target?, rawFwdDeps: MutableCollection<Target?>): MutableCollection<Target?>? {
        if (target !is Rule) {
            return rawFwdDeps
        }
        val allowedLabels: MutableSet<Label?> = getAllowedDeps(target as Rule?)
        return com.google.common.collect.Collections2.filter<Target?>(
            rawFwdDeps,
            com.google.common.base.Predicate { t: Target? -> allowedLabels.contains(t.getLabel()) })
    }

    @Throws(java.lang.InterruptedException::class)
    override fun getFwdDeps(
        targets: Iterable<Target?>, context: QueryExpressionContext<Target?>
    ): ThreadSafeMutableSet<Target?> {
        val missingTargetsBuilder: com.google.common.collect.ImmutableSet.Builder<SkyKey?> =
            com.google.common.collect.ImmutableSet.builder<SkyKey?>()
        val result: ThreadSafeMutableSet<Target?> = getFwdDeps(targets, context, missingTargetsBuilder)

        val missingTargets: com.google.common.collect.ImmutableSet<SkyKey?> = missingTargetsBuilder.build()
        if (!missingTargets.isEmpty()) {
            eventHandler.handle(com.google.devtools.build.lib.events.Event.warn("Targets were missing from graph: " + missingTargets))
        }
        return result
    }

    @Throws(java.lang.InterruptedException::class)
    protected fun getFwdDeps(
        targets: Iterable<Target?>,
        context: QueryExpressionContext<Target?>,
        missingTargetCollector: com.google.common.collect.ImmutableSet.Builder<SkyKey?>
    ): ThreadSafeMutableSet<Target?> {
        val targetsByKey: MutableMap<SkyKey?, Target?> =
            com.google.common.collect.Maps.newHashMapWithExpectedSize<SkyKey?, Target?>(
                com.google.common.collect.Iterables.size(targets)
            )
        for (target in targets) {
            targetsByKey.put(TARGET_TO_SKY_KEY.apply(target), target)
        }
        val fwdDepLabels: com.google.common.collect.ImmutableMap<SkyKey?, Iterable<SkyKey?>?> =
            getFwdDepLabels(targetsByKey.keys, context.extraGlobalDeps())
        val directDeps: MutableMap<SkyKey?, MutableCollection<Target?>> =
            targetifyValues(fwdDepLabels, missingTargetCollector)
        val result: ThreadSafeMutableSet<Target?> = createThreadSafeMutableSet()
        for (entry in directDeps.entries) {
            result.addAll(filterFwdDeps(targetsByKey.get(entry.key), entry.value))
        }
        return result
    }

    @Throws(java.lang.InterruptedException::class)
    fun getFwdDepLabels(
        targetLabels: Iterable<SkyKey?>,
        extraGlobalDeps: com.google.common.collect.ImmutableSetMultimap<SkyKey?, SkyKey?>
    ): com.google.common.collect.ImmutableMap<SkyKey?, Iterable<SkyKey?>?> {
        com.google.common.base.Preconditions.checkState(
            com.google.common.collect.Iterables.all<SkyKey?>(targetLabels, IS_LABEL),
            "Expected all labels: %s",
            targetLabels
        )
        val deps: MutableMap<SkyKey?, Iterable<SkyKey?>?> = graph.getDirectDeps(targetLabels)
        val resultsBuilder: com.google.common.collect.ImmutableMap.Builder<SkyKey?, Iterable<SkyKey?>?> =
            com.google.common.collect.ImmutableMap.builder<SkyKey?, Iterable<SkyKey?>?>()
        for (entry in deps.entries) {
            var depsLabels: Iterable<SkyKey?> =
                com.google.common.collect.Iterables.filter<SkyKey?>(entry.value, IS_LABEL)
            val globals: com.google.common.collect.ImmutableSet<SkyKey?> = extraGlobalDeps.get(entry.key)
            depsLabels = com.google.common.collect.Iterables.concat<SkyKey?>(depsLabels, globals)
            resultsBuilder.put(entry.key, depsLabels)
        }
        return resultsBuilder.buildOrThrow()
    }

    override fun getDepsBounded(
        queryExpression: QueryExpression,
        context: QueryExpressionContext<Target?>,
        callback: com.google.devtools.build.lib.query2.engine.Callback<Target?>,
        depthBound: Int,
        caller: QueryExpression?
    ): QueryTaskFuture<java.lang.Void?> {
        // Re-implement the bounded deps algorithm to allow for proper error reporting of missing
        // targets that cannot be targetified.
        val minDepthUniquifier: MinDepthUniquifier<Target?> = createMinDepthUniquifier()
        return eval(
            queryExpression,
            context,
            com.google.devtools.build.lib.query2.engine.Callback { partialResult: Iterable<Target?>? ->
                var current: ThreadSafeMutableSet<Target?> = createThreadSafeMutableSet()
                com.google.common.collect.Iterables.addAll<Target?>(current, partialResult)
                for (i in 0..depthBound) {
                    // Filter already visited nodes: if we see a node in a later round, then we don't need
                    // to visit it again, because the depth at which we see it at must be greater than or
                    // equal to the last visit.
                    val toProcess: com.google.common.collect.ImmutableList<Target?> =
                        minDepthUniquifier.uniqueAtDepthLessThanOrEqualTo(current, i)
                    callback.process(toProcess)

                    if (i == depthBound) {
                        // We don't need to fetch dep targets any more.
                        break
                    }

                    val missingTargetBuilder: com.google.common.collect.ImmutableSet.Builder<SkyKey?> =
                        com.google.common.collect.ImmutableSet.builder<SkyKey?>()
                    current = getFwdDeps(toProcess, context, missingTargetBuilder)
                    reportUnsuccessfulOrMissingTargetsInternal(
                        current, missingTargetBuilder.build(), caller
                    )

                    if (current.isEmpty()) {
                        // Exit when there are no more nodes to visit.
                        break
                    }
                }
            })
    }

    override fun somePath(
        fromExpression: QueryExpression?,
        toExpression: QueryExpression?,
        context: QueryExpressionContext<Target?>,
        callback: com.google.devtools.build.lib.query2.engine.Callback<Target?>,
        caller: QueryExpression?
    ): QueryTaskFuture<java.lang.Void?> {
        // Note that the body of this method is entirely copied from SomePathFunction, which allows
        // subclasses of SkyQueryEnvironment to override it.
        // TODO: b/382066616 - Refactor this and avoid the duplication.
        val fromValueFuture: QueryTaskFuture<ThreadSafeMutableSet<Target?>>? =
            QueryUtil.evalAll<Target?>(this, context, fromExpression)
        val toValueFuture: QueryTaskFuture<ThreadSafeMutableSet<Target?>>? =
            QueryUtil.evalAll<Target?>(this, context, toExpression)

        return whenAllSucceedCall<java.lang.Void?>(
            com.google.common.collect.ImmutableList.of<QueryTaskFuture<ThreadSafeMutableSet<Target?>?>?>(
                fromValueFuture,
                toValueFuture
            ),
            object : QueryTaskCallable<java.lang.Void?> {
                @Throws(
                    com.google.devtools.build.lib.query2.engine.QueryException::class,
                    java.lang.InterruptedException::class
                )
                override fun call(): java.lang.Void? {
                    // Implementation strategy: for each x in "from", compute its forward
                    // transitive closure.  If it intersects "to", then do a path search from x
                    // to an arbitrary node in the intersection, and return the path.  This
                    // avoids computing the full transitive closure of "from" in some cases.

                    val fromValue: ThreadSafeMutableSet<Target?> = fromValueFuture.getIfSuccessful()
                    val toValue: ThreadSafeMutableSet<Target?> = toValueFuture.getIfSuccessful()

                    buildTransitiveClosure(caller, fromValue, OptionalInt.empty())

                    for (x in fromValue) {
                        // TODO(b/122548314): if x was already seen as part of a previous node's tc, we should
                        // skip it here. That's subsumed by the TODO below.
                        val xSet: ThreadSafeMutableSet<Target?> = createThreadSafeMutableSet()
                        xSet.add(x)
                        // TODO(b/122548314): this transitive closure building should stop at any nodes that
                        // have already been visited.
                        val xtc: ThreadSafeMutableSet<Target?> = getTransitiveClosure(xSet, context)
                        val result: com.google.common.collect.Sets.SetView<Target?>?
                        if (xtc.size > toValue.size) {
                            result = com.google.common.collect.Sets.intersection<Target?>(toValue, xtc)
                        } else {
                            result = com.google.common.collect.Sets.intersection<Target?>(xtc, toValue)
                        }
                        if (!result.isEmpty()) {
                            callback.process(getNodesOnPath(x, result.iterator().next(), context))
                            return null
                        }
                    }
                    callback.process(com.google.common.collect.ImmutableSet.of<Target?>())
                    return null
                }
            })
    }

    override fun allPaths(
        fromExpression: QueryExpression?,
        toExpression: QueryExpression?,
        context: QueryExpressionContext<Target?>,
        callback: com.google.devtools.build.lib.query2.engine.Callback<Target?>,
        caller: QueryExpression?
    ): QueryTaskFuture<java.lang.Void?> {
        // Note that the body of this method is entirely copied from AllPathsFunction, which allows
        // subclasses of SkyQueryEnvironment to override it.
        // TODO: b/382066616 - Refactor this and avoid the duplication.
        val fromValueFuture: QueryTaskFuture<ThreadSafeMutableSet<Target?>>? =
            QueryUtil.evalAll<Target?>(this, context, fromExpression)
        val toValueFuture: QueryTaskFuture<ThreadSafeMutableSet<Target?>>? =
            QueryUtil.evalAll<Target?>(this, context, toExpression)

        return whenAllSucceedCall<java.lang.Void?>(
            com.google.common.collect.ImmutableList.of<QueryTaskFuture<ThreadSafeMutableSet<Target?>?>?>(
                fromValueFuture,
                toValueFuture
            ),
            QueryTaskCallable {
                // Algorithm: compute "reachableFromX", the forward transitive closure of the "from" set,
                // then find the intersection of "reachableFromX" with the reverse transitive closure of
                // the "to" set.  The reverse transitive closure and intersection operations are
                // interleaved for efficiency. "result" holds the intersection.
                val fromValue: ThreadSafeMutableSet<Target?> = fromValueFuture.getIfSuccessful()
                val toValue: ThreadSafeMutableSet<Target?> = toValueFuture.getIfSuccessful()

                buildTransitiveClosure(caller, fromValue, OptionalInt.empty())

                val reachableFromX: MutableSet<Target?> = getTransitiveClosure(fromValue, context)
                val reachable: com.google.common.base.Predicate<Target?> =
                    com.google.common.base.Predicates.`in`<Target?>(reachableFromX)
                val uniquifier: Uniquifier<Target?> = createUniquifier()
                val result: com.google.common.collect.ImmutableList<Target?> =
                    uniquifier.unique(intersection<Target?>(reachableFromX, toValue))
                callback.process(result)
                var worklist: com.google.common.collect.ImmutableList<Target?> = result
                while (!worklist.isEmpty()) {
                    val reverseDeps: Iterable<Target?> = getReverseDeps(worklist, context)
                    worklist =
                        uniquifier.unique(com.google.common.collect.Iterables.filter<Target?>(reverseDeps, reachable))
                    callback.process(worklist)
                }
                null
            })
    }

    @Throws(java.lang.InterruptedException::class)
    override fun getReverseDeps(
        targets: Iterable<Target?>, context: QueryExpressionContext<Target?>
    ): MutableCollection<Target?> {
        return processRawReverseDeps(
            getReverseDepsOfLabels(
                com.google.common.collect.Iterables.transform<Target?, Label?>(
                    targets,
                    Target::getLabel
                ), context
            )
        )
    }

    @Throws(java.lang.InterruptedException::class)
    protected fun getReverseDepsOfLabels(
        targetLabels: Iterable<Label?>, context: QueryExpressionContext<Target?>
    ): MutableMap<SkyKey?, MutableCollection<Target?>> {
        return getRawReverseDeps(
            com.google.common.collect.Iterables.transform<Label?, SkyKey?>(
                targetLabels,
                com.google.common.base.Function { label: Label? -> label }), context.extraGlobalDeps()
        )
    }

    /** Targetify SkyKeys of reverse deps and filter out targets whose deps are not allowed.  */
    @Throws(java.lang.InterruptedException::class)
    fun filterRawReverseDepsOfTransitiveTraversalKeys(
        rawReverseDeps: MutableMap<SkyKey?, out Iterable<SkyKey?>?>,
        packageKeyToTargetKeyMap: com.google.common.collect.Multimap<SkyKey?, SkyKey?>
    ): MutableCollection<Target?> {
        return processRawReverseDeps(
            targetifyValues(
                rawReverseDeps,
                packageKeyToTargetKeyMap,
                com.google.common.collect.ImmutableSet.builder<SkyKey?>()
            )
        )
    }

    private fun processRawReverseDeps(rawReverseDeps: MutableMap<SkyKey?, MutableCollection<Target?>>): MutableSet<Target?> {
        val result: MutableSet<Target?> =
            com.google.devtools.build.lib.collect.compacthashset.CompactHashSet.create<Target?>()
        val visited: com.google.devtools.build.lib.collect.compacthashset.CompactHashSet<Target?> =
            com.google.devtools.build.lib.collect.compacthashset.CompactHashSet.createWithExpectedSize<Target?>(
                totalSizeOfCollections<Target?>(rawReverseDeps.values)
            )

        val keys: MutableSet<Label?> =
            com.google.devtools.build.lib.collect.compacthashset.CompactHashSet.create<Label?>(
                com.google.common.collect.Collections2.transform<SkyKey?, Label?>(
                    rawReverseDeps.keys,
                    SKYKEY_TO_LABEL
                )
            )
        for (parentCollection in rawReverseDeps.values) {
            for (parent in parentCollection) {
                if (visited.add(parent)) {
                    if (parent is Rule && dependencyFilter !== DependencyFilter.ALL_DEPS) {
                        for (label in getAllowedDeps(parent as Rule?)) {
                            if (keys.contains(label)) {
                                result.add(parent)
                            }
                        }
                    } else {
                        result.add(parent)
                    }
                }
            }
        }
        return result
    }

    @Throws(java.lang.InterruptedException::class)
    override fun getTransitiveClosure(
        targets: ThreadSafeMutableSet<Target?>?, context: QueryExpressionContext<Target?>
    ): ThreadSafeMutableSet<Target?> {
        return SkyQueryUtils.getTransitiveClosure<Target?>(
            targets,
            GetFwdDeps { targets1: Iterable<Target?>? -> getFwdDeps(targets1!!, context) },
            createThreadSafeMutableSet()
        )
    }

    @Throws(java.lang.InterruptedException::class)
    override fun getNodesOnPath(
        from: Target?, to: Target?, context: QueryExpressionContext<Target?>
    ): com.google.common.collect.ImmutableList<Target?>? {
        return SkyQueryUtils.getNodesOnPath<Target?, Any?>(
            from, to, GetFwdDeps { targets: Iterable<Target?>? -> getFwdDeps(targets!!, context) }, Target::getLabel
        )
    }

    protected fun <R> safeSubmit(callable: java.util.concurrent.Callable<R?>): com.google.common.util.concurrent.ListenableFuture<R?> {
        try {
            return executor.submit<R?>(callable)
        } catch (e: RejectedExecutionException) {
            return com.google.common.util.concurrent.Futures.immediateCancelledFuture<R?>()
        }
    }

    private fun <R> safeSubmitAsync(callable: QueryTaskAsyncCallable<R?>): com.google.common.util.concurrent.ListenableFuture<R?> {
        try {
            return com.google.common.util.concurrent.Futures.submitAsync<R?>(
                com.google.common.util.concurrent.AsyncCallable { callable.call() as com.google.common.util.concurrent.ListenableFuture<R?>? },
                executor
            )
        } catch (e: RejectedExecutionException) {
            return com.google.common.util.concurrent.Futures.immediateCancelledFuture<R?>()
        }
    }

    @com.google.devtools.build.lib.concurrent.ThreadSafety.ThreadSafe
    override fun eval(
        expr: QueryExpression,
        context: QueryExpressionContext<Target?>?,
        callback: com.google.devtools.build.lib.query2.engine.Callback<Target?>?
    ): QueryTaskFuture<java.lang.Void?> {
        // TODO(bazel-team): As in here, use concurrency for the async #eval of other QueryEnvironment
        // implementations.
        return executeAsync<java.lang.Void?>(QueryTaskAsyncCallable {
            expr.eval<Target?>(
                this@SkyQueryEnvironment,
                context,
                callback
            )
        })
    }

    /**
     * In [SkyQueryEvaluateExpressionImpl], the constructor will create a [ ][.settableFuture] which returned [QueryTaskFuture] delegates to. The [ ][.settableFuture] will set as the return from `expr.eval(...)` when [ ][.eval] method is called to provide the real callback implementation.
     */
    protected inner class SkyQueryEvaluateExpressionImpl(
        expr: QueryExpression,
        context: QueryExpressionContext<Target?>?
    ) : EvaluateExpression<Target?> {
        protected val settableFuture: com.google.common.util.concurrent.SettableFuture<java.lang.Void?> =
            com.google.common.util.concurrent.SettableFuture.create<java.lang.Void?>()
        protected val expression: QueryExpression
        protected val context: QueryExpressionContext<Target?>?
        private var wasGracefullyCancelled = false

        init {
            this.expression = expr
            this.context = context
        }

        override fun eval(callback: com.google.devtools.build.lib.query2.engine.Callback<Target?>?): QueryTaskFuture<java.lang.Void?> {
            setSettableFuture(callback)
            return QueryTaskFutureImpl.Companion.ofDelegate<java.lang.Void?>(settableFuture)
        }

        protected fun setSettableFuture(callback: com.google.devtools.build.lib.query2.engine.Callback<Target?>?) {
            settableFuture.setFuture(
                this@SkyQueryEnvironment.eval(
                    expression,
                    context,
                    callback
                ) as com.google.common.util.concurrent.ListenableFuture<java.lang.Void?>?
            )
        }

        override fun gracefullyCancel(): Boolean {
            wasGracefullyCancelled = true
            return settableFuture.cancel(true)
        }

        val isUngracefullyCancelled: Boolean
            get() = settableFuture.isCancelled() && !wasGracefullyCancelled
    }

    override fun createEvaluateExpression(
        expr: QueryExpression, context: QueryExpressionContext<Target?>?
    ): EvaluateExpression<Target?> {
        return SkyQueryEvaluateExpressionImpl(expr, context)
    }

    override fun <R> execute(callable: QueryTaskCallable<R?>): QueryTaskFuture<R?> {
        return QueryTaskFutureImpl.Companion.ofDelegate<R?>(safeSubmit<R?>(callable))
    }

    override fun <R> executeAsync(callable: QueryTaskAsyncCallable<R?>): QueryTaskFuture<R?> {
        return QueryTaskFutureImpl.Companion.ofDelegate<R?>(safeSubmitAsync<R?>(callable))
    }

    override fun <T1, T2> transformAsync(
        future: QueryTaskFuture<T1?>?, function: com.google.common.base.Function<T1?, QueryTaskFuture<T2?>?>
    ): QueryTaskFuture<T2?> {
        return QueryTaskFutureImpl.Companion.ofDelegate<T2?>(
            com.google.common.util.concurrent.Futures.transformAsync<T1?, T2?>(
                future as QueryTaskFutureImpl<T1?>?,
                com.google.common.util.concurrent.AsyncFunction { input: T1? -> function.apply(input) as QueryTaskFutureImpl<T2?>? },
                executor
            )
        )
    }

    override fun <R> whenAllSucceedCall(
        futures: Iterable<out QueryTaskFuture<*>?>?, callable: QueryTaskCallable<R?>
    ): QueryTaskFuture<R?> {
        return QueryTaskFutureImpl.Companion.ofDelegate<R?>(
            com.google.common.util.concurrent.Futures.whenAllSucceed<Any?>(
                AbstractBlazeQueryEnvironment.Companion.cast(
                    futures
                )
            ).call<R?>(callable, executor)
        )
    }

    override fun <R> whenSucceedsOrIsCancelledCall(
        future: QueryTaskFuture<*>?, callable: QueryTaskCallable<R?>?
    ): QueryTaskFuture<R?> {
        return QueryTaskFutureImpl.Companion.whenSucceedsOrIsCancelledCall<R?>(
            future as QueryTaskFutureImpl<*>?, callable, executor
        )
    }

    @com.google.devtools.build.lib.concurrent.ThreadSafety.ThreadSafe
    override fun createThreadSafeMutableSet(): ThreadSafeMutableSet<Target?> {
        return ThreadSafeMutableKeyExtractorBackedSetImpl<Target?, Label?>(
            TargetKeyExtractor.Companion.INSTANCE, Target::class.java, queryEvaluationParallelismLevel
        )
    }

    @com.google.devtools.build.lib.concurrent.ThreadSafety.ThreadSafe
    protected fun createUniquifierForOuterBatchStreamedCallback(
        expr: QueryExpression?
    ): NonExceptionalUniquifier<Target?> {
        return createUniquifier()
    }

    @com.google.devtools.build.lib.concurrent.ThreadSafety.ThreadSafe
    override fun createUniquifier(): NonExceptionalUniquifier<Target?> {
        return UniquifierImpl<Target?, Label?>(TargetKeyExtractor.Companion.INSTANCE, queryEvaluationParallelismLevel)
    }

    @com.google.devtools.build.lib.concurrent.ThreadSafety.ThreadSafe
    override fun createMinDepthUniquifier(): MinDepthUniquifier<Target?> {
        return MinDepthUniquifierImpl<Target?, Label?>(
            TargetKeyExtractor.Companion.INSTANCE, queryEvaluationParallelismLevel
        )
    }

    @com.google.devtools.build.lib.concurrent.ThreadSafety.ThreadSafe
    fun createMinDepthSkyKeyUniquifier(): MinDepthUniquifier<SkyKey?> {
        return MinDepthUniquifierImpl<SkyKey?, SkyKey?>(
            SkyKeyKeyExtractor.Companion.INSTANCE, queryEvaluationParallelismLevel
        )
    }

    @com.google.devtools.build.lib.concurrent.ThreadSafety.ThreadSafe
    fun createSkyKeyUniquifier(): Uniquifier<SkyKey?> {
        return UniquifierImpl<SkyKey?, SkyKey?>(SkyKeyKeyExtractor.Companion.INSTANCE, queryEvaluationParallelismLevel)
    }

    @com.google.devtools.build.lib.concurrent.ThreadSafety.ThreadSafe
    @Throws(com.google.devtools.build.lib.query2.engine.QueryException::class, java.lang.InterruptedException::class)
    override fun getSiblingTargetsInPackage(target: Target?): MutableCollection<Target?> {
        try {
            return graphBackedRecursivePackageProvider.getSiblingTargetsInPackage(eventHandler, target)
        } catch (e: NoSuchPackageException) {
            throw com.google.devtools.build.lib.query2.engine.QueryException(
                e.getMessage(),
                e,
                e.getDetailedExitCode().getFailureDetail()
            )
        }
    }

    @com.google.devtools.build.lib.concurrent.ThreadSafety.ThreadSafe
    override fun getTargetsMatchingPattern(
        owner: QueryExpression?,
        pattern: String?,
        callback: com.google.devtools.build.lib.query2.engine.Callback<Target?>
    ): QueryTaskFuture<java.lang.Void?>? {
        val targetPatternKey: TargetPatternKey?
        try {
            targetPatternKey =
                TargetPatternValue.key(
                    SignedTargetPattern.parse(pattern, mainRepoTargetParser),
                    FilteringPolicies.NO_FILTER
                )
        } catch (tpe: TargetParsingException) {
            try {
                handleError(owner, tpe.getMessage(), tpe.getDetailedExitCode())
            } catch (qe: com.google.devtools.build.lib.query2.engine.QueryException) {
                return immediateFailedFuture<java.lang.Void?>(qe)
            }
            return immediateSuccessfulFuture<java.lang.Void?>(null)
        }
        return evalTargetPatternKey(owner, targetPatternKey, callback)
    }

    @com.google.devtools.build.lib.concurrent.ThreadSafety.ThreadSafe
    fun evalTargetPatternKey(
        owner: QueryExpression?,
        targetPatternKey: TargetPatternKey,
        callback: com.google.devtools.build.lib.query2.engine.Callback<Target?>
    ): QueryTaskFuture<java.lang.Void?> {
        val patternToEval: TargetPattern = targetPatternKey.getParsedPattern()
        var filteredCallback: com.google.devtools.build.lib.query2.engine.Callback<Target?>? = callback
        if (!targetPatternKey.getPolicy().equals(NO_FILTER)) {
            filteredCallback =
                com.google.devtools.build.lib.query2.engine.Callback { targets: Iterable<Target?>? ->
                    callback.process(
                        com.google.common.collect.Iterables.filter<Target?>(
                            targets,
                            com.google.common.base.Predicate { target: Target? ->
                                targetPatternKey
                                    .getPolicy()
                                    .shouldRetain(target,  /* explicit= */false)
                            })
                    )
                }
        }
        val evalFuture: com.google.common.util.concurrent.ListenableFuture<java.lang.Void?> =
            patternToEval.evalAsync(
                resolver,
                {
                    (graph.getValue(
                        IgnoredSubdirectoriesValue.key(patternToEval.repository)
                    ) as IgnoredSubdirectoriesValue)
                        .asIgnoredSubdirectories()
                },
                targetPatternKey.getExcludedSubdirectories(),
                filteredCallback,
                com.google.devtools.build.lib.query2.engine.QueryException::class.java,
                executor
            )
        return QueryTaskFutureImpl.Companion.ofDelegate<R?>(
            com.google.common.util.concurrent.Futures.catchingAsync<V?, X?>(
                evalFuture,
                TargetParsingException::class.java,
                com.google.common.util.concurrent.AsyncFunction { exn: X? ->
                    handleError(owner, exn.getMessage(), exn.getDetailedExitCode())
                    com.google.common.util.concurrent.Futures.immediateVoidFuture()
                },
                com.google.common.util.concurrent.MoreExecutors.directExecutor()
            )
        )
    }

    val transitiveLoadFilesHelper: TransitiveLoadFilesHelper<Target?>
        get() = object : TransitiveLoadFilesHelperForTargets() {
            @Throws(java.lang.InterruptedException::class)
            override fun getLoadFileTarget(originalTarget: Target?, bzlLabel: Label?): Target? {
                return FakeLoadTarget(bzlLabel, getBuildFileTarget(originalTarget).getPackageoid())
            }

            @Throws(java.lang.InterruptedException::class)
            override fun getBuildFileTarget(originalTarget: Target?): Target? {
                return graphBackedRecursivePackageProvider.getBuildFile(originalTarget)
            }

            @Throws(
                com.google.devtools.build.lib.query2.engine.QueryException::class,
                java.lang.InterruptedException::class
            )
            override fun maybeGetBuildFileTargetForLoadFileTarget(originalTarget: Target?, bzlLabel: Label): Target? {
                val packageIdentifier: PackageIdentifier? = bzlLabel.getPackageIdentifier()
                val packageLookupValue: PackageLookupValue? =
                    graph.getValue(PackageLookupValue.key(packageIdentifier)) as PackageLookupValue?
                if (packageLookupValue == null) {
                    BugReport.sendBugReport(
                        java.lang.IllegalStateException(
                            ("PackageLookupValue for package of extension file "
                                    + bzlLabel
                                    + " not in graph")
                        )
                    )
                    throw com.google.devtools.build.lib.query2.engine.QueryException(
                        bzlLabel.toString() + " does not exist in graph",
                        FailureDetail.newBuilder()
                            .setMessage("BUILD file not found on package path")
                            .setPackageLoading(
                                FailureDetails.PackageLoading.newBuilder()
                                    .setCode(FailureDetails.PackageLoading.Code.BUILD_FILE_MISSING)
                                    .build()
                            )
                            .build()
                    )
                }
                return FakeLoadTarget(
                    Label.createUnvalidated(
                        packageIdentifier,
                        packageLookupValue.getBuildFileName().getFilenameFragment().getBaseName()
                    ),
                    getBuildFileTarget(originalTarget).getPackageoid()
                )
            }
        }

    val visitBatchSizeForParallelVisitation: Int
        get() = ParallelSkyQueryUtils.VISIT_BATCH_SIZE

    val visitTaskStatusCallback: VisitTaskStatusCallback
        get() = VisitTaskStatusCallback.NULL_INSTANCE

    @com.google.devtools.build.lib.concurrent.ThreadSafety.ThreadSafe
    @Throws(
        java.lang.InterruptedException::class,
        com.google.devtools.build.lib.query2.engine.QueryException::class,
        NoSuchPackageException::class
    )
    private fun getPackage(packageIdentifier: PackageIdentifier?): Package {
        val packageValue: PackageValue? = graph.getValue(packageIdentifier) as PackageValue?
        if (packageValue != null) {
            val pkg: Package = packageValue.getPackage()
            if (pkg.containsErrors()) {
                throw BuildFileContainsErrorsException(packageIdentifier)
            }
            return pkg
        } else {
            val exception: NoSuchPackageException? =
                graph.getException(packageIdentifier) as NoSuchPackageException?
            if (exception != null) {
                throw exception
            }
            if (graph.isCycle(packageIdentifier)) {
                throw NoSuchPackageException(packageIdentifier, "Package depends on a cycle")
            } else {
                throw com.google.devtools.build.lib.query2.engine.QueryException(
                    packageIdentifier.toString() + " does not exist in graph",
                    Query.Code.CYCLE
                )
            }
        }
    }

    @com.google.devtools.build.lib.concurrent.ThreadSafety.ThreadSafe
    @Throws(
        TargetNotFoundException::class,
        com.google.devtools.build.lib.query2.engine.QueryException::class,
        java.lang.InterruptedException::class
    )
    override fun getTarget(label: Label): Target {
        try {
            val pkg: Package = getPackage(label.getPackageIdentifier())
            return pkg.getTarget(label.name)
        } catch (e: NoSuchThingException) {
            throw TargetNotFoundException(e, e.getDetailedExitCode())
        }
    }

    @Throws(java.lang.InterruptedException::class)
    override fun getTargets(labels: Iterable<Label?>): MutableMap<Label?, Target?> {
        if (com.google.common.collect.Iterables.isEmpty(labels)) {
            return com.google.common.collect.ImmutableMap.of<Label?, Target?>()
        }
        val packageIdToLabelMap: com.google.common.collect.Multimap<PackageIdentifier?, Label> =
            com.google.common.collect.ArrayListMultimap.create<PackageIdentifier?, Label>()
        labels.forEach(java.util.function.Consumer { label: Label? ->
            packageIdToLabelMap.put(
                label.getPackageIdentifier(),
                label
            )
        })

        packageSemaphore.acquireAll(packageIdToLabelMap.keySet())
        val packageIdToPackageMap: MutableMap<PackageIdentifier?, Package?> =
            bulkGetPackages(packageIdToLabelMap.keySet())
        val resultBuilder: com.google.common.collect.ImmutableMap.Builder<Label?, Target?> =
            com.google.common.collect.ImmutableMap.builder<Label?, Target?>()
        try {
            for (pkgId in packageIdToLabelMap.keySet()) {
                val pkg: Package? = packageIdToPackageMap.get(pkgId)
                if (pkg == null) {
                    continue
                }
                for (label in packageIdToLabelMap.get(pkgId)) {
                    val target: Target?
                    try {
                        target = pkg.getTarget(label.name)
                    } catch (e: NoSuchTargetException) {
                        continue
                    }
                    resultBuilder.put(label, target)
                }
            }
            return resultBuilder.buildOrThrow()
        } finally {
            packageSemaphore.releaseAll(packageIdToLabelMap.keySet())
        }
    }

    @com.google.devtools.build.lib.concurrent.ThreadSafety.ThreadSafe
    @Throws(java.lang.InterruptedException::class)
    fun bulkGetPackages(pkgIds: Iterable<PackageIdentifier?>?): MutableMap<PackageIdentifier?, Package?> {
        val pkgResults: com.google.common.collect.ImmutableMap.Builder<PackageIdentifier?, Package?> =
            com.google.common.collect.ImmutableMap.builder<PackageIdentifier?, Package?>()
        val packages: MutableMap<SkyKey?, SkyValue?> = graph.getSuccessfulValues(pkgIds)
        for (pkgEntry in packages.entries) {
            val pkgId: PackageIdentifier? = pkgEntry.key.argument() as PackageIdentifier?
            val pkgValue: PackageValue = pkgEntry.value as PackageValue
            pkgResults.put(
                pkgId,
                com.google.common.base.Preconditions.checkNotNull<Package?>(pkgValue.getPackage(), pkgId)
            )
        }
        return pkgResults.buildOrThrow()
    }

    @Throws(InconsistentFilesystemException::class, java.lang.InterruptedException::class)
    fun bulkIsPackage(pkgIds: Iterable<PackageIdentifier?>?): com.google.common.collect.ImmutableSet<PackageIdentifier?> {
        return graphBackedRecursivePackageProvider.bulkIsPackage(eventHandler, pkgIds)
    }

    @Throws(com.google.devtools.build.lib.query2.engine.QueryException::class, java.lang.InterruptedException::class)
    override fun buildTransitiveClosure(
        caller: QueryExpression?, targets: ThreadSafeMutableSet<Target?>, maxDepth: OptionalInt?
    ) {
        // Everything has already been loaded, so here we just check for errors so that we can
        // pre-emptively throw/report if needed.
        reportUnsuccessfulOrMissingTargetsInternal(
            targets,
            com.google.common.collect.ImmutableSet.of<SkyKey?>(),
            caller
        )
    }

    override fun preloadOrThrow(caller: QueryExpression?, patterns: MutableCollection<String?>?) {
        // SkyQueryEnvironment directly evaluates target patterns in #getTarget and similar methods
        // using its graph, which is prepopulated using the universeScope (see #beforeEvaluateQuery),
        // so no preloading of target patterns is necessary.
    }

    @Throws(java.lang.InterruptedException::class, com.google.devtools.build.lib.query2.engine.QueryException::class)
    fun reportUnsuccessfulOrMissingTargets(
        keysWithTargets: MutableMap<out SkyKey?, Target?>,
        allTargetKeys: MutableSet<SkyKey?>,
        caller: QueryExpression?
    ) {
        val missingTargets: MutableSet<SkyKey?> = HashSet<SkyKey?>()
        val keysFound: MutableSet<out SkyKey?> = keysWithTargets.keys
        for (key in allTargetKeys) {
            if (!keysFound.contains(key)) {
                missingTargets.add(key)
            }
        }
        reportUnsuccessfulOrMissingTargetsInternal(keysWithTargets.values, missingTargets, caller)
    }

    @Throws(java.lang.InterruptedException::class, com.google.devtools.build.lib.query2.engine.QueryException::class)
    private fun reportUnsuccessfulOrMissingTargetsInternal(
        targets: Iterable<Target?>, missingTargetKeys: Iterable<SkyKey?>, caller: QueryExpression?
    ) {
        // Targets can be in four states:
        //  (1) Existent TransitiveTraversalValue with no error
        //  (2) Existent TransitiveTraversalValue with an error (eg. transitive dependency error)
        //  (3) Non-existent because it threw a SkyFunctionException
        //  (4) Non-existent because it was never evaluated
        //
        // We first find the errors in the existent TransitiveTraversalValues that have an error. We
        // then find which keys correspond to SkyFunctionExceptions and extract the errors from those.
        // Lastly, any leftover keys are marked as missing from the graph and an error is produced.
        val errorsBuilder: com.google.common.collect.ImmutableList.Builder<ErrorsToHandle?> =
            com.google.common.collect.ImmutableList.builder<ErrorsToHandle?>()
        val successfulKeys: MutableSet<SkyKey?> = filterSuccessfullyLoadedTargets(targets, errorsBuilder)

        val keysWithTarget: Iterable<SkyKey?> = makeLabels<Target?>(targets)
        // Next, look for errors from the unsuccessfully evaluated TransitiveTraversal skyfunctions.
        val unsuccessfulKeys: Iterable<SkyKey?> =
            com.google.common.collect.Iterables.filter<SkyKey?>(
                keysWithTarget,
                com.google.common.base.Predicates.not<SkyKey?>(
                    com.google.common.base.Predicates.`in`<SkyKey?>(successfulKeys)
                )
            )
        val unsuccessfulOrMissingKeys: Iterable<SkyKey?> =
            com.google.common.collect.Iterables.concat<SkyKey?>(unsuccessfulKeys, missingTargetKeys)
        processUnsuccessfulAndMissingKeys(unsuccessfulOrMissingKeys, errorsBuilder)

        // Lastly, report all found errors.
        if (!com.google.common.collect.Iterables.isEmpty(unsuccessfulOrMissingKeys)) {
            eventHandler.handle(
                com.google.devtools.build.lib.events.Event.warn("Targets were missing from graph: " + unsuccessfulOrMissingKeys)
            )
        }
        for (error in errorsBuilder.build()) {
            handleError(caller, error.message, DetailedExitCode.of(error.failureDetail))
        }
    }

    /**
     * An error message and a [FailureDetail] to include in either [.handleError]'s thrown
     * [QueryException] or emitted [Event].
     */
    // This exists because NoSuchPackageException's #getMessage and its FailureDetail's message field
    // can have different contents. That fact is related to how NSPE's #getMessage dynamically adds a
    // prefix... which would be nice, but painstaking, to unwind.
    protected class ErrorsToHandle(private val message: String?, failureDetail: FailureDetail?) {
        private val failureDetail: FailureDetail?

        init {
            this.failureDetail = failureDetail
        }
    }

    // Finds labels that were evaluated but resulted in an exception, adding any errors to the
    // passed-in errorsBuilder.
    @Throws(java.lang.InterruptedException::class)
    protected fun processUnsuccessfulAndMissingKeys(
        unsuccessfulKeys: Iterable<SkyKey?>?,
        errorsBuilder: com.google.common.collect.ImmutableList.Builder<ErrorsToHandle?>
    ) {
        val errorEntries: MutableSet<MutableMap.MutableEntry<SkyKey?, java.lang.Exception?>> =
            graph.getMissingAndExceptions(unsuccessfulKeys).entries
        for (entry in errorEntries) {
            val exception: java.lang.Exception? = entry.value
            if (exception != null) {
                errorsBuilder.add(
                    ErrorsToHandle(exception.message, createUnsuccessfulKeyFailure(exception))
                )
            }
        }
    }

    // Filters for successful targets while storing error messages of unsuccessful targets.
    @Throws(java.lang.InterruptedException::class)
    protected fun filterSuccessfullyLoadedTargets(
        targets: Iterable<Target?>, errorsBuilder: com.google.common.collect.ImmutableList.Builder<ErrorsToHandle?>
    ): MutableSet<SkyKey?> {
        val transitiveTraversalKeys: Iterable<SkyKey?> = makeLabels<Target?>(targets)

        // First, look for errors in the successfully evaluated TransitiveTraversalValues. They may
        // have encountered errors that they were able to recover from.
        val successfulEntries: MutableSet<MutableMap.MutableEntry<SkyKey?, SkyValue?>> =
            graph.getSuccessfulValues(transitiveTraversalKeys).entries
        val successfulKeysBuilder: com.google.common.collect.ImmutableSet.Builder<SkyKey?> =
            com.google.common.collect.ImmutableSet.builder<SkyKey?>()
        for (successfulEntry in successfulEntries) {
            successfulKeysBuilder.add(successfulEntry.key)
            val value: TransitiveTraversalValue = successfulEntry.value as TransitiveTraversalValue
            val errorMessage: String? = value.getErrorMessage()
            if (errorMessage != null) {
                val failureDetail: FailureDetail? =
                    FailureDetail.newBuilder()
                        .setMessage(errorMessage)
                        .setQuery(Query.newBuilder().setCode(Code.SKYQUERY_TRANSITIVE_TARGET_ERROR))
                        .build()
                errorsBuilder.add(ErrorsToHandle(errorMessage, failureDetail))
            }
        }
        return successfulKeysBuilder.build()
    }

    val eventHandler: ExtendedEventHandler?

    @com.google.devtools.build.lib.concurrent.ThreadSafety.ThreadSafe
    @Throws(java.lang.InterruptedException::class)
    fun getTargetKeyToTargetMapForPackageKeyToTargetKeyMap(
        packageKeyToTargetKeyMap: com.google.common.collect.Multimap<SkyKey?, SkyKey?>
    ): MutableMap<SkyKey?, Target?> {
        val resultBuilder: com.google.common.collect.ImmutableMap.Builder<SkyKey?, Target?> =
            com.google.common.collect.ImmutableMap.builder<SkyKey?, Target?>()
        getTargetsForPackageKeyToTargetKeyMapHelper(
            packageKeyToTargetKeyMap,
            java.util.function.BiConsumer { key: SkyKey?, value: Target? -> resultBuilder.put(key, value) })
        return resultBuilder.buildOrThrow()
    }

    @com.google.devtools.build.lib.concurrent.ThreadSafety.ThreadSafe
    @Throws(java.lang.InterruptedException::class)
    fun getPkgIdToTargetMultimapForPackageKeyToTargetKeyMap(
        packageKeyToTargetKeyMap: com.google.common.collect.Multimap<SkyKey?, SkyKey?>
    ): com.google.common.collect.Multimap<PackageIdentifier?, Target?> {
        val result: com.google.common.collect.Multimap<PackageIdentifier?, Target?> =
            com.google.common.collect.ArrayListMultimap.create<PackageIdentifier?, Target?>()
        getTargetsForPackageKeyToTargetKeyMapHelper(
            packageKeyToTargetKeyMap,
            java.util.function.BiConsumer { k: SkyKey?, t: Target? ->
                result.put(
                    t.getLabel().getPackageIdentifier(),
                    t
                )
            })
        return result
    }

    @Throws(java.lang.InterruptedException::class)
    private fun getTargetsForPackageKeyToTargetKeyMapHelper(
        packageKeyToTargetKeyMap: com.google.common.collect.Multimap<SkyKey?, SkyKey?>,
        targetKeyAndTargetConsumer: java.util.function.BiConsumer<SkyKey?, Target?>
    ) {
        val processedTargets: MutableSet<SkyKey?> = HashSet<SkyKey?>()
        val packageMap: MutableMap<SkyKey?, SkyValue?> = graph.getSuccessfulValues(packageKeyToTargetKeyMap.keySet())
        for (entry in packageMap.entries) {
            val pkg: Package = (entry.value as PackageValue).getPackage()
            for (targetKey in packageKeyToTargetKeyMap.get(entry.key)) {
                if (processedTargets.add(targetKey)) {
                    try {
                        val target: Target? = pkg.getTarget(SKYKEY_TO_LABEL.apply(targetKey).name)
                        targetKeyAndTargetConsumer.accept(targetKey, target)
                    } catch (e: NoSuchTargetException) {
                        // Skip missing target.
                    }
                }
            }
        }
    }

    init {
        this.graphFactory = graphFactory
        this.pkgPath = pkgPath
        this.universeScope = universeScope
        this.mainRepoTargetParser = mainRepoTargetParser
        this.parserPrefix = parserPrefix
        this.queryEvaluationParallelismLevel = queryEvaluationParallelismLevel
        // In #getAllowedDeps we have special treatment of deps entailed by the `visibility` attribute.
        // Since this attribute is of the NODEP type, that means we need a special implementation of
        // NO_NODEP_DEPS.
        this.visibilityDepsAreAllowed =
            !settings.contains(com.google.devtools.build.lib.query2.engine.QueryEnvironment.Setting.NO_NODEP_DEPS)
        // The "toolchains" parameter of rule definition should be treated as an implicit dep despite
        // not being represented by an attribute.
        this.toolchainTypeDepsAreAllowed =
            !settings.contains(com.google.devtools.build.lib.query2.engine.QueryEnvironment.Setting.NO_IMPLICIT_DEPS)
    }

    override fun getOrCreate(target: Target?): Target? {
        return target
    }

    @Throws(com.google.devtools.build.lib.query2.engine.QueryException::class, java.lang.InterruptedException::class)
    fun getBuildFileTargetsForPackageKeys(
        pkgIds: MutableSet<PackageIdentifier?>?, context: QueryExpressionContext<Target?>?
    ): Iterable<Target?> {
        packageSemaphore.acquireAll(pkgIds)
        try {
            return com.google.common.collect.Iterables.transform<SkyValue?, Target?>(
                graph.getSuccessfulValues(pkgIds).values,
                com.google.common.base.Function { skyValue: SkyValue? ->
                    (skyValue as PackageValue).getPackage().getBuildFile()
                })
        } finally {
            packageSemaphore.releaseAll(pkgIds)
        }
    }

    /**
     * Calculates the set of packages whose evaluation transitively depends on (e.g. via 'load'
     * statements) the contents of the specified paths. The emitted [Target]s are BUILD file
     * targets.
     */
    @com.google.devtools.build.lib.concurrent.ThreadSafety.ThreadSafe
    fun getRBuildFiles(
        fileIdentifiers: MutableCollection<PathFragment?>?,
        context: QueryExpressionContext<Target?>?,
        callback: com.google.devtools.build.lib.query2.engine.Callback<Target?>?
    ): QueryTaskFuture<java.lang.Void?> {
        return QueryTaskFutureImpl.Companion.ofDelegate<java.lang.Void?>(
            safeSubmit<java.lang.Void?>(
                java.util.concurrent.Callable {
                    ParallelSkyQueryUtils.getRBuildFilesParallel(
                        this@SkyQueryEnvironment, fileIdentifiers, context, callback
                    )
                    null
                })
        )
    }

    val functions: Iterable<QueryFunction>
        get() = com.google.common.collect.ImmutableList.builder<QueryFunction?>()
            .addAll(super.getFunctions())
            .add(AllRdepsFunction())
            .add(RBuildFilesFunction())
            .build()

    private class SkyKeyKeyExtractor : KeyExtractor<SkyKey?, SkyKey?> {
        override fun extractKey(element: SkyKey?): SkyKey? {
            return element
        }

        companion object {
            private val INSTANCE = SkyKeyKeyExtractor()
        }
    }

    /**
     * Wraps a [Callback] and guarantees that all calls to the original will have at least
     * `batchThreshold` [Target]s, except for the final such call.
     * 
     * 
     * Retains fewer than `batchThreshold` [Target]s at a time.
     * 
     * 
     * After this object's [.process] has been called for the last time, {#link
     * #processLastPending} must be called to "flush" any remaining [Target]s through to the
     * original.
     * 
     * 
     * This callback may be called from multiple threads concurrently. At most one thread will call
     * the wrapped `callback` concurrently.
     */
    // TODO(nharmata): For queries with less than {@code batchThreshold} results, this batching
    // strategy probably hurts performance since we can only start formatting results once the entire
    // query is finished.
    // TODO(nharmata): This batching strategy is also potentially harmful from a memory perspective
    // since when the Targets being output are backed by Package instances, we're delaying GC of the
    // Package instances until the output batch size is met.
    private class BatchStreamedCallback(
        callback: ThreadSafeOutputFormatterCallback<Target?>,
        batchThreshold: Int,
        uniquifier: NonExceptionalUniquifier<Target?>
    ) : ThreadSafeOutputFormatterCallback<Target?>(), com.google.devtools.build.lib.query2.engine.Callback<Target?> {
        // TODO(nharmata): Now that we know the wrapped callback is ThreadSafe, there's no correctness
        // concern that requires the prohibition of concurrent uses of the callback; the only concern is
        // memory. We should have a threshold for when to invoke the callback with a batch, and also a
        // separate, larger, bound on the number of targets being processed at the same time.
        private val callback: ThreadSafeOutputFormatterCallback<Target?>
        private val uniquifier: NonExceptionalUniquifier<Target?>
        private val pendingLock = Any()
        private var pending: MutableList<Target?>? = java.util.ArrayList<Target?>()
        private val batchThreshold: Int

        init {
            this.callback = callback
            this.batchThreshold = batchThreshold
            this.uniquifier = uniquifier
        }

        @Throws(IOException::class)
        override fun start() {
            callback.start()
        }

        @Throws(IOException::class, java.lang.InterruptedException::class)
        override fun processOutput(partialResult: Iterable<Target?>?) {
            val uniquifiedTargets: com.google.common.collect.ImmutableList<Target?>? = uniquifier.unique(partialResult)
            var toProcess: Iterable<Target?>? = null
            synchronized(pendingLock) {
                com.google.common.base.Preconditions.checkNotNull<MutableList<Target?>?>(
                    pending,
                    "Reuse of the callback is not allowed"
                )
                pending!!.addAll(uniquifiedTargets)
                if (pending!!.size >= batchThreshold) {
                    toProcess = pending
                    pending = java.util.ArrayList<Target?>()
                }
            }
            if (toProcess != null) {
                callback.processOutput(toProcess)
            }
        }

        @Throws(IOException::class, java.lang.InterruptedException::class)
        override fun close(failFast: Boolean) {
            if (!failFast) {
                processLastPending()
            }
            callback.close(failFast)
        }

        @Throws(IOException::class, java.lang.InterruptedException::class)
        fun processLastPending() {
            synchronized(pendingLock) {
                if (!pending!!.isEmpty()) {
                    callback.processOutput(pending)
                    pending = null
                }
            }
        }
    }

    @com.google.devtools.build.lib.concurrent.ThreadSafety.ThreadSafe
    override fun getAllRdepsUnboundedParallel(
        expression: QueryExpression?,
        context: QueryExpressionContext<Target?>?,
        callback: com.google.devtools.build.lib.query2.engine.Callback<Target?>?
    ): QueryTaskFuture<java.lang.Void?>? {
        return ParallelSkyQueryUtils.getAllRdepsUnboundedParallel(this, expression, context, callback)
    }

    @com.google.devtools.build.lib.concurrent.ThreadSafety.ThreadSafe
    override fun getAllRdepsBoundedParallel(
        expression: QueryExpression?,
        depth: Int,
        context: QueryExpressionContext<Target?>?,
        callback: com.google.devtools.build.lib.query2.engine.Callback<Target?>?
    ): QueryTaskFuture<java.lang.Void?>? {
        return ParallelSkyQueryUtils.getAllRdepsBoundedParallel(
            this, expression, depth, context, callback
        )
    }

    protected fun getUnfilteredUniverseDTCSkyKeyPredicateFuture(
        universe: QueryExpression?, context: QueryExpressionContext<Target?>?
    ): QueryTaskFuture<com.google.common.base.Predicate<SkyKey?>?>? {
        return ParallelSkyQueryUtils.getDTCSkyKeyPredicateFuture(
            this, universe, context, BATCH_CALLBACK_SIZE, queryEvaluationParallelismLevel
        )
    }

    @com.google.devtools.build.lib.concurrent.ThreadSafety.ThreadSafe
    override fun getRdepsUnboundedParallel(
        expression: QueryExpression?,
        universe: QueryExpression?,
        context: QueryExpressionContext<Target?>?,
        callback: com.google.devtools.build.lib.query2.engine.Callback<Target?>?
    ): QueryTaskFuture<java.lang.Void?> {
        return
        Void > transformAsync<com.google.common.base.Predicate<SkyKey?>?, java.lang.Void?>( // Even if we need to do edge filtering, it's fine to construct the rdeps universe via an
            // unfiltered DTC visitation; the subsequent rdeps visitation will perform the edge
            // filtering.
            getUnfilteredUniverseDTCSkyKeyPredicateFuture(universe, context),
            com.google.common.base.Function { unfilteredUniversePredicate: com.google.common.base.Predicate<SkyKey?>? ->
                ParallelSkyQueryUtils.getRdepsInUniverseUnboundedParallel(
                    this, expression, unfilteredUniversePredicate, context, callback
                )
            })
    }

    override fun getDepsUnboundedParallel(
        expression: QueryExpression?,
        context: QueryExpressionContext<Target?>?,
        callback: com.google.devtools.build.lib.query2.engine.Callback<Target?>?,
        caller: QueryExpression?
    ): QueryTaskFuture<java.lang.Void?>? {
        return ParallelSkyQueryUtils.getDepsUnboundedParallel(
            this@SkyQueryEnvironment,
            expression,
            context,
            callback,  /* depsNeedFiltering= */
            !dependencyFilter.equals(DependencyFilter.ALL_DEPS),
            caller
        )
    }

    @com.google.devtools.build.lib.concurrent.ThreadSafety.ThreadSafe
    override fun getRdepsBoundedParallel(
        expression: QueryExpression?,
        depth: Int,
        universe: QueryExpression?,
        context: QueryExpressionContext<Target?>?,
        callback: com.google.devtools.build.lib.query2.engine.Callback<Target?>?
    ): QueryTaskFuture<java.lang.Void?> {
        return
        Void > transformAsync<com.google.common.base.Predicate<SkyKey?>?, java.lang.Void?>( // Even if we need to do edge filtering, it's fine to construct the rdeps universe via an
            // unfiltered DTC visitation; the subsequent rdeps visitation will perform the edge
            // filtering.
            getUnfilteredUniverseDTCSkyKeyPredicateFuture(universe, context),
            com.google.common.base.Function { universePredicate: com.google.common.base.Predicate<SkyKey?>? ->
                ParallelSkyQueryUtils.getRdepsInUniverseBoundedParallel(
                    this, expression, depth, universePredicate, context, callback
                )
            })
    }

    companion object {
        // 10k is likely a good balance between using batch efficiently and not blowing up memory.
        // TODO(janakr): Unify with RecursivePackageProviderBackedTargetPatternResolver's constant.
        const val BATCH_CALLBACK_SIZE: Int = 10000
        val DEFAULT_THREAD_COUNT: Int = java.lang.Runtime.getRuntime().availableProcessors()
        private val logger: GoogleLogger = GoogleLogger.forEnclosingClass()

        @Throws(com.google.devtools.build.lib.query2.engine.QueryException::class)
        private fun checkEvaluationResult(
            universeScopeList: com.google.common.collect.ImmutableList<String?>?,
            roots: MutableSet<SkyKey?>,
            universeKey: SkyKey?,
            result: EvaluationResult<SkyValue?>,
            exprForError: QueryExpression?
        ) {
            // If the only root is the universe key, we expect to see either a single successfully evaluated
            // value or a cycle in the result or a catastrophic error.
            val values: MutableCollection<SkyValue?> = result.values()
            if (!values.isEmpty()) {
                if (roots.size != 1 || com.google.common.collect.Iterables.getOnlyElement<SkyKey?>(roots) != universeKey) {
                    return
                }
                com.google.common.base.Preconditions.checkState(
                    values.size == 1,
                    "Universe query \"%s\" returned multiple values unexpectedly (%s values in result)",
                    universeScopeList,
                    values.size
                )
                com.google.common.base.Preconditions.checkNotNull<SkyValue?>(result.get(universeKey), result)
                return
            }
            com.google.common.base.Preconditions.checkState(
                result.hasError(),
                "Universe query \"%s\" failed but had no error: %s",
                universeScopeList,
                result
            )
            QueryTransitivePackagePreloader.Companion.maybeThrowQueryExceptionForResultWithError(
                result, roots, exprForError,  /* operation= */"Building universe scope"
            )
        }

        private val MIN_LOGGING: java.time.Duration? = java.time.Duration.ofMillis(50)
        private val MAX_QUERY_WEIGHT_TO_LOG = 10 * 1024 * 1024

        /**
         * Returns a (new, mutable, unordered) set containing the intersection of the two specified sets.
         */
        private fun <T> intersection(x: MutableSet<T?>, y: MutableSet<T?>): MutableSet<T?> {
            val result: MutableSet<T?> = HashSet<T?>()
            if (x.size > y.size) {
                com.google.common.collect.Sets.intersection<T?>(y, x).copyInto<MutableSet<T?>?>(result)
            } else {
                com.google.common.collect.Sets.intersection<T?>(x, y).copyInto<MutableSet<T?>?>(result)
            }
            return result
        }

        private fun <T> totalSizeOfCollections(nestedCollections: Iterable<MutableCollection<T?>>): Int {
            var totalSize = 0
            for (collection in nestedCollections) {
                totalSize += collection.size
            }
            return totalSize
        }

        protected fun createUnsuccessfulKeyFailure(exception: java.lang.Exception): FailureDetail? {
            return if (exception is DetailedException)
                exception.getDetailedExitCode().getFailureDetail()
            else
                FailureDetail.newBuilder()
                    .setMessage(exception.message)
                    .setQuery(Query.newBuilder().setCode(Code.SKYQUERY_TARGET_EXCEPTION))
                    .build()
        }

        val IS_LABEL: com.google.common.base.Predicate<SkyKey?> = SkyFunctionName.functionIs(Label.TRANSITIVE_TRAVERSAL)

        val SKYKEY_TO_LABEL: com.google.common.base.Function<SkyKey?, Label?> =
            com.google.common.base.Function { skyKey: SkyKey? -> if (IS_LABEL.test(skyKey)) skyKey.argument() as Label? else null }

        private val PACKAGE_SKYKEY_TO_PACKAGE_IDENTIFIER: com.google.common.base.Function<SkyKey?, PackageIdentifier?> =
            com.google.common.base.Function { skyKey: SkyKey? -> skyKey.argument() as PackageIdentifier? }

        fun makePackageKeyToTargetKeyMap(keys: Iterable<SkyKey?>): com.google.common.collect.Multimap<SkyKey?, SkyKey?> {
            val packageKeyToTargetKeyMap: com.google.common.collect.Multimap<SkyKey?, SkyKey?> =
                com.google.common.collect.ArrayListMultimap.create<SkyKey?, SkyKey?>()
            for (key in keys) {
                val label: Label? = SKYKEY_TO_LABEL.apply(key)
                if (label == null) {
                    continue
                }
                packageKeyToTargetKeyMap.put(label.getPackageIdentifier(), key)
            }
            return packageKeyToTargetKeyMap
        }

        fun getPkgIdsNeededForTargetification(
            packageKeyToTargetKeyMap: com.google.common.collect.Multimap<SkyKey?, SkyKey?>
        ): MutableSet<PackageIdentifier?> {
            return packageKeyToTargetKeyMap.keySet().stream()
                .map<PackageIdentifier?>(PACKAGE_SKYKEY_TO_PACKAGE_IDENTIFIER)
                .collect(com.google.common.collect.ImmutableSet.toImmutableSet<PackageIdentifier?>())
        }

        val TARGET_TO_SKY_KEY: com.google.common.base.Function<Target?, SkyKey?> =
            com.google.common.base.Function { target: Target? -> TransitiveTraversalValue.key(target.getLabel()) }

        /** A strict (i.e. non-lazy) variant of [.makeLabels].  */
        fun <T : Target?> makeLabelsStrict(targets: Iterable<T?>): Iterable<SkyKey?> {
            return com.google.common.collect.ImmutableList.copyOf<SkyKey?>(makeLabels<T?>(targets))
        }

        protected fun <T : Target?> makeLabels(targets: Iterable<T?>): Iterable<SkyKey?> {
            return com.google.common.collect.Iterables.transform<F?, T?>(targets, TARGET_TO_SKY_KEY)
        }
    }
}
