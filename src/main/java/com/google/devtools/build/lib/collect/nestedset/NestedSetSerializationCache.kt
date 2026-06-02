// Copyright 2021 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.collect.nestedset

import com.github.benmanes.caffeine.cache.Caffeine
import com.google.devtools.build.lib.bugreport.BugReporter
import com.google.devtools.build.lib.skyframe.serialization.PackedFingerprint
import com.google.devtools.build.lib.skyframe.serialization.PutOperation
import com.google.devtools.build.lib.skyframe.serialization.SerializationConstants
import com.google.devtools.build.lib.skyframe.serialization.WriteStatuses

/**
 * A bidirectional, in-memory, weak cache for fingerprint ⇔ [NestedSet] associations.
 * 
 * 
 * For use by [NestedSetStore] to minimize work during [NestedSet] (de)serialization.
 * 
 * 
 * The cache supports the possibility of semantically different arrays having the same serialized
 * representation. For this reason, a context object is included in the key for the fingerprint ⇒
 * array mapping. This object should encapsulate all additional context necessary to deserialize a
 * [NestedSet] element. The array ⇒ fingerprint mapping, on the other hand, is expected to be
 * deterministic.
 */
internal class NestedSetSerializationCache(bugReporter: BugReporter) {
    /**
     * Fingerprint to array cache.
     * 
     * 
     * The values in this cache are always `Object[]` or `ListenableFuture<Object[]>`.
     * We avoid a common wrapper object both for memory efficiency and because our cache eviction
     * policy is based on value GC, and wrapper objects would defeat that.
     * 
     * 
     * While a fetch for the contents is outstanding, the value in the cache will be a [ ]. When it is resolved, it is replaced with the unwrapped `Object[]`.
     * This is done because if the array is a transitive member, its future may be GC'd, and we want
     * entries to stay in this cache while the contents are still live.
     */
    private val fingerprintToContents: com.github.benmanes.caffeine.cache.Cache<FingerprintKey?, Any?> =
        Caffeine.newBuilder()
            .initialCapacity(SerializationConstants.DESERIALIZATION_POOL_SIZE)
            .weakValues()
            .build<FingerprintKey?, Any?>()

    /** `Object[]` contents to fingerprint. Maintained for fast fingerprinting.  */
    private val contentsToFingerprint: com.github.benmanes.caffeine.cache.Cache<Array<Any?>?, PutOperation?> =
        Caffeine.newBuilder()
            .initialCapacity(SerializationConstants.DESERIALIZATION_POOL_SIZE)
            .weakKeys()
            .build<Array<Any?>?, PutOperation?>()

    private val bugReporter: BugReporter

    init {
        this.bugReporter = bugReporter
    }

    /**
     * Returns contents (an `Object[]` or a `ListenableFuture<Object[]>`) for the [ ] associated with the given fingerprint if there was already one. Otherwise associates
     * `future` with `fingerprint` and returns `null`.
     * 
     * 
     * Upon a `null` return, the caller should ensure that the given future is eventually set
     * with the fetched contents.
     * 
     * 
     * Upon a non-`null` return, the caller should discard the given future in favor of the
     * returned contents, blocking for them if the return value is itself a future.
     * 
     * @param fingerprint the fingerprint of the desired [NestedSet] contents
     * @param context the context needed to deterministically deserialize the contents associated with
     * `fingerprint`
     * @param future a freshly created [SettableFuture]
     */
    fun putFutureIfAbsent(
        fingerprint: PackedFingerprint?,
        future: com.google.common.util.concurrent.SettableFuture<Array<Any?>?>,
        context: Any?
    ): Any? {
        com.google.common.base.Preconditions.checkArgument(!future.isDone(), "Must pass a fresh future: %s", future)
        val existing: Any? =
            fingerprintToContents.asMap().putIfAbsent(FingerprintKey(fingerprint, context), future)
        if (existing != null) {
            return existing
        }
        // This is the first request of this fingerprint.
        unwrapWhenDone(fingerprint, future, context)
        return null
    }

    /**
     * Registers a [FutureCallback] that associates the provided fingerprint and the contents of
     * the future, when it completes.
     */
    private fun unwrapWhenDone(
        fingerprint: PackedFingerprint?,
        futureContents: com.google.common.util.concurrent.ListenableFuture<Array<Any?>?>,
        context: Any?
    ) {
        com.google.common.util.concurrent.Futures.addCallback<Array<Any?>?>(
            futureContents,
            object : com.google.common.util.concurrent.FutureCallback<Array<Any?>?> {
                override fun onSuccess(contents: Array<Any?>?) {
                    // Store a PutOperation so that we can skip fingerprinting this array and writing it to
                    // storage (it's already there - we just fetched it). Also replace the cached future
                    // with the unwrapped contents, since the future may be GC'd. If there was a call to
                    // putIfAbsent with this fingerprint while the future was pending, we may overwrite a
                    // fingerprint ⇒ array mapping, but this is fine since both arrays have the same
                    // contents. In this case, it would be nice to also complete the other array's write
                    // future, but the semantics of SettableFuture makes this difficult (set after setFuture
                    // has no effect).
                    val unused: PutOperation? =
                        putIfAbsent(
                            contents, PutOperation(fingerprint, WriteStatuses.immediateWriteStatus()), context
                        )
                }

                override fun onFailure(t: Throwable) {
                    // Failure to fetch the NestedSet contents is unexpected, but the failed future can be
                    // stored as the NestedSet children. This way the exception is only propagated if the
                    // NestedSet is consumed (unrolled).
                    bugReporter.sendNonFatalBugReport(t)
                }
            },
            com.google.common.util.concurrent.MoreExecutors.directExecutor()
        )
    }

    /**
     * Retrieves the fingerprint associated with the given [NestedSet] contents, or `null`
     * if the given contents are not known.
     */
    fun fingerprintForContents(contents: Array<Any?>?): PutOperation? {
        return contentsToFingerprint.getIfPresent(contents)
    }

    /**
     * Ensures that a fingerprint ⟺ contents association is cached in both directions.
     * 
     * 
     * If the given fingerprint and array are already *fully* cached, returns the existing
     * [PutOperation]. Otherwise returns `null`.
     * 
     * 
     * If the given fingerprint is only *partially* cached (meaning that [ ][.putFutureIfAbsent] has been called but the associated future has not yet completed), then the
     * cached future is overwritten in favor of the actual contents.
     */
    fun putIfAbsent(contents: Array<Any?>?, result: PutOperation, context: Any?): PutOperation? {
        val existingResult: PutOperation? = contentsToFingerprint.asMap().putIfAbsent(contents, result)
        if (existingResult != null) {
            return existingResult
        }
        fingerprintToContents.put(FingerprintKey(result.fingerprint, context), contents)
        return null
    }

    internal class FingerprintKey(fingerprint: PackedFingerprint?, context: Any?) {
        val fingerprint: PackedFingerprint?
        val context: Any?

        init {
            this.fingerprint = fingerprint
            this.context = context
        }
    }
}
