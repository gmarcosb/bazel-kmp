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
package com.google.devtools.build.lib.skyframe.serialization

import com.google.devtools.build.lib.skyframe.serialization.SharedValueDeserializationContext
import com.google.devtools.build.lib.skyframe.serialization.SharedValueDeserializationContext.LookupAbandonedException
import com.google.devtools.build.lib.skyframe.serialization.SharedValueDeserializationContext.PeerFailedException
import com.google.devtools.build.lib.skyframe.serialization.SkyframeDependencyException
import com.google.devtools.build.lib.skyframe.serialization.SkyframeLookupContinuation
import com.google.devtools.build.skyframe.SkyFunction.LookupEnvironment
import com.google.devtools.build.skyframe.SkyKey
import com.google.devtools.build.skyframe.SkyframeLookupResult
import java.util.ArrayDeque
import java.util.concurrent.ExecutionException

/**
 * A partial deserialization result that may require one or more Skyframe lookups to complete.
 * 
 * 
 * This class is designed to reside in [SkyKeyComputeState]. In particular, note that
 * [.abandon] should be called.
 */
class SkyframeLookupContinuation internal constructor(
    skyframeLookups: ArrayDeque<com.google.devtools.build.lib.skyframe.serialization.SharedValueDeserializationContext.SkyframeLookup<*>>,
    result: com.google.common.util.concurrent.ListenableFuture<*>?
) {
    private val skyframeLookups: ArrayDeque<com.google.devtools.build.lib.skyframe.serialization.SharedValueDeserializationContext.SkyframeLookup<*>>
    private val result: com.google.common.util.concurrent.ListenableFuture<*>?

    private var state: State =
        com.google.devtools.build.lib.skyframe.serialization.SkyframeLookupContinuation.State.LOOKUP

    init {
        this.skyframeLookups = skyframeLookups
        this.result = result
    }

    private enum class State {
        /** Start state that performs initial Skyframe lookups for any keys.  */
        LOOKUP,

        /**
         * State that is ready to resume from restart.
         * 
         * 
         * If this state is reached, all values should be present in Skyframe.
         */
        RESUME,

        /**
         * Marker indicating completion.
         * 
         * 
         * It's an error to call [.process] from this state.
         */
        ENDED
    }

    /**
     * Performs the next deserialization processing step.
     * 
     * 
     * Clients may need to call this twice, with the 2nd call being after a Skyframe restart.
     * 
     * @return a future containing the deserialization result (which could be pending deserialization
     * occurring in other threads) or null if a Skyframe restart is needed
     */
    @Throws(java.lang.InterruptedException::class, SkyframeDependencyException::class)
    fun process(env: LookupEnvironment): com.google.common.util.concurrent.ListenableFuture<*>? {
        return when (state) {
            com.google.devtools.build.lib.skyframe.serialization.SkyframeLookupContinuation.State.LOOKUP -> doLookup(env)
            com.google.devtools.build.lib.skyframe.serialization.SkyframeLookupContinuation.State.RESUME -> resume(env)
            com.google.devtools.build.lib.skyframe.serialization.SkyframeLookupContinuation.State.ENDED -> throw java.lang.IllegalStateException(
                "already ended: " + result
            )
        }
    }

    /**
     * Performs state cleanup.
     * 
     * 
     * This must be called if the lookups cannot be completed, for example, if [ ][SkyKeyComputeState.close] is called on any containing compute state or if there's an error.
     */
    fun abandon(exception: LookupAbandonedException?) {
        for (lookup in skyframeLookups) {
            lookup.abandon(exception)
        }
        skyframeLookups.clear()
    }

    @com.google.common.annotations.VisibleForTesting
    fun getSkyframeLookupsForTesting(): ArrayDeque<com.google.devtools.build.lib.skyframe.serialization.SharedValueDeserializationContext.SkyframeLookup<*>> {
        return skyframeLookups
    }

    /**
     * Performs any needed Skyframe lookups.
     * 
     * @return a future containing the deserialization result or null if a Skyframe restart is needed
     */
    @Throws(java.lang.InterruptedException::class, SkyframeDependencyException::class)
    private fun doLookup(env: LookupEnvironment): com.google.common.util.concurrent.ListenableFuture<*>? {
        if (skyframeLookups.isEmpty()) {
            this.state = com.google.devtools.build.lib.skyframe.serialization.SkyframeLookupContinuation.State.ENDED
            return result
        }

        // TODO: b/335901349 - consider implementing an optimized codepath for unary lookups.
        val lookupResult: SkyframeLookupResult
        try {
            // This is the only method that can throw InterruptedException.
            lookupResult =
                env.getValuesAndExceptions(
                    com.google.common.collect.Iterables.transform<com.google.devtools.build.lib.skyframe.serialization.SharedValueDeserializationContext.SkyframeLookup<*>?, SkyKey?>(
                        skyframeLookups,
                        com.google.common.base.Function { obj: com.google.devtools.build.lib.skyframe.serialization.SharedValueDeserializationContext.SkyframeLookup<*>? -> obj.getKey() })
                )
        } catch (e: java.lang.InterruptedException) {
            abandon(LookupAbandonedException(e))
            java.lang.Thread.currentThread().interrupt() // Restores the interrupted status.
            throw e
        }
        val lookupCount: Int = skyframeLookups.size()
        /* !!! Hit visitElement for element type: class org.jetbrains.kotlin.nj2k.tree.JKJavaForLoopStatement !!! */
        if (skyframeLookups.isEmpty()) { // all lookups succeeded
            this.state = com.google.devtools.build.lib.skyframe.serialization.SkyframeLookupContinuation.State.ENDED
            return result
        }
        this.state = com.google.devtools.build.lib.skyframe.serialization.SkyframeLookupContinuation.State.RESUME
        return null // Skyframe restart needed
    }

    /**
     * Resumes deserialization after a Skyframe restart by consuming pending values.
     * 
     * @return a future containing the deserialization result
     */
    @Throws(SkyframeDependencyException::class)
    private fun resume(env: LookupEnvironment): com.google.common.util.concurrent.ListenableFuture<*>? {
        // There was a Skyframe restart. Everything that was requested should be available now. This
        // method should not be reachable by error bubbling because it can only be reached by
        // pre-existing SkyKeyComputeState, which is evicted before error bubbling.

        val lookupResult: SkyframeLookupResult = env.getLookupHandleForPreviouslyRequestedDeps()
        for (lookup in skyframeLookups) {
            val key: SkyKey? = lookup.getKey()
            com.google.common.base.Preconditions.checkState(
                lookupResult.queryDep(key, lookup),
                "previously requested key %s missing from Skyframe after restart",
                key
            )
            throwDependencyExceptionIfFailed(lookup)
        }
        skyframeLookups.clear()
        this.state = com.google.devtools.build.lib.skyframe.serialization.SkyframeLookupContinuation.State.ENDED
        return result
    }

    @Throws(SkyframeDependencyException::class)
    private fun throwDependencyExceptionIfFailed(lookup: com.google.devtools.build.lib.skyframe.serialization.SharedValueDeserializationContext.SkyframeLookup<*>) {
        if (!lookup.isFailed()) {
            return
        }
        this.state = com.google.devtools.build.lib.skyframe.serialization.SkyframeLookupContinuation.State.ENDED
        try {
            val unused: java.lang.Void? = com.google.common.util.concurrent.Futures.getDone<java.lang.Void?>(lookup)
        } catch (e: ExecutionException) {
            // In general, SkyframeLookups can contain either SkyframeDependencyExceptions or
            // LookupAbandonedExceptions. This is only reachable before any LookupAbandonedExceptions can
            // be propagated.
            val cause: SkyframeDependencyException = e.getCause() as SkyframeDependencyException
            abandon(PeerFailedException(cause))
            throw cause
        }
        throw java.lang.IllegalStateException("should have thrown an exception: " + lookup)
    }
}
