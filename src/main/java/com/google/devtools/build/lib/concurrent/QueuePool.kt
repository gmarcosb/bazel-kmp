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
 * A shared, thread-local pool of [ArrayList] containing `Operation` instances.
 * 
 * 
 * This pool is designed to be shared across multiple [EagerRequestBatcher] instances. It
 * eliminates churn by reusing the same thread-local list allocations.
 */
class QueuePool<RequestT, ResponseT>(maxBatchSize: Int) {
    private val pool: java.lang.ThreadLocal<MutableList<com.google.devtools.build.lib.concurrent.RequestBatching.Operation<RequestT?, ResponseT?>?>?>
    val maxBatchSize: Int

    init {
        com.google.common.base.Preconditions.checkArgument(maxBatchSize >= 1, "maxBatchSize must be >= 1")
        this.maxBatchSize = maxBatchSize
        this.pool =
            java.lang.ThreadLocal.withInitial<MutableList<com.google.devtools.build.lib.concurrent.RequestBatching.Operation<RequestT?, ResponseT?>?>?>(
                java.util.function.Supplier {
                    java.util.ArrayList<com.google.devtools.build.lib.concurrent.RequestBatching.Operation<RequestT?, ResponseT?>?>(
                        maxBatchSize
                    )
                })
    }

    val queue: MutableList<com.google.devtools.build.lib.concurrent.RequestBatching.Operation<RequestT?, ResponseT?>>?
        /**
         * Gets a list from the pool for the current thread.
         * 
         * 
         * IMPORTANT: if the caller modifies or takes ownership of this list, it must recycle a
         * different, unowned, list. Otherwise, a later call to `getQueue` could return the same
         * list and cause an aliasing bug.
         */
        get() = pool.get()

    /** Clears the list and returns it to the pool for the current thread.  */
    fun recycleQueue(queue: MutableList<com.google.devtools.build.lib.concurrent.RequestBatching.Operation<RequestT?, ResponseT?>?>) {
        queue.clear()
        pool.set(queue)
    }
}
