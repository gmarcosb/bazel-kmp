// Copyright 2022 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.runtime

import com.google.devtools.build.lib.concurrent.ExecutorUtil
import com.google.devtools.build.lib.runtime.BlazeModule
import com.google.devtools.build.lib.runtime.CommandEnvironment
import com.google.devtools.build.lib.util.AbruptExitException
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** A [BlazeModule] that waits for submitted tasks to terminate after every command.  */
class BlockWaitingModule : BlazeModule() {
    /** A task to be submitted.  */
    interface Task {
        @Throws(AbruptExitException::class)
        fun call()
    }

    /**
     * Wraps an AbruptExitException thrown by a task.
     * 
     * 
     * This is needed because a task that can throw a checked exception cannot be submitted to
     * [ExecutorService].
     */
    private class TaskException(cause: AbruptExitException?) : java.lang.RuntimeException(cause)

    private var executorService: ExecutorService? = null
    private var submittedTasks: java.util.ArrayList<java.util.concurrent.Future<*>>? = null

    @Throws(AbruptExitException::class)
    override fun beforeCommand(env: CommandEnvironment?) {
        com.google.common.base.Preconditions.checkState(executorService == null, "executorService must be null")
        com.google.common.base.Preconditions.checkState(submittedTasks == null, "submittedTasks must be null")

        executorService =
            Executors.newCachedThreadPool(
                com.google.common.util.concurrent.ThreadFactoryBuilder().setNameFormat("block-waiting-%d").build()
            )

        submittedTasks = java.util.ArrayList<java.util.concurrent.Future<*>>()
    }

    fun submit(task: Task) {
        com.google.common.base.Preconditions.checkNotNull<ExecutorService?>(
            executorService,
            "executorService must not be null"
        )
        com.google.common.base.Preconditions.checkNotNull<java.util.ArrayList<java.util.concurrent.Future<*>?>?>(
            submittedTasks,
            "submittedTasks must be null"
        )

        submittedTasks.add(
            executorService.submit(
                java.lang.Runnable {
                    try {
                        task.call()
                    } catch (e: AbruptExitException) {
                        throw TaskException(e)
                    }
                })
        )
    }

    @Throws(AbruptExitException::class)
    override fun afterCommand() {
        com.google.common.base.Preconditions.checkNotNull<ExecutorService?>(
            executorService,
            "executorService must not be null"
        )

        if (ExecutorUtil.interruptibleShutdown(executorService)) {
            java.lang.Thread.currentThread().interrupt()
        }

        for (f in submittedTasks) {
            try {
                f.get() // guaranteed to have completed.
            } catch (e: java.lang.InterruptedException) {
                throw java.lang.AssertionError("task should not have been interrupted")
            } catch (e: ExecutionException) {
                val cause: Throwable? = e.getCause()
                if (cause is TaskException) {
                    com.google.common.base.Preconditions.checkState(cause.getCause() is AbruptExitException)
                    throw cause.getCause() as AbruptExitException?
                }
                throw java.lang.RuntimeException(e)
            }
        }

        executorService = null
        submittedTasks = null
    }
}
