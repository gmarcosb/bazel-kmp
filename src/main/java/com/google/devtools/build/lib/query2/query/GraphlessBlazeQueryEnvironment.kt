// Copyright 2019 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.query2.query

import com.google.devtools.build.lib.cmdline.Label

/**
 * The environment of a Blaze query. Not thread-safe.
 * 
 * 
 * In contrast with [BlazeQueryEnvironment], this one does not support ordered output, and
 * therefore also does not make a partial copy of the graph in a Digraph instance. As a corollary,
 * it only returns an instance of [ ] rather than [ ], and can therefore not be
 * used with most existing [com.google.devtools.build.lib.query2.query.output.OutputFormatter]
 * implementations, many of which expect the latter.
 * 
 * 
 * This environment is valid only for a single query, called via [.evaluateQuery]. Call
 * only once!
 */
class GraphlessBlazeQueryEnvironment(
    queryTransitivePackagePreloader: QueryTransitivePackagePreloader?,
    targetProvider: TargetProvider,
    cachingPackageLocator: CachingPackageLocator,
    targetPatternPreloader: TargetPatternPreloader,
    mainRepoTargetParser: Parser?,
    keepGoing: Boolean,
    strictScope: Boolean,
    loadingPhaseThreads: Int,
    labelFilter: java.util.function.Predicate<Label?>?,
    eventHandler: ExtendedEventHandler?,
    settings: MutableSet<com.google.devtools.build.lib.query2.engine.QueryEnvironment.Setting?>?,
    extraFunctions: Iterable<QueryFunction?>?,
    labelPrinter: LabelPrinter?
) : AbstractBlazeQueryEnvironment<Target?>(
    keepGoing, strictScope, labelFilter, eventHandler, settings, extraFunctions, labelPrinter
), CustomFunctionQueryEnvironment<Target?> {
    private val resolvedTargetPatterns: MutableMap<String?, MutableCollection<Target?>?> =
        HashMap<String?, MutableCollection<Target?>?>()
    private val targetPatternPreloader: TargetPatternPreloader
    private val mainRepoTargetParser: TargetPattern.Parser?
    private val queryTransitivePackagePreloader: QueryTransitivePackagePreloader?
    private val targetProvider: TargetProvider
    private val cachingPackageLocator: CachingPackageLocator
    private val errorObserver: ErrorPrintingTargetEdgeErrorObserver
    private val labelVisitor: LabelVisitor
    protected val loadingPhaseThreads: Int

    val accessor: BlazeTargetAccessor = BlazeTargetAccessor(this)

    private var doneQuery = false

    /**
     * Note that the correct operation of this class critically depends on the Reporter being a
     * singleton object, shared by all cooperating classes contributing to Query.
     * 
     * @param strictScope if true, fail the whole query if a label goes out of scope.
     * @param loadingPhaseThreads the number of threads to use during loading the packages for the
     * query.
     * @param labelFilter a predicate that determines if a specific label is allowed to be visited
     * during query execution. If it returns false, the query execution is stopped with an error
     * message.
     * @param settings a set of enabled settings
     */
    init {
        this.targetPatternPreloader = targetPatternPreloader
        this.mainRepoTargetParser = mainRepoTargetParser
        this.queryTransitivePackagePreloader = queryTransitivePackagePreloader
        this.targetProvider = targetProvider
        this.cachingPackageLocator = cachingPackageLocator
        this.errorObserver = ErrorPrintingTargetEdgeErrorObserver(this.eventHandler)
        this.loadingPhaseThreads = loadingPhaseThreads
        this.labelVisitor = LabelVisitor(targetProvider, dependencyFilter)
    }

    override fun eval(
        expr: QueryExpression,
        context: QueryExpressionContext<Target?>?,
        callback: com.google.devtools.build.lib.query2.engine.Callback<Target?>?,
        batch: Boolean
    ): QueryTaskFuture<java.lang.Void?>? {
        if (batch) {
            // This uses AbstractBlazeQueryEnvironment#eval that aggregates the results of the futures
            // into a single batch before running the callback on the batch of results, providing an
            // alternative for the environment to decide when to batch the results and when batching is
            // not needed.
            return super.eval(expr, context, callback)
        }
        return eval(expr, context, callback)
    }

    override fun eval(
        expr: QueryExpression,
        context: QueryExpressionContext<Target?>?,
        callback: com.google.devtools.build.lib.query2.engine.Callback<Target?>?
    ): QueryTaskFuture<java.lang.Void?>? {
        // The graphless query implementation does not perform any streaming at this point. However,
        // not all operators return a single future (e.g. 'SetExpression'), as such, do not use this if
        // the callback does heavy blocking work (e.g. 'deps').
        return expr.eval<Target?>(this, context, callback)
    }

    @Throws(
        com.google.devtools.build.lib.query2.engine.QueryException::class,
        IOException::class,
        java.lang.InterruptedException::class
    )
    override fun evaluateQuery(
        expr: QueryExpression, callback: ThreadSafeOutputFormatterCallback<Target?>?
    ): QueryEvalResult? {
        com.google.common.base.Preconditions.checkState(!doneQuery, "Can only use environment for one query: %s", expr)
        doneQuery = true
        return evaluateQueryInternal(expr, callback)
    }

    override fun close() {
        // BlazeQueryEnvironment has no resources that need to be cleaned up.
    }

    @Throws(com.google.devtools.build.lib.query2.engine.QueryException::class, java.lang.InterruptedException::class)
    override fun getSiblingTargetsInPackage(target: Target?): MutableCollection<Target?> {
        try {
            return targetProvider.getSiblingTargetsInPackage(eventHandler, target)
        } catch (e: NoSuchPackageException) {
            throw com.google.devtools.build.lib.query2.engine.QueryException(
                e.getMessage(),
                e,
                e.getDetailedExitCode().getFailureDetail()
            )
        }
    }

    override fun getTargetsMatchingPattern(
        owner: QueryExpression?,
        pattern: String?,
        callback: com.google.devtools.build.lib.query2.engine.Callback<Target?>
    ): QueryTaskFuture<java.lang.Void?>? {
        try {
            getTargetsMatchingPatternImpl(pattern, callback)
            return immediateSuccessfulFuture<java.lang.Void?>(null)
        } catch (e: com.google.devtools.build.lib.query2.engine.QueryException) {
            return immediateFailedFuture<java.lang.Void?>(e)
        } catch (e: java.lang.InterruptedException) {
            return immediateCancelledFuture<java.lang.Void?>()
        }
    }

    @Throws(com.google.devtools.build.lib.query2.engine.QueryException::class, java.lang.InterruptedException::class)
    private fun getTargetsMatchingPatternImpl(
        pattern: String?,
        callback: com.google.devtools.build.lib.query2.engine.Callback<Target?>
    ) {
        val targets: MutableSet<Target?> = LinkedHashSet<Target?>(resolvedTargetPatterns.get(pattern))
        validateScopeOfTargets(targets)
        callback.process(targets)
    }

    @Throws(
        TargetNotFoundException::class,
        com.google.devtools.build.lib.query2.engine.QueryException::class,
        java.lang.InterruptedException::class
    )
    override fun getTarget(label: Label?): Target {
        try {
            return getTargetOrThrow(label)
        } catch (e: NoSuchThingException) {
            throw TargetNotFoundException(e, e.getDetailedExitCode())
        }
    }

    override fun getOrCreate(target: Target?): Target? {
        return target
    }

    override fun getFwdDeps(
        targets: Iterable<Target?>?, context: QueryExpressionContext<Target?>?
    ): MutableCollection<Target?>? {
        throw java.lang.UnsupportedOperationException()
    }

    override fun getReverseDeps(
        targets: Iterable<Target?>?, context: QueryExpressionContext<Target?>?
    ): MutableCollection<Target?>? {
        throw java.lang.UnsupportedOperationException()
    }

    override fun getTransitiveClosure(
        targetNodes: ThreadSafeMutableSet<Target?>?, context: QueryExpressionContext<Target?>?
    ): ThreadSafeMutableSet<Target?>? {
        throw java.lang.UnsupportedOperationException()
    }

    @Throws(java.lang.InterruptedException::class, com.google.devtools.build.lib.query2.engine.QueryException::class)
    override fun deps(
        from: Iterable<Target>,
        maxDepth: OptionalInt,
        caller: QueryExpression?,
        callback: com.google.devtools.build.lib.query2.engine.Callback<Target?>
    ) {
        // TODO(ulfjack): There's no need to visit the transitive closure twice. Ideally, preloading
        //  would return the list of targets, but it currently only returns the list of labels.
        Profiler.instance().profile("preloadTransitiveClosure").use { closeable ->
            preloadTransitiveClosure(from, maxDepth, caller)
        }
        val result: MutableSet<Target?> = com.google.common.collect.Sets.newConcurrentHashSet<Target?>()
        Profiler.instance().profile("syncUncached").use { closeable ->
            LabelVisitor(targetProvider, dependencyFilter)
                .syncUncached(
                    eventHandler,
                    from,
                    keepGoing,
                    loadingPhaseThreads,
                    maxDepth,
                    object : TargetEdgeObserver() {
                        public override fun edge(from: Target?, attribute: Attribute?, to: Target?) {
                            errorObserver.edge(from, attribute, to)
                        }

                        public override fun missingEdge(target: Target?, to: Label?, e: NoSuchThingException) {
                            errorObserver.missingEdge(target, to, e)
                        }

                        public override fun node(node: Target) {
                            result.add(node)
                            errorObserver.node(node)
                        }
                    })
        }
        if (errorObserver.hasErrors()) {
            handleError(
                caller,
                "errors were encountered while computing transitive closure",
                errorObserver.getDetailedExitCode()
            )
        }
        callback.process(result)
    }

    @Throws(java.lang.InterruptedException::class, com.google.devtools.build.lib.query2.engine.QueryException::class)
    override fun somePath(
        from: Iterable<Target>,
        to: Iterable<Target?>,
        caller: QueryExpression?,
        callback: com.google.devtools.build.lib.query2.engine.Callback<Target?>
    ) {
        Profiler.instance().profile("preloadTransitiveClosure").use { closeable ->
            preloadTransitiveClosure(from,  /*maxDepth=*/OptionalInt.empty(), caller)
        }
        val results: Iterable<Target?> =
            PathLabelVisitor(targetProvider, dependencyFilter, errorObserver)
                .somePath(eventHandler, from, to)
        if (errorObserver.hasErrors()) {
            handleError(
                caller,
                "errors were encountered while computing transitive closure",
                errorObserver.getDetailedExitCode()
            )
        }
        callback.process(results)
    }

    @Throws(java.lang.InterruptedException::class, com.google.devtools.build.lib.query2.engine.QueryException::class)
    override fun allPaths(
        from: Iterable<Target>,
        to: Iterable<Target?>,
        caller: QueryExpression?,
        callback: com.google.devtools.build.lib.query2.engine.Callback<Target?>
    ) {
        Profiler.instance().profile("preloadTransitiveClosure").use { closeable ->
            preloadTransitiveClosure(from,  /*maxDepth=*/OptionalInt.empty(), caller)
        }
        val results: Iterable<Target?> =
            PathLabelVisitor(targetProvider, dependencyFilter, errorObserver)
                .allPaths(eventHandler, from, to)
        if (errorObserver.hasErrors()) {
            handleError(
                caller,
                "errors were encountered while computing transitive closure",
                errorObserver.getDetailedExitCode()
            )
        }
        callback.process(results)
    }

    @Throws(java.lang.InterruptedException::class, com.google.devtools.build.lib.query2.engine.QueryException::class)
    override fun samePkgDirectRdeps(
        from: Iterable<Target?>,
        caller: QueryExpression?,
        callback: com.google.devtools.build.lib.query2.engine.Callback<Target?>
    ) {
        val targetsToPreload: MutableSet<Target> = HashSet<Target>()
        for (t in from) {
            targetsToPreload.addAll(getSiblingTargetsInPackage(t))
        }
        Profiler.instance().profile("preloadTransitiveClosure").use { closeable ->
            preloadTransitiveClosure(
                targetsToPreload,  /*maxDepth=*/SamePkgDirectRdepsFunction.Companion.DEPTH_ONE, caller
            )
        }
        val results: Iterable<Target?> =
            PathLabelVisitor(targetProvider, dependencyFilter, errorObserver)
                .samePkgDirectRdeps(eventHandler, from)
        if (errorObserver.hasErrors()) {
            handleError(
                caller,
                "errors were encountered while computing transitive closure",
                errorObserver.getDetailedExitCode()
            )
        }
        callback.process(results)
    }

    @Throws(java.lang.InterruptedException::class, com.google.devtools.build.lib.query2.engine.QueryException::class)
    override fun rdeps(
        from: Iterable<Target?>,
        universe: Iterable<Target>,
        maxDepth: OptionalInt?,
        caller: QueryExpression?,
        callback: com.google.devtools.build.lib.query2.engine.Callback<Target?>
    ) {
        Profiler.instance().profile("preloadTransitiveClosure").use { closeable ->
            preloadTransitiveClosure(
                universe,  // PathLabelVisitor#rdeps, called below, necessarily needs to crawl the full DTC of
                // `universe` in order to be able to reify the reverse edges needed to determine the rdeps
                // of `from` at the specified depth. Therefore we preload the full DTC of `universe` in
                // parallel, so that PathLabelVisitor#rdeps doesn't need to do novel package loading.
                /* maxDepth= */
                OptionalInt.empty(),
                caller
            )
        }
        val results: Iterable<Target?> =
            PathLabelVisitor(targetProvider, dependencyFilter, errorObserver)
                .rdeps(eventHandler, from, universe, maxDepth)
        if (errorObserver.hasErrors()) {
            handleError(
                caller,
                "errors were encountered while computing transitive closure",
                errorObserver.getDetailedExitCode()
            )
        }
        callback.process(results)
    }

    @Throws(com.google.devtools.build.lib.query2.engine.QueryException::class, java.lang.InterruptedException::class)
    override fun buildTransitiveClosure(
        caller: QueryExpression?, targetNodes: ThreadSafeMutableSet<Target>, maxDepth: OptionalInt
    ) {
        Profiler.instance().profile("preloadTransitiveClosure").use { closeable ->
            preloadTransitiveClosure(targetNodes, maxDepth, caller)
        }
        Profiler.instance().profile("syncWithVisitor").use { closeable ->
            labelVisitor.syncWithVisitor(
                eventHandler, targetNodes, keepGoing, loadingPhaseThreads, maxDepth, errorObserver
            )
        }
        if (errorObserver.hasErrors()) {
            handleError(
                caller,
                "errors were encountered while computing transitive closure",
                errorObserver.getDetailedExitCode()
            )
        }
    }

    override fun getNodesOnPath(
        from: Target?, to: Target?, context: QueryExpressionContext<Target?>?
    ): Iterable<Target?>? {
        throw java.lang.UnsupportedOperationException()
    }

    @com.google.devtools.build.lib.concurrent.ThreadSafety.ThreadSafe
    override fun createThreadSafeMutableSet(): ThreadSafeMutableSet<Target?> {
        return ThreadSafeMutableKeyExtractorBackedSetImpl<Target?, Label?>(
            TargetKeyExtractor.Companion.INSTANCE, Target::class.java
        )
    }

    override fun createUniquifier(): Uniquifier<Target?> {
        return UniquifierImpl<Target?, Label?>(TargetKeyExtractor.Companion.INSTANCE)
    }

    override fun createMinDepthUniquifier(): MinDepthUniquifier<Target?> {
        return MinDepthUniquifierImpl<Target?, Label?>(TargetKeyExtractor.Companion.INSTANCE,  /*concurrencyLevel=*/1)
    }

    val transitiveLoadFilesHelper: TransitiveLoadFilesHelper<Target?>
        get() = object : TransitiveLoadFilesHelperForTargets() {
            @Throws(java.lang.InterruptedException::class)
            override fun getLoadFileTarget(originalTarget: Target?, bzlLabel: Label?): Target? {
                return FakeLoadTarget(bzlLabel, getBuildFileTarget(originalTarget).getPackageoid())
            }

            @Throws(java.lang.InterruptedException::class)
            override fun getBuildFileTarget(originalTarget: Target?): Target {
                return targetProvider.getBuildFile(originalTarget)
            }

            @Throws(java.lang.InterruptedException::class)
            override fun maybeGetBuildFileTargetForLoadFileTarget(originalTarget: Target?, bzlLabel: Label): Target? {
                val pkgIdOfBzlLabel: PackageIdentifier? = bzlLabel.getPackageIdentifier()
                val baseName: String? = cachingPackageLocator.getBaseNameForLoadedPackage(pkgIdOfBzlLabel)
                if (baseName == null) {
                    return null
                }
                return FakeLoadTarget(
                    Label.createUnvalidated(pkgIdOfBzlLabel, baseName),
                    getBuildFileTarget(originalTarget).getPackageoid()
                )
            }
        }

    @Throws(java.lang.InterruptedException::class, com.google.devtools.build.lib.query2.engine.QueryException::class)
    private fun preloadTransitiveClosure(
        targets: Iterable<Target>, maxDepth: OptionalInt, callerForError: QueryExpression?
    ) {
        if (QueryEnvironment.Companion.shouldVisit(maxDepth, MAX_DEPTH_FULL_SCAN_LIMIT)
            && queryTransitivePackagePreloader != null
        ) {
            // Only do the full visitation if "maxDepth" is large enough. Otherwise, the benefits of
            // preloading will be outweighed by the cost of doing more work than necessary.
            val labels: MutableSet<Label?> =
                com.google.devtools.build.lib.collect.compacthashset.CompactHashSet.create<Label?>()
            for (t in targets) {
                labels.add(t.getLabel())
            }
            queryTransitivePackagePreloader.preloadTransitiveTargets(
                eventHandler,
                labels,
                keepGoing,
                loadingPhaseThreads,  // Don't throw an error if in keep-going mode or if the depth was limited: it's possible
                // that an encountered error was deeper than the depth bound.
                if (keepGoing || maxDepth.isPresent()) null else callerForError
            )
        }
    }

    @Throws(NoSuchThingException::class, SkyframeRestartQueryException::class, java.lang.InterruptedException::class)
    private fun getTargetOrThrow(label: Label?): Target {
        val target: Target = targetProvider.getTarget(eventHandler, label)
        if (target == null) {
            throw SkyframeRestartQueryException()
        }
        return target
    }

    @Throws(TargetParsingException::class, java.lang.InterruptedException::class)
    override fun preloadOrThrow(caller: QueryExpression?, patterns: MutableCollection<String?>?) {
        com.google.common.base.Preconditions.checkState(
            resolvedTargetPatterns.isEmpty(),
            "Already resolved patterns: %s %s",
            patterns,
            resolvedTargetPatterns
        )
        // Note that this may throw a RuntimeException if deps are missing in Skyframe and this is
        // being called from within a SkyFunction.
        resolvedTargetPatterns.putAll(
            targetPatternPreloader.preloadTargetPatterns(
                eventHandler, mainRepoTargetParser, patterns, keepGoing
            )
        )
    }

    companion object {
        private const val MAX_DEPTH_FULL_SCAN_LIMIT = 20
    }
}
