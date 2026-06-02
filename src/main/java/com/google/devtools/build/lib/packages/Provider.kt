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
 * Declared Provider (a constructor for [Info]).
 * 
 * 
 * Declared providers can be declared either natively ([BuiltinProvider] or in Starlark
 * [StarlarkProvider].
 * 
 * 
 * [Provider] serves both as "type identifier" for declared provider instances and as a
 * function that can be called to construct a provider. To the Starlark user, there are "providers"
 * and "provider instances"; the former is a Java instance of this class, and the latter is a Java
 * instance of [Info].
 * 
 * 
 * Prefer to use [Key] as a serializable identifier of [Provider]. In particular,
 * [Key] should be used in all data structures exposed to Skyframe.
 */
@Immutable
interface Provider : ProviderApi {
    override fun hasInstance(value: Any?): Boolean {
        if (value is com.google.devtools.build.lib.packages.Info) {
            return value.getProvider() == this
        }
        return false
    }

    /**
     * Has this [Provider] been exported? All built-in providers are always exported. Starlark
     * providers are exported if they are assigned to top-level name in a Starlark module.
     */
    fun isExported(): Boolean

    /** Returns a serializable representation of this [Provider].  */
    fun getKey(): Key?

    /** Returns a name of this [Provider] that should be used in error messages.  */
    fun getPrintableName(): String?

    /**
     * Returns an error message for instances to use for their [ ][net.starlark.java.eval.Structure.getErrorMessageForUnknownField].
     */
    fun getErrorMessageForUnknownField(name: String?): String? {
        return java.lang.String.format("'%s' value has no field or method '%s'", getPrintableName(), name)
    }

    /**
     * Returns the location at which provider was defined.
     */
    fun getLocation(): net.starlark.java.syntax.Location?

    /** A serializable and fingerprintable representation of [Provider].  */
    class Key {
        abstract fun fingerprint(fp: Fingerprint?)
    }
}
