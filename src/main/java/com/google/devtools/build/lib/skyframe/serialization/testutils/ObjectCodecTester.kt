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

import com.google.common.flogger.GoogleLogger
import com.google.common.truth.Truth
import com.google.devtools.build.lib.skyframe.serialization.DeserializationContext
import com.google.devtools.build.lib.skyframe.serialization.ObjectCodec
import com.google.devtools.build.lib.skyframe.serialization.ObjectCodecs
import com.google.devtools.build.lib.skyframe.serialization.SerializationContext
import com.google.devtools.build.lib.skyframe.serialization.testutils.ObjectCodecTester
import com.google.devtools.build.lib.skyframe.serialization.testutils.RoundTripping
import com.google.protobuf.CodedInputStream
import java.io.IOException

/** Utility for testing [ObjectCodec] instances.  */
class ObjectCodecTester<T> private constructor(
    underTest: ObjectCodec<T?>,
    subjects: com.google.common.collect.ImmutableList<T?>,
    writeContext: SerializationContext?,
    readContext: DeserializationContext?,
    skipBadDataTest: Boolean,
    verificationFunction: VerificationFunction<T?>,
    repetitions: Int
) {
    /** Interface for testing successful deserialization of an object.  */
    fun interface VerificationFunction<T> {
        /**
         * Verify whether or not the original object was sufficiently serialized/deserialized. Typically
         * this will be some sort of assertion.
         * 
         * @throws Exception on verification failure
         */
        @Throws(java.lang.Exception::class)
        fun verifyDeserialized(original: T?, deserialized: T?)
    }

    private val underTest: ObjectCodec<T?>
    private val subjects: com.google.common.collect.ImmutableList<T?>
    private val writeContext: SerializationContext?
    private val readContext: DeserializationContext?
    private val skipBadDataTest: Boolean
    private val verificationFunction: VerificationFunction<T?>
    private val repetitions: Int

    init {
        this.underTest = underTest
        com.google.common.base.Preconditions.checkState(!subjects.isEmpty(), "No subjects provided")
        this.subjects = subjects
        this.writeContext = writeContext
        this.readContext = readContext
        this.skipBadDataTest = skipBadDataTest
        this.verificationFunction = verificationFunction
        this.repetitions = repetitions
    }

    @Throws(java.lang.Exception::class)
    private fun runTests() {
        testSerializeDeserialize()
        testStableSerialization()
        if (!skipBadDataTest) {
            testDeserializeJunkData()
        }
    }

    /** Runs serialization/deserialization tests.  */
    @Throws(java.lang.Exception::class)
    fun testSerializeDeserialize() {
        val timer: com.google.common.base.Stopwatch = com.google.common.base.Stopwatch.createStarted()
        var totalBytes = 0
        /* !!! Hit visitElement for element type: class org.jetbrains.kotlin.nj2k.tree.JKJavaForLoopStatement !!! */
        logger.atInfo().log(
            "%s total serialized bytes = %d, %s",
            underTest.getEncodedClass().getSimpleName(), totalBytes, timer
        )
    }

    /** Runs serialized bytes stability tests.  */
    @Throws(java.lang.Exception::class)
    fun testStableSerialization() {
        for (subject in subjects) {
            val serialized = toBytes(subject)
            val deserialized = fromBytes(serialized)
            val reserialized = toBytes(deserialized)
            Truth.assertThat(reserialized).isEqualTo(serialized)
        }
    }

    /** Runs junk-data recognition tests.  */
    fun testDeserializeJunkData() {
        try {
            underTest.deserialize(
                readContext, CodedInputStream.newInstance("junk".getBytes(java.nio.charset.StandardCharsets.UTF_8))
            )
            org.junit.Assert.fail("Expected exception")
        } catch (e: com.google.devtools.build.lib.skyframe.serialization.SerializationException) {
            // Expected.
        } catch (e: IOException) {
        }
    }

    @Throws(com.google.devtools.build.lib.skyframe.serialization.SerializationException::class, IOException::class)
    private fun fromBytes(bytes: ByteArray?): T? {
        return RoundTripping.fromBytes<T?>(readContext, underTest, bytes)
    }

    @Throws(IOException::class, com.google.devtools.build.lib.skyframe.serialization.SerializationException::class)
    private fun toBytes(subject: T?): ByteArray {
        return RoundTripping.toBytes<T?>(writeContext, underTest, subject)
    }

    /** Builder for [ObjectCodecTester].  */
    class Builder<T> private constructor(underTest: ObjectCodec<T?>) {
        private val underTest: ObjectCodec<T?>
        private val subjectsBuilder: com.google.common.collect.ImmutableList.Builder<T?> =
            com.google.common.collect.ImmutableList.builder<T?>()
        private val dependenciesBuilder: com.google.common.collect.ImmutableClassToInstanceMap.Builder<Any?> =
            com.google.common.collect.ImmutableClassToInstanceMap.builder<Any?>()
        private var skipBadDataTest = false
        private var verificationFunction: VerificationFunction<T?> =
            com.google.devtools.build.lib.skyframe.serialization.testutils.ObjectCodecTester.VerificationFunction { original: T?, deserialized: T? ->
                Truth.assertThat(deserialized).isEqualTo(original)
            }
        var repetitions: Int = 1

        init {
            this.underTest = underTest
        }

        /** Add subjects to be tested for serialization/deserialization.  */
        @java.lang.SafeVarargs
        fun addSubjects(vararg subjects: T?): Builder<T?> {
            return addSubjects(com.google.common.collect.ImmutableList.copyOf<T?>(subjects))
        }

        /** Add subjects to be tested for serialization/deserialization.  */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun addSubjects(subjects: com.google.common.collect.ImmutableList<T?>): Builder<T?> {
            subjectsBuilder.addAll(subjects)
            return this
        }

        /** Add subjects to be tested for serialization/deserialization.  */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun <D> addDependency(type: java.lang.Class<in D?>, dependency: D?): Builder<T?> {
            dependenciesBuilder.put(type, dependency)
            return this
        }

        /**
         * Skip tests that check for the ability to detect bad data. This may be useful for simpler
         * codecs which don't do any error verification.
         */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun skipBadDataTest(): Builder<T?> {
            this.skipBadDataTest = true
            return this
        }

        /**
         * Sets [ObjectCodecTester.VerificationFunction] for verifying deserialization. Default is
         * simple equality assertion, a custom version may be provided for more, or less, detailed
         * checks.
         */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun verificationFunction(verificationFunction: VerificationFunction<T?>?): Builder<T?> {
            this.verificationFunction =
                com.google.common.base.Preconditions.checkNotNull<VerificationFunction<T?>>(verificationFunction)
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun setRepetitions(repetitions: Int): Builder<T?> {
            this.repetitions = repetitions
            return this
        }

        /** Captures the state of this builder and run all associated tests.  */
        @Throws(java.lang.Exception::class)
        fun buildAndRunTests() {
            build().runTests()
        }

        /**
         * Creates a new [ObjectCodecTester] from this builder. Exposed to allow running tests
         * individually.
         */
        fun build(): ObjectCodecTester<T?> {
            val codecs: ObjectCodecs = ObjectCodecs(dependenciesBuilder.build())
            return ObjectCodecTester<T?>(
                underTest,
                subjectsBuilder.build(),
                codecs.getSerializationContextForTesting(),
                codecs.getDeserializationContextForTesting(),
                skipBadDataTest,
                verificationFunction,
                repetitions
            )
        }
    }

    companion object {
        private val logger: GoogleLogger = GoogleLogger.forEnclosingClass()

        /**
         * Create an [ObjectCodecTester.Builder] for the supplied instance. See
         * [ObjectCodecTester.Builder] for details.
         */
        fun <T> newBuilder(toTest: ObjectCodec<T?>): Builder<T?> {
            return com.google.devtools.build.lib.skyframe.serialization.testutils.ObjectCodecTester.Builder<T?>(toTest)
        }
    }
}
