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
package com.google.devtools.build.lib.collect.compacthashset

import com.google.devtools.build.lib.skyframe.serialization.AsyncDeserializationContext
import com.google.devtools.build.lib.skyframe.serialization.DeferredObjectCodec
import com.google.devtools.build.lib.skyframe.serialization.DeferredObjectCodec.DeferredValue
import com.google.devtools.build.lib.skyframe.serialization.SerializationContext
import com.google.protobuf.CodedInputStream
import com.google.protobuf.CodedOutputStream
import java.io.IOException

/** Codec implementation for [CompactHashSet].  */
internal class CompactHashSetCodec :
    DeferredObjectCodec<com.google.devtools.build.lib.collect.compacthashset.CompactHashSet<*>?>() {
    val encodedClass: java.lang.Class<com.google.devtools.build.lib.collect.compacthashset.CompactHashSet<*>?>
        get() = com.google.devtools.build.lib.collect.compacthashset.CompactHashSet::class.java

    @Throws(com.google.devtools.build.lib.skyframe.serialization.SerializationException::class, IOException::class)
    override fun serialize(
        context: SerializationContext,
        obj: com.google.devtools.build.lib.collect.compacthashset.CompactHashSet<*>,
        codedOut: CodedOutputStream
    ) {
        codedOut.writeInt32NoTag(obj.size())
        for (elt in obj) {
            context.serialize(elt, codedOut)
        }
    }

    @Throws(com.google.devtools.build.lib.skyframe.serialization.SerializationException::class, IOException::class)
    override fun deserializeDeferred(
        context: AsyncDeserializationContext, codedIn: CodedInputStream
    ): DeferredValue<com.google.devtools.build.lib.collect.compacthashset.CompactHashSet<*>?> {
        val size: Int = codedIn.readInt32()
        val builder: Builder = com.google.devtools.build.lib.collect.compacthashset.CompactHashSetCodec.Builder(size)
        for (i in 0..<size) {
            context.deserializeArrayElement(codedIn, builder.elements, i)
        }
        return builder
    }

    private class Builder(size: Int) :
        DeferredValue<com.google.devtools.build.lib.collect.compacthashset.CompactHashSet<*>?> {
        private val elements: Array<Any?>

        init {
            this.elements = arrayOfNulls<Any>(size)
        }

        override fun call(): com.google.devtools.build.lib.collect.compacthashset.CompactHashSet<*> {
            return com.google.devtools.build.lib.collect.compacthashset.CompactHashSet.Companion.create<Any?>(*elements)
        }
    }
}
