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

import java.util.concurrent.ExecutorService
import java.util.concurrent.ForkJoinPool
import java.util.concurrent.ForkJoinTask

/** A [QuiescingExecutor] implementation that wraps a [ForkJoinPool].  */ // TODO(bazel-team): This extends AQV to ensure that they share the same semantics for interrupt
// handling, error propagation, and task completion. Because FJP provides a native implementation
// for awaitQuiescence, a careful refactoring would allow FJQE to avoid the overhead of
// maintaining AQV.remainingTasks.
class ForkJoinQuiescingExecutor private constructor(
    forkJoinPool: ForkJoinPool?,
    errorClassifier: com.google.devtools.build.lib.concurrent.ErrorClassifier?
) : com.google.devtools.build.lib.concurrent.AbstractQueueVisitor(
    forkJoinPool,
    com.google.devtools.build.lib.concurrent.AbstractQueueVisitor.ExecutorOwnership.PRIVATE,
    com.google.devtools.build.lib.concurrent.AbstractQueueVisitor.ExceptionHandlingMode.FAIL_FAST,
    errorClassifier
) {
    /** Builder for [ForkJoinQuiescingExecutor].  */
    class Builder private constructor() {
        private var forkJoinPool: ForkJoinPool? = null
        private var errorClassifier: com.google.devtools.build.lib.concurrent.ErrorClassifier? =
            com.google.devtools.build.lib.concurrent.ErrorClassifier.Companion.DEFAULT

        /**
         * Sets the [ForkJoinPool] that will be used by the to-be-built [ ]. The given [ForkJoinPool] will be shut down on completion of
         * the [ForkJoinQuiescingExecutor].
         */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun withOwnershipOf(forkJoinPool: ForkJoinPool?): Builder {
            com.google.common.base.Preconditions.checkState(this.forkJoinPool == null)
            this.forkJoinPool = forkJoinPool
            return this
        }

        /**
         * Sets the [ErrorClassifier] that will be used by the to-be-built [ ].
         */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setErrorClassifier(errorClassifier: com.google.devtools.build.lib.concurrent.ErrorClassifier?): Builder {
            this.errorClassifier = errorClassifier
            return this
        }

        /**
         * Returns a fresh [ForkJoinQuiescingExecutor] using the previously given options.
         */
        fun build(): ForkJoinQuiescingExecutor {
            com.google.common.base.Preconditions.checkNotNull<ForkJoinPool?>(
                forkJoinPool,
                "fork join pool must be supplied"
            )
            return com.google.devtools.build.lib.concurrent.ForkJoinQuiescingExecutor(forkJoinPool, errorClassifier)
        }
    }

    override fun executeWrappedRunnable(
        runnable: com.google.devtools.build.lib.concurrent.AbstractQueueVisitor.WrappedRunnable,
        executorService: ExecutorService
    ) {
        if (ForkJoinTask.getPool() === executorService) {
            @Suppress("unused") val possiblyIgnoredError: java.util.concurrent.Future<*> =
                ForkJoinTask.adapt(runnable).fork()
        } else {
            super.executeWrappedRunnable(runnable, executorService)
        }
    }

    companion object {
        /** Returns a fresh [Builder].  */
        @kotlin.jvm.JvmStatic
        fun newBuilder(): Builder {
            return com.google.devtools.build.lib.concurrent.ForkJoinQuiescingExecutor.Builder()
        }
    }
}
