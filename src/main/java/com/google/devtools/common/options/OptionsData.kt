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
package com.google.devtools.common.options

import com.google.devtools.build.lib.supplier.InterruptibleSupplier.get

/**
 * This extends IsolatedOptionsData with information that can only be determined once all the [ ] subclasses for a parser are known. In particular, this includes expansion
 * information.
 */
@javax.annotation.concurrent.Immutable
internal class OptionsData private constructor(
    base: com.google.devtools.common.options.IsolatedOptionsData,
    evaluatedExpansions: MutableMap<com.google.devtools.common.options.OptionDefinition?, com.google.common.collect.ImmutableList<String?>?>,
    /**
     * Whether this options data has been created with duplicate options definitions allowed as long
     * as those options are parsed (but not necessarily evaluated) equivalently.
     */
    private val allowDuplicatesParsingEquivalently: Boolean
) : com.google.devtools.common.options.IsolatedOptionsData(base) {
    /** Mapping from each option to the (unparsed) options it expands to, if any.  */
    private val evaluatedExpansions: com.google.common.collect.ImmutableMap<com.google.devtools.common.options.OptionDefinition?, com.google.common.collect.ImmutableList<String?>?>

    /** Construct [OptionsData] by extending an [IsolatedOptionsData] with new info.  */
    init {
        this.evaluatedExpansions =
            com.google.common.collect.ImmutableMap.copyOf<com.google.devtools.common.options.OptionDefinition?, com.google.common.collect.ImmutableList<String?>?>(
                evaluatedExpansions
            )
    }

    /**
     * Returns the expansion of an options field. If the field is not an expansion option returns an
     * empty array.
     */
    fun getEvaluatedExpansion(optionDefinition: com.google.devtools.common.options.OptionDefinition?): com.google.common.collect.ImmutableList<String?>? {
        val result: com.google.common.collect.ImmutableList<String?>? = evaluatedExpansions.get(optionDefinition)
        return if (result != null) result else com.google.devtools.common.options.OptionsData.Companion.EMPTY_EXPANSION
    }

    /**
     * Returns whether this options data has been created with duplicate options definitions allowed
     * as long as those options are parsed (but not necessarily evaluated) equivalently.
     */
    fun createdWithAllowDuplicatesParsingEquivalently(): Boolean {
        return allowDuplicatesParsingEquivalently
    }

    companion object {
        private val EMPTY_EXPANSION: com.google.common.collect.ImmutableList<String?> =
            com.google.common.collect.ImmutableList.of<String?>()

        /**
         * Constructs an [OptionsData] object for a parser that knows about the given [ ] classes. In addition to the work done to construct the [ ], this also computes expansion information. If an option has an expansion,
         * try to precalculate its here.
         */
        fun from(
            classes: MutableCollection<java.lang.Class<out com.google.devtools.common.options.OptionsBase?>?>,
            allowDuplicatesParsingEquivalently: Boolean
        ): OptionsData {
            val isolatedData: com.google.devtools.common.options.IsolatedOptionsData =
                com.google.devtools.common.options.IsolatedOptionsData.Companion.from(
                    classes,
                    allowDuplicatesParsingEquivalently
                )

            // All that's left is to compute expansions.
            val evaluatedExpansionsBuilder: com.google.common.collect.ImmutableMap.Builder<com.google.devtools.common.options.OptionDefinition?, com.google.common.collect.ImmutableList<String?>?> =
                com.google.common.collect.ImmutableMap.builder<com.google.devtools.common.options.OptionDefinition?, com.google.common.collect.ImmutableList<String?>?>()
            for (entry in isolatedData.getAllOptionDefinitions()) {
                val optionDefinition: com.google.devtools.common.options.OptionDefinition = entry.getValue()
                val constExpansion: Array<String?> = optionDefinition.getOptionExpansion()
                if (constExpansion.size > 0) {
                    evaluatedExpansionsBuilder.put(
                        optionDefinition,
                        com.google.common.collect.ImmutableList.copyOf<String?>(constExpansion)
                    )
                }
            }
            return com.google.devtools.common.options.OptionsData(
                isolatedData,
                evaluatedExpansionsBuilder.buildOrThrow(),
                allowDuplicatesParsingEquivalently
            )
        }
    }
}
