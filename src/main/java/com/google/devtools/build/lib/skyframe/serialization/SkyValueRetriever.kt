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

import com.google.devtools.build.lib.skyframe.serialization.analysis.proto.MissReason

/** Fetches remotely stored [SkyValue]s by [SkyKey].  */
object SkyValueRetriever {
    /**
     * Attempts to retrieve the value associated with `key` from `fingerprintValueService`.
     * 
     * 
     * The key is formed by serializing the SkyKey, appending the version metadata, and
     * fingerprinting the result.
     * 
     * @param analysisCacheClient client for querying the AnalysisCacheService. Uses frontier-based
     * invalidation.
     * @return a [RetrievalResult] instance. This can be [NoCachedData] when there is no
     * data associated with the given key.
     */
    @Throws(
        java.lang.InterruptedException::class,
        com.google.devtools.build.lib.skyframe.serialization.SerializationException::class
    )
    fun tryRetrieve(
        env: LookupEnvironment?,
        futuresShim: DependOnFutureShim,
        codecs: ObjectCodecs,
        fingerprintValueService: FingerprintValueService?,
        analysisCacheClient: RemoteAnalysisCacheClient,
        key: SkyKey?,
        retrievalContext: RetrievalContext,
        frontierNodeVersion: FrontierNodeVersion?
    ): RetrievalResult {
        var serializationState = retrievalContext.getState()
        try {
            while (true) {
                when (serializationState) {
                    -> {
                        val cacheKey: PackedFingerprint =
                            FingerprintValueService.Companion.computeFingerprint(
                                fingerprintValueService, codecs, key, frontierNodeVersion
                            )
                        val futureResponse: com.google.common.util.concurrent.ListenableFuture<com.google.devtools.build.lib.skyframe.serialization.analysis.LookupResult?>? =
                            analysisCacheClient.lookup(ByteString.copyFrom(cacheKey.toBytes()))

                        serializationState = WaitingForCacheServiceResponse(futureResponse)
                        when (futuresShim.dependOnFuture(futureResponse)) {
                            ObservedFutureStatus.DONE -> {}
                            ObservedFutureStatus.NOT_DONE -> return Restart.RESTART
                        }
                    }

                    -> {
                        val result: com.google.devtools.build.lib.skyframe.serialization.analysis.LookupResult?
                        try {
                            result =
                                com.google.common.util.concurrent.Futures.getDone<com.google.devtools.build.lib.skyframe.serialization.analysis.LookupResult?>(
                                    futureResult
                                )
                        } catch (e: ExecutionException) {
                            throw com.google.devtools.build.lib.skyframe.serialization.SerializationException(
                                "getting cache response for " + key,
                                e
                            )
                        }
                        if (result.value.isEmpty()) {
                            serializationState = NoCachedData(result.missReason)
                            break
                        }

                        serializationState = ProcessValueBytes(result.value)
                    }

                    -> {
                        val value: Any? = valueBytes.deserializeWithSkyframe(codecs, fingerprintValueService)
                        if (value !is com.google.common.util.concurrent.ListenableFuture<*>) {
                            serializationState = RetrievedValue(value as SkyValue?)
                            break
                        }

                        val futureContinuation: com.google.common.util.concurrent.ListenableFuture<SkyframeLookupContinuation?> =
                            value as com.google.common.util.concurrent.ListenableFuture<SkyframeLookupContinuation?>
                        serializationState = WaitingForFutureLookupContinuation(futureContinuation)
                        when (futuresShim.dependOnFuture(futureContinuation)) {
                            ObservedFutureStatus.DONE -> {}
                            ObservedFutureStatus.NOT_DONE -> return Restart.RESTART
                        }
                    }

                    ->             // This state is transient. It discards the wrapping future before
                        // WaitingForLookupContinuation so restarts from that state do not need repeat the
                        // unwrapping.
                        try {
                            serializationState = WaitingForLookupContinuation(
                                com.google.common.util.concurrent.Futures.getDone<SkyframeLookupContinuation?>(
                                    futureContinuation
                                )
                            )
                        } catch (e: ExecutionException) {
                            val reason: MissReason? =
                                if (e.getCause() is com.google.devtools.build.lib.skyframe.serialization.SerializationException)
                                    se.getReason()
                                else
                                    MissReason.MISS_REASON_UNSPECIFIED
                            throw com.google.devtools.build.lib.skyframe.serialization.SerializationException(
                                "waiting for all owned shared values for " + key, e, reason
                            )
                        }

                    -> {
                        val futureResult: com.google.common.util.concurrent.ListenableFuture<*>?
                        try {
                            futureResult =
                                lookupContinuation.process(env) // only source of InterruptedException
                        } catch (e: SkyframeDependencyException) {
                            throw com.google.devtools.build.lib.skyframe.serialization.SerializationException(
                                "skyframe dependency error during deserialization for " + key, e
                            )
                        }
                        if (futureResult == null) {
                            return Restart.RESTART
                        }
                        serializationState = WaitingForFutureResult(futureResult)
                        when (futuresShim.dependOnFuture(futureResult)) {
                            ObservedFutureStatus.DONE -> {}
                            ObservedFutureStatus.NOT_DONE -> return Restart.RESTART
                        }
                    }

                    -> try {
                        serializationState =
                            RetrievedValue(com.google.common.util.concurrent.Futures.getDone(futureResult) as SkyValue?)
                    } catch (e: ExecutionException) {
                        throw com.google.devtools.build.lib.skyframe.serialization.SerializationException(
                            "waiting for deserialization result for " + key,
                            e
                        )
                    }

                    -> return value
                    -> return noCachedData
                }
            }
        } catch (e: CancellationException) {
            // CancellationException may be thrown from any of the calls to getDone. Reports
            // NO_CACHED_DATA to bail out of remote retrieval.
            //
            // TODO: b/438142239 - ideally, CancellationException would be handled by Skyframe. However,
            // it is only thrown by this method and NO_CACHED_DATA is a safe fallback.
            val result = NoCachedData(MissReason.MISS_REASON_UNSPECIFIED)
            serializationState = result
            return result
        } finally {
            retrievalContext.setState(serializationState)
        }
    }

    /**
     * A wrapper for the mutable state of the analysis cache deserialization machinery.
     * 
     * 
     * It's mostly a continuation but also contains various kinds of data mostly useful for
     * debugging that are orthogonal to the continuation.
     */
    class RetrievalContext {
        @kotlin.jvm.JvmField
        private var state: SerializationState
        private var restarts = 0

        init {
            state = InitialQuery.INITIAL_QUERY
        }

        fun getState(): SerializationState {
            return state
        }

        fun setState(newState: SerializationState) {
            state = newState
        }

        fun getRestarts(): Int {
            return restarts
        }

        fun addRestart() {
            restarts++
        }
    }

    /**
     * A [SkyKeyComputeState] implementation may additionally support this interface to enable
     * the [SkyFunction] to use [.tryRetrieve].
     * 
     * 
     * Provides access to a [SerializationState] that is intended to persist across Skyframe
     * restarts and which is used to represent the state of the serialization machinery.
     */
    interface RetrievalContextProvider {
        fun getRetrievalContext(): RetrievalContext?
    }

    /** A [RetrievalContextProvider] implemented as a [SkyKeyComputeState].  */
    interface SerializableSkyKeyComputeState

        : RetrievalContextProvider, SkyKeyComputeState {
        override fun close() {
            getRetrievalContext()!!.getState().cleanupSerializationState()
        }
    }

    /**
     * Opaque state of serialization.
     * 
     * 
     * Clients must call [.cleanupSerializationState] if state is evicted.
     * 
     * 
     * Each permitted type corresponds to a mostly sequential state.
     * 
     * 
     *  1. [InitialQuery]: serializes the key and initiates fetching via the
     * AnalysisCacheService client.
     *  1. [WaitingForCacheServiceResponse]: waits for value bytes from the
     * AnalysisCacheService to become available.
     *  1. [ProcessValueBytes]: a transient state that begins deserialization of the value
     * bytes. When the result is available immediately, may directly transition to [       ].
     *  1. [WaitingForFutureLookupContinuation]: waits for the [       ] to become available. This corresponds to immediate
     * deserialization of any owned shared bytes. Immediate means that the shared bytes have
     * been processed, but pending futures might be registered by this step. The significance of
     * this instant is that all [SkyKey]s that need to be looked up to deserialize the
     * value are known.
     *  1. [WaitingForLookupContinuation]: looks up any [SkyKey]s needed for
     * deserialization and waits for any resulting Skyframe restarts.
     *  1. [WaitingForFutureResult]: waits for remaining futures needed to complete
     * deserialization. This includes: any required *unowned* shared values; a small amount
     * of work to set values in parents; and propagating the corresponding futures.
     *  1. [NoCachedData]: a sentinel state representing a known cache miss. This is a
     * terminal serialization state so that Skyframe restarts during the fallback local
     * evaluation will not try to fetch the non-existent value again and waste computation.
     *  1. [RetrievedValue]: returns the retrieved value, idempotently.
     * 
     */
    interface SerializationState {
        fun cleanupSerializationState() {}
    }

    /** Return value of [.tryRetrieve].  */
    interface RetrievalResult


    /**
     * Deserialization requires a Skyframe restart to proceed.
     * 
     * 
     * This may indicate that deserialization is blocked on an unavailable Skyframe value or
     * asynchronous I/O. When [.tryRetrieve] is called inside a [SkyFunction] returning
     * null from [SkyFunction.compute] is appropriate.
     */
    enum class Restart : RetrievalResult {
        RESTART
    }

    /**
     * There was no associated data in the cache for the requested key.
     * 
     * 
     * A typical client falls back on local computation upon seeing this.
     */
    class NoCachedData(reason: MissReason?) : SerializationState, RetrievalResult {
        val reason: MissReason?

        init {
            this.reason = reason
        }
    }

    /** The value was successfully retrieved.  */
    class RetrievedValue(value: SkyValue?) : SerializationState, RetrievalResult {
        val value: SkyValue?

        init {
            this.value = value
        }
    }

    private enum class InitialQuery : SerializationState {
        INITIAL_QUERY
    }

    @com.google.common.annotations.VisibleForTesting
    internal class WaitingForCacheServiceResponse(lookupResult: com.google.common.util.concurrent.ListenableFuture<com.google.devtools.build.lib.skyframe.serialization.analysis.LookupResult?>?) :
        SerializationState {
        val lookupResult: com.google.common.util.concurrent.ListenableFuture<com.google.devtools.build.lib.skyframe.serialization.analysis.LookupResult?>?

        init {
            this.lookupResult = lookupResult
        }
    }

    private class ProcessValueBytes(valueBytes: ByteString?) : SerializationState {
        @Throws(com.google.devtools.build.lib.skyframe.serialization.SerializationException::class)
        fun deserializeWithSkyframe(
            codecs: ObjectCodecs, fingerprintValueService: FingerprintValueService?
        ): Any? {
            return codecs.deserializeWithSkyframe(fingerprintValueService, valueBytes)
        }

        val valueBytes: ByteString?

        init {
            this.valueBytes = valueBytes
        }
    }

    @com.google.common.annotations.VisibleForTesting
    internal class WaitingForFutureLookupContinuation(futureContinuation: com.google.common.util.concurrent.ListenableFuture<SkyframeLookupContinuation?>?) :
        SerializationState {
        val futureContinuation: com.google.common.util.concurrent.ListenableFuture<SkyframeLookupContinuation?>?

        init {
            this.futureContinuation = futureContinuation
        }
    }

    @com.google.common.annotations.VisibleForTesting
    internal class WaitingForLookupContinuation(continuation: SkyframeLookupContinuation?) : SerializationState {
        override fun cleanupSerializationState() {
            continuation.abandon(StateEvictedException())
        }

        val continuation: SkyframeLookupContinuation?

        init {
            this.continuation = continuation
        }
    }

    @com.google.common.annotations.VisibleForTesting
    internal class WaitingForFutureResult(futureResult: com.google.common.util.concurrent.ListenableFuture<*>?) :
        SerializationState {
        val futureResult: com.google.common.util.concurrent.ListenableFuture<*>?

        init {
            this.futureResult = futureResult
        }
    }
}
