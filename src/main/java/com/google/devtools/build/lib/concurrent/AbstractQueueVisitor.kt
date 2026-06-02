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
package com.google.devtools.build.lib.concurrent

import com.google.common.flogger.GoogleLogger
import java.util.concurrent.BlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.ForkJoinPool
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReadWriteLock
import java.util.concurrent.locks.ReentrantLock
import java.util.concurrent.locks.ReentrantReadWriteLock

/** A [QuiescingExecutor] implementation that wraps an [ExecutorService].  */
open class AbstractQueueVisitor(
    executorService: ExecutorService?,
    /** If `true`, shut down the [ExecutorService] on completion.  */
    private val executorOwnership: ExecutorOwnership?,
    /** If `true`, don't run new actions after an uncaught exception.  */
    private val exceptionHandlingMode: ExceptionHandlingMode?,
    errorClassifier: com.google.devtools.build.lib.concurrent.ErrorClassifier?
) : com.google.devtools.build.lib.concurrent.QuiescingExecutor {
    /**
     * The most severe unhandled exception thrown by a worker thread, according to [ ][.errorClassifier]. This exception gets propagated to the calling thread of [ ][.awaitQuiescence] . We use the most severe error for the sake of not masking e.g. crashes in
     * worker threads after the first critical error that can occur due to race conditions in client
     * code.
     * 
     * 
     * Field updates happen only in blocks that are synchronized on the [ ] object.
     * 
     * 
     * If [AbstractQueueVisitor] clients don't like the semantics of storing and propagating
     * the most severe error, then they should be provide an [ErrorClassifier] that does the
     * right thing (e.g. to cause the _first_ error to be propagated, you'd want to provide an [ ] that gives all errors the exact same [ErrorClassification]).
     * 
     * 
     * Note that this is not a performance-critical path.
     */
    @kotlin.concurrent.Volatile
    private var unhandled: Throwable? = null

    /**
     * An uncaught exception when submitting a job to the [ExecutorService] is catastrophic, and
     * usually indicates a lack of stack space on which to allocate a native thread. The [ ] may reach an inconsistent state in such circumstances, so we avoid blocking on
     * its termination when this field is non-`null`.
     */
    @kotlin.concurrent.Volatile
    private var catastrophe: Throwable? = null

    private val zeroRemainingTasksLock: java.util.concurrent.locks.Lock = ReentrantLock()

    /**
     * A condition object for the condition `remainingTasks.get() == 0 || jobsMustBeStopped`.
     */
    private val zeroRemainingTasksCondition: java.util.concurrent.locks.Condition =
        zeroRemainingTasksLock.newCondition()

    /** The number of [Runnable]s [.execute]-d that have not finished evaluation.  */
    private val remainingTasks: AtomicLong = AtomicLong(0)

    /**
     * A thread that wants to add or remove a future from `outstandingFutures` must first obtain
     * the *read* lock and then check the `threadInterrupted` flag. The thread that
     * cancels all futures must first set the `threadInterrupted` flag, and then obtain the
     * *write* lock. Only once both have happened is the main thread allowed to iterate over
     * the `outstandingFutures`. This allows concurrent future registration, but ensures that
     * canceling only happens when there are no concurrent modifications to the set since that
     * requires iterating over all elements.
     */
    private val outstandingFuturesLock: ReadWriteLock = ReentrantReadWriteLock()

    private val outstandingFutures: MutableSet<com.google.common.util.concurrent.ListenableFuture<*>> =
        com.google.common.collect.Sets.newConcurrentHashSet<com.google.common.util.concurrent.ListenableFuture<*>?>()

    /**
     * Flag used to record when all threads were killed by failed action execution. Only ever
     * transitions from `false` to `true`.
     * 
     * 
     * Except for [.mustJobsBeStopped], may only be accessed in a block that is synchronized
     * on [.zeroRemainingTasks].
     */
    @kotlin.concurrent.Volatile
    private var jobsMustBeStopped = false

    /** Map from thread to number of jobs executing in the thread. Used for interrupt handling.  */
    private val jobs: MutableMap<java.lang.Thread, AtomicLong?> = ConcurrentHashMap<java.lang.Thread, AtomicLong?>()

    private val executorService: ExecutorService

    /** Get the value of the interrupted flag.  */
    /**
     * Flag used to record when the main thread (the thread which called [.awaitQuiescence]) is
     * interrupted.
     * 
     * 
     * When this is `true`, adding tasks to the [ExecutorService] will fail quietly as
     * a part of the process of shutting down the worker threads.
     */
    @get:com.google.devtools.build.lib.concurrent.ThreadSafety.ThreadSafe
    @kotlin.concurrent.Volatile
    protected var isInterrupted: Boolean = false
        private set

    /**
     * Latches used to signal when the visitor has been interrupted or seen an exception. Used only
     * for testing.
     */
    private val interruptedLatch: CountDownLatch = CountDownLatch(1)

    private val exceptionLatch: CountDownLatch = CountDownLatch(1)

    private val errorClassifier: com.google.devtools.build.lib.concurrent.ErrorClassifier

    /**
     * Create the [AbstractQueueVisitor].
     * 
     * @param parallelism a measure of parallelism for the [ExecutorService], such as `parallelism` in [java.util.concurrent.ForkJoinPool], or both `corePoolSize` and
     * `maximumPoolSize` in [ThreadPoolExecutor].
     * @param keepAliveTime the keep-alive time for the [ExecutorService], if applicable.
     * @param units the time units of keepAliveTime.
     * @param exceptionHandlingMode what to do when a task throws an uncaught exception.
     * @param poolName sets the name of threads spawned by the [ExecutorService]. If `null`, default thread naming will be used.
     * @param errorClassifier an error classifier used to determine whether to log and/or stop jobs.
     */
    constructor(
        parallelism: Int,
        keepAliveTime: Long,
        units: TimeUnit,
        exceptionHandlingMode: ExceptionHandlingMode?,
        poolName: String?,
        errorClassifier: com.google.devtools.build.lib.concurrent.ErrorClassifier?
    ) : this(
        com.google.devtools.build.lib.concurrent.AbstractQueueVisitor.Companion.createExecutorService(
            parallelism,
            keepAliveTime,
            units,
            com.google.devtools.build.lib.concurrent.BlockingStack<java.lang.Runnable?>(),
            poolName
        ),
        com.google.devtools.build.lib.concurrent.AbstractQueueVisitor.ExecutorOwnership.PRIVATE,
        exceptionHandlingMode,
        errorClassifier
    )

    /**
     * Whether this [AbstractQueueVisitor] will own the [ExecutorService] it is running
     * tasks on.
     */
    enum class ExecutorOwnership {
        /**
         * Shut down the executor once the visitation is done (after [.awaitQuiescence].
         * 
         * 
         * Callers must not shut down the [ExecutorService] while queue visitors use it.
         */
        PRIVATE,

        /** Keep the executor running after the visitation is done.  */
        SHARED
    }

    /** What to do if a task throws an uncaught exception.  */
    enum class ExceptionHandlingMode {
        /** Don't run new tasks after one throws an uncaught exception.  */
        FAIL_FAST,

        /** Keep running new tasks when one throws an uncaught exception.  */
        KEEP_GOING,
    }

    /**
     * Create the AbstractQueueVisitor.
     * 
     * @param executorService the [ExecutorService] to use.
     * @param executorOwnership whether the [AbstractQueueVisitor] being created owns the [     ] it uses.
     * @param exceptionHandlingMode what to do when a task throws an uncaught exception.
     * @param errorClassifier an error classifier used to determine whether to log and/or stop jobs.
     */
    init {
        this.executorService = com.google.common.base.Preconditions.checkNotNull<ExecutorService>(executorService)
        this.errorClassifier =
            com.google.common.base.Preconditions.checkNotNull<com.google.devtools.build.lib.concurrent.ErrorClassifier>(
                errorClassifier
            )
    }

    @Throws(java.lang.InterruptedException::class)
    override fun awaitQuiescence(interruptWorkers: Boolean) {
        if (catastrophe != null) {
            com.google.common.base.Throwables.throwIfUnchecked(catastrophe)
        }
        try {
            zeroRemainingTasksLock.lock()
            try {
                while (remainingTasks.get() != 0L && !jobsMustBeStopped) {
                    zeroRemainingTasksCondition.await()
                }
            } finally {
                zeroRemainingTasksLock.unlock()
            }
        } catch (e: java.lang.InterruptedException) {
            // Mark the visitor, so that it's known to be interrupted, and
            // then break out of here, stop the worker threads and return ASAP,
            // sending the interruption to the parent thread.
            setInterrupted()
        }

        awaitTermination(interruptWorkers)
    }

    @Throws(java.lang.InterruptedException::class)
    override fun awaitQuiescenceWithoutShutdown(interruptWorkers: Boolean) {
        if (catastrophe != null) {
            com.google.common.base.Throwables.throwIfUnchecked(catastrophe)
        }
        zeroRemainingTasksLock.lock()
        try {
            while (remainingTasks.get() != 0L && !jobsMustBeStopped) {
                zeroRemainingTasksCondition.await()
            }
        } finally {
            zeroRemainingTasksLock.unlock()
        }
    }

    /**
     * Schedules a [runnable][Runnable] to be executed in a worker thread.
     * 
     * 
     * The [runnable][Runnable] is not guaranteed to be executed since it is possible
     * that the thread where the [runnable][Runnable] is scheduled blocks new actions or has
     * already been interrupted. For more details, see:
     * 
     * 
     *  * [WrappedRunnable.run] immediate returns without executing the `originalRunnable` when [.blockNewActions] returns true,
     *  * [.recordError] swallows [RejectedExecutionException] thrown by the
     * interrupted thread.
     * 
     */
    override fun execute(runnable: java.lang.Runnable) {
        executeWithExecutorService(runnable, executorService)
    }

    protected fun executeWithExecutorService(runnable: java.lang.Runnable, executorService: ExecutorService) {
        val wrappedRunnable: WrappedRunnable =
            com.google.devtools.build.lib.concurrent.AbstractQueueVisitor.WrappedRunnable(runnable)
        try {
            // It's impossible for this increment to result in remainingTasks.get <= 0 because
            // remainingTasks is never negative. Therefore it isn't necessary to check its value for
            // the purpose of updating zeroRemainingTasks.
            val tasks: Long = remainingTasks.incrementAndGet()
            com.google.common.base.Preconditions.checkState(
                tasks > 0,
                "Incrementing remaining tasks counter resulted in impossible non-positive number."
            )
            executeWrappedRunnable(wrappedRunnable, executorService)
        } catch (e: Throwable) {
            if (!wrappedRunnable.ran) {
                // Note that keeping track of ranTask is necessary to disambiguate the case where
                // execute() itself failed, vs. a caller-runs policy on pool exhaustion, where the
                // runnable threw. To be extra cautious, we decrement the task count in a finally
                // block, even though the CountDownLatch is unlikely to throw.
                recordError(e)
            }
        }
    }

    protected open fun executeWrappedRunnable(runnable: WrappedRunnable?, executorService: ExecutorService) {
        executorService.execute(runnable)
    }

    @kotlin.jvm.Synchronized
    private fun maybeSaveUnhandledThrowable(e: Throwable?, markToStopJobs: Boolean) {
        var critical = false
        val errorClassification: com.google.devtools.build.lib.concurrent.ErrorClassifier.ErrorClassification =
            errorClassifier.classify(e)
        when (errorClassification) {
            com.google.devtools.build.lib.concurrent.ErrorClassifier.ErrorClassification.AS_CRITICAL_AS_POSSIBLE, com.google.devtools.build.lib.concurrent.ErrorClassifier.ErrorClassification.CRITICAL_AND_LOG -> {
                critical = true
                com.google.devtools.build.lib.concurrent.AbstractQueueVisitor.Companion.logger.atWarning().withCause(e)
                    .log("Found critical error in queue visitor")
            }

            com.google.devtools.build.lib.concurrent.ErrorClassifier.ErrorClassification.CRITICAL -> critical = true
            else -> {}
        }
        if (unhandled == null
            || errorClassification.compareTo(errorClassifier.classify(unhandled)) > 0
        ) {
            // Save the most severe error.
            unhandled = e
            exceptionLatch.countDown()
        }
        if (markToStopJobs) {
            zeroRemainingTasksLock.lock()
            try {
                if (critical && !jobsMustBeStopped) {
                    jobsMustBeStopped = true
                    // This introduces a benign race, but it's the best we can do. When we have multiple
                    // errors of the same severity that is at least CRITICAL, we'll end up saving (above) and
                    // propagating (in 'awaitQuiescence') the most severe one we see, but the set of errors we
                    // see is non-deterministic and is at the mercy of how quickly the calling thread of
                    // 'awaitQuiescence' can do its thing after this 'notify' call.
                    zeroRemainingTasksCondition.signal()
                }
            } finally {
                zeroRemainingTasksLock.unlock()
            }
        }
    }

    private fun recordError(e: Throwable?) {
        try {
            // If threadInterrupted is true, then RejectedExecutionExceptions are expected. There's no
            // need to remember them, but there is a need to call decrementRemainingTasks, which is
            // satisfied by the finally block below.
            if (e is RejectedExecutionException && this.isInterrupted) {
                return
            }
            catastrophe = e
            maybeSaveUnhandledThrowable(e,  /*markToStopJobs=*/false)
        } finally {
            decrementRemainingTasks()
        }
    }

    /**
     * A wrapped [Runnable] that:
     * 
     * 
     *  * Sets [.run] to `true` when `WrappedRunnable` is run,
     *  * Records the thread evaluating `r` in [.jobs] while `r` is evaluated,
     *  * Prevents [.originalRunnable] from being invoked if [.blockNewActions] returns
     * `true`,
     *  * Synchronously invokes `runnable.run()`,
     *  * Catches any [Throwable] thrown by `runnable.run()`, and if it is the most
     * severe [Throwable] seen by this [AbstractQueueVisitor], assigns it to [       ][.unhandled], and sets [.jobsMustBeStopped] if necessary,
     *  * And, lastly, calls [.decrementRemainingTasks].
     * 
     */
    protected inner class WrappedRunnable private constructor(originalRunnable: java.lang.Runnable) :
        java.lang.Runnable, Comparable<WrappedRunnable?> {
        private val originalRunnable: java.lang.Runnable

        @kotlin.concurrent.Volatile
        private var ran = false

        init {
            this.originalRunnable = originalRunnable
        }

        override fun run() {
            ran = true
            var thread: java.lang.Thread? = null
            var addedJob = false
            try {
                thread = java.lang.Thread.currentThread()
                addJob(thread)
                addedJob = true
                if (blockNewActions()) {
                    // Make any newly enqueued tasks quickly die. We check after adding to the jobs map so
                    // that if another thread is racing to kill this thread and didn't make it before this
                    // conditional, it will be able to find and kill this thread anyway.
                    return
                }
                originalRunnable.run()
            } catch (e: Throwable) {
                maybeSaveUnhandledThrowable(e,  /*markToStopJobs=*/true)
            } finally {
                try {
                    if (thread != null && addedJob) {
                        removeJob(thread)
                    }
                } finally {
                    decrementRemainingTasks()
                }
            }
        }

        override fun compareTo(o: WrappedRunnable): Int {
            // This should only be called when the concrete class is submitting comparable runnables.
            return (originalRunnable as Comparable<*>).compareTo(o.originalRunnable)
        }
    }

    private fun addJob(thread: java.lang.Thread?) {
        jobs.computeIfAbsent(thread, java.util.function.Function { k: java.lang.Thread? -> AtomicLong() })
            .incrementAndGet()
    }

    private fun removeJob(thread: java.lang.Thread?) {
        if (jobs.get(thread).decrementAndGet() == 0L) {
            jobs.remove(thread)
        }
    }

    /** Set an internal flag to show that an interrupt was detected.  */
    protected fun setInterrupted() {
        this.isInterrupted = true
    }

    private fun decrementRemainingTasks() {
        // This decrement statement may result in remainingTasks.get() == 0, so it must be checked
        // and the zeroRemainingTasks condition object notified if that condition is obtained.
        val tasks: Long = remainingTasks.decrementAndGet()
        com.google.common.base.Preconditions.checkState(
            tasks >= 0, "Decrementing remaining tasks counter resulted in impossible negative number."
        )
        if (tasks == 0L) {
            zeroRemainingTasksLock.lock()
            try {
                zeroRemainingTasksCondition.signal()
            } finally {
                zeroRemainingTasksLock.unlock()
            }
        }
    }

    /** If this returns true, don't enqueue new actions.  */
    protected fun blockNewActions(): Boolean {
        return this.isInterrupted
                || (unhandled != null && exceptionHandlingMode == com.google.devtools.build.lib.concurrent.AbstractQueueVisitor.ExceptionHandlingMode.FAIL_FAST)
    }

    @get:com.google.common.annotations.VisibleForTesting
    val exceptionLatchForTestingOnly: CountDownLatch
        get() = exceptionLatch

    @get:com.google.common.annotations.VisibleForTesting
    val interruptionLatchForTestingOnly: CountDownLatch
        get() = interruptedLatch

    val taskCount: Long
        /**
         * Get number of jobs remaining. Note that this can increase in value if running tasks submit
         * further jobs.
         */
        get() = remainingTasks.get()

    protected fun getExecutorService(): ExecutorService {
        return executorService
    }

    @Throws(java.lang.InterruptedException::class)
    override fun dependOnFuture(future: com.google.common.util.concurrent.ListenableFuture<*>) {
        outstandingFuturesLock.readLock().lock()
        try {
            if (this.isInterrupted) {
                future.cancel( /*mayInterruptIfRunning=*/true)
                throw java.lang.InterruptedException()
            }
            remainingTasks.incrementAndGet()
            outstandingFutures.add(future)
            future.addListener(
                java.lang.Runnable { markFutureDone(future) },
                com.google.common.util.concurrent.MoreExecutors.directExecutor()
            )
        } finally {
            outstandingFuturesLock.readLock().unlock()
        }
    }

    private fun markFutureDone(future: com.google.common.util.concurrent.ListenableFuture<*>?) {
        decrementRemainingTasks()
        outstandingFuturesLock.readLock().lock()
        try {
            if (this.isInterrupted) {
                // Since futures get worked on asynchronously, there is an inherent race between this method
                // being called and the future getting canceled. If we get here, the other thread either
                // already attempted to cancel the future, or is just about to do so. In either case, no
                // need to throw or do anything with outstandingFutures here.
                return
            }
            outstandingFutures.remove(future)
        } finally {
            outstandingFuturesLock.readLock().unlock()
        }
    }

    /**
     * Whether all running and pending jobs will be stopped or cancelled. Also newly submitted tasks
     * will be rejected if this is true.
     * 
     * 
     * This function returns the CURRENT state of whether jobs should be stopped. If the value is
     * false right now, it may be changed to true by another thread later.
     */
    protected fun mustJobsBeStopped(): Boolean {
        return jobsMustBeStopped
    }

    /**
     * Waits for the task queue to drain. Then if `ownExecutorService` is true, shuts down the
     * [ExecutorService] and waits for it to terminate. Throws (the same) unchecked exception if
     * any worker thread failed unexpectedly.
     */
    @Throws(java.lang.InterruptedException::class)
    fun awaitTermination(interruptWorkers: Boolean) {
        reallyAwaitTermination(interruptWorkers)

        if (this.isInterrupted) {
            // Set interrupted bit on current thread so that callers can see that it was interrupted. Note
            // that if the thread was interrupted while awaiting termination, we might not hit this
            // code path, but then the current thread's interrupt bit is already set, so we are fine.
            java.lang.Thread.currentThread().interrupt()
        }
        // Throw the first unhandled (worker thread) exception in the main thread. We throw an unchecked
        // exception instead of InterruptedException if both are present because an unchecked exception
        // may indicate a catastrophic failure that should shut down the program. The caller can
        // check the interrupted bit if they will handle the unchecked exception without crashing.
        com.google.common.base.Throwables.propagateIfPossible(unhandled)

        if (java.lang.Thread.interrupted()) {
            throw java.lang.InterruptedException()
        }
    }

    private fun reallyAwaitTermination(interruptWorkers: Boolean) {
        // TODO(bazel-team): verify that interrupt() is safe for every use of
        // AbstractQueueVisitor and remove the interruptWorkers flag.
        if (interruptWorkers && !jobs.isEmpty()) {
            interruptInFlightTasks()
        }
        if (interruptWorkers) {
            // If the computation is done, this does nothing because there are no outstanding futures. Do
            // not predicate on outstandingFutures.isEmpty() here: in the case of an interrupt, there may
            // still be threads concurrently adding futures to the set, and we need to make sure that
            // those are canceled correctly.
            cancelAllFutures()
        }

        if (this.isInterrupted) {
            interruptedLatch.countDown()
        }

        com.google.common.base.Throwables.propagateIfPossible(catastrophe)
        zeroRemainingTasksLock.lock()
        try {
            while (remainingTasks.get() != 0L) {
                try {
                    zeroRemainingTasksCondition.await()
                } catch (e: java.lang.InterruptedException) {
                    setInterrupted()
                }
            }
        } finally {
            zeroRemainingTasksLock.unlock()
        }

        if (executorOwnership == com.google.devtools.build.lib.concurrent.AbstractQueueVisitor.ExecutorOwnership.PRIVATE) {
            shutdownExecutorService(catastrophe)
        }
    }

    protected open fun shutdownExecutorService(catastrophe: Throwable?) {
        executorService.shutdown()
        while (true) {
            try {
                com.google.common.base.Throwables.propagateIfPossible(catastrophe)
                executorService.awaitTermination(java.lang.Integer.MAX_VALUE.toLong(), TimeUnit.SECONDS)
                break
            } catch (e: java.lang.InterruptedException) {
                setInterrupted()
            }
        }
    }

    private fun interruptInFlightTasks() {
        val thisThread: java.lang.Thread? = java.lang.Thread.currentThread()
        for (thread in jobs.keySet()) {
            if (thisThread !== thread) {
                thread.interrupt()
            }
        }
    }

    private fun cancelAllFutures() {
        // Nobody else can modify outstandingFutures while this thread is holding the write lock.
        outstandingFuturesLock.writeLock().lock()
        try {
            for (future in outstandingFutures) {
                future.cancel( /*mayInterruptIfRunning=*/true)
            }
            outstandingFutures.clear()
        } finally {
            outstandingFuturesLock.writeLock().unlock()
        }
    }

    companion object {
        private val logger: GoogleLogger = GoogleLogger.forEnclosingClass()

        /**
         * Default function for constructing [ThreadPoolExecutor]s. The [ThreadPoolExecutor]s
         * this creates have the same value for `corePoolSize` and `maximumPoolSize` because
         * that results in a fixed-size thread pool, and the current use cases for [ ] don't require any more sophisticated thread pool size management.
         * 
         * 
         * If client use cases change, they may invoke one of the [ ][AbstractQueueVisitor.AbstractQueueVisitor] constructors that accepts a pre-constructed [ ].
         */
        private fun createExecutorService(
            parallelism: Int,
            keepAliveTime: Long,
            units: TimeUnit,
            workQueue: BlockingQueue<java.lang.Runnable?>,
            poolName: String?
        ): ExecutorService {
            return ThreadPoolExecutor( /*corePoolSize=*/
                parallelism,  /*maximumPoolSize=*/
                parallelism,
                keepAliveTime,
                units,
                workQueue,
                com.google.common.util.concurrent.ThreadFactoryBuilder()
                    .setNameFormat(com.google.common.base.Preconditions.checkNotNull<String?>(poolName) + " %d")
                    .build()
            )
        }

        @kotlin.jvm.JvmStatic
        fun createExecutorService(parallelism: Int, poolName: String?): ExecutorService {
            return com.google.devtools.build.lib.concurrent.NamedForkJoinPool.Companion.newNamedPool(
                poolName,
                parallelism
            )
        }

        fun createWithExecutorService(
            executorService: ExecutorService?,
            exceptionHandlingMode: ExceptionHandlingMode?,
            errorClassifier: com.google.devtools.build.lib.concurrent.ErrorClassifier?
        ): AbstractQueueVisitor? {
            if (executorService is ForkJoinPool) {
                return com.google.devtools.build.lib.concurrent.ForkJoinQuiescingExecutor.Companion.newBuilder()
                    .withOwnershipOf(executorService as ForkJoinPool)
                    .setErrorClassifier(errorClassifier)
                    .build()
            }
            return com.google.devtools.build.lib.concurrent.AbstractQueueVisitor(
                executorService,
                com.google.devtools.build.lib.concurrent.AbstractQueueVisitor.ExecutorOwnership.PRIVATE,
                exceptionHandlingMode,
                errorClassifier
            )
        }

        fun create(
            name: String?, parallelism: Int, errorClassifier: com.google.devtools.build.lib.concurrent.ErrorClassifier?
        ): AbstractQueueVisitor? {
            return com.google.devtools.build.lib.concurrent.AbstractQueueVisitor.Companion.createWithExecutorService(
                com.google.devtools.build.lib.concurrent.NamedForkJoinPool.Companion.newNamedPool(name, parallelism),
                com.google.devtools.build.lib.concurrent.AbstractQueueVisitor.ExceptionHandlingMode.KEEP_GOING,  // Not actually used.
                errorClassifier
            )
        }
    }
}
