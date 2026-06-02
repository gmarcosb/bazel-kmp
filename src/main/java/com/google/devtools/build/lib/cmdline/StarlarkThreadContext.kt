// Copyright 2018 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.cmdline

import com.google.devtools.build.lib.supplier.InterruptibleSupplier

/**
 * Bazel-specific contextual information associated with a Starlark evaluation thread.
 * 
 * 
 * This is stored in the [StarlarkThread] object as a thread-local. A distinct subclass of
 * this class should be defined and used for each different scenario of Starlark evaluation; in any
 * case, it is still keyed in the thread-locals under `StarlarkThreadContext.class`. Users of
 * this class should prefer to use a `fromOrFail` static method to retrieve an instance from a
 * [StarlarkThread] instead of calling [StarlarkThread.getThreadLocal] directly, and
 * prefer to use [.storeInThread] instead of calling [StarlarkThread.setThreadLocal]
 * directly.
 * 
 * 
 * This object tends to be mutable and should not be accessed simultaneously or reused for more
 * than one Starlark thread.
 */
abstract class StarlarkThreadContext protected constructor(mainRepoMappingSupplier: InterruptibleSupplier<com.google.devtools.build.lib.cmdline.RepositoryMapping?>?) {
    // TODO: decide the extent to which we should enforce that such a context object is available
    //  anywhere we execute Starlark code in Bazel. As of right now (Jun 2024), the only field here is
    //  `mainRepoMappingSupplier`, and even that one is not strictly necessary (can be null and things
    //  will still work).
    /**
     * Saves this [StarlarkThreadContext] in the specified Starlark thread. Call only once,
     * before evaluation begins.
     * 
     * 
     * Users of this class should prefer to use this method instead of calling [ ][StarlarkThread.setThreadLocal] directly.
     */
    fun storeInThread(thread: net.starlark.java.eval.StarlarkThread) {
        com.google.common.base.Preconditions.checkState(
            thread.getThreadLocal<StarlarkThreadContext?>(
                StarlarkThreadContext::class.java
            ) == null
        )
        thread.setThreadLocal<StarlarkThreadContext?>(StarlarkThreadContext::class.java, this)
    }

    private val mainRepoMappingSupplier: InterruptibleSupplier<com.google.devtools.build.lib.cmdline.RepositoryMapping?>?

    /**
     * @param mainRepoMappingSupplier a supplier for the repo mapping of the main repo. This is used
     * for debug-printing [Label] objects. Can be null if the main repo mapping isn't
     * readily available, which just causes the debug-printing to produce canonical label
     * literals.
     */
    init {
        this.mainRepoMappingSupplier = mainRepoMappingSupplier
    }

    @get:Throws(java.lang.InterruptedException::class)
    val mainRepoMapping: com.google.devtools.build.lib.cmdline.RepositoryMapping?
        /**
         * The repository mapping applicable to the main repository. This is purely meant to support
         * [Label.debugPrint].
         */
        get() = if (mainRepoMappingSupplier == null) null else mainRepoMappingSupplier.get()
}
