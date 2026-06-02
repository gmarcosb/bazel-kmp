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
import com.google.devtools.build.lib.skyframe.serialization.HashMapCodec
import com.google.devtools.build.lib.skyframe.serialization.MapHelpers
import com.google.devtools.build.lib.skyframe.serialization.SerializationContext
import com.google.protobuf.CodedInputStream
import com.google.protobuf.CodedOutputStream
import java.io.IOException
import java.util.HashMap
import java.util.LinkedHashMap

/**
 * [ObjectCodec] for [HashMap] that returns [LinkedHashMap] for determinism.
 * 
 * 
 * This type transformation is safe because [LinkedHashMap] is a subclass of [ ].
 */
internal class HashMapCodec : AsyncObjectCodec<HashMap<*, *>?>() {
    override fun getEncodedClass(): java.lang.Class<HashMap<*, *>?> {
        return HashMap::class.java
    }

    @Throws(com.google.devtools.build.lib.skyframe.serialization.SerializationException::class, IOException::class)
    override fun serialize(context: SerializationContext?, obj: HashMap<*, *>, codedOut: CodedOutputStream) {
        codedOut.writeInt32NoTag(obj.size())
        MapHelpers.serializeMapEntries(context, obj, codedOut)
    }

    @Throws(com.google.devtools.build.lib.skyframe.serialization.SerializationException::class, IOException::class)
    override fun deserializeAsync(context: AsyncDeserializationContext, codedIn: CodedInputStream): HashMap<*, *> {
        val size: Int = codedIn.readInt32()
        // Load factor is 0.75, so we need an initial capacity of 4/3 actual size to avoid rehashing.
        val result: LinkedHashMap<*, *> = LinkedHashMap<Any?, Any?>(4 * size / 3)

        context.registerInitialValue(result)
        if (size == 0) {
            return result
        }

        populateMap(context, codedIn, result, size)

        return result
    }

    /**
     * Buffers the keys and values until all are available, then populates the map.
     * 
     * 
     * This approach is thread-safe.
     */
    private class EntryBuffer(result: LinkedHashMap<*, *>, size: Int) : java.lang.Runnable {
        private val result: LinkedHashMap<*, *>
        private val keys: Array<Any?>
        private val values: Array<Any?>

        init {
            this.result = result
            this.keys = arrayOfNulls<Any>(size)
            this.values = arrayOfNulls<Any>(size)
        }

        override fun run() {
            /* !!! Hit visitElement for element type: class org.jetbrains.kotlin.nj2k.tree.JKJavaForLoopStatement !!! */
        }
    }

    companion object {
        @Throws(com.google.devtools.build.lib.skyframe.serialization.SerializationException::class, IOException::class)
        fun populateMap(
            context: AsyncDeserializationContext?, codedIn: CodedInputStream?, map: LinkedHashMap<*, *>, size: Int
        ) {
            val buffer: EntryBuffer =
                com.google.devtools.build.lib.skyframe.serialization.HashMapCodec.EntryBuffer(map, size)
            MapHelpers.deserializeMapEntries(
                context, codedIn, buffer.keys, buffer.values,  /* done= */buffer as java.lang.Runnable
            )
        }
    }
}
