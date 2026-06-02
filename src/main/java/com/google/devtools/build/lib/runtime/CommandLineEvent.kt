// Copyright 2017 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.lib.runtime

import com.google.devtools.build.lib.buildeventstream.BuildEventContext

/** A build event reporting the command line by which Bazel was invoked.  */
abstract class CommandLineEvent : BuildEventWithOrderConstraint {
    val childrenEvents: MutableCollection<BuildEventId>
        get() = com.google.common.collect.ImmutableList.of<BuildEventId?>()

    public override fun postedAfter(): MutableCollection<BuildEventId?> {
        return com.google.common.collect.ImmutableList.of<E?>(BuildEventIdUtil.buildStartedId())
    }

    /** A CommandLineEvent that stores functions and values common to both Bazel command lines.  */
    abstract class BazelCommandLineEvent internal constructor(
        protected val productName: String?,
        activeStartupOptions: com.google.devtools.common.options.OptionsParsingResult,
        commandName: String,
        residue: MutableList<String>,
        includeResidueInRunBepEvent: Boolean,
        starlarkOptionAllowingMultiple: MutableSet<String?>
    ) : CommandLineEvent() {
        protected val activeStartupOptions: com.google.devtools.common.options.OptionsParsingResult
        protected val commandName: String
        protected val residue: MutableList<String>
        protected val includeResidueInRunBepEvent: Boolean
        protected val starlarkOptionAllowingMultiple: MutableSet<String?>

        init {
            this.activeStartupOptions = activeStartupOptions
            this.commandName = commandName
            this.residue = residue
            this.includeResidueInRunBepEvent = includeResidueInRunBepEvent
            this.starlarkOptionAllowingMultiple = starlarkOptionAllowingMultiple
        }

        val executableSection: CommandLineSection
            get() = CommandLineSection.newBuilder()
                .setSectionLabel("executable")
                .setChunkList(ChunkList.newBuilder().addChunk(productName))
                .build()

        val commandSection: CommandLineSection
            get() = CommandLineSection.newBuilder()
                .setSectionLabel("command")
                .setChunkList(ChunkList.newBuilder().addChunk(commandName))
                .build()

        fun getOptionListFromParsedOptionDescriptions(
            parsedOptionDescriptions: MutableList<com.google.devtools.common.options.ParsedOptionDescription>
        ): MutableList<Option?> {
            val options: MutableList<Option?> = java.util.ArrayList<Option?>()
            for (parsedOption in parsedOptionDescriptions) {
                options.add(
                    createOption(
                        parsedOption.getOptionDefinition(),
                        parsedOption.getSource(),
                        parsedOption.getCommandLineForm(),
                        parsedOption.getUnconvertedValue()
                    )
                )
            }
            return options
        }

        private fun createOption(
            optionDefinition: com.google.devtools.common.options.OptionDefinition,
            source: String?,
            combinedForm: String?,
            value: String?
        ): Option {
            val option: Option.Builder = Option.newBuilder()
            option.setCombinedForm(combinedForm)
            option.setOptionName(optionDefinition.getOptionName())
            if (value != null) {
                option.setOptionValue(value)
            }
            option.addAllEffectTags(getProtoEffectTags(optionDefinition.getOptionEffectTags()))
            option.addAllMetadataTags(getProtoMetadataTags(optionDefinition.getOptionMetadataTags()))
            if (source != null) {
                option.setSource(source)
            }
            return option.build()
        }

        /**
         * Reverses a parsed Starlark flag / option value pair into a stream of [Option] objects.
         * 
         * 
         * Emits multiple option objects if the value is a collection and the Starlark option allows
         * multiple instances.
         */
        fun streamStarlarkOption(starlarkFlag: String?, value: Any?): java.util.stream.Stream<Option?> {
            if (starlarkOptionAllowingMultiple.contains(starlarkFlag)
                && value is MutableCollection<*>
            ) {
                return value.stream()
                    .map<Option?> { element: Any? -> createSingleStarlarkOption(starlarkFlag, element) }
            } else {
                return java.util.stream.Stream.of<Option?>(createSingleStarlarkOption(starlarkFlag, value))
            }
        }

        fun createSingleStarlarkOption(starlarkFlag: String?, value: Any?): Option {
            val sb: java.lang.StringBuilder = java.lang.StringBuilder("--").append(starlarkFlag)
            if (value != null) {
                sb.append("=")
                if (value is MutableCollection<*>) {
                    // Render non-repeatable lists/sets as comma-separated
                    com.google.common.base.Joiner.on(",").appendTo(sb, value)
                } else {
                    sb.append(value)
                }
            }
            val option: Option.Builder = Option.newBuilder()
            option.setCombinedForm(sb.toString())
            option.setOptionName(starlarkFlag)
            if (value != null) {
                option.setOptionValue(value.toString())
            }
            return option.build()
        }

        /**
         * Returns the startup option section of the command line for the startup options as the server
         * received them at its startup. Since not all client options get passed to the server as
         * startup options, this might not represent the actual list of startup options as the user
         * provided them.
         */
        fun getActiveStartupOptions(): CommandLineSection {
            return CommandLineSection.newBuilder()
                .setSectionLabel("startup options")
                .setOptionList(
                    OptionList.newBuilder()
                        .addAllOption(
                            getOptionListFromParsedOptionDescriptions(
                                activeStartupOptions.asCompleteListOfParsedOptions()
                            )
                        )
                )
                .build()
        }

        val residual: CommandLineSection
            /**
             * Returns the final part of the command line, containing whatever was left after obtaining the
             * command and its options.
             */
            get() {
                // Potential further split: how the residual, if any is accepted, gets interpreted depends on
                // the command. For example, for build commands, we might want to consider separating out
                // project files, as in runtime.commands.ProjectFileSupport. To properly report this, we would
                // need to let the command customize how the residual is listed. This catch-all could serve
                // as a default in this case.
                val builder: CommandLineSection.Builder =
                    CommandLineSection.newBuilder().setSectionLabel("residual")
                if (commandName == "run" && !includeResidueInRunBepEvent && !residue.isEmpty()) {
                    val target: String? = residue.get(0)
                    val residual: ChunkList.Builder = ChunkList.newBuilder().addChunk(target)
                    if (residue.size > 1) {
                        residual.addChunk("REDACTED")
                    }
                    builder.setChunkList(residual)
                } else {
                    builder.setChunkList(ChunkList.newBuilder().addAllChunk(residue))
                }
                return builder.build()
            }

        companion object {
            /**
             * Convert an array of tags to the equivalent proto-generated enum values.
             * 
             * 
             * The proto type is duplicate in order to not burden the OptionsParser with the proto
             * dependency. A test guarantees that the two enum types are kept in sync with matching indices.
             */
            fun getProtoEffectTags(tagArray: Array<com.google.devtools.common.options.OptionEffectTag>): MutableList<OptionFilters.OptionEffectTag?> {
                val effectTags: java.util.ArrayList<OptionFilters.OptionEffectTag?> =
                    java.util.ArrayList<OptionFilters.OptionEffectTag?>(tagArray.size)
                for (tag in tagArray) {
                    effectTags.add(OptionFilters.OptionEffectTag.forNumber(tag.getValue()))
                }
                return effectTags
            }

            /**
             * Convert an array of tags to the equivalent proto-generated enum values.
             * 
             * 
             * The proto type is duplicate in order to not burden the OptionsParser with the proto
             * dependency. A test guarantees that the two enum types are kept in sync with matching indices.
             */
            fun getProtoMetadataTags(
                tagArray: Array<com.google.devtools.common.options.OptionMetadataTag>
            ): MutableList<OptionFilters.OptionMetadataTag?> {
                val metadataTags: java.util.ArrayList<OptionFilters.OptionMetadataTag?> =
                    java.util.ArrayList<OptionFilters.OptionMetadataTag?>(tagArray.size)
                for (tag in tagArray) {
                    metadataTags.add(OptionFilters.OptionMetadataTag.forNumber(tag.getValue()))
                }
                return metadataTags
            }
        }
    }

    /** This reports a reassembled version of the command line as Bazel received it.  */
    class OriginalCommandLineEvent(
        productName: String?,
        startupOptionsProvider: com.google.devtools.common.options.OptionsParsingResult,
        commandName: String,
        residue: MutableList<String>,
        includeResidueInRunBepEvent: Boolean,
        explicitOptions: MutableList<com.google.devtools.common.options.ParsedOptionDescription?>,
        explicitStarlarkOptions: MutableMap<String?, Any?>,
        starlarkOptionAllowingMultiple: MutableSet<String?>,
        originalStartupOptions: java.util.Optional<MutableList<com.google.devtools.build.lib.util.Pair<String?, String?>>?>
    ) : BazelCommandLineEvent(
        productName,
        startupOptionsProvider,
        commandName,
        residue,
        includeResidueInRunBepEvent,
        starlarkOptionAllowingMultiple
    ) {
        protected val explicitOptions: MutableList<com.google.devtools.common.options.ParsedOptionDescription?>
        private val explicitStarlarkOptions: MutableMap<String?, Any?>
        private val originalStartupOptions: java.util.Optional<MutableList<com.google.devtools.build.lib.util.Pair<String?, String?>>?>

        init {
            this.explicitOptions = explicitOptions
            this.explicitStarlarkOptions = explicitStarlarkOptions
            this.originalStartupOptions = originalStartupOptions
        }

        val eventId: BuildEventId
            get() = BuildEventIdUtil.structuredCommandlineId(LABEL)

        private val startupOptionSection: CommandLineSection
            /**
             * Returns the literal command line options as received. These are not the final parsed values,
             * but are passed as is from the client, so we do not have the full OptionDefinition
             * information. In this form, only set the "combinedForm" field.
             */
            get() {
                if (originalStartupOptions.isPresent()) {
                    val options: MutableList<Option?> = java.util.ArrayList<Option?>()
                    for (sourceToOptionPair in originalStartupOptions.get()) {
                        // Only add the options that were added by the command line.
                        // TODO(b/19881919) decide the format that option source information should take and then
                        // add all options, tagged with the source, instead of filtering out the rc options.
                        if (sourceToOptionPair.first != null && sourceToOptionPair.first.isEmpty()) {
                            options.add(
                                Option.newBuilder().setCombinedForm(sourceToOptionPair.getSecond()).build()
                            )
                        }
                    }
                    return CommandLineSection.newBuilder()
                        .setSectionLabel("startup options")
                        .setOptionList(OptionList.newBuilder().addAllOption(options))
                        .build()
                } else {
                    // If we were not provided with the startup options, fallback to reporting the active ones
                    // stored by the Bazel Runtime.
                    return getActiveStartupOptions()
                }
            }

        private val explicitCommandOptions: CommandLineSection
            get() {
                val explicitOptionsCommandLinePriority: MutableList<com.google.devtools.common.options.ParsedOptionDescription> =
                    explicitOptions.stream()
                        .filter { parsedOptionDescription: com.google.devtools.common.options.ParsedOptionDescription? ->
                            commandLinePriority(
                                parsedOptionDescription
                            )
                        }
                        .collect(Collectors.toList())
                val starlarkOptions: MutableList<Option?> =
                    explicitStarlarkOptions.entries.stream()
                        .flatMap<Option?> { e: MutableMap.MutableEntry<String?, Any?>? ->
                            streamStarlarkOption(
                                e!!.key,
                                e.value
                            )
                        }
                        .collect(Collectors.toList())
                return CommandLineSection.newBuilder()
                    .setSectionLabel("command options")
                    .setOptionList(
                        OptionList.newBuilder()
                            .addAllOption(
                                getOptionListFromParsedOptionDescriptions(explicitOptionsCommandLinePriority)
                            )
                            .addAllOption(starlarkOptions)
                    )
                    .build()
            }

        public override fun asStreamProto(converters: BuildEventContext?): BuildEventStreamProtos.BuildEvent {
            return GenericBuildEvent.protoChaining(this)
                .setStructuredCommandLine(
                    CommandLine.newBuilder()
                        .setCommandLineLabel(LABEL)
                        .addSections(this.executableSection)
                        .addSections(this.startupOptionSection)
                        .addSections(this.commandSection)
                        .addSections(this.explicitCommandOptions)
                        .addSections(this.residual)
                        .build()
                )
                .build()
        }

        companion object {
            const val LABEL: String = "original"
            fun commandLinePriority(parsedOptionDescription: com.google.devtools.common.options.ParsedOptionDescription): Boolean {
                return (parsedOptionDescription.getPriority().getPriorityCategory()
                        == com.google.devtools.common.options.OptionPriority.PriorityCategory.COMMAND_LINE)
            }
        }
    }

    /** This reports the canonical form of the command line.  */
    class CanonicalCommandLineEvent(
        productName: String?,
        startupOptionsProvider: com.google.devtools.common.options.OptionsParsingResult,
        commandName: String,
        residue: MutableList<String>,
        includeResidueInRunBepEvent: Boolean,
        protected val explicitStarlarkOptions: MutableMap<String?, Any?>?,
        protected val starlarkOptions: MutableMap<String?, Any?>,
        starlarkOptionAllowingMultiple: MutableSet<String?>,
        canonicalOptions: MutableList<com.google.devtools.common.options.ParsedOptionDescription>,
        replaceable: Boolean
    ) : BazelCommandLineEvent(
        productName,
        startupOptionsProvider,
        commandName,
        residue,
        includeResidueInRunBepEvent,
        starlarkOptionAllowingMultiple
    ), ReplaceableBuildEvent {
        protected val canonicalOptions: MutableList<com.google.devtools.common.options.ParsedOptionDescription>
        private val replaceable: Boolean

        init {
            this.canonicalOptions = canonicalOptions
            this.replaceable = replaceable
        }

        val eventId: BuildEventId
            get() = BuildEventIdUtil.structuredCommandlineId(LABEL)

        public override fun replaceable(): Boolean {
            return replaceable
        }

        private val canonicalStartupOptions: CommandLineSection
            /**
             * Returns the effective startup options.
             * 
             * 
             * Since in this command line the command options include invocation policy's and rcs'
             * contents expanded fully, the list of startup options should prevent reapplication of these
             * contents.
             * 
             * 
             * The options parser does not understand the effect of these flags, since the relationship
             * between these startup options and the command options is not held within the options parser,
             * so instead, we add a small hack. Remove any explicit mentions of these flags, and explicitly
             * add the options that prevent Blaze from looking for the default rc files.
             */
            get() {
                val unfilteredOptions: MutableList<Option?> =
                    getActiveStartupOptions().getOptionList().getOptionList()
                // Create the fake ones to prevent reapplication of the original rc file contents.
                val fakeOptions: com.google.devtools.common.options.OptionsParser =
                    com.google.devtools.common.options.OptionsParser.builder()
                        .optionsClasses(BlazeServerStartupOptions::class.java).build()
                try {
                    fakeOptions.parse("--ignore_all_rc_files")
                } catch (e: com.google.devtools.common.options.OptionsParsingException) {
                    // Unless someone changes the definition of these flags, this is impossible.
                    throw java.lang.IllegalStateException(e)
                }

                // Remove any instances of the applied, and add the new blocking ones.
                return CommandLineSection.newBuilder()
                    .setSectionLabel("startup options")
                    .setOptionList(
                        OptionList.newBuilder()
                            .addAllOption(
                                unfilteredOptions.stream()
                                    .filter { option: Option? ->
                                        val optionName: String = option.getOptionName()
                                        (optionName != "ignore_all_rc_files") && (optionName != "blazerc") && (optionName != "master_blazerc") && (optionName != "bazelrc") && (optionName != "master_bazelrc") && (optionName != "invocation_policy")
                                    }
                                    .collect(Collectors.toList()))
                            .addAllOption(
                                getOptionListFromParsedOptionDescriptions(
                                    fakeOptions.asCompleteListOfParsedOptions()
                                )
                            ))
                    .build()
            }

        private val canonicalCommandOptions: CommandLineSection
            /** Returns the canonical command options, overridden and default values are not listed.  */
            get() {
                val starlarkOptionsAsList: MutableList<Option?> =
                    starlarkOptions.entries.stream()
                        .flatMap<Option?> { e: MutableMap.MutableEntry<String?, Any?>? ->
                            streamStarlarkOption(
                                e!!.key,
                                e.value
                            )
                        }
                        .collect(Collectors.toList())
                return CommandLineSection.newBuilder()
                    .setSectionLabel("command options")
                    .setOptionList(
                        OptionList.newBuilder()
                            .addAllOption(getOptionListFromParsedOptionDescriptions(canonicalOptions))
                            .addAllOption(starlarkOptionsAsList)
                    )
                    .build()
            }

        val explicitCommandLineHash: Long
            /**
             * Hash including the explicit command line options as well as the residue, e.g. the targets.
             */
            get() {
                var hash: Long = 0
                for (starlarkOption in starlarkOptions.entries) {
                    hash = hash * 31 + starlarkOption.toString().hashCode()
                }
                for (canonicalOptionDesc in canonicalOptions) {
                    if (canonicalOptionDesc == null || canonicalOptionDesc.isHidden()
                        || ("command line options" != canonicalOptionDesc.getSource())
                    ) {
                        continue
                    }
                    hash = hash * 31 + canonicalOptionDesc.getCanonicalForm().hashCode()
                }
                for (r in residue) {
                    hash = hash * 31 + r.hashCode()
                }
                return hash
            }

        public override fun asStreamProto(converters: BuildEventContext?): BuildEventStreamProtos.BuildEvent {
            return GenericBuildEvent.protoChaining(this)
                .setStructuredCommandLine(
                    CommandLine.newBuilder()
                        .setCommandLineLabel(LABEL)
                        .addSections(this.executableSection)
                        .addSections(this.canonicalStartupOptions)
                        .addSections(this.commandSection)
                        .addSections(this.canonicalCommandOptions)
                        .addSections(this.residual)
                        .build()
                )
                .build()
        }

        companion object {
            const val LABEL: String = "canonical"
        }
    }

    /**
     * A command line that Bazel accepts via flag (yes, we see the irony there).
     * 
     * 
     * Permits Bazel to report command lines from the tool that invoked it, if such a tool exists.
     */
    class ToolCommandLineEvent internal constructor(commandLine: CommandLine?) : CommandLineEvent() {
        private val commandLine: CommandLine?

        init {
            this.commandLine = commandLine
        }

        public override fun asStreamProto(converters: BuildEventContext?): BuildEvent {
            return GenericBuildEvent.protoChaining(this).setStructuredCommandLine(commandLine).build()
        }

        val eventId: BuildEventId
            /**
             * The label of this command line event is always "tool," so that the BuildStartingEvent
             * correctly tracks its children. The provided command line may have its own label that will be
             * more descriptive.
             */
            get() = BuildEventIdUtil.structuredCommandlineId(LABEL)

        /**
         * The converter for the option value. We accept the command line both in base64 encoded proto
         * form and as unstructured strings.
         */
        class Converter

            : com.google.devtools.common.options.Converter.Contextless<ToolCommandLineEvent?>() {
            @Throws(com.google.devtools.common.options.OptionsParsingException::class)
            override fun convert(input: String): ToolCommandLineEvent {
                if (input.isEmpty()) {
                    return ToolCommandLineEvent(CommandLine.getDefaultInstance())
                }

                var commandLine: CommandLine?
                try {
                    // Try decoding the input as a base64 encoded binary proto.
                    commandLine = CommandLine.parseFrom(com.google.common.io.BaseEncoding.base64().decode(input))
                } catch (e: java.lang.IllegalArgumentException) {
                    // If the value was not recognized as a base64-encoded proto, store the flag value as a
                    // single string chunk.
                    commandLine =
                        CommandLine.newBuilder()
                            .setCommandLineLabel(LABEL)
                            .addSections(
                                CommandLineSection.newBuilder()
                                    .setChunkList(ChunkList.newBuilder().addChunk(input))
                            )
                            .build()
                } catch (e: InvalidProtocolBufferException) {
                    throw com.google.devtools.common.options.OptionsParsingException(
                        String.format("Malformed value of --experimental_tool_command_line: %s", input), e
                    )
                }
                return ToolCommandLineEvent(commandLine)
            }

            val typeDescription: String
                get() = ("A command line, either as a simple string, or as a base64-encoded binary form of a"
                        + " CommandLine proto")
        }

        override fun toString(): String {
            return com.google.common.base.MoreObjects.toStringHelper(this).add("commandLine", commandLine).toString()
        }

        companion object {
            const val LABEL: String = "tool"
        }
    }
}
