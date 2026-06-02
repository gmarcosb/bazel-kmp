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

import com.google.devtools.build.lib.runtime.Command.BuildPhase.EXECUTES

/** Builds and run a target with the given command line arguments.  */
@Command(
    name = "run",
    buildPhase = EXECUTES,
    options = [RunOptions::class],
    inheritsOptionsFrom = [BuildCommand::class],
    shortDescription = "Runs the specified target.",
    help = "resource:run.txt",
    allowResidue = true,
    hasSensitiveResidue = true,
    completion = "label-bin"
)
class RunCommand(testPolicy: TestPolicy) : BlazeCommand {
    /** Options for the "run" command.  */
    @com.google.devtools.common.options.OptionsClass
    abstract class RunOptions : com.google.devtools.common.options.OptionsBase() {
        @get:com.google.devtools.common.options.Option(
            name = "script_path",
            defaultValue = "null",
            documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.OUTPUT_PARAMETERS,
            effectTags = [com.google.devtools.common.options.OptionEffectTag.AFFECTS_OUTPUTS, com.google.devtools.common.options.OptionEffectTag.EXECUTION],
            converter = com.google.devtools.build.lib.util.OptionsUtils.PathFragmentConverter::class,
            help = ("If set, write a shell script to the given file which invokes the target. If this"
                    + " option is set, the target is not run from %{product}. Use '%{product} run"
                    + " --script_path=foo //foo && ./foo' to invoke target '//foo' This differs from"
                    + " '%{product} run //foo' in that the %{product} lock is released and the"
                    + " executable is connected to the terminal's stdin.")
        )
        abstract val scriptPath: PathFragment?

        @get:com.google.devtools.common.options.Option(
            name = "emit_script_path_in_exec_request",
            defaultValue = "false",
            documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.UNDOCUMENTED,
            effectTags = [com.google.devtools.common.options.OptionEffectTag.AFFECTS_OUTPUTS],
            help = ("If true, emits the ExecRequest with --script_path file value and script contents"
                    + " instead of writing the script.")
        )
        abstract val emitScriptPathInExecRequest: Boolean

        @get:com.google.devtools.common.options.Option(
            name = "run",
            defaultValue = "true",
            documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.BAZEL_CLIENT_OPTIONS,
            effectTags = [com.google.devtools.common.options.OptionEffectTag.AFFECTS_OUTPUTS],
            help = ("If false, skip running the command line constructed for the built target. Note that"
                    + " this flag is ignored for all --script_path builds.")
        )
        abstract val runBuiltTarget: Boolean

        @get:com.google.devtools.common.options.Option(
            name = "portable_paths",
            defaultValue = "false",
            documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.BAZEL_CLIENT_OPTIONS,
            effectTags = [com.google.devtools.common.options.OptionEffectTag.AFFECTS_OUTPUTS],
            help = ("If true, includes paths to replace in ExecRequest to make the resulting paths"
                    + " portable.")
        )
        abstract val portablePaths: Boolean

        @get:com.google.devtools.common.options.Option(
            name = "run_env",
            converter = com.google.devtools.build.lib.util.EnvVar.Converter::class,
            allowMultiple = true,
            defaultValue = "null",
            documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.BAZEL_CLIENT_OPTIONS,
            effectTags = [com.google.devtools.common.options.OptionEffectTag.AFFECTS_OUTPUTS],
            help = ("Specifies the set of environment variables available to the target to run."
                    + " Variables can be either specified by name, in which case the value will be"
                    + " taken from the invocation environment, by the <code>name=value</code> pair"
                    + " which sets the value independent of the invocation environment, or by"
                    + " <code>=name</code>, which unsets the variable of that name. This option can"
                    + " be used multiple times; for options given for the same variable, the latest"
                    + " wins, options for different variables accumulate. Note that the executed target"
                    + " will generally see the full environment of the host except for those variables"
                    + " that have been explicitly unset.")
        )
        abstract val runEnvironment: MutableList<EnvVar>?

        @get:com.google.devtools.common.options.Option(
            name = "run_in_cwd",
            defaultValue = "false",
            documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.BAZEL_CLIENT_OPTIONS,
            effectTags = [com.google.devtools.common.options.OptionEffectTag.AFFECTS_OUTPUTS],
            help = ("If true, runs the target in the current working directory instead of the runfile"
                    + " tree.")
        )
        abstract val runInCwd: Boolean

        @get:com.google.devtools.common.options.Option(
            name = "omit_run_args",
            defaultValue = "true",
            documentationCategory = com.google.devtools.common.options.OptionDocumentationCategory.LOGGING,
            effectTags = [com.google.devtools.common.options.OptionEffectTag.TERMINAL_OUTPUT],
            help = ("Specifies whether the arguments passed to the runnable target will be omitted from the"
                    + " output for privacy reasons. If set to true, the output will not contain the"
                    + " arguments passed to the target. If set to false, the output will contain the"
                    + " arguments passed to the target.")
        )
        abstract val runOmitRunArgs: Boolean
    }

    /** The test policy to determine the environment variables from when running tests  */
    private val testPolicy: TestPolicy

    init {
        this.testPolicy = testPolicy
    }

    public override fun editOptions(optionsParser: com.google.devtools.common.options.OptionsParser?) {}

    public override fun exec(
        env: CommandEnvironment,
        options: com.google.devtools.common.options.OptionsParsingResult
    ): BlazeCommandResult {
        val runOptions: RunOptions? = options.getOptions<RunOptions?>(RunOptions::class.java)
        // This list should look like: ["//executable:target", "arg1", "arg2"]
        val targetAndArgs: MutableList<String> = options.getResidue()

        // The user must at the least specify an executable target.
        if (targetAndArgs.isEmpty()) {
            env.getReporter()
                .post(
                    RunBuildCompleteEvent(
                        ExitCode.COMMAND_LINE_ERROR, env.getRuntime().getClock().currentTimeMillis()
                    )
                )
            return reportAndCreateFailureResult(
                env, "Must specify a target to run", Code.NO_TARGET_SPECIFIED
            )
        }
        val targetString = targetAndArgs.get(0)
        val runUnder: RunUnder? = options.getOptions<O?>(CoreOptions::class.java).getRunUnder()

        val builtTargets: BuiltTargets?
        try {
            builtTargets = runBuild(env, options, targetString, runUnder)
        } catch (e: RunCommandException) {
            env.getReporter()
                .post(
                    RunBuildCompleteEvent(
                        e.result.getDetailedExitCode().getExitCode(), e.finishTimeMillis
                    )
                )
            return e.result
        }
        val runCompleteChildrenEvents: com.google.common.collect.ImmutableList.Builder<BuildEventId?> =
            com.google.common.collect.ImmutableList.builder<BuildEventId?>()
                .add(BuildEventIdUtil.buildToolLogs())
                .add(BuildEventIdUtil.buildMetrics())
        if (runOptions!!.scriptPath == null) {
            runCompleteChildrenEvents.add(BuildEventIdUtil.execRequestId())
        }
        env.getReporter()
            .post(
                RunBuildCompleteEvent( // If the build returned non-zero exit code, an error would have already been
                    // thrown.
                    ExitCode.SUCCESS, builtTargets.stopTime, runCompleteChildrenEvents.build()
                )
            )
        val argsFromResidue: com.google.common.collect.ImmutableList<String?> =
            com.google.common.collect.ImmutableList.copyOf<String?>(targetAndArgs.subList(1, targetAndArgs.size))
        val runCommandLine: RunCommandLine
        try {
            runCommandLine =
                Companion.getCommandLineInfo(env, builtTargets, options, argsFromResidue, runOptions, testPolicy)
        } catch (e: RunCommandException) {
            return e.result
        }
        val batchMode: Boolean =
            env.getRuntime()
                .getStartupOptionsProvider()
                .getOptions(BlazeServerStartupOptions::class.java).batch
        val finalRunEnv: TreeMap<String?, String?> = TreeMap<String?, String?>(runCommandLine.getEnvironment())
        if (batchMode) {
            // In --batch, prioritize original client env-var values over those added by the c++ launcher.
            // Only necessary in --batch since the command runs as a subprocess of the java server.
            finalRunEnv.putAll(env.getClientEnv())
        }

        val execRequest: ExecRequest.Builder
        try {
            val shouldRunTarget =
                runOptions.scriptPath == null && runOptions.runBuiltTarget
            val pathsToReplace: com.google.common.collect.ImmutableList<PathToReplace?> =
                if (runOptions.portablePaths)
                    getPathsToReplace(
                        env,  /* testLogDir= */
                        builtTargets
                            .configuration
                            .getTestLogsDirectory(RepositoryName.MAIN)
                            .getExecPathString(),
                        runCommandLine.isTestTarget()
                    )
                else
                    com.google.common.collect.ImmutableList.of<PathToReplace?>()

            execRequest =
                execRequestBuilder(
                    env,
                    runCommandLine,
                    com.google.common.collect.ImmutableSortedMap.copyOf<String?, String?>(finalRunEnv),
                    builtTargets.configuration,
                    builtTargets.stopTime,
                    shouldRunTarget,
                    pathsToReplace
                )
        } catch (e: RunCommandException) {
            return e.result
        }

        if (runOptions.scriptPath != null) {
            return Companion.handleScriptPath(runOptions, execRequest, runCommandLine, env, builtTargets)
        }

        val executionOptions: ExecutionOptions? = options.getOptions<O?>(ExecutionOptions::class.java)
        val showSubcommands: ActionExecutionContext.ShowSubcommands? = executionOptions.showSubcommands

        val commandDescription: String?
        if (showSubcommands !== ActionExecutionContext.ShowSubcommands.FALSE) {
            var shExecutable: String? = null
            if (runCommandLine.requiresShExecutable()) {
                try {
                    shExecutable =
                        getShellExecutableOrThrow(env, builtTargets.configuration, "", builtTargets.stopTime)
                } catch (e: RunCommandException) {
                    return e.result
                }
            }
            val args: com.google.common.collect.ImmutableList<String?> = runCommandLine.getArgs(shExecutable)
            commandDescription =
                CommandFailureUtils.describeCommand(
                    CommandDescriptionForm.COMPLETE,
                    showSubcommands === ActionExecutionContext.ShowSubcommands.PRETTY_PRINT,
                    args,
                    finalRunEnv,
                    com.google.common.collect.ImmutableList.copyOf<String?>(runCommandLine.getEnvironmentVariablesToClear()),
                    runCommandLine.getWorkingDir().getPathString(),  /* configurationChecksum= */
                    null,  /* executionPlatformLabel= */
                    null,  /* spawnRunner= */
                    null
                )
        } else {
            commandDescription = runCommandLine.getPrettyArgs(runOptions.runOmitRunArgs)
        }

        val prefix = if (runOptions.runBuiltTarget) "Running" else "Runnable"
        val separator =
            if (showSubcommands !== ActionExecutionContext.ShowSubcommands.FALSE) ":\n" else ": "
        env.getReporter()
            .handle(
                com.google.devtools.build.lib.events.Event.info(
                    null,
                    prefix + " command line" + separator + commandDescription
                )
            )

        try {
            env.getReporter()
                .post(
                    ExecRequestEvent(
                        execRequest.build(),  /* redactedArgv= */
                        if (options
                                .getOptions<O?>(BuildEventProtocolOptions::class.java).includeResidueInRunBepEvent
                        )
                            com.google.common.collect.ImmutableList.copyOf(execRequest.getArgvList())
                        else
                            getArgvWithoutResidue(
                                env, runCommandLine, builtTargets.configuration, builtTargets.stopTime
                            )
                    )
                )
            return BlazeCommandResult.execute(execRequest.build())
        } catch (e: RunCommandException) {
            return e.result
        }
    }

    private class RunCommandException(result: BlazeCommandResult, finishTimeMillis: Long) : java.lang.Exception() {
        private val result: BlazeCommandResult
        private val finishTimeMillis: Long

        init {
            com.google.common.base.Preconditions.checkArgument(
                !result.isSuccess(),
                "Success is not exceptional: %s",
                result
            )
            this.result = result
            this.finishTimeMillis = finishTimeMillis
        }
    }

    /** Contains the targets built as part of a run-command invocation.  */
    private class BuiltTargets(
        targetToRun: ConfiguredTarget,
        targetToRunRunfilesDir: com.google.devtools.build.lib.vfs.Path?,
        targetToRunRunfilesSupport: RunfilesSupport?,
        runUnderTarget: ConfiguredTarget?,
        configuration: BuildConfigurationValue,
        convenienceSymlinks: com.google.common.collect.ImmutableMap<PathFragment?, PathFragment?>?,
        stopTime: Long
    ) {
        private val targetToRun: ConfiguredTarget
        private val targetToRunRunfilesDir: com.google.devtools.build.lib.vfs.Path?
        private val targetToRunRunfilesSupport: RunfilesSupport?
        private val runUnderTarget: ConfiguredTarget?
        private val configuration: BuildConfigurationValue
        private val convenienceSymlinks: com.google.common.collect.ImmutableMap<PathFragment?, PathFragment?>?
        private val stopTime: Long

        init {
            this.targetToRun = targetToRun
            this.runUnderTarget = runUnderTarget
            this.targetToRunRunfilesDir = targetToRunRunfilesDir
            this.targetToRunRunfilesSupport = targetToRunRunfilesSupport
            this.configuration = configuration
            this.convenienceSymlinks = convenienceSymlinks
            this.stopTime = stopTime
        }
    }

    private class RunfilesException(message: String?, detailedCode: Code?, cause: java.lang.Exception?) :
        java.lang.Exception("Error creating runfiles: " + message, cause) {
        private val detailedCode: FailureDetails.RunCommand.Code?

        init {
            this.detailedCode = detailedCode
        }

        fun createFailureDetail(): FailureDetail {
            return createFailureDetail(message, detailedCode)
        }
    }

    companion object {
        private const val NO_TARGET_MESSAGE = "No targets found to run"

        private val MULTIPLE_TESTS_MESSAGE =
            ("'run' only works with tests with one shard ('--test_sharding_strategy=disabled' is okay) "
                    + "and without --runs_per_test")

        private val ENV_VARIABLES_TO_CLEAR_UNCONDITIONALLY: com.google.common.collect.ImmutableSortedSet<String?> =
            com.google.common.collect.ImmutableSortedSet.of<String?>( // These variables are all used by runfiles libraries to locate the runfiles directory or
                // manifest and can cause incorrect behavior when set for the top-level binary run with
                // bazel run.
                "JAVA_RUNFILES",
                "RUNFILES_DIR",
                "RUNFILES_MANIFEST_FILE",
                "RUNFILES_MANIFEST_ONLY",
                "TEST_SRCDIR"
            )

        /** Returns the arguments in a [ConfiguredTarget]'s `args` attribute.  */
        private fun getBinaryArgs(targetToRun: ConfiguredTarget): com.google.common.collect.ImmutableList<String?>? {
            val provider: FilesToRunProvider? = targetToRun.getProvider(FilesToRunProvider::class.java)
            if (provider == null) {
                return com.google.common.collect.ImmutableList.of<String?>()
            }
            val runfilesSupport: RunfilesSupport? = provider.getRunfilesSupport()
            if (runfilesSupport == null) {
                return com.google.common.collect.ImmutableList.of<String?>()
            }
            return runfilesSupport.getArgs().arguments()
        }

        @Throws(RunCommandException::class)
        private fun runBuild(
            env: CommandEnvironment,
            options: com.google.devtools.common.options.OptionsParsingResult?,
            targetString: String,
            runUnder: RunUnder?
        ): BuiltTargets {
            val targetsToBuild: com.google.common.collect.ImmutableList<String?> =
                if (runUnder is LabelRunUnder)
                    com.google.common.collect.ImmutableList.of<E?>(targetString, runUnder.label().toString())
                else
                    com.google.common.collect.ImmutableList.of<String?>(targetString)
            val request: BuildRequest =
                BuildRequest.builder()
                    .setCommandName(RunCommand::class.java.getAnnotation<A?>(Command::class.java).name())
                    .setId(env.getCommandId())
                    .setOptions(options)
                    .setStartupOptions(env.getRuntime().getStartupOptionsProvider())
                    .setOutErr(env.getReporter().getOutErr())
                    .setTargets(targetsToBuild)
                    .setStartTimeMillis(env.commandStartTime)
                    .build()

            val buildResult: BuildResult =
                BuildTool(env)
                    .processRequest(
                        request,
                        { tgts: MutableCollection<Target>?, keepGoing: Boolean ->
                            Companion.validateTargets(
                                env.getReporter(), request.targets, tgts!!, runUnder, keepGoing
                            )
                        },
                        options
                    )
            if (!buildResult.getSuccess()) {
                env.getReporter()
                    .handle(com.google.devtools.build.lib.events.Event.error("Build failed. Not running target"))
                throw RunCommandException(
                    BlazeCommandResult.detailedExitCode(buildResult.getDetailedExitCode()),
                    buildResult.getStopTime()
                )
            }
            // Build succeeded - make sure outputs are available before attempting to use them.
            flushOutputs(env)

            return getBuiltTargets(buildResult, env, targetString, runUnder)
        }

        @Throws(RunCommandException::class)
        private fun getBuiltTargets(
            result: BuildResult, env: CommandEnvironment, targetString: String?, runUnder: RunUnder?
        ): BuiltTargets {
            val topLevelTargets: MutableCollection<ConfiguredTarget>? = result.getSuccessfulTargets()
            var targetToRun: ConfiguredTarget? = null
            var runUnderTarget: ConfiguredTarget? = null

            if (topLevelTargets != null) {
                // Make sure that we have exactly 1 built target (excluding --run_under) and that it is
                // executable. These checks should only fail if keepGoing is true, because we already did
                // validation before the build began in validateTargets().
                val maxTargets = if (runUnder is LabelRunUnder) 2 else 1
                if (topLevelTargets.size > maxTargets) {
                    throw RunCommandException(
                        reportAndCreateFailureResult(
                            env,
                            makeErrorMessageForNotHavingASingleTarget(
                                targetString,
                                com.google.common.collect.Iterables.transform<ConfiguredTarget?, String?>(
                                    topLevelTargets,
                                    com.google.common.base.Function { ct: ConfiguredTarget? ->
                                        ct.getLabel().toString()
                                    })
                            ),
                            Code.TOO_MANY_TARGETS_SPECIFIED
                        ),
                        result.getStopTime()
                    )
                }

                for (target in topLevelTargets) {
                    val targetValidationResult: BlazeCommandResult = fullyValidateTarget(env, target)
                    if (!targetValidationResult.isSuccess()) {
                        throw RunCommandException(targetValidationResult, result.getStopTime())
                    }
                    if (runUnder is LabelRunUnder
                        && target.getOriginalLabel().equals(runUnder.label())
                    ) {
                        if (runUnderTarget != null) {
                            throw RunCommandException(
                                reportAndCreateFailureResult(
                                    env,
                                    "Can't identify the run_under target from multiple options?",
                                    Code.RUN_UNDER_TARGET_NOT_BUILT
                                ),
                                result.getStopTime()
                            )
                        }
                        runUnderTarget = target
                    } else if (targetToRun == null) {
                        targetToRun = target
                    } else {
                        throw RunCommandException(
                            reportAndCreateFailureResult(
                                env,
                                makeErrorMessageForNotHavingASingleTarget(
                                    targetString,
                                    com.google.common.collect.Iterables.transform<ConfiguredTarget?, String?>(
                                        topLevelTargets,
                                        com.google.common.base.Function { ct: ConfiguredTarget? ->
                                            ct.getLabel().toString()
                                        })
                                ),
                                Code.TOO_MANY_TARGETS_SPECIFIED
                            ),
                            result.getStopTime()
                        )
                    }
                }
            }

            // Handle target & run_under referring to the same target.
            if (targetToRun == null && runUnderTarget != null) {
                targetToRun = runUnderTarget
            }

            if (targetToRun == null) {
                throw RunCommandException(
                    reportAndCreateFailureResult(env, NO_TARGET_MESSAGE, Code.NO_TARGET_SPECIFIED),
                    result.getStopTime()
                )
            }

            var configuration: BuildConfigurationValue? =
                env.getSkyframeExecutor()
                    .getConfiguration(env.getReporter(), targetToRun.getConfigurationKey())
            if (configuration == null) {
                // The target may be an input file, which doesn't have a configuration. In that case, we
                // choose any target configuration.
                configuration = result.getBuildConfiguration()
            }

            // When --nobuild_runfile_manifests is enabled, the output service is responsible for staging
            // runfiles.
            if (!configuration.buildRunfileManifests()
                && !env.getOutputService().stagesTopLevelRunfiles()
            ) {
                throw RunCommandException(
                    reportAndCreateFailureResult(
                        env,
                        "--nobuild_runfile_manifests is incompatible with the \"run\" command",
                        Code.RUN_PREREQ_UNMET
                    ),
                    result.getStopTime()
                )
            }

            // Ensure runfiles directories are constructed, both for the target to run
            // and the --run_under target. The path of the runfiles directory of the
            // target to run needs to be preserved, as it acts as the working directory.
            var targetToRunRunfilesDir: com.google.devtools.build.lib.vfs.Path? = null
            var targetToRunRunfilesSupport: RunfilesSupport? = null
            val runfilesTreeUpdater: RunfilesTreeUpdater = RunfilesTreeUpdater.forCommandEnvironment(env)
            for (target in topLevelTargets!!) {
                val provider: FilesToRunProvider? = target.getProvider(FilesToRunProvider::class.java)
                val runfilesSupport: RunfilesSupport? = if (provider == null) null else provider.getRunfilesSupport()

                if (runfilesSupport == null) {
                    continue
                }
                try {
                    val runfilesDir: com.google.devtools.build.lib.vfs.Path =
                        ensureRunfilesBuilt(
                            env,
                            runfilesSupport,
                            env.getSkyframeExecutor()
                                .getConfiguration(env.getReporter(), target.getConfigurationKey()),
                            runfilesTreeUpdater
                        )
                    if (target === targetToRun) {
                        targetToRunRunfilesDir = runfilesDir
                        targetToRunRunfilesSupport = runfilesSupport
                    }
                } catch (e: RunfilesException) {
                    env.getReporter().handle(com.google.devtools.build.lib.events.Event.error(e.message))
                    throw RunCommandException(
                        BlazeCommandResult.failureDetail(e.createFailureDetail()), result.getStopTime()
                    )
                } catch (e: java.lang.InterruptedException) {
                    env.getReporter().handle(com.google.devtools.build.lib.events.Event.error("Interrupted"))
                    throw RunCommandException(
                        BlazeCommandResult.failureDetail(
                            FailureDetail.newBuilder()
                                .setInterrupted(Interrupted.newBuilder().setCode(Interrupted.Code.INTERRUPTED))
                                .build()
                        ),
                        result.getStopTime()
                    )
                }
            }
            return BuiltTargets(
                targetToRun,
                targetToRunRunfilesDir,
                targetToRunRunfilesSupport,
                runUnderTarget,
                configuration,
                result.getConvenienceSymlinks(),
                result.getStopTime()
            )
        }

        @Throws(RunCommandException::class)
        private fun execRequestBuilder(
            env: CommandEnvironment,
            runCommandLine: RunCommandLine,
            runEnv: com.google.common.collect.ImmutableSortedMap<String?, String?>,
            configuration: BuildConfigurationValue?,
            stopTime: Long,
            shouldRunTarget: Boolean,
            pathsToReplace: com.google.common.collect.ImmutableList<PathToReplace?>?
        ): ExecRequest.Builder {
            val execDescription: ExecRequest.Builder =
                ExecRequest.newBuilder()
                    .setWorkingDirectory(
                        ByteString.copyFrom(
                            runCommandLine.getWorkingDir().getPathString(),
                            java.nio.charset.StandardCharsets.ISO_8859_1
                        )
                    )
                    .addAllArgv(getArgvForExecRequest(env, runCommandLine, configuration, stopTime))

            for (variable in runEnv.entries) {
                execDescription.addEnvironmentVariable(
                    EnvironmentVariable.newBuilder()
                        .setName(ByteString.copyFrom(variable.key, java.nio.charset.StandardCharsets.ISO_8859_1))
                        .setValue(ByteString.copyFrom(variable.value, java.nio.charset.StandardCharsets.ISO_8859_1))
                        .build()
                )
            }
            return execDescription
                .addAllEnvironmentVariableToClear(
                    runCommandLine.getEnvironmentVariablesToClear().stream()
                        .map<ByteString?> { s: String? ->
                            ByteString.copyFrom(
                                s,
                                java.nio.charset.StandardCharsets.ISO_8859_1
                            )
                        }
                        .collect(com.google.common.collect.ImmutableList.toImmutableList<E?>()))
                .setShouldExec(shouldRunTarget)
                .addAllPathToReplace(pathsToReplace)
        }

        private fun getPathsToReplace(
            env: CommandEnvironment, testLogDir: String, isTestTarget: Boolean
        ): com.google.common.collect.ImmutableList<PathToReplace?> {
            val pathsToReplace: com.google.common.collect.ImmutableList<PathToReplace?> =
                PathToReplaceUtils.getPathsToReplace(env)
            if (isTestTarget) {
                return com.google.common.collect.ImmutableList.builder<PathToReplace?>()
                    .addAll(pathsToReplace)
                    .add(
                        PathToReplace.newBuilder()
                            .setType(PathToReplace.Type.TEST_LOG_SUBDIR)
                            .setValue(ByteString.copyFrom(testLogDir, java.nio.charset.StandardCharsets.ISO_8859_1))
                            .build()
                    )
                    .build()
            }
            return pathsToReplace
        }

        @Throws(RunCommandException::class)
        private fun getArgvForExecRequest(
            env: CommandEnvironment,
            runCommandLine: RunCommandLine,
            configuration: BuildConfigurationValue?,
            stopTime: Long
        ): com.google.common.collect.ImmutableList<ByteString?> {
            return getArgv(env, runCommandLine,  /* includeResidue= */true, configuration, stopTime)
        }

        @Throws(RunCommandException::class)
        private fun getArgvWithoutResidue(
            env: CommandEnvironment,
            runCommandLine: RunCommandLine,
            configuration: BuildConfigurationValue?,
            stopTime: Long
        ): com.google.common.collect.ImmutableList<ByteString?> {
            return getArgv(env, runCommandLine,  /* includeResidue= */false, configuration, stopTime)
        }

        @Throws(RunCommandException::class)
        private fun getArgv(
            env: CommandEnvironment,
            runCommandLine: RunCommandLine,
            includeResidue: Boolean,
            configuration: BuildConfigurationValue?,
            stopTime: Long
        ): com.google.common.collect.ImmutableList<ByteString?> {
            var shExecutable: String? = null
            if (runCommandLine.requiresShExecutable()) {
                shExecutable = getShellExecutableOrThrow(env, configuration,  /* reason= */"", stopTime)
            }
            val args: com.google.common.collect.ImmutableList<String?> =
                if (includeResidue)
                    runCommandLine.getArgs(shExecutable)
                else
                    runCommandLine.getArgsWithoutResidue(shExecutable)
            return args.stream()
                .map<ByteString?> { s: String? -> ByteString.copyFrom(s, java.nio.charset.StandardCharsets.ISO_8859_1) }
                .collect(com.google.common.collect.ImmutableList.toImmutableList<ByteString?>())
        }

        private fun handleScriptPath(
            runOptions: RunOptions,
            execRequest: ExecRequest.Builder,
            runCommandLine: RunCommandLine,
            env: CommandEnvironment,
            builtTargets: BuiltTargets
        ): BlazeCommandResult {
            val shExecutable: String?
            try {
                shExecutable =
                    getShellExecutableOrThrow(
                        env, builtTargets.configuration, "with \"--script_path\"", builtTargets.stopTime
                    )
            } catch (e: RunCommandException) {
                return e.result
            }

            val scriptContents: String = runCommandLine.getScriptForm(shExecutable)

            if (runOptions.emitScriptPathInExecRequest) {
                execRequest.setScriptPath(
                    CommandProtos.ScriptPath.newBuilder()
                        .setScriptPath(
                            ByteString.copyFrom(
                                runOptions.scriptPath.toString(),
                                java.nio.charset.StandardCharsets.ISO_8859_1
                            )
                        )
                        .setScriptContents(
                            ByteString.copyFrom(
                                scriptContents,
                                java.nio.charset.StandardCharsets.ISO_8859_1
                            )
                        )
                        .build()
                )
                return BlazeCommandResult.execute(execRequest.build())
            } else {
                try {
                    writeScript(env, runOptions.scriptPath, scriptContents)
                } catch (e: IOException) {
                    val message = "Error writing run script: " + e.message
                    return reportAndCreateFailureResult(env, message, Code.SCRIPT_WRITE_FAILURE)
                }
                return BlazeCommandResult.success()
            }
        }

        @Throws(RunCommandException::class)
        private fun getCommandLineInfo(
            env: CommandEnvironment,
            builtTargets: BuiltTargets,
            options: com.google.devtools.common.options.OptionsParsingResult,
            argsFromResidue: com.google.common.collect.ImmutableList<String?>?,
            runOptions: RunOptions,
            testPolicy: TestPolicy
        ): RunCommandLine {
            if (builtTargets.targetToRun.getProvider(TestProvider::class.java) != null) {
                return getTestCommandLine(env, builtTargets, options, argsFromResidue, testPolicy)
            }

            var actionEnvironment: ActionEnvironment = ActionEnvironment.EMPTY
            if (builtTargets.targetToRunRunfilesSupport != null) {
                actionEnvironment = builtTargets.targetToRunRunfilesSupport.getActionEnvironment()
            }
            // The final run environment is a combination of the environment constructed here and the
            // unrestricted client environment. This means that there is a difference between a variable
            // that isn't included in runEnvironment (which will have its value inherited from the
            // client environment) and a variable that is explicitly removed (which will be unset in the
            // run environment). We thus track the environment variables to clear separately.
            val runEnvironment: TreeMap<String?, String?> = makeMutableRunEnvironment(env)
            val envVariablesToClear: HashSet<String?> = HashSet<String?>()
            val clientEnv: com.google.common.collect.ImmutableMap<String?, String?> = env.getClientEnv()
            // Process --run_env flags first
            for (envVar in runOptions.runEnvironment!!) {
                when (envVar) {
                    -> {
                        runEnvironment.put(name, value)
                        envVariablesToClear.remove(name)
                    }

                    -> {
                        // If a value is missing, inherit from client environment if present, otherwise leave
                        // unset. In the latter case, explicitly remove since the same name might be given
                        // multiple times.
                        if (clientEnv.containsKey(name)) {
                            runEnvironment.put(name, clientEnv.get(name))
                        } else {
                            runEnvironment.remove(name)
                        }
                        envVariablesToClear.remove(name)
                    }

                    -> {
                        runEnvironment.remove(name)
                        envVariablesToClear.add(name)
                    }
                }
            }
            // Then let the target's environment override --run_env flags
            actionEnvironment.resolve(runEnvironment, clientEnv)

            return constructCommandLine(
                env,
                builtTargets,
                com.google.common.collect.ImmutableSortedMap.copyOf<String?, String?>(runEnvironment),
                com.google.common.collect.ImmutableSortedSet.copyOf<String?>(
                    com.google.common.collect.Iterables.concat<String?>(
                        envVariablesToClear,
                        ENV_VARIABLES_TO_CLEAR_UNCONDITIONALLY
                    )
                ),
                getBinaryArgs(builtTargets.targetToRun),
                argsFromResidue,
                runOptions
            )
        }

        /**
         * Returns the command line for the test, making a best effort to mimic the environment had we run
         * `test //target`.
         */
        @Throws(RunCommandException::class)
        private fun getTestCommandLine(
            env: CommandEnvironment,
            builtTargets: BuiltTargets,
            options: com.google.devtools.common.options.OptionsParsingResult,
            argsFromResidue: com.google.common.collect.ImmutableList<String?>?,
            testPolicy: TestPolicy
        ): RunCommandLine {
            val statusArtifacts: com.google.common.collect.ImmutableList<Artifact.DerivedArtifact?> =
                TestProvider.getTestStatusArtifacts(builtTargets.targetToRun)
            if (statusArtifacts.size() != 1) {
                throw RunCommandException(
                    reportAndCreateFailureResult(
                        env, MULTIPLE_TESTS_MESSAGE, Code.TOO_MANY_TEST_SHARDS_OR_RUNS
                    ),
                    builtTargets.stopTime
                )
            }

            val testAction: TestRunnerAction =
                env.getSkyframeExecutor()
                    .getActionGraph(env.getReporter())
                    .getGeneratingAction(com.google.common.collect.Iterables.getOnlyElement<T?>(statusArtifacts)) as TestRunnerAction
            val settings: TestTargetExecutionSettings = testAction.getExecutionSettings()
            // ensureRunfilesBuilt does build the runfiles, but an extra consistency check won't hurt.
            com.google.common.base.Preconditions.checkState(
                settings.getRunfilesSymlinksCreated()
                        === options.getOptions<O?>(CoreOptions::class.java).getBuildRunfileLinks()
            )

            val execRoot: com.google.devtools.build.lib.vfs.Path = env.getExecRoot()
            var runfilesDir: com.google.devtools.build.lib.vfs.Path? = settings.getRunfilesDir()
            if (runfilesDir == null) {
                runfilesDir = builtTargets.targetToRunRunfilesDir.getParentDirectory()
            }

            val executionOptions: ExecutionOptions? = options.getOptions<O?>(ExecutionOptions::class.java)
            val tmpDirRoot: com.google.devtools.build.lib.vfs.Path =
                TestStrategy.getTmpRoot(env.getWorkspace(), execRoot, executionOptions)
            val maybeRelativeTmpDir: PathFragment =
                if (tmpDirRoot.startsWith(execRoot)) tmpDirRoot.relativeTo(execRoot) else tmpDirRoot.asFragment()
            val runEnvironment: TreeMap<String?, String?> = makeMutableRunEnvironment(env)
            runEnvironment.putAll(
                testPolicy.computeTestEnvironment(
                    testAction,
                    env.getClientEnv(),
                    runfilesDir.relativeTo(execRoot),
                    maybeRelativeTmpDir.getRelative(TestStrategy.getTmpDirName(testAction))
                )
            )

            try {
                testAction.prepare(
                    env.getExecRoot(),
                    ArtifactPathResolver.IDENTITY,  /* bulkDeleter= */
                    null,  /* cleanupArchivedArtifacts= */
                    false
                )
            } catch (e: IOException) {
                throw RunCommandException(
                    reportAndCreateFailureResult(
                        env,
                        "Error while setting up test: " + e.getMessage(),
                        Code.TEST_ENVIRONMENT_SETUP_FAILURE
                    ),
                    builtTargets.stopTime
                )
            } catch (e: java.lang.InterruptedException) {
                throw RunCommandException(
                    reportAndCreateFailureResult(
                        env,
                        "Error while setting up test: " + e.getMessage(),
                        Code.TEST_ENVIRONMENT_SETUP_INTERRUPTED
                    ),
                    builtTargets.stopTime
                )
            }

            val testArgs: com.google.common.collect.ImmutableList<String?>
            try {
                testArgs = TestStrategy.getArgs(testAction)
            } catch (e: ExecException) {
                throw RunCommandException(
                    reportAndCreateFailureResult(
                        env,
                        com.google.common.base.Strings.nullToEmpty(e.getMessage()),
                        Code.COMMAND_LINE_EXPANSION_FAILURE
                    ),
                    builtTargets.stopTime
                )
            } catch (e: java.lang.InterruptedException) {
                val message = "run: command line expansion interrupted"
                env.getReporter().handle(com.google.devtools.build.lib.events.Event.error(message))
                throw RunCommandException(
                    BlazeCommandResult.detailedExitCode(InterruptedFailureDetails.detailedExitCode(message)),
                    builtTargets.stopTime
                )
            }

            return com.google.devtools.build.lib.runtime.commands.RunCommandLine.Builder(
                com.google.common.collect.ImmutableSortedMap.copyOf<String?, String?>(runEnvironment),
                ENV_VARIABLES_TO_CLEAR_UNCONDITIONALLY,  /* workingDir= */
                execRoot,  /* isTestTarget= */
                true
            )
                .addArgs(testArgs)
                .addArgsFromResidue(argsFromResidue)
                .build()
        }

        /**
         * Returns a new [TreeMap] with environment variables common to all run invocations. The
         * return value is a new, mutable instance - this is necessary since we want to maintain order and
         * overwrite existing keys, something which isn't supported by current immutable implementations.
         */
        // Sorted, mutable map is desired - see javadoc.
        private fun makeMutableRunEnvironment(env: CommandEnvironment): TreeMap<String?, String?> {
            val result: TreeMap<String?, String?> = TreeMap<String?, String?>()
            result.put("BUILD_WORKSPACE_DIRECTORY", env.getWorkspace().getPathString())
            result.put("BUILD_WORKING_DIRECTORY", env.getWorkingDirectory().getPathString())
            result.put("BUILD_EXECROOT", env.getExecRoot().getPathString())
            result.put("BUILD_ID", env.getCommandId().toString())
            return result
        }

        private fun constructCommandLine(
            env: CommandEnvironment,
            builtTargets: BuiltTargets,
            runEnvironment: com.google.common.collect.ImmutableSortedMap<String?, String?>?,
            envVariablesToClear: com.google.common.collect.ImmutableSortedSet<String?>?,
            argsFromBinary: com.google.common.collect.ImmutableList<String?>,
            argsFromResidue: com.google.common.collect.ImmutableList<String?>?,
            runOptions: RunOptions
        ): RunCommandLine {
            val requestOptions: BuildRequestOptions = env.getOptions().getOptions(BuildRequestOptions::class.java)
            val prettyPrinter: PathPrettyPrinter =
                PathPrettyPrinter(
                    env.getRelativeWorkingDirectory(),
                    requestOptions.getSymlinkPrefix(env.getRuntime().productName),
                    builtTargets.convenienceSymlinks
                )
            val runCommandLine: com.google.devtools.build.lib.runtime.commands.RunCommandLine.Builder =
                com.google.devtools.build.lib.runtime.commands.RunCommandLine.Builder(
                    runEnvironment,
                    envVariablesToClear,  /* workingDir= */
                    if (!runOptions.runInCwd
                        && builtTargets.targetToRunRunfilesDir != null
                    )
                        builtTargets.targetToRunRunfilesDir
                    else
                        env.getWorkingDirectory(),  /* isTestTarget= */
                    false
                )

            val runUnder: RunUnder? = env.getOptions().getOptions(CoreOptions::class.java).getRunUnder()
            // Insert the command prefix specified by the "--run_under=<command-prefix>" option
            // at the start of the command line.
            if (runUnder != null) {
                if (builtTargets.runUnderTarget != null) {
                    // --run_under specifies a target. Get the corresponding executable, this will be an
                    // absolute path because the run_under target is only in the runfiles of test targets
                    val runUnderPath: com.google.devtools.build.lib.vfs.Path =
                        builtTargets
                            .runUnderTarget
                            .getProvider(FilesToRunProvider::class.java)
                            .getExecutable()
                            .getPath()
                    runCommandLine.setRunUnderTarget(runUnderPath, runUnder.options(), prettyPrinter)
                } else {
                    runCommandLine.setRunUnderPrefix(runUnder.value())
                }
            }

            val executable: Artifact =
                builtTargets.targetToRun.getProvider(FilesToRunProvider::class.java).getExecutable()
            return runCommandLine
                .addArg(executable.getPath(), prettyPrinter)
                .addArgs(argsFromBinary)
                .addArgsFromResidue(argsFromResidue)
                .build()
        }

        @Throws(RunCommandException::class)
        private fun getShellExecutableOrThrow(
            env: CommandEnvironment, configuration: BuildConfigurationValue?, reason: String?, stopTime: Long
        ): String? {
            val shExecutable: PathFragment = ShToolchain.getPathForHost(configuration)
            if (shExecutable.isEmpty()) {
                throw RunCommandException(
                    reportAndCreateFailureResult(
                        env,
                        ("the \"run\" command needs a shell"
                                + reason
                                + "; use the --shell_executable=<path> "
                                + "flag to specify the shell's path, e.g. --shell_executable=/bin/bash"),
                        Code.NO_SHELL_SPECIFIED
                    ),
                    stopTime
                )
            }
            return shExecutable.getPathString()
        }

        /**
         * When using an output service (e.g. Build without the Bytes), flushes the output tree, waiting
         * for downloads to complete. This is necessary since outputs might still be downloading in the
         * background.
         */
        private fun flushOutputs(env: CommandEnvironment) {
            if (env.getOutputService() != null) {
                try {
                    env.getOutputService().flushOutputTree()
                } catch (ignored: java.lang.InterruptedException) {
                    java.lang.Thread.currentThread().interrupt()
                }
            }
        }

        private fun reportAndCreateFailureResult(
            env: CommandEnvironment, message: String?, detailedCode: Code?
        ): BlazeCommandResult {
            env.getReporter().handle(com.google.devtools.build.lib.events.Event.error(message))
            return BlazeCommandResult.failureDetail(createFailureDetail(message, detailedCode))
        }

        /**
         * Ensures that runfiles are built for the specified target. If they already are, does nothing,
         * otherwise builds them.
         */
        @Throws(RunfilesException::class, java.lang.InterruptedException::class)
        private fun ensureRunfilesBuilt(
            env: CommandEnvironment,
            runfilesSupport: RunfilesSupport,
            configuration: BuildConfigurationValue,
            runfilesTreeUpdater: RunfilesTreeUpdater
        ): com.google.devtools.build.lib.vfs.Path {
            val runfilesDir: PathFragment? = runfilesSupport.getRunfilesTree().getExecPath()
            var workingDir: com.google.devtools.build.lib.vfs.Path = env.getExecRoot().getRelative(runfilesDir)
            // On Windows, runfiles tree is disabled.
            // Workspace name directory doesn't exist, so don't add it.
            if (configuration.runfilesEnabled()) {
                workingDir = workingDir.getRelative(runfilesSupport.getRunfiles().getPrefix())
            }

            // Return early if runfiles staging is managed by the output service.
            if (env.getOutputService().stagesTopLevelRunfiles()) {
                return workingDir
            }

            // Always create runfiles directory and the workspace-named directory underneath, even if we
            // run with --enable_runfiles=no (which is the default on Windows as of 2020-01-24).
            // If the binary we run is in fact a test, it will expect to be able to chdir into the runfiles
            // directory. See https://github.com/bazelbuild/bazel/issues/10621
            try {
                runfilesSupport
                    .getRunfilesDirectory()
                    .getRelative(runfilesSupport.getRunfilesTree().getWorkspaceName())
                    .createDirectoryAndParents()
            } catch (e: IOException) {
                throw RunfilesException(
                    "Failed to create runfiles directories: " + e.getMessage(),
                    Code.RUNFILES_DIRECTORIES_CREATION_FAILURE,
                    e
                )
            }

            try {
                runfilesTreeUpdater.updateRunfiles(com.google.common.collect.ImmutableList.of<E?>(runfilesSupport.getRunfilesTree()))
            } catch (e: ExecException) {
                throw RunfilesException(
                    "Failed to create runfiles symlinks: " + e.getMessage(),
                    Code.RUNFILES_SYMLINKS_CREATION_FAILURE,
                    e
                )
            } catch (e: IOException) {
                throw RunfilesException(
                    "Failed to create runfiles symlinks: " + e.getMessage(),
                    Code.RUNFILES_SYMLINKS_CREATION_FAILURE,
                    e
                )
            }
            return workingDir
        }

        @Throws(IOException::class)
        private fun writeScript(
            env: CommandEnvironment, scriptPathFrag: PathFragment?, scriptContent: String?
        ) {
            val scriptPath: com.google.devtools.build.lib.vfs.Path =
                env.getWorkingDirectory().getRelative(scriptPathFrag)
            com.google.devtools.build.lib.vfs.FileSystemUtils.writeContent(
                scriptPath,
                java.nio.charset.StandardCharsets.ISO_8859_1,
                scriptContent
            )
            scriptPath.setExecutable(true)
        }

        // Make sure we are building exactly 1 binary target.
        // If keepGoing, we'll build all the targets even if they are non-binary.
        @Throws(LoadingFailedException::class)
        private fun validateTargets(
            reporter: com.google.devtools.build.lib.events.Reporter,
            targetPatternStrings: MutableList<String?>,
            targets: MutableCollection<Target>,
            runUnder: RunUnder?,
            keepGoing: Boolean
        ) {
            var targetToRun: Target? = null
            var runUnderTarget: Target? = null

            var singleTargetWarningWasOutput = false
            val maxTargets = if (runUnder is LabelRunUnder) 2 else 1
            if (targets.size() > maxTargets) {
                warningOrException(
                    reporter,
                    makeErrorMessageForNotHavingASingleTarget(
                        targetPatternStrings.get(0),
                        com.google.common.collect.Iterables.transform<Target?, String?>(
                            targets,
                            com.google.common.base.Function { t: Target? -> t.getLabel().toString() })
                    ),
                    keepGoing,
                    Code.TOO_MANY_TARGETS_SPECIFIED
                )
                singleTargetWarningWasOutput = true
            }
            for (target in targets) {
                if (!isExecutable(target)) {
                    warningOrException(
                        reporter, notExecutableError(target), keepGoing, Code.TARGET_NOT_EXECUTABLE
                    )
                }

                if (runUnder is LabelRunUnder
                    && target.getLabel().equals(runUnder.label())
                ) {
                    // It's impossible to have two targets with the same label.
                    com.google.common.base.Preconditions.checkState(runUnderTarget == null)
                    runUnderTarget = target
                } else if (targetToRun == null) {
                    targetToRun = target
                } else {
                    if (!singleTargetWarningWasOutput) {
                        warningOrException(
                            reporter,
                            makeErrorMessageForNotHavingASingleTarget(
                                targetPatternStrings.get(0),
                                com.google.common.collect.Iterables.transform<Target?, String?>(
                                    targets,
                                    com.google.common.base.Function { t: Target? -> t.getLabel().toString() })
                            ),
                            keepGoing,
                            Code.TOO_MANY_TARGETS_SPECIFIED
                        )
                    }
                    return
                }
            }
            // Handle target & run_under referring to the same target.
            if ((targetToRun == null) && (runUnderTarget != null)) {
                targetToRun = runUnderTarget
            }
            if (targetToRun == null) {
                warningOrException(reporter, NO_TARGET_MESSAGE, keepGoing, Code.NO_TARGET_SPECIFIED)
            }
        }

        /**
         * If keepGoing, print a warning and return the given collection. Otherwise, throw
         * InvalidTargetException.
         */
        @Throws(LoadingFailedException::class)
        private fun warningOrException(
            reporter: com.google.devtools.build.lib.events.Reporter,
            message: String?,
            keepGoing: Boolean,
            detailedCode: Code?
        ) {
            if (keepGoing) {
                reporter.handle(com.google.devtools.build.lib.events.Event.warn(message + ". Will continue anyway"))
            } else {
                throw LoadingFailedException(
                    message, DetailedExitCode.of(createFailureDetail(message, detailedCode))
                )
            }
        }

        private fun notExecutableError(target: Target): String {
            return "Cannot run target " + target.getLabel() + ": Not executable"
        }

        /**
         * Performs all available validation checks on an individual target.
         * 
         * @param configuredTarget ConfiguredTarget to validate
         * @return BlazeCommandResult.exitCode(ExitCode.SUCCESS) if all checks succeeded, otherwise a
         * result describing the failure.
         * @throws IllegalStateException if unable to find a target from the package manager.
         */
        private fun fullyValidateTarget(
            env: CommandEnvironment, configuredTarget: ConfiguredTarget
        ): BlazeCommandResult {
            val target: Target
            try {
                target = env.getPackageManager().getTarget(env.getReporter(), configuredTarget.getLabel())
            } catch (e: java.lang.InterruptedException) {
                val message = "run command interrupted"
                env.getReporter().handle(com.google.devtools.build.lib.events.Event.error(message))
                return BlazeCommandResult.detailedExitCode(
                    InterruptedFailureDetails.detailedExitCode(message)
                )
            } catch (e: NoSuchTargetException) {
                env.getReporter()
                    .handle(com.google.devtools.build.lib.events.Event.error("Failed to find a target to validate. " + e))
                throw java.lang.IllegalStateException("Failed to find a target to validate", e)
            } catch (e: NoSuchPackageException) {
                env.getReporter()
                    .handle(com.google.devtools.build.lib.events.Event.error("Failed to find a target to validate. " + e))
                throw java.lang.IllegalStateException("Failed to find a target to validate", e)
            }

            if (!isExecutable(target)) {
                return reportAndCreateFailureResult(
                    env, notExecutableError(target), Code.TARGET_NOT_EXECUTABLE
                )
            }

            val executable: Artifact? =
                com.google.common.base.Preconditions.checkNotNull(
                    configuredTarget.getProvider(FilesToRunProvider::class.java), configuredTarget
                )
                    .getExecutable()
            if (executable == null) {
                return reportAndCreateFailureResult(
                    env, notExecutableError(target), Code.TARGET_NOT_EXECUTABLE
                )
            }

            val executablePath: com.google.devtools.build.lib.vfs.Path = executable.getPath()
            try {
                if (!executablePath.exists() || !executablePath.isExecutable()) {
                    return reportAndCreateFailureResult(
                        env,
                        "Non-existent or non-executable " + executablePath,
                        Code.TARGET_BUILT_BUT_PATH_NOT_EXECUTABLE
                    )
                }
            } catch (e: IOException) {
                return reportAndCreateFailureResult(
                    env,
                    "Error checking " + executablePath.getPathString() + ": " + e.getMessage(),
                    Code.TARGET_BUILT_BUT_PATH_VALIDATION_FAILED
                )
            }

            return BlazeCommandResult.success()
        }

        /**
         * Return true iff it is possible that `target` is a rule that has an executable file. This
         * *_test rules, *_binary rules, aliases, generated outputs, and inputs.
         * 
         * 
         * Determining definitively whether a rule produces an executable can only be done after
         * analysis. This is only an early check to quickly catch most mistakes.
         */
        private fun isExecutable(target: Target?): Boolean {
            return isPlainFile(target)
                    || isExecutableNonTestRule(target)
                    || TargetUtils.isTestRule(target)
                    || AliasProvider.mayBeAlias(target)
        }

        /**
         * Return true iff `target` is a rule that generates an executable file and is user-executed
         * code.
         */
        private fun isExecutableNonTestRule(target: Target?): Boolean {
            if (target !is Rule) {
                return false
            }
            return target.isExecutable()
        }

        private fun isPlainFile(target: Target?): Boolean {
            return (target is OutputFile) || (target is InputFile)
        }

        private fun makeErrorMessageForNotHavingASingleTarget(
            targetPatternString: String?, expandedTargetNames: Iterable<String?>
        ): String? {
            val maxNumExpandedTargetsToIncludeInErrorMessage = 5
            val truncateTargetNameList = com.google.common.collect.Iterables.size(expandedTargetNames) > 5
            val targetNamesToIncludeInErrorMessage =
                if (truncateTargetNameList)
                    com.google.common.collect.Iterables.limit<String?>(
                        expandedTargetNames,
                        maxNumExpandedTargetsToIncludeInErrorMessage
                    )
                else
                    expandedTargetNames
            return java.lang.String.format(
                "Only a single target can be run. Your target pattern %s expanded to the targets %s%s",
                targetPatternString,
                com.google.common.base.Joiner.on(", ").join(
                    com.google.common.collect.ImmutableSortedSet.copyOf<String?>(targetNamesToIncludeInErrorMessage)
                ),
                if (truncateTargetNameList) "[TRUNCATED]" else ""
            )
        }

        private fun createFailureDetail(message: String?, detailedCode: Code?): FailureDetail {
            return FailureDetail.newBuilder()
                .setMessage(message)
                .setRunCommand(FailureDetails.RunCommand.newBuilder().setCode(detailedCode))
                .build()
        }
    }
}
