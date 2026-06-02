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

import java.text.BreakIterator
import java.util.Locale
import java.util.stream.Collectors

/** A renderer for usage messages for any combination of options classes.  */
internal object OptionsUsage {
    private val NEWLINE_SPLITTER: com.google.common.base.Splitter = com.google.common.base.Splitter.on('\n')
    private val COMMA_JOINER: com.google.common.base.Joiner = com.google.common.base.Joiner.on(",")

    /**
     * Given an options class, render the usage string into the usage, which is passed in as an
     * argument. This will not include information about expansions for options using expansion
     * functions (it would be unsafe to report this as we cannot know what options from other [ ] subclasses they depend on until a complete parser is constructed).
     */
    fun getUsage(
        optionsClass: java.lang.Class<out com.google.devtools.common.options.OptionsBase?>?,
        usage: java.lang.StringBuilder
    ) {
        val data: com.google.devtools.common.options.OptionsData =
            com.google.devtools.common.options.OptionsParser.Companion.getOptionsDataInternal(optionsClass)
        val optionDefinitions: MutableList<com.google.devtools.common.options.OptionDefinition> =
            java.util.ArrayList<com.google.devtools.common.options.OptionDefinition>(
                com.google.devtools.common.options.IsolatedOptionsData.Companion.getAllOptionDefinitionsForClass(
                    optionsClass
                )
            )
        optionDefinitions.sort(com.google.devtools.common.options.OptionDefinition.Companion.BY_OPTION_NAME)
        for (optionDefinition in optionDefinitions) {
            com.google.devtools.common.options.OptionsUsage.getUsage(
                optionDefinition,
                usage,
                com.google.devtools.common.options.HelpVerbosity.LONG,
                data,
                false
            )
        }
    }

    /** Appends the usage message for a single option-field message to 'usage'.  */
    fun getUsage(
        optionDefinition: com.google.devtools.common.options.OptionDefinition,
        usage: java.lang.StringBuilder,
        helpVerbosity: com.google.devtools.common.options.HelpVerbosity?,
        optionsData: com.google.devtools.common.options.OptionsData,
        includeTags: Boolean
    ) {
        val flagName: String? = com.google.devtools.common.options.OptionsUsage.getFlagName(optionDefinition)
        val typeDescription: String =
            com.google.devtools.common.options.OptionsUsage.getTypeDescription(optionDefinition)
        usage.append("  --").append(flagName)
        if (helpVerbosity == com.google.devtools.common.options.HelpVerbosity.SHORT) {
            usage.append('\n')
            return
        }

        // Add the option's type and default information. Stop there for "medium" verbosity.
        if (optionDefinition.getAbbreviation() != '\u0000') {
            usage.append(" [-").append(optionDefinition.getAbbreviation()).append(']')
        }
        if (!typeDescription.isEmpty()) {
            usage.append(" (").append(typeDescription).append("; ")
            if (optionDefinition.allowsMultiple()) {
                usage.append("may be used multiple times")
            } else {
                // Don't call the annotation directly (we must allow overrides to certain defaults)
                val defaultValueString: String? = optionDefinition.getUnparsedDefaultValue()
                if (optionDefinition.isSpecialNullDefault()) {
                    usage.append("default: see description")
                } else {
                    usage.append("default: \"").append(defaultValueString).append("\"")
                }
            }
            usage.append(")")
        }
        usage.append("\n")
        if (helpVerbosity == com.google.devtools.common.options.HelpVerbosity.MEDIUM) {
            return
        }

        // For verbosity "long," add the full description and expansion, along with the tag
        // information if requested.
        if (!optionDefinition.getHelpText().isEmpty()) {
            usage.append(
                com.google.devtools.common.options.OptionsUsage.paragraphFill(
                    optionDefinition.getHelpText(),  /*indent=*/
                    4,  /*width=*/
                    80
                )
            )
            usage.append('\n')
        }
        val expansion: com.google.common.collect.ImmutableList<String?> =
            optionsData.getEvaluatedExpansion(optionDefinition)
        if (!expansion.isEmpty()) {
            val expandsMsg: java.lang.StringBuilder = java.lang.StringBuilder("Expands to: ")
            for (exp in expansion) {
                expandsMsg.append(exp).append(" ")
            }
            usage.append(
                com.google.devtools.common.options.OptionsUsage.paragraphFill(
                    expandsMsg.toString(),  /*indent=*/
                    6,  /*width=*/
                    80
                )
            )
            usage.append('\n')
        }
        if (optionDefinition.hasImplicitRequirements()) {
            val requiredMsg: java.lang.StringBuilder = java.lang.StringBuilder("Using this option will also add: ")
            for (req in optionDefinition.getImplicitRequirements()) {
                requiredMsg.append(req).append(" ")
            }
            usage.append(
                com.google.devtools.common.options.OptionsUsage.paragraphFill(
                    requiredMsg.toString(),
                    6,
                    80
                )
            ) // (indent, width)
            usage.append('\n')
        }
        if (!includeTags) {
            return
        }

        // If we are expected to include the tags, add them for high verbosity.
        val effectTagStream: java.util.stream.Stream<com.google.devtools.common.options.OptionEffectTag?>? =
            java.util.Arrays.stream<com.google.devtools.common.options.OptionEffectTag?>(optionDefinition.getOptionEffectTags())
                .filter(java.util.function.Predicate { obj: com.google.devtools.common.options.OptionEffectTag? -> com.google.devtools.common.options.OptionsUsage.shouldEffectTagBeListed() })
        val metadataTagStream: java.util.stream.Stream<com.google.devtools.common.options.OptionMetadataTag?>? =
            java.util.Arrays.stream<com.google.devtools.common.options.OptionMetadataTag?>(optionDefinition.getOptionMetadataTags())
                .filter(java.util.function.Predicate { obj: com.google.devtools.common.options.OptionMetadataTag? -> com.google.devtools.common.options.OptionsUsage.shouldMetadataTagBeListed() })
        val tagList: String =
            java.util.stream.Stream.concat<Enum<out Enum<*>?>?>(effectTagStream, metadataTagStream)
                .map<String?>(java.util.function.Function { tag: Enum<out Enum<*>?>? ->
                    com.google.common.base.Ascii.toLowerCase(
                        tag.toString()
                    )
                })
                .collect(Collectors.joining(", "))
        if (!tagList.isEmpty()) {
            usage.append(
                com.google.devtools.common.options.OptionsUsage.paragraphFill(
                    "Tags: " + tagList,
                    6,
                    80
                )
            ) // (indent, width)
            usage.append("\n")
        }
    }

    /**
     * Paragraph-fill the specified input text, indenting lines to 'indent' and wrapping lines at
     * 'w.idth'. Returns the formatted result.
     */
    @kotlin.jvm.JvmStatic
    fun paragraphFill(`in`: String, indent: Int, width: Int): String {
        val indentString: String = " ".repeat(indent)
        val out: java.lang.StringBuilder = java.lang.StringBuilder()
        var sep = ""
        for (paragraph in com.google.devtools.common.options.OptionsUsage.NEWLINE_SPLITTER.split(`in`)) {
            // TODO(ccalvarin) break iterators expect hyphenated words to be line-breakable, which looks
            // funny for --flag
            val boundary: BreakIterator = BreakIterator.getLineInstance() // (factory)
            boundary.setText(paragraph)
            out.append(sep).append(indentString)
            var cursor = indent
            var start: Int = boundary.first()
            var end: Int = boundary.next()
            while (end != BreakIterator.DONE
            ) {
                val word: String =
                    paragraph.substring(start, end) // (may include trailing space)
                if (word.length() + cursor > width) {
                    out.append('\n').append(indentString)
                    cursor = indent
                }
                out.append(word)
                cursor += word.length()
                start = end
                end = boundary.next()
            }
            sep = "\n"
        }
        return out.toString()
    }

    // Placeholder tag "UNKNOWN" is ignored.
    fun shouldEffectTagBeListed(effectTag: com.google.devtools.common.options.OptionEffectTag): Boolean {
        return effectTag != com.google.devtools.common.options.OptionEffectTag.UNKNOWN
    }

    // Tags that only apply to undocumented options are excluded.
    fun shouldMetadataTagBeListed(metadataTag: com.google.devtools.common.options.OptionMetadataTag): Boolean {
        return metadataTag != com.google.devtools.common.options.OptionMetadataTag.HIDDEN && metadataTag != com.google.devtools.common.options.OptionMetadataTag.INTERNAL
    }

    /**
     * Returns the available completion for the given option field. The completions are the exact
     * command line option (with the prepending '--') that one should pass. It is suitable for
     * completion script to use. If the option expect an argument, the kind of argument is given
     * after the equals. If the kind is a enum, the various enum values are given inside an accolade
     * in a comma separated list. For other special kind, the type is given as a name (e.g.,
     * `label`, `float, `path`...). Example outputs of this
     * function are for, respectively, a tristate flag `tristate_flag`, a enum
     * flag `enum_flag` which can take `value1`, `value2` and
     * `value3`, a path fragment flag `path_flag`, a string flag
     * `string_flag` and a void flag `void_flag`:
     * <pre>
     * --tristate_flag={auto,yes,no}
     * --notristate_flag
     * --enum_flag={value1,value2,value3}
     * --path_flag=path
     * --string_flag=
     * --void_flag
    </pre> * 
     * 
     * @param optionDefinition The field to return completion for
     * @param builder the string builder to store the completion values
    ` */
    fun getCompletion(
        optionDefinition: com.google.devtools.common.options.OptionDefinition,
        builder: java.lang.StringBuilder
    ) {
        // Return the list of possible completions for this option
        val flagName: String? = optionDefinition.getOptionName()
        val fieldType: java.lang.Class<*> = optionDefinition.getType()
        builder.append("--").append(flagName)
        if (fieldType == Boolean::class.javaPrimitiveType) {
            builder.append("\n")
            builder.append("--no").append(flagName).append("\n")
        } else if (fieldType == com.google.devtools.common.options.TriState::class.java) {
            builder.append("={auto,yes,no}\n")
            builder.append("--no").append(flagName).append("\n")
        } else if (fieldType.isEnum()) {
            builder
                .append("={")
                .append(
                    com.google.devtools.common.options.OptionsUsage.COMMA_JOINER.join(fieldType.getEnumConstants())
                        .toLowerCase(Locale.ENGLISH)
                )
                .append("}\n")
        } else if (fieldType.getSimpleName() == "Label") {
            // String comparison so we don't introduce a dependency to com.google.devtools.build.lib.
            builder.append("=label\n")
        } else if (fieldType.getSimpleName() == "PathFragment") {
            builder.append("=path\n")
        } else if (java.lang.Void::class.java.isAssignableFrom(fieldType)) {
            builder.append("\n")
        } else {
            // TODO(bazel-team): add more types. Maybe even move the completion type
            // to the @Option annotation?
            builder.append("=\n")
        }
    }

    fun getTypeDescription(optionsDefinition: com.google.devtools.common.options.OptionDefinition): String {
        return optionsDefinition.getConverter().getTypeDescription()
    }

    fun getFlagName(optionDefinition: com.google.devtools.common.options.OptionDefinition): String? {
        val name: String? = optionDefinition.getOptionName()
        return if (optionDefinition.usesBooleanValueSyntax()) "[no]" + name else name
    }
}
