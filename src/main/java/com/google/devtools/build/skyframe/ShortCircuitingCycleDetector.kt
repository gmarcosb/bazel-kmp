// Copyright 2025 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.concurrent.ForkJoinQuiescingExecutor

/**
 * [CycleDetector] that does not try to actually identify cycles, instead just marking every
 * incomplete but reachable node entry as a cycle node. This is an optimization for consumers of
 * Skyframe evaluations that don't actually need to know what the precise cycles are.
 */
class ShortCircuitingCycleDetector(private val numThreads: Int) : CycleDetector {
    private val seenNodes: MutableSet<SkyKey?>

    init {
        seenNodes =
            Collections.newSetFromMap<SkyKey?>(
                ConcurrentHashMap<SkyKey?, Boolean?>( /* initialCapacity= */numThreads,  /* loadFactor= */0.75f)
            )
    }

    @Throws(java.lang.InterruptedException::class)
    override fun checkForCycles(
        badRoots: Iterable<SkyKey?>?,
        result: com.google.devtools.build.skyframe.EvaluationResult.Builder<*>,
        evaluatorContext: ParallelEvaluatorContext
    ) {
        val roots: MutableMap<SkyKey?, out NodeEntry?> =
            evaluatorContext
                .getGraph()
                .getBatchMap(null, com.google.devtools.build.skyframe.QueryableGraph.Reason.CYCLE_CHECKING, badRoots)
        val quiescingExecutor: QuiescingExecutor =
            ForkJoinQuiescingExecutor.newBuilder()
                .withOwnershipOf(
                    NamedForkJoinPool.newNamedPool("short-circuiting-cycle-detector", numThreads)
                )
                .setErrorClassifier(ParallelEvaluatorErrorClassifier.Companion.instance())
                .build()
        seenNodes.addAll(roots.keySet())
        for (rootEntry in roots.entrySet()) {
            if (evaluatorContext.keepGoing(rootEntry.getKey())) {
                // In keepGoing mode we want to visit the DTC of the cycle root node and mark all undone
                // nodes as being done with a cycle.
                quiescingExecutor.execute(MarkCycle(rootEntry, evaluatorContext, quiescingExecutor))
            }
            // In contrast, in noKeepGoing mode we don't want to touch the graph at all, so that way the
            // inflight nodes get deleted and have the chance to be recreated on future evaluations.
            result.addError(rootEntry.getKey(), CYCLE_ERROR_INFO)
        }
        try {
            quiescingExecutor.awaitQuiescence( /* interruptWorkers= */true)
        } catch (e: SchedulerException) {
            com.google.common.base.Preconditions.checkState(e.getCause() is java.lang.InterruptedException, e)
            throw e.getCause() as java.lang.InterruptedException?
        }
    }

    private inner class MarkCycle(
        mapEntry: MutableMap.MutableEntry<SkyKey?, out NodeEntry>,
        evaluatorContext: ParallelEvaluatorContext,
        executor: QuiescingExecutor
    ) : java.lang.Runnable {
        private val key: SkyKey?
        private val entry: NodeEntry
        private val evaluatorContext: ParallelEvaluatorContext
        private val executor: QuiescingExecutor

        init {
            this.key = mapEntry.getKey()
            this.entry = mapEntry.getValue()
            this.evaluatorContext = evaluatorContext
            this.executor = executor
        }

        override fun run() {
            try {
                var dirtyDeps: MutableList<SkyKey?> = com.google.common.collect.ImmutableList.of<SkyKey?>()
                while (entry.hasUnsignaledDeps()) {
                    entry.signalDep(evaluatorContext.getGraphVersion(), null)
                }
                if (entry.isDirty() && entry.getLifecycleState() == LifecycleState.CHECK_DEPENDENCIES) {
                    // The entry was checking dependencies, but had no dependencies outstanding (otherwise
                    // the signaling loop above would have put it into the NEEDS_REBUILDING state). The only
                    // reason it didn't get another chance to build must have been that we were in a
                    // nokeep_going build. Tolerate that situation, even though it currently only occurs in
                    // tests.
                    // Tell entry we're about to check some of its deps so it lets us build it.
                    //
                    //
                    // TODO(b/456225011): Both the above comment and this code are probably stale.
                    dirtyDeps = entry.getNextDirtyDirectDeps()
                    entry.addTemporaryDirectDepGroup(dirtyDeps)
                    for (dep in dirtyDeps) {
                        entry.signalDep(evaluatorContext.getGraphVersion(), dep)
                    }
                    com.google.common.base.Preconditions.checkState(
                        !entry.hasUnsignaledDeps(), "Entry has unsignaled deps: %s %s", entry, dirtyDeps
                    )
                    com.google.common.base.Preconditions.checkState(
                        entry.getLifecycleState() == LifecycleState.NEEDS_REBUILDING,
                        "Not NEEDS_REBUILDING: %s",
                        entry
                    )
                }
                val deps: MutableMap<SkyKey?, out NodeEntry?> =
                    evaluatorContext
                        .getGraph()
                        .getBatchMap(
                            key,
                            com.google.devtools.build.skyframe.QueryableGraph.Reason.CYCLE_CHECKING,
                            com.google.common.collect.Iterables.concat<SkyKey?>(
                                dirtyDeps,
                                com.google.common.collect.Iterables.concat<SkyKey?>(entry.getTemporaryDirectDeps())
                            )
                        )
                for (depEntry in deps.entrySet()) {
                    if (!depEntry.getValue().isDone() && seenNodes.add(depEntry.getKey())) {
                        executor.execute(MarkCycle(depEntry, evaluatorContext, executor))
                    }
                }
                AbstractParallelEvaluator.Companion.maybeMarkRebuilding(entry)
                val env: SkyFunctionEnvironment =
                    SkyFunctionEnvironment.Companion.createForError(
                        key,
                        entry.getTemporaryDirectDeps(),  /* bubbleErrorInfo= */
                        com.google.common.collect.ImmutableMap.of<SkyKey?, ValueWithMetadata?>(),
                        entry.getAllRemainingDirtyDirectDeps(),
                        evaluatorContext
                    )

                env.setError(entry, CYCLE_ERROR_INFO)
                // We aren't committing cycles in graph order (in fact we visit parents before children), so
                // it's completely possible that one of our not-yet-visited children is not done because it
                // too transitively depends on a cycle.
                val unusedReverseDeps: MutableSet<SkyKey?>? =
                    env.commitAndGetParents(entry,  /* expectDoneDeps= */false)
            } catch (e: java.lang.InterruptedException) {
                throw SchedulerException.Companion.ofInterruption(e, key)
            }
        }
    }

    companion object {
        private val DUMMY_CYCLE_MARKER: SkyFunctionName = SkyFunctionName.Companion.createHermetic("DUMMY_CYCLE_MARKER")

        @SerializationConstant
        val DUMMY_CYCLE_KEY: SkyKey = SkyKey { DUMMY_CYCLE_MARKER }

        @SerializationConstant
        val CYCLE_ERROR_INFO: com.google.devtools.build.skyframe.ErrorInfo =
            com.google.devtools.build.skyframe.ErrorInfo.Companion.fromCycle(
                CycleInfo.Companion.createCycleInfo(
                    com.google.common.collect.ImmutableList.of<SkyKey?>(),
                    com.google.common.collect.ImmutableList.of<SkyKey?>(
                        DUMMY_CYCLE_KEY
                    )
                )
            )
    }
}
