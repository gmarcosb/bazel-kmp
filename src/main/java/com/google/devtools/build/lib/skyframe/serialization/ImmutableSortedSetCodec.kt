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
import com.google.devtools.build.lib.skyframe.serialization.SerializationContext
import com.google.devtools.build.lib.unsafe.UnsafeProvider
import com.google.protobuf.CodedInputStream
import com.google.protobuf.CodedOutputStream
import java.io.IOException
import java.util.SortedSet

/**
 * [ObjectCodec] for [ImmutableSortedSet]. Comparator must be serializable, ideally a
 * registered constant.
 */
internal class ImmutableSortedSetCodec<E> : DeferredObjectCodec<com.google.common.collect.ImmutableSortedSet<E?>?>() {
    override fun getEncodedClass(): java.lang.Class<com.google.common.collect.ImmutableSortedSet<E?>?> {
        return com.google.common.collect.ImmutableSortedSet::class.java as java.lang.Class<*> as java.lang.Class<com.google.common.collect.ImmutableSortedSet<E?>?>
    }

    @Throws(com.google.devtools.build.lib.skyframe.serialization.SerializationException::class, IOException::class)
    override fun serialize(
        context: SerializationContext,
        `object`: com.google.common.collect.ImmutableSortedSet<E?>,
        codedOut: CodedOutputStream
    ) {
        codedOut.writeInt32NoTag(`object`.size())
        context.serialize(`object`.comparator(), codedOut)
        for (obj in `object`) {
            context.serialize(obj, codedOut)
        }
    }

    @Throws(com.google.devtools.build.lib.skyframe.serialization.SerializationException::class, IOException::class)
    override fun deserializeDeferred(
        context: AsyncDeserializationContext, codedIn: CodedInputStream
    ): DeferredValue<com.google.common.collect.ImmutableSortedSet<E?>?> {
        val size: Int = codedIn.readInt32()
        val sortedSetShim =
            SortedSetShimForEfficientDeserialization<E?>(size)
        context.deserialize(codedIn, sortedSetShim, COMPARATOR_OFFSET)
        ArrayProcessor.Companion.deserializeObjectArray(context, codedIn, sortedSetShim.sortedElementsArray, size)
        return sortedSetShim
    }

    /**
     * Implementation of parts of the [SortedSet] interface minimally needed for efficient
     * [ImmutableSortedSet] construction that avoids re-sorting the list of elements.
     */
    // SortedSet required for ImmutableSortedSet.copyOfSorted
    private class SortedSetShimForEfficientDeserialization<E>
        (size: Int) : SortedSet<E?>, DeferredValue<com.google.common.collect.ImmutableSortedSet<E?>?> {
        private val comparator: java.util.Comparator<E?>? = null
        private val sortedElementsArray: Array<Any?>

        init {
            this.sortedElementsArray = arrayOfNulls<Any>(size)
        }

        override fun call(): com.google.common.collect.ImmutableSortedSet<E?> {
            return com.google.common.collect.ImmutableSortedSet.copyOfSorted<E?>(this)
        }

        override fun comparator(): java.util.Comparator<in E?>? {
            return comparator
        }

        override fun toArray(): Array<Any?> {
            return sortedElementsArray
        }

        override fun <T> toArray(a: Array<T?>?): Array<T?>? {
            throw java.lang.UnsupportedOperationException()
        }

        override fun subSet(fromElement: E?, toElement: E?): SortedSet<E?>? {
            throw java.lang.UnsupportedOperationException()
        }

        override fun headSet(toElement: E?): SortedSet<E?>? {
            throw java.lang.UnsupportedOperationException()
        }

        override fun tailSet(fromElement: E?): SortedSet<E?>? {
            throw java.lang.UnsupportedOperationException()
        }

        override fun first(): E? {
            throw java.lang.UnsupportedOperationException()
        }

        override fun last(): E? {
            throw java.lang.UnsupportedOperationException()
        }

        override fun size(): Int {
            throw java.lang.UnsupportedOperationException()
        }

        override fun isEmpty(): Boolean {
            throw java.lang.UnsupportedOperationException()
        }

        override fun contains(o: Any?): Boolean {
            throw java.lang.UnsupportedOperationException()
        }

        override fun iterator(): MutableIterator<E?>? {
            throw java.lang.UnsupportedOperationException()
        }

        override fun add(e: E?): Boolean {
            return false
        }

        override fun remove(o: Any?): Boolean {
            throw java.lang.UnsupportedOperationException()
        }

        override fun containsAll(c: MutableCollection<*>?): Boolean {
            throw java.lang.UnsupportedOperationException()
        }

        override fun addAll(c: MutableCollection<out E?>?): Boolean {
            throw java.lang.UnsupportedOperationException()
        }

        override fun retainAll(c: MutableCollection<*>?): Boolean {
            throw java.lang.UnsupportedOperationException()
        }

        override fun removeAll(c: MutableCollection<*>?): Boolean {
            throw java.lang.UnsupportedOperationException()
        }

        override fun clear() {
            throw java.lang.UnsupportedOperationException()
        }

        override fun equals(o: Any?): Boolean {
            throw java.lang.UnsupportedOperationException()
        }

        override fun hashCode(): Int {
            throw java.lang.UnsupportedOperationException()
        }
    }

    companion object {
        private val COMPARATOR_OFFSET: Long

        init {
            try {
                COMPARATOR_OFFSET =
                    UnsafeProvider.getFieldOffset(SortedSetShimForEfficientDeserialization::class.java, "comparator")
            } catch (e: java.lang.NoSuchFieldException) {
                throw java.lang.ExceptionInInitializerError(e)
            }
        }
    }
}
