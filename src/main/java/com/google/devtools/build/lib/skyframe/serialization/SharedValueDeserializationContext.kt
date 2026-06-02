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

import com.github.luben.zstd.RecyclingBufferPool

/** Implementation that supports sharing of sub-objects between objects.  */
internal class SharedValueDeserializationContext private constructor(
    codecRegistry: ObjectCodecRegistry?,
    dependencies: com.google.common.collect.ImmutableClassToInstanceMap<Any?>?,
    fingerprintValueService: FingerprintValueService,
    skyframeLookupCollector: SkyframeLookupCollector?
) : MemoizingDeserializationContext(codecRegistry, dependencies) {
    private val fingerprintValueService: FingerprintValueService

    /**
     * List of futures that must be resolved before this value is completely deserialized.
     * 
     * 
     * The synchronous [.deserialize] overload includes waiting for these
     * futures to to be resolved. The top-level deserialization call typically uses that overload
     * while codec implementations use the ones in [AsyncDeserializationContext].
     */
    // Initialized lazily.
    private var readStatusFutures: java.util.ArrayList<com.google.common.util.concurrent.ListenableFuture<*>?>? = null

    /**
     * Tracks which [.readStatusFutures] were added, transitively, while deserializing a value.
     * 
     * 
     * When deserializing a value, [MemoizingDeserializationContext] calls [ ][.deserializeAndMaybeHandleDeferredValues], then [.combineValueWithReadFutures].
     * 
     * 
     * [.deserializeAndMaybeHandleDeferredValues] performs the following steps.
     * 
     * 
     *  * It notes the size of [.readStatusFutures] when it begins.
     *  * It initiates deserialization of the next value with a given [ObjectCodec].
     *  * After that deserialization invocation completes, it sets `lastStartingReadCount` to
     * the size it noted when it started.
     * 
     * 
     * 
     * Consequently, the futures in [.readStatusFutures] with index greater than `lastStartingReadCount` are ones added (transitively) by deserialization.
     * 
     * 
     * Next, [.combineValueWithReadFutures] uses `lastStartingReadCount` to determine
     * what futures were added and need to be complete before deserialization of the value is
     * complete.
     */
    private var lastStartingReadCount = 0

    // non-null when Skyframe lookups are enabled
    private val skyframeLookupCollector: SkyframeLookupCollector?

    init {
        this.fingerprintValueService = fingerprintValueService
        this.skyframeLookupCollector = skyframeLookupCollector
    }

    // TODO: b/386384684 - remove Unsafe usage
    @Throws(
        IOException::class,
        com.google.devtools.build.lib.skyframe.serialization.SerializationException::class
    )  // TODO: b/331765692 - delete this
    override fun deserialize(codedIn: CodedInputStream, parent: Any?, offset: Long) {
        val result: Any? = processTagAndDeserialize(codedIn)
        if (result == null) {
            return
        }

        if (result !is com.google.common.util.concurrent.ListenableFuture<*>) {
            UnsafeProvider.unsafe().putObject(parent, offset, result)
            return
        }

        addReadStatusFuture(
            com.google.common.util.concurrent.Futures.transform(
                result as com.google.common.util.concurrent.ListenableFuture<*>,
                { value: Any? ->
                    UnsafeProvider.unsafe().putObject(parent, offset, value)
                    null
                },
                com.google.common.util.concurrent.MoreExecutors.directExecutor()
            )
        )
    }

    @Throws(IOException::class, com.google.devtools.build.lib.skyframe.serialization.SerializationException::class)
    override fun <T> deserialize(
        codedIn: CodedInputStream,
        parent: T?,
        setter: com.google.devtools.build.lib.skyframe.serialization.AsyncDeserializationContext.FieldSetter<in T?>
    ) {
        val result: Any? = processTagAndDeserialize(codedIn)
        if (result == null) {
            return
        }

        if (result !is com.google.common.util.concurrent.ListenableFuture<*>) {
            setter.set(parent, result)
            return
        }

        addReadStatusFuture(
            com.google.common.util.concurrent.Futures.transformAsync(
                result as com.google.common.util.concurrent.ListenableFuture<*>,
                { value: Any? ->
                    try {
                        setter.set(parent, value)
                    } catch (e: com.google.devtools.build.lib.skyframe.serialization.SerializationException) {
                        return@transformAsync com.google.common.util.concurrent.Futures.immediateFailedFuture<java.lang.Void?>(
                            e
                        )
                    }
                    com.google.common.util.concurrent.Futures.immediateVoidFuture()
                },
                com.google.common.util.concurrent.MoreExecutors.directExecutor()
            )
        )
    }

    // TODO: b/386384684 - remove Unsafe usage
    @Throws(
        IOException::class,
        com.google.devtools.build.lib.skyframe.serialization.SerializationException::class
    )  // TODO: b/331765692 - delete this
    override fun deserialize(codedIn: CodedInputStream, parent: Any?, offset: Long, done: java.lang.Runnable) {
        val result: Any? = processTagAndDeserialize(codedIn)
        if (result == null) {
            done.run()
            return
        }

        if (result !is com.google.common.util.concurrent.ListenableFuture<*>) {
            UnsafeProvider.unsafe().putObject(parent, offset, result)
            done.run()
            return
        }

        addReadStatusFuture(
            com.google.common.util.concurrent.Futures.transform(
                result as com.google.common.util.concurrent.ListenableFuture<*>,
                { value: Any? ->
                    UnsafeProvider.unsafe().putObject(parent, offset, value)
                    done.run()
                    null
                },
                com.google.common.util.concurrent.MoreExecutors.directExecutor()
            )
        )
    }

    @Throws(IOException::class, com.google.devtools.build.lib.skyframe.serialization.SerializationException::class)
    override fun deserializeArrayElement(codedIn: CodedInputStream, arr: Array<Any?>, index: Int) {
        val result: Any? = processTagAndDeserialize(codedIn)
        if (result == null) {
            return
        }

        if (result is com.google.common.util.concurrent.ListenableFuture<*>) {
            addReadStatusFuture(
                com.google.common.util.concurrent.Futures.transform(
                    result,
                    { value: Any? -> arr[index] = value },
                    com.google.common.util.concurrent.MoreExecutors.directExecutor()
                )
            )
            return
        }

        arr[index] = result
    }

    @Throws(IOException::class, com.google.devtools.build.lib.skyframe.serialization.SerializationException::class)
    override fun <T> getSharedValue(
        codedIn: CodedInputStream,
        distinguisher: Any?,
        codec: DeferredObjectCodec<*>,
        parent: T?,
        setter: com.google.devtools.build.lib.skyframe.serialization.AsyncDeserializationContext.FieldSetter<in T?>
    ) {
        val fingerprint: PackedFingerprint = PackedFingerprint.Companion.readFrom(codedIn)
        val getOperation: com.google.common.util.concurrent.SettableFuture<Any?> =
            com.google.common.util.concurrent.SettableFuture.create<Any?>()
        val previous: Any? =
            fingerprintValueService.getOrClaimGetOperation(fingerprint, distinguisher, getOperation)
        if (previous != null) {
            // This object was previously requested. Discards `getOperation`.
            if (previous is com.google.common.util.concurrent.ListenableFuture<*>) {
                addReadStatusFuture(
                    com.google.common.util.concurrent.Futures.transformAsync(
                        previous,
                        { value: Any? ->
                            try {
                                setter.set(parent, value)
                            } catch (e: com.google.devtools.build.lib.skyframe.serialization.SerializationException) {
                                return@transformAsync com.google.common.util.concurrent.Futures.immediateFailedFuture<java.lang.Void?>(
                                    e
                                )
                            }
                            com.google.common.util.concurrent.Futures.immediateVoidFuture()
                        },
                        com.google.common.util.concurrent.MoreExecutors.directExecutor()
                    )
                )
                return
            }
            setter.set(parent, previous)
            return
        }

        // There is no previous result. Fetches the remote bytes and deserializes them.
        readValueForFingerprint<T?>(fingerprint, codec, parent, setter, getOperation)
        addReadStatusFuture(getOperation)
    }

    override fun <T> getSkyValue(
        key: SkyKey?,
        parent: T?,
        setter: com.google.devtools.build.lib.skyframe.serialization.AsyncDeserializationContext.FieldSetter<in T?>
    ) {
        val lookup: SkyframeLookup<T?> =
            com.google.devtools.build.lib.skyframe.serialization.SharedValueDeserializationContext.SkyframeLookup<T?>(
                key,
                parent,
                setter
            )
        skyframeLookupCollector.addLookup(lookup)
        addReadStatusFuture(lookup)
    }

    @Throws(IOException::class)
    private fun <T> readValueForFingerprint(
        fingerprint: PackedFingerprint?,
        codec: DeferredObjectCodec<*>,
        parent: T?,
        setter: com.google.devtools.build.lib.skyframe.serialization.AsyncDeserializationContext.FieldSetter<in T?>,
        getOperation: com.google.common.util.concurrent.SettableFuture<Any?>
    ) {
        if (skyframeLookupCollector != null) {
            skyframeLookupCollector.notifyFetchStarting()
        }
        try {
            com.google.common.util.concurrent.Futures.addCallback<ByteArray?>(
                fingerprintValueService.get(fingerprint),
                SharedBytesProcessor<T?>(
                    codec,
                    parent,
                    setter,
                    getOperation
                ),  // Switches to another executor to avoid performing serialization work on an an RPC
                // executor thread.
                fingerprintValueService.getExecutor()
            )
        } catch (e: IOException) {
            if (skyframeLookupCollector != null) {
                skyframeLookupCollector.notifyFetchException(e)
            }
            getOperation.setException(e)
            throw e
        } catch (e: java.lang.RuntimeException) {
            if (skyframeLookupCollector != null) {
                skyframeLookupCollector.notifyFetchException(e)
            }
            getOperation.setException(e)
            throw e
        } catch (e: java.lang.Error) {
            if (skyframeLookupCollector != null) {
                skyframeLookupCollector.notifyFetchException(e)
            }
            getOperation.setException(e)
            throw e
        }
    }

    private inner class SharedBytesProcessor<T>(
        codec: DeferredObjectCodec<*>,
        parent: T?,
        setter: com.google.devtools.build.lib.skyframe.serialization.AsyncDeserializationContext.FieldSetter<in T?>,
        getOperation: com.google.common.util.concurrent.SettableFuture<Any?>
    ) : com.google.common.util.concurrent.FutureCallback<ByteArray?> {
        private val codec: DeferredObjectCodec<*>
        private val parent: T?
        private val setter: com.google.devtools.build.lib.skyframe.serialization.AsyncDeserializationContext.FieldSetter<in T?>
        private val getOperation: com.google.common.util.concurrent.SettableFuture<Any?>

        init {
            this.codec = codec
            this.parent = parent
            this.setter = setter
            this.getOperation = getOperation
        }

        override fun onSuccess(bytes: ByteArray?) {
            if (bytes == null) {
                // This error should be tolerated by falling back on computation.
                onFailure(MissingSharedValueBytesException.Companion.INSTANCE)
                return
            }
            val innerContext = getFreshContext()
            val deferred: DeferredValue<*>
            try {
                maybeDecompressBytes(bytes).use { inputStream ->
                    deferred =
                        codec.deserializeDeferred(innerContext, CodedInputStream.newInstance(inputStream))
                }
            } catch (e: com.google.devtools.build.lib.skyframe.serialization.SerializationException) {
                onFailure(e)
                return
            } catch (e: IOException) {
                onFailure(e)
                return
            } catch (e: java.lang.RuntimeException) {
                onFailure(e)
                return
            } catch (e: java.lang.Error) {
                onFailure(e)
                return
            }
            if (skyframeLookupCollector != null) {
                // The codec above is responsible for calling `getSkyValue` so any SkyKey directly requested
                // by this deserialization will be requested by this point and the notification can be sent.
                //
                // The inner reads could also call `getSkyValue` but have independent reference counting.
                skyframeLookupCollector.notifyFetchDone()
            }
            val innerReadStatusFutures: MutableList<com.google.common.util.concurrent.ListenableFuture<*>?>? =
                innerContext.readStatusFutures
            if (innerReadStatusFutures == null || innerReadStatusFutures.isEmpty()) {
                val result: Any? = deferred.call()
                try {
                    setter.set(parent, result)
                } catch (e: com.google.devtools.build.lib.skyframe.serialization.SerializationException) {
                    getOperation.setException(e)
                    return
                }
                getOperation.set(result)
                return
            }
            getOperation.setFuture(
                com.google.common.util.concurrent.Futures.whenAllSucceed<Any?>(innerReadStatusFutures)
                    .call<Any?>(
                        java.util.concurrent.Callable {
                            val result: Any? = deferred.call()
                            setter.set(parent, result)
                            result
                        },
                        com.google.common.util.concurrent.MoreExecutors.directExecutor()
                    )
            )
        }

        override fun onFailure(t: Throwable) {
            if (skyframeLookupCollector != null) {
                skyframeLookupCollector.notifyFetchException(t)
            }
            getOperation.setException(t)
        }
    }

    override fun getFreshContext(): SharedValueDeserializationContext {
        return SharedValueDeserializationContext(
            getRegistry(), getDependencies(), fingerprintValueService, skyframeLookupCollector
        )
    }

    @Throws(com.google.devtools.build.lib.skyframe.serialization.SerializationException::class)
    override fun makeSynchronous(obj: Any?): Any? {
        if (obj is com.google.common.util.concurrent.ListenableFuture<*>) {
            return FutureHelpers.waitForDeserializationFuture(obj)
        }
        return obj
    }

    @Throws(com.google.devtools.build.lib.skyframe.serialization.SerializationException::class, IOException::class)
    override fun deserializeAndMaybeHandleDeferredValues(codec: ObjectCodec<*>, codedIn: CodedInputStream?): Any? {
        val startingReadCount = if (readStatusFutures == null) 0 else readStatusFutures.size()

        val value: Any? =
            when (codec) {
                -> deferredCodec.deserializeDeferred(this, codedIn)
                -> {
                    val initialValue: Any? = interningCodec.deserializeInterned(this, codedIn)
                    val castCodec: InterningObjectCodec<Any?> = interningCodec as InterningObjectCodec<Any?>
                    InterningDeferredValue(castCodec, codec.safeCast(initialValue))
                }

                else -> codec.safeCast(codec.deserialize(this, codedIn))
            }

        this.lastStartingReadCount = startingReadCount
        return value
    }

    private class InterningDeferredValue(codec: InterningObjectCodec<Any?>, value: Any?) : DeferredValue<Any?> {
        private val codec: InterningObjectCodec<Any?>
        private val value: Any?

        init {
            this.codec = codec
            this.value = value
        }

        override fun call(): Any? {
            return codec.intern(value)
        }
    }

    override fun combineValueWithReadFutures(value: Any?): Any? {
        if (readStatusFutures == null) {
            return unwrapIfDeferredValue(value)
        }
        val length: Int = readStatusFutures.size()
        if (length <= lastStartingReadCount) {
            return unwrapIfDeferredValue(value)
        }

        val futures: MutableList<com.google.common.util.concurrent.ListenableFuture<*>?> =
            readStatusFutures.subList(lastStartingReadCount, length)
        val combiner: com.google.common.util.concurrent.Futures.FutureCombiner<*> =
            com.google.common.util.concurrent.Futures.whenAllSucceed<Any?>(futures)
        futures.clear() // clears this sublist from from `readStatusFutures`

        val futureValue: com.google.common.util.concurrent.ListenableFuture<Any?>?
        if (value is DeferredValue<*>) {
            val castValue: DeferredValue<Any?> = value as DeferredValue<Any?>
            futureValue =
                combiner.call<Any?>(castValue, com.google.common.util.concurrent.MoreExecutors.directExecutor())
        } else {
            futureValue = combiner.call<Any?>(
                java.util.concurrent.Callable { value },
                com.google.common.util.concurrent.MoreExecutors.directExecutor()
            )
        }
        readStatusFutures.add(futureValue)
        return futureValue
    }

    private fun addReadStatusFuture(readStatus: com.google.common.util.concurrent.ListenableFuture<*>?) {
        if (readStatusFutures == null) {
            readStatusFutures = java.util.ArrayList<com.google.common.util.concurrent.ListenableFuture<*>?>()
        }
        readStatusFutures.add(readStatus)
    }

    internal class SkyframeLookup<T> @com.google.common.annotations.VisibleForTesting constructor(
        key: SkyKey?,
        parent: T?,
        setter: com.google.devtools.build.lib.skyframe.serialization.AsyncDeserializationContext.FieldSetter<in T?>
    ) : com.google.common.util.concurrent.AbstractFuture<java.lang.Void?>(), QueryDepCallback {
        private val key: SkyKey?
        private val parent: T?
        private val setter: com.google.devtools.build.lib.skyframe.serialization.AsyncDeserializationContext.FieldSetter<in T?>

        /** Set true if the Skyframe dependency has an exception.  */
        @kotlin.jvm.JvmField
        private var isFailed = false

        init {
            this.key = key
            this.parent = parent
            this.setter = setter
        }

        fun getKey(): SkyKey? {
            return key
        }

        override fun acceptValue(unusedKey: SkyKey?, value: SkyValue?) {
            try {
                setter.set(parent, value)
                set(null)
            } catch (e: com.google.devtools.build.lib.skyframe.serialization.SerializationException) {
                setException(e)
            }
        }

        override fun tryHandleException(unusedKey: SkyKey?, e: java.lang.Exception?): Boolean {
            setException(SkyframeDependencyException(e))
            return true
        }

        fun isFailed(): Boolean {
            return isFailed
        }

        fun abandon(exception: LookupAbandonedException) {
            setException(exception)
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        override fun setException(t: Throwable): Boolean {
            this.isFailed = true
            return super.setException(t)
        }
    }

    /**
     * Error signaling that a [SkyframeLookup] is abandoned.
     * 
     * 
     * This does not indicate a deserialization failure for the value depending on the lookup. See
     * the subclasses for more details.
     */
    internal open class LookupAbandonedException : java.lang.Exception {
        constructor()

        constructor(cause: Throwable?) : super(cause)
    }

    /**
     * Used when SkyKey compute state is evicted (due to memory pressure).
     * 
     * 
     * Since the compute state is lost, there's no way to perform the Skyframe lookups needed to
     * satisfy the [SkyframeLookup].
     */
    internal class StateEvictedException : LookupAbandonedException()

    /**
     * A lookup is abandoned because another sub-value failed to deserialize (possibly due to a failed
     * Skyframe lookup).
     * 
     * 
     * Since one sub-value did not deserialize correctly, there's no way to deserialize the value.
     */
    internal class PeerFailedException(cause: Throwable?) : LookupAbandonedException(cause)

    /**
     * Indicates that the bytes for a shared value were missing.
     * 
     * 
     * This error should be tolerated by falling back on computation.
     */
    class MissingSharedValueBytesException private constructor() :
        com.google.devtools.build.lib.skyframe.serialization.SerializationException(
            "Missing shared value bytes",
            MissReason.MISS_REASON_REFERENCED_OBJECT_MISS
        ) {
        /**
         * Does nothing.
         * 
         * 
         * This is overridden for performance. Since this exception is used for control flow, the
         * stack trace is not needed and avoiding filling it in is a significant optimization.
         */
        override fun fillInStackTrace(): Throwable {
            // No-op to avoid capturing the stack trace.
            return this
        }

        companion object {
            /**
             * Singleton instance.
             * 
             * 
             * This exception is used to signal cache misses and can be thrown millions of times in a
             * build. Using a singleton avoids the overhead of object allocation, message formatting, and
             * stack trace generation, all of which are expensive at scale and unnecessary for control flow.
             */
            @kotlin.jvm.JvmField
            val INSTANCE: MissingSharedValueBytesException = MissingSharedValueBytesException()
        }
    }

    companion object {
        @com.google.common.annotations.VisibleForTesting // private
        fun createForTesting(
            codecRegistry: ObjectCodecRegistry?,
            dependencies: com.google.common.collect.ImmutableClassToInstanceMap<Any?>?,
            fingerprintValueService: FingerprintValueService
        ): SharedValueDeserializationContext {
            return SharedValueDeserializationContext(
                codecRegistry, dependencies, fingerprintValueService,  /* skyframeLookupCollector= */null
            )
        }

        @Throws(com.google.devtools.build.lib.skyframe.serialization.SerializationException::class)
        fun deserializeWithSharedValues(
            codecRegistry: ObjectCodecRegistry?,
            dependencies: com.google.common.collect.ImmutableClassToInstanceMap<Any?>?,
            fingerprintValueService: FingerprintValueService,
            bytes: ByteString
        ): Any? {
            try {
                return ObjectCodecs.Companion.deserializeStreamFully(
                    bytes.newCodedInput(),
                    SharedValueDeserializationContext(
                        codecRegistry,
                        dependencies,
                        fingerprintValueService,  /* skyframeLookupCollector= */
                        null
                    )
                )
            } catch (e: com.google.devtools.build.lib.skyframe.serialization.SerializationException) {
                val cause: Throwable? = e.getCause()
                if (cause is MissingFingerprintValueException) {
                    // TODO: b/297857068 - eventually, callers of this should handle this by falling back on
                    // local recomputation.
                    throw java.lang.IllegalStateException("Not yet supported.", cause)
                }
                throw e
            }
        }

        /**
         * Deserializes with possible Skyframe lookups via [.getSkyValue].
         * 
         * 
         * This may return two distinct kinds of results.
         * 
         * 
         *  * An immediate nullable value that is the result of deserialization.
         *  * A `ListenableFuture<SkyframeLookupContinuation>` requiring further resolution.
         * 
         */
        @Throws(com.google.devtools.build.lib.skyframe.serialization.SerializationException::class)
        fun deserializeWithSkyframe(
            codecRegistry: ObjectCodecRegistry?,
            dependencies: com.google.common.collect.ImmutableClassToInstanceMap<Any?>?,
            fingerprintValueService: FingerprintValueService,
            codedIn: CodedInputStream
        ): Any? {
            // Enabling aliasing of `codedIn` here might be better for performance but causes deserialized
            // values to differ subtly from the input values, complicating testing.
            //
            // TODO: b/335901349 - re-enable aliasing
            val lookupCollector: SkyframeLookupCollector = SkyframeLookupCollector()
            val context =
                SharedValueDeserializationContext(
                    codecRegistry, dependencies, fingerprintValueService, lookupCollector
                )
            val result: Any?
            try {
                result = context.processTagAndDeserialize(codedIn)
            } catch (e: IOException) {
                throw com.google.devtools.build.lib.skyframe.serialization.SerializationException(
                    "Failed to deserialize data",
                    e
                )
            }
            ObjectCodecs.Companion.checkInputFullyConsumed(codedIn, result)
            if (result == null) {
                return null
            }
            if (result !is com.google.common.util.concurrent.ListenableFuture<*>) {
                return result
            }
            lookupCollector.notifyFetchesInitialized()
            return com.google.common.util.concurrent.Futures.transform<I?, O?>(
                lookupCollector,
                com.google.common.base.Function { lookups: I? -> SkyframeLookupContinuation(lookups, result) },
                com.google.common.util.concurrent.MoreExecutors.directExecutor()
            )
        }

        @Throws(IOException::class)
        private fun maybeDecompressBytes(bytes: ByteArray): java.io.InputStream {
            val byteArrayInputStream: ByteArrayInputStream =
                ByteArrayInputStream(bytes, 1, bytes.size - 1)
            if (bytes[0] == 0.toByte()) {
                return byteArrayInputStream
            }
            return ZstdInputStream(byteArrayInputStream, RecyclingBufferPool.INSTANCE)
        }

        private fun unwrapIfDeferredValue(value: Any?): Any? {
            if (value is DeferredValue<*>) {
                val castValue: DeferredValue<Any?> = value as DeferredValue<Any?>
                return castValue.call()
            }
            return value
        }
    }
}
