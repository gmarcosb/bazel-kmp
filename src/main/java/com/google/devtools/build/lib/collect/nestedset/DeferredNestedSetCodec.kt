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
import com.google.devtools.build.zip.ZipReader.entries
import com.google.protobuf.CodedInputStream
import com.google.protobuf.CodedOutputStream
import java.io.IOException

/**
 * A codec implementation that is asynchronous-compatible.
 * 
 * 
 * This is required if deserialization of the [NestedSet] elements may perform Skyframe
 * lookups using [AsyncDeserializationContext.getSkyValue].
 */
class DeferredNestedSetCodec : DeferredObjectCodec<NestedSet<*>?>() {
    override fun autoRegister(): Boolean {
        return false
    }

    val encodedClass: java.lang.Class<NestedSet<*>?>
        get() = (NestedSet::class.java as java.lang.Class<*>) as java.lang.Class<NestedSet<*>?>

    @Throws(com.google.devtools.build.lib.skyframe.serialization.SerializationException::class, IOException::class)
    override fun serialize(context: SerializationContext, obj: NestedSet<*>, codedOut: CodedOutputStream) {
        com.google.common.base.Preconditions.checkState(
            !obj.isEmpty(),
            "empty NestedSet should have been a serialization constant"
        )
        codedOut.writeInt32NoTag(obj.getDepthAndOrder())
        if (obj.isSingleton()) {
            codedOut.writeBoolNoTag(true)
            context.serialize(obj.getChildren(), codedOut)
            return
        }
        codedOut.writeBoolNoTag(false)
        context.putSharedValue<Array<Any?>?>(
            obj.getChildren() as Array<Any?>?,  /* distinguisher= */
            null,
            com.google.devtools.build.lib.collect.nestedset.NestedArrayCodec.Companion.nestedArrayCodec(),
            codedOut
        )
    }

    @Throws(com.google.devtools.build.lib.skyframe.serialization.SerializationException::class, IOException::class)
    override fun deserializeDeferred(
        context: AsyncDeserializationContext, codedIn: CodedInputStream
    ): DeferredValue<NestedSet<*>?> {
        val depthAndOrder: Int = codedIn.readInt32()
        val order: com.google.devtools.build.lib.collect.nestedset.Order? =
            com.google.devtools.build.lib.collect.nestedset.Order.entries[depthAndOrder and 3]
        val depth = depthAndOrder shr 2
        val builder: DeserializationBuilder =
            com.google.devtools.build.lib.collect.nestedset.DeferredNestedSetCodec.DeserializationBuilder(order, depth)
        if (codedIn.readBool()) { // singleton
            context.deserialize<DeserializationBuilder?>(
                codedIn,
                builder,
                AsyncDeserializationContext.FieldSetter { builder: DeserializationBuilder?, children: Any? ->
                    com.google.devtools.build.lib.collect.nestedset.DeferredNestedSetCodec.DeserializationBuilder.Companion.setChildren(
                        builder,
                        children
                    )
                })
        } else {
            context.getSharedValue<DeserializationBuilder?>(
                codedIn,  /* distinguisher= */
                null,
                com.google.devtools.build.lib.collect.nestedset.NestedArrayCodec.Companion.nestedArrayCodec(),
                builder,
                AsyncDeserializationContext.FieldSetter { builder: DeserializationBuilder?, children: Any? ->
                    com.google.devtools.build.lib.collect.nestedset.DeferredNestedSetCodec.DeserializationBuilder.Companion.setChildren(
                        builder,
                        children
                    )
                })
        }
        return builder
    }

    private class DeserializationBuilder(
        order: com.google.devtools.build.lib.collect.nestedset.Order?,
        approxDepth: Int
    ) : DeferredValue<NestedSet<*>?> {
        private val order: com.google.devtools.build.lib.collect.nestedset.Order?
        private val approxDepth: Int
        private var children: Any? = null

        init {
            this.order = order
            this.approxDepth = approxDepth
        }

        override fun call(): NestedSet<*> {
            return NestedSet.Companion.forDeserialization<Any?>(order, approxDepth, children)
        }

        companion object {
            private fun setChildren(builder: DeserializationBuilder, children: Any?) {
                builder.children = children
            }
        }
    }

    companion object {
        init {
            // A sanity check of for NestedSet.depthAndOrder properties, which this codec depends on.
            com.google.common.base.Preconditions.checkState(com.google.devtools.build.lib.collect.nestedset.Order.entries.toTypedArray().length == 4)
        }
    }
}
