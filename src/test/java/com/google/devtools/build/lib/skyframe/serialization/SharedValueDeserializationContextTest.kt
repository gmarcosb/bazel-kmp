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
class SharedValueDeserializationContextTest {
    private val executor: ForkJoinPool = ForkJoinPool(CONCURRENCY)
    private val rng: Random = Random(0)

    @org.junit.Test
    @TestParameters("{size: 2, useDeferredCodec: false}")
    @TestParameters("{size: 4, useDeferredCodec: false}")
    @TestParameters("{size: 4, useDeferredCodec: true}")
    @TestParameters("{size: 8, useDeferredCodec: false}")
    @TestParameters("{size: 16, useDeferredCodec: false}")
    @TestParameters("{size: 16, useDeferredCodec: true}")
    @TestParameters("{size: 32, useDeferredCodec: false}")
    @TestParameters("{size: 64, useDeferredCodec: false}")
    @TestParameters("{size: 128, useDeferredCodec: false}")
    @Throws(java.lang.Exception::class)
    fun codec_roundTrips(size: Int, useDeferredCodec: Boolean) {
        SerializationTester(
            NotNestedSet.Companion.createRandom(
                rng,
                size,
                size,
                java.util.function.Function { obj: Random? -> obj.nextInt() })
        )
            .addCodec(
                if (useDeferredCodec)
                    NotNestedSetDeferredCodec(NestedArrayCodec())
                else
                    defaultNotNestedSetCodec
            )
            .makeMemoizingAndAllowFutureBlocking( /* allowFutureBlocking= */true)
            .setVerificationFunction(
                { original: NotNestedSet?, deserialized: NotNestedSet? ->
                    verifyDeserializedNotNestedSet(
                        original,
                        deserialized
                    )
                })
            .runTests()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun getsShouldBeConcurrent() {
        // When deserializing a value, multiple calls to `FingerprintValueStore.get` may occur. These
        // should not block each other.

        val store: GetRecordingStore = GetRecordingStore()
        val fingerprintValueService: FingerprintValueService? =
            FingerprintValueService.createForTesting(store)
        val codecs: ObjectCodecs = createObjectCodecs()

        val subject: NotNestedSet =
            NotNestedSet(
                arrayOf<Any>(
                    NotNestedSet.Companion.createRandomLeafArray(
                        rng,
                        java.util.function.Function { obj: Random? -> obj.nextInt() }),
                    NotNestedSet.Companion.createRandomLeafArray(
                        rng,
                        java.util.function.Function { obj: Random? -> obj.nextInt() }),
                    NotNestedSet.Companion.createRandomLeafArray(
                        rng,
                        java.util.function.Function { obj: Random? -> obj.nextInt() })
                )
            )

        val serialized: SerializationResult<ByteString?> =
            codecs.serializeMemoizedAndBlocking(fingerprintValueService, subject)
        val writeStatus: com.google.common.util.concurrent.ListenableFuture<*>? = serialized.getFutureToBlockWritesOn()
        if (writeStatus != null) {
            // If it is asynchronous, writing should complete without throwing any exceptions.
            writeStatus.get()
        }

        val result: com.google.common.util.concurrent.ListenableFuture<Any?> =
            deserializeWithExecutor(codecs, fingerprintValueService, serialized.getObject())

        // There are 4 nested arrays. The top-level one and its 3 child arrays. The child arrays aren't
        // requested until the top-level array is requested. Completes the top-level request.
        store.takeFirstRequest().complete()

        // The 3 child requests should become available. Since none of them are complete, they must be
        // concurrent.
        val childGets: java.util.ArrayList<GetRequest> = java.util.ArrayList<GetRequest>(3)
        for (i in 0..2) {
            childGets.add(store.takeFirstRequest())
        }

        // Since the child requests have not been satisfied, the result can't be done yet.
        Truth.assertThat(result.isDone()).isFalse()

        // Completes the child requests and verifies the result.
        for (request in childGets) {
            request.complete()
        }
        verifyDeserializedNotNestedSet(subject, result.get() as NotNestedSet?)
    }

    private class NotNestedSetContainer {
        private var first: NotNestedSet? = null
        private var second: NotNestedSet? = null

        private constructor()

        private constructor(first: NotNestedSet?, second: NotNestedSet?) {
            this.first = first
            this.second = second
        }

        companion object {
            private val FIRST_OFFSET: Long
            private val SECOND_OFFSET: Long

            init {
                try {
                    FIRST_OFFSET = getFieldOffset(NotNestedSetContainer::class.java, "first")
                    SECOND_OFFSET = getFieldOffset(NotNestedSetContainer::class.java, "second")
                } catch (e: java.lang.ReflectiveOperationException) {
                    throw java.lang.ExceptionInInitializerError(e)
                }
            }
        }
    }

    /** Selects the [AsyncDeserializationContext.deserialize] overload.  */
    private enum class DeserializeOverloadSelector {
        OFFSET,
        SETTER,
        OFFSET_WITH_DONE_CALLBACK
    }

    /** Codec that observes futures through [AsyncObjectCodec.deserialize] overloads.  */
    private class NotNestedSetContainerCodec
        (
        private val overloadSelector: DeserializeOverloadSelector,
        expectedFirst: NotNestedSet?,
        expectedSecond: NotNestedSet?
    ) : AsyncObjectCodec<NotNestedSetContainer?>() {
        private val expectedFirst: NotNestedSet?
        private val expectedSecond: NotNestedSet?

        init {
            this.expectedFirst = expectedFirst
            this.expectedSecond = expectedSecond
        }

        val encodedClass: java.lang.Class<NotNestedSetContainer?>
            get() = NotNestedSetContainer::class.java

        @Throws(SerializationException::class, IOException::class)
        public override fun serialize(
            context: SerializationContext, container: NotNestedSetContainer, codedOut: CodedOutputStream?
        ) {
            context.serialize(container.first, codedOut)
            context.serialize(container.second, codedOut)
        }

        @Throws(SerializationException::class, IOException::class)
        public override fun deserializeAsync(
            context: AsyncDeserializationContext, codedIn: CodedInputStream?
        ): NotNestedSetContainer {
            val value = NotNestedSetContainer()
            context.registerInitialValue(value)
            // The additional verifications in the code below are redundant with the ones performed by the
            // SerializationTester except that they occur at the moment the context provides the values by
            // callback. This enables verification that the provided values are fully deserialized as soon
            // as they are set, as required by the specification.
            when (overloadSelector) {
                DeserializeOverloadSelector.OFFSET -> {
                    context.deserialize(codedIn, value, NotNestedSetContainer.Companion.FIRST_OFFSET)
                    context.deserialize(codedIn, value, NotNestedSetContainer.Companion.SECOND_OFFSET)
                }

                DeserializeOverloadSelector.SETTER -> {
                    context.deserialize(
                        codedIn,
                        value,
                        { container, untypedFirst ->
                            val first: NotNestedSet? = untypedFirst as NotNestedSet?
                            container.first = first
                            verifyDeserializedNotNestedSet(expectedFirst, first)
                        })
                    context.deserialize(
                        codedIn,
                        value,
                        { container, untypedSecond ->
                            val second: NotNestedSet? = untypedSecond as NotNestedSet?
                            container.second = second
                            verifyDeserializedNotNestedSet(expectedSecond, second)
                        })
                }

                DeserializeOverloadSelector.OFFSET_WITH_DONE_CALLBACK -> {
                    context.deserialize(
                        codedIn,
                        value,
                        NotNestedSetContainer.Companion.FIRST_OFFSET,
                        { verifyDeserializedNotNestedSet(expectedFirst, value.first) })
                    context.deserialize(
                        codedIn,
                        value,
                        NotNestedSetContainer.Companion.SECOND_OFFSET,
                        { verifyDeserializedNotNestedSet(expectedSecond, value.second) })
                }
            }
            return value
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun valueDependsOnFuture(
        @TestParameter overloadSelector: DeserializeOverloadSelector,
        @TestParameter doesSecondAliasFirst: Boolean
    ) {
        // Exercises the case where AsyncDeserializationContext.deserialize overloads are called and the
        // result is a future. In the special case where `doesSecondAliasFirst` = true, `subject.second`
        // is a backreference to the first, which exercises the case where a future is added to the
        // memoization table.

        val subject: NotNestedSetContainer?
        if (doesSecondAliasFirst) {
            subject =
                NotNestedSetContainer(
                    NotNestedSet.Companion.createRandom(
                        rng,
                        4,
                        4,
                        java.util.function.Function { obj: Random? -> obj.nextInt() }),
                    NotNestedSet.Companion.createRandom(
                        rng,
                        4,
                        4,
                        java.util.function.Function { obj: Random? -> obj.nextInt() })
                )
        } else {
            val contained: NotNestedSet = NotNestedSet.Companion.createRandom(
                rng,
                5,
                5,
                java.util.function.Function { obj: Random? -> obj.nextInt() })
            subject = NotNestedSetContainer(contained, contained)
        }
        SerializationTester(subject)
            .addCodec(defaultNotNestedSetCodec)
            .addCodec(NotNestedSetContainerCodec(overloadSelector, subject.first, subject.second))
            .makeMemoizingAndAllowFutureBlocking( /* allowFutureBlocking= */true)
            .setVerificationFunction(
                { original: NotNestedSetContainer, deserialized: NotNestedSetContainer ->
                    verifyDeserializedNotNestedSetContainer(
                        original,
                        deserialized
                    )
                })
            .runTests()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun internedValueWithSharedElement() {
        SerializationTester(InternedValue.Companion.create(101), InternedValue.Companion.create(45678))
            .makeMemoizingAndAllowFutureBlocking( /* allowFutureBlocking= */true)
            .runTests()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun missingSharedValueData_producesSpecificError() {
        val store: GetRecordingStore = GetRecordingStore()
        val fingerprintValueService: FingerprintValueService? =
            FingerprintValueService.createForTesting(store)
        val codecs: ObjectCodecs = createObjectCodecs()

        // This subject results in exactly one shared value, storing the leaf array.
        val subject: NotNestedSet = NotNestedSet(
            arrayOf<Any>(
                NotNestedSet.Companion.createRandomLeafArray(
                    rng,
                    java.util.function.Function { obj: Random? -> obj.nextInt() })
            )
        )

        val serialized: SerializationResult<ByteString?> =
            codecs.serializeMemoizedAndBlocking(fingerprintValueService, subject)
        val writeStatus: com.google.common.util.concurrent.ListenableFuture<*>? = serialized.getFutureToBlockWritesOn()
        if (writeStatus != null) {
            // If it is asynchronous, writing should complete without throwing any exceptions.
            writeStatus.get()
        }

        val result: com.google.common.util.concurrent.ListenableFuture<Any?> =
            deserializeWithExecutor(codecs, fingerprintValueService, serialized.getObject())

        // Completes the request for shared value bytes with null bytes, indicating missing data.
        store.takeFirstRequest().completeWithNullBytes()

        val thrown: MissingSharedValueBytesException? =
            org.junit.Assert.assertThrows<ExecutionException?>(
                ExecutionException::class.java,
                org.junit.function.ThrowingRunnable { result.get() }).getCause() as MissingSharedValueBytesException?
        assertThat(thrown).hasMessageThat().isEqualTo("Missing shared value bytes")
    }

    @get:Throws(java.lang.Exception::class)
    @get:org.junit.Test
    val sharedValue_missingBytesFromCache_notifiesLookupCollector: Unit
        get() {
            val store: GetRecordingStore = GetRecordingStore()
            val fingerprintValueService: FingerprintValueService? =
                FingerprintValueService.createForTesting(store)
            val codecs: ObjectCodecs = createObjectCodecs()

            // This subject results in exactly one shared value, storing the leaf array.
            val subject: NotNestedSet = NotNestedSet(
                arrayOf<Any>(
                    NotNestedSet.Companion.createRandomLeafArray(
                        rng,
                        java.util.function.Function { obj: Random? -> obj.nextInt() })
                )
            )

            val serialized: SerializationResult<ByteString?> =
                codecs.serializeMemoizedAndBlocking(fingerprintValueService, subject)
            val writeStatus: com.google.common.util.concurrent.ListenableFuture<*>? =
                serialized.getFutureToBlockWritesOn()
            if (writeStatus != null) {
                // If the write is asynchronous, writing should complete without throwing any exceptions.
                writeStatus.get()
            }

            val result: com.google.common.util.concurrent.ListenableFuture<SkyframeLookupContinuation?> =
                SharedValueDeserializationContext.deserializeWithSkyframe(
                    codecs.getCodecRegistry(),
                    com.google.common.collect.ImmutableClassToInstanceMap.of<B?>(),
                    fingerprintValueService,
                    serialized.getObject().newCodedInput()
                ) as com.google.common.util.concurrent.ListenableFuture<SkyframeLookupContinuation?>

            // Completes the request for shared value bytes with null bytes, indicating missing data.
            store.takeFirstRequest().completeWithNullBytes()

            // The following get call hangs if the missing bytes are not propagated.
            val thrown: MissingSharedValueBytesException? =
                org.junit.Assert.assertThrows<ExecutionException?>(
                    ExecutionException::class.java,
                    org.junit.function.ThrowingRunnable {
                        result.get(
                            com.google.devtools.build.lib.testutil.TestUtils.WAIT_TIMEOUT_SECONDS,
                            TimeUnit.SECONDS
                        )
                    })
                    .getCause() as MissingSharedValueBytesException?
            assertThat(thrown).hasMessageThat().isEqualTo("Missing shared value bytes")
        }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun sharedValueIsDecompressed(@TestParameter compress: Boolean) {
        val store: GetRecordingStore = GetRecordingStore()
        val fingerprintValueService: FingerprintValueService? =
            FingerprintValueService.createForTesting(store)
        val codecs: ObjectCodecs = createObjectCodecs()
        val bytes = ByteArray(if (compress) 2000 else 1000)
        val subject: NotNestedSet =
            NotNestedSet(
                arrayOf<Any>(
                    bytes,
                )
            )

        val serialized: SerializationResult<ByteString?> =
            codecs.serializeMemoizedAndBlocking(fingerprintValueService, subject)
        val writeStatus: com.google.common.util.concurrent.ListenableFuture<*>? = serialized.getFutureToBlockWritesOn()
        if (writeStatus != null) {
            writeStatus.get()
        }

        val result: com.google.common.util.concurrent.ListenableFuture<Any?> =
            deserializeWithExecutor(codecs, fingerprintValueService, serialized.getObject())

        val storeValues: com.google.common.collect.ImmutableList<ByteArray?> =
            com.google.common.collect.ImmutableList.copyOf(store.getFingerprintToContents().values())
        Truth.assertThat(storeValues).hasSize(1)
        Truth.assertThat(storeValues.get(0)).hasLength(if (compress) 23 else 1007)

        store.takeFirstRequest().complete()
        verifyDeserializedNotNestedSet(subject, result.get() as NotNestedSet?)
    }

    private class InternedValue {
        private var value: Int? = null

        override fun hashCode(): Int {
            return value!!
        }

        override fun equals(obj: Any?): Boolean {
            if (obj is InternedValue) {
                return value == obj.value
            }
            return false
        }

        companion object {
            private fun create(value: Int): InternedValue {
                val result = InternedValue()
                result.value = value
                return result
            }
        }
    }

    @com.google.errorprone.annotations.Keep
    private class InternedValueCodec : InterningObjectCodec<InternedValue?>() {
        val encodedClass: java.lang.Class<InternedValue?>
            get() = InternedValue::class.java

        @Throws(SerializationException::class, IOException::class)
        public override fun serialize(
            context: SerializationContext, obj: InternedValue, codedOut: CodedOutputStream?
        ) {
            context.putSharedValue(
                obj.value,  /* distinguisher= */null, DeferredIntegerCodec.Companion.INSTANCE, codedOut
            )
        }

        @Throws(SerializationException::class, IOException::class)
        public override fun deserializeInterned(
            context: AsyncDeserializationContext, codedIn: CodedInputStream?
        ): InternedValue {
            val value = InternedValue()
            context.getSharedValue(
                codedIn,  /* distinguisher= */
                null,
                DeferredIntegerCodec.Companion.INSTANCE,
                value,
                { parent, v -> parent.value = v as Int? })
            return value
        }

        // fake implementation just returns input
        public override fun intern(interned: InternedValue): InternedValue {
            com.google.common.base.Preconditions.checkNotNull<Int?>(interned.value)
            return interned
        }
    }

    private class DeferredIntegerCodec : DeferredObjectCodec<Int?>() {
        val encodedClass: java.lang.Class<Int?>
            get() = Int::class.java

        public override fun autoRegister(): Boolean {
            return false
        }

        @Throws(SerializationException::class, IOException::class)
        public override fun serialize(context: SerializationContext?, obj: Int, codedOut: CodedOutputStream) {
            codedOut.writeInt32NoTag(obj)
        }

        @Throws(SerializationException::class, IOException::class)
        public override fun deserializeDeferred(
            context: AsyncDeserializationContext?, codedIn: CodedInputStream
        ): DeferredValue<Int?> {
            val value: Int = codedIn.readInt32()
            return DeferredValue { value }
        }

        companion object {
            private val INSTANCE = DeferredIntegerCodec()
        }
    }

    private fun deserializeWithExecutor(
        codecs: ObjectCodecs, fingerprintValueService: FingerprintValueService?, data: ByteString?
    ): com.google.common.util.concurrent.ListenableFuture<Any?> {
        val task: com.google.common.util.concurrent.ListenableFutureTask<V?> =
            com.google.common.util.concurrent.ListenableFutureTask.create<V?>(
                java.util.concurrent.Callable { codecs.deserializeMemoizedAndBlocking(fingerprintValueService, data) })
        executor.execute(task)
        return task
    }

    companion object {
        private const val CONCURRENCY = 20

        private fun verifyDeserializedNotNestedSet(
            original: NotNestedSet?, deserialized: NotNestedSet?
        ) {
            assertThat(dumpStructureWithEquivalenceReduction(deserialized))
                .isEqualTo(dumpStructureWithEquivalenceReduction(original))
        }

        private fun verifyDeserializedNotNestedSetContainer(
            original: NotNestedSetContainer, deserialized: NotNestedSetContainer
        ) {
            verifyDeserializedNotNestedSet(original.first, deserialized.first)
            verifyDeserializedNotNestedSet(original.second, deserialized.second)
        }

        private fun createObjectCodecs(): ObjectCodecs {
            return ObjectCodecs(
                AutoRegistry.get().getBuilder().add(defaultNotNestedSetCodec).build()
            )
        }

        private val defaultNotNestedSetCodec: ObjectCodec<NotNestedSet?>
            get() = NotNestedSetCodec(NestedArrayCodec())
    }
}
