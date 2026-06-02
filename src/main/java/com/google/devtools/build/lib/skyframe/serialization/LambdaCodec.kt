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
import com.google.devtools.build.lib.skyframe.serialization.DeferredObjectCodec
import com.google.devtools.build.lib.skyframe.serialization.DeferredObjectCodec.DeferredValue
import com.google.devtools.build.lib.skyframe.serialization.SerializationContext
import com.google.devtools.build.lib.unsafe.UnsafeProvider
import com.google.protobuf.CodedInputStream
import com.google.protobuf.CodedOutputStream
import java.io.IOException

/**
 * A codec for Java 8 serializable lambdas. Lambdas that are tagged as [Serializable] have a
 * generated method, `writeReplace`, that converts them into a [SerializedLambda], which
 * can then be serialized like a normal object. On deserialization, we call [ ][SerializedLambda.readResolve], which converts the object back into a lambda.
 * 
 * 
 * Since lambdas do not share a common base class, choosing this codec for serializing them must
 * be special-cased in [ObjectCodecRegistry]. We must also make a somewhat arbitrary choice
 * around the generic parameter. Since all of our lambdas are [Serializable], we use that.
 * Because [Serializable] is an interface, not a class, this codec will never be chosen for
 * any object without special-casing.
 */
internal class LambdaCodec : DeferredObjectCodec<java.io.Serializable?>() {
    override fun getEncodedClass(): java.lang.Class<out java.io.Serializable?> {
        return java.io.Serializable::class.java
    }

    @Throws(com.google.devtools.build.lib.skyframe.serialization.SerializationException::class, IOException::class)
    override fun serialize(context: SerializationContext, obj: java.io.Serializable, codedOut: CodedOutputStream?) {
        val objClass: java.lang.Class<*> = obj.getClass()
        if (!isProbablyLambda(objClass)) {
            throw com.google.devtools.build.lib.skyframe.serialization.SerializationException(obj.toString() + " is not a lambda: " + objClass)
        }
        val writeReplaceMethod: java.lang.reflect.Method
        try {
            // TODO(janakr): We could cache these methods if retrieval shows up as a hotspot.
            writeReplaceMethod = objClass.getDeclaredMethod("writeReplace")
        } catch (e: java.lang.NoSuchMethodException) {
            throw com.google.devtools.build.lib.skyframe.serialization.SerializationException(
                "No writeReplace method for " + obj + " with " + objClass, e
            )
        }
        writeReplaceMethod.setAccessible(true)
        val serializedLambda: java.lang.invoke.SerializedLambda?
        try {
            serializedLambda = writeReplaceMethod.invoke(obj) as java.lang.invoke.SerializedLambda?
        } catch (e: java.lang.ReflectiveOperationException) {
            throw com.google.devtools.build.lib.skyframe.serialization.SerializationException(
                "Exception invoking writeReplace for " + obj + " with " + objClass, e
            )
        }
        context.serialize(serializedLambda, codedOut)
    }

    @Throws(com.google.devtools.build.lib.skyframe.serialization.SerializationException::class, IOException::class)
    override fun deserializeDeferred(
        context: AsyncDeserializationContext, codedIn: CodedInputStream?
    ): DeferredValue<java.io.Serializable?> {
        val supplier = LambdaSupplier()
        context.deserialize(codedIn, supplier, SERIALIZED_LAMBDA_OFFSET)
        return supplier
    }

    private class LambdaSupplier : DeferredValue<java.io.Serializable?> {
        private val serializedLambda: java.lang.invoke.SerializedLambda? = null

        override fun call(): java.io.Serializable? {
            try {
                return READ_RESOLVE_METHOD.invoke(serializedLambda) as java.io.Serializable?
            } catch (e: java.lang.ReflectiveOperationException) {
                throw java.lang.IllegalStateException("Error read-resolving " + serializedLambda, e)
            }
        }
    }

    companion object {
        private val READ_RESOLVE_METHOD: java.lang.reflect.Method
        private val SERIALIZED_LAMBDA_OFFSET: Long

        init {
            try {
                READ_RESOLVE_METHOD = java.lang.invoke.SerializedLambda::class.java.getDeclaredMethod("readResolve")
                SERIALIZED_LAMBDA_OFFSET = UnsafeProvider.getFieldOffset(LambdaSupplier::class.java, "serializedLambda")
            } catch (e: java.lang.ReflectiveOperationException) {
                throw java.lang.ExceptionInInitializerError(e)
            }
            READ_RESOLVE_METHOD.setAccessible(true)
        }

        fun isProbablyLambda(type: java.lang.Class<*>): Boolean {
            return type.isSynthetic() && !type.isLocalClass() && !type.isAnonymousClass()
        }
    }
}
