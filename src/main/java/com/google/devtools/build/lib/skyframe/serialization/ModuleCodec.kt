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

/**
 * Codec for [Module].
 * 
 * 
 * Serializes using the module's associated [BzlLoadValue.Key].
 */
class ModuleCodec private constructor() : DeferredObjectCodec<net.starlark.java.eval.Module?>() {
    val encodedClass: java.lang.Class<net.starlark.java.eval.Module?>
        get() = net.starlark.java.eval.Module::class.java

    override fun autoRegister(): Boolean {
        // Unit tests that bypass Skyframe for Module loading cannot use this codec.
        return false
    }

    @Throws(IOException::class, com.google.devtools.build.lib.skyframe.serialization.SerializationException::class)
    override fun serialize(
        context: SerializationContext,
        obj: net.starlark.java.eval.Module?,
        codedOut: CodedOutputStream?
    ) {
        val moduleContext: Any = checkNotNull(BazelModuleContext.of(obj), "module %s missing context", obj)
        context.serializeLeaf<T?>(moduleContext.key() as BzlLoadValue.Key?, bzlLoadKeyCodec(), codedOut)
    }

    @Throws(IOException::class, com.google.devtools.build.lib.skyframe.serialization.SerializationException::class)
    override fun deserializeDeferred(
        context: AsyncDeserializationContext, codedIn: CodedInputStream?
    ): DeferredValue<net.starlark.java.eval.Module?> {
        val bzlLoadKey: BzlLoadValue.Key? = context.deserializeLeaf<T?>(codedIn, bzlLoadKeyCodec())
        val builder: DeserializationBuilder =
            com.google.devtools.build.lib.skyframe.serialization.ModuleCodec.DeserializationBuilder()
        context.getSkyValue<T?>(
            bzlLoadKey,
            builder,
            com.google.devtools.build.lib.skyframe.serialization.AsyncDeserializationContext.FieldSetter { builder: T?, value: Any? ->
                com.google.devtools.build.lib.skyframe.serialization.ModuleCodec.DeserializationBuilder.Companion.setBzlLoadValue(
                    builder,
                    value
                )
            })
        return builder
    }

    private class DeserializationBuilder : DeferredValue<net.starlark.java.eval.Module?> {
        private var loadValue: BzlLoadValue? = null

        override fun call(): net.starlark.java.eval.Module {
            return com.google.common.base.Preconditions.checkNotNull<Any?>(loadValue, "Skyframe lookup value not set")
                .getModule()
        }

        companion object {
            private fun setBzlLoadValue(builder: DeserializationBuilder, value: Any?) {
                builder.loadValue = value as BzlLoadValue?
            }
        }
    }

    companion object {
        private val INSTANCE = ModuleCodec()

        @kotlin.jvm.JvmStatic
        fun moduleCodec(): ModuleCodec {
            return INSTANCE
        }
    }
}
