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
package com.google.devtools.build.lib.collect

import com.google.devtools.build.lib.skyframe.serialization.ArrayProcessor
import com.google.devtools.build.lib.skyframe.serialization.AsyncDeserializationContext
import com.google.devtools.build.lib.skyframe.serialization.AsyncObjectCodec
import com.google.devtools.build.lib.skyframe.serialization.SerializationContext
import com.google.protobuf.CodedInputStream
import com.google.protobuf.CodedOutputStream
import java.io.IOException

internal object IterableCodecs {
    @Throws(com.google.devtools.build.lib.skyframe.serialization.SerializationException::class, IOException::class)
    private fun serialize(
        context: SerializationContext, obj: Iterable<*>, codedOut: CodedOutputStream
    ) {
        val elements = if (obj is MutableCollection<*>) obj else com.google.common.collect.Lists.newArrayList<Any?>(obj)
        codedOut.writeInt32NoTag(elements.size())
        for (elt in elements) {
            context.serialize(elt, codedOut)
        }
    }

    /**
     * Codec for [FluentIterable].
     * 
     * 
     * [FluentIterable] isn't directly used, but instances are created through [ ][com.google.common.collect.Iterables.concat].
     */
    internal class FluentIterableCodec : AsyncObjectCodec<com.google.common.collect.FluentIterable<*>?>() {
        val encodedClass: java.lang.Class<com.google.common.collect.FluentIterable<*>?>
            get() = com.google.common.collect.FluentIterable::class.java

        @Throws(com.google.devtools.build.lib.skyframe.serialization.SerializationException::class, IOException::class)
        override fun serialize(
            context: SerializationContext, obj: com.google.common.collect.FluentIterable<*>, codedOut: CodedOutputStream
        ) {
            IterableCodecs.serialize(context, obj, codedOut)
        }

        @Throws(com.google.devtools.build.lib.skyframe.serialization.SerializationException::class, IOException::class)
        override fun deserializeAsync(
            context: AsyncDeserializationContext, codedIn: CodedInputStream
        ): com.google.common.collect.FluentIterable<*> {
            val count: Int = codedIn.readInt32()
            if (count == 0) {
                val empty: com.google.common.collect.FluentIterable<*> =
                    com.google.common.collect.FluentIterable.of<Any?>()
                context.registerInitialValue(empty)
                return empty
            }

            val elements = arrayOfNulls<Any>(count)
            val value = DeserializedFluentIterable(elements)
            context.registerInitialValue(value)

            ArrayProcessor.deserializeObjectArray(context, codedIn, elements, count)

            return value
        }
    }

    /** This imitates the anonymous implementation in [FluentIterable.from].  */
    private class DeserializedFluentIterable(private val elements: Array<Any?>) :
        com.google.common.collect.FluentIterable<Any?>() {
        override fun iterator(): MutableIterator<*> {
            return com.google.common.collect.Iterators.forArray<Any?>(*elements)
        }
    }
}
