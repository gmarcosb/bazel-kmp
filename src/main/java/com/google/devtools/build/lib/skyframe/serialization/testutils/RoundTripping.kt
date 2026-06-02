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

import com.google.devtools.build.lib.skyframe.serialization.AutoRegistry
import com.google.devtools.build.lib.skyframe.serialization.DeserializationContext
import com.google.devtools.build.lib.skyframe.serialization.FingerprintValueService
import com.google.devtools.build.lib.skyframe.serialization.ObjectCodec
import com.google.devtools.build.lib.skyframe.serialization.ObjectCodecRegistry
import com.google.devtools.build.lib.skyframe.serialization.ObjectCodecs
import com.google.devtools.build.lib.skyframe.serialization.SerializationContext
import com.google.devtools.build.lib.skyframe.serialization.SerializationResult
import com.google.devtools.build.lib.skyframe.serialization.SkyframeDependencyException
import com.google.devtools.build.lib.skyframe.serialization.SkyframeLookupContinuation
import com.google.devtools.build.skyframe.SkyKey
import com.google.devtools.build.skyframe.state.EnvironmentForUtilities
import com.google.devtools.build.skyframe.state.EnvironmentForUtilities.ResultProvider
import com.google.protobuf.ByteString
import com.google.protobuf.CodedInputStream
import com.google.protobuf.CodedOutputStream
import java.io.IOException
import java.util.concurrent.ExecutionException

/** Helpers for round tripping in serialization tests.  */
object RoundTripping {
    /** Serialize a value to a new byte array.  */
    @Throws(IOException::class, com.google.devtools.build.lib.skyframe.serialization.SerializationException::class)
    fun <T> toBytes(context: SerializationContext?, codec: ObjectCodec<T?>, value: T?): ByteArray? {
        val bytes: java.io.ByteArrayOutputStream = java.io.ByteArrayOutputStream()
        val codedOut: CodedOutputStream = CodedOutputStream.newInstance(bytes)
        codec.serialize(context, value, codedOut)
        codedOut.flush()
        return bytes.toByteArray()
    }

    @Throws(IOException::class, com.google.devtools.build.lib.skyframe.serialization.SerializationException::class)
    fun <T> toBytes(serializationContext: SerializationContext, value: T?): ByteString? {
        val output: ByteString.Output = ByteString.newOutput()
        val codedOut: CodedOutputStream = CodedOutputStream.newInstance(output)
        serializationContext.serialize(value, codedOut)
        codedOut.flush()
        return output.toByteString()
    }

    @Throws(IOException::class, com.google.devtools.build.lib.skyframe.serialization.SerializationException::class)
    fun fromBytes(deserializationContext: DeserializationContext, bytes: ByteString): Any? {
        return deserializationContext.deserialize<Any?>(bytes.newCodedInput())
    }

    /** Deserialize a value from a byte array.  */
    @Throws(com.google.devtools.build.lib.skyframe.serialization.SerializationException::class, IOException::class)
    fun <T> fromBytes(context: DeserializationContext?, codec: ObjectCodec<T?>, bytes: ByteArray): T? {
        return codec.deserialize(context, CodedInputStream.newInstance(bytes))
    }

    @Throws(IOException::class, com.google.devtools.build.lib.skyframe.serialization.SerializationException::class)
    fun <T> roundTrip(value: T?, registry: ObjectCodecRegistry?): T? {
        return RoundTripping.roundTrip<T?>(value, ObjectCodecs(registry))
    }

    @Throws(IOException::class, com.google.devtools.build.lib.skyframe.serialization.SerializationException::class)
    fun <T> roundTrip(value: T?, dependencies: com.google.common.collect.ImmutableClassToInstanceMap<Any?>?): T? {
        return RoundTripping.roundTrip<T?>(value, ObjectCodecs(dependencies))
    }

    @Throws(IOException::class, com.google.devtools.build.lib.skyframe.serialization.SerializationException::class)
    fun <T> roundTrip(value: T?): T? {
        return RoundTripping.roundTrip<T?>(value, ObjectCodecs())
    }

    @Throws(com.google.devtools.build.lib.skyframe.serialization.SerializationException::class)
    private fun <T> roundTrip(value: T?, codecs: ObjectCodecs): T? {
        val result = codecs.deserialize(codecs.serialize(value)) as T?
        return result
    }

    @Throws(IOException::class, com.google.devtools.build.lib.skyframe.serialization.SerializationException::class)
    fun toBytesMemoized(original: Any?, registry: ObjectCodecRegistry?): ByteString? {
        return ObjectCodecs(registry).serializeMemoized(original)
    }

    @Throws(com.google.devtools.build.lib.skyframe.serialization.SerializationException::class)
    fun fromBytesMemoized(bytes: ByteString?, registry: ObjectCodecRegistry?): Any? {
        return ObjectCodecs(registry).deserializeMemoized(bytes)
    }

    @Throws(com.google.devtools.build.lib.skyframe.serialization.SerializationException::class)
    fun toBytesMemoizedAndBlocking(
        codecs: ObjectCodecs, fingerprintValueService: FingerprintValueService?, subject: Any?
    ): ByteString {
        val result: SerializationResult<ByteString> =
            codecs.serializeMemoizedAndBlocking(fingerprintValueService, subject)
        val futureToBlockWritesOn: com.google.common.util.concurrent.ListenableFuture<*>? =
            result.getFutureToBlockWritesOn()
        if (futureToBlockWritesOn != null) {
            try {
                val unused: Any? =
                    com.google.common.util.concurrent.Uninterruptibles.getUninterruptibly(futureToBlockWritesOn)
            } catch (e: ExecutionException) {
                throw com.google.devtools.build.lib.skyframe.serialization.SerializationException(
                    "waiting for futureToBlockWritesOn",
                    e.getCause()
                )
            }
        }
        return result.getObject()
    }

    @Throws(
        com.google.devtools.build.lib.skyframe.serialization.SerializationException::class,
        SkyframeDependencyException::class,
        MissingResultException::class
    )
    fun fromBytesWithSkyframe(
        codecs: ObjectCodecs,
        fingerprintValueService: FingerprintValueService?,
        resultProvider: ResultProvider,
        data: ByteString
    ): Any? {
        val result: Any? = codecs.deserializeWithSkyframe(fingerprintValueService, data)
        if (result is com.google.common.util.concurrent.ListenableFuture<*>) {
            val continuation: SkyframeLookupContinuation?
            try {
                continuation =
                    com.google.common.util.concurrent.Uninterruptibles.getUninterruptibly(result) as SkyframeLookupContinuation?
            } catch (e: ExecutionException) {
                throw com.google.devtools.build.lib.skyframe.serialization.SerializationException(
                    "waiting for remote values",
                    e.getCause()
                )
            }
            val recordingResultProvider = KeyRecordingResultProvider(resultProvider)
            val futureValue: com.google.common.util.concurrent.ListenableFuture<*>?
            try {
                futureValue = continuation.process(EnvironmentForUtilities(recordingResultProvider))
            } catch (e: java.lang.InterruptedException) {
                // Formally, an InterruptedException may occur when interacting with a LookupEnvironment,
                // but the EnvironmentForUtilities never throws it.
                throw java.lang.AssertionError("unexpected InterruptedException", e)
            }
            if (futureValue == null) {
                throw MissingResultException(recordingResultProvider.formatRecordedSkyKeys())
            }
            try {
                return com.google.common.util.concurrent.Uninterruptibles.getUninterruptibly(futureValue)
            } catch (e: ExecutionException) {
                throw com.google.devtools.build.lib.skyframe.serialization.SerializationException(
                    "waiting for bookkeeping and shared values",
                    e.getCause()
                )
            }
        }
        return result
    }

    @Throws(IOException::class, com.google.devtools.build.lib.skyframe.serialization.SerializationException::class)
    fun <T> roundTripMemoized(original: T?, registry: ObjectCodecRegistry?): T? {
        val codecs: ObjectCodecs = ObjectCodecs(registry)
        return codecs.deserializeMemoized(codecs.serializeMemoized(original)) as T?
    }

    @Throws(IOException::class, com.google.devtools.build.lib.skyframe.serialization.SerializationException::class)
    fun <T> roundTripMemoized(original: T?, vararg codecs: ObjectCodec<*>?): T? {
        val builder: com.google.devtools.build.lib.skyframe.serialization.ObjectCodecRegistry.Builder =
            AutoRegistry.get().getBuilder()
        for (codec in codecs) {
            builder.add(codec)
        }
        return RoundTripping.roundTripMemoized<T?>(original, builder.build())
    }

    @Throws(
        com.google.devtools.build.lib.skyframe.serialization.SerializationException::class,
        SkyframeDependencyException::class,
        MissingResultException::class
    )
    fun roundTripWithSkyframe(
        codecs: ObjectCodecs,
        fingerprintValueService: FingerprintValueService?,
        resultProvider: ResultProvider,
        subject: Any?
    ): Any? {
        val bytes: ByteString = toBytesMemoizedAndBlocking(codecs, fingerprintValueService, subject)
        return fromBytesWithSkyframe(codecs, fingerprintValueService, resultProvider, bytes)
    }

    @Throws(
        com.google.devtools.build.lib.skyframe.serialization.SerializationException::class,
        SkyframeDependencyException::class,
        MissingResultException::class
    )
    fun roundTripWithSkyframe(
        resultProvider: ResultProvider, subject: Any?
    ): Any? {
        return roundTripWithSkyframe(
            ObjectCodecs(), FingerprintValueService.Companion.createForTesting(), resultProvider, subject
        )
    }

    /**
     * Thrown if the `resultProvider` passed to [.fromBytesWithSkyframe] is missing
     * values.
     */
    class MissingResultException private constructor(message: String?) : java.lang.Exception(message)

    private class KeyRecordingResultProvider
        (delegate: ResultProvider) : ResultProvider {
        private val delegate: ResultProvider
        private val presentKeys: java.util.ArrayList<SkyKey> = java.util.ArrayList<SkyKey>()
        private val missingKeys: java.util.ArrayList<SkyKey> = java.util.ArrayList<SkyKey>()

        init {
            this.delegate = delegate
        }

        override fun getValueOrException(key: SkyKey?): Any? {
            val result: Any? = delegate.getValueOrException(key)
            if (result == null) {
                missingKeys.add(key)
            } else {
                presentKeys.add(key)
            }
            return result
        }

        fun formatRecordedSkyKeys(): String {
            val builder: java.lang.StringBuilder = java.lang.StringBuilder("successfully looked up=")
            formatSkyKeys(presentKeys, builder)
            builder.append(", missing=")
            formatSkyKeys(missingKeys, builder)
            return builder.toString()
        }

        companion object {
            private fun formatSkyKeys(keys: Iterable<SkyKey>, builder: java.lang.StringBuilder) {
                builder.append('[')
                var isFirst = true
                for (key in keys) {
                    if (isFirst) {
                        isFirst = false
                    } else {
                        builder.append(", ")
                    }
                    // Explicitly includes the type because many SkyKey types have String representations where
                    // this is unclear.
                    builder.append(key).append('<').append(key.getClass().getName()).append('>')
                }
                builder.append(']')
            }
        }
    }
}
