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
package com.google.devtools.build.lib.concurrent

import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

/**
 * An implementation of MultiThreadPoolsQuiescingExecutor that has 2 ExecutorServices, one with a
 * larger thread pool for IO/Network-bound tasks, and one with a smaller thread pool for CPU-bound
 * tasks.
 * 
 * 
 * With merged analysis and execution phases, this QueueVisitor is responsible for all 3 phases:
 * loading, analysis and execution. There's an additional 3rd pool for execution tasks. This is done
 * for performance reason: each of these phases has an optimal number of threads for its thread
 * pool.
 * 
 * 
 * Created anew each build.
 */
class MultiExecutorQueueVisitor private constructor(
    regularPoolExecutorService: ExecutorService?,
    cpuHeavyPoolExecutorService: ExecutorService?,
    executionPhaseExecutorService: ExecutorService?,
    exceptionHandlingMode: com.google.devtools.build.lib.concurrent.AbstractQueueVisitor.ExceptionHandlingMode?,
    errorClassifier: com.google.devtools.build.lib.concurrent.ErrorClassifier?
) : com.google.devtools.build.lib.concurrent.AbstractQueueVisitor(
    regularPoolExecutorService,
    com.google.devtools.build.lib.concurrent.AbstractQueueVisitor.ExecutorOwnership.PRIVATE,
    exceptionHandlingMode,
    errorClassifier
), com.google.devtools.build.lib.concurrent.MultiThreadPoolsQuiescingExecutor {
    private val regularPoolExecutorService: ExecutorService
    private val cpuHeavyPoolExecutorService: ExecutorService
    private val executionPhaseExecutorService: ExecutorService?

    // Whether execution phase tasks should be allowed to move forward.
    private var executionPhaseTasksGoAhead: Boolean

    @javax.annotation.concurrent.GuardedBy("this")
    private var queuedPendingGoAhead: MutableList<java.lang.Runnable?>? = null

    init {
        this.regularPoolExecutorService = super.getExecutorService()
        this.cpuHeavyPoolExecutorService =
            com.google.common.base.Preconditions.checkNotNull<ExecutorService>(cpuHeavyPoolExecutorService)
        this.executionPhaseExecutorService = executionPhaseExecutorService
        this.executionPhaseTasksGoAhead = executionPhaseExecutorService == null

        if (executionPhaseExecutorService != null) {
            queuedPendingGoAhead = java.util.ArrayList<java.lang.Runnable?>()
        }
    }

    override fun execute(
        runnable: java.lang.Runnable?,
        threadPoolType: com.google.devtools.build.lib.concurrent.MultiThreadPoolsQuiescingExecutor.ThreadPoolType,
        shouldStallAwaitingSignal: Boolean
    ) {
        if (shouldStallAwaitingSignal && !executionPhaseTasksGoAhead) {
            synchronized(this) {
                if (!executionPhaseTasksGoAhead) {
                    com.google.common.base.Preconditions.checkNotNull<MutableList<java.lang.Runnable?>?>(
                        queuedPendingGoAhead
                    ).add(runnable)
                    return
                }
            }
        }
        super.executeWithExecutorService(runnable, getExecutorServiceByThreadPoolType(threadPoolType))
    }

    @com.google.common.annotations.VisibleForTesting
    fun getExecutorServiceByThreadPoolType(threadPoolType: com.google.devtools.build.lib.concurrent.MultiThreadPoolsQuiescingExecutor.ThreadPoolType): ExecutorService? {
        return when (threadPoolType) {
            com.google.devtools.build.lib.concurrent.MultiThreadPoolsQuiescingExecutor.ThreadPoolType.REGULAR -> regularPoolExecutorService
            com.google.devtools.build.lib.concurrent.MultiThreadPoolsQuiescingExecutor.ThreadPoolType.CPU_HEAVY -> cpuHeavyPoolExecutorService
            com.google.devtools.build.lib.concurrent.MultiThreadPoolsQuiescingExecutor.ThreadPoolType.EXECUTION_PHASE -> {
                com.google.common.base.Preconditions.checkNotNull<ExecutorService?>(executionPhaseExecutorService)
                executionPhaseExecutorService
            }
        }
    }

    public override fun shutdownExecutorService(catastrophe: Throwable?) {
        if (catastrophe != null) {
            com.google.common.base.Throwables.throwIfUnchecked(catastrophe)
        }
        internalShutdownExecutorService(regularPoolExecutorService)
        internalShutdownExecutorService(cpuHeavyPoolExecutorService)
        if (executionPhaseExecutorService != null) {
            internalShutdownExecutorService(executionPhaseExecutorService)
        }
    }

    private fun internalShutdownExecutorService(executorService: ExecutorService) {
        executorService.shutdown()
        while (true) {
            try {
                executorService.awaitTermination(java.lang.Integer.MAX_VALUE.toLong(), TimeUnit.SECONDS)
                break
            } catch (e: java.lang.InterruptedException) {
                setInterrupted()
            }
        }
    }

    override fun launchQueuedUpExecutionPhaseTasks() {
        synchronized(this) {
            executionPhaseTasksGoAhead = true
            for (runnable in com.google.common.base.Preconditions.checkNotNull<MutableList<java.lang.Runnable?>?>(
                queuedPendingGoAhead
            )) {
                execute(
                    runnable,
                    com.google.devtools.build.lib.concurrent.MultiThreadPoolsQuiescingExecutor.ThreadPoolType.EXECUTION_PHASE,  /* shouldStallAwaitingSignal= */
                    false
                )
            }
            queuedPendingGoAhead = null
        }
    }

    override fun hasSeparatePoolForExecutionTasks(): Boolean {
        return executionPhaseExecutorService != null
    }

    companion object {
        fun createWithExecutorServices(
            regularPoolExecutorService: ExecutorService?,
            cpuHeavyPoolExecutorService: ExecutorService?,
            exceptionHandlingMode: com.google.devtools.build.lib.concurrent.AbstractQueueVisitor.ExceptionHandlingMode?,
            errorClassifier: com.google.devtools.build.lib.concurrent.ErrorClassifier?
        ): MultiExecutorQueueVisitor {
            return com.google.devtools.build.lib.concurrent.MultiExecutorQueueVisitor.Companion.createWithExecutorServices(
                regularPoolExecutorService,
                cpuHeavyPoolExecutorService,  /* executionPhaseExecutorService= */
                null,
                exceptionHandlingMode,
                errorClassifier
            )
        }

        fun createWithExecutorServices(
            regularPoolExecutorService: ExecutorService?,
            cpuHeavyPoolExecutorService: ExecutorService?,
            executionPhaseExecutorService: ExecutorService?,
            exceptionHandlingMode: com.google.devtools.build.lib.concurrent.AbstractQueueVisitor.ExceptionHandlingMode?,
            errorClassifier: com.google.devtools.build.lib.concurrent.ErrorClassifier?
        ): MultiExecutorQueueVisitor {
            return com.google.devtools.build.lib.concurrent.MultiExecutorQueueVisitor(
                regularPoolExecutorService,
                cpuHeavyPoolExecutorService,
                executionPhaseExecutorService,
                exceptionHandlingMode,
                errorClassifier
            )
        }
    }
}
