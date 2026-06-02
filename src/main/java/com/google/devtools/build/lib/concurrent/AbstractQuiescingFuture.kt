// Copyright 2026 The Bazel Authors. All rights reserved.
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

/**
 * A base class for futures that track in-flight tasks and complete when the tasks quiesce or an
 * error occurs.
 */
abstract class AbstractQuiescingFuture<T> protected constructor(
    getValueExecutor: java.util.concurrent.Executor,
    taskCount: Int
) : com.google.common.util.concurrent.AbstractFuture<T?>(), java.lang.Runnable {
    private val getValueExecutor: java.util.concurrent.Executor

    /**
     * Count of in-flight tasks.
     * 
     * 
     * This is initialized to 1 to support the "pre-increment" pattern, which prevents premature
     * completion during initialization.
     * 
     * 
     * Use [.TASK_COUNT_HANDLE] for atomic operations.
     */
    @com.google.errorprone.annotations.Keep // used via TASK_COUNT_HANDLE
    @kotlin.concurrent.Volatile
    private var taskCount: Int

    @com.google.errorprone.annotations.Keep // used via ERROR_COUNT_HANDLE
    @kotlin.concurrent.Volatile
    private var errorCount = 0

    /** Increments the task count.  */
    fun increment() {
        com.google.devtools.build.lib.concurrent.AbstractQuiescingFuture.Companion.TASK_COUNT_HANDLE.getAndAdd(this, 1)
    }

    /** Decrements the task count.  */
    fun decrement() {
        val countBeforeDecrement =
            com.google.devtools.build.lib.concurrent.AbstractQuiescingFuture.Companion.TASK_COUNT_HANDLE.getAndAdd(
                this,
                -1
            ) as Int
        if (countBeforeDecrement == 1) {
            getValueExecutor.execute(this)
        }
    }

    /**
     * Sets the future as failing with `t`.
     * 
     * 
     * If the client calls this, it should not call [.decrement] for the same task. It's
     * already called.
     */
    fun notifyException(t: Throwable) {
        setException(t)
        com.google.devtools.build.lib.concurrent.AbstractQuiescingFuture.Companion.ERROR_COUNT_HANDLE.getAndAdd(this, 1)
        decrement()
    }

    fun handleQuiescence() {
        if (com.google.devtools.build.lib.concurrent.AbstractQuiescingFuture.Companion.ERROR_COUNT_HANDLE.getAcquire(
                this
            ) as Int > 0
        ) {
            doneWithError()
        } else {
            set(this.value)
        }
    }

    @get:com.google.errorprone.annotations.ForOverride
    protected abstract val value: T?

    /**
     * Called if there was an error, after all the associated tasks complete.
     * 
     * 
     * Allows clients to perform cleanup work if there is an error.
     */
    @com.google.errorprone.annotations.ForOverride
    protected open fun doneWithError() {
    }

    /**
     * Constructor.
     * 
     * @param getValueExecutor runner for running [.getValue] or [.doneWithError].
     * @param taskCount initial task count.
     */
    init {
        this.getValueExecutor = getValueExecutor
        this.taskCount = taskCount
    }

    companion object {
        /**
         * Handle for [.taskCount].
         * 
         * 
         * This uses less memory than [java.util.concurrent.AtomicInteger].
         */
        private val TASK_COUNT_HANDLE: java.lang.invoke.VarHandle

        private val ERROR_COUNT_HANDLE: java.lang.invoke.VarHandle

        init {
            val lookup: java.lang.invoke.MethodHandles.Lookup = java.lang.invoke.MethodHandles.lookup()
            try {
                com.google.devtools.build.lib.concurrent.AbstractQuiescingFuture.Companion.TASK_COUNT_HANDLE =
                    lookup.findVarHandle(
                        com.google.devtools.build.lib.concurrent.AbstractQuiescingFuture::class.java,
                        "taskCount",
                        Int::class.javaPrimitiveType
                    )
                com.google.devtools.build.lib.concurrent.AbstractQuiescingFuture.Companion.ERROR_COUNT_HANDLE =
                    lookup.findVarHandle(
                        com.google.devtools.build.lib.concurrent.AbstractQuiescingFuture::class.java,
                        "errorCount",
                        Int::class.javaPrimitiveType
                    )
            } catch (e: java.lang.ReflectiveOperationException) {
                throw java.lang.ExceptionInInitializerError(e)
            }
        }
    }
}
