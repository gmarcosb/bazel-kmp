// Copyright 2022 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.skyframe.ArtifactConflictFinder.NUM_JOBS

/**
 * An incremental artifact conflict finder that maintains a running state.
 * 
 * 
 * Once an ActionLookupKey is analyzed, its actions are registered with this conflict finder
 * before execution. The internal action graph accumulates these actions in order to detect a
 * conflict later on. There should be one instance of this class per build.
 */
@ThreadSafe
class IncrementalArtifactConflictFinder(
    threadSafeMutableActionGraph: MutableActionGraph,
    walkableGraph: WalkableGraph
) {
    private val threadSafeMutableActionGraph: MutableActionGraph
    private val pathFragmentTrieRoot: ConcurrentMap<String?, Any?>
    private val exclusivePool: QuiescingExecutor
    private val freeForAllPool: com.google.common.util.concurrent.ListeningExecutorService
    private val walkableGraph: WalkableGraph
    private val conflictFound: AtomicBoolean = AtomicBoolean(false)
    private var globalVisited: MutableSet<ActionLookupKey?> =
        com.google.common.collect.Sets.newConcurrentHashSet<ActionLookupKey?>()

    @javax.annotation.concurrent.GuardedBy("exclusivePortionLock")
    private var nextSignalToWaitFor: CountDownLatch? = null

    // The common lock for the portions of the process where top level targets need to be processed
    // exclusively.
    private val exclusivePortionLock = Any()

    init {
        this.threadSafeMutableActionGraph = threadSafeMutableActionGraph
        this.pathFragmentTrieRoot = ConcurrentHashMap<String?, Any?>()
        this.walkableGraph = walkableGraph
        this.exclusivePool =
            AbstractQueueVisitor.createWithExecutorService(
                Executors.newFixedThreadPool(
                    NUM_JOBS,
                    com.google.common.util.concurrent.ThreadFactoryBuilder().setNameFormat("ALV collector %d").build()
                ),
                ExceptionHandlingMode.KEEP_GOING,
                ErrorClassifier.DEFAULT
            )
        this.freeForAllPool =
            com.google.common.util.concurrent.MoreExecutors.listeningDecorator(
                Executors.newFixedThreadPool(
                    NUM_JOBS,
                    com.google.common.util.concurrent.ThreadFactoryBuilder().setNameFormat("Action conflict finder %d")
                        .build()
                )
            )
    }

    val outputArtifactCount: Int
        get() = threadSafeMutableActionGraph.getSize()

    @Throws(java.lang.InterruptedException::class)
    fun findArtifactConflicts(actionLookupKey: ActionLookupKey?): ActionConflictsAndStats? {
        return findArtifactConflicts(actionLookupKey,  /* inRerun= */false)
    }

    /**
     * The following scenario would be used for the rest of this section:
     * 
     * 
     *  * topA depends on C1 and C2,
     *  * topB also depends on C1 and C2,
     *  * C1 and C2 conflict
     *  * --keep_going
     * 
     * 
     * With Skymeld, conflict checking has to be done incrementally the moment each top level target's
     * analysis is finished. We're essentially trying to ensure 2 goals: (goal#1) for the "happy
     * path", no extra ALV is traversed and (goal#2) for the conflict case, no top level target is
     * allowed to enter execution without making sure that there's no conflict in its actions. Some
     * past solutions that didn't quite work:
     * 
     * 
     *  * If we use a naive global set of visited ALKs to prune traversal, we achieve (goal#1) but
     * fail (goal#2). Explanation below [1].
     *  * If we only add ALKs to this set when we know these ALKs are conflict-free, we achieve
     * (goal#2) but fail (goal#1): if conflict_check(topA) and conflict_check(topB) happen
     * around the same time, we essentially get no ALV pruning. Also covered below [1].
     * 
     * 
     * To achieve both, we use the following algorithm:
     * 
     * <pre>`1. [Sequential portion] Sequentially collect the ALVs in the transitive closure of a top level    target. Store the visited keys in a set and use that to exclude them from traversals by    other top level targets.    - The strict sequential ordering ensures that by the time we're done with the conflict check      of a top level target, its full transitive closure is covered and therefore avoiding      missing possible conflicts. More explanation in [2]. 2. [Concurrent portion] Concurrently check the actions in the collected ALVs. 3. Finalizing the conflict checking of the ith top level key only if that of the (i - 1)th key    is finalized. Once a key is finalized, we can be sure that it contains no conflict.    - Finalizing, in practice, simply means allowing the conflict checking method to return and      essentially starting the execution.    - The ordering is the order in which top level targets start checking for conflicts.    - The ordering is important for correctness reasons: a top level target needs to wait until      the ALVs that were in the visited set when it started checking for conflicts to have      actually been checked for conflicts. 4. If there's a conflict detected at any point, rerun the check for the unfinished keys without    pruning (the full transitive closure would be visited). `</pre>
     * 
     * 
     * #1 would ensure (goal#1) since there's pruning. #3 and #4 would ensure (goal#2). #2 is for
     * performance.
     * 
     * 
     * Why do we need #1 to be sequential? See [2].
     * 
     * 
     * Why do we need #2 to be a separate concurrent section? Without it, we'd essentially be doing
     * the entire conflict checking sequentially. Our benchmark has shown that this was very slow.
     * 
     * 
     * Why do we need the ordering in #3? See [3].
     * 
     * 
     * Why do we need the rerun in #4? Without it, we can't really proceed. Should a top level
     * target topC be stopped from executing by a conflict discovered in topA? We don't have enough
     * information to know without rerunning.
     * 
     * 
     * === Footnotes ===
     * 
     * 
     * [1] Assume the following sequence:
     * 
     * <pre>`conflict_check(topA) topA visits C1 topA visits C2 conflict_check(topB) topB doesn't visit C1 & C2 since they're in the visited set check_actions(topB) returns with no conflict check_actions(topA) finally recognizes the conflict, but it's too late. topB already started executing. `</pre>
     * 
     * 
     * To avoid this issue, we have been only updating the global set with conflict-free keys. This
     * however comes with a heavy performance penalty: if the top level targets start to check for
     * conflicts at roughly the same time, this pruning mechanism is ineffective and would result in a
     * lot more extra work.
     * 
     * 
     * [2] If #1 isn't sequential, the following can happen:
     * 
     * <pre>`# conflict_check = collect_alv (concurrent) + check_actions (concurrent) collect_alv(topA) collect_alv(topB) topA visits C1 topB visits C2. Since C2 is visited, topA doesn't visit it anymore check_actions(topA) returns with no conflict check_actions(topB) finally recognizes the conflict, but it's too late. topA already started executing. `</pre>
     * 
     * What we've ensured here is: if we discover a conflict foo, there's no chance of it being
     * executed by a top level target that's already confirmed to be conflict-free.
     * 
     * 
     * [3] If the ith key doesn't wait for the (i - 1)th key, the following can happen:
     * 
     * <pre>`# conflict_check = collect_alv (sequential) + check_actions (concurrent) collect_alv(topA) topA visits C1 topA visits C2 collect_alv(topB) check_actions(topB) does not wait for top A and returns with no conflict check_actions(topA) finally recognizes the conflict, but it's too late. topB already started executing. `</pre>
     */
    @Throws(java.lang.InterruptedException::class)
    fun findArtifactConflicts(actionLookupKey: ActionLookupKey?, inRerun: Boolean): ActionConflictsAndStats? {
        val temporaryBadActionMap: ConcurrentMap<ActionAnalysisMetadata?, ActionConflictException?> =
            ConcurrentHashMap<ActionAnalysisMetadata?, ActionConflictException?>()

        val actionCheckingFutures: MutableCollection<com.google.common.util.concurrent.ListenableFuture<java.lang.Void?>?> =
            ConcurrentLinkedQueue<com.google.common.util.concurrent.ListenableFuture<java.lang.Void?>?>()

        var toWaitFor: CountDownLatch? = null
        var mySignal: CountDownLatch? = null

        Profiler.instance().profile(ProfilerTask.CONFLICT_CHECK, "ALV collection").use { c ->
            synchronized(exclusivePortionLock) {
                if (!inRerun) {
                    toWaitFor = nextSignalToWaitFor
                    mySignal = CountDownLatch(1)
                    nextSignalToWaitFor = mySignal
                }
                exclusivePool.execute(
                    CheckForConflictsUnderKey(
                        actionLookupKey,
                        actionCheckingFutures,
                        temporaryBadActionMap,  // While rerunning, we only keep a local set of visited ALKs.
                        /* dedupSet= */
                        if (inRerun) com.google.common.collect.Sets.newConcurrentHashSet<ActionLookupKey?>() else globalVisited
                    )
                )
                exclusivePool.awaitQuiescenceWithoutShutdown(true)
            }
        }
        Profiler.instance().profile(ProfilerTask.CONFLICT_CHECK, "Go through actions").use { c ->
            try {
                com.google.common.util.concurrent.Futures.whenAllSucceed<java.lang.Void?>(actionCheckingFutures)
                    .call<Any?>(
                        java.util.concurrent.Callable { null },
                        com.google.common.util.concurrent.MoreExecutors.directExecutor()
                    ).get()
            } catch (e: ExecutionException) {
                throw java.lang.IllegalStateException("Unexpected exception", e)
            }
            if (!temporaryBadActionMap.isEmpty()) {
                conflictFound.set(true)
                // We can drop the globalVisited set now.
                globalVisited = com.google.common.collect.Sets.newConcurrentHashSet<ActionLookupKey?>()
            }
        }
        if (!inRerun) {
            // Wait for the previous check in the queue.
            Profiler.instance()
                .profile(ProfilerTask.CONFLICT_CHECK, "Awaiting signal from a prior key.").use { c ->
                    if (toWaitFor != null) {
                        toWaitFor.await()
                    }
                }
            // Signal the next check in the queue to continue.
            mySignal.countDown()

            // Rerun if there's a conflict and this isn't the rerun already.
            // No need to rerun if the temporaryBadActionMap is non-empty: this means a conflict has
            // been detected for this top level target and it won't be executed. That's all we want.
            if (conflictFound.get() && toWaitFor != null && temporaryBadActionMap.isEmpty()) {
                return findArtifactConflicts(actionLookupKey,  /* inRerun= */true)
            }
        }

        return ActionConflictsAndStats.create(
            com.google.common.collect.ImmutableMap.< K,
            V > copyOf<K?, V?>(temporaryBadActionMap),
            threadSafeMutableActionGraph.getSize()
        )
    }

    fun shutdown() {
        try {
            synchronized(exclusivePortionLock) {
                exclusivePool.awaitQuiescence(true)
            }
        } catch (e: java.lang.InterruptedException) {
            // Preserve the interrupt status.
            java.lang.Thread.currentThread().interrupt()
        }
        synchronized(freeForAllPool) {
            if (!freeForAllPool.isShutdown() && ExecutorUtil.interruptibleShutdown(freeForAllPool)) {
                // Preserve the interrupt status.
                java.lang.Thread.currentThread().interrupt()
            }
        }
    }

    @Throws(ActionConflictException::class, java.lang.InterruptedException::class)
    fun conflictCheckPerAction(action: ActionAnalysisMetadata) {
        threadSafeMutableActionGraph.registerAction(action)

        for (output in action.getOutputs()) {
            checkOutputPrefix(threadSafeMutableActionGraph, pathFragmentTrieRoot, output, null)
        }
    }

    /** Visit the transitive closure of `key` and check for conflicts among the actions.  */
    private inner class CheckForConflictsUnderKey(
        key: ActionLookupKey?,
        actionCheckingFutures: MutableCollection<com.google.common.util.concurrent.ListenableFuture<java.lang.Void?>?>,
        badActionMap: ConcurrentMap<ActionAnalysisMetadata?, ActionConflictException?>,
        dedupSet: MutableSet<ActionLookupKey?>
    ) : java.lang.Runnable {
        private val key: ActionLookupKey?
        private val actionCheckingFutures: MutableCollection<com.google.common.util.concurrent.ListenableFuture<java.lang.Void?>?>
        private val badActionMap: ConcurrentMap<ActionAnalysisMetadata?, ActionConflictException?>

        private val dedupSet: MutableSet<ActionLookupKey?>

        init {
            this.key = key
            this.actionCheckingFutures = actionCheckingFutures
            this.badActionMap = badActionMap
            this.dedupSet = dedupSet
        }

        override fun run() {
            var value: SkyValue? = null
            try {
                value = walkableGraph.getValue(key)
            } catch (e: java.lang.InterruptedException) {
                java.lang.Thread.currentThread().interrupt()
            }
            if (value == null) { // The value failed to evaluate.
                return
            }

            val directDeps: Iterable<SkyKey?>
            try {
                directDeps = walkableGraph.getDirectDeps(key)
            } catch (e: java.lang.InterruptedException) {
                java.lang.Thread.currentThread().interrupt()
                return
            }
            for (dep in directDeps) {
                if (dep !is ActionLookupKey) {
                    // The subgraph of dependencies of ActionLookupKeys never has a non-ActionLookupKey
                    // depending on an ActionLookupKey. So we can skip any non-ActionLookupKeys in the
                    // traversal as an optimization.
                    continue
                }
                if (dedupSet.add(dep)) {
                    exclusivePool.execute(
                        CheckForConflictsUnderKey(dep, actionCheckingFutures, badActionMap, dedupSet)
                    )
                }
            }
            val finalValue: SkyValue? = value
            // The value can be a non ActionLookupValue e.g. NonRuleConfiguredTargetValue.
            if (finalValue !is ActionLookupValue) {
                return
            }
            val goThroughActions: java.util.concurrent.Callable<java.lang.Void?> =
                java.util.concurrent.Callable {
                    actionRegistration(
                        finalValue as ActionLookupValue,
                        threadSafeMutableActionGraph,
                        pathFragmentTrieRoot,
                        badActionMap
                    )
                }
            try {
                val actionCheckingFuture: com.google.common.util.concurrent.ListenableFuture<java.lang.Void?> =
                    freeForAllPool.submit<java.lang.Void?>(goThroughActions)
                actionCheckingFutures.add(actionCheckingFuture)
            } catch (e: RejectedExecutionException) {
                // Some other thread shut down the executor, exit now. This can happen in the case of an
                // analysis error.
            }
        }
    }

    companion object {
        private fun actionRegistration(
            alv: ActionLookupValue,
            actionGraph: MutableActionGraph,
            pathFragmentTrieRoot: ConcurrentMap<String?, Any?>?,
            badActionMap: ConcurrentMap<ActionAnalysisMetadata?, ActionConflictException?>
        ): java.lang.Void? {
            for (action in alv.getActions()) {
                try {
                    actionGraph.registerAction(action)
                } catch (e: ActionConflictException) {
                    // It may be possible that we detect a conflict for the same action more than once, if
                    // that action belongs to multiple aspect values. In this case we will harmlessly
                    // overwrite the badActionMap entry.
                    badActionMap.put(action, e)
                    // We skip the rest of the loop, and do not add the path->artifact mapping for this
                    // artifact below -- we don't need to check it since this action is already in
                    // error.
                    continue
                } catch (e: java.lang.InterruptedException) {
                    // Bail.
                    java.lang.Thread.currentThread().interrupt()
                    return null
                }
                try {
                    for (output in action.getOutputs()) {
                        checkOutputPrefix(actionGraph, pathFragmentTrieRoot, output, badActionMap)
                    }
                } catch (e: ActionConflictException) {
                    throw java.lang.IllegalStateException(
                        "ActionConflictException aren't expected to be thrown here.", e
                    )
                }
            }
            return null
        }

        /**
         * Fits the path segments into the existing trie.
         * 
         * 
         * A conceptual path segment TrieNode can be:
         * 
         * 
         *  * an Artifact if it's a leaf node, or
         *  * a `ConcurrentMap<String, Object>` if it's a non-leaf node. The mapping is from a
         * path segment to another trie node.
         * 
         * 
         * 
         * We do this instead of creating a proper wrapper TrieNode data structure to save memory, as
         * the trie is expected to get quite large.
         * 
         * @throws ActionConflictException only when badActionMap is null.
         */
        @Throws(ActionConflictException::class)
        private fun checkOutputPrefix(
            actionGraph: MutableActionGraph,
            root: ConcurrentMap<String?, Any?>?,
            newArtifact: Artifact,
            badActionMap: ConcurrentMap<ActionAnalysisMetadata?, ActionConflictException?>?
        ) {
            var existingTrieNode: Any? = root
            val newArtifactPathFragment: PathFragment = newArtifact.getExecPath()
            val newPathIter: MutableIterator<String?> = newArtifactPathFragment.segments().iterator()

            while (newPathIter.hasNext() && existingTrieNode !is Artifact) {
                val newSegment = newPathIter.next()
                val isFinalSegmentOfNewPath = !newPathIter.hasNext()
                val existingNonLeafNode: ConcurrentMap<String?, Any> =
                    existingTrieNode as ConcurrentMap<String?, Any>

                val matchingChildNode: Any =
                    existingNonLeafNode.computeIfAbsent(
                        newSegment,
                        if (isFinalSegmentOfNewPath)
                            java.util.function.Function { unused: String? -> newArtifact }
                        else
                            java.util.function.Function { unused: String? -> ConcurrentHashMap<String?, Any?>() })

                // By the time we arrive in this method, we know for sure that there can't be any exact
                // matches in the paths since that would have been an ActionConflictException.
                val newPathIsPrefixOfExisting =
                    matchingChildNode !is Artifact && isFinalSegmentOfNewPath
                val existingPathIsPrefixOfNew =
                    matchingChildNode is Artifact && !isFinalSegmentOfNewPath

                if (existingPathIsPrefixOfNew || newPathIsPrefixOfExisting) {
                    val conflictingExistingArtifact: Artifact? = getOwningArtifactFromTrie(matchingChildNode)

                    // If 2 paths collide, we need to update the Trie to contain only the shorter one.
                    // This is required for correctness: the set of subsequent paths that could conflict with
                    // the longer path is a subset of that of the shorter path.
                    val prefix: Artifact?
                    val child: Artifact?
                    if (newPathIsPrefixOfExisting) {
                        existingNonLeafNode.put(newSegment, newArtifact)
                        prefix = newArtifact
                        child = conflictingExistingArtifact
                    } else {
                        prefix = conflictingExistingArtifact
                        child = newArtifact
                    }

                    if (!Actions.isRunfilesArtifactPair(prefix, child)) {
                        val priorAction: ActionAnalysisMetadata =
                            com.google.common.base.Preconditions.checkNotNull(
                                actionGraph.getGeneratingAction(conflictingExistingArtifact),
                                conflictingExistingArtifact
                            )
                        val currentAction: ActionAnalysisMetadata =
                            com.google.common.base.Preconditions.checkNotNull(
                                actionGraph.getGeneratingAction(
                                    newArtifact
                                ), newArtifact
                            )
                        val exception: ActionConflictException =
                            ActionConflictException.createPrefix(
                                conflictingExistingArtifact, newArtifact, priorAction, currentAction
                            )
                        if (badActionMap == null) {
                            throw exception
                        }

                        badActionMap.put(priorAction, exception)
                        badActionMap.put(currentAction, exception)

                        break
                    }
                }
                existingTrieNode = matchingChildNode
            }
        }

        // TODO(b/214389062) Fix the issue with SolibSymlinkAction before launch.
        private fun getOwningArtifactFromTrie(trieNode: Any): Artifact? {
            com.google.common.base.Preconditions.checkArgument(
                trieNode is Artifact || trieNode is ConcurrentHashMap<*, *>
            )
            if (trieNode is Artifact) {
                return trieNode
            }
            var nodeIter = trieNode
            while (nodeIter !is Artifact) {
                // Just pick the first path available down the Trie.
                for (value in (nodeIter as ConcurrentHashMap<*, *>).values) {
                    nodeIter = value
                    break
                }
            }
            return nodeIter as Artifact?
        }
    }
}
