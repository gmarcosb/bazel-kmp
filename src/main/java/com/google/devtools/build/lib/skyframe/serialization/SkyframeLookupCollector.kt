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

import com.google.devtools.build.lib.concurrent.QuiescingFuture

/**
 * Tracks state pertaining to Skyframe lookups from deserialization.
 * 
 * 
 * The future completes once it can be determined that all Skyframe lookups are known.
 * 
 * 
 * This is shared across [SharedValueDeserializationContext] and transitive inner contexts
 * created by [SharedValueDeserializationContext.readValueForFingerprint].
 */
internal class SkyframeLookupCollector :
    QuiescingFuture<ArrayDeque<com.google.devtools.build.lib.skyframe.serialization.SharedValueDeserializationContext.SkyframeLookup<*>?>?>(
        com.google.common.util.concurrent.MoreExecutors.directExecutor()
    ) {
    /** Skyframe lookups required for deserialization.  */
    private val skyframeLookups: ArrayDeque<com.google.devtools.build.lib.skyframe.serialization.SharedValueDeserializationContext.SkyframeLookup<*>> =
        ArrayDeque<com.google.devtools.build.lib.skyframe.serialization.SharedValueDeserializationContext.SkyframeLookup<*>>()

    @javax.annotation.concurrent.GuardedBy("this")
    private var cause: PeerFailedException? = null

    /**
     * A notification that balances the pre-increment of [QuiescingFuture].
     * 
     * 
     * The client must call this once. Must not be called before all initial calls to [ ][SharedValueDeserializationContext.readValueForFingerprint] occur.
     * 
     * 
     * [SharedValueDeserializationContext.getSharedValue] calls may recursively trigger more
     * fetches asynchronously which is fine as long as the parent child notification ordering
     * described in [QuiescingFuture] is followed.
     */
    fun notifyFetchesInitialized() {
        decrement()
    }

    fun notifyFetchStarting() {
        increment()
    }

    fun notifyFetchDone() {
        decrement()
    }

    fun notifyFetchException(t: Throwable?) {
        synchronized(this) {
            if (cause == null) {
                // If this is the first failure, captures it and abandons any previously collected lookups.
                cause = PeerFailedException(t)
                for (lookup in skyframeLookups) {
                    lookup.abandon(cause)
                }
                skyframeLookups.clear()
            }
        }
        // The future fails fast here. Any lookups that are added after the failure are immediately
        // abandoned.
        notifyException(t)
    }

    protected override fun getValue(): ArrayDeque<com.google.devtools.build.lib.skyframe.serialization.SharedValueDeserializationContext.SkyframeLookup<*>> {
        return skyframeLookups
    }

    @kotlin.jvm.Synchronized
    fun addLookup(lookup: com.google.devtools.build.lib.skyframe.serialization.SharedValueDeserializationContext.SkyframeLookup<*>) {
        if (cause != null) {
            lookup.abandon(cause) // Abandons any lookups added after the first error.
            return
        }
        skyframeLookups.addLast(lookup)
    }
}
