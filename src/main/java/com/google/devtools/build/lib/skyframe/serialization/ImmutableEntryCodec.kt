// Copyright 2025 The Bazel Authors. All rights reserved.
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

/** A codec for the hidden [Map.Entry] subclass emitted by [Maps.immutableEntry].  */
internal class ImmutableEntryCodec : DeferredObjectCodec<MutableMap.MutableEntry<*, *>?>() {
    override fun getEncodedClass(): java.lang.Class<out MutableMap.MutableEntry<*, *>?> {
        return IMMUTABLE_ENTRY_TYPE
    }

    @Throws(com.google.devtools.build.lib.skyframe.serialization.SerializationException::class, IOException::class)
    override fun serialize(
        context: SerializationContext,
        entry: MutableMap.MutableEntry<*, *>,
        codedOut: CodedOutputStream?
    ) {
        context.serialize(entry.getKey(), codedOut)
        context.serialize(entry.getValue(), codedOut)
    }

    @Throws(com.google.devtools.build.lib.skyframe.serialization.SerializationException::class, IOException::class)
    override fun deserializeDeferred(
        context: AsyncDeserializationContext, codedIn: CodedInputStream?
    ): DeferredValue<MutableMap.MutableEntry<*, *>?> {
        val result = EntryBuilder()
        context.deserialize<EntryBuilder?>(
            codedIn,
            result,
            com.google.devtools.build.lib.skyframe.serialization.AsyncDeserializationContext.FieldSetter { builder: T?, key: Any? ->
                EntryBuilder.Companion.setKey(
                    builder,
                    key
                )
            })
        context.deserialize<EntryBuilder?>(
            codedIn,
            result,
            com.google.devtools.build.lib.skyframe.serialization.AsyncDeserializationContext.FieldSetter { builder: T?, value: Any? ->
                EntryBuilder.Companion.setValue(
                    builder,
                    value
                )
            })
        return result
    }

    private class EntryBuilder : DeferredValue<MutableMap.MutableEntry<*, *>?> {
        private var key: Any? = null
        private var value: Any? = null

        override fun call(): MutableMap.MutableEntry<*, *> {
            return com.google.common.collect.Maps.immutableEntry<Any?, Any?>(key, value)
        }

        companion object {
            private fun setKey(builder: EntryBuilder, key: Any?) {
                builder.key = key
            }

            private fun setValue(builder: EntryBuilder, value: Any?) {
                builder.value = value
            }
        }
    }

    companion object {
        private val IMMUTABLE_ENTRY_TYPE: java.lang.Class<out MutableMap.MutableEntry<*, *>?> =
            com.google.common.collect.Maps.immutableEntry<String?, String?>("", "").getClass()
    }
}
