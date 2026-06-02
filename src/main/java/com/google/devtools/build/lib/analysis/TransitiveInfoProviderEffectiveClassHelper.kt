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

import com.github.benmanes.caffeine.cache.Caffeine

/**
 * Provides the effective class for the provider. The effective class is inferred as the sole class
 * in the provider's inheritance hierarchy that implements [TransitiveInfoProvider] directly.
 * This allows for simple subclasses such as those created by AutoValue, but will fail if there's
 * any ambiguity as to which implementor of the [TransitiveInfoProvider] is intended. If the
 * provider implements multiple TransitiveInfoProvider interfaces, prefer the explicit put builder
 * methods.
 */
internal object TransitiveInfoProviderEffectiveClassHelper {
    private val effectiveProviderClassCache: com.github.benmanes.caffeine.cache.LoadingCache<java.lang.Class<out com.google.devtools.build.lib.analysis.TransitiveInfoProvider?>?, java.lang.Class<out com.google.devtools.build.lib.analysis.TransitiveInfoProvider?>?> =
        Caffeine.newBuilder()
            .build<java.lang.Class<out com.google.devtools.build.lib.analysis.TransitiveInfoProvider?>?, java.lang.Class<out com.google.devtools.build.lib.analysis.TransitiveInfoProvider?>?>(
                com.github.benmanes.caffeine.cache.CacheLoader { obj: java.lang.Class<out com.google.devtools.build.lib.analysis.TransitiveInfoProvider?>? -> TransitiveInfoProviderEffectiveClassHelper.findEffectiveProviderClass() })

    private fun findEffectiveProviderClass(
        providerClass: java.lang.Class<out com.google.devtools.build.lib.analysis.TransitiveInfoProvider?>
    ): java.lang.Class<out com.google.devtools.build.lib.analysis.TransitiveInfoProvider?>? {
        val result: MutableSet<java.lang.Class<out com.google.devtools.build.lib.analysis.TransitiveInfoProvider?>?> =
            getDirectImplementations(providerClass)
        com.google.common.base.Preconditions.checkState(
            result.size == 1,
            "Effective provider class for %s is ambiguous (%s), specify explicitly.",
            providerClass,
            result
        )
        return result.iterator().next()
    }

    private fun getDirectImplementations(
        providerClass: java.lang.Class<out com.google.devtools.build.lib.analysis.TransitiveInfoProvider?>
    ): MutableSet<java.lang.Class<out com.google.devtools.build.lib.analysis.TransitiveInfoProvider?>?> {
        val result: MutableSet<java.lang.Class<out com.google.devtools.build.lib.analysis.TransitiveInfoProvider?>?> =
            com.google.common.collect.Sets.newLinkedHashSetWithExpectedSize<java.lang.Class<out com.google.devtools.build.lib.analysis.TransitiveInfoProvider?>?>(
                1
            )
        for (clazz in providerClass.getInterfaces()) {
            if (com.google.devtools.build.lib.analysis.TransitiveInfoProvider::class.java == clazz) {
                result.add(providerClass)
            } else if (com.google.devtools.build.lib.analysis.TransitiveInfoProvider::class.java.isAssignableFrom(clazz)) {
                result.addAll(
                    getDirectImplementations(
                        clazz.asSubclass<com.google.devtools.build.lib.analysis.TransitiveInfoProvider?>(
                            com.google.devtools.build.lib.analysis.TransitiveInfoProvider::class.java
                        )
                    )
                )
            }
        }

        val superclass: java.lang.Class<*>? = providerClass.getSuperclass()
        if (superclass != null && com.google.devtools.build.lib.analysis.TransitiveInfoProvider::class.java.isAssignableFrom(
                superclass
            )
        ) {
            result.addAll(
                getDirectImplementations(
                    superclass.asSubclass<com.google.devtools.build.lib.analysis.TransitiveInfoProvider?>(
                        com.google.devtools.build.lib.analysis.TransitiveInfoProvider::class.java
                    )
                )
            )
        }
        return result
    }

    fun <T : com.google.devtools.build.lib.analysis.TransitiveInfoProvider?> get(provider: T?): java.lang.Class<T?>? {
        return TransitiveInfoProviderEffectiveClassHelper.get<T?>(provider.javaClass as java.lang.Class<T?>)
    }

    fun <T : com.google.devtools.build.lib.analysis.TransitiveInfoProvider?> get(providerClass: java.lang.Class<T?>?): java.lang.Class<T?>? {
        return effectiveProviderClassCache.get(providerClass) as java.lang.Class<T?>?
    }
}
