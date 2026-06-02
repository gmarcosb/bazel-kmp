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

import com.google.devtools.build.lib.skyframe.BzlLoadValue.bzlLoadKeyCodec

/** Base codec for exported Starlark symbols.  */
abstract class AbstractExportedStarlarkSymbolCodec<T> : DeferredObjectCodec<T?>() {
    @com.google.errorprone.annotations.ForOverride
    protected abstract fun getBzlLoadKey(obj: T?): BzlLoadValue.Key?

    @com.google.errorprone.annotations.ForOverride
    protected abstract fun getExportedName(obj: T?): String?

    @Throws(com.google.devtools.build.lib.skyframe.serialization.SerializationException::class, IOException::class)
    override fun serialize(context: SerializationContext, obj: T?, codedOut: CodedOutputStream?) {
        context.serializeLeaf<T?>(getBzlLoadKey(obj), bzlLoadKeyCodec(), codedOut)
        context.serializeLeaf<String?>(getExportedName(obj), UnsafeStringCodec.stringCodec(), codedOut)
    }

    @Throws(com.google.devtools.build.lib.skyframe.serialization.SerializationException::class, IOException::class)
    override fun deserializeDeferred(
        context: AsyncDeserializationContext, codedIn: CodedInputStream?
    ): DeferredValue<out T?> {
        val bzlLoadKey: BzlLoadValue.Key? = context.deserializeLeaf<T?>(codedIn, bzlLoadKeyCodec())
        val name: String? = context.deserializeLeaf<String?>(codedIn, UnsafeStringCodec.stringCodec())

        val builder: DeserializationBuilder<out T?> =
            com.google.devtools.build.lib.skyframe.serialization.AbstractExportedStarlarkSymbolCodec.DeserializationBuilder<T?>(
                getEncodedClass(),
                name
            )
        context.getSkyValue<T?>(
            bzlLoadKey,
            builder,
            AsyncDeserializationContext.FieldSetter { builder: T?, value: Any? ->
                com.google.devtools.build.lib.skyframe.serialization.AbstractExportedStarlarkSymbolCodec.DeserializationBuilder.Companion.setBzlLoadValue(
                    builder,
                    value
                )
            })
        return builder
    }

    private class DeserializationBuilder<T>(type: java.lang.Class<T?>, name: String?) : DeferredValue<T?> {
        private val type: java.lang.Class<T?>
        private val name: String?
        private var loadValue: BzlLoadValue? = null

        init {
            this.type = type
            this.name = name
        }

        override fun call(): T? {
            val module: net.starlark.java.eval.Module =
                com.google.common.base.Preconditions.checkNotNull<Any?>(loadValue, "Skyframe lookup value not set")
                    .getModule()
            return type.cast(
                com.google.common.base.Preconditions.checkNotNull<Any?>(
                    module.getGlobal(name),
                    "%s not found in %s",
                    name,
                    module
                )
            )
        }

        companion object {
            private fun setBzlLoadValue(builder: DeserializationBuilder<*>, value: Any?) {
                builder.loadValue = value as BzlLoadValue?
            }
        }
    }
}
