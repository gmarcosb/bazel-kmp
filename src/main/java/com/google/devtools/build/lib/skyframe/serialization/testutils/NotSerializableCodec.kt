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
package com.google.devtools.build.lib.skyframe.serialization.testutils

import com.google.devtools.build.lib.skyframe.serialization.LeafDeserializationContext
import com.google.devtools.build.lib.skyframe.serialization.LeafObjectCodec
import com.google.devtools.build.lib.skyframe.serialization.LeafSerializationContext
import com.google.protobuf.CodedInputStream
import com.google.protobuf.CodedOutputStream
import java.io.NotSerializableException

/** A testing helper to force serialization errors.  */
class NotSerializableCodec(type: java.lang.Class<*>?) : LeafObjectCodec<Any?>() {
    private val type: java.lang.Class<*>?

    init {
        this.type = type
    }

    override fun getEncodedClass(): java.lang.Class<*>? {
        return type
    }

    @Throws(NotSerializableException::class)
    override fun serialize(
        context: LeafSerializationContext?, unusedObj: Any?, unusedCodedOut: CodedOutputStream?
    ) {
        throw NotSerializableException(type.toString() + " marked not serializable")
    }

    @Throws(NotSerializableException::class)
    override fun deserialize(context: LeafDeserializationContext?, unusedCodedIn: CodedInputStream?): Any? {
        throw NotSerializableException(type.toString() + " marked not serializable")
    }
}
