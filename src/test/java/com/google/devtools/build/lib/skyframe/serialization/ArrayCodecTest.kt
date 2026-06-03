// Copyright 2018 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.skyframe.serialization.testutils.SerializationTester

/** Tests for [ArrayCodec].  */
@RunWith(JUnit4::class)
class ArrayCodecTest {
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun objectArray() {
        val instance = arrayOfNulls<Any>(2)
        instance[0] = "hi"
        val inner = arrayOfNulls<Any>(2)
        inner[0] = "inner1"
        inner[1] = null
        instance[1] = inner
        SerializationTester(arrayOfNulls<Any>(0), instance)
            .setVerificationFunction({ original: Array<Any?>, deserialized: Array<Any?> ->
                verifyDeserialized(
                    original,
                    deserialized
                )
            })
            .runTests()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun typedArray() {
        SerializationTester(
            arrayOf<BigInteger?>(),
            arrayOf<BigInteger?>(BigInteger.ZERO),
            arrayOf<BigInteger?>(BigInteger.ZERO, BigInteger.ONE, BigInteger.TWO)
        )
            .addCodec(ArrayCodec.forComponentType(BigInteger::class.java))
            .runTests()
    }

    @org.junit.Test
    fun stackOverflowTransformedIntoSerializationException() {
        class Foo

        val foo = Foo()

        class FooCodec : ObjectCodec<Foo?> {
            val encodedClass: java.lang.Class<out Foo?>
                get() = Foo::class.java

            public override fun serialize(context: SerializationContext?, obj: Foo?, codedOut: CodedOutputStream?) {
                if (obj === foo) {
                    throw java.lang.StackOverflowError()
                }
            }

            public override fun deserialize(context: DeserializationContext?, codedIn: CodedInputStream?): Foo? {
                throw java.lang.UnsupportedOperationException()
            }
        }

        val codecs: ObjectCodecs =
            ObjectCodecs(ObjectCodecRegistry.newBuilder().add(FooCodec()).build())
        // Serialize an array containing a special object of a special class for which the code always
        // will throw a StackOverflowError. This way we exercise the catch block in ArrayCodec without
        // having to cause a real StackOverflowError to organically occur.
        //
        // We used to take that approach of trying to get a real StackOverflowError to occur, by
        // serializing an array-of-array-of-... nested thousands of times. But this approach was
        // brittle: On different machines/architectures that have a lot of stack memory, the JVM
        // wouldn't organically throw a StackOverflowError, and when we increased the nesting depth that
        // caused segfaults on machines/architectures with less stack memory.
        val array = arrayOf<Any?>(foo)
        org.junit.Assert.assertThrows<T?>(
            SerializationException::class.java,
            org.junit.function.ThrowingRunnable { codecs.serialize(array) })
    }

    companion object {
        private fun verifyDeserialized(original: Array<Any?>, deserialized: Array<Any?>) {
            Truth.assertThat<Any?>(deserialized).hasLength(original.size)
            for (i in deserialized.indices) {
                if (original[i] is Array<Any>) {
                    Truth.assertThat(deserialized[i]).isInstanceOf(Array<Any>::class.java)
                    Companion.verifyDeserialized(
                        (original[i] as kotlin.Array<kotlin.Any?>?)!!,
                        (deserialized[i] as kotlin.Array<kotlin.Any?>?)!!
                    )
                } else {
                    Truth.assertThat(deserialized[i]).isEqualTo(original[i])
                }
            }
        }
    }
}
