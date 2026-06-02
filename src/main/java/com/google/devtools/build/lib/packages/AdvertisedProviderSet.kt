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

import com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable

/**
 * Captures the set of providers rules and aspects can advertise. It is either of:
 * 
 * 
 *  * a set of builtin and Starlark providers
 *  * "can have any provider" set that alias rules have.
 * 
 * 
 * 
 * Built-in providers should in theory only contain subclasses of [ ], but our current dependency
 * structure does not allow a reference to that class here.
 */
@Immutable
class AdvertisedProviderSet private constructor(
    private val canHaveAnyProvider: Boolean,
    builtinProviders: com.google.common.collect.ImmutableSet<java.lang.Class<*>?>,
    starlarkProviders: com.google.common.collect.ImmutableSet<StarlarkProviderIdentifier?>
) {
    private val builtinProviders: com.google.common.collect.ImmutableSet<java.lang.Class<*>?>
    private val starlarkProviders: com.google.common.collect.ImmutableSet<StarlarkProviderIdentifier?>

    init {
        this.builtinProviders = builtinProviders
        this.starlarkProviders = starlarkProviders
    }

    override fun hashCode(): Int {
        return java.util.Objects.hash(canHaveAnyProvider, builtinProviders, starlarkProviders)
    }

    override fun equals(obj: Any?): Boolean {
        if (this === obj) {
            return true
        }

        if (obj !is AdvertisedProviderSet) {
            return false
        }

        val that = obj
        return this.canHaveAnyProvider == that.canHaveAnyProvider && this.builtinProviders == that.builtinProviders
                && this.starlarkProviders == that.starlarkProviders
    }

    override fun toString(): String {
        if (canHaveAnyProvider()) {
            return "Any Provider"
        }
        return java.lang.String.format(
            "allowed built-in providers=%s, allowed Starlark providers=%s",
            builtinProviders, starlarkProviders
        )
    }

    /** Checks whether the rule can have any provider.
     * 
     * Used for alias rules.
     */
    fun canHaveAnyProvider(): Boolean {
        return canHaveAnyProvider
    }

    /** Get all advertised built-in providers.  */
    fun getBuiltinProviders(): com.google.common.collect.ImmutableSet<java.lang.Class<*>?> {
        return builtinProviders
    }

    /** Get all advertised Starlark providers.  */
    fun getStarlarkProviders(): com.google.common.collect.ImmutableSet<StarlarkProviderIdentifier?> {
        return starlarkProviders
    }

    /**
     * Adds the fingerprints of this [AdvertisedProviderSet] into `fp`.
     * 
     * 
     * Fingerprints of [AdvertisedProviderSet] must have the following properties:
     * 
     * 
     *  * If `aps1.equals(aps2)` then `aps1` and `aps2` have the same
     * fingerprint.
     *  * If `!aps1.equals(aps2)` then `aps1` and `aps2` don't have the same
     * fingerprint (except for unintentional digest collisions).
     * 
     * 
     * 
     * In other words, this method is a proxy for [.equals]. These properties *do not* need
     * to be maintained across Blaze versions (e.g. there's no need to worry about historical
     * serialized fingerprints).
     */
    fun fingerprint(fp: Fingerprint) {
        fp.addBoolean(canHaveAnyProvider)
        // #builtinProviders and #starlarkProviders are ordered according to the calls to the builder
        // methods, and that order is assumed to be deterministic.
        builtinProviders.forEach(java.util.function.Consumer { clazz: java.lang.Class<*>? -> fp.addString(clazz.getCanonicalName()) })
        starlarkProviders.forEach(java.util.function.Consumer { starlarkProvider: StarlarkProviderIdentifier? ->
            starlarkProvider.fingerprint(
                fp
            )
        })
    }

    /**
     * Returns `true` if this provider set can have any provider, or if it advertises the
     * specific Starlark provider requested.
     */
    fun advertises(starlarkProvider: StarlarkProviderIdentifier?): Boolean {
        if (canHaveAnyProvider()) {
            return true
        }
        return starlarkProviders.contains(starlarkProvider)
    }

    /** Builder for [AdvertisedProviderSet]  */
    class Builder private constructor() {
        private var canHaveAnyProvider = false
        private val builtinProviders: java.util.ArrayList<java.lang.Class<*>?>
        private val starlarkProviders: java.util.ArrayList<StarlarkProviderIdentifier?>

        init {
            builtinProviders = java.util.ArrayList<java.lang.Class<*>?>()
            starlarkProviders = java.util.ArrayList<StarlarkProviderIdentifier?>()
        }

        /** Advertise all providers inherited from a parent rule.  */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun addParent(parentSet: AdvertisedProviderSet): Builder {
            com.google.common.base.Preconditions.checkState(
                !canHaveAnyProvider,
                "Alias rules inherit from no other rules"
            )
            com.google.common.base.Preconditions.checkState(
                !parentSet.canHaveAnyProvider(),
                "Cannot inherit from alias rules"
            )
            builtinProviders.addAll(parentSet.getBuiltinProviders())
            starlarkProviders.addAll(parentSet.getStarlarkProviders())
            return this
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun addBuiltin(builtinProvider: java.lang.Class<*>?): Builder {
            this.builtinProviders.add(builtinProvider)
            return this
        }

        fun canHaveAnyProvider() {
            com.google.common.base.Preconditions.checkState(builtinProviders.isEmpty() && starlarkProviders.isEmpty())
            this.canHaveAnyProvider = true
        }

        fun build(): AdvertisedProviderSet? {
            if (canHaveAnyProvider) {
                com.google.common.base.Preconditions.checkState(builtinProviders.isEmpty() && starlarkProviders.isEmpty())
                return ANY
            }
            return create(
                com.google.common.collect.ImmutableSet.copyOf<java.lang.Class<*>?>(builtinProviders),
                com.google.common.collect.ImmutableSet.copyOf<StarlarkProviderIdentifier?>(starlarkProviders)
            )
        }

        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun addStarlark(id: StarlarkProviderIdentifier?): Builder {
            starlarkProviders.add(id)
            return this
        }
    }

    companion object {
        @kotlin.jvm.JvmField
        @SerializationConstant
        val ANY: AdvertisedProviderSet = AdvertisedProviderSet(
            true,
            com.google.common.collect.ImmutableSet.of<java.lang.Class<*>?>(),
            com.google.common.collect.ImmutableSet.of<StarlarkProviderIdentifier?>()
        )

        @kotlin.jvm.JvmField
        @SerializationConstant
        val EMPTY: AdvertisedProviderSet = AdvertisedProviderSet(
            false,
            com.google.common.collect.ImmutableSet.of<java.lang.Class<*>?>(),
            com.google.common.collect.ImmutableSet.of<StarlarkProviderIdentifier?>()
        )

        fun create(
            builtinProviders: com.google.common.collect.ImmutableSet<java.lang.Class<*>?>,
            starlarkProviders: com.google.common.collect.ImmutableSet<StarlarkProviderIdentifier?>
        ): AdvertisedProviderSet? {
            if (builtinProviders.isEmpty() && starlarkProviders.isEmpty()) {
                return EMPTY
            }
            return AdvertisedProviderSet(false, builtinProviders, starlarkProviders)
        }

        @kotlin.jvm.JvmStatic
        fun builder(): Builder {
            return com.google.devtools.build.lib.packages.AdvertisedProviderSet.Builder()
        }
    }
}
