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
package com.google.devtools.common.options

import com.google.devtools.build.lib.supplier.InterruptibleSupplier.get
import java.util.HashMap
import java.util.LinkedHashSet
import java.util.stream.Collectors

/**
 * The implementation of the options parser. This is intentionally package private for full
 * flexibility. Use [OptionsParser] or [Options] if you're a consumer.
 */
internal class OptionsParserImpl(
    optionsData: com.google.devtools.common.options.OptionsData,
    argsPreProcessor: com.google.devtools.common.options.ArgsPreProcessor,
    skippedPrefixes: MutableList<String?>,
    ignoreInternalOptions: Boolean,
    aliasFlag: String?,
    conversionContext: Any?,
    aliases: MutableMap<String?, String>
) {
    /** Helper class to create a new instance of [OptionsParserImpl].  */
    internal class Builder {
        private var optionsData: com.google.devtools.common.options.OptionsData? = null
        private var argsPreProcessor: com.google.devtools.common.options.ArgsPreProcessor =
            com.google.devtools.common.options.ArgsPreProcessor { args: MutableList<com.google.devtools.common.options.OptionsParser.ArgAndFallbackData?>? -> args }
        private val skippedPrefixes: java.util.ArrayList<String?> = java.util.ArrayList<String?>()
        private var ignoreInternalOptions = true
        private var aliasFlag: String? = null
        private var conversionContext: Any? = null
        private val aliases: MutableMap<String?, String> = HashMap<String?, String>()

        /** Set the [OptionsData] to be used in this instance.  */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun optionsData(optionsData: com.google.devtools.common.options.OptionsData): Builder {
            this.optionsData = optionsData
            return this
        }

        /** Sets the [ArgsPreProcessor] to use during processing.  */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun argsPreProcessor(preProcessor: com.google.devtools.common.options.ArgsPreProcessor): Builder {
            this.argsPreProcessor = preProcessor
            return this
        }

        /** Any flags with this prefix will be skipped during processing.  */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun skippedPrefix(skippedPrefix: String?): Builder {
            this.skippedPrefixes.add(skippedPrefix)
            return this
        }

        /** Sets whether the parser should ignore internal-only options.  */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun ignoreInternalOptions(ignoreInternalOptions: Boolean): Builder {
            this.ignoreInternalOptions = ignoreInternalOptions
            return this
        }

        /**
         * Sets what flag the parser should use for flag aliasing. Defaults to null if not set,
         * effectively disabling the aliasing functionality.
         */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun withAliasFlag(aliasFlag: String?): Builder {
            if (aliasFlag != null) {
                this.aliasFlag = aliasFlag
            }
            return this
        }

        fun withConversionContext(conversionContext: Any?): Builder {
            this.conversionContext = conversionContext
            return this
        }

        /**
         * Adds a map of flag aliases where the keys are the flags' alias names and the values are their
         * actual names.
         */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun withAliases(aliases: MutableMap<String?, String>?): Builder {
            this.aliases.putAll(aliases!!)
            return this
        }

        /** Returns a newly-initialized [OptionsParserImpl].  */
        fun build(): OptionsParserImpl {
            return com.google.devtools.common.options.OptionsParserImpl(
                this.optionsData,
                this.argsPreProcessor,
                this.skippedPrefixes,
                this.ignoreInternalOptions,
                this.aliasFlag,
                this.conversionContext,
                this.aliases
            )
        }
    }

    private val optionsData: com.google.devtools.common.options.OptionsData

    /**
     * We store the results of option parsing in here - since there can only be one value per option
     * field, this is where the different instances of an option have been combined and the final
     * value is tracked. It'll look like
     * 
     * <pre>
     * OptionDefinition("--host") -> "www.google.com"
     * OptionDefinition("--port") -> 80
    </pre> * 
     * 
     * This map is modified by repeated calls to [.parse].
     */
    private val optionValues: MutableMap<com.google.devtools.common.options.OptionDefinition?, com.google.devtools.common.options.OptionValueDescription> =
        HashMap<com.google.devtools.common.options.OptionDefinition?, com.google.devtools.common.options.OptionValueDescription>()

    /**
     * Since parse() expects multiple calls to it with the same [PriorityCategory] to be treated
     * as though the args in the later call have higher priority over the earlier calls, we need to
     * track the high water mark of option priority at each category. Each call to parse will start at
     * this level.
     */
    private val nextPriorityPerPriorityCategory: MutableMap<com.google.devtools.common.options.OptionPriority.PriorityCategory?, com.google.devtools.common.options.OptionPriority?> =
        java.util.Arrays.stream<com.google.devtools.common.options.OptionPriority.PriorityCategory?>(com.google.devtools.common.options.OptionPriority.PriorityCategory.entries.toTypedArray())
            .collect(
                Collectors.toMap(
                    java.util.function.Function { p: com.google.devtools.common.options.OptionPriority.PriorityCategory? -> p },
                    java.util.function.Function { category: com.google.devtools.common.options.OptionPriority.PriorityCategory? ->
                        com.google.devtools.common.options.OptionPriority.Companion.lowestOptionPriorityAtCategory(
                            category
                        )
                    })
            )

    /**
     * Explicit option tracking, tracking each option as it was provided, after they have been parsed.
     * 
     * 
     * The value is unconverted, still the string as it was read from the input, or partially
     * altered in cases where the flag was set by non `--flag=value` forms; e.g. `--nofoo`
     * becomes `--foo=0`.
     */
    private val parsedOptions: MutableList<com.google.devtools.common.options.ParsedOptionDescription?> =
        java.util.ArrayList<com.google.devtools.common.options.ParsedOptionDescription?>()

    private val skippedOptions: MutableList<com.google.devtools.common.options.ParsedOptionDescription?> =
        java.util.ArrayList<com.google.devtools.common.options.ParsedOptionDescription?>()

    private val flagAliasMappings: MutableMap<String?, String>

    // We want to keep the invariant that warnings are produced as they are encountered, but only
    // show each one once.
    private val warnings: MutableSet<String?> = LinkedHashSet<String?>()
    private val argsPreProcessor: com.google.devtools.common.options.ArgsPreProcessor
    private val skippedPrefixes: MutableList<String?>
    private val ignoreInternalOptions: Boolean
    private val aliasFlag: String?
    private val conversionContext: Any?

    /**
     * This option is used to collect skipped arguments while preserving the relative ordering between
     * those given explicitly on the command line and those expanded by `ConfigExpander`. The
     * field itself is not used for any purpose other than retrieving its [Option] annotation.
     */
    @com.google.errorprone.annotations.Keep
    @Suppress("unused") // Used for reflection.
    @com.google.devtools.common.options.OptionsClass
    abstract class SkippedArgs : com.google.devtools.common.options.OptionsBase() {
        @com.google.devtools.common.options.Option(
            name = "skipped args",
            allowMultiple = true,
            defaultValue = "null",
            documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNDOCUMENTED,
            metadataTags = [com.google.devtools.common.options.OptionMetadataTag.INTERNAL],
            effectTags = [com.google.devtools.common.options.OptionEffectTag.NO_OP],
            help = "Only used internally by OptionsParserImpl"
        )
        abstract fun getSkippedArgs(): MutableList<String?>?
    }

    init {
        this.optionsData = optionsData
        this.argsPreProcessor = argsPreProcessor
        this.skippedPrefixes = skippedPrefixes
        this.ignoreInternalOptions = ignoreInternalOptions
        this.aliasFlag = aliasFlag
        this.conversionContext = conversionContext
        this.flagAliasMappings = aliases
    }

    /** Returns the [OptionsData] used in this instance.  */
    fun getOptionsData(): com.google.devtools.common.options.OptionsData {
        return optionsData
    }

    fun getConversionContext(): Any? {
        return conversionContext
    }

    /** Returns a [Builder] that is configured the same as this parser.  */
    fun toBuilder(): Builder {
        val builder: Builder =
            com.google.devtools.common.options.OptionsParserImpl.Companion.builder()
                .optionsData(optionsData)
                .argsPreProcessor(argsPreProcessor)
                .withAliasFlag(aliasFlag)
                .withAliases(flagAliasMappings)
                .withConversionContext(conversionContext)
                .ignoreInternalOptions(ignoreInternalOptions)
        for (skippedPrefix in skippedPrefixes) {
            builder.skippedPrefix(skippedPrefix)
        }
        return builder
    }

    /** Implements [OptionsParser.asCompleteListOfParsedOptions].  */
    fun asCompleteListOfParsedOptions(): MutableList<com.google.devtools.common.options.ParsedOptionDescription?> {
        return parsedOptions.stream() // It is vital that this sort is stable so that options on the same priority are not
            // reordered.
            .sorted(
                java.util.Comparator.comparing<com.google.devtools.common.options.ParsedOptionDescription?, com.google.devtools.common.options.OptionPriority?>(
                    java.util.function.Function { obj: com.google.devtools.common.options.ParsedOptionDescription? -> obj.getPriority() })
            )
            .collect(Collectors.toCollection(java.util.function.Supplier { ArrayList() }))
    }

    /** Implements [OptionsParser.asListOfExplicitOptions].  */
    fun asListOfExplicitOptions(): MutableList<com.google.devtools.common.options.ParsedOptionDescription?> {
        return parsedOptions.stream()
            .filter(java.util.function.Predicate { obj: com.google.devtools.common.options.ParsedOptionDescription? -> obj.isExplicit() }) // It is vital that this sort is stable so that options on the same priority are not
            // reordered.
            .sorted(
                java.util.Comparator.comparing<com.google.devtools.common.options.ParsedOptionDescription?, com.google.devtools.common.options.OptionPriority?>(
                    java.util.function.Function { obj: com.google.devtools.common.options.ParsedOptionDescription? -> obj.getPriority() })
            )
            .collect(Collectors.toCollection(java.util.function.Supplier { ArrayList() }))
    }

    fun getSkippedOptions(): MutableList<com.google.devtools.common.options.ParsedOptionDescription?> {
        return skippedOptions
    }

    /** Implements [OptionsParser.canonicalize].  */
    fun asCanonicalizedList(): MutableList<String?> {
        return asCanonicalizedListOfParsedOptions().stream()
            .map<String?>(java.util.function.Function { obj: com.google.devtools.common.options.ParsedOptionDescription? -> obj.getDeprecatedCanonicalForm() })
            .collect(com.google.common.collect.ImmutableList.toImmutableList<String?>())
    }

    /** Implements [OptionsParser.canonicalize].  */
    fun asCanonicalizedListOfParsedOptions(): MutableList<com.google.devtools.common.options.ParsedOptionDescription?> {
        return optionValues.keySet().stream()
            .filter(java.util.function.Predicate { k: com.google.devtools.common.options.OptionDefinition? -> k != com.google.devtools.common.options.OptionsParserImpl.Companion.skippedArgsDefinition })
            .map<MutableList<com.google.devtools.common.options.ParsedOptionDescription?>?>(java.util.function.Function { optionDefinition: com.google.devtools.common.options.OptionDefinition? ->
                optionValues.get(
                    optionDefinition
                ).getCanonicalInstances()
            })
            .flatMap<com.google.devtools.common.options.ParsedOptionDescription?>(java.util.function.Function { obj: MutableList<com.google.devtools.common.options.ParsedOptionDescription?>? -> obj.stream() }) // Return the effective (canonical) options in the order they were applied.
            .sorted(
                java.util.Comparator.comparing<com.google.devtools.common.options.ParsedOptionDescription?, com.google.devtools.common.options.OptionPriority?>(
                    java.util.function.Function { obj: com.google.devtools.common.options.ParsedOptionDescription? -> obj.getPriority() })
            )
            .collect(com.google.common.collect.ImmutableList.toImmutableList<com.google.devtools.common.options.ParsedOptionDescription?>())
    }

    /** Implements [OptionsParser.asListOfOptionValues].  */
    fun asListOfEffectiveOptions(): MutableList<com.google.devtools.common.options.OptionValueDescription> {
        val result: MutableList<com.google.devtools.common.options.OptionValueDescription> =
            java.util.ArrayList<com.google.devtools.common.options.OptionValueDescription>()
        for (mapEntry in optionsData.getAllOptionDefinitions()) {
            val optionDefinition: com.google.devtools.common.options.OptionDefinition? = mapEntry.getValue()
            val optionValue: com.google.devtools.common.options.OptionValueDescription? =
                optionValues.get(optionDefinition)
            if (optionValue == null) {
                result.add(
                    com.google.devtools.common.options.OptionValueDescription.Companion.getDefaultOptionValue(
                        optionDefinition,
                        conversionContext
                    )
                )
            } else {
                result.add(optionValue)
            }
        }
        return result
    }

    fun allOptionValues(): MutableList<com.google.devtools.common.options.OptionValueDescription?> {
        return optionsData.getAllOptionDefinitions().stream()
            .map<com.google.devtools.common.options.OptionDefinition?>(java.util.function.Function { java.util.Map.Entry.getValue() })
            .map<com.google.devtools.common.options.OptionValueDescription?>(java.util.function.Function { key: com.google.devtools.common.options.OptionDefinition? ->
                optionValues.get(
                    key
                )
            })
            .filter(java.util.function.Predicate { optionValue: com.google.devtools.common.options.OptionValueDescription? -> optionValue != null })
            .collect(com.google.common.collect.ImmutableList.toImmutableList<com.google.devtools.common.options.OptionValueDescription?>())
    }

    private fun maybeAddDeprecationWarning(
        optionDefinition: com.google.devtools.common.options.OptionDefinition,
        priority: com.google.devtools.common.options.OptionPriority.PriorityCategory
    ) {
        // Don't add a warning for deprecated flag set by the invocation policy.
        if (priority == com.google.devtools.common.options.OptionPriority.PriorityCategory.INVOCATION_POLICY) {
            return
        }
        // Continue to support the old behavior for @Deprecated options.
        val warning: String = optionDefinition.getDeprecationWarning()
        if (!warning.isEmpty() || optionDefinition.isDeprecated()) {
            addDeprecationWarning(optionDefinition.getOptionName(), warning)
        }
    }

    private fun maybeAddOldNameWarning(parsedOption: com.google.devtools.common.options.ParsedOptionDescription) {
        // Don't add a warning for old name options set by the invocation policy.
        if (parsedOption.getPriority()
                .getPriorityCategory() == com.google.devtools.common.options.OptionPriority.PriorityCategory.INVOCATION_POLICY
        ) {
            return
        }
        val optionDefinition: com.google.devtools.common.options.OptionDefinition = parsedOption.getOptionDefinition()
        if (!optionDefinition.getOldNameWarning()) {
            return
        }
        val oldOptionName: String? = optionDefinition.getOldOptionName()
        val optionName: String? = optionDefinition.getOptionName()
        if (parsedOption.isOldNameUsed()) {
            addDeprecationWarning(oldOptionName, java.lang.String.format("Use --%s instead", optionName))
        }
    }

    private fun addDeprecationWarning(optionName: String?, warning: String) {
        warnings.add(
            java.lang.String.format(
                "Option '%s' is deprecated%s", optionName, (if (warning.isEmpty()) "" else ": " + warning)
            )
        )
    }

    @Throws(com.google.devtools.common.options.OptionsParsingException::class)
    fun clearValue(optionDefinition: com.google.devtools.common.options.OptionDefinition?): com.google.devtools.common.options.OptionValueDescription? {
        return optionValues.remove(optionDefinition)
    }

    fun getOptionValueDescription(name: String?): com.google.devtools.common.options.OptionValueDescription? {
        val optionDefinition: com.google.devtools.common.options.OptionDefinition =
            optionsData.getOptionDefinitionFromName(name)
        requireNotNull(optionDefinition) { "No such option '" + name + "'" }
        return optionValues.get(optionDefinition)
    }

    @Throws(com.google.devtools.common.options.OptionsParsingException::class)
    fun getOptionDescription(name: String?): com.google.devtools.common.options.OptionsParser.OptionDescription? {
        val optionDefinition: com.google.devtools.common.options.OptionDefinition? =
            optionsData.getOptionDefinitionFromName(name)
        if (optionDefinition == null) {
            return null
        }
        return com.google.devtools.common.options.OptionsParser.OptionDescription(optionDefinition, optionsData)
    }

    /**
     * Implementation of [OptionsParser.getExpansionValueDescriptions]
     */
    @Throws(com.google.devtools.common.options.OptionsParsingException::class)
    fun getExpansionValueDescriptions(
        expansionFlagDef: com.google.devtools.common.options.OptionDefinition,
        originOfExpansionFlag: com.google.devtools.common.options.OptionInstanceOrigin
    ): com.google.common.collect.ImmutableList<com.google.devtools.common.options.ParsedOptionDescription?> {
        val builder: com.google.common.collect.ImmutableList.Builder<com.google.devtools.common.options.ParsedOptionDescription?> =
            com.google.common.collect.ImmutableList.builder<com.google.devtools.common.options.ParsedOptionDescription?>()

        // Values needed to correctly track the origin of the expanded options.
        var nextOptionPriority: com.google.devtools.common.options.OptionPriority =
            com.google.devtools.common.options.OptionPriority.Companion.getChildPriority(originOfExpansionFlag.getPriority())
        val source: String?
        var implicitDependent: com.google.devtools.common.options.ParsedOptionDescription? = null
        var expandedFrom: com.google.devtools.common.options.ParsedOptionDescription? = null

        val options: com.google.common.collect.ImmutableList<String?>
        val expansionFlagParsedDummy: com.google.devtools.common.options.ParsedOptionDescription =
            com.google.devtools.common.options.ParsedOptionDescription.Companion.newDummyInstance(
                expansionFlagDef, originOfExpansionFlag, conversionContext
            )
        if (expansionFlagDef.hasImplicitRequirements()) {
            options =
                com.google.common.collect.ImmutableList.copyOf<String?>(expansionFlagDef.getImplicitRequirements())
            source =
                java.lang.String.format(
                    "implicitly required by %s (source: %s)",
                    expansionFlagDef, originOfExpansionFlag.getSource()
                )
            implicitDependent = expansionFlagParsedDummy
        } else if (expansionFlagDef.isExpansionOption()) {
            options = optionsData.getEvaluatedExpansion(expansionFlagDef)
            source =
                java.lang.String.format(
                    "expanded by %s (source: %s)", expansionFlagDef, originOfExpansionFlag.getSource()
                )
            expandedFrom = expansionFlagParsedDummy
        } else {
            return com.google.common.collect.ImmutableList.of<com.google.devtools.common.options.ParsedOptionDescription?>()
        }

        val optionsIterator: MutableIterator<String> = options.iterator()
        while (optionsIterator.hasNext()) {
            val unparsedFlagExpression = optionsIterator.next()
            identifyOptionAndPossibleArgument(
                unparsedFlagExpression,
                optionsIterator,
                nextOptionPriority,
                java.util.function.Function { o: com.google.devtools.common.options.OptionDefinition? -> source },
                implicitDependent,
                expandedFrom,  /* fallbackData= */
                null
            )
                .parsedOptionDescription
                .ifPresent(java.util.function.Consumer { element: com.google.devtools.common.options.ParsedOptionDescription? ->
                    builder.add(
                        element
                    )
                })
            nextOptionPriority =
                com.google.devtools.common.options.OptionPriority.Companion.nextOptionPriority(nextOptionPriority)
        }
        return builder.build()
    }

    fun containsExplicitOption(name: String?): Boolean {
        val optionDefinition: com.google.devtools.common.options.OptionDefinition =
            optionsData.getOptionDefinitionFromName(name)
        requireNotNull(optionDefinition) { "No such option '" + name + "'" }
        return optionValues.get(optionDefinition) != null
    }

    fun getSkippedArgs(): MutableList<String?>? {
        val value: com.google.devtools.common.options.OptionValueDescription? =
            optionValues.get(com.google.devtools.common.options.OptionsParserImpl.Companion.skippedArgsDefinition)
        if (value == null) {
            return com.google.common.collect.ImmutableList.of<String?>()
        }
        return value.getValue() as MutableList<String?>?
    }

    /**
     * Parses the args, and returns what it doesn't parse. May be called multiple times, and may be
     * called recursively. The option's definition dictates how it reacts to multiple settings. By
     * default, the arg seen last at the highest priority takes precedence, overriding the early
     * values. Options that accumulate multiple values will track them in priority and appearance
     * order.
     */
    @Throws(com.google.devtools.common.options.OptionsParsingException::class)
    fun parse(
        priorityCat: com.google.devtools.common.options.OptionPriority.PriorityCategory?,
        sourceFunction: java.util.function.Function<com.google.devtools.common.options.OptionDefinition?, String?>,
        args: MutableList<com.google.devtools.common.options.OptionsParser.ArgAndFallbackData?>?
    ): OptionsParserImplResult {
        val optionsParserImplResult =
            parse(nextPriorityPerPriorityCategory.get(priorityCat), sourceFunction, null, null, args)
        nextPriorityPerPriorityCategory.put(priorityCat, optionsParserImplResult.nextPriority)
        return optionsParserImplResult
    }

    /**
     * Parses the args, and returns what it doesn't parse. May be called multiple times, and may be
     * called recursively. Calls may contain intersecting sets of options; in that case, the arg seen
     * last takes precedence.
     * 
     * 
     * The method treats options that have neither an implicitDependent nor an expandedFrom value
     * as explicitly set.
     */
    @Throws(com.google.devtools.common.options.OptionsParsingException::class)
    private fun parse(
        priority: com.google.devtools.common.options.OptionPriority,
        sourceFunction: java.util.function.Function<com.google.devtools.common.options.OptionDefinition?, String?>,
        implicitDependent: com.google.devtools.common.options.ParsedOptionDescription?,
        expandedFrom: com.google.devtools.common.options.ParsedOptionDescription?,
        args: MutableList<com.google.devtools.common.options.OptionsParser.ArgAndFallbackData?>?
    ): OptionsParserImplResult {
        var priority: com.google.devtools.common.options.OptionPriority = priority
        val unparsedArgs: MutableList<String?> = java.util.ArrayList<String?>()
        val unparsedPostDoubleDashArgs: MutableList<String?> = java.util.ArrayList<String?>()
        val ignoredArgs: MutableList<String?> = java.util.ArrayList<String?>()

        val argsAndFallbackDataIterator: MutableIterator<com.google.devtools.common.options.OptionsParser.ArgAndFallbackData> =
            argsPreProcessor.preProcess(args).iterator()
        val argsIterator: MutableIterator<String> =
            com.google.common.collect.Iterators.transform<com.google.devtools.common.options.OptionsParser.ArgAndFallbackData?, String?>(
                argsAndFallbackDataIterator,
                com.google.common.base.Function { a: com.google.devtools.common.options.OptionsParser.ArgAndFallbackData? -> a.arg })
        while (argsAndFallbackDataIterator.hasNext()) {
            val argAndFallbackData: com.google.devtools.common.options.OptionsParser.ArgAndFallbackData =
                argsAndFallbackDataIterator.next()
            var arg: String = argAndFallbackData.arg
            val fallbackData: com.google.devtools.common.options.OptionsData? = argAndFallbackData.fallbackData

            if (!arg.startsWith("-")) {
                unparsedArgs.add(arg)
                continue  // not an option arg
            }

            if (arg.startsWith("-//") || arg.startsWith("-@")) {
                // Fail with a helpful error when an invalid option looks like an absolute negative target
                // pattern or a typoed Starlark option.
                throw com.google.devtools.common.options.OptionsParsingException(
                    java.lang.String.format(
                        ("Invalid options syntax: %s\n"
                                + "Note: Negative target patterns can only appear after the end of options"
                                + " marker ('--'). Flags corresponding to Starlark-defined build settings"
                                + " always start with '--', not '-'."),
                        arg
                    )
                )
            }

            arg = swapShorthandAlias(arg)

            if (arg == "--") { // "--" means all remaining args aren't options
                com.google.common.collect.Iterators.addAll<String?>(unparsedPostDoubleDashArgs, argsIterator)
                break
            }

            val parsedOption: java.util.Optional<com.google.devtools.common.options.ParsedOptionDescription?>?
            if (containsSkippedPrefix(arg)) {
                // Parse the skipped arg into a synthetic allowMultiple option to preserve its order
                // relative to skipped args coming from expansions. Simply adding it to the residue would
                // end up placing expanded skipped args after all explicitly given skipped args, which isn't
                // correct.
                parsedOption =
                    java.util.Optional.of<com.google.devtools.common.options.ParsedOptionDescription?>(
                        com.google.devtools.common.options.ParsedOptionDescription.Companion.newParsedOptionDescription(
                            com.google.devtools.common.options.OptionsParserImpl.Companion.skippedArgsDefinition,
                            arg,
                            arg,
                            com.google.devtools.common.options.OptionInstanceOrigin(
                                priority,
                                sourceFunction.apply(com.google.devtools.common.options.OptionsParserImpl.Companion.skippedArgsDefinition),
                                implicitDependent,
                                expandedFrom
                            ),
                            conversionContext
                        )
                    )
            } else {
                val result =
                    identifyOptionAndPossibleArgument(
                        arg,
                        argsIterator,
                        priority,
                        sourceFunction,
                        implicitDependent,
                        expandedFrom,
                        fallbackData
                    )
                result.ignoredArgs.ifPresent(java.util.function.Consumer { e: String? -> ignoredArgs.add(e) })
                parsedOption = result.parsedOptionDescription
            }
            if (parsedOption.isPresent()) {
                handleNewParsedOption(parsedOption.get(), fallbackData)
            }
            priority = com.google.devtools.common.options.OptionPriority.Companion.nextOptionPriority(priority)
        }

        // Go through the final values and make sure they are valid values for their option. Unlike any
        // checks that happened above, this also checks that flags that were not set have a valid
        // default value. getValue() will throw if the value is invalid.
        for (valueDescription in asListOfEffectiveOptions()) {
            valueDescription.getValue()
        }

        return com.google.devtools.common.options.OptionsParserImpl.OptionsParserImplResult(
            unparsedArgs, unparsedPostDoubleDashArgs, ignoredArgs, priority, flagAliasMappings
        )
    }

    /** A class that stores residue and priority information.  */
    internal class OptionsParserImplResult(
        val preDoubleDashResidue: MutableList<String?>,
        val postDoubleDashResidue: MutableList<String?>,
        ignoredArgs: MutableList<String?>,
        nextPriority: com.google.devtools.common.options.OptionPriority?,
        aliases: MutableMap<String?, String>
    ) {
        val ignoredArgs: com.google.common.collect.ImmutableList<String?>
        val nextPriority: com.google.devtools.common.options.OptionPriority?
        val aliases: com.google.common.collect.ImmutableMap<String?, String?>

        init {
            this.ignoredArgs = com.google.common.collect.ImmutableList.copyOf<String?>(ignoredArgs)
            this.nextPriority = nextPriority
            this.aliases = com.google.common.collect.ImmutableMap.copyOf<String?, String?>(aliases)
        }

        fun getResidue(): MutableList<String?> {
            val toReturn: MutableList<String?> =
                java.util.ArrayList<String?>(preDoubleDashResidue.size() + postDoubleDashResidue.size())
            toReturn.addAll(preDoubleDashResidue)
            toReturn.addAll(postDoubleDashResidue)
            return toReturn
        }
    }

    /** Implements [OptionsParser.parseArgsAsExpansionOfOption]  */
    @Throws(com.google.devtools.common.options.OptionsParsingException::class)
    fun parseArgsAsExpansionOfOption(
        optionToExpand: com.google.devtools.common.options.ParsedOptionDescription,
        sourceFunction: java.util.function.Function<com.google.devtools.common.options.OptionDefinition?, String?>,
        args: MutableList<com.google.devtools.common.options.OptionsParser.ArgAndFallbackData?>?
    ): OptionsParserImplResult {
        return parse(
            com.google.devtools.common.options.OptionPriority.Companion.getChildPriority(optionToExpand.getPriority()),
            sourceFunction,
            null,
            optionToExpand,
            args
        )
    }

    /**
     * Implementation of [ ][OptionsParser.setOptionValueAtSpecificPriorityWithoutExpansion]
     */
    @Throws(com.google.devtools.common.options.OptionsParsingException::class)
    fun setOptionValueAtSpecificPriorityWithoutExpansion(
        origin: com.google.devtools.common.options.OptionInstanceOrigin?,
        option: com.google.devtools.common.options.OptionDefinition?,
        unconvertedValue: String?
    ) {
        com.google.common.base.Preconditions.checkNotNull<com.google.devtools.common.options.OptionDefinition?>(option)
        com.google.common.base.Preconditions.checkNotNull<String?>(
            unconvertedValue,
            "Cannot set %s to a null value. Pass \"\" if an empty value is required.",
            option
        )
        com.google.common.base.Preconditions.checkNotNull<com.google.devtools.common.options.OptionInstanceOrigin?>(
            origin,
            "Cannot assign value '%s' to %s without a clear origin for this value.",
            unconvertedValue,
            option
        )
        val priorityCategory: com.google.devtools.common.options.OptionPriority.PriorityCategory? =
            origin.getPriority().getPriorityCategory()
        val isNotDefault =
            priorityCategory != com.google.devtools.common.options.OptionPriority.PriorityCategory.DEFAULT
        com.google.common.base.Preconditions.checkArgument(
            isNotDefault,
            "Attempt to assign value '%s' to %s at priority %s failed. Cannot set options at "
                    + "default priority - by definition, that means the option is unset.",
            unconvertedValue,
            option,
            priorityCategory
        )

        setOptionValue(
            com.google.devtools.common.options.ParsedOptionDescription.Companion.newParsedOptionDescription(
                option,
                java.lang.String.format("--%s=%s", option.getOptionName(), unconvertedValue),
                unconvertedValue,
                origin,
                conversionContext
            )
        )
    }

    /** Takes care of tracking the parsed option's value in relation to other options.  */
    @Throws(com.google.devtools.common.options.OptionsParsingException::class)
    private fun handleNewParsedOption(
        parsedOption: com.google.devtools.common.options.ParsedOptionDescription,
        fallbackData: com.google.devtools.common.options.OptionsData?
    ) {
        val optionDefinition: com.google.devtools.common.options.OptionDefinition = parsedOption.getOptionDefinition()
        val expansionBundle: com.google.devtools.common.options.OptionValueDescription.ExpansionBundle? =
            setOptionValue(parsedOption)
        val unconvertedValue: String? = parsedOption.getUnconvertedValue()

        if (expansionBundle != null) {
            val optionsParserImplResult =
                parse(
                    com.google.devtools.common.options.OptionPriority.Companion.getChildPriority(parsedOption.getPriority()),
                    java.util.function.Function { o: com.google.devtools.common.options.OptionDefinition? -> expansionBundle.sourceOfExpansionArgs },
                    if (optionDefinition.hasImplicitRequirements()) parsedOption else null,
                    if (optionDefinition.isExpansionOption()) parsedOption else null,
                    com.google.devtools.common.options.OptionsParser.ArgAndFallbackData.Companion.wrapWithFallbackData(
                        expansionBundle.expansionArgs,
                        fallbackData
                    )
                )
            if (!optionsParserImplResult.getResidue().isEmpty()) {
                // Throw an assertion here, because this indicates an error in the definition of this
                // option's expansion or requirements, not with the input as provided by the user.

                throw java.lang.AssertionError(
                    ("Unparsed options remain after processing "
                            + unconvertedValue
                            + ": "
                            + com.google.common.base.Joiner.on(' ').join(optionsParserImplResult.getResidue()))
                )
            }
        }
    }

    @Throws(com.google.devtools.common.options.OptionsParsingException::class)
    private fun setOptionValue(parsedOption: com.google.devtools.common.options.ParsedOptionDescription): com.google.devtools.common.options.OptionValueDescription.ExpansionBundle? {
        val optionDefinition: com.google.devtools.common.options.OptionDefinition = parsedOption.getOptionDefinition()
        // All options can be deprecated; check and warn before doing any option-type specific work.
        maybeAddDeprecationWarning(optionDefinition, parsedOption.getPriority().getPriorityCategory())
        // Check if the old option name is used and add a warning
        maybeAddOldNameWarning(parsedOption)
        // Track the value, before any remaining option-type specific work that is done outside of
        // the OptionValueDescription.
        val entry: com.google.devtools.common.options.OptionValueDescription =
            optionValues.computeIfAbsent(
                optionDefinition,
                java.util.function.Function { def: com.google.devtools.common.options.OptionDefinition? ->
                    com.google.devtools.common.options.OptionValueDescription.Companion.createOptionValueDescription(
                        def, optionsData, conversionContext
                    )
                })
        val expansionBundle: com.google.devtools.common.options.OptionValueDescription.ExpansionBundle? =
            entry.addOptionInstance(parsedOption, warnings)

        // There are 3 types of flags that expand to other flag values. Expansion flags are the
        // accepted way to do this, but implicit requirements also do this. We rely on the
        // OptionProcessor compile-time check's guarantee that no option sets
        // both expansion behaviors. (In Bazel, --config is another such flag, but that expansion
        // is not controlled within the options parser, so we ignore it here)

        // As much as possible, we want the behaviors of these different types of flags to be
        // identical, as this minimizes the number of edge cases, but we do not yet track these values
        // in the same way.
        if (parsedOption.getImplicitDependent() == null) {
            if (parsedOption.getOptionDefinition() == com.google.devtools.common.options.OptionsParserImpl.Companion.skippedArgsDefinition) {
                // This may be a Starlark option. Don't parse it here (save it for StarlarkOptionsParser)
                // but keep the context so we can track if the option was explicitly set or not for BEP
                // reporting.
                skippedOptions.add(parsedOption)
            } else {
                // Log explicit options and expanded options in the order they are parsed (can be sorted
                // later). This information is needed to correctly canonicalize flags.
                parsedOptions.add(parsedOption)
            }

            if (aliasFlag != null && parsedOption.getCommandLineForm().startsWith("--" + aliasFlag)) {
                val alias: MutableList<String?> =
                    com.google.common.base.Splitter.on('=').limit(2).splitToList(parsedOption.getUnconvertedValue())

                flagAliasMappings.put(alias.get(0), alias.get(1)!!)
            }
        }

        return expansionBundle
    }

    /**
     * Keep the properties of [OptionsData] used below in sync with [ ][.equivalentForParsing].
     * 
     * 
     * If an option is not found in the current [OptionsData], but is found in the specified
     * fallback data, a [ParsedOptionDescriptionOrIgnoredArgs] with no [ ], but the ignored arguments is returned.
     */
    @Throws(com.google.devtools.common.options.OptionsParsingException::class)
    private fun identifyOptionAndPossibleArgument(
        arg: String,
        nextArgs: MutableIterator<String>,
        priority: com.google.devtools.common.options.OptionPriority?,
        sourceFunction: java.util.function.Function<com.google.devtools.common.options.OptionDefinition?, String?>,
        implicitDependent: com.google.devtools.common.options.ParsedOptionDescription?,
        expandedFrom: com.google.devtools.common.options.ParsedOptionDescription?,
        fallbackData: com.google.devtools.common.options.OptionsData?
    ): ParsedOptionDescriptionOrIgnoredArgs {
        // Store the way this option was parsed on the command line.

        val commandLineForm: java.lang.StringBuilder = java.lang.StringBuilder()
        commandLineForm.append(arg)
        var unconvertedValue: String? = null
        var lookupResult: OptionLookupResult?
        var booleanValue = true
        var parsedOptionName = ""

        if (arg.length() == 2) { // -l  (may be nullary or unary)
            lookupResult =
                getWithFallback<Char?>(java.util.function.BiFunction { obj: com.google.devtools.common.options.OptionsData?, abbrev: Char? ->
                    obj.getFieldForAbbrev(abbrev)
                }, arg.charAt(1), fallbackData)
            booleanValue = true
        } else if (arg.length() == 3 && arg.charAt(2) == '-') { // -l-  (boolean)
            lookupResult =
                getWithFallback<Char?>(java.util.function.BiFunction { obj: com.google.devtools.common.options.OptionsData?, abbrev: Char? ->
                    obj.getFieldForAbbrev(abbrev)
                }, arg.charAt(1), fallbackData)
            booleanValue = false
        } else if (arg.startsWith("--")) { // --long_option

            val equalsAt: Int = arg.indexOf('='.code)
            val nameStartsAt = 2
            var name: String =
                if (equalsAt == -1) arg.substring(nameStartsAt) else arg.substring(nameStartsAt, equalsAt)
            if (name.trim().isEmpty()) {
                throw com.google.devtools.common.options.OptionsParsingException("Invalid options syntax: " + arg, arg)
            }
            unconvertedValue = if (equalsAt == -1) null else arg.substring(equalsAt + 1)
            lookupResult =
                getWithFallback<String?>(java.util.function.BiFunction { obj: com.google.devtools.common.options.OptionsData?, name: String? ->
                    obj.getOptionDefinitionFromName(name)
                }, name, fallbackData)

            // Look for a "no"-prefixed option name: "no<optionName>".
            if (lookupResult == null && name.startsWith("no")) {
                name = name.substring(2)
                lookupResult =
                    getWithFallback<String?>(java.util.function.BiFunction { obj: com.google.devtools.common.options.OptionsData?, name: String? ->
                        obj.getOptionDefinitionFromName(
                            name
                        )
                    }, name, fallbackData)
                booleanValue = false
                if (lookupResult != null) {
                    // TODO(bazel-team): Add tests for these cases.
                    if (!lookupResult.definition.usesBooleanValueSyntax()) {
                        throw com.google.devtools.common.options.OptionsParsingException(
                            "Illegal use of 'no' prefix on non-boolean option: " + arg, arg
                        )
                    }
                    if (unconvertedValue != null) {
                        throw com.google.devtools.common.options.OptionsParsingException(
                            "Unexpected value after boolean option: " + arg,
                            arg
                        )
                    }
                    // "no<optionname>" signifies a boolean option w/ false value
                    unconvertedValue = "0"
                }
            }
            parsedOptionName = name
        } else {
            throw com.google.devtools.common.options.OptionsParsingException("Invalid options syntax: " + arg, arg)
        }

        // Do not recognize internal options, which are treated as if they did not exist.
        if (lookupResult == null || shouldIgnoreOption(lookupResult.definition)) {
            val suggestion: String?
            // Do not offer suggestions for short-form options.
            if (arg.startsWith("--")) {
                suggestion = net.starlark.java.spelling.SpellChecker.didYouMean(arg, getAllValidArgs())
            } else {
                suggestion = ""
            }
            throw com.google.devtools.common.options.OptionsParsingException(
                "Unrecognized option: " + arg + suggestion,
                arg
            )
        }

        if (unconvertedValue == null) {
            // Special-case boolean to supply value based on presence of "no" prefix.
            if (lookupResult.definition.usesBooleanValueSyntax()) {
                unconvertedValue = if (booleanValue) "1" else "0"
            } else if (lookupResult.definition.getType() == java.lang.Void::class.java) {
                // This is expected, Void type options have no args.
            } else if (nextArgs.hasNext()) {
                // "--flag value" form
                unconvertedValue = nextArgs.next()
                commandLineForm.append(" ").append(unconvertedValue)
            } else {
                throw com.google.devtools.common.options.OptionsParsingException("Expected value after " + arg)
            }
        }

        if (lookupResult.fromFallback) {
            // The option was not found on the current command, but is a valid option for some other
            // command. Ignore it.
            return com.google.devtools.common.options.OptionsParserImpl.ParsedOptionDescriptionOrIgnoredArgs(
                java.util.Optional.empty<com.google.devtools.common.options.ParsedOptionDescription?>(),
                java.util.Optional.of<String?>(commandLineForm.toString())
            )
        }

        return com.google.devtools.common.options.OptionsParserImpl.ParsedOptionDescriptionOrIgnoredArgs(
            java.util.Optional.of<com.google.devtools.common.options.ParsedOptionDescription?>(
                com.google.devtools.common.options.ParsedOptionDescription.Companion.newParsedOptionDescription(
                    lookupResult.definition,
                    commandLineForm.toString(),
                    unconvertedValue,
                    com.google.devtools.common.options.OptionInstanceOrigin(
                        priority,
                        sourceFunction.apply(lookupResult.definition),
                        implicitDependent,
                        expandedFrom
                    ),
                    conversionContext,
                    !parsedOptionName.isEmpty()
                            && lookupResult.definition.getOldOptionName() == parsedOptionName
                )
            ),
            java.util.Optional.empty<String?>()
        )
    }

    private fun getAllValidArgs(): Iterable<String?> {
        return Iterable {
            optionsData.getAllOptionDefinitions().stream()
                .filter(java.util.function.Predicate { entry: MutableMap.MutableEntry<String?, com.google.devtools.common.options.OptionDefinition?>? ->
                    !shouldIgnoreOption(
                        entry.getValue()
                    )
                })
                .flatMap<String?>(
                    java.util.function.Function { definition: MutableMap.MutableEntry<String?, com.google.devtools.common.options.OptionDefinition?>? ->
                        val builder: java.util.stream.Stream.Builder<String?> =
                            java.util.stream.Stream.builder<String?>()
                        builder.add("--" + definition.getKey())
                        if (definition.getValue().usesBooleanValueSyntax()) {
                            builder.add("--no" + definition.getKey())
                        }
                        builder.build()
                    })
                .iterator()
        }
    }

    // TODO: Replace with a sealed interface unwrapped via pattern matching when available.
    private class ParsedOptionDescriptionOrIgnoredArgs(
        parsedOptionDescription: java.util.Optional<com.google.devtools.common.options.ParsedOptionDescription?>,
        ignoredArgs: java.util.Optional<String?>
    ) {
        val parsedOptionDescription: java.util.Optional<com.google.devtools.common.options.ParsedOptionDescription?>
        val ignoredArgs: java.util.Optional<String?>

        init {
            com.google.common.base.Preconditions.checkArgument(parsedOptionDescription.isPresent() != ignoredArgs.isPresent())
            this.parsedOptionDescription = parsedOptionDescription
            this.ignoredArgs = ignoredArgs
        }
    }

    private class OptionLookupResult(
        definition: com.google.devtools.common.options.OptionDefinition,
        fromFallback: Boolean
    ) {
        val definition: com.google.devtools.common.options.OptionDefinition
        val fromFallback: Boolean

        init {
            this.definition = definition
            this.fromFallback = fromFallback
        }
    }

    private fun <T> getWithFallback(
        getter: java.util.function.BiFunction<com.google.devtools.common.options.OptionsData?, T?, com.google.devtools.common.options.OptionDefinition?>,
        param: T?,
        fallbackData: com.google.devtools.common.options.OptionsData?
    ): OptionLookupResult? {
        var optionDefinition: com.google.devtools.common.options.OptionDefinition?
        if ((getter.apply(optionsData, param).also { optionDefinition = it }) != null) {
            return com.google.devtools.common.options.OptionsParserImpl.OptionLookupResult(optionDefinition, false)
        }
        if (fallbackData != null && (getter.apply(fallbackData, param).also { optionDefinition = it }) != null) {
            return com.google.devtools.common.options.OptionsParserImpl.OptionLookupResult(optionDefinition, true)
        }
        return null
    }

    private fun shouldIgnoreOption(optionDefinition: com.google.devtools.common.options.OptionDefinition): Boolean {
        return ignoreInternalOptions
                && com.google.common.collect.ImmutableList.copyOf<com.google.devtools.common.options.OptionMetadataTag?>(
            optionDefinition.getOptionMetadataTags()
        )
            .contains(com.google.devtools.common.options.OptionMetadataTag.INTERNAL)
    }

    /** Gets the result of parsing the options.  */
    fun <O : com.google.devtools.common.options.OptionsBase?> getParsedOptions(optionsClass: java.lang.Class<O?>?): O? {
        // Create the instance:
        val optionsInstance: O?
        try {
            val constructor: java.lang.reflect.Constructor<O?>? = optionsData.getConstructor<O?>(optionsClass)
            if (constructor == null) {
                return null
            }
            optionsInstance = constructor.newInstance()
        } catch (e: java.lang.ReflectiveOperationException) {
            throw java.lang.IllegalStateException("Error while instantiating options class", e)
        }

        // Set the fields
        for (optionDefinition in com.google.devtools.common.options.IsolatedOptionsData.Companion.getAllOptionDefinitionsForClass(
            optionsClass
        )) {
            val value: Any?
            val optionValue: com.google.devtools.common.options.OptionValueDescription? =
                optionValues.get(optionDefinition)
            if (optionValue == null || optionValue.containsErrors()) {
                value = optionDefinition.getDefaultValue(conversionContext)
            } else {
                value = optionValue.getValue()
            }
            try {
                optionDefinition.setValue(optionsInstance, value)
            } catch (e: java.lang.IllegalArgumentException) {
                // May happen when a boolean option got a string value. Just ignore this error without
                // updating the field. Fixes https://github.com/bazelbuild/bazel/issues/7847
            }
        }
        return optionsInstance
    }

    fun getWarnings(): com.google.common.collect.ImmutableList<String?> {
        return com.google.common.collect.ImmutableList.copyOf<String?>(warnings)
    }

    /**
     * Takes a string with a leading "-" and swaps it with the matching alias mapping. Example case
     * with --flag_alias=foo=bar mapped:
     * 
     * <pre>
     * swapShorthandAlias("-c") returns "-c"
     * swapShorthandAlias("--foo") returns "--bar"
     * swapShorthandAlias("--baz") returns "--baz"
    </pre> * 
     * 
     * This method returns immediately when aliasFlag is not set via the builder, which is an implicit
     * disabling of the aliasing functionality.
     */
    private fun swapShorthandAlias(arg: String): String {
        if (aliasFlag == null || !arg.startsWith("--")) {
            return arg
        }

        val equalSign: Int = arg.indexOf("=")

        // Extracts the <arg> from '--<arg>=<value>' and '--<arg> <value>' formats on the command line
        val actualArg: String = if (equalSign != -1) arg.substring(2, equalSign) else arg.substring(2)

        if (flagAliasMappings.containsKey(actualArg)) {
            val alias: String = flagAliasMappings.get(actualArg)!!
            return if (equalSign != -1) "--" + alias + arg.substring(equalSign) else "--" + alias
        }

        // If a valid alias is not found, check for unsupported --no<alias> flag semantics.
        // If a native option is aliased and being used in this case, a deprecation
        // warning will be added to notify the user that this usage is unsupported.
        if (!actualArg.startsWith("no")) {
            // If the arg does not start with "no", then the deprecation warning does not apply.
            return arg
        }
        val nameWithoutNo: String = actualArg.substring(2)
        val def: com.google.devtools.common.options.OptionDefinition? =
            optionsData.getOptionDefinitionFromName(nameWithoutNo)
        // Only consider adding the deprecation warning if a native option is being aliased.
        if (!flagAliasMappings.containsKey(nameWithoutNo) || def == null) {
            return arg
        }

        maybeAddDeprecationWarning(def, com.google.devtools.common.options.OptionPriority.PriorityCategory.COMMAND_LINE)
        // Only add the general deprecation warning if one wasn't already added for the specific flag.
        // E.g. a specific deprecationWarning on the option definition.
        if (def.getDeprecationWarning().isEmpty()) {
            warnings.add(
                java.lang.String.format(
                    "Flag --no%s is deprecated. Use --%s=false instead.", nameWithoutNo, nameWithoutNo
                )
            )
        }

        return arg
    }

    private fun containsSkippedPrefix(arg: String): Boolean {
        return skippedPrefixes.stream()
            .anyMatch(java.util.function.Predicate { prefix: String? -> arg.startsWith(prefix) })
    }

    companion object {
        /** Returns a new [Builder] with correct defaults applied.  */
        fun builder(): Builder {
            return com.google.devtools.common.options.OptionsParserImpl.Builder()
        }

        private val skippedArgsDefinition: com.google.devtools.common.options.OptionDefinition

        init {
            com.google.devtools.common.options.OptionsParserImpl.Companion.skippedArgsDefinition =
                com.google.devtools.common.options.MethodOptionDefinition.Companion.get(
                    com.google.devtools.common.options.OptionsParserImpl.SkippedArgs::class.java,
                    "getSkippedArgs"
                )
        }
    }
}
