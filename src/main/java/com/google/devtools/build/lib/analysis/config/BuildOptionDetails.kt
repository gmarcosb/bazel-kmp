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
package com.google.devtools.build.lib.analysis.config

import com.google.devtools.common.options.OptionsParser.getOptionDefinitionByName

/**
 * Maps build option names as they appear to the user (e.g. `compilation_mode`) to structured
 * metadata.
 * 
 * 
 * For native options (`@Option` defined in a [FragmentOptions] implementation), this
 * tracks:
 * 
 * 
 *  * what [FragmentOptions] class defines the option
 *  * the option's current value
 *  * whether it allows multiple values to be specified ([Option.allowMultiple]
 *  * whether it is selectable, i.e., allowed to appear in a `config_setting`
 * 
 * 
 * 
 * For Starlark options (defined in a Starlark `build_setting`), this tracks their value in
 * built-in Starlark-object form (post-parse, pre-implementation function form).
 */
class BuildOptionDetails private constructor(
    nativeOptionsMap: com.google.common.collect.ImmutableMap<String?, OptionDetails?>,
    oldNameToCanonicalName: com.google.common.collect.ImmutableMap<String?, String?>,
    starlarkOptionsMap: com.google.common.collect.ImmutableMap<Label?, Any?>
) {
    private class OptionDetails(
        optionsClass: java.lang.Class<out FragmentOptions?>?,
        value: Any?,
        allowsMultiple: Boolean
    ) {
        /** The [FragmentOptions] class that defines this option.  */
        private val optionsClass: java.lang.Class<out FragmentOptions?>?

        /** The value of the given option (either explicitly defined or default). May be null.  */
        private val value: Any?

        /** Whether or not this option supports multiple values.  */
        private val allowsMultiple: Boolean

        init {
            this.optionsClass = optionsClass
            this.value = value
            this.allowsMultiple = allowsMultiple
        }
    }

    /**
     * Maps native option names to the [OptionDetails] the option takes for this configuration.
     * 
     * 
     * This can be used to:
     * 
     * 
     *  1. Find an option's (parsed) value given its command-line name
     *  1. Parse alternative values for the option.
     * 
     */
    private val nativeOptionsMap: com.google.common.collect.ImmutableMap<String?, OptionDetails?>

    /**
     * For options with [Option.oldName], maps the old name to the canonical name. Options
     * with no old name aren't in this map.
     */
    private val oldNameToCanonicalName: com.google.common.collect.ImmutableMap<String?, String?>

    /** Maps Starlark option labels to values  */
    private val starlarkOptionsMap: com.google.common.collect.ImmutableMap<Label?, Any?>

    init {
        this.nativeOptionsMap = nativeOptionsMap
        this.oldNameToCanonicalName = oldNameToCanonicalName
        this.starlarkOptionsMap = starlarkOptionsMap
    }

    /**
     * Returns the [FragmentOptions] class the defines the given option, null if the option
     * isn't recognized.
     * 
     * 
     * optionName is the name of the option as it appears on the command line e.g. [ ][OptionDefinition.getOptionName]).
     */
    fun getOptionClass(optionName: String?): java.lang.Class<out FragmentOptions?>? {
        val optionDetails: OptionDetails? = nativeOptionsMap.get(optionName)
        return if (optionDetails == null) null else optionDetails.optionsClass
    }

    /**
     * Returns the value of the specified native option for this configuration or null if the option
     * isn't recognized. Since an option's legitimate value could be null, use [.getOptionClass]
     * to distinguish between that and an unknown option.
     * 
     * 
     * optionName is the name of the option as it appears on the command line e.g. [ ][OptionDefinition.getOptionName]).
     */
    fun getOptionValue(optionName: String?): Any? {
        val optionDetails: OptionDetails? = nativeOptionsMap.get(optionName)
        return if (optionDetails == null) null else optionDetails.value
    }

    /** Returns the value of the specified Starlark option or null if it isn't recognized  */
    fun getOptionValue(optionName: Label?): Any? {
        return starlarkOptionsMap.get(optionName)
    }

    /**
     * If this is an [Option.oldName] alias for a canonical option name, returns the canonical
     * name. Else returns the original name (since there's only one).
     */
    fun getCanonicalName(optionName: String?): String? {
        return oldNameToCanonicalName.getOrDefault(optionName, optionName)
    }

    /**
     * Returns whether or not the given option supports multiple values at the command line (e.g.
     * "--myoption value1 --myOption value2 ..."). Returns false for unrecognized options. Use [ ][.getOptionClass] to distinguish between those and legitimate single-value options.
     * 
     * 
     * As declared in [OptionDefinition.allowsMultiple], multi-value options are expected
     * to be of type `List<T>`.
     */
    fun allowsMultipleValues(optionName: String?): Boolean {
        val optionDetails: OptionDetails? = nativeOptionsMap.get(optionName)
        return optionDetails != null && optionDetails.allowsMultiple
    }

    fun isNonConfigurable(optionName: String?): Boolean {
        val optionDetails: OptionDetails? = nativeOptionsMap.get(optionName)
        if (optionDetails == null) {
            return false
        }
        val optionDefinition: OptionDefinition? =
            getOptionDefinitionByName(optionDetails.optionsClass, optionName)
        if (optionDefinition == null) {
            return false
        }
        return stream(optionDefinition.getOptionMetadataTags())
            .anyMatch(OptionMetadataTag.NON_CONFIGURABLE::equals)
    }

    companion object {
        /** Builds a `BuildOptionDetails` for the given set of native options  */
        @com.google.common.annotations.VisibleForTesting
        fun forOptionsForTesting(
            buildOptions: Iterable<out FragmentOptions>
        ): BuildOptionDetails {
            return forOptions(buildOptions, com.google.common.collect.ImmutableMap.of<Label?, Any?>())
        }

        /** Builds a `BuildOptionDetails` for the given set of native and Starlark options.  */
        fun forOptions(
            buildOptions: Iterable<out FragmentOptions>, starlarkOptions: MutableMap<Label?, Any?>
        ): BuildOptionDetails {
            val map: com.google.common.collect.ImmutableMap.Builder<String?, OptionDetails?> =
                com.google.common.collect.ImmutableMap.builder<String?, OptionDetails?>()
            val oldNameToCanonicalName: com.google.common.collect.ImmutableMap.Builder<String?, String?> =
                com.google.common.collect.ImmutableMap.builder<String?, String?>()
            for (options in buildOptions) {
                val optionDefinitions: com.google.common.collect.ImmutableList<out OptionDefinition> =
                    OptionDefinition.getOptionDefinitions(options.getOptionsClass())

                for (optionDefinition in optionDefinitions) {
                    if (com.google.common.collect.ImmutableList.copyOf(optionDefinition.getOptionMetadataTags())
                            .contains(OptionMetadataTag.INTERNAL)
                    ) {
                        // ignore internal options
                        continue
                    }
                    if (!optionDefinition.getOldOptionName().isEmpty()) {
                        oldNameToCanonicalName.put(
                            optionDefinition.getOldOptionName(), optionDefinition.getOptionName()
                        )
                    }
                    val value: Any? = optionDefinition.getValue(options)
                    map.put(
                        optionDefinition.getOptionName(),
                        OptionDetails(options.getOptionsClass(), value, optionDefinition.allowsMultiple())
                    )
                }
            }
            return BuildOptionDetails(
                map.buildOrThrow(),
                oldNameToCanonicalName.buildOrThrow(),
                com.google.common.collect.ImmutableMap.copyOf<Label?, Any?>(starlarkOptions)
            )
        }
    }
}
