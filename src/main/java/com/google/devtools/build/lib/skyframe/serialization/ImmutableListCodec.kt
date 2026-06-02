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
 * An [AsyncObjectCodec] for [ImmutableList].
 * 
 * 
 * This codec is necessary because [ImmutableList]:
 * 
 * 
 *  * has a number of hidden subclasses; and
 *  * marks important fields transient.
 * 
 */
internal class ImmutableListCodec : AsyncObjectCodec<com.google.common.collect.ImmutableList<*>?>() {
    override fun getEncodedClass(): java.lang.Class<com.google.common.collect.ImmutableList<*>?> {
        return com.google.common.collect.ImmutableList::class.java
    }

    @Throws(IOException::class, com.google.devtools.build.lib.skyframe.serialization.SerializationException::class)
    override fun serialize(
        context: SerializationContext, `object`: com.google.common.collect.ImmutableList<*>, codedOut: CodedOutputStream
    ) {
        codedOut.writeInt32NoTag(`object`.size())
        for (obj in `object`) {
            context.serialize(obj, codedOut)
        }
    }

    // TODO: b/386384684 - remove Unsafe usage
    @Throws(IOException::class, com.google.devtools.build.lib.skyframe.serialization.SerializationException::class)
    override fun deserializeAsync(
        context: AsyncDeserializationContext, codedIn: CodedInputStream
    ): com.google.common.collect.ImmutableList<*>? {
        val size: Int = codedIn.readInt32()
        if (size == 0) {
            return com.google.common.collect.ImmutableList.of<Any?>()
        }

        val list: com.google.common.collect.ImmutableList<*>?
        if (size == 1) {
            try {
                list = UnsafeProvider.unsafe()
                    .allocateInstance(SINGLETON_IMMUTABLE_LIST_CLASS) as com.google.common.collect.ImmutableList<*>?
            } catch (e: java.lang.InstantiationException) {
                throw com.google.devtools.build.lib.skyframe.serialization.SerializationException(
                    "could not instantiate " + SINGLETON_IMMUTABLE_LIST_CLASS, e
                )
            }
            context.registerInitialValue(list)

            context.deserialize(codedIn, list, ELEMENT_OFFSET)
            return list
        }

        try {
            list = UnsafeProvider.unsafe()
                .allocateInstance(REGULAR_IMMUTABLE_LIST_CLASS) as com.google.common.collect.ImmutableList<*>?
        } catch (e: java.lang.InstantiationException) {
            throw com.google.devtools.build.lib.skyframe.serialization.SerializationException(
                "could not instantiate " + REGULAR_IMMUTABLE_LIST_CLASS,
                e
            )
        }
        context.registerInitialValue(list)

        val elements = arrayOfNulls<Any>(size)
        ArrayProcessor.Companion.deserializeObjectArray(context, codedIn, elements, size)

        UnsafeProvider.unsafe().putObject(list, ARRAY_OFFSET, elements)

        return list
    }

    companion object {
        private val SINGLETON_IMMUTABLE_LIST_CLASS: java.lang.Class<out com.google.common.collect.ImmutableList<*>?> =
            com.google.common.collect.ImmutableList.of<Int?>(0).getClass()
        private val REGULAR_IMMUTABLE_LIST_CLASS: java.lang.Class<out com.google.common.collect.ImmutableList<*>?> =
            com.google.common.collect.ImmutableList.of<Int?>(0, 1).getClass()

        private val ELEMENT_OFFSET: Long
        private val ARRAY_OFFSET: Long

        init {
            try {
                ELEMENT_OFFSET = UnsafeProvider.getFieldOffset(SINGLETON_IMMUTABLE_LIST_CLASS, "element")
                ARRAY_OFFSET = UnsafeProvider.getFieldOffset(REGULAR_IMMUTABLE_LIST_CLASS, "array")
            } catch (e: java.lang.NoSuchFieldException) {
                throw java.lang.ExceptionInInitializerError(e)
            }
        }
    }
}
