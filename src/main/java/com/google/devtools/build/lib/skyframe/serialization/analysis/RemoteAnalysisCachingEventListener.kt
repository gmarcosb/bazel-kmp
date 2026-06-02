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
package com.google.devtools.build.lib.skyframe.serialization.analysis

import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos.BuildMetrics.RemoteAnalysisCacheStatistics.InvalidationLookupMetrics

/** An [com.google.common.eventbus.EventBus] listener for remote analysis caching events.  */
@ThreadSafety.ThreadSafe
class RemoteAnalysisCachingEventListener {
    /**
     * An event for when a Skyframe node has been serialized, but its associated write futures (i.e.
     * RPC latency) may not be done yet.
     */
    class SerializedNodeEvent(key: SkyKey?) {
        val key: SkyKey?

        init {
            this.key = key
            com.google.common.base.Preconditions.checkNotNull<SkyKey?>(key)
        }
    }

    private val serializedKeys: MutableSet<SkyKey?> = ConcurrentHashMap.newKeySet<SkyKey?>()
    private val cacheHits: MutableSet<SkyKey?> = ConcurrentHashMap.newKeySet<SkyKey?>()
    private val cacheMisses: MutableSet<SkyKey?> = ConcurrentHashMap.newKeySet<SkyKey?>()
    private val serializationExceptions: MutableSet<com.google.devtools.build.lib.skyframe.serialization.SerializationException?> =
        ConcurrentHashMap.newKeySet<com.google.devtools.build.lib.skyframe.serialization.SerializationException?>()
    private val hitsBySkyFunctionName: ConcurrentHashMap<SkyFunctionName?, AtomicLong?> =
        ConcurrentHashMap<SkyFunctionName?, AtomicLong?>()
    private val missesBySkyFunctionName: ConcurrentHashMap<SkyFunctionName?, AtomicLong?> =
        ConcurrentHashMap<SkyFunctionName?, AtomicLong?>()

    private val missesByReason: ConcurrentHashMap<MissReason?, AtomicLong?> =
        ConcurrentHashMap<MissReason?, AtomicLong?>()
    private val invalidationLookupMetrics: AtomicReference<InvalidationLookupMetrics?> =
        AtomicReference<InvalidationLookupMetrics?>()

    private val skyValueVersion: AtomicReference<FrontierNodeVersion?> = AtomicReference<FrontierNodeVersion?>()

    private var fingerprintValueStoreStats: FingerprintValueStore.Stats = FingerprintValueStore.EMPTY_STATS
    private var remoteAnalysisCacheStats: RemoteAnalysisCacheClient.Stats = RemoteAnalysisCacheClient.EMPTY_STATS

    private var clientId: com.google.devtools.build.lib.skyframe.serialization.analysis.ClientId? = null

    @com.google.common.eventbus.Subscribe
    @com.google.common.eventbus.AllowConcurrentEvents
    @Suppress("unused")
    fun onSerializationComplete(event: SerializedNodeEvent) {
        serializedKeys.add(event.key)
    }

    val skyfunctionCounts: com.google.common.collect.Multiset<SkyFunctionName?>
        /** Returns the counts of [SkyFunctionName] from serialized nodes of this invocation.  */
        get() {
            val counts: com.google.common.collect.Multiset<SkyFunctionName?> =
                com.google.common.collect.HashMultiset.create<SkyFunctionName?>()
            serializedKeys.forEach(java.util.function.Consumer { key: SkyKey? -> counts.add(key.functionName()) })
            return counts
        }

    val serializedKeysCount: Int
        /** Returns the count of serialized nodes of this invocation.  */
        get() = serializedKeys.size()

    fun getSerializedKeys(): MutableSet<SkyKey?> {
        return com.google.common.collect.ImmutableSet.copyOf<SkyKey?>(serializedKeys)
    }

    fun getCacheHits(): MutableSet<SkyKey?> {
        return com.google.common.collect.ImmutableSet.copyOf<SkyKey?>(cacheHits)
    }

    fun getCacheMisses(): MutableSet<SkyKey?> {
        return com.google.common.collect.ImmutableSet.copyOf<SkyKey?>(cacheMisses)
    }

    fun recordServiceStats(
        fvsStats: FingerprintValueStore.Stats?, raccStats: RemoteAnalysisCacheClient.Stats?
    ) {
        fingerprintValueStoreStats =
            com.google.common.base.Preconditions.checkNotNull<FingerprintValueStore.Stats>(fvsStats)
        remoteAnalysisCacheStats =
            com.google.common.base.Preconditions.checkNotNull<RemoteAnalysisCacheClient.Stats>(raccStats)
    }

    fun getFingerprintValueStoreStats(): FingerprintValueStore.Stats {
        return fingerprintValueStoreStats
    }

    fun getRemoteAnalysisCacheStats(): RemoteAnalysisCacheClient.Stats {
        return remoteAnalysisCacheStats
    }

    @ThreadSafe
    fun recordRetrievalResult(result: RetrievalResult, key: SkyKey) {
        when (result) {
            -> {
                if (!cacheHits.add(key)) {
                    return
                }
                hitsBySkyFunctionName
                    .computeIfAbsent(
                        key.functionName(),
                        java.util.function.Function { k: SkyFunctionName? -> AtomicLong() })
                    .incrementAndGet()
            }

            -> recordCacheMiss(key, reason)
            Restart.RESTART -> {}
        }
    }

    /** Returns the number of cache hits grouped by SkyFunction name.  */
    fun getHitsBySkyFunctionName(): com.google.common.collect.ImmutableMap<SkyFunctionName?, AtomicLong?> {
        return com.google.common.collect.ImmutableMap.copyOf<SkyFunctionName?, AtomicLong?>(hitsBySkyFunctionName)
    }

    /** Returns the number of cache misses grouped by SkyFunction name.  */
    fun getMissesBySkyFunctionName(): com.google.common.collect.ImmutableMap<SkyFunctionName?, AtomicLong?> {
        return com.google.common.collect.ImmutableMap.copyOf<SkyFunctionName?, AtomicLong?>(missesBySkyFunctionName)
    }

    fun getMissesByReason(): com.google.common.collect.ImmutableMap<MissReason?, AtomicLong?> {
        return com.google.common.collect.ImmutableMap.copyOf<MissReason?, AtomicLong?>(missesByReason)
    }

    /** Records a [SerializationException] encountered during SkyValue retrievals.  */
    fun recordSerializationException(
        e: com.google.devtools.build.lib.skyframe.serialization.SerializationException,
        key: SkyKey
    ) {
        serializationExceptions.add(e)
        recordCacheMiss(key, e.getReason())
    }

    val serializationExceptionCounts: Int
        /**
         * Returns the number of [SerializationException]s that were thrown during this invocation.
         */
        get() = serializationExceptions.size()

    fun recordSkyValueVersion(version: FrontierNodeVersion?) {
        this.skyValueVersion.set(version)
    }

    fun getSkyValueVersion(): FrontierNodeVersion? {
        return skyValueVersion.get()
    }

    fun setClientId(clientId: com.google.devtools.build.lib.skyframe.serialization.analysis.ClientId?) {
        this.clientId = clientId
    }

    fun getClientId(): com.google.devtools.build.lib.skyframe.serialization.analysis.ClientId? {
        return clientId
    }

    fun setInvalidationLookupMetrics(invalidationLookupMetrics: InvalidationLookupMetrics?) {
        this.invalidationLookupMetrics.set(invalidationLookupMetrics)
    }

    fun getInvalidationLookupMetrics(): InvalidationLookupMetrics? {
        return invalidationLookupMetrics.get()
    }

    private fun recordCacheMiss(key: SkyKey, reason: MissReason?) {
        if (reason === MissReason.MISS_REASON_NOT_ATTEMPTED) {
            // Not actually a cache miss
            return
        }

        if (!cacheMisses.add(key)) {
            return
        }
        missesBySkyFunctionName
            .computeIfAbsent(key.functionName(), java.util.function.Function { k: SkyFunctionName? -> AtomicLong() })
            .incrementAndGet()

        missesByReason.computeIfAbsent(reason, java.util.function.Function { r: MissReason? -> AtomicLong() })
            .incrementAndGet()
    }
}
