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
package com.google.devtools.build.lib.runtime.commands

import com.google.devtools.build.lib.runtime.Command.BuildPhase.NONE

/** The 'blaze help' command, which prints all available commands as well as specific help pages.  */
@Command(
    name = "help",
    buildPhase = NONE,
    options = [com.google.devtools.build.lib.runtime.commands.HelpCommand.Options::class],
    allowResidue = true,
    mustRunInWorkspace = false,
    shortDescription = "Prints help for commands, or the index.",
    completion = "command|{startup_options,target-syntax,info-keys}",
    help = "resource:help.txt"
)
class HelpCommand : BlazeCommand {
    /** Options for the `help` command.  */
    @com.google.devtools.common.options.OptionsClass
    abstract class Options : com.google.devtools.common.options.OptionsBase() {
        @get:com.google.devtools.common.options.Option(
            name = "help_verbosity",
            defaultValue = "medium",
            converter = com.google.devtools.common.options.Converters.HelpVerbosityConverter::class,
            documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.LOGGING,
            effectTags = [com.google.devtools.common.options.OptionEffectTag.TERMINAL_OUTPUT],
            help = "Select the verbosity of the help command."
        )
        abstract val helpVerbosity: com.google.devtools.common.options.HelpVerbosity?

        @get:com.google.devtools.common.options.Option(
            name = "long",
            abbrev = 'l',
            defaultValue = "null",
            expansion = ["--help_verbosity=long"],
            documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.LOGGING,
            effectTags = [com.google.devtools.common.options.OptionEffectTag.TERMINAL_OUTPUT],
            help = "Show full description of each option, instead of just its name."
        )
        abstract val showLongFormOptions: java.lang.Void?

        @get:com.google.devtools.common.options.Option(
            name = "short",
            defaultValue = "null",
            expansion = ["--help_verbosity=short"],
            documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.LOGGING,
            effectTags = [com.google.devtools.common.options.OptionEffectTag.TERMINAL_OUTPUT],
            help = "Show only the names of the options, not their types or meanings."
        )
        abstract val showShortFormOptions: java.lang.Void?
    }

    public override fun exec(
        env: CommandEnvironment,
        options: com.google.devtools.common.options.OptionsParsingResult
    ): BlazeCommandResult? {
        env.getEventBus().post(NoBuildEvent())

        val runtime: BlazeRuntime = env.getRuntime()
        val outErr: OutErr = env.getReporter().getOutErr()
        val helpOptions: Options? =
            options.getOptions<Options?>(com.google.devtools.build.lib.runtime.commands.HelpCommand.Options::class.java)
        if (options.getResidue().isEmpty()) {
            com.google.devtools.build.lib.runtime.commands.HelpCommand.Companion.emitBlazeVersionInfo(
                outErr,
                runtime.productName
            )
            com.google.devtools.build.lib.runtime.commands.HelpCommand.Companion.emitGenericHelp(outErr, runtime)
            return BlazeCommandResult.success()
        }
        if (options.getResidue().getFirst() == "completion") {
            if (options.getResidue().size > 2) {
                val message = "The completion command takes at most one argument"
                env.getReporter().handle(com.google.devtools.build.lib.events.Event.error(message))
                return com.google.devtools.build.lib.runtime.commands.HelpCommand.Companion.createFailureResult(
                    message,
                    Code.MISSING_ARGUMENT
                )
            }
            val shell: String? = if (options.getResidue().size > 1) options.getResidue().get(1) else null
            return com.google.devtools.build.lib.runtime.commands.HelpCommand.Companion.emitCompletionHelp(
                shell,
                runtime,
                env.getReporter()
            )
        }
        if (options.getResidue().size != 1) {
            val message = "You must specify exactly one command"
            env.getReporter().handle(com.google.devtools.build.lib.events.Event.error(message))
            return com.google.devtools.build.lib.runtime.commands.HelpCommand.Companion.createFailureResult(
                message,
                Code.MISSING_ARGUMENT
            )
        }
        val helpSubject: String = options.getResidue().getFirst()
        val productName: String? = runtime.productName
        // Go through the custom subjects before going through Bazel commands.
        when (helpSubject) {
            "startup_options" -> {
                com.google.devtools.build.lib.runtime.commands.HelpCommand.Companion.emitBlazeVersionInfo(
                    outErr,
                    runtime.productName
                )
                emitStartupOptions(outErr, helpOptions!!.helpVerbosity, runtime)
                return BlazeCommandResult.success()
            }

            "target-syntax" -> {
                com.google.devtools.build.lib.runtime.commands.HelpCommand.Companion.emitBlazeVersionInfo(
                    outErr,
                    runtime.productName
                )
                emitTargetSyntaxHelp(outErr, productName)

                return BlazeCommandResult.success()
            }

            "info-keys" -> {
                com.google.devtools.build.lib.runtime.commands.HelpCommand.Companion.emitInfoKeysHelp(env, outErr)
                return BlazeCommandResult.success()
            }

            "flags-as-proto" -> {
                com.google.devtools.build.lib.runtime.commands.HelpCommand.Companion.emitFlagsAsProtoHelp(
                    runtime,
                    outErr
                )
                return BlazeCommandResult.success()
            }

            "everything-as-html" -> {
                HtmlEmitter(runtime).emit(outErr)
                return BlazeCommandResult.success()
            }

            else -> {}
        }

        val command: BlazeCommand? = runtime.getCommandMap().get(helpSubject)
        if (command == null) {
            val message = "'" + helpSubject + "' is not a known command"
            env.getReporter().handle(com.google.devtools.build.lib.events.Event.error(null, message))
            return com.google.devtools.build.lib.runtime.commands.HelpCommand.Companion.createFailureResult(
                message,
                Code.COMMAND_NOT_FOUND
            )
        }
        com.google.devtools.build.lib.runtime.commands.HelpCommand.Companion.emitBlazeVersionInfo(outErr, productName)
        outErr.printOut(
            BlazeCommandUtils.getUsage(
                command.getClass(),
                helpOptions!!.helpVerbosity,
                runtime.getOptionsSuppliers(),
                runtime.getRuleClassProvider(),
                productName
            )
        )

        return BlazeCommandResult.success()
    }

    private fun emitStartupOptions(
        outErr: OutErr, helpVerbosity: com.google.devtools.common.options.HelpVerbosity?, runtime: BlazeRuntime
    ) {
        outErr.printOut(
            BlazeCommandUtils.expandHelpTopic(
                "startup_options",
                "resource:startup_options.txt",
                javaClass,
                BlazeCommandUtils.getStartupOptions(runtime.getOptionsSuppliers()),
                helpVerbosity,
                runtime.productName
            )
        )
    }

    private fun emitTargetSyntaxHelp(outErr: OutErr, productName: String?) {
        outErr.printOut(
            BlazeCommandUtils.expandHelpTopic(
                "target-syntax",
                "resource:target-syntax.txt",
                javaClass,
                com.google.common.collect.ImmutableList.of<E?>(),
                com.google.devtools.common.options.HelpVerbosity.MEDIUM,
                productName
            )
        )
    }

    private class HtmlEmitter(runtime: BlazeRuntime) {
        private val runtime: BlazeRuntime

        init {
            this.runtime = runtime
        }

        fun emit(outErr: OutErr) {
            val commandsByName: MutableMap<String?, BlazeCommand?> =
                com.google.devtools.build.lib.runtime.commands.HelpCommand.Companion.getSortedCommands(runtime)
            val result: java.lang.StringBuilder = java.lang.StringBuilder()
            result.append("<h2>Commands</h2>\n")
            result.append("<table>\n")
            for (e in commandsByName.entries) {
                val command: BlazeCommand = e.value
                val annotation: Command = command.getClass().getAnnotation(Command::class.java)
                if (annotation.hidden()) {
                    continue
                }
                val shortDescription: String =
                    annotation.shortDescription().replace("%{product}", runtime.productName)

                result.append("<tr>\n")
                result.append(
                    String.format(
                        "  <td><a href=\"#%s\"><code>%s</code></a></td>\n", e.key, e.key
                    )
                )
                result.append("  <td>").append(
                    com.google.devtools.build.lib.runtime.commands.HelpCommand.Companion.HTML_ESCAPER.escape(
                        shortDescription
                    )
                ).append("</td>\n")
                result.append("</tr>\n")
            }
            result.append("</table>\n")
            result.append("\n")

            result.append("<h2>Startup Options</h2>\n")
            appendOptionsHtml(
                result,
                BlazeCommandUtils.getStartupOptions(runtime.getOptionsSuppliers()),
                com.google.common.collect.ImmutableList.of<String?>(),
                "startup_options"
            )
            result.append("\n")

            result.append("<h2><a name=\"common_options\">Options Common to all Commands</a></h2>\n")
            appendOptionsHtml(
                result,
                BlazeCommandUtils.getCommonOptions(runtime.getOptionsSuppliers()),
                com.google.common.collect.ImmutableList.of<String?>(),
                "common_options"
            )
            result.append("\n")

            for (e in commandsByName.entries) {
                result.append(
                    String.format(
                        "<h2><a name=\"%s\">%s Options</a></h2>\n",
                        e.key, com.google.devtools.build.lib.util.StringUtilities.capitalize(e.key)
                    )
                )
                val command: BlazeCommand = e.value
                val annotation: Command = command.getClass().getAnnotation(Command::class.java)
                if (annotation.hidden()) {
                    continue
                }
                val inheritedCmdNames: MutableList<String?> = java.util.ArrayList<String?>()
                for (base in annotation.inheritsOptionsFrom()) {
                    val name: String? = base.getAnnotation<A?>(Command::class.java).name()
                    inheritedCmdNames.add(String.format("<a href=\"#%s\">%s</a>", name, name))
                }
                if (!inheritedCmdNames.isEmpty()) {
                    result.append("<p>Inherits all options from ")
                    result.append(
                        com.google.devtools.build.lib.util.StringUtil.joinEnglishList(
                            inheritedCmdNames,
                            "and"
                        )
                    )
                    result.append(".</p>\n\n")
                }
                val options: MutableSet<java.lang.Class<out com.google.devtools.common.options.OptionsBase?>?> =
                    HashSet<java.lang.Class<out com.google.devtools.common.options.OptionsBase?>?>()
                Collections.addAll(options, annotation.options())
                for (supplier in runtime.getOptionsSuppliers()) {
                    com.google.common.collect.Iterables.addAll<java.lang.Class<out com.google.devtools.common.options.OptionsBase?>?>(
                        options,
                        supplier.getCommandOptions(annotation.name())
                    )
                }
                val optionsToIgnore =
                    appendOptionsHtml(result, options, com.google.common.collect.ImmutableList.of<String?>(), e.key)
                result.append("\n")

                // For now, we print all the configuration options in a list after all the non-configuration
                // options.
                if (annotation.usesConfigurationOptions()) {
                    options.clear()
                    Collections.addAll(options, annotation.options())
                    options.addAll(runtime.getRuleClassProvider().getFragmentRegistry().getOptionsClasses())
                    appendOptionsHtml(result, options, optionsToIgnore, null)
                    result.append("\n")
                }
            }

            // Describe the tags once, any mentions above should link to these descriptions.
            val productName: String? = runtime.productName
            val effectTagDescriptions: com.google.common.collect.ImmutableMap<com.google.devtools.common.options.OptionEffectTag?, String?> =
                com.google.devtools.common.options.OptionFilterDescriptions.getOptionEffectTagDescription(productName)
            result.append("<h3>Option Effect Tags</h3>\n")
            result.append("<table>\n")
            for (tag in com.google.devtools.common.options.OptionEffectTag.entries) {
                val tagDescription: String? = effectTagDescriptions.get(tag)

                result.append("<tr>\n")
                result.append(
                    String.format(
                        "<td id=\"effect_tag_%s\"><code>%s</code></td>\n",
                        tag, com.google.common.base.Ascii.toLowerCase(tag.name)
                    )
                )
                result.append(
                    String.format(
                        "<td>%s</td>\n",
                        com.google.devtools.build.lib.runtime.commands.HelpCommand.Companion.HTML_ESCAPER.escape(
                            tagDescription
                        )
                    )
                )
                result.append("</tr>\n")
            }
            result.append("</table>\n")

            val metadataTagDescriptions: com.google.common.collect.ImmutableMap<com.google.devtools.common.options.OptionMetadataTag?, String?> =
                com.google.devtools.common.options.OptionFilterDescriptions.getOptionMetadataTagDescription(productName)
            result.append("<h3>Option Metadata Tags</h3>\n")
            result.append("<table>\n")
            for (tag in com.google.devtools.common.options.OptionMetadataTag.entries) {
                // skip the tags that are reserved for undocumented flags.
                if (tag != com.google.devtools.common.options.OptionMetadataTag.HIDDEN && tag != com.google.devtools.common.options.OptionMetadataTag.INTERNAL) {
                    val tagDescription: String? = metadataTagDescriptions.get(tag)

                    result.append("<tr>\n")
                    result.append(
                        String.format(
                            "<td id=\"metadata_tag_%s\"><code>%s</code></td>\n",
                            tag, com.google.common.base.Ascii.toLowerCase(tag.name)
                        )
                    )
                    result.append(
                        String.format(
                            "<td>%s</td>\n",
                            com.google.devtools.build.lib.runtime.commands.HelpCommand.Companion.HTML_ESCAPER.escape(
                                tagDescription
                            )
                        )
                    )
                    result.append("</tr>\n")
                }
            }
            result.append("</table>\n")

            outErr.printOut(result.toString())
        }

        // Returns the list of appended option names.
        @com.google.errorprone.annotations.CanIgnoreReturnValue
        fun appendOptionsHtml(
            result: java.lang.StringBuilder,
            optionsClasses: Iterable<java.lang.Class<out com.google.devtools.common.options.OptionsBase?>?>?,
            optionsToIgnore: MutableList<String?>?,
            commandName: String?
        ): MutableList<String?> {
            val parser: com.google.devtools.common.options.OptionsParser =
                com.google.devtools.common.options.OptionsParser.builder().optionsClasses(optionsClasses).build()
            val productName: String = runtime.productName
            result.append(
                HtmlUtils.describeOptionsHtml(
                    parser,
                    com.google.devtools.build.lib.runtime.commands.HelpCommand.Companion.HTML_ESCAPER,
                    optionsToIgnore,
                    commandName
                )
                    .replace("%{product}", productName)
            )

            val optionNames: MutableList<String?> = java.util.ArrayList<String?>()
            for (category in parser.getOptionsSortedByCategory().values) {
                for (option in category) {
                    optionNames.add(option.getOptionName())
                }
            }
            return optionNames
        }
    }

    /** A visitor for Blaze commands and their respective command line options.  */
    internal fun interface CommandOptionVisitor {
        /**
         * Visits a Blaze command by providing access to its name, its meta-data and its command line
         * options (via an [OptionsParser] instance).
         * 
         * @param commandName name of the command, e.g. "help".
         * @param commandAnnotation [Command] that contains addition information about the
         * command.
         * @param parser an [OptionsParser] instance that provides access to all options supported
         * by the command.
         */
        fun visit(
            commandName: String?,
            commandAnnotation: Command?,
            parser: com.google.devtools.common.options.OptionsParser?
        )
    }

    companion object {
        private val SPACE_JOINER: com.google.common.base.Joiner = com.google.common.base.Joiner.on(" ")

        /**
         * Only to be used to escape the internal hard-coded help texts when outputting HTML from help,
         * which don't pose a security risk.
         */
        private val HTML_ESCAPER: com.google.common.escape.Escaper = com.google.common.html.HtmlEscapers.htmlEscaper()

        private fun emitBlazeVersionInfo(outErr: OutErr, productName: String?) {
            val releaseInfo: String? = BlazeVersionInfo.instance().getReleaseName()
            val line: String? = "[%s %s]".formatted(productName, releaseInfo)
            outErr.printOut("%80s\n".formatted(line))
        }

        private fun emitCompletionHelp(
            shell: String?, runtime: BlazeRuntime, reporter: com.google.devtools.build.lib.events.Reporter
        ): BlazeCommandResult? {
            val outErr: OutErr = reporter.getOutErr()
            return when (shell) {
                "bash" -> {
                    outErr.printOutLn(
                        com.google.devtools.build.lib.runtime.commands.HelpCommand.Companion.loadCompletionScript(
                            "bazel-complete-header.bash"
                        )
                    )
                    com.google.devtools.build.lib.runtime.commands.HelpCommand.Companion.emitCompletionVariables(
                        runtime,
                        outErr
                    )
                    outErr.printOutLn(
                        com.google.devtools.build.lib.runtime.commands.HelpCommand.Companion.loadCompletionScript(
                            "bazel-complete-template.bash"
                        )
                    )
                    BlazeCommandResult.success()
                }

                null -> {
                    // Preserved for backwards compatibility: print only the variables part of the bash
                    // completion script.
                    com.google.devtools.build.lib.runtime.commands.HelpCommand.Companion.emitCompletionVariables(
                        runtime,
                        outErr
                    )
                    BlazeCommandResult.success()
                }

                else -> {
                    val message: String? =
                        "The completion command only supports 'bash' as an argument, got '%s'".formatted(shell)
                    reporter.handle(com.google.devtools.build.lib.events.Event.error(message))
                    com.google.devtools.build.lib.runtime.commands.HelpCommand.Companion.createFailureResult(
                        message,
                        Code.MISSING_ARGUMENT
                    )
                }
            }
        }

        private fun loadCompletionScript(basename: String): String {
            try {
                val resourceName = "/scripts/" + basename
                com.google.devtools.build.lib.runtime.commands.HelpCommand::class.java.getResourceAsStream(resourceName)
                    .use { stream ->
                        if (stream == null) {
                            throw IOException(resourceName + " not found.")
                        }
                        return String(stream.readAllBytes(), java.nio.charset.StandardCharsets.ISO_8859_1)
                    }
            } catch (e: IOException) {
                throw java.lang.IllegalStateException(
                    "Failed to read built-in resource %s: %s".formatted(basename, e.message), e
                )
            }
        }

        private fun emitCompletionVariables(runtime: BlazeRuntime, outErr: OutErr) {
            val commandsByName: MutableMap<String?, BlazeCommand?> =
                com.google.devtools.build.lib.runtime.commands.HelpCommand.Companion.getSortedCommands(runtime)

            outErr.printOutLn(
                "BAZEL_COMMAND_LIST=\"" + com.google.devtools.build.lib.runtime.commands.HelpCommand.Companion.SPACE_JOINER.join(
                    commandsByName.keys
                ) + "\""
            )

            outErr.printOutLn("BAZEL_INFO_KEYS=\"")
            for (name in InfoCommand.Companion.getHardwiredInfoItemNames(runtime.productName)) {
                outErr.printOutLn(name)
            }
            outErr.printOutLn("\"")

            val startupOptionVisitor: java.util.function.Consumer<com.google.devtools.common.options.OptionsParser?> =
                java.util.function.Consumer { parser: com.google.devtools.common.options.OptionsParser? ->
                    outErr.printOutLn("BAZEL_STARTUP_OPTIONS=\"")
                    outErr.printOut(parser.getOptionsCompletion())
                    outErr.printOutLn("\"")
                }
            val commandOptionVisitor =
                CommandOptionVisitor { commandName: String?, commandAnnotation: Command?, parser: com.google.devtools.common.options.OptionsParser? ->
                    val varName: String = com.google.common.base.CaseFormat.LOWER_HYPHEN.to(
                        com.google.common.base.CaseFormat.UPPER_UNDERSCORE,
                        commandName
                    )
                    if (!com.google.common.base.Strings.isNullOrEmpty(commandAnnotation.completion())) {
                        outErr.printOutLn(
                            ("BAZEL_COMMAND_"
                                    + varName
                                    + "_ARGUMENT=\""
                                    + commandAnnotation.completion()
                                    + "\"")
                        )
                    }
                    outErr.printOutLn("BAZEL_COMMAND_" + varName + "_FLAGS=\"")
                    outErr.printOut(parser.getOptionsCompletion())
                    outErr.printOutLn("\"")
                }

            com.google.devtools.build.lib.runtime.commands.HelpCommand.Companion.visitAllOptions(
                runtime,
                startupOptionVisitor,
                commandOptionVisitor
            )
        }

        private fun emitFlagsAsProtoHelp(runtime: BlazeRuntime, outErr: OutErr) {
            val flags: MutableMap<String?, BazelFlagsProto.FlagInfo.Builder> =
                HashMap<String?, BazelFlagsProto.FlagInfo.Builder>()

            val allOptions: java.util.function.Predicate<com.google.devtools.common.options.OptionDefinition?> =
                java.util.function.Predicate { unused: com.google.devtools.common.options.OptionDefinition? -> true }
            val visitor: java.util.function.BiConsumer<String?, com.google.devtools.common.options.OptionDefinition?> =
                java.util.function.BiConsumer { commandName: String?, option: com.google.devtools.common.options.OptionDefinition? ->
                    if (com.google.common.collect.ImmutableSet.copyOf<com.google.devtools.common.options.OptionMetadataTag?>(
                            option.getOptionMetadataTags()
                        )
                            .contains(com.google.devtools.common.options.OptionMetadataTag.INTERNAL)
                    ) {
                        return@BiConsumer
                    }
                    val info: BazelFlagsProto.FlagInfo.Builder =
                        flags.computeIfAbsent(option.getOptionName()) { unused: String? ->
                            com.google.devtools.build.lib.runtime.commands.HelpCommand.Companion.createFlagInfo(
                                option
                            )
                        }
                    info.addCommands(commandName)
                }
            val startupOptionVisitor: java.util.function.Consumer<com.google.devtools.common.options.OptionsParser?> =
                java.util.function.Consumer { parser: com.google.devtools.common.options.OptionsParser? ->
                    parser.visitOptions(
                        allOptions,
                        java.util.function.Consumer { option: com.google.devtools.common.options.OptionDefinition? ->
                            visitor.accept(
                                "startup",
                                option
                            )
                        })
                }
            val commandOptionVisitor =
                CommandOptionVisitor { commandName: String?, unused: Command?, parser: com.google.devtools.common.options.OptionsParser? ->
                    parser.visitOptions(
                        allOptions,
                        java.util.function.Consumer { option: com.google.devtools.common.options.OptionDefinition? ->
                            visitor.accept(
                                commandName,
                                option
                            )
                        })
                }

            com.google.devtools.build.lib.runtime.commands.HelpCommand.Companion.visitAllOptions(
                runtime,
                startupOptionVisitor,
                commandOptionVisitor
            )

            val collectionBuilder: BazelFlagsProto.FlagCollection.Builder =
                BazelFlagsProto.FlagCollection.newBuilder()
            for (info in flags.values) {
                collectionBuilder.addFlagInfos(info)
            }
            outErr.printOut(java.util.Base64.getEncoder().encodeToString(collectionBuilder.build().toByteArray()))
        }

        private fun createFlagInfo(option: com.google.devtools.common.options.OptionDefinition): BazelFlagsProto.FlagInfo.Builder {
            val flagBuilder: BazelFlagsProto.FlagInfo.Builder = BazelFlagsProto.FlagInfo.newBuilder()
            flagBuilder.setName(option.getOptionName())
            flagBuilder.setHasNegativeFlag(option.usesBooleanValueSyntax())
            flagBuilder.setDocumentation(option.getHelpText())
            flagBuilder.setAllowsMultiple(option.allowsMultiple())
            flagBuilder.setRequiresValue(option.requiresValue())

            if (option.getAbbreviation() != '\u0000') {
                flagBuilder.setAbbreviation(option.getAbbreviation().toString())
            }
            if (!option.getOldOptionName().isEmpty()) {
                flagBuilder.setOldName(option.getOldOptionName())
            }

            val optionEffectTags: MutableList<String?> =
                java.util.Arrays.stream<com.google.devtools.common.options.OptionEffectTag?>(option.getOptionEffectTags())
                    .map<String?> { obj: com.google.devtools.common.options.OptionEffectTag? -> obj.toString() }
                    .toList()
            flagBuilder.addAllEffectTags(optionEffectTags)

            val optionMetadataTags: MutableList<String?> =
                java.util.Arrays.stream<com.google.devtools.common.options.OptionMetadataTag?>(option.getOptionMetadataTags())
                    .map<String?> { obj: com.google.devtools.common.options.OptionMetadataTag? -> obj.toString() }
                    .toList()
            flagBuilder.addAllMetadataTags(optionMetadataTags)

            if (option.getDocumentationCategory() != null) {
                flagBuilder.setDocumentationCategory(option.getDocumentationCategory().toString())
            }

            if (!option.isSpecialNullDefault()) {
                flagBuilder.setDefaultValue(option.getUnparsedDefaultValue())
            }

            if (!option.getDeprecationWarning().isEmpty()) {
                flagBuilder.setDeprecationWarning(option.getDeprecationWarning())
            }

            if (option.getOptionExpansion().size > 0) {
                flagBuilder.addAllOptionExpansions(com.google.common.collect.ImmutableList.< E > copyOf < E ? > (option.getOptionExpansion()))
            }

            val converter: com.google.devtools.common.options.Converter<*> = option.getConverter()
            val converterClassName: String = converter.javaClass.getSimpleName()
            if (converterClassName.endsWith("Converter")) {
                val shortName: String =
                    converterClassName.substring(0, converterClassName.length - "Converter".length)
                flagBuilder.setTypeConverter(shortName)
            }
            if (converter is com.google.devtools.common.options.EnumConverter<*>) {
                val enumValues: MutableList<String?> =
                    java.util.Arrays.stream(converter.getEnumType().getEnumConstants())
                        .map<String?> { obj: Any? -> obj.toString() }
                        .collect(com.google.common.collect.ImmutableList.toImmutableList<String?>())
                flagBuilder.addAllEnumValues(enumValues)
            }

            return flagBuilder
        }

        private fun visitAllOptions(
            runtime: BlazeRuntime,
            startupOptionVisitor: java.util.function.Consumer<com.google.devtools.common.options.OptionsParser?>,
            commandOptionVisitor: CommandOptionVisitor
        ) {
            // First startup_options
            val optionsSuppliers: Iterable<com.google.devtools.build.lib.runtime.OptionsSupplier?>? =
                runtime.getOptionsSuppliers()
            val ruleClassProvider: ConfiguredRuleClassProvider? = runtime.getRuleClassProvider()
            val commandsByName: MutableMap<String?, BlazeCommand?> =
                com.google.devtools.build.lib.runtime.commands.HelpCommand.Companion.getSortedCommands(runtime)

            var options: Iterable<java.lang.Class<out com.google.devtools.common.options.OptionsBase?>?>? =
                BlazeCommandUtils.getStartupOptions(optionsSuppliers)
            startupOptionVisitor.accept(
                com.google.devtools.common.options.OptionsParser.builder().optionsClasses(options).build()
            )

            for (e in commandsByName.entries) {
                val command: BlazeCommand = e.value
                val annotation: Command? = command.getClass().getAnnotation(Command::class.java)
                options =
                    BlazeCommandUtils.getOptions(command.getClass(), optionsSuppliers, ruleClassProvider)
                commandOptionVisitor.visit(
                    e.key,
                    annotation,
                    com.google.devtools.common.options.OptionsParser.builder().optionsClasses(options).build()
                )
            }
        }

        private fun getSortedCommands(runtime: BlazeRuntime): MutableMap<String?, BlazeCommand?> {
            return com.google.common.collect.ImmutableSortedMap.copyOf(runtime.getCommandMap())
        }

        private fun emitInfoKeysHelp(env: CommandEnvironment, outErr: OutErr) {
            for (item in InfoCommand.Companion.getInfoItemMap(
                env,
                com.google.devtools.common.options.OptionsParser.builder().build()
            ).values) {
                outErr.printOut("%-23s %s\n".formatted(item.name, item.description))
            }
        }

        private fun emitGenericHelp(outErr: OutErr, runtime: BlazeRuntime) {
            outErr.printOut("Usage: %s <command> <options> ...\n\n".formatted(runtime.productName))
            outErr.printOut("Available commands:\n")

            for (entry in com.google.devtools.build.lib.runtime.commands.HelpCommand.Companion.getSortedCommands(runtime).entries) {
                val name: String? = entry.key
                val command: BlazeCommand = entry.value
                val annotation: Command = command.getClass().getAnnotation(Command::class.java)
                if (annotation.hidden()) {
                    continue
                }

                val shortDescription: String? =
                    annotation.shortDescription().replace("%{product}", runtime.productName)
                outErr.printOut("  %-19s %s\n".formatted(name, shortDescription))
            }

            outErr.printOut(
                """

        Getting more help:
          %1${'$'}s help <command>
                           Prints help and options for <command>.
          %1${'$'}s help startup_options
                           Options for the JVM hosting %1${'$'}s.
          %1${'$'}s help target-syntax
                           Explains the syntax for specifying targets.
          %1${'$'}s help info-keys
                           Displays a list of keys used by the info command.
        
        """
                    .trimIndent()
                    .formatted(runtime.productName)
            )
        }

        private fun createFailureResult(message: String?, detailedCode: Code?): BlazeCommandResult {
            return BlazeCommandResult.failureDetail(
                FailureDetail.newBuilder()
                    .setMessage(message)
                    .setHelpCommand(FailureDetails.HelpCommand.newBuilder().setCode(detailedCode))
                    .build()
            )
        }
    }
}
