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

import com.google.devtools.build.lib.skyframe.serialization.AutoRegistry
import com.google.devtools.build.lib.skyframe.serialization.DeserializationContext
import com.google.devtools.build.lib.skyframe.serialization.LeafDeserializationContext
import com.google.devtools.build.lib.skyframe.serialization.LeafObjectCodec
import com.google.devtools.build.lib.skyframe.serialization.ObjectCodec
import com.google.devtools.build.lib.skyframe.serialization.ObjectCodecRegistry
import com.google.protobuf.CodedInputStream
import java.io.IOException

/** An immutable deserialization context.  */
class ImmutableDeserializationContext @com.google.common.annotations.VisibleForTesting constructor(
    registry: ObjectCodecRegistry?,
    dependencies: com.google.common.collect.ImmutableClassToInstanceMap<Any?>?
) : DeserializationContext(registry, dependencies) {
    @com.google.common.annotations.VisibleForTesting
    constructor(dependencies: com.google.common.collect.ImmutableClassToInstanceMap<Any?>?) : this(
        AutoRegistry.get(),
        dependencies
    )

    @com.google.common.annotations.VisibleForTesting
    constructor() : this(com.google.common.collect.ImmutableClassToInstanceMap.of<Any?>())

    override fun getFreshContext(): ImmutableDeserializationContext {
        return this
    }

    override fun registerInitialValue(initialValue: Any?) {}

    @Throws(com.google.devtools.build.lib.skyframe.serialization.SerializationException::class, IOException::class)
    override fun deserializeAndMaybeMemoize(codec: ObjectCodec<*>, codedIn: CodedInputStream?): Any? {
        return codec.deserialize(this, codedIn)
    }

    @Throws(com.google.devtools.build.lib.skyframe.serialization.SerializationException::class, IOException::class)
    override fun <T> deserializeLeaf(codedIn: CodedInputStream, codec: LeafObjectCodec<T?>): T? {
        val tag: Int = codedIn.readSInt32()
        if (tag == 0) {
            return null
        }
        val maybeConstant: Any? = codec.safeCast(maybeGetConstantByTag(tag))
        if (maybeConstant != null) {
            return codec.safeCast(maybeConstant)
        }
        return codec.deserialize(this as LeafDeserializationContext, codedIn)
    }

    override fun getMemoizedBackReference(memoIndex: Int): Any? {
        throw java.lang.UnsupportedOperationException(
            "The tag should never be less than 0 in the stateless case"
        )
    }
}
