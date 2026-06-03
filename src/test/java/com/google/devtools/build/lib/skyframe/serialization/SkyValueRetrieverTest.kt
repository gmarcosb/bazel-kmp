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

import com.google.devtools.build.lib.skyframe.serialization.DependOnFutureShim.ObservedFutureStatus.DONE

@RunWith(TestParameterInjector::class)
class SkyValueRetrieverTest {
    private var codecs: ObjectCodecs = ObjectCodecs()


    private enum class InitialQueryCases {
        IMMEDIATE_EMPTY_VALUE,
        IMMEDIATE_MISSING_VALUE,
        FUTURE_VALUE
    }


    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun initialQueryState_withAnalysisCacheService_progressesToWaiting(
        @TestParameter testCase: InitialQueryCases
    ) {
        val fingerprintValueService: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            FingerprintValueService.createForAnalysisCacheTesting()
        val data: HashMap<ByteString?, ByteString> = HashMap<ByteString?, ByteString>()
        val captured: java.util.ArrayList<com.google.common.util.concurrent.SettableFuture<LookupResult?>?> =
            java.util.ArrayList<com.google.common.util.concurrent.SettableFuture<LookupResult?>?>()
        val analysisCacheClient: RemoteAnalysisCacheClient =
            when (testCase) {
                InitialQueryCases.IMMEDIATE_EMPTY_VALUE, InitialQueryCases.IMMEDIATE_MISSING_VALUE -> createFakeAnalysisCacheClient(
                    data
                )

                InitialQueryCases.FUTURE_VALUE -> createCapturingAnalysisCacheClient(java.util.function.Consumer { e: com.google.common.util.concurrent.SettableFuture<LookupResult?>? ->
                    captured.add(
                        e
                    )
                })
            }

        val key: TrivialKey = com.google.devtools.build.lib.skyframe.serialization.SkyValueRetrieverTest.TrivialKey("a")
        val keyBytes: SerializationResult<ByteString?> =
            codecs.serializeMemoizedAndBlocking(fingerprintValueService, key)
        assertThat(keyBytes.getFutureToBlockWritesOn()).isNull()

        if (testCase == InitialQueryCases.IMMEDIATE_EMPTY_VALUE) {
            assertThat(
                fingerprintValueService
                    .put(fingerprintValueService.fingerprint(keyBytes.getObject()), ByteArray(0))
                    .get()
            )
                .isTrue()
        }

        val state: RetrievalContext = RetrievalContext()

        var result: RetrievalResult =
            SkyValueRetriever.tryRetrieve(
                NO_LOOKUP_ENVIRONMENT,
                { future: com.google.common.util.concurrent.ListenableFuture<*> -> dependOnFutureImpl(future) },
                codecs,
                fingerprintValueService,
                analysisCacheClient,
                key,
                state,  /* frontierNodeVersion= */
                CONSTANT_FOR_TESTING
            )

        if (testCase == InitialQueryCases.FUTURE_VALUE) {
            assertThat(state.getState()).isInstanceOf(WaitingForCacheServiceResponse::class.java)
            assertThat(result).isEqualTo(RESTART)
        } else {
            result =
                maybeWaitForAnalysisCacheService(
                    fingerprintValueService, analysisCacheClient, state, key, result
                )
            assertThat(state.getState()).isInstanceOf(NoCachedData::class.java)
            assertThat((result as NoCachedData).reason()).isEqualTo(MissReason.MISS_REASON_SKYVALUE_MISS)
        }
    }


    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun waitingForCacheServiceResponse_returnsValue() {
        val fingerprintValueService: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            FingerprintValueService.createForAnalysisCacheTesting()
        val analysisCacheServiceData: HashMap<ByteString?, ByteString> = HashMap<ByteString?, ByteString>()
        val state: RetrievalContext = RetrievalContext()
        val analysisCacheClient: RemoteAnalysisCacheClient =
            createFakeAnalysisCacheClient(analysisCacheServiceData)

        val key: TrivialKey = com.google.devtools.build.lib.skyframe.serialization.SkyValueRetrieverTest.TrivialKey("a")
        val value = TrivialValue("abc")

        uploadKeyValuePair(key, value, fingerprintValueService, analysisCacheServiceData)

        var result: RetrievalResult =
            SkyValueRetriever.tryRetrieve(
                NO_LOOKUP_ENVIRONMENT,
                { future: com.google.common.util.concurrent.ListenableFuture<*> -> dependOnFutureImpl(future) },
                codecs,
                fingerprintValueService,
                analysisCacheClient,
                key,
                state,  /* frontierNodeVersion= */
                CONSTANT_FOR_TESTING
            )

        result =
            maybeWaitForAnalysisCacheService(
                fingerprintValueService, analysisCacheClient, state, key, result
            )

        assertThat((result as RetrievedValue).value()).isEqualTo(value)
        assertThat(state.getState()).isInstanceOf(RetrievedValue::class.java)
    }

    @Throws(SerializationException::class, ExecutionException::class, java.lang.InterruptedException::class)
    private fun maybeWaitForAnalysisCacheService(
        fingerprintValueService: FingerprintValueService?,
        analysisCacheClient: RemoteAnalysisCacheClient?,
        state: RetrievalContext,
        key: SkyKey?,
        previousResult: RetrievalResult
    ): RetrievalResult {
        if (state.getState()
                    is
                ) {
            // There's a race condition here due to the RequestBatcher's response handling executor.
            // Most of the time, the test thread will outrace the executor and require a restart, but
            // RequestBatcher could occasionally outrace this thread.

            // Waits for the future to complete and simulates a restart.

            val unused: LookupResult? = futureResult.get()
            return SkyValueRetriever.tryRetrieve(
                NO_LOOKUP_ENVIRONMENT,
                { future: com.google.common.util.concurrent.ListenableFuture<*> -> dependOnFutureImpl(future) },
                codecs,
                fingerprintValueService,
                analysisCacheClient,
                key,
                state,  /* frontierNodeVersion= */
                CONSTANT_FOR_TESTING
            )
        }
        return previousResult
    }


    @Throws(java.lang.Exception::class)
    private fun fingerprintObject(
        fingerprintValueService: FingerprintValueService, o: Any?
    ): PackedFingerprint {
        val codec:  // codec() returns ObjectCodec<?>
                ObjectCodec<Any?> =
            codecs.getCodecRegistry().getCodecDescriptorForObject(o).codec() as ObjectCodec<Any?>
        val outputStream: java.io.ByteArrayOutputStream = java.io.ByteArrayOutputStream()
        outputStream.write(0.toByte().toInt()) // Assume the object is not compressed, see maybeCompressBytes()
        val cos: CodedOutputStream = CodedOutputStream.newInstance(outputStream)
        codec.serialize(codecs.getSerializationContextForTesting(), o, cos)
        cos.flush()
        return fingerprintValueService.fingerprint(outputStream.toByteArray())
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun missingReferencedValue_resultsInObjectMiss() {
        val store: InMemoryFingerprintValueStore = InMemoryFingerprintValueStore(true)
        val fingerprintValueService: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            FingerprintValueService.createForTesting(store)
        val analysisCacheServiceData: HashMap<ByteString?, ByteString> = HashMap<ByteString?, ByteString>()
        val state: RetrievalContext = RetrievalContext()
        val analysisCacheClient: RemoteAnalysisCacheClient =
            createFakeAnalysisCacheClient(analysisCacheServiceData)

        val codec = ValueWithReferenceCodec()
        codecs = codecs.withCodecOverridesForTesting(com.google.common.collect.ImmutableList.of<E?>(codec))

        val key: TrivialKey = com.google.devtools.build.lib.skyframe.serialization.SkyValueRetrieverTest.TrivialKey("a")
        val v1 = ValueWithReference(1, null)
        val v2 = ValueWithReference(2, v1)
        val v3 = ValueWithReference(3, v2)
        val v4 = ValueWithReference(4, v3)

        val skyValueFingerprint: PackedFingerprint =
            uploadKeyValuePair(key, v4, fingerprintValueService, analysisCacheServiceData)
        val v1Fingerprint: PackedFingerprint = fingerprintObject(fingerprintValueService, v1)

        store.remove(v1Fingerprint)
        val e: SerializationException =
            org.junit.Assert.assertThrows<T>(
                SerializationException::class.java,
                org.junit.function.ThrowingRunnable {
                    SkyValueRetriever.tryRetrieve(
                        NO_LOOKUP_ENVIRONMENT,
                        { future: com.google.common.util.concurrent.ListenableFuture<*> ->
                            alwaysDoneDependOnFuture(
                                future
                            )
                        },
                        codecs,
                        fingerprintValueService,
                        analysisCacheClient,
                        key,
                        state,  /* frontierNodeVersion= */
                        CONSTANT_FOR_TESTING
                    )
                })
        assertThat(e.getReason()).isEqualTo(MissReason.MISS_REASON_REFERENCED_OBJECT_MISS)

        // Also check just in case that if we remove the SkyValue entry, we get a SKYVALUE_MISS
        store.remove(skyValueFingerprint)
        analysisCacheServiceData.remove(ByteString.copyFrom(skyValueFingerprint.toBytes()))
        val state2: RetrievalContext = RetrievalContext()
        val result: RetrievalResult =
            SkyValueRetriever.tryRetrieve(
                NO_LOOKUP_ENVIRONMENT,
                { future: com.google.common.util.concurrent.ListenableFuture<*> -> alwaysDoneDependOnFuture(future) },
                codecs,
                fingerprintValueService,
                analysisCacheClient,
                key,
                state2,  /* frontierNodeVersion= */
                CONSTANT_FOR_TESTING
            )
        assertThat((result as NoCachedData).reason()).isEqualTo(MissReason.MISS_REASON_SKYVALUE_MISS)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun tryRetrieve_withoutRestarts_returnsValue() {
        val fingerprintValueService: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            FingerprintValueService.createForAnalysisCacheTesting()
        val analysisCacheServiceData: HashMap<ByteString?, ByteString> = HashMap<ByteString?, ByteString>()
        val state: RetrievalContext = RetrievalContext()
        val analysisCacheClient: RemoteAnalysisCacheClient =
            createFakeAnalysisCacheClient(analysisCacheServiceData)

        codecs =
            codecs.withCodecOverridesForTesting(com.google.common.collect.ImmutableList.of<E?>(TrivialValueSharingCodec()))

        val key: TrivialKey = com.google.devtools.build.lib.skyframe.serialization.SkyValueRetrieverTest.TrivialKey("a")
        val value = TrivialValue("abc")
        uploadKeyValuePair(key, value, fingerprintValueService, analysisCacheServiceData)

        val result: RetrievalResult =
            SkyValueRetriever.tryRetrieve(
                NO_LOOKUP_ENVIRONMENT,
                { future: com.google.common.util.concurrent.ListenableFuture<*> -> alwaysDoneDependOnFuture(future) },
                codecs,
                fingerprintValueService,
                analysisCacheClient,
                key,
                state,  /* frontierNodeVersion= */
                CONSTANT_FOR_TESTING
            )

        assertThat((result as RetrievedValue).value()).isEqualTo(value)
    }


    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun tryRetrieve_withSkyframeRestart_completes() {
        val fingerprintValueService: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            FingerprintValueService.createForAnalysisCacheTesting()
        val analysisCacheServiceData: HashMap<ByteString?, ByteString> = HashMap<ByteString?, ByteString>()
        val state: RetrievalContext = RetrievalContext()
        val analysisCacheClient: RemoteAnalysisCacheClient =
            createFakeAnalysisCacheClient(analysisCacheServiceData)

        val key: ExampleKey = ExampleKey("a")
        val value: ExampleValue = ExampleValue(key, 10)
        uploadKeyValuePair(key, value, fingerprintValueService, analysisCacheServiceData)

        val capturedKey: Array<SkyKey?> = arrayOfNulls<SkyKey>(1)

        var result: RetrievalResult =
            SkyValueRetriever.tryRetrieve(
                EnvironmentForUtilities(
                    { k ->
                        assertThat(capturedKey[0]).isNull()
                        capturedKey[0] = k
                        null
                    }),
                { future: com.google.common.util.concurrent.ListenableFuture<*> -> alwaysDoneDependOnFuture(future) },
                codecs,
                fingerprintValueService,
                analysisCacheClient,
                key,
                state,  /* frontierNodeVersion= */
                CONSTANT_FOR_TESTING
            )

        assertThat(result).isEqualTo(RESTART)
        assertThat(capturedKey[0]).isEqualTo(key)
        assertThat(state.getState()).isInstanceOf(WaitingForLookupContinuation::class.java)

        result =
            SkyValueRetriever.tryRetrieve(
                EnvironmentForUtilities(
                    { k ->
                        assertThat(k).isEqualTo(key)
                        value
                    }),
                { future: com.google.common.util.concurrent.ListenableFuture<*> -> alwaysDoneDependOnFuture(future) },
                codecs,
                fingerprintValueService,
                analysisCacheClient,
                key,
                state,  /* frontierNodeVersion= */
                CONSTANT_FOR_TESTING
            )

        assertThat((result as RetrievedValue).value()).isEqualTo(value)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun retrievalError_throwsException() {
        val fingerprintValueService: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            FingerprintValueService.createForAnalysisCacheTesting()
        val state: RetrievalContext = RetrievalContext()
        val captured: java.util.ArrayList<com.google.common.util.concurrent.SettableFuture<LookupResult?>?> =
            java.util.ArrayList<com.google.common.util.concurrent.SettableFuture<LookupResult?>?>()
        val analysisCacheClient: RemoteAnalysisCacheClient =
            createCapturingAnalysisCacheClient(java.util.function.Consumer { e: com.google.common.util.concurrent.SettableFuture<LookupResult?>? ->
                captured.add(
                    e
                )
            })

        val key: TrivialKey = com.google.devtools.build.lib.skyframe.serialization.SkyValueRetrieverTest.TrivialKey("a")

        val result: RetrievalResult? =
            SkyValueRetriever.tryRetrieve(
                NO_LOOKUP_ENVIRONMENT,
                { future: com.google.common.util.concurrent.ListenableFuture<*> -> dependOnFutureImpl(future) },
                codecs,
                fingerprintValueService,
                analysisCacheClient,
                key,
                state,  /* frontierNodeVersion= */
                CONSTANT_FOR_TESTING
            )

        assertThat(result).isEqualTo(RESTART)
        assertThat(state.getState()).isInstanceOf(WaitingForCacheServiceResponse::class.java)
        Truth.assertThat(captured).hasSize(1)

        val error: IOException = IOException()
        captured.get(0).setException(error)

        val thrown: T? =
            org.junit.Assert.assertThrows<T?>(
                SerializationException::class.java,
                org.junit.function.ThrowingRunnable {
                    SkyValueRetriever.tryRetrieve(
                        NO_LOOKUP_ENVIRONMENT,
                        { future: com.google.common.util.concurrent.ListenableFuture<*> -> dependOnFutureImpl(future) },
                        codecs,
                        fingerprintValueService,
                        analysisCacheClient,
                        key,
                        state,  /* frontierNodeVersion= */
                        CONSTANT_FOR_TESTING
                    )
                })

        assertThat(thrown).hasMessageThat().contains("getting cache response for " + key)
        assertThat(thrown).hasCauseThat().hasCauseThat().isSameInstanceAs(error)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun retrievalCancelled_returnsNoCachedData() {
        val fingerprintValueService: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            FingerprintValueService.createForAnalysisCacheTesting()
        val state: RetrievalContext = RetrievalContext()
        val captured: java.util.ArrayList<com.google.common.util.concurrent.SettableFuture<LookupResult?>?> =
            java.util.ArrayList<com.google.common.util.concurrent.SettableFuture<LookupResult?>?>()
        val analysisCacheClient: RemoteAnalysisCacheClient =
            createCapturingAnalysisCacheClient(java.util.function.Consumer { e: com.google.common.util.concurrent.SettableFuture<LookupResult?>? ->
                captured.add(
                    e
                )
            })

        val key: TrivialKey = com.google.devtools.build.lib.skyframe.serialization.SkyValueRetrieverTest.TrivialKey("a")

        var result: RetrievalResult =
            SkyValueRetriever.tryRetrieve(
                NO_LOOKUP_ENVIRONMENT,
                { future: com.google.common.util.concurrent.ListenableFuture<*> -> dependOnFutureImpl(future) },
                codecs,
                fingerprintValueService,
                analysisCacheClient,
                key,
                state,  /* frontierNodeVersion= */
                CONSTANT_FOR_TESTING
            )

        assertThat(result).isEqualTo(RESTART)
        assertThat(state.getState()).isInstanceOf(WaitingForCacheServiceResponse::class.java)
        Truth.assertThat(captured).hasSize(1)

        captured.get(0).cancel(false)

        result =
            SkyValueRetriever.tryRetrieve(
                NO_LOOKUP_ENVIRONMENT,
                { future: com.google.common.util.concurrent.ListenableFuture<*> -> dependOnFutureImpl(future) },
                codecs,
                fingerprintValueService,
                analysisCacheClient,
                key,
                state,  /* frontierNodeVersion= */
                CONSTANT_FOR_TESTING
            )

        assertThat(result).isInstanceOf(NoCachedData::class.java)
        assertThat(state.getState()).isInstanceOf(NoCachedData::class.java)
        assertThat((result as NoCachedData).reason()).isEqualTo(MissReason.MISS_REASON_UNSPECIFIED)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun skyframeLookupError_throwsException() {
        val fingerprintValueService: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            FingerprintValueService.createForAnalysisCacheTesting()
        val analysisCacheServiceData: HashMap<ByteString?, ByteString> = HashMap<ByteString?, ByteString>()
        val state: RetrievalContext = RetrievalContext()
        val analysisCacheClient: RemoteAnalysisCacheClient =
            createFakeAnalysisCacheClient(analysisCacheServiceData)

        val key: ExampleKey = ExampleKey("a")
        val value: ExampleValue = ExampleValue(key, 10)
        uploadKeyValuePair(key, value, fingerprintValueService, analysisCacheServiceData)

        val capturedKey: Array<SkyKey?> = arrayOfNulls<SkyKey>(1)

        val result: RetrievalResult? =
            SkyValueRetriever.tryRetrieve(
                EnvironmentForUtilities(
                    { k ->
                        assertThat(capturedKey[0]).isNull()
                        capturedKey[0] = k
                        null
                    }),
                { future: com.google.common.util.concurrent.ListenableFuture<*> -> alwaysDoneDependOnFuture(future) },
                codecs,
                fingerprintValueService,
                analysisCacheClient,
                key,
                state,  /* frontierNodeVersion= */
                CONSTANT_FOR_TESTING
            )

        assertThat(result).isEqualTo(RESTART)
        assertThat(capturedKey[0]).isEqualTo(key)
        assertThat(state.getState()).isInstanceOf(WaitingForLookupContinuation::class.java)

        val error: java.lang.Exception = java.lang.Exception()

        val thrown: T? =
            org.junit.Assert.assertThrows<T?>(
                SerializationException::class.java,
                org.junit.function.ThrowingRunnable {
                    SkyValueRetriever.tryRetrieve(
                        EnvironmentForUtilities(
                            { k ->
                                assertThat(k).isEqualTo(key)
                                error
                            }),
                        { future: com.google.common.util.concurrent.ListenableFuture<*> ->
                            alwaysDoneDependOnFuture(
                                future
                            )
                        },
                        codecs,
                        fingerprintValueService,
                        analysisCacheClient,
                        key,
                        state,  /* frontierNodeVersion= */
                        CONSTANT_FOR_TESTING
                    )
                })

        assertThat(thrown)
            .hasMessageThat()
            .contains("skyframe dependency error during deserialization for " + key)
        assertThat(thrown).hasCauseThat().isInstanceOf(SkyframeDependencyException::class.java)
        assertThat(thrown).hasCauseThat().hasCauseThat().isSameInstanceAs(error)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun skyframeLookupError_marksOtherLookupsAbandoned() {
        val fingerprintValueService: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            FingerprintValueService.createForAnalysisCacheTesting()
        val analysisCacheServiceData: HashMap<ByteString?, ByteString> = HashMap<ByteString?, ByteString>()
        val state: RetrievalContext = RetrievalContext()
        val analysisCacheClient: RemoteAnalysisCacheClient =
            createFakeAnalysisCacheClient(analysisCacheServiceData)

        val key: TrivialKey = com.google.devtools.build.lib.skyframe.serialization.SkyValueRetrieverTest.TrivialKey("a")

        val lookupKey0: ExampleKey = ExampleKey("a")
        val lookupKey1: ExampleKey = ExampleKey("b")
        val multiLookupValue =
            MultiLookupValue(ExampleValue(lookupKey0, 3), ExampleValue(lookupKey1, 5))
        uploadKeyValuePair(key, multiLookupValue, fingerprintValueService, analysisCacheServiceData)

        val capturedKeys: java.util.ArrayList<SkyKey?> = java.util.ArrayList<SkyKey?>()

        val result: RetrievalResult? =
            SkyValueRetriever.tryRetrieve(
                EnvironmentForUtilities(
                    { k ->
                        capturedKeys.add(k)
                        null
                    }),
                { future: com.google.common.util.concurrent.ListenableFuture<*> -> alwaysDoneDependOnFuture(future) },
                codecs,
                fingerprintValueService,
                analysisCacheClient,
                key,
                state,  /* frontierNodeVersion= */
                CONSTANT_FOR_TESTING
            )

        assertThat(result).isEqualTo(RESTART)
        Truth.assertThat(capturedKeys).containsExactly(lookupKey0, lookupKey1).inOrder()
        assertThat(state.getState()).isInstanceOf(WaitingForLookupContinuation::class.java)

        val lookups: com.google.common.collect.ImmutableList<out Any?> =
            com.google.common.collect.ImmutableList.copyOf(
                (state.getState() as WaitingForLookupContinuation)
                    .continuation()
                    .getSkyframeLookupsForTesting()
            )
        Truth.assertThat(lookups).hasSize(2)

        val error: java.lang.Exception = java.lang.Exception()
        val thrown: T? =
            org.junit.Assert.assertThrows<T?>(
                SerializationException::class.java,
                org.junit.function.ThrowingRunnable {
                    SkyValueRetriever.tryRetrieve(
                        EnvironmentForUtilities(
                            { k ->
                                assertThat(k).isEqualTo(lookupKey0)
                                error
                            }),
                        { future: com.google.common.util.concurrent.ListenableFuture<*> ->
                            alwaysDoneDependOnFuture(
                                future
                            )
                        },
                        codecs,
                        fingerprintValueService,
                        analysisCacheClient,
                        key,
                        state,  /* frontierNodeVersion= */
                        CONSTANT_FOR_TESTING
                    )
                })
        assertThat(thrown)
            .hasMessageThat()
            .contains("skyframe dependency error during deserialization for " + key)
        assertThat(thrown).hasCauseThat().isInstanceOf(SkyframeDependencyException::class.java)
        assertThat(thrown).hasCauseThat().hasCauseThat().isSameInstanceAs(error)

        val thrownByLookup0: Throwable? =
            org.junit.Assert.assertThrows<ExecutionException?>(ExecutionException::class.java, lookups.get(0)::get)
                .getCause()
        Truth.assertThat(thrownByLookup0).isInstanceOf(SkyframeDependencyException::class.java)
        Truth.assertThat(thrownByLookup0).hasCauseThat().isSameInstanceAs(error)

        val thrownByLookup1: Throwable? =
            org.junit.Assert.assertThrows<ExecutionException?>(ExecutionException::class.java, lookups.get(1)::get)
                .getCause()
        Truth.assertThat(thrownByLookup1).isInstanceOf(PeerFailedException::class.java)
        Truth.assertThat(thrownByLookup1).hasCauseThat().isSameInstanceAs(thrownByLookup0)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun exceptionWhileWaitingForResult_throwsException() {
        val fingerprintValueService: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            FingerprintValueService.createForAnalysisCacheTesting()
        val analysisCacheServiceData: HashMap<ByteString?, ByteString> = HashMap<ByteString?, ByteString>()
        val state: RetrievalContext = RetrievalContext()
        val analysisCacheClient: RemoteAnalysisCacheClient =
            createFakeAnalysisCacheClient(analysisCacheServiceData)

        codecs =
            codecs.withCodecOverridesForTesting(com.google.common.collect.ImmutableList.of<E?>(FaultyTrivialValueCodec()))

        val key: TrivialKey = com.google.devtools.build.lib.skyframe.serialization.SkyValueRetrieverTest.TrivialKey("k")
        val value = TrivialValue("v")
        uploadKeyValuePair(key, value, fingerprintValueService, analysisCacheServiceData)

        val thrown: T? =
            org.junit.Assert.assertThrows<T?>(
                SerializationException::class.java,
                org.junit.function.ThrowingRunnable {
                    SkyValueRetriever.tryRetrieve(
                        NO_LOOKUP_ENVIRONMENT,
                        { future: com.google.common.util.concurrent.ListenableFuture<*> ->
                            alwaysDoneDependOnFuture(
                                future
                            )
                        },
                        codecs,
                        fingerprintValueService,
                        analysisCacheClient,
                        key,
                        state,  /* frontierNodeVersion= */
                        CONSTANT_FOR_TESTING
                    )
                })

        assertThat(thrown).hasMessageThat().contains("waiting for deserialization result for " + key)
        assertThat(thrown).hasCauseThat().hasMessageThat().contains("error setting value")
    }

    @org.junit.Test
    fun frontierNodeVersions_areEqual_ifTupleComponentsAreEqual() {
        val first: FrontierNodeVersion =
            FrontierNodeVersion(
                "foo",
                com.google.common.hash.HashCode.fromInt(42),
                byteArrayOf(1, 2, 3),
                IntVersion.of(9000),
                "distinguisher",
                true,
                java.util.Optional.empty<T?>()
            )
        val second: FrontierNodeVersion =
            FrontierNodeVersion(
                "foo",
                com.google.common.hash.HashCode.fromInt(42),
                byteArrayOf(1, 2, 3),
                IntVersion.of(9000),
                "distinguisher",
                true,
                java.util.Optional.empty<T?>()
            )

        assertThat(first.getPrecomputedFingerprint()).isEqualTo(second.getPrecomputedFingerprint())
        assertThat(first).isEqualTo(second)
    }

    @org.junit.Test
    fun frontierNodeVersions_areNotEqual_ifTopLevelConfigChecksumIsDifferent() {
        val first: FrontierNodeVersion =
            FrontierNodeVersion(
                "foo",
                com.google.common.hash.HashCode.fromInt(42),
                byteArrayOf(1, 2, 3),
                IntVersion.of(9000),
                "distinguisher",
                true,
                java.util.Optional.empty<T?>()
            )
        val second: FrontierNodeVersion =
            FrontierNodeVersion(
                "CHANGED",
                com.google.common.hash.HashCode.fromInt(42),
                byteArrayOf(1, 2, 3),
                IntVersion.of(9000),
                "distinguisher",
                true,
                java.util.Optional.empty<T?>()
            )

        assertThat(first.getPrecomputedFingerprint()).isNotEqualTo(second.getPrecomputedFingerprint())
        assertThat(first).isNotEqualTo(second)
    }

    @org.junit.Test
    fun frontierNodeVersions_areNotEqual_ifBlazeInstallMD5IsDifferent() {
        val first: FrontierNodeVersion =
            FrontierNodeVersion(
                "foo",
                com.google.common.hash.HashCode.fromInt(42),
                byteArrayOf(1, 2, 3),
                IntVersion.of(9000),
                "distinguisher",
                true,
                java.util.Optional.empty<T?>()
            )
        val second: FrontierNodeVersion =
            FrontierNodeVersion(
                "foo",
                com.google.common.hash.HashCode.fromInt(9000),
                byteArrayOf(1, 2, 3),
                IntVersion.of(9000),
                "distinguisher",
                true,
                java.util.Optional.empty<T?>()
            )

        assertThat(first.getPrecomputedFingerprint()).isNotEqualTo(second.getPrecomputedFingerprint())
        assertThat(first).isNotEqualTo(second)
    }

    @org.junit.Test
    fun frontierNodeVersions_areNotEqual_ifStarlarkSemanticsIsDifferent() {
        val first: FrontierNodeVersion =
            FrontierNodeVersion(
                "foo",
                com.google.common.hash.HashCode.fromInt(42),
                byteArrayOf(1, 2, 3),
                IntVersion.of(9000),
                "distinguisher",
                true,
                java.util.Optional.empty<T?>()
            )
        val second: FrontierNodeVersion =
            FrontierNodeVersion(
                "foo",
                com.google.common.hash.HashCode.fromInt(42),
                byteArrayOf(4, 5, 6),
                IntVersion.of(9000),
                "distinguisher",
                true,
                java.util.Optional.empty<T?>()
            )

        assertThat(first.getPrecomputedFingerprint()).isNotEqualTo(second.getPrecomputedFingerprint())
        assertThat(first).isNotEqualTo(second)
    }

    @org.junit.Test
    fun frontierNodeVersions_areNotEqual_ifEvaluatingVersionIsDifferent() {
        val first: FrontierNodeVersion =
            FrontierNodeVersion(
                "foo",
                com.google.common.hash.HashCode.fromInt(42),
                byteArrayOf(1, 2, 3),
                IntVersion.of(9000),
                "distinguisher",
                true,
                java.util.Optional.empty<T?>()
            )
        val second: FrontierNodeVersion =
            FrontierNodeVersion(
                "foo",
                com.google.common.hash.HashCode.fromInt(42),
                byteArrayOf(1, 2, 3),
                IntVersion.of(10000),
                "distinguisher",
                true,
                java.util.Optional.empty<T?>()
            )

        assertThat(first.getPrecomputedFingerprint()).isNotEqualTo(second.getPrecomputedFingerprint())
        assertThat(first).isNotEqualTo(second)
    }

    @org.junit.Test
    fun frontierNodeVersions_areNotEqual_ifDistinguisherIsDifferent() {
        val first: FrontierNodeVersion =
            FrontierNodeVersion(
                "foo",
                com.google.common.hash.HashCode.fromInt(42),
                byteArrayOf(1, 2, 3),
                IntVersion.of(9000),
                "distinguisher",
                true,
                java.util.Optional.empty<T?>()
            )
        val second: FrontierNodeVersion =
            FrontierNodeVersion(
                "foo",
                com.google.common.hash.HashCode.fromInt(42),
                byteArrayOf(1, 2, 3),
                IntVersion.of(9000),
                "changed",
                true,
                java.util.Optional.empty<T?>()
            )
        assertThat(first.getPrecomputedFingerprint()).isNotEqualTo(second.getPrecomputedFingerprint())
        assertThat(first).isNotEqualTo(second)
    }

    @org.junit.Test
    fun frontierNodeVersions_areNotEqual_ifUseFakeStampDataIsDifferent() {
        val first: FrontierNodeVersion =
            FrontierNodeVersion(
                "foo",
                com.google.common.hash.HashCode.fromInt(42),
                byteArrayOf(1, 2, 3),
                IntVersion.of(9000),
                "distinguisher",
                true,
                java.util.Optional.empty<T?>()
            )
        val second: FrontierNodeVersion =
            FrontierNodeVersion(
                "foo",
                com.google.common.hash.HashCode.fromInt(42),
                byteArrayOf(1, 2, 3),
                IntVersion.of(9000),
                "distinguisher",
                false,
                java.util.Optional.empty<T?>()
            )
        assertThat(first.getPrecomputedFingerprint()).isNotEqualTo(second.getPrecomputedFingerprint())
        assertThat(first).isNotEqualTo(second)
    }

    @org.junit.Test
    fun frontierNodeVersions_areEqual_evenIfSnapshotIsDifferent() {
        val first: FrontierNodeVersion =
            FrontierNodeVersion(
                "foo",
                com.google.common.hash.HashCode.fromInt(42),
                byteArrayOf(1, 2, 3),
                IntVersion.of(9000),
                "distinguisher",
                true,
                java.util.Optional.of<T?>(SnapshotClientId("changed", 123))
            )
        val second: FrontierNodeVersion =
            FrontierNodeVersion(
                "foo",
                com.google.common.hash.HashCode.fromInt(42),
                byteArrayOf(1, 2, 3),
                IntVersion.of(9000),
                "distinguisher",
                true,
                java.util.Optional.empty<T?>()
            )

        assertThat(first.getPrecomputedFingerprint()).isEqualTo(second.getPrecomputedFingerprint())
        assertThat(first).isEqualTo(second)
    }

    @com.google.errorprone.annotations.CanIgnoreReturnValue
    @Throws(SerializationException::class, java.lang.InterruptedException::class, ExecutionException::class)
    private fun uploadKeyValuePair(
        key: SkyKey?,
        value: SkyValue?,
        fingerprintValueService: FingerprintValueService,
        analysisCacheServiceData: MutableMap<ByteString?, ByteString>?
    ): PackedFingerprint {
        return uploadKeyValuePair(
            key, CONSTANT_FOR_TESTING, value, fingerprintValueService, analysisCacheServiceData
        )
    }

    @com.google.errorprone.annotations.CanIgnoreReturnValue
    @Throws(SerializationException::class, java.lang.InterruptedException::class, ExecutionException::class)
    private fun uploadKeyValuePair(
        key: SkyKey?,
        version: FrontierNodeVersion,
        value: SkyValue?,
        fingerprintValueService: FingerprintValueService,
        analysisCacheServiceData: MutableMap<ByteString?, ByteString>?
    ): PackedFingerprint {
        val keyBytes: SerializationResult<ByteString?> =
            codecs.serializeMemoizedAndBlocking(fingerprintValueService, key)
        var writeStatus: com.google.common.util.concurrent.ListenableFuture<*>? = keyBytes.getFutureToBlockWritesOn()
        if (writeStatus != null) {
            val unused: Any? = writeStatus.get()
        }

        val valueBytes: SerializationResult<ByteString?> =
            codecs.serializeMemoizedAndBlocking(fingerprintValueService, value)
        writeStatus = keyBytes.getFutureToBlockWritesOn()
        if (writeStatus != null) {
            val unused: Any? = writeStatus.get()
        }

        val keyFingerprint: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            fingerprintValueService.fingerprint(version.concat(keyBytes.getObject().toByteArray()))

        if (analysisCacheServiceData != null) {
            analysisCacheServiceData.put(
                ByteString.copyFrom(keyFingerprint.toBytes()), valueBytes.getObject()
            )
        } else {
            val unused: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
                fingerprintValueService
                    .put(
                        keyFingerprint, prependFakeInvalidationData(valueBytes.getObject()).toByteArray()
                    )
                    .get()
        }

        return keyFingerprint
    }

    @AutoCodec
    @VisibleForSerialization
    @kotlin.jvm.JvmRecord
    internal data class TrivialKey(val text: String?) : SkyKey {
        public override fun functionName(): SkyFunctionName? {
            throw java.lang.UnsupportedOperationException()
        }
    }

    @kotlin.jvm.JvmRecord
    private data class ValueWithReference(val id: Int, val ref: ValueWithReference?) : SkyValue

    private class ValueWithReferenceBuilder
        (private val id: Int) : DeferredValue<ValueWithReference?> {
        private var ref: ValueWithReference? = null

        public override fun call(): ValueWithReference {
            return ValueWithReference(id, ref)
        }

        companion object {
            private fun setRef(builder: ValueWithReferenceBuilder, ref: Any?) {
                builder.ref = ref as ValueWithReference?
            }
        }
    }

    private class ValueWithReferenceCodec

        : DeferredObjectCodec<ValueWithReference?>() {
        public override fun autoRegister(): Boolean {
            return false
        }

        val encodedClass: java.lang.Class<out ValueWithReference?>
            get() = ValueWithReference::class.java

        @Throws(SerializationException::class, IOException::class)
        public override fun serialize(
            context: SerializationContext, obj: ValueWithReference, codedOut: CodedOutputStream
        ) {
            codedOut.writeInt32NoTag(obj.id)
            if (obj.ref == null) {
                codedOut.writeBoolNoTag(false)
            } else {
                codedOut.writeBoolNoTag(true)
                context.putSharedValue(obj.ref, null, this, codedOut)
            }
        }

        @Throws(SerializationException::class, IOException::class)
        public override fun deserializeDeferred(
            context: AsyncDeserializationContext, codedIn: CodedInputStream
        ): DeferredValue<ValueWithReference?> {
            val id: Int = codedIn.readInt32()
            if (!codedIn.readBool()) {
                val simpleResult: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
                    SimpleDeferredValue.< ValueWithReference > create < ValueWithReference ? > ()
                SimpleDeferredValue.set(simpleResult, ValueWithReference(id, null))
                return simpleResult
            }

            val sharedResult = ValueWithReferenceBuilder(id)
            context.getSharedValue(
                codedIn,
                null,
                this,
                sharedResult,
                { builder: ValueWithReferenceBuilder, ref: Any? ->
                    ValueWithReferenceBuilder.Companion.setRef(
                        builder,
                        ref
                    )
                })
            return sharedResult
        }
    }

    @AutoCodec
    @VisibleForSerialization
    @kotlin.jvm.JvmRecord
    internal data class TrivialValue(val text: String?) : SkyValue

    private class TrivialValueSharingCodec : DeferredObjectCodec<TrivialValue?>() {
        val encodedClass: java.lang.Class<TrivialValue?>
            get() = TrivialValue::class.java

        public override fun autoRegister(): Boolean {
            return false
        }

        @Throws(SerializationException::class, IOException::class)
        public override fun serialize(
            context: SerializationContext, value: TrivialValue?, codedOut: CodedOutputStream?
        ) {
            context.putSharedValue(value,  /* distinguisher= */null, TRIVIAL_SKY_VALUE_CODEC, codedOut)
        }

        @Throws(SerializationException::class, IOException::class)
        public override fun deserializeDeferred(
            context: AsyncDeserializationContext, codedIn: CodedInputStream?
        ): DeferredValue<TrivialValue?> {
            val builder: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
                SimpleDeferredValue.< TrivialValue > create < TrivialValue ? > ()
            context.getSharedValue(
                codedIn,  /* distinguisher= */
                null,
                TRIVIAL_SKY_VALUE_CODEC,
                builder,
                SimpleDeferredValue::set
            )
            return builder
        }
    }

    private class FaultyTrivialValueCodec : DeferredObjectCodec<TrivialValue?>() {
        val encodedClass: java.lang.Class<TrivialValue?>
            get() = TrivialValue::class.java

        public override fun autoRegister(): Boolean {
            return false
        }

        @Throws(SerializationException::class, IOException::class)
        public override fun serialize(
            context: SerializationContext, obj: TrivialValue?, codedOut: CodedOutputStream?
        ) {
            context.putSharedValue(obj,  /* distinguisher= */null, TRIVIAL_SKY_VALUE_CODEC, codedOut)
        }

        @Throws(SerializationException::class, IOException::class)
        public override fun deserializeDeferred(
            context: AsyncDeserializationContext, codedIn: CodedInputStream?
        ): DeferredValue<TrivialValue?> {
            val builder: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
                SimpleDeferredValue.< TrivialValue > create < TrivialValue ? > ()
            context.getSharedValue(
                codedIn,  /* distinguisher= */
                null,
                TRIVIAL_SKY_VALUE_CODEC,
                builder,
                { b, v ->
                    throw SerializationException("error setting value")
                })
            return builder
        }
    }

    /** Value that requires multiple Skyframe lookups to deserialize.  */
    private class MultiLookupValue(value1: ExampleValue?, value2: ExampleValue?) : SkyValue {
        val value1: ExampleValue?
        val value2: ExampleValue?

        init {
            this.value1 = value1
            this.value2 = value2
        }
    }

    @com.google.errorprone.annotations.Keep // used reflectively
    private class MultiLookupValueCodec : DeferredObjectCodec<MultiLookupValue?>() {
        val encodedClass: java.lang.Class<MultiLookupValue?>
            get() = MultiLookupValue::class.java

        @Throws(SerializationException::class, IOException::class)
        public override fun serialize(
            context: SerializationContext, obj: MultiLookupValue, codedOut: CodedOutputStream?
        ) {
            context.putSharedValue(
                obj.value1,  /* distinguisher= */null, ExampleValue.Companion.exampleValueCodec(), codedOut
            )
            context.putSharedValue(
                obj.value2,  /* distinguisher= */null, ExampleValue.Companion.exampleValueCodec(), codedOut
            )
        }

        @Throws(SerializationException::class, IOException::class)
        public override fun deserializeDeferred(
            context: AsyncDeserializationContext, codedIn: CodedInputStream?
        ): DeferredValue<MultiLookupValue?> {
            val builder = MultiLookupValueBuilder()
            context.getSharedValue(
                codedIn,  /* distinguisher= */
                null,
                ExampleValue.Companion.exampleValueCodec(),
                builder,
                { obj: MultiLookupValueBuilder?, obj: Any? -> obj.setValue1(obj) })
            context.getSharedValue(
                codedIn,  /* distinguisher= */
                null,
                ExampleValue.Companion.exampleValueCodec(),
                builder,
                { obj: MultiLookupValueBuilder?, obj: Any? -> obj.setValue2(obj) })
            return builder
        }

        private class MultiLookupValueBuilder : DeferredValue<MultiLookupValue?> {
            private var value1: ExampleValue? = null
            private var value2: ExampleValue? = null

            fun setValue1(obj: Any?) {
                this.value1 = obj as ExampleValue?
            }

            fun setValue2(obj: Any?) {
                this.value2 = obj as ExampleValue?
            }

            public override fun call(): MultiLookupValue {
                return MultiLookupValue(value1, value2)
            }
        }
    }

    companion object {
        /** Default implementation that errors if any keys are requested.  */
        private val NO_LOOKUP_ENVIRONMENT: EnvironmentForUtilities = EnvironmentForUtilities(
            { k ->
                throw java.lang.IllegalStateException("no requests expected, got " + k)
            })

        private fun createFakeAnalysisCacheClient(
            data: MutableMap<ByteString?, ByteString>
        ): RemoteAnalysisCacheClient {
            val result: RemoteAnalysisCacheClient =
                Mockito.mock<RemoteAnalysisCacheClient>(RemoteAnalysisCacheClient::class.java)
            Mockito.`when`<T?>(result.lookup(ArgumentMatchers.any<T?>()))
                .thenAnswer(
                    Answer { invocation: InvocationOnMock? ->
                        val key: ByteString? = invocation.getArgument<ByteString?>(0)
                        val value: ByteString = data.getOrDefault(key, ByteString.empty())
                        com.google.common.util.concurrent.Futures.immediateFuture<Any?>(
                            LookupResult(
                                value,
                                if (value.isEmpty())
                                    MissReason.MISS_REASON_SKYVALUE_MISS
                                else
                                    MissReason.MISS_REASON_UNSPECIFIED
                            )
                        )
                    })

            return result
        }

        /**
         * Creates a [RequestBatcher] that emits a [SettableFuture] per request.
         * 
         * 
         * The client sets the [SettableFuture] to complete the request.
         */
        private fun createCapturingAnalysisCacheClient(
            capturer: java.util.function.Consumer<com.google.common.util.concurrent.SettableFuture<LookupResult?>?>
        ): RemoteAnalysisCacheClient {
            val result: RemoteAnalysisCacheClient =
                Mockito.mock<RemoteAnalysisCacheClient>(RemoteAnalysisCacheClient::class.java)

            Mockito.`when`<T?>(result.lookup(ArgumentMatchers.any<T?>()))
                .thenAnswer(
                    Answer { invocation: InvocationOnMock? ->
                        val settable: com.google.common.util.concurrent.SettableFuture<LookupResult?> =
                            com.google.common.util.concurrent.SettableFuture.create<LookupResult?>()
                        capturer.accept(settable)
                        settable
                    })

            return result
        }

        private fun dependOnFutureImpl(future: com.google.common.util.concurrent.ListenableFuture<*>): ObservedFutureStatus {
            return if (future.isDone()) DONE else NOT_DONE
        }

        private fun alwaysDoneDependOnFuture(future: com.google.common.util.concurrent.ListenableFuture<*>): ObservedFutureStatus {
            // Although the in-memory FingerprintValueStore is synchronous, the returned bytes are
            // processed asynchronously on an executor. There are 3 places where this may be called and 2
            // where the future might still be unset.
            //
            // 1. At the end of WaitingForFutureValueBytes, there's a wait for the
            //    SkyframeLookupContinuation to become available. That happens on the executor
            //    thread that processes the shared bytes.
            // 2. At the end of WaitingForLookupContinuation, there's a small wait for the final result.
            //    This wait corresponds to setting the shared value in the parent and happens on the
            //    executor thread after 1 so the caller might observe an unset future.
            try {
                val unused: Any? = future.get()
            } catch (e: ExecutionException) {
                // Exceptions are ignored here but handled by the next state, which always calls getDone.
            } catch (e: java.lang.InterruptedException) {
            }
            return DONE
        }

        private val TRIVIAL_SKY_VALUE_CODEC: DeferredObjectCodec<TrivialValue?> =
            SkyValueRetrieverTest_TrivialValue_AutoCodec()
    }
}
