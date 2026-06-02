// Copyright 2017 The Bazel Authors. All rights reserved.
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


import com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable

/**
 * Abstract base class for implementations of [Info] that expose StarlarkCallable-annotated
 * fields (not just methods) to Starlark code. Subclasses must be immutable.
 */
// TODO(adonovan): ensure that all subclasses are named *Info and not *Provider.
// (Info is to object as Provider is to class.)
@Immutable
abstract class NativeInfo : com.google.devtools.build.lib.packages.Info, net.starlark.java.lib.StarlarkEncodable {
    override fun isImmutable(): Boolean {
        return true // immutable and Starlark-hashable
    }

    // TODO(b/408391489) repr, hash, equals for native providers are inefficient; implement them
    //  directly and remove getLegacyStarlarkMethodNames getLegacyFields
    private fun getLegacyStarlarkMethodNames(): MutableList<String?> {
        return com.google.common.collect.Ordering.natural<Comparable<*>?>()
            .sortedCopy<String?>(
                net.starlark.java.eval.Starlark.dir(
                    net.starlark.java.eval.Mutability.IMMUTABLE,
                    net.starlark.java.eval.StarlarkSemantics.DEFAULT,
                    this
                )
            )
    }

    private fun getLegacyFields(): com.google.common.collect.ImmutableMap<String?, Any?> {
        val fields: com.google.common.collect.ImmutableMap.Builder<String?, Any?> =
            com.google.common.collect.ImmutableMap.builder<String?, Any?>()
        for (fieldName in getLegacyStarlarkMethodNames()) {
            try {
                val value: Any? =
                    net.starlark.java.eval.Starlark.getattr(
                        net.starlark.java.eval.Mutability.IMMUTABLE,
                        net.starlark.java.eval.StarlarkSemantics.DEFAULT,
                        this,
                        fieldName,
                        null
                    )
                if (value is net.starlark.java.eval.BuiltinFunction) {
                    continue
                }
                fields.put(fieldName, value)
            } catch (e: net.starlark.java.eval.EvalException) {
                fields.put(fieldName, net.starlark.java.eval.Starlark.NONE)
            } catch (e: java.lang.InterruptedException) {
                // Struct fields on NativeInfo objects are supposed to behave well and not throw
                // exceptions, as they should be logicless field accessors. If this occurs, it's
                // indicative of a bad NativeInfo implementation.
                throw java.lang.IllegalStateException(
                    java.lang.String.format(
                        "Access of field %s was unexpectedly interrupted, but should be "
                                + "uninterruptible. This is indicative of a bad provider implementation.",
                        fieldName
                    ),
                    e
                )
            }
        }
        return fields.buildOrThrow()
    }

    override fun equals(otherObject: Any?): Boolean {
        if (otherObject !is NativeInfo) {
            return false
        }
        if (this === otherObject) {
            return true
        }
        if (this.getProvider() != otherObject.getProvider()) {
            return false
        }
        // Compare objects' fields and their values
        if (!com.google.common.base.Objects.equal(
                getLegacyStarlarkMethodNames(),
                otherObject.getLegacyStarlarkMethodNames()
            )
        ) {
            return false
        }
        return com.google.common.base.Objects.equal(getLegacyFields(), otherObject.getLegacyFields())
    }

    override fun hashCode(): Int {
        return com.google.common.base.Objects.hashCode(getProvider(), getLegacyFields())
    }

    /**
     * Convert the object to string using Starlark syntax. The output tries to be reversible (but
     * there is no guarantee, it depends on the actual values).
     */
    override fun repr(printer: net.starlark.java.eval.Printer, semantics: net.starlark.java.eval.StarlarkSemantics?) {
        net.starlark.java.lib.MapWrapperStructure(getLegacyFields()).repr(printer, semantics)
    }

    override fun objectForEncoding(semantics: net.starlark.java.eval.StarlarkSemantics?): net.starlark.java.eval.Structure {
        return net.starlark.java.lib.MapWrapperStructure(getLegacyFields())
    }

    override fun toString(): String {
        return net.starlark.java.eval.Starlark.repr(this, net.starlark.java.eval.StarlarkSemantics.DEFAULT)
    }
}
