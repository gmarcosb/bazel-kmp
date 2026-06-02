// Copyright 2025 The Bazel Authors. All rights reserved.
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

/**
 * Helper class for checking which keys should be invalidated using a remote analysis cache service.
 */
class AnalysisCacheInvalidator(
    analysisCacheClient: RemoteAnalysisCacheClient?,
    objectCodecs: ObjectCodecs?,
    fingerprintValueService: FingerprintValueService?,
    currentVersion: FrontierNodeVersion?,
    currentClientId: com.google.devtools.build.lib.skyframe.serialization.analysis.ClientId?,
    eventHandler: ExtendedEventHandler?,
    eventListener: RemoteAnalysisCachingEventListener?
) {
    private val analysisCacheClient: RemoteAnalysisCacheClient
    private val codecs: ObjectCodecs
    private val fingerprintService: FingerprintValueService
    private val eventHandler: ExtendedEventHandler
    private val eventListener: RemoteAnalysisCachingEventListener
    private val currentVersion: FrontierNodeVersion
    private val currentClientId: com.google.devtools.build.lib.skyframe.serialization.analysis.ClientId

    init {
        this.analysisCacheClient = com.google.common.base.Preconditions.checkNotNull<RemoteAnalysisCacheClient>(
            analysisCacheClient,
            "analysisCacheClient"
        )
        this.codecs = com.google.common.base.Preconditions.checkNotNull<ObjectCodecs>(objectCodecs, "objectCodecs")
        this.fingerprintService = com.google.common.base.Preconditions.checkNotNull<FingerprintValueService>(
            fingerprintValueService,
            "fingerprintValueService"
        )
        this.currentVersion =
            com.google.common.base.Preconditions.checkNotNull<FrontierNodeVersion>(currentVersion, "currentVersion")
        this.currentClientId =
            com.google.common.base.Preconditions.checkNotNull<com.google.devtools.build.lib.skyframe.serialization.analysis.ClientId>(
                currentClientId,
                "currentClientId"
            )
        this.eventHandler =
            com.google.common.base.Preconditions.checkNotNull<ExtendedEventHandler>(eventHandler, "eventHandler")
        this.eventListener = com.google.common.base.Preconditions.checkNotNull<RemoteAnalysisCachingEventListener>(
            eventListener,
            "eventListener"
        )
    }

    /**
     * Looks up the given keys in the analysis cache service to determine which ones should be
     * invalidated.
     * 
     * @param keysToLookupSupplier The supplier of set of SkyKeys to check.
     * @return The subset of keysToLookup that got a cache miss should be invalidated locally.
     */
    @Throws(java.lang.InterruptedException::class)
    fun lookupKeysToInvalidate(
        keysToLookupSupplier: java.util.function.Supplier<com.google.common.collect.ImmutableSet<SkyKey?>>,
        serverState: RemoteAnalysisCachingServerState
    ): com.google.common.collect.ImmutableSet<SkyKey?>? {
        val previousVersion: FrontierNodeVersion? = serverState.version()
        if (previousVersion == null) {
            // TODO: b/439857268 - it looks like this can happen if the previous build was interrupted,
            // but the exact way that leads to the previous version being unset is not entirely clear.
            logger.atWarning().log(
                "Skycache: no previous version was found during invalidation check. Invalidating"
                        + " everything"
            )
            return keysToLookupSupplier.get() // invalidate everything
        }

        if (previousVersion != currentVersion) {
            logger.atInfo().log(
                "Skycache: Version changed during invalidation check. Previous version: %s, current"
                        + " version: %s.",
                previousVersion, currentVersion
            )
            return keysToLookupSupplier.get() // everything must be invalidated
        }

        if (currentClientId == serverState.clientId()) {
            // The current client state is the same as the previous client state, so
            // no invalidation is needed because all deserialized keys are still valid.
            return com.google.common.collect.ImmutableSet.of<SkyKey?>()
        }

        val keysToLookup: com.google.common.collect.ImmutableSet<SkyKey?> = keysToLookupSupplier.get()

        if (keysToLookup.isEmpty()) {
            logger.atInfo().log("Skycache: No keys to lookup for invalidation check.")
            return com.google.common.collect.ImmutableSet.of<SkyKey?>()
        }

        val stopwatch: com.google.common.base.Stopwatch = com.google.common.base.Stopwatch.createStarted()

        val futures: com.google.common.collect.ImmutableList<com.google.common.util.concurrent.ListenableFuture<java.util.Optional<SkyKey?>?>?>?
        Profiler.instance().profile("submitInvalidationLookups").use { unused ->
            futures =
                keysToLookup.parallelStream()
                    .map<com.google.common.util.concurrent.ListenableFuture<java.util.Optional<SkyKey?>?>?>(java.util.function.Function { key: SkyKey? ->
                        this.submitInvalidationLookup(
                            key
                        )
                    })
                    .collect(com.google.common.collect.ImmutableList.toImmutableList<com.google.common.util.concurrent.ListenableFuture<java.util.Optional<SkyKey?>?>?>())
        }
        Profiler.instance().profile("waitInvalidationLookups").use { unused ->
            var keysToInvalidate: com.google.common.collect.ImmutableSet<SkyKey?>?
            var status: InvalidationLookupMetrics.Status? = null
            var numInvalidatedKeys = 0
            try {
                keysToInvalidate =
                    com.google.common.util.concurrent.Futures.allAsList<java.util.Optional<SkyKey?>?>(futures)
                        .get(10, TimeUnit.SECONDS)
                        .stream() // Flatten Optionals, keeping only non-empty ones (keys to invalidate)
                        .flatMap<SkyKey?>(java.util.function.Function { obj: java.util.Optional<SkyKey?>? -> obj.stream() })
                        .collect(com.google.common.collect.ImmutableSet.toImmutableSet<SkyKey?>())
                status = InvalidationLookupMetrics.Status.OK
                numInvalidatedKeys = keysToInvalidate.size()
            } catch (e: ExecutionException) {
                status = InvalidationLookupMetrics.Status.ERROR
                numInvalidatedKeys = keysToLookup.size()
                logger.atWarning().withCause(e).log(
                    "Skycache: Error waiting for analysis cache responses during invalidation check."
                            + " Invalidating everything."
                )
                return keysToLookup
            } catch (e: java.util.concurrent.TimeoutException) {
                status = InvalidationLookupMetrics.Status.TIMED_OUT
                numInvalidatedKeys = keysToLookup.size()
                logger.atWarning().log(
                    "Skycache: Timeout waiting for analysis cache responses during invalidation check."
                            + " Invalidating everything."
                )
                return keysToLookup
            } finally {
                stopwatch.stop()
                if (status != null) {
                    eventListener.setInvalidationLookupMetrics(
                        InvalidationLookupMetrics.newBuilder()
                            .setLatencyMicros(stopwatch.elapsed(TimeUnit.MICROSECONDS))
                            .setStatus(status)
                            .setNumKeys(keysToLookup.size())
                            .setNumInvalidatedKeys(numInvalidatedKeys)
                            .build()
                    )
                }
            }
            eventHandler.handle(
                Event.info(
                    java.lang.String.format(
                        "Skycache: Invalidation lookup took %s. %s/%s keys will be invalidated.",
                        stopwatch, keysToInvalidate.size(), futures.size()
                    )
                )
            )
            return keysToInvalidate
        }
    }

    /**
     * Checks if the given node should be invalidated by submitting the node's fingerprint to the
     * analysis cache.
     * 
     * 
     * Returns the node's SkyKey if the node should be invalidated (i.e. cache miss), otherwise
     * returns an empty Optional.
     * 
     * 
     * Note: only lookup SkyKeys that were deserialized! Sending a key that was never serialized
     * will result in a cache miss for every build.
     */
    private fun submitInvalidationLookup(key: SkyKey): com.google.common.util.concurrent.ListenableFuture<java.util.Optional<SkyKey?>?> {
        // 1. Serialize the key
        val serializeKeyTask: AsyncSerializationTask =
            codecs.serializeMemoizedAsync(fingerprintService, key, null)
        serializeKeyTask.run()

        // 2. Compute the fingerprint from the serialized blob
        val fingerprint: com.google.common.util.concurrent.ListenableFuture<PackedFingerprint?> =
            com.google.common.util.concurrent.Futures.transform<SerializationResult<ByteString?>?, PackedFingerprint?>(
                serializeKeyTask,
                com.google.common.base.Function { k: SerializationResult<ByteString?>? ->
                    fingerprintService.fingerprint(
                        currentVersion.concat(k.getObject().toByteArray())
                    )
                },
                ForkJoinPool.commonPool()
            )

        // 3. Submit the fingerprint to the analysis cache service
        val responseFuture: com.google.common.util.concurrent.ListenableFuture<com.google.devtools.build.lib.skyframe.serialization.analysis.LookupResult?> =
            com.google.common.util.concurrent.Futures.transformAsync<PackedFingerprint?, com.google.devtools.build.lib.skyframe.serialization.analysis.LookupResult?>(
                fingerprint,
                com.google.common.util.concurrent.AsyncFunction { f: PackedFingerprint? ->
                    analysisCacheClient.lookup(
                        ByteString.copyFrom(f.toBytes())
                    )
                },
                ForkJoinPool.commonPool()
            )

        // 4. Transform result to return keys that should be invalidated (i.e.
        // empty response, cache miss)
        return com.google.common.util.concurrent.Futures.transform<com.google.devtools.build.lib.skyframe.serialization.analysis.LookupResult?, java.util.Optional<SkyKey?>?>(
            responseFuture,
            com.google.common.base.Function { response: com.google.devtools.build.lib.skyframe.serialization.analysis.LookupResult? ->
                if (response.value.isEmpty()) java.util.Optional.of<SkyKey?>(
                    key
                ) else java.util.Optional.empty<SkyKey?>()
            },
            com.google.common.util.concurrent.MoreExecutors.directExecutor()
        )
    }

    companion object {
        private val logger: GoogleLogger = GoogleLogger.forEnclosingClass()
    }
}
