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
import com.google.devtools.build.lib.skyframe.serialization.SerializationContext
import com.google.protobuf.CodedInputStream
import com.google.protobuf.CodedOutputStream
import java.io.IOException
import java.util.Collections
import java.util.HashSet
import java.util.LinkedHashSet
import java.util.concurrent.atomic.AtomicInteger

/**
 * [ObjectCodec] for [HashSet] that returns [LinkedHashSet] for determinism.
 * 
 * 
 * This type transformation is safe because [LinkedHashSet] is a subclass of [ ].
 */
internal class HashSetCodec : AsyncObjectCodec<HashSet<*>?>() {
    override fun getEncodedClass(): java.lang.Class<HashSet<*>?> {
        return HashSet::class.java
    }

    @Throws(com.google.devtools.build.lib.skyframe.serialization.SerializationException::class, IOException::class)
    override fun serialize(context: SerializationContext, obj: HashSet<*>, codedOut: CodedOutputStream) {
        codedOut.writeInt32NoTag(obj.size())
        for (`object` in obj) {
            context.serialize(`object`, codedOut)
        }
    }

    // TODO: b/386384684 - remove Unsafe usage
    @Throws(com.google.devtools.build.lib.skyframe.serialization.SerializationException::class, IOException::class)
    override fun deserializeAsync(context: AsyncDeserializationContext, codedIn: CodedInputStream): HashSet<*> {
        val size: Int = codedIn.readInt32()
        val set: LinkedHashSet<*> = com.google.common.collect.Sets.newLinkedHashSetWithExpectedSize<Any?>(size)
        context.registerInitialValue(set)

        if (size == 0) {
            return set
        }

        val buffer: ElementBuffer =
            com.google.devtools.build.lib.skyframe.serialization.HashSetCodec.ElementBuffer(set, size)
        /* !!! Hit visitElement for element type: class org.jetbrains.kotlin.nj2k.tree.JKJavaForLoopStatement !!! */

        return set
    }

    /**
     * Buffers the elements and populates the set once all are available.
     * 
     * 
     * This approach is implicitly thread-safe.
     */
    private class ElementBuffer(set: LinkedHashSet<*>?, size: Int) : java.lang.Runnable {
        private val set: LinkedHashSet<*>?
        private val elements: Array<Any?>

        private val remaining: AtomicInteger

        init {
            this.set = set
            this.elements = arrayOfNulls<Any>(size)

            this.remaining = AtomicInteger(size)
        }

        override fun run() {
            if (remaining.decrementAndGet() == 0) {
                Collections.addAll<Any?>(set, *elements)
            }
        }
    }
}
