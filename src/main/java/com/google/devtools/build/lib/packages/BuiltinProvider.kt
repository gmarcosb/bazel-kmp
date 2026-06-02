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

import com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable

/**
 * Base class for declared providers {@see Provider} built into Blaze.
 * 
 * 
 * Every subclass of [BuiltinProvider] should have exactly one instance. If multiple
 * instances of the same subclass are instantiated, they are considered equivalent. This design is
 * motivated by the need for serialization. Starlark providers are readily identified by the pair
 * (.bzl file name, sequence number during execution). BuiltinProviders need an analogous
 * serializable identifier, yet JVM classes (notoriously) don't have a predictable initialization
 * order, so we can't use a sequence number. A distinct subclass for each built-in provider acts as
 * that identifier.
 * 
 * 
 * Implementations of native declared providers should subclass this class, and define a method
 * in the subclass definition to create instances of its corresponding Info object. The method
 * should be annotated with [StarlarkMethod] with [StarlarkMethod.selfCall] set to true,
 * and with [StarlarkConstructor] for the info type it constructs.
 */
@Immutable
abstract class BuiltinProvider<T : com.google.devtools.build.lib.packages.Info?> protected constructor(
    name: String?,
    valueClass: java.lang.Class<T?>?
) : com.google.devtools.build.lib.packages.Provider {
    @kotlin.jvm.JvmField
    private val key: Key
    private val name: String?
    private val valueClass: java.lang.Class<T?>?

    init {
        this.key = com.google.devtools.build.lib.packages.BuiltinProvider.Key(name, getClass())
        this.name = name
        this.valueClass = valueClass
    }

    fun getValueClass(): java.lang.Class<T?>? {
        return valueClass
    }

    /**
     * Defines the equivalence relation: all BuiltinProviders of the same Java class are equal,
     * regardless of `name` or `valueClass`.
     */
    override fun equals(other: Any?): Boolean {
        return other != null && this.getClass() == other.getClass()
    }

    override fun hashCode(): Int {
        return getClass().hashCode()
    }

    override fun checkHashable() {
        // The hash code is based on the class, so it is hashable.
    }

    override fun isExported(): Boolean {
        return true
    }

    override fun getKey(): Key {
        return key
    }

    override fun getLocation(): net.starlark.java.syntax.Location {
        return net.starlark.java.syntax.Location.BUILTIN
    }

    override fun getPrintableName(): String? {
        return name
    }

    override fun repr(printer: net.starlark.java.eval.Printer, semantics: net.starlark.java.eval.StarlarkSemantics?) {
        // TODO(adonovan): change to '<provider name>'.
        printer.append("<function " + name + ">")
    }

    /** Returns the identifier of this provider.  */
    fun id(): StarlarkProviderIdentifier? {
        return StarlarkProviderIdentifier.Companion.forKey(key)
    }

    /**
     * Implement this to mark that a built-in provider should be exported with certain name to
     * Starlark. Broken: only works for rules, not for aspects. DO NOT USE FOR NEW CODE!
     * 
     */
    @Deprecated(
        """Use declared providers mechanism exclusively to expose providers to both native and
        Starlark code."""
    )
    interface WithLegacyStarlarkName {
        fun getStarlarkName(): String?
    }

    /** A serializable reference to a [BuiltinProvider].  */
    @Immutable
    class Key(name: String?, providerClass: java.lang.Class<out com.google.devtools.build.lib.packages.Provider?>) :
        com.google.devtools.build.lib.packages.Provider.Key() {
        private val name: String?
        private val providerClass: java.lang.Class<out com.google.devtools.build.lib.packages.Provider?>

        init {
            this.name = name
            this.providerClass = providerClass
        }

        fun getName(): String? {
            return name
        }

        fun getProviderClass(): java.lang.Class<out com.google.devtools.build.lib.packages.Provider?> {
            return providerClass
        }

        override fun fingerprint(fp: Fingerprint) {
            // True => native
            fp.addBoolean(true)
            fp.addString(name)
        }

        override fun hashCode(): Int {
            return providerClass.hashCode()
        }

        override fun equals(obj: Any?): Boolean {
            return obj is Key && providerClass == obj.providerClass
        }

        override fun toString(): String {
            return name!!
        }
    }
}
