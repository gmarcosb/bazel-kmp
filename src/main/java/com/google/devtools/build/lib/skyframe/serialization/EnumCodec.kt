// Copyright 2017 The Bazel Authors. All rights reserved.
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
import com.google.protobuf.CodedInputStream
import com.google.protobuf.CodedOutputStream
import java.io.IOException

/** Codec for an enum.  */
open class EnumCodec<T : Enum<T?>?>(enumClass: java.lang.Class<T?>) : LeafObjectCodec<T?>() {
    private val enumClass: java.lang.Class<T?>

    /**
     * A cached copy of T.values(), to avoid allocating an array upon every deserialization operation.
     */
    private val values: com.google.common.collect.ImmutableList<T?>

    init {
        this.enumClass = enumClass
        this.values = com.google.common.collect.ImmutableList.copyOf<T?>(enumClass.getEnumConstants())
    }

    override fun getEncodedClass(): java.lang.Class<T?> {
        return enumClass
    }

    @Throws(IOException::class)
    override fun serialize(context: LeafSerializationContext?, value: T?, codedOut: CodedOutputStream) {
        com.google.common.base.Preconditions.checkNotNull<T?>(value, "Enum value for %s is null", enumClass)
        codedOut.writeEnumNoTag(value.ordinal())
    }

    @Throws(com.google.devtools.build.lib.skyframe.serialization.SerializationException::class, IOException::class)
    override fun deserialize(context: LeafDeserializationContext?, codedIn: CodedInputStream): T? {
        val ordinal: Int = codedIn.readEnum()
        try {
            return values.get(ordinal)
        } catch (e: java.lang.ArrayIndexOutOfBoundsException) {
            throw com.google.devtools.build.lib.skyframe.serialization.SerializationException(
                "Invalid ordinal for " + enumClass.getName() + " enum: " + ordinal, e
            )
        }
    }
}
