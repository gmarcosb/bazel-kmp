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

import java.util.stream.Collectors

/**
 * The value of an option.
 * 
 * 
 * This takes care of tracking the final value as multiple instances of an option are parsed.
 */
abstract class OptionValueDescription(
    optionDefinition: com.google.devtools.common.options.OptionDefinition,
    conversionContext: Any?
) {
    protected val optionDefinition: com.google.devtools.common.options.OptionDefinition
    protected val conversionContext: Any?

    init {
        this.optionDefinition = optionDefinition
        this.conversionContext = conversionContext
    }

    fun getOptionDefinition(): com.google.devtools.common.options.OptionDefinition {
        return optionDefinition
    }

    /** Returns the current or final value of this option.  */
    abstract fun getValue(): Any?

    abstract fun containsErrors(): Boolean

    /** Returns the source(s) of this option, if there were multiple, duplicates are removed.  */
    abstract fun getSourceString(): String?

    /**
     * Add an instance of the option to this value. The various types of options are in charge of
     * making sure that the value is correctly stored, with proper tracking of its priority and
     * placement amongst other options.
     * 
     * @return a bundle containing arguments that need to be parsed further.
     */
    @Throws(com.google.devtools.common.options.OptionsParsingException::class)
    abstract fun addOptionInstance(
        parsedOption: com.google.devtools.common.options.ParsedOptionDescription?, warnings: MutableSet<String?>?
    ): ExpansionBundle?

    /**
     * Grouping of convenience for the options that expand to other options, to attach an
     * option-appropriate source string along with the options that need to be parsed.
     */
    class ExpansionBundle(var expansionArgs: MutableList<String?>?, var sourceOfExpansionArgs: String?)

    /**
     * Returns the canonical instances of this option - the instances that affect the current value.
     * 
     * 
     * For options that do not have values in their own right, this should be the empty list. In
     * contrast, the DefaultOptionValue does not have a canonical form at all, since it was never set,
     * and is null.
     */
    abstract fun getCanonicalInstances(): MutableList<com.google.devtools.common.options.ParsedOptionDescription?>?

    private class DefaultOptionValueDescription(
        optionDefinition: com.google.devtools.common.options.OptionDefinition,
        conversionContext: Any?
    ) : OptionValueDescription(optionDefinition, conversionContext) {
        override fun getValue(): Any? {
            return optionDefinition.getDefaultValue(conversionContext)
        }

        override fun containsErrors(): Boolean {
            return false
        }

        override fun getSourceString(): String? {
            return null
        }

        override fun addOptionInstance(
            parsedOption: com.google.devtools.common.options.ParsedOptionDescription?,
            warnings: MutableSet<String?>?
        ): ExpansionBundle? {
            throw java.lang.IllegalStateException(
                "Cannot add values to the default option value. Create a modifiable "
                        + "OptionValueDescription using createOptionValueDescription() instead."
            )
        }

        override fun getCanonicalInstances(): com.google.common.collect.ImmutableList<com.google.devtools.common.options.ParsedOptionDescription?>? {
            return null
        }
    }

    /**
     * The form of a value for a default type of flag, one that does not accumulate multiple values
     * and has no expansion.
     */
    private open class SingleOptionValueDescription(
        optionDefinition: com.google.devtools.common.options.OptionDefinition,
        conversionContext: Any?
    ) : OptionValueDescription(optionDefinition, conversionContext) {
        private var effectiveOptionInstance: com.google.devtools.common.options.ParsedOptionDescription?
        private var effectiveValue: Any?
        private var containsErrors: Boolean

        init {
            if (optionDefinition.allowsMultiple()) {
                throw com.google.devtools.common.options.ConstructionException("Can't have a single value for an allowMultiple option.")
            }
            if (optionDefinition.isExpansionOption()) {
                throw com.google.devtools.common.options.ConstructionException("Can't have a single value for an expansion option.")
            }
            effectiveOptionInstance = null
            effectiveValue = null
            containsErrors = false
        }

        override fun getValue(): Any? {
            return effectiveValue
        }

        override fun containsErrors(): Boolean {
            return containsErrors
        }

        override fun getSourceString(): String? {
            return effectiveOptionInstance.getSource()
        }

        @Throws(com.google.devtools.common.options.OptionsParsingException::class)
        fun convertValue(parsedOption: com.google.devtools.common.options.ParsedOptionDescription): Any? {
            try {
                return parsedOption.getConvertedValue()
            } catch (e: com.google.devtools.common.options.OptionsParsingException) {
                containsErrors = true
                throw e
            }
        }

        // Warnings should not end with a '.' because the internal reporter adds one automatically.
        @Throws(com.google.devtools.common.options.OptionsParsingException::class)
        override fun addOptionInstance(
            parsedOption: com.google.devtools.common.options.ParsedOptionDescription,
            warnings: MutableSet<String?>
        ): ExpansionBundle? {
            // This might be the first value, in that case, just store it!
            if (effectiveOptionInstance == null) {
                effectiveOptionInstance = parsedOption
                effectiveValue = convertValue(parsedOption)
                return null
            }

            // If there was another value, check whether the new one will override it, and if so,
            // log warnings describing the change.
            if (parsedOption.getPriority().compareTo(effectiveOptionInstance.getPriority()) >= 0) {
                // Identify the option that might have led to the current and new value of this option.
                val implicitDependent: com.google.devtools.common.options.ParsedOptionDescription? =
                    parsedOption.getImplicitDependent()
                val expandedFrom: com.google.devtools.common.options.ParsedOptionDescription? =
                    parsedOption.getExpandedFrom()
                val optionThatDependsOnEffectiveValue: com.google.devtools.common.options.ParsedOptionDescription? =
                    effectiveOptionInstance.getImplicitDependent()
                val optionThatExpandedToEffectiveValue: com.google.devtools.common.options.ParsedOptionDescription? =
                    effectiveOptionInstance.getExpandedFrom()

                val newValue = convertValue(parsedOption)
                // Output warnings if there is conflicting options set different values in a way that might
                // not have been obvious to the user, such as through expansions and implicit requirements.
                if (effectiveValue != null && effectiveValue != newValue) {
                    val samePriorityCategory =
                        (parsedOption
                            .getPriority()
                            .getPriorityCategory()
                                == effectiveOptionInstance.getPriority().getPriorityCategory())
                    if ((implicitDependent != null) && (optionThatDependsOnEffectiveValue != null)) {
                        if (implicitDependent != optionThatDependsOnEffectiveValue) {
                            warnings.add(
                                java.lang.String.format(
                                    "%s is implicitly defined by both %s and %s",
                                    optionDefinition, optionThatDependsOnEffectiveValue, implicitDependent
                                )
                            )
                        }
                    } else if ((implicitDependent != null) && samePriorityCategory) {
                        warnings.add(
                            java.lang.String.format(
                                "%s is implicitly defined by %s; the implicitly set value "
                                        + "overrides the previous one",
                                optionDefinition, implicitDependent
                            )
                        )
                    } else if (optionThatDependsOnEffectiveValue != null) {
                        warnings.add(
                            java.lang.String.format(
                                "A new value for %s overrides a previous implicit setting of that "
                                        + "option by %s",
                                optionDefinition, optionThatDependsOnEffectiveValue
                            )
                        )
                    } else if (samePriorityCategory
                        && (parsedOption
                            .getPriority()
                            .getPriorityCategory()
                                == com.google.devtools.common.options.OptionPriority.PriorityCategory.COMMAND_LINE)
                        && ((optionThatExpandedToEffectiveValue == null) && (expandedFrom != null))
                    ) {
                        // Create a warning if an expansion option overrides an explicit option:
                        warnings.add(
                            java.lang.String.format(
                                "%s was expanded and now overrides the explicit option %s with %s",
                                expandedFrom,
                                effectiveOptionInstance.getCommandLineForm(),
                                parsedOption.getCommandLineForm()
                            )
                        )
                    } else if ((optionThatExpandedToEffectiveValue != null)
                        && (expandedFrom != null)
                        && !(samePriorityCategory
                                && (parsedOption
                            .getPriority()
                            .getPriorityCategory()
                                == com.google.devtools.common.options.OptionPriority.PriorityCategory.RC_FILE))
                    ) {
                        warnings.add(
                            java.lang.String.format(
                                "%s was expanded from both %s and %s",
                                optionDefinition, optionThatExpandedToEffectiveValue, expandedFrom
                            )
                        )
                    }
                }

                // Record the new value:
                effectiveOptionInstance = parsedOption
                effectiveValue = newValue
            }
            return null
        }

        override fun getCanonicalInstances(): com.google.common.collect.ImmutableList<com.google.devtools.common.options.ParsedOptionDescription?>? {
            // If the current option is an implicit requirement, we don't need to list this value since
            // the parent implies it. In this case, it is sufficient to not list this value at all.
            if (effectiveOptionInstance.getImplicitDependent() == null) {
                return com.google.common.collect.ImmutableList.of<com.google.devtools.common.options.ParsedOptionDescription?>(
                    effectiveOptionInstance
                )
            }
            return com.google.common.collect.ImmutableList.of<com.google.devtools.common.options.ParsedOptionDescription?>()
        }
    }

    /** The form of a value for an option that accumulates multiple values on the command line.  */
    private class RepeatableOptionValueDescription(
        optionDefinition: com.google.devtools.common.options.OptionDefinition,
        conversionContext: Any?
    ) : OptionValueDescription(optionDefinition, conversionContext) {
        private val parsedOptions: com.google.common.collect.ListMultimap<com.google.devtools.common.options.OptionPriority?, com.google.devtools.common.options.ParsedOptionDescription?>
        private val optionValues: com.google.common.collect.ListMultimap<com.google.devtools.common.options.OptionPriority?, Any?>
        private var containsErrors: Boolean

        init {
            if (!optionDefinition.allowsMultiple()) {
                throw com.google.devtools.common.options.ConstructionException(
                    "Can't have a repeated value for a non-allowMultiple option."
                )
            }
            parsedOptions =
                com.google.common.collect.ArrayListMultimap.create<com.google.devtools.common.options.OptionPriority?, com.google.devtools.common.options.ParsedOptionDescription?>()
            optionValues =
                com.google.common.collect.ArrayListMultimap.create<com.google.devtools.common.options.OptionPriority?, Any?>()
            containsErrors = false
        }

        override fun getSourceString(): String? {
            return parsedOptions.asMap().entrySet().stream()
                .sorted(java.util.Map.Entry.comparingByKey<com.google.devtools.common.options.OptionPriority?, MutableCollection<com.google.devtools.common.options.ParsedOptionDescription?>?>())
                .map<MutableCollection<com.google.devtools.common.options.ParsedOptionDescription?>?>(java.util.function.Function { java.util.Map.Entry.getValue() })
                .flatMap<com.google.devtools.common.options.ParsedOptionDescription?>(java.util.function.Function { obj: MutableCollection<com.google.devtools.common.options.ParsedOptionDescription?>? -> obj.stream() })
                .map<String?>(java.util.function.Function { obj: com.google.devtools.common.options.ParsedOptionDescription? -> obj.getSource() })
                .distinct()
                .collect(Collectors.joining(", "))
        }

        override fun getValue(): com.google.common.collect.ImmutableList<Any?> {
            // Sort the results by option priority and return them in a new list. The generic type of
            // the list is not known at runtime, so we can't use it here.
            return optionValues.asMap().entrySet().stream()
                .sorted(java.util.Map.Entry.comparingByKey<com.google.devtools.common.options.OptionPriority?, MutableCollection<Any?>?>())
                .map<MutableCollection<Any?>?>(java.util.function.Function { java.util.Map.Entry.getValue() })
                .flatMap<Any?>(java.util.function.Function { obj: MutableCollection<Any?>? -> obj.stream() })
                .collect(com.google.common.collect.ImmutableList.toImmutableList<Any?>())
        }

        override fun containsErrors(): Boolean {
            return containsErrors
        }

        @Throws(com.google.devtools.common.options.OptionsParsingException::class)
        override fun addOptionInstance(
            parsedOption: com.google.devtools.common.options.ParsedOptionDescription,
            warnings: MutableSet<String?>?
        ): ExpansionBundle? {
            // For repeatable options, we allow flags that take both single values and multiple values,
            // potentially collapsing them down.
            val convertedValue: Any?

            try {
                convertedValue = parsedOption.getConvertedValue()
            } catch (e: com.google.devtools.common.options.OptionsParsingException) {
                containsErrors = true
                throw e
            }

            val priority: com.google.devtools.common.options.OptionPriority? = parsedOption.getPriority()
            parsedOptions.put(priority, parsedOption)
            if (convertedValue is MutableList<*>) {
                optionValues.putAll(priority, convertedValue)
            } else {
                optionValues.put(priority, convertedValue)
            }
            return null
        }

        override fun getCanonicalInstances(): com.google.common.collect.ImmutableList<com.google.devtools.common.options.ParsedOptionDescription?>? {
            return parsedOptions.asMap().entrySet().stream()
                .sorted(java.util.Map.Entry.comparingByKey<com.google.devtools.common.options.OptionPriority?, MutableCollection<com.google.devtools.common.options.ParsedOptionDescription?>?>())
                .map<MutableCollection<com.google.devtools.common.options.ParsedOptionDescription?>?>(java.util.function.Function { java.util.Map.Entry.getValue() })
                .flatMap<com.google.devtools.common.options.ParsedOptionDescription?>(java.util.function.Function { obj: MutableCollection<com.google.devtools.common.options.ParsedOptionDescription?>? -> obj.stream() }) // Only provide the options that aren't implied elsewhere.
                .filter(java.util.function.Predicate { optionDesc: com.google.devtools.common.options.ParsedOptionDescription? -> optionDesc.getImplicitDependent() == null })
                .collect(com.google.common.collect.ImmutableList.toImmutableList<com.google.devtools.common.options.ParsedOptionDescription?>())
        }
    }

    /**
     * The form of a value for an expansion option, one that does not have its own value but expands
     * in place to other options. This should be used for flags with anN expansion defined in [ ][Option.expansion].
     */
    private class ExpansionOptionValueDescription(
        optionDefinition: com.google.devtools.common.options.OptionDefinition,
        optionsData: com.google.devtools.common.options.OptionsData,
        conversionContext: Any?
    ) : OptionValueDescription(optionDefinition, conversionContext) {
        private val expansion: MutableList<String?>?

        init {
            this.expansion = optionsData.getEvaluatedExpansion(optionDefinition)
            if (!optionDefinition.isExpansionOption()) {
                throw com.google.devtools.common.options.ConstructionException(
                    "Options without expansions can't be tracked using ExpansionOptionValueDescription"
                )
            }
        }

        override fun getValue(): Any? {
            return null
        }

        override fun containsErrors(): Boolean {
            return false
        }

        override fun getSourceString(): String? {
            return null
        }

        override fun addOptionInstance(
            parsedOption: com.google.devtools.common.options.ParsedOptionDescription,
            warnings: MutableSet<String?>
        ): ExpansionBundle {
            if (parsedOption.getUnconvertedValue() != null
                && !parsedOption.getUnconvertedValue().isEmpty()
            ) {
                warnings.add(
                    java.lang.String.format(
                        "%s is an expansion option. It does not accept values, and does not change its "
                                + "expansion based on the value provided. Value '%s' will be ignored.",
                        optionDefinition, parsedOption.getUnconvertedValue()
                    )
                )
            }

            return com.google.devtools.common.options.OptionValueDescription.ExpansionBundle(
                expansion,
                if (parsedOption.getSource() == null)
                    java.lang.String.format("expanded from %s", optionDefinition)
                else
                    java.lang.String.format(
                        "expanded from %s (source %s)", optionDefinition, parsedOption.getSource()
                    )
            )
        }

        override fun getCanonicalInstances(): com.google.common.collect.ImmutableList<com.google.devtools.common.options.ParsedOptionDescription?> {
            // The options this expands to are incorporated in their own right - this option does
            // not have a canonical form.
            return com.google.common.collect.ImmutableList.of<com.google.devtools.common.options.ParsedOptionDescription?>()
        }
    }

    /** The form of a value for a flag with implicit requirements.  */
    private class OptionWithImplicitRequirementsValueDescription
        (optionDefinition: com.google.devtools.common.options.OptionDefinition, conversionContext: Any?) :
        SingleOptionValueDescription(optionDefinition, conversionContext) {
        init {
            if (!optionDefinition.hasImplicitRequirements()) {
                throw com.google.devtools.common.options.ConstructionException(
                    "Options without implicit requirements can't be tracked using "
                            + "OptionWithImplicitRequirementsValueDescription"
                )
            }
        }

        @Throws(com.google.devtools.common.options.OptionsParsingException::class)
        override fun addOptionInstance(
            parsedOption: com.google.devtools.common.options.ParsedOptionDescription,
            warnings: MutableSet<String?>
        ): ExpansionBundle {
            // This is a valued flag, its value is handled the same way as a normal
            // SingleOptionValueDescription. (We check at compile time that these flags aren't
            // "allowMultiple")
            val superExpansion = super.addOptionInstance(parsedOption, warnings)
            com.google.common.base.Preconditions.checkArgument(
                superExpansion == null, "SingleOptionValueDescription should not expand to anything."
            )
            if (parsedOption
                    .getConvertedValue()
                == optionDefinition.getDefaultValue(conversionContext)
            ) {
                warnings.add(
                    java.lang.String.format(
                        ("%s sets %s to its default value. Since this option has implicit requirements that "
                                + "are set whenever the option is explicitly provided, regardless of the "
                                + "value, this will behave differently than letting a default be a default. "
                                + "Specifically, this options expands to {%s}."),
                        parsedOption.getCommandLineForm(),
                        optionDefinition,
                        java.lang.String.join(" ", *optionDefinition.getImplicitRequirements())
                    )
                )
            }

            // Now deal with the implicit requirements.
            return com.google.devtools.common.options.OptionValueDescription.ExpansionBundle(
                com.google.common.collect.ImmutableList.copyOf<String?>(optionDefinition.getImplicitRequirements()),
                if (parsedOption.getSource() == null)
                    java.lang.String.format("implicit requirement of %s", optionDefinition)
                else
                    java.lang.String.format(
                        "implicit requirement of %s (source %s)",
                        optionDefinition, parsedOption.getSource()
                    )
            )
        }
    }

    companion object {
        /**
         * For the given option, returns the correct type of OptionValueDescription, to which unparsed
         * values can be added.
         * 
         * 
         * The categories of option types are non-overlapping, an invariant checked by the
         * OptionProcessor at compile time.
         */
        fun createOptionValueDescription(
            option: com.google.devtools.common.options.OptionDefinition,
            optionsData: com.google.devtools.common.options.OptionsData,
            conversionContext: Any?
        ): OptionValueDescription {
            if (option.isExpansionOption()) {
                return com.google.devtools.common.options.OptionValueDescription.ExpansionOptionValueDescription(
                    option,
                    optionsData,
                    conversionContext
                )
            } else if (option.allowsMultiple()) {
                return com.google.devtools.common.options.OptionValueDescription.RepeatableOptionValueDescription(
                    option,
                    conversionContext
                )
            } else if (option.hasImplicitRequirements()) {
                return com.google.devtools.common.options.OptionValueDescription.OptionWithImplicitRequirementsValueDescription(
                    option,
                    conversionContext
                )
            } else {
                return com.google.devtools.common.options.OptionValueDescription.SingleOptionValueDescription(
                    option,
                    conversionContext
                )
            }
        }

        /**
         * For options that have not been set, this will return a correct OptionValueDescription for the
         * default value.
         */
        fun getDefaultOptionValue(
            option: com.google.devtools.common.options.OptionDefinition, conversionContext: Any?
        ): OptionValueDescription {
            return com.google.devtools.common.options.OptionValueDescription.DefaultOptionValueDescription(
                option,
                conversionContext
            )
        }
    }
}
