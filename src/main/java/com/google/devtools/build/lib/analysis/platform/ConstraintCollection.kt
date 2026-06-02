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
package com.google.devtools.build.lib.analysis.platform

import com.google.auto.value.AutoValue
import com.google.auto.value.extension.memoized.Memoized
import com.google.devtools.build.lib.analysis.platform.ConstraintCollection
import com.google.devtools.build.lib.analysis.platform.ConstraintSettingInfo
import com.google.devtools.build.lib.analysis.platform.ConstraintValueInfo
import com.google.devtools.build.lib.starlarkbuildapi.platform.ConstraintCollectionApi
import com.google.devtools.build.lib.util.Fingerprint
import java.util.stream.Collectors

/** A collection of constraint values.  */
@com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable
@AutoValue
abstract class ConstraintCollection

    : ConstraintCollectionApi<ConstraintSettingInfo?, ConstraintValueInfo?> {
    @Memoized
    abstract override fun hashCode(): Int

    /** A builder class to help create instances of [ConstraintCollection].  */
    class Builder private constructor() {
        private var parent: ConstraintCollection? = null
        private val constraintValues: com.google.common.collect.ImmutableList.Builder<ConstraintValueInfo?> =
            com.google.common.collect.ImmutableList.Builder<ConstraintValueInfo?>()

        /** Sets the parent [ConstraintCollection] of this instance.  */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun parent(parent: ConstraintCollection?): Builder {
            this.parent = parent
            return this
        }

        /** Adds the given constraints to the current collection.  */
        fun addConstraints(vararg constraints: ConstraintValueInfo?): Builder {
            return addConstraints(com.google.common.collect.ImmutableList.copyOf<ConstraintValueInfo?>(constraints))
        }

        /** Adds the given constraints to the current collection.  */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun addConstraints(constraints: Iterable<ConstraintValueInfo?>): Builder {
            constraintValues.addAll(constraints)
            return this
        }

        /** Returns the completed [ConstraintCollection] instance.  */
        @Throws(com.google.devtools.build.lib.analysis.platform.ConstraintCollection.DuplicateConstraintException::class)
        fun build(): ConstraintCollection {
            val constraintValues: com.google.common.collect.ImmutableList<ConstraintValueInfo?> =
                this.constraintValues.build()
            validateConstraints(constraintValues)
            return AutoValue_ConstraintCollection(
                this.parent,
                constraintValues.stream()
                    .collect(
                        com.google.common.collect.ImmutableMap.toImmutableMap<ConstraintValueInfo?, ConstraintSettingInfo?, Any?>(
                            java.util.function.Function { obj: ConstraintValueInfo? -> obj.constraint() },
                            java.util.function.Function.identity<Any?>()
                        )
                    )
            )
        }
    }

    val isImmutable: Boolean
        get() = true // immutable and Starlark-hashable

    /**
     * Returns the parent [ConstraintCollection] for this instance, or `null` if none
     * exists.
     */
    abstract fun parent(): ConstraintCollection?

    /** Returns the constraints supplied by this collection.  */
    abstract fun constraints(): com.google.common.collect.ImmutableMap<ConstraintSettingInfo?, ConstraintValueInfo?>?

    /**
     * Returns `true` if this [ConstraintCollection] contains every [ ] in `expected`, or if the expected constraint value is the default
     * for its setting.
     */
    fun containsAll(expected: Iterable<ConstraintValueInfo>): Boolean {
        return findMissing(expected).isEmpty()
    }

    /**
     * Returns the set of [constraints][ConstraintValueInfo] from `expected` that are not
     * present in this [ConstraintCollection], either directly, or by being the default for
     * their [ConstraintSettingInfo].
     */
    fun findMissing(expected: Iterable<ConstraintValueInfo>): com.google.common.collect.ImmutableList<ConstraintValueInfo?> {
        val missing: com.google.common.collect.ImmutableList.Builder<ConstraintValueInfo?> =
            com.google.common.collect.ImmutableList.Builder<ConstraintValueInfo?>()
        // For every constraint check if it is (1) non-null and (2) set correctly.
        for (constraint in expected) {
            val setting: ConstraintSettingInfo = constraint.constraint()
            val targetValue: ConstraintValueInfo? = get(setting)
            if (targetValue == null || constraint != targetValue) {
                missing.add(constraint)
            }
        }
        return missing.build()
    }

    /**
     * Returns the set of [settings][ConstraintSettingInfo] where this [ ] and `other` have different [values][ConstraintValueInfo].
     */
    fun diff(other: ConstraintCollection): com.google.common.collect.ImmutableSet<ConstraintSettingInfo?> {
        val constraintsToCheck: com.google.common.collect.ImmutableSet<ConstraintSettingInfo> =
            com.google.common.collect.ImmutableSet.Builder<ConstraintSettingInfo?>()
                .addAll(this.constraintSettings())
                .addAll(other.constraintSettings())
                .build()
        val mismatchSettings: com.google.common.collect.ImmutableSet.Builder<ConstraintSettingInfo?> =
            com.google.common.collect.ImmutableSet.Builder<ConstraintSettingInfo?>()
        for (constraintSetting in constraintsToCheck) {
            val thisConstraint: ConstraintValueInfo? = this.get(constraintSetting)
            val otherConstraint: ConstraintValueInfo? = other.get(constraintSetting)

            if (thisConstraint != null && thisConstraint != otherConstraint) {
                mismatchSettings.add(constraintSetting)
            }
        }

        return mismatchSettings.build()
    }

    override fun has(constraint: ConstraintSettingInfo): Boolean {
        // First, check locally.
        if (constraints().containsKey(constraint)) {
            return true
        }

        // Then, check the parent.
        if (parent() != null) {
            return parent()!!.has(constraint)
        }

        return constraint.hasDefaultConstraintValue()
    }

    fun hasWithoutDefault(constraint: ConstraintSettingInfo?): Boolean {
        // First, check locally.
        if (constraints().containsKey(constraint)) {
            return true
        }

        // Then, check the parent, directly to ignore defaults.
        if (parent() != null) {
            return parent()!!.hasWithoutDefault(constraint)
        }

        return false
    }

    override fun hasConstraintValue(constraintValue: ConstraintValueInfo): Boolean {
        val discoveredConstraintValue: ConstraintValueInfo? = this.get(constraintValue.constraint())
        return constraintValue == discoveredConstraintValue
    }

    /**
     * Returns the [ConstraintValueInfo] for the given [ConstraintSettingInfo], or `null` if none exists.
     */
    override fun get(constraint: ConstraintSettingInfo): ConstraintValueInfo? {
        // First, check locally.
        if (constraints().containsKey(constraint)) {
            return constraints().get(constraint)
        }

        // Then, check the parent, directly to ignore defaults.
        if (parent() != null) {
            return parent()!!.get(constraint)
        }

        // Finally, Since this constraint isn't set, fall back to the default.
        return constraint.defaultConstraintValue()
    }

    override fun constraintSettings(): net.starlark.java.eval.Sequence<ConstraintSettingInfo?>? {
        return net.starlark.java.eval.StarlarkList.immutableCopyOf<ConstraintSettingInfo?>(constraints().keys)
    }

    @Throws(net.starlark.java.eval.EvalException::class)
    override fun getIndex(semantics: net.starlark.java.eval.StarlarkSemantics?, key: Any): Any? {
        val constraintSettingInfo: ConstraintSettingInfo = convertKey(key)
        val result: Any? = get(constraintSettingInfo)
        if (result == null) {
            return net.starlark.java.eval.Starlark.NONE
        }
        return result
    }

    @Throws(net.starlark.java.eval.EvalException::class)
    override fun containsKey(semantics: net.starlark.java.eval.StarlarkSemantics?, key: Any): Boolean {
        return has(convertKey(key))
    }

    // It's easier to use the Starlark repr as a string form, not what AutoValue produces.
    override fun toString(): String {
        return net.starlark.java.eval.Starlark.repr(this, net.starlark.java.eval.StarlarkSemantics.DEFAULT)
    }

    override fun repr(printer: net.starlark.java.eval.Printer, semantics: net.starlark.java.eval.StarlarkSemantics?) {
        printer.append("<")
        if (parent() != null) {
            printer.append("parent: ")
            parent()!!.repr(printer, semantics)
            printer.append(", ")
        }
        printer.append("[")
        printer.append(
            constraints().values.stream()
                .map<com.google.devtools.build.lib.cmdline.Label?> { obj: ConstraintValueInfo? -> obj.label() }
                .map<String?>(com.google.common.base.Functions.toStringFunction())
                .collect(Collectors.joining(", ")))
        printer.append("]")
        printer.append(">")
    }

    /**
     * Adds information to the [Fingerprint] to uniquely identify this collection of
     * constraints.
     */
    fun addToFingerprint(fp: Fingerprint) {
        // Encode whether there is a parent.
        fp.addBoolean(parent() != null)
        // Add the parent.
        if (parent() != null) {
            parent()!!.addToFingerprint(fp)
        }
        // Add the actual constraints.
        fp.addInt(constraints().size)
        constraints().values.forEach(java.util.function.Consumer { constraintValue: ConstraintValueInfo? ->
            constraintValue.addTo(
                fp
            )
        })
    }

    /**
     * Exception class used when more than one [ConstraintValueInfo] for the same [ ] is added to a [Builder].
     */
    class DuplicateConstraintException internal constructor(duplicateConstraints: com.google.common.collect.ListMultimap<ConstraintSettingInfo?, ConstraintValueInfo?>) :
        java.lang.Exception(
            com.google.devtools.build.lib.analysis.platform.ConstraintCollection.DuplicateConstraintException.Companion.formatError(
                duplicateConstraints
            )
        ) {
        private val duplicateConstraints: com.google.common.collect.ImmutableListMultimap<ConstraintSettingInfo?, ConstraintValueInfo?>


        init {
            this.duplicateConstraints =
                com.google.common.collect.ImmutableListMultimap.copyOf<ConstraintSettingInfo?, ConstraintValueInfo?>(
                    duplicateConstraints
                )
        }

        fun duplicateConstraints(): com.google.common.collect.ImmutableListMultimap<ConstraintSettingInfo?, ConstraintValueInfo?> {
            return duplicateConstraints
        }

        companion object {
            fun formatError(
                duplicateConstraints: com.google.common.collect.ListMultimap<ConstraintSettingInfo?, ConstraintValueInfo?>
            ): String? {
                return String.format(
                    "Duplicate constraint values detected: %s",
                    duplicateConstraints.asMap().entries.stream()
                        .map<String?> { duplicate: MutableMap.MutableEntry<ConstraintSettingInfo?, MutableCollection<ConstraintValueInfo?>?>? ->
                            com.google.devtools.build.lib.analysis.platform.ConstraintCollection.DuplicateConstraintException.Companion.describeSingleDuplicateConstraintSetting(
                                duplicate
                            )
                        }
                        .collect(Collectors.joining(", ")))
            }

            private fun describeSingleDuplicateConstraintSetting(
                duplicate: MutableMap.MutableEntry<ConstraintSettingInfo?, MutableCollection<ConstraintValueInfo?>?>
            ): String? {
                return String.format(
                    "constraint_setting %s has [%s]",
                    duplicate.key.label(),
                    duplicate.value.stream()
                        .map<com.google.devtools.build.lib.cmdline.Label?> { obj: ConstraintValueInfo? -> obj.label() }
                        .map<String?> { obj: com.google.devtools.build.lib.cmdline.Label? -> obj.toString() }
                        .collect(Collectors.joining(", ")))
            }
        }
    }

    companion object {
        /** Returns a new [Builder] suitable for creating [ConstraintCollection] instances.  */
        @kotlin.jvm.JvmStatic
        fun builder(): Builder {
            return com.google.devtools.build.lib.analysis.platform.ConstraintCollection.Builder()
        }

        @Throws(net.starlark.java.eval.EvalException::class)
        private fun convertKey(key: Any): ConstraintSettingInfo {
            if (key !is ConstraintSettingInfo) {
                throw net.starlark.java.eval.Starlark.errorf(
                    "Constraint names must be platform_common.ConstraintSettingInfo, got %s instead",
                    net.starlark.java.eval.Starlark.type(key)
                )
            }

            return key as ConstraintSettingInfo
        }

        /**
         * Validates that the given constraints do not contain conflicting values.
         * 
         * 
         * Checks that no [ConstraintSettingInfo] has multiple different [ ] values. Multiple instances of the same constraint value are allowed.
         * 
         * @param constraintValues the constraints to validate
         * @throws DuplicateConstraintException if multiple different constraint values exist for the same
         * constraint setting
         */
        @Throws(com.google.devtools.build.lib.analysis.platform.ConstraintCollection.DuplicateConstraintException::class)
        fun validateConstraints(constraintValues: Iterable<ConstraintValueInfo?>) {
            // Collect the constraints by the settings.
            val constraints: com.google.common.collect.ImmutableListMultimap<ConstraintSettingInfo?, ConstraintValueInfo?> =
                com.google.common.collect.Streams.stream<ConstraintValueInfo?>(constraintValues)
                    .collect()
            TODO(
                """
                |Cannot convert element
                |With text:
                |ConstraintValueInfo, ConstraintSettingInfo, ConstraintValueInfo>toImmutableListMultimap(ConstraintValueInfo::constraint, Functions.<ConstraintValueInfo>identity())
                """.trimMargin()
            )


            // Find different constraint values targeting the same constraint setting.
            // Ignore multiple instances of the same constraint value.
            val duplicates: com.google.common.collect.ImmutableListMultimap<ConstraintSettingInfo?, ConstraintValueInfo?> =
                constraints.asMap().entries.stream()
                    .filter { e: MutableMap.MutableEntry<ConstraintSettingInfo?, MutableCollection<ConstraintValueInfo?>?>? ->
                        e!!.value.stream().distinct().count() > 1
                    }
                    .collect()
            TODO(
                """
                |Cannot convert element
                |With text:
                |ConstraintSettingInfo, ConstraintValueInfo>flatteningToImmutableListMultimap(
                |                    Map.Entry::getKey, e -> e.getValue().stream().distinct())
                """.trimMargin()
            )


            if (!duplicates.isEmpty()) {
                throw com.google.devtools.build.lib.analysis.platform.ConstraintCollection.DuplicateConstraintException(
                    duplicates
                )
            }
        }
    }
}
