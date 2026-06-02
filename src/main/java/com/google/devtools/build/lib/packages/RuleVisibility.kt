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
package com.google.devtools.build.lib.packages

import com.google.devtools.build.lib.cmdline.Label

/**
 * A RuleVisibility specifies which other rules can depend on a specified rule. Note that the actual
 * method that performs this check is declared in RuleConfiguredTargetVisibility.
 * 
 * 
 * The conversion to ConfiguredTargetVisibility is handled in an ugly if-ladder, because I want
 * to avoid this package depending on build.lib.view.
 * 
 * 
 * All implementations of this interface are immutable.
 */
abstract class RuleVisibility {
    /**
     * Returns the list of all labels comprising this visibility.
     * 
     * 
     * This includes labels that are not loadable, such as //visibility:public and //foo:__pkg__.
     */
    abstract fun getDeclaredLabels(): MutableList<Label?>

    /**
     * Same as [.getDeclaredLabels], but excludes labels that cannot be loaded.
     * 
     * 
     * I.e., this returns the labels of the top-level `package_group`s that must be loaded in
     * order to determine the complete set of packages represented by this visibility. (Additional
     * `package_group`s may need to be loaded due to their `includes` attribute.)
     */
    abstract fun getDependencyLabels(): MutableList<Label?>?

    /**
     * Returns a `RuleVisibility` representing the logical result of concatenating this
     * visibility's label list with a singleton list containing the given package.
     * 
     * 
     * Public and private visibilities are normalized as in [.validateAndSimplify]. In
     * addition, the new item is not concatenated if it is already present as an item in this
     * visibility's list.
     */
    fun concatWithPackage(packageIdentifier: PackageIdentifier?): RuleVisibility {
        val pkgItem: Label = Label.createUnvalidated(packageIdentifier, "__pkg__")

        if (this == PRIVATE) {
            // Left-side private is dropped.
            return parseUnchecked(com.google.common.collect.ImmutableList.of<Label?>(pkgItem))
        } else if (this == PUBLIC) {
            // Public is idempotent.
            return PUBLIC
        } else {
            val items: MutableList<Label?> = getDeclaredLabels()
            if (items.contains(pkgItem)) {
                return this
            } else {
                val newItems: com.google.common.collect.ImmutableList.Builder<Label?> =
                    com.google.common.collect.ImmutableList.Builder<Label?>()
                newItems.addAll(items)
                newItems.add(pkgItem)
                return parseUnchecked(newItems.build())
            }
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) {
            return true
        } else if (other !is RuleVisibility) {
            return false
        } else {
            // PackageGroupsRuleVisibility is not allowed to contain the special public/private items, so
            // we don't have to worry about that overlapping with our singleton PUBLIC/PRIVATE instances
            // here.
            return getDeclaredLabels() == other.getDeclaredLabels()
        }
    }

    override fun hashCode(): Int {
        return java.util.Objects.hash(getClass(), getDeclaredLabels())
    }

    companion object {
        @kotlin.jvm.JvmField
        @SerializationConstant
        val PUBLIC_LABEL: Label = Label.parseCanonicalUnchecked("//visibility:public")

        @kotlin.jvm.JvmField
        @SerializationConstant
        val PRIVATE_LABEL: Label = Label.parseCanonicalUnchecked("//visibility:private")

        // Constant for memory efficiency; see b/370873477.
        @SerializationConstant
        val PUBLIC_DECLARED_LABELS: com.google.common.collect.ImmutableList<Label> =
            com.google.common.collect.ImmutableList.of<Label>(
                PUBLIC_LABEL
            )

        // Constant for memory efficiency; see b/370873477.
        @SerializationConstant
        val PRIVATE_DECLARED_LABELS: com.google.common.collect.ImmutableList<Label> =
            com.google.common.collect.ImmutableList.of<Label>(
                PRIVATE_LABEL
            )

        @kotlin.jvm.JvmField
        @SerializationConstant
        val PUBLIC: RuleVisibility = object : RuleVisibility() {
            override fun getDeclaredLabels(): com.google.common.collect.ImmutableList<Label> {
                return PUBLIC_DECLARED_LABELS
            }

            override fun getDependencyLabels(): com.google.common.collect.ImmutableList<Label?> {
                return com.google.common.collect.ImmutableList.of<Label?>()
            }

            override fun toString(): String {
                return PUBLIC_LABEL.toString()
            }
        }

        @kotlin.jvm.JvmField
        @SerializationConstant
        val PRIVATE: RuleVisibility = object : RuleVisibility() {
            override fun getDeclaredLabels(): com.google.common.collect.ImmutableList<Label> {
                return PRIVATE_DECLARED_LABELS
            }

            override fun getDependencyLabels(): com.google.common.collect.ImmutableList<Label?> {
                return com.google.common.collect.ImmutableList.of<Label?>()
            }

            override fun toString(): String {
                return PRIVATE_LABEL.toString()
            }
        }

        /** Validates and parses the given labels into a [RuleVisibility].  */
        @Throws(net.starlark.java.eval.EvalException::class)
        fun parse(labels: MutableList<Label>): RuleVisibility {
            return parseUnchecked(validateAndSimplify(labels))
        }

        /**
         * Same as [.parse] except does not perform validation checks or public/private
         * simplification.
         * 
         * 
         * Use only after the given labels have been [validated and][.validateAndSimplify].
         */
        fun parseUnchecked(labels: MutableList<Label>): RuleVisibility {
            val result = parseIfConstant(labels)
            if (result != null) {
                return result
            }
            return PackageGroupsRuleVisibility.Companion.create(labels)
        }

        /**
         * If the given list of labels represents a constant [RuleVisibility] ([.PUBLIC] or
         * [.PRIVATE]), returns that visibility instance, otherwise returns `null`.
         * 
         * 
         * Use only after the given labels have been [validated and][.validateAndSimplify].
         */
        fun parseIfConstant(labels: MutableList<Label>): RuleVisibility? {
            if (labels.size() != 1) {
                return null
            }
            val label: Label = labels.getFirst()
            if (label.equals(PUBLIC_LABEL)) {
                return PUBLIC
            }
            if (label.equals(PRIVATE_LABEL)) {
                return PRIVATE
            }
            return null
        }

        /**
         * Throws if the label is in the special `//visibility` package but is neither `//visibility:__pkg__` nor `//visibility:__subpackages__`.
         * 
         * 
         * The caller is presumed to have already handled the cases where it is `//visibility:public` or `//visibility:private`. If those labels make it to this method it
         * will throw.
         * 
         * 
         * `//visibility:__pkg__` and `//visibility:__subpackages__` are only useful in the
         * rare case that there exists a literal `//visibility` package in the build. It is
         * disallowed to refer to any package groups declared in `//visibility`. This restriction
         * lets us presume that any label in `//visibility` besides these four cases is an
         * accidental misspelling.
         */
        @Throws(net.starlark.java.eval.EvalException::class)
        private fun checkForVisibilityMisspelling(label: Label) {
            if (label.getPackageIdentifier().equals(PUBLIC_LABEL.getPackageIdentifier())
                && PackageSpecification.Companion.fromLabel(label) == null
            ) {
                // Suggest just public/private, as that's way more common than //visibility:__pkg__ or
                // //visibility:__subpackages__.
                throw net.starlark.java.eval.Starlark.errorf(
                    "Invalid visibility label '%s'; did you mean //visibility:public or"
                            + " //visibility:private?",
                    label
                )
            }
        }

        /**
         * Validates visibility labels, simplifies a list containing "//visibility:public" to
         * ["//visibility:public"], drops "//visibility:private" if it occurs with other labels, and
         * canonicalizes an empty list to ["//visibility:private"].
         * 
         * @param labels list of visibility labels; not modified even if mutable.
         * @return either `labels` unmodified if it does not require simplification, or a new
         * simplified list of visibility labels.
         */
        // TODO(arostovtsev): we ought to uniquify the labels, matching the behavior of {@link
        // #concatWithElement}; note that this would be an incompatible change (affects query output).
        @Throws(net.starlark.java.eval.EvalException::class)
        fun validateAndSimplify(labels: MutableList<Label>): MutableList<Label> {
            var hasPublicLabel = false
            var numPrivateLabels = 0
            for (label in labels) {
                if (label.equals(PUBLIC_LABEL)) {
                    // Do not short-circuit here; we want to validate all the labels.
                    hasPublicLabel = true
                } else if (label.equals(PRIVATE_LABEL)) {
                    numPrivateLabels++
                } else {
                    checkForVisibilityMisspelling(label)
                }
            }
            if (hasPublicLabel) {
                return PUBLIC_DECLARED_LABELS
            }
            if (numPrivateLabels == labels.size()) {
                return PRIVATE_DECLARED_LABELS
            }
            if (numPrivateLabels == 0) {
                return labels
            }
            val withoutPrivateLabels: com.google.common.collect.ImmutableList.Builder<Label?> =
                com.google.common.collect.ImmutableList.builderWithExpectedSize<Label?>(labels.size() - numPrivateLabels)
            for (label in labels) {
                if (!label.equals(PRIVATE_LABEL)) {
                    withoutPrivateLabels.add(label)
                }
            }
            return withoutPrivateLabels.build()
        }
    }
}
