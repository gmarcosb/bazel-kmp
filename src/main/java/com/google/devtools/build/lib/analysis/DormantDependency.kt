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
package com.google.devtools.build.lib.analysis

import com.google.devtools.build.lib.skyframe.serialization.LeafDeserializationContext
import com.google.devtools.build.lib.skyframe.serialization.LeafObjectCodec
import com.google.devtools.build.lib.skyframe.serialization.LeafSerializationContext
import com.google.protobuf.CodedInputStream
import com.google.protobuf.CodedOutputStream
import java.io.IOException

/**
 * Not an actual dependency, but the possibility of one.
 * 
 * 
 * Dormant attributes result in an instance of this object for each possible dependency edge. It
 * can then be passed up the dependency graph and turned into an actual dependency ("materialized")
 * by rules in the reverse transitive closure.
 */
class DormantDependency(label: com.google.devtools.build.lib.cmdline.Label?) : net.starlark.java.eval.StarlarkValue {
    override fun repr(printer: net.starlark.java.eval.Printer, semantics: net.starlark.java.eval.StarlarkSemantics?) {
        printer.append("<dormant dependency label='")
        printer.str(label, semantics)
        printer.append("'>")
    }

    @net.starlark.java.annot.StarlarkMethod(name = "label", structField = true, doc = "TBD")
    fun getLabel(): com.google.devtools.build.lib.cmdline.Label? {
        return label
    }

    override fun isImmutable(): Boolean {
        return true
    }

    override fun toString(): String {
        return "<dormant dependency " + label.toString() + ">"
    }

    @com.google.errorprone.annotations.Keep
    private class Codec : LeafObjectCodec<DormantDependency?>() {
        override fun getEncodedClass(): java.lang.Class<DormantDependency?> {
            return DormantDependency::class.java
        }

        @Throws(com.google.devtools.build.lib.skyframe.serialization.SerializationException::class, IOException::class)
        override fun serialize(
            context: LeafSerializationContext, obj: DormantDependency, codedOut: CodedOutputStream?
        ) {
            context.serializeLeaf<com.google.devtools.build.lib.cmdline.Label?>(
                obj.label,
                com.google.devtools.build.lib.cmdline.Label.labelCodec(),
                codedOut
            )
        }

        @Throws(com.google.devtools.build.lib.skyframe.serialization.SerializationException::class, IOException::class)
        override fun deserialize(
            context: LeafDeserializationContext, codedIn: CodedInputStream?
        ): DormantDependency {
            val label: com.google.devtools.build.lib.cmdline.Label? =
                context.deserializeLeaf<com.google.devtools.build.lib.cmdline.Label?>(
                    codedIn,
                    com.google.devtools.build.lib.cmdline.Label.labelCodec()
                )
            return DormantDependency(label)
        }
    }

    val label: com.google.devtools.build.lib.cmdline.Label?

    init {
        this.label = label
    }

    companion object {
        const val NAME: String = "dormant_dependency"
        const val ALLOWLIST_ATTRIBUTE_NAME: String = "\$allowlist_dormant_dependency"
        const val ALLOWLIST_LABEL_STR: String = "//tools/allowlists/dormant_dependency_allowlist"
        val ALLOWLIST_LABEL: com.google.devtools.build.lib.cmdline.Label? =
            com.google.devtools.build.lib.cmdline.Label.parseCanonicalUnchecked(
                ALLOWLIST_LABEL_STR
            )
    }
}
