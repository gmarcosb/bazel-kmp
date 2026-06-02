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

import com.google.devtools.build.lib.skyframe.serialization.AsyncSerializationTask
import com.google.devtools.build.lib.skyframe.serialization.AutoRegistry
import com.google.devtools.build.lib.skyframe.serialization.DeserializationContext
import com.google.devtools.build.lib.skyframe.serialization.FingerprintValueService
import com.google.devtools.build.lib.skyframe.serialization.ImmutableDeserializationContext
import com.google.devtools.build.lib.skyframe.serialization.ImmutableSerializationContext
import com.google.devtools.build.lib.skyframe.serialization.MemoizingDeserializationContext
import com.google.devtools.build.lib.skyframe.serialization.MemoizingSerializationContext
import com.google.devtools.build.lib.skyframe.serialization.ObjectCodec
import com.google.devtools.build.lib.skyframe.serialization.ObjectCodecRegistry
import com.google.devtools.build.lib.skyframe.serialization.ProfileCollector
import com.google.devtools.build.lib.skyframe.serialization.SerializationResult
import com.google.devtools.build.lib.skyframe.serialization.SharedValueDeserializationContext
import com.google.devtools.build.lib.skyframe.serialization.SharedValueSerializationContext
import com.google.protobuf.ByteString
import com.google.protobuf.CodedInputStream
import com.google.protobuf.CodedOutputStream
import java.io.IOException


/**
 * Wrapper for the minutiae of serializing and deserializing objects using [ObjectCodec]s,
 * serving as a layer between the streaming-oriented [ObjectCodec] interface and users.
 */
class ObjectCodecs @kotlin.jvm.JvmOverloads constructor(
    codecRegistry: ObjectCodecRegistry? = AutoRegistry.get(),
    dependencies: com.google.common.collect.ImmutableClassToInstanceMap<Any?>? = com.google.common.collect.ImmutableClassToInstanceMap.of<Any?>()
) {
    private val serializationContext: ImmutableSerializationContext
    private val deserializationContext: ImmutableDeserializationContext

    /**
     * Creates an instance using the supplied `ObjectCodecRegistry` for looking up [ ]s.
     */
    init {
        serializationContext = ImmutableSerializationContext(codecRegistry, dependencies)
        deserializationContext = ImmutableDeserializationContext(codecRegistry, dependencies)
    }

    constructor(dependencies: com.google.common.collect.ImmutableClassToInstanceMap<Any?>?) : this(
        AutoRegistry.get(),
        dependencies
    )

    fun getCodecRegistryChecksum(): ByteArray? {
        return getCodecRegistry().getChecksum()
    }

    @com.google.common.annotations.VisibleForTesting // private
    fun getSerializationContextForTesting(): ImmutableSerializationContext {
        return serializationContext
    }

    @com.google.common.annotations.VisibleForTesting // private
    fun getMemoizingSerializationContextForTesting(): MemoizingSerializationContext {
        return MemoizingSerializationContext.Companion.createForTesting(getCodecRegistry(), getDependencies())
    }

    @com.google.common.annotations.VisibleForTesting // private
    fun getSharedValueSerializationContextForTesting(
        fingerprintValueService: FingerprintValueService?
    ): SharedValueSerializationContext {
        return SharedValueSerializationContext.Companion.createForTesting(
            getCodecRegistry(), getDependencies(), fingerprintValueService
        )
    }

    @com.google.common.annotations.VisibleForTesting // private
    fun withDependencyOverridesForTesting(dependencyOverrides: com.google.common.collect.ClassToInstanceMap<*>): ObjectCodecs {
        return ObjectCodecs(
            getCodecRegistry(), overrideDependencies(getDependencies(), dependencyOverrides)
        )
    }

    @com.google.common.annotations.VisibleForTesting // private
    fun withCodecOverridesForTesting(codecs: MutableList<ObjectCodec<*>?>): ObjectCodecs {
        val registryBuilder: com.google.devtools.build.lib.skyframe.serialization.ObjectCodecRegistry.Builder =
            getCodecRegistry().getBuilder()
        for (codec in codecs) {
            registryBuilder.add(codec)
        }
        return ObjectCodecs(registryBuilder.build(), getDependencies())
    }

    @com.google.common.annotations.VisibleForTesting // private
    fun getDeserializationContextForTesting(): ImmutableDeserializationContext {
        return deserializationContext
    }

    @com.google.common.annotations.VisibleForTesting // private
    fun getMemoizingDeserializationContextForTesting(): MemoizingDeserializationContext {
        return MemoizingDeserializationContext.Companion.createForTesting(getCodecRegistry(), getDependencies())
    }

    @com.google.common.annotations.VisibleForTesting // private
    fun getSharedValueDeserializationContextForTesting(
        fingerprintValueService: FingerprintValueService?
    ): SharedValueDeserializationContext {
        return SharedValueDeserializationContext.Companion.createForTesting(
            getCodecRegistry(), getDependencies(), fingerprintValueService
        )
    }

    /**
     * Serializes `obj` using a naive traversal.
     * 
     * 
     * This approach works well for simple, tree values. However, the naive traversal will stack
     * overflow on cyclic structures and can exhibit exponential complexity for DAGs.
     */
    @Throws(com.google.devtools.build.lib.skyframe.serialization.SerializationException::class)
    fun serialize(subject: Any?): ByteString? {
        val bytesOut: ByteString.Output = ByteString.newOutput()
        val codedOut: CodedOutputStream = CodedOutputStream.newInstance(bytesOut)
        try {
            serializationContext.serialize(subject, codedOut)
            codedOut.flush()
        } catch (e: IOException) {
            throw com.google.devtools.build.lib.skyframe.serialization.SerializationException(
                "Failed to serialize " + subject,
                e
            )
        }
        return bytesOut.toByteString()
    }

    @Throws(com.google.devtools.build.lib.skyframe.serialization.SerializationException::class)
    fun serialize(subject: Any?, codedOut: CodedOutputStream?) {
        try {
            serializationContext.serialize(subject, codedOut)
        } catch (e: IOException) {
            throw com.google.devtools.build.lib.skyframe.serialization.SerializationException(
                "Failed to serialize " + subject,
                e
            )
        }
    }

    /** Serializes `subject` using memoization.  */
    @Throws(com.google.devtools.build.lib.skyframe.serialization.SerializationException::class)
    fun serializeMemoized(subject: Any?): ByteString? {
        return MemoizingSerializationContext.Companion.serializeToByteString(
            getCodecRegistry(),
            getDependencies(),
            subject,
            DEFAULT_OUTPUT_CAPACITY,
            CodedOutputStream.DEFAULT_BUFFER_SIZE
        )
    }

    /**
     * Serializes `subject` using memoization, with `byte[]` output.
     * 
     * @param outputCapacity the initial capacity of the [ByteArrayOutputStream]
     * @param bufferSize size passed to [CodedOutputStream.newInstance]
     */
    @Throws(com.google.devtools.build.lib.skyframe.serialization.SerializationException::class)
    fun serializeMemoizedToBytes(
        subject: Any?,
        outputCapacity: Int,
        bufferSize: Int,
        profileCollector: ProfileCollector?
    ): ByteArray? {
        return MemoizingSerializationContext.Companion.serializeToBytes(
            getCodecRegistry(),
            getDependencies(),
            subject,
            outputCapacity,
            bufferSize,
            profileCollector
        )
    }

    /** Serializes `subject` using a [SharedValueSerializationContext].  */
    @Throws(com.google.devtools.build.lib.skyframe.serialization.SerializationException::class)
    fun serializeMemoizedAndBlocking(
        fingerprintValueService: FingerprintValueService?, subject: Any?
    ): SerializationResult<ByteString?>? {
        return SharedValueSerializationContext.Companion.serializeToResult(
            getCodecRegistry(), getDependencies(), fingerprintValueService, subject
        )
    }

    /**
     * Serializes `subject` using a [SharedValueSerializationContext].
     * 
     * @param dependencyOverrides dependencies to override, see [.overrideDependencies]
     */
    @Throws(com.google.devtools.build.lib.skyframe.serialization.SerializationException::class)
    fun serializeMemoizedAndBlocking(
        fingerprintValueService: FingerprintValueService?,
        dependencyOverrides: com.google.common.collect.ImmutableClassToInstanceMap<*>,
        subject: Any?
    ): SerializationResult<ByteString?>? {
        return SharedValueSerializationContext.Companion.serializeToResult(
            getCodecRegistry(),
            overrideDependencies(getDependencies(), dependencyOverrides),
            fingerprintValueService,
            subject
        )
    }

    fun serializeMemoizedAsync(
        fingerprintValueService: FingerprintValueService?,
        subject: Any?,
        profileCollector: ProfileCollector?
    ): AsyncSerializationTask {
        return SharedValueSerializationContext.Companion.serializeToResultAsync(
            getCodecRegistry(), getDependencies(), fingerprintValueService, subject, profileCollector
        )
    }

    @Throws(com.google.devtools.build.lib.skyframe.serialization.SerializationException::class)
    fun deserialize(data: ByteArray): Any? {
        return deserialize(CodedInputStream.newInstance(data))
    }

    @Throws(com.google.devtools.build.lib.skyframe.serialization.SerializationException::class)
    fun deserialize(data: ByteString): Any? {
        return deserialize(data.newCodedInput())
    }

    @Throws(com.google.devtools.build.lib.skyframe.serialization.SerializationException::class)
    fun deserialize(codedIn: CodedInputStream): Any? {
        return deserializeStreamFully(codedIn, deserializationContext)
    }

    @Throws(com.google.devtools.build.lib.skyframe.serialization.SerializationException::class)
    fun deserializeMemoized(data: ByteString): Any? {
        return MemoizingDeserializationContext.Companion.deserializeMemoized(
            getCodecRegistry(), getDependencies(), data
        )
    }

    @Throws(com.google.devtools.build.lib.skyframe.serialization.SerializationException::class)
    fun deserializeMemoized(data: ByteArray?): Any? {
        return MemoizingDeserializationContext.Companion.deserializeMemoized(
            getCodecRegistry(), getDependencies(), data
        )
    }

    @Throws(com.google.devtools.build.lib.skyframe.serialization.SerializationException::class)
    fun deserializeMemoizedAndBlocking(
        fingerprintValueService: FingerprintValueService?, data: ByteString
    ): Any? {
        return SharedValueDeserializationContext.Companion.deserializeWithSharedValues(
            getCodecRegistry(), getDependencies(), fingerprintValueService, data
        )
    }

    @Throws(com.google.devtools.build.lib.skyframe.serialization.SerializationException::class)
    fun deserializeMemoizedAndBlocking(
        fingerprintValueService: FingerprintValueService?,
        data: ByteString,
        dependencyOverrides: com.google.common.collect.ImmutableClassToInstanceMap<*>
    ): Any? {
        return SharedValueDeserializationContext.Companion.deserializeWithSharedValues(
            getCodecRegistry(),
            overrideDependencies(getDependencies(), dependencyOverrides),
            fingerprintValueService,
            data
        )
    }

    /**
     * Deserializes `data`, possibly with Skyframe lookups.
     * 
     * 
     * See comments at [SharedValueDeserializationContext.deserializeWithSkyframe] for
     * possible return values.
     */
    @Throws(com.google.devtools.build.lib.skyframe.serialization.SerializationException::class)
    fun deserializeWithSkyframe(
        fingerprintValueService: FingerprintValueService?, data: ByteString
    ): Any? {
        return deserializeWithSkyframe(fingerprintValueService, data.newCodedInput())
    }

    @Throws(com.google.devtools.build.lib.skyframe.serialization.SerializationException::class)
    fun deserializeWithSkyframe(
        fingerprintValueService: FingerprintValueService?, codedIn: CodedInputStream?
    ): Any? {
        return SharedValueDeserializationContext.Companion.deserializeWithSkyframe(
            getCodecRegistry(), getDependencies(), fingerprintValueService, codedIn
        )
    }

    // It's awkward that values are read from `serializationContext` instead of
    // `deserializationContext` and that they always have the same values. There's not much cohesion
    // between these two, however, so introducing an extra layer of indirection to store a (codec
    // registry, dependencies) tuple doesn't appear to be worth it.
    @com.google.common.annotations.VisibleForTesting // private
    fun getCodecRegistry(): ObjectCodecRegistry? {
        return serializationContext.getCodecRegistry()
    }

    private fun getDependencies(): com.google.common.collect.ImmutableClassToInstanceMap<Any?>? {
        return serializationContext.getDependencies()
    }

    companion object {
        /**
         * Default initial capacity of the output byte stream.
         * 
         * 
         * The same value that ByteArrayOutputStream's default constructor uses.
         */
        private const val DEFAULT_OUTPUT_CAPACITY = 32

        @Throws(com.google.devtools.build.lib.skyframe.serialization.SerializationException::class)
        fun deserializeStreamFully(codedIn: CodedInputStream, context: DeserializationContext): Any? {
            // Allows access to buffer without copying (although this means buffer may be pinned in memory).
            codedIn.enableAliasing(true)
            val result: Any?
            try {
                result = context.deserialize<Any?>(codedIn)
            } catch (e: IOException) {
                throw com.google.devtools.build.lib.skyframe.serialization.SerializationException(
                    "Failed to deserialize data",
                    e
                )
            }
            checkInputFullyConsumed(codedIn, result)
            return result
        }

        @Throws(com.google.devtools.build.lib.skyframe.serialization.SerializationException::class)
        fun checkInputFullyConsumed(codedIn: CodedInputStream, resultForDebugging: Any?) {
            try {
                if (!codedIn.isAtEnd()) {
                    throw com.google.devtools.build.lib.skyframe.serialization.SerializationException(
                        "input stream not exhausted after deserializing " + resultForDebugging
                    )
                }
            } catch (e: IOException) {
                throw com.google.devtools.build.lib.skyframe.serialization.SerializationException(
                    "Error checking for end of stream with " + resultForDebugging, e
                )
            }
        }

        /**
         * Returns a new dependency map composed by applying overrides to `dependencies`.
         * 
         * 
         * The given `dependencyOverrides` may contain keys already present (in which case the
         * dependency will be replaced) or new keys (in which case the dependency will be added).
         */
        private fun overrideDependencies(
            dependencies: com.google.common.collect.ImmutableClassToInstanceMap<Any?>,
            dependencyOverrides: com.google.common.collect.ClassToInstanceMap<*>
        ): com.google.common.collect.ImmutableClassToInstanceMap<Any?> {
            return com.google.common.collect.ImmutableClassToInstanceMap.builder<Any?>()
                .putAll<Any?>(
                    com.google.common.collect.Maps.filterKeys<java.lang.Class<*>?, Any?>(
                        dependencies,
                        com.google.common.base.Predicate { k: java.lang.Class<*>? -> !dependencyOverrides.containsKey(k) })
                )
                .putAll<Any?>(dependencyOverrides)
                .build()
        }
    }
}
