// Copyright 2024 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.worker

import com.google.common.flogger.GoogleLogger
import com.google.devtools.build.lib.supplier.InterruptibleSupplier.get
import com.google.devtools.build.lib.worker.WorkerFactory
import com.google.devtools.build.lib.worker.WorkerKey
import com.google.devtools.build.lib.worker.WorkerPool
import com.google.devtools.build.lib.worker.WorkerPoolConfig
import com.google.devtools.build.lib.worker.WorkerProcessStatus
import java.io.IOException
import java.util.HashSet
import java.util.LinkedHashMap
import java.util.concurrent.BlockingDeque
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.atomic.AtomicInteger

/**
 * Implementation of the WorkerPool.
 * 
 * 
 * TODO(b/323880131): Remove documentation once we completely remove the legacy implementation.
 * 
 * 
 * This implementation flattens this to have a single `WorkerKeyPool` for each worker key
 * (we don't need the indirection in referencing both mnemonic and worker key since the mnemonic is
 * part of the key). Additionally, it bakes in pool shrinking logic so that we can handle concurrent
 * calls.
 */
class WorkerPoolImpl(factory: WorkerFactory, config: WorkerPoolConfig) : WorkerPool {
    private val factory: WorkerFactory

    private val singleplexMaxInstances: com.google.common.collect.ImmutableMap<String?, Int?>
    private val multiplexMaxInstances: com.google.common.collect.ImmutableMap<String?, Int?>
    private val pools: ConcurrentHashMap<WorkerKey?, WorkerKeyPool> = ConcurrentHashMap<WorkerKey?, WorkerKeyPool>()

    init {
        this.factory = factory
        this.singleplexMaxInstances =
            getMaxInstances(config.getWorkerMaxInstances(), DEFAULT_MAX_SINGLEPLEX_WORKERS)
        this.multiplexMaxInstances =
            getMaxInstances(config.getWorkerMaxMultiplexInstances(), DEFAULT_MAX_MULTIPLEX_WORKERS)
    }

    override fun getMaxTotalPerKey(key: WorkerKey?): Int {
        return getPool(key).effectiveMax
    }

    override fun getNumActive(key: WorkerKey?): Int {
        return getPool(key).numActive
    }

    override fun hasAvailableQuota(key: WorkerKey?): Boolean {
        return getPool(key).hasAvailableQuota()
    }

    @Throws(java.lang.InterruptedException::class)
    override fun evictWorkers(workerIdsToEvict: com.google.common.collect.ImmutableSet<Int?>): com.google.common.collect.ImmutableSet<Int?> {
        // TODO: Without having the Worker objects themselves, we can't directly pass the worker to the
        // pool to be evicted.
        val evictedWorkerIds: com.google.common.collect.ImmutableSet<Int?> =
            pools.values().stream()
                .flatMap<Int?>(java.util.function.Function { p: WorkerKeyPool? ->
                    p!!.evictWorkers(workerIdsToEvict).stream()
                })
                .collect(com.google.common.collect.ImmutableSet.toImmutableSet<Int?>())
        return workerIdsToEvict.stream()
            .filter(java.util.function.Predicate { `object`: Int? -> evictedWorkerIds.contains(`object`) })
            .collect(com.google.common.collect.ImmutableSet.toImmutableSet<Int?>())
    }

    @get:Throws(java.lang.InterruptedException::class)
    val idleWorkers: com.google.common.collect.ImmutableSet<Int?>
        get() = pools.values().stream()
            .flatMap<Int?>(java.util.function.Function { p: WorkerKeyPool? -> p!!.getIdleWorkers().stream() })
            .collect(com.google.common.collect.ImmutableSet.toImmutableSet<Int?>())

    @Throws(IOException::class, java.lang.InterruptedException::class)
    override fun borrowWorker(key: WorkerKey): com.google.devtools.build.lib.worker.Worker? {
        return getPool(key).borrowWorker(key)
    }

    override fun returnWorker(key: WorkerKey?, obj: com.google.devtools.build.lib.worker.Worker) {
        getPool(key).returnWorker(key,  /* worker= */obj)
    }

    @Throws(java.lang.InterruptedException::class)
    override fun invalidateWorker(worker: com.google.devtools.build.lib.worker.Worker) {
        getPool(worker.getWorkerKey()).invalidateWorker(worker, worker.getStatus().isPendingEviction())
    }

    override fun reset() {
        for (pool in pools.values()) {
            pool.reset()
        }
    }

    override fun close() {
        for (pool in pools.values()) {
            pool.close()
        }
    }

    private fun getPool(key: WorkerKey?): WorkerKeyPool {
        return pools.computeIfAbsent(key, java.util.function.Function { key: WorkerKey? -> this.createPool(key) })
    }

    private fun createPool(key: WorkerKey): WorkerKeyPool {
        if (key.isMultiplex()) {
            return WorkerKeyPool(
                key,
                getMaxWorkerInstances(
                    multiplexMaxInstances, key.getMnemonic(), WorkerPoolImpl.Companion.DEFAULT_MAX_MULTIPLEX_WORKERS
                )!!
            )
        }
        return WorkerKeyPool(
            key,
            getMaxWorkerInstances(
                singleplexMaxInstances, key.getMnemonic(), DEFAULT_MAX_SINGLEPLEX_WORKERS
            )!!
        )
    }

    private fun getMaxWorkerInstances(
        maxInstances: com.google.common.collect.ImmutableMap<String?, Int?>, mnemonic: String?, defaultMaxInstances: Int
    ): Int? {
        if (maxInstances.containsKey(mnemonic)) {
            return maxInstances.get(mnemonic)
        }
        // Empty-string contains the user-specified worker maximum instances.
        return maxInstances.getOrDefault("", defaultMaxInstances)
    }

    /**
     * Actual pool implementation that handles the borrowing, returning and invalidation of workers of
     * a single worker key.
     * 
     * 
     * The following describes how the key features of the pool and how they work in tandem with
     * each other:
     * 
     * 
     *  * Borrowing a worker: If quota is available, the pool returns an already existing idle
     * worker or creates a new worker. If quota is not available, it creates a `PendingWorkerRequest` in the waiting queue and waits on it.
     *  * Returning worker: If there are pending requests in the waiting queue, directly hand the
     * worker over to that request, signalling to the waiting thread to proceed. Otherwise,
     * returns the worker back to the pool.
     *  * Invalidating worker: Destroys this worker and removes it from the pool. The pool is
     * optionally shrunk, which reduces the maximum number of workers that can be in the pool
     * (to a minimum of 1). If the pool is not shrunk, the destruction of this worker represents
     * a freeing up of quota, in this case it signals for any pending request to continue and
     * effectively taking over this quota.
     * 
     */
    private inner class WorkerKeyPool(key: WorkerKey, max: Int) {
        private val key: WorkerKey
        private val max: Int

        // The number of workers in use.
        private val acquired: AtomicInteger = AtomicInteger(0)

        // The number by which the overall quota is shrunk by.
        private val shrunk: AtomicInteger = AtomicInteger(0)

        private val idleWorkers: BlockingDeque<com.google.devtools.build.lib.worker.Worker> =
            LinkedBlockingDeque<com.google.devtools.build.lib.worker.Worker>()

        /**
         * The waiting queue is meant to provide fairness in borrowing from the pool (first come first
         * serve), any freeing up of quota (either through returning or invalidating a worker) will
         * service requests this queue first.
         * 
         * 
         * With workers as a resource, workers are only borrowed when they are available, so this
         * doesn't get used, i.e. there shouldn't be any borrowers waiting here, where the `ResourceManager` handles the proper synchronization to ensure that workers are borrowed
         * together with its allocated resources.
         * 
         * 
         * Regardless, this implementation is still included to ensure correctness such that multiple
         * threads can still borrow concurrently, without needing to check how many workers are actually
         * available (blocking if unavailable).
         */
        private val waitingQueue: BlockingDeque<PendingWorkerRequest?> = LinkedBlockingDeque<PendingWorkerRequest?>()

        private val activeSet: MutableSet<com.google.devtools.build.lib.worker.Worker> =
            HashSet<com.google.devtools.build.lib.worker.Worker>()

        init {
            this.key = key
            this.max = max
        }

        @kotlin.jvm.Synchronized
        fun evictWorkers(workerIdsToEvict: MutableSet<Int?>): MutableSet<Int?> {
            val evictedWorkerIds: MutableSet<Int?> = HashSet<Int?>()
            for (worker in idleWorkers) {
                if (workerIdsToEvict.contains(worker.getWorkerId())) {
                    evictedWorkerIds.add(worker.getWorkerId())
                    worker.getStatus()
                        .maybeUpdateStatus(com.google.devtools.build.lib.worker.WorkerProcessStatus.Status.PENDING_KILL_DUE_TO_MEMORY_PRESSURE)
                    invalidateWorker(worker,  /* shouldShrinkPool= */true)
                    idleWorkers.remove(worker)
                    logger.atInfo().log(
                        "Evicted %s worker (id %d, key hash %d).",
                        worker.getWorkerKey().getMnemonic(),
                        worker.getWorkerId(),
                        worker.getWorkerKey().hashCode()
                    )
                }
                // TODO(b/323880131): Move postponing of invalidation from {@code WorkerLifecycleManager}
                // here, since all we need to do is to update the statuses. We keep it like this for now
                // to preserve the existing behavior.
            }
            return evictedWorkerIds
        }

        @get:kotlin.jvm.Synchronized
        val numActive: Int
            get() = acquired.get()

        @get:kotlin.jvm.Synchronized
        val effectiveMax: Int
            get() = max - shrunk.get()

        @kotlin.jvm.Synchronized
        fun hasAvailableQuota(): Boolean {
            return this.effectiveMax - this.numActive > 0
        }

        // Callers should atomically check to confirm that workers are available before calling this
        // method or risk being blocked waiting for a worker to be available.
        @Throws(IOException::class, java.lang.InterruptedException::class)
        fun borrowWorker(key: WorkerKey): com.google.devtools.build.lib.worker.Worker? {
            var worker: com.google.devtools.build.lib.worker.Worker? = null
            var pendingReq: PendingWorkerRequest? = null
            // We don't want to hold the lock on the pool while creating or waiting for a worker or quota
            // to be available.
            synchronized(this) {
                while (!idleWorkers.isEmpty()) {
                    // LIFO: It's better to re-use a worker as often as possible and keep it hot, in order to
                    // profit from JIT optimizations as much as possible.
                    // This cannot be null because we already checked that the queue is not empty.
                    worker = idleWorkers.peekLast()
                    // We need to validate with the passed in `key` rather than `worker.getWorkerKey()`
                    // because the former can contain a different combined files hash if the files changed.
                    if (factory.validateWorker(key, worker)) {
                        acquired.incrementAndGet()
                        idleWorkers.remove(worker)
                        break
                    }
                    invalidateWorker(worker,  /* shouldShrinkPool= */false)
                    worker = null
                }
                if (worker == null) {
                    // If we were unable to get an idle worker, then either create or wait for one.
                    if (hasAvailableQuota()) {
                        // No idle workers, but we have space to create another.
                        acquired.incrementAndGet()
                    } else {
                        pendingReq = PendingWorkerRequest()
                        waitingQueue.add(pendingReq)
                    }
                }
            }

            if (pendingReq != null) {
                // Wait until the resources are available. We cannot do this while synchronized because that
                // would deadlock by blocking other threads from returning and thus freeing up quota for
                // this to proceed.
                worker = pendingReq.await()
            }

            if (worker == null) {
                worker = factory.create(key)
            }

            activeSet.add(worker)
            return worker
        }

        @kotlin.jvm.Synchronized
        fun returnWorker(key: WorkerKey?, worker: com.google.devtools.build.lib.worker.Worker) {
            if (!factory.validateWorker(key, worker)) {
                invalidateWorker(worker, true)
                return
            }

            if (activeSet.contains(worker)) {
                activeSet.remove(worker)
            } else {
                throw java.lang.IllegalStateException(
                    java.lang.String.format(
                        "Worker %s (id %d) is not in the active set",
                        worker.getWorkerKey().getMnemonic(), worker.getWorkerId()
                    )
                )
            }

            val pendingReq: PendingWorkerRequest? = waitingQueue.poll()
            if (pendingReq != null) {
                // Pass the worker directly to the waiting thread.
                pendingReq.signal(worker)
            } else {
                acquired.decrementAndGet()
                idleWorkers.addLast(worker)
            }
        }

        @kotlin.jvm.Synchronized
        fun invalidateWorker(worker: com.google.devtools.build.lib.worker.Worker, shouldShrinkPool: Boolean) {
            factory.destroyWorker(worker.getWorkerKey(), worker)

            if (idleWorkers.contains(worker)) {
                idleWorkers.remove(worker)
                return
            }

            // If it isn't idle, then we're destroying an active worker.
            if (activeSet.contains(worker)) {
                activeSet.remove(worker)
            } else {
                throw java.lang.IllegalStateException(
                    java.lang.String.format(
                        "Worker %s (id %d) is not in the active set",
                        worker.getWorkerKey().getMnemonic(), worker.getWorkerId()
                    )
                )
            }

            // We don't want to shrink the pool to 0.
            if (shouldShrinkPool && this.effectiveMax > 1) {
                // When shrinking, there is no effective change in the availability, so there is no need to
                // signal a waiting thread to proceed.
                acquired.decrementAndGet()
                shrunk.incrementAndGet()
                return
            }

            val pendingReq: PendingWorkerRequest? = waitingQueue.poll()
            if (pendingReq == null) {
                // Since there is no pending request, we free up this quota.
                acquired.decrementAndGet()
            } else {
                // Since there is a pending request, hold onto this quota (and do not decrement acquired) so
                // that other threads aren't able to borrow before this pending request (thus creating a
                // race condition).
                pendingReq.signal(null)
            }
        }

        /**
         * It is not important that we synchronize here, the `WorkerLifecycleManager` takes the
         * idle workers and figures out (non-atomically with respect to this instance) which workers to
         * evict. So it is possible that an idle worker gets acquired before it decides to evict a
         * previously idle worker.
         */
        fun getIdleWorkers(): MutableSet<Int?> {
            return idleWorkers.stream()
                .map<Int?>(java.util.function.Function { obj: com.google.devtools.build.lib.worker.Worker? -> obj.getWorkerId() })
                .collect(com.google.common.collect.ImmutableSet.toImmutableSet<Int?>())
        }

        fun reset() {
            shrunk.set(0)
            logger.atInfo().log(
                "clearing shrunk values for %s (key hash %d) worker pool",
                key.getMnemonic(), key.hashCode()
            )
        }

        // Destroys all workers created in this pool.
        @kotlin.jvm.Synchronized
        fun close() {
            for (worker in idleWorkers) {
                factory.destroyWorker(worker.getWorkerKey(), worker)
            }
            for (worker in activeSet) {
                logger.atInfo().log(
                    "Interrupting and shutting down active worker %s (id %d) due to pool shutdown",
                    key.getMnemonic(), worker.getWorkerId()
                )
                factory.destroyWorker(worker.getWorkerKey(), worker)
            }
        }
    }

    /**
     * Used to pass workers from threads that are returning the worker to the pool, bypassing the
     * queue.
     */
    private class PendingWorkerRequest {
        val latch: CountDownLatch = CountDownLatch(1)

        @kotlin.concurrent.Volatile
        var worker: com.google.devtools.build.lib.worker.Worker? = null

        /** Returns a worker instance that has been freed, or null if the worker needs to be created.  */
        @Throws(java.lang.InterruptedException::class)
        fun await(): com.google.devtools.build.lib.worker.Worker? {
            latch.await()
            return worker
        }

        /**
         * Signals to the thread #await(ing) to proceed. When calling this, the `WorkerKeyPool.acquired` quota associated to this worker should not be released because that
         * allows for race conditions with other threads attempting to borrow from the pool.
         */
        fun signal(worker: com.google.devtools.build.lib.worker.Worker?) {
            this.worker = worker
            latch.countDown()
        }
    }

    companion object {
        private val logger: GoogleLogger = GoogleLogger.forEnclosingClass()

        /** Unless otherwise specified, the max number of workers per WorkerKey.  */
        private const val DEFAULT_MAX_SINGLEPLEX_WORKERS = 4

        /** Unless otherwise specified, the max number of multiplex workers per WorkerKey.  */
        private const val DEFAULT_MAX_MULTIPLEX_WORKERS = 8

        private fun getMaxInstances(
            maxInstances: MutableList<MutableMap.MutableEntry<String?, Int?>>, defaultMaxWorkers: Int
        ): com.google.common.collect.ImmutableMap<String?, Int?> {
            val newConfigBuilder: LinkedHashMap<String?, Int?> = LinkedHashMap<String?, Int?>()
            for (entry in maxInstances) {
                if (entry.getValue() != null) {
                    newConfigBuilder.put(entry.getKey(), entry.getValue())
                } else if (entry.getKey() != null) {
                    newConfigBuilder.put(entry.getKey(), defaultMaxWorkers)
                }
            }
            return com.google.common.collect.ImmutableMap.copyOf<String?, Int?>(newConfigBuilder)
        }
    }
}
