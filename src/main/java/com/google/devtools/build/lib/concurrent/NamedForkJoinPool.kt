// Copyright 2016 The Bazel Authors. All rights reserved.
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

import java.util.concurrent.ForkJoinPool
import java.util.concurrent.ForkJoinPool.ForkJoinWorkerThreadFactory
import java.util.concurrent.ForkJoinWorkerThread
import java.util.concurrent.atomic.AtomicLong

/** A [ForkJoinPool] with support for thread naming.  */
class NamedForkJoinPool private constructor(name: String?, poolSize: Int) : ForkJoinPool(
    poolSize,
    com.google.devtools.build.lib.concurrent.NamedForkJoinPool.NamedForkJoinWorkerThreadFactory(name + "-%s"),
    null,  // Uncaught exception handler.
    /* asyncMode= */
    false
) {
    /** A [ForkJoinWorkerThread] named on construction.  */
    private class NamedForkJoinWorkerThread(forkJoinPool: ForkJoinPool, name: String) :
        ForkJoinWorkerThread(forkJoinPool) {
        init {
            this.setName(name)
        }
    }

    /**
     * A factory for [NamedForkJoinWorkerThread]s that names those threads using a
     * client-provided name format that consumes a thread index.
     */
    private class NamedForkJoinWorkerThreadFactory(private val nameFormat: String) : ForkJoinWorkerThreadFactory {
        private val nextUnusedThreadIndex: AtomicLong = AtomicLong(0L)

        override fun newThread(forkJoinPool: ForkJoinPool): ForkJoinWorkerThread {
            return com.google.devtools.build.lib.concurrent.NamedForkJoinPool.NamedForkJoinWorkerThread(
                forkJoinPool, java.lang.String.format(nameFormat, nextUnusedThreadIndex.getAndIncrement())
            )
        }
    }

    companion object {
        /**
         * Creates a new NamedForkJoinPool.
         * 
         * @param name A string identifying the pool. This string must not contain any formatting
         * parameters.
         * @param numThreads The maximum number of threads to create, see [ForkJoinPool].
         */
        @kotlin.jvm.JvmStatic
        fun newNamedPool(name: String?, numThreads: Int): NamedForkJoinPool {
            return com.google.devtools.build.lib.concurrent.NamedForkJoinPool(name, numThreads)
        }
    }
}
