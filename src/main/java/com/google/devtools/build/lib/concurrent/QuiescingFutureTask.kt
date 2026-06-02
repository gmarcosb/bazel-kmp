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
 * A future that tracks in-flight tasks and completes when the tasks quiesce or an error occurs.
 * 
 * 
 * Unlike [QuiescingFuture], this class is itself a task that can be submitted to an [ ].
 * 
 * 
 * This class uses the "pre-increment" pattern (initializing `taskCount` to 1) to prevent
 * premature completion during initialization. However, it **automatically** offsets this by
 * calling [.decrement] at the end of [.run] (after [.arrangeSubtasks]). Unlike
 * [QuiescingFuture], users of [QuiescingFutureTask] do **not** need to call [ ][.decrement] manually to offset the initial count.
 */
abstract class QuiescingFutureTask<T>
/**
 * Constructor.
 * 
 * @param getValueExecutor runner for running [.getValue] or [.doneWithError].
 */
    (getValueExecutor: java.util.concurrent.Executor?) :
    com.google.devtools.build.lib.concurrent.AbstractQuiescingFuture<T?>(getValueExecutor,  /* taskCount= */1) {
    /** State used to distinguish between the initial run and subsequent completion runs.  */
    @com.google.errorprone.annotations.Keep // used via STATE_HANDLE
    @kotlin.concurrent.Volatile
    private var state = 0

    /**
     * Arranges subtasks.
     * 
     * 
     * Implementations should call [.increment] for each subtask and [.decrement] once
     * the subtask completes.
     * 
     * 
     * Note: The base class's [.run] method automatically calls [.decrement] to offset
     * the initial count after this method completes.
     * 
     * 
     * If this method fails with an unchecked exception, the future is failed immediately. In this
     * case, there's no guarantee that [.doneWithError] is called.
     */
    @com.google.errorprone.annotations.ForOverride
    protected abstract fun arrangeSubtasks()

    /**
     * Called to either arrange subtasks or handle quiescence.
     * 
     * 
     * Unlike [QuiescingFuture], this method is used for both the initial setup (by
     * submitting this task to an executor) and for finalization (when the task count reaches zero).
     * 
     * 
     *  * **INITIAL (0):** The first call to this method executes [.arrangeSubtasks] and
     * then calls [.decrement] to offset the initial count.
     *  * **ARRANGED (1):** Subsequent calls to this method (triggered when the task count
     * reaches zero) will execute the completion logic via [.handleQuiescence].
     * 
     */
    override fun run() {
        if (com.google.devtools.build.lib.concurrent.QuiescingFutureTask.Companion.STATE_HANDLE.compareAndSet(
                this,
                0,
                1
            )
        ) {
            try {
                arrangeSubtasks()
                decrement()
            } catch (t: Throwable) {
                notifyException(t)
            }
        } else {
            handleQuiescence()
        }
    }

    companion object {
        private val STATE_HANDLE: java.lang.invoke.VarHandle

        init {
            val lookup: java.lang.invoke.MethodHandles.Lookup = java.lang.invoke.MethodHandles.lookup()
            try {
                com.google.devtools.build.lib.concurrent.QuiescingFutureTask.Companion.STATE_HANDLE =
                    lookup.findVarHandle(
                        com.google.devtools.build.lib.concurrent.QuiescingFutureTask::class.java,
                        "state",
                        Int::class.javaPrimitiveType
                    )
            } catch (e: java.lang.ReflectiveOperationException) {
                throw java.lang.ExceptionInInitializerError(e)
            }
        }
    }
}
