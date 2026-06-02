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

import com.github.benmanes.caffeine.cache.Caffeine
import com.google.devtools.build.lib.skyframe.serialization.FingerprintValueCache
import com.google.devtools.build.lib.skyframe.serialization.FutureHelpers.FutureStatusCallback
import com.google.devtools.build.lib.skyframe.serialization.PackedFingerprint
import com.google.devtools.build.lib.skyframe.serialization.PutOperation
import com.google.devtools.build.lib.skyframe.serialization.SerializationConstants
import com.google.devtools.build.lib.skyframe.serialization.SharedValueDeserializationContext.MissingSharedValueBytesException

/**
 * A bidirectional, in-memory, weak cache storing fingerprint ↔ value associations for the [ ].
 * 
 * 
 * The cache supports the possibility of semantically different values having the same serialized
 * representation. For this reason, a distinguisher object can be included in the key for the
 * fingerprint ⇒ value mapping. This object should encapsulate all additional context necessary to
 * deserialize a value. The value ⇒ fingerprint mapping, on the other hand, is expected to be
 * deterministic. See [FingerprintWithDistinguisher].
 */
class FingerprintValueCache @kotlin.jvm.JvmOverloads constructor(private val mode: SyncMode = com.google.devtools.build.lib.skyframe.serialization.FingerprintValueCache.SyncMode.LINKED) {
    /**
     * Fingerprint to value cache.
     * 
     * 
     * Used to deduplicate fetches, or in some cases, where the object to be fetched was already
     * serialized, retrieves the already existing object.
     * 
     * 
     * The keys can either be a [PackedFingerprint] or a [ ].
     * 
     * 
     * The values in this cache are always `Object` or `ListenableFuture<Object>`. We
     * avoid a common wrapper object both for memory efficiency and because our cache eviction policy
     * is based on value GC, and wrapper objects would defeat that.
     * 
     * 
     * While a fetch for the contents is outstanding, the value in the cache will be a [ ]. When it is resolved, it is replaced with the unwrapped `Object`.
     */
    private val deserializationCache: com.github.benmanes.caffeine.cache.Cache<Any?, Any?> = Caffeine.newBuilder()
        .initialCapacity(SerializationConstants.DESERIALIZATION_POOL_SIZE)
        .weakValues()
        .build<Any?, Any?>()

    /**
     * [Object] contents to store result mapping, eventually a fingerprint, but a future while
     * in-flight or in case of errors.
     * 
     * 
     * This cache deduplicates serializing the same contents to the [FingerprintValueStore].
     * Its entries are as follows.
     * 
     * 
     *  * key: the content value object, using reference equality
     *  * value: either a `ListenableFuture<PutOperation>` when the operation is in flight or
     * a [PackedFingerprint] fingerprint when it is complete
     * 
     * 
     * 
     * `ListenableFuture<PutOperation>` contains two distinct asynchronous operations.
     * 
     * 
     *  * *Outer `ListenableFuture`*: represents the asynchronous completion of
     * serialization, fingerprinting and the initialization of the [       ][FingerprintValueStore.put] operation.
     *  * *[PutOperation.writeStatus]*: represents the completion of the [       ][FingerprintValueStore.put] operation.
     * 
     */
    private val serializationCache: com.github.benmanes.caffeine.cache.Cache<Any?, Any?> = Caffeine.newBuilder()
        .initialCapacity(SerializationConstants.DESERIALIZATION_POOL_SIZE)
        .weakKeys()
        .build<Any?, Any?>()

    /** Determines synchronization behavior of the bidirectional cache.  */
    enum class SyncMode {
        /**
         * Keeps the two caches [.serializationCache] and [.deserializationCache]
         * synchronized in a best-effort manner.
         * 
         * 
         * When a cache operation completes asynchronously, it updates the cache entry's value from a
         * future pointing to the result to the result itself. It also updates the reverse mapping at
         * the same time. This may save work when the client is simultaneously a cache reader and
         * writer.
         */
        LINKED,

        /**
         * The two caches are not synchronized.
         * 
         * 
         * This saves memory when the client is exclusively a reader or writer.
         * 
         * 
         * It is also useful in testing round-tripping behavior when populating the reverse mapping
         * would cause a cache hit that reduces test coverage. That is, when linked, serialization
         * followed by deserialization would result in a cache hit that skips actual deserialization
         * work.
         */
        NOT_LINKED,
    }

    @com.google.common.annotations.VisibleForTesting
    fun getSerializationCache(): com.github.benmanes.caffeine.cache.Cache<Any?, Any?> {
        return serializationCache
    }

    /**
     * Gets the result of a previous `putOperation` or registers a new one for `obj`, the
     * [.serializationCache] key.
     * 
     * 
     * If the `obj` has already been serialized or if its serialization is in-flight, returns
     * a non-null object that may be either:
     * 
     * 
     *  * a `ListenableFuture<PutOperation>` if it is still in flight; or
     *  * a [PackedFingerprint] fingerprint if writing to remote storage is successful.
     * 
     * 
     * 
     * If a `ListenableFuture<PutOperation>` is returned, its expected [ ] causes are [SerializationException] and [IOException]. The
     * caller must ensure that these are the only possible causes.
     * 
     * 
     * If a previous operation is returned, `putOperation` is ignored. Otherwise, if a null
     * value is returned, the caller owns the `putOperation` and must ensure it completes and
     * handle its errors.
     * 
     * @param distinguisher an optional key distinguisher, see [FingerprintWithDistinguisher]
     */
    fun getOrClaimPutOperation(
        obj: Any?, distinguisher: Any?, putOperation: com.google.common.util.concurrent.ListenableFuture<PutOperation?>
    ): Any? {
        // Any contention here is caused by two threads racing to serialize the same object. Since
        // the serialization is pure CPU work, it's tempting to simplify this code by using
        // `computeIfAbsent` instead. That unfortunately leads to recursive `ConcurrentMap` updates,
        // which isn't supported.
        val previous: Any? = serializationCache.asMap().putIfAbsent(obj, putOperation)
        if (previous != null) {
            return previous
        }
        unwrapFingerprintWhenDone(obj, distinguisher, putOperation)
        return null
    }

    /**
     * Gets the result of a previous `getOperation` or registers a new one for `fingerprint`, the [.deserializationCache] key.
     * 
     * 
     * This is used to avoid deduplicate fetches or fetching an object that had already been
     * serialized from this cache. If the key is for an already stored or retrieved object, or one
     * where retrievial is in-flight, returns a non-null value that can be one of the following.
     * 
     * 
     *  * a `ListenableFuture<Object>` if retrieval is in-flight; or
     *  * an [Object] if it is already known for the key.
     * 
     * 
     * 
     * If a `ListenableFuture<Object>` is returned, its possible [ExecutionException]
     * causes are [SerializationException], [IOException] and [ ]. The caller must ensure these are the only possible causes.
     * 
     * 
     * If a non-null value is returned, `getOperation` is ignored. Otherwise, when this
     * returns null, the caller must ensure that `getOperation` is eventually completed with the
     * or an error. The caller is responsible for handling errors.
     * 
     * @param distinguisher an optional distinguisher, see [FingerprintWithDistinguisher]
     */
    fun getOrClaimGetOperation(
        fingerprint: PackedFingerprint?,
        distinguisher: Any?,
        getOperation: com.google.common.util.concurrent.ListenableFuture<Any?>
    ): Any? {
        val key = createKey(fingerprint, distinguisher)
        val previous: Any? = deserializationCache.asMap().putIfAbsent(key, getOperation)
        if (previous != null) {
            return previous
        }
        unwrapValueWhenDone(fingerprint, key, getOperation)
        return null
    }

    /** Populates the reverse mapping and unwraps futures when they are no longer needed.  */
    private fun unwrapFingerprintWhenDone(
        obj: Any?, distinguisher: Any?, putOperation: com.google.common.util.concurrent.ListenableFuture<PutOperation?>
    ) {
        com.google.common.util.concurrent.Futures.addCallback<PutOperation?>(
            putOperation,
            object : com.google.common.util.concurrent.FutureCallback<PutOperation?> {
                override fun onSuccess(operation: PutOperation) {
                    // Serialization and fingerprinting has succeeded and storing the bytes in the
                    // FingerprintValueStore has started.

                    if (mode == com.google.devtools.build.lib.skyframe.serialization.FingerprintValueCache.SyncMode.LINKED) {
                        // Stores the reverse mapping in `deserializationCache`.
                        deserializationCache.put(createKey(operation.fingerprint, distinguisher), obj)
                    }

                    // It's possible to discard the outermost future at this point and only keep the
                    // PutOperation instead, but for simplicity, discards both once both succeed.
                    com.google.common.util.concurrent.Futures.addCallback<Boolean?>(
                        operation.writeStatus,
                        object : FutureStatusCallback() {
                            override fun onSuccess() {
                                // The object has been successfully written to remote storage. Discards all the
                                // wrappers.
                                serializationCache.put(obj, operation.fingerprint)
                            }

                            override fun onFailure(t: Throwable) {
                                // Failure will be reported by the owner of `putOperation`.
                            }
                        },
                        com.google.common.util.concurrent.MoreExecutors.directExecutor()
                    )
                }

                override fun onFailure(t: Throwable) {
                    // Failure will be reported by the owner of `putOperation`.
                }
            },
            com.google.common.util.concurrent.MoreExecutors.directExecutor()
        )
    }

    /** Unwraps the future and populates the reverse mapping when done.  */
    private fun unwrapValueWhenDone(
        fingerprint: PackedFingerprint?,
        key: Any?,
        getOperation: com.google.common.util.concurrent.ListenableFuture<Any?>
    ) {
        com.google.common.util.concurrent.Futures.addCallback<Any?>(
            getOperation,
            object : com.google.common.util.concurrent.FutureCallback<Any?> {
                override fun onSuccess(value: Any?) {
                    deserializationCache.put(key, value)
                    if (mode == com.google.devtools.build.lib.skyframe.serialization.FingerprintValueCache.SyncMode.LINKED) {
                        // Stores the reverse mapping in `serializationCache`.
                        serializationCache.put(value, fingerprint)
                    }
                }

                override fun onFailure(t: Throwable) {
                    // Failure will be reported by the owner of the `getOperation`.

                    // If a value fails to deserialize because it's missing bytes for a
                    // shared value, it will consistently fail for any subsequent
                    // lookups, so cache the failure.

                    if (t is MissingSharedValueBytesException) {
                        deserializationCache.put(key, MISSING_SHARED_VALUE_BYTES_FUTURE)
                    }

                    // TODO: b/417445528 - It might make sense to delete the failed deserialization future
                    // here, especially if it comes from an abandoned SkyframeLookup. However, since the
                    // deserializationCache is weak-valued, it should become eligible for cleanup quickly,
                    // as nothing else should be adding retained references to these futures.
                }
            },
            com.google.common.util.concurrent.MoreExecutors.directExecutor()
        )
    }

    /**
     * An extended [.deserializationCache] key, needed when the fingerprint alone is not enough.
     * 
     * 
     * The mapping stores a bidirectional fingerprint to value associations. However, there can be
     * multiple values for the same fingerprint. For example, consider the parent and child objects
     * (A, B). Suppose that both A and B share a common value S. When serializing B, S may be omitted
     * because it is already known to A and can be reinjected during deserialization.
     * 
     * 
     * The problem is that the fingerprint of B does not include anything about the shared value S.
     * So it could collide on fingerprint with some other (C, D) with a different shared value T.
     * 
     * 
     * Including a *distinguisher* in the key to account for the contextual value can be
     * used to avoid conflicts in cases like this.
     * 
     * @param fingerprint The primary key for a [.deserializationCache] entry.
     * @param distinguisher A secondary key, sometimes needed to resolve ambiguity.
     */
    internal class FingerprintWithDistinguisher(fingerprint: PackedFingerprint?, val distinguisher: Any?) {
        val fingerprint: PackedFingerprint?

        init {
            this.fingerprint = fingerprint
            java.util.Objects.requireNonNull<PackedFingerprint?>(fingerprint, "fingerprint")
            java.util.Objects.requireNonNull<Any?>(distinguisher, "distinguisher")
        }

        companion object {
            fun of(fingerprint: PackedFingerprint?, distinguisher: Any?): FingerprintWithDistinguisher {
                return FingerprintWithDistinguisher(fingerprint, distinguisher)
            }
        }
    }

    /** Forces Caffeine's internal maintenance for testing.  */
    @com.google.common.annotations.VisibleForTesting
    fun cleanUpForTesting() {
        serializationCache.cleanUp()
        deserializationCache.cleanUp()
    }

    companion object {
        private val MISSING_SHARED_VALUE_BYTES_FUTURE: com.google.common.util.concurrent.ListenableFuture<Any?> =
            com.google.common.util.concurrent.Futures.immediateFailedFuture<Any?>(MissingSharedValueBytesException.Companion.INSTANCE)

        private fun createKey(fingerprint: PackedFingerprint?, distinguisher: Any?): Any? {
            if (distinguisher == null) {
                return fingerprint
            }
            return FingerprintWithDistinguisher.Companion.of(fingerprint, distinguisher)
        }
    }
}
