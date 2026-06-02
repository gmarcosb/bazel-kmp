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
import com.google.devtools.build.lib.skyframe.serialization.AutoRegistry
import com.google.devtools.build.lib.skyframe.serialization.FingerprintValueCache
import com.google.devtools.build.lib.skyframe.serialization.FingerprintValueService
import com.google.devtools.build.lib.skyframe.serialization.FutureHelpers
import com.google.devtools.build.lib.skyframe.serialization.ObjectCodec
import com.google.devtools.build.lib.skyframe.serialization.ObjectCodecRegistry
import com.google.devtools.build.lib.skyframe.serialization.ObjectCodecs
import com.google.devtools.build.lib.skyframe.serialization.SerializationResult
import com.google.protobuf.ByteString
import java.util.Random

/**
 * Utility for testing serialization of given subjects.
 * 
 * 
 * Differs from [ObjectCodecTester] in that this uses the context to perform serialization
 * deserialization instead of a specific codec.
 */
class SerializationTester(subjects: com.google.common.collect.ImmutableList<*>) {
    /** Interface for testing successful deserialization of an object.  */
    fun interface VerificationFunction<T> {
        /**
         * Verifies that the original object was sufficiently serialized/deserialized.
         * 
         * 
         * *Must* throw an exception on failure.
         */
        @Throws(java.lang.Exception::class)
        fun verifyDeserialized(original: T?, deserialized: T?)
    }

    private val subjects: com.google.common.collect.ImmutableList<*>
    private val dependenciesBuilder: com.google.common.collect.ImmutableClassToInstanceMap.Builder<Any?> =
        com.google.common.collect.ImmutableClassToInstanceMap.builder<Any?>()
    private val additionalCodecs: java.util.ArrayList<ObjectCodec<*>?> = java.util.ArrayList<ObjectCodec<*>?>()
    private var memoize = false
    private var allowFutureBlocking = false
    private var objectCodecs: ObjectCodecs? = null

    // TODO: b/297857068 - consider splitting out a builder to cleanly separate this state
    // lazily initialized
    private var fingerprintValueService: FingerprintValueService? = null

    private var exerciseDeserializationInKeyValueStore = true

    private var verificationFunction: VerificationFunction<*> =
        com.google.devtools.build.lib.skyframe.serialization.testutils.SerializationTester.VerificationFunction { original: Any?, deserialized: Any? ->
            Truth.assertThat(deserialized).isEqualTo(original)
        }

    private var repetitions = 1

    constructor(vararg subjects: Any?) : this(com.google.common.collect.ImmutableList.copyOf<Any?>(subjects))

    init {
        com.google.common.base.Preconditions.checkArgument(!subjects.isEmpty())
        this.subjects = subjects
    }

    @com.google.errorprone.annotations.CanIgnoreReturnValue
    fun <D> addDependency(type: java.lang.Class<in D?>, dependency: D?): SerializationTester {
        dependenciesBuilder.put(type, dependency)
        return this
    }

    @com.google.errorprone.annotations.CanIgnoreReturnValue
    fun addDependencies(dependencies: com.google.common.collect.ClassToInstanceMap<*>): SerializationTester {
        dependenciesBuilder.putAll<Any?>(dependencies)
        return this
    }

    @com.google.errorprone.annotations.CanIgnoreReturnValue
    fun addCodec(codec: ObjectCodec<*>?): SerializationTester {
        additionalCodecs.add(codec)
        return this
    }

    @com.google.errorprone.annotations.CanIgnoreReturnValue
    fun makeMemoizing(): SerializationTester {
        this.memoize = true
        return this
    }

    @com.google.errorprone.annotations.CanIgnoreReturnValue
    fun makeMemoizingAndAllowFutureBlocking(allowFutureBlocking: Boolean): SerializationTester {
        makeMemoizing()
        this.allowFutureBlocking = allowFutureBlocking
        return this
    }

    @com.google.errorprone.annotations.CanIgnoreReturnValue
    fun setObjectCodecs(objectCodecs: ObjectCodecs?): SerializationTester {
        this.objectCodecs = objectCodecs
        return this
    }

    @com.google.errorprone.annotations.CanIgnoreReturnValue
    fun <T> setVerificationFunction(
        verificationFunction: VerificationFunction<T?>
    ): SerializationTester {
        this.verificationFunction = verificationFunction
        return this
    }

    /** Sets the number of times to repeat serialization and deserialization.  */
    @com.google.errorprone.annotations.CanIgnoreReturnValue
    fun setRepetitions(repetitions: Int): SerializationTester {
        this.repetitions = repetitions
        return this
    }

    @com.google.errorprone.annotations.CanIgnoreReturnValue
    fun setExerciseDeserializationInKeyValueStore(
        exerciseDeserializationInKeyValueStore: Boolean
    ): SerializationTester {
        this.exerciseDeserializationInKeyValueStore = exerciseDeserializationInKeyValueStore
        return this
    }

    @Throws(java.lang.Exception::class)
    private fun runTests(verifyStableSerialization: Boolean) {
        val codecs: ObjectCodecs = if (this.objectCodecs == null) createObjectCodecs() else this.objectCodecs
        testSerializeDeserialize(codecs)
        fingerprintValueService = null
        if (verifyStableSerialization) {
            testStableSerialization(codecs)
            fingerprintValueService = null
        }
        testDeserializeJunkData(codecs)
        fingerprintValueService = null
    }

    @Throws(java.lang.Exception::class)
    fun runTests() {
        runTests(true)
    }

    /**
     * Runs serialization tests without checking for stable serialization (`serialize(deserialize(serialize(x))) == serialize(x)`). Call [.runTests]} instead if
     * possible.
     * 
     * 
     * To be used only when serialization is not stable for good reasons: please understand the
     * cause before using this. Typically unstable serialization is the result of non-determinism in
     * your underlying objects, which can cause problems throughout Blaze by harming incrementality.
     * Only if you are sure that the non-determinism in your objects is not detectable in its public
     * interface or behavior (including `equals` if implemented) should you use this instead of
     * [.runTests].
     */
    @Throws(java.lang.Exception::class)
    fun runTestsWithoutStableSerializationCheck() {
        runTests(false)
    }

    private fun createObjectCodecs(): ObjectCodecs {
        val registry: ObjectCodecRegistry = AutoRegistry.get()
        val dependencies: com.google.common.collect.ImmutableClassToInstanceMap<Any?> = dependenciesBuilder.build()
        val registryBuilder: com.google.devtools.build.lib.skyframe.serialization.ObjectCodecRegistry.Builder =
            registry.getBuilder()
        for (`val` in dependencies.values()) {
            registryBuilder.addReferenceConstant(`val`)
        }
        for (codec in additionalCodecs) {
            registryBuilder.add(codec)
        }
        return ObjectCodecs(registryBuilder.build(), dependencies)
    }

    @Throws(com.google.devtools.build.lib.skyframe.serialization.SerializationException::class)
    private fun serialize(subject: Any?, codecs: ObjectCodecs): ByteString {
        if (!memoize) {
            return codecs.serialize(subject)
        }
        if (!allowFutureBlocking) {
            return codecs.serializeMemoized(subject)
        }
        val result: SerializationResult<ByteString> =
            codecs.serializeMemoizedAndBlocking(getFingerprintValueService(), subject)
        val writeFuture: com.google.common.util.concurrent.ListenableFuture<*>? = result.getFutureToBlockWritesOn()
        if (writeFuture != null) {
            val unused: Any? = FutureHelpers.waitForSerializationFuture(writeFuture)
        }
        return result.getObject()
    }

    @Throws(com.google.devtools.build.lib.skyframe.serialization.SerializationException::class)
    private fun deserialize(serialized: ByteString, codecs: ObjectCodecs): Any? {
        if (!memoize) {
            return codecs.deserialize(serialized)
        }
        return if (allowFutureBlocking)
            codecs.deserializeMemoizedAndBlocking(getFingerprintValueService(), serialized)
        else
            codecs.deserializeMemoized(serialized)
    }

    private fun getFingerprintValueService(): FingerprintValueService {
        if (fingerprintValueService == null) {
            fingerprintValueService =
                FingerprintValueService.Companion.createForTesting(
                    if (exerciseDeserializationInKeyValueStore)
                        com.google.devtools.build.lib.skyframe.serialization.FingerprintValueCache.SyncMode.NOT_LINKED
                    else
                        com.google.devtools.build.lib.skyframe.serialization.FingerprintValueCache.SyncMode.LINKED
                )
        }
        return fingerprintValueService
    }

    /** Runs serialization/deserialization tests.  */
    @Throws(java.lang.Exception::class)
    private fun testSerializeDeserialize(codecs: ObjectCodecs) {
        val timer: com.google.common.base.Stopwatch = com.google.common.base.Stopwatch.createStarted()
        var totalBytes = 0
        /* !!! Hit visitElement for element type: class org.jetbrains.kotlin.nj2k.tree.JKJavaForLoopStatement !!! */
        com.google.devtools.build.lib.skyframe.serialization.testutils.SerializationTester.Companion.logger.atInfo()
            .log(
                "%s total serialized bytes = %d, %s",
                subjects.get(0).getClass().getSimpleName(), totalBytes, timer
            )
    }

    /** Runs serialized bytes stability tests.  */
    @Throws(com.google.devtools.build.lib.skyframe.serialization.SerializationException::class)
    private fun testStableSerialization(codecs: ObjectCodecs) {
        for (subject in subjects) {
            val serialized: ByteString = serialize(subject, codecs)
            val deserialized = deserialize(serialized, codecs)
            val reserialized: ByteString = serialize(deserialized, codecs)
            Truth.assertThat(reserialized).isEqualTo(serialized)
        }
    }

    /** Runs junk-data recognition tests.  */
    private fun testDeserializeJunkData(codecs: ObjectCodecs) {
        val rng: Random = Random(0)
        /* !!! Hit visitElement for element type: class org.jetbrains.kotlin.nj2k.tree.JKJavaForLoopStatement !!! */
        Truth.assertWithMessage("all junk was parsed successfully").fail()
    }

    companion object {
        const val DEFAULT_JUNK_INPUTS: Int = 20
        const val JUNK_LENGTH_UPPER_BOUND: Int = 20

        private val logger: GoogleLogger = GoogleLogger.forEnclosingClass()
    }
}
