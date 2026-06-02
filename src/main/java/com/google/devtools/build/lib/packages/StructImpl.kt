// Copyright 2016 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.starlarkbuildapi.core.StructApi
import java.util.Collections

/**
 * An abstract base class for Starlark values that have fields, have an associated provider (type
 * symbol), and may be returned as the result of analysis from one target to another.
 * 
 * 
 * StructImpl does not specify how the fields are represented; subclasses must define `getValue` and `getFieldNames`. For example, `NativeInfo` supplies fields from the
 * subclass's `StarlarkMethod(structField=true)` annotations, and `StarlarkInfo`
 * supplies fields from the map provided at its construction.
 * 
 * 
 * Two StructImpls are equivalent if they have the same provider and, for each field name
 * reported by `getFieldNames` their corresponding field values are equivalent, or accessing
 * them both returns an error.
 */
abstract class StructImpl : com.google.devtools.build.lib.packages.Info, net.starlark.java.eval.Structure, StructApi {
    /**
     * Returns the result of [.getValue], cast as the given type, throwing [ ] if the cast fails.
     */
    @Throws(net.starlark.java.eval.EvalException::class)
    fun <T> getValue(key: String?, type: java.lang.Class<T?>): T? {
        val obj: Any? = getValue(key)
        if (obj == null) {
            return null
        }
        try {
            return type.cast(obj)
        } catch (unused: java.lang.ClassCastException) {
            throw net.starlark.java.eval.Starlark.errorf(
                "for %s field, got %s, want %s",
                key,
                net.starlark.java.eval.Starlark.type(obj),
                net.starlark.java.eval.Starlark.classType(type)
            )
        }
    }

    /**
     * Returns the result of [.getValue], cast as the given type, throwing [ ] if the cast fails. If the value is [Starlark.NONE], returns null.
     */
    @Throws(net.starlark.java.eval.EvalException::class)
    fun <T> getNoneableValue(key: String?, type: java.lang.Class<T?>): T? {
        val obj: Any? = getValue(key)
        if (obj == null || obj === net.starlark.java.eval.Starlark.NONE) {
            return null
        }
        try {
            return type.cast(obj)
        } catch (unused: java.lang.ClassCastException) {
            throw net.starlark.java.eval.Starlark.errorf(
                "for %s field, got %s, want %s",
                key,
                net.starlark.java.eval.Starlark.type(obj),
                net.starlark.java.eval.Starlark.classType(type)
            )
        }
    }

    /**
     * Returns the error message format to use for unknown fields.
     * 
     * 
     * By default, it is the one specified by the provider.
     */
    override fun getErrorMessageForUnknownField(name: String?): String? {
        return getProvider().getErrorMessageForUnknownField(name) + allAttributesSuffix()
    }

    fun allAttributesSuffix(): String {
        // TODO(adonovan): when is it appropriate for the error to show all attributes,
        // and when to show a single spelling suggestion (the default)?
        return ("\nAvailable attributes: "
                + com.google.common.base.Joiner.on(", ")
            .join(com.google.common.collect.Ordering.natural<Comparable<*>?>().sortedCopy<String?>(getFieldNames())))
    }

    override fun equals(otherObject: Any?): Boolean {
        if (otherObject !is StructImpl) {
            return false
        }
        val other = otherObject
        if (this === other) {
            return true
        }
        if (this.getProvider() != other.getProvider()) {
            return false
        }
        // Compare objects' fields and their values
        if (this.getFieldNames() != other.getFieldNames()) {
            return false
        }
        for (field in getFieldNames()) {
            if (!com.google.common.base.Objects.equal(this.getValueOrNull(field), other.getValueOrNull(field))) {
                return false
            }
        }
        return true
    }

    override fun hashCode(): Int {
        val fields: MutableList<String?> = java.util.ArrayList<String?>(getFieldNames())
        Collections.sort<String?>(fields)
        val objectsToHash: MutableList<Any?> = java.util.ArrayList<Any?>()
        objectsToHash.add(getProvider())
        for (field in fields) {
            objectsToHash.add(field)
            objectsToHash.add(getValueOrNull(field))
        }
        return com.google.common.base.Objects.hashCode(*objectsToHash.toArray())
    }

    /**
     * Convert the object to string using Starlark syntax. The output tries to be reversible (but
     * there is no guarantee, it depends on the actual values).
     */
    override fun repr(printer: net.starlark.java.eval.Printer, semantics: net.starlark.java.eval.StarlarkSemantics?) {
        var first = true
        printer.append("struct(")
        // Sort by key to ensure deterministic output.
        for (fieldName in com.google.common.collect.Ordering.natural<Comparable<*>?>()
            .sortedCopy<String?>(getFieldNames())) {
            if (!first) {
                printer.append(", ")
            }
            first = false
            printer.append(fieldName)
            printer.append(" = ")
            printer.repr(getValueOrNull(fieldName), semantics)
        }
        printer.append(")")
    }

    private fun getValueOrNull(name: String?): Any? {
        try {
            return getValue(name)
        } catch (e: net.starlark.java.eval.EvalException) {
            return null
        }
    }

    override fun toString(): String {
        return net.starlark.java.eval.Starlark.repr(this, net.starlark.java.eval.StarlarkSemantics.DEFAULT)
    }
}
