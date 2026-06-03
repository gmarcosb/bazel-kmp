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

import com.google.devtools.build.lib.skyframe.serialization.FutureHelpers.waitForSerializationFuture

@RunWith(JUnit4::class)
class ProfileCollectorTest {
    @org.junit.Test
    fun toProto_hasExpectedMetadata() {
        val collector: ProfileCollector = ProfileCollector()
        collector.recordSample(com.google.common.collect.ImmutableList.of<E?>(codecA(), codecB()), 10)
        collector.recordSample(com.google.common.collect.ImmutableList.of<E?>(codecA()), 20)

        val profile: Profile = collector.toProto()

        val stringTable: MutableList<String?>? = profile.getStringTableList()
        Truth.assertThat(stringTable).hasSize(7)
        Truth.assertThat(stringTable!!.subList(0, 5))
            .isEqualTo(
                com.google.common.collect.ImmutableList.of<String?>(
                    "",  // empty, required by the schema
                    ProfileCollector.SAMPLES,
                    ProfileCollector.COUNT,
                    ProfileCollector.STORAGE,
                    ProfileCollector.BYTES
                )
            )
        // The records are traversed in a non-deterministic order. Depending on which one comes first,
        // "a" or "b" might be the earlier entry in the string table.
        Truth.assertThat(stringTable.subList(5, 7)).containsExactly(CODEC_A_TEXT, CODEC_B_TEXT)

        assertThat(profile.getSampleTypeList())
            .containsExactly( // ProfileCollector.SAMPLES with units ProfileCollector.COUNT
                ValueType.newBuilder().setType(1).setUnit(2)
                    .build(),  // ProfileCollector.STORAGE with units ProfileCollector.BYTES
                ValueType.newBuilder().setType(3).setUnit(4).build()
            )
            .inOrder()

        Truth.assertThat(getSamples(profile))
            .containsExactly( // The stack trace is reversed with the leaf is position 0, as per the proto spec.
                com.google.devtools.build.lib.skyframe.serialization.ProfileCollectorTest.Sample(
                    com.google.common.collect.ImmutableList.of<String?>(
                        CODEC_B_TEXT, CODEC_A_TEXT
                    ), 1, 10
                ),  // This was originally 20 but became 10 by subtracting the child.
                com.google.devtools.build.lib.skyframe.serialization.ProfileCollectorTest.Sample(
                    com.google.common.collect.ImmutableList.of<String?>(
                        CODEC_A_TEXT
                    ), 1, 10
                )
            )
    }

    @org.junit.Test
    fun toProto_aggregatesSamples() {
        val collector: ProfileCollector = ProfileCollector()
        collector.recordSample(com.google.common.collect.ImmutableList.of<E?>(codecA(), codecB(), codecC()), 10)
        collector.recordSample(com.google.common.collect.ImmutableList.of<E?>(codecA(), codecB(), codecD()), 7)
        collector.recordSample(com.google.common.collect.ImmutableList.of<E?>(codecA(), codecB()), 20)
        collector.recordSample(com.google.common.collect.ImmutableList.of<E?>(codecA()), 25)

        collector.recordSample(com.google.common.collect.ImmutableList.of<E?>(codecA(), codecB(), codecD()), 2)
        collector.recordSample(com.google.common.collect.ImmutableList.of<E?>(codecA(), codecB()), 5)
        collector.recordSample(com.google.common.collect.ImmutableList.of<E?>(codecA()), 10)

        collector.recordSample(com.google.common.collect.ImmutableList.of<E?>(codecA()), 1)

        Truth.assertThat(getSamples(collector.toProto()))
            .containsExactly( // Only 1 entry. The stack trace is reversed with the leaf in position, as per the proto
                // spec.
                com.google.devtools.build.lib.skyframe.serialization.ProfileCollectorTest.Sample(
                    getStackText(
                        codecC(),
                        codecB(),
                        codecA()
                    ), 1, 10
                ),  // 2 samples, bytes = 2 + 7.
                com.google.devtools.build.lib.skyframe.serialization.ProfileCollectorTest.Sample(
                    getStackText(
                        codecD(),
                        codecB(),
                        codecA()
                    ), 2, 9
                ),  // 2 samples, bytes = 20 + 5 - (9 + 10) = 6.
                com.google.devtools.build.lib.skyframe.serialization.ProfileCollectorTest.Sample(
                    getStackText(
                        codecB(),
                        codecA()
                    ), 2, 6
                ),  // 3 samples, bytes = 25 + 10 + 1 - (20 + 5) = 11.
                com.google.devtools.build.lib.skyframe.serialization.ProfileCollectorTest.Sample(
                    getStackText(codecA()),
                    3,
                    11
                )
            )
    }

    @org.junit.Test
    fun recordSamples_mergesBatchesAndSubtractsAncestors() {
        val collector: ProfileCollector = ProfileCollector()

        val batch: HashMap<com.google.common.collect.ImmutableList<ProfilerLocationProvider?>?, ProfileCollector.Counts?> =
            HashMap<com.google.common.collect.ImmutableList<ProfilerLocationProvider?>?, ProfileCollector.Counts?>()
        val stackAB:  // false positive
                com.google.common.collect.ImmutableList<ProfilerLocationProvider?> =
            com.google.common.collect.ImmutableList.of<E?>(
                codecA(), codecB()
            )
        batch.put(
            stackAB,
            Counts(stackAB, AtomicInteger(1), AtomicInteger(100))
        )
        val stackABC:  // false positive
                com.google.common.collect.ImmutableList<ProfilerLocationProvider?> =
            com.google.common.collect.ImmutableList.of<E?>(codecA(), codecB(), codecC())
        batch.put(
            stackABC,
            Counts(stackABC, AtomicInteger(1), AtomicInteger(40))
        )

        collector.recordSamples(batch)

        Truth.assertThat(getSamples(collector.toProto()))
            .containsExactly(
                com.google.devtools.build.lib.skyframe.serialization.ProfileCollectorTest.Sample(
                    getStackText(
                        codecC(),
                        codecB(),
                        codecA()
                    ), 1, 40
                ),
                com.google.devtools.build.lib.skyframe.serialization.ProfileCollectorTest.Sample(
                    getStackText(
                        codecB(),
                        codecA()
                    ), 1, 60
                ),
                com.google.devtools.build.lib.skyframe.serialization.ProfileCollectorTest.Sample(
                    getStackText(codecA()),
                    0,
                    -100
                )
            )
    }

    @get:org.junit.Test
    val displayText_convertsLambdas: Unit
        get() {
            val anon: java.lang.Runnable = java.lang.Runnable {}
            // Anonymous classes have no canonical name.
            Truth.assertThat(anon.getClass().getCanonicalName()).isNull()

            val codec: DynamicCodec = DynamicCodec(anon.getClass())
            val text: String? = codec.getLocationText()
            Truth.assertThat(text)
                .isEqualTo(anon.getClass().getName() + "(" + DynamicCodec::class.java.getCanonicalName() + ")")
        }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun memoizingCodec_profilingCorrectlyAccountsForBackreferences() {
        // This test verifies that with the MemoizingSerializationContext, profiling correctly accounts
        // for memoized backreferences in the leaf and non-leaf case and nulls.

        // The example memoizes in a couple different places.

        val subject: java.util.ArrayList<ExampleLeaf?> = java.util.ArrayList<ExampleLeaf?>()
        // 1. Initial item.
        subject.add(ExampleLeaf("a", "b"))
        // 2. "a" is a memoized backreference to the 1st item's leaf "a".
        subject.add(ExampleLeaf("a", "c"))
        // 3. Entire item will be a memoized backreference to the 1st item.
        subject.add(ExampleLeaf("a", "b"))
        // 4. Exercises null leaves.
        subject.add(ExampleLeaf(null, null))
        // 5. Exercises a null non-leaf.
        subject.add(null)

        val codecs: ObjectCodecs = ObjectCodecs()
        val profileCollector: ProfileCollector = ProfileCollector()

        val bytes: ByteArray =
            codecs.serializeMemoizedToBytes(
                subject,  /* outputCapacity= */32,  /* bufferSize= */32, profileCollector
            )
        assertThat(codecs.deserializeMemoized(bytes)).isEqualTo(subject) // sanity check

        val samples: com.google.common.collect.ImmutableList<Sample?> = getSamples(profileCollector.toProto())

        // Verifies the object counts of the samples. Exact byte counts are omitted to avoid
        // brittleness.
        val bytesErasedSamples: com.google.common.collect.ImmutableList<Sample?> =
            samples.stream()
                .map<Sample?>(java.util.function.Function { sample: Sample? ->
                    com.google.devtools.build.lib.skyframe.serialization.ProfileCollectorTest.Sample(
                        sample!!.stack,
                        sample.count,
                        0
                    )
                })
                .collect(com.google.common.collect.ImmutableList.toImmutableList<Sample?>())
        val arrayListCodec: ArrayListCodec = ArrayListCodec()
        val exampleLeafCodec = ExampleLeafCodec()
        Truth.assertThat(bytesErasedSamples)
            .containsExactly(
                com.google.devtools.build.lib.skyframe.serialization.ProfileCollectorTest.Sample(
                    getStackText(arrayListCodec),
                    1,  // There's exactly 1 ArrayList.
                    0
                ),
                com.google.devtools.build.lib.skyframe.serialization.ProfileCollectorTest.Sample(
                    getStackText(
                        exampleLeafCodec,
                        arrayListCodec
                    ),  // The 4 samples here are the 1st-4th items. The null item doesn't increment the
                    // count.
                    4,
                    0
                ),
                com.google.devtools.build.lib.skyframe.serialization.ProfileCollectorTest.Sample(
                    getStackText(
                        stringCodec(),
                        exampleLeafCodec,
                        arrayListCodec
                    ),  // The 6 samples here are 2 each from the 1st, 2nd and 4th list items. Memoized
                    // leaves count as distinct samples. The 2 nulls in the 4th item can be counted as
                    // two Strings because their type is known to the parent codec. The Strings in the
                    // 3rd item are fully memoized away at the ExampleLeaf level.
                    6,
                    0
                )
            )

        // Verifies that the profiler sees exactly the same number of bytes as output.
        val profiledBytes: Int = samples.stream()
            .mapToInt(com.google.devtools.build.lib.skyframe.serialization.ProfileCollectorTest.Sample::bytes).sum()
        Truth.assertThat(profiledBytes).isEqualTo(bytes.size)
    }

    @kotlin.jvm.JvmRecord
    private data class ExampleLeaf(val first: String?, val second: String?)

    @com.google.errorprone.annotations.Keep
    private class ExampleLeafCodec : LeafObjectCodec<ExampleLeaf?>() {
        val encodedClass: java.lang.Class<ExampleLeaf?>
            get() = ExampleLeaf::class.java

        @Throws(SerializationException::class, IOException::class)
        public override fun serialize(
            context: LeafSerializationContext, obj: ExampleLeaf, codedOut: CodedOutputStream?
        ) {
            context.serializeLeaf(obj.first, stringCodec(), codedOut)
            context.serializeLeaf(obj.second, stringCodec(), codedOut)
        }

        @Throws(SerializationException::class, IOException::class)
        public override fun deserialize(context: LeafDeserializationContext, codedIn: CodedInputStream?): ExampleLeaf {
            return ExampleLeaf(
                context.deserializeLeaf(codedIn, stringCodec()),
                context.deserializeLeaf(codedIn, stringCodec())
            )
        }
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun sharedValue_isOnlySerializedOnceAndInANewStack() {
        val subject = ExampleLeafSharer()
        subject.leaf = ExampleLeaf("abc", "def")

        val codecs: ObjectCodecs = ObjectCodecs()
        val fingerprintValueService: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
            FingerprintValueService.createForTesting()
        val profileCollector: ProfileCollector = ProfileCollector()

        val runCount = 20

        val totalBytes: AtomicInteger = AtomicInteger()
        val writeStatuses: MutableList<com.google.common.util.concurrent.ListenableFuture<*>?> =
            Collections.synchronizedList<com.google.common.util.concurrent.ListenableFuture<*>?>(java.util.ArrayList<com.google.common.util.concurrent.ListenableFuture<*>?>())

        val allRunsDone: CountDownLatch = CountDownLatch(runCount)
        for (i in 0..<runCount) {
            ForkJoinPool.commonPool()
                .execute(
                    java.lang.Runnable {
                        try {
                            val result: SerializationResult<ByteString?>
                            try {
                                val asyncTask: AsyncSerializationTask =
                                    codecs.serializeMemoizedAsync(
                                        fingerprintValueService, subject, profileCollector
                                    )
                                asyncTask.run()
                                asyncTask.registerWriteStatus(WriteStatuses.immediateWriteStatus())
                                result = waitForSerializationFuture(asyncTask)
                            } catch (e: SerializationException) {
                                writeStatuses.add(com.google.common.util.concurrent.Futures.immediateFailedFuture<V?>(e))
                                return@execute
                            }
                            totalBytes.getAndAdd(result.getObject().size())

                            val writeStatus: com.google.common.util.concurrent.ListenableFuture<*>? =
                                result.getFutureToBlockWritesOn()
                            if (writeStatus != null) {
                                writeStatuses.add(writeStatus)
                            }
                        } finally {
                            allRunsDone.countDown()
                        }
                    })
        }
        allRunsDone.await()

        val unused: Any? = com.google.common.util.concurrent.Futures.whenAllSucceed<Any?>(writeStatuses).call<Any?>(
            java.util.concurrent.Callable { null },
            com.google.common.util.concurrent.MoreExecutors.directExecutor()
        ).get()

        val samples: com.google.common.collect.ImmutableList<Sample?> = getSamples(profileCollector.toProto())

        val topStack: com.google.common.collect.ImmutableList<String?>? =
            getStackText(ExampleLeafSharerCodec.Companion.INSTANCE)
        // Erases the bytes except for the top of the stack which is recorded in `totalBytes`. The other
        // bytes could be brittle to run assertions aren't easily recorded and would be brittle to
        // assert on.
        val bytesErasedSamples: com.google.common.collect.ImmutableList<Sample?> =
            samples.stream()
                .map<Sample?>(
                    java.util.function.Function { sample: Sample? ->
                        if (sample!!.stack == topStack)
                            sample
                        else
                            com.google.devtools.build.lib.skyframe.serialization.ProfileCollectorTest.Sample(
                                sample.stack,
                                sample.count,
                                0
                            )
                    })
                .collect(com.google.common.collect.ImmutableList.toImmutableList<Sample?>())
        Truth.assertThat(bytesErasedSamples)
            .containsExactly( //  The top level value is serialized runCount times and the bytes are precisely tracked
                //  in `totalBytes`.
                com.google.devtools.build.lib.skyframe.serialization.ProfileCollectorTest.Sample(
                    topStack,
                    runCount,
                    totalBytes.get()
                ),  // The shared ExampleLeaf instance is only serialized once. Note that this is a shared
                // value, it is serialized under a new, independent stack.
                com.google.devtools.build.lib.skyframe.serialization.ProfileCollectorTest.Sample(
                    getStackText(
                        DeferredExampleLeafCodec.Companion.INSTANCE
                    ), 1, 0
                ),
                com.google.devtools.build.lib.skyframe.serialization.ProfileCollectorTest.Sample(
                    getStackText(stringCodec(), DeferredExampleLeafCodec.Companion.INSTANCE),
                    2,  // "abc" and "def" in `subject.leaf`
                    0
                )
            )
    }

    private class ExampleLeafSharer {
        private var leaf: ExampleLeaf? = null // mutable simplifies deserialization code

        companion object {
            private fun setLeaf(sharer: ExampleLeafSharer, obj: Any?) {
                sharer.leaf = obj as ExampleLeaf?
            }
        }
    }

    @com.google.errorprone.annotations.Keep
    private class ExampleLeafSharerCodec : AsyncObjectCodec<ExampleLeafSharer?>() {
        val encodedClass: java.lang.Class<ExampleLeafSharer?>
            get() = ExampleLeafSharer::class.java

        @Throws(SerializationException::class, IOException::class)
        public override fun serialize(
            context: SerializationContext, obj: ExampleLeafSharer, codedOut: CodedOutputStream?
        ) {
            context.putSharedValue(
                obj.leaf,  /* distinguisher= */null, DeferredExampleLeafCodec.Companion.INSTANCE, codedOut
            )
        }

        @Throws(SerializationException::class, IOException::class)
        public override fun deserializeAsync(
            context: AsyncDeserializationContext, codedIn: CodedInputStream?
        ): ExampleLeafSharer {
            val result = ExampleLeafSharer()
            context.registerInitialValue(result)
            context.getSharedValue(
                codedIn,  /* distinguisher= */
                null,
                DeferredExampleLeafCodec.Companion.INSTANCE,
                result,
                { sharer: ExampleLeafSharer, obj: Any? -> ExampleLeafSharer.Companion.setLeaf(sharer, obj) })
            return result
        }

        companion object {
            private val INSTANCE = ExampleLeafSharerCodec()
        }
    }

    /** As [DeferredObjectCodec] as required by [SerializationContext.putSharedValue].  */
    private class DeferredExampleLeafCodec : DeferredObjectCodec<ExampleLeaf?>() {
        val encodedClass: java.lang.Class<ExampleLeaf?>
            get() = ExampleLeaf::class.java

        public override fun autoRegister(): Boolean {
            return false
        }

        @Throws(IOException::class, SerializationException::class)
        public override fun serialize(context: SerializationContext, obj: ExampleLeaf, codedOut: CodedOutputStream?) {
            context.serializeLeaf(obj.first, stringCodec(), codedOut)
            context.serializeLeaf(obj.second, stringCodec(), codedOut)
        }

        @Throws(SerializationException::class, IOException::class)
        public override fun deserializeDeferred(
            context: AsyncDeserializationContext, codedIn: CodedInputStream?
        ): DeferredValue<ExampleLeaf?> {
            val result =
                ExampleLeaf(
                    context.deserializeLeaf(codedIn, stringCodec()),
                    context.deserializeLeaf(codedIn, stringCodec())
                )
            return DeferredValue { result }
        }

        companion object {
            private val INSTANCE = DeferredExampleLeafCodec()
        }
    }

    private class Sample(stack: com.google.common.collect.ImmutableList<String?>?, count: Int, bytes: Int) {
        val stack: com.google.common.collect.ImmutableList<String?>?
        val count: Int
        val bytes: Int

        init {
            this.stack = stack
            this.count = count
            this.bytes = bytes
        }
    }

    private class A

    private class CodecA : AsyncObjectCodec<A?>() {
        val encodedClass: java.lang.Class<A?>
            get() = com.google.devtools.build.lib.skyframe.serialization.ProfileCollectorTest.A::class.java

        public override fun autoRegister(): Boolean {
            return false
        }

        public override fun serialize(context: SerializationContext?, obj: A?, codedOut: CodedOutputStream?) {
            throw java.lang.UnsupportedOperationException()
        }

        public override fun deserializeAsync(context: AsyncDeserializationContext?, codedIn: CodedInputStream?): A? {
            throw java.lang.UnsupportedOperationException()
        }

        companion object {
            private val INSTANCE: CodecA =
                com.google.devtools.build.lib.skyframe.serialization.ProfileCollectorTest.CodecA()
        }
    }

    private class B

    private class CodecB : AsyncObjectCodec<B?>() {
        val encodedClass: java.lang.Class<B?>
            get() = com.google.devtools.build.lib.skyframe.serialization.ProfileCollectorTest.B::class.java

        public override fun autoRegister(): Boolean {
            return false
        }

        public override fun serialize(context: SerializationContext?, obj: B?, codedOut: CodedOutputStream?) {
            throw java.lang.UnsupportedOperationException()
        }

        public override fun deserializeAsync(context: AsyncDeserializationContext?, codedIn: CodedInputStream?): B? {
            throw java.lang.UnsupportedOperationException()
        }

        companion object {
            private val INSTANCE: CodecB =
                com.google.devtools.build.lib.skyframe.serialization.ProfileCollectorTest.CodecB()
        }
    }

    private class C

    private class CodecC : AsyncObjectCodec<C?>() {
        val encodedClass: java.lang.Class<C?>
            get() = com.google.devtools.build.lib.skyframe.serialization.ProfileCollectorTest.C::class.java

        public override fun autoRegister(): Boolean {
            return false
        }

        public override fun serialize(context: SerializationContext?, obj: C?, codedOut: CodedOutputStream?) {
            throw java.lang.UnsupportedOperationException()
        }

        public override fun deserializeAsync(context: AsyncDeserializationContext?, codedIn: CodedInputStream?): C? {
            throw java.lang.UnsupportedOperationException()
        }

        companion object {
            private val INSTANCE = CodecC()
        }
    }

    private class D

    private class CodecD : AsyncObjectCodec<D?>() {
        val encodedClass: java.lang.Class<D?>
            get() = D::class.java

        public override fun autoRegister(): Boolean {
            return false
        }

        public override fun serialize(context: SerializationContext?, obj: D?, codedOut: CodedOutputStream?) {
            throw java.lang.UnsupportedOperationException()
        }

        public override fun deserializeAsync(context: AsyncDeserializationContext?, codedIn: CodedInputStream?): D? {
            throw java.lang.UnsupportedOperationException()
        }

        companion object {
            private val INSTANCE = CodecD()
        }
    }

    companion object {
        private fun codecA(): CodecA {
            return com.google.devtools.build.lib.skyframe.serialization.ProfileCollectorTest.CodecA.Companion.INSTANCE
        }

        private val CODEC_A_TEXT = (codecA().encodedClass.getCanonicalName()
                + "("
                + codecA().getClass().getCanonicalName()
                + ")")

        private fun codecB(): CodecB {
            return com.google.devtools.build.lib.skyframe.serialization.ProfileCollectorTest.CodecB.Companion.INSTANCE
        }

        private val CODEC_B_TEXT = (codecB().encodedClass.getCanonicalName()
                + "("
                + codecB().getClass().getCanonicalName()
                + ")")

        private fun codecC(): CodecC {
            return CodecC.Companion.INSTANCE
        }

        private fun codecD(): CodecD {
            return CodecD.Companion.INSTANCE
        }

        private fun getStackText(vararg codecs: ObjectCodec<*>): com.google.common.collect.ImmutableList<String?> {
            val text: com.google.common.collect.ImmutableList.Builder<String?> =
                com.google.common.collect.ImmutableList.builder<String?>()
            for (codec in codecs) {
                text.add(codec.getLocationText())
            }
            return text.build()
        }

        /** Converts the `profile` message into an easily inspectable list of [Sample]s.  */
        private fun getSamples(profile: Profile): com.google.common.collect.ImmutableList<Sample?> {
            val strings: MutableList<String?> = profile.getStringTableList()
            val functionNames: HashMap<Int?, String?> = HashMap<Int?, String?>()
            for (function in profile.getFunctionList()) {
                val id = function.getId() as Int
                val previous: String? = functionNames.putIfAbsent(id, strings.get(function.getName() as Int))
                Truth.assertWithMessage("duplicate function ID %s in %s", id, profile.getFunctionList())
                    .that(previous)
                    .isNull()
            }
            val locationNames: HashMap<Int?, String?> = HashMap<Int?, String?>()
            for (location in profile.getLocationList()) {
                val id = location.getId() as Int
                val lines: MutableList<Line?> = location.getLineList()
                Truth.assertWithMessage("location with unexpected number of lines: %s", location)
                    .that(lines)
                    .hasSize(1)
                Truth.assertWithMessage("location with id different from function id: %s", location)
                    .that(lines.get(0).getFunctionId())
                    .isEqualTo(id)
                val previous: String? = locationNames.putIfAbsent(id, functionNames.get(id))
                Truth.assertWithMessage("duplicate location ID %s in %s", id, profile.getLocationList())
                    .that(previous)
                    .isNull()
            }
            Truth.assertThat(locationNames).isEqualTo(functionNames)

            val samples: com.google.common.collect.ImmutableList.Builder<Sample?> =
                com.google.common.collect.ImmutableList.builder<Sample?>()
            for (sample in profile.getSampleList()) {
                val stack: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
                    sample.getLocationIdList().stream()
                        .map({ id -> locationNames.get((id as Long).toInt()) })
                        .collect(com.google.common.collect.ImmutableList.toImmutableList<E?>())
                val values: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
                    sample.getValueList()
                assertThat(values).hasSize(2)
                samples.add(
                    com.google.devtools.build.lib.skyframe.serialization.ProfileCollectorTest.Sample(
                        stack,
                        (values.get(0) as Long).toInt(),
                        (values.get(1) as Long).toInt()
                    )
                )
            }
            return samples.build()
        }
    }
}
