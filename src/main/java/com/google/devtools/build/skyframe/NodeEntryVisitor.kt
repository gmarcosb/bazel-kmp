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

import com.google.devtools.build.lib.concurrent.MultiThreadPoolsQuiescingExecutor

/**
 * Threadpool manager for [ParallelEvaluator]. Wraps a [QuiescingExecutor] and keeps
 * track of pending nodes.
 */
internal class NodeEntryVisitor(
    quiescingExecutor: QuiescingExecutor,
    progressReceiver: InflightTrackingProgressReceiver,
    runnableMaker: RunnableMaker,
    stateCache: com.github.benmanes.caffeine.cache.Cache<SkyKey?, SkyKeyComputeState?>
) {
    private val quiescingExecutor: QuiescingExecutor
    private val preventNewEvaluations: AtomicBoolean = AtomicBoolean(false)
    private val crashes: MutableSet<java.lang.RuntimeException?> =
        com.google.common.collect.Sets.newConcurrentHashSet<java.lang.RuntimeException?>()
    private val progressReceiver: InflightTrackingProgressReceiver

    /**
     * Function that allows this visitor to execute the appropriate [Runnable] when given a
     * [SkyKey] to evaluate.
     */
    private val runnableMaker: RunnableMaker

    private val partialReevaluationRunnableMaker: RunnableMaker
    private val stateCache: com.github.benmanes.caffeine.cache.Cache<SkyKey?, SkyKeyComputeState?>

    /**
     * This state enum is used with [.partialReevaluationStates] to describe, for each [ ] opting into partial reevaluation, a state describing its partial reevaluation status.
     * 
     * 
     * Along with the values specified in the enum, the absence of an entry for a key in the map
     * means something: that no evaluation of the key's [SkyFunction] is currently happening.
     */
    internal enum class PartialReevaluationState {
        /**
         * This state means that an evaluation of the key's [SkyFunction] has been called for via
         * either [.enqueueEvaluation] or [.enqueuePartialReevaluation]. The evaluation
         * might be currently underway, or may be pending in [.quiescingExecutor], or is about to
         * be scheduled with [.quiescingExecutor].
         */
        EVALUATING,

        /**
         * This state means that either [.enqueueEvaluation] or [ ][.enqueuePartialReevaluation] was called for the key while it was already in an [ ][.EVALUATING] state. Because it is unknown whether the "current" [SkyFunction]
         * evaluation (i.e. the one associated with its original `null` to `EVALUATING`
         * state transition) has been able to observe the newly completed signaling dep's value, the
         * signaled dep must be given another chance.
         * 
         * 
         * After that current evaluation completes, it will be scheduled again.
         */
        EVALUATING_SIGNALED,
    }

    private val partialReevaluationStates: ConcurrentHashMap<SkyKey?, PartialReevaluationState> =
        ConcurrentHashMap<SkyKey?, PartialReevaluationState>()

    private inner class PartialReevaluationRunnableMaker : RunnableMaker {
        override fun make(key: SkyKey?): java.lang.Runnable {
            val inner: java.lang.Runnable = runnableMaker.make(key)
            return java.lang.Runnable {
                var state: PartialReevaluationState? = PartialReevaluationState.EVALUATING
                while (state == PartialReevaluationState.EVALUATING) {
                    inner.run()
                    state =
                        partialReevaluationStates.compute(
                            key,
                            java.util.function.BiFunction { k: SkyKey?, s: PartialReevaluationState? ->
                                com.google.common.base.Preconditions.checkNotNull<PartialReevaluationState?>(
                                    s,
                                    "Null state during evaluation: %s",
                                    k
                                )
                                when (s) {
                                    PartialReevaluationState.EVALUATING ->                         // Note that returning null from this compute function causes the entry to
                                        // be removed from the map.
                                        return@compute null

                                    PartialReevaluationState.EVALUATING_SIGNALED -> return@compute PartialReevaluationState.EVALUATING
                                }
                                throw java.lang.AssertionError(s)
                            })
                }
            }
        }
    }

    init {
        this.quiescingExecutor = quiescingExecutor
        this.progressReceiver = progressReceiver
        this.runnableMaker = runnableMaker
        this.partialReevaluationRunnableMaker = PartialReevaluationRunnableMaker()
        this.stateCache = stateCache
    }

    @Throws(java.lang.InterruptedException::class)
    fun waitForCompletion() {
        quiescingExecutor.awaitQuiescence( /* interruptWorkers= */true)
    }

    /**
     * Enqueue `key` for evaluation.
     * 
     * 
     * This won't immediately enqueue `key` if `key.supportsPartialReevaluation()` and
     * a partial reevaluation is currently running, but that reevaluation will be immediately followed
     * by another reevaluation.
     */
    fun enqueueEvaluation(key: SkyKey, signalingDep: SkyKey?) {
        if (key.supportsPartialReevaluation()) {
            enqueuePartialReevaluation(key, signalingDep)
        } else {
            innerEnqueueEvaluation(key, runnableMaker)
        }
    }

    /**
     * Registers a listener with all passed futures that causes the node to be re-enqueued when all
     * futures are completed.
     */
    @Throws(java.lang.InterruptedException::class)
    fun registerExternalDeps(
        skyKey: SkyKey,
        entry: NodeEntry,
        externalDeps: MutableList<com.google.common.util.concurrent.ListenableFuture<*>?>
    ) {
        // Generally speaking, there is no ordering guarantee for listeners registered with a single
        // listenable future. If we used a listener here, there would be a potential race condition
        // between re-enqueuing the key and notifying the quiescing executor, in which case the executor
        // could shut down even though the work is not done yet. That would be bad.
        //
        // However, the whenAllComplete + run API guarantees that the Runnable is run before the
        // returned future completes, i.e., before the quiescing executor is notified.
        val future: com.google.common.util.concurrent.ListenableFuture<*> =
            com.google.common.util.concurrent.Futures.whenAllComplete<Any?>(externalDeps)
                .run(
                    java.lang.Runnable {
                        if (entry.signalDep(entry.getVersion(), null)) {
                            enqueueEvaluation(skyKey, null)
                        }
                    },
                    com.google.common.util.concurrent.MoreExecutors.directExecutor()
                )
        quiescingExecutor.dependOnFuture(future)
    }

    /**
     * Returns whether any new evaluations should be prevented.
     * 
     * 
     * If called from within node evaluation, the caller may use the return value to determine
     * whether it is responsible for throwing an exception to halt evaluation at the executor level.
     */
    fun shouldPreventNewEvaluations(): Boolean {
        return preventNewEvaluations.get()
    }

    /**
     * Stop any new evaluations from being enqueued. Returns whether this was the first thread to
     * request a halt.
     * 
     * 
     * If called from within node evaluation, the caller may use the return value to determine
     * whether it is responsible for throwing an exception to halt evaluation at the executor level.
     */
    fun preventNewEvaluations(): Boolean {
        return preventNewEvaluations.compareAndSet(false, true)
    }

    fun noteCrash(e: java.lang.RuntimeException?) {
        crashes.add(e)
    }

    fun getCrashes(): MutableCollection<java.lang.RuntimeException?> {
        return crashes
    }

    @com.google.common.annotations.VisibleForTesting
    fun getExceptionLatchForTestingOnly(): CountDownLatch {
        return quiescingExecutor.exceptionLatchForTestingOnly
    }

    private fun enqueuePartialReevaluation(key: SkyKey?, signalingDep: SkyKey?) {
        val mailbox: PartialReevaluationMailbox = getMailbox(key)
        if (signalingDep != null) {
            mailbox.signal(signalingDep)
        } else {
            mailbox.enqueuedNotByDeps()
        }

        val reevaluationState: PartialReevaluationState =
            partialReevaluationStates.compute(
                key,
                java.util.function.BiFunction { k: SkyKey?, s: PartialReevaluationState ->
                    if (s == null)
                        PartialReevaluationState.EVALUATING
                    else
                        PartialReevaluationState.EVALUATING_SIGNALED
                })
        if (reevaluationState == PartialReevaluationState.EVALUATING) {
            innerEnqueueEvaluation(key, partialReevaluationRunnableMaker)
        }
    }

    private fun getMailbox(key: SkyKey?): PartialReevaluationMailbox {
        return PartialReevaluationMailbox.Companion.from(
            stateCache.get(
                key,
                java.util.function.Function { k: SkyKey? -> ClassToInstanceMapSkyKeyComputeState() }) as ClassToInstanceMapSkyKeyComputeState?
        )
    }

    private fun innerEnqueueEvaluation(key: SkyKey?, runnableMakerToUse: RunnableMaker) {
        if (shouldPreventNewEvaluations()) {
            // If an error happens in nokeep_going mode, we still want to mark these nodes as inflight,
            // otherwise cleanup will not happen properly.
            progressReceiver.enqueueAfterError(key)
            return
        }
        progressReceiver.enqueueing(key)

        val runnable: java.lang.Runnable = runnableMakerToUse.make(key)
        if (quiescingExecutor
                    is MultiThreadPoolsQuiescingExecutor
        ) {
            val threadPoolType: ThreadPoolType?
            if (key is CPUHeavySkyKey) {
                threadPoolType = ThreadPoolType.CPU_HEAVY
            } else if (quiescingExecutor.hasSeparatePoolForExecutionTasks()
                && key is ExecutionPhaseSkyKey
            ) {
                // Only possible with --experimental_merged_skyframe_analysis_execution.
                threadPoolType = ThreadPoolType.EXECUTION_PHASE
            } else {
                threadPoolType = ThreadPoolType.REGULAR
            }
            quiescingExecutor.execute(
                runnable,
                threadPoolType,  /* shouldStallAwaitingSignal= */
                key is StallableSkykey
            )
        } else {
            quiescingExecutor.execute(runnable)
        }
    }
}
