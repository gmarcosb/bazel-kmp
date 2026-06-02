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
package com.google.devtools.build.lib.analysis.config

import java.util.AbstractMap
import java.util.LinkedHashMap

/** Command-line build options for a Blaze module.  */
abstract class FragmentOptions : com.google.devtools.common.options.OptionsBase(), Cloneable {
    // Reflection doesn't support generics
    override fun getOptionsClass(): java.lang.Class<out FragmentOptions?>? {
        return super.getOptionsClass() as java.lang.Class<out FragmentOptions?>?
    }

    public override fun clone(): FragmentOptions? {
        try {
            return super.clone() as FragmentOptions?
        } catch (e: java.lang.CloneNotSupportedException) {
            // This can't happen.
            throw java.lang.IllegalStateException(e)
        }
    }

    /**
     * Creates a new instance of this `FragmentOptions` with all flags set to their default
     * values.
     */
    fun getDefault(): FragmentOptions? {
        return com.google.devtools.common.options.Options.getDefaults(getOptionsClass())
    }

    /**
     * Returns an instance of `FragmentOptions` with all flags adjusted to be suitable for
     * forming configurations.
     * 
     * 
     * If this instance is already suitable, it will be returned without creating a new instance.
     * 
     * 
     * Motivation: Sometimes a fragment's physical option values, as set by the options parser, do
     * not correspond to their logical interpretation. For example, an option may need custom code to
     * determine its logical default value at runtime, but it's limited to a single hard-coded
     * physical default value in the [Option.defaultValue] annotation field. If two instances of
     * the fragment have the same logical value but different physical values, a redundant
     * configuration can be created, which results in an action conflict (particularly for unshareable
     * actions; see #7808).
     * 
     * 
     * To solve this, we can distinguish between "normalized" and "non-normalized" instances of a
     * fragment type, and preserve the invariant that configured targets only ever see normalized
     * instances. This requires that 1) the top-level configuration is normalized, and 2) all
     * transitions preserve normalization. Step 1) is ensured by [BuildOptions] calling this
     * method. Step 2) is the responsibility of each transition implementation.
     */
    open fun getNormalized(): FragmentOptions? {
        return this
    }

    /** Converts the options to a string-keyed map.  */
    fun asMap(): MutableMap<String?, Any?> {
        return com.google.devtools.common.options.Options.toMap<FragmentOptions?>(this)
    }

    /** Tracks limitations on referring to an option in a `config_setting`.  */ // TODO(bazel-team): There will likely also be a need to customize whether or not an option is
    // visible to users for setting on the command line (or perhaps even in a test of a Starlark
    // rule). This class may be a good place to add this functionality.
    class SelectRestriction(private val visibleWithinToolsPackage: Boolean, private val errorMessage: String?) {
        /**
         * Whether the option can still be seen by `config_setting`s that are defined by packages
         * underneath the tools repository's "tools" package, e.g. `@bazel_tools//tools/...`.
         */
        fun isVisibleWithinToolsPackage(): Boolean {
            return visibleWithinToolsPackage
        }

        /**
         * An additional explanation to append to the generic error message when a user attempts to use
         * this option. Should explain why this option is unavailable.
         * 
         * 
         * If null, no content will be appended to the generic error message.
         */
        fun getErrorMessage(): String? {
            return errorMessage
        }
    }

    companion object {
        /**
         * Helper method for subclasses to normalize set valued options. In addition to removing
         * duplicates, it picks a deterministic ordering. The fact that the deterministic ordering is
         * based on sorting is an accident and should NOT be relied upon.
         */
        protected fun dedupAndSort(values: MutableList<String?>?): com.google.common.collect.ImmutableList<String?> {
            if (values == null || values.isEmpty()) {
                return com.google.common.collect.ImmutableList.of<String?>()
            }

            val result: com.google.common.collect.ImmutableList<String?> =
                values.stream() // Use the natural String ordering.
                    .sorted()
                    .distinct()
                    .collect(com.google.common.collect.ImmutableList.toImmutableList<String?>())

            // If the value is already deduped and sorted return the exact same instance we got.
            return if (result == values) com.google.common.collect.ImmutableList.copyOf<String?>(values) else result
        }

        /**
         * Helper method for subclasses to normalize list of map entries by keeping only the last entry
         * for each key. The order of the entries is preserved.
         */
        protected fun <V> normalizeEntries(
            entries: MutableList<MutableMap.MutableEntry<String?, V?>>
        ): MutableList<MutableMap.MutableEntry<String?, V?>> {
            val normalizedEntries: LinkedHashMap<String?, V?> = LinkedHashMap<String?, V?>()
            for (entry in entries) {
                normalizedEntries.put(entry.key, entry.value)
            }
            // If we made no changes, return the same instance we got to reduce churn.
            if (normalizedEntries.size == entries.size) {
                return entries
            }
            return normalizedEntries.entries.stream()
                .map<AbstractMap.SimpleEntry<String?, V?>?> { entry: MutableMap.MutableEntry<String?, V?>? ->
                    AbstractMap.SimpleEntry(
                        entry
                    )
                }
                .collect(com.google.common.collect.ImmutableList.toImmutableList<MutableMap.MutableEntry<String?, V?>?>())
        }

        /**
         * Helper method for subclasses to normalize list of [EnvVar]s by keeping only the last
         * entry for each key. The order of the entries is preserved.
         */
        protected fun normalizeEnvVars(entries: MutableList<com.google.devtools.build.lib.util.EnvVar>): MutableList<com.google.devtools.build.lib.util.EnvVar> {
            val normalizedEntries: LinkedHashMap<String?, com.google.devtools.build.lib.util.EnvVar?> =
                LinkedHashMap<String?, com.google.devtools.build.lib.util.EnvVar?>()
            for (entry in entries) {
                normalizedEntries.put(entry.name(), entry)
            }
            // If we made no changes, return the same instance we got to reduce churn.
            if (normalizedEntries.size == entries.size) {
                return entries
            }
            return com.google.common.collect.ImmutableList.copyOf<com.google.devtools.build.lib.util.EnvVar?>(
                normalizedEntries.values
            )
        }
    }
}
