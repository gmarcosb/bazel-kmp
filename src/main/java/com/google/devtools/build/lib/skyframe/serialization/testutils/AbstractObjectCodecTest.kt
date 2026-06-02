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
package com.google.devtools.build.lib.skyframe.serialization.testutils

import com.google.common.truth.Truth
import com.google.devtools.build.lib.skyframe.serialization.DeserializationContext
import com.google.devtools.build.lib.skyframe.serialization.ObjectCodec
import com.google.devtools.build.lib.skyframe.serialization.SerializationContext
import com.google.devtools.build.lib.skyframe.serialization.testutils.ObjectCodecTester
import com.google.devtools.build.lib.skyframe.serialization.testutils.RoundTripping
import org.junit.Before
import java.io.IOException

/**
 * Base class for [ObjectCodec] tests. This is a slim wrapper around [ObjectCodecTester]
 * and exists mostly to support existing tests.
 */
abstract class AbstractObjectCodecTest<T> {
    protected var underTest: ObjectCodec<T?>? = null
    protected var subjects: com.google.common.collect.ImmutableList<T?>? = null
    private var objectCodecTester: ObjectCodecTester<T?>? = null

    /** Construct with the given codec and subjects.  */
    protected constructor(underTest: ObjectCodec<T?>?, vararg subjects: T?) {
        this.underTest = underTest
        this.subjects = com.google.common.collect.ImmutableList.copyOf<T?>(subjects)
    }

    /**
     * Construct without a codec and subjects. They must be set in the subclass's constructor instead.
     * 
     * 
     * This is useful if the logic for creating the codec and/or subjects is non-trivial. Using
     * this super constructor, the logic can be placed in the subclass's constructor; whereas if using
     * the above super constructor, the logic must be factored into a static method.
     */
    protected constructor()

    @Before
    fun initialize() {
        com.google.common.base.Preconditions.checkNotNull<ObjectCodec<T?>?>(underTest)
        com.google.common.base.Preconditions.checkNotNull<com.google.common.collect.ImmutableList<T?>?>(subjects)
        objectCodecTester = ObjectCodecTester.Companion.newBuilder<T?>(underTest)
            .verificationFunction(
                com.google.devtools.build.lib.skyframe.serialization.testutils.ObjectCodecTester.VerificationFunction { original: T?, deserialized: T? ->
                    this.verifyDeserialization(
                        deserialized,
                        original
                    )
                })
            .addSubjects(subjects)
            .build()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSuccessfulSerializationDeserialization() {
        objectCodecTester.testSerializeDeserialize()
    }

    @org.junit.Test
    @Throws(java.lang.Exception::class)
    fun testSerializationRoundTripBytes() {
        objectCodecTester.testStableSerialization()
    }

    @org.junit.Test
    fun testDeserializeBadDataThrowsSerializationException() {
        objectCodecTester.testDeserializeJunkData()
    }

    @Throws(com.google.devtools.build.lib.skyframe.serialization.SerializationException::class, IOException::class)
    protected fun fromBytes(context: DeserializationContext?, bytes: ByteArray?): T? {
        return RoundTripping.fromBytes<T?>(context, underTest, bytes)
    }

    /** Serialize subject using the [ObjectCodec] under test.  */
    @Throws(IOException::class, com.google.devtools.build.lib.skyframe.serialization.SerializationException::class)
    protected fun toBytes(context: SerializationContext?, subject: T?): ByteArray? {
        return RoundTripping.toBytes<T?>(context, underTest, subject)
    }

    protected fun verifyDeserialization(deserialized: T?, subject: T?) {
        Truth.assertThat(deserialized).isEqualTo(subject)
    }
}
