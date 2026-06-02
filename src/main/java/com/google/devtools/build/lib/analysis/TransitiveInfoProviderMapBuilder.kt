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

import com.google.devtools.build.lib.analysis.TransitiveInfoProviderEffectiveClassHelper
import com.google.devtools.build.lib.analysis.TransitiveInfoProviderMap
import com.google.devtools.build.lib.analysis.TransitiveInfoProviderMapImpl
import java.util.LinkedHashMap

/** A builder for [TransitiveInfoProviderMap].  */
class TransitiveInfoProviderMapBuilder {
    // TODO(arielb): share the instance with the outerclass and copy on write instead?
    private val providers: LinkedHashMap<Any?, Any?> = LinkedHashMap<Any?, Any?>()

    /**
     * Returns <tt>true</tt> if a [TransitiveInfoProvider] has been added for the class
     * provided.
     */
    fun contains(providerClass: java.lang.Class<out com.google.devtools.build.lib.analysis.TransitiveInfoProvider?>?): Boolean {
        return providers.containsKey(providerClass)
    }

    fun contains(legacyId: String?): Boolean {
        return providers.containsKey(legacyId)
    }

    fun contains(key: com.google.devtools.build.lib.packages.Provider.Key?): Boolean {
        return providers.containsKey(key)
    }

    @com.google.errorprone.annotations.CanIgnoreReturnValue
    fun <T : com.google.devtools.build.lib.analysis.TransitiveInfoProvider?> put(
        providerClass: java.lang.Class<out T?>?, provider: T?
    ): TransitiveInfoProviderMapBuilder {
        com.google.common.base.Preconditions.checkNotNull(providerClass)
        com.google.common.base.Preconditions.checkNotNull<T?>(provider)
        com.google.common.base.Preconditions.checkState(
            provider !is com.google.devtools.build.lib.packages.Info,
            "Expose %s as native declared provider",
            providerClass
        )

        // TODO(arielb): throw an exception if the providerClass is already present?
        // This is enforced by aspects but RuleConfiguredTarget presents violations
        // particularly around LicensesProvider
        providers.put(providerClass, provider)
        return this
    }

    @com.google.errorprone.annotations.CanIgnoreReturnValue
    fun put(classObject: com.google.devtools.build.lib.packages.Info?): TransitiveInfoProviderMapBuilder {
        com.google.common.base.Preconditions.checkNotNull<com.google.devtools.build.lib.packages.Info?>(classObject)
        com.google.common.base.Preconditions.checkState(
            classObject !is com.google.devtools.build.lib.analysis.TransitiveInfoProvider,
            "Declared provider %s should not implement TransitiveInfoProvider",
            classObject.javaClass
        )

        providers.put(classObject.getProvider().getKey(), classObject)
        return this
    }

    @com.google.errorprone.annotations.CanIgnoreReturnValue
    fun put(legacyKey: String?, classObject: Any?): TransitiveInfoProviderMapBuilder {
        com.google.common.base.Preconditions.checkNotNull<String?>(legacyKey)
        com.google.common.base.Preconditions.checkNotNull<Any?>(classObject)
        providers.put(legacyKey, classObject)
        return this
    }


    fun add(provider: com.google.devtools.build.lib.analysis.TransitiveInfoProvider): TransitiveInfoProviderMapBuilder {
        return put<com.google.devtools.build.lib.analysis.TransitiveInfoProvider?>(
            TransitiveInfoProviderEffectiveClassHelper.get<com.google.devtools.build.lib.analysis.TransitiveInfoProvider?>(
                provider
            ),
            provider
        )
    }

    @com.google.errorprone.annotations.CanIgnoreReturnValue
    fun addAll(other: TransitiveInfoProviderMap): TransitiveInfoProviderMapBuilder {
        for (i in 0..<other.getProviderCount()) {
            providers.put(other.getProviderKeyAt(i), other.getProviderInstanceAt(i))
        }
        return this
    }

    fun <P : com.google.devtools.build.lib.analysis.TransitiveInfoProvider?> getProvider(providerClass: java.lang.Class<P?>): P? {
        return providerClass.cast(providers.get(providerClass))
    }

    fun getProvider(key: com.google.devtools.build.lib.packages.Provider.Key?): com.google.devtools.build.lib.packages.Info? {
        return providers.get(key) as com.google.devtools.build.lib.packages.Info?
    }

    fun build(): TransitiveInfoProviderMap? {
        return TransitiveInfoProviderMapImpl.Companion.create(providers)
    }
}
