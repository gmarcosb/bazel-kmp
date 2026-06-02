// Copyright 2023 The Bazel Authors. All rights reserved.
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
 * A helper for wrapping an instance of a Starlark-defined provider with a native class `T`.
 * 
 * 
 * This is useful for allowing native rules to interoperate with Starlark-defined providers
 * (including providers defined in `@_builtins`} while retaining the friendlier API of a Java
 * class.
 * 
 * 
 * To use, create a subclass that overrides [.wrap], and pass a singleton instance of that
 * subclass to [ ][com.google.devtools.build.lib.analysis.ProviderCollection.get].
 * 
 * 
 * `T` is not typically itself a provider. There is no mechanism for converting `T`
 * back into a Starlark provider instance; instead, the caller should construct that instance
 * manually.
 */
@Immutable
abstract class StarlarkProviderWrapper<T> protected constructor(loadKey: BzlLoadValue.Key?, name: String?) {
    private val key: com.google.devtools.build.lib.packages.StarlarkProvider.Key

    init {
        this.key = com.google.devtools.build.lib.packages.StarlarkProvider.Key(loadKey, name)
    }

    /**
     * Converts an instance of the Starlark-defined provider to an instance of the wrapping class
     * `T`.
     * 
     * 
     * `value` may be assumed to be an instance of the provider identified by [ ][.getKey].
     * 
     * 
     * Any schema errors (missing or mistyped fields) should be reported by throwing [ ]
     */
    @Throws(RuleErrorException::class)
    abstract fun wrap(value: com.google.devtools.build.lib.packages.Info?): T?

    fun getKey(): com.google.devtools.build.lib.packages.StarlarkProvider.Key {
        return key
    }

    /** Returns the identifier of this provider.  */
    fun id(): StarlarkProviderIdentifier? {
        return StarlarkProviderIdentifier.Companion.forKey(key)
    }
}
