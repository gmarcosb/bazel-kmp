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
package com.google.devtools.build.lib.concurrent

/**
 * A future that tracks in-flight tasks and completes when the tasks quiesce or an error occurs.
 * 
 * 
 * It uses the *pre-increment* pattern (initializing `taskCount` to 1), which is
 * useful in cases where the task count can transiently hit zero during setup or if there are cases
 * where no tasks are created at all. The caller should call [.decrement] one additional time
 * after initialization to offset the pre-increment.
 * 
 * 
 * Contrast this with [QuiescingFutureTask], which handles this offset automatically. In
 * this class, the manual call to [.decrement] is **mandatory**. Typical usage looks like
 * the following.
 * 
 * 
 *  1. Create a [QuiescingFuture]. Once created, the future may be used freely, for example,
 * to chain other tasks.
 *  1. Start tasks, instrumented with calls to [.increment] on creation and [       ][.decrement] on completion. The tasks may recursively create more instrumented tasks. If a
 * task recursively creates child tasks, it must [.increment] for child tasks before
 * calling [.decrement] to mark its own completion to avoid premature completion.
 *  1. Call [.decrement] once to offset the *pre-increment*.
 *  1. The future completes once all the tasks complete (but not before step 3 above).
 * 
 */
abstract class QuiescingFuture<T> : com.google.devtools.build.lib.concurrent.AbstractQuiescingFuture<T?> {
    /**
     * Constructor.
     * 
     * @param getValueExecutor runner for running [.getValue] or [.doneWithError].
     */
    constructor(getValueExecutor: java.util.concurrent.Executor?) : super(getValueExecutor,  /* taskCount= */1)

    /**
     * Direct constructor.
     * 
     * 
     * This is useful when the total number of tasks is known in advance.
     * 
     * @param getValueExecutor runner for running [.getValue] or [.doneWithError].
     * @param taskCount initial task count, *no pre-increment* is applied
     */
    constructor(getValueExecutor: java.util.concurrent.Executor?, taskCount: Int) : super(getValueExecutor, taskCount)

    /**
     * Called when all tasks are complete.
     * 
     */
    @com.google.errorprone.annotations.DoNotCall
    @Deprecated("only for {@link #decrement}")
    override fun run() {
        handleQuiescence()
    }
}
