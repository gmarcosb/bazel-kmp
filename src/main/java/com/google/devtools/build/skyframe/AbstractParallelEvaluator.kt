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
package com.google.devtools.build.skyframe

import com.google.devtools.build.lib.collect.nestedset.NestedSet

/**
 * Defines the evaluation action used in the multi-threaded Skyframe evaluation, and constructs the
 * [ParallelEvaluatorContext] that the actions rely on.
 * 
 * 
 * Evaluates a set of given functions (`SkyFunction`s) with arguments (`SkyKey`s).
 * Cycles are not allowed and are detected during the traversal.
 * 
 * 
 * This class implements multi-threaded evaluation. This is a fairly complex process that has
 * strong consistency requirements between the [ProcessableGraph], the nodes in the graph of
 * type [NodeEntry], the work queue, and the set of in-flight nodes.
 * 
 * 
 * The basic invariants are:
 * 
 * 
 * A node can be in one of three states: ready, waiting, and done. A node is ready if and only if
 * all of its dependencies have been signaled. A node is done if it has a value. It is waiting if
 * not all of its dependencies have been signaled.
 * 
 * 
 * A node must be in the work queue if and only if it is ready. It is an error for a node to be
 * in the work queue twice at the same time.
 * 
 * 
 * A node is considered in-flight if it has been created, and is not done yet. In case of an
 * interrupt, the work queue is discarded, and the in-flight set is used to remove partially
 * computed values.
 * 
 * 
 * Each evaluation of the graph takes place at a "version," which is currently given by a
 * non-negative `long`. The version can also be thought of as an "mtime." Each node in the
 * graph has a version, which is the last version at which its value changed. This version data is
 * used to avoid unnecessary re-evaluation of values. If a node is re-evaluated and found to have
 * the same data as before, its version (mtime) remains the same. If all of a node's children's have
 * the same version as before, its re-evaluation can be skipped.
 * 
 * 
 * This does not implement other parts of Skyframe evaluation setup and post-processing, such as
 * translating a set of requested top-level nodes into actions, or constructing an evaluation
 * result. Derived classes should do this.
 */
internal abstract class AbstractParallelEvaluator(
    graph: ProcessableGraph,
    graphVersion: com.google.devtools.build.skyframe.Version?,
    minimalVersion: com.google.devtools.build.skyframe.Version?,
    skyFunctions: com.google.common.collect.ImmutableMap<SkyFunctionName?, SkyFunction?>?,
    reporter: ExtendedEventHandler?,
    emittedEventState: EmittedEventState,
    storedEventFilter: com.google.devtools.build.skyframe.EventFilter?,
    errorInfoManager: ErrorInfoManager?,
    progressReceiver: InflightTrackingProgressReceiver?,
    graphInconsistencyReceiver: GraphInconsistencyReceiver?,
    executor: QuiescingExecutor?,
    cycleDetector: CycleDetector?,
    keepGoing: java.util.function.Predicate<SkyKey?>?
) {
    val graph: ProcessableGraph
    val evaluatorContext: ParallelEvaluatorContext
    val cycleDetector: CycleDetector?

    val stateCache: com.github.benmanes.caffeine.cache.Cache<SkyKey?, SkyKeyComputeState?> = Caffeine.newBuilder()
        .executor(java.util.concurrent.Executor { obj: java.lang.Runnable? -> obj.run() }) // run the removalListener immediately in the same thread
        .removalListener<SkyKey?, SkyKeyComputeState?>(com.github.benmanes.caffeine.cache.RemovalListener { k: SkyKey?, v: SkyKeyComputeState?, cause: com.github.benmanes.caffeine.cache.RemovalCause? -> v.close() })
        .build<SkyKey?, SkyKeyComputeState?>()

    init {
        this.graph = graph
        this.cycleDetector = cycleDetector
        this.evaluatorContext =
            ParallelEvaluatorContext(
                graph,
                graphVersion,
                minimalVersion,
                skyFunctions,
                reporter,
                emittedEventState,
                progressReceiver,
                storedEventFilter,
                errorInfoManager,
                graphInconsistencyReceiver,
                executor,
                com.google.common.base.Supplier {
                    NodeEntryVisitor(
                        executor,
                        progressReceiver,
                        RunnableMaker { skyKey: SkyKey? -> Evaluate(skyKey) },
                        stateCache
                    )
                },
                stateCache,
                keepGoing
            )
    }

    internal enum class DirtyOutcome {
        ALREADY_PROCESSED,
        NEEDS_EVALUATION
    }

    /** * An action that evaluates a value.  */
    private inner class Evaluate(skyKey: SkyKey) : java.lang.Runnable {
        private val skyKey: SkyKey

        /**
         * Notes the rdep from the parent to the child, and then does the appropriate thing with the
         * child or the parent, returning whether the parent has both been signalled and also is ready
         * for evaluation.
         */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        @Throws(java.lang.InterruptedException::class)
        fun enqueueChild(
            skyKey: SkyKey,
            entry: NodeEntry,
            child: SkyKey,
            childEntry: NodeEntry,
            depAlreadyExists: Boolean,
            enqueueParentIfReady: Boolean,
            environmentIfEnqueuing: SkyFunctionEnvironment?
        ): Boolean {
            com.google.common.base.Preconditions.checkState(!entry.isDone(), "%s %s", skyKey, entry)
            val dependencyState: DependencyState
            try {
                dependencyState =
                    if (depAlreadyExists)
                        childEntry.checkIfDoneForDirtyReverseDep(skyKey)
                    else
                        childEntry.addReverseDepAndCheckIfDone(skyKey)
            } catch (e: java.lang.IllegalStateException) {
                // Add some more context regarding crashes.
                throw java.lang.IllegalStateException("child key: " + child + " error: " + e.getMessage(), e)
            }
            when (dependencyState) {
                DependencyState.DONE -> if (entry.signalDep(childEntry.getVersion(), child)) {
                    if (enqueueParentIfReady) {
                        evaluatorContext.getVisitor().enqueueEvaluation(skyKey, child)
                    }
                    return true
                } else {
                    if (skyKey.supportsPartialReevaluation()
                        && environmentIfEnqueuing != null && environmentIfEnqueuing.wasNewlyRequestedDepNullForPartialReevaluation(
                            child
                        )
                    ) {
                        // If a dep was observed not-done by its parent when the parent tried to read its
                        // value, but that dep is now done, then this is the only chance the parent has to be
                        // signalled by that dep.
                        evaluatorContext.getVisitor().enqueueEvaluation(skyKey, child)
                    }
                }

                DependencyState.ALREADY_EVALUATING -> {}
                DependencyState.NEEDS_SCHEDULING -> evaluatorContext.getVisitor().enqueueEvaluation(child, null)
            }
            return false
        }

        /**
         * Returns true if this depGroup consists of the error transience value and the error transience
         * value is newer than the entry, meaning that the entry must be re-evaluated.
         */
        @Throws(java.lang.InterruptedException::class)
        fun invalidatedByErrorTransience(depGroup: MutableCollection<SkyKey>, entry: NodeEntry): Boolean {
            return depGroup.size() == 1 && depGroup.contains(ErrorTransienceValue.Companion.KEY)
                    && !graph
                .get(
                    null,
                    com.google.devtools.build.skyframe.QueryableGraph.Reason.OTHER,
                    ErrorTransienceValue.Companion.KEY
                )
                .getVersion()
                .atMost(entry.getVersion())
        }

        @Throws(java.lang.InterruptedException::class)
        fun maybeHandleDirtyNode(nodeEntry: NodeEntry): DirtyOutcome {
            while (nodeEntry.getLifecycleState() == LifecycleState.CHECK_DEPENDENCIES) {
                // Evaluating a dirty node for the first time, and checking its children to see if any
                // of them have changed. Note that there must be dirty children for this to happen.

                // Check the children group by group -- we don't want to evaluate a value that is no
                // longer needed because an earlier dependency changed. For example, //foo:foo depends
                // on target //bar:bar and is built. Then foo/BUILD is modified to remove the dependence
                // on bar, and bar/BUILD is deleted. Reloading //bar:bar would incorrectly throw an
                // exception. To avoid this, we must reload foo/BUILD first, at which point we will
                // discover that it has changed, and re-evaluate target //foo:foo from scratch.
                // On the other hand, when an action requests all of its inputs, we can safely check all
                // of them in parallel on a subsequent build. So we allow checking an entire group in
                // parallel here, if the node builder requested a group last build.
                // Note: every dep returned here must either have this node re-registered for it (using
                // checkIfDoneForDirtyReverseDep) and be registered as a direct dep of this node, or have
                // its reverse dep on this node removed. Failing to do either one of these would result in
                // a graph inconsistency, where the child had a reverse dep on this node, but this node
                // had no kind of dependency on the child.

                val directDepsToCheck: MutableList<SkyKey> = nodeEntry.getNextDirtyDirectDeps()

                if (invalidatedByErrorTransience(directDepsToCheck, nodeEntry)) {
                    // If this dep is the ErrorTransienceValue and the ErrorTransienceValue has been
                    // updated then we need to force a rebuild. We would like to just signal the entry as
                    // usual, but we can't, because then the ErrorTransienceValue would remain as a dep,
                    // which would be incorrect if, for instance, the value re-evaluated to a non-error.
                    nodeEntry.forceRebuild()
                    graph.get(
                        skyKey,
                        com.google.devtools.build.skyframe.QueryableGraph.Reason.RDEP_REMOVAL,
                        ErrorTransienceValue.Companion.KEY
                    ).removeReverseDep(skyKey)
                    return DirtyOutcome.NEEDS_EVALUATION
                }
                var entriesToCheck: NodeBatch? = null
                if (!evaluatorContext.keepGoing(skyKey)) {
                    // This check ensures that we maintain the invariant that if a node with an error is
                    // reached during a no-keep-going build, none of its currently building parents
                    // finishes building. If the child isn't done building yet, it will detect on its own
                    // that it has an error (see the VERIFIED_CLEAN case below). On the other hand, if it
                    // is done, then it is the parent's responsibility to notice that, which we do here.
                    // We check the deps for errors so that we don't continue building this node if it has
                    // a child error.
                    entriesToCheck = graph.getBatch(
                        skyKey,
                        com.google.devtools.build.skyframe.QueryableGraph.Reason.OTHER,
                        directDepsToCheck
                    )
                    for (keyToCheck in directDepsToCheck) {
                        val nodeEntryToCheck: NodeEntry? = entriesToCheck.get(keyToCheck)
                        val valueMaybeWithMetadata: SkyValue? = nodeEntryToCheck.getValueMaybeWithMetadata()
                        if (valueMaybeWithMetadata == null) {
                            continue
                        }
                        val maybeErrorInfo: com.google.devtools.build.skyframe.ErrorInfo? =
                            ValueWithMetadata.Companion.getMaybeErrorInfo(valueMaybeWithMetadata)
                        if (maybeErrorInfo == null) {
                            continue
                        }
                        // This child has an error. We add a dep from this node to it and throw an exception
                        // coming from it.
                        nodeEntry.addSingletonTemporaryDirectDep(keyToCheck)
                        nodeEntryToCheck.checkIfDoneForDirtyReverseDep(skyKey)
                        // Perform the necessary bookkeeping for any deps that are not being used.
                        for (depKey in directDepsToCheck) {
                            if (depKey != keyToCheck) {
                                entriesToCheck.get(depKey).removeReverseDep(skyKey)
                            }
                        }
                        if (!evaluatorContext.getVisitor().preventNewEvaluations()) {
                            // An error was already thrown in the evaluator. Don't do anything here.
                            return DirtyOutcome.ALREADY_PROCESSED
                        }
                        throw SchedulerException.Companion.ofError(
                            maybeErrorInfo,
                            keyToCheck,
                            com.google.common.collect.ImmutableSet.of<SkyKey?>(skyKey)
                        )
                    }
                }
                // It is safe to add these deps back to the node -- even if one of them has changed, the
                // contract of pruning is that the node will request these deps again when it rebuilds.
                // We must add these deps before enqueuing them, so that the node knows that it depends
                // on them. If one of these deps is the error transience node, the check we did above
                // in #invalidatedByErrorTransience means that the error transience node is not newer
                // than this node, so we are going to mark it clean (since the error transience node is
                // always the last dep).
                nodeEntry.addTemporaryDirectDepGroup(directDepsToCheck)
                val depsReport: DepsReport = graph.analyzeDepsDoneness(skyKey, directDepsToCheck)
                val unknownStatusDeps: MutableCollection<SkyKey> =
                    if (depsReport.hasInformation()) depsReport else directDepsToCheck
                var needsScheduling = false
                for (i in 0..<directDepsToCheck.size() - unknownStatusDeps.size()) {
                    // Since all of these nodes were done at an earlier version than this one, we may safely
                    // signal with the minimal version, since they cannot trigger a re-evaluation.
                    needsScheduling = nodeEntry.signalDep(
                        com.google.devtools.build.skyframe.Version.Companion.minimal(),  /* childForDebugging= */
                        null
                    )
                }
                if (needsScheduling) {
                    com.google.common.base.Preconditions.checkState(
                        unknownStatusDeps.isEmpty(),
                        "Ready without all deps checked? %s %s %s",
                        skyKey,
                        nodeEntry,
                        unknownStatusDeps
                    )
                    continue
                }
                if (entriesToCheck == null || depsReport.hasInformation()) {
                    entriesToCheck = graph.getBatch(
                        skyKey,
                        com.google.devtools.build.skyframe.QueryableGraph.Reason.ENQUEUING_CHILD,
                        unknownStatusDeps
                    )
                }
                val parentIsSignalledAndReady =
                    handleKnownChildrenForDirtyNode(
                        unknownStatusDeps,
                        entriesToCheck,
                        nodeEntry,  /* enqueueParentIfReady= */
                        false,  /* environmentIfEnqueuing= */
                        null
                    )
                if (!parentIsSignalledAndReady
                    || evaluatorContext.getVisitor().shouldPreventNewEvaluations()
                ) {
                    return DirtyOutcome.ALREADY_PROCESSED
                }
                // If we're here, then we may proceed to the rest of the method and continue processing
                // the node intra-thread. This is a performance optimization: By not enqueuing the node,
                // we avoid contention on the queue data structure (between concurrent threads
                // enqueueing and dequeueing), and we also save wall time since the node gets processed
                // now rather than at some point in the future.
            }
            when (nodeEntry.getLifecycleState()) {
                LifecycleState.VERIFIED_CLEAN -> {
                    // No child has a changed value. This node can be marked done and its parents signaled
                    // without any re-evaluation.
                    val nodeValueAndRdeps: NodeValueAndRdepsToSignal = nodeEntry.markClean()
                    val rDepsToSignal: MutableSet<SkyKey?>? = nodeValueAndRdeps.getRdepsToSignal()
                    val valueMaybeWithMetadata: SkyValue? = nodeValueAndRdeps.getValue()
                    // Replay events once change-pruned.
                    replay(ValueWithMetadata.Companion.getEvents(valueMaybeWithMetadata))
                    // Tell the receiver that the value was not actually changed this run.
                    evaluatorContext
                        .getProgressReceiver()
                        .evaluated(
                            skyKey,
                            EvaluationState.Companion.get(
                                valueMaybeWithMetadata,  /* versionChanged= */
                                false
                            ),  /* newValue= */
                            null,  /* newError= */
                            null,  /* directDeps= */
                            null
                        )
                    if (!evaluatorContext.keepGoing(skyKey) && nodeEntry.getErrorInfo() != null) {
                        if (!evaluatorContext.getVisitor().preventNewEvaluations()) {
                            return DirtyOutcome.ALREADY_PROCESSED
                        }
                        throw SchedulerException.Companion.ofError(nodeEntry.getErrorInfo(), skyKey, rDepsToSignal)
                    }
                    evaluatorContext.signalParentsAndEnqueueIfReady(
                        skyKey, rDepsToSignal, nodeEntry.getVersion()
                    )
                    return DirtyOutcome.ALREADY_PROCESSED
                }

                LifecycleState.NEEDS_REBUILDING -> {
                    nodeEntry.markRebuilding()
                    return DirtyOutcome.NEEDS_EVALUATION
                }

                LifecycleState.REBUILDING -> return DirtyOutcome.NEEDS_EVALUATION
                else -> throw java.lang.IllegalStateException("key: " + skyKey + ", entry: " + nodeEntry)
            }
        }

        /** Returns whether the parent has both been signalled and also is ready for evaluation.  */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        @Throws(java.lang.InterruptedException::class)
        fun handleKnownChildrenForDirtyNode(
            knownChildren: MutableCollection<SkyKey>,
            oldChildren: NodeBatch,
            nodeEntry: NodeEntry,
            enqueueParentIfReady: Boolean,
            environmentIfEnqueuing: SkyFunctionEnvironment?
        ): Boolean {
            var parentIsSignalledAndReady = false
            for (directDep in knownChildren) {
                val directDepEntry: NodeEntry =
                    com.google.common.base.Preconditions.checkNotNull<NodeEntry>(
                        oldChildren.get(directDep),
                        "Dirty parent had missing child (child=%s, parent=%s %s)",
                        directDep,
                        skyKey,
                        nodeEntry
                    )
                parentIsSignalledAndReady = parentIsSignalledAndReady or
                        enqueueChild(
                            skyKey,
                            nodeEntry,
                            directDep,
                            directDepEntry,  /* depAlreadyExists= */
                            true,
                            enqueueParentIfReady,
                            environmentIfEnqueuing
                        )
            }
            return parentIsSignalledAndReady
        }

        override fun run() {
            var env: SkyFunctionEnvironment? = null
            try {
                val nodeEntry: NodeEntry? =
                    graph.get(null, com.google.devtools.build.skyframe.QueryableGraph.Reason.EVALUATION, skyKey)
                if (nodeEntry == null || !nodeEntry.isReadyToEvaluate()) {
                    com.google.common.base.Preconditions.checkState(
                        skyKey.supportsPartialReevaluation(),
                        "%s %s",
                        skyKey,
                        nodeEntry
                    )
                    evaluatorContext.getProgressReceiver().removeFromInflight(skyKey)
                    return
                }
                try {
                    evaluatorContext.getProgressReceiver().stateStarting(skyKey, NodeState.CHECK_DIRTY)
                    if (maybeHandleDirtyNode(nodeEntry) == DirtyOutcome.ALREADY_PROCESSED) {
                        if (nodeEntry.getLifecycleState() == LifecycleState.DONE) {
                            evaluatorContext.getProgressReceiver().changePruned(skyKey)
                        }
                        return
                    }
                } finally {
                    evaluatorContext.getProgressReceiver().stateEnding(skyKey, NodeState.CHECK_DIRTY)
                }

                val oldDeps: com.google.common.collect.ImmutableSet<SkyKey?> =
                    nodeEntry.getAllRemainingDirtyDirectDeps()
                try {
                    evaluatorContext
                        .getProgressReceiver()
                        .stateStarting(skyKey, NodeState.INITIALIZING_ENVIRONMENT)
                    env =
                        SkyFunctionEnvironment.Companion.create(
                            skyKey,
                            nodeEntry.getTemporaryDirectDeps(),
                            oldDeps,
                            nodeEntry.getMaxTransitiveSourceVersion(),
                            evaluatorContext
                        )
                } catch (undonePreviouslyRequestedDeps: UndonePreviouslyRequestedDeps) {
                    handleUndonePreviouslyRequestedDep(nodeEntry)
                    return
                } finally {
                    evaluatorContext
                        .getProgressReceiver()
                        .stateEnding(skyKey, NodeState.INITIALIZING_ENVIRONMENT)
                }
                val functionName: SkyFunctionName? = skyKey.functionName()
                val skyFunction: SkyFunction =
                    com.google.common.base.Preconditions.checkNotNull<SkyFunction>(
                        evaluatorContext.getSkyFunctions().get(functionName),
                        "Unable to find SkyFunction '%s' for node with key %s, %s",
                        functionName,
                        skyKey,
                        nodeEntry
                    )

                var value: SkyValue? = null
                try {
                    Profiler.instance()
                        .profile(ProfilerTask.SKYFUNCTION, skyKey.functionName().getName()).use { s ->
                            try {
                                evaluatorContext.getProgressReceiver().stateStarting(skyKey, NodeState.COMPUTE)
                                value = skyFunction.compute(skyKey, env)
                            } finally {
                                evaluatorContext.getProgressReceiver().stateEnding(skyKey, NodeState.COMPUTE)
                            }
                        }
                } catch (builderException: SkyFunctionException) {
                    // TODO(b/261604460): invalidating the state cache here appears to be load-bearing for
                    // error propagation. It ought to be allowed to invalidate it only after the following
                    // early return checks pass, but something is misusing the state cache, and moving it
                    // causes tests to fail.
                    stateCache.invalidate(skyKey)

                    // In keep-going mode, we do not let SkyFunctions complete with a thrown error if they
                    // have missing deps. Instead, we wait until their deps are done and restart the
                    // SkyFunction, so we can have a definitive error and definitive graph structure, thus
                    // avoiding non-determinism. It's completely reasonable for SkyFunctions to throw eagerly
                    // because they do not know if they are in keep-going mode.
                    if (!evaluatorContext.keepGoing(skyKey) || !env.valuesMissing()) {
                        if (nodeEntry.hasUnsignaledDeps()) {
                            // This is a partial reevaluation. It is not safe to set the error because a dep may
                            // yet signal this node. We return (without preventing new evaluations) so that any
                            // not-yet-complete deps can complete and signal this node.
                            return
                        }

                        if (maybeHandleRegisteringNewlyDiscoveredDepsForDoneEntry(
                                skyKey, nodeEntry, oldDeps, env, evaluatorContext.keepGoing(skyKey)
                            )
                        ) {
                            // A newly requested dep transitioned from done to dirty before this node finished.
                            // It is not safe to set the error because the now-dirty dep has not signaled this
                            // node. We return (without preventing new evaluations) so that the dep can complete
                            // and signal this node.
                            return
                        }

                        try {
                            env.ensurePreviouslyRequestedDepsFetched()
                        } catch (e: UndonePreviouslyRequestedDeps) {
                            handleUndonePreviouslyRequestedDep(nodeEntry)
                            return
                        }

                        val shouldFailFast =
                            !evaluatorContext.keepGoing(skyKey) || builderException.isCatastrophic()
                        if (shouldFailFast) {
                            // After we commit this error to the graph but before the doMutatingEvaluation call
                            // completes with the error there is a race-like opportunity for the error to be used,
                            // either by an in-flight computation or by a future computation.
                            if (!evaluatorContext.getVisitor().preventNewEvaluations()) {
                                // This is not the first error encountered, so we ignore it so that we can terminate
                                // with the first error.
                                return
                            } else {
                                logger.atWarning().withCause(builderException).log(
                                    "Aborting evaluation while evaluating %s", skyKey
                                )
                            }
                        }
                        val reifiedBuilderException: ReifiedSkyFunctionException =
                            ReifiedSkyFunctionException(builderException)
                        val isTransitivelyTransient =
                            reifiedBuilderException.isTransient()
                                    || env.isAnyDirectDepErrorTransitivelyTransient()
                                    || env.isAnyNewlyRequestedDepErrorTransitivelyTransient()
                        val errorInfo: com.google.devtools.build.skyframe.ErrorInfo? =
                            evaluatorContext
                                .getErrorInfoManager()
                                .fromException(skyKey, reifiedBuilderException, isTransitivelyTransient)
                        env.setError(nodeEntry, errorInfo)
                        val rdepsToBubbleUpTo: MutableSet<SkyKey?>? =
                            env.commitAndGetParents(nodeEntry,  /* expectDoneDeps= */true)
                        if (shouldFailFast) {
                            evaluatorContext.signalParentsOnAbort(
                                skyKey, rdepsToBubbleUpTo, nodeEntry.getVersion()
                            )
                            throw SchedulerException.Companion.ofError(errorInfo, skyKey, rdepsToBubbleUpTo)
                        }
                        evaluatorContext.signalParentsAndEnqueueIfReady(
                            skyKey, rdepsToBubbleUpTo, nodeEntry.getVersion()
                        )
                        return
                    }
                } catch (re: java.lang.RuntimeException) {
                    // Programmer error (most likely NPE or a failed precondition in a SkyFunction). Output
                    // some context together with the exception.
                    val msg = prepareCrashMessage(skyKey, nodeEntry.getInProgressReverseDeps())
                    val ex: java.lang.RuntimeException = java.lang.RuntimeException(msg, re)
                    evaluatorContext.getVisitor().noteCrash(ex)
                    throw ex
                } finally {
                    env.doneBuilding()
                }

                // For any `SkyKey`s, regardless of partially evaluated or not, the node's Max Transitive
                // Source Version so far is always tracked at the end of a Skyframe restart.
                // This effort makes it meaningless to fetch MTSV of all deps during
                // INITIALIZE_ENVIRONMENT's batch prefetch, and resolves a blocker to remove batch prefetch
                // from INITIALIZE_ENVIRONMENT. Also, `SkyFunctionEnvironment#PartialEvaluation` subclass
                // starts to support `getMaxTransitiveSourceVersionSoFar()` method.
                // TODO(b/324948927): This comment should be rephrased when batch prefetch is removed from
                // INITIALIZE_ENVIRONMENT PHASE.
                nodeEntry.setTemporaryMaxTransitiveSourceVersion(env.getMaxTransitiveSourceVersionSoFar())

                if (value is Reset) {
                    if (nodeEntry.hasUnsignaledDeps()) {
                        // This is a partial reevaluation. It is not safe to reset the node because a dep may
                        // be racing to signal it.
                        return
                    }
                    dirtyRewindGraphAndResetEntry(skyKey, nodeEntry, value as Reset)
                    stateCache.invalidate(skyKey)
                    cancelExternalDeps(env)
                    evaluatorContext.getVisitor().enqueueEvaluation(skyKey, null)
                    return
                }

                val newDeps: MutableSet<SkyKey> = env.getNewlyRequestedDeps()
                if (value != null) {
                    if (nodeEntry.hasUnsignaledDeps()) {
                        // This is a partial reevaluation. It is not safe to set the value because a dep may be
                        // racing to signal this node.
                        return
                    }

                    try {
                        env.ensurePreviouslyRequestedDepsFetched()
                    } catch (e: UndonePreviouslyRequestedDeps) {
                        handleUndonePreviouslyRequestedDep(nodeEntry)
                        return
                    }

                    com.google.common.base.Preconditions.checkState(
                        !env.valuesMissing(),
                        "Evaluation of %s returned non-null value but requested dependencies that weren't "
                                + "computed yet (one of %s), NodeEntry: %s",
                        skyKey,
                        newDeps,
                        nodeEntry
                    )

                    stateCache.invalidate(skyKey)
                    try {
                        evaluatorContext.getProgressReceiver().stateStarting(skyKey, NodeState.COMMIT)
                        if (maybeHandleRegisteringNewlyDiscoveredDepsForDoneEntry(
                                skyKey, nodeEntry, oldDeps, env, evaluatorContext.keepGoing(skyKey)
                            )
                        ) {
                            // A newly requested dep transitioned from done to dirty before this node finished.
                            // This node will be signalled again, and so we should return.
                            return
                        }
                        env.setValue(value)
                        val reverseDeps: MutableSet<SkyKey?>? =
                            env.commitAndGetParents(nodeEntry,  /* expectDoneDeps= */true)
                        evaluatorContext.signalParentsAndEnqueueIfReady(
                            skyKey, reverseDeps, nodeEntry.getVersion()
                        )
                    } finally {
                        evaluatorContext.getProgressReceiver().stateEnding(skyKey, NodeState.COMMIT)
                    }
                    return
                }

                val childErrorKey: SkyKey? = env.getDepErrorKey()
                if (childErrorKey != null) {
                    com.google.common.base.Preconditions.checkState(
                        !evaluatorContext.keepGoing(skyKey), "%s %s %s", skyKey, nodeEntry, childErrorKey
                    )
                    // We encountered a child error in noKeepGoing mode, so we want to fail fast. But we first
                    // need to add the edge between the current node and the child error it requested so that
                    // error bubbling can occur. Note that this edge will subsequently be removed during graph
                    // cleaning (since the current node will never be committed to the graph).
                    val childErrorEntry: NodeEntry =
                        com.google.common.base.Preconditions.checkNotNull<NodeEntry>(
                            graph.get(
                                skyKey,
                                com.google.devtools.build.skyframe.QueryableGraph.Reason.OTHER,
                                childErrorKey
                            ),
                            "skyKey: %s, nodeEntry: %s childErrorKey: %s",
                            skyKey,
                            nodeEntry,
                            childErrorKey
                        )
                    if (newDeps.contains(childErrorKey)) {
                        // Add this dep if it was just requested. In certain rare race conditions (see
                        // MemoizingEvaluatorTest.cachedErrorCausesRestart) this dep may have already been
                        // requested.
                        nodeEntry.addSingletonTemporaryDirectDep(childErrorKey)
                        val childErrorState: DependencyState?
                        if (oldDeps.contains(childErrorKey)) {
                            childErrorState = childErrorEntry.checkIfDoneForDirtyReverseDep(skyKey)
                        } else {
                            childErrorState = childErrorEntry.addReverseDepAndCheckIfDone(skyKey)
                        }
                        if (childErrorState != DependencyState.DONE) {
                            // The child in error may have transitioned from done to dirty between when this node
                            // discovered the error and now. Notify the graph inconsistency receiver so that we
                            // can crash if that's unexpected.
                            // We don't enqueue the child, even if it returns NEEDS_SCHEDULING, because we are
                            // about to shut down evaluation.
                            evaluatorContext
                                .getGraphInconsistencyReceiver()
                                .noteInconsistencyAndMaybeThrow(
                                    skyKey,
                                    com.google.common.collect.ImmutableList.of<SkyKey?>(childErrorKey),
                                    Inconsistency.BUILDING_PARENT_FOUND_UNDONE_CHILD
                                )
                        }
                    }
                    val childErrorInfoMaybe: SkyValue =
                        com.google.common.base.Preconditions.checkNotNull<SkyValue>(
                            env.maybeGetValueFromErrorOrDeps(childErrorKey),
                            "dep error found but then lost while building: %s %s",
                            skyKey,
                            childErrorKey
                        )
                    val childErrorInfo: com.google.devtools.build.skyframe.ErrorInfo =
                        com.google.common.base.Preconditions.checkNotNull<com.google.devtools.build.skyframe.ErrorInfo>(
                            ValueWithMetadata.Companion.getMaybeErrorInfo(childErrorInfoMaybe),
                            "dep error found but then wasn't an error while building: %s %s %s",
                            skyKey,
                            childErrorKey,
                            childErrorInfoMaybe
                        )
                    evaluatorContext.getVisitor().preventNewEvaluations()
                    // TODO(b/166268889): Remove when fixed.
                    if (childErrorInfo.getException() is IOException) {
                        logger.atInfo().withCause(childErrorInfo.getException()).log(
                            "Child %s with IOException forced abort of %s", childErrorKey, skyKey
                        )
                    }
                    throw SchedulerException.Companion.ofError(
                        childErrorInfo,
                        childErrorKey,
                        com.google.common.collect.ImmutableSet.of<SkyKey?>(skyKey)
                    )
                }

                // TODO(bazel-team): This code is not safe to interrupt, because we would lose the state in
                // newDirectDeps.

                // TODO(bazel-team): An ill-behaved SkyFunction can throw us into an infinite loop where we
                // add more dependencies on every run. [skyframe-core]

                // Add all the newly requested dependencies to the temporary direct deps. Note that
                // newDirectDeps does not contain any elements in common with the already existing temporary
                // direct deps. uniqueNewDeps will be the set of unique keys contained in newDirectDeps.
                env.addTemporaryDirectDepsTo(nodeEntry)

                val externalDeps: MutableList<com.google.common.util.concurrent.ListenableFuture<*>>? = env.externalDeps
                // If the key does not support partial reevaluation and there were no newly requested
                // dependencies, then at least one of them was in error or there is a bug in the SkyFunction
                // implementation. The environment has collected its errors, so we just order it to be
                // built.
                if (newDeps.isEmpty() && externalDeps == null && !skyKey.supportsPartialReevaluation()) {
                    com.google.common.base.Preconditions.checkState(
                        !env.getChildErrorInfos().isEmpty(),
                        "Evaluation of SkyKey failed and no dependencies were requested: %s %s",
                        skyKey,
                        nodeEntry
                    )

                    // If the child error was catastrophic, committing this parent to the graph is not
                    // necessary, but since we don't do error bubbling in catastrophes, it doesn't violate any
                    // invariants either.
                    val reverseDeps: MutableSet<SkyKey?>? =
                        env.commitAndGetParents(nodeEntry,  /* expectDoneDeps= */true)
                    evaluatorContext.signalParentsAndEnqueueIfReady(
                        skyKey, reverseDeps, nodeEntry.getVersion()
                    )
                    return
                }

                // If there are external deps, we register that fact on the NodeEntry before we enqueue
                // child nodes in order to prevent the current node from being re-enqueued between here and
                // the call to registerExternalDeps below.
                if (externalDeps != null) {
                    nodeEntry.addExternalDep()
                }

                // We want to split apart the dependencies that existed for this node the last time we did
                // an evaluation and those that were introduced in this evaluation. To be clear, the prefix
                // "newDeps" refers to newly discovered this time around after a SkyFunction#compute call
                // and not to be confused with the oldDeps variable which refers to the last evaluation,
                // i.e. a prior call to ParallelEvaluator#eval.
                val newDepsThatWerentInTheLastEvaluation: MutableCollection<SkyKey>?
                val newDepsThatWereInTheLastEvaluation: com.google.common.collect.ImmutableList<SkyKey>?
                if (oldDeps.isEmpty()) {
                    // When there are no old deps (clean evaluations), avoid set views which have O(n) size.
                    newDepsThatWerentInTheLastEvaluation = newDeps
                    newDepsThatWereInTheLastEvaluation = com.google.common.collect.ImmutableList.of<SkyKey?>()
                } else {
                    newDepsThatWerentInTheLastEvaluation =
                        com.google.common.collect.ImmutableList.copyOf<SkyKey?>(
                            com.google.common.collect.Sets.difference<SkyKey?>(
                                newDeps,
                                oldDeps
                            )
                        )
                    newDepsThatWereInTheLastEvaluation =
                        com.google.common.collect.ImmutableList.copyOf<SkyKey?>(
                            com.google.common.collect.Sets.intersection<SkyKey?>(
                                newDeps,
                                oldDeps
                            )
                        )
                }

                val newDepsThatWerentInTheLastEvaluationNodes: InterruptibleSupplier<NodeBatch?> =
                    graph.createIfAbsentBatchAsync(
                        skyKey,
                        com.google.devtools.build.skyframe.QueryableGraph.Reason.RDEP_ADDITION,
                        newDepsThatWerentInTheLastEvaluation
                    )
                val resetDeps: com.google.common.collect.ImmutableSet<SkyKey?> = nodeEntry.getResetDirectDeps()

                // Due to multi-threading, either the following call to handleKnownChildrenForDirtyNode or
                // the enqueueChild loop may cause the current node to be re-enqueued (and evaluated) if all
                // new children of this node are already done. Therefore, the rest of this method cannot
                // assume that the node is dirty.
                handleKnownChildrenForDirtyNode(
                    newDepsThatWereInTheLastEvaluation,
                    graph.getBatch(
                        skyKey,
                        com.google.devtools.build.skyframe.QueryableGraph.Reason.ENQUEUING_CHILD,
                        newDepsThatWereInTheLastEvaluation
                    ),
                    nodeEntry,  /* enqueueParentIfReady= */
                    true,
                    env
                )

                val newNodes: NodeBatch? = newDepsThatWerentInTheLastEvaluationNodes.get()
                for (newDirectDep in newDepsThatWerentInTheLastEvaluation!!) {
                    enqueueChild(
                        skyKey,
                        nodeEntry,
                        newDirectDep,
                        newNodes.get(newDirectDep),  /* depAlreadyExists= */
                        resetDeps.contains(newDirectDep),  /* enqueueParentIfReady= */
                        true,
                        env
                    )
                }
                if (externalDeps != null) {
                    // This can cause the current node to be re-enqueued if all futures are already done.
                    // This is an exception to the rule above that there must not be code below the for
                    // loop. It is safe because we call nodeEntry.addExternalDep above, which prevents
                    // re-enqueueing of the current node in the above loop if externalDeps != null.
                    evaluatorContext.getVisitor().registerExternalDeps(skyKey, nodeEntry, externalDeps)
                }
                // Do not put any code here! Any code here can race with a re-evaluation of this same node
                // in another thread.
            } catch (ie: java.lang.InterruptedException) {
                // The current thread can be interrupted at various places during evaluation or while
                // committing the result in this method. Since we only register the future(s) with the
                // underlying AbstractQueueVisitor in the registerExternalDeps call above, we have to make
                // sure that any known futures are correctly canceled if we do not reach that call. Note
                // that it is safe to cancel a future multiple times.
                cancelExternalDeps(env)
                // InterruptedException cannot be thrown by Runnable.run, so we must wrap it.
                // Interrupts can be caught by both the Evaluator and the AbstractQueueVisitor.
                // The former will unwrap the IE and propagate it as is; the latter will throw a new IE.
                throw SchedulerException.Companion.ofInterruption(ie, skyKey)
            }
        }

        fun handleUndonePreviouslyRequestedDep(nodeEntry: NodeEntry) {
            // If a previously requested dep is no longer done, restart this node from scratch.
            stateCache.invalidate(skyKey)
            resetEntry(skyKey, nodeEntry)
            evaluatorContext.getVisitor().enqueueEvaluation(skyKey, null)
        }

        fun cancelExternalDeps(env: SkyFunctionEnvironment?) {
            if (env != null && env.externalDeps != null) {
                for (future in env.externalDeps) {
                    future.cancel( /* mayInterruptIfRunning= */true)
                }
            }
        }

        fun prepareCrashMessage(skyKey: SkyKey?, reverseDeps: Iterable<SkyKey?>): String? {
            val reverseDepDump: java.lang.StringBuilder = java.lang.StringBuilder()
            for (key in reverseDeps) {
                if (reverseDepDump.length() > com.google.devtools.build.skyframe.AbstractParallelEvaluator.Evaluate.Companion.MAX_REVERSEDEP_DUMP_LENGTH) {
                    reverseDepDump.append(", ...")
                    break
                }
                if (reverseDepDump.length() > 0) {
                    reverseDepDump.append(", ")
                }
                reverseDepDump.append("'")
                reverseDepDump.append(key)
                reverseDepDump.append("'")
            }

            return java.lang.String.format(
                "Unrecoverable error while evaluating node '%s' (requested by nodes %s)",
                skyKey, reverseDepDump
            )
        }

        init {
            this.skyKey = skyKey
        }

        companion object {
            private const val MAX_REVERSEDEP_DUMP_LENGTH = 1000
        }
    }

    protected fun replay(transitiveEvents: NestedSet<Reportable?>) {
        // Replaying actions is done on a small number of nodes, but potentially over a large dependency
        // graph. Under those conditions, using the regular NestedSet flattening with .toList() is more
        // efficient than using NestedSetVisitor's custom traversal logic.
        evaluatorContext.getReplayingNestedSetEventVisitor().visit(transitiveEvents.toList())
    }

    /**
     * Resets `entry`, and the other nodes specified by `restart.rewindGraph()` will be
     * marked changed via postorder DFS.
     * 
     * 
     * `restart.rewindGraph()` must be empty or must contain `key`.
     * 
     * 
     * TODO(b/123993876): this should verify that edges in rewindGraph correspond to deps in the
     * Skyframe graph. Will require a safe way of requesting deps for nodes which may not be done.
     */
    // Nodes must be marked changed via postorder DFS. To see why, suppose we have this graph:
    //
    //   FailedNode   SomeOtherRdepOfR1
    //       |       /
    //       |  -----
    //       | /
    //       R1
    //       |
    //       R2
    //
    // Suppose FailedNode (FN) fails and requires that R1 and R2 must be dirtied and run again.
    // Suppose they aren't dirtied via postorder DFS, so R1 is dirtied first.
    //
    // Then, the evaluation thread working on dirtying these nodes is suspended.
    //
    // On a separate evaluation thread, SomeOtherRdepOfR1 requests R1. R1 is scheduled for evaluation,
    // checks its dep R2, and because R2 is done, R1 completes without scheduling R2 for evaluation.
    //
    // Then, the evaluation thread working on dirtying these nodes continues its work. It dirties
    // R2 and schedules FN for evaluation.
    //
    // When FN next evaluates, it requests R1, and because R1 is done, R2 is not scheduled for
    // evaluation, contrary to FN's expectations.
    @Throws(java.lang.InterruptedException::class)
    private fun dirtyRewindGraphAndResetEntry(key: SkyKey, entry: NodeEntry, restart: Reset) {
        val rewindGraph: com.google.common.graph.ImmutableGraph<SkyKey?> = restart.rewindGraph()
        com.google.common.base.Preconditions.checkState(
            rewindGraph.nodes().contains(key),
            "Rewind graph missing evaluating key %s: %s",
            key,
            rewindGraph
        )

        val builder: com.google.common.collect.ImmutableList.Builder<SkyKey?> =
            com.google.common.collect.ImmutableList.builderWithExpectedSize<SkyKey?>(rewindGraph.nodes().size() - 1)
        for (k in com.google.common.graph.Traverser.forGraph<SkyKey?>(rewindGraph).depthFirstPostOrder(key)) {
            if (k != key) {
                builder.add(k)
            }
        }
        val childrenToRestart: com.google.common.collect.ImmutableList<SkyKey> = builder.build()
        if (!childrenToRestart.isEmpty()) {
            evaluatorContext
                .getGraphInconsistencyReceiver()
                .noteInconsistencyAndMaybeThrow(
                    key, childrenToRestart, Inconsistency.PARENT_FORCE_REBUILD_OF_CHILD
                )

            val children: NodeBatch =
                evaluatorContext.getGraph().getBatch(
                    key,
                    com.google.devtools.build.skyframe.QueryableGraph.Reason.REWINDING,
                    childrenToRestart
                )

            for (childToRestart in childrenToRestart) {
                val childEntry: NodeEntry =
                    com.google.common.base.Preconditions.checkNotNull<NodeEntry>(
                        children.get(childToRestart),
                        "Missing child for rewinding: %s (parent=%s)",
                        childToRestart,
                        key
                    )

                if (childEntry.markDirty(DirtyType.REWIND) != null) {
                    evaluatorContext.getProgressReceiver().dirtied(childToRestart, DirtyType.REWIND)
                }
            }
        }

        resetEntry(key, entry)
    }

    private fun resetEntry(key: SkyKey?, entry: NodeEntry) {
        evaluatorContext
            .getGraphInconsistencyReceiver()
            .noteInconsistencyAndMaybeThrow(key,  /* otherKeys= */null, Inconsistency.RESET_REQUESTED)
        entry.resetEvaluationFromScratch()
    }

    fun propagateEvaluatorContextCrashIfAny() {
        if (!evaluatorContext.getVisitor().getCrashes().isEmpty()) {
            evaluatorContext
                .getReporter()
                .handle(Event.error("Crashes detected: " + evaluatorContext.getVisitor().getCrashes()))
            throw com.google.common.base.Preconditions.checkNotNull<java.lang.RuntimeException?>(
                com.google.common.collect.Iterables.getFirst<java.lang.RuntimeException?>(
                    evaluatorContext.getVisitor().getCrashes(),
                    null
                )
            )
        }
    }

    /**
     * Add any newly discovered deps that were registered during the run of a SkyFunction that
     * finished by returning a value or throwing an error. SkyFunctions may throw errors even if all
     * their deps were not provided -- we trust that a SkyFunction might know it should throw an error
     * even if not all of its requested deps are done. However, that means we're assuming the
     * SkyFunction would throw that same error if all of its requested deps were done. Unfortunately,
     * there is no way to enforce that condition.
     * 
     * 
     * Returns `true` if any newly discovered dep is dirty when this node registers itself as
     * an rdep and if one of those dirty deps will schedule this node for evaluation.
     * 
     * 
     * This can happen if a newly discovered dep transitions from done to dirty between when this
     * node's evaluation accessed the dep's value and here. Adding this node as an rdep of that dep
     * (or checking that this node is an rdep of that dep) will cause this node to be signalled when
     * that dep completes.
     * 
     * 
     * If this returns `true`, this node should not actually finish, and this evaluation
     * attempt should make no changes to the node after this method returns, because a completing dep
     * may schedule a new evaluation attempt at any time.
     */
    @Throws(java.lang.InterruptedException::class)
    private fun maybeHandleRegisteringNewlyDiscoveredDepsForDoneEntry(
        skyKey: SkyKey?,
        entry: NodeEntry,
        oldDeps: com.google.common.collect.ImmutableSet<SkyKey?>,
        env: SkyFunctionEnvironment,
        keepGoing: Boolean
    ): Boolean {
        // We don't expect any unfinished deps in a keep-going build.
        if (!keepGoing) {
            env.removeUndoneNewlyRequestedDeps()
        }

        val newDeps: MutableSet<SkyKey> = env.getNewlyRequestedDeps()
        if (newDeps.isEmpty()) {
            return false
        }

        env.addTemporaryDirectDepsTo(entry)

        // Reset deps is usually empty. Avoid an unnecessary allocation from Sets.union if possible.
        val resetDeps: com.google.common.collect.ImmutableSet<SkyKey?> = entry.getResetDirectDeps()
        val alreadyRegisteredDeps: MutableSet<SkyKey?>
        if (resetDeps.isEmpty()) {
            alreadyRegisteredDeps = oldDeps
        } else if (oldDeps.isEmpty()) {
            alreadyRegisteredDeps = resetDeps
        } else {
            alreadyRegisteredDeps = com.google.common.collect.Sets.union<SkyKey?>(oldDeps, resetDeps)
        }

        val newlyAddedNewDeps: MutableCollection<SkyKey>?
        val previouslyRegisteredNewDeps: com.google.common.collect.ImmutableCollection<SkyKey>?
        if (alreadyRegisteredDeps.isEmpty()) {
            newlyAddedNewDeps = newDeps
            previouslyRegisteredNewDeps = com.google.common.collect.ImmutableSet.of<SkyKey?>()
        } else {
            newlyAddedNewDeps = com.google.common.collect.ImmutableList.copyOf<SkyKey?>(
                com.google.common.collect.Sets.difference<SkyKey?>(
                    newDeps,
                    alreadyRegisteredDeps
                )
            )
            previouslyRegisteredNewDeps =
                com.google.common.collect.ImmutableList.copyOf<SkyKey?>(
                    com.google.common.collect.Sets.intersection<SkyKey?>(
                        newDeps,
                        alreadyRegisteredDeps
                    )
                )
        }

        val newlyAddedNewDepNodes: InterruptibleSupplier<NodeBatch?> =
            graph.getBatchAsync(
                skyKey,
                com.google.devtools.build.skyframe.QueryableGraph.Reason.RDEP_ADDITION,
                newlyAddedNewDeps
            )

        // Dep entries in the following two loops may not be done, but they must be present. In a
        // keep-going build, we normally expect all deps to be done. In a non-keep-going build, if
        // env.newlyRequestedDeps contained a key for a node that wasn't done, then it would have been
        // removed via removeUndoneNewlyRequestedDeps() just above this loop. However, with
        // intra-evaluation dirtying, a dep may not be done.
        var dirtyDepFound = false
        var selfSignalled = false

        val previouslyRegisteredEntries: NodeBatch =
            graph.getBatch(
                skyKey,
                com.google.devtools.build.skyframe.QueryableGraph.Reason.SIGNAL_DEP,
                previouslyRegisteredNewDeps
            )
        for (newDep in previouslyRegisteredNewDeps) {
            // We choose not to use `getOrRecreateDepEntry(...)` due to there is no use case where nodes
            // are expected to be missing on incremental builds (which this loop is specific to).
            val depEntry: NodeEntry =
                com.google.common.base.Preconditions.checkNotNull<NodeEntry>(
                    previouslyRegisteredEntries.get(newDep),
                    "Missing already declared dep %s (parent=%s)",
                    newDep,
                    skyKey
                )
            val triState: DependencyState = depEntry.checkIfDoneForDirtyReverseDep(skyKey)
            when (maybeHandleUndoneDepForDoneEntry(entry, depEntry, triState, skyKey, newDep)) {
                MaybeHandleUndoneDepResult.DEP_DONE_SELF_SIGNALLED -> selfSignalled = true
                MaybeHandleUndoneDepResult.DEP_DONE_SELF_NOT_SIGNALLED -> {}
                MaybeHandleUndoneDepResult.DEP_NOT_DONE -> dirtyDepFound = true
            }
        }

        for (newDep in newlyAddedNewDeps!!) {
            val depEntry: NodeEntry =
                getOrRecreateDepEntry(
                    newDep,
                    newlyAddedNewDepNodes.get(),
                    skyKey,
                    com.google.devtools.build.skyframe.QueryableGraph.Reason.RDEP_ADDITION
                )

            val triState: DependencyState? = depEntry.addReverseDepAndCheckIfDone(skyKey)
            when (maybeHandleUndoneDepForDoneEntry(entry, depEntry, triState, skyKey, newDep)) {
                MaybeHandleUndoneDepResult.DEP_DONE_SELF_SIGNALLED -> selfSignalled = true
                MaybeHandleUndoneDepResult.DEP_DONE_SELF_NOT_SIGNALLED -> {}
                MaybeHandleUndoneDepResult.DEP_NOT_DONE -> dirtyDepFound = true
            }
        }

        com.google.common.base.Preconditions.checkState(
            selfSignalled || dirtyDepFound,
            "%s %s %s %s",
            skyKey,
            entry,
            newlyAddedNewDeps,
            previouslyRegisteredNewDeps
        )

        return !selfSignalled
    }

    /**
     * Returns a [NodeEntry] for `depKey`.
     * 
     * 
     * If `depKey` is present in `depEntries`, its corresponding entry is returned.
     * Otherwise, if the evaluator permits [Inconsistency.ALREADY_DECLARED_CHILD_MISSING], the
     * entry will be recreated.
     */
    @Throws(java.lang.InterruptedException::class)
    private fun getOrRecreateDepEntry(
        depKey: SkyKey,
        depEntries: NodeBatch,
        requestor: SkyKey?,
        reason: com.google.devtools.build.skyframe.QueryableGraph.Reason?
    ): NodeEntry {
        val depEntry: NodeEntry? = depEntries.get(depKey)
        if (depEntry != null) {
            return depEntry
        }

        val missing: com.google.common.collect.ImmutableList<SkyKey> =
            com.google.common.collect.ImmutableList.of<SkyKey?>(depKey)
        evaluatorContext
            .getGraphInconsistencyReceiver()
            .noteInconsistencyAndMaybeThrow(
                requestor, missing, Inconsistency.ALREADY_DECLARED_CHILD_MISSING
            )
        return com.google.common.base.Preconditions.checkNotNull<NodeEntry>(
            graph.createIfAbsentBatch(
                requestor,
                reason,
                missing
            ).get(depKey), depKey
        )
    }

    private enum class MaybeHandleUndoneDepResult {
        DEP_DONE_SELF_SIGNALLED,
        DEP_DONE_SELF_NOT_SIGNALLED,
        DEP_NOT_DONE
    }

    /**
     * Returns [MaybeHandleUndoneDepResult.DEP_NOT_DONE] if `depEntry` was not done.
     * Notifies the [GraphInconsistencyReceiver] if so. Schedules `depEntry` for
     * evaluation if necessary.
     * 
     * 
     * If `depEntry` was done, then this calls `entry.signalDep`.
     * 
     * 
     * If the call to `#signalDep` returns false, this returns [ ][MaybeHandleUndoneDepResult.DEP_DONE_SELF_NOT_SIGNALLED].
     * 
     * 
     * If the call to `#signalDep` returns true, this returns [ ][MaybeHandleUndoneDepResult.DEP_DONE_SELF_SIGNALLED]. This will happen for the last new dep if
     * all of them were done. It can also happen if some new deps weren't done but they all signal
     * `entry` before [.maybeHandleRegisteringNewlyDiscoveredDepsForDoneEntry] finishes
     * checking deps.
     */
    private fun maybeHandleUndoneDepForDoneEntry(
        entry: NodeEntry, depEntry: NodeEntry, triState: DependencyState?, skyKey: SkyKey?, depKey: SkyKey
    ): MaybeHandleUndoneDepResult {
        if (triState == DependencyState.DONE) {
            return if (entry.signalDep(depEntry.getVersion(), depKey))
                MaybeHandleUndoneDepResult.DEP_DONE_SELF_SIGNALLED
            else
                MaybeHandleUndoneDepResult.DEP_DONE_SELF_NOT_SIGNALLED
        }
        // The dep may have transitioned from done to dirty between when this node read its value and
        // now. Notify the graph inconsistency receiver so that we can crash if that's unexpected. We
        // schedule the dep if it needs scheduling, because nothing else can if we don't.
        evaluatorContext
            .getGraphInconsistencyReceiver()
            .noteInconsistencyAndMaybeThrow(
                skyKey,
                com.google.common.collect.ImmutableList.of<SkyKey?>(depKey),
                Inconsistency.BUILDING_PARENT_FOUND_UNDONE_CHILD
            )
        if (triState == DependencyState.NEEDS_SCHEDULING) {
            evaluatorContext.getVisitor().enqueueEvaluation(depKey, null)
        }
        return MaybeHandleUndoneDepResult.DEP_NOT_DONE
    }

    companion object {
        private val logger: GoogleLogger = GoogleLogger.forEnclosingClass()

        /**
         * If the entry is dirty and not already rebuilding, puts it in a state so that it can rebuild.
         */
        fun maybeMarkRebuilding(entry: NodeEntry) {
            if (entry.isDirty() && entry.getLifecycleState() != LifecycleState.REBUILDING) {
                entry.markRebuilding()
            }
        }

        @Throws(java.lang.InterruptedException::class)
        fun propagateInterruption(e: SchedulerException) {
            val mustThrowInterrupt: Boolean = java.lang.Thread.interrupted()
            if (e.getCause() != null) {
                com.google.common.base.Throwables.throwIfInstanceOf<java.lang.InterruptedException?>(
                    e.getCause(),
                    java.lang.InterruptedException::class.java
                )
                com.google.common.base.Throwables.throwIfUnchecked(e.getCause())
            }
            if (mustThrowInterrupt) {
                // As per the contract of AbstractQueueVisitor#work, if an unchecked exception is thrown and
                // the build is interrupted, the thrown exception is what will be rethrown. Since the user
                // presumably wanted to interrupt the build, we ignore the thrown SchedulerException (which
                // doesn't indicate a programming bug) and throw an InterruptedException.
                throw java.lang.InterruptedException()
            }
        }

        /**
         * Return true if the entry does not need to be re-evaluated this build. The entry will need to be
         * re-evaluated if it is not done, but also if it was not completely evaluated last build and this
         * build is keepGoing.
         */
        fun isDoneForBuild(entry: NodeEntry?): Boolean {
            return entry != null && entry.isDone()
        }
    }
}
