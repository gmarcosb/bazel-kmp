// Copyright 2020 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.cmdline.Label

/** Tests for [ImmutableClassToInstanceMapCodec].  */
@RunWith(JUnit4::class)
class ImmutableClassToInstanceMapCodecTest {
    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun smoke() {
        SerializationTester(
            com.google.common.collect.ImmutableClassToInstanceMap.of<B?>(),
            com.google.common.collect.ImmutableClassToInstanceMap.of<B?, T?>(String::class.java, "foo"),
            com.google.common.collect.ImmutableClassToInstanceMap.builder<Any?>()
                .put<T?>(Label::class.java, Label.create("myPackage", "myTarget"))
                .put<String?>(String::class.java, "bar")
                .put<Int?>(Int::class.javaPrimitiveType, 3)
                .build()
        ) // Check for order.
            .setVerificationFunction(
                VerificationFunction { deserialized, subject ->
                    assertThat(deserialized).isEqualTo(subject)
                    assertThat(deserialized).containsExactlyEntriesIn(subject).inOrder()
                } as VerificationFunction<com.google.common.collect.ImmutableClassToInstanceMap<*>?>)
            .runTests()
    }

    @org.junit.Test
    fun serializingErrorIncludesKeyStringAndValueClass() {
        val expected: SerializationException? =
            org.junit.Assert.assertThrows<T?>(
                SerializationException::class.java,
                org.junit.function.ThrowingRunnable {
                    RoundTripping.toBytesMemoized(
                        com.google.common.collect.ImmutableClassToInstanceMap.of<B?, T?>(
                            com.google.devtools.build.lib.skyframe.serialization.ImmutableClassToInstanceMapCodecTest.Dummy::class.java,
                            com.google.devtools.build.lib.skyframe.serialization.ImmutableClassToInstanceMapCodecTest.Dummy()
                        ),
                        AutoRegistry.get()
                            .getBuilder()
                            .add(
                                com.google.devtools.build.lib.skyframe.serialization.ImmutableClassToInstanceMapCodecTest.DummyThrowingCodec( /* throwsOnSerialization= */
                                    true
                                )
                            )
                            .build()
                    )
                })
        assertThat(expected)
            .hasMessageThat()
            .containsMatch("Exception while serializing value of type .*\\\$Dummy for key '.*\\\$Dummy'")
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun deserializingErrorIncludesKeyString() {
        val registry: ObjectCodecRegistry? =
            AutoRegistry.get()
                .getBuilder()
                .add(
                    com.google.devtools.build.lib.skyframe.serialization.ImmutableClassToInstanceMapCodecTest.DummyThrowingCodec( /*throwsOnSerialization=*/
                        false
                    )
                )
                .build()
        val codecs: ObjectCodecs = ObjectCodecs(registry)
        val data: ByteString? = codecs.serialize(
            com.google.common.collect.ImmutableClassToInstanceMap.of<B?, T?>(
                com.google.devtools.build.lib.skyframe.serialization.ImmutableClassToInstanceMapCodecTest.Dummy::class.java,
                com.google.devtools.build.lib.skyframe.serialization.ImmutableClassToInstanceMapCodecTest.Dummy()
            )
        )
        val expected: SerializationException? =
            org.junit.Assert.assertThrows<T?>(
                SerializationException::class.java,
                org.junit.function.ThrowingRunnable { codecs.deserialize(data) })
        assertThat(expected)
            .hasMessageThat()
            .containsMatch("Exception while deserializing value for key 'class .*\\\$Dummy'")
    }

    private class Dummy

    private class DummyThrowingCodec(private val throwsOnSerialization: Boolean) : ObjectCodec<Dummy?> {
        val encodedClass: java.lang.Class<Dummy?>
            get() = com.google.devtools.build.lib.skyframe.serialization.ImmutableClassToInstanceMapCodecTest.Dummy::class.java

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
}
