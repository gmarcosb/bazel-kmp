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

import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock

/**
 * An [java.util.concurrent.ExecutorService] that creates a pool of threads to execute
 * submitted tasks in a work-stealing manner.
 * 
 * 
 * Similar to [java.util.concurrent.ForkJoinPool], [WorkStealingThreadPoolExecutor]
 * submits tasks to threads' local task queue in order to reduce contention and implements
 * work-stealing to improve the throughput.
 * 
 * 
 * One difference to [java.util.concurrent.ForkJoinPool] is, [ ] accepts a [ThreadFactory] which allows users to use `VirtualThread` as worker threads.
 */
class WorkStealingThreadPoolExecutor(parallelism: Int, threadFactory: ThreadFactory) : AbstractExecutorService() {
    private val threadFactory: ThreadFactory
    private val workerMap: ConcurrentHashMap<java.lang.Thread, Worker?>

    private val workers: com.google.common.collect.ImmutableList<Worker>
    private val deregisteredWorkersCountDown: CountDownLatch

    @kotlin.concurrent.Volatile
    var isShutdown: Boolean = false
        private set
    private val remainingTasks: AtomicInteger = AtomicInteger(0)
    private val remainingTasksAvailableLock: ReentrantLock = ReentrantLock()

    /** A condition object for the condition `remainingTasks.get() > 0`.  */
    @javax.annotation.concurrent.GuardedBy("remainingTasksAvailableLock")
    private val remainingTasksAvailableCondition: java.util.concurrent.locks.Condition =
        remainingTasksAvailableLock.newCondition()

    /** A task queue that is local to a worker thread. All methods must be thread-safe.  */
    private class TaskQueue {
        private val queue: ConcurrentLinkedDeque<java.lang.Runnable?> = ConcurrentLinkedDeque<java.lang.Runnable?>()

        /** Add a task to this queue  */
        fun add(runnable: java.lang.Runnable?) {
            queue.addLast(runnable)
        }

        /**
         * Retrieves and removes a task from this deque for the owning worker, or returns `null`
         * if this queue is empty.
         */
        fun poll(): java.lang.Runnable? {
            return queue.pollLast()
        }

        /**
         * Retrieves and removes a task from this deque for the non-owning worker, or returns `null` if this queue is empty.
         */
        fun steal(): java.lang.Runnable? {
            return queue.pollFirst()
        }
    }

    init {
        com.google.common.base.Preconditions.checkState(parallelism > 0)

        this.threadFactory = threadFactory
        this.workerMap = ConcurrentHashMap<java.lang.Thread, Worker?>(parallelism)
        this.deregisteredWorkersCountDown = CountDownLatch(parallelism)

        val threads: java.util.ArrayList<java.lang.Thread> = java.util.ArrayList<java.lang.Thread>(parallelism)
        val workers: com.google.common.collect.ImmutableList.Builder<Worker?> =
            com.google.common.collect.ImmutableList.builderWithExpectedSize<Worker?>(parallelism)
        for (i in 0..<parallelism) {
            val worker: Worker = com.google.devtools.build.lib.concurrent.WorkStealingThreadPoolExecutor.Worker(this)
            workers.add(worker)
            threads.add(threadFactory.newThread(worker))
        }
        this.workers = workers.build()

        for (thread in threads) {
            thread.start()
        }
    }

    private class Worker(private val pool: WorkStealingThreadPoolExecutor) : java.lang.Runnable {
        private val queue: TaskQueue =
            com.google.devtools.build.lib.concurrent.WorkStealingThreadPoolExecutor.TaskQueue()

        override fun run() {
            val thread: java.lang.Thread? = java.lang.Thread.currentThread()
            pool.registerWorker(thread, this)
            try {
                var task: java.lang.Runnable?
                while ((pool.pollOrStealOrWaitTask(this).also { task = it }) != null) {
                    try {
                        task.run()
                    } catch (e: Throwable) {
                        // If the task throws an exception, let the thread exit so it gets reported to the
                        // uncaught exception handler, but create a fresh thread to fill its place.
                        pool.assignWorkerToNewThread(thread, this)
                        throw e
                    }
                }
            } finally {
                pool.deregisterWorker(thread)
            }
        }

        /** Add a task to the worker's local queue.  */
        fun addTask(task: java.lang.Runnable?) {
            queue.add(task)
            pool.remainingTasks.incrementAndGet()
        }

        /**
         * Retrieves and removes a task from the worker's own queue, or returns `null` if the
         * queue is empty.
         */
        fun poll(): java.lang.Runnable? {
            val task: java.lang.Runnable? = queue.poll()
            if (task != null) {
                pool.remainingTasks.decrementAndGet()
            }
            return task
        }

        /**
         * Retrieves and removes a task from the worker's own queue, or returns `null` if the
         * queue is empty.
         * 
         * 
         * Thread that doesn't own the worker should use [.steal] to retrive task. It retrives
         * task from another end of the queue, so that, when the system is under pressure, the tasks
         * won't be retained for a long time. Failed to do so will likely cause more major GCs.
         */
        fun steal(): java.lang.Runnable? {
            val task: java.lang.Runnable? = queue.steal()
            if (task != null) {
                pool.remainingTasks.decrementAndGet()
            }
            return task
        }

        /**
         * Retrieves and removes all tasks from the worker's own queue and adds them to `remainingTasks`.
         */
        fun pollRemainingTasks(remainingTasks: com.google.common.collect.ImmutableList.Builder<java.lang.Runnable?>) {
            var task: java.lang.Runnable?
            while ((queue.poll().also { task = it }) != null) {
                pool.remainingTasks.decrementAndGet()
                remainingTasks.add(task)
            }
        }
    }

    private fun registerWorker(thread: java.lang.Thread?, worker: Worker?) {
        workerMap.put(thread, worker)
    }

    private fun assignWorkerToNewThread(thread: java.lang.Thread?, worker: Worker?) {
        val prevWorker: Worker? = workerMap.remove(thread)
        com.google.common.base.Preconditions.checkState(prevWorker === worker)
        threadFactory.newThread(worker).start()
    }

    private fun deregisterWorker(thread: java.lang.Thread?) {
        // If the worker is assigned to a different thread, do nothing.
        if (workerMap.remove(thread) != null) {
            deregisteredWorkersCountDown.countDown()
        }
    }

    /**
     * Retrieves and removes a task from a random worker in the pool, or returns `null` if no
     * task is available.
     */
    private fun scan(): java.lang.Runnable? {
        val numWorkers: Int = workers.size()
        // Scan workers starting at a random location and with a fixed step. Use an arbitrary prime
        // number for the step so that it shares no common divisor with `numWorkers`, thus ensuring
        // that each of the `numWorkers` iterations visits a distinct worker.
        var i: Int = java.util.concurrent.ThreadLocalRandom.current().nextInt(numWorkers)
        val step = 31
        for (n in numWorkers downTo 1) {
            val task: java.lang.Runnable? = workers.get(i).steal()
            if (task != null) {
                return task
            }
            i = (i + step) % numWorkers
        }
        return null
    }

    /**
     * Retrieves and removes a task from `worker`'s queue. If no task is available, steals tasks
     * from other workers in the pool. If still no task is available, blocking await until the thread
     * is signaled.
     * 
     * 
     * Returns `null` if no more tasks in the pool and the executor [.isShutdown].
     */
    private fun pollOrStealOrWaitTask(worker: Worker): java.lang.Runnable? {
        while (true) {
            var task: java.lang.Runnable? = worker.poll()
            if (task == null) {
                task = scan()
            }
            if (task != null) {
                return task
            }

            remainingTasksAvailableLock.lock()
            try {
                // Only wait if there is no pending tasks and more tasks are allowed to be submitted.
                // Otherwise, the thread may never be signaled.
                while (remainingTasks.get() == 0) {
                    if (isShutdown) {
                        return null
                    }

                    try {
                        remainingTasksAvailableCondition.await()
                    } catch (e: java.lang.InterruptedException) {
                        // Intentionally ignored
                    }
                }
            } finally {
                remainingTasksAvailableLock.unlock()
            }
        }
    }

    /** Wake up one worker that is awaiting new task.  */
    private fun signalOneWorker() {
        remainingTasksAvailableLock.lock()
        try {
            remainingTasksAvailableCondition.signal()
        } finally {
            remainingTasksAvailableLock.unlock()
        }
    }

    /** Wake up all workers that are awaiting new task.  */
    private fun signalAllWorkers() {
        remainingTasksAvailableLock.lock()
        try {
            remainingTasksAvailableCondition.signalAll()
        } finally {
            remainingTasksAvailableLock.unlock()
        }
    }

    override fun shutdown() {
        isShutdown = true
        signalAllWorkers()
    }

    override fun shutdownNow(): com.google.common.collect.ImmutableList<java.lang.Runnable?> {
        isShutdown = true

        val remainingTasks: com.google.common.collect.ImmutableList.Builder<java.lang.Runnable?> =
            com.google.common.collect.ImmutableList.builder<java.lang.Runnable?>()
        for (worker in workers) {
            worker.pollRemainingTasks(remainingTasks)
        }
        for (thread in workerMap.keySet()) {
            thread.interrupt()
        }
        return remainingTasks.build()
    }

    val isTerminated: Boolean
        get() = deregisteredWorkersCountDown.getCount() == 0L

    @Throws(java.lang.InterruptedException::class)
    override fun awaitTermination(timeout: Long, unit: TimeUnit?): Boolean {
        return deregisteredWorkersCountDown.await(timeout, unit)
    }

    private val randomWorker: Worker
        get() = workers.get(java.util.concurrent.ThreadLocalRandom.current().nextInt(workers.size()))

    override fun execute(command: java.lang.Runnable?) {
        if (this.isShutdown) {
            throw RejectedExecutionException()
        }

        var worker: Worker? = workerMap.get(java.lang.Thread.currentThread())
        if (worker == null) {
            worker = this.randomWorker
        }
        worker.addTask(command)
        // Wake up an idle worker to give it a chance to steal the task.
        signalOneWorker()
    }
}
