// Copyright 2023 The Bazel Authors. All rights reserved.
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

import com.google.devtools.build.lib.analysis.config.FragmentOptions
import com.google.devtools.build.lib.util.OrderedSetMultimap
import java.util.HashSet
import java.util.LinkedHashMap
import java.util.SequencedMap

/**
 * A diff class for BuildOptions. Fields are meant to be populated and returned by [ ][OptionsDiff.diff].
 */
class OptionsDiff {
    private val differingOptions: com.google.common.collect.ListMultimap<java.lang.Class<out FragmentOptions?>?, com.google.devtools.common.options.OptionDefinition?> =
        com.google.common.collect.ArrayListMultimap.create<java.lang.Class<out FragmentOptions?>?, com.google.devtools.common.options.OptionDefinition?>()

    // The keyset for the {@link first} and {@link second} maps are identical and indicate which
    // specific options differ between the first and second built options.
    private val first: MutableMap<com.google.devtools.common.options.OptionDefinition?, Any?> =
        LinkedHashMap<com.google.devtools.common.options.OptionDefinition?, Any?>()

    // Since this class can be used to track the result of transitions, {@link second} is a multimap
    // to be able to handle {@link SplitTransition}s.
    private val second: com.google.common.collect.SetMultimap<com.google.devtools.common.options.OptionDefinition?, Any?> =
        OrderedSetMultimap.create<com.google.devtools.common.options.OptionDefinition?, Any?>()

    // List of "extra" fragments for each BuildOption aka fragments that were trimmed off one
    // BuildOption but not the other.
    private val extraFirstFragments: MutableSet<java.lang.Class<out FragmentOptions?>?> =
        HashSet<java.lang.Class<out FragmentOptions?>?>()
    private val extraSecondFragments: MutableSet<FragmentOptions?> = HashSet<FragmentOptions?>()

    private val starlarkFirst: MutableMap<com.google.devtools.build.lib.cmdline.Label?, Any?> =
        LinkedHashMap<com.google.devtools.build.lib.cmdline.Label?, Any?>()

    // TODO(b/112041323): This should also be multimap but we don't diff multiple times with
    // Starlark options anywhere yet so add that feature when necessary.
    private val starlarkSecond: MutableMap<com.google.devtools.build.lib.cmdline.Label?, Any?> =
        LinkedHashMap<com.google.devtools.build.lib.cmdline.Label?, Any?>()

    private val extraStarlarkOptionsFirst: MutableList<com.google.devtools.build.lib.cmdline.Label?> =
        java.util.ArrayList<com.google.devtools.build.lib.cmdline.Label?>()
    private val extraStarlarkOptionsSecond: SequencedMap<com.google.devtools.build.lib.cmdline.Label?, Any?> =
        LinkedHashMap<com.google.devtools.build.lib.cmdline.Label?, Any?>()

    private var hasStarlarkOptions = false

    @com.google.common.annotations.VisibleForTesting
    fun getExtraFirstFragmentClassesForTesting(): MutableSet<java.lang.Class<out FragmentOptions?>?> {
        return extraFirstFragments
    }

    @com.google.common.annotations.VisibleForTesting
    fun getExtraSecondFragmentsForTesting(): MutableSet<FragmentOptions?> {
        return extraSecondFragments
    }

    fun getFirst(): MutableMap<com.google.devtools.common.options.OptionDefinition?, Any?> {
        return first
    }

    fun getSecond(): com.google.common.collect.Multimap<com.google.devtools.common.options.OptionDefinition?, Any?> {
        return second
    }

    private fun addDiff(
        fragmentOptionsClass: java.lang.Class<out FragmentOptions?>?,
        option: com.google.devtools.common.options.OptionDefinition?,
        firstValue: Any?,
        secondValue: Any?
    ) {
        differingOptions.put(fragmentOptionsClass, option)
        first.put(option, firstValue)
        second.put(option, secondValue)
    }

    private fun addExtraFirstFragment(options: java.lang.Class<out FragmentOptions?>?) {
        extraFirstFragments.add(options)
    }

    private fun addExtraSecondFragment(options: FragmentOptions?) {
        extraSecondFragments.add(options)
    }

    private fun putStarlarkDiff(
        buildSetting: com.google.devtools.build.lib.cmdline.Label?,
        firstValue: Any?,
        secondValue: Any?
    ) {
        starlarkFirst.put(buildSetting, firstValue)
        starlarkSecond.put(buildSetting, secondValue)
        hasStarlarkOptions = true
    }

    private fun addExtraFirstStarlarkOption(buildSetting: com.google.devtools.build.lib.cmdline.Label?) {
        extraStarlarkOptionsFirst.add(buildSetting)
        hasStarlarkOptions = true
    }

    private fun addExtraSecondStarlarkOption(buildSetting: com.google.devtools.build.lib.cmdline.Label?, value: Any?) {
        extraStarlarkOptionsSecond.put(buildSetting, value)
        hasStarlarkOptions = true
    }

    /**
     * Returns the labels of all starlark options that caused a difference between the first and
     * second options set.
     */
    fun getChangedStarlarkOptions(): com.google.common.collect.ImmutableSet<com.google.devtools.build.lib.cmdline.Label?> {
        return com.google.common.collect.ImmutableSet.builder<com.google.devtools.build.lib.cmdline.Label?>()
            .addAll(starlarkFirst.keys)
            .addAll(starlarkSecond.keys)
            .addAll(extraStarlarkOptionsFirst)
            .addAll(extraStarlarkOptionsSecond.keys)
            .build()
    }

    @com.google.common.annotations.VisibleForTesting
    fun getStarlarkFirstForTesting(): MutableMap<com.google.devtools.build.lib.cmdline.Label?, Any?> {
        return starlarkFirst
    }

    @com.google.common.annotations.VisibleForTesting
    fun getStarlarkSecondForTesting(): MutableMap<com.google.devtools.build.lib.cmdline.Label?, Any?> {
        return starlarkSecond
    }

    @com.google.common.annotations.VisibleForTesting
    fun getExtraStarlarkOptionsFirstForTesting(): MutableList<com.google.devtools.build.lib.cmdline.Label?> {
        return extraStarlarkOptionsFirst
    }

    @com.google.common.annotations.VisibleForTesting
    fun getExtraStarlarkOptionsSecondForTesting(): MutableMap<com.google.devtools.build.lib.cmdline.Label?, Any?> {
        return extraStarlarkOptionsSecond
    }

    /**
     * Note: it's not enough for first and second to be empty, with trimming, they must also contain
     * the same options classes.
     */
    fun areSame(): Boolean {
        return first.isEmpty()
                && second.isEmpty()
                && extraSecondFragments.isEmpty()
                && extraFirstFragments.isEmpty()
                && differingOptions.isEmpty()
                && starlarkFirst.isEmpty()
                && starlarkSecond.isEmpty()
                && extraStarlarkOptionsFirst.isEmpty()
                && extraStarlarkOptionsSecond.isEmpty()
    }

    fun prettyPrint(): String {
        val toReturn: java.lang.StringBuilder = java.lang.StringBuilder()
        for (diff in getPrettyPrintList()) {
            toReturn.append(diff).append("\n")
        }
        return toReturn.toString()
    }

    fun getPrettyPrintList(): MutableList<String?> {
        val toReturn: MutableList<String?> = java.util.ArrayList<String?>()
        first.forEach { (option: com.google.devtools.common.options.OptionDefinition?, value: Any?) ->
            toReturn.add(
                option.getOptionName() + ":" + value + " -> " + second.get(option)
            )
        }
        starlarkFirst.forEach { (option: com.google.devtools.build.lib.cmdline.Label?, value: Any?) ->
            toReturn.add(
                option.toString() + ":" + value + starlarkSecond.get(option)
            )
        }
        return toReturn
    }

    companion object {
        /** Returns the difference between two BuildOptions in a new [OptionsDiff].  */
        fun diff(first: BuildOptions?, second: BuildOptions?): OptionsDiff {
            return diff(OptionsDiff(), first, second)
        }

        /**
         * Returns the difference between two BuildOptions in a pre-existing [OptionsDiff].
         * 
         * 
         * In a single pass through this method, the method can only compare a single "first" [ ] and single "second" BuildOptions; but an OptionsDiff instance can store the diff
         * between a single "first" BuildOptions and multiple "second" BuildOptions. Being able to
         * maintain a single OptionsDiff over multiple calls to diff is useful for, for example,
         * aggregating the difference between a single BuildOptions and the results of applying a [ ]) to it.
         */
        // See comment above == comparison.
        fun diff(diff: OptionsDiff, first: BuildOptions?, second: BuildOptions?): OptionsDiff {
            com.google.common.base.Preconditions.checkArgument(
                !diff.hasStarlarkOptions,
                "OptionsDiff cannot handle multiple 'second' BuildOptions with Starlark options and is"
                        + " trying to diff against %s",
                diff
            )
            com.google.common.base.Preconditions.checkNotNull<Any?>(first)
            com.google.common.base.Preconditions.checkNotNull<Any?>(second)
            if (first.equals(second)) {
                return diff
            }

            // Check and report if either class has been trimmed of an options class that exists in the
            // other.
            val firstOptionClasses: com.google.common.collect.ImmutableSet<java.lang.Class<out FragmentOptions?>?> =
                first.getNativeOptions().stream()
                    .map(FragmentOptions::getOptionsClass)
                    .collect(com.google.common.collect.ImmutableSet.toImmutableSet<E?>())
            val secondOptionClasses: com.google.common.collect.ImmutableSet<java.lang.Class<out FragmentOptions?>?> =
                second.getNativeOptions().stream()
                    .map(FragmentOptions::getOptionsClass)
                    .collect(com.google.common.collect.ImmutableSet.toImmutableSet<E?>())
            com.google.common.collect.Sets.difference<java.lang.Class<out FragmentOptions?>?>(
                firstOptionClasses,
                secondOptionClasses
            ).forEach(java.util.function.Consumer { options: java.lang.Class<out FragmentOptions?>? ->
                diff.addExtraFirstFragment(options)
            })
            com.google.common.collect.Sets.difference<java.lang.Class<out FragmentOptions?>?>(
                secondOptionClasses,
                firstOptionClasses
            ).stream()
                .map<Any?>(second::get)
                .forEach { options: Any? -> diff.addExtraSecondFragment(options) }

            // For fragments in common, report differences.
            for (clazz in com.google.common.collect.Sets.intersection<java.lang.Class<out FragmentOptions?>?>(
                firstOptionClasses,
                secondOptionClasses
            )) {
                val firstOptions: FragmentOptions? = first.get(clazz)
                val secondOptions: FragmentOptions? = second.get(clazz)
                // We avoid calling #equals because we are going to do a field-by-field comparison anyway.
                if (firstOptions === secondOptions) {
                    continue
                }
                for (definition in com.google.devtools.common.options.OptionDefinition.getOptionDefinitions(clazz)) {
                    val firstValue: Any? = definition.getValue(firstOptions)
                    val secondValue: Any? = definition.getValue(secondOptions)
                    if (firstValue != secondValue) {
                        diff.addDiff(clazz, definition, firstValue, secondValue)
                    }
                }
            }

            // Compare Starlark options for the two classes.
            val starlarkFirst: com.google.common.collect.ImmutableMap<com.google.devtools.build.lib.cmdline.Label?, Any?> =
                first.getStarlarkOptions()
            val starlarkSecond: com.google.common.collect.ImmutableMap<com.google.devtools.build.lib.cmdline.Label?, Any?> =
                second.getStarlarkOptions()
            for (buildSetting in com.google.common.collect.Sets.union<com.google.devtools.build.lib.cmdline.Label?>(
                starlarkFirst.keys,
                starlarkSecond.keys
            )) {
                if (starlarkFirst.get(buildSetting) == null) {
                    diff.addExtraSecondStarlarkOption(buildSetting, starlarkSecond.get(buildSetting))
                } else if (starlarkSecond.get(buildSetting) == null) {
                    diff.addExtraFirstStarlarkOption(buildSetting)
                } else if (starlarkFirst.get(buildSetting) != starlarkSecond.get(buildSetting)) {
                    diff.putStarlarkDiff(
                        buildSetting, starlarkFirst.get(buildSetting), starlarkSecond.get(buildSetting)
                    )
                }
            }
            return diff
        }
    }
}
