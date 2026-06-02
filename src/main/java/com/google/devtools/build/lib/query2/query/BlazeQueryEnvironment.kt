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
package com.google.devtools.build.lib.query2.query

import com.google.devtools.build.lib.cmdline.Label

/**
 * The environment of a Blaze query. Not thread-safe.
 * 
 * 
 * This environment is valid only for a single query, called via [.evaluateQuery]. Call
 * only once!
 */
class BlazeQueryEnvironment(
    queryTransitivePackagePreloader: QueryTransitivePackagePreloader?,
    targetProvider: TargetProvider,
    cachingPackageLocator: CachingPackageLocator,
    targetPatternPreloader: TargetPatternPreloader,
    targetParser: Parser?,
    keepGoing: Boolean,
    strictScope: Boolean,
    loadingPhaseThreads: Int,
    labelFilter: com.google.common.base.Predicate<Label?>?,
    eventHandler: ExtendedEventHandler?,
    settings: MutableSet<com.google.devtools.build.lib.query2.engine.QueryEnvironment.Setting?>?,
    extraFunctions: Iterable<QueryFunction?>?,
    labelPrinter: LabelPrinter?
) : AbstractBlazeQueryEnvironment<Target?>(
    keepGoing, strictScope, labelFilter, eventHandler, settings, extraFunctions, labelPrinter
) {
    private val resolvedTargetPatterns: MutableMap<String?, MutableCollection<Target?>?> =
        HashMap<String?, MutableCollection<Target?>?>()
    private val targetPatternPreloader: TargetPatternPreloader
    private val targetParser: TargetPattern.Parser?
    private val queryTransitivePackagePreloader: QueryTransitivePackagePreloader?
    private val targetProvider: TargetProvider
    private val cachingPackageLocator: CachingPackageLocator
    private val graph: Digraph<Target?> = Digraph<Target?>()
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
        this.targetParser = targetParser
        this.queryTransitivePackagePreloader = queryTransitivePackagePreloader
        this.targetProvider = targetProvider
        this.cachingPackageLocator = cachingPackageLocator
        this.errorObserver = ErrorPrintingTargetEdgeErrorObserver(this.eventHandler)
        this.loadingPhaseThreads = loadingPhaseThreads
        this.labelVisitor = LabelVisitor(targetProvider, dependencyFilter)
    }

    override fun close() {
        // BlazeQueryEnvironment has no resources that need to be cleaned up.
    }

    @Throws(
        com.google.devtools.build.lib.query2.engine.QueryException::class,
        java.lang.InterruptedException::class,
        IOException::class
    )
    override fun evaluateQuery(
        expr: QueryExpression, callback: ThreadSafeOutputFormatterCallback<Target?>?
    ): DigraphQueryEvalResult<Target?> {
        com.google.common.base.Preconditions.checkState(!doneQuery, "Can only use environment for one query: %s", expr)
        doneQuery = true
        val queryEvalResult: QueryEvalResult = evaluateQueryInternal(expr, callback)
        return DigraphQueryEvalResult<Target?>(
            queryEvalResult.getSuccess(),
            queryEvalResult.isEmpty(),
            queryEvalResult.getDetailedExitCode(),
            graph
        )
    }

    @Throws(com.google.devtools.build.lib.query2.engine.QueryException::class, java.lang.InterruptedException::class)
    override fun getSiblingTargetsInPackage(target: Target?): MutableCollection<Target?> {
        val siblings: com.google.common.collect.ImmutableCollection<Target?>
        try {
            siblings = targetProvider.getSiblingTargetsInPackage(eventHandler, target)
        } catch (e: NoSuchPackageException) {
            throw com.google.devtools.build.lib.query2.engine.QueryException(
                e.getMessage(),
                e,
                e.getDetailedExitCode().getFailureDetail()
            )
        }
        // Ensure that the sibling targets are in the graph being built-up.
        siblings.forEach(java.util.function.Consumer { target: Target? -> this.getNode(target) })
        return siblings
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
        // We can safely ignore the boolean error flag. The evaluateQuery() method above wraps the
        // entire query computation in an error sensor.

        // This must be a collections class with a fast contains() implementation, or the code below
        // becomes quadratic in runtime.

        val targets: MutableSet<Target> = LinkedHashSet<Target>(resolvedTargetPatterns.get(pattern))

        validateScopeOfTargets(targets)

        val packages: MutableSet<PackageIdentifier?> =
            com.google.devtools.build.lib.collect.compacthashset.CompactHashSet.create<PackageIdentifier?>()
        for (target in targets) {
            packages.add(target.getLabel().getPackageIdentifier())
        }

        for (target in targets) {
            // This triggers node creation in the Digraph; getOrCreate(X) returns X.
            getOrCreate(target)

            // Preservation of graph order: it is important that targets obtained via
            // a wildcard such as p:* are correctly ordered w.r.t. each other, so to
            // ensure this, we add edges between any pair of directly connected
            // targets in this set.
            if (target is OutputFile) {
                if (targets.contains(target.getGeneratingRule())) {
                    makeEdge(target, target.getGeneratingRule())
                }
            } else if (target is Rule) {
                for (label in target.getSortedLabels(dependencyFilter)) {
                    if (!packages.contains(label.getPackageIdentifier())) {
                        continue  // don't cause additional package loading
                    }
                    try {
                        if (!validateScope(label, strictScope)) {
                            continue  // Don't create edges to targets which are out of scope.
                        }
                        val to = getTargetOrThrow(label)
                        if (targets.contains(to)) {
                            makeEdge(target, to)
                        }
                    } catch (e: NoSuchThingException) {
                        /* ignore */
                    }
                }
            }
        }
        callback.process(targets)
    }

    @Throws(
        TargetNotFoundException::class,
        com.google.devtools.build.lib.query2.engine.QueryException::class,
        java.lang.InterruptedException::class
    )
    override fun getTarget(label: Label?): Target? {
        // Can't use strictScope here because we are expecting a target back.
        validateScope(label, true)
        try {
            return getNode(getTargetOrThrow(label)).label
        } catch (e: NoSuchThingException) {
            throw TargetNotFoundException(e, e.getDetailedExitCode())
        }
    }

    private fun getNode(target: Target?): com.google.devtools.build.lib.graph.Node<Target?> {
        return graph.createNode(target)
    }

    private fun getNodes(target: Iterable<Target>): MutableCollection<com.google.devtools.build.lib.graph.Node<Target?>?> {
        val result: MutableSet<com.google.devtools.build.lib.graph.Node<Target?>?> =
            LinkedHashSet<com.google.devtools.build.lib.graph.Node<Target?>?>()
        for (t in target) {
            result.add(getNode(t))
        }
        return result
    }

    override fun getOrCreate(target: Target?): Target? {
        return getNode(target).label
    }

    override fun getFwdDeps(
        targets: Iterable<Target?>, context: QueryExpressionContext<Target?>?
    ): MutableCollection<Target?> {
        val result: ThreadSafeMutableSet<Target?> = createThreadSafeMutableSet()
        for (target in targets) {
            result.addAll(getTargetsFromNodes(getNode(target).successors))
        }
        return result
    }

    override fun getReverseDeps(
        targets: Iterable<Target?>, context: QueryExpressionContext<Target?>?
    ): MutableCollection<Target?> {
        val result: ThreadSafeMutableSet<Target?> = createThreadSafeMutableSet()
        for (target in targets) {
            result.addAll(getTargetsFromNodes(getNode(target).predecessors))
        }
        return result
    }

    override fun getTransitiveClosure(
        targetNodes: ThreadSafeMutableSet<Target>, context: QueryExpressionContext<Target?>?
    ): ThreadSafeMutableSet<Target?> {
        for (node in targetNodes) {
            checkBuilt(node)
        }
        return getTargetsFromNodes(graph.getFwdReachable(getNodes(targetNodes)))
    }

    /**
     * Checks that the graph rooted at 'targetNode' has been completely built; fails if not. Callers
     * of [.getTransitiveClosure] must ensure that [.buildTransitiveClosure] has been
     * called before.
     * 
     * 
     * It would be inefficient and failure-prone to make getTransitiveClosure call
     * buildTransitiveClosure directly. Also, it would cause nondeterministic behavior of the
     * operators, since the set of packages loaded (and hence errors reported) would depend on the
     * ordering details of the query operators' implementations.
     */
    private fun checkBuilt(targetNode: Target) {
        com.google.common.base.Preconditions.checkState(
            labelVisitor.hasVisited(targetNode.getLabel()),
            "getTransitiveClosure(%s) called without prior call to buildTransitiveClosure()",
            targetNode
        )
    }

    @Throws(com.google.devtools.build.lib.query2.engine.QueryException::class, java.lang.InterruptedException::class)
    override fun buildTransitiveClosure(
        caller: QueryExpression?, targetNodes: ThreadSafeMutableSet<Target?>, maxDepth: OptionalInt
    ) {
        Profiler.instance().profile("preloadTransitiveClosure").use { closeable ->
            preloadTransitiveClosure(targetNodes, maxDepth, caller)
        }
        Profiler.instance().profile("syncWithVisitor").use { closeable ->
            labelVisitor.syncWithVisitor(
                eventHandler,
                targetNodes,
                keepGoing,
                loadingPhaseThreads,
                maxDepth,
                GraphBuildingObserver()
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
    ): Iterable<Target?> {
        val builder: com.google.common.collect.ImmutableList.Builder<Target?> =
            com.google.common.collect.ImmutableList.builder<Target?>()
        for (node in graph.getShortestPath(getNode(from), getNode(to))) {
            builder.add(node.label)
        }
        return builder.build()
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
                return getNode(
                    FakeLoadTarget(bzlLabel, getBuildFileTarget(originalTarget).getPackageoid())
                ).label
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
                return getNode(
                    FakeLoadTarget(
                        Label.createUnvalidated(pkgIdOfBzlLabel, baseName),
                        getBuildFileTarget(originalTarget).getPackageoid()
                    )
                ).label
            }
        }

    @Throws(java.lang.InterruptedException::class, com.google.devtools.build.lib.query2.engine.QueryException::class)
    private fun preloadTransitiveClosure(
        targets: ThreadSafeMutableSet<Target?>, maxDepth: OptionalInt, callerForError: QueryExpression?
    ) {
        if (QueryEnvironment.Companion.shouldVisit(maxDepth, MAX_DEPTH_FULL_SCAN_LIMIT)
            && queryTransitivePackagePreloader != null
        ) {
            // Only do the full visitation if "maxDepth" is large enough. Otherwise, the benefits of
            // preloading will be outweighed by the cost of doing more work than necessary.
            val labels: MutableSet<Label?> = targets.stream().map<Any?>(Target::getLabel)
                .collect(com.google.common.collect.ImmutableSet.toImmutableSet<Any?>())
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

    /**
     * It suffices to synchronize the modifications of this.graph from within the
     * GraphBuildingObserver, because that's the only concurrent part. Concurrency is always
     * encapsulated within the evaluation of a single query operator (e.g. deps(), somepath(), etc).
     */
    private inner class GraphBuildingObserver : TargetEdgeObserver {
        public override fun edge(from: Target?, attribute: Attribute?, to: Target?) {
            com.google.common.base.Preconditions.checkState(
                attribute == null || dependencyFilter.test(from as Rule?, attribute),
                "Disallowed edge from LabelVisitor: %s --> %s",
                from,
                to
            )
            makeEdge(from, to)
            errorObserver.edge(from, attribute, to)
        }

        public override fun node(node: Target) {
            graph.createNode(node)
            errorObserver.node(node)
        }

        public override fun missingEdge(target: Target?, to: Label?, e: NoSuchThingException) {
            errorObserver.missingEdge(target, to, e)
        }
    }

    private fun makeEdge(from: Target?, to: Target?) {
        graph.addEdge(from, to)
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
                eventHandler, targetParser, patterns, keepGoing
            )
        )
    }

    /** Given a set of target nodes, returns the targets.  */
    private fun getTargetsFromNodes(input: Iterable<com.google.devtools.build.lib.graph.Node<Target?>>): ThreadSafeMutableSet<Target?> {
        val result: ThreadSafeMutableSet<Target?> = createThreadSafeMutableSet()
        for (node in input) {
            result.add(node.label)
        }
        return result
    }

    companion object {
        private const val MAX_DEPTH_FULL_SCAN_LIMIT = 20
    }
}
