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

import com.google.devtools.build.lib.skyframe.serialization.ArrayProcessor
import com.google.devtools.build.lib.skyframe.serialization.AsyncDeserializationContext
import com.google.devtools.build.lib.skyframe.serialization.AsyncObjectCodec
import com.google.devtools.build.lib.skyframe.serialization.SerializationContext
import com.google.devtools.build.lib.unsafe.UnsafeProvider
import com.google.protobuf.CodedInputStream
import com.google.protobuf.CodedOutputStream
import java.io.IOException

/**
 * Codec for [ArrayList].
 * 
 * 
 * This is needed because [ArrayList] marks its `elementData` field transient and
 * even if it weren't, it uses an array slightly larger than its size.
 */
internal class ArrayListCodec : AsyncObjectCodec<java.util.ArrayList<*>?>() {
    val encodedClass: java.lang.Class<java.util.ArrayList<*>?>
        get() = java.util.ArrayList::class.java

    @Throws(com.google.devtools.build.lib.skyframe.serialization.SerializationException::class, IOException::class)
    override fun serialize(context: SerializationContext, list: java.util.ArrayList<*>, codedOut: CodedOutputStream) {
        codedOut.writeInt32NoTag(list.size())
        for (item in list) {
            context.serialize(item, codedOut)
        }
    }

    // TODO: b/386384684 - remove Unsafe usage
    @Throws(com.google.devtools.build.lib.skyframe.serialization.SerializationException::class, IOException::class)
    override fun deserializeAsync(
        context: AsyncDeserializationContext,
        codedIn: CodedInputStream
    ): java.util.ArrayList<*>? {
        val length: Int = codedIn.readInt32()
        if (length < 0) {
            throw com.google.devtools.build.lib.skyframe.serialization.SerializationException("Expected non-negative length: " + length)
        }

        if (length == 0) {
            val empty: java.util.ArrayList<*> = java.util.ArrayList<Any?>( /* initialCapacity= */0)
            context.registerInitialValue(empty)
            return empty
        }

        val list: java.util.ArrayList<*>?
        try {
            list = UnsafeProvider.unsafe().allocateInstance(java.util.ArrayList::class.java) as java.util.ArrayList<*>?
        } catch (e: java.lang.InstantiationException) {
            throw com.google.devtools.build.lib.skyframe.serialization.SerializationException(
                "could not instantiate ArrayList",
                e
            )
        }
        context.registerInitialValue(list)

        // Sets the elementData directly, then reflectively inserts it into the ArrayList. ArrayList's
        // public API doesn't provide an efficient way to populate elementData by offset.
        val elementData = arrayOfNulls<Any>(length)
        ArrayProcessor.Companion.deserializeObjectArray(context, codedIn, elementData, length)

        UnsafeProvider.unsafe().putObject(
            list,
            com.google.devtools.build.lib.skyframe.serialization.ArrayListCodec.Companion.ELEMENT_DATA_OFFSET,
            elementData
        )
        UnsafeProvider.unsafe().putInt(
            list,
            com.google.devtools.build.lib.skyframe.serialization.ArrayListCodec.Companion.SIZE_OFFSET,
            length
        )

        return list
    }

    companion object {
        private val ELEMENT_DATA_OFFSET: Long
        private val SIZE_OFFSET: Long

        init {
            try {
                com.google.devtools.build.lib.skyframe.serialization.ArrayListCodec.Companion.ELEMENT_DATA_OFFSET =
                    UnsafeProvider.getFieldOffset(java.util.ArrayList::class.java, "elementData")
                com.google.devtools.build.lib.skyframe.serialization.ArrayListCodec.Companion.SIZE_OFFSET =
                    UnsafeProvider.getFieldOffset(java.util.ArrayList::class.java, "size")
            } catch (e: java.lang.NoSuchFieldException) {
                throw java.lang.ExceptionInInitializerError(e)
            }
        }
    }
}
