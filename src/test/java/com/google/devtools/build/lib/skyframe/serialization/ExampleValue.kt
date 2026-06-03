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

import com.google.devtools.build.lib.skyframe.serialization.DeferredObjectCodec.DeferredValue

/** Class deserialized using [DeserializationContext.getSkyValue].  */
internal class ExampleValue(key: ExampleKey?, x: Int) : SkyValue {
    private class ExampleValueCodec : DeferredObjectCodec<ExampleValue?>() {
        val encodedClass: java.lang.Class<ExampleValue?>
            get() = ExampleValue::class.java

        @Throws(SerializationException::class, IOException::class)
        public override fun serialize(
            context: SerializationContext, obj: ExampleValue, codedOut: CodedOutputStream?
        ) {
            context.serializeLeaf(obj.key, ExampleKey.Companion.exampleKeyCodec(), codedOut)
        }

        @Throws(SerializationException::class, IOException::class)
        public override fun deserializeDeferred(
            context: AsyncDeserializationContext, codedIn: CodedInputStream?
        ): DeferredValue<ExampleValue?>? {
            val key: ExampleKey? = context.deserializeLeaf(codedIn, ExampleKey.Companion.exampleKeyCodec())
            val builder: SimpleDeferredValue<ExampleValue?>? = SimpleDeferredValue.create()
            context.getSkyValue(key, builder, SimpleDeferredValue::set)
            return builder
        }

        companion object {
            private val INSTANCE = ExampleValueCodec()
        }
    }

    val key: ExampleKey?
    val x: Int

    init {
        this.key = key
        this.x = x
    }

    companion object {
        fun exampleValueCodec(): ExampleValueCodec {
            return ExampleValueCodec.Companion.INSTANCE
        }
    }
}
