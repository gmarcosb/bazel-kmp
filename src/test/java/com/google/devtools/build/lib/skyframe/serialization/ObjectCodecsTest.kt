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
package com.google.devtools.build.lib.skyframe.serialization

import com.google.common.truth.Truth
import com.google.protobuf.ByteString
import com.google.protobuf.CodedInputStream
import com.google.protobuf.CodedOutputStream
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import org.mockito.ArgumentMatchers
import org.mockito.Mockito
import org.mockito.invocation.InvocationOnMock
import org.mockito.stubbing.Answer
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

/** Tests for [ObjectCodecs].  */
@RunWith(JUnit4::class)
class ObjectCodecsTest {
    /** Dummy ObjectCodec implementation so we can verify nice type system interaction.  */
    private class IntegerCodec : ObjectCodec<Int?> {
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
        public override fun deserialize(context: DeserializationContext?, codedIn: CodedInputStream): Int {
            return codedIn.readInt32()
        }
    }

    private val spyObjectCodec: ObjectCodec<Int?>? = Mockito.spy<IntegerCodec?>(IntegerCodec())

    private val underTest: ObjectCodecs = ObjectCodecs(
        ObjectCodecRegistry.newBuilder().add(spyObjectCodec).build(),
        com.google.common.collect.ImmutableClassToInstanceMap.of<B?>()
    )

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSerializeDeserializeUsesCustomLogicWhenAvailable() {
        val original = 12345

        Mockito.doAnswer(
            Answer { invocation: InvocationOnMock? ->
                val codedOutArg: CodedOutputStream = invocation.getArguments()[2] as CodedOutputStream
                codedOutArg.writeInt32NoTag(42)
                null
            })
            .`when`<Any?>(spyObjectCodec)
            .serialize(
                ArgumentMatchers.any<T?>(SerializationContext::class.java),
                ArgumentMatchers.eq<T?>(original),
                ArgumentMatchers.any<T?>(CodedOutputStream::class.java)
            )
        val readInteger: AtomicInteger = AtomicInteger(0)
        Mockito.doAnswer(
            Answer { invocation: InvocationOnMock? ->
                readInteger.set((invocation.getArguments()[1] as CodedInputStream).readInt32())
                original
            })
            .`when`<Any?>(spyObjectCodec)
            .deserialize(
                ArgumentMatchers.any<T?>(DeserializationContext::class.java),
                ArgumentMatchers.any<T?>(CodedInputStream::class.java)
            )

        val serialized: ByteString? = underTest.serialize(original)
        val deserialized: Any? = underTest.deserialize(serialized)
        Truth.assertThat(deserialized).isEqualTo(original)

        Truth.assertThat(readInteger.get()).isEqualTo(42)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun tooManyBytesCausesFailure() {
        Mockito.doReturn(1)
            .`when`<Any?>(spyObjectCodec)
            .deserialize(
                ArgumentMatchers.any<T?>(DeserializationContext::class.java),
                ArgumentMatchers.any<T?>(CodedInputStream::class.java)
            )
        Mockito.doAnswer(
            Answer { invocation: InvocationOnMock? ->
                (invocation.getArguments()[2] as CodedOutputStream).writeInt64NoTag(0xAAAAAA)
                null
            })
            .`when`<Any?>(spyObjectCodec)
            .serialize(
                ArgumentMatchers.any<T?>(SerializationContext::class.java),
                ArgumentMatchers.eq(1),
                ArgumentMatchers.any<T?>(CodedOutputStream::class.java)
            )
        val e: SerializationException? =
            org.junit.Assert.assertThrows<T?>(
                SerializationException::class.java,
                org.junit.function.ThrowingRunnable { underTest.deserialize(underTest.serialize(1)) })
        assertThat(e).hasMessageThat().isEqualTo("input stream not exhausted after deserializing 1")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSerializePropagatesSerializationExceptionFromCustomCodec() {
        val original: Int = java.lang.Integer.valueOf(12345)

        val staged: SerializationException = SerializationException("BECAUSE FAIL")
        doThrow(staged)
            .`when`<Any?>(spyObjectCodec)
            .serialize(
                ArgumentMatchers.any<T?>(SerializationContext::class.java),
                ArgumentMatchers.eq<T?>(original),
                ArgumentMatchers.any<T?>(CodedOutputStream::class.java)
            )
        val e: SerializationException? =
            org.junit.Assert.assertThrows<T?>(
                SerializationException::class.java,
                org.junit.function.ThrowingRunnable { underTest.serialize(original) })
        assertThat(e).isSameInstanceAs(staged)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSerializePropagatesIOExceptionFromCustomCodecsAsSerializationException() {
        val original: Int = java.lang.Integer.valueOf(12345)

        val staged: IOException = IOException("BECAUSE FAIL")
        Mockito.doThrow(staged)
            .`when`<Any?>(spyObjectCodec)
            .serialize(
                ArgumentMatchers.any<T?>(SerializationContext::class.java),
                ArgumentMatchers.eq<T?>(original),
                ArgumentMatchers.any<T?>(CodedOutputStream::class.java)
            )
        val e: SerializationException? =
            org.junit.Assert.assertThrows<T?>(
                SerializationException::class.java,
                org.junit.function.ThrowingRunnable { underTest.serialize(original) })
        assertThat(e).hasCauseThat().isSameInstanceAs(staged)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDeserializePropagatesSerializationExceptionFromCustomCodec() {
        val staged: SerializationException = SerializationException("BECAUSE FAIL")
        doThrow(staged)
            .`when`<Any?>(spyObjectCodec)
            .deserialize(
                ArgumentMatchers.any<T?>(DeserializationContext::class.java),
                ArgumentMatchers.any<T?>(CodedInputStream::class.java)
            )
        val thrown: SerializationException? =
            org.junit.Assert.assertThrows<T?>(
                SerializationException::class.java,
                org.junit.function.ThrowingRunnable { underTest.deserialize(underTest.serialize(1)) })
        assertThat(thrown).isSameInstanceAs(staged)
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDeserializePropagatesIOExceptionFromCustomCodecAsSerializationException() {
        val staged: IOException = IOException("BECAUSE FAIL")
        Mockito.doThrow(staged)
            .`when`<Any?>(spyObjectCodec)
            .deserialize(
                ArgumentMatchers.any<T?>(DeserializationContext::class.java),
                ArgumentMatchers.any<T?>(CodedInputStream::class.java)
            )
        val e: SerializationException? =
            org.junit.Assert.assertThrows<T?>(
                SerializationException::class.java,
                org.junit.function.ThrowingRunnable { underTest.deserialize(underTest.serialize(1)) })
        assertThat(e).hasCauseThat().isSameInstanceAs(staged)
    }

    @org.junit.Test
    fun testDeserializePropagatesSerializationExceptionFromDefaultCodec() {
        val serialized: ByteString = ByteString.copyFromUtf8("probably not serialized anything")

        org.junit.Assert.assertThrows<T?>(
            SerializationException::class.java,
            org.junit.function.ThrowingRunnable { underTest.deserialize(serialized) })
    }

    @org.junit.Test
    fun testSerializeFailsWhenNoCustomCodecAndFallbackDisabled() {
        val underTest: ObjectCodecs =
            ObjectCodecs(
                ObjectCodecRegistry.newBuilder().setAllowDefaultCodec(false).build(),
                com.google.common.collect.ImmutableClassToInstanceMap.of<B?>()
            )
        val expected: SerializationException.NoCodecException? =
            org.junit.Assert.assertThrows<T?>(
                SerializationException.NoCodecException::class.java,
                org.junit.function.ThrowingRunnable { underTest.serialize("Y") })
        assertThat(expected)
            .hasMessageThat()
            .isEqualTo("No codec available for class java.lang.String and default fallback disabled")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testDeserializeFailsWithNoCodecs() {
        val serialized: ByteString? = underTest.serialize(1)
        val underTest: ObjectCodecs =
            ObjectCodecs(
                ObjectCodecRegistry.newBuilder().setAllowDefaultCodec(false).build(),
                com.google.common.collect.ImmutableClassToInstanceMap.of<B?>()
            )
        org.junit.Assert.assertThrows<T?>(
            SerializationException.NoCodecException::class.java,
            org.junit.function.ThrowingRunnable { underTest.deserialize(serialized) })
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSerializeDeserialize() {
        val underTest: ObjectCodecs =
            ObjectCodecs(AutoRegistry.get(), com.google.common.collect.ImmutableClassToInstanceMap.of<B?>())
        Truth.assertThat(underTest.deserialize(underTest.serialize("hello")) as String?).isEqualTo("hello")
        assertThat(underTest.deserialize(underTest.serialize(null))).isNull()
    }

    private class MyException : java.lang.Exception()

    @org.junit.Test
    @Throws(SerializationException::class)
    fun exception() {
        val exception = MyException()
        // Force initialization of stack trace.
        val stackTrace: Array<java.lang.StackTraceElement?>? = exception.getStackTrace()
        val underTest: ObjectCodecs =
            ObjectCodecs(AutoRegistry.get(), com.google.common.collect.ImmutableClassToInstanceMap.of<B?>())
        Truth.assertThat<java.lang.StackTraceElement?>(
            (underTest.deserializeMemoized(underTest.serializeMemoized(exception)) as MyException)
                .getStackTrace()
        )
            .isEqualTo(stackTrace)
    }
}
