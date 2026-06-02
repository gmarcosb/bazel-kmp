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
package com.google.devtools.build.lib.skyframe.serialization

import com.google.devtools.build.lib.concurrent.QuiescingFuture

/**
 * Container for [WriteStatus] and its implementations.
 * 
 * 
 * The alternative of having [WriteStatus] as the top level type and its implementations as
 * inner classes, requires all the implementations to be public.
 */
object WriteStatuses {
    /** Returns the stateless, immediately successful write status.  */
    @kotlin.jvm.JvmStatic
    fun immediateWriteStatus(): WriteStatus {
        return ImmediateWriteStatus.Companion.NOVEL
    }

    /**
     * Returns a stateless, immediately successful write status with the given novelty.
     * 
     * @param wasNovel true if new bytes were actually written; false if they already existed in the
     * backend.
     */
    @kotlin.jvm.JvmStatic
    fun immediateWriteStatus(wasNovel: Boolean): WriteStatus {
        return if (wasNovel) ImmediateWriteStatus.Companion.NOVEL else ImmediateWriteStatus.Companion.NOT_NOVEL
    }

    /** Creates an immediately failed write status.  */
    @kotlin.jvm.JvmStatic
    fun immediateFailedWriteStatus(cause: Throwable?): WriteStatus {
        return ImmediateFailedWriteStatus(cause)
    }

    /**
     * Combines `writeStatuses` into a single future using *sparse* aggregation.
     * 
     * 
     * NB: This is not a general purpose aggregation and must only be used under certain
     * conditions. See [SparseAggregateWriteStatus] for details.
     */
    fun sparselyAggregateWriteStatuses(writeStatuses: MutableCollection<WriteStatus>): WriteStatus? {
        if (writeStatuses.isEmpty()) {
            return immediateWriteStatus()
        }
        if (writeStatuses.size() == 1) {
            return writeStatuses.iterator().next()
        }
        return SparseAggregateWriteStatus.Companion.create(writeStatuses)
    }

    /** Combines `futures` into a single future (general purpose).  */
    fun aggregateWriteStatuses(writeStatuses: MutableCollection<WriteStatus>): WriteStatus? {
        if (writeStatuses.isEmpty()) {
            return immediateWriteStatus()
        }
        if (writeStatuses.size() == 1) {
            return writeStatuses.iterator().next()
        }
        return AggregateWriteStatus.Companion.create(writeStatuses)
    }

    /**
     * Represents future success or failure of a write operation.
     * 
     * 
     * This can act like an ordinary future, but has special case, memory saving handling for
     * aggregation.
     * 
     * 
     * The [Boolean] result of this future indicates the "novelty" of the write. A `true` result means new bytes were actually written to the storage backend; `false` means
     * they were already present. Novelty tracking is used for metrics and defaults to `true` if
     * the backend configuration doesn't support it.
     * 
     * 
     * OR semantics are used for aggregation: an aggregate is novel if any of its components are
     * novel.
     */
    // The ImmediateWriteStatus class should be singleton, so it's cleaner to not derive it from
    // AbstractFuture.
    interface WriteStatus : com.google.common.util.concurrent.ListenableFuture<Boolean?>


    /**
     * A reference-count based aggregator for [WriteStatus]es.
     * 
     * 
     * **Sparsity:** when [addToAggregator] is called, only the first invocation creates a
     * callback and the rest are ignored. This is appropriate when all [WriteStatus]es are
     * ultimately aggregated into a single top-level future, e.g., the [ ].
     * 
     * 
     * When a [com.google.common.util.concurrent.SettableFuture] with sparse edges is
     * desired, this class may be used by calling the methods [.notifyWriteSucceeded] and [ ][.notifyWriteFailed] appropriately. Since this class derives from [QuiescingFuture],
     * there's a pre-increment, so calling one of those two methods once is sufficient for setting the
     * value.
     */
    class SparseAggregateWriteStatus :
        QuiescingFuture<Boolean?>(com.google.common.util.concurrent.MoreExecutors.directExecutor()), WriteStatus,
        com.google.common.util.concurrent.FutureCallback<Boolean?> {
        @kotlin.concurrent.Volatile
        private var listeningAggregate: SparseAggregateWriteStatus? = null

        @kotlin.concurrent.Volatile
        private var wasNovel = false

        /**
         * Signals the successful completion of an aggregate component with novelty information.
         * 
         * 
         * Only clients using the aggregate as a settable future call this.
         * 
         * @param novel true if new bytes were actually written; false if they already existed in the
         * backend.
         */
        /**
         * Signals the successful completion of an aggregate component.
         * 
         * 
         * Only clients using the aggregate as a settable future call this.
         */
        @kotlin.jvm.JvmOverloads
        fun notifyWriteSucceeded(novel: Boolean = true) {
            if (novel) {
                // "OR" semantics: if any component was novel, the aggregate is novel.
                val unused: Boolean = WAS_NOVEL_HANDLE.compareAndSet(this, false, true)
            }
            decrement()
        }

        /**
         * Signals the failure of an aggregate component.
         * 
         * 
         * Only clients using the aggregate as a settable future (or [ ]) call this.
         */
        fun notifyWriteFailed(t: Throwable?) {
            if (t is CancellationException) {
                cancel( /* mayInterruptIfRunning= */false) // nothing running
                return
            }
            notifyException(t)
        }

        protected override fun getValue(): Boolean {
            return wasNovel
        }

        /**
         * Prepares for the addition of a new write operation by incrementing the internal reference
         * count.
         * 
         * 
         * By incrementing *before* the write is actually added, we ensure that the reference count
         * accurately reflects the number of pending writes, even if some writes complete immediately.
         */
        private fun prepareForAddingWrite() {
            increment()
        }

        private fun addToAggregator(aggregate: SparseAggregateWriteStatus) {
            // The CAS here accepts the first listener, and ignores any additional ones.
            if (LISTENING_AGGREGATOR_HANDLE.compareAndSet(this, null, aggregate)) {
                aggregate.prepareForAddingWrite()
                addListener(
                    java.lang.Runnable {
                        try {
                            val result: Boolean = com.google.common.util.concurrent.Futures.getDone<Boolean?>(this)
                            listeningAggregate!!.notifyWriteSucceeded(result)
                        } catch (e: ExecutionException) {
                            listeningAggregate!!.notifyWriteFailed(e)
                        } catch (e: CancellationException) {
                            listeningAggregate.cancel( /* mayInterruptIfRunning= */false) // nothing running
                        }
                    },
                    com.google.common.util.concurrent.MoreExecutors.directExecutor()
                )
            }
        }

        private fun clearPreincrement() {
            decrement()
        }

        /**
         * Implementation of [<].
         * 
         */
        @com.google.errorprone.annotations.DoNotCall
        @Deprecated("only for use by {@link #create} callback processing.")
        override fun onSuccess(novel: Boolean) {
            notifyWriteSucceeded(novel)
        }

        /**
         * Implementation of [<].
         * 
         */
        @com.google.errorprone.annotations.DoNotCall
        @Deprecated("only for use by {@link #create} callback processing.")
        override fun onFailure(t: Throwable) {
            if (t is CancellationException) {
                cancel( /* mayInterruptIfRunning= */false)
                return
            }
            notifyWriteFailed(t)
        }

        companion object {
            /** Creates an aggregate that depends on all the statuses in `writeStatuses`.  */
            private fun create(
                writeStatuses: Iterable<out com.google.common.util.concurrent.ListenableFuture<Boolean?>>
            ): SparseAggregateWriteStatus {
                return SparseAggregateWriteStatusBuilder().addAll(writeStatuses).build()
            }

            private val LISTENING_AGGREGATOR_HANDLE: java.lang.invoke.VarHandle
            private val WAS_NOVEL_HANDLE: java.lang.invoke.VarHandle

            init {
                try {
                    LISTENING_AGGREGATOR_HANDLE =
                        java.lang.invoke.MethodHandles.lookup()
                            .findVarHandle(
                                SparseAggregateWriteStatus::class.java,
                                "listeningAggregate",
                                SparseAggregateWriteStatus::class.java
                            )
                    WAS_NOVEL_HANDLE =
                        java.lang.invoke.MethodHandles.lookup()
                            .findVarHandle(
                                SparseAggregateWriteStatus::class.java,
                                "wasNovel",
                                Boolean::class.javaPrimitiveType
                            )
                } catch (e: java.lang.ReflectiveOperationException) {
                    throw java.lang.ExceptionInInitializerError(e)
                }
            }
        }
    }

    /**
     * A general purpose, reference-count-based [WriteStatus] aggregator.
     * 
     * 
     * This class implements [WriteStatus] and thus extends [<]
     * (via [<]) to track novelty.
     * 
     * 
     * Uses less memory in-flight than [Futures.whenAllSucceed] because it does not retain
     * the list of input futures and therefore also releases those futures earlier.
     * 
     * 
     * In contrast to [SparseAggregateWriteStatus] preserves all callback edges.
     */
    private class AggregateWriteStatus :
        QuiescingFuture<Boolean?>(com.google.common.util.concurrent.MoreExecutors.directExecutor()), WriteStatus,
        com.google.common.util.concurrent.FutureCallback<Boolean?> {
        @kotlin.concurrent.Volatile
        private var wasNovel = false

        protected override fun getValue(): Boolean {
            return wasNovel
        }

        /**
         * Implementation of [<].
         * 
         */
        @Deprecated("only used by {@link #create} for callback processing")
        override fun onSuccess(novel: Boolean) {
            if (novel) {
                val unused: Boolean = WAS_NOVEL_HANDLE.compareAndSet(this, false, true)
            }
            decrement()
        }

        /**
         * Implementation of [<].
         * 
         */
        @Deprecated("only used by {@link #create} for callback processing")
        override fun onFailure(t: Throwable) {
            if (t is CancellationException) {
                cancel( /* mayInterruptIfRunning= */false) // nothing running
                return
            }
            notifyException(t)
        }

        fun add(status: com.google.common.util.concurrent.ListenableFuture<Boolean?>) {
            increment()
            com.google.common.util.concurrent.Futures.addCallback<Boolean?>(
                status,
                this as com.google.common.util.concurrent.FutureCallback<Boolean?>,
                com.google.common.util.concurrent.MoreExecutors.directExecutor()
            )
        }

        fun clearPreincrement() {
            decrement()
        }

        companion object {
            private fun create(writeStatuses: Iterable<WriteStatus>): AggregateWriteStatus {
                return AggregateWriteStatusBuilder().addAll(writeStatuses).build()
            }

            private val WAS_NOVEL_HANDLE: java.lang.invoke.VarHandle

            init {
                try {
                    WAS_NOVEL_HANDLE =
                        java.lang.invoke.MethodHandles.lookup()
                            .findVarHandle(
                                AggregateWriteStatus::class.java,
                                "wasNovel",
                                Boolean::class.javaPrimitiveType
                            )
                } catch (e: java.lang.ReflectiveOperationException) {
                    throw java.lang.ExceptionInInitializerError(e)
                }
            }
        }
    }

    /** Interface for building aggregated [WriteStatus]es.  */
    interface WriteStatusBuilder {
        /** Adds a status to the aggregate.  */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun add(status: com.google.common.util.concurrent.ListenableFuture<Boolean?>?): WriteStatusBuilder?

        /** Adds all statuses to the aggregate.  */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun addAll(statuses: Iterable<out com.google.common.util.concurrent.ListenableFuture<Boolean?>?>?): WriteStatusBuilder?

        /**
         * Builds and returns the aggregated [WriteStatus].
         * 
         * 
         * Should only be called once.
         */
        fun build(): WriteStatus?
    }

    /**
     * Builder for [AggregateWriteStatus].
     * 
     * 
     * This builder is thread safe, but [.build] should only be called once.
     */
    internal class AggregateWriteStatusBuilder : WriteStatusBuilder {
        private val aggregate = AggregateWriteStatus()
        private val preincrementCleared: AtomicBoolean = AtomicBoolean(false)

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        override fun add(status: com.google.common.util.concurrent.ListenableFuture<Boolean?>): AggregateWriteStatusBuilder {
            aggregate.add(status)
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        override fun addAll(
            statuses: Iterable<out com.google.common.util.concurrent.ListenableFuture<Boolean?>>
        ): AggregateWriteStatusBuilder {
            for (status in statuses) {
                aggregate.add(status)
            }
            return this
        }

        /** Should only be called once.  */
        override fun build(): AggregateWriteStatus {
            com.google.common.base.Preconditions.checkState(
                !preincrementCleared.getAndSet(true),
                "build must only be called once"
            )
            aggregate.clearPreincrement()
            return aggregate
        }
    }

    /**
     * Builder for [SparseAggregateWriteStatus].
     * 
     * 
     * This builder is thread safe, but [.build] should only be called once.
     */
    class SparseAggregateWriteStatusBuilder : WriteStatusBuilder {
        private val aggregate = SparseAggregateWriteStatus()
        private val preincrementCleared: AtomicBoolean = AtomicBoolean(false)

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        override fun add(status: com.google.common.util.concurrent.ListenableFuture<Boolean?>): SparseAggregateWriteStatusBuilder {
            if (status.isDone()) {
                try {
                    if (com.google.common.util.concurrent.Futures.getDone<Boolean?>(status)) {
                        // notifyWriteSucceeded(true) updates the novelty bit and also decrements.
                        // Increments the reference count to stay consistent.
                        aggregate.prepareForAddingWrite()
                        aggregate.notifyWriteSucceeded(true)
                    }
                } catch (e: ExecutionException) {
                    // InternalFutureFailureAccess might be more efficient, but failures should be rare.
                    //
                    // Increments the reference count for consistency.
                    aggregate.prepareForAddingWrite()
                    aggregate.notifyWriteFailed(e)
                } catch (e: CancellationException) {
                    aggregate.prepareForAddingWrite()
                    aggregate.notifyWriteFailed(e)
                }
                return this
            }

            when (status) {
                ->           // The addToAggregator logic ensures that each SparseAggregateWriteStatus has at most one
                    // SparseAggregateWriteStatus parent.
                    sparse.addToAggregator(aggregate)

                else -> {
                    aggregate.prepareForAddingWrite()
                    com.google.common.util.concurrent.Futures.addCallback<Boolean?>(
                        status,
                        aggregate as com.google.common.util.concurrent.FutureCallback<Boolean?>,
                        com.google.common.util.concurrent.MoreExecutors.directExecutor()
                    )
                }
            }
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        override fun addAll(
            statuses: Iterable<out com.google.common.util.concurrent.ListenableFuture<Boolean?>>
        ): SparseAggregateWriteStatusBuilder {
            for (status in statuses) {
                add(status)
            }
            return this
        }

        override fun build(): SparseAggregateWriteStatus {
            com.google.common.base.Preconditions.checkState(
                !preincrementCleared.getAndSet(true),
                "build must only be called once"
            )
            aggregate.clearPreincrement()
            return aggregate
        }
    }

    /**
     * A settable [WriteStatus], analogous to [ ].
     */
    class SettableWriteStatus : com.google.common.util.concurrent.AbstractFuture<Boolean?>(), WriteStatus {
        /**
         * Signals the successful completion of the write operation with novelty information.
         * 
         * @param wasNovel true if new bytes were actually written; false if they already existed in the
         * backend.
         */
        /** Signals the successful completion of the write operation with novelty set to true.  */
        @kotlin.jvm.JvmOverloads
        fun markSuccess(wasNovel: Boolean = true) {
            com.google.common.base.Preconditions.checkState(
                set(wasNovel),
                "attempted to markSuccess already set %s",
                this
            )
        }

        fun failWith(cause: Throwable) {
            if (cause is CancellationException) {
                com.google.common.base.Preconditions.checkState(
                    cancel( /* mayInterruptIfRunning= */false),
                    "attempted to failWith(%s) already set %s",
                    cause,
                    this
                )
                return
            }
            com.google.common.base.Preconditions.checkState(
                setException(cause),
                "attempted to failWith(%s) already set %s",
                cause,
                this
            )
        }

        fun completeWith(future: WriteStatus) {
            com.google.common.base.Preconditions.checkState(
                setFuture(future),
                "attempted to completeWith(%s) already set %s",
                future,
                this
            )
        }
    }

    private class ImmediateWriteStatus(private val wasNovel: Boolean) : WriteStatus {
        override fun addListener(listener: java.lang.Runnable, executor: java.util.concurrent.Executor) {
            executor.execute(listener) // Immediately executes listener.
        }

        override fun cancel(mayInterruptIfRunning: Boolean): Boolean {
            return false
        }

        override fun get(): Boolean {
            return wasNovel
        }

        override fun get(timeout: Long, unit: TimeUnit?): Boolean {
            return wasNovel
        }

        override fun isCancelled(): Boolean {
            return false
        }

        override fun isDone(): Boolean {
            return true
        }

        companion object {
            private val NOVEL = ImmediateWriteStatus(true)
            private val NOT_NOVEL = ImmediateWriteStatus(false)
        }
    }

    private class ImmediateFailedWriteStatus(cause: Throwable?) : WriteStatus {
        private val exception: ExecutionException

        init {
            this.exception = ExecutionException(cause)
        }

        override fun addListener(listener: java.lang.Runnable, executor: java.util.concurrent.Executor) {
            executor.execute(listener) // Immediately executes listener.
        }

        override fun cancel(mayInterruptIfRunning: Boolean): Boolean {
            return false
        }

        @Throws(ExecutionException::class)
        override fun get(): Boolean {
            throw exception
        }

        @Throws(ExecutionException::class)
        override fun get(timeout: Long, unit: TimeUnit?): Boolean {
            return get()
        }

        override fun isCancelled(): Boolean {
            return false
        }

        override fun isDone(): Boolean {
            return true
        }
    }
}
