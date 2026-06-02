// Copyright 2021 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.remote.util

import io.reactivex.rxjava3.core.Completable
import io.reactivex.rxjava3.core.CompletableEmitter
import io.reactivex.rxjava3.core.CompletableOnSubscribe
import io.reactivex.rxjava3.core.Flowable
import io.reactivex.rxjava3.core.Single
import io.reactivex.rxjava3.core.SingleEmitter
import io.reactivex.rxjava3.core.SingleObserver
import io.reactivex.rxjava3.core.SingleOnSubscribe
import io.reactivex.rxjava3.disposables.Disposable
import io.reactivex.rxjava3.subjects.AsyncSubject
import java.util.HashMap
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A cache which de-duplicates the executions and stores the results of asynchronous tasks. Each
 * task is identified by a key of type [KeyT] and has the result of type [ValueT].
 * 
 * 
 * Use [.executeIfNot] or [.execute] and subscribe the returned [Single] to
 * start executing a task. The [Single] turns to completed once the task is `finished`.
 * Errors are propagated if any.
 * 
 * 
 * Calling `execute[IfNot]` multiple times with the same task key can get an [Single]
 * which connects to the same underlying execution if the task is still executing, or get a
 * completed [Single] if the task is already finished. Set `force` to `true ` to
 * re-execute a finished task.
 * 
 * 
 * Dispose the [Single] to cancel to task execution.
 * 
 * 
 * Use [.shutdown] to shuts the cache down. Any in progress tasks will continue running
 * while new tasks will be injected with [CancellationException]. Use [ ][.awaitTermination] after [.shutdown] to wait for the in progress tasks finished.
 * 
 * 
 * Use [.shutdownNow] to cancel all in progress and new tasks with exception [ ].
 */
@javax.annotation.concurrent.ThreadSafe
class AsyncTaskCache<KeyT, ValueT> {
    private val lock = Any()

    @javax.annotation.concurrent.GuardedBy("lock")
    private var state = STATE_ACTIVE

    @javax.annotation.concurrent.GuardedBy("lock")
    private val terminationSubscriber: java.util.ArrayList<CompletableEmitter> =
        java.util.ArrayList<CompletableEmitter>()

    // Concurrent so that {@link #invalidate} can run without acquiring {@code lock}, which prevents
    // lock-ordering deadlocks when invalidation is triggered from within another cache's observer
    // notification (e.g., a doFinally on an upload completion).
    private val finished: ConcurrentHashMap<KeyT?, ValueT?> = ConcurrentHashMap<KeyT?, ValueT?>()

    @javax.annotation.concurrent.GuardedBy("lock")
    private var inProgress: MutableMap<KeyT?, Execution?> = HashMap<KeyT?, Execution?>()

    val finishedTasks: com.google.common.collect.ImmutableSet<KeyT?>
        /** Returns a set of keys for tasks which is finished.  */
        get() = com.google.common.collect.ImmutableSet.copyOf<KeyT?>(finished.keySet())

    /**
     * Removes any cached result for the given `key`, so that the next call to [.execute]
     * for that key re-runs the task. Does not affect in-progress tasks. Safe to call concurrently
     * with [.execute].
     */
    fun invalidate(key: KeyT?) {
        finished.remove(key)
    }

    /**
     * Atomically replaces the cached result for `key` with `value`. The new value is
     * visible to subsequent [.execute] callers. Safe to call concurrently with [ ][.execute].
     */
    fun put(key: KeyT?, value: ValueT?) {
        finished.put(key, value)
    }

    val inProgressTasks: com.google.common.collect.ImmutableSet<KeyT?>
        /** Returns a set of keys for tasks which is still executing.  */
        get() {
            synchronized(lock) {
                return com.google.common.collect.ImmutableSet.copyOf<KeyT?>(inProgress.keySet())
            }
        }

    /**
     * Executes a task if it hasn't been executed.
     * 
     * @param key identifies the task.
     * @return a [Single] which turns to completed once the task is finished or propagates the
     * error if any.
     */
    fun executeIfNot(key: KeyT?, task: Single<ValueT?>): Single<ValueT?>? {
        return execute(key, task, false)
    }

    /** Returns count of subscribers for a task.  */
    fun getSubscriberCount(key: KeyT?): Int {
        synchronized(lock) {
            val task = inProgress.get(key)
            if (task != null) {
                return task.subscriberCount
            }
        }

        return 0
    }

    internal inner class Execution(private val key: KeyT?, upstream: Single<ValueT?>) : Single<ValueT?>(),
        SingleObserver<ValueT?> {
        private val upstream: Single<ValueT?>

        @javax.annotation.concurrent.GuardedBy("lock")
        private var terminated = false

        @javax.annotation.concurrent.GuardedBy("lock")
        private var upstreamDisposable: Disposable? = null

        @javax.annotation.concurrent.GuardedBy("lock")
        private val observers: MutableList<SingleObserver<in ValueT?>?> =
            java.util.ArrayList<SingleObserver<in ValueT?>?>()

        private val completion: AsyncSubject<ValueT?> = AsyncSubject.create<ValueT?>()

        init {
            this.upstream = upstream
        }

        val subscriberCount: Int
            get() {
                synchronized(lock) {
                    return observers.size()
                }
            }

        override fun subscribeActual(observer: SingleObserver<in ValueT?>) {
            synchronized(lock) {
                com.google.common.base.Preconditions.checkState(!terminated, "terminated")
                val shouldSubscribe = observers.isEmpty()

                observers.add(observer)

                observer.onSubscribe(ExecutionDisposable(this, observer))
                if (shouldSubscribe) {
                    upstream.subscribe(this)
                }
            }
        }

        override fun onSubscribe(d: Disposable) {
            synchronized(lock) {
                upstreamDisposable = d
                if (terminated) {
                    d.dispose()
                }
            }
        }

        override fun onSuccess(value: ValueT) {
            synchronized(lock) {
                if (!terminated) {
                    inProgress.remove(key)
                    finished.put(key, value)
                    terminated = true

                    for (observer in com.google.common.collect.ImmutableList.copyOf<SingleObserver<in ValueT?>?>(
                        observers
                    )) {
                        observer.onSuccess(value)
                    }

                    completion.onNext(value)
                    completion.onComplete()

                    maybeNotifyTermination()
                }
            }
        }

        override fun onError(error: Throwable) {
            synchronized(lock) {
                if (!terminated) {
                    inProgress.remove(key)
                    terminated = true

                    for (observer in com.google.common.collect.ImmutableList.copyOf<SingleObserver<in ValueT?>?>(
                        observers
                    )) {
                        observer.onError(error)
                    }

                    completion.onError(error)

                    maybeNotifyTermination()
                }
            }
        }

        fun remove(observer: SingleObserver<in ValueT?>?) {
            synchronized(lock) {
                observers.remove(observer)
                if (observers.isEmpty() && !terminated) {
                    inProgress.remove(key)
                    terminated = true

                    if (upstreamDisposable != null) {
                        upstreamDisposable.dispose()
                    }
                }
            }
        }

        fun cancel() {
            synchronized(lock) {
                if (!terminated) {
                    if (upstreamDisposable != null) {
                        upstreamDisposable.dispose()
                    }

                    onError(CancellationException("cancelled"))
                }
            }
        }
    }

    internal inner class ExecutionDisposable(val execution: Execution, observer: SingleObserver<in ValueT?>?) :
        Disposable {
        val observer: SingleObserver<in ValueT?>?
        var isDisposed: AtomicBoolean = AtomicBoolean(false)

        init {
            this.observer = observer
        }

        override fun dispose() {
            if (isDisposed.compareAndSet(false, true)) {
                execution.remove(observer)
            }
        }

        override fun isDisposed(): Boolean {
            return isDisposed.get()
        }
    }

    /**
     * Executes a task.
     * 
     * @see .execute
     */
    fun execute(key: KeyT?, task: Single<ValueT?>, force: Boolean): Single<ValueT?>? {
        return execute(
            key,
            task,
            io.reactivex.rxjava3.functions.Action {},
            io.reactivex.rxjava3.functions.Action {},
            force
        )
    }

    /**
     * Executes a task. If the task has already finished, this execution of the task is ignored unless
     * `force` is true. If the task is in progress this execution of the task is always ignored.
     * 
     * 
     * If the cache is already shutdown, a [CancellationException] will be emitted.
     * 
     * @param key identifies the task.
     * @param onAlreadyRunning callback called when provided task is already running.
     * @param onAlreadyFinished callback called when provided task is already finished.
     * @param force re-execute a finished task if set to `true`.
     * @return a [Single] which turns to completed once the task is finished or propagates the
     * error if any.
     */
    fun execute(
        key: KeyT?,
        task: Single<ValueT?>,
        onAlreadyRunning: io.reactivex.rxjava3.functions.Action,
        onAlreadyFinished: io.reactivex.rxjava3.functions.Action,
        force: Boolean
    ): Single<ValueT?>? {
        return Single.create<ValueT?>(
            SingleOnSubscribe { emitter: SingleEmitter<ValueT?>? ->
                synchronized(lock) {
                    if (state != STATE_ACTIVE) {
                        emitter.onError(CancellationException("already shutdown"))
                        return@create
                    }
                    if (!force) {
                        val cached: ValueT? = finished.get(key)
                        if (cached != null) {
                            onAlreadyFinished.run()
                            emitter.onSuccess(cached)
                            return@create
                        }
                    } else {
                        finished.remove(key)
                    }

                    var execution = inProgress.get(key)
                    if (execution != null) {
                        onAlreadyRunning.run()
                    } else {
                        execution = Execution(key, task)
                        inProgress.put(key, execution)
                    }

                    // We must subscribe the execution within the scope of lock to avoid race condition
                    // that:
                    //    1. Two callers get the same execution instance
                    //    2. One decides to dispose the execution, since no more observers, the execution
                    // will change to the terminate state
                    //    3. Another one try to subscribe, will get "terminated" error.
                    execution.subscribe(
                        object : SingleObserver<ValueT?>() {
                            override fun onSubscribe(d: Disposable) {
                                emitter.setDisposable(d)
                            }

                            override fun onSuccess(valueT: ValueT) {
                                emitter.onSuccess(valueT)
                            }

                            override fun onError(e: Throwable) {
                                if (!emitter.isDisposed()) {
                                    emitter.onError(e)
                                }
                            }
                        })
                }
            })
    }

    /**
     * Initiates an orderly shutdown in which preexisting tasks continue but new tasks are immediately
     * cancelled with [CancellationException].
     */
    fun shutdown() {
        synchronized(lock) {
            if (state == STATE_ACTIVE) {
                state = STATE_SHUTDOWN
                maybeNotifyTermination()
            }
        }
    }

    /**
     * Waits for the in-progress tasks to finish. Any tasks that are submitted after the call are not
     * waited.
     */
    @Throws(java.lang.InterruptedException::class)
    fun awaitInProgressTasks() {
        val completable: Completable =
            Completable.defer(
                io.reactivex.rxjava3.functions.Supplier {
                    val executions: com.google.common.collect.ImmutableList<Execution?>?
                    synchronized(lock) {
                        executions = com.google.common.collect.ImmutableList.copyOf<Execution?>(inProgress.values())
                    }

                    if (executions.isEmpty()) {
                        return@defer Completable.complete()
                    }
                    Completable.fromPublisher<ValueT?>(
                        Flowable.fromIterable<Execution?>(executions)
                            .flatMapSingle<ValueT?>(io.reactivex.rxjava3.functions.Function { e: Execution? ->
                                Single.fromObservable<ValueT?>(
                                    e.completion
                                )
                            })
                    )
                })

        try {
            completable.blockingAwait()
        } catch (e: java.lang.RuntimeException) {
            val cause: Throwable? = e.getCause()
            if (cause != null) {
                com.google.common.base.Throwables.throwIfInstanceOf<java.lang.InterruptedException?>(
                    cause,
                    java.lang.InterruptedException::class.java
                )
            }
            throw e
        }
    }

    /** Waits for the channel to become terminated.  */
    @Throws(java.lang.InterruptedException::class)
    fun awaitTermination() {
        val completable: Completable =
            Completable.create(
                CompletableOnSubscribe { emitter: CompletableEmitter? ->
                    synchronized(lock) {
                        if (state == STATE_TERMINATED) {
                            // Reduce retained size in case references to the cache are held after shutdown.
                            terminationSubscriber.trimToSize()
                            inProgress = HashMap<KeyT?, Execution?>()
                            finished.clear()
                            emitter.onComplete()
                        } else {
                            terminationSubscriber.add(emitter)

                            emitter.setCancellable(
                                io.reactivex.rxjava3.functions.Cancellable {
                                    synchronized(lock) {
                                        if (state != STATE_TERMINATED) {
                                            terminationSubscriber.remove(emitter)
                                        }
                                    }
                                })
                        }
                    }
                })

        try {
            completable.blockingAwait()
        } catch (e: java.lang.RuntimeException) {
            val cause: Throwable? = e.getCause()
            if (cause != null) {
                com.google.common.base.Throwables.throwIfInstanceOf<java.lang.InterruptedException?>(
                    cause,
                    java.lang.InterruptedException::class.java
                )
            }
            throw e
        }
    }

    /**
     * Initiates a forceful shutdown in which preexisting and new tasks are cancelled with [ ]. Although forceful, the shutdown process is still not instantaneous;
     * [.isTerminated] will likely return `false` immediately after this method returns.
     */
    fun shutdownNow() {
        shutdown()

        synchronized(lock) {
            if (state == STATE_SHUTDOWN) {
                for (execution in com.google.common.collect.ImmutableList.copyOf<Execution?>(inProgress.values())) {
                    execution.cancel()
                }
            }
        }
    }

    val isShutdown: Boolean
        /**
         * Returns whether the cache is shutdown. Shutdown cache immediately cancels any new tasks, but
         * may still have some tasks in the progress.
         */
        get() {
            synchronized(lock) {
                return state == STATE_SHUTDOWN || state == STATE_TERMINATED
            }
        }

    val isTerminated: Boolean
        /**
         * Returns whether the cache is terminated. Terminated cache have no running tasks and relevant
         * resources released.
         */
        get() {
            synchronized(lock) {
                return state == STATE_TERMINATED
            }
        }

    @javax.annotation.concurrent.GuardedBy("lock")
    private fun maybeNotifyTermination() {
        if (state == STATE_SHUTDOWN && inProgress.isEmpty()) {
            state = STATE_TERMINATED

            for (emitter in terminationSubscriber) {
                emitter.onComplete()
            }
            terminationSubscriber.clear()
        }
    }

    /** An [AsyncTaskCache] without result.  */
    class NoResult<KeyT>(cache: AsyncTaskCache<KeyT?, java.util.Optional<java.lang.Void?>?>) {
        private val cache: AsyncTaskCache<KeyT?, java.util.Optional<java.lang.Void?>?>

        init {
            this.cache = cache
        }

        /** Same as [AsyncTaskCache.executeIfNot] but operates on [Completable].  */
        fun executeIfNot(key: KeyT?, task: Completable): Completable? {
            return Completable.fromSingle<java.util.Optional<java.lang.Void?>?>(
                cache.executeIfNot(
                    key,
                    task.toSingleDefault<java.util.Optional<java.lang.Void?>?>(java.util.Optional.empty<java.lang.Void?>())
                )
            )
        }

        /** Same as [AsyncTaskCache.execute] but operates on [Completable].  */
        fun execute(key: KeyT?, task: Completable, force: Boolean): Completable? {
            return execute(
                key,
                task,
                io.reactivex.rxjava3.functions.Action {},
                io.reactivex.rxjava3.functions.Action {},
                force
            )
        }

        /** Same as [AsyncTaskCache.execute] but operates on [Completable].  */
        fun execute(
            key: KeyT?,
            task: Completable,
            onAlreadyRunning: io.reactivex.rxjava3.functions.Action,
            onAlreadyFinished: io.reactivex.rxjava3.functions.Action,
            force: Boolean
        ): Completable? {
            return Completable.fromSingle<java.util.Optional<java.lang.Void?>?>(
                cache.execute(
                    key,
                    task.toSingleDefault<java.util.Optional<java.lang.Void?>?>(java.util.Optional.empty<java.lang.Void?>()),
                    onAlreadyRunning,
                    onAlreadyFinished,
                    force
                )
            )
        }

        val finishedTasks: com.google.common.collect.ImmutableSet<KeyT?>
            /** Returns a set of keys for tasks which is finished.  */
            get() = cache.finishedTasks

        val inProgressTasks: com.google.common.collect.ImmutableSet<KeyT?>
            /** Returns a set of keys for tasks which is still executing.  */
            get() = cache.inProgressTasks

        /** Returns count of subscribers for a task.  */
        fun getSubscriberCount(key: KeyT?): Int {
            return cache.getSubscriberCount(key)
        }

        /**
         * Initiates an orderly shutdown in which preexisting tasks continue but new tasks are
         * immediately cancelled with [CancellationException].
         */
        fun shutdown() {
            cache.shutdown()
        }

        /**
         * Waits for the in-progress tasks to finish. Any tasks that are submitted after the call are
         * not waited.
         */
        @Throws(java.lang.InterruptedException::class)
        fun awaitInProgressTasks() {
            cache.awaitInProgressTasks()
        }

        /** Waits for the cache to become terminated.  */
        @Throws(java.lang.InterruptedException::class)
        fun awaitTermination() {
            cache.awaitTermination()
        }

        /**
         * Initiates a forceful shutdown in which preexisting and new tasks are cancelled with [ ]. Although forceful, the shutdown process is still not instantaneous;
         * [.isTerminated] will likely return `false` immediately after this method
         * returns.
         */
        fun shutdownNow() {
            cache.shutdownNow()
        }

        val isShutdown: Boolean
            /**
             * Returns whether the cache is shutdown. Shutdown cache immediately cancels any new tasks, but
             * may still have some tasks in the progress.
             */
            get() = cache.isShutdown

        val isTerminated: Boolean
            /**
             * Returns whether the cache is terminated. Terminated cache have no running tasks and relevant
             * resources released.
             */
            get() = cache.isTerminated

        companion object {
            fun <KeyT> create(): NoResult<KeyT?> {
                return NoResult<KeyT?>(AsyncTaskCache.Companion.create<KeyT?, java.util.Optional<java.lang.Void?>?>())
            }
        }
    }

    companion object {
        private const val STATE_ACTIVE = 0
        private const val STATE_SHUTDOWN = 1
        private const val STATE_TERMINATED = 2

        fun <KeyT, ValueT> create(): AsyncTaskCache<KeyT?, ValueT?> {
            return AsyncTaskCache<KeyT?, ValueT?>()
        }
    }
}
