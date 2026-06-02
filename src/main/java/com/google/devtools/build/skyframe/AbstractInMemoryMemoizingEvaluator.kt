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
package com.google.devtools.build.skyframe

import com.google.devtools.build.lib.concurrent.AbstractQueueVisitor

/**
 * Partial implementation of [MemoizingEvaluator] with support for incremental and
 * non-incremental evaluations on an [InMemoryGraph].
 */
abstract class AbstractInMemoryMemoizingEvaluator protected constructor(
    skyFunctions: com.google.common.collect.ImmutableMap<SkyFunctionName?, SkyFunction?>?,
    differencer: Differencer?,
    progressReceiver: DirtyAndInflightTrackingProgressReceiver?,
    eventFilter: com.google.devtools.build.skyframe.EventFilter?,
    emittedEventState: EmittedEventState?,
    graphInconsistencyReceiver: GraphInconsistencyReceiver?,
    keepEdges: Boolean,
    minimalVersion: com.google.devtools.build.skyframe.Version?
) : MemoizingEvaluator {
    protected val skyFunctions: com.google.common.collect.ImmutableMap<SkyFunctionName?, SkyFunction?>? = null
    protected val progressReceiver: DirtyAndInflightTrackingProgressReceiver

    // State related to invalidation and deletion.
    private var valuesToDelete: MutableSet<SkyKey?> = LinkedHashSet<SkyKey?>()
    private var valuesToDirty: MutableSet<SkyKey?> = LinkedHashSet<SkyKey?>()
    private var valuesToInject: MutableMap<SkyKey?, Delta?> = HashMap<SkyKey?, Delta?>()
    private val deleterState: DeletingInvalidationState = DeletingInvalidationState()
    private val differencer: Differencer
    protected val graphInconsistencyReceiver: GraphInconsistencyReceiver?
    private val eventFilter: com.google.devtools.build.skyframe.EventFilter?

    /**
     * Whether to store edges in the graph. Can be false to save memory, in which case incremental
     * builds are not possible, and all evaluations will be at [Version.constant].
     */
    protected val keepEdges: Boolean

    private val minimalVersion: com.google.devtools.build.skyframe.Version?

    // Values that the caller explicitly specified are assumed to be changed -- they will be
    // re-evaluated even if none of their children are changed.
    private val invalidatorState: com.google.devtools.build.skyframe.InvalidatingNodeVisitor.InvalidationState =
        DirtyingInvalidationState()

    private val emittedEventState: EmittedEventState?

    // Null until the first incremental evaluation completes. Always null when not keeping edges.
    private var lastGraphVersion: IntVersion? = null

    private val evaluating: AtomicBoolean = AtomicBoolean(false)

    private var latestTopLevelEvaluations: MutableSet<SkyKey?> = HashSet<SkyKey?>()

    private var rememberTopLevelEvaluations = false

    init {
        TODO(
            """
            |Cannot convert element
            |With text:
            |this.skyFunctions = <ImmutableMap<SkyFunctionName, SkyFunction>>checkNotNull(skyFunctions);
            """.trimMargin()
        )
            .also {
                this.differencer = it
            }<Differencer> com . google . common . base . Preconditions . checkNotNull < Differencer ? > (differencer)
            .also {
                this.progressReceiver = it
            }<DirtyAndInflightTrackingProgressReceiver> com . google . common . base . Preconditions . checkNotNull < DirtyAndInflightTrackingProgressReceiver ? > (progressReceiver)
            .also {
                this.emittedEventState = it
            }<EmittedEventState> com . google . common . base . Preconditions . checkNotNull < EmittedEventState ? > (emittedEventState)
            .also {
                this.eventFilter = it
            }<EventFilter> com . google . common . base . Preconditions . checkNotNull < com . google . devtools . build . skyframe . EventFilter ? > (eventFilter)
            .also {
                this.graphInconsistencyReceiver = it
            }<GraphInconsistencyReceiver> com . google . common . base . Preconditions . checkNotNull < GraphInconsistencyReceiver ? > (graphInconsistencyReceiver)
        this.keepEdges = keepEdges
            .also {
                this.minimalVersion = it
            }<Version> com . google . common . base . Preconditions . checkNotNull < com . google . devtools . build . skyframe . Version ? > (minimalVersion)
    }

    @Throws(java.lang.InterruptedException::class)
    override fun <T : SkyValue?> evaluate(
        roots: Iterable<out SkyKey?>, evaluationContext: com.google.devtools.build.skyframe.EvaluationContext
    ): EvaluationResult<T?>? {
        // NOTE: Performance critical code. See bug "Null build performance parity".
        val graphVersion: com.google.devtools.build.skyframe.Version? = this.nextGraphVersion
        setAndCheckEvaluateState(true, roots)

        // Only remember roots for Skyfocus if we're tracking incremental states by keeping edges.
        if (keepEdges && rememberTopLevelEvaluations) {
            // Remember the top level evaluation of the build invocation for post-build consumption.
            com.google.common.collect.Iterables.addAll<SkyKey?>(latestTopLevelEvaluations, roots)
        }

        // Mark for removal any nodes from the previous evaluation that were still inflight or were
        // rewound but did not complete successfully. When the invalidator runs, it will delete the
        // reverse transitive closure.
        valuesToDelete.addAll(progressReceiver.getAndClearInflightKeys())
        valuesToDelete.addAll(progressReceiver.getAndClearUnsuccessfullyRewoundKeys())
        try {
            // The RecordingDifferencer implementation is not quite working as it should be at this point.
            // It clears the internal data structures after getDiff is called and will not return
            // diffs for historical versions. This makes the following code sensitive to interrupts.
            // Ideally we would simply not update lastGraphVersion if an interrupt occurs.
            val diff: com.google.devtools.build.skyframe.Differencer.Diff =
                differencer.getDiff(
                    DelegatingWalkableGraph(getInMemoryGraph()), lastGraphVersion, graphVersion
                )
            if (!diff.isEmpty() || !valuesToInject.isEmpty() || !valuesToDelete.isEmpty()) {
                valuesToInject.putAll(diff.changedKeysWithNewValues())
                invalidate(diff.changedKeysWithoutNewValues())
                pruneInjectedValues(valuesToInject)
                invalidate(valuesToInject.keySet())

                performInvalidation()
                injectValues(graphVersion)
            }
            val graph: ProcessableGraph? = getGraphForEvaluation(evaluationContext)

            val result: EvaluationResult<T?>
            Profiler.instance().profile("ParallelEvaluator.eval").use { c ->
                val evaluator: ParallelEvaluator =
                    ParallelEvaluator(
                        graph,
                        graphVersion,
                        minimalVersion,
                        skyFunctions,
                        evaluationContext.getEventHandler(),
                        emittedEventState,
                        eventFilter,
                        UseChildErrorInfoIfNecessary.Companion.INSTANCE,
                        progressReceiver,
                        graphInconsistencyReceiver,
                        evaluationContext
                            .getExecutor()
                            .orElseGet(
                                java.util.function.Supplier {
                                    AbstractQueueVisitor.create(
                                        "skyframe-evaluator-memoizing",
                                        evaluationContext.getParallelism(),
                                        ParallelEvaluatorErrorClassifier.Companion.instance()
                                    )
                                }),
                        if (evaluationContext.detectCycles())
                            SimpleCycleDetector(evaluationContext.storeExactCycles())
                        else
                            ShortCircuitingCycleDetector(evaluationContext.getParallelism()),
                        evaluationContext.getUnnecessaryTemporaryStateDropperReceiver(),
                        getKeepGoingPredicate(evaluationContext)
                    )
                result = evaluator.eval<T?>(roots)
            }
            return EvaluationResult.Companion.builder<T?>()
                .mergeFrom(result)
                .setWalkableGraph(DelegatingWalkableGraph(getInMemoryGraph()))
                .build()
        } finally {
            if (keepEdges) {
                lastGraphVersion = graphVersion as IntVersion?
            }
            setAndCheckEvaluateState(false, roots)
        }
    }

    /**
     * Returns whether a key should always be evaluated with --keep_going. By default, all keys should
     * respect the build level --keep_going flag.
     */
    @com.google.errorprone.annotations.ForOverride
    protected fun getKeepGoingPredicate(evaluationContext: com.google.devtools.build.skyframe.EvaluationContext): java.util.function.Predicate<SkyKey?> {
        return if (evaluationContext.getKeepGoing()) com.google.common.base.Predicates.alwaysTrue<SkyKey?>() else com.google.common.base.Predicates.alwaysFalse<SkyKey?>()
    }

    @com.google.errorprone.annotations.ForOverride
    @Throws(java.lang.InterruptedException::class)
    protected fun getGraphForEvaluation(evaluationContext: com.google.devtools.build.skyframe.EvaluationContext?): ProcessableGraph? {
        return getInMemoryGraph()
    }

    override fun delete(deletePredicate: java.util.function.BiPredicate<SkyKey?, SkyValue?>) {
        GoogleAutoProfilerUtils.logged("deletion marking", MIN_TIME_TO_LOG_DELETION).use { ignored ->
            val toDelete: MutableSet<SkyKey?> = com.google.common.collect.Sets.newConcurrentHashSet<SkyKey?>()
            getInMemoryGraph()
                .parallelForEach(
                    java.util.function.Consumer { e: InMemoryNodeEntry? ->
                        if (e.isDirty() || deletePredicate.test(e.getKey(), e.getValue())) {
                            toDelete.add(e.getKey())
                        }
                    })
            valuesToDelete.addAll(toDelete)
        }
    }

    private fun setAndCheckEvaluateState(newValue: Boolean, roots: Iterable<out SkyKey?>?) {
        com.google.common.base.Preconditions.checkState(
            evaluating.getAndSet(newValue) != newValue, "Re-entrant evaluation for request: %s", roots
        )
    }

    override fun rememberTopLevelEvaluations(remember: Boolean) {
        this.rememberTopLevelEvaluations = remember
    }

    override fun skyfocusSupported(): Boolean {
        return true
    }

    override fun deleteDirty(versionAgeLimit: Long) {
        com.google.common.base.Preconditions.checkArgument(versionAgeLimit >= 0, versionAgeLimit)
        val threshold: com.google.devtools.build.skyframe.Version? =
            IntVersion.Companion.of(lastGraphVersion.getVal() - versionAgeLimit)
        valuesToDelete.addAll(
            com.google.common.collect.Sets.filter<SkyKey?>(
                progressReceiver.getUnenqueuedDirtyKeys(),
                com.google.common.base.Predicate { skyKey: SkyKey? ->
                    val entry: NodeEntry = com.google.common.base.Preconditions.checkNotNull<InMemoryNodeEntry>(
                        getInMemoryGraph().getIfPresent(skyKey), skyKey
                    )
                    com.google.common.base.Preconditions.checkState(entry.isDirty(), skyKey)
                    entry.getVersion().atMost(threshold)
                })
        )
    }

    val values: MutableMap<SkyKey, SkyValue>?
        get() = getInMemoryGraph().getValues()

    val doneValues: MutableMap<SkyKey, SkyValue>?
        get() = getInMemoryGraph().getDoneValues()

    override fun getExistingValue(key: SkyKey?): SkyValue? {
        val entry: InMemoryNodeEntry? = getExistingEntryAtCurrentlyEvaluatingVersion(key)
        // Use toValue() to guard against the node being rewound after we check that it's done. Calling
        // getValue() in such a case would throw an exception, while toValue() returns the latest value.
        return if (isDone(entry)) entry.toValue() else null
    }

    override fun getExistingErrorForTesting(key: SkyKey?): com.google.devtools.build.skyframe.ErrorInfo? {
        val entry: InMemoryNodeEntry? = getExistingEntryAtCurrentlyEvaluatingVersion(key)
        return if (isDone(entry)) entry.getErrorInfo() else null
    }

    override fun getExistingEntryAtCurrentlyEvaluatingVersion(key: SkyKey?): InMemoryNodeEntry? {
        return getInMemoryGraph().getIfPresent(key)
    }

    override fun dumpSummary(out: PrintStream) {
        var nodes: Long = 0
        var edges: Long = 0
        for (entry in getInMemoryGraph().getAllNodeEntries()) {
            nodes++
            if (entry.isDone() && entry.keepsEdges()) {
                edges += com.google.common.collect.Iterables.size(entry.getDirectDeps()).toLong()
            }
        }
        out.println("Node count: " + nodes)
        out.println("Edge count: " + edges)
    }

    override fun dumpCount(out: PrintStream) {
        val counter: com.google.common.collect.Multiset<SkyFunctionName?> =
            com.google.common.collect.HashMultiset.create<SkyFunctionName?>()
        for (entry in getInMemoryGraph().getAllNodeEntries()) {
            counter.add(entry.getKey().functionName())
        }
        for (entry in com.google.common.collect.Multisets.copyHighestCountFirst<SkyFunctionName?>(counter).entrySet()) {
            out.println(entry.getElement().toString() + "\t" + entry.getCount()) // \t is spreadsheet-friendly.
        }
    }

    @Throws(java.lang.InterruptedException::class)
    private fun processGraphForDumpCommand(
        filter: java.util.function.Predicate<String?>,
        out: PrintStream,
        consumer: java.util.function.Consumer<InMemoryNodeEntry?>
    ) {
        for (entry in getInMemoryGraph().getAllNodeEntries()) {
            // This can be very long running on large graphs so check for user abort requests.
            if (java.lang.Thread.interrupted()) {
                out.println("aborting")
                throw java.lang.InterruptedException()
            }

            if (!filter.test(entry.getKey().getCanonicalName()) || !entry.isDone()) {
                continue
            }

            consumer.accept(entry)
        }
    }

    @Throws(java.lang.InterruptedException::class)
    override fun dumpValues(out: PrintStream, filter: java.util.function.Predicate<String?>) {
        processGraphForDumpCommand(
            filter,
            out,
            java.util.function.Consumer { entry: InMemoryNodeEntry? ->
                out.println(entry.getKey().getCanonicalName())
                entry.getValue().debugPrint(out)
                out.println()
            })
    }

    @Throws(java.lang.InterruptedException::class)
    override fun dumpDeps(out: PrintStream, filter: java.util.function.Predicate<String?>) {
        processGraphForDumpCommand(
            filter,
            out,
            java.util.function.Consumer { entry: InMemoryNodeEntry? ->
                val canonicalizedKey: String? = entry.getKey().getCanonicalName()
                out.println(canonicalizedKey)
                if (entry.keepsEdges()) {
                    val deps: GroupedDeps =
                        GroupedDeps.Companion.decompress(entry.getCompressedDirectDepsForDoneEntry())
                    for (i in 0..<deps.numGroups()) {
                        out.format("  Group %d:\n", i + 1)
                        for (dep in deps.getDepGroup(i)) {
                            out.print("    ")
                            out.println(dep.getCanonicalName())
                            out.println() // newline for readability
                        }
                    }
                } else {
                    out.println("  (direct deps not stored)")
                }
                out.println()
            })
    }

    @Throws(java.lang.InterruptedException::class)
    override fun dumpFunctionGraph(out: PrintStream, filter: java.util.function.Predicate<String?>) {
        val seen: com.google.common.collect.HashMultimap<SkyFunctionName?, SkyFunctionName?> =
            com.google.common.collect.HashMultimap.create<SkyFunctionName?, SkyFunctionName?>()
        out.println("digraph {")
        processGraphForDumpCommand(
            filter,
            out,
            java.util.function.Consumer { entry: InMemoryNodeEntry? ->
                if (entry.keepsEdges()) {
                    val source: SkyFunctionName? = entry.getKey().functionName()
                    for (dep in entry.getDirectDeps()) {
                        val dest: SkyFunctionName? = dep.functionName()
                        if (!seen.put(source, dest)) {
                            continue
                        }
                        out.format("  \"%s\" -> \"%s\"\n", source, dest)
                    }
                }
            })
        out.println("}")
    }

    @Throws(java.lang.InterruptedException::class)
    override fun dumpRdeps(out: PrintStream, filter: java.util.function.Predicate<String?>) {
        processGraphForDumpCommand(
            filter,
            out,
            java.util.function.Consumer { entry: InMemoryNodeEntry? ->
                out.println(entry.getKey().getCanonicalName())
                if (entry.keepsEdges()) {
                    val rdeps: MutableCollection<SkyKey> = entry.getReverseDepsForDoneEntry()
                    for (rdep in rdeps) {
                        out.print("    ")
                        out.println(rdep.getCanonicalName())
                        out.println()
                    }
                } else {
                    out.println("  (rdeps not stored)")
                }
                out.println()
            })
    }

    override fun cleanupInterningPools() {
        getInMemoryGraph().cleanupInterningPools()
    }

    private fun invalidate(diff: Iterable<SkyKey?>) {
        com.google.common.collect.Iterables.addAll<SkyKey?>(valuesToDirty, diff)
    }

    /**
     * Removes entries in `valuesToInject` whose values are equal to the present values in the
     * graph.
     */
    private fun pruneInjectedValues(valuesToInject: MutableMap<SkyKey?, Delta?>) {
        val it: MutableIterator<MutableMap.MutableEntry<SkyKey?, Delta?>> = valuesToInject.entrySet().iterator()
        while (it.hasNext()) {
            val entry: MutableMap.MutableEntry<SkyKey?, Delta?> = it.next()
            val key: SkyKey? = entry.getKey()
            val newValue: SkyValue = entry.getValue().newValue
            val prevEntry: InMemoryNodeEntry? = getInMemoryGraph().getIfPresent(key)
            val newMtsv: com.google.devtools.build.skyframe.Version? = entry.getValue().newMaxTransitiveSourceVersion
            if (isDone(prevEntry)) {
                val oldMtsv: com.google.devtools.build.skyframe.Version? = prevEntry.getMaxTransitiveSourceVersion()
                if (keepEdges) {
                    if (!prevEntry.hasAtLeastOneDep()) {
                        if (newValue == prevEntry.getValue()
                            && !valuesToDirty.contains(key) && !valuesToDelete.contains(key) && newMtsv == oldMtsv
                        ) {
                            it.remove()
                        }
                    } else {
                        // Rare situation of an injected dep that depends on another node. Usually the dep is
                        // the error transience node. When working with external repositories, it can also be
                        // an external workspace file. Don't bother injecting it, just invalidate it.
                        // We'll wastefully evaluate the node freshly during evaluation, but this happens very
                        // rarely.
                        valuesToDirty.add(key)
                        it.remove()
                    }
                } else {
                    // No incrementality. Just delete the old value from the graph. The new value is about to
                    // be injected.
                    getInMemoryGraph().remove(key)
                }
            }
        }
    }

    /** Injects values in `valuesToInject` into the graph.  */
    private fun injectValues(version: com.google.devtools.build.skyframe.Version?) {
        if (valuesToInject.isEmpty()) {
            return
        }
        try {
            ParallelEvaluator.Companion.injectValues(valuesToInject, version, getInMemoryGraph(), progressReceiver)
        } catch (e: java.lang.InterruptedException) {
            throw java.lang.IllegalStateException("InMemoryGraph doesn't throw interrupts", e)
        }
        // Start with a new map to avoid bloat since clear() does not downsize the map.
        valuesToInject = HashMap<SkyKey?, Delta?>()
    }

    @Throws(java.lang.InterruptedException::class)
    private fun performInvalidation() {
        EagerInvalidator.delete(
            getInMemoryGraph(), valuesToDelete, progressReceiver, deleterState, keepEdges
        )
        // Note that clearing the valuesToDelete would not do an internal resizing. Therefore, if any
        // build has a large set of dirty values, subsequent operations (even clearing) will be slower.
        // Instead, just start afresh with a new LinkedHashSet.
        valuesToDelete = LinkedHashSet<SkyKey?>()

        EagerInvalidator.invalidate(
            getInMemoryGraph(), valuesToDirty, progressReceiver, invalidatorState
        )
        // Ditto.
        valuesToDirty = LinkedHashSet<SkyKey?>()
    }

    private val nextGraphVersion: com.google.devtools.build.skyframe.Version?
        get() {
            if (!keepEdges) {
                return com.google.devtools.build.skyframe.Version.Companion.constant()
            } else if (lastGraphVersion == null) {
                return IntVersion.Companion.of(0)
            } else {
                return lastGraphVersion.next()
            }
        }

    fun getLatestTopLevelEvaluations(): MutableSet<SkyKey?> {
        return latestTopLevelEvaluations
    }

    override fun cleanupLatestTopLevelEvaluations() {
        latestTopLevelEvaluations = HashSet<SkyKey?>()
    }

    override fun postLoggingStats(eventHandler: ExtendedEventHandler) {
        val evaluationStats: EvaluationStats = progressReceiver.aggregateAndReset()
        eventHandler.post(
            SkyframeGraphStatsEvent(getInMemoryGraph().valuesSize(), evaluationStats)
        )
    }

    companion object {
        private val MIN_TIME_TO_LOG_DELETION: java.time.Duration? = java.time.Duration.ofMillis(10)

        private fun isDone(entry: NodeEntry?): Boolean {
            return entry != null && entry.isDone()
        }
    }
}
