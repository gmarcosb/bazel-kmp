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

import com.google.devtools.build.lib.skyframe.serialization.FingerprintValueStore
import com.google.devtools.build.lib.skyframe.serialization.KeyBytesProvider
import com.google.devtools.build.lib.skyframe.serialization.WriteStatuses
import com.google.devtools.build.lib.skyframe.serialization.WriteStatuses.WriteStatus
import com.google.protobuf.ByteString
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentMap

/** Encapsulates fingerprint keyed bytes storage system.  */
interface FingerprintValueStore {
    /** Usage statistics.  */
    class Stats(
        val valueBytesReceived: Long,
        val valueBytesSent: Long,
        val keyBytesSent: Long,
        val entriesWritten: Long,
        val entriesFound: Long,
        val entriesNotFound: Long,
        val getBatches: Long,
        val setBatches: Long,
        getLatencyMicros: com.google.common.collect.ImmutableList<com.google.devtools.build.lib.util.Bucket?>?,
        setLatencyMicros: com.google.common.collect.ImmutableList<com.google.devtools.build.lib.util.Bucket?>?,
        getBatchLatencyMicros: com.google.common.collect.ImmutableList<com.google.devtools.build.lib.util.Bucket?>?,
        setBatchLatencyMicros: com.google.common.collect.ImmutableList<com.google.devtools.build.lib.util.Bucket?>?
    ) {
        val getLatencyMicros: com.google.common.collect.ImmutableList<com.google.devtools.build.lib.util.Bucket?>?
        val setLatencyMicros: com.google.common.collect.ImmutableList<com.google.devtools.build.lib.util.Bucket?>?
        val getBatchLatencyMicros: com.google.common.collect.ImmutableList<com.google.devtools.build.lib.util.Bucket?>?
        val setBatchLatencyMicros: com.google.common.collect.ImmutableList<com.google.devtools.build.lib.util.Bucket?>?

        init {
            this.getLatencyMicros = getLatencyMicros
            this.setLatencyMicros = setLatencyMicros
            this.getBatchLatencyMicros = getBatchLatencyMicros
            this.setBatchLatencyMicros = setBatchLatencyMicros
        }
    }

    fun getStats(): Stats {
        return EMPTY_STATS
    }

    fun shutdown() {}

    /**
     * Associates a fingerprint with the serialized representation of some object.
     * 
     * 
     * The caller should deduplicate `put` calls to avoid multiple writes of the same
     * fingerprint.
     * 
     * @return a future that completes when the write completes
     */
    fun put(fingerprint: KeyBytesProvider?, serializedBytes: ByteArray?): WriteStatus?

    /**
     * Retrieves the serialized bytes associated with `fingerprint`.
     * 
     * @return a future eventually containing the serialized bytes. If the fingerprint is missing, the
     * future may contain null or a failed future, depending on the implementation.
     */
    @Throws(IOException::class)
    fun get(fingerprint: KeyBytesProvider?): com.google.common.util.concurrent.ListenableFuture<ByteArray?>?

    /**
     * [FingerprintValueStore.get] was called with a fingerprint that does not exist in the
     * store.
     */
    class MissingFingerprintValueException @kotlin.jvm.JvmOverloads constructor(
        fingerprint: KeyBytesProvider?,
        cause: Throwable? = null
    ) : java.lang.Exception("No remote value for " + fingerprint, cause)

    /** An in-memory [FingerprintValueStore] for testing.  */
    class InMemoryFingerprintValueStore(
        kvMap: ConcurrentMap<ByteString?, ByteString?>,
        useNullForMissingValues: Boolean
    ) : FingerprintValueStore {
        @kotlin.jvm.JvmField
        val fingerprintToContents: ConcurrentMap<ByteString?, ByteString?>

        private val useNullForMissingValues: Boolean

        @kotlin.jvm.JvmOverloads
        constructor(useNullForMissingValues: Boolean = false) : this(
            ConcurrentHashMap<ByteString?, ByteString?>(),
            useNullForMissingValues
        )

        init {
            this.fingerprintToContents = kvMap
            this.useNullForMissingValues = useNullForMissingValues
        }

        override fun put(fingerprint: KeyBytesProvider, serializedBytes: ByteArray): WriteStatus? {
            val wasNovel =
                (fingerprintToContents.put(
                    ByteString.copyFrom(fingerprint.toBytes()), ByteString.copyFrom(serializedBytes)
                )
                        == null)
            return WriteStatuses.immediateWriteStatus(wasNovel)
        }

        override fun get(fingerprint: KeyBytesProvider): com.google.common.util.concurrent.ListenableFuture<ByteArray?> {
            val serializedBytes: ByteString? =
                fingerprintToContents.get(ByteString.copyFrom(fingerprint.toBytes()))
            if (serializedBytes == null) {
                return if (useNullForMissingValues)
                    IMMEDIATE_NULL
                else
                    com.google.common.util.concurrent.Futures.immediateFailedFuture<ByteArray?>(
                        MissingFingerprintValueException(fingerprint)
                    )
            }
            return com.google.common.util.concurrent.Futures.immediateFuture<ByteArray?>(serializedBytes.toByteArray())
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun remove(fingerprint: KeyBytesProvider): ByteArray? {
            val result: ByteString? = fingerprintToContents.remove(ByteString.copyFrom(fingerprint.toBytes()))
            return if (result == null) null else result.toByteArray()
        }

        fun keys(): Iterable<ByteString?> {
            return com.google.common.collect.ImmutableList.copyOf<ByteString?>(fingerprintToContents.keySet())
        }

        companion object {
            private val IMMEDIATE_NULL: com.google.common.util.concurrent.ListenableFuture<ByteArray?> =
                com.google.common.util.concurrent.Futures.immediateFuture<ByteArray?>(null as ByteArray?)
        }
    }

    companion object {
        @kotlin.jvm.JvmStatic
        fun inMemoryStore(): InMemoryFingerprintValueStore {
            return InMemoryFingerprintValueStore()
        }

        val EMPTY_STATS: Stats = com.google.devtools.build.lib.skyframe.serialization.FingerprintValueStore.Stats(
            0,
            0,
            0,
            0,
            0,
            0,
            0,
            0,
            com.google.common.collect.ImmutableList.of<com.google.devtools.build.lib.util.Bucket?>(),
            com.google.common.collect.ImmutableList.of<com.google.devtools.build.lib.util.Bucket?>(),
            com.google.common.collect.ImmutableList.of<com.google.devtools.build.lib.util.Bucket?>(),
            com.google.common.collect.ImmutableList.of<com.google.devtools.build.lib.util.Bucket?>()
        )
    }
}
