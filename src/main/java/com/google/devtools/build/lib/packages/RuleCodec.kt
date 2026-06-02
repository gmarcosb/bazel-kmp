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
package com.google.devtools.build.lib.packages

import com.google.devtools.build.lib.skyframe.serialization.LeafDeserializationContext
import com.google.devtools.build.lib.skyframe.serialization.LeafObjectCodec
import com.google.devtools.build.lib.skyframe.serialization.LeafSerializationContext
import com.google.protobuf.CodedInputStream
import com.google.protobuf.CodedOutputStream

/**
 * Codec for [Rule] that throws. We expect never to serialize Rule except for in PackageCodec,
 * which has custom logic.
 */
class RuleCodec : LeafObjectCodec<com.google.devtools.build.lib.packages.Rule?>() {
    override fun getEncodedClass(): java.lang.Class<out com.google.devtools.build.lib.packages.Rule?> {
        return com.google.devtools.build.lib.packages.Rule::class.java
    }

    @Throws(com.google.devtools.build.lib.skyframe.serialization.SerializationException::class)
    override fun serialize(
        context: LeafSerializationContext?,
        obj: com.google.devtools.build.lib.packages.Rule?,
        codedOut: CodedOutputStream?
    ) {
        throw com.google.devtools.build.lib.skyframe.serialization.SerializationException(
            java.lang.String.format(
                SERIALIZATION_ERROR_TEMPLATE, obj
            )
        )
    }

    @Throws(com.google.devtools.build.lib.skyframe.serialization.SerializationException::class)
    override fun deserialize(
        context: LeafDeserializationContext?,
        codedIn: CodedInputStream?
    ): com.google.devtools.build.lib.packages.Rule? {
        throw com.google.devtools.build.lib.skyframe.serialization.SerializationException(DESERIALIZATION_ERROR_TEMPLATE)
    }

    companion object {
        @kotlin.jvm.JvmField
        @com.google.common.annotations.VisibleForTesting
        val SERIALIZATION_ERROR_TEMPLATE: String =
            ("Rule serialization is not permitted outside of PackageCodec, but attempted to serialize "
                    + "Rule %s.")

        private val DESERIALIZATION_ERROR_TEMPLATE =
            ("Rule deserialization is not permitted outside of PackageCodec, but attempted to deserialize "
                    + "a rule")
    }
}
