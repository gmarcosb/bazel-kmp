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

import com.google.devtools.build.lib.skyframe.serialization.AsyncSerializationTask
import com.google.devtools.build.lib.skyframe.serialization.FingerprintValueCache
import com.google.devtools.build.lib.skyframe.serialization.FingerprintValueStore
import com.google.devtools.build.lib.skyframe.serialization.FingerprintValueStore.InMemoryFingerprintValueStore
import com.google.devtools.build.lib.skyframe.serialization.Fingerprinter
import com.google.devtools.build.lib.skyframe.serialization.FrontierNodeVersion
import com.google.devtools.build.lib.skyframe.serialization.KeyBytesProvider
import com.google.devtools.build.lib.skyframe.serialization.KeyValueWriter
import com.google.devtools.build.lib.skyframe.serialization.ObjectCodecs
import com.google.devtools.build.lib.skyframe.serialization.PackedFingerprint
import com.google.devtools.build.lib.skyframe.serialization.PutOperation
import com.google.devtools.build.lib.skyframe.serialization.SerializationResult
import com.google.devtools.build.lib.skyframe.serialization.WriteStatuses.WriteStatus
import com.google.devtools.build.lib.util.DecimalBucketer
import com.google.devtools.build.skyframe.SkyKey
import com.google.protobuf.ByteString
import java.io.IOException
import java.time.Instant
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Bundles the components needed to store serialized values by fingerprint, the storage interface,
 * the cache and the hash function for computing fingerprints.
 */
class FingerprintValueService(
    executor: java.util.concurrent.Executor?,
    store: FingerprintValueStore,
    cache: FingerprintValueCache,
    fingerprinter: Fingerprinter
) : KeyValueWriter {
    private val executor: java.util.concurrent.Executor?
    private val store: FingerprintValueStore
    private val cache: FingerprintValueCache

    /**
     * The function used to generate fingerprints.
     * 
     * 
     * Used to derive [.fingerprintPlaceholder] and [.fingerprintLength].
     */
    private val fingerprinter: Fingerprinter

    private val fingerprintPlaceholder: PackedFingerprint
    private val fingerprintLength: Int

    private val getLatencyMicros: DecimalBucketer = DecimalBucketer()
    private val setLatencyMicros: DecimalBucketer = DecimalBucketer()

    init {
        this.executor = executor
        this.store = store
        this.cache = cache
        this.fingerprinter = fingerprinter

        this.fingerprintPlaceholder = fingerprint(byteArrayOf())
        this.fingerprintLength = fingerprintPlaceholder.toBytes().size
    }

    fun shutdown() {
        store.shutdown()
    }

    /** Delegates to [FingerprintValueStore.put].  */
    override fun put(fingerprint: KeyBytesProvider?, serializedBytes: ByteArray?): WriteStatus {
        val before: Instant = Instant.now()
        val putStatus: WriteStatus = store.put(fingerprint, serializedBytes)
        putStatus.addListener(
            java.lang.Runnable {
                setLatencyMicros.add(
                    TimeUnit.NANOSECONDS.toMicros(java.time.Duration.between(before, Instant.now()).toNanos())
                )
            },
            com.google.common.util.concurrent.MoreExecutors.directExecutor()
        )
        return putStatus
    }

    fun getStats(): com.google.devtools.build.lib.skyframe.serialization.FingerprintValueStore.Stats {
        val storeStats: com.google.devtools.build.lib.skyframe.serialization.FingerprintValueStore.Stats =
            store.getStats()
        return com.google.devtools.build.lib.skyframe.serialization.FingerprintValueStore.Stats(
            storeStats.valueBytesReceived,
            storeStats.valueBytesSent,
            storeStats.keyBytesSent,
            storeStats.entriesWritten,
            storeStats.entriesFound,
            storeStats.entriesNotFound,
            storeStats.getBatches,
            storeStats.setBatches,
            getLatencyMicros.getBuckets(),
            setLatencyMicros.getBuckets(),
            storeStats.getBatchLatencyMicros,
            storeStats.setBatchLatencyMicros
        )
    }

    /** Delegates to [FingerprintValueStore.get].  */
    @Throws(IOException::class)
    fun get(fingerprint: KeyBytesProvider?): com.google.common.util.concurrent.ListenableFuture<ByteArray?> {
        val before: Instant = Instant.now()
        val result: com.google.common.util.concurrent.ListenableFuture<ByteArray?> = store.get(fingerprint)
        result.addListener(
            java.lang.Runnable {
                getLatencyMicros.add(
                    TimeUnit.NANOSECONDS.toMicros(java.time.Duration.between(before, Instant.now()).toNanos())
                )
            },
            com.google.common.util.concurrent.MoreExecutors.directExecutor()
        )
        return result
    }

    /** Delegates to [FingerprintValueCache.getOrClaimPutOperation].  */
    fun getOrClaimPutOperation(
        obj: Any?, distinguisher: Any?, putOperation: com.google.common.util.concurrent.ListenableFuture<PutOperation?>?
    ): Any? {
        return cache.getOrClaimPutOperation(obj, distinguisher, putOperation)
    }

    /** Delegates to [FingerprintValueCache.getOrClaimGetOperation].  */
    fun getOrClaimGetOperation(
        fingerprint: PackedFingerprint?,
        distinguisher: Any?,
        getOperation: com.google.common.util.concurrent.ListenableFuture<Any?>?
    ): Any? {
        return cache.getOrClaimGetOperation(fingerprint, distinguisher, getOperation)
    }

    /** Computes the fingerprint of `bytes`.  */
    override fun fingerprint(bytes: ByteArray?): PackedFingerprint {
        return fingerprinter.fingerprint(bytes)
    }

    /** Convenience overload of [.fingerprint].  */
    @com.google.common.annotations.VisibleForTesting
    fun fingerprint(bytes: ByteString): PackedFingerprint {
        return fingerprint(bytes.toByteArray())
    }

    /**
     * A placeholder fingerprint to use when the actual fingerprint is not yet available.
     * 
     * 
     * The placeholder has the same length as the real fingerprint so the real fingerprint can
     * overwrite the placeholder when it becomes available.
     */
    fun fingerprintPlaceholder(): PackedFingerprint {
        return fingerprintPlaceholder
    }

    /** The fixed length of fingerprints.  */
    fun fingerprintLength(): Int {
        return fingerprintLength
    }

    /**
     * Executor for scheduling work related to serializing and deserializing values from the
     * fingerprint value store.
     * 
     * 
     * Technically, this should be plumbed separately but for the time being, [ ] is a convenient container for the [Executor].
     */
    fun getExecutor(): java.util.concurrent.Executor? {
        return executor
    }

    @com.google.common.annotations.VisibleForTesting
    fun getStoreForTesting(): FingerprintValueStore {
        return store
    }

    @com.google.common.annotations.VisibleForTesting
    fun getCachedFingerprintForTesting(`object`: Any?): PackedFingerprint? {
        return cache.getSerializationCache().getIfPresent(`object`) as PackedFingerprint?
    }

    @com.google.common.annotations.VisibleForTesting
    fun cacheCleanUpForTesting() {
        cache.cleanUpForTesting()
    }

    companion object {
        /** A [Fingerprinter] implementation for non-production use.  */
        @kotlin.jvm.JvmField
        val NONPROD_FINGERPRINTER: Fingerprinter = Fingerprinter { input: ByteArray? ->
            PackedFingerprint.Companion.fromBytes(
                com.google.common.hash.Hashing.murmur3_128().hashBytes(input).asBytes()
            )
        }

        @com.google.common.annotations.VisibleForTesting
        fun createForTesting(): FingerprintValueService {
            return createForTesting(
                FingerprintValueStore.Companion.inMemoryStore(),
                com.google.devtools.build.lib.skyframe.serialization.FingerprintValueCache.SyncMode.NOT_LINKED
            )
        }

        /**
         * Returns an instance that uses a [FingerprintValueStore] that indicates a missing entry by
         * returning null, which is what analysis caching expects.
         */
        @kotlin.jvm.JvmStatic
        @com.google.common.annotations.VisibleForTesting
        fun createForAnalysisCacheTesting(): FingerprintValueService {
            return Companion.createForTesting(InMemoryFingerprintValueStore(true))
        }

        @com.google.common.annotations.VisibleForTesting
        fun createForTesting(store: FingerprintValueStore): FingerprintValueService {
            return createForTesting(
                store,
                com.google.devtools.build.lib.skyframe.serialization.FingerprintValueCache.SyncMode.NOT_LINKED
            )
        }

        @com.google.common.annotations.VisibleForTesting
        fun createForTesting(mode: com.google.devtools.build.lib.skyframe.serialization.FingerprintValueCache.SyncMode?): FingerprintValueService {
            return createForTesting(FingerprintValueStore.Companion.inMemoryStore(), mode)
        }

        @kotlin.jvm.JvmStatic
        private fun createForTesting(
            store: FingerprintValueStore,
            mode: com.google.devtools.build.lib.skyframe.serialization.FingerprintValueCache.SyncMode?
        ): FingerprintValueService {
            return FingerprintValueService(
                Executors.newSingleThreadExecutor(), store, FingerprintValueCache(mode), NONPROD_FINGERPRINTER
            )
        }

        /**
         * Serializes a [SkyKey], concatenates it with the [FrontierNodeVersion], computes the
         * fingerprint, and returns the [PackedFingerprint].
         */
        @Throws(
            java.lang.InterruptedException::class,
            com.google.devtools.build.lib.skyframe.serialization.SerializationException::class
        )
        fun computeFingerprint(
            fingerprintValueService: FingerprintValueService,
            codecs: ObjectCodecs,
            key: SkyKey?,
            nodeVersion: FrontierNodeVersion
        ): PackedFingerprint? {
            val serializeKeyTask: AsyncSerializationTask =
                codecs.serializeMemoizedAsync(fingerprintValueService, key,  /* profileCollector= */null)
            serializeKeyTask.run()

            val fingerprintFuture: com.google.common.util.concurrent.ListenableFuture<PackedFingerprint?> =
                com.google.common.util.concurrent.Futures.transform<SerializationResult<ByteString?>?, PackedFingerprint?>(
                    serializeKeyTask,
                    com.google.common.base.Function { k: SerializationResult<ByteString?>? ->
                        fingerprintValueService.fingerprint(
                            nodeVersion.concat(k.getObject().toByteArray())
                        )
                    },  // Keys are hopefully small enough that it's reasonable to not spawn off a separate task
                    com.google.common.util.concurrent.MoreExecutors.directExecutor()
                )

            try {
                return fingerprintFuture.get()
            } catch (e: ExecutionException) {
                com.google.common.base.Throwables.throwIfInstanceOf<com.google.devtools.build.lib.skyframe.serialization.SerializationException?>(
                    e.getCause(),
                    com.google.devtools.build.lib.skyframe.serialization.SerializationException::class.java
                )
                throw java.lang.IllegalStateException(e)
            }
        }
    }
}
