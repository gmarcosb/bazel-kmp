// Copyright 2019 The Bazel Authors. All rights reserved.
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
import com.google.protobuf.CodedInputStream
import com.google.protobuf.CodedOutputStream
import java.io.IOException

/**
 * Encodes an [ImmutableClassToInstanceMap]. The iteration order of the deserialized map is
 * the same as the original map's.
 * 
 * 
 * We handle [ImmutableClassToInstanceMap] by treating it as an [ImmutableMap] and
 * calling the proper conversion method ([ImmutableClassToInstanceMap.copyOf]) when
 * deserializing.
 * 
 * 
 * Any [SerializationException] or [IOException] that arises while serializing or
 * deserializing a map entry's value (not its key) will be wrapped in a new [ ] using [SerializationException.propagate]. (Note that this preserves
 * the type of [SerializationException.NoCodecException] exceptions.) The message will include
 * the `toString()` of the entry's key. For errors that occur while serializing, it will also
 * include the class name of the entry's value. Errors that occur while serializing an entry key are
 * not affected.
 */
internal class ImmutableClassToInstanceMapCodec :
    DeferredObjectCodec<com.google.common.collect.ImmutableClassToInstanceMap<*>?>() {
    override fun getEncodedClass(): java.lang.Class<com.google.common.collect.ImmutableClassToInstanceMap<*>?> {
        return com.google.common.collect.ImmutableClassToInstanceMap::class.java
    }

    @Throws(com.google.devtools.build.lib.skyframe.serialization.SerializationException::class, IOException::class)
    override fun serialize(
        context: SerializationContext?,
        map: com.google.common.collect.ImmutableClassToInstanceMap<*>,
        codedOut: CodedOutputStream
    ) {
        codedOut.writeInt32NoTag(map.size())
        MapHelpers.serializeMapEntries(context, map, codedOut)
    }

    @Throws(com.google.devtools.build.lib.skyframe.serialization.SerializationException::class, IOException::class)
    override fun deserializeDeferred(
        context: AsyncDeserializationContext?, codedIn: CodedInputStream
    ): DeferredValue<com.google.common.collect.ImmutableClassToInstanceMap<*>?> {
        val size: Int = codedIn.readInt32()
        if (size < 0) {
            throw com.google.devtools.build.lib.skyframe.serialization.SerializationException("Expected non-negative length: " + size)
        }
        if (size == 0) {
            return DeferredValue { com.google.common.collect.ImmutableClassToInstanceMap.of() }
        }

        val buffer: EntryBuffer =
            com.google.devtools.build.lib.skyframe.serialization.ImmutableClassToInstanceMapCodec.EntryBuffer(size)
        MapHelpers.deserializeMapEntries(
            context,
            codedIn,
            buffer.keys,
            buffer.values
        )
        return buffer
    }

    private class EntryBuffer(size: Int) : DeferredValue<com.google.common.collect.ImmutableClassToInstanceMap<*>?> {
        val keys: Array<Any?>
        val values: Array<Any?>

        init {
            this.keys = arrayOfNulls<Any>(size)
            this.values = arrayOfNulls<Any>(size)
        }

        override fun call(): com.google.common.collect.ImmutableClassToInstanceMap<*> {
            val builder: com.google.common.collect.ImmutableClassToInstanceMap.Builder<*> =
                com.google.common.collect.ImmutableClassToInstanceMap.builder<Any?>()
            /* !!! Hit visitElement for element type: class org.jetbrains.kotlin.nj2k.tree.JKJavaForLoopStatement !!! */
            return builder.build()
        }

        fun size(): Int {
            return keys.size
        }
    }
}
