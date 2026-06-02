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
package com.google.devtools.build.lib.cmdline

import java.util.concurrent.ExecutorService
import java.util.concurrent.LinkedBlockingQueue

/**
 * A helper class for performing a custom visitation on the Skyframe graph, using [ ].
 * 
 * 
 * The visitor uses an AbstractQueueVisitor backed by a ThreadPoolExecutor with a thread pool NOT
 * part of the global query evaluation pool to avoid starvation.
 * 
 * 
 * The visitation starts with [InputT]s via [.visitAndWaitForCompletion] which is
 * then converted to [VisitKeyT] through [.preprocessInitialVisit].
 * 
 * @param <InputT> the type of objects provided to initialize visitation
 * @param <VisitKeyT> the type of objects to visit
 * @param <OutputKeyT> the type of the key used to reference a result value
 * @param <OutputResultT> the type of visitation results to process
 * @param <ExceptionT> the exception type that can be thrown during visitation and the callback
 * @param <CallbackT> the callback type accepting `OutputResultT` and may throw `ExceptionT`
</CallbackT></ExceptionT></OutputResultT></OutputKeyT></VisitKeyT></InputT> */
@com.google.devtools.build.lib.concurrent.ThreadSafety.ThreadSafe
abstract class ParallelVisitor<InputT, VisitKeyT, OutputKeyT, OutputResultT, ExceptionT, CallbackT : com.google.devtools.build.lib.cmdline.BatchCallback<OutputResultT?, ExceptionT?>?> protected constructor(
    protected val callback: CallbackT?,
    exceptionClass: java.lang.Class<ExceptionT?>,
    visitBatchSize: Int,
    processResultsBatchSize: Int,
    minPendingTasks: Long,
    batchCallbackSize: Int,
    executor: ExecutorService?,
    visitTaskStatusCallback: VisitTaskStatusCallback
) where ExceptionT : java.lang.Exception?, ExceptionT : com.google.devtools.build.lib.cmdline.QueryExceptionMarkerInterface? {
    protected val exceptionClass: java.lang.Class<ExceptionT?>
    private val visitBatchSize: Int
    private val processResultsBatchSize: Int
    @kotlin.jvm.JvmField
    protected val resultBatchSize: Int
    private val executor: VisitingTaskExecutor
    private val visitTaskStatusCallback: VisitTaskStatusCallback

    /**
     * A queue to store pending visits. These should be unique wrt [ ][.noteAndReturnUniqueVisitationKeys].
     */
    private val visitQueue: LinkedBlockingQueue<VisitKeyT?> = LinkedBlockingQueue<VisitKeyT?>()

    /**
     * The minimum number of pending tasks the scheduler tries to hit. The 3x number is set based on
     * experiments. We do not want to schedule tasks too frequently to miss the benefits of large
     * number of keys being grouped by packages. On the other hand, we want to keep all threads in the
     * pool busy to achieve full capacity. A low number here will cause some of the worker threads to
     * go idle at times before the next scheduling cycle.
     * 
     * 
     * TODO(shazh): Revisit the choice of task target based on real-prod performance.
     */
    private val minPendingTasks: Long

    init {
        this.exceptionClass = exceptionClass
        this.visitBatchSize = visitBatchSize
        this.processResultsBatchSize = processResultsBatchSize
        this.resultBatchSize = batchCallbackSize
        this.visitTaskStatusCallback = visitTaskStatusCallback
        this.executor =
            VisitingTaskExecutor(executor, PARALLEL_VISITOR_ERROR_CLASSIFIER, batchCallbackSize)
        this.minPendingTasks = minPendingTasks
    }

    /** Factory for [ParallelVisitor] instances.  */
    interface Factory<InputT, VisitKeyT, OutputKeyT, OutputResultT, ExceptionT, CallbackT : com.google.devtools.build.lib.cmdline.BatchCallback<OutputResultT?, ExceptionT?>?> where ExceptionT : java.lang.Exception?, ExceptionT : com.google.devtools.build.lib.cmdline.QueryExceptionMarkerInterface? {
        fun create(): ParallelVisitor<InputT?, VisitKeyT?, OutputKeyT?, OutputResultT?, ExceptionT?, CallbackT?>?
    }

    /** A hook for getting notified when a visitation is discovered or completed.  */
    interface VisitTaskStatusCallback {
        fun onVisitTaskDiscovered()

        fun onVisitTaskCompleted()

        companion object {
            @kotlin.jvm.JvmField
            val NULL_INSTANCE: VisitTaskStatusCallback = object : VisitTaskStatusCallback {
                override fun onVisitTaskDiscovered() {}

                override fun onVisitTaskCompleted() {}
            }
        }
    }

    @Throws(ExceptionT::class, java.lang.InterruptedException::class)
    protected abstract fun outputKeysToOutputValues(
        targetKeys: Iterable<OutputKeyT?>?
    ): Iterable<OutputResultT?>?

    /** An object to hold keys to visit and keys ready for processing.  */
    protected inner class Visit(
        private val keysToUseForResult: Iterable<OutputKeyT?>,
        private val keysToVisit: Iterable<VisitKeyT?>?
    )

    @Throws(ExceptionT::class, java.lang.InterruptedException::class)
    fun visitAndWaitForCompletion(keys: Iterable<InputT?>?) {
        noteAndReturnUniqueVisitationKeys(preprocessInitialVisit(keys)).forEach(java.util.function.Consumer { visitKey: VisitKeyT? ->
            this.addToVisitQueue(
                visitKey
            )
        })
        executor.visitAndWaitForCompletion()
    }

    /** Gets the [Visit] representing the local visitation of the given `values`.  */
    @Throws(ExceptionT::class, java.lang.InterruptedException::class)
    protected abstract fun getVisitResult(values: Iterable<VisitKeyT?>?): Visit

    /** Transforms the initial input [InputT]s to [VisitKeyT] to start the visitation.  */
    protected abstract fun preprocessInitialVisit(inputs: Iterable<InputT?>?): Iterable<VisitKeyT?>?

    /**
     * Returns the values that have never been visited before in [.getVisitResult].
     * 
     * 
     * Used to dedupe visitations before adding them to [.visitQueue].
     */
    @Throws(ExceptionT::class)
    protected abstract fun noteAndReturnUniqueVisitationKeys(
        prospectiveVisitationKeys: Iterable<VisitKeyT?>?
    ): Iterable<VisitKeyT?>?

    /** Gets tasks to visit pending keys.  */
    @Throws(java.lang.InterruptedException::class, ExceptionT::class)
    protected open fun getVisitTasks(pendingKeysToVisit: MutableCollection<VisitKeyT?>): Iterable<Task<ExceptionT?>?>? {
        val builder: com.google.common.collect.ImmutableList.Builder<Task<ExceptionT?>?> =
            com.google.common.collect.ImmutableList.builder<Task<ExceptionT?>?>()
        for (keysToVisitBatch in com.google.common.collect.Iterables.partition<VisitKeyT?>(
            pendingKeysToVisit,
            visitBatchSize
        )) {
            builder.add(VisitTask(keysToVisitBatch, exceptionClass))
        }

        return builder.build()
    }

    private fun addToVisitQueue(visitKey: VisitKeyT?) {
        visitQueue.add(visitKey)
        visitTaskStatusCallback.onVisitTaskDiscovered()
    }

    /** A [Runnable] which handles [ExceptionT] and [InterruptedException].  */
    protected abstract class Task<ExceptionT : java.lang.Exception?> internal constructor(exceptionClass: java.lang.Class<ExceptionT?>) :
        java.lang.Runnable {
        protected val exceptionClass: java.lang.Class<ExceptionT?>

        init {
            this.exceptionClass = exceptionClass
        }

        override fun run() {
            try {
                process()
            } catch (e: java.lang.InterruptedException) {
                throw RuntimeInterruptedException(e)
            } catch (e: java.lang.RuntimeException) {
                // Rethrow all RuntimeExceptions so they aren't caught by the following "catch Exception".
                throw e
            } catch (e: java.lang.Exception) {
                // We can't "catch (ExceptionT e)" in Java. Instead we catch all checked exceptions and
                // double-check the type at runtime using the real ExceptionT class object.
                com.google.common.base.Preconditions.checkArgument(
                    exceptionClass.isInstance(e),
                    "got checked exception type %s, expected %s. Thrown exception: %s\nStack Trace: %s",
                    e.getClass(),
                    exceptionClass,
                    e.getMessage(),
                    com.google.common.base.Throwables.getStackTraceAsString(e)
                )
                throw RuntimeCheckedException(e)
            }
        }

        @Throws(ExceptionT::class, java.lang.InterruptedException::class)
        abstract fun process()
    }

    /** A task to visit a batch of [keys][VisitKeyT].  */
    inner class VisitTask(private val keysToVisit: Iterable<VisitKeyT?>, exceptionClass: java.lang.Class<ExceptionT?>) :
        Task<ExceptionT?>(exceptionClass) {
        @Throws(ExceptionT::class, java.lang.InterruptedException::class)
        override fun process() {
            val visit: Visit = getVisitResult(keysToVisit)
            for (keysToUseForResultBatch in com.google.common.collect.Iterables.partition<OutputKeyT?>(
                visit.keysToUseForResult,
                processResultsBatchSize
            )) {
                executor.execute(
                    GetAndProcessUniqueResultsTask(keysToUseForResultBatch, exceptionClass)
                )
            }
            noteAndReturnUniqueVisitationKeys(visit.keysToVisit)
                .forEach(java.util.function.Consumer { visitKey: VisitKeyT? ->
                    this@ParallelVisitor.addToVisitQueue(
                        visitKey
                    )
                })
            keysToVisit.forEach(
                java.util.function.Consumer { key: VisitKeyT? -> this@ParallelVisitor.visitTaskStatusCallback.onVisitTaskCompleted() })
        }
    }

    private inner class GetAndProcessUniqueResultsTask(
        private val uniqueKeysToUseForResult: Iterable<OutputKeyT?>?,
        exceptionClass: java.lang.Class<ExceptionT?>
    ) : Task<ExceptionT?>(exceptionClass) {
        @Throws(ExceptionT::class, java.lang.InterruptedException::class)
        protected override fun process() {
            callback.process(outputKeysToOutputValues(uniqueKeysToUseForResult))
        }
    }

    /**
     * A custom implementation of [QuiescingExecutor] which uses a centralized queue and
     * scheduler for parallel visitations.
     */
    private inner class VisitingTaskExecutor(
        executor: ExecutorService?,
        errorClassifier: com.google.devtools.build.lib.concurrent.ErrorClassifier?,
        private val batchCallbackSize: Int
    ) : com.google.devtools.build.lib.concurrent.AbstractQueueVisitor( /* executorService= */
        executor,  // Leave the thread pool active for other current and future callers.
        com.google.devtools.build.lib.concurrent.AbstractQueueVisitor.ExecutorOwnership.SHARED,
        com.google.devtools.build.lib.concurrent.AbstractQueueVisitor.ExceptionHandlingMode.FAIL_FAST,  /* errorClassifier= */
        errorClassifier
    ) {
        @Throws(ExceptionT::class, java.lang.InterruptedException::class)
        fun visitAndWaitForCompletion() {
            // The scheduler keeps running until either of the following two conditions are met.
            //
            // 1. Errors (ExceptionT or InterruptedException) occurred and visitations should fail
            //    fast.
            // 2. There is no pending visit in the queue and no pending task running.
            while (!mustJobsBeStopped() && moreWorkToDo()) {
                // To achieve maximum efficiency, queue is drained in either of the following two
                // conditions:
                //
                // 1. The number of pending tasks is low. We schedule new tasks to avoid wasting CPU.
                // 2. The process queue size is large.
                if (getTaskCount() < minPendingTasks || visitQueue.size() >= batchCallbackSize) {
                    val pendingKeysToVisit: MutableCollection<VisitKeyT?> =
                        java.util.ArrayList<VisitKeyT?>(visitQueue.size())
                    visitQueue.drainTo(pendingKeysToVisit)
                    for (task in getVisitTasks(pendingKeysToVisit)!!) {
                        execute(task)
                    }
                }

                try {
                    java.lang.Thread.sleep(SCHEDULING_INTERVAL_MILLISECONDS)
                } catch (e: java.lang.InterruptedException) {
                    // If the main thread waiting for completion of the visitation is interrupted, we should
                    // gracefully terminate all running and pending tasks before exit. If ExceptionT
                    // occurred in any of the worker thread, awaitTerminationAndPropagateErrorsIfAny
                    // propagates the ExceptionT instead of InterruptedException.
                    setInterrupted()
                    awaitTerminationAndPropagateErrorsIfAny()
                }
            }

            // We reach here either because the visitation is complete, or because an error prevents us
            // from proceeding with the visitation. awaitTerminationAndPropagateErrorsIfAny will either
            // gracefully exit if the visitation is complete, or propagate the exception if error
            // occurred.
            awaitTerminationAndPropagateErrorsIfAny()
        }

        fun moreWorkToDo(): Boolean {
            // Note that we must check the task count first -- checking the processing queue first has the
            // following race condition:
            // (1) Check processing queue and observe that it is empty
            // (2) A remaining task adds to the processing queue and shuts down
            // (3) We check the task count and observe it is empty
            return getTaskCount() > 0 || !visitQueue.isEmpty()
        }

        // We check against Class<ExceptionT> before creating RuntimeCheckedException.
        @Throws(ExceptionT::class, java.lang.InterruptedException::class)
        fun awaitTerminationAndPropagateErrorsIfAny() {
            try {
                awaitTermination( /*interruptWorkers=*/true)
            } catch (e: RuntimeCheckedException) {
                throw e.getCause() as ExceptionT?
            } catch (e: RuntimeInterruptedException) {
                throw e.getCause() as java.lang.InterruptedException?
            }
        }
    }

    private class RuntimeCheckedException(checkedException: java.lang.Exception?) :
        java.lang.RuntimeException(checkedException)

    private class RuntimeInterruptedException(interruptedException: java.lang.InterruptedException?) :
        java.lang.RuntimeException(interruptedException)

    companion object {
        /**
         * The max time interval between two scheduling passes in milliseconds. A scheduling pass is
         * defined as the scheduler thread determining whether to drain all pending visits from the queue
         * and submitting tasks to perform the visits.
         * 
         * 
         * The choice of 1ms is a result based of experiments. It is an attempted balance due to a few
         * facts about the scheduling interval:
         * 
         * 
         * 1. A large interval adds systematic delay. In an extreme case, a visit which is supposed to
         * take only 1ms now may take 5ms. For most visits which take longer than a few hundred
         * milliseconds, it should not be noticeable.
         * 
         * 
         * 2. A zero-interval config eats too much CPU.
         * 
         * 
         * Even though the scheduler runs once every 1 ms, it does not try to drain it every time.
         * Pending visits are drained only certain criteria are met.
         */
        private const val SCHEDULING_INTERVAL_MILLISECONDS: Long = 1

        /**
         * Fail fast on RuntimeExceptions, including `RuntimeInterruptedException` and `RuntimeCheckedException`, which result from InterruptedException and `<ExceptionT>`.
         * 
         * 
         * Doesn't log for `RuntimeInterruptedException`, which is expected when evaluations are
         * interrupted, or `RuntimeCheckedException`, which happens when expected visitation
         * failures occur.
         */
        private val PARALLEL_VISITOR_ERROR_CLASSIFIER: com.google.devtools.build.lib.concurrent.ErrorClassifier =
            object : com.google.devtools.build.lib.concurrent.ErrorClassifier() {
                override fun classifyException(e: java.lang.Exception?): com.google.devtools.build.lib.concurrent.ErrorClassifier.ErrorClassification {
                    if (e is RuntimeInterruptedException
                        || e is RuntimeCheckedException
                    ) {
                        return com.google.devtools.build.lib.concurrent.ErrorClassifier.ErrorClassification.CRITICAL
                    } else if (e is java.lang.RuntimeException) {
                        return com.google.devtools.build.lib.concurrent.ErrorClassifier.ErrorClassification.CRITICAL_AND_LOG
                    } else {
                        return com.google.devtools.build.lib.concurrent.ErrorClassifier.ErrorClassification.NOT_CRITICAL
                    }
                }
            }
    }
}
