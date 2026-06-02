// Copyright 2016 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.collect.nestedset.NestedSetVisitor

/**
 * Context object holding sufficient information for [SkyFunctionEnvironment] to perform its
 * duties. Shared among all [SkyFunctionEnvironment] instances, which should regard this
 * object as a read-only collection of data.
 * 
 * 
 * Also used during cycle detection.
 */
internal class ParallelEvaluatorContext(
    graph: QueryableGraph,
    graphVersion: com.google.devtools.build.skyframe.Version?,
    minimalVersion: com.google.devtools.build.skyframe.Version?,
    skyFunctions: com.google.common.collect.ImmutableMap<SkyFunctionName?, SkyFunction?>?,
    reporter: ExtendedEventHandler?,
    emittedEventState: EmittedEventState,
    progressReceiver: InflightTrackingProgressReceiver?,
    storedEventFilter: com.google.devtools.build.skyframe.EventFilter?,
    errorInfoManager: ErrorInfoManager?,
    graphInconsistencyReceiver: GraphInconsistencyReceiver?,
    executor: QuiescingExecutor?,
    visitorSupplier: com.google.common.base.Supplier<NodeEntryVisitor?>,
    stateCache: com.github.benmanes.caffeine.cache.Cache<SkyKey?, SkyKeyComputeState?>?,
    keepGoing: java.util.function.Predicate<SkyKey?>
) {
    private val graph: QueryableGraph
    private val graphVersion: com.google.devtools.build.skyframe.Version?
    private val minimalVersion: com.google.devtools.build.skyframe.Version?
    private val skyFunctions: com.google.common.collect.ImmutableMap<SkyFunctionName?, SkyFunction?>?
    private val reporter: ExtendedEventHandler?
    private val emittedEventState: EmittedEventState?
    private val replayingNestedSetEventVisitor: NestedSetVisitor<Reportable?>
    private val keepGoing: java.util.function.Predicate<SkyKey?>

    private val progressReceiver: InflightTrackingProgressReceiver
    private val storedEventFilter: com.google.devtools.build.skyframe.EventFilter?
    private val errorInfoManager: ErrorInfoManager?
    private val graphInconsistencyReceiver: GraphInconsistencyReceiver?
    private val executor: QuiescingExecutor?
    private val stateCache: com.github.benmanes.caffeine.cache.Cache<SkyKey?, SkyKeyComputeState?>?

    /**
     * The visitor managing the thread pool. Used to enqueue parents when an entry is finished, and,
     * during testing, to block until an exception is thrown if a node builder requests that.
     * Initialized after construction to avoid the overhead of the caller's creating a threadpool in
     * cases where it is not needed.
     */
    private val visitorSupplier: com.google.common.base.Supplier<NodeEntryVisitor?>

    /** * Returns a [Runnable] given a `key` to evaluate.  */
    internal interface RunnableMaker {
        fun make(key: SkyKey?): java.lang.Runnable?
    }

    init {
        this.graph = graph
        this.graphVersion = graphVersion
        this.minimalVersion = minimalVersion
        this.skyFunctions = skyFunctions
        this.reporter = reporter
        this.graphInconsistencyReceiver = graphInconsistencyReceiver
        this.emittedEventState = emittedEventState
        this.replayingNestedSetEventVisitor =
            NestedSetVisitor(
                NestedSetEventReceiver(reporter), emittedEventState.asVisitedState()
            )
        this.progressReceiver =
            com.google.common.base.Preconditions.checkNotNull<InflightTrackingProgressReceiver>(progressReceiver)
        this.storedEventFilter = storedEventFilter
        this.errorInfoManager = errorInfoManager
        this.executor = executor
        this.visitorSupplier = com.google.common.base.Suppliers.memoize<NodeEntryVisitor?>(visitorSupplier)
        this.stateCache = stateCache
        this.keepGoing = keepGoing
    }

    /**
     * Signals all parents that this node is finished.
     * 
     * 
     * Calling this method indicates that we are building this node after the main build aborted,
     * so skips signalling any parents that are already done (that can happen with cycles).
     */
    @Throws(java.lang.InterruptedException::class)
    fun signalParentsOnAbort(
        skyKey: SkyKey?,
        parents: MutableSet<SkyKey>,
        version: com.google.devtools.build.skyframe.Version?
    ) {
        val batch: NodeBatch =
            graph.getBatch(skyKey, com.google.devtools.build.skyframe.QueryableGraph.Reason.SIGNAL_DEP, parents)
        for (parent in parents) {
            val entry: NodeEntry =
                com.google.common.base.Preconditions.checkNotNull<NodeEntry>(batch.get(parent), parent)
            if (!entry.isDone()) { // In cycles, we can have parents that are already done.
                entry.signalDep(version, skyKey)
            }
        }
    }

    /** Signals all parents that this node is finished and enqueues any parents that are ready.  */
    @Throws(java.lang.InterruptedException::class)
    fun signalParentsAndEnqueueIfReady(
        skyKey: SkyKey?,
        parents: MutableSet<SkyKey>,
        version: com.google.devtools.build.skyframe.Version?
    ) {
        val batch: NodeBatch =
            graph.getBatch(skyKey, com.google.devtools.build.skyframe.QueryableGraph.Reason.SIGNAL_DEP, parents)
        for (parent in parents) {
            val entry: NodeEntry =
                com.google.common.base.Preconditions.checkNotNull<NodeEntry>(batch.get(parent), parent)
            val evaluationRequired: Boolean = entry.signalDep(version, skyKey)
            if (evaluationRequired || parent.supportsPartialReevaluation()) {
                getVisitor().enqueueEvaluation(parent, skyKey)
            }
        }
    }

    fun getGraph(): QueryableGraph {
        return graph
    }

    fun getGraphVersion(): com.google.devtools.build.skyframe.Version? {
        return graphVersion
    }

    fun getMinimalVersion(): com.google.devtools.build.skyframe.Version? {
        return minimalVersion
    }

    fun keepGoing(key: SkyKey?): Boolean {
        return keepGoing.test(key)
    }

    fun getVisitor(): NodeEntryVisitor? {
        return visitorSupplier.get()
    }

    fun getProgressReceiver(): InflightTrackingProgressReceiver {
        return progressReceiver
    }

    fun getGraphInconsistencyReceiver(): GraphInconsistencyReceiver? {
        return graphInconsistencyReceiver
    }

    fun getEmittedEventState(): EmittedEventState? {
        return emittedEventState
    }

    fun getReplayingNestedSetEventVisitor(): NestedSetVisitor<Reportable?> {
        return replayingNestedSetEventVisitor
    }

    fun getReporter(): ExtendedEventHandler? {
        return reporter
    }

    fun getSkyFunctions(): com.google.common.collect.ImmutableMap<SkyFunctionName?, SkyFunction?>? {
        return skyFunctions
    }

    fun getStoredEventFilter(): com.google.devtools.build.skyframe.EventFilter? {
        return storedEventFilter
    }

    fun getErrorInfoManager(): ErrorInfoManager? {
        return errorInfoManager
    }

    fun getExecutor(): QuiescingExecutor? {
        return executor
    }

    fun stateCache(): com.github.benmanes.caffeine.cache.Cache<SkyKey?, SkyKeyComputeState?>? {
        return stateCache
    }

    /** Receives the events from the NestedSet and delegates to the reporter.  */
    private class NestedSetEventReceiver
        (reporter: ExtendedEventHandler?) : NestedSetVisitor.Receiver<Reportable?> {
        private val reporter: ExtendedEventHandler?

        init {
            this.reporter = reporter
        }

        public override fun accept(event: Reportable) {
            event.reportTo(reporter)
        }
    }
}
