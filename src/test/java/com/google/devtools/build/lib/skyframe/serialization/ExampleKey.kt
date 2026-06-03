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

import com.google.devtools.build.lib.skyframe.serialization.strings.UnsafeStringCodec.stringCodec

/** An example [SkyKey] for testing serialization involving Skyframe.  */
@kotlin.jvm.JvmRecord
internal data class ExampleKey(val name: String?) : SkyKey {
    public override fun functionName(): SkyFunctionName? {
        throw java.lang.UnsupportedOperationException()
    }

    private class ExampleKeyCodec : LeafObjectCodec<ExampleKey?>() {
        val encodedClass: java.lang.Class<ExampleKey?>
            get() = ExampleKey::class.java

        @Throws(SerializationException::class, IOException::class)
        public override fun serialize(
            context: LeafSerializationContext, key: ExampleKey, codedOut: CodedOutputStream?
        ) {
            context.serializeLeaf(key.name, stringCodec(), codedOut)
        }

        @Throws(SerializationException::class, IOException::class)
        public override fun deserialize(context: LeafDeserializationContext, codedIn: CodedInputStream?): ExampleKey {
            return ExampleKey(context.deserializeLeaf(codedIn, stringCodec()))
        }

        companion object {
            private val INSTANCE = ExampleKeyCodec()
        }
    }

    companion object {
        fun exampleKeyCodec(): ExampleKeyCodec {
            return ExampleKeyCodec.Companion.INSTANCE
        }
    }
}
