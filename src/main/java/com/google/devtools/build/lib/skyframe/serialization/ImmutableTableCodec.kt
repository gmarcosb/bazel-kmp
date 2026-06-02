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

/** Codec for [ImmutableTable].  */
class ImmutableTableCodec : DeferredObjectCodec<com.google.common.collect.ImmutableTable<*, *, *>?>() {
    override fun getEncodedClass(): java.lang.Class<com.google.common.collect.ImmutableTable<*, *, *>?> {
        return com.google.common.collect.ImmutableTable::class.java
    }

    @Throws(com.google.devtools.build.lib.skyframe.serialization.SerializationException::class, IOException::class)
    override fun serialize(
        context: SerializationContext,
        `object`: com.google.common.collect.ImmutableTable<*, *, *>,
        codedOut: CodedOutputStream
    ) {
        val cellSet: com.google.common.collect.ImmutableSet<com.google.common.collect.Table.Cell<*, *, *>> =
            `object`.cellSet()
        codedOut.writeInt32NoTag(cellSet.size())
        for (cell in cellSet) {
            context.serialize(cell.getRowKey(), codedOut)
            context.serialize(cell.getColumnKey(), codedOut)
            context.serialize(cell.getValue(), codedOut)
        }
    }

    // TODO: b/386384684 - remove Unsafe usage
    @Throws(com.google.devtools.build.lib.skyframe.serialization.SerializationException::class, IOException::class)
    override fun deserializeDeferred(
        context: AsyncDeserializationContext, codedIn: CodedInputStream
    ): DeferredValue<com.google.common.collect.ImmutableTable<*, *, *>?> {
        val size: Int = codedIn.readInt32()
        if (size < 0) {
            throw com.google.devtools.build.lib.skyframe.serialization.SerializationException("Expected non-negative size: " + size)
        }
        if (size == 0) {
            return DeferredValue { com.google.common.collect.ImmutableTable.of() }
        }

        val buffer: EntryBuffer =
            com.google.devtools.build.lib.skyframe.serialization.ImmutableTableCodec.EntryBuffer(size)
        var offset: Long = sun.misc.Unsafe.ARRAY_OBJECT_BASE_OFFSET.toLong()
        /* !!! Hit visitElement for element type: class org.jetbrains.kotlin.nj2k.tree.JKJavaForLoopStatement !!! */
        return buffer
    }

    private class EntryBuffer(size: Int) : DeferredValue<com.google.common.collect.ImmutableTable<*, *, *>?> {
        private val rowKeys: Array<Any?>
        private val columnKeys: Array<Any?>
        private val values: Array<Any?>

        init {
            this.rowKeys = arrayOfNulls<Any>(size)
            this.columnKeys = arrayOfNulls<Any>(size)
            this.values = arrayOfNulls<Any>(size)
        }

        override fun call(): com.google.common.collect.ImmutableTable<*, *, *> {
            val builder: com.google.common.collect.ImmutableTable.Builder<*, *, *> =
                com.google.common.collect.ImmutableTable.builder<Any?, Any?, Any?>()
            /* !!! Hit visitElement for element type: class org.jetbrains.kotlin.nj2k.tree.JKJavaForLoopStatement !!! */
            return builder.buildOrThrow()
        }

        fun size(): Int {
            return rowKeys.size
        }
    }
}
