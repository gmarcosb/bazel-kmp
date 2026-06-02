// Copyright 2017 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.exec

import com.google.devtools.build.lib.actions.ActionContext

/**
 * A cache that can lookup a [SpawnResult] given a [Spawn], and can also upload the
 * results of an executed spawn to the cache.
 * 
 * 
 * This is an experimental interface to implement caching with sandboxed local execution.
 */
interface SpawnCache : ActionContext {
    /** A no-op spawn cache.  */
    class NoSpawnCache private constructor() : SpawnCache {
        override fun lookup(spawn: Spawn?, context: SpawnExecutionContext?): CacheHandle {
            return NO_RESULT_NO_STORE
        }
    }

    /**
     * This object represents both a successful and an unsuccessful cache lookup.
     * 
     * 
     * If [.hasResult] returns true, then [.getResult] returns a non-null instance.
     * Otherwise, if [.hasResult] returns false, then [.getResult] throws an [ ].
     * 
     * 
     * If [.willStore] returns true, then [.store] may be called to upload the result
     * to the cache after successful execution. Otherwise, if [.willStore] returns false, then
     * [.store] throws an [IllegalStateException].
     */
    interface CacheHandle : com.google.devtools.build.lib.profiler.SilentCloseable {
        /** Returns whether the cache lookup was successful.  */
        fun hasResult(): Boolean

        /**
         * Returns the cached result.
         * 
         * @throws NoSuchElementException if there is no result in this cache entry
         */
        @kotlin.jvm.JvmField
        val result: SpawnResult?

        /**
         * Returns true if the store call will actually do work. Use this to avoid unnecessary work
         * before store if it won't do anything.
         */
        fun willStore(): Boolean

        /**
         * Called after successful [Spawn] execution, which may or may not store the result in the
         * cache.
         * 
         * 
         * A cache may silently return from a failed store operation. We recommend to err on the side
         * of raising an exception rather than returning silently, and to offer command-line flags to
         * tweak this default policy as needed.
         * 
         * 
         * If the current thread is interrupted, then this method should return as quickly as
         * possible with an [InterruptedException].
         */
        @Throws(ExecException::class, java.lang.InterruptedException::class, IOException::class)
        fun store(result: SpawnResult?)
    }

    /**
     * Perform a spawn lookup. This method is similar to [SpawnRunner.exec], taking the same
     * parameters and being allowed to throw the same exceptions. The intent for this method is to
     * compute a cache lookup key for the given spawn, looking it up in an implementation-dependent
     * cache (can be either on the local or remote machine), and returning a non-null [ ] instance.
     * 
     * 
     * If the lookup was successful, this method should write the cached outputs to their
     * corresponding output locations in the output tree, as well as stdout and stderr, after
     * notifying [SpawnExecutionContext.lockOutputFiles].
     * 
     * 
     * If the lookup was unsuccessful, this method can return a [CacheHandle] instance that
     * has no result, but uploads the results of the execution to the cache. The reason for a callback
     * object is for the cache to store expensive intermediate values (such as the cache key) that are
     * needed both for the lookup and the subsequent store operation.
     * 
     * 
     * The lookup must not succeed for non-cachable spawns. See [Spawns.mayBeCached] and
     * [Spawns.mayBeCachedRemotely].
     * 
     * 
     * Note that cache stores may be disabled, in which case the returned [CacheHandle]
     * instance's [CacheHandle.store] is a no-op.
     */
    @Throws(ExecException::class, IOException::class, java.lang.InterruptedException::class)
    fun lookup(spawn: Spawn?, context: SpawnExecutionContext?): CacheHandle?

    /**
     * Returns whether this cache implementation makes sense to use together with dynamic execution.
     * 
     * 
     * A cache that's part of the remote system used for dynamic execution should not also be used
     * for the local speculative execution. However, a local cache or a separate remote cache-only
     * system would be.
     */
    fun usefulInDynamicExecution(): Boolean {
        return true
    }

    companion object {
        /** A no-op implementation that has no result, and performs no upload.  */
        @kotlin.jvm.JvmField
        val NO_RESULT_NO_STORE: CacheHandle = object : CacheHandle {
            override fun hasResult(): Boolean {
                return false
            }

            override fun getResult(): SpawnResult? {
                throw java.util.NoSuchElementException()
            }

            override fun willStore(): Boolean {
                return false
            }

            @Throws(java.lang.InterruptedException::class, IOException::class)
            override fun store(result: SpawnResult?) {
                // Do nothing.
            }

            override fun close() {}
        }

        /**
         * Helper method to create a [CacheHandle] from a successful [SpawnResult] instance.
         */
        fun success(result: SpawnResult): CacheHandle {
            return object : CacheHandle {
                override fun hasResult(): Boolean {
                    return true
                }

                override fun getResult(): SpawnResult {
                    return result
                }

                override fun willStore(): Boolean {
                    return false
                }

                @Throws(java.lang.InterruptedException::class, IOException::class)
                override fun store(result: SpawnResult?) {
                    throw java.lang.IllegalStateException()
                }

                override fun close() {}
            }
        }

        /** A no-op implementation that has no results and performs no stores.  */
        @kotlin.jvm.JvmField
        val NO_CACHE: SpawnCache = NoSpawnCache()
    }
}
