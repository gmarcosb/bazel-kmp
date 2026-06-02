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

import com.google.devtools.build.lib.skyframe.serialization.LeafDeserializationContext
import com.google.devtools.build.lib.skyframe.serialization.LeafObjectCodec
import com.google.devtools.build.lib.skyframe.serialization.LeafSerializationContext
import com.google.devtools.build.lib.skyframe.serialization.strings.UnsafeStringCodec
import com.google.protobuf.CodedInputStream
import com.google.protobuf.CodedOutputStream
import java.io.IOException

/** Codec for [Class].  */
internal class ClassCodec : LeafObjectCodec<java.lang.Class<*>?>() {
    val encodedClass: java.lang.Class<java.lang.Class<*>?>
        get() = java.lang.Class::class.java as Any as java.lang.Class<java.lang.Class<*>?>

    @Throws(com.google.devtools.build.lib.skyframe.serialization.SerializationException::class, IOException::class)
    override fun serialize(context: LeafSerializationContext, obj: java.lang.Class<*>, codedOut: CodedOutputStream) {
        codedOut.writeBoolNoTag(obj.isPrimitive())
        if (obj.isPrimitive()) {
            codedOut.writeInt32NoTag(
                com.google.common.base.Preconditions.checkNotNull<Int?>(
                    PRIMITIVE_CLASS_INDEX_MAP.get(
                        obj
                    ), obj
                )
            )
        } else {
            context.serializeLeaf<String?>(obj.getName(), UnsafeStringCodec.Companion.stringCodec(), codedOut)
        }
    }

    @Throws(com.google.devtools.build.lib.skyframe.serialization.SerializationException::class, IOException::class)
    override fun deserialize(context: LeafDeserializationContext, codedIn: CodedInputStream): java.lang.Class<*>? {
        val isPrimitive: Boolean = codedIn.readBool()
        if (isPrimitive) {
            return PRIMITIVE_CLASS_INDEX_MAP.inverse().get(codedIn.readInt32())
        }
        val className: String? = context.deserializeLeaf<String?>(codedIn, UnsafeStringCodec.Companion.stringCodec())
        try {
            return java.lang.Class.forName(className)
        } catch (e: java.lang.ClassNotFoundException) {
            throw com.google.devtools.build.lib.skyframe.serialization.SerializationException(
                "Couldn't find class for " + className,
                e
            )
        }
    }

    companion object {
        private val INSTANCE = ClassCodec()

        fun classCodec(): ClassCodec {
            return INSTANCE
        }

        private val PRIMITIVE_CLASS_INDEX_MAP: com.google.common.collect.ImmutableBiMap<java.lang.Class<*>?, Int?> =
            com.google.common.collect.ImmutableBiMap.builder<java.lang.Class<*>?, Int?>()
                .put(Byte::class.javaPrimitiveType, 1)
                .put(Short::class.javaPrimitiveType, 2)
                .put(Int::class.javaPrimitiveType, 3)
                .put(Long::class.javaPrimitiveType, 4)
                .put(Char::class.javaPrimitiveType, 5)
                .put(Float::class.javaPrimitiveType, 6)
                .put(Double::class.javaPrimitiveType, 7)
                .put(Boolean::class.javaPrimitiveType, 8)
                .put(Void.TYPE, 9)
                .buildOrThrow()
    }
}
