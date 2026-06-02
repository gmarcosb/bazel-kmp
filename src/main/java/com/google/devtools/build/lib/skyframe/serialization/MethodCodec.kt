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

import com.google.devtools.build.lib.skyframe.serialization.ClassCodec
import com.google.devtools.build.lib.skyframe.serialization.LeafDeserializationContext
import com.google.devtools.build.lib.skyframe.serialization.LeafObjectCodec
import com.google.devtools.build.lib.skyframe.serialization.LeafSerializationContext
import com.google.devtools.build.lib.skyframe.serialization.strings.UnsafeStringCodec
import com.google.protobuf.CodedInputStream
import com.google.protobuf.CodedOutputStream
import java.io.IOException

/** [ObjectCodec] for [Method].  */
internal class MethodCodec : LeafObjectCodec<java.lang.reflect.Method?>() {
    override fun getEncodedClass(): java.lang.Class<java.lang.reflect.Method?> {
        return java.lang.reflect.Method::class.java
    }

    @Throws(com.google.devtools.build.lib.skyframe.serialization.SerializationException::class, IOException::class)
    override fun serialize(
        context: LeafSerializationContext,
        obj: java.lang.reflect.Method,
        codedOut: CodedOutputStream
    ) {
        context.serializeLeaf<java.lang.Class<*>?>(obj.getDeclaringClass(), ClassCodec.Companion.classCodec(), codedOut)
        context.serializeLeaf<String?>(obj.getName(), UnsafeStringCodec.Companion.stringCodec(), codedOut)
        val parameterTypes: Array<java.lang.Class<*>?> = obj.getParameterTypes()
        codedOut.writeInt32NoTag(parameterTypes.size)
        for (parameter in parameterTypes) {
            context.serializeLeaf<java.lang.Class<*>?>(parameter, ClassCodec.Companion.classCodec(), codedOut)
        }
    }

    @Throws(com.google.devtools.build.lib.skyframe.serialization.SerializationException::class, IOException::class)
    override fun deserialize(
        context: LeafDeserializationContext,
        codedIn: CodedInputStream
    ): java.lang.reflect.Method? {
        val clazz: java.lang.Class<*> =
            context.deserializeLeaf<java.lang.Class<*>?>(codedIn, ClassCodec.Companion.classCodec())
        val name: String = context.deserializeLeaf<String>(codedIn, UnsafeStringCodec.Companion.stringCodec())

        val parameters: Array<java.lang.Class<*>?> = arrayOfNulls<java.lang.Class<*>>(codedIn.readInt32())
        /* !!! Hit visitElement for element type: class org.jetbrains.kotlin.nj2k.tree.JKJavaForLoopStatement !!! */
        try {
            return clazz.getDeclaredMethod(name, *parameters)
        } catch (e: java.lang.NoSuchMethodException) {
            throw com.google.devtools.build.lib.skyframe.serialization.SerializationException(
                ("Couldn't get method "
                        + name
                        + " in "
                        + clazz
                        + " with parameters "
                        + java.util.Arrays.toString(parameters)),
                e
            )
        }
    }
}
