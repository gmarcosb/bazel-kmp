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

import java.util.concurrent.CountDownLatch

/**
 * QuiescingExecutor is an [Executor] which supports waiting until all submitted tasks are
 * complete. This is useful when tasks may submit additional tasks.
 * 
 * 
 * Consider the following example:
 * <pre>
 * ThreadPoolExecutor executor = <...>
 * executor.submit(myRunnableTask);
 * executor.shutdown();
 * executor.awaitTermination();
</pre> * 
 * 
 * 
 * This won't work properly if `myRunnableTask` submits additional tasks to the
 * executor, because it may already have shut down by that point.
 * 
 * 
 * QuiescingExecutor supports interruption. If the main thread is interrupted, tasks will no
 * longer be started, and the [.awaitQuiescence] method will throw [ ].
 */
interface QuiescingExecutor : java.util.concurrent.Executor {
    /**
     * Waits for all tasks to complete. If the [QuiescingExecutor] owns its own [ ], the service will also be shutdown.
     * 
     * 
     * Throws (the same) unchecked exception if any worker thread failed unexpectedly. If the main
     * thread is interrupted and a worker also throws an unchecked exception, the unchecked exception
     * is rethrown, since it may indicate a programming bug. If callers handle the unchecked
     * exception, they may check the interrupted bit to see if the pool was interrupted.
     * 
     * @param interruptWorkers if true, interrupt worker threads if main thread gets an interrupt.
     * If false, just wait for them to terminate normally.
     */
    @Throws(java.lang.InterruptedException::class)
    fun awaitQuiescence(interruptWorkers: Boolean)

    /**
     * Similar to [.awaitQuiescence], but without shutting down the ExecutorService.
     * 
     * 
     * This is ideal for situations where tasks, that can spawn other tasks, are submitted in waves
     * and we'd like to reuse the same QuiescingExecutor for them.
     */
    @Throws(java.lang.InterruptedException::class)
    fun awaitQuiescenceWithoutShutdown(interruptWorkers: Boolean)

    /**
     * Prevent quiescence of the executor until the given future is completed. If the executor is
     * interrupted, then the executor will call [ListenableFuture.cancel] with a parameter of
     * `true`.
     */
    @Throws(java.lang.InterruptedException::class)
    fun dependOnFuture(future: com.google.common.util.concurrent.ListenableFuture<*>?)

    @kotlin.jvm.JvmField
    @get:com.google.common.annotations.VisibleForTesting
    val exceptionLatchForTestingOnly: CountDownLatch?

    @kotlin.jvm.JvmField
    @get:com.google.common.annotations.VisibleForTesting
    val interruptionLatchForTestingOnly: CountDownLatch?
}
