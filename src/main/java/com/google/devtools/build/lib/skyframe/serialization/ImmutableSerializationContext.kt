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
package com.google.devtools.build.lib.skyframe.serialization

import com.google.devtools.build.lib.skyframe.serialization.LeafObjectCodec
import com.google.devtools.build.lib.skyframe.serialization.LeafSerializationContext
import com.google.devtools.build.lib.skyframe.serialization.ObjectCodec
import com.google.devtools.build.lib.skyframe.serialization.ObjectCodecRegistry
import com.google.devtools.build.lib.skyframe.serialization.ProfileRecorder
import com.google.devtools.build.lib.skyframe.serialization.SerializationContext
import com.google.protobuf.CodedOutputStream
import java.io.IOException

/**
 * An explicitly immutable implementation of [SerializationContext].
 * 
 * 
 * Immutability makes this class thread safe.
 */
internal class ImmutableSerializationContext(
    codecRegistry: ObjectCodecRegistry?,
    dependencies: com.google.common.collect.ImmutableClassToInstanceMap<Any?>?
) : SerializationContext(codecRegistry, dependencies) {
    override fun getFreshContext(): ImmutableSerializationContext {
        return this
    }

    @Throws(com.google.devtools.build.lib.skyframe.serialization.SerializationException::class)
    override fun addExplicitlyAllowedClass(allowedClass: java.lang.Class<*>?) {
        throw com.google.devtools.build.lib.skyframe.serialization.SerializationException(
            "Cannot add explicitly allowed class %s without memoization: " + allowedClass
        )
    }

    @Throws(com.google.devtools.build.lib.skyframe.serialization.SerializationException::class)
    override fun <T> checkClassExplicitlyAllowed(allowedClass: java.lang.Class<T?>?, objectForDebugging: T?) {
        throw com.google.devtools.build.lib.skyframe.serialization.SerializationException(
            ("Cannot check explicitly allowed class "
                    + allowedClass
                    + " without memoization ("
                    + objectForDebugging
                    + ")")
        )
    }

    @Throws(com.google.devtools.build.lib.skyframe.serialization.SerializationException::class, IOException::class)
    override fun serializeWithCodec(codec: ObjectCodec<Any?>, obj: Any?, codedOut: CodedOutputStream?) {
        codec.serialize(this, obj, codedOut)
    }

    @Throws(com.google.devtools.build.lib.skyframe.serialization.SerializationException::class, IOException::class)
    override fun <T> serializeLeaf(
        obj: T?, codec: LeafObjectCodec<T?>, codedOut: CodedOutputStream
    ) {
        if (writeIfNullOrConstant(obj, codedOut)) {
            return
        }
        // It was not constant or null. Emits -1 to signal an immediate value and serializes the value.
        codedOut.writeSInt32NoTag(-1)
        codec.serialize(this as LeafSerializationContext, obj, codedOut)
    }

    override fun writeBackReferenceIfMemoized(obj: Any?, codedOut: CodedOutputStream?, isLeafType: Boolean): Boolean {
        return false
    }

    override fun isMemoizing(): Boolean {
        return false
    }

    override fun getProfileRecorder(): ProfileRecorder? {
        return null
    }
}
