// Copyright 2015 The Bazel Authors. All rights reserved.
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
package com.google.devtools.build.lib.runtime.mobileinstall

import com.google.common.base.Strings
import com.google.common.collect.ImmutableList
import com.google.common.collect.Iterables
import com.google.devtools.build.lib.analysis.OutputGroupInfo.INTERNAL_SUFFIX
import com.google.devtools.build.lib.events.Event
import com.google.devtools.build.lib.rules.android.WriteAdbArgsAction
import com.google.devtools.build.lib.shell.Command
import com.google.devtools.build.lib.shell.CommandException
import com.google.devtools.build.lib.vfs.Path
import com.google.devtools.common.options.*
import java.util.function.Function

/** Implementation of the 'mobile-install' command.  */
@Command(
    name = "mobile-install",
    buildPhase = EXECUTES,
    options = [MobileInstallCommand.Options::class, WriteAdbArgsAction.Options::class],
    inheritsOptionsFrom = [BuildCommand::class],
    shortDescription = "Installs targets to mobile devices.",
    completion = "label",
    allowResidue = true,
    help = "resource:mobile-install.txt"
)
class MobileInstallCommand : BlazeCommand {
    /** An enumeration of all the modes that mobile-install supports.  */
    enum class Mode {
        CLASSIC,
        CLASSIC_INTERNAL_TEST_DO_NOT_USE,
        SKYLARK
    }

    /**
     * Converter for the --mode option.
     */
    class ModeConverter : EnumConverter<Mode?>(Mode::class.java, "mode")

    /** Command line options for the 'mobile-install' command.  */
    @OptionsClass
    abstract class Options : OptionsBase() {
        @get:Option(
            name = "split_apks",
            defaultValue = "false",
            documentationCategory = OptionDocumentationCategory.OUTPUT_SELECTION,
            effectTags = [OptionEffectTag.LOADING_AND_ANALYSIS, OptionEffectTag.AFFECTS_OUTPUTS],
            help = ("Whether to use split apks to install and update the "
                    + "application on the device. Works only with devices with "
                    + "Marshmallow or later")
        )
        abstract val splitApks: Boolean

        @get:Option(
            name = "incremental",
            defaultValue = "false",
            documentationCategory = OptionDocumentationCategory.OUTPUT_SELECTION,
            effectTags = [OptionEffectTag.LOADING_AND_ANALYSIS],
            help = ("Whether to do an incremental install. If true, try to avoid unnecessary additional"
                    + " work by reading the state of the device the code is to be installed on and"
                    + " using that information to avoid unnecessary work. If false (the default),"
                    + " always do a full install.")
        )
        abstract val incremental: Boolean

        @get:Option(
            name = "mode",
            defaultValue = "skylark",
            converter = ModeConverter::class,
            documentationCategory = OptionDocumentationCategory.EXECUTION_STRATEGY,
            effectTags = [OptionEffectTag.LOADING_AND_ANALYSIS, OptionEffectTag.EXECUTION],
            metadataTags = [OptionMetadataTag.INCOMPATIBLE_CHANGE, OptionMetadataTag.DEPRECATED],
            help = "Deprecated no-effect flag. Only skylark mode is still supported."
        )
        @get:Deprecated("")
        abstract val mode: Mode?

        @get:Option(
            name = "mobile_install_aspect",
            defaultValue = "@rules_android//mobile_install:mi.bzl",
            documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
            effectTags = [OptionEffectTag.LOADING_AND_ANALYSIS, OptionEffectTag.CHANGES_INPUTS],
            help = "The aspect to use for mobile-install."
        )
        abstract val mobileInstallAspect: String?

        @get:Option(
            name = "mobile_install_supported_rules",
            defaultValue = "android_binary",
            converter = Converters.CommaSeparatedOptionListConverter::class,
            documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
            effectTags = [OptionEffectTag.LOADING_AND_ANALYSIS],
            help = "The supported rules for mobile-install."
        )
        abstract val mobileInstallSupportedRules: MutableList<String?>?

        @get:Option(
            name = "run_in_client",
            defaultValue = "false",
            documentationCategory = OptionDocumentationCategory.UNDOCUMENTED,
            effectTags = [OptionEffectTag.BAZEL_INTERNAL_CONFIGURATION],
            help = ("If true, the mobile-install deployer command will be sent to the bazel client for "
                    + "execution. Useful for configurations where the bazel client is on a different "
                    + "machine than the bazel server.")
        )
        abstract val runInClient: Boolean
    }

    public override fun exec(env: CommandEnvironment, options: OptionsParsingResult): BlazeCommandResult {
        // This list should look like: ["//executable:target", "arg1", "arg2"]
        val targetAndArgs = options.getResidue()

        // The user must at least specify an executable target.
        if (targetAndArgs.isEmpty()) {
            val message = "Must specify a target to run"
            env.getReporter().handle(Event.error(message))
            return BlazeCommandResult.failureDetail(
                createFailureResult(message, Code.NO_TARGET_SPECIFIED)
            )
        }

        val targets: MutableList<String?> = ImmutableList.of<String?>(targetAndArgs.get(0))
        val runTargetArgs = targetAndArgs.subList(1, targetAndArgs.size())

        val outErr: OutErr? = env.getReporter().getOutErr()

        val request: BuildRequest? =
            BuildRequest.builder()
                .setCommandName(this.getClass().getAnnotation<A?>(Command::class.java).name())
                .setId(env.getCommandId())
                .setOptions(options)
                .setStartupOptions(env.getRuntime().getStartupOptionsProvider())
                .setOutErr(outErr)
                .setTargets(targets)
                .setStartTimeMillis(env.commandStartTime)
                .build()

        val deployerRequestRef: AtomicReference<ExecRequest?> = AtomicReference<ExecRequest?>()
        val result: BuildResult =
            BuildTool(env)
                .processRequest(
                    request,  /* validator= */
                    null,
                    { successfulTargets ->
                        doMobileInstall(
                            env, options, runTargetArgs, successfulTargets, deployerRequestRef
                        )
                    },
                    options,  /* targetsForProjectResolution= */
                    null
                )
        if (!result.getSuccess()) {
            env.getReporter().handle(Event.error("Build failed. Not running mobile-install on target."))
            return BlazeCommandResult.detailedExitCode(result.getDetailedExitCode())
        }

        val failureDetail: FailureDetail? = result.getPostBuildCallBackFailureDetail()
        if (failureDetail == null) {
            return if (deployerRequestRef.get() == null)
                BlazeCommandResult.success()
            else
                BlazeCommandResult.execute(deployerRequestRef.get())
        }
        return BlazeCommandResult.failureDetail(failureDetail)
    }

    @Throws(InterruptedException::class)  // Returns null in case of success.
    private fun doMobileInstall(
        env: CommandEnvironment,
        options: OptionsParsingResult,
        runTargetArgs: MutableList<String?>,
        successfulTargets: MutableCollection<ConfiguredTarget?>?,
        deployerRequestRef: AtomicReference<ExecRequest?>
    ): FailureDetail? {
        if (successfulTargets == null) {
            env.getReporter().handle(Event.warn(NO_TARGET_MESSAGE))
            return null
        }
        if (successfulTargets.size() != 1) {
            env.getReporter().handle(Event.error(SINGLE_TARGET_MESSAGE))
            return createFailureResult(SINGLE_TARGET_MESSAGE, Code.MULTIPLE_TARGETS_SPECIFIED)
        }
        val targetToRun: ConfiguredTarget? = Iterables.getOnlyElement<ConfiguredTarget?>(successfulTargets)
        val mobileInstallOptions = options.getOptions<Options?>(Options::class.java)
        val adbOptions = options.getOptions<WriteAdbArgsAction.Options?>(WriteAdbArgsAction.Options::class.java)

        if (!mobileInstallOptions!!.mobileInstallSupportedRules!!.isEmpty()) {
            val message: String? =
                Companion.errorMessageIfNotSupported(
                    targetToRun, mobileInstallOptions.mobileInstallSupportedRules!!
                )
            if (message != null) {
                env.getReporter().handle(Event.error(message))
                return createFailureResult(message, Code.TARGET_TYPE_INVALID)
            }
        }

        val cmdLine = ImmutableList.builder<String?>()
        // TODO(bazel-team): Get the executable path from the filesToRun provider from the aspect.
        val configuration: BuildConfigurationValue =
            env.getSkyframeExecutor()
                .getConfiguration(env.getReporter(), targetToRun.getConfigurationKey())
        cmdLine.add(
            (configuration.getBinFragment(targetToRun.getLabel().getRepository()).getPathString()
                    + "/"
                    + targetToRun.getLabel().toPathFragment().getPathString()
                    + "_mi/launcher")
        )
        cmdLine.addAll(runTargetArgs)

        cmdLine.add("--build_id=" + env.getCommandId())

        // Collect relevant common command options.
        val commonCommandOptions: CommonCommandOptions? =
            options.getOptions<CommonCommandOptions?>(CommonCommandOptions::class.java)
        if (!commonCommandOptions.getToolTag().isEmpty()) {
            cmdLine.add("--tool_tag=" + commonCommandOptions.getToolTag())
        }

        // Collect relevant adb options.
        cmdLine.add("--start=" + adbOptions!!.start)
        if (!adbOptions.adb.isEmpty()) {
            cmdLine.add("--adb=" + adbOptions.adb)
        }
        for (adbArg in adbOptions.adbArgs!!) {
            if (!adbArg.isEmpty()) {
                cmdLine.add("--adb_arg=" + adbArg)
            }
        }
        if (!adbOptions.device.isEmpty()) {
            cmdLine.add("--device=" + adbOptions.device)
        }

        // Collect relevant test options.
        val testOptions: TestOptions? = options.getOptions<O?>(TestOptions::class.java)
        // Default value of testFilter is null.
        if (!Strings.isNullOrEmpty(testOptions.testFilter)) {
            cmdLine.add("--test_filter=" + testOptions.testFilter)
        }
        for (arg in testOptions.testArguments) {
            if (!arg.isEmpty()) {
                cmdLine.add("--test_arg=" + arg)
            }
        }

        val workingDir: Path =
            env.getDirectories().getOutputPath(env.getWorkspaceName()).getParentDirectory()

        if (mobileInstallOptions.runInClient) {
            deployerRequestRef.set(createExecRequest(env, workingDir, cmdLine.build()))
            return null
        } else {
            return executeAsChild(env, workingDir, cmdLine.build())
        }
    }

    public override fun editOptions(optionsParser: OptionsParser) {
        val options = optionsParser.getOptions<Options?>(Options::class.java)
        try {
            optionsParser.parse(
                OptionPriority.PriorityCategory.COMMAND_LINE,
                "Options required by the Starlark implementation of mobile-install command",
                ImmutableList.of<String?>(
                    "--aspects=" + options!!.mobileInstallAspect + "%MIASPECT",
                    "--output_groups=mobile_install" + INTERNAL_SUFFIX,
                    "--output_groups=mobile_install_launcher" + INTERNAL_SUFFIX
                )
            )
        } catch (e: OptionsParsingException) {
            throw IllegalStateException(e)
        }
    }

    companion object {
        private const val SINGLE_TARGET_MESSAGE =
            "Can only run a single target. Do not use wildcards that match more than one target"
        private const val NO_TARGET_MESSAGE = "No targets found to run"

        /** Executes the mobile-install deployer as a child process on this machine.  */
        @Throws(InterruptedException::class)
        private fun executeAsChild(
            env: CommandEnvironment, workingDir: Path?, cmdLine: ImmutableList<String?>?
        ): FailureDetail? {
            val command: Command =
                CommandBuilder(env.getClientEnv())
                    .addArgs(cmdLine)
                    .setEnv(env.getClientEnv())
                    .setWorkingDir(workingDir)
                    .build()

            try {
                GoogleAutoProfilerUtils.profiledAndLogged("mobile install", ProfilerTask.INFO).use { p ->
                    // Restore a raw EventHandler if it is registered. This allows for blaze run to produce the
                    // actual output of the command being run even if --color=no is specified.
                    env.getReporter().switchToAnsiAllowingHandler()

                    val outErr: OutErr = env.getReporter().getOutErr()
                    // The command API is a little strange in that the following statement will return normally
                    // only if the program exits with exit code 0. If it ends with any other code, we have to
                    // catch BadExitStatusException.
                    command
                        .execute(outErr.getOutputStream(), outErr.getErrorStream())
                        .terminationStatus
                        .getExitCode()
                    return null
                }
            } catch (e: BadExitStatusException) {
                val message =
                    ("Non-zero return code '"
                            + e.getResult().terminationStatus.getExitCode()
                            + "' from command: "
                            + e.getMessage())
                env.getReporter().handle(Event.error(message))
                return createFailureResult(message, Code.NON_ZERO_EXIT)
            } catch (e: CommandException) {
                val message = "Error running program: " + e.getMessage()
                env.getReporter().handle(Event.error(message))
                return createFailureResult(message, Code.ERROR_RUNNING_PROGRAM)
            }
        }

        /** Returns an [ExecRequest] for running the mobile-install deployer in the client.  */
        private fun createExecRequest(
            env: CommandEnvironment, workingDir: Path, cmdLine: ImmutableList<String?>
        ): ExecRequest {
            return ExecRequest.newBuilder()
                .setShouldExec(true)
                .setWorkingDirectory(PathToReplaceUtils.bytes(workingDir.getPathString()))
                .addAllArgv(
                    cmdLine.stream().map<ByteString?>(Function { obj: String? -> PathToReplaceUtils.bytes() }).collect(
                        ImmutableList.toImmutableList<E?>()
                    )
                )
                .addAllPathToReplace(PathToReplaceUtils.getPathsToReplace(env)) // TODO: b/333695932 - Shim for client run-support, remove once no longer needed.
                .addEnvironmentVariable(
                    EnvironmentVariable.newBuilder()
                        .setName(PathToReplaceUtils.bytes("BUILD_WORKING_DIRECTORY"))
                        .setValue(PathToReplaceUtils.bytes(env.getWorkingDirectory().getPathString()))
                )
                .addEnvironmentVariable(
                    EnvironmentVariable.newBuilder()
                        .setName(PathToReplaceUtils.bytes("BUILD_WORKSPACE_DIRECTORY"))
                        .setValue(PathToReplaceUtils.bytes(env.getWorkspace().getPathString()))
                )
                .build()
        }

        private fun errorMessageIfNotSupported(
            target: ConfiguredTarget, mobileInstallSupportedRules: MutableList<String?>
        ): String? {
            // Dereference any aliases that might be present.
            var target: ConfiguredTarget = target
            target = target.getActual()

            if (target is AbstractConfiguredTarget) {
                val ruleType: String? = target.getRuleClassString()
                if (!mobileInstallSupportedRules.contains(ruleType)) {
                    return java.lang.String.format(
                        "mobile-install can only be run on %s targets. Got: %s",
                        mobileInstallSupportedRules, ruleType
                    )
                } else {
                    return null
                }
            }
            return "Invalid target"
        }

        private fun createFailureResult(message: String?, detailedCode: Code?): FailureDetail {
            return FailureDetail.newBuilder()
                .setMessage(message)
                .setMobileInstall(MobileInstall.newBuilder().setCode(detailedCode))
                .build()
        }
    }
}
