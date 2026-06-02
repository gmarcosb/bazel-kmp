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
import com.google.devtools.build.lib.skyframe.serialization.SerializationContext
import com.google.protobuf.CodedInputStream
import com.google.protobuf.CodedOutputStream
import java.io.IOException

/**
 * Codecs for [Multimap]. Handles [ImmutableListMultimap], [ImmutableSetMultimap]
 * and [LinkedHashMultimap].
 */
object MultimapCodecs {
    @Throws(com.google.devtools.build.lib.skyframe.serialization.SerializationException::class, IOException::class)
    private fun serializeMultimap(
        context: SerializationContext, obj: com.google.common.collect.Multimap<*, *>, codedOut: CodedOutputStream
    ) {
        val map: MutableMap<*, *> = obj.asMap()
        codedOut.writeInt32NoTag(map.size())
        for (next in map.entrySet()) {
            val entry = next as MutableMap.MutableEntry<*, *>

            context.serialize(entry.getKey(), codedOut)

            val values = entry.getValue() as MutableCollection<*>
            codedOut.writeInt32NoTag(values.size())
            for (value in values) {
                context.serialize(value, codedOut)
            }
        }
    }

    /** Takes care to fully deserialize all keys and values as they will be used in sets.  */
    @Throws(com.google.devtools.build.lib.skyframe.serialization.SerializationException::class, IOException::class)
    private fun deserializeSetMultimap(
        context: AsyncDeserializationContext, codedIn: CodedInputStream, buffer: MultimapBuffer
    ) {
        /* !!! Hit visitElement for element type: class org.jetbrains.kotlin.nj2k.tree.JKJavaForLoopStatement !!! */
    }

    @com.google.errorprone.annotations.Keep // used reflectively
    private class ImmutableListMultimapCodec

        : DeferredObjectCodec<com.google.common.collect.ImmutableListMultimap<*, *>?>() {
        override fun getEncodedClass(): java.lang.Class<com.google.common.collect.ImmutableListMultimap<*, *>?> {
            return com.google.common.collect.ImmutableListMultimap::class.java
        }

        @Throws(com.google.devtools.build.lib.skyframe.serialization.SerializationException::class, IOException::class)
        override fun serialize(
            context: SerializationContext,
            obj: com.google.common.collect.ImmutableListMultimap<*, *>,
            codedOut: CodedOutputStream
        ) {
            serializeMultimap(context, obj, codedOut)
        }

        @Throws(com.google.devtools.build.lib.skyframe.serialization.SerializationException::class, IOException::class)
        override fun deserializeDeferred(
            context: AsyncDeserializationContext, codedIn: CodedInputStream
        ): DeferredValue<com.google.common.collect.ImmutableListMultimap<*, *>?> {
            val size: Int = codedIn.readInt32()
            if (size == 0) {
                return DeferredValue { com.google.common.collect.ImmutableListMultimap.of() }
            }

            val buffer = ImmutableListMultimapBuffer(size)
            /* !!! Hit visitElement for element type: class org.jetbrains.kotlin.nj2k.tree.JKJavaForLoopStatement !!! */
            return buffer
        }
    }

    @com.google.errorprone.annotations.Keep // used reflectively
    private class ImmutableSetMultimapCodec :
        DeferredObjectCodec<com.google.common.collect.ImmutableSetMultimap<*, *>?>() {
        override fun getEncodedClass(): java.lang.Class<com.google.common.collect.ImmutableSetMultimap<*, *>?> {
            return com.google.common.collect.ImmutableSetMultimap::class.java
        }

        @Throws(com.google.devtools.build.lib.skyframe.serialization.SerializationException::class, IOException::class)
        override fun serialize(
            context: SerializationContext,
            obj: com.google.common.collect.ImmutableSetMultimap<*, *>,
            codedOut: CodedOutputStream
        ) {
            serializeMultimap(context, obj, codedOut)
        }

        @Throws(com.google.devtools.build.lib.skyframe.serialization.SerializationException::class, IOException::class)
        override fun deserializeDeferred(
            context: AsyncDeserializationContext, codedIn: CodedInputStream
        ): DeferredValue<com.google.common.collect.ImmutableSetMultimap<*, *>?> {
            val size: Int = codedIn.readInt32()
            if (size == 0) {
                return DeferredValue { com.google.common.collect.ImmutableSetMultimap.of() }
            }

            val buffer = ImmutableSetMultimapBuffer(size)
            deserializeSetMultimap(context, codedIn, buffer)
            return buffer
        }
    }

    @com.google.errorprone.annotations.Keep // used reflectively
    private class LinkedHashMultimapCodec : DeferredObjectCodec<com.google.common.collect.LinkedHashMultimap<*, *>?>() {
        override fun getEncodedClass(): java.lang.Class<com.google.common.collect.LinkedHashMultimap<*, *>?> {
            return com.google.common.collect.LinkedHashMultimap::class.java
        }

        @Throws(com.google.devtools.build.lib.skyframe.serialization.SerializationException::class, IOException::class)
        override fun serialize(
            context: SerializationContext,
            obj: com.google.common.collect.LinkedHashMultimap<*, *>,
            codedOut: CodedOutputStream
        ) {
            serializeMultimap(context, obj, codedOut)
        }

        @Throws(com.google.devtools.build.lib.skyframe.serialization.SerializationException::class, IOException::class)
        override fun deserializeDeferred(
            context: AsyncDeserializationContext, codedIn: CodedInputStream
        ): DeferredValue<com.google.common.collect.LinkedHashMultimap<*, *>?> {
            val size: Int = codedIn.readInt32()
            if (size == 0) {
                return DeferredValue { com.google.common.collect.LinkedHashMultimap.create() }
            }

            val buffer = LinkedHashMultimapBuffer(size)
            deserializeSetMultimap(context, codedIn, buffer)
            return buffer
        }
    }

    private open class MultimapBuffer(size: Int) {
        val keys: Array<Any?>
        val values: Array<Array<Any?>?>

        init {
            this.keys = arrayOfNulls<Any>(size)
            this.values = arrayOfNulls<Array<Any?>>(size)
        }

        fun size(): Int {
            return keys.size
        }
    }

    private class ImmutableListMultimapBuffer(size: Int) : MultimapBuffer(size),
        DeferredValue<com.google.common.collect.ImmutableListMultimap<*, *>?> {
        override fun call(): com.google.common.collect.ImmutableListMultimap<*, *> {
            val builder: com.google.common.collect.ImmutableListMultimap.Builder<*, *> =
                com.google.common.collect.ImmutableListMultimap.builder<Any?, Any?>()
            /* !!! Hit visitElement for element type: class org.jetbrains.kotlin.nj2k.tree.JKJavaForLoopStatement !!! */
            return builder.build()
        }
    }

    private class ImmutableSetMultimapBuffer(size: Int) : MultimapBuffer(size),
        DeferredValue<com.google.common.collect.ImmutableSetMultimap<*, *>?> {
        override fun call(): com.google.common.collect.ImmutableSetMultimap<*, *> {
            val builder: com.google.common.collect.ImmutableSetMultimap.Builder<*, *> =
                com.google.common.collect.ImmutableSetMultimap.builder<Any?, Any?>()
            /* !!! Hit visitElement for element type: class org.jetbrains.kotlin.nj2k.tree.JKJavaForLoopStatement !!! */
            return builder.build()
        }
    }

    private class LinkedHashMultimapBuffer(size: Int) : MultimapBuffer(size),
        DeferredValue<com.google.common.collect.LinkedHashMultimap<*, *>?> {
        override fun call(): com.google.common.collect.LinkedHashMultimap<*, *> {
            var totalValues = 0
            /* !!! Hit visitElement for element type: class org.jetbrains.kotlin.nj2k.tree.JKJavaForLoopStatement !!! */
            val result: com.google.common.collect.LinkedHashMultimap<*, *> =
                com.google.common.collect.LinkedHashMultimap.create<Any?, Any?>(size(), totalValues / size())
            /* !!! Hit visitElement for element type: class org.jetbrains.kotlin.nj2k.tree.JKJavaForLoopStatement !!! */
            return result
        }
    }
}
