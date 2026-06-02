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
package com.google.devtools.build.lib.skyframe

import com.google.devtools.build.lib.cmdline.Label

/**
 * A *transitive* target reference that, when built in skyframe, loads the entire transitive
 * closure of a target. Retains the first error message found during the transitive traversal, the
 * kind of target, and a set of names of providers if the target is a [Rule].
 * 
 * 
 * Interns values for error-free traversal nodes that correspond to built-in rules.
 */
@Immutable
@ThreadSafe
abstract class TransitiveTraversalValue protected constructor(kind: String?) : SkyValue {
    /** Returns the target kind.  */
    val kind: String

    init {
        this.kind = com.google.common.base.Preconditions.checkNotNull<String>(kind)
    }

    /**
     * Returns the set of provider names from the target, if the target is a [Rule]. Otherwise
     * returns the empty set.
     */
    abstract val providers: AdvertisedProviderSet?

    /**
     * Returns a deterministic error message, if any, from loading the target and its transitive
     * dependencies.
     */
    @kotlin.jvm.JvmField
    abstract val errorMessage: String?

    override fun equals(o: Any?): Boolean {
        if (this === o) {
            return true
        }
        if (o !is TransitiveTraversalValue) {
            return false
        }
        return this.errorMessage == o.errorMessage
                && this.kind == o.kind
                && this.providers.equals(o.providers)
    }

    override fun hashCode(): Int {
        return java.util.Objects.hash(this.errorMessage, this.kind, this.providers)
    }

    /** A transitive target reference without error.  */
    class TransitiveTraversalValueWithoutError private constructor(providers: AdvertisedProviderSet?, kind: String?) :
        TransitiveTraversalValue(kind) {
        private val advertisedProviders: AdvertisedProviderSet

        init {
            this.advertisedProviders =
                com.google.common.base.Preconditions.checkNotNull<AdvertisedProviderSet>(providers)
        }

        override fun getProviders(): AdvertisedProviderSet {
            return advertisedProviders
        }

        override fun getErrorMessage(): String? {
            return null
        }

        override fun toString(): String {
            return com.google.common.base.MoreObjects.toStringHelper(this)
                .add("kind", this.kind)
                .add("providers", advertisedProviders)
                .toString()
        }
    }

    /** A transitive target reference with error.  */
    class TransitiveTraversalValueWithError private constructor(errorMessage: String?, kind: String?) :
        TransitiveTraversalValue(kind) {
        private val errorMessage: String?

        init {
            this.errorMessage = com.google.common.base.Preconditions.checkNotNull<String?>(errorMessage).intern()
        }

        override fun getProviders(): AdvertisedProviderSet {
            return AdvertisedProviderSet.EMPTY
        }

        override fun getErrorMessage(): String? {
            return errorMessage
        }

        override fun toString(): String {
            return com.google.common.base.MoreObjects.toStringHelper(this)
                .add("error", errorMessage)
                .add("kind", this.kind)
                .toString()
        }
    }

    companion object {
        // A quick-lookup cache that allows us to get the value for a given target kind, assuming no error
        // messages for the target. The number of built-in target kinds is limited, so memory bloat is not
        // a concern.
        private val VALUES_BY_TARGET_KIND: ConcurrentMap<String?, TransitiveTraversalValue?> =
            ConcurrentHashMap<String?, TransitiveTraversalValue?>()

        /**
         * A strong interner of TransitiveTargetValue objects. Because we only wish to intern values for
         * built-in non-Starlark targets, we need an interner with an additional method to return the
         * canonical representative if it is present without interning our sample. This is only mutated in
         * [.forTarget], and read in [.forTarget] and [.create].
         */
        private val VALUE_INTERNER: InternerWithPresenceCheck<TransitiveTraversalValue?> = InternerWithPresenceCheck()

        fun unsuccessfulTransitiveTraversal(
            errorMessage: String?, target: Target
        ): TransitiveTraversalValue {
            return TransitiveTraversalValueWithError(
                com.google.common.base.Preconditions.checkNotNull<String?>(errorMessage), target.getTargetKind()
            )
        }

        fun forTarget(target: Target, errorMessage: String?): TransitiveTraversalValue? {
            if (errorMessage == null) {
                if (target is Rule && (target as Rule).getRuleClassObject().isStarlark) {
                    // Do not intern values for Starlark rules.
                    return create(
                        target.getRuleClassObject().getAdvertisedProviders(), target.getTargetKind(), errorMessage
                    )
                } else {
                    var value: TransitiveTraversalValue? = VALUES_BY_TARGET_KIND.get(target.getTargetKind())
                    if (value != null) {
                        return value
                    }

                    val providers: AdvertisedProviderSet? =
                        if (target is Rule)
                            target.getRuleClassObject().getAdvertisedProviders()
                        else
                            AdvertisedProviderSet.EMPTY

                    value = TransitiveTraversalValueWithoutError(providers, target.getTargetKind())
                    // May already be there from another target or a concurrent put.
                    value = VALUE_INTERNER.intern(value)
                    // May already be there from a concurrent put.
                    VALUES_BY_TARGET_KIND.putIfAbsent(target.getTargetKind(), value)
                    return value
                }
            } else {
                return TransitiveTraversalValueWithError(errorMessage, target.getTargetKind())
            }
        }

        fun create(
            providers: AdvertisedProviderSet?, kind: String?, errorMessage: String?
        ): TransitiveTraversalValue {
            val value =
                if (errorMessage == null)
                    TransitiveTraversalValueWithoutError(providers, kind)
                else
                    TransitiveTraversalValueWithError(errorMessage, kind)
            if (errorMessage == null) {
                val oldValue: TransitiveTraversalValue? = VALUE_INTERNER.getCanonical(value)
                return if (oldValue == null) value else oldValue
            }
            return value
        }

        @ThreadSafe
        fun key(label: Label?): SkyKey? {
            return label
        }
    }
}
