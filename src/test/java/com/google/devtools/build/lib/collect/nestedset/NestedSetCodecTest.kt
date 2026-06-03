// Copyright 2017 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.collect.nestedset

import com.google.devtools.build.lib.skyframe.serialization.PackedFingerprint.getFingerprintForTesting

/** Tests for [NestedSet] serialization.  */
@RunWith(JUnit4::class)
class NestedSetCodecTest {
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testAutoCodecedCodec() {
        val objectCodecs: ObjectCodecs =
            ObjectCodecs(
                AutoRegistry.get().getBuilder().setAllowDefaultCodec(true).build(),
                com.google.common.collect.ImmutableClassToInstanceMap.of<B?>()
            )
        NestedSetCodecTestUtils.checkCodec(objectCodecs, false, false)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testCodecWithInMemoryNestedSetStore() {
        val objectCodecs: ObjectCodecs = createCodecs(NestedSetStore.inMemory())
        NestedSetCodecTestUtils.checkCodec(objectCodecs, true, true)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun onlyOneReadPerArray() {
        val base: NestedSet<String?>? = NestedSetBuilder.create(Order.STABLE_ORDER, "a", "b")
        val top: NestedSet<String?>? = NestedSetBuilder.fromNestedSet(base).add("c").build()

        val reads: AtomicInteger = AtomicInteger()
        val fingerprintValueStore: FingerprintValueStore =
            object : FingerprintValueStore() {
                val delegate: FingerprintValueStore = FingerprintValueStore.inMemoryStore()

                public override fun put(fingerprint: KeyBytesProvider?, serializedBytes: ByteArray?): WriteStatus {
                    return delegate.put(fingerprint, serializedBytes)
                }

                @Throws(IOException::class)
                public override fun get(fingerprint: KeyBytesProvider?): com.google.common.util.concurrent.ListenableFuture<ByteArray?> {
                    reads.incrementAndGet()
                    return delegate.get(fingerprint)
                }
            }
        val fingerprintValueService: FingerprintValueService? =
            FingerprintValueService.createForTesting(fingerprintValueStore)

        val serializer: ObjectCodecs = createCodecs(createStore(fingerprintValueStore))
        val serializedBase: ByteString? =
            serializer.serializeMemoizedAndBlocking(fingerprintValueService, base).getObject()
        val serializedTop: ByteString? =
            serializer.serializeMemoizedAndBlocking(fingerprintValueService, top).getObject()

        // When deserializing top, we should perform 2 reads, one for each array in [[a, b], c].
        // Deliberately recreates the store to avoid getting a cached value.
        val deserializer: ObjectCodecs = createCodecs(createStore(fingerprintValueStore))
        val deserializedTop: NestedSet<*> = deserializer.deserializeMemoized(serializedTop) as NestedSet<*>
        assertThat(deserializedTop.toList()).containsExactly("a", "b", "c")
        Truth.assertThat(reads.get()).isEqualTo(2)

        // When deserializing base, we should not need to perform any additional reads since we have
        // already read [a, b] and it is still in memory.
        GcFinalization.awaitFullGc()
        val deserializedBase: NestedSet<*> = deserializer.deserializeMemoized(serializedBase) as NestedSet<*>
        assertThat(deserializedBase.toList()).containsExactly("a", "b")
        Truth.assertThat(reads.get()).isEqualTo(2)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun missingNestedSetException_hiddenUntilNestedSetIsConsumed() {
        val missingNestedSetException: Throwable =
            MissingFingerprintValueException(getFingerprintForTesting("fingerprint"))
        val fingerprintValueStore: FingerprintValueStore =
            object : FingerprintValueStore() {
                public override fun put(fingerprint: KeyBytesProvider?, serializedBytes: ByteArray?): WriteStatus {
                    return immediateWriteStatus()
                }

                public override fun get(fingerprint: KeyBytesProvider?): com.google.common.util.concurrent.ListenableFuture<ByteArray?> {
                    return com.google.common.util.concurrent.Futures.immediateFailedFuture<ByteArray?>(
                        missingNestedSetException
                    )
                }
            }
        val fingerprintValueService: FingerprintValueService? =
            FingerprintValueService.createForTesting(fingerprintValueStore)
        val bugReporter: BugReporter? = Mockito.mock<BugReporter?>(BugReporter::class.java)
        val serializer: ObjectCodecs = createCodecs(createStore(fingerprintValueStore))
        val deserializer: ObjectCodecs =
            createCodecs(createStoreWithBugReporter(fingerprintValueStore, bugReporter))

        val serialized: NestedSet<*>? = NestedSetBuilder.create(Order.STABLE_ORDER, "a", "b")
        val result: ByteString? =
            serializer.serializeMemoizedAndBlocking(fingerprintValueService, serialized).getObject()
        val deserialized: Any = deserializer.deserializeMemoized(result)

        Truth.assertThat(deserialized).isInstanceOf(NestedSet::class.java)
        org.junit.Assert.assertThrows<T?>(
            MissingFingerprintValueException::class.java, (deserialized as NestedSet<*>?)::toListInterruptibly
        )
        Mockito.verify<BugReporter?>(bugReporter).sendNonFatalBugReport(missingNestedSetException)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun exceptionOnPut_propagatedToFutureToBlockWritesOn() {
        val e: java.lang.Exception = java.lang.Exception("Something went wrong")
        val fingerprintValueStore: FingerprintValueStore =
            object : FingerprintValueStore() {
                public override fun put(fingerprint: KeyBytesProvider?, serializedBytes: ByteArray?): WriteStatus {
                    return immediateFailedWriteStatus(e)
                }

                public override fun get(fingerprint: KeyBytesProvider?): com.google.common.util.concurrent.ListenableFuture<ByteArray?>? {
                    throw java.lang.UnsupportedOperationException()
                }
            }
        val fingerprintValueService: FingerprintValueService? =
            FingerprintValueService.createForTesting(fingerprintValueStore)
        val codecs: ObjectCodecs = createCodecs(createStore(fingerprintValueStore))

        val serialized: NestedSet<*>? = NestedSetBuilder.create(Order.STABLE_ORDER, "a", "b")
        val result: SerializationResult<ByteString?> =
            codecs.serializeMemoizedAndBlocking(fingerprintValueService, serialized)
        val futureToBlockWritesOn: java.util.concurrent.Future<*> = result.getFutureToBlockWritesOn()
        val thrown: java.lang.Exception? = org.junit.Assert.assertThrows<ExecutionException?>(
            ExecutionException::class.java,
            org.junit.function.ThrowingRunnable { futureToBlockWritesOn.get() })
        Truth.assertThat(thrown).hasCauseThat().isSameInstanceAs(e)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun exceptionOnGet_hiddenUntilNestedSetIsConsumed() {
        val e: java.lang.Exception = java.lang.Exception("Something went wrong")
        val fingerprintValueStore: FingerprintValueStore =
            object : FingerprintValueStore() {
                public override fun put(fingerprint: KeyBytesProvider?, serializedBytes: ByteArray?): WriteStatus {
                    return immediateWriteStatus()
                }

                public override fun get(fingerprint: KeyBytesProvider?): com.google.common.util.concurrent.ListenableFuture<ByteArray?> {
                    return com.google.common.util.concurrent.Futures.immediateFailedFuture<ByteArray?>(e)
                }
            }
        val fingerprintValueService: FingerprintValueService? =
            FingerprintValueService.createForTesting(fingerprintValueStore)
        val serializer: ObjectCodecs = createCodecs(createStore(fingerprintValueStore))
        // Creates a separate deserializer so it does not see cached entries from the serializer.
        val deserializer: ObjectCodecs = createCodecs(createStore(fingerprintValueStore))

        val serialized: NestedSet<*>? = NestedSetBuilder.create(Order.STABLE_ORDER, "a", "b")
        val result: ByteString? =
            serializer.serializeMemoizedAndBlocking(fingerprintValueService, serialized).getObject()
        val deserialized: Any =
            deserializer.deserializeMemoizedAndBlocking(fingerprintValueService, result)

        Truth.assertThat(deserialized).isInstanceOf(NestedSet::class.java)
        val thrown: java.lang.Exception? = org.junit.Assert.assertThrows<java.lang.RuntimeException?>(
            java.lang.RuntimeException::class.java,
            (deserialized as NestedSet<*>?)::toList
        )
        Truth.assertThat(thrown).hasMessageThat().contains("Something went wrong")
    }

    /**
     * Tests that serialization of a `NestedSet<NestedSet<String>>` waits on the writes of the
     * inner NestedSets.
     */
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testNestedNestedSetSerialization() {
        val mockStorage: FingerprintValueStore = Mockito.mock<FingerprintValueStore>(FingerprintValueStore::class.java)
        val fingerprintValueService: FingerprintValueService? =
            FingerprintValueService.createForTesting(mockStorage)
        val innerWrite: SettableWriteStatus = SettableWriteStatus()
        val outerWrite: SettableWriteStatus = SettableWriteStatus()
        Mockito.`when`<T?>(
            mockStorage.put(
                ArgumentMatchers.any<T?>(),
                ArgumentMatchers.any<T?>()
            )
        ) // The write of the inner NestedSet {"a", "b"}
            .thenReturn(innerWrite) // The write of the inner NestedSet {"c", "d"}
            .thenReturn(innerWrite) // The write of the outer NestedSet {{"a", "b"}, {"c", "d"}}
            .thenReturn(outerWrite)
        val objectCodecs: ObjectCodecs = createCodecs(createStore(mockStorage))

        val nestedNestedSet: NestedSet<NestedSet<String?>?>? =
            NestedSetBuilder.create(
                Order.STABLE_ORDER,
                NestedSetBuilder.create(Order.STABLE_ORDER, "a", "b"),
                NestedSetBuilder.create(Order.STABLE_ORDER, "c", "d")
            )

        val result: SerializationResult<ByteString?> =
            objectCodecs.serializeMemoizedAndBlocking(fingerprintValueService, nestedNestedSet)
        outerWrite.markSuccess()
        assertThat(result.getFutureToBlockWritesOn().isDone()).isFalse()
        innerWrite.markSuccess()
        assertThat(result.getFutureToBlockWritesOn().isDone()).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testNestedNestedSetsWithCommonDependencyWaitOnSameInnerFuture() {
        val mockStorage: FingerprintValueStore = Mockito.mock<FingerprintValueStore>(FingerprintValueStore::class.java)
        val fingerprintValueService: FingerprintValueService? =
            FingerprintValueService.createForTesting(mockStorage)
        val sharedInnerWrite: SettableWriteStatus = SettableWriteStatus()
        val outerWrite: SettableWriteStatus = SettableWriteStatus()
        Mockito.`when`<T?>(
            mockStorage.put(
                ArgumentMatchers.any<T?>(),
                ArgumentMatchers.any<T?>()
            )
        ) // The write of the shared inner NestedSet {"a", "b"}
            .thenReturn(sharedInnerWrite) // The write of the inner NestedSet {"c", "d"}
            .thenReturn(immediateWriteStatus()) // The write of the outer NestedSet {{"a", "b"}, {"c", "d"}}
            .thenReturn(outerWrite) // The write of the inner NestedSet {"e", "f"}
            .thenReturn(immediateWriteStatus())
        val objectCodecs: ObjectCodecs = createCodecs(createStore(mockStorage))

        val sharedInnerNestedSet: NestedSet<String?>? = NestedSetBuilder.create(Order.STABLE_ORDER, "a", "b")
        val nestedNestedSet1: NestedSet<NestedSet<String?>?>? =
            NestedSetBuilder.create(
                Order.STABLE_ORDER,
                sharedInnerNestedSet,
                NestedSetBuilder.create(Order.STABLE_ORDER, "c", "d")
            )
        val nestedNestedSet2: NestedSet<NestedSet<String?>?>? =
            NestedSetBuilder.create(
                Order.STABLE_ORDER,
                sharedInnerNestedSet,
                NestedSetBuilder.create(Order.STABLE_ORDER, "e", "f")
            )

        val result1: SerializationResult<ByteString?> =
            objectCodecs.serializeMemoizedAndBlocking(fingerprintValueService, nestedNestedSet1)
        val result2: SerializationResult<ByteString?> =
            objectCodecs.serializeMemoizedAndBlocking(fingerprintValueService, nestedNestedSet2)
        outerWrite.markSuccess()
        assertThat(result1.getFutureToBlockWritesOn().isDone()).isFalse()
        assertThat(result2.getFutureToBlockWritesOn().isDone()).isFalse()
        sharedInnerWrite.markSuccess()
        assertThat(result1.getFutureToBlockWritesOn().isDone()).isTrue()
        assertThat(result2.getFutureToBlockWritesOn().isDone()).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSingletonNestedSetSerializedWithoutStore() {
        val mockNestedSetStore: NestedSetStore = Mockito.mock<NestedSetStore>(NestedSetStore::class.java)
        Mockito.`when`<T?>(
            mockNestedSetStore.computeFingerprintAndStore(
                ArgumentMatchers.any<T?>(),
                ArgumentMatchers.any<T?>()
            )
        )
            .thenThrow(java.lang.AssertionError("NestedSetStore should not have been used"))

        val objectCodecs: ObjectCodecs = createCodecs(mockNestedSetStore)
        val singletonNestedSet: NestedSet<String?>? =
            NestedSet.< String > builder < kotlin . String ? > (Order.STABLE_ORDER).add("a").build()
        objectCodecs.serialize(singletonNestedSet)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun serializationWeaklyCachesNestedSet() {
        // Avoid NestedSetBuilder.wrap/create - they use their own cache which interferes with what
        // we're testing.
        var nestedSet: NestedSet<*>? = NestedSetBuilder.stableOrder().add("a").add("b").build()
        val storageEndpoint: FingerprintValueStore? = FingerprintValueStore.inMemoryStore()
        val fingerprintValueService: FingerprintValueService? =
            FingerprintValueService.createForTesting(storageEndpoint)
        val codecs: ObjectCodecs = createCodecs(createStore(storageEndpoint))
        val unused: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            codecs.serializeMemoizedAndBlocking(fingerprintValueService, nestedSet)
        val ref: java.lang.ref.WeakReference<*> = java.lang.ref.WeakReference<Any?>(nestedSet)
        nestedSet = null
        GcFinalization.awaitClear(ref)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDeserializationInParallel() {
        val nestedSetFingerprintValueStore: FingerprintValueStore? =
            spy(FingerprintValueStore.inMemoryStore())
        val fingerprintValueService: FingerprintValueService? =
            FingerprintValueService.createForTesting(nestedSetFingerprintValueStore)
        val emptyNestedSetCache: NestedSetSerializationCache =
            Mockito.mock<NestedSetSerializationCache>(NestedSetSerializationCache::class.java)
        val nestedSetStore: NestedSetStore =
            createStoreWithCache(nestedSetFingerprintValueStore, emptyNestedSetCache)
        val objectCodecs: ObjectCodecs = createCodecs(nestedSetStore)

        val subset1: NestedSet<String?>? =
            NestedSet.< String > builder < kotlin . String ? > (Order.STABLE_ORDER).add("a").add("b").build()
        val subset1Future: com.google.common.util.concurrent.SettableFuture<ByteArray?> =
            com.google.common.util.concurrent.SettableFuture.create<ByteArray?>()
        val subset2: NestedSet<String?>? =
            NestedSet.< String > builder < kotlin . String ? > (Order.STABLE_ORDER).add("c").add("d").build()
        val subset2Future: com.google.common.util.concurrent.SettableFuture<ByteArray?> =
            com.google.common.util.concurrent.SettableFuture.create<ByteArray?>()
        val set: NestedSet<String?> =
            NestedSet.< String > builder < kotlin . String ? > (Order.STABLE_ORDER)
                .addTransitive(subset1)
                .addTransitive(subset2)
                .build()

        // We capture the arguments to #put() during serialization, so as to correctly mock results for
        // #get()
        val fingerprintCaptor: ArgumentCaptor<PackedFingerprint?> =
            ArgumentCaptor.forClass<PackedFingerprint?, PackedFingerprint?>(PackedFingerprint::class.java)
        val fingerprint: PackedFingerprint? =
            nestedSetStore
                .computeFingerprintAndStore(
                    set.getChildren() as Array<Any?>?,
                    objectCodecs.getSharedValueSerializationContextForTesting(fingerprintValueService)
                )
                .fingerprint()
        Mockito.verify<Any?>(nestedSetFingerprintValueStore, Mockito.times(3))
            .put(fingerprintCaptor.capture(), ArgumentMatchers.any<T?>())
        Mockito.doReturn(subset1Future)
            .`when`<Any?>(nestedSetFingerprintValueStore)
            .get(fingerprintCaptor.getAllValues().get(0))
        Mockito.doReturn(subset2Future)
            .`when`<Any?>(nestedSetFingerprintValueStore)
            .get(fingerprintCaptor.getAllValues().get(1))
        Mockito.`when`<T?>(
            emptyNestedSetCache.putFutureIfAbsent(
                ArgumentMatchers.any<T?>(),
                ArgumentMatchers.any<T?>(),
                ArgumentMatchers.any<T?>()
            )
        ).thenReturn(null)

        val deserializationFuture: com.google.common.util.concurrent.ListenableFuture<Array<Any?>?> =
            nestedSetStore.getContentsAndDeserialize(
                fingerprint,
                objectCodecs.getSharedValueDeserializationContextForTesting(
                    fingerprintValueService
                )
            ) as com.google.common.util.concurrent.ListenableFuture<Array<Any?>?>
        // At this point, we expect deserializationFuture to be waiting on both of the underlying
        // fetches, which should have both been started.
        Truth.assertThat(deserializationFuture.isDone()).isFalse()
        Mockito.verify<Any?>(nestedSetFingerprintValueStore, Mockito.times(3)).get(ArgumentMatchers.any<T?>())

        // Once the underlying fetches complete, we expect deserialization to complete.
        subset1Future.set(ByteString.copyFrom("mock bytes", java.nio.charset.Charset.defaultCharset()).toByteArray())
        subset2Future.set(ByteString.copyFrom("mock bytes", java.nio.charset.Charset.defaultCharset()).toByteArray())
        Truth.assertThat(deserializationFuture.isDone()).isTrue()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun racingDeserialization() {
        val nestedSetFingerprintValueStore: FingerprintValueStore =
            Mockito.mock<FingerprintValueStore>(FingerprintValueStore::class.java)
        val nestedSetCache: NestedSetSerializationCache? =
            spy(NestedSetSerializationCache(BugReporter.defaultInstance()))
        val nestedSetStore: NestedSetStore =
            createStoreWithCache(nestedSetFingerprintValueStore, nestedSetCache)
        val deserializationContext: DeserializationContext? =
            Mockito.mock<DeserializationContext?>(DeserializationContext::class.java)
        val fingerprint: PackedFingerprint? = getFingerprintForTesting("fingerprint")
        // Future never completes, so we don't have to exercise that code in NestedSetStore.
        val storageFuture: com.google.common.util.concurrent.SettableFuture<ByteArray?> =
            com.google.common.util.concurrent.SettableFuture.create<ByteArray?>()
        Mockito.`when`<T?>(nestedSetFingerprintValueStore.get(fingerprint)).thenReturn(storageFuture)
        val fingerprintRequested: CountDownLatch = CountDownLatch(2)
        Mockito.doAnswer(
            Answer { invocation: InvocationOnMock? ->
                fingerprintRequested.countDown()
                val result: com.google.common.util.concurrent.ListenableFuture<Array<Any?>?>? =
                    invocation.callRealMethod() as com.google.common.util.concurrent.ListenableFuture<Array<Any?>?>?
                fingerprintRequested.await()
                result
            })
            .`when`<Any?>(nestedSetCache)
            .putFutureIfAbsent(< T > eq < T ? > (fingerprint), ArgumentMatchers.any<T?>(), ArgumentMatchers.any<T?>())
        val asyncResult: AtomicReference<com.google.common.util.concurrent.ListenableFuture<Array<Any?>?>?> =
            AtomicReference<com.google.common.util.concurrent.ListenableFuture<Array<Any?>?>?>()
        val asyncThread: java.lang.Thread =
            java.lang.Thread(
                java.lang.Runnable {
                    try {
                        val asyncContents: com.google.common.util.concurrent.ListenableFuture<Array<Any?>?>? =
                            nestedSetStore.getContentsAndDeserialize(
                                fingerprint, deserializationContext
                            ) as com.google.common.util.concurrent.ListenableFuture<Array<Any?>?>?
                        asyncResult.set(asyncContents)
                    } catch (e: IOException) {
                        throw java.lang.IllegalStateException(e)
                    }
                })
        asyncThread.start()
        val result: com.google.common.util.concurrent.ListenableFuture<Array<Any?>?> =
            nestedSetStore.getContentsAndDeserialize(
                fingerprint,
                deserializationContext
            ) as com.google.common.util.concurrent.ListenableFuture<Array<Any?>?>
        asyncThread.join()
        Mockito.verify<Any?>(nestedSetFingerprintValueStore).get(< T > eq < T ? > (fingerprint))
        Truth.assertThat(result).isSameInstanceAs(asyncResult.get())
        Truth.assertThat(result.isDone()).isFalse()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun racingSerialization() {
        // Exercises calling serialization twice for the same contents, concurrently, in 2 threads.
        val fingerprintValueStore: FingerprintValueStore? = spy(FingerprintValueStore.inMemoryStore())
        val nestedSetCache: NestedSetSerializationCache? =
            spy(NestedSetSerializationCache(BugReporter.defaultInstance()))
        val nestedSetStore: NestedSetStore = createStoreWithCache(fingerprintValueStore, nestedSetCache)
        val serializationContext: SerializationContext? =
            ObjectCodecs()
                .getSharedValueSerializationContextForTesting(
                    FingerprintValueService.createForTesting(fingerprintValueStore)
                )
        val contents = arrayOf<Any?>("contents")
        // NestedSet serialization of a `contents` Object[] performs the following steps in sequence.
        // 1. Checks if the fingerprint is already available via
        //    NestedSetSerializationCache.fingerprintForContents for `contents`.
        //    (If the fingerprint is already available, the computation is short-circuited.)
        // 2. Serializes to bytes and computes a fingerprint for those bytes.
        // 3. Puts the fingerprint into the cache.
        //
        // The latch here ensures that both threads do not short circuit in step 1.
        val fingerprintRequested: CountDownLatch = CountDownLatch(2)
        Mockito.doAnswer(
            Answer { invocation: InvocationOnMock? ->
                val result: PutOperation? = invocation.callRealMethod() as PutOperation?
                assertThat(result).isNull()
                // Allows the other thread to progress only after checking for the fingerprint.
                fingerprintRequested.countDown()
                // Waits for the other thread to finish checking the fingerprint before proceeding.
                fingerprintRequested.await()
                null
            })
            .`when`<Any?>(nestedSetCache)
            .fingerprintForContents(contents)
        val asyncResult: AtomicReference<PutOperation?> = AtomicReference<PutOperation?>()
        val asyncThread: java.lang.Thread =
            java.lang.Thread(
                java.lang.Runnable {
                    try {
                        asyncResult.set(
                            nestedSetStore.computeFingerprintAndStore(contents, serializationContext)
                        )
                    } catch (e: IOException) {
                        throw java.lang.IllegalStateException(e)
                    } catch (e: SerializationException) {
                        throw java.lang.IllegalStateException(e)
                    }
                })
        asyncThread.start()
        val result: PutOperation? = nestedSetStore.computeFingerprintAndStore(contents, serializationContext)
        asyncThread.join()

        Mockito.verify<Any?>(fingerprintValueStore).put(ArgumentMatchers.any<T?>(), ArgumentMatchers.any<T?>())
        assertThat(result).isSameInstanceAs(asyncResult.get())
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun writeFuturesWaitForTransitiveWrites() {
        val mockWriter: FingerprintValueStore = Mockito.mock<FingerprintValueStore>(FingerprintValueStore::class.java)
        val store: NestedSetStore = createStore(mockWriter)
        val serializationContext: SerializationContext? =
            createCodecs(store)
                .getSharedValueSerializationContextForTesting(
                    FingerprintValueService.createForTesting()
                )

        val bottomReadFuture: SettableWriteStatus = SettableWriteStatus()
        val middleReadFuture: SettableWriteStatus = SettableWriteStatus()
        val topReadFuture: SettableWriteStatus = SettableWriteStatus()
        Mockito.`when`<T?>(mockWriter.put(ArgumentMatchers.any<T?>(), ArgumentMatchers.any<T?>()))
            .thenReturn(bottomReadFuture, middleReadFuture, topReadFuture)

        val bottom: NestedSet<String?>? =
            NestedSetBuilder.< String > stableOrder < kotlin . String ? > ().add("bottom1").add("bottom2").build()
        val middle: NestedSet<String?>? =
            NestedSetBuilder.< String > stableOrder < kotlin . String ? > ()
                .add("middle1")
                .add("middle2")
                .addTransitive(bottom)
                .build()
        val top: NestedSet<String?>? =
            NestedSetBuilder.< String > stableOrder < kotlin . String ? > ()
                .add("top1")
                .add("top2")
                .addTransitive(middle)
                .build()

        val bottomWriteFuture: com.google.common.util.concurrent.ListenableFuture<*> =
            NestedSetCodecTestUtils.writeToStoreFuture(store, bottom, serializationContext)
        val middleWriteFuture: com.google.common.util.concurrent.ListenableFuture<*> =
            NestedSetCodecTestUtils.writeToStoreFuture(store, middle, serializationContext)
        val topWriteFuture: com.google.common.util.concurrent.ListenableFuture<*> =
            NestedSetCodecTestUtils.writeToStoreFuture(store, top, serializationContext)
        Truth.assertThat(bottomWriteFuture.isDone()).isFalse()
        Truth.assertThat(middleWriteFuture.isDone()).isFalse()
        Truth.assertThat(topWriteFuture.isDone()).isFalse()

        topReadFuture.markSuccess()
        middleReadFuture.markSuccess()
        Truth.assertThat(bottomWriteFuture.isDone()).isFalse()
        Truth.assertThat(middleWriteFuture.isDone()).isFalse()
        Truth.assertThat(topWriteFuture.isDone()).isFalse()

        bottomReadFuture.markSuccess()
        Truth.assertThat(bottomWriteFuture.isDone()).isTrue()
        Truth.assertThat(middleWriteFuture.isDone()).isTrue()
        Truth.assertThat(topWriteFuture.isDone()).isTrue()
    }

    internal class ColorfulThing(thing: String?, color: Color?) {
        val thing: String?
        val color: Color?

        init {
            this.color = color
            this.thing = thing
            java.util.Objects.requireNonNull<String?>(thing, "thing")
            java.util.Objects.requireNonNull<Any?>(color, "color")
        }

        companion object {
            fun of(thing: String?, color: Color?): ColorfulThing {
                return ColorfulThing(thing, color)
            }
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun cacheContext_disambiguatesIdenticalSerializedRepresentation() {
        // Serializes ColorfulThing without color, reading the color as a deserialization dependency.
        class BlackAndWhiteCodec : ObjectCodec<ColorfulThing?> {
            val encodedClass: java.lang.Class<ColorfulThing?>
                get() = ColorfulThing::class.java

            @Throws(SerializationException::class, IOException::class)
            public override fun serialize(
                context: SerializationContext, obj: ColorfulThing, codedOut: CodedOutputStream?
            ) {
                context.serialize(obj.thing, codedOut)
            }

            @Throws(SerializationException::class, IOException::class)
            public override fun deserialize(
                context: DeserializationContext,
                codedIn: CodedInputStream?
            ): ColorfulThing {
                val thing: String? = context.deserialize(codedIn)
                val color: Color? = context.getDependency(Color::class.java)
                return ColorfulThing.Companion.of(thing, color)
            }
        }

        val fingerprintValueStore: FingerprintValueStore? = FingerprintValueStore.inMemoryStore()
        val fingerprintValueService: FingerprintValueService? =
            FingerprintValueService.createForTesting(fingerprintValueStore)

        val codecs: ObjectCodecs =
            createCodecs(
                createStoreWithCacheContext(
                    fingerprintValueStore,
                    java.util.function.Function { ctx: SerializationDependencyProvider? -> ctx.getDependency(Color::class.java) }),
                BlackAndWhiteCodec()
            )

        val stuff: MutableList<String?> = com.google.common.collect.ImmutableList.of<String?>("bird", "paint", "shoes")
        val redStuff: NestedSet<ColorfulThing?>? =
            NestedSetBuilder.wrap(
                Order.STABLE_ORDER,
                com.google.common.collect.Lists.transform<F?, T?>(
                    stuff,
                    com.google.common.base.Function { thing: F? -> ColorfulThing.Companion.of(thing, Color.RED) })
            )
        val blueStuff: NestedSet<ColorfulThing?>? =
            NestedSetBuilder.wrap(
                Order.STABLE_ORDER,
                com.google.common.collect.Lists.transform<F?, T?>(
                    stuff,
                    com.google.common.base.Function { thing: F? -> ColorfulThing.Companion.of(thing, Color.BLUE) })
            )

        val redCodecs: ObjectCodecs =
            codecs.withDependencyOverridesForTesting(
                com.google.common.collect.ImmutableClassToInstanceMap.of<B?, T?>(Color::class.java, Color.RED)
            )
        val redSerialized: ByteString? =
            redCodecs.serializeMemoizedAndBlocking(fingerprintValueService, redStuff).getObject()
        val blueCodecs: ObjectCodecs =
            codecs.withDependencyOverridesForTesting(
                com.google.common.collect.ImmutableClassToInstanceMap.of<B?, T?>(Color::class.java, Color.BLUE)
            )
        val blueSerialized: ByteString? =
            blueCodecs.serializeMemoizedAndBlocking(fingerprintValueService, blueStuff).getObject()
        Truth.assertThat(redSerialized).isEqualTo(blueSerialized)

        val redDeserialized: Any? =
            redCodecs.deserializeMemoizedAndBlocking(fingerprintValueService, redSerialized)
        val blueDeserialized: Any? =
            blueCodecs.deserializeMemoizedAndBlocking(fingerprintValueService, blueSerialized)
        Truth.assertThat(redDeserialized).isSameInstanceAs(redStuff)
        Truth.assertThat(blueDeserialized).isSameInstanceAs(blueStuff)

        // Test that we can deserialize in a context that was not previously serialized.
        val greenCodecs: ObjectCodecs =
            codecs.withDependencyOverridesForTesting(
                com.google.common.collect.ImmutableClassToInstanceMap.of<B?, T?>(Color::class.java, Color.GREEN)
            )
        val greenDeserialized: Any =
            greenCodecs.deserializeMemoizedAndBlocking(fingerprintValueService, redSerialized)
        Truth.assertThat(greenDeserialized).isInstanceOf(NestedSet::class.java)
        assertThat((greenDeserialized as NestedSet<*>).toList())
            .isEqualTo(
                com.google.common.collect.Lists.transform<String?, ColorfulThing?>(
                    stuff,
                    com.google.common.base.Function { thing: String? ->
                        ColorfulThing.Companion.of(
                            thing,
                            Color.GREEN
                        )
                    })
            )
    }

    companion object {
        private fun createStore(fingerprintValueStore: FingerprintValueStore?): NestedSetStore {
            return createStoreWithBugReporter(fingerprintValueStore, BugReporter.defaultInstance())
        }

        private fun createStoreWithBugReporter(
            fingerprintValueStore: FingerprintValueStore?, bugReporter: BugReporter?
        ): NestedSetStore {
            return NestedSetStore(
                fingerprintValueStore,
                com.google.common.util.concurrent.MoreExecutors.directExecutor(),
                bugReporter,
                NestedSetStore.NO_CONTEXT
            )
        }

        private fun createStoreWithCache(
            fingerprintValueStore: FingerprintValueStore?, cache: NestedSetSerializationCache?
        ): NestedSetStore {
            return NestedSetStore(
                fingerprintValueStore,
                com.google.common.util.concurrent.MoreExecutors.directExecutor(),
                cache,
                NestedSetStore.NO_CONTEXT
            )
        }

        private fun createStoreWithCacheContext(
            fingerprintValueStore: FingerprintValueStore?,
            cacheContextFn: java.util.function.Function<SerializationDependencyProvider?, *>?
        ): NestedSetStore {
            return NestedSetStore(
                fingerprintValueStore,
                com.google.common.util.concurrent.MoreExecutors.directExecutor(),
                BugReporter.defaultInstance(),
                cacheContextFn
            )
        }

        private fun createCodecs(store: NestedSetStore?, vararg codecs: ObjectCodec<*>?): ObjectCodecs {
            val registry: ObjectCodecRegistry.Builder =
                AutoRegistry.get()
                    .getBuilder()
                    .setAllowDefaultCodec(true)
                    .add(NestedSetCodecWithStore(store))
            for (codec in codecs) {
                registry.add(codec)
            }
            return ObjectCodecs(
                registry.build(),  /*dependencies=*/
                com.google.common.collect.ImmutableClassToInstanceMap.of<B?>()
            )
        }
    }
}
