// Copyright 2015 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.packages

import com.google.devtools.build.lib.packages.AspectClass
import com.google.devtools.build.lib.packages.AspectDefinition
import com.google.devtools.build.lib.packages.AspectParameters
import com.google.devtools.build.lib.packages.RuleClassProvider
import com.google.devtools.build.lib.skyframe.serialization.LeafDeserializationContext
import com.google.devtools.build.lib.skyframe.serialization.LeafObjectCodec
import com.google.devtools.build.lib.skyframe.serialization.LeafSerializationContext
import com.google.devtools.build.lib.skyframe.serialization.strings.UnsafeStringCodec
import com.google.protobuf.CodedInputStream
import com.google.protobuf.CodedOutputStream
import java.io.IOException

/**
 * A class of aspects that are implemented natively in Bazel.
 * 
 * 
 * This class just wraps a [java.lang.Class] implementing the
 * aspect factory. All wrappers of the same class are equal.
 */
abstract class NativeAspectClass : AspectClass {
    override fun getName(): String? {
        return getClass().getSimpleName()
    }

    abstract fun getDefinition(aspectParameters: AspectParameters?): AspectDefinition?

    @com.google.errorprone.annotations.Keep // used reflectively
    private class Codec : LeafObjectCodec<NativeAspectClass?>() {
        override fun getEncodedClass(): java.lang.Class<NativeAspectClass?> {
            return NativeAspectClass::class.java
        }

        @Throws(com.google.devtools.build.lib.skyframe.serialization.SerializationException::class, IOException::class)
        override fun serialize(
            context: LeafSerializationContext, obj: NativeAspectClass, codedOut: CodedOutputStream?
        ) {
            val ruleClassProvider: RuleClassProvider =
                context.getDependency<RuleClassProvider>(RuleClassProvider::class.java)
            val storedAspect: NativeAspectClass? = ruleClassProvider.getNativeAspectClass(obj.getKey())
            com.google.common.base.Preconditions.checkState(
                obj === storedAspect, "Not stored right: %s %s %s", obj, storedAspect, ruleClassProvider
            )
            context.serializeLeaf<String?>(obj.getKey(), UnsafeStringCodec.stringCodec(), codedOut)
        }

        @Throws(com.google.devtools.build.lib.skyframe.serialization.SerializationException::class, IOException::class)
        override fun deserialize(
            context: LeafDeserializationContext, codedIn: CodedInputStream?
        ): NativeAspectClass {
            val aspectKey: String? = context.deserializeLeaf<String?>(codedIn, UnsafeStringCodec.stringCodec())
            return com.google.common.base.Preconditions.checkNotNull<NativeAspectClass>(
                context.getDependency<RuleClassProvider?>(RuleClassProvider::class.java)
                    .getNativeAspectClass(aspectKey),
                aspectKey
            )
        }
    }
}
