// Copyright 2025 The Bazel Authors. All rights reserved.
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
import com.google.devtools.build.lib.util.Markdown
import java.util.stream.Collectors

/** Utilities related to HTML generation.  */
object HtmlUtils {
    /**
     * Returns a description of all the options the given parser can digest. In addition to [ ] annotations, this method also interprets [OptionsUsage] annotations which give an
     * intuitive short description for the options.
     */
    fun describeOptionsHtml(
        parser: com.google.devtools.common.options.OptionsParser,
        escaper: com.google.common.escape.Escaper,
        optionsToIgnore: MutableList<String?>,
        commandName: String?
    ): String {
        val desc: java.lang.StringBuilder = java.lang.StringBuilder()
        val optionCategoryDescriptions: com.google.common.collect.ImmutableMap<com.google.devtools.common.options.OptionDocumentationCategory?, String?> =
            com.google.devtools.common.options.OptionFilterDescriptions.getOptionCategoriesEnumDescription()

        for (e in parser.getOptionsSortedByCategory().entrySet()) {
            var categorizedOptionsList: MutableList<com.google.devtools.common.options.OptionDefinition> = e.getValue()
            categorizedOptionsList =
                categorizedOptionsList.stream()
                    .filter(
                        java.util.function.Predicate { optionDef: com.google.devtools.common.options.OptionDefinition? ->
                            java.util.Arrays.stream<com.google.devtools.common.options.OptionEffectTag?>(optionDef.getOptionEffectTags())
                                .noneMatch(java.util.function.Predicate { effectTag: com.google.devtools.common.options.OptionEffectTag? -> effectTag == com.google.devtools.common.options.OptionEffectTag.NO_OP })
                        })
                    .filter(java.util.function.Predicate { optionDef: com.google.devtools.common.options.OptionDefinition? ->
                        !optionsToIgnore.contains(
                            optionDef.getOptionName()
                        )
                    })
                    .collect(com.google.common.collect.ImmutableList.toImmutableList<com.google.devtools.common.options.OptionDefinition?>())
            if (categorizedOptionsList.isEmpty()) {
                continue
            }
            val categoryDescription: String? = optionCategoryDescriptions.get(e.getKey())

            desc.append("<dl>").append(escaper.escape(categoryDescription)).append(":\n")
            for (optionDef in categorizedOptionsList) {
                getUsageHtml(
                    optionDef,
                    desc,
                    escaper,
                    com.google.devtools.common.options.OptionsParser.getOptionsDataInternal(
                        optionDef.getDeclaringClass<com.google.devtools.common.options.OptionsBase?>(
                            com.google.devtools.common.options.OptionsBase::class.java
                        )
                    ),
                    true,
                    commandName
                )
            }
            desc.append("</dl>\n")
        }
        return desc.toString()
    }

    /** Append the usage message for a single option-field message to 'usage'.  */
    fun getUsageHtml(
        optionDefinition: com.google.devtools.common.options.OptionDefinition,
        usage: java.lang.StringBuilder,
        escaper: com.google.common.escape.Escaper,
        optionsData: com.google.devtools.common.options.OptionsData,
        includeTags: Boolean,
        commandName: String?
    ) {
        val plainFlagName: String? = optionDefinition.getOptionName()
        val flagName: String? = com.google.devtools.common.options.OptionsUsage.getFlagName(optionDefinition)
        val valueDescription: String = optionDefinition.getValueTypeHelpText()
        val typeDescription: String =
            com.google.devtools.common.options.OptionsUsage.getTypeDescription(optionDefinition)

        val anchorId: java.lang.StringBuilder = java.lang.StringBuilder()
        if (commandName != null) {
            anchorId.append(commandName).append("-")
        }
        anchorId.append("flag--").append(plainFlagName)

        // String.format is a lot slower, sometimes up to 10x.
        // https://stackoverflow.com/questions/925423/is-it-better-practice-to-use-string-format-over-string-concatenation-in-java
        //
        // Considering that this runs for every flag in the CLI reference, it's better to use regular
        // appends here.
        usage
            .append("<dt id=\"") // Add the id of the flag to point anchor hrefs to it
            .append(anchorId)
            .append("\">") // Add the href to the id hash
            .append("<code")
        // Historically, we used `flag--${plainFlagName}` as the anchor id, but this is not unique
        // across commands. We now use the per-command `anchorId` defined above, but we moved the old
        // `flag--${plainFlagName}` to be an id on the code block for backwards compatibility with
        // old links.
        if (commandName != null) {
            usage.append(" id=\"").append(plainFlagName).append("\"")
        }
        usage
            .append(">")
            .append("<a href=\"#")
            .append(anchorId)
            .append("\">")
            .append("--")
            .append(flagName)
            .append("</a>")

        if (!optionDefinition.requiresValue()) {
            // Nothing for boolean, tristate, boolean_or_enum, or void options.
        } else if (!valueDescription.isEmpty()) {
            usage.append("=").append(escaper.escape(valueDescription))
        } else if (!typeDescription.isEmpty()) {
            // Generic fallback, which isn't very good.
            usage.append("=&lt;").append(escaper.escape(typeDescription)).append("&gt;")
        }
        usage.append("</code>")
        if (optionDefinition.getAbbreviation() != '\u0000') {
            usage.append(" [<code>-").append(optionDefinition.getAbbreviation()).append("</code>]")
        }
        if (optionDefinition.allowsMultiple()) {
            // Allow-multiple options can't have a default value.
            usage.append(" multiple uses are accumulated")
        } else {
            // Don't call the annotation directly (we must allow overrides to certain defaults).
            val defaultValueString: String = optionDefinition.getUnparsedDefaultValue()
            if (optionDefinition.isVoidField()) {
                // Void options don't have a default.
            } else if (optionDefinition.isSpecialNullDefault()) {
                usage.append(" default: see description")
            } else {
                usage.append(" default: \"").append(escaper.escape(defaultValueString)).append("\"")
            }
        }
        usage.append("</dt>\n")
        usage.append("<dd>\n")
        if (!optionDefinition.getHelpText().isEmpty()) {
            usage.append(Markdown.renderToHtml(optionDefinition.getHelpText()))
            usage.append('\n')
        }

        val expansion: com.google.common.collect.ImmutableList<String> =
            optionsData.getEvaluatedExpansion(optionDefinition)
        if (!expansion.isEmpty()) {
            // If this is an expansion option, list the expansion if known, or at least specify that we
            // don't know.
            usage.append("<p>Expands to:\n")
            for (exp in expansion) {
                // TODO(jingwen): We link to the expanded flags here, but unfortunately we don't
                // currently guarantee that all flags are only printed once. A flag in an OptionBase that
                // is included by 2 different commands, but not inherited through a parent command, will
                // be printed multiple times. Clicking on the flag will bring the user to its first
                // definition.
                usage
                    .append("<br/>&nbsp;&nbsp;")
                    .append("<code><a href=\"#flag") // Link to the '#flag--flag_name' hash.
                    // Some expansions are in the form of '--flag_name=value', so we drop everything from
                    // '=' onwards.
                    .append(
                        com.google.common.collect.Iterables.get<String?>(
                            com.google.common.base.Splitter.on('=').split(escaper.escape(exp)), 0
                        )
                    )
                    .append("\">")
                    .append(escaper.escape(exp))
                    .append("</a></code>\n")
            }
            usage.append("</p>")
        }

        // Add effect tags, if not UNKNOWN, and metadata tags, if not empty.
        if (includeTags) {
            val effectTagStream: java.util.stream.Stream<com.google.devtools.common.options.OptionEffectTag?>? =
                java.util.Arrays.stream<com.google.devtools.common.options.OptionEffectTag?>(optionDefinition.getOptionEffectTags())
                    .filter(java.util.function.Predicate { effectTag: com.google.devtools.common.options.OptionEffectTag? ->
                        com.google.devtools.common.options.OptionsUsage.shouldEffectTagBeListed(
                            effectTag
                        )
                    })
            val metadataTagStream: java.util.stream.Stream<com.google.devtools.common.options.OptionMetadataTag?>? =
                java.util.Arrays.stream<com.google.devtools.common.options.OptionMetadataTag?>(optionDefinition.getOptionMetadataTags())
                    .filter(java.util.function.Predicate { metadataTag: com.google.devtools.common.options.OptionMetadataTag? ->
                        com.google.devtools.common.options.OptionsUsage.shouldMetadataTagBeListed(
                            metadataTag
                        )
                    })
            val tagList: String =
                java.util.stream.Stream.concat<String?>(
                    effectTagStream.map<String?>(
                        java.util.function.Function { tag: com.google.devtools.common.options.OptionEffectTag? ->
                            java.lang.String.format(
                                "<a href=\"#effect_tag_%s\"><code>%s</code></a>",
                                tag, com.google.common.base.Ascii.toLowerCase(tag.name())
                            )
                        }),
                    metadataTagStream.map<String?>(
                        java.util.function.Function { tag: com.google.devtools.common.options.OptionMetadataTag? ->
                            java.lang.String.format(
                                "<a href=\"#metadata_tag_%s\"><code>%s</code></a>",
                                tag, com.google.common.base.Ascii.toLowerCase(tag.name())
                            )
                        })
                )
                    .collect(Collectors.joining(", "))
            if (!tagList.isEmpty()) {
                usage.append("<p>Tags:\n").append(tagList).append("\n</p>")
            }
        }

        usage.append("</dd>\n")
    }
}
