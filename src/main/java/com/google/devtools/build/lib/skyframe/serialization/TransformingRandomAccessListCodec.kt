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

import com.google.devtools.build.lib.skyframe.serialization.ArrayProcessor
import com.google.devtools.build.lib.skyframe.serialization.AsyncDeserializationContext
import com.google.devtools.build.lib.skyframe.serialization.DeferredObjectCodec
import com.google.devtools.build.lib.skyframe.serialization.DeferredObjectCodec.DeferredValue
import com.google.devtools.build.lib.skyframe.serialization.SerializationContext
import com.google.devtools.build.lib.skyframe.serialization.TransformingRandomAccessListCodec
import com.google.protobuf.CodedInputStream
import com.google.protobuf.CodedOutputStream
import java.io.IOException

/**
 * Codec that serializes the hidden, [Lists.TransformingRandomAccessList] type.
 * 
 * 
 * Note that [List] is an interface, so codec resolution will never match it. Since [ ] is hidden, it's safe to deserialize as [ImmutableList],
 * because it must be referenced as a [List] or something more general.
 */
internal class TransformingRandomAccessListCodec private constructor() : DeferredObjectCodec<MutableList<*>?>() {
    override fun getEncodedClass(): java.lang.Class<out MutableList<*>?> {
        return TRANSFORMING_RANDOM_ACCESS_LIST_TYPE
    }

    @Throws(com.google.devtools.build.lib.skyframe.serialization.SerializationException::class, IOException::class)
    override fun serialize(context: SerializationContext, `object`: MutableList<*>, codedOut: CodedOutputStream) {
        codedOut.writeInt32NoTag(`object`.size())
        for (obj in `object`) {
            context.serialize(obj, codedOut)
        }
    }

    @Throws(com.google.devtools.build.lib.skyframe.serialization.SerializationException::class, IOException::class)
    override fun deserializeDeferred(
        context: AsyncDeserializationContext?, codedIn: CodedInputStream
    ): DeferredValue<MutableList<*>?> {
        val size: Int = codedIn.readInt32()

        val buffer: ElementBuffer =
            com.google.devtools.build.lib.skyframe.serialization.TransformingRandomAccessListCodec.ElementBuffer(size)
        ArrayProcessor.Companion.deserializeObjectArray(context, codedIn, buffer.elements, size)
        return buffer
    }

    private class ElementBuffer(size: Int) : DeferredValue<MutableList<*>?> {
        private val elements: Array<Any?>

        init {
            this.elements = arrayOfNulls<Any>(size)
        }

        override fun call(): com.google.common.collect.ImmutableList<*> {
            return com.google.common.collect.ImmutableList.builderWithExpectedSize<Any?>(elements.size).add(*elements)
                .build()
        }
    }

    companion object {
        private val TRANSFORMING_RANDOM_ACCESS_LIST_TYPE: java.lang.Class<out MutableList<*>?> =
            com.google.common.collect.Lists.transform<Int?, Int?>(
                com.google.common.collect.ImmutableList.of<Int?>(
                    1,
                    2,
                    3
                ), com.google.common.base.Function { x: Int? -> x }).getClass()
    }
}
