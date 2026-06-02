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
package net.starlark.java.lib

import com.google.devtools.build.lib.supplier.InterruptibleSupplier.get

/** A simple [Structure] implementation that wraps a map of fields.  */
class MapWrapperStructure(fields: MutableMap<String?, Any?>) : net.starlark.java.eval.Structure {
    protected val fields: com.google.common.collect.ImmutableMap<String?, Any?>

    init {
        this.fields = com.google.common.collect.ImmutableMap.copyOf<String?, Any?>(fields)
    }

    override fun getValue(name: String?): Any? {
        return fields.get(name)
    }

    override fun getFieldNames(): com.google.common.collect.ImmutableSet<String?>? {
        return fields.keySet()
    }

    override fun getErrorMessageForUnknownField(field: String?): String? {
        return null
    }

    override fun repr(printer: net.starlark.java.eval.Printer, semantics: net.starlark.java.eval.StarlarkSemantics?) {
        var first = true
        printer.append("struct(")
        for (field in fields.entrySet()) {
            if (!first) {
                printer.append(", ")
            }
            first = false
            printer.append(field.getKey())
            printer.append(" = ")
            printer.repr(field.getValue(), semantics)
        }
        printer.append(")")
    }
}
