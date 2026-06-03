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

import com.google.devtools.build.lib.skyframe.serialization.FingerprintValueStore.InMemoryFingerprintValueStore

@RunWith(TestParameterInjector::class)
class SharedValueSerializationContextTest {
    private val executor: ForkJoinPool = ForkJoinPool(CONCURRENCY)
    private val rng: Random = Random(0)

    private class PutRecordingStore : FingerprintValueStore {
        private val putResponses: java.util.ArrayList<SettableWriteStatus> = java.util.ArrayList<SettableWriteStatus>()

        public override fun put(fingerprint: KeyBytesProvider?, serializedBytes: ByteArray?): WriteStatus {
            val response: SettableWriteStatus = SettableWriteStatus()
            synchronized(putResponses) {
                putResponses.add(response)
            }
            return response
        }

        public override fun get(fingerprint: KeyBytesProvider?): com.google.common.util.concurrent.ListenableFuture<ByteArray?>? {
            throw java.lang.UnsupportedOperationException()
        }

        fun completeAllResponses() {
            for (response in putResponses) {
                response.markSuccess()
            }
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun resultDoesNotBlockOnPut() {
        // The result is available prior to completion of the put operations and that completion of the
        // put operations propagates to the SerializationResult's future.
        val store = PutRecordingStore()
        val fingerprintValueService: FingerprintValueService? =
            FingerprintValueService.createForTesting(store)
        val codecs: ObjectCodecs = createObjectCodecs()

        // Creates a diamond.
        //   a
        //  / \
        // b   c
        //  \ /
        //   d
        val d = createRandomLeafArray()
        val c: Array<Any?> = arrayOf<Any>(d)
        val b: Array<Any?> = arrayOf<Any>(d)
        val a: Array<Any?> = arrayOf<Any>(b, c)
        val diamond: NotNestedSet = NotNestedSet(a)
        val result: SerializationResult<ByteString?> =
            codecs.serializeMemoizedAndBlocking(fingerprintValueService, diamond)

        // 4 remote arrays were written because d is memoized via the cache, despite the fact that d
        // occurs twice in the traversal.
        val responses: java.util.ArrayList<SettableWriteStatus> = store.putResponses
        Truth.assertThat(responses).hasSize(4)

        val writeStatus: com.google.common.util.concurrent.ListenableFuture<*>? = result.getFutureToBlockWritesOn()
        Truth.assertThat(writeStatus).isNotNull()
        Truth.assertThat(writeStatus.isDone()).isFalse()

        // Sets some, but not all of the responses.
        for (i in 0..1) {
            responses.get(i).markSuccess()
        }
        Truth.assertThat(writeStatus.isDone()).isFalse() // not yet done

        // Sets the remaining responses.
        for (i in 2..<responses.size()) {
            responses.get(i).markSuccess()
        }
        Truth.assertThat(writeStatus.isDone()).isTrue() // write status future completes
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun writeStatusPropagatesToSecondCaller() {
        // When a shared value is serialized by two different callers, the 2nd caller's
        // SerializationResult.futureToBlockWritingOn also waits for writes to complete.
        val store = PutRecordingStore()
        val fingerprintValueService: FingerprintValueService? =
            FingerprintValueService.createForTesting(store)
        val codecs: ObjectCodecs = createObjectCodecs()

        val shared = createRandomLeafArray()
        val set1: NotNestedSet = NotNestedSet(shared)
        val set2: NotNestedSet = NotNestedSet(shared)

        val result1: SerializationResult<ByteString?> =
            codecs.serializeMemoizedAndBlocking(fingerprintValueService, set1)
        val writeStatus1: com.google.common.util.concurrent.ListenableFuture<*> = result1.getFutureToBlockWritesOn()
        Truth.assertThat(writeStatus1.isDone()).isFalse()

        Truth.assertThat(store.putResponses).hasSize(1)

        val result2: SerializationResult<ByteString?> =
            codecs.serializeMemoizedAndBlocking(fingerprintValueService, set2)
        val writeStatus2: com.google.common.util.concurrent.ListenableFuture<*> = result2.getFutureToBlockWritesOn()
        Truth.assertThat(writeStatus2.isDone()).isFalse()

        // The store only observes 1 put because it is shared between set1 and set2.
        Truth.assertThat(store.putResponses).hasSize(1)

        // Completing the response causes both of the write statuses to complete.
        store.completeAllResponses()
        Truth.assertThat(writeStatus1.isDone()).isTrue()
        Truth.assertThat(writeStatus2.isDone()).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun multipleSharedValues_requestedInParallel() {
        // Serialization does not block on blocked fingerprint computations in another thread.
        val arrayCodec: NestedArrayCodec = NestedArrayCodec()
        val codecs: ObjectCodecs =
            ObjectCodecs(
                AutoRegistry.get().getBuilder().add(NotNestedSetCodec(arrayCodec)).build()
            )
        val fingerprintValueService: FingerprintValueService? = FingerprintValueService.createForTesting()

        val sharedArray = createRandomLeafArray()
        val sharedEntered: CountDownLatch = CountDownLatch(1)
        val sharedBlocker: CountDownLatch = CountDownLatch(1)
        arrayCodec.injectSerializeDelay(sharedArray, sharedEntered, sharedBlocker)

        // Serializes `sharedArray`, which is registered to block on `sharedBlocker`.
        val first: com.google.common.util.concurrent.ListenableFuture<SerializationResult<ByteString?>?> =
            serializeWithExecutor(codecs, fingerprintValueService, NotNestedSet(sharedArray))
        sharedEntered.await() // Waits for the above thread take ownership of `sharedArray`.

        val myArray = createRandomLeafArray()
        val myArrayEntered: CountDownLatch = CountDownLatch(1)
        // Does not block serialization of `myArray`, but uses `myArrayEntered` to determine that
        // serialization of `myArray` has started.
        arrayCodec.injectSerializeDelay(myArray, myArrayEntered, CountDownLatch(0))

        val second: com.google.common.util.concurrent.ListenableFuture<SerializationResult<ByteString?>?> =
            serializeWithExecutor(
                codecs, fingerprintValueService, NotNestedSet(arrayOf<Any>(sharedArray, myArray))
            )

        // Completing the line below means that the serialization of `myArray` can start even though
        // serialization of `sharedArray` is blocked.
        myArrayEntered.await()

        // Neither is done due to being blocked by `sharedBlocker`.
        Truth.assertThat(first.isDone()).isFalse()
        Truth.assertThat(second.isDone()).isFalse()

        sharedBlocker.countDown() // unblocks serialization of `sharedArray`

        // Serialization succeeds now that it is unblocked.
        val unusedFirstResult: SerializationResult<ByteString?>? = first.get()
        val unusedSecondResult: SerializationResult<ByteString?>? = second.get()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun concurrentSharing_waitsForCompleteBytes() {
        // Under parallel sharing, serialization blocks until all fingerprints are computed.

        // Counts down every FingerprintValueStore.put.

        val arrived: CountDownLatch = CountDownLatch(CONCURRENCY)
        // For each FingerprintValueStore.put, a CountDownLatch(1) is added to the end of this queue.
        // The put thread blocks, awaiting its associated latch.
        val putPermits: ConcurrentLinkedDeque<CountDownLatch> = ConcurrentLinkedDeque<CountDownLatch>()
        // Responses returned by the FingerprintValueStore.
        val putResponses: java.util.ArrayList<SettableWriteStatus> = java.util.ArrayList<SettableWriteStatus>()

        val blockingStore: FingerprintValueStore? =
            object : FingerprintValueStore() {
                public override fun put(fingerprint: KeyBytesProvider?, serializedBytes: ByteArray?): WriteStatus {
                    val response: SettableWriteStatus = SettableWriteStatus()
                    synchronized(putResponses) {
                        putResponses.add(response)
                    }
                    val permit: CountDownLatch = CountDownLatch(1)
                    putPermits.offerLast(permit)
                    arrived.countDown()
                    try {
                        permit.await()
                    } catch (e: java.lang.InterruptedException) {
                        throw java.lang.AssertionError(e)
                    }
                    return response
                }

                public override fun get(fingerprint: KeyBytesProvider?): com.google.common.util.concurrent.ListenableFuture<ByteArray?>? {
                    throw java.lang.UnsupportedOperationException()
                }
            }
        val fingerprintValueService: FingerprintValueService? =
            FingerprintValueService.createForTesting(blockingStore)

        val sharedArrays: java.util.ArrayList<Array<Any?>?> = java.util.ArrayList<Array<Any?>?>(CONCURRENCY)
        for (i in 0..<CONCURRENCY) {
            sharedArrays.add(createRandomLeafArray())
        }

        val codecs: ObjectCodecs = createObjectCodecs()

        val results: java.util.ArrayList<com.google.common.util.concurrent.ListenableFuture<SerializationResult<ByteString?>?>> =
            java.util.ArrayList<com.google.common.util.concurrent.ListenableFuture<SerializationResult<ByteString?>?>>(
                CONCURRENCY
            )
        for (i in 0..<CONCURRENCY) {
            val arrays = arrayOfNulls<Any>(CONCURRENCY)
            for (j in 0..<CONCURRENCY) {
                arrays[(i + j) % CONCURRENCY] = sharedArrays.get(j)
            }
            val set: NotNestedSet = NotNestedSet(arrays)
            // Each thread will acquire ownership of a unique `sharedArrays` element then block when it
            // hits the `putPermits`.
            results.add(serializeWithExecutor(codecs, fingerprintValueService, set))
        }
        // When the following await has succeeded, each thread has acquired ownership of one of the
        // `sharedArrays`.
        arrived.await()

        // Verifies that all SerializationResults are blocked (due to incomplete fingerprints).
        for (result in results) {
            Truth.assertThat(result.isDone()).isFalse()
        }

        // Unblocks all but 1 of the threads.
        for (i in 0..<CONCURRENCY - 1) {
            putPermits.pollFirst().countDown()
        }
        // Since the permits are ordered, the first element of the queue is the one associated with a
        // put of one of the `sharedArrays`. It doesn't happen in the current implementation, but more
        // permits could be added by the unblocking above, which is why this distinction matters.
        val lastSharedPut: CountDownLatch? = putPermits.pollFirst()
        Truth.assertThat(lastSharedPut).isNotNull()

        // Even with all but 1 of the shared puts complete, all results are still blocked since all
        // threads require all fingerprints.
        for (result in results) {
            Truth.assertThat(result.isDone()).isFalse()
        }

        lastSharedPut.countDown() // Releases the remaining put.

        // Releasing the putPermits above unblocks additional serialization work for the top-level
        // nested arrays. Unblocks the additional resulting puts.
        for (i in 0..<CONCURRENCY) {
            waitForLastPermit(putPermits).countDown()
        }

        // Everything succeeds once all the threads wake up from being blocked and complete.
        val resultList: MutableList<SerializationResult<ByteString?>> =
            com.google.common.util.concurrent.Futures.successfulAsList<SerializationResult<ByteString?>>(results).get()

        // Even with all the results available, the write status futures are still not done because
        // `putResponses` have not been set.
        for (result in resultList) {
            assertThat(result.getFutureToBlockWritesOn().isDone()).isFalse()
        }

        // There's 2 for each subject: its top-level array and its owned element of `sharedArrays`.
        Truth.assertThat(putResponses).hasSize(CONCURRENCY * 2)

        // Setting all the responses completes the result write status futures.
        for (response in putResponses) {
            response.markSuccess()
        }
        for (result in resultList) {
            assertThat(result.getFutureToBlockWritesOn().isDone()).isTrue()
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun errorInSharedPut() {
        // When a shared value is serialized in error by one thread, another thread serializing the
        // same shared value reports the same error.
        val fingerprintValueService: FingerprintValueService? = FingerprintValueService.createForTesting()
        val codecs: ObjectCodecs =
            ObjectCodecs(
                ObjectCodecRegistry.newBuilder().add(FaultySharedValueExampleCodec()).build()
            )
        val subject1 = SharedValueExample(10)
        val thrown1: T? =
            org.junit.Assert.assertThrows<T?>(
                SerializationException::class.java,
                org.junit.function.ThrowingRunnable {
                    codecs.serializeMemoizedAndBlocking(
                        fingerprintValueService,
                        subject1
                    )
                })

        val subject2 = SharedValueExample(subject1.sharedData)
        val thrown2: T? =
            org.junit.Assert.assertThrows<T?>(
                SerializationException::class.java,
                org.junit.function.ThrowingRunnable {
                    codecs.serializeMemoizedAndBlocking(
                        fingerprintValueService,
                        subject2
                    )
                })
        assertThat(thrown2).isSameInstanceAs(thrown1)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun sharedValueIsCompressed(@TestParameter compress: Boolean) {
        val store: InMemoryFingerprintValueStore = InMemoryFingerprintValueStore()
        val fingerprintValueService: FingerprintValueService? =
            FingerprintValueService.createForTesting(store)
        val codecs: ObjectCodecs = createObjectCodecs()
        val byteArray = ByteArray(if (compress) 2000 else 1000)
        val a: Array<Any?> = arrayOf<Any>(byteArray)

        val unused: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            codecs.serializeMemoizedAndBlocking(fingerprintValueService, NotNestedSet(a))

        val storeValues: com.google.common.collect.ImmutableList<ByteString> =
            com.google.common.collect.ImmutableList.copyOf(store.fingerprintToContents.values())
        Truth.assertThat(storeValues).hasSize(1)
        Truth.assertThat(storeValues.get(0).toByteArray()).hasLength(if (compress) 23 else 1007)
    }

    /** Test data for [.errorInSharedPut].  */
    @kotlin.jvm.JvmRecord
    private data class SharedValueExample(val sharedData: Int?)

    private class FaultySharedValueExampleCodec

        : DeferredObjectCodec<SharedValueExample?>() {
        val encodedClass: java.lang.Class<SharedValueExample?>
            get() = SharedValueExample::class.java

        public override fun autoRegister(): Boolean {
            return false
        }

        @Throws(SerializationException::class, IOException::class)
        public override fun serialize(
            context: SerializationContext, obj: SharedValueExample, codedOut: CodedOutputStream?
        ) {
            context.putSharedValue(
                obj.sharedData,  /* distinguisher= */null, FaultySerializationCodec.Companion.INSTANCE, codedOut
            )
        }

        public override fun deserializeDeferred(
            context: AsyncDeserializationContext?, codedIn: CodedInputStream?
        ): DeferredValue<SharedValueExample?>? {
            throw java.lang.AssertionError("not reachable")
        }
    }

    private class FaultySerializationCodec : DeferredObjectCodec<Int?>() {
        val encodedClass: java.lang.Class<Int?>
            get() = Int::class.java

        public override fun autoRegister(): Boolean {
            return false
        }

        @Throws(SerializationException::class)
        public override fun serialize(context: SerializationContext?, obj: Int?, codedOut: CodedOutputStream?) {
            throw SerializationException("injected error")
        }

        public override fun deserializeDeferred(
            context: AsyncDeserializationContext?, codedIn: CodedInputStream?
        ): DeferredValue<Int?>? {
            throw java.lang.AssertionError("not reachable")
        }

        companion object {
            private val INSTANCE = FaultySerializationCodec()
        }
    }

    private fun serializeWithExecutor(
        codecs: ObjectCodecs, fingerprintValueService: FingerprintValueService?, subject: Any?
    ): com.google.common.util.concurrent.ListenableFuture<SerializationResult<ByteString?>?> {
        val task: com.google.common.util.concurrent.ListenableFutureTask<V?> =
            com.google.common.util.concurrent.ListenableFutureTask.create<V?>(
                java.util.concurrent.Callable { codecs.serializeMemoizedAndBlocking(fingerprintValueService, subject) })
        executor.execute(task)
        return task
    }

    private fun createRandomLeafArray(): Array<Any?> {
        return NotNestedSet.Companion.createRandomLeafArray(
            rng,
            java.util.function.Function { obj: Random? -> obj.nextInt() })
    }

    companion object {
        private const val CONCURRENCY = 20

        private const val POLL_MS: Long = 100

        @Throws(java.lang.InterruptedException::class)
        private fun waitForLastPermit(deque: ConcurrentLinkedDeque<CountDownLatch>): CountDownLatch {
            var latch: CountDownLatch
            while ((deque.pollLast().also { latch = it }) == null) {
                java.lang.Thread.sleep(POLL_MS)
            }
            return latch
        }

        private fun createObjectCodecs(): ObjectCodecs {
            return ObjectCodecs(
                AutoRegistry.get().getBuilder().add(NotNestedSetCodec(NestedArrayCodec())).build()
            )
        }
    }
}
