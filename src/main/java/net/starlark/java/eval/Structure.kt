// Copyright 2014 The Bazel Authors. All rights reserved.
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
package net.starlark.java.eval

/**
 * An interface for Starlark values (such as Bazel structs) with fields that may be accessed using
 * Starlark's `x.field` notation and optionally updating using an `x.f=y` assignment.
 */
interface Structure : net.starlark.java.eval.StarlarkValue {
    /**
     * Returns the value of the field with the given name, or null if the field does not exist. The
     * interpreter (Starlark code) calls the getValue below, which has access to StarlarkSemantics.
     * 
     * 
     * The set of names for which `getValue` returns non-null should match `getFieldNames` if possible.
     * 
     * @throws EvalException if a user-visible error occurs (other than non-existent field).
     */
    // TODO(adonovan): rename "getField".
    @Throws(net.starlark.java.eval.EvalException::class)
    fun getValue(name: String?): Any?

    /**
     * Returns the value of the field with the given name, or null if the field does not exist. The
     * interpreter (Starlark code) calls this getValue, but client code cannot be relied upon to do
     * so, so any checks done on the semantics are incompletely enforced.
     * 
     * @param semantics the Starlark semantics, which determine the available fields
     * @param name the name of the field to retrieve
     * @throws EvalException if the field exists but could not be retrieved
     */
    @Throws(net.starlark.java.eval.EvalException::class)
    fun getValue(semantics: net.starlark.java.eval.StarlarkSemantics?, name: String?): Any? {
        return this.getValue(name)
    }

    /**
     * Returns the names of this value's fields, in some undefined but stable order.
     * 
     * 
     * A call to `getValue` for each of these names should return non-null, though this is
     * not enforced.
     * 
     * 
     * The Starlark expression `dir(x)` reports the union of `getFieldNames()` and any
     * StarlarkMethod-annotated fields and methods of this value.
     */
    fun getFieldNames(): com.google.common.collect.ImmutableCollection<String?>?

    /**
     * Returns the error message to print for an attempt to access an undefined field.
     * 
     * 
     * May return null to use a default error message.
     */
    fun getErrorMessageForUnknownField(field: String?): String?

    /**
     * Updates the named field of this value as if by the Starlark statement `this.field = value`.
     * 
     * @throws EvalException if the update failed because this value is immutable, does not support
     * field update, or update of that particular field, or because the value was inappropriate.
     */
    @Throws(net.starlark.java.eval.EvalException::class)
    fun setField(field: String?, value: Any?) {
        throw net.starlark.java.eval.Starlark.Companion.errorf(
            "%s value does not support field assignment",
            net.starlark.java.eval.Starlark.Companion.type(this)
        )
    }

    /**
     * Returns the Starlark type of this struct. For efficiency, implementations should override this
     * method to return a memoized value.
     */
    override fun getStarlarkType(semantics: net.starlark.java.eval.StarlarkSemantics?): net.starlark.java.syntax.Types.StructType? {
        val fieldTypes: com.google.common.collect.ImmutableMap.Builder<String?, net.starlark.java.syntax.StarlarkType?> =
            com.google.common.collect.ImmutableMap.builderWithExpectedSize<String?, net.starlark.java.syntax.StarlarkType?>(
                getFieldNames().size()
            )
        for (fieldName in getFieldNames()) {
            try {
                fieldTypes.put(
                    fieldName,
                    net.starlark.java.eval.Starlark.Companion.getStarlarkType(getValue(fieldName), semantics)
                )
            } catch (e: net.starlark.java.eval.EvalException) {
                // Ignore; if retrieving some internal-only field is an evaluation error, then retrieving it
                // should be a type checking error too.
            }
        }
        return net.starlark.java.syntax.Types.struct(fieldTypes.buildOrThrow())
    }
}
