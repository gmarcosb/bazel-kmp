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
package com.google.devtools.build.lib.analysis

import com.google.devtools.build.lib.packages.RequiredProviders
import com.google.devtools.build.lib.packages.StarlarkProviderIdentifier
import com.google.devtools.build.lib.starlarkbuildapi.core.TransitiveInfoCollectionApi

/**
 * Multiple [TransitiveInfoProvider]s bundled together.
 * 
 * 
 * Represents the information made available by a [ConfiguredTarget] to other ones that
 * depend on it. For more information about the analysis phase, see [ ].
 * 
 * 
 * Implementations of build rules should **not** hold on to references to the [ ]s representing their direct prerequisites in order to reduce their
 * memory footprint (otherwise, the referenced object could refer one of its direct dependencies in
 * turn, thereby making the size of the objects reachable from a single instance unbounded).
 * 
 * @see com.google.devtools.build.lib.analysis.RuleConfiguredTargetFactory
 * 
 * @see TransitiveInfoProvider
 */
interface TransitiveInfoCollection

    : net.starlark.java.eval.StarlarkIndexable, ProviderCollection, TransitiveInfoCollectionApi {
    /**
     * Returns the label associated with this prerequisite.
     */
    @kotlin.jvm.JvmField
    val label: com.google.devtools.build.lib.cmdline.Label?

    /**
     * Checks whether this [TransitiveInfoCollection] satisfies given [RequiredProviders].
     */
    fun satisfies(providers: RequiredProviders): Boolean {
        return providers.isSatisfiedBy(
            java.util.function.Predicate { aClass: java.lang.Class<out com.google.devtools.build.lib.analysis.TransitiveInfoProvider?>? ->
                getProvider(
                    aClass
                ) != null
            }, java.util.function.Predicate { id: StarlarkProviderIdentifier? -> this.get(id) != null })
    }

    /**
     * Returns providers that this [TransitiveInfoCollection] misses from a given [ ].
     * 
     * 
     * If none are missing, returns [RequiredProviders] that accept any set of providers.
     */
    fun missingProviders(providers: RequiredProviders): RequiredProviders? {
        return providers.getMissing(
            java.util.function.Predicate { aClass: java.lang.Class<out com.google.devtools.build.lib.analysis.TransitiveInfoProvider?>? ->
                getProvider(
                    aClass.asSubclass<U?>(com.google.devtools.build.lib.analysis.TransitiveInfoProvider::class.java)
                ) != null
            },
            java.util.function.Predicate { id: StarlarkProviderIdentifier? -> this.get(id) != null })
    }
}
