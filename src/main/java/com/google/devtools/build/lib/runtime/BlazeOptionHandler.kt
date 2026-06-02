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
package com.google.devtools.build.lib.runtime

import com.google.devtools.build.lib.analysis.config.CoreOptionConverters

/**
 * Handles parsing the blaze command arguments.
 * 
 * 
 * This class manages rc options, configs, default options, and invocation policy.
 */
class BlazeOptionHandler internal constructor(
    runtime: BlazeRuntime,
    workspace: BlazeWorkspace,
    command: BlazeCommand,
    commandAnnotation: com.google.devtools.build.lib.runtime.Command,
    optionsParser: com.google.devtools.common.options.OptionsParser,
    invocationPolicy: InvocationPolicy?
) {
    private val runtime: BlazeRuntime
    private val optionsParser: com.google.devtools.common.options.OptionsParser
    private val workspace: BlazeWorkspace
    private val command: BlazeCommand
    private val commandAnnotation: com.google.devtools.build.lib.runtime.Command
    private val invocationPolicy: InvocationPolicy?
    @kotlin.jvm.JvmField
    val rcfileNotes: MutableList<String?> = java.util.ArrayList<String?>()
    private val allOptionsClasses: com.google.common.collect.ImmutableList<java.lang.Class<out com.google.devtools.common.options.OptionsBase?>?>

    init {
        this.runtime = runtime
        this.workspace = workspace
        this.command = command
        this.commandAnnotation = commandAnnotation
        this.optionsParser = optionsParser
        this.invocationPolicy = invocationPolicy
        this.allOptionsClasses =
            runtime.getCommandMap().values().stream()
                .map(java.util.function.Function { obj: BlazeCommand? -> obj.getClass() })
                .flatMap<java.lang.Class<out com.google.devtools.common.options.OptionsBase?>?>(
                    java.util.function.Function { cmd: java.lang.Class<BlazeCommand?>? ->
                        BlazeCommandUtils.getOptions(
                            cmd, runtime.getOptionsSuppliers(), runtime.getRuleClassProvider()
                        )
                            .stream()
                    })
                .distinct()
                .collect(com.google.common.collect.ImmutableList.toImmutableList<java.lang.Class<out com.google.devtools.common.options.OptionsBase?>?>())
    }

    val optionsResult: com.google.devtools.common.options.OptionsParsingResult
        /**
         * Return options as [OptionsParsingResult] so the options can't be easily modified after
         * we've applied the invocation policy.
         */
        get() = optionsParser

    /**
     * Only some commands work if cwd != workspaceSuffix in Blaze. In that case, also check if Blaze
     * was called from the output directory and fail if it was.
     */
    private fun checkCwdInWorkspace(eventHandler: com.google.devtools.build.lib.events.EventHandler): DetailedExitCode {
        if (!commandAnnotation.mustRunInWorkspace) {
            return DetailedExitCode.success()
        }

        if (!workspace.getDirectories().inWorkspace()) {
            val message =
                ("The '"
                        + commandAnnotation.name
                        + "' command is only supported from within a workspace"
                        + " (below a directory having a MODULE.bazel file).\n"
                        + "See documentation at"
                        + " https://bazel.build/concepts/build-ref#workspace")
            eventHandler.handle(com.google.devtools.build.lib.events.Event.error(message))
            return createDetailedExitCode(message, Code.NOT_IN_WORKSPACE)
        }

        val workspacePath: com.google.devtools.build.lib.vfs.Path = workspace.getWorkspace()
        if (workspacePath.getParentDirectory() != null) {
            val doNotBuild: com.google.devtools.build.lib.vfs.Path =
                workspacePath.getParentDirectory().getRelative(BlazeWorkspace.Companion.DO_NOT_BUILD_FILE_NAME)

            if (doNotBuild.exists()) {
                val message = getNotInRealWorkspaceError(doNotBuild)
                eventHandler.handle(com.google.devtools.build.lib.events.Event.error(message))
                return createDetailedExitCode(message, Code.IN_OUTPUT_DIRECTORY)
            }
        }
        return DetailedExitCode.success()
    }

    /**
     * Parses the unconditional options from .rc files for the current command.
     * 
     * 
     * This is not as trivial as simply taking the list of options for the specified command
     * because commands can inherit arguments from each other, and we have to respect that (e.g. if an
     * option is specified for 'build', it needs to take effect for the 'test' command, too). More
     * specific commands should have priority over the broader commands (say a "build" option that
     * conflicts with a "common" option should override the common one regardless of order.)
     * 
     * 
     * For each command, the options are parsed in rc order. This uses the primary rc file first,
     * and follows import statements. This is the order in which they were passed by the client.
     */
    @com.google.common.annotations.VisibleForTesting
    @Throws(com.google.devtools.common.options.OptionsParsingException::class)
    fun parseRcOptions(
        eventHandler: com.google.devtools.build.lib.events.EventHandler?,
        commandToRcArgs: com.google.common.collect.ListMultimap<String, RcChunkOfArgs>
    ) {
        for (commandToParse in getCommandNamesToParse(commandAnnotation)) {
            // Get all args defined for this command (or "common"), grouped by rc chunk.
            for (rcArgs in commandToRcArgs.get(commandToParse)) {
                if (!rcArgs.getArgs().isEmpty()) {
                    val inherited = if (commandToParse == commandAnnotation.name) "" else "Inherited "
                    val source: String? =
                        if (rcArgs.getRcFile() == "client")
                            "Options provided by the client"
                        else
                            java.lang.String.format(
                                "Reading rc options for '%s' from %s",
                                commandAnnotation.name, rcArgs.getRcFile()
                            )
                    rcfileNotes.add(
                        java.lang.String.format(
                            "%s:\n  %s'%s' options: %s",
                            source,
                            inherited,
                            commandToParse,
                            com.google.common.base.Joiner.on(' ').join(rcArgs.getArgs())
                        )
                    )
                }
                if (commandToParse == COMMON_PSEUDO_COMMAND) {
                    // Pass in options data for all commands supported by the runtime so that options that
                    // apply to some but not the current command can be ignored.
                    //
                    // Important note: The consistency checks performed by
                    // OptionsParser#getFallbackOptionsData ensure that there aren't any two options across
                    // all commands that have the same name but parse differently (e.g. because one accepts
                    // a value and the other doesn't). This means that the options available on a command
                    // limit the options available on other commands even without command inheritance. This
                    // restriction is necessary to ensure that the options specified on the "common"
                    // pseudo command can be parsed unambiguously.
                    val ignoredArgs: com.google.common.collect.ImmutableList<String?> =
                        optionsParser.parseWithSourceFunction(
                            com.google.devtools.common.options.OptionPriority.PriorityCategory.RC_FILE,
                            java.util.function.Function { o: com.google.devtools.common.options.OptionDefinition? -> rcArgs.getRcFile() },
                            rcArgs.getArgs(),
                            com.google.devtools.common.options.OptionsParser.getFallbackOptionsData(allOptionsClasses)
                        )
                    if (!ignoredArgs.isEmpty()) {
                        // Append richer information to the note.
                        val index: Int = rcfileNotes.size() - 1
                        var note = rcfileNotes.get(index)
                        note +=
                            java.lang.String.format(
                                "\n  Ignored as unsupported by '%s': %s",
                                commandAnnotation.name, com.google.common.base.Joiner.on(' ').join(ignoredArgs)
                            )
                        rcfileNotes.set(index, note)
                    }
                } else {
                    optionsParser.parse(
                        com.google.devtools.common.options.OptionPriority.PriorityCategory.RC_FILE,
                        rcArgs.getRcFile(),
                        rcArgs.getArgs()
                    )
                }
            }
        }
    }

    /**
     * Returns a map from rc file definitions to the options they define and which rc files defined.
     * them. For example, "build:asan" keys a `--config=asan` definition and "build" keys
     * options that apply to all build commands.
     */
    @Throws(
        com.google.devtools.common.options.OptionsParsingException::class,
        java.lang.InterruptedException::class,
        AbruptExitException::class
    )
    private fun parseArgsAndConfigs(
        args: MutableList<String?>, eventHandler: ExtendedEventHandler
    ): com.google.common.collect.ListMultimap<String, RcChunkOfArgs> {
        val workspaceDirectory: com.google.devtools.build.lib.vfs.Path = workspace.getWorkspace()
        // TODO(ulfjack): The working directory is passed by the client as part of CommonCommandOptions,
        // and we can't know it until after we've parsed the options, so use the workspace for now.
        val workingDirectory: com.google.devtools.build.lib.vfs.Path = workspace.getWorkspace()

        val commandOptionSourceFunction: java.util.function.Function<com.google.devtools.common.options.OptionDefinition?, String?> =
            java.util.function.Function { option: com.google.devtools.common.options.OptionDefinition? ->
                if (INTERNAL_COMMAND_OPTIONS.contains(option.getOptionName())) {
                    return@Function "options generated by " + runtime.productName + " launcher"
                } else {
                    return@Function "command line options"
                }
            }

        // Explicit command-line options:
        val cmdLineAfterCommand = args.subList(1, args.size())

        // Before parsing any rcfiles we need to first parse --rc_source so the parser can reference the
        // proper rcfiles. The --default_override options should be parsed with the --rc_source since
        // {@link #parseRcOptions} depends on the list populated by the {@link
        // ClientOptions#OptionOverrideConverter}.
        val defaultOverridesAndRcSources: com.google.common.collect.ImmutableList.Builder<String?> =
            com.google.common.collect.ImmutableList.Builder<String?>()
        val remainingCmdLine: com.google.common.collect.ImmutableList.Builder<String?> =
            com.google.common.collect.ImmutableList.Builder<String?>()
        partitionCommandLineArgs(cmdLineAfterCommand, defaultOverridesAndRcSources, remainingCmdLine)

        // Parses options needed to parse rcfiles properly.
        optionsParser.parseWithSourceFunction(
            com.google.devtools.common.options.OptionPriority.PriorityCategory.COMMAND_LINE,
            commandOptionSourceFunction,
            defaultOverridesAndRcSources.build(),  /* fallbackData= */
            null
        )

        // Command-specific options from .blazerc passed in via --default_override and --rc_source.
        val rcFileOptions: ClientOptions? = optionsParser.getOptions<ClientOptions?>(ClientOptions::class.java)
        val commandToRcArgs: com.google.common.collect.ListMultimap<String, RcChunkOfArgs> =
            structureRcOptionsAndConfigs(
                eventHandler,
                rcFileOptions.getRcSource(),
                rcFileOptions.getOptionsOverrides(),
                runtime.getCommandMap().keySet()
            )
        parseRcOptions(eventHandler, commandToRcArgs)

        // Parses the remaining command-line options.
        optionsParser.parseWithSourceFunction(
            com.google.devtools.common.options.OptionPriority.PriorityCategory.COMMAND_LINE,
            commandOptionSourceFunction,
            remainingCmdLine.build(),  /* fallbackData= */
            null
        )

        if (commandAnnotation.buildPhase.analyzes()) {
            // split project files from targets in the traditional sense.
            ProjectFileSupport.handleProjectFiles(
                eventHandler,
                runtime.getProjectFileProvider(),
                workspaceDirectory.asFragment(),
                workingDirectory,
                optionsParser,
                commandAnnotation.name
            )
        }

        expandConfigOptions(eventHandler, commandToRcArgs)
        return commandToRcArgs
    }

    /**
     * [ExtendedEventHandler] override that passes through "normal" events but not events that
     * would go to the build event proto.
     * 
     * 
     * Starlark flags are conceptually options but still need target pattern evaluation. If we pass
     * [.post]able events from that evaluation, that would produce "target loaded" and "target
     * configured" events in the build event proto output that consumers can confuse with actual
     * targets requested by the build.
     * 
     * 
     * This is important because downstream services (like a continuous integration tool or build
     * results dashboard) read these messages to reconcile which requested targets were built. If they
     * determine Blaze tried to build `//foo //bar` then see a "target configured" message for
     * some other target `//my_starlark_flag`, they might show misleading messages like "Built 3
     * of 2 requested targets.".
     * 
     * 
     * Hence this class. By dropping those events, we restrict all info and error reporting logic
     * to the options parsing pipeline.
     */
    private class NonPostingEventHandler(delegate: ExtendedEventHandler) : ExtendedEventHandler {
        private val delegate: ExtendedEventHandler

        init {
            this.delegate = delegate
        }

        override fun handle(e: com.google.devtools.build.lib.events.Event?) {
            delegate.handle(e)
        }

        override fun post(e: Postable?) {
            // Fetches of external repositories are not reported as BES events and important to surface
            // in the CLI due to their long-running nature.
            if (e is FetchProgress) {
                delegate.post(e)
            }
        }
    }

    /**
     * Lets [StarlarkOptionsParser] convert flag names to [Target]s through [ ].
     * 
     * 
     * This is used for top-level flag parsing, outside any [SkyFunction].
     */
    class SkyframeExecutorTargetLoader

        : BuildSettingLoader {
        private val skyframeExecutor: SkyframeExecutor
        private val relativeWorkingDirectory: PathFragment?
        private val reporter: ExtendedEventHandler

        constructor(env: CommandEnvironment) {
            this.skyframeExecutor = env.getSkyframeExecutor()
            this.relativeWorkingDirectory = env.getRelativeWorkingDirectory()
            this.reporter = NonPostingEventHandler(env.getReporter())
        }

        @com.google.common.annotations.VisibleForTesting
        constructor(
            skyframeExecutor: SkyframeExecutor,
            relativeWorkingDirectory: PathFragment?,
            reporter: ExtendedEventHandler
        ) {
            this.skyframeExecutor = skyframeExecutor
            this.relativeWorkingDirectory = relativeWorkingDirectory
            this.reporter = NonPostingEventHandler(reporter)
        }

        @Throws(java.lang.InterruptedException::class, TargetParsingException::class)
        override fun loadBuildSetting(targetLabel: String?): Target? {
            val tpv: TargetPatternPhaseValue =
                skyframeExecutor.loadTargetPatternsWithoutFilters(
                    reporter,
                    Collections.singletonList<String?>(targetLabel),
                    relativeWorkingDirectory,
                    SkyframeExecutor.DEFAULT_THREAD_COUNT,  /* keepGoing= */
                    false
                )
            val result: com.google.common.collect.ImmutableSet<Target?> =
                tpv.getTargets(reporter, skyframeExecutor.getPackageManager())
            if (result.size() != 1) {
                throw TargetParsingException(
                    "user-defined flags must reference exactly one target",
                    TargetPatterns.Code.TARGET_FORMAT_INVALID
                )
            }
            return com.google.common.collect.Iterables.getOnlyElement<Target?>(result)
        }
    }

    /**
     * TODO(bazel-team): When we move CoreOptions options to be defined in starlark, make sure they're
     * not passed in here during [.getOptionsResult].
     */
    fun parseStarlarkOptions(env: CommandEnvironment, args: MutableList<String?>): DetailedExitCode {
        // For now, restrict starlark options to commands that already build to ensure that loading
        // will work. We may want to open this up to other commands in the future.
        if (!commandAnnotation.buildPhase.analyzes()) {
            return DetailedExitCode.success()
        }
        try {
            val buildSettingLoader: BuildSettingLoader = SkyframeExecutorTargetLoader(env)
            val starlarkOptionsParser: StarlarkOptionsParser =
                StarlarkOptionsParser.builder()
                    .buildSettingLoader(buildSettingLoader)
                    .nativeOptionsParser(optionsParser)
                    .build()
            com.google.common.base.Preconditions.checkState(starlarkOptionsParser.parse())
        } catch (e: com.google.devtools.common.options.OptionsParsingException) {
            val logMessage = "Error parsing Starlark options"
            if (e.getInvalidArgument() != null) {
                var i = 0
                while (i < args.size() - 1) {
                    if (args.get(i) == e.getInvalidArgument() && !args.get(i + 1).startsWith("-")) {
                        e =
                            com.google.devtools.common.options.OptionsParsingException(
                                java.lang.String.format(
                                    "%s. Did you mean %s=%s?", e.getMessage(), args.get(i), args.get(i + 1)
                                ),
                                e.getInvalidArgument()
                            )
                    }
                    i++
                }
            }
            logger.atInfo().withCause(e).log("%s", logMessage)
            return processOptionsParsingException(
                env.getReporter(), e, logMessage, Code.STARLARK_OPTIONS_PARSE_FAILURE
            )
        } catch (e: java.lang.InterruptedException) {
            val message = "Interrupted while parsing Starlark options"
            env.getReporter().handle(com.google.devtools.build.lib.events.Event.error(message))
            return InterruptedFailureDetails.detailedExitCode(message)
        }
        return DetailedExitCode.success()
    }

    /** Detailed parsing results: exit code and `--config=foo` definitions.  */
    internal class DetailedParseResults(
        detailedExitCode: DetailedExitCode,
        configFlagDefinitions: ConfigFlagDefinitions?
    ) {
        val detailedExitCode: DetailedExitCode
        val configFlagDefinitions: ConfigFlagDefinitions?

        init {
            this.detailedExitCode = detailedExitCode
            this.configFlagDefinitions = configFlagDefinitions
        }
    }

    /**
     * Parses the options, taking care not to generate any output to outErr, return, or throw an
     * exception.
     * 
     * @return `DetailedExitCode.success()` if everything went well, or some other value if not
     */
    fun parseOptions(
        args: MutableList<String?>,
        eventHandler: ExtendedEventHandler,
        invocationPolicyFlagListBuilder: com.google.common.collect.ImmutableList.Builder<com.google.devtools.common.options.OptionAndRawValue?>?
    ): DetailedExitCode {
        val result =
            parseOptionsInternal(
                args, eventHandler, invocationPolicyFlagListBuilder,  /* getConfigDefinitions= */false
            )
        if (!result.detailedExitCode.isSuccess()) {
            optionsParser.setError()
        }
        return result.detailedExitCode
    }

    /**
     * [.parseOptions] variation that also returns `--config=foo` definitions. Callers can
     * use this to determine which flags `--config=foo` sets.
     */
    fun parseOptionsAndGetConfigDefinitions(
        args: MutableList<String?>,
        eventHandler: ExtendedEventHandler,
        invocationPolicyFlagListBuilder: com.google.common.collect.ImmutableList.Builder<com.google.devtools.common.options.OptionAndRawValue?>?
    ): DetailedParseResults {
        val result =
            parseOptionsInternal(
                args, eventHandler, invocationPolicyFlagListBuilder,  /* getConfigDefinitions= */true
            )
        if (!result.detailedExitCode.isSuccess()) {
            optionsParser.setError()
        }
        return result
    }

    private fun parseOptionsInternal(
        args: MutableList<String?>,
        eventHandler: ExtendedEventHandler,
        invocationPolicyFlagListBuilder: com.google.common.collect.ImmutableList.Builder<com.google.devtools.common.options.OptionAndRawValue?>?,
        getConfigDefinitions: Boolean
    ): DetailedParseResults {
        // The initialization code here was carefully written to parse the options early before we call
        // into the BlazeModule APIs, which means we must not generate any output to outErr, return, or
        // throw an exception. All the events happening here are instead stored in a temporary event
        // handler, and later replayed.
        val earlyExitCode: DetailedExitCode = checkCwdInWorkspace(eventHandler)
        if (!earlyExitCode.isSuccess()) {
            return DetailedParseResults(
                earlyExitCode,
                ConfigFlagDefinitions(com.google.common.collect.ImmutableListMultimap.of<String?, ConfigDefinition?>())
            )
        }

        var rcDefinitions: com.google.common.collect.ListMultimap<String, RcChunkOfArgs> =
            com.google.common.collect.ImmutableListMultimap.of<String?, RcChunkOfArgs?>()
        var exitCode: DetailedExitCode
        try {
            rcDefinitions = parseArgsAndConfigs(args, eventHandler)
            // Allow the command to edit the options.
            command.editOptions(optionsParser)
            // Merge the invocation policy that is user-supplied, from the command line, and any
            // invocation policy that was added by a module. The module one goes 'first,' so the user
            // one has priority.
            val combinedPolicy: InvocationPolicy? =
                InvocationPolicy.newBuilder()
                    .mergeFrom(runtime.getModuleInvocationPolicy())
                    .mergeFrom(invocationPolicy)
                    .build()
            val optionsPolicyEnforcer: InvocationPolicyEnforcer =
                InvocationPolicyEnforcer(
                    combinedPolicy, java.util.logging.Level.INFO, optionsParser.getConversionContext()
                )
            // Enforce the invocation policy. It is intentional that this is the last step in preparing
            // the options. The invocation policy is used in security-critical contexts, and may be used
            // as a last resort to override flags. That means that the policy can override flags set in
            // BlazeCommand.editOptions, so the code needs to be safe regardless of the actual flag
            // values. At the time of this writing, editOptions was only used as a convenience feature or
            // to improve the user experience, but not required for safety or correctness.
            optionsPolicyEnforcer.enforce(
                optionsParser, commandAnnotation.name, invocationPolicyFlagListBuilder
            )
            // Print warnings for odd options usage
            for (warning in optionsParser.getWarnings()) {
                eventHandler.handle(com.google.devtools.build.lib.events.Event.warn(warning))
            }
            val commonOptions: CommonCommandOptions? =
                optionsParser.getOptions<CommonCommandOptions?>(CommonCommandOptions::class.java)
            for (warning in commonOptions.getDeprecationWarnings()) {
                eventHandler.handle(com.google.devtools.build.lib.events.Event.warn(warning))
            }
            exitCode = DetailedExitCode.success()
        } catch (e: com.google.devtools.common.options.OptionsParsingException) {
            val logMessage = "Error parsing options"
            logger.atInfo().withCause(e).log("%s", logMessage)
            exitCode =
                processOptionsParsingException(eventHandler, e, logMessage, Code.OPTIONS_PARSE_FAILURE)
        } catch (e: java.lang.InterruptedException) {
            exitCode =
                DetailedExitCode.of(
                    FailureDetail.newBuilder()
                        .setInterrupted(
                            FailureDetails.Interrupted.newBuilder()
                                .setCode(FailureDetails.Interrupted.Code.INTERRUPTED)
                        )
                        .build()
                )
        } catch (e: AbruptExitException) {
            exitCode = e.getDetailedExitCode()
        }

        if (!getConfigDefinitions) {
            return DetailedParseResults(
                exitCode,
                ConfigFlagDefinitions(com.google.common.collect.ImmutableListMultimap.of<String?, ConfigDefinition?>())
            )
        }
        // Transforms all rc definitions into valid --config definitions for this command. For example,
        // "build:asan" is valid for a build command but not "test:asan". Rc definitions like "build"
        // aren't included because those aren't --config definitions.
        val validConfigDefs: com.google.common.collect.ImmutableListMultimap.Builder<String?, ConfigDefinition?> =
            com.google.common.collect.ImmutableListMultimap.Builder<String?, ConfigDefinition?>()
        val matchingRcCommands = getCommandNamesToParse(commandAnnotation)
        for (entry in rcDefinitions.entries()) {
            val rcKey: String = entry.getKey()
            val firstColon: Int = rcKey.indexOf(":")
            if (firstColon == -1) {
                continue
            }
            val cmd: String = rcKey.substring(0, firstColon)
            val configName: String = rcKey.substring(firstColon + 1)
            if (matchingRcCommands.contains(cmd)) {
                validConfigDefs.put(
                    configName,
                    ConfigDefinition(
                        com.google.common.collect.ImmutableList.copyOf<String?>(entry.getValue().getArgs()),
                        entry.getValue().getRcFile()
                    )
                )
            }
        }
        return DetailedParseResults(exitCode, ConfigFlagDefinitions(validConfigDefs.build()))
    }

    /**
     * Expand the values of --config according to the definitions provided in the rc files and the
     * applicable command.
     */
    @com.google.common.annotations.VisibleForTesting
    @Throws(com.google.devtools.common.options.OptionsParsingException::class)
    fun expandConfigOptions(
        eventHandler: com.google.devtools.build.lib.events.EventHandler?,
        commandToRcArgs: com.google.common.collect.ListMultimap<String, RcChunkOfArgs>?
    ) {
        ConfigExpander.expandConfigOptions(
            eventHandler,
            commandToRcArgs,
            commandAnnotation.name,
            getCommandNamesToParse(commandAnnotation),
            java.util.function.Consumer { e: String? -> rcfileNotes.add(e) },
            optionsParser,
            com.google.devtools.common.options.OptionsParser.getFallbackOptionsData(allOptionsClasses)
        )
    }

    private fun getNotInRealWorkspaceError(doNotBuildFile: com.google.devtools.build.lib.vfs.Path?): String? {
        var message: String? =
            java.lang.String.format(
                "%1\$s should not be called from a %1\$s output directory. ", runtime.productName
            )
        try {
            val realWorkspace =
                String(com.google.devtools.build.lib.vfs.FileSystemUtils.readContentAsLatin1(doNotBuildFile))
            message += java.lang.String.format("The pertinent workspace directory is: '%s'", realWorkspace)
        } catch (e: IOException) {
            // We are exiting anyway.
        }

        return message
    }

    companion object {
        private val logger: GoogleLogger = GoogleLogger.forEnclosingClass()

        // Keep in sync with options added in OptionProcessor::AddRcfileArgsAndOptions()
        private val INTERNAL_COMMAND_OPTIONS: com.google.common.collect.ImmutableSet<String?> =
            com.google.common.collect.ImmutableSet.of<String?>(
                "rc_source",
                "default_override",
                "isatty",
                "terminal_columns",
                "ignore_client_env",
                "client_env",
                "client_cwd"
            )

        // All options set on this pseudo command are inherited by all commands, with unrecognized options
        // resulting in an error.
        private const val ALWAYS_PSEUDO_COMMAND = "always"

        // All options set on this pseudo command are inherited by all commands, with unrecognized options
        // being ignored as long as they are recognized by at least one (other) command.
        const val COMMON_PSEUDO_COMMAND: String = "common"

        // Startup options are processed by the C++ client before the Java server starts.
        private const val STARTUP_PSEUDO_COMMAND = "startup"

        private val BUILD_COMMAND_ANCESTORS: com.google.common.collect.ImmutableSet<String?> =
            com.google.common.collect.ImmutableSet.of<String?>("build", COMMON_PSEUDO_COMMAND, ALWAYS_PSEUDO_COMMAND)

        // Marks an event to indicate a parsing error.
        const val BAD_OPTION_TAG: String = "invalidOption"

        // Separates the invalid tag from the full error message for easier parsing.
        const val ERROR_SEPARATOR: String = " :: "

        private fun getCommandNamesToParse(commandAnnotation: com.google.devtools.build.lib.runtime.Command): MutableList<String> {
            val result: MutableList<String> = java.util.ArrayList<String>()
            result.add(ALWAYS_PSEUDO_COMMAND)
            result.add(COMMON_PSEUDO_COMMAND)
            getCommandNamesToParseHelper(commandAnnotation, result)
            return result
        }

        private fun getCommandNamesToParseHelper(
            commandAnnotation: com.google.devtools.build.lib.runtime.Command, accumulator: MutableList<String>
        ) {
            for (base in commandAnnotation.inheritsOptionsFrom) {
                getCommandNamesToParseHelper(
                    base.getAnnotation<com.google.devtools.build.lib.runtime.Command?>(com.google.devtools.build.lib.runtime.Command::class.java),
                    accumulator
                )
            }
            accumulator.add(commandAnnotation.name)
        }

        private fun processOptionsParsingException(
            eventHandler: ExtendedEventHandler,
            e: com.google.devtools.common.options.OptionsParsingException,
            logMessage: String?,
            failureCode: Code?
        ): DetailedExitCode {
            val error: com.google.devtools.build.lib.events.Event?
            // Differentiates errors stemming from an invalid argument and errors from different parts of
            // the codebase.
            if (e.getInvalidArgument() != null) {
                error =
                    com.google.devtools.build.lib.events.Event.error(e.getInvalidArgument() + ERROR_SEPARATOR + e.getMessage())
                        .withTag(BAD_OPTION_TAG)
            } else {
                error = com.google.devtools.build.lib.events.Event.error(e.getMessage())
            }
            eventHandler.handle(error)
            return createDetailedExitCode(logMessage + ": " + e.getMessage(), failureCode)
        }

        /**
         * The rc options are passed via [ClientOptions.optionsOverrides] and [ ][ClientOptions.rcSource], which is basically a line-by-line transfer of the rc files read by the
         * client. This is not a particularly useful format for expanding the options, so this method
         * structures the list so that it is easier to find the arguments that apply to a command, or to
         * find the definitions of a config value.
         */
        @com.google.common.annotations.VisibleForTesting
        @Throws(com.google.devtools.common.options.OptionsParsingException::class)
        fun structureRcOptionsAndConfigs(
            eventHandler: com.google.devtools.build.lib.events.EventHandler,
            rcFiles: MutableList<String>,
            rawOverrides: MutableList<OptionOverride>,
            validCommands: MutableSet<String?>
        ): com.google.common.collect.ListMultimap<String, RcChunkOfArgs> {
            val commandToRcArgs: com.google.common.collect.ListMultimap<String, RcChunkOfArgs> =
                com.google.common.collect.ArrayListMultimap.create<String?, RcChunkOfArgs?>()

            var lastRcFile: String? = null
            var commandToArgMapForLastRc: LinkedHashMap<String?, MutableList<String?>>? = null
            for (override in rawOverrides) {
                if (override.blazeRc < 0 || override.blazeRc >= rcFiles.size()) {
                    eventHandler.handle(
                        com.google.devtools.build.lib.events.Event.warn("inconsistency in generated command line args. Ignoring bogus argument\n")
                    )
                    continue
                }
                val rcFile = rcFiles.get(override.blazeRc)
                // The canonicalize-flags command only inherits bazelrc "build" commands. Not "test", not
                // "build:foo". Restrict --flag_alias accordingly to prevent building with flags that
                // canonicalize-flags can't recognize.
                if ((override.option.startsWith("--" + CoreOptionConverters.BLAZE_ALIASING_FLAG + "=")
                            || override.option == "--" + CoreOptionConverters.BLAZE_ALIASING_FLAG)
                    && !BUILD_COMMAND_ANCESTORS.contains(override.command)
                ) {
                    throw com.google.devtools.common.options.OptionsParsingException(
                        java.lang.String.format(
                            "%s: \"%s %s\" disallowed. --%s only supports these commands: %s",
                            rcFile,
                            override.command,
                            override.option,
                            CoreOptionConverters.BLAZE_ALIASING_FLAG,
                            java.lang.String.join(", ", BUILD_COMMAND_ANCESTORS)
                        )
                    )
                }
                var command: String = override.command
                val index: Int = command.indexOf(':'.code)
                if (index > 0) {
                    command = command.substring(0, index)
                }
                if (!validCommands.contains(command) && (command != ALWAYS_PSEUDO_COMMAND) && (command != COMMON_PSEUDO_COMMAND) && (command != STARTUP_PSEUDO_COMMAND)) {
                    eventHandler.handle(
                        com.google.devtools.build.lib.events.Event.warn(
                            ("while reading option defaults file '"
                                    + rcFile
                                    + "':\n"
                                    + "  invalid command name '"
                                    + override.command
                                    + "'.")
                        )
                    )
                    continue
                }

                // We've moved on to another rc file "chunk," store the accumulated args from the last one.
                if (rcFile != lastRcFile) {
                    if (lastRcFile != null) {
                        // Go through the various commands identified in this rc file (or chunk of file) and
                        // store them grouped first by command, then by rc chunk.
                        for (entry in commandToArgMapForLastRc.entrySet()) {
                            commandToRcArgs.put(
                                entry.getKey(),
                                RcChunkOfArgs(
                                    lastRcFile,
                                    com.google.common.collect.ImmutableList.copyOf<String?>(entry.getValue())
                                )
                            )
                        }
                    }
                    lastRcFile = rcFile
                    commandToArgMapForLastRc = LinkedHashMap<String?, MutableList<String?>>()
                }

                val argsForCommand: MutableList<String?> =
                    commandToArgMapForLastRc.computeIfAbsent(
                        override.command,
                        java.util.function.Function { unused: String? -> java.util.ArrayList<String?>() })
                if (!override.option.isEmpty()) {
                    argsForCommand.add(override.option)
                } else if (override.command.indexOf(':'.code) == -1) {
                    commandToArgMapForLastRc.remove(override.command)
                }
            }
            if (lastRcFile != null) {
                // Once again, for this last rc file chunk, store them grouped by command.
                for (entry in commandToArgMapForLastRc.entrySet()) {
                    commandToRcArgs.put(
                        entry.getKey(),
                        RcChunkOfArgs(
                            lastRcFile,
                            com.google.common.collect.ImmutableList.copyOf<String?>(entry.getValue())
                        )
                    )
                }
            }

            return commandToRcArgs
        }

        private fun createDetailedExitCode(message: String?, detailedCode: Code?): DetailedExitCode {
            return DetailedExitCode.of(
                FailureDetail.newBuilder()
                    .setMessage(message)
                    .setCommand(FailureDetails.Command.newBuilder().setCode(detailedCode))
                    .build()
            )
        }

        private fun partitionCommandLineArgs(
            cmdLine: MutableList<String?>,
            defaultOverridesAndRcSources: com.google.common.collect.ImmutableList.Builder<String?>,
            remainingCmdLine: com.google.common.collect.ImmutableList.Builder<String?>
        ) {
            val cmdLineIterator: MutableIterator<String> = cmdLine.iterator()

            while (cmdLineIterator.hasNext()) {
                val option = cmdLineIterator.next()
                if (option.startsWith("--rc_source=") || option.startsWith("--default_override=")) {
                    defaultOverridesAndRcSources.add(option)
                } else if (option == "--rc_source" || option == "--default_override") {
                    val possibleArgument: java.util.Optional<String?> =
                        if (cmdLineIterator.hasNext()) java.util.Optional.of<String?>(cmdLineIterator.next()) else java.util.Optional.empty<String?>()
                    defaultOverridesAndRcSources.add(option)
                    if (possibleArgument.isPresent()) {
                        defaultOverridesAndRcSources.add(possibleArgument.get())
                    }
                } else {
                    remainingCmdLine.add(option)
                }
            }
        }
    }
}
