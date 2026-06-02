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
import java.util.Collections
import java.util.HashMap
import java.util.LinkedHashMap

/**
 * A parser for options. Typical use case in a main method:
 * 
 * <pre>
 * OptionsParser parser = OptionsParser.newOptionsParser(FooOptions.class, BarOptions.class);
 * parser.parseAndExitUponError(args);
 * FooOptions foo = parser.getOptions(FooOptions.class);
 * BarOptions bar = parser.getOptions(BarOptions.class);
 * List&lt;String&gt; otherArguments = parser.getResidue();
</pre> * 
 * 
 * 
 * FooOptions and BarOptions would be options specification classes, derived from OptionsBase,
 * that contain fields annotated with @Option(...).
 * 
 * 
 * Alternatively, rather than calling [ ][.parseAndExitUponError], client code may call
 * [.parse], and handle parser exceptions usage
 * messages themselves.
 * 
 * 
 * This options parsing implementation has (at least) one design flaw. It allows both '--foo=baz'
 * and '--foo baz' for all options except void, boolean and tristate options. For these, the 'baz'
 * in '--foo baz' is not treated as a parameter to the option, making it is impossible to switch
 * options between void/boolean/tristate and everything else without breaking backwards
 * compatibility.
 * 
 * @see Options a simpler class which you can use if you only have one options specification class
 */
class OptionsParser private constructor(
    impl: com.google.devtools.common.options.OptionsParserImpl,
    allowResidue: Boolean,
    ignoreUserOptions: Boolean
) : com.google.devtools.common.options.OptionsParsingResult {
    /** A helper class to create new instances of [OptionsParser].  */
    class Builder private constructor(implBuilder: com.google.devtools.common.options.OptionsParserImpl.Builder) {
        private val implBuilder: com.google.devtools.common.options.OptionsParserImpl.Builder
        private var allowResidue = true
        private var ignoreUserOptions = false

        init {
            this.implBuilder = implBuilder
        }

        /** Directly sets the [OptionsData] used by this parser.  */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun optionsData(optionsData: com.google.devtools.common.options.OptionsData): Builder {
            com.google.common.base.Preconditions.checkArgument(!optionsData.createdWithAllowDuplicatesParsingEquivalently())
            this.implBuilder.optionsData(optionsData)
            return this
        }

        /** Directly sets the [OpaqueOptionsData] used by this parser.  */
        fun optionsData(optionsData: com.google.devtools.common.options.OpaqueOptionsData?): Builder {
            return this.optionsData(optionsData as com.google.devtools.common.options.OptionsData?)
        }

        /**
         * Sets the [OptionsData] used by this parser, based on the given `optionsClasses`.
         */
        @java.lang.SafeVarargs
        fun optionsClasses(vararg optionsClasses: java.lang.Class<out com.google.devtools.common.options.OptionsBase?>?): Builder {
            return this.optionsData(
                com.google.devtools.common.options.OptionsParser.Companion.getOptionsDataInternal(
                    com.google.common.collect.ImmutableList.copyOf<java.lang.Class<out com.google.devtools.common.options.OptionsBase?>?>(
                        optionsClasses
                    ), false
                ) as com.google.devtools.common.options.OpaqueOptionsData?
            )
        }

        /**
         * Sets the [OptionsData] used by this parser, based on the given `optionsClasses`.
         */
        fun optionsClasses(optionsClasses: Iterable<out java.lang.Class<out com.google.devtools.common.options.OptionsBase?>?>): Builder {
            return this.optionsData(
                com.google.devtools.common.options.OptionsParser.Companion.getOptionsDataInternal(
                    com.google.common.collect.ImmutableList.copyOf<java.lang.Class<out com.google.devtools.common.options.OptionsBase?>?>(
                        optionsClasses
                    ), false
                ) as com.google.devtools.common.options.OpaqueOptionsData?
            )
        }

        /**
         * Enables the Parser to handle params files using the provided [ParamsFilePreProcessor].
         */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun argsPreProcessor(preProcessor: com.google.devtools.common.options.ArgsPreProcessor?): Builder {
            this.implBuilder.argsPreProcessor(preProcessor)
            return this
        }

        /** Skip all the prefixes associated with Starlark options  */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun skipStarlarkOptionPrefixes(): Builder {
            for (prefix in com.google.devtools.common.options.OptionsParser.Companion.STARLARK_SKIPPED_PREFIXES) {
                this.implBuilder.skippedPrefix(prefix)
            }

            return this
        }

        /**
         * Indicates whether or not the parser will allow a non-empty residue; that is, iff this value
         * is true then a call to one of the `parse` methods will throw [ ] unless [.getResidue] is empty after parsing.
         */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun allowResidue(allowResidue: Boolean): Builder {
            this.allowResidue = allowResidue
            return this
        }

        /** Sets whether the parser should ignore internal-only options.  */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun ignoreInternalOptions(ignoreInternalOptions: Boolean): Builder {
            this.implBuilder.ignoreInternalOptions(ignoreInternalOptions)
            return this
        }

        /** Sets whether the parser should ignore user options. If true, returns no user options.  */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun ignoreUserOptions(): Builder {
            this.ignoreUserOptions = true
            return this
        }

        /** Sets the string the parser should look for as an identifier for flag aliases.  */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun withAliasFlag(aliasFlag: String?): Builder {
            this.implBuilder.withAliasFlag(aliasFlag)
            return this
        }

        /**
         * Adds a map of flag aliases for the OptionsParser to reference. The keys are the aliases and
         * the values are the actual options.
         */
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun withAliases(aliases: MutableMap<String?, String?>?): Builder {
            this.implBuilder.withAliases(aliases)
            return this
        }

        fun withConversionContext(conversionContext: Any?): Builder {
            this.implBuilder.withConversionContext(conversionContext)
            return this
        }

        /** Returns a new [OptionsParser].  */
        fun build(): OptionsParser {
            return com.google.devtools.common.options.OptionsParser(
                implBuilder.build(),
                allowResidue,
                ignoreUserOptions
            )
        }
    }

    fun toBuilder(): Builder {
        return com.google.devtools.common.options.OptionsParser.Builder(impl.toBuilder()).allowResidue(allowResidue)
    }

    private val impl: com.google.devtools.common.options.OptionsParserImpl
    private val residue: MutableList<String?> = java.util.ArrayList<String?>()
    private val postDoubleDashResidue: MutableList<String?> = java.util.ArrayList<String?>()
    private val allowResidue: Boolean
    private val ignoreUserOptions: Boolean

    private var starlarkOptions: com.google.common.collect.ImmutableSortedMap<String?, Any?> =
        com.google.common.collect.ImmutableSortedMap.of<String?, Any?>()
    private var starlarkOptionsAllowingMultiple: com.google.common.collect.ImmutableSet<String?> =
        com.google.common.collect.ImmutableSet.of<String?>()

    // scopes for starlark options
    private var scopesAttributes: com.google.common.collect.ImmutableSortedMap<String?, String?> =
        com.google.common.collect.ImmutableSortedMap.of<String?, String?>()
    private var onLeaveScopeValues: com.google.common.collect.ImmutableSortedMap<String?, Any?> =
        com.google.common.collect.ImmutableSortedMap.of<String?, Any?>()
    private val aliases: MutableMap<String?, String?> = HashMap<String?, String?>()
    private var success = true

    init {
        this.impl = impl
        this.allowResidue = allowResidue
        this.ignoreUserOptions = ignoreUserOptions
    }

    fun getConversionContext(): Any? {
        return impl.getConversionContext()
    }

    override fun getStarlarkOptions(): com.google.common.collect.ImmutableSortedMap<String?, Any?> {
        return starlarkOptions
    }

    override fun getScopesAttributes(): com.google.common.collect.ImmutableMap<String?, String?> {
        return scopesAttributes
    }

    override fun getOnLeaveScopeValues(): com.google.common.collect.ImmutableMap<String?, Any?> {
        return onLeaveScopeValues
    }

    override fun getExplicitCommandLineStarlarkOptions(): com.google.common.collect.ImmutableSortedMap<String?, Any?> {
        val explicitOptions: com.google.common.collect.ImmutableSet<String?> =
            impl.getSkippedOptions().stream()
                .filter(
                    java.util.function.Predicate { d: com.google.devtools.common.options.ParsedOptionDescription? ->
                        d.isExplicit()
                                && (d.getPriority().getPriorityCategory()
                                == com.google.devtools.common.options.OptionPriority.PriorityCategory.COMMAND_LINE)
                    }) // Since this was passed from OptionsParserImpl unparsed, it still appears in its raw
                // form "--//foo=bar". Do some more string manipulation to reduce it to "//foo". By
                // contract, getStarlarkOptions(), which we compare against below, contains options that
                // were fully parsed by StarlarkOptionsParser. So the keys of that method are already in
                // "//foo" form.
                // TODO(https://github.com/bazelbuild/bazel/issues/17414): integrate Starlark and native
                // options parsing more tightly together in the options parsing logic. The complication
                // is that getSkippedOptions, which comes from OptionsParserImpl, has the
                // ParsedOptionsDescription structure which includes where the option comes from (i.e.
                // from a blazerc). But it doesn't have the <String, Object> map of the actually parsed
                // Starlark option. StarlarkOptionsParser is the exact converse. It'd be nice to have
                // common logic that could store both pieces of information so we don't have to
                // awkwardly synthesize the data we need from both sources here.
                .map<String?>(java.util.function.Function { d: com.google.devtools.common.options.ParsedOptionDescription? ->
                    com.google.common.collect.Iterables.get<String?>(
                        com.google.common.base.Splitter.on('=').split(d.getCommandLineForm().substring(2)),
                        0
                    )
                })
                .collect(com.google.common.collect.ImmutableSet.toImmutableSet<String?>())
        val result: com.google.common.collect.ImmutableSortedMap.Builder<String?, Any?> =
            com.google.common.collect.ImmutableSortedMap.naturalOrder<String?, Any?>()
        for (entry in getStarlarkOptions().entrySet()) {
            // getSkippedOptions() doesn't necessarily *only* have Starlark options. By comparing here we
            // filter to just Starlark options.
            if (explicitOptions.contains(entry.getKey())) {
                result.put(entry)
            }
        }
        return result.buildOrThrow()
    }

    override fun getStarlarkOptionsAllowingMultiple(): com.google.common.collect.ImmutableSet<String?> {
        return starlarkOptionsAllowingMultiple
    }

    fun setStarlarkOptions(
        starlarkOptions: MutableMap<String?, Any?>, starlarkOptionsAllowingMultiple: MutableSet<String?>
    ) {
        this.starlarkOptions = com.google.common.collect.ImmutableSortedMap.copyOf<String?, Any?>(starlarkOptions)
        this.starlarkOptionsAllowingMultiple =
            com.google.common.collect.ImmutableSet.copyOf<String?>(starlarkOptionsAllowingMultiple)
    }

    fun setScopesAttributes(scopesAttributes: MutableMap<String?, String?>) {
        this.scopesAttributes = com.google.common.collect.ImmutableSortedMap.copyOf<String?, String?>(scopesAttributes)
    }

    fun setOnLeaveScopeValues(onLeaveScopeValues: MutableMap<String?, Any?>) {
        this.onLeaveScopeValues = com.google.common.collect.ImmutableSortedMap.copyOf<String?, Any?>(onLeaveScopeValues)
    }

    fun parseAndExitUponError(args: Array<String>) {
        parseAndExitUponError(
            com.google.devtools.common.options.OptionPriority.PriorityCategory.COMMAND_LINE,
            "unknown",
            args
        )
    }

    /**
     * A convenience function for use in main methods. Parses the command line parameters, and exits
     * upon error. Also, prints out the usage message if "--help" appears anywhere within `args`.
     */
    fun parseAndExitUponError(
        priority: com.google.devtools.common.options.OptionPriority.PriorityCategory?,
        source: String?,
        args: Array<String>
    ) {
        for (arg in args) {
            if (arg == "--help") {
                java.lang.System.out.println(
                    describeOptionsWithDeprecatedCategories(
                        com.google.common.collect.ImmutableMap.of<String?, String?>(),
                        com.google.devtools.common.options.HelpVerbosity.LONG
                    )
                )

                java.lang.System.exit(0)
            }
        }
        try {
            parse(priority, source, java.util.Arrays.asList<String?>(*args))
        } catch (e: com.google.devtools.common.options.OptionsParsingException) {
            java.lang.System.err.println("Error parsing command line: " + e.getMessage())
            java.lang.System.err.println("Try --help.")
            java.lang.System.exit(2)
        }
    }

    /** The metadata about an option, in the context of this options parser.  */
    class OptionDescription internal constructor(
        definition: com.google.devtools.common.options.OptionDefinition,
        optionsData: com.google.devtools.common.options.OptionsData
    ) {
        private val optionDefinition: com.google.devtools.common.options.OptionDefinition
        private val evaluatedExpansion: com.google.common.collect.ImmutableList<String?>

        init {
            this.optionDefinition = definition
            this.evaluatedExpansion = optionsData.getEvaluatedExpansion(optionDefinition)
        }

        fun getOptionDefinition(): com.google.devtools.common.options.OptionDefinition {
            return optionDefinition
        }

        fun isExpansion(): Boolean {
            return optionDefinition.isExpansionOption()
        }

        override fun equals(obj: Any?): Boolean {
            if (obj is OptionDescription) {
                val other = obj
                // Check that the option is the same, with the same expansion.
                return other.optionDefinition == optionDefinition
                        && other.evaluatedExpansion == evaluatedExpansion
            }
            return false
        }

        override fun hashCode(): Int {
            return optionDefinition.hashCode() + evaluatedExpansion.hashCode()
        }
    }

    /**
     * Returns a description of all the options this parser can digest. In addition to [Option]
     * annotations, this method also interprets [OptionsUsage] annotations which give an
     * intuitive short description for the options. Options of the same category (see [ ]) will be grouped together.
     * 
     * @param helpVerbosity if `long`, the options will be described verbosely, including their
     * types, defaults and descriptions. If `medium`, the descriptions are omitted, and if
     * `short`, the options are just enumerated.
     */
    fun describeOptions(helpVerbosity: com.google.devtools.common.options.HelpVerbosity?): String {
        val desc: java.lang.StringBuilder = java.lang.StringBuilder()
        val optionsByCategory: LinkedHashMap<com.google.devtools.common.options.OptionDocumentationCategory?, MutableList<com.google.devtools.common.options.OptionDefinition?>?> =
            getOptionsSortedByCategory()
        val optionCategoryDescriptions: com.google.common.collect.ImmutableMap<com.google.devtools.common.options.OptionDocumentationCategory?, String?> =
            com.google.devtools.common.options.OptionFilterDescriptions.getOptionCategoriesEnumDescription()
        for (e in optionsByCategory.entrySet()) {
            val categoryDescription: String? = optionCategoryDescriptions.get(e.getKey())
            val categorizedOptionList: MutableList<com.google.devtools.common.options.OptionDefinition?> = e.getValue()

            // Describe the category if we're going to end up using it at all.
            if (!categorizedOptionList.isEmpty()) {
                desc.append("\n").append(categoryDescription).append(":\n")
            }
            // Describe the options in this category.
            for (optionDef in categorizedOptionList) {
                com.google.devtools.common.options.OptionsUsage.getUsage(
                    optionDef,
                    desc,
                    helpVerbosity,
                    impl.getOptionsData(),
                    true
                )
            }
        }

        return desc.toString().trim()
    }

    /**
     * Returns all documented options loaded in this parser, grouped by categories in display order.
     */
    fun getOptionsSortedByCategory(): LinkedHashMap<com.google.devtools.common.options.OptionDocumentationCategory?, MutableList<com.google.devtools.common.options.OptionDefinition?>?> {
        val data: com.google.devtools.common.options.OptionsData = impl.getOptionsData()
        if (data.getOptionsClasses().isEmpty()) {
            return LinkedHashMap<com.google.devtools.common.options.OptionDocumentationCategory?, MutableList<com.google.devtools.common.options.OptionDefinition?>?>()
        }

        // Get the documented options grouped by category.
        val optionsByCategories: com.google.common.collect.ListMultimap<com.google.devtools.common.options.OptionDocumentationCategory?, com.google.devtools.common.options.OptionDefinition?> =
            com.google.common.collect.ArrayListMultimap.create<com.google.devtools.common.options.OptionDocumentationCategory?, com.google.devtools.common.options.OptionDefinition?>()
        for (optionsClass in data.getOptionsClasses()) {
            for (optionDefinition in com.google.devtools.common.options.IsolatedOptionsData.Companion.getAllOptionDefinitionsForClass(
                optionsClass
            )) {
                // Only track documented options.
                if (optionDefinition.getDocumentationCategory()
                    != com.google.devtools.common.options.OptionDocumentationCategory.UNDOCUMENTED
                ) {
                    optionsByCategories.put(optionDefinition.getDocumentationCategory(), optionDefinition)
                }
            }
        }

        // Put the categories into display order and sort the options in each category.
        val sortedCategoriesToOptions: LinkedHashMap<com.google.devtools.common.options.OptionDocumentationCategory?, MutableList<com.google.devtools.common.options.OptionDefinition?>?> =
            LinkedHashMap<com.google.devtools.common.options.OptionDocumentationCategory?, MutableList<com.google.devtools.common.options.OptionDefinition?>?>(
                com.google.devtools.common.options.OptionFilterDescriptions.documentationOrder.size,
                1f
            )
        for (category in com.google.devtools.common.options.OptionFilterDescriptions.documentationOrder) {
            val optionList: MutableList<com.google.devtools.common.options.OptionDefinition?> =
                optionsByCategories.get(category)
            if (optionList != null) {
                optionList.sort(com.google.devtools.common.options.OptionDefinition.Companion.BY_OPTION_NAME)
                sortedCategoriesToOptions.put(category, optionList)
            }
        }
        return sortedCategoriesToOptions
    }

    /**
     * Returns a description of all the options this parser can digest. In addition to [Option]
     * annotations, this method also interprets [OptionsUsage] annotations which give an
     * intuitive short description for the options. Options of the same category (see [ ][Option.category]) will be grouped together.
     * 
     * @param categoryDescriptions a mapping from category names to category descriptions.
     * Descriptions are optional; if omitted, a string based on the category name will be used.
     * @param helpVerbosity if `long`, the options will be described verbosely, including their
     * types, defaults and descriptions. If `medium`, the descriptions are omitted, and if
     * `short`, the options are just enumerated.
     */
    @Deprecated("")
    fun describeOptionsWithDeprecatedCategories(
        categoryDescriptions: MutableMap<String?, String?>,
        helpVerbosity: com.google.devtools.common.options.HelpVerbosity?
    ): String {
        val data: com.google.devtools.common.options.OptionsData = impl.getOptionsData()
        val desc: java.lang.StringBuilder = java.lang.StringBuilder()
        if (!data.getOptionsClasses().isEmpty()) {
            val allFields: MutableList<com.google.devtools.common.options.OptionDefinition> =
                java.util.ArrayList<com.google.devtools.common.options.OptionDefinition>()
            for (optionsClass in data.getOptionsClasses()) {
                allFields.addAll(
                    com.google.devtools.common.options.IsolatedOptionsData.Companion.getAllOptionDefinitionsForClass(
                        optionsClass
                    )
                )
            }
            Collections.sort<com.google.devtools.common.options.OptionDefinition?>(
                allFields,
                com.google.devtools.common.options.OptionDefinition.Companion.BY_CATEGORY
            )
            var prevCategory: String? = null

            for (optionDefinition in allFields) {
                val category: String = optionDefinition.getOptionCategory()
                if (category != prevCategory && (optionDefinition.getDocumentationCategory()
                            != com.google.devtools.common.options.OptionDocumentationCategory.UNDOCUMENTED)
                ) {
                    var description = categoryDescriptions.get(category)
                    if (description == null) {
                        description = "Options category '" + category + "'"
                    }
                    desc.append("\n").append(description).append(":\n")
                    prevCategory = category
                }

                if (optionDefinition.getDocumentationCategory()
                    != com.google.devtools.common.options.OptionDocumentationCategory.UNDOCUMENTED
                ) {
                    com.google.devtools.common.options.OptionsUsage.getUsage(
                        optionDefinition, desc, helpVerbosity, impl.getOptionsData(), false
                    )
                }
            }
        }
        return desc.toString().trim()
    }

    /**
     * Returns a string listing the possible flag completion for this command along with the command
     * completion if any. See [OptionsUsage.getCompletion] for
     * more details on the format for the flag completion.
     */
    fun getOptionsCompletion(): String {
        val desc: java.lang.StringBuilder = java.lang.StringBuilder()

        visitOptions(
            java.util.function.Predicate { optionDefinition: com.google.devtools.common.options.OptionDefinition? -> optionDefinition.getDocumentationCategory() != com.google.devtools.common.options.OptionDocumentationCategory.UNDOCUMENTED },
            java.util.function.Consumer { optionDefinition: com.google.devtools.common.options.OptionDefinition? ->
                com.google.devtools.common.options.OptionsUsage.getCompletion(
                    optionDefinition,
                    desc
                )
            })

        return desc.toString()
    }

    fun visitOptions(
        predicate: java.util.function.Predicate<com.google.devtools.common.options.OptionDefinition?>?,
        visitor: java.util.function.Consumer<com.google.devtools.common.options.OptionDefinition?>?
    ) {
        com.google.common.base.Preconditions.checkNotNull<java.util.function.Predicate<com.google.devtools.common.options.OptionDefinition?>?>(
            predicate,
            "Missing predicate."
        )
        com.google.common.base.Preconditions.checkNotNull<java.util.function.Consumer<com.google.devtools.common.options.OptionDefinition?>?>(
            visitor,
            "Missing visitor."
        )

        val data: com.google.devtools.common.options.OptionsData = impl.getOptionsData()
        data
            .getOptionsClasses() // List all options
            .stream()
            .flatMap<com.google.devtools.common.options.OptionDefinition?>(java.util.function.Function { optionsClass: java.lang.Class<out com.google.devtools.common.options.OptionsBase?>? ->
                com.google.devtools.common.options.IsolatedOptionsData.Companion.getAllOptionDefinitionsForClass(
                    optionsClass
                ).stream()
            }) // Sort field for deterministic ordering
            .sorted(com.google.devtools.common.options.OptionDefinition.Companion.BY_OPTION_NAME)
            .filter(predicate)
            .forEach(visitor)
    }

    /**
     * Returns a description of the option.
     * 
     * @return The [OptionDescription] for the option, or null if there is no option by the
     * given name.
     */
    @Throws(com.google.devtools.common.options.OptionsParsingException::class)
    fun getOptionDescription(name: String?): OptionDescription? {
        return impl.getOptionDescription(name)
    }

    /**
     * Returns the parsed options that get expanded from this option, whether it expands due to an
     * implicit requirement or expansion.
     * 
     * @param expansionOption the option that might need to be expanded. If this option does not
     * expand to other options, the empty list will be returned.
     * @param originOfExpansionOption the origin of the option that's being expanded. This function
     * will take care of adjusting the source messages as necessary.
     */
    @Throws(com.google.devtools.common.options.OptionsParsingException::class)
    fun getExpansionValueDescriptions(
        expansionOption: com.google.devtools.common.options.OptionDefinition?,
        originOfExpansionOption: com.google.devtools.common.options.OptionInstanceOrigin
    ): com.google.common.collect.ImmutableList<com.google.devtools.common.options.ParsedOptionDescription?>? {
        return impl.getExpansionValueDescriptions(expansionOption, originOfExpansionOption)
    }

    /**
     * {@inheritDoc}
     * 
     * 
     * Returns the value set by the last previous call to [ ][.parse] that successfully set the given option.
     * If the option is of type [List], the description will correspond to any one of the calls,
     * but not necessarily the last.
     */
    override fun getOptionValueDescription(name: String?): com.google.devtools.common.options.OptionValueDescription? {
        return impl.getOptionValueDescription(name)
    }

    /**
     * A convenience method, equivalent to `parse(PriorityCategory.COMMAND_LINE, null, Arrays.asList(args))`.
     */
    @Throws(com.google.devtools.common.options.OptionsParsingException::class)
    fun parse(vararg args: String?) {
        parse(
            com.google.devtools.common.options.OptionPriority.PriorityCategory.COMMAND_LINE,
            null,
            java.util.Arrays.asList<String?>(*args)
        )
    }

    /**
     * A convenience method, equivalent to `parse(PriorityCategory.COMMAND_LINE, null, args)`.
     */
    @Throws(com.google.devtools.common.options.OptionsParsingException::class)
    fun parse(args: MutableList<String?>) {
        parse(com.google.devtools.common.options.OptionPriority.PriorityCategory.COMMAND_LINE, null, args)
    }

    /**
     * Parses `args`, using the classes registered with this parser, at the given priority.
     * 
     * 
     * May be called multiple times; later options override existing ones if they have equal or
     * higher priority. Strings that cannot be parsed as options are accumulated as residue, if this
     * parser allows it.
     * 
     * 
     * [.getOptions] and [.getResidue] will return the results.
     * 
     * @param priority the priority at which to parse these options. Within this priority category,
     * each option will be given an index to track its position. If parse() has already been
     * called at this priority, the indexing will continue where it left off, to keep ordering.
     * @param source the source to track for each option parsed.
     * @param args the arg list to parse. Each element might be an option, a value linked to an
     * option, or residue.
     */
    @Throws(com.google.devtools.common.options.OptionsParsingException::class)
    fun parse(
        priority: com.google.devtools.common.options.OptionPriority.PriorityCategory?,
        source: String?,
        args: MutableList<String?>
    ) {
        parseWithSourceFunction(
            priority,
            java.util.function.Function { o: com.google.devtools.common.options.OptionDefinition? -> source },
            args,  /* fallbackData= */
            null
        )
    }

    /**
     * Parses `args`, using the classes registered with this parser, at the given priority.
     * 
     * 
     * May be called multiple times; later options override existing ones if they have equal or
     * higher priority. Strings that cannot be parsed as options are accumulated as residue, if this
     * parser allows it.
     * 
     * 
     * [.getOptions] and [.getResidue] will return the results.
     * 
     * @param priority the priority at which to parse these options. Within this priority category,
     * each option will be given an index to track its position. If parse() has already been
     * called at this priority, the indexing will continue where it left off, to keep ordering.
     * @param sourceFunction a function that maps option names to the source of the option.
     * @param args the arg list to parse. Each element might be an option, a value linked to an
     * option, or residue.
     * @return a list of options and values that were parsed but ignored due to only resolving against
     * the fallback data
     */
    @com.google.errorprone.annotations.CanIgnoreReturnValue
    @Throws(com.google.devtools.common.options.OptionsParsingException::class)
    fun parseWithSourceFunction(
        priority: com.google.devtools.common.options.OptionPriority.PriorityCategory?,
        sourceFunction: java.util.function.Function<com.google.devtools.common.options.OptionDefinition?, String?>?,
        args: MutableList<String?>,
        fallbackData: com.google.devtools.common.options.OpaqueOptionsData?
    ): com.google.common.collect.ImmutableList<String?> {
        com.google.common.base.Preconditions.checkNotNull<com.google.devtools.common.options.OptionPriority.PriorityCategory?>(
            priority
        )
        com.google.common.base.Preconditions.checkArgument(priority != com.google.devtools.common.options.OptionPriority.PriorityCategory.DEFAULT)
        val optionsParserImplResult: com.google.devtools.common.options.OptionsParserImpl.OptionsParserImplResult =
            impl.parse(
                priority,
                sourceFunction,
                com.google.devtools.common.options.OptionsParser.ArgAndFallbackData.Companion.wrapWithFallbackData(
                    args,
                    fallbackData
                )
            )
        addResidueFromResult(optionsParserImplResult)
        aliases.putAll(optionsParserImplResult.aliases)
        return optionsParserImplResult.ignoredArgs
    }

    /**
     * Parses the args at the priority of the provided option. This is useful for after-the-fact
     * expansion.
     * 
     * @param optionToExpand the option that is being "expanded" after the fact. The provided args
     * will have the same priority as this option.
     * @param source a description of where the expansion arguments came from.
     * @param args the arguments to parse as the expansion. Order matters, as the value of a flag may
     * be in the following argument. Each arg is optionally annotated with the full collection of
     * options that should be parsed and ignored without raising an error if they are not
     * recognized by the options classes registered with this parser.
     * @return a list of options and values that were parsed but ignored due to only resolving against
     * the fallback data
     */
    @com.google.errorprone.annotations.CanIgnoreReturnValue
    @Throws(com.google.devtools.common.options.OptionsParsingException::class)
    fun parseArgsAsExpansionOfOption(
        optionToExpand: com.google.devtools.common.options.ParsedOptionDescription?,
        source: String?,
        args: MutableList<ArgAndFallbackData?>?
    ): com.google.common.collect.ImmutableList<String?> {
        com.google.common.base.Preconditions.checkNotNull<com.google.devtools.common.options.ParsedOptionDescription?>(
            optionToExpand, "Option for expansion not specified for arglist %s", args
        )
        com.google.common.base.Preconditions.checkArgument(
            optionToExpand.getPriority().getPriorityCategory()
                    != com.google.devtools.common.options.OptionPriority.PriorityCategory.DEFAULT,
            "Priority cannot be default, which was specified for arglist %s",
            args
        )
        val optionsParserImplResult: com.google.devtools.common.options.OptionsParserImpl.OptionsParserImplResult =
            impl.parseArgsAsExpansionOfOption(
                optionToExpand,
                java.util.function.Function { o: com.google.devtools.common.options.OptionDefinition? -> source },
                args
            )
        addResidueFromResult(optionsParserImplResult)
        return optionsParserImplResult.ignoredArgs
    }

    @Throws(com.google.devtools.common.options.OptionsParsingException::class)
    private fun addResidueFromResult(result: com.google.devtools.common.options.OptionsParserImpl.OptionsParserImplResult) {
        residue.addAll(result.getResidue())
        postDoubleDashResidue.addAll(result.postDoubleDashResidue)
        if (!allowResidue && !residue.isEmpty()) {
            val errorMsg = "Unrecognized arguments: " + com.google.common.base.Joiner.on(' ').join(residue)
            throw com.google.devtools.common.options.OptionsParsingException(errorMsg)
        }
    }

    /**
     * Sets provided value for a flag with a particular priority. This only sets the value of the flag
     * itself and does not affect any of its implicit requirements or expansions.
     * 
     * @param origin the origin of this option instance, it includes the priority of the value. If
     * other values have already been or will be parsed at a higher priority, they might override
     * the provided value. If this option already has a value at this priority, this value will
     * have precedence, but this should be avoided, as it breaks order tracking.
     * @param option the option to add the value for.
     * @param value the value to add at the given priority.
     */
    @Throws(com.google.devtools.common.options.OptionsParsingException::class)
    fun setOptionValueAtSpecificPriorityWithoutExpansion(
        origin: com.google.devtools.common.options.OptionInstanceOrigin?,
        option: com.google.devtools.common.options.OptionDefinition?,
        value: String?
    ) {
        impl.setOptionValueAtSpecificPriorityWithoutExpansion(origin, option, value)
    }

    /**
     * Clears the given option.
     * 
     * 
     * This will not affect options objects that have already been retrieved from this parser
     * through [.getOptions].
     * 
     * @param option The option to clear.
     * @return The old value of the option that was cleared.
     * @throws IllegalArgumentException If the flag does not exist.
     */
    @Throws(com.google.devtools.common.options.OptionsParsingException::class)
    fun clearValue(option: com.google.devtools.common.options.OptionDefinition?): com.google.devtools.common.options.OptionValueDescription? {
        return impl.clearValue(option)
    }

    override fun getAliases(): MutableMap<String?, String?> {
        return com.google.common.collect.ImmutableMap.copyOf<String?, String?>(aliases)
    }

    /** Makes [.success] return false.  */
    fun setError() {
        success = false
    }

    override fun success(): Boolean {
        return success
    }

    override fun getSkippedArgs(): com.google.common.collect.ImmutableList<String?> {
        return com.google.common.collect.ImmutableList.copyOf<String?>(impl.getSkippedArgs())
    }

    override fun getResidue(): com.google.common.collect.ImmutableList<String?> {
        return com.google.common.collect.ImmutableList.copyOf<String?>(residue)
    }

    override fun getPreDoubleDashResidue(): MutableList<String?> {
        return if (postDoubleDashResidue.isEmpty())
            com.google.common.collect.ImmutableList.copyOf<String?>(residue)
        else
            residue.stream()
                .filter(java.util.function.Predicate { residue: String? -> !postDoubleDashResidue.contains(residue) })
                .collect(com.google.common.collect.ImmutableList.toImmutableList<String?>())
    }

    /**
     * Sets the residue (all elements parsed as non-options) to `residue`, as well as the part
     * of the residue that follows the double-dash on the command line, `postDoubleDashResidue`.
     * `postDoubleDashResidue` must be a subset of `residue`.
     */
    fun setResidue(residue: MutableList<String>, postDoubleDashResidue: MutableList<String>?) {
        com.google.common.base.Preconditions.checkArgument(residue.containsAll(postDoubleDashResidue!!))
        this.residue.clear()
        this.residue.addAll(residue)
        this.postDoubleDashResidue.clear()
        this.postDoubleDashResidue.addAll(postDoubleDashResidue)
    }

    /** Returns a list of warnings about problems encountered by previous parse calls.  */
    fun getWarnings(): com.google.common.collect.ImmutableList<String?>? {
        return impl.getWarnings()
    }

    override fun <O : com.google.devtools.common.options.OptionsBase?> getOptions(optionsClass: java.lang.Class<O?>?): O? {
        return impl.getParsedOptions<O?>(optionsClass)
    }

    override fun containsExplicitOption(name: String?): Boolean {
        return impl.containsExplicitOption(name)
    }

    override fun asCompleteListOfParsedOptions(): MutableList<com.google.devtools.common.options.ParsedOptionDescription?>? {
        return impl.asCompleteListOfParsedOptions()
    }

    override fun asListOfExplicitOptions(): MutableList<com.google.devtools.common.options.ParsedOptionDescription?>? {
        return impl.asListOfExplicitOptions()
    }

    override fun asListOfCanonicalOptions(): MutableList<com.google.devtools.common.options.ParsedOptionDescription?>? {
        return impl.asCanonicalizedListOfParsedOptions()
    }

    override fun asListOfOptionValues(): MutableList<com.google.devtools.common.options.OptionValueDescription?>? {
        return impl.asListOfEffectiveOptions()
    }

    override fun allOptionValues(): MutableList<com.google.devtools.common.options.OptionValueDescription?>? {
        return impl.allOptionValues()
    }

    override fun canonicalize(): MutableList<String?>? {
        return impl.asCanonicalizedList()
    }

    override fun getUserOptions(): com.google.common.collect.ImmutableMap<String?, String?> {
        if (ignoreUserOptions) {
            return com.google.common.collect.ImmutableMap.of<String?, String?>()
        }

        // First collect to a hashmap to deduplicate options.
        val userOptions: HashMap<String?, String?> = HashMap<String?, String?>()

        asCompleteListOfParsedOptions().stream()
            .filter(com.google.devtools.common.options.GlobalRcUtils.IS_GLOBAL_RC_OPTION.negate())
            .filter(java.util.function.Predicate { option: com.google.devtools.common.options.ParsedOptionDescription? ->
                !option.getCanonicalForm().contains("default_override")
            })
            .forEach(java.util.function.Consumer { option: com.google.devtools.common.options.ParsedOptionDescription? ->
                userOptions.put(
                    option.getCanonicalForm(),
                    com.google.devtools.common.options.OptionsParser.Companion.getFinalExpansion(option)
                )
            })
        impl.getSkippedOptions().stream()
            .filter(com.google.devtools.common.options.GlobalRcUtils.IS_GLOBAL_RC_OPTION.negate())
            .map<String?>(java.util.function.Function { option: com.google.devtools.common.options.ParsedOptionDescription? -> option.getUnconvertedValue() })
            .filter(
                java.util.function.Predicate { o: String? ->
                    getStarlarkOptions()
                        .containsKey(
                            com.google.common.collect.Iterables.get<String?>(
                                com.google.common.base.Splitter.on(
                                    '='
                                ).split(o.replace("--", "")), 0
                            )
                        )
                })
            .forEach(java.util.function.Consumer { option: String? -> userOptions.put(option, "") })

        return com.google.common.collect.ImmutableMap.copyOf<String?, String?>(userOptions)
    }

    /**
     * A container for an arg and associated options that should be silently ignored when parsed but
     * not recognized by the current command.
     */
    class ArgAndFallbackData(arg: String?, fallbackData: com.google.devtools.common.options.OpaqueOptionsData?) {
        @kotlin.jvm.JvmField
        val arg: String
        val fallbackData: com.google.devtools.common.options.OptionsData?

        init {
            this.arg = com.google.common.base.Preconditions.checkNotNull<String>(arg)
            this.fallbackData = fallbackData as com.google.devtools.common.options.OptionsData?
        }

        companion object {
            fun wrapWithFallbackData(
                args: MutableList<String?>, fallbackData: com.google.devtools.common.options.OpaqueOptionsData?
            ): MutableList<ArgAndFallbackData?> {
                return com.google.common.collect.Lists.transform<String?, ArgAndFallbackData?>(
                    args,
                    com.google.common.base.Function { arg: String? ->
                        com.google.devtools.common.options.OptionsParser.ArgAndFallbackData(
                            arg,
                            fallbackData
                        )
                    })
            }
        }
    }

    companion object {
        /**
         * A cache for the parsed options data. Both keys and values are immutable, so this is always
         * safe. Only access this field through the [.getOptionsData] method for thread-safety! The
         * cache is very unlikely to grow to a significant amount of memory, because there's only a fixed
         * set of options classes on the classpath.
         */
        private val optionsData: MutableMap<com.google.devtools.build.lib.util.Pair<com.google.common.collect.ImmutableList<java.lang.Class<out com.google.devtools.common.options.OptionsBase?>?>?, Boolean?>?, com.google.devtools.common.options.OptionsData?> =
            HashMap<com.google.devtools.build.lib.util.Pair<com.google.common.collect.ImmutableList<java.lang.Class<out com.google.devtools.common.options.OptionsBase?>?>?, Boolean?>?, com.google.devtools.common.options.OptionsData?>()

        /** Skipped prefixes for starlark options.  */
        val STARLARK_SKIPPED_PREFIXES: com.google.common.collect.ImmutableList<String?> =
            com.google.common.collect.ImmutableList.of<String?>("--//", "--no//", "--@", "--no@")

        /**
         * Returns [OpaqueOptionsData] suitable for passing along to [ ][Builder.optionsData].
         * 
         * 
         * This is useful when you want to do the work of analyzing the given `optionsClasses`
         * exactly once, but you want to parse lots of different lists of strings (and thus need to
         * construct lots of different [OptionsParser] instances).
         */
        fun getOptionsData(
            optionsClasses: MutableList<java.lang.Class<out com.google.devtools.common.options.OptionsBase?>?>
        ): com.google.devtools.common.options.OpaqueOptionsData {
            return com.google.devtools.common.options.OptionsParser.Companion.getOptionsDataInternal(
                optionsClasses,
                false
            )
        }

        fun getFallbackOptionsData(
            optionsClasses: MutableList<java.lang.Class<out com.google.devtools.common.options.OptionsBase?>?>
        ): com.google.devtools.common.options.OpaqueOptionsData {
            return com.google.devtools.common.options.OptionsParser.Companion.getOptionsDataInternal(
                optionsClasses,
                true
            )
        }

        /** Returns the [OptionsData] associated with the given list of options classes.  */
        @kotlin.jvm.Synchronized
        fun getOptionsDataInternal(
            optionsClasses: MutableList<java.lang.Class<out com.google.devtools.common.options.OptionsBase?>?>,
            allowDuplicatesParsingEquivalently: Boolean
        ): com.google.devtools.common.options.OptionsData {
            val immutableOptionsClasses: com.google.common.collect.ImmutableList<java.lang.Class<out com.google.devtools.common.options.OptionsBase?>?> =
                com.google.common.collect.ImmutableList.copyOf<java.lang.Class<out com.google.devtools.common.options.OptionsBase?>?>(
                    optionsClasses
                )
            val cacheKey: com.google.devtools.build.lib.util.Pair<com.google.common.collect.ImmutableList<java.lang.Class<out com.google.devtools.common.options.OptionsBase?>?>?, Boolean?> =
                com.google.devtools.build.lib.util.Pair.Companion.of<com.google.common.collect.ImmutableList<java.lang.Class<out com.google.devtools.common.options.OptionsBase?>?>?, Boolean?>(
                    immutableOptionsClasses,
                    allowDuplicatesParsingEquivalently
                )
            var result: com.google.devtools.common.options.OptionsData? =
                com.google.devtools.common.options.OptionsParser.Companion.optionsData.get(cacheKey)
            if (result == null) {
                try {
                    result = com.google.devtools.common.options.OptionsData.Companion.from(
                        immutableOptionsClasses,
                        allowDuplicatesParsingEquivalently
                    )
                } catch (e: java.lang.Exception) {
                    com.google.common.base.Throwables.throwIfInstanceOf<com.google.devtools.common.options.ConstructionException?>(
                        e,
                        com.google.devtools.common.options.ConstructionException::class.java
                    )
                    throw com.google.devtools.common.options.ConstructionException(e.getMessage(), e)
                }
                com.google.devtools.common.options.OptionsParser.Companion.optionsData.put(cacheKey, result)
            }
            return result
        }

        /** Returns the [OptionsData] associated with the given options class.  */
        fun getOptionsDataInternal(optionsClass: java.lang.Class<out com.google.devtools.common.options.OptionsBase?>): com.google.devtools.common.options.OptionsData {
            return com.google.devtools.common.options.OptionsParser.Companion.getOptionsDataInternal(
                com.google.common.collect.ImmutableList.of<java.lang.Class<out com.google.devtools.common.options.OptionsBase?>?>(
                    optionsClass
                ), false
            )
        }

        /** Returns a new [Builder] to create [OptionsParser] instances.  */
        @kotlin.jvm.JvmStatic
        fun builder(): Builder {
            return com.google.devtools.common.options.OptionsParser.Builder(com.google.devtools.common.options.OptionsParserImpl.Companion.builder())
        }

        private fun getFinalExpansion(option: com.google.devtools.common.options.ParsedOptionDescription): String? {
            var option: com.google.devtools.common.options.ParsedOptionDescription = option
            if (option.getExpandedFrom() == null) {
                return ""
            }
            while (option.getExpandedFrom() != null) {
                option = option.getExpandedFrom()
            }
            return option.getCanonicalForm()
        }

        /**
         * Returns the option with the given name from the given class.
         * 
         * 
         * The preferred way of using this method is as the initializer for a static final field in the
         * options class which defines the option. This reduces the possibility that another contributor
         * might change the name of the option without realizing it's used by name elsewhere.
         * 
         * @throws IllegalArgumentException if there are two or more options with that name.
         * @throws java.util.NoSuchElementException if there are no options with that name.
         */
        fun getOptionDefinitionByName(
            optionsClass: java.lang.Class<out com.google.devtools.common.options.OptionsBase?>?, optionName: String?
        ): com.google.devtools.common.options.OptionDefinition {
            return com.google.devtools.common.options.OptionDefinition.Companion.getOptionDefinitions(optionsClass)
                .stream()
                .filter { definition: com.google.devtools.common.options.OptionDefinition -> definition.getOptionName() == optionName }
                .collect(com.google.common.collect.MoreCollectors.onlyElement())
        }
    }
}
