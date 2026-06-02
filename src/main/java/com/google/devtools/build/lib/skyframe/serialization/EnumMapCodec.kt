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
import com.google.devtools.build.lib.skyframe.serialization.AsyncObjectCodec
import com.google.devtools.build.lib.skyframe.serialization.ClassCodec
import com.google.devtools.build.lib.skyframe.serialization.SerializationContext
import com.google.devtools.build.lib.unsafe.UnsafeProvider
import com.google.protobuf.CodedInputStream
import com.google.protobuf.CodedOutputStream
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

/**
 * Serialize [EnumMap]. Subclasses of [EnumMap] will crash at runtime because currently
 * there are no "benign" subclasses of [EnumMap] in the Bazel codebase that can be used where
 * an [EnumMap] was expected.
 */
// TODO: b/386384684 - remove Unsafe usage
internal class EnumMapCodec : AsyncObjectCodec<java.util.EnumMap<*, *>?>() {
    override fun getEncodedClass(): java.lang.Class<java.util.EnumMap<*, *>?> {
        return java.util.EnumMap::class.java
    }

    // TODO: b/386384684 - remove Unsafe usage
    @Throws(com.google.devtools.build.lib.skyframe.serialization.SerializationException::class, IOException::class)
    override fun serialize(context: SerializationContext, obj: java.util.EnumMap<*, *>, codedOut: CodedOutputStream) {
        if (obj.getClass() != java.util.EnumMap::class.java) {
            throw com.google.devtools.build.lib.skyframe.serialization.SerializationException(
                "Cannot serialize subclasses of EnumMap: " + obj.getClass() + " (" + obj + ")"
            )
        }
        ClassCodec.Companion.classCodec()
            .serialize(
                context,
                (UnsafeProvider.unsafe().getObject(obj, KEY_TYPE_OFFSET) as java.lang.Class<*>?),
                codedOut
            )

        codedOut.writeInt32NoTag(obj.size())
        if (obj.isEmpty()) {
            return
        }

        for (next in obj.entrySet()) {
            val entry = next as MutableMap.MutableEntry<*, *>
            codedOut.writeInt32NoTag((entry.getKey() as Enum<*>).ordinal())
            context.serialize(entry.getValue(), codedOut)
        }
    }

    // TODO: b/386384684 - remove Unsafe usage
    @Throws(com.google.devtools.build.lib.skyframe.serialization.SerializationException::class, IOException::class)
    override fun deserializeAsync(
        context: AsyncDeserializationContext,
        codedIn: CodedInputStream
    ): java.util.EnumMap<*, *> {
        val clazz: java.lang.Class<*> = ClassCodec.Companion.classCodec().deserialize(context, codedIn)
        val size: Int = codedIn.readInt32()
        val result: java.util.EnumMap<*, *> = java.util.EnumMap<Any?, Any?>(clazz)
        context.registerInitialValue(result)

        val buffer = MapBuffer(result, size)

        val enums = clazz.getEnumConstants() as Array<Enum<*>?>
        /* !!! Hit visitElement for element type: class org.jetbrains.kotlin.nj2k.tree.JKJavaForLoopStatement !!! */
        return result
    }

    /** Buffers the entry elements and populates the map once all values are done.  */
    private class MapBuffer(result: java.util.EnumMap<*, *>, size: Int) : java.lang.Runnable {
        private val result: java.util.EnumMap<*, *>
        private val enums: Array<Enum<*>?>
        private val values: Array<Any?>

        private val remaining: AtomicInteger

        init {
            this.result = result
            this.enums = arrayOfNulls<Enum<*>>(size)
            this.values = arrayOfNulls<Any>(size)
            this.remaining = AtomicInteger(size)
        }

        override fun run() {
            if (remaining.decrementAndGet() == 0) {
                /* !!! Hit visitElement for element type: class org.jetbrains.kotlin.nj2k.tree.JKJavaForLoopStatement !!! */
            }
        }

        fun setEnum(index: Int, enumKey: Enum<*>?) {
            enums[index] = enumKey
        }
    }

    companion object {
        /** Used to retrieve the hidden [EnumMap.keyType] field.  */
        private val KEY_TYPE_OFFSET: Long

        init {
            try {
                KEY_TYPE_OFFSET =
                    UnsafeProvider.unsafe().objectFieldOffset(java.util.EnumMap::class.java.getDeclaredField("keyType"))
            } catch (e: java.lang.NoSuchFieldException) {
                throw java.lang.ExceptionInInitializerError(e)
            }
        }
    }
}
