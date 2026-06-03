// Copyright 2020 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.analysis.config

import com.google.devtools.build.lib.concurrent.ThreadSafety.Immutable

/**
 * An interface for language-specific configurations.
 * 
 * 
 * Implementations must have a constructor that takes a single [BuildOptions] argument. If
 * the constructor reads any [FragmentOptions] from this argument, the fragment must declare
 * them via [RequiresOptions].
 * 
 * 
 * All implementations must be immutable and communicate this as clearly as possible (e.g.
 * declare [com.google.common.collect.ImmutableList] signatures on their interfaces vs. [ ]). This is because fragment instances may be shared across configurations.
 * 
 * 
 * Fragments are Starlark values, as returned by `ctx.fragments.android`, for example.
 */
@Immutable
abstract class Fragment : StarlarkValue {
    /**
     * When a fragment doesn't want to be part of the configuration (for example, when its required
     * options are missing and the fragment determines this means the configuration doesn't need it),
     * it should override this method.
     */
    fun shouldInclude(): Boolean {
        return true
    }

    public override fun isImmutable(): Boolean {
        return true // immutable and Starlark-hashable
    }

    /**
     * Validates the options for this Fragment. Issues warnings for the use of deprecated options, and
     * warnings or errors for any option settings that conflict.
     */
    @Suppress("unused")
    fun reportInvalidOptions(reporter: EventHandler?, buildOptions: BuildOptions?) {
    }

    /**
     * Context needed by implementations of [Fragment.processForOutputPathMnemonic].
     * 
     * 
     * The Fragment constructor should already have sufficient access to targetOptions as per
     * RequiresOption above. So a getTargetOption method should not be necessary.
     */
    interface OutputDirectoriesContext {
        /** If available, get the baseline version of some FragmentOption  */
        fun <T : FragmentOptions?> getBaseline(optionsClass: java.lang.Class<T?>?): T?

        /**
         * Adds given String to the explicit part of the output path.
         * 
         * 
         * A null or empty value is not added to the mnemonic. Ideally this function will eventually
         * just error when supplied those values.
         * 
         * @throws AddToMnemonicException if given value cannot be put in an output path.
         */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        @Throws(AddToMnemonicException::class)
        fun addToMnemonic(value: String?): OutputDirectoriesContext?

        /**
         * Mark the option as explicit in output path so it no longer contributes to hash computation.
         * 
         * 
         * Options which are marked must be explicitly included in the output path by [ ] (or indirectly in [Fragment.getOutputDirectoryName]) and thus will not
         * be included in the hash of changed options used to generically disambiguate output
         * directories of different configurations. (See [OutputPathMnemonicComputer].)
         * 
         * 
         * This tag should only be added to options that can guarantee that any change to that option
         * corresponds to a change to [OutputPathMnemonicComputer.computeMnemonic]. Put
         * mathematically, given any two BuildOptions instances A and B with respective values for the
         * marked option a and b (where all other options are the same and there is some potentially
         * null baseline): `a == b iff computeMnemonic(A, baseline) == computeMnemonic(b, baseline)`
         * 
         * 
         * As a historical note, this used to be implemented as EXPLICIT_IN_OUTPUT_PATH
         */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun markAsExplicitInOutputPathFor(optionName: String?): OutputDirectoriesContext?

        /** bubble up error with adding to mnemonic (likely a problematic value supplied)  */
        class AddToMnemonicException internal constructor(badValue: String?, e: java.lang.Exception?) :
            java.lang.Exception("Invalid option value " + badValue, e) {
            val tunneledException: java.lang.Exception?
            val badValue: String?

            init {
                this.tunneledException = e
                this.badValue = badValue
            }
        }
    }

    /**
     * Returns a fragment of the output directory name for this set of options. See [ ])
     */
    @Throws(AddToMnemonicException::class)
    fun processForOutputPathMnemonic(ctx: OutputDirectoriesContext?) {
    }

    companion object {
        /** Returns the option classes needed to create a fragment.  */
        fun requiredOptions(
            fragmentClass: java.lang.Class<out Fragment?>
        ): com.google.common.collect.ImmutableSet<java.lang.Class<out FragmentOptions?>?> {
            val annotation: RequiresOptions? = fragmentClass.getAnnotation<A?>(RequiresOptions::class.java)
            return if (annotation == null) com.google.common.collect.ImmutableSet.of<java.lang.Class<out FragmentOptions?>?>() else com.google.common.collect.ImmutableSet.copyOf(
                annotation.options()
            )
        }

        /** Returns `true` if the given fragment requires access to starlark options.  */
        fun requiresStarlarkOptions(fragmentClass: java.lang.Class<out Fragment?>): Boolean {
            val annotation: RequiresOptions? = fragmentClass.getAnnotation<A?>(RequiresOptions::class.java)
            return annotation != null && annotation.starlark()
        }
    }
}
