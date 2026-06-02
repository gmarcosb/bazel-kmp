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

import com.google.common.flogger.GoogleLogger
import com.google.devtools.build.lib.bugreport.BugReport
import com.google.devtools.build.lib.bugreport.BugReport.sendBugReport
import com.google.devtools.build.lib.bugreport.BugReporter.sendBugReport
import com.google.devtools.build.lib.supplier.InterruptibleSupplier.get
import com.google.devtools.build.skyframe.AbstractParallelEvaluator
import com.google.devtools.build.skyframe.CycleDetector
import com.google.devtools.build.skyframe.CycleInfo
import com.google.devtools.build.skyframe.EvaluationResult
import com.google.devtools.build.skyframe.GroupedDeps
import com.google.devtools.build.skyframe.NodeBatch
import com.google.devtools.build.skyframe.NodeEntry
import com.google.devtools.build.skyframe.NodeEntry.LifecycleState
import com.google.devtools.build.skyframe.ParallelEvaluatorContext
import com.google.devtools.build.skyframe.QueryableGraph
import com.google.devtools.build.skyframe.SkyFunctionEnvironment
import com.google.devtools.build.skyframe.SkyFunctionEnvironment.UndonePreviouslyRequestedDeps
import com.google.devtools.build.skyframe.SkyKey
import com.google.devtools.build.skyframe.SkyValue
import com.google.devtools.build.skyframe.ValueWithMetadata
import java.util.ArrayDeque
import java.util.Deque
import java.util.HashSet

/**
 * Depth-first implementation of cycle detection after a [ParallelEvaluator] evaluation has
 * completed with at least one root unfinished.
 */
class SimpleCycleDetector(private val storeExactCycles: Boolean) : CycleDetector {
    @Throws(java.lang.InterruptedException::class)
    override fun checkForCycles(
        badRoots: Iterable<SkyKey?>,
        result: com.google.devtools.build.skyframe.EvaluationResult.Builder<*>,
        evaluatorContext: ParallelEvaluatorContext
    ) {
        for (root in badRoots) {
            val errorInfo: com.google.devtools.build.skyframe.ErrorInfo? = checkForCycles(root, evaluatorContext)
            if (errorInfo == null) {
                // This node just wasn't finished when evaluation aborted -- there were no cycles below
                // it.
                com.google.common.base.Preconditions.checkState(
                    !evaluatorContext.keepGoing(root),
                    "Missing error info with keep going (root=%s, badRoots=%s)",
                    root,
                    badRoots
                )
                continue
            }
            com.google.common.base.Preconditions.checkState(
                !errorInfo.getCycleInfo().isEmpty(),
                "%s was not evaluated, but was not part of a cycle",
                root
            )
            result.addError(root, errorInfo)
            if (!evaluatorContext.keepGoing(root)) {
                return
            }
        }
    }

    /**
     * The algorithm for this cycle detector is as follows. We visit the graph depth-first, keeping
     * track of the path we are currently on. We skip any DONE nodes (they are transitively
     * error-free). If we come to a node already on the path, we immediately construct a cycle. If we
     * are in the noKeepGoing case, we return ErrorInfo with that cycle to the caller. Otherwise, we
     * continue. Once all of a node's children are done, we construct an error value for it, based on
     * those children. Finally, when the original root's node is constructed, we return its ErrorInfo.
     */
    @Throws(java.lang.InterruptedException::class)
    private fun checkForCycles(
        root: SkyKey?,
        evaluatorContext: ParallelEvaluatorContext
    ): com.google.devtools.build.skyframe.ErrorInfo? {
        // The number of cycles found. Do not keep on searching for more cycles after this many were
        // found.
        var cyclesFound = 0
        // The path through the graph currently being visited.
        val graphPath: MutableList<SkyKey> = java.util.ArrayList<SkyKey>()
        // Set of nodes on the path, to avoid expensive searches through the path for cycles.
        val pathSet: MutableSet<SkyKey?> = HashSet<SkyKey?>()

        // Maintain a stack explicitly instead of recursion to avoid stack overflows
        // on extreme graphs (with long dependency chains).
        val toVisit: Deque<SkyKey> = ArrayDeque<SkyKey>()

        toVisit.push(root)

        // The procedure for this check is as follows: we visit a node, push it onto the graph path,
        // push a marker value onto the toVisit stack, and then push all of its children onto the
        // toVisit stack. Thus, when the marker node comes to the top of the toVisit stack, we have
        // visited the downward transitive closure of the value. At that point, all of its children must
        // be finished, and so we can build the definitive error info for the node, popping it off the
        // graph path.
        while (!toVisit.isEmpty()) {
            var key: SkyKey = toVisit.pop()

            val entry: NodeEntry?
            if (key === CHILDREN_FINISHED) {
                // We have reached the marker node - that means all children of a node have been visited.
                // Since all nodes have errors, we must have found errors in the children at this point.
                key = graphPath.remove(graphPath.size() - 1)
                entry =
                    com.google.common.base.Preconditions.checkNotNull<NodeEntry?>(
                        evaluatorContext.getGraph()
                            .get(null, com.google.devtools.build.skyframe.QueryableGraph.Reason.CYCLE_CHECKING, key),
                        key
                    )
                pathSet.remove(key)
                // Skip this node if it was first/last node of a cycle, and so has already been processed.
                if (entry.isDone()) {
                    continue
                }
                if (!evaluatorContext.keepGoing(key)) {
                    // in the --nokeep_going mode, we would have already returned if we'd found a cycle below
                    // this node. We haven't, so there are no cycles below this node; skip further evaluation
                    continue
                }
                var removedDeps: MutableSet<SkyKey?> = com.google.common.collect.ImmutableSet.of<SkyKey?>()
                if (cyclesFound < MAX_CYCLES_TO_STORE || !storeExactCycles) {
                    // Value must be ready, because all of its children have finished, so we can build its
                    // error.
                    com.google.common.base.Preconditions.checkState(
                        !entry.hasUnsignaledDeps(), "%s has unsignaled deps. ValueEntry: %s", key, entry
                    )
                } else if (entry.hasUnsignaledDeps()) {
                    removedDeps =
                        removeIncompleteChildrenForCycle(
                            key,
                            entry,
                            entry.getTemporaryDirectDeps().getAllElementsAsIterable(),
                            evaluatorContext
                        )
                }
                if (maybeHandleVerifiedCleanNode(key, entry, evaluatorContext, graphPath)) {
                    continue
                }
                AbstractParallelEvaluator.Companion.maybeMarkRebuilding(entry)
                val directDeps: GroupedDeps = entry.getTemporaryDirectDeps()
                // Find out which children have errors. Similar logic to that in Evaluate#run().
                val errorDeps: MutableList<com.google.devtools.build.skyframe.ErrorInfo?> =
                    getChildrenErrorsForCycle(
                        key, directDeps.getAllElementsAsIterable(), entry, evaluatorContext, removedDeps
                    )
                com.google.common.base.Preconditions.checkState(
                    !errorDeps.isEmpty(),
                    "Node %s was not successfully evaluated, but had no child errors. NodeEntry: %s",
                    key,
                    entry
                )
                val env: SkyFunctionEnvironment?
                try {
                    env =
                        SkyFunctionEnvironment.Companion.create(
                            key,
                            directDeps,
                            com.google.common.collect.Sets.difference<SkyKey?>(
                                entry.getAllRemainingDirtyDirectDeps(),
                                removedDeps
                            ),
                            entry.getMaxTransitiveSourceVersion(),
                            evaluatorContext
                        )
                    // When the environment sets a cycle node to be in error and commits afterwards, it
                    // requires all of its deps to be fetched. See `SkyFunctionEnvironment#setError()`'s
                    // JavaDoc for more details.
                    env.ensurePreviouslyRequestedDepsFetched()
                } catch (undoneDeps: UndonePreviouslyRequestedDeps) {
                    // All children were finished according to the CHILDREN_FINISHED sentinel, and cycle
                    // detection does not do normal SkyFunction evaluation, so no restarting nor child
                    // dirtying was possible.
                    throw java.lang.IllegalStateException(
                        "Previously requested deps not done: " + undoneDeps.getDepKeys(), undoneDeps
                    )
                }
                env.setError(
                    entry,
                    com.google.devtools.build.skyframe.ErrorInfo.Companion.fromChildErrors(key, errorDeps)
                )
                val reverseDeps: MutableSet<SkyKey?>? = env.commitAndGetParents(entry,  /* expectDoneDeps= */true)
                evaluatorContext.signalParentsOnAbort(key, reverseDeps, entry.getVersion())
            } else {
                entry = evaluatorContext.getGraph()
                    .get(null, com.google.devtools.build.skyframe.QueryableGraph.Reason.CYCLE_CHECKING, key)
            }

            com.google.common.base.Preconditions.checkNotNull<NodeEntry?>(entry, key)
            // Nothing to be done for this node if it already has an entry.
            if (entry.isDone()) {
                continue
            }
            if (cyclesFound >= MAX_CYCLES_TO_STORE && storeExactCycles) {
                // Do not keep on searching for cycles indefinitely, to avoid excessive runtime/OOMs.
                continue
            }

            if (pathSet.contains(key)) {
                val cycleStart = graphPath.indexOf(key)
                // Found a cycle!
                cyclesFound++
                val cycle: Iterable<SkyKey?>? = graphPath.subList(cycleStart, graphPath.size())
                // Log the cycle only if storing cycles, as cycle-storing mode ensures that the number
                // of graph cycles is bounded. Otherwise, a cycle-heavy graph could overflow the
                // INFO log.
                if (storeExactCycles) {
                    logger.atInfo().log("Found cycle : %s from %s", cycle, graphPath)
                }
                // Put this node into a consistent state for building if it is dirty.
                if (entry.isDirty()) {
                    // If this loop runs more than once, we are in the peculiar position of entry not needing
                    // rebuilding even though it was signaled with the graph version. This can happen when the
                    // entry was previously evaluated at this version, but then invalidated anyway, even
                    // though nothing changed.
                    var loopCount = 0
                    val graphVersion: com.google.devtools.build.skyframe.Version? = evaluatorContext.getGraphVersion()
                    while (entry.getLifecycleState() == LifecycleState.CHECK_DEPENDENCIES) {
                        entry.signalDep(graphVersion, null)
                        loopCount++
                    }
                    if (loopCount > 1 && entry.getVersion() != graphVersion) {
                        BugReport.sendBugReport(
                            java.lang.IllegalStateException(
                                ("Entry needed multiple signaling but didn't have the graph version: "
                                        + key
                                        + ", "
                                        + entry
                                        + ", "
                                        + graphVersion
                                        + ", "
                                        + graphPath)
                            )
                        )
                    }
                    if (entry.getLifecycleState() == LifecycleState.NEEDS_REBUILDING) {
                        entry.markRebuilding()
                    } else if (maybeHandleVerifiedCleanNode(key, entry, evaluatorContext, graphPath)) {
                        continue
                    }
                }
                if (evaluatorContext.keepGoing(key)) {
                    // Any children of this node that we haven't already visited are not worth visiting,
                    // since this node is about to be done. Thus, the only child worth visiting is the one in
                    // this cycle, the cycleChild (which may == key if this cycle is a self-edge).
                    val cycleChild: SkyKey = selectCycleChild(key, graphPath, cycleStart)
                    val removedDeps: MutableSet<SkyKey?> =
                        removeDescendantsOfCycleValue(
                            key, entry, cycleChild, toVisit, graphPath.size() - cycleStart, evaluatorContext
                        )
                    val dummyValue: ValueWithMetadata =
                        ValueWithMetadata.Companion.wrapWithMetadata(object : SkyValue {})

                    val env: SkyFunctionEnvironment =
                        SkyFunctionEnvironment.Companion.createForError(
                            key,
                            entry.getTemporaryDirectDeps(),
                            com.google.common.collect.ImmutableMap.of<SkyKey?, ValueWithMetadata?>(
                                cycleChild,
                                dummyValue
                            ),
                            com.google.common.collect.Sets.difference<SkyKey?>(
                                entry.getAllRemainingDirtyDirectDeps(),
                                removedDeps
                            ),
                            evaluatorContext
                        )

                    // Construct full error info for this node. Get errors from children, which are all done
                    // except possibly for the cycleChild.
                    val allErrors: MutableList<com.google.devtools.build.skyframe.ErrorInfo?> =
                        getChildrenErrorsForCycleChecking(
                            entry.getTemporaryDirectDeps().getAllElementsAsIterable(),  /* unfinishedChild= */
                            cycleChild,
                            evaluatorContext
                        )
                    val cycleInfo: CycleInfo? =
                        if (storeExactCycles) CycleInfo.Companion.createCycleInfo(cycle) else CycleInfo.Companion.cycleInfoNoDetails()
                    // Add in this cycle.
                    allErrors.add(com.google.devtools.build.skyframe.ErrorInfo.Companion.fromCycle(cycleInfo))
                    env.setError(
                        entry,
                        com.google.devtools.build.skyframe.ErrorInfo.Companion.fromChildErrors(key, allErrors)
                    )
                    val reverseDeps: MutableSet<SkyKey?>? = env.commitAndGetParents(entry,  /* expectDoneDeps= */true)
                    evaluatorContext.signalParentsOnAbort(key, reverseDeps, entry.getVersion())
                    continue
                } else {
                    // We need to return right away in the noKeepGoing case, so construct the cycle (with the
                    // path) and return.
                    com.google.common.base.Preconditions.checkState(
                        graphPath.get(0) == root,
                        "%s not reached from %s. ValueEntry: %s",
                        key,
                        root,
                        entry
                    )
                    return com.google.devtools.build.skyframe.ErrorInfo.Companion.fromCycle(
                        CycleInfo.Companion.createCycleInfo(graphPath.subList(0, cycleStart), cycle)
                    )
                }
            }

            // This node is not yet known to be in a cycle. So process its children.
            val temporaryDirectDeps: GroupedDeps = entry.getTemporaryDirectDeps()
            if (temporaryDirectDeps.isEmpty()) {
                continue
            }
            // Prefetch all children, in case our graph performs better with a primed cache. No need to
            // recurse into done nodes. The fields of done nodes aren't necessary, since we'll filter them
            // out.
            // TODO(janakr): If graph implementations start using these hints for not-done nodes, we may
            // have to change this.
            val children: Iterable<SkyKey> = temporaryDirectDeps.getAllElementsAsIterable()
            val childNodes: NodeBatch =
                evaluatorContext.getGraph().getBatch(
                    key,
                    com.google.devtools.build.skyframe.QueryableGraph.Reason.EXISTENCE_CHECKING,
                    children
                )

            // This marker flag will tell us when all this node's children have been processed.
            toVisit.push(CHILDREN_FINISHED)
            // This node is now part of the path through the graph.
            graphPath.add(key)
            pathSet.add(key)
            for (childKey in children) {
                val childEntry: NodeEntry =
                    com.google.common.base.Preconditions.checkNotNull<NodeEntry>(
                        childNodes.get(childKey),
                        "Missing already declared dep %s (parent=%s)",
                        childKey,
                        key
                    )
                if (!childEntry.isDone()) {
                    toVisit.push(childKey)
                }
            }
        }
        return if (evaluatorContext.keepGoing(root))
            checkDone(
                root,
                evaluatorContext.getGraph()
                    .get(null, com.google.devtools.build.skyframe.QueryableGraph.Reason.CYCLE_CHECKING, root)
            )
                .getErrorInfo()
        else
            null
    }

    /**
     * Get all the errors of child nodes.
     * 
     * @param children child nodes to query for errors.
     * @param unfinishedChild child which is allowed to not be done.
     * @return List of ErrorInfos from all children that had errors.
     */
    @Throws(java.lang.InterruptedException::class)
    private fun getChildrenErrorsForCycleChecking(
        children: Iterable<SkyKey>, unfinishedChild: SkyKey?, evaluatorContext: ParallelEvaluatorContext
    ): MutableList<com.google.devtools.build.skyframe.ErrorInfo?> {
        val allErrors: MutableList<com.google.devtools.build.skyframe.ErrorInfo?> =
            java.util.ArrayList<com.google.devtools.build.skyframe.ErrorInfo?>()
        val childEntries: NodeBatch =
            evaluatorContext.getGraph()
                .getBatch(null, com.google.devtools.build.skyframe.QueryableGraph.Reason.CYCLE_CHECKING, children)
        for (childKey in children) {
            val childNodeEntry: NodeEntry? = childEntries.get(childKey)
            val errorInfo: com.google.devtools.build.skyframe.ErrorInfo? =
                getErrorMaybe(
                    childKey, childNodeEntry,  /* allowUnfinished= */childKey == unfinishedChild
                )
            if (errorInfo != null) {
                // Drop child cycle error if not storing cycles, as these will be redundant with the cycle
                // error of the parent node.
                val dropErrorInfo = !storeExactCycles && !errorInfo.getCycleInfo().isEmpty()
                if (!dropErrorInfo) {
                    allErrors.add(errorInfo)
                }
            }
        }
        return allErrors
    }

    companion object {
        private val logger: GoogleLogger = GoogleLogger.forEnclosingClass()

        /** The max number of cycles we will report to the user for a given root, to avoid OOMing.  */
        private const val MAX_CYCLES_TO_STORE = 20

        /**
         * Fully process `entry` if it is dirty but verified to be clean. This can only happen in
         * rare circumstances where a node with a cycle is invalidated at the same version. Returns true
         * if the entry was successfully processed, meaning that its value has been set and all reverse
         * deps signaled.
         */
        @Throws(java.lang.InterruptedException::class)
        private fun maybeHandleVerifiedCleanNode(
            key: SkyKey?,
            entry: NodeEntry,
            evaluatorContext: ParallelEvaluatorContext,
            graphPathForDebugging: MutableList<SkyKey>?
        ): Boolean {
            if (entry.getLifecycleState() != LifecycleState.VERIFIED_CLEAN) {
                return false
            }
            val rdeps: MutableSet<SkyKey?>? = entry.markClean().getRdepsToSignal()
            evaluatorContext.signalParentsOnAbort(key, rdeps, entry.getVersion())
            val error: com.google.devtools.build.skyframe.ErrorInfo? = entry.getErrorInfo()
            if (error.getCycleInfo().isEmpty()) {
                BugReport.sendBugReport(
                    java.lang.IllegalStateException(
                        ("Entry was unchanged from last build, but cycle was found this time and not"
                                + " last time: "
                                + key
                                + ", "
                                + entry
                                + ", "
                                + graphPathForDebugging)
                    )
                )
            }
            return true
        }

        /**
         * Marker value that we push onto a stack before we push a node's children on. When the marker
         * value is popped, we know that all the children are finished. We would use null instead, but
         * ArrayDeque does not permit null elements.
         */
        private val CHILDREN_FINISHED: SkyKey = SkyKey { null }

        /**
         * Returns the child of this node that is in the cycle that was just found. If the cycle is a
         * self-edge, returns the node itself.
         */
        private fun selectCycleChild(key: SkyKey, graphPath: MutableList<SkyKey>, cycleStart: Int): SkyKey {
            return if (cycleStart + 1 == graphPath.size()) key else graphPath.get(cycleStart + 1)
        }

        /**
         * Get all the errors of child nodes. There must be at least one cycle amongst them.
         * 
         * @param children child nodes to query for errors.
         * @return List of ErrorInfos from all children that had errors.
         */
        @Throws(java.lang.InterruptedException::class)
        private fun getChildrenErrorsForCycle(
            parent: SkyKey?,
            children: Iterable<SkyKey>,
            entryForDebugging: NodeEntry?,
            evaluatorContext: ParallelEvaluatorContext,
            removedDepsForDebugging: MutableSet<SkyKey?>?
        ): MutableList<com.google.devtools.build.skyframe.ErrorInfo?> {
            val allErrors: MutableList<com.google.devtools.build.skyframe.ErrorInfo?> =
                java.util.ArrayList<com.google.devtools.build.skyframe.ErrorInfo?>()
            var foundCycle = false
            val childNodes: NodeBatch =
                evaluatorContext.getGraph()
                    .getBatch(parent, com.google.devtools.build.skyframe.QueryableGraph.Reason.CYCLE_CHECKING, children)
            for (childKey in children) {
                val childEntry: NodeEntry =
                    com.google.common.base.Preconditions.checkNotNull<NodeEntry>(
                        childNodes.get(childKey),
                        "Missing already declared dep %s (parent=%s)",
                        childKey,
                        parent
                    )
                checkDone(childKey, childEntry)
                val errorInfo: com.google.devtools.build.skyframe.ErrorInfo? = childEntry.getErrorInfo()
                if (errorInfo != null) {
                    foundCycle = foundCycle or !errorInfo.getCycleInfo().isEmpty()
                    allErrors.add(errorInfo)
                }
            }
            com.google.common.base.Preconditions.checkState(
                foundCycle,
                "Key %s with entry %s had no cycle beneath it: %s; Removed deps: %s",
                parent,
                entryForDebugging,
                allErrors,
                removedDepsForDebugging
            )
            return allErrors
        }

        @Throws(java.lang.InterruptedException::class)
        private fun getErrorMaybe(
            key: SkyKey?, childNodeEntry: NodeEntry?, allowUnfinished: Boolean
        ): com.google.devtools.build.skyframe.ErrorInfo? {
            com.google.common.base.Preconditions.checkNotNull<NodeEntry?>(childNodeEntry, key)
            if (!allowUnfinished) {
                return checkDone(key, childNodeEntry).getErrorInfo()
            }
            return if (childNodeEntry.isDone()) childNodeEntry.getErrorInfo() else null
        }

        /**
         * Removes direct children of key from toVisit and from the entry itself, and makes the entry
         * ready if necessary. We must do this because it would not make sense to try to build the
         * children after building the entry. It would violate the invariant that a parent can only be
         * built after its children are built; See bug "Precondition error while evaluating a Skyframe
         * graph with a cycle".
         * 
         * @param key SkyKey of node in a cycle.
         * @param entry NodeEntry of node in a cycle.
         * @param cycleChild direct child of key in the cycle, or key itself if the cycle is a self-edge.
         * @param toVisit list of remaining nodes to visit by the cycle-checker.
         * @param cycleLength the length of the cycle found.
         */
        @Throws(java.lang.InterruptedException::class)
        private fun removeDescendantsOfCycleValue(
            key: SkyKey?,
            entry: NodeEntry,
            cycleChild: SkyKey?,
            toVisit: Iterable<SkyKey>,
            cycleLength: Int,
            evaluatorContext: ParallelEvaluatorContext
        ): MutableSet<SkyKey?> {
            var cycleLength = cycleLength
            val directDeps: GroupedDeps = entry.getTemporaryDirectDeps()
            val unvisitedDeps: MutableSet<SkyKey> = HashSet<SkyKey>(directDeps.getAllElementsAsIterable())
            unvisitedDeps.remove(cycleChild)
            // Remove any children from this node that are not part of the cycle we just found. They are
            // irrelevant to the node as it stands, and if they are deleted from the graph because they are
            // not built by the end of cycle-checking, we would have dangling references.
            val removedDeps: MutableSet<SkyKey?> =
                removeIncompleteChildrenForCycle(key, entry, unvisitedDeps, evaluatorContext)
            if (entry.hasUnsignaledDeps()) {
                // The entry has at most one undone dep now, its cycleChild. Signal to make entry ready. Note
                // that the entry can conceivably be ready if its cycleChild already found a different cycle
                // and was built.
                entry.signalDep(evaluatorContext.getGraphVersion(), cycleChild)
            }
            AbstractParallelEvaluator.Companion.maybeMarkRebuilding(entry)
            com.google.common.base.Preconditions.checkState(
                !entry.hasUnsignaledDeps(),
                "%s %s %s",
                key,
                cycleChild,
                entry
            )
            val it: MutableIterator<SkyKey?> = toVisit.iterator()
            while (it.hasNext()) {
                val descendant: SkyKey? = it.next()
                if (descendant === CHILDREN_FINISHED) {
                    // Marker value, delineating the end of a group of children that were enqueued.
                    cycleLength--
                    if (cycleLength == 0) {
                        // We have seen #cycleLength-1 marker values, and have arrived at the one for this value,
                        // so we are done.
                        return removedDeps
                    }
                    continue  // Don't remove marker values.
                }
                if (cycleLength == 1) {
                    // Remove the direct children remaining to visit of the cycle node.
                    com.google.common.base.Preconditions.checkState(
                        unvisitedDeps.contains(descendant), "%s %s %s %s", key, descendant, cycleChild, entry
                    )
                    it.remove()
                }
            }
            throw java.lang.IllegalStateException(
                java.lang.String.format(
                    "There were not %d groups of children in %s when trying to remove children of %s other "
                            + "than %s",
                    cycleLength, toVisit, key, cycleChild
                )
            )
        }

        @Throws(java.lang.InterruptedException::class)
        private fun removeIncompleteChildrenForCycle(
            key: SkyKey?,
            entry: NodeEntry,
            children: Iterable<SkyKey>,
            evaluatorContext: ParallelEvaluatorContext
        ): MutableSet<SkyKey?> {
            val unfinishedDeps: MutableSet<SkyKey?> = HashSet<SkyKey?>()
            for (child in children) {
                if (removeIncompleteChildForCycle(key, child, evaluatorContext)) {
                    unfinishedDeps.add(child)
                }
            }
            entry.removeUnfinishedDeps(unfinishedDeps)
            return unfinishedDeps
        }

        private fun checkDone(key: SkyKey?, entry: NodeEntry): NodeEntry {
            com.google.common.base.Preconditions.checkNotNull<NodeEntry?>(entry, key)
            com.google.common.base.Preconditions.checkState(entry.isDone(), "%s %s", key, entry)
            return entry
        }

        /**
         * If child is not done, removes `inProgressParent` from `child`'s reverse deps.
         * Returns whether child should be removed from inProgressParent's entry's direct deps.
         */
        @Throws(java.lang.InterruptedException::class)
        private fun removeIncompleteChildForCycle(
            inProgressParent: SkyKey?, child: SkyKey?, evaluatorContext: ParallelEvaluatorContext
        ): Boolean {
            val childEntry: NodeEntry? =
                evaluatorContext.getGraph().get(
                    inProgressParent,
                    com.google.devtools.build.skyframe.QueryableGraph.Reason.CYCLE_CHECKING,
                    child
                )
            if (!AbstractParallelEvaluator.Companion.isDoneForBuild(childEntry)) {
                childEntry.removeReverseDep(inProgressParent)
                return true
            }
            return false
        }
    }
}
