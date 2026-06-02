// Copyright 2021 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.analysis.config.FragmentClassSet
import com.google.devtools.build.lib.analysis.config.FragmentOptions

/** A registry of all [Fragment] and [FragmentOptions] classes registered at startup.  */
class FragmentRegistry private constructor(
    allFragments: FragmentClassSet?,
    universalFragments: FragmentClassSet?,
    optionsClasses: com.google.common.collect.ImmutableSortedSet<java.lang.Class<out FragmentOptions?>?>?
) {
    private val allFragments: FragmentClassSet?
    private val universalFragments: FragmentClassSet?
    private val optionsClasses: com.google.common.collect.ImmutableSortedSet<java.lang.Class<out FragmentOptions?>?>?

    init {
        this.allFragments = allFragments
        this.universalFragments = universalFragments
        this.optionsClasses = optionsClasses
    }

    /** Returns the set of all registered configuration fragments.  */
    fun getAllFragments(): FragmentClassSet? {
        return allFragments
    }

    /**
     * Returns a subset of [.getAllFragments] that should be available to all rules even when
     * not explicitly required.
     */
    fun getUniversalFragments(): FragmentClassSet? {
        return universalFragments
    }

    /**
     * Returns the set of all registered [FragmentOptions] classes.
     * 
     * 
     * Includes at least all options classes [required][RequiresOptions] by fragments in
     * [.getAllFragments].
     */
    fun getOptionsClasses(): com.google.common.collect.ImmutableSortedSet<java.lang.Class<out FragmentOptions?>?>? {
        return optionsClasses
    }

    companion object {
        /**
         * Creates a `FragmentRegistry`.
         * 
         * 
         * Order of elements in the given lists does not matter - the resulting registry will contain
         * deterministically ordered sets.
         * 
         * @param allFragments all registered fragment classes, including `universalFragments`
         * @param universalFragments fragment classes that should be available to all rules even when not
         * explicitly required
         * @param additionalOptions any additional options classes not accounted for by a [     ] annotation on a [Fragment] class in `allFragments`
         */
        fun create(
            allFragments: MutableList<java.lang.Class<out Fragment?>?>?,
            universalFragments: MutableList<java.lang.Class<out Fragment?>?>?,
            additionalOptions: MutableList<java.lang.Class<out FragmentOptions?>?>
        ): FragmentRegistry {
            val allFragmentsSet: FragmentClassSet = FragmentClassSet.Companion.of(allFragments)
            val universalFragmentsSet: FragmentClassSet = FragmentClassSet.Companion.of(universalFragments)
            require(allFragmentsSet.containsAll(universalFragmentsSet)) {
                ("Missing universally required fragments: "
                        + com.google.common.collect.Sets.difference<java.lang.Class<out Fragment?>?>(
                    universalFragmentsSet,
                    allFragmentsSet
                ))
            }

            val optionsClasses: com.google.common.collect.ImmutableSortedSet.Builder<java.lang.Class<out FragmentOptions?>?> =
                com.google.common.collect.ImmutableSortedSet.orderedBy<java.lang.Class<out FragmentOptions?>?>(
                    BuildOptions.LEXICAL_FRAGMENT_OPTIONS_COMPARATOR
                )
            for (fragment in allFragmentsSet) {
                optionsClasses.addAll(Fragment.requiredOptions(fragment))
            }
            optionsClasses.addAll(additionalOptions)

            return FragmentRegistry(allFragmentsSet, universalFragmentsSet, optionsClasses.build())
        }
    }
}
