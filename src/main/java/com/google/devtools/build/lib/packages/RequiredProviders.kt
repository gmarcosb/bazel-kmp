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

import com.google.devtools.build.lib.analysis.TransitiveInfoProvider

/**
 * Represents a constraint on a set of providers required by a dependency (of a rule or an aspect).
 * 
 * 
 * Currently we support three kinds of constraints:
 * 
 * 
 *  * accept any dependency.
 *  * accept no dependency (used for aspects-on-aspects to indicate that an aspect never wants to
 * see any other aspect applied to a target.
 *  * accept a dependency that provides all providers from one of several sets of providers. It
 * just so happens that in all current usages these sets are either all builtin providers or
 * all Starlark providers, so this is the only use case this class currently supports.
 * 
 */
@Immutable
class RequiredProviders @com.google.devtools.build.lib.skyframe.serialization.VisibleForSerialization internal constructor(
    constraint: Constraint,
    builtinProviders: com.google.common.collect.ImmutableList<com.google.common.collect.ImmutableSet<java.lang.Class<out TransitiveInfoProvider?>?>>,
    starlarkProviders: com.google.common.collect.ImmutableList<com.google.common.collect.ImmutableSet<StarlarkProviderIdentifier?>>
) {
    /** A constraint: either ANY, NONE, or RESTRICTED  */
    private val constraint: Constraint

    /**
     * Sets of builtin providers. If non-empty, [.constraint] is [Constraint.RESTRICTED]
     */
    private val builtinProviders: com.google.common.collect.ImmutableList<com.google.common.collect.ImmutableSet<java.lang.Class<out TransitiveInfoProvider?>?>>

    /**
     * Sets of builtin providers. If non-empty, [.constraint] is [Constraint.RESTRICTED]
     */
    private val starlarkProviders: com.google.common.collect.ImmutableList<com.google.common.collect.ImmutableSet<StarlarkProviderIdentifier?>>

    fun getDescription(): String? {
        return constraint.getDescription(this)
    }

    override fun toString(): String {
        return getDescription()!!
    }

    /**
     * Returns the list of sets of acceptable Starlark providers for a restricted constraint, or an
     * empty list for an "any" or "none" constraint.
     * 
     * 
     * This method is intended for documentation generation. Do not use it for evaluating whether
     * provider constraints are satisfied: it does not distinguish between `acceptsAny` and
     * `acceptsNone`, and it does not export built-in TransitiveInfoProvider constraints.
     */
    fun getStarlarkProviders(): com.google.common.collect.ImmutableList<com.google.common.collect.ImmutableSet<StarlarkProviderIdentifier?>> {
        return starlarkProviders
    }

    /** Represents one of the constraints as desctibed in [RequiredProviders]  */
    @com.google.devtools.build.lib.skyframe.serialization.VisibleForSerialization
    internal enum class Constraint {
        /** Accept any dependency  */
        ANY {
            override fun satisfies(
                advertisedProviderSet: AdvertisedProviderSet?,
                requiredProviders: RequiredProviders?,
                missing: Builder?
            ): Boolean {
                return true
            }

            public override fun satisfies(
                hasBuiltinProvider: java.util.function.Predicate<java.lang.Class<out TransitiveInfoProvider?>?>?,
                hasStarlarkProvider: java.util.function.Predicate<StarlarkProviderIdentifier?>?,
                requiredProviders: RequiredProviders?,
                missingProviders: Builder?
            ): Boolean {
                return true
            }

            override fun copyAsBuilder(providers: RequiredProviders?): Builder {
                return acceptAnyBuilder()
            }

            public override fun getDescription(providers: RequiredProviders?): String {
                return "no providers required"
            }
        },

        /** Accept no dependency  */
        NONE {
            override fun satisfies(
                advertisedProviderSet: AdvertisedProviderSet?,
                requiredProviders: RequiredProviders?,
                missing: Builder?
            ): Boolean {
                return false
            }

            public override fun satisfies(
                hasBuiltinProvider: java.util.function.Predicate<java.lang.Class<out TransitiveInfoProvider?>?>?,
                hasStarlarkProvider: java.util.function.Predicate<StarlarkProviderIdentifier?>?,
                requiredProviders: RequiredProviders?,
                missingProviders: Builder?
            ): Boolean {
                return false
            }

            override fun copyAsBuilder(providers: RequiredProviders?): Builder {
                return acceptNoneBuilder()
            }

            public override fun getDescription(providers: RequiredProviders?): String {
                return "no providers accepted"
            }
        },

        /** Accept a dependency that has all providers from one of the sets.  */
        RESTRICTED {
            override fun satisfies(
                advertisedProviderSet: AdvertisedProviderSet,
                requiredProviders: RequiredProviders,
                missing: Builder?
            ): Boolean {
                if (advertisedProviderSet.canHaveAnyProvider()) {
                    return true
                }
                return satisfies(
                    java.util.function.Predicate { `object`: java.lang.Class<out TransitiveInfoProvider?>? ->
                        advertisedProviderSet.getBuiltinProviders().contains(`object`)
                    },
                    java.util.function.Predicate { `object`: StarlarkProviderIdentifier? ->
                        advertisedProviderSet.getStarlarkProviders().contains(`object`)
                    },
                    requiredProviders,
                    missing
                )
            }

            override fun satisfies(
                hasBuiltinProvider: java.util.function.Predicate<java.lang.Class<out TransitiveInfoProvider?>?>?,
                hasStarlarkProvider: java.util.function.Predicate<StarlarkProviderIdentifier?>?,
                requiredProviders: RequiredProviders,
                missingProviders: Builder?
            ): Boolean {
                for (builtinProviderSet in requiredProviders.builtinProviders) {
                    if (builtinProviderSet.stream().allMatch(hasBuiltinProvider)) {
                        return true
                    }

                    // Collect missing providers
                    if (missingProviders != null) {
                        missingProviders.addBuiltinSet(
                            builtinProviderSet.stream()
                                .filter(hasBuiltinProvider.negate())
                                .collect(com.google.common.collect.ImmutableSet.toImmutableSet<java.lang.Class<out TransitiveInfoProvider?>?>())
                        )
                    }
                }

                for (starlarkProviderSet in requiredProviders.starlarkProviders) {
                    if (starlarkProviderSet.stream().allMatch(hasStarlarkProvider)) {
                        return true
                    }
                    // Collect missing providers
                    if (missingProviders != null) {
                        missingProviders.addStarlarkSet(
                            starlarkProviderSet.stream()
                                .filter(hasStarlarkProvider.negate())
                                .collect(com.google.common.collect.ImmutableSet.toImmutableSet<StarlarkProviderIdentifier?>())
                        )
                    }
                }
                return false
            }

            override fun copyAsBuilder(providers: RequiredProviders): Builder {
                val result = acceptAnyBuilder()
                for (builtinProviderSet in providers.builtinProviders) {
                    result.addBuiltinSet(builtinProviderSet)
                }
                for (starlarkProviderSet in providers.starlarkProviders) {
                    result.addStarlarkSet(starlarkProviderSet)
                }
                return result
            }

            public override fun getDescription(providers: RequiredProviders): String {
                val result: java.lang.StringBuilder = java.lang.StringBuilder()
                Companion.describe<java.lang.Class<out TransitiveInfoProvider?>?>(
                    result,
                    providers.builtinProviders,
                    java.util.function.Function { obj: java.lang.Class<out TransitiveInfoProvider?>? -> obj.getSimpleName() })
                Companion.describe<StarlarkProviderIdentifier?>(
                    result,
                    providers.starlarkProviders,
                    java.util.function.Function { id: StarlarkProviderIdentifier? -> "'" + id.toString() + "'" })
                return result.toString()
            }
        };

        /** Checks if `advertisedProviderSet` satisfies these `RequiredProviders`  */
        abstract fun satisfies(
            advertisedProviderSet: AdvertisedProviderSet?,
            requiredProviders: RequiredProviders?,
            missing: Builder?
        ): Boolean

        /**
         * Checks if a set of providers encoded by predicates `hasBuiltinProvider` and `hasStarlarkProvider` satisfies these `RequiredProviders`
         */
        abstract fun satisfies(
            hasBuiltinProvider: java.util.function.Predicate<java.lang.Class<out TransitiveInfoProvider?>?>?,
            hasStarlarkProvider: java.util.function.Predicate<StarlarkProviderIdentifier?>?,
            requiredProviders: RequiredProviders?,
            missingProviders: Builder?
        ): Boolean

        abstract fun copyAsBuilder(providers: RequiredProviders?): Builder?

        /** Returns a string describing the providers that can be presented to the user.  */
        abstract fun getDescription(providers: RequiredProviders?): String?
    }

    /** Checks if `advertisedProviderSet` satisfies this `RequiredProviders` instance.  */
    fun isSatisfiedBy(advertisedProviderSet: AdvertisedProviderSet?): Boolean {
        return constraint.satisfies(advertisedProviderSet, this, null)
    }

    /**
     * Checks if a set of providers encoded by predicates `hasBuiltinProvider` and `hasStarlarkProvider` satisfies this `RequiredProviders` instance.
     */
    fun isSatisfiedBy(
        hasBuiltinProvider: java.util.function.Predicate<java.lang.Class<out TransitiveInfoProvider?>?>?,
        hasStarlarkProvider: java.util.function.Predicate<StarlarkProviderIdentifier?>?
    ): Boolean {
        return constraint.satisfies(hasBuiltinProvider, hasStarlarkProvider, this, null)
    }

    /**
     * Returns providers that are missing. If none are missing, returns `RequiredProviders` that
     * accept anything.
     */
    fun getMissing(
        hasBuiltinProvider: java.util.function.Predicate<java.lang.Class<out TransitiveInfoProvider?>?>?,
        hasStarlarkProvider: java.util.function.Predicate<StarlarkProviderIdentifier?>?
    ): RequiredProviders {
        val builder = acceptAnyBuilder()
        if (constraint.satisfies(hasBuiltinProvider, hasStarlarkProvider, this, builder)) {
            // Ignore all collected missing providers.
            return acceptAnyBuilder().build()
        }
        return builder.build()
    }

    /**
     * Returns providers that are missing. If none are missing, returns `RequiredProviders` that
     * accept anything.
     */
    fun getMissing(set: AdvertisedProviderSet?): RequiredProviders {
        val builder = acceptAnyBuilder()
        if (constraint.satisfies(set, this, builder)) {
            // Ignore all collected missing providers.
            return acceptAnyBuilder().build()
        }
        return builder.build()
    }

    /** Returns true if this `RequiredProviders` instance accepts any set of providers.  */
    fun acceptsAny(): Boolean {
        return constraint == com.google.devtools.build.lib.packages.RequiredProviders.Constraint.ANY
    }

    /** Returns true if this `RequiredProviders` instance never accepts a set of providers.  */
    fun acceptsNone(): Boolean {
        return constraint == com.google.devtools.build.lib.packages.RequiredProviders.Constraint.NONE
    }

    init {
        this.constraint = constraint

        com.google.common.base.Preconditions.checkState(
            constraint == com.google.devtools.build.lib.packages.RequiredProviders.Constraint.RESTRICTED
                    || (builtinProviders.isEmpty() && starlarkProviders.isEmpty())
        )

        this.builtinProviders = builtinProviders
        this.starlarkProviders = starlarkProviders
    }

    override fun equals(o: Any?): Boolean {
        if (this === o) {
            return true
        }
        if (o == null || getClass() != o.getClass()) {
            return false
        }
        val that = o as RequiredProviders
        return constraint === that.constraint && builtinProviders == that.builtinProviders
                && starlarkProviders == that.starlarkProviders
    }

    override fun hashCode(): Int {
        return java.util.Objects.hash(constraint, builtinProviders, starlarkProviders)
    }

    /** Returns a Builder initialized to the same value as this `RequiredProvider`  */
    fun copyAsBuilder(): Builder? {
        return constraint.copyAsBuilder(this)
    }

    /** A builder for [RequiredProviders]  */
    class Builder private constructor(acceptNone: Boolean) {
        private val builtinProviders: com.google.common.collect.ImmutableList.Builder<com.google.common.collect.ImmutableSet<java.lang.Class<out TransitiveInfoProvider?>?>?>

        private val starlarkProviders: com.google.common.collect.ImmutableList.Builder<com.google.common.collect.ImmutableSet<StarlarkProviderIdentifier?>?>
        private var constraint: Constraint

        init {
            constraint =
                if (acceptNone) com.google.devtools.build.lib.packages.RequiredProviders.Constraint.NONE else com.google.devtools.build.lib.packages.RequiredProviders.Constraint.ANY
            builtinProviders =
                com.google.common.collect.ImmutableList.builder<com.google.common.collect.ImmutableSet<java.lang.Class<out TransitiveInfoProvider?>?>?>()
            starlarkProviders =
                com.google.common.collect.ImmutableList.builder<com.google.common.collect.ImmutableSet<StarlarkProviderIdentifier?>?>()
        }

        /**
         * Add an alternative set of Starlark providers.
         * 
         * 
         * If all of these providers are present in the dependency, the dependency satisfies [ ].
         */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun addStarlarkSet(starlarkProviderSet: com.google.common.collect.ImmutableSet<StarlarkProviderIdentifier?>): Builder {
            constraint = com.google.devtools.build.lib.packages.RequiredProviders.Constraint.RESTRICTED
            com.google.common.base.Preconditions.checkState(!starlarkProviderSet.isEmpty())
            this.starlarkProviders.add(starlarkProviderSet)
            return this
        }

        /**
         * Add an alternative set of builtin providers.
         * 
         * 
         * If all of these providers are present in the dependency, the dependency satisfies [ ].
         */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun addBuiltinSet(
            builtinProviderSet: com.google.common.collect.ImmutableSet<java.lang.Class<out TransitiveInfoProvider?>?>
        ): Builder {
            constraint = com.google.devtools.build.lib.packages.RequiredProviders.Constraint.RESTRICTED
            com.google.common.base.Preconditions.checkState(!builtinProviderSet.isEmpty())
            this.builtinProviders.add(builtinProviderSet)
            return this
        }

        fun build(): RequiredProviders {
            return RequiredProviders(constraint, builtinProviders.build(), starlarkProviders.build())
        }
    }

    companion object {
        /** Helper method to describe lists of sets of things.  */
        private fun <T> describe(
            result: java.lang.StringBuilder,
            listOfSets: com.google.common.collect.ImmutableList<com.google.common.collect.ImmutableSet<T?>>,
            describeOne: java.util.function.Function<T?, String?>?
        ) {
            val joiner: com.google.common.base.Joiner = com.google.common.base.Joiner.on(", ")
            for (ids in listOfSets) {
                if (result.length() > 0) {
                    result.append(" or ")
                }
                result.append(if (ids.size() > 1) "[" else "")
                joiner.appendTo(result, ids.stream().map<String?>(describeOne).iterator())
                result.append(if (ids.size() > 1) "]" else "")
            }
        }

        /**
         * A builder for [RequiredProviders] that accepts any dependency
         * unless restriction provider sets are added.
         */
        @kotlin.jvm.JvmStatic
        fun acceptAnyBuilder(): Builder {
            return com.google.devtools.build.lib.packages.RequiredProviders.Builder(false)
        }

        /**
         * A builder for [RequiredProviders] that accepts no dependency
         * unless restriction provider sets are added.
         */
        @kotlin.jvm.JvmStatic
        fun acceptNoneBuilder(): Builder {
            return com.google.devtools.build.lib.packages.RequiredProviders.Builder(true)
        }
    }
}
