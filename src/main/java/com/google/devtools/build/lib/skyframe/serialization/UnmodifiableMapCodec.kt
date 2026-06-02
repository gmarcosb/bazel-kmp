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
import java.util.Collections
import java.util.HashMap
import java.util.LinkedHashMap

/** [ObjectCodec] for [java.util.Collections.UnmodifiableMap].  */
internal class UnmodifiableMapCodec : AsyncObjectCodec<MutableMap<*, *>?>() {
    override fun getEncodedClass(): java.lang.Class<*> {
        return EMPTY.getClass()
    }

    @Throws(com.google.devtools.build.lib.skyframe.serialization.SerializationException::class, IOException::class)
    override fun serialize(context: SerializationContext?, obj: MutableMap<*, *>, codedOut: CodedOutputStream) {
        codedOut.writeInt32NoTag(obj.size())
        MapHelpers.serializeMapEntries(context, obj, codedOut)
    }

    @Throws(com.google.devtools.build.lib.skyframe.serialization.SerializationException::class, IOException::class)
    override fun deserializeAsync(context: AsyncDeserializationContext, codedIn: CodedInputStream): MutableMap<*, *> {
        val size: Int = codedIn.readInt32()
        if (size == 0) {
            return EMPTY
        }

        // Load factor is 0.75, so we need an initial capacity of 4/3 actual size to avoid rehashing.
        val map: LinkedHashMap<*, *> = LinkedHashMap<Any?, Any?>(4 * size / 3)

        val result: MutableMap<*, *> = Collections.unmodifiableMap<Any?, Any?>(map)
        context.registerInitialValue(result)

        HashMapCodec.Companion.populateMap(context, codedIn, map, size)
        return result
    }

    companion object {
        private val EMPTY: MutableMap<*, *> = Collections.unmodifiableMap<Any?, Any?>(HashMap<Any?, Any?>())
    }
}
