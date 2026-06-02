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
package com.google.devtools.build.lib.analysis

import com.google.devtools.build.lib.packages.BuiltinProvider
import com.google.devtools.build.lib.packages.RuleClass.ConfiguredTargetFactory.RuleErrorException
import com.google.devtools.build.lib.packages.StarlarkProviderIdentifier
import com.google.devtools.build.lib.packages.StarlarkProviderWrapper

/**
 * Interface to mark classes that could contain transitive information added using the Starlark
 * framework.
 */
interface ProviderCollection {
    /**
     * Returns the transitive information provider requested, or null if the provider is not found.
     * The provider has to be a TransitiveInfoProvider Java class.
     */
    fun <P : com.google.devtools.build.lib.analysis.TransitiveInfoProvider?> getProvider(provider: java.lang.Class<P?>?): P?

    /**
     * Returns the transitive information requested or null, if the information is not found. The
     * transitive information has to have been added using the Starlark framework.
     */
    fun get(providerKey: String?): Any?

    /**
     * Returns the declared provider requested, or null, if the information is not found.
     * 
     * 
     * Use [.get] for built-in providers.
     */
    fun get(providerKey: com.google.devtools.build.lib.packages.Provider.Key?): com.google.devtools.build.lib.packages.Info?

    /**
     * Returns the native declared provider requested, or null, if the information is not found.
     * 
     * 
     * Type-safe version of [.get] for built-in providers.
     */
    fun <T : com.google.devtools.build.lib.packages.Info?> get(provider: BuiltinProvider<T?>): T? {
        return provider.getValueClass().cast(get(provider.getKey()))
    }

    /**
     * Retrieves and converts an instance of a Starlark-defined provider to an instance of `T`,
     * according to the conversion defined by `wrapper`.
     * 
     * 
     * If the provider identified by `wrapper` is not present, returns null.
     * 
     * 
     * Conversion errors (e.g. missing fields or bad types) are indicated by throwing [ ].
     */
    @Throws(RuleErrorException::class)
    fun <T> get(wrapper: StarlarkProviderWrapper<T?>): T? {
        val value: com.google.devtools.build.lib.packages.Info? = get(wrapper.getKey())
        return if (value == null) null else wrapper.wrap(value)
    }

    /**
     * Returns the provider defined in Starlark, or null, if the information is not found. The
     * transitive information has to have been added using the Starlark framework.
     * 
     * 
     * This method dispatches to either [.get] or [.get]
     * depending on whether [StarlarkProviderIdentifier] is for legacy or for declared provider.
     */
    fun get(id: StarlarkProviderIdentifier): Any? {
        return this.get(id.getKey())
    }
}
