// Copyright 2026 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.skyframe.serialization.WriteStatuses.SettableWriteStatus

@RunWith(JUnit4::class)
class ProfileRecorderTest {
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun buffering_mergesOnlyOnSuccessWithTrue() {
        val collector: ProfileCollector = ProfileCollector()
        var recorder: ProfileRecorder = ProfileRecorder(collector)

        val bytesOut: java.io.ByteArrayOutputStream = java.io.ByteArrayOutputStream()
        val codedOut: CodedOutputStream = CodedOutputStream.newInstance(bytesOut)

        // Record some samples
        recorder.pushLocation(codecA())
        recorder.recordBytesAndPopLocation(0, codedOut) // count=1, bytes=0

        recorder.pushLocation(codecA())
        recorder.pushLocation(codecB())
        recorder.recordBytesAndPopLocation(0, codedOut) // count=1, bytes=0
        recorder.recordBytesAndPopLocation(
            0, codedOut
        ) // count=1, bytes=0 (Wait, startBytes was 0, but codedOut moved)

        // Actually recordBytesAndPopLocation uses codedOut.getTotalBytesWritten() - startBytes.
        // If startBytes is 0 and we didn't write anything, byteCount is 0.

        // Collector should be empty
        Truth.assertThat(getSamples(collector.toProto())).isEmpty()

        // Trigger merge with false (not novel)
        recorder.onSuccess(false)
        Truth.assertThat(getSamples(collector.toProto())).isEmpty()

        // Create a new recorder for another batch
        recorder = ProfileRecorder(collector)
        recorder.pushLocation(codecA())
        // Write 10 bytes
        codedOut.writeRawBytes(ByteArray(10))
        recorder.recordBytesAndPopLocation(0, codedOut)

        // Trigger merge with true (novel)
        recorder.onSuccess(true)
        Truth.assertThat(getSamples(collector.toProto()))
            .containsExactly(
                com.google.devtools.build.lib.skyframe.serialization.ProfileRecorderTest.Sample(
                    getStackText(
                        codecA()
                    ), 1, 10
                )
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun byteScale_scalesBytesOnMerge() {
        val collector: ProfileCollector = ProfileCollector()
        val recorder: ProfileRecorder = ProfileRecorder(collector)

        val bytesOut: java.io.ByteArrayOutputStream = java.io.ByteArrayOutputStream()
        val codedOut: CodedOutputStream = CodedOutputStream.newInstance(bytesOut)

        recorder.pushLocation(codecA())
        codedOut.writeRawBytes(ByteArray(100))
        recorder.recordBytesAndPopLocation(0, codedOut)

        recorder.setByteScale(0.5)
        recorder.onSuccess(true)

        Truth.assertThat(getSamples(collector.toProto()))
            .containsExactly(
                com.google.devtools.build.lib.skyframe.serialization.ProfileRecorderTest.Sample(
                    getStackText(
                        codecA()
                    ), 1, 50
                )
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun registerWriteStatus_triggersMerge() {
        val collector: ProfileCollector = ProfileCollector()
        val recorder: ProfileRecorder = ProfileRecorder(collector)

        val bytesOut: java.io.ByteArrayOutputStream = java.io.ByteArrayOutputStream()
        val codedOut: CodedOutputStream = CodedOutputStream.newInstance(bytesOut)

        recorder.pushLocation(codecA())
        codedOut.writeRawBytes(ByteArray(20))
        recorder.recordBytesAndPopLocation(0, codedOut)

        val status: SettableWriteStatus = SettableWriteStatus()
        recorder.registerWriteStatus(status)

        Truth.assertThat(getSamples(collector.toProto())).isEmpty()

        status.markSuccess(true)
        Truth.assertThat(getSamples(collector.toProto()))
            .containsExactly(
                com.google.devtools.build.lib.skyframe.serialization.ProfileRecorderTest.Sample(
                    getStackText(
                        codecA()
                    ), 1, 20
                )
            )
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun recordBytesAndPopLocation_worksWithoutCodedOutputStream() {
        val collector: ProfileCollector = ProfileCollector()
        val recorder: ProfileRecorder = ProfileRecorder(collector)

        recorder.pushLocation(codecA())
        recorder.recordBytes(50)
        recorder.popLocation()

        recorder.onSuccess(true)
        Truth.assertThat(getSamples(collector.toProto()))
            .containsExactly(
                com.google.devtools.build.lib.skyframe.serialization.ProfileRecorderTest.Sample(
                    getStackText(
                        codecA()
                    ), 1, 50
                )
            )
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
            get() = com.google.devtools.build.lib.skyframe.serialization.ProfileRecorderTest.A::class.java

        public override fun serialize(c: SerializationContext?, o: A?, s: CodedOutputStream?) {}

        public override fun deserializeAsync(c: AsyncDeserializationContext?, s: CodedInputStream?): A? {
            return null
        }

        companion object {
            private val INSTANCE: CodecA =
                com.google.devtools.build.lib.skyframe.serialization.ProfileRecorderTest.CodecA()
        }
    }

    private class B

    private class CodecB : AsyncObjectCodec<B?>() {
        val encodedClass: java.lang.Class<B?>
            get() = com.google.devtools.build.lib.skyframe.serialization.ProfileRecorderTest.B::class.java

        public override fun serialize(c: SerializationContext?, o: B?, s: CodedOutputStream?) {}

        public override fun deserializeAsync(c: AsyncDeserializationContext?, s: CodedInputStream?): B? {
            return null
        }

        companion object {
            private val INSTANCE: CodecB =
                com.google.devtools.build.lib.skyframe.serialization.ProfileRecorderTest.CodecB()
        }
    }

    companion object {
        private fun codecA(): CodecA {
            return com.google.devtools.build.lib.skyframe.serialization.ProfileRecorderTest.CodecA.Companion.INSTANCE
        }

        private fun codecB(): CodecB {
            return com.google.devtools.build.lib.skyframe.serialization.ProfileRecorderTest.CodecB.Companion.INSTANCE
        }

        private fun getStackText(vararg codecs: ObjectCodec<*>): com.google.common.collect.ImmutableList<String?> {
            val text: com.google.common.collect.ImmutableList.Builder<String?> =
                com.google.common.collect.ImmutableList.builder<String?>()
            for (codec in codecs) {
                text.add(codec.getLocationText())
            }
            return text.build()
        }

        private fun getSamples(profile: Profile): com.google.common.collect.ImmutableList<Sample?> {
            val strings: MutableList<String?> = profile.getStringTableList()
            val functionNames: HashMap<Int?, String?> = HashMap<Int?, String?>()
            for (function in profile.getFunctionList()) {
                val id = function.getId() as Int
                functionNames.put(id, strings.get(function.getName() as Int))
            }
            val locationNames: HashMap<Int?, String?> = HashMap<Int?, String?>()
            for (location in profile.getLocationList()) {
                val id = location.getId() as Int
                val lines: MutableList<Line?> = location.getLineList()
                locationNames.put(id, functionNames.get(lines.get(0).getFunctionId() as Int))
            }

            val samples: com.google.common.collect.ImmutableList.Builder<Sample?> =
                com.google.common.collect.ImmutableList.builder<Sample?>()
            for (sample in profile.getSampleList()) {
                val stack: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
                    sample.getLocationIdList().stream()
                        .map({ id -> locationNames.get((id as Long).toInt()) })
                        .collect(com.google.common.collect.ImmutableList.toImmutableList<E?>())
                val values: Unit /* TODO: class org.jetbrains.kotlin.nj2k.types.JKJavaNullPrimitiveType */? =
                    sample.getValueList()
                samples.add(
                    com.google.devtools.build.lib.skyframe.serialization.ProfileRecorderTest.Sample(
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
