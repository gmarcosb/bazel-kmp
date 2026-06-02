// Copyright 2015 The Bazel Authors. All rights reserved.
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

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.IntUnaryOperator

/**
 * Deduplicates concurrent tasks identified by unique keys. For any given key, only one task is
 * actively executed at a time.
 * 
 * 
 * Any futures returned by this class can be individually canceled without affecting other
 * callers. The shared task is only canceled if all callers have canceled their futures and the task
 * is interrupted if and only if all callers requested interruption.
 */
class TaskDeduplicator<K, V> {
    private val inFlightTasks: ConcurrentMap<K?, RefcountedFuture<V?>> = ConcurrentHashMap<K?, RefcountedFuture<V?>>()

    /**
     * Returns a future representing either a new or already ongoing execution of the task.
     * 
     * 
     * The returned future must eventually be completed. The task is only canceled if the futures
     * returned to all callers for the same key have been canceled.
     * 
     * 
     * taskSupplier may be called multiple times. It should be inexpensive and free of side
     * effects.
     */
    @com.google.errorprone.annotations.CheckReturnValue
    fun executeIfNew(
        key: K?,
        taskSupplier: java.util.function.Supplier<com.google.common.util.concurrent.ListenableFuture<V?>?>
    ): com.google.common.util.concurrent.ListenableFuture<V?> {
        while (true) {
            val isNewHolder = BooleanArray(1)
            val future: RefcountedFuture<V?> =
                inFlightTasks.computeIfAbsent(
                    key,
                    java.util.function.Function { unusedKey: K? ->
                        isNewHolder[0] = true
                        RefcountedFuture.Companion.wrap<V?>(taskSupplier.get())
                    })
            if (isNewHolder[0]) {
                future.addListener(
                    java.lang.Runnable { inFlightTasks.remove(key, future) },
                    com.google.common.util.concurrent.MoreExecutors.directExecutor()
                )
            } else {
                // The shared future may have been canceled between the lookup and the call to retain().
                if (!future.retain()) {
                    inFlightTasks.remove(key, future)
                    continue
                }
            }
            return IndividuallyCancelableFuture.Companion.wrap<V?>(future)
        }
    }

    /**
     * Returns a future representing either a new or already ongoing execution of the task that is
     * guaranteed to happen-after any executions started before the call of this method.
     * 
     * 
     * The returned future must eventually be completed. The task is only canceled if the futures
     * returned to all callers for the same key have been canceled.
     * 
     * 
     * taskSupplier may be called multiple times. It should be inexpensive and free of side
     * effects.
     */
    @com.google.errorprone.annotations.CheckReturnValue
    fun executeUnconditionally(
        key: K?, taskSupplier: java.util.function.Supplier<com.google.common.util.concurrent.ListenableFuture<V?>?>
    ): com.google.common.util.concurrent.ListenableFuture<V?> {
        inFlightTasks.remove(key)
        return executeIfNew(key, taskSupplier)
    }

    /**
     * Returns a future representing an already ongoing execution of the task or null if there is
     * none.
     * 
     * 
     * The returned future must eventually be completed. The task is only canceled if the futures
     * returned to all callers for the same key have been canceled.
     */
    @com.google.errorprone.annotations.CheckReturnValue
    fun maybeJoinExecution(key: K?): com.google.common.util.concurrent.ListenableFuture<V?>? {
        val future: RefcountedFuture<V?>? = inFlightTasks.get(key)
        if (future == null) {
            return null
        }
        if (!future.retain()) {
            inFlightTasks.remove(key, future)
            return null
        }
        return IndividuallyCancelableFuture.Companion.wrap<V?>(future)
    }

    /**
     * A future adapter that is canceled only when [.cancel] has been called one more time than
     * [.retain].
     */
    private class RefcountedFuture<V>(delegate: com.google.common.util.concurrent.ListenableFuture<V?>) :
        com.google.common.util.concurrent.AbstractFuture<V?>(), java.lang.Runnable {
        private val delegate: com.google.common.util.concurrent.ListenableFuture<V?>?

        // Initialized to 1 in the constructor and incremented via retain(). Once it drops to 0, it
        // can never return to 1 or higher (0 is a sticky state).
        private val refcount: AtomicInteger = AtomicInteger(1)

        @kotlin.concurrent.Volatile
        private var mayInterruptIfRunning = true

        init {
            this.delegate = delegate
            setFuture(delegate)
        }

        override fun run() {}

        override fun cancel(mayInterruptIfRunning: Boolean): Boolean {
            if (!mayInterruptIfRunning) {
                this.mayInterruptIfRunning = false
            }
            if (refcount.updateAndGet(IntUnaryOperator { oldCount: Int -> if (oldCount >= 1) oldCount - 1 else 0 }) == 0) {
                return super.cancel(this.mayInterruptIfRunning)
            }
            return false
        }

        override fun pendingToString(): String? {
            return "delegate=[%s (%d active uses)]".formatted(delegate, refcount.get())
        }

        /** Retains the future, returning true if successful.  */
        fun retain(): Boolean {
            return refcount.updateAndGet(IntUnaryOperator { oldCount: Int -> if (oldCount >= 1) oldCount + 1 else 0 }) != 0
        }

        companion object {
            fun <V> wrap(delegate: com.google.common.util.concurrent.ListenableFuture<V?>): RefcountedFuture<V?> {
                val wrappedFuture = RefcountedFuture<V?>(delegate)
                delegate.addListener(wrappedFuture, com.google.common.util.concurrent.MoreExecutors.directExecutor())
                return wrappedFuture
            }
        }
    }

    /**
     * A future adapter that forwards cancellation requests to its delegate but cancels itself even if
     * the delegate doesn't.
     */
    private class IndividuallyCancelableFuture<V>(private val delegate: RefcountedFuture<V?>) :
        com.google.common.util.concurrent.AbstractFuture<V?>(), java.lang.Runnable {
        override fun run() {
            setFuture(delegate)
        }

        override fun cancel(mayInterruptIfRunning: Boolean): Boolean {
            val didCancel: Boolean = super.cancel(mayInterruptIfRunning)
            if (didCancel) {
                delegate.cancel(mayInterruptIfRunning)
            }
            return didCancel
        }

        override fun pendingToString(): String? {
            return "delegate=[%s]".formatted(delegate)
        }

        companion object {
            fun <V> wrap(delegate: RefcountedFuture<V?>): com.google.common.util.concurrent.ListenableFuture<V?> {
                val wrappedFuture = IndividuallyCancelableFuture<V?>(delegate)
                delegate.addListener(wrappedFuture, com.google.common.util.concurrent.MoreExecutors.directExecutor())
                return wrappedFuture
            }
        }
    }
}
