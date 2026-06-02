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
import com.google.devtools.build.lib.skyframe.serialization.DeferredObjectCodec
import com.google.devtools.build.lib.skyframe.serialization.DeferredObjectCodec.DeferredValue
import com.google.devtools.build.lib.skyframe.serialization.ImmutableSetCodec
import com.google.devtools.build.lib.skyframe.serialization.SerializationContext
import com.google.devtools.build.lib.skyframe.serialization.autocodec.SerializationConstant
import com.google.protobuf.CodedInputStream
import com.google.protobuf.CodedOutputStream
import java.io.IOException
import java.util.Collections

/** [ObjectCodec] for [ImmutableSet] and other sets that should be immutable.  */
class ImmutableSetCodec : DeferredObjectCodec<MutableSet<*>?>() {
    override fun getEncodedClass(): java.lang.Class<com.google.common.collect.ImmutableSet<*>?> {
        return com.google.common.collect.ImmutableSet::class.java
    }

    override fun additionalEncodedClasses(): com.google.common.collect.ImmutableSet<java.lang.Class<out MutableSet<*>?>?> {
        return com.google.common.collect.ImmutableSet.of<java.lang.Class<out MutableSet<*>?>?>(
            MULTIMAP_VALUE_SET_CLASS,
            SINGLETON_SET_CLASS,
            SUBSET_CLASS
        )
    }

    @Throws(com.google.devtools.build.lib.skyframe.serialization.SerializationException::class, IOException::class)
    override fun serialize(context: SerializationContext, `object`: MutableSet<*>, codedOut: CodedOutputStream) {
        codedOut.writeInt32NoTag(`object`.size())
        for (obj in `object`) {
            context.serialize(obj, codedOut)
        }
    }

    @Throws(com.google.devtools.build.lib.skyframe.serialization.SerializationException::class, IOException::class)
    override fun deserializeDeferred(
        context: AsyncDeserializationContext?, codedIn: CodedInputStream
    ): DeferredValue<MutableSet<*>?> {
        val size: Int = codedIn.readInt32()

        val buffer: ElementBuffer =
            com.google.devtools.build.lib.skyframe.serialization.ImmutableSetCodec.ElementBuffer(size)
        ArrayProcessor.Companion.deserializeObjectArray(context, codedIn, buffer.elements, size)
        return buffer
    }

    private class ElementBuffer(size: Int) : DeferredValue<MutableSet<*>?> {
        private val elements: Array<Any?>

        init {
            this.elements = arrayOfNulls<Any>(size)
        }

        override fun call(): com.google.common.collect.ImmutableSet<*> {
            return com.google.common.collect.ImmutableSet.builderWithExpectedSize<Any?>(elements.size).add(*elements)
                .build()
        }
    }

    companion object {
        // Conversion of the types below to ImmutableSet is sound because the underlying types are hidden
        // and only referenceable as the Set type.
        @kotlin.jvm.JvmField
        @com.google.common.annotations.VisibleForTesting
        val MULTIMAP_VALUE_SET_CLASS: java.lang.Class<MutableSet<*>?> =
            com.google.common.collect.LinkedHashMultimap.create<String?, String?>(
                com.google.common.collect.ImmutableMultimap.of<String?, String?>(
                    "a",
                    "b"
                )
            ).get("a").getClass() as java.lang.Class<MutableSet<*>?>

        private val SINGLETON_SET_CLASS: java.lang.Class<MutableSet<*>?> =
            Collections.singleton<String?>("a").getClass() as java.lang.Class<MutableSet<*>?>

        private val SUBSET_CLASS: java.lang.Class<MutableSet<*>?> =
            com.google.common.collect.Iterables.getOnlyElement<MutableSet<Any?>?>(
                com.google.common.collect.Sets.powerSet<Any?>(com.google.common.collect.ImmutableSet.of<Any?>())
            ).getClass() as java.lang.Class<MutableSet<*>?>

        /**
         * Defines a reference constant for [Collections.emptySet].
         * 
         * 
         * This is done here because we can't add the annotation to the JDK code.
         */
        @SerializationConstant
        val EMPTY_SET: MutableSet<*> = Collections.emptySet<Any?>()
    }
}
