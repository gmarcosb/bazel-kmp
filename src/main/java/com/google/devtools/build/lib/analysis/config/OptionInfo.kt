// Copyright 2022 The Bazel Authors. All rights reserved.
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

/** Stores information about a build option gathered via reflection.  */
class OptionInfo private constructor(
    optionClass: java.lang.Class<out FragmentOptions?>?,
    definition: com.google.devtools.common.options.OptionDefinition?
) {
    private val optionClass: java.lang.Class<out FragmentOptions?>?
    private val definition: com.google.devtools.common.options.OptionDefinition?

    init {
        this.optionClass = optionClass
        this.definition = definition
    }

    fun getOptionClass(): java.lang.Class<out FragmentOptions?>? {
        return optionClass
    }

    fun getDefinition(): com.google.devtools.common.options.OptionDefinition? {
        return definition
    }

    fun hasOptionMetadataTag(tag: com.google.devtools.common.options.OptionMetadataTag): Boolean {
        return java.util.Arrays.stream<com.google.devtools.common.options.OptionMetadataTag?>(getDefinition().getOptionMetadataTags())
            .anyMatch { other: com.google.devtools.common.options.OptionMetadataTag? -> tag.equals(other) }
    }

    companion object {
        /** For all the options in the BuildOptions, build a map from option name to its information.  */
        fun buildMapFrom(buildOptions: BuildOptions): com.google.common.collect.ImmutableMap<String?, OptionInfo?> {
            val builder: com.google.common.collect.ImmutableMap.Builder<String?, OptionInfo?> =
                com.google.common.collect.ImmutableMap.Builder<String?, OptionInfo?>()

            val optionClasses: com.google.common.collect.ImmutableSet<java.lang.Class<out FragmentOptions?>?> =
                buildOptions.getNativeOptions().stream()
                    .map(FragmentOptions::getOptionsClass)
                    .collect(com.google.common.collect.ImmutableSet.toImmutableSet<E?>())

            for (optionClass in optionClasses) {
                val optionDefinitions: com.google.common.collect.ImmutableList<out com.google.devtools.common.options.OptionDefinition> =
                    com.google.devtools.common.options.OptionDefinition.getOptionDefinitions(optionClass)
                for (def in optionDefinitions) {
                    val optionName: String? = def.getOptionName()
                    builder.put(optionName, com.google.devtools.build.lib.analysis.config.OptionInfo(optionClass, def))
                }
            }

            return builder.build()
        }
    }
}
