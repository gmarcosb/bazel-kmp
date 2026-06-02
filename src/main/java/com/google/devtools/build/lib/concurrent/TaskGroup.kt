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
package com.google.devtools.build.lib.concurrent

import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.ExecutionException
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** An API for structured concurrency, inspired by JDK's `StructuredTaskScope`.  */
class TaskGroup<T, R> private constructor(
    threadFactory: ThreadFactory,
    policy: Policy<in T?>,
    joiner: Joiner<in T?, out R?>
) : java.lang.AutoCloseable {
    private val threadFactory: ThreadFactory
    private val policy: Policy<in T?>
    private val joiner: Joiner<in T?, out R?>

    private val owner: java.lang.Thread?
    private val threads: MutableSet<java.lang.Thread?>

    private enum class TaskGroupState {
        NEW,
        FORKED,  // subtasks forked, need to join.
        JOIN_STARTED,  // join started, can no longer fork
        JOIN_COMPLETED,  // join completed
        CLOSED
    }

    // state, only accessed by owner thread
    private var state: TaskGroupState

    // set or read by any thread
    private val cancelled: AtomicBoolean

    // set to 1 + number of subtasks forked and not yet joined
    private val latch: com.google.devtools.build.lib.concurrent.IncrementableCountDownLatch

    init {
        this.threadFactory = threadFactory
        this.policy = policy
        this.joiner = joiner
        this.owner = java.lang.Thread.currentThread()
        this.threads = com.google.common.collect.Sets.newConcurrentHashSet<java.lang.Thread?>()
        this.latch = com.google.devtools.build.lib.concurrent.IncrementableCountDownLatch(1)
        this.state = com.google.devtools.build.lib.concurrent.TaskGroup.TaskGroupState.NEW
        this.cancelled = AtomicBoolean(false)
    }

    private fun ensureOwner() {
        check(java.lang.Thread.currentThread() === owner) { "Current thread not owner" }
    }

    private fun ensureNotJoined() {
        check(state.compareTo(com.google.devtools.build.lib.concurrent.TaskGroup.TaskGroupState.FORKED) <= 0) { "Already joined or task group is closed" }
    }

    private fun ensureJoinedIfOwner() {
        check(!(java.lang.Thread.currentThread() === owner && state.compareTo(com.google.devtools.build.lib.concurrent.TaskGroup.TaskGroupState.JOIN_STARTED) <= 0)) { "join not called" }
    }

    /**
     * Forks a subtask to be executed in a new thread. The new thread execute the subtasks
     * concurrently with the current thread.
     * 
     * 
     * If a new thread cannot be created, a [RejectedExecutionException] is thrown.
     * 
     * 
     * If the task completes successfully, the result is available through [Subtask.get]. If
     * the task fails, the exception is available through [Subtask.exception]. If the task group
     * is cancelled, the task is not started, neither method can be used to obtain the outcome.
     * 
     * @throws IllegalStateException if not called from the owner thread, or if the task group is
     * already joined.
     */
    @com.google.errorprone.annotations.CanIgnoreReturnValue
    fun <U : T?> fork(task: java.util.concurrent.Callable<out U?>): Subtask<U?> {
        ensureOwner()
        ensureNotJoined()

        val subtask: SubtaskImpl<U?> = com.google.devtools.build.lib.concurrent.TaskGroup.SubtaskImpl<U?>(this, task)

        if (!cancelled.get()) {
            val thread: java.lang.Thread = threadFactory.newThread(subtask)
            if (thread == null) {
                throw RejectedExecutionException("Rejected by thread factory")
            }
            latch.increment()
            thread.start()
        }

        state = com.google.devtools.build.lib.concurrent.TaskGroup.TaskGroupState.FORKED
        return subtask
    }

    @com.google.errorprone.annotations.CanIgnoreReturnValue
    fun <U : T?> fork(task: java.lang.Runnable): Subtask<U?> {
        return fork<U?>(
            java.util.concurrent.Callable {
                task.run()
                null
            })
    }

    /**
     * Returns a result or throws per the [Joiner], after waiting for subtasks to complete per
     * the [Policy].
     * 
     * 
     * This method must be called if [.fork] has been called at least once. Once it returns
     * without interruption, it must not be called again.
     * 
     * @throws IllegalStateException if called from a thread other than the owner
     * @throws InterruptedException if interrupted while waiting for subtasks to complete
     */
    @com.google.errorprone.annotations.CanIgnoreReturnValue
    @Throws(ExecutionException::class, java.lang.InterruptedException::class)
    fun join(): R? {
        ensureOwner()
        ensureNotJoined()

        state = com.google.devtools.build.lib.concurrent.TaskGroup.TaskGroupState.JOIN_STARTED

        latch.countDown()
        // If the await is interrupted, the group will be cancelled inside {@link #close}.
        latch.await()

        state = com.google.devtools.build.lib.concurrent.TaskGroup.TaskGroupState.JOIN_COMPLETED

        try {
            return joiner.result()
        } catch (e: Throwable) {
            throw ExecutionException(e)
        }
    }

    /**
     * Similar to [.join], but throws the checked exception from the subtasks instead of
     * wrapping them in an [ExecutionException]. If a subtask throws an exception that doesn't
     * match the given class, an [IllegalStateException] is thrown with the cause set to the
     * actual exception.
     */
    @Throws(E::class, java.lang.InterruptedException::class)
    fun <E : java.lang.Exception?> joinOrThrow(exceptionClass: java.lang.Class<E?>?): R? {
        return joinOrThrowInternal<E?, java.lang.RuntimeException?, java.lang.RuntimeException?>(
            exceptionClass,
            null,
            null
        )
    }

    /**
     * Similar to [.join], but throws the checked exception from the subtasks instead of
     * wrapping them in an [ExecutionException]. If a subtask throws an exception that doesn't
     * match the given class, an [IllegalStateException] is thrown with the cause set to the
     * actual exception.
     */
    @Throws(E1::class, E2::class, java.lang.InterruptedException::class)
    fun <E1 : java.lang.Exception?, E2 : java.lang.Exception?> joinOrThrow(
        exceptionClass1: java.lang.Class<E1?>?, exceptionClass2: java.lang.Class<E2?>?
    ): R? {
        return joinOrThrowInternal<E1?, E2?, java.lang.RuntimeException?>(exceptionClass1, exceptionClass2, null)
    }

    /**
     * Similar to [.join], but throws the checked exception from the subtasks instead of
     * wrapping them in an [ExecutionException]. If a subtask throws an exception that doesn't
     * match the given class, an [IllegalStateException] is thrown with the cause set to the
     * actual exception.
     */
    @Throws(E1::class, E2::class, E3::class, java.lang.InterruptedException::class)
    fun <E1 : java.lang.Exception?, E2 : java.lang.Exception?, E3 : java.lang.Exception?> joinOrThrow(
        exceptionClass1: java.lang.Class<E1?>?,
        exceptionClass2: java.lang.Class<E2?>?,
        exceptionClass3: java.lang.Class<E3?>?
    ): R? {
        return joinOrThrowInternal<E1?, E2?, E3?>(exceptionClass1, exceptionClass2, exceptionClass3)
    }

    @Throws(E1::class, E2::class, E3::class, java.lang.InterruptedException::class)
    private fun <E1 : java.lang.Exception?, E2 : java.lang.Exception?, E3 : java.lang.Exception?> joinOrThrowInternal(
        exceptionClass1: java.lang.Class<E1?>?,
        exceptionClass2: java.lang.Class<E2?>?,
        exceptionClass3: java.lang.Class<E3?>?
    ): R? {
        try {
            return join()
        } catch (e: ExecutionException) {
            val cause: Throwable = e.getCause()
            if (exceptionClass1 != null) {
                com.google.common.base.Throwables.throwIfInstanceOf<E1?>(cause, exceptionClass1)
            }
            if (exceptionClass2 != null) {
                com.google.common.base.Throwables.throwIfInstanceOf<E2?>(cause, exceptionClass2)
            }
            if (exceptionClass3 != null) {
                com.google.common.base.Throwables.throwIfInstanceOf<E3?>(cause, exceptionClass3)
            }
            com.google.common.base.Throwables.throwIfUnchecked(cause)
            throw java.lang.IllegalStateException(cause)
        }
    }

    /** Returns whether the group is cancelled or in the process of being cancelled.  */
    fun isCancelled(): Boolean {
        return cancelled.get()
    }

    private fun onComplete(subtask: Subtask<out T?>, thread: java.lang.Thread?) {
        try {
            if (subtask.state() != com.google.devtools.build.lib.concurrent.TaskGroup.Subtask.State.UNAVAILABLE) {
                // We want to call Joiner#onComplete first, so that if subtask failed and the policy decides
                // to cancel the group, the joiner can see the exception from this subtask first. Otherwise,
                // the exception from this subtask may race with the InterruptedException from other
                // subtasks that are cancelled. This will cause the joiner to sometimes throw
                // InterruptedException instead of the exception from this subtask, if the joiner only
                // throws one exception.
                joiner.onComplete(subtask)
                if (policy.onComplete(subtask)) {
                    cancel()
                }
            }
        } finally {
            threads.remove(thread)
            latch.countDown()
        }
    }

    private fun interruptAll() {
        val currentThread: java.lang.Thread? = java.lang.Thread.currentThread()
        for (thread in com.google.common.collect.ImmutableSet.copyOf<java.lang.Thread?>(threads)) {
            if (thread !== currentThread) {
                thread.interrupt()
            }
        }
    }

    /**
     * Cancels the task group if not already cancelled.
     * 
     * 
     * Cancellation will interrupt all subtask threads in the task group. No new subtasks can be
     * forked after cancellation.
     * 
     * 
     * This method can be called by any subtask threads.
     */
    private fun cancel() {
        if (cancelled.compareAndSet(false, true)) {
            interruptAll()
        }
    }

    /**
     * @throws IllegalStateException if [.fork] was called at least once and [.join] was
     * never called
     */
    override fun close() {
        ensureOwner()

        val s = state
        var ownerDidNotJoin = false
        when (s) {
            com.google.devtools.build.lib.concurrent.TaskGroup.TaskGroupState.NEW -> {
                // If the group is new, the latch was never decremented. We need to decrement it here
                // because the latch is initialized with a count of 1.
                latch.countDown()
            }

            com.google.devtools.build.lib.concurrent.TaskGroup.TaskGroupState.FORKED -> {
                cancel()
                // The latch is initialized with a count of 1 (the owner's share). In the FORKED state,
                // join() was never called, so this initial count of 1 was never decremented. We must
                // decrement it here, otherwise waiting for the subtasks to terminate will deadlock.
                latch.countDown()
                ownerDidNotJoin = true
            }

            com.google.devtools.build.lib.concurrent.TaskGroup.TaskGroupState.JOIN_STARTED -> {
                // Cancel the group if join did not complete.
                cancel()
            }

            com.google.devtools.build.lib.concurrent.TaskGroup.TaskGroupState.JOIN_COMPLETED -> {}
            com.google.devtools.build.lib.concurrent.TaskGroup.TaskGroupState.CLOSED -> {
                return
            }
        }

        try {
            latch.awaitUninterruptibly()
        } finally {
            state = com.google.devtools.build.lib.concurrent.TaskGroup.TaskGroupState.CLOSED
        }

        // throw if the owner didn't join after forking
        check(!ownerDidNotJoin) { "Owner did not join after forking" }
    }

    @com.google.common.annotations.VisibleForTesting
    fun getThreads(): com.google.common.collect.ImmutableSet<java.lang.Thread?> {
        return com.google.common.collect.ImmutableSet.copyOf<java.lang.Thread?>(threads)
    }

    /** A subtask forked with [.fork].  */
    interface Subtask<T> : java.util.function.Supplier<T?> {
        /** The state of the subtask.  */
        enum class State {
            UNAVAILABLE,
            FAILED,
            SUCCESS,
        }

        /** Returns the state of the subtask.  */
        fun state(): State

        /**
         * Returns the result of the subtask if it completed successfully.
         * 
         * @throws IllegalStateException if the subtask has not completed, or did not complete
         * successfully.
         */
        override fun get(): T?

        /**
         * Returns the exception thrown by the subtask if it failed.
         * 
         * @throws IllegalStateException if the subtask has not completed, or did not fail.
         */
        fun exception(): Throwable?
    }

    private class SubtaskImpl<T>(
        private val taskGroup: TaskGroup<in T?, *>,
        task: java.util.concurrent.Callable<out T?>
    ) : Subtask<T?>, java.lang.Runnable {
        private val task: java.util.concurrent.Callable<out T?>

        @kotlin.concurrent.Volatile
        private var result: Any? = null

        init {
            this.task = task
        }

        override fun run() {
            val thread: java.lang.Thread? = java.lang.Thread.currentThread()
            val added = taskGroup.threads.add(thread)
            com.google.common.base.Preconditions.checkState(added)
            try {
                if (taskGroup.cancelled.get()) {
                    // If the task group was cancelled, skip the task. We must check the cancellation state
                    // after adding the thread to the set to avoid a race with {@link #cancel}.
                    return
                }

                var result: T? = null
                var ex: Throwable? = null
                try {
                    result = task.call()
                } catch (e: Throwable) {
                    ex = e
                }

                if (ex == null) {
                    this.result =
                        if (result != null) result else com.google.devtools.build.lib.concurrent.TaskGroup.SubtaskImpl.Companion.RESULT_NULL
                } else {
                    this.result =
                        com.google.devtools.build.lib.concurrent.TaskGroup.SubtaskImpl.NullOrExceptionResult(ex)
                }
            } finally {
                taskGroup.onComplete(this, thread)
            }
        }

        override fun state(): Subtask.State {
            val result = this.result
            if (result == null) {
                return com.google.devtools.build.lib.concurrent.TaskGroup.Subtask.State.UNAVAILABLE
            } else if (result is NullOrExceptionResult) {
                // null or failed
                return if (result.exception == null) com.google.devtools.build.lib.concurrent.TaskGroup.Subtask.State.SUCCESS else com.google.devtools.build.lib.concurrent.TaskGroup.Subtask.State.FAILED
            } else {
                return com.google.devtools.build.lib.concurrent.TaskGroup.Subtask.State.SUCCESS
            }
        }

        override fun get(): T? {
            taskGroup.ensureJoinedIfOwner()
            val result = this.result
            if (result is NullOrExceptionResult) {
                if (result.exception == null) {
                    return null
                }
            } else if (result != null) {
                val r = result as T?
                return r
            }
            throw java.lang.IllegalStateException(
                "Result is unavailable or subtask did not complete successfully"
            )
        }

        override fun exception(): Throwable {
            taskGroup.ensureJoinedIfOwner()
            val result = this.result
            if (result is NullOrExceptionResult) {
                if (result.exception != null) {
                    return result.exception
                }
            }
            throw java.lang.IllegalStateException(
                "Result is unavailable or subtask did not complete with exception"
            )
        }

        override fun toString(): String {
            val stateAsString =
                when (state()) {
                    com.google.devtools.build.lib.concurrent.TaskGroup.Subtask.State.UNAVAILABLE -> "[Unavailable]"
                    com.google.devtools.build.lib.concurrent.TaskGroup.Subtask.State.SUCCESS -> "[Completed successfully]"
                    com.google.devtools.build.lib.concurrent.TaskGroup.Subtask.State.FAILED -> "[Failed: " + (result as NullOrExceptionResult).exception + "]"
                }
            return java.util.Objects.toIdentityString(this) + stateAsString
        }

        /** A result of a subtask that is either null or an exception.  */
        @kotlin.jvm.JvmRecord
        private data class NullOrExceptionResult(val exception: Throwable?)
        companion object {
            private val RESULT_NULL: NullOrExceptionResult =
                com.google.devtools.build.lib.concurrent.TaskGroup.SubtaskImpl.NullOrExceptionResult(null)
        }
    }

    /** An object that can be used to cancel the task group depending on the subtask state.  */
    interface Policy<T> {
        /**
         * Called by the thread that started the subtask when it completes.
         * 
         * @return true to cancel the task group.
         */
        fun onComplete(subtask: Subtask<out T?>?): Boolean {
            return false
        }
    }

    /** A collection of [Policy] implementations.  */
    object Policies {
        /** Returns a policy that cancels the task group if any subtask fails.  */
        @kotlin.jvm.JvmStatic
        fun <T> allSuccessful(): Policy<T?> {
            return com.google.devtools.build.lib.concurrent.TaskGroup.Policies.ALL_SUCCESSFUL as Policy<T?>
        }

        private val ALL_SUCCESSFUL: Policy<Any?> = object : Policy<Any?> {
            override fun onComplete(subtask: Subtask<out Any?>): Boolean {
                return subtask.state() == com.google.devtools.build.lib.concurrent.TaskGroup.Subtask.State.FAILED
            }
        }

        /** Returns a policy that cancels the task group if any subtask succeeds.  */
        @kotlin.jvm.JvmStatic
        fun <T> anySuccessful(): Policy<T?> {
            return com.google.devtools.build.lib.concurrent.TaskGroup.Policies.ANY_SUCCESSFUL as Policy<T?>
        }

        private val ANY_SUCCESSFUL: Policy<Any?> = object : Policy<Any?> {
            override fun onComplete(subtask: Subtask<out Any?>): Boolean {
                return subtask.state() == com.google.devtools.build.lib.concurrent.TaskGroup.Subtask.State.SUCCESS
            }
        }

        /** Returns a policy that waits for all subtasks to complete, no matter their state.  */
        fun <T> allCompleted(): Policy<T?> {
            return com.google.devtools.build.lib.concurrent.TaskGroup.Policies.ALL_COMPLETED as Policy<T?>
        }

        private val ALL_COMPLETED: Policy<Any?> = object : Policy<Any?> {}
    }

    /**
     * An object used to process the result of subtasks and produce the final result for the task
     * group.
     */
    interface Joiner<T, R> {
        /** Called by the thread that started the subtask when it completes.  */
        fun onComplete(subtask: Subtask<out T?>?)

        /**
         * Called by [.join] to get the final result after waiting for all subtasks to complete.
         * The result from this method is returned by [.join]. If this method throws, then [ ][.join] throws an [ExecutionException] which the exception thrown by this method as the
         * cause.
         */
        @Throws(Throwable::class)
        fun result(): R?
    }

    /** A collection of [Joiner] implementations.  */
    object Joiners {
        /**
         * Returns a joiner that returns the result of all subtasks that complete successfully.
         * 
         * 
         * If any subtask fails, the joiner causes [.join] to throw.
         * 
         * 
         * The order of the items in the returned list is undefined - it is not guaranteed to be the
         * same as the order in which the subtasks were forked.
         */
        fun <T> allSuccessfulOrThrow(): Joiner<T?, MutableList<T?>?> {
            return com.google.devtools.build.lib.concurrent.TaskGroup.Joiners.AllSuccessfulOrThrow<T?>()
        }

        /**
         * Returns a joiner that returns the result of an arbitrarily chosen subtask that completes
         * successfully.
         * 
         * 
         * If all subtasks fail, the joiner causes [.join] to throw [ ].
         */
        @kotlin.jvm.JvmStatic
        fun <T> anySuccessfulOrThrow(): Joiner<T?, T?> {
            return com.google.devtools.build.lib.concurrent.TaskGroup.Joiners.AnySuccessfulOrThrow<T?>()
        }

        /**
         * Returns a joiner that ignores the result of successful subtasks.
         * 
         * 
         * If any subtask fails, the joiner causes [.join] to throw.
         */
        @kotlin.jvm.JvmStatic
        fun <T> voidOrThrow(): Joiner<T?, java.lang.Void?> {
            return com.google.devtools.build.lib.concurrent.TaskGroup.Joiners.VoidOrThrow<T?>()
        }

        private class AllSuccessfulOrThrow<T> : Joiner<T?, MutableList<T?>?> {
            private val results: ConcurrentLinkedDeque<T?> = ConcurrentLinkedDeque<T?>()

            @kotlin.concurrent.Volatile
            private var error: Throwable? = null

            override fun onComplete(subtask: Subtask<out T?>) {
                val state = subtask.state()
                if (state == com.google.devtools.build.lib.concurrent.TaskGroup.Subtask.State.FAILED) {
                    if (error == null) {
                        // There might be a race here, but it doesn't matter which error got set.
                        error = subtask.exception()
                    }
                } else {
                    results.add(subtask.get())
                }
            }

            @Throws(Throwable::class)
            override fun result(): com.google.common.collect.ImmutableList<T?> {
                val e = error
                if (e != null) {
                    throw e
                } else {
                    return com.google.common.collect.ImmutableList.copyOf<T?>(results)
                }
            }
        }

        private class AnySuccessfulOrThrow<T> : Joiner<T?, T?> {
            private val subtaskRef: AtomicReference<Subtask<out T?>?> = AtomicReference<Subtask<out T?>?>(null)

            override fun onComplete(subtask: Subtask<out T?>) {
                val newState = subtask.state()
                var oldSubtask: Subtask<out T?>?
                while (((subtaskRef.get().also { oldSubtask = it }) == null)
                    || oldSubtask!!.state().compareTo(newState) < 0
                ) {
                    if (subtaskRef.compareAndSet(oldSubtask, subtask)) {
                        return
                    }
                }
            }

            @Throws(Throwable::class)
            override fun result(): T? {
                val subtask: Subtask<out T?> = this.subtaskRef.get()
                if (subtask == null) {
                    throw java.util.NoSuchElementException("No subtasks completed")
                }
                return when (subtask.state()) {
                    com.google.devtools.build.lib.concurrent.TaskGroup.Subtask.State.SUCCESS -> subtask.get()
                    com.google.devtools.build.lib.concurrent.TaskGroup.Subtask.State.FAILED -> throw subtask.exception()
                    else -> throw java.lang.IllegalStateException("Unexpected state: " + subtask.state())
                }
            }
        }

        @com.google.common.annotations.VisibleForTesting
        internal class VoidOrThrow<T> : Joiner<T?, java.lang.Void?> {
            @get:com.google.common.annotations.VisibleForTesting
            @kotlin.concurrent.Volatile
            var error: Throwable? = null
                private set

            override fun onComplete(subtask: Subtask<out T?>) {
                val state = subtask.state()
                if (state == com.google.devtools.build.lib.concurrent.TaskGroup.Subtask.State.FAILED && error == null) {
                    // There might be a race here, but it doesn't matter which error got set.
                    error = subtask.exception()
                }
            }

            @Throws(Throwable::class)
            override fun result(): java.lang.Void? {
                val e = error
                if (e != null) {
                    throw e
                } else {
                    return null
                }
            }
        }
    }

    companion object {
        private fun defaultThreadFactory(): ThreadFactory? {
            return java.lang.Thread.ofVirtual().factory()
        }

        /** Similar to [.open], but uses a default thread factory.  */
        fun <T, R> open(
            policy: Policy<in T?>, joiner: Joiner<in T?, out R?>
        ): TaskGroup<T?, R?> {
            return com.google.devtools.build.lib.concurrent.TaskGroup<T?, R?>(
                com.google.devtools.build.lib.concurrent.TaskGroup.Companion.defaultThreadFactory(),
                policy,
                joiner
            )
        }

        /**
         * Opens a new task group with the given policy and joiner. It should be used with
         * try-with-resources statement like:
         * 
         * <pre>`try (var group = TaskGroup.open(policy, joiner)) {   ... } `</pre>
         * 
         * 
         * The calling thread becomes the task group's owner and is the only thread allowed to call
         * [.fork], [.join] or [.close] on it.
         * 
         * 
         * A new thread is created using the given `threadFactory` for each subtask. If the
         * factory returns `null`, a [RejectedExecutionException] is thrown.
         */
        fun <T, R> open(
            threadFactory: ThreadFactory,
            policy: Policy<in T?>,
            joiner: Joiner<in T?, out R?>
        ): TaskGroup<T?, R?> {
            return com.google.devtools.build.lib.concurrent.TaskGroup<T?, R?>(threadFactory, policy, joiner)
        }
    }
}
