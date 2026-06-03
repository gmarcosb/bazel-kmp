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

import com.google.devtools.build.lib.skyframe.serialization.testutils.Dumper.dumpStructureWithEquivalenceReduction

@RunWith(TestParameterInjector::class)
class SerializationWithSkyframeTest {
    private val codecs: ObjectCodecs = ObjectCodecs(
        AutoRegistry.get()
            .getBuilder()
            .add(NotNestedSetCodec(NestedArrayCodec()))
            .build()
    )
    private val recordingStore: GetRecordingStore = GetRecordingStore()
    private var fingerprintValueService: FingerprintValueService? =
        FingerprintValueService.createForTesting(recordingStore)

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun serializeWithOneSkyKey(
        @TestParameter missingValue: Boolean, @TestParameter injectSkyframeError: Boolean
    ) {
        val key: ExampleKey = ExampleKey("test")
        val value: ExampleValue = ExampleValue(key, 10)

        val serialized: SerializationResult<ByteString?> =
            codecs.serializeMemoizedAndBlocking(fingerprintValueService, value)
        assertThat(serialized.getFutureToBlockWritesOn()).isNull()

        // Deserialization always returns a future because there is a Skyframe lookup. The future is
        // always done because there are no shared values to wait on.
        val continuation: SkyframeLookupContinuation? =
            com.google.common.util.concurrent.Futures.getDone(
                codecs.deserializeWithSkyframe(
                    fingerprintValueService, serialized.getObject()
                ) as com.google.common.util.concurrent.ListenableFuture<*>?
            ) as SkyframeLookupContinuation?

        if (missingValue) {
            // The continuation must resume, returning null, because the value is not in the injected
            // Skyframe environment.
            Truth.assertThat(
                processWithEntries(
                    continuation,
                    com.google.common.collect.ImmutableMap.of<SkyKey?, Any?>()
                )
            ).isNull()
        }

        if (injectSkyframeError) {
            val error: java.lang.Exception = java.lang.Exception("error")
            val thrown: SkyframeDependencyException? =
                org.junit.Assert.assertThrows<T?>(
                    SkyframeDependencyException::class.java,
                    org.junit.function.ThrowingRunnable {
                        processWithEntries(
                            continuation,
                            com.google.common.collect.ImmutableMap.of<K?, Any?>(key, error)
                        )
                    })
            assertThat(thrown).hasCauseThat().isSameInstanceAs(error)
        } else {
            // Injects the key-value pair into the environment. The continuation produces a result.
            val futureValue: com.google.common.util.concurrent.ListenableFuture<*>? =
                processWithEntries(continuation, com.google.common.collect.ImmutableMap.of<K?, Any?>(key, value))
            // The result is done because there are no shared values.
            Truth.assertThat(com.google.common.util.concurrent.Futures.getDone(futureValue)).isSameInstanceAs(value)
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun serializeWithTwoSkyKeys() {
        val key1: ExampleKey = ExampleKey("key1")
        val value1: ExampleValue = ExampleValue(key1, 10)
        val key2: ExampleKey = ExampleKey("key2")
        val value2: ExampleValue = ExampleValue(key2, 20)

        val value: com.google.common.collect.ImmutableList<ExampleValue?> =
            com.google.common.collect.ImmutableList.of<ExampleValue?>(value1, value2)

        val serialized: SerializationResult<ByteString?> =
            codecs.serializeMemoizedAndBlocking(fingerprintValueService, value)
        assertThat(serialized.getFutureToBlockWritesOn()).isNull()

        // Deserialization always returns a future because there is a Skyframe lookup. The future is
        // always done because there are no shared values to wait on.
        val continuation: SkyframeLookupContinuation? =
            com.google.common.util.concurrent.Futures.getDone(
                codecs.deserializeWithSkyframe(
                    fingerprintValueService, serialized.getObject()
                ) as com.google.common.util.concurrent.ListenableFuture<*>?
            ) as SkyframeLookupContinuation?

        // Evaluates the continuation as if `key1` is already present but `key2` is missing. It returns
        // null, requesting a restart.
        Truth.assertThat(
            processWithEntries(
                continuation,
                com.google.common.collect.ImmutableMap.of<K?, Any?>(key1, value1)
            )
        ).isNull()

        val futureValue: com.google.common.util.concurrent.ListenableFuture<*>? =
            processWithEntries(
                continuation,
                com.google.common.collect.ImmutableMap.of<K?, Any?>(key1, value1, key2, value2)
            )
        // The future is done because there are no shared values.
        Truth.assertThat(com.google.common.util.concurrent.Futures.getDone(futureValue)).isEqualTo(value)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun serializeInSharedValue(@TestParameter fetchError: Boolean) {
        val key: ExampleKey = ExampleKey("test")
        val value: ExampleValue = ExampleValue(key, 1)
        val sharedValue = SharedExampleValue(value)

        val serialized: SerializationResult<ByteString?> =
            codecs.serializeMemoizedAndBlocking(fingerprintValueService, sharedValue)
        val writeStatus: com.google.common.util.concurrent.ListenableFuture<*> = serialized.getFutureToBlockWritesOn()
        writeStatus.get() // ensures that the future succeeds

        val futureResult: com.google.common.util.concurrent.ListenableFuture<*> =
            codecs.deserializeWithSkyframe(
                fingerprintValueService,
                serialized.getObject()
            ) as com.google.common.util.concurrent.ListenableFuture<*>
        Truth.assertThat(futureResult.isDone()).isFalse() // not done because the fetch is blocked

        val request: GetRequest = recordingStore.takeFirstRequest()
        if (fetchError) {
            val error: IOException = IOException()
            request.response().setException(error)
            val thrown: ExecutionException? = org.junit.Assert.assertThrows<ExecutionException?>(
                ExecutionException::class.java,
                org.junit.function.ThrowingRunnable { futureResult.get() })
            Truth.assertThat(thrown).hasCauseThat().isSameInstanceAs(error)
        } else {
            request.complete() // completes the fetch
            val continuation: SkyframeLookupContinuation = futureResult.get() as SkyframeLookupContinuation
            val futureValue: com.google.common.util.concurrent.ListenableFuture<*>? =
                processWithEntries(continuation, com.google.common.collect.ImmutableMap.of<K?, Any?>(key, value))
            // There may be a small amount of bookkeeping work in the shared values deserialization
            // threads so the following call may block.
            Truth.assertThat(futureValue.get()).isEqualTo(sharedValue)
        }
    }

    /**
     * Error scenarios for [.serializeWithCrossValueSharing].
     * 
     * 
     * In this scenario, there are two concurrent deserializations, one for `subject0` and
     * one for `subject1` and they share a value. The test is arranged so that deserialization
     * of `subject0` owns the shared value. The scenarios here exercise the propagation of the
     * error from `subject0` to `subject1`.
     */
    internal enum class CrossError {
        NO_ERROR,

        /** Error occurs in [FingerprintValueStore.get].  */
        FETCH_ERROR,

        /** Error occurs in a Skyframe lookup.  */
        SKY_VALUE_ERROR
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun serializeWithCrossValueSharing(@TestParameter crossError: CrossError) {
        val key0: ExampleKey = ExampleKey("key0")
        val value0: ExampleValue = ExampleValue(key0, 1)
        val sharedValue0 = SharedExampleValue(value0)

        val key1: ExampleKey = ExampleKey("key1")
        val value1: ExampleValue = ExampleValue(key1, 3)
        val sharedValue1 = SharedExampleValue(value1)

        val subject0 = sharedValue0
        val subject1: com.google.common.collect.ImmutableList<SharedExampleValue?> =
            com.google.common.collect.ImmutableList.of<SharedExampleValue?>(sharedValue0, sharedValue1)

        val serializedBytes: java.util.ArrayList<ByteString?> = java.util.ArrayList<ByteString?>()
        for (sharedValue in com.google.common.collect.ImmutableList.of<Any?>(subject0, subject1)) {
            val serialized: SerializationResult<ByteString?> =
                codecs.serializeMemoizedAndBlocking(fingerprintValueService, sharedValue)
            val writeStatus: com.google.common.util.concurrent.ListenableFuture<*> =
                serialized.getFutureToBlockWritesOn()
            writeStatus.get() // ensures that the future succeeds
            serializedBytes.add(serialized.getObject())
        }

        // Deserializing subject0 first makes futureResult0 own deserialization of value0.
        val futureResult0: com.google.common.util.concurrent.ListenableFuture<*> =
            codecs.deserializeWithSkyframe(
                fingerprintValueService,
                serializedBytes.get(0)
            ) as com.google.common.util.concurrent.ListenableFuture<*>
        // As subject1 deserializes, it'll try to deserialize value0 but see that its deserialization is
        // already owned by another thread.
        val futureResult1: com.google.common.util.concurrent.ListenableFuture<*> =
            codecs.deserializeWithSkyframe(
                fingerprintValueService,
                serializedBytes.get(1)
            ) as com.google.common.util.concurrent.ListenableFuture<*>

        val getRequest0: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            recordingStore.takeFirstRequest()
        val getRequest1: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            recordingStore.takeFirstRequest()

        // Completes the fetch associated with sharedValue1, but leaves sharedValue0 blocked.
        getRequest1.complete()
        // This allows futureResult1 to progress to its continuation. Although value0 is blocked, it is
        // owned by futureResult0 and doesn't interfere with futureResult1's continuation.
        val continuation1: SkyframeLookupContinuation = futureResult1.get() as SkyframeLookupContinuation
        // On the other hand, futureResult0 is always blocked here because getRequest0 is blocked.
        Truth.assertThat(futureResult0.isDone()).isFalse()

        val futureValue1: com.google.common.util.concurrent.ListenableFuture<*>? =
            processWithEntries(continuation1, com.google.common.collect.ImmutableMap.of<K?, Any?>(key1, value1))
        // key1, value1 is the only Skyframe entry needed, so no restart. value0 is being deserialized
        // concurrently under futureResult0.
        Truth.assertThat(futureValue1).isNotNull()

        when (crossError) {
            CrossError.NO_ERROR -> {
                // Unblocks the remaining blocked fetch, enabling the next step of deserialization of
                // value0.
                getRequest0.complete()
                val continuation0: SkyframeLookupContinuation = futureResult0.get() as SkyframeLookupContinuation

                Truth.assertThat(futureValue1.isDone()).isFalse() // subject1 is still blocked on value0

                val futureValue0: com.google.common.util.concurrent.ListenableFuture<*>? =
                    processWithEntries(continuation0, com.google.common.collect.ImmutableMap.of<K?, Any?>(key0, value0))
                Truth.assertThat(futureValue0).isNotNull() // continuation0 only requires key0, value0.

                // Deserialization is fully unblocked.
                Truth.assertThat(
                    com.google.common.util.concurrent.Futures.allAsList<Any?>(futureValue0, futureValue1).get()
                )
                    .containsExactly(subject0, subject1)
                    .inOrder()
            }

            CrossError.FETCH_ERROR -> {
                val fetchError: IOException = IOException()
                getRequest0.response().setException(fetchError)
                val thrown0: ExecutionException? = org.junit.Assert.assertThrows<ExecutionException?>(
                    ExecutionException::class.java,
                    org.junit.function.ThrowingRunnable { futureResult0.get() })
                Truth.assertThat(thrown0).hasCauseThat().isSameInstanceAs(fetchError)

                val thrown1: ExecutionException? = org.junit.Assert.assertThrows<ExecutionException?>(
                    ExecutionException::class.java,
                    org.junit.function.ThrowingRunnable { futureValue1.get() })
                Truth.assertThat(thrown1).hasCauseThat().isSameInstanceAs(fetchError)
            }

            CrossError.SKY_VALUE_ERROR -> {
                getRequest0.complete()
                val continuation0: SkyframeLookupContinuation = futureResult0.get() as SkyframeLookupContinuation

                val skyValueError: java.lang.Exception = java.lang.Exception("failed")
                val thrown0: T? =
                    org.junit.Assert.assertThrows<T?>(
                        SkyframeDependencyException::class.java,
                        org.junit.function.ThrowingRunnable {
                            processWithEntries(
                                continuation0,
                                com.google.common.collect.ImmutableMap.of<K?, Any?>(key0, skyValueError)
                            )
                        })
                assertThat(thrown0).hasCauseThat().isSameInstanceAs(skyValueError)

                val thrown1: ExecutionException = org.junit.Assert.assertThrows<ExecutionException>(
                    ExecutionException::class.java,
                    org.junit.function.ThrowingRunnable { futureValue1.get() })
                Truth.assertThat(com.google.common.base.Throwables.getRootCause(thrown1))
                    .isSameInstanceAs(skyValueError)
            }
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun randomNestedData(@TestParameter("10", "20", "50") size: Int) {
        // Doesn't use the GetRecordingStore because it is too complex to use in this test.
        this.fingerprintValueService = FingerprintValueService.createForTesting()

        val random: Random = Random(0)
        val entries: HashMap<SkyKey?, Any?> = HashMap<SkyKey?, Any?>()
        val subject: NotNestedSet =
            NotNestedSet.Companion.createRandom(
                random,
                size,
                size,
                java.util.function.Function { rng: Random? ->
                    val key: ExampleKey = ExampleKey(java.lang.Integer.toString(rng.nextInt(1000)))
                    var value: Any? = entries.get(key)
                    if (value != null) {
                        return@createRandom value
                    }
                    value = ExampleValue(key, rng.nextInt())
                    entries.put(key, value)
                    value
                })

        val serialized: SerializationResult<ByteString?> =
            codecs.serializeMemoizedAndBlocking(fingerprintValueService, subject)
        val writeStatus: com.google.common.util.concurrent.ListenableFuture<*> = serialized.getFutureToBlockWritesOn()
        writeStatus.get() // ensures that the future succeeds

        val futureResult: com.google.common.util.concurrent.ListenableFuture<*> =
            codecs.deserializeWithSkyframe(
                fingerprintValueService,
                serialized.getObject()
            ) as com.google.common.util.concurrent.ListenableFuture<*>

        val continuation: SkyframeLookupContinuation = futureResult.get() as SkyframeLookupContinuation
        val futureValue: com.google.common.util.concurrent.ListenableFuture<*>? =
            processWithEntries(continuation, entries)

        assertThat(dumpStructureWithEquivalenceReduction(futureValue.get()))
            .isEqualTo(dumpStructureWithEquivalenceReduction(subject))
    }

    private class SharedExampleValue(value: ExampleValue?) {
        val value: ExampleValue?

        init {
            this.value = value
        }
    }

    @com.google.errorprone.annotations.Keep // used reflectively
    private class SharedExampleValueCodec

        : DeferredObjectCodec<SharedExampleValue?>() {
        val encodedClass: java.lang.Class<SharedExampleValue?>
            get() = SharedExampleValue::class.java

        @Throws(SerializationException::class, IOException::class)
        public override fun serialize(
            context: SerializationContext, value: SharedExampleValue, codedOut: CodedOutputStream?
        ) {
            context.putSharedValue(
                value.value,  /* distinguisher= */null, ExampleValue.Companion.exampleValueCodec(), codedOut
            )
        }

        @Throws(SerializationException::class, IOException::class)
        public override fun deserializeDeferred(
            context: AsyncDeserializationContext, codedIn: CodedInputStream?
        ): DeferredValue<SharedExampleValue?> {
            val builder = SharedExampleValueBuilder()
            context.getSharedValue(
                codedIn,  /* distinguisher= */
                null,
                ExampleValue.Companion.exampleValueCodec(),
                builder,
                { builder: SharedExampleValueBuilder, obj: Any? ->
                    SharedExampleValueBuilder.Companion.setValue(
                        builder,
                        obj
                    )
                })
            return builder
        }
    }

    private class SharedExampleValueBuilder

        : DeferredValue<SharedExampleValue?> {
        private var value: ExampleValue? = null

        public override fun call(): SharedExampleValue {
            return SharedExampleValue(value)
        }

        companion object {
            private fun setValue(builder: SharedExampleValueBuilder, obj: Any?) {
                builder.value = obj as ExampleValue?
            }
        }
    }

    companion object {
        @Throws(java.lang.InterruptedException::class, SkyframeDependencyException::class)
        private fun processWithEntries(
            continuation: SkyframeLookupContinuation, entries: MutableMap<SkyKey?, Any?>
        ): com.google.common.util.concurrent.ListenableFuture<*>? {
            return continuation.process(EnvironmentForUtilities({ key: Any? -> entries.get(key) }))
        }
    }
}
