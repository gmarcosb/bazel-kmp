// Copyright 2024 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.collect.nestedset

import com.google.devtools.build.lib.collect.nestedset.NestedSet
import com.google.devtools.build.lib.skyframe.serialization.AsyncDeserializationContext
import com.google.devtools.build.lib.skyframe.serialization.DeferredObjectCodec
import com.google.devtools.build.lib.skyframe.serialization.DeferredObjectCodec.DeferredValue
import com.google.devtools.build.lib.skyframe.serialization.SerializationContext
import com.google.protobuf.CodedInputStream
import com.google.protobuf.CodedOutputStream
import java.io.IOException

/**
 * Codec that handles nested `Object[]` with arbitrary contents.
 * 
 * 
 * This codec is intended for use with [SerializationContext.putSharedValue] and uses
 * [SerializationContext.putSharedValue] for subarrays to promoting sharing.
 */
internal class NestedArrayCodec private constructor() : DeferredObjectCodec<Array<Any?>?>() {
    override fun autoRegister(): Boolean {
        return false
    }

    val encodedClass: java.lang.Class<Array<Any?>?>
        get() = Array<Any>::class.java

    @Throws(com.google.devtools.build.lib.skyframe.serialization.SerializationException::class, IOException::class)
    override fun serialize(
        context: SerializationContext, nestedArray: Array<Any?>, codedOut: CodedOutputStream
    ) {
        val length: Int = nestedArray.length
        codedOut.writeInt32NoTag(length)
        for (i in 0..<length) {
            val child = nestedArray[i]
            if (child is Array<Any>) {
                codedOut.writeBoolNoTag(true)
                context.putSharedValue<Array<Any?>?>(
                    child as Array<Any?>,  /* distinguisher= */null,  /* codec= */this, codedOut
                )
            } else {
                codedOut.writeBoolNoTag(false)
                context.serialize(child, codedOut)
            }
        }
    }

    @Throws(com.google.devtools.build.lib.skyframe.serialization.SerializationException::class, IOException::class)
    override fun deserializeDeferred(
        context: AsyncDeserializationContext, codedIn: CodedInputStream
    ): DeferredValue<Array<Any?>?> {
        val length: Int = codedIn.readInt32()
        if (length == 0) {
            return DeferredValue { NestedSet.Companion.EMPTY_CHILDREN }
        }
        val values = arrayOfNulls<Any>(length)
        for (i in 0..<length) {
            if (codedIn.readBool()) {
                context.getSharedValue<Array<Any?>?>(
                    codedIn,  /* distinguisher= */null,  /* codec= */this, values, ArrayFieldSetter(i)
                )
            } else {
                context.deserialize<Array<Any?>?>(codedIn, values, ArrayFieldSetter(i))
            }
        }
        return DeferredValue { values }
    }

    private class ArrayFieldSetter
        (private val index: Int) : AsyncDeserializationContext.FieldSetter<Array<Any?>?> {
        override fun set(array: Array<Any?>, value: Any?) {
            array[index] = value
        }
    }

    companion object {
        private val INSTANCE: NestedArrayCodec = com.google.devtools.build.lib.collect.nestedset.NestedArrayCodec()

        fun nestedArrayCodec(): NestedArrayCodec {
            return com.google.devtools.build.lib.collect.nestedset.NestedArrayCodec.Companion.INSTANCE
        }
    }
}
