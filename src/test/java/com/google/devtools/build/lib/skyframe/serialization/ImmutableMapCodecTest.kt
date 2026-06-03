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

import com.google.devtools.build.lib.skyframe.serialization.SerializationException.NoCodecException

/** Tests for [ImmutableMapCodec].  */
@RunWith(JUnit4::class)
class ImmutableMapCodecTest {
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun smoke() {
        SerializationTester(
            com.google.common.collect.ImmutableMap.of<K?, V?>(),
            com.google.common.collect.ImmutableMap.of<K?, V?>("A", "//foo:A"),
            com.google.common.collect.ImmutableMap.of<K?, V?>("B", "//foo:B"),
            com.google.common.collect.ImmutableSortedMap.of<K?, V?>(),
            com.google.common.collect.ImmutableSortedMap.< K, V > of<K?, V?>("A", "//foo:A"),
            com.google.common.collect.ImmutableSortedMap.< K, V > of<K?, V?>("B", "//foo:B"),
            com.google.common.collect.ImmutableSortedMap.reverseOrder<Comparable<*>?, Any?>().put("a", "b")
                .put("c", "d").buildOrThrow()
        ) // Check for order.
            .setVerificationFunction(
                VerificationFunction { deserialized, subject ->
                    assertThat(deserialized).isEqualTo(subject)
                    assertThat(deserialized).containsExactlyEntriesIn(subject).inOrder()
                } as VerificationFunction<com.google.common.collect.ImmutableMap<*, *>?>)
            .runTests()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun immutableSortedMapRoundTripsWithTheSameComparator() {
        val deserialized: com.google.common.collect.ImmutableSortedMap<*, *> =
            RoundTripping.roundTrip(
                com.google.common.collect.ImmutableSortedMap.orderedBy<String?, Any?>(HELLO_FIRST_COMPARATOR)
                    .put("a", "b")
                    .put("hello", "there")
                    .buildOrThrow()
            )

        Truth.assertThat(deserialized).containsExactly("hello", "there", "a", "b")
        Truth.assertThat(deserialized.comparator()).isSameInstanceAs(HELLO_FIRST_COMPARATOR)
    }

    @org.junit.Test
    fun immutableSortedMapUnserializableComparatorFails() {
        val comparator: java.util.Comparator<String?> = selectedFirstComparator("c")

        val thrown: NoCodecException? =
            org.junit.Assert.assertThrows<T?>(
                NoCodecException::class.java,
                org.junit.function.ThrowingRunnable {
                    RoundTripping.roundTrip(
                        com.google.common.collect.ImmutableSortedMap.orderedBy<String?, String?>(comparator)
                            .put("a", "b")
                            .put("c", "d")
                            .buildOrThrow()
                    )
                })
        assertThat(thrown)
            .hasMessageThat()
            .startsWith("No default codec available for " + comparator.getClass().getName())
    }

    @org.junit.Test
    fun serializingErrorIncludesKeyStringAndValueClass() {
        val expected: SerializationException? =
            org.junit.Assert.assertThrows<T?>(
                SerializationException::class.java,
                org.junit.function.ThrowingRunnable {
                    RoundTripping.toBytesMemoized(
                        com.google.common.collect.ImmutableMap.of<K?, V?>(
                            "a",
                            com.google.devtools.build.lib.skyframe.serialization.ImmutableMapCodecTest.Dummy()
                        ),
                        AutoRegistry.get()
                            .getBuilder()
                            .add(
                                com.google.devtools.build.lib.skyframe.serialization.ImmutableMapCodecTest.DummyThrowingCodec( /* throwsOnSerialization= */
                                    true
                                )
                            )
                            .build()
                    )
                })
        assertThat(expected)
            .hasMessageThat()
            .containsMatch("Exception while serializing value of type .*\\\$Dummy for key 'a'")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun deserializingErrorIncludesKeyString() {
        val registry: ObjectCodecRegistry? =
            AutoRegistry.get()
                .getBuilder()
                .add(
                    com.google.devtools.build.lib.skyframe.serialization.ImmutableMapCodecTest.DummyThrowingCodec( /*throwsOnSerialization=*/
                        false
                    )
                )
                .build()
        val codecs: ObjectCodecs = ObjectCodecs(registry)
        val data: ByteString? = codecs.serialize(
            com.google.common.collect.ImmutableMap.of<K?, V?>(
                "a",
                com.google.devtools.build.lib.skyframe.serialization.ImmutableMapCodecTest.Dummy()
            )
        )
        val expected: SerializationException? =
            org.junit.Assert.assertThrows<T?>(
                SerializationException::class.java,
                org.junit.function.ThrowingRunnable { codecs.deserialize(data) })
        assertThat(expected)
            .hasMessageThat()
            .contains("Exception while deserializing value for key 'a'")
    }

    private class Dummy

    private class DummyThrowingCodec(private val throwsOnSerialization: Boolean) : ObjectCodec<Dummy?> {
        val encodedClass: java.lang.Class<Dummy?>
            get() = com.google.devtools.build.lib.skyframe.serialization.ImmutableMapCodecTest.Dummy::class.java

        @Throws(SerializationException::class)
        public override fun serialize(context: SerializationContext?, value: Dummy?, codedOut: CodedOutputStream?) {
            if (throwsOnSerialization) {
                throw SerializationException("Expected failure")
            }
        }

        @Throws(SerializationException::class)
        public override fun deserialize(context: DeserializationContext?, codedIn: CodedInputStream?): Dummy? {
            com.google.common.base.Preconditions.checkState(!throwsOnSerialization)
            throw SerializationException("Expected failure")
        }
    }

    companion object {
        @Suppress("unused")
        @SerializationConstant
        @VisibleForSerialization
        val ORDERING_REVERSE_NATURAL: java.util.Comparator<*> =
            com.google.common.collect.Ordering.natural<Comparable<*>?>().reverse<Comparable<*>?>()

        @SerializationConstant
        @VisibleForSerialization
        val HELLO_FIRST_COMPARATOR: java.util.Comparator<String?> = selectedFirstComparator("hello")

        private fun selectedFirstComparator(first: String?): java.util.Comparator<String?> {
            return java.util.Comparator { a: String?, b: String? ->
                if (a == b) {
                    return@Comparator 0
                }
                if (a == first) {
                    return@Comparator -1
                }
                if (b == first) {
                    return@Comparator 1
                }
                a!!.compareTo(b!!)
            }
        }
    }
}
