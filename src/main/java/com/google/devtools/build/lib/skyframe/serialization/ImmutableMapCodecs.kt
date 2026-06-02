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

import com.google.devtools.build.lib.skyframe.serialization.AsyncDeserializationContext
import com.google.devtools.build.lib.skyframe.serialization.DeferredObjectCodec
import com.google.devtools.build.lib.skyframe.serialization.DeferredObjectCodec.DeferredValue
import com.google.devtools.build.lib.skyframe.serialization.MapHelpers
import com.google.devtools.build.lib.skyframe.serialization.SerializationContext
import com.google.devtools.build.lib.skyframe.serialization.autocodec.SerializationConstant
import com.google.devtools.build.lib.unsafe.UnsafeProvider
import com.google.protobuf.CodedInputStream
import com.google.protobuf.CodedOutputStream
import java.io.IOException

/**
 * Encodes an [ImmutableMap], which may be an [ImmutableSortedMap]. The iteration order
 * of the deserialized map is the same as the original map's.
 * 
 * 
 * Any [SerializationException] or [IOException] that arises while serializing or
 * deserializing a map entry's value (not its key) will be wrapped in a new [ ] using [SerializationException.propagate]. (Note that this preserves
 * the type of [SerializationException.NoCodecException] exceptions.) The message will include
 * the `toString()` of the entry's key. For errors that occur while serializing, it will also
 * include the class name of the entry's value. Errors that occur while serializing an entry key are
 * not affected.
 * 
 * 
 * Because of the ambiguity around the key type (Comparable in the case of [ ], arbitrary otherwise, we avoid specifying the key type as a parameter.
 */
object ImmutableMapCodecs {
    @SerializationConstant
    val ORDERING_NATURAL: java.util.Comparator<*> = com.google.common.collect.Ordering.natural<Comparable<*>?>()

    // In practice, the natural comparator seems to always be Ordering.natural(), but be flexible.
    @SerializationConstant
    val COMPARATOR_NATURAL_ORDER: java.util.Comparator<*>? = java.util.Comparator.naturalOrder<T?>()

    val IMMUTABLE_MAP_CODEC: ImmutableMapCodec = ImmutableMapCodec()

    private val COMPARATOR_OFFSET: Long

    init {
        try {
            COMPARATOR_OFFSET = UnsafeProvider.getFieldOffset(ImmutableSortedMapEntryBuffer::class.java, "comparator")
        } catch (e: java.lang.NoSuchFieldException) {
            throw java.lang.ExceptionInInitializerError(e)
        }
    }

    @com.google.errorprone.annotations.Keep // used reflectively
    private class ImmutableMapCodec : DeferredObjectCodec<com.google.common.collect.ImmutableMap<*, *>?>() {
        override fun getEncodedClass(): java.lang.Class<com.google.common.collect.ImmutableMap<*, *>?> {
            return com.google.common.collect.ImmutableMap::class.java
        }

        @Throws(com.google.devtools.build.lib.skyframe.serialization.SerializationException::class, IOException::class)
        override fun serialize(
            context: SerializationContext?,
            map: com.google.common.collect.ImmutableMap<*, *>,
            codedOut: CodedOutputStream
        ) {
            codedOut.writeInt32NoTag(map.size())
            MapHelpers.serializeMapEntries(context, map, codedOut)
        }

        @Throws(com.google.devtools.build.lib.skyframe.serialization.SerializationException::class, IOException::class)
        override fun deserializeDeferred(
            context: AsyncDeserializationContext?, codedIn: CodedInputStream
        ): DeferredValue<com.google.common.collect.ImmutableMap<*, *>?> {
            val size: Int = codedIn.readInt32()
            if (size < 0) {
                throw com.google.devtools.build.lib.skyframe.serialization.SerializationException("Expected non-negative length: " + size)
            }
            if (size == 0) {
                return DeferredValue { com.google.common.collect.ImmutableMap.of() }
            }

            val buffer = ImmutableMapEntryBuffer(size)
            MapHelpers.deserializeMapEntries(
                context,
                codedIn,
                buffer.keys,
                buffer.values
            )
            return buffer
        }
    }

    @com.google.errorprone.annotations.Keep // used reflectively
    private class ImmutableSortedMapCodec : DeferredObjectCodec<com.google.common.collect.ImmutableSortedMap<*, *>?>() {
        override fun getEncodedClass(): java.lang.Class<com.google.common.collect.ImmutableSortedMap<*, *>?> {
            return com.google.common.collect.ImmutableSortedMap::class.java
        }

        @Throws(com.google.devtools.build.lib.skyframe.serialization.SerializationException::class, IOException::class)
        override fun serialize(
            context: SerializationContext,
            map: com.google.common.collect.ImmutableSortedMap<*, *>,
            codedOut: CodedOutputStream
        ) {
            codedOut.writeInt32NoTag(map.size())
            if (map.isEmpty()) {
                return
            }

            context.serialize(map.comparator(), codedOut)
            MapHelpers.serializeMapEntries(context, map, codedOut)
        }

        @Throws(com.google.devtools.build.lib.skyframe.serialization.SerializationException::class, IOException::class)
        override fun deserializeDeferred(
            context: AsyncDeserializationContext, codedIn: CodedInputStream
        ): DeferredValue<com.google.common.collect.ImmutableSortedMap<*, *>?> {
            val size: Int = codedIn.readInt32()
            if (size < 0) {
                throw com.google.devtools.build.lib.skyframe.serialization.SerializationException("Expected non-negative length: " + size)
            }
            if (size == 0) {
                return DeferredValue { com.google.common.collect.ImmutableSortedMap.of() }
            }

            val buffer = ImmutableSortedMapEntryBuffer(size)
            context.deserialize(codedIn, buffer, COMPARATOR_OFFSET)
            MapHelpers.deserializeMapEntries(
                context,
                codedIn,
                buffer.keys,
                buffer.values
            )
            return buffer
        }
    }

    private open class EntryBuffer(size: Int) {
        val keys: Array<Any?>
        val values: Array<Any?>

        init {
            this.keys = arrayOfNulls<Any>(size)
            this.values = arrayOfNulls<Any>(size)
        }

        fun size(): Int {
            return keys.size
        }
    }

    private class ImmutableMapEntryBuffer(size: Int) : EntryBuffer(size),
        DeferredValue<com.google.common.collect.ImmutableMap<*, *>?> {
        override fun call(): com.google.common.collect.ImmutableMap<*, *> {
            val builder: com.google.common.collect.ImmutableMap.Builder<*, *> =
                com.google.common.collect.ImmutableMap.builderWithExpectedSize<Any?, Any?>(size())
            /* !!! Hit visitElement for element type: class org.jetbrains.kotlin.nj2k.tree.JKJavaForLoopStatement !!! */
            return builder.buildOrThrow()
        }
    }

    private class ImmutableSortedMapEntryBuffer(size: Int) : EntryBuffer(size),
        DeferredValue<com.google.common.collect.ImmutableSortedMap<*, *>?> {
        private val comparator: java.util.Comparator<*>? = null

        override fun call(): com.google.common.collect.ImmutableSortedMap<*, *> {
            val builder: com.google.common.collect.ImmutableSortedMap.Builder<*, *> =
                com.google.common.collect.ImmutableSortedMap.Builder<Any?, Any?>(comparator)
            /* !!! Hit visitElement for element type: class org.jetbrains.kotlin.nj2k.tree.JKJavaForLoopStatement !!! */
            return builder.buildOrThrow()
        }
    }
}
