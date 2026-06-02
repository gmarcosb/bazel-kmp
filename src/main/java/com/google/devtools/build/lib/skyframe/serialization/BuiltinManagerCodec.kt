// Copyright 2026 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.skyframe.PrecomputedValue

/**
 * A codec for [BuiltinManager] that serializes no payload on the wire, and which deserializes
 * by simply requesting the manager for the [StarlarkSemantics] that's stored in Skyframe as a
 * precomputed.
 * 
 * 
 * The [BuiltinManager] generally needs to be serialized for the sake of [ ], which itself is referred to by [BuiltinFunction]. That is, this is
 * needed when builtin Starlark symbols (e.g. `len()`, or .bzl-specific native symbols) get
 * serialized.
 */
class BuiltinManagerCodec : DeferredObjectCodec<net.starlark.java.eval.CallUtils.BuiltinManager?>() {
    val encodedClass: java.lang.Class<net.starlark.java.eval.CallUtils.BuiltinManager?>
        get() = net.starlark.java.eval.CallUtils.BuiltinManager::class.java

    override fun serialize(
        context: SerializationContext?,
        obj: net.starlark.java.eval.CallUtils.BuiltinManager?,
        codedOut: CodedOutputStream?
    ) {
        // Nothing to serialize.
    }

    @Throws(com.google.devtools.build.lib.skyframe.serialization.SerializationException::class)
    override fun deserializeDeferred(
        context: AsyncDeserializationContext, codedIn: CodedInputStream?
    ): DeferredValue<net.starlark.java.eval.CallUtils.BuiltinManager?> {
        val builder: DeserializationBuilder =
            com.google.devtools.build.lib.skyframe.serialization.BuiltinManagerCodec.DeserializationBuilder()
        context.getSkyValue<T?>(
            PrecomputedValue.STARLARK_SEMANTICS.getKey(),
            builder,
            com.google.devtools.build.lib.skyframe.serialization.AsyncDeserializationContext.FieldSetter { builder: T?, value: Any? ->
                com.google.devtools.build.lib.skyframe.serialization.BuiltinManagerCodec.DeserializationBuilder.Companion.setStarlarkSemanticsFromPrecomputedValue(
                    builder,
                    value
                )
            })

        return builder
    }

    private class DeserializationBuilder : DeferredValue<net.starlark.java.eval.CallUtils.BuiltinManager?> {
        private var semantics: net.starlark.java.eval.StarlarkSemantics? = null

        override fun call(): net.starlark.java.eval.CallUtils.BuiltinManager? {
            com.google.common.base.Preconditions.checkNotNull<net.starlark.java.eval.StarlarkSemantics?>(
                semantics,
                "StarlarkSemantics not set"
            )
            return net.starlark.java.eval.CallUtils.getBuiltinManager(semantics)
        }

        companion object {
            private fun setStarlarkSemanticsFromPrecomputedValue(
                builder: DeserializationBuilder, value: Any
            ) {
                builder.semantics = (value as PrecomputedValue).get() as net.starlark.java.eval.StarlarkSemantics?
            }
        }
    }
}
